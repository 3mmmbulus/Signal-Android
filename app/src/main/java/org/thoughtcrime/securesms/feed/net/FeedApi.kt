/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** 解析时忽略不认识的字段，缺的字段用默认值，服务端加字段不会让旧客户端解析失败。 */
val FeedJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  coerceInputValues = true
}

/**
 * feed token 的来源：Signal-Server 签的短期 JWT（内容引擎 `internal/engine/auth`）。token 只在内存里，不落盘。
 *
 * 正式实现从 Signal-Server `GET /v1/tellomi/feed/auth` 取（Signal-Server 这边还没写）。
 */
interface FeedTokenSource {
  /** 当前可用的 token；[forceRefresh] 为真时丢掉缓存重新取。 */
  suspend fun token(forceRefresh: Boolean = false): String
}

/** 内容引擎返回的错误。[code] 是错误体 `{"error": "…"}` 里的机器可读代码。 */
class FeedApiException(
  val status: Int,
  val code: String?,
  val retryAfterSeconds: Long? = null
) : IOException("feed api $status ${code.orEmpty()}") {
  /**
   * 引擎对每个接口都回 404 `feed_hidden`：不在内测名单，或这个区域的紧急开关关了。客户端隐藏入口，不显示空列表。
   */
  val isFeedDisabled: Boolean
    get() = status == 404 && code == "feed_hidden"
}

/**
 * 内容引擎的 HTTP 客户端，只做动态列表页和发帖要用的那几个接口。
 *
 * - 401（`credential_expired` 等）时换一次 token 再试，**只重试一次**；
 * - 不在这里重试别的错误，也不缓存：缓存是调用方的事。
 */
