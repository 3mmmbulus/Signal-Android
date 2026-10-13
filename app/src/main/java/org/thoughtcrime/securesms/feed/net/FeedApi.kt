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

/** 契约第一节：解析时忽略不认识的字段，缺的字段用默认值。 */
val FeedJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  coerceInputValues = true
}

/**
 * feed token 的来源（契约第二节）。token 只在内存里，不落盘。
 *
 * 正式实现从 Signal-Server `GET /v1/tellomi/feed/auth` 取（服务端接口还没写，契约第十一节第 1 条）。
 */
interface FeedTokenSource {
  /** 当前可用的 token；[forceRefresh] 为真时丢掉缓存重新取。 */
  suspend fun token(forceRefresh: Boolean = false): String
}

/** 内容引擎返回的错误（契约 1.1）。[code] 是错误体里机器可读的 `error`。 */
class FeedApiException(
  val status: Int,
  val code: String?,
  val retryAfterSeconds: Long? = null
) : IOException("feed api $status ${code.orEmpty()}") {
  /** 403 `feed_disabled`：这个区域的动态被关了，或账号不在内测名单。客户端隐藏入口。 */
  val isFeedDisabled: Boolean
    get() = status == 403 && code == "feed_disabled"
}

/**
 * 内容引擎的 HTTP 客户端，只做契约里 P1 列表页要用的那几个接口。
 *
 * - 401 时换一次 token 再试，**只重试一次**（契约 1.1）；
 * - 不在这里重试别的错误，也不缓存：缓存是调用方的事。
 */
class FeedApi(
  private val baseUrl: HttpUrl,
  private val client: OkHttpClient,
  private val tokens: FeedTokenSource
) {

  suspend fun config(): FeedConfig {
    return get(url("v1/feed/config"), FeedConfig.serializer())
  }

  suspend fun timeline(tabApiName: String, cursor: String?, limit: Int = DEFAULT_PAGE_SIZE): FeedTimelinePage {
    val url = url("v1/feed/timeline").newBuilder()
      .addQueryParameter("tab", tabApiName)
      .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
      .addQueryParameter("limit", limit.coerceIn(1, MAX_PAGE_SIZE).toString())
      .build()
    return get(url, FeedTimelinePage.serializer())
  }

  suspend fun cards(ids: List<String>): FeedCardsResponse {
    require(ids.size <= MAX_CARD_IDS) { "at most $MAX_CARD_IDS ids per call" }
    val body = FeedJson.encodeToString(FeedCardsRequest.serializer(), FeedCardsRequest(ids)).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/cards")).post(body), FeedCardsResponse.serializer())
  }

  /** 契约第六节：点赞 / 收藏，PUT 加、DELETE 去，幂等。 */
  suspend fun setReaction(contentId: String, reaction: String, on: Boolean) {
    require(reaction == REACTION_LIKE || reaction == REACTION_SAVE) { "unknown reaction $reaction" }
    val url = url("v1/feed/content").newBuilder().addPathSegment(contentId).addPathSegment("reactions").addPathSegment(reaction).build()
    val builder = Request.Builder().url(url)
    if (on) builder.put(EMPTY_BODY) else builder.delete()
    sendNoContent(builder)
  }

  suspend fun notInterested(contentId: String, reason: String?) {
    val body = FeedJson.encodeToString(
      FeedNotInterestedRequest.serializer(),
      FeedNotInterestedRequest(targetType = "content", targetId = contentId, reason = reason)
    ).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/not-interested")).post(body))
  }

  suspend fun undoNotInterested(contentId: String) {
    val url = url("v1/feed/not-interested").newBuilder().addPathSegment("content").addPathSegment(contentId).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  /** 契约 6.1：一批最多 100 条；失败由调用方丢弃，不重试。 */
  suspend fun events(events: List<FeedEventPayload>) {
    require(events.size <= MAX_EVENTS) { "at most $MAX_EVENTS events per call" }
    val body = FeedJson.encodeToString(FeedEventsRequest.serializer(), FeedEventsRequest(events)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/events")).post(body))
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
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 50
    const val MAX_CARD_IDS = 100
    const val MAX_EVENTS = 100

    const val REACTION_LIKE = "like"
    const val REACTION_SAVE = "save"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
  }
}