class FeedApi(
  private val baseUrl: HttpUrl,
  private val client: OkHttpClient,
  private val tokens: FeedTokenSource
) {

  /**
   * 客户端要显示什么（契约 3.2）：栏目、推荐模式、个性化开关、IP 属地、上限、未成年模式。
   * 地区差异全由引擎定，客户端不自己判断。和 [status] 一样，对任何有效凭证都是 200，`enabled = false` 时隐藏入口。
   */
  suspend fun config(): FeedConfig {
    return get(url("v1/feed/config"), FeedConfig.serializer())
  }

  /** 能不能看动态：不在内测名单或者紧急开关关了是 false。 */
  suspend fun status(): FeedStatus {
    return get(url("v1/feed/status"), FeedStatus.serializer())
  }

  /**
   * 一栏的一页 id：[tabApiName] 是 `recommend` 或 `following`。推荐不收 cursor（每次给新的一页）；
   * [personalized] 只对推荐有用，个性化开关关掉时传 false（第三节第 47 条）。
   */
  suspend fun feed(tabApiName: String, cursor: String?, personalized: Boolean = true): FeedIdsPage {
    require(tabApiName == TAB_RECOMMEND || tabApiName == TAB_FOLLOWING) { "unknown tab $tabApiName" }
    val url = url("v1/feed").newBuilder()
      .addPathSegment(tabApiName)
      .apply {
        if (cursor != null) addQueryParameter("cursor", cursor)
        if (tabApiName == TAB_RECOMMEND && !personalized) addQueryParameter("personalized", "false")
      }
      .build()
    return get(url, FeedIdsPage.serializer())
  }

  suspend fun cards(ids: List<String>): FeedCardsResponse {
    require(ids.size <= MAX_CARD_IDS) { "at most $MAX_CARD_IDS ids per call" }
    val body = FeedJson.encodeToString(FeedCardsRequest.serializer(), FeedCardsRequest(ids)).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/cards")).post(body), FeedCardsResponse.serializer())
  }

  /** 点赞 / 收藏：PUT 加、DELETE 去，幂等。 */
  suspend fun setReaction(contentId: String, reaction: String, on: Boolean) {
    require(reaction == REACTION_LIKE || reaction == REACTION_SAVE) { "unknown reaction $reaction" }
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment(reaction).build()
    val builder = Request.Builder().url(url)
    if (on) builder.put(EMPTY_BODY) else builder.delete()
    sendNoContent(builder)
  }

  suspend fun notInterested(contentId: String, reason: String?) {
    val body = FeedJson.encodeToString(
      FeedNotInterestedRequest.serializer(),
      FeedNotInterestedRequest(contentId = contentId, reason = reason)
    ).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/not-interested")).post(body))
  }

  suspend fun undoNotInterested(contentId: String) {
    val url = url("v1/feed/not-interested").newBuilder().addPathSegment(contentId).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  /** 日志类事件，一批最多 100 条；失败由调用方丢弃，不重试。 */
  suspend fun events(events: List<FeedEventPayload>) {
    require(events.size <= MAX_EVENTS) { "at most $MAX_EVENTS events per call" }
    val body = FeedJson.encodeToString(FeedEventsRequest.serializer(), FeedEventsRequest(events)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/events")).post(body))
  }

  /** 发帖。大陆区返回 `pending`（先审后发，R-2.2）；没建过主页返回 409 `profile_required`。 */
  /** 自己的帖子，带审核状态（契约 7.3）。[before] 是上一页的 `next_before`。 */
  suspend fun myPosts(before: String?): FeedMyPostsPage {
    val url = url("v1/feed/me/posts").newBuilder()
      .apply { if (before != null) addQueryParameter("before", before) }
      .build()
    return get(url, FeedMyPostsPage.serializer())
  }

  /** 发布后 15 分钟内改正文 / 标题；超时 409 `edit_window_closed`，未通过的 409 `not_editable`。 */
  suspend fun editPost(contentId: String, request: FeedEditPostRequest): FeedCreatePostResponse {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).build()
    val body = FeedJson.encodeToString(FeedEditPostRequest.serializer(), request).toRequestBody(JSON)
    return send(Request.Builder().url(url).patch(body), FeedCreatePostResponse.serializer())
  }

  /** 一条帖子下的评论（契约第五节）。 */
  suspend fun comments(postId: String, cursor: String?): FeedCommentsResponse {
    val url = url("v1/feed/posts").newBuilder()
      .addPathSegment(postId)
      .addPathSegment("comments")
      .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
      .build()
    return get(url, FeedCommentsResponse.serializer())
  }

  /**
   * 发评论。403 `comments_closed` / `reply_not_allowed`，409 `profile_required`（要先建公开主页），
   * 400 `invalid_parent`（被回复的评论不在了）。
   */
  suspend fun createComment(postId: String, request: FeedCreateCommentRequest): FeedCreateCommentResponse {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(postId).addPathSegment("comments").build()
    val body = FeedJson.encodeToString(FeedCreateCommentRequest.serializer(), request).toRequestBody(JSON)
    return send(Request.Builder().url(url).post(body), FeedCreateCommentResponse.serializer())
  }

  /** 删自己的帖子（204）。别人的帖子引擎回 404。 */
  suspend fun deletePost(contentId: String) {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  suspend fun createPost(request: FeedCreatePostRequest): FeedCreatePostResponse {
    val body = FeedJson.encodeToString(FeedCreatePostRequest.serializer(), request).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/posts")).post(body), FeedCreatePostResponse.serializer())
  }

  private fun url(path: String): HttpUrl {
    return baseUrl.newBuilder().addPathSegments(path).build()
  }

  private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>): T {
    return send(Request.Builder().url(url).get(), serializer)
  }

  private suspend fun <T> send(builder: Request.Builder, serializer: KSerializer<T>): T {
    return execute(builder) { response ->
      val text = response.body.string()
      FeedJson.decodeFromString(serializer, text)
    }
  }

  private suspend fun sendNoContent(builder: Request.Builder) {
    execute(builder) { }
  }

  private suspend fun <T> execute(builder: Request.Builder, read: (Response) -> T): T = withContext(Dispatchers.IO) {
    var response = call(builder, tokens.token(forceRefresh = false))
    if (response.code == 401) {
      response.close()
      response = call(builder, tokens.token(forceRefresh = true))
    }

    response.use {
      if (!it.isSuccessful) {
        throw errorOf(it)
      }
      read(it)
    }
  }

  private fun call(builder: Request.Builder, token: String): Response {
    val request = builder
      .header("Authorization", "Bearer $token")
      .header("Accept", "application/json")
      .build()
    return client.newCall(request).execute()
  }

  private fun errorOf(response: Response): FeedApiException {
    val body = try {
      FeedJson.decodeFromString(FeedErrorBody.serializer(), response.body.string())
    } catch (e: Exception) {
      null
    }
    val retryAfter = response.header("Retry-After")?.toLongOrNull()
    return FeedApiException(status = response.code, code = body?.error, retryAfterSeconds = retryAfter)
  }

  companion object {
    const val TAB_RECOMMEND = "recommend"
    const val TAB_FOLLOWING = "following"

    const val MAX_CARD_IDS = 100
    const val MAX_EVENTS = 100

    const val REACTION_LIKE = "like"
    const val REACTION_SAVE = "save"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
  }
}
