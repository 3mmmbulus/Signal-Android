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
 * 正式实现从 Signal-Server `GET /v1/tellomi/feed/auth` 取（tellomi/Signal-Server#13；服务端部署时才启用）。
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

  /** 自己的公开主页；没建过时引擎回 404 `profile_required`，这里给 null。 */
  suspend fun myProfile(): FeedMyProfile? {
    return try {
      get(url("v1/feed/me/profile"), FeedMyProfile.serializer())
    } catch (e: FeedApiException) {
      if (e.status == 404 && e.code == "profile_required") null else throw e
    }
  }

  /** 建或改公开主页。400 `invalid_field` / `display_name_required`。 */
  suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile {
    val body = FeedJson.encodeToString(FeedProfileUpdate.serializer(), update).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/me/profile")).put(body), FeedMyProfile.serializer())
  }

  /** 对未通过的帖子申诉一次（202）。409 `not_rejected` / `already_appealed`，400 `invalid_text`。 */
  /** 自己帖子的置顶、隐藏点赞数（第三节第 26 条）→ 204。置顶满 3 条 409 `too_many_pinned`。 */
  suspend fun updatePostSettings(contentId: String, update: FeedPostSettingsUpdate) {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment("settings").build()
    val body = FeedJson.encodeToString(FeedPostSettingsUpdate.serializer(), update).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url).patch(body))
  }

  /** 自己帖子当前的设置（置顶、隐藏点赞数、回复选项）：只有作者能看，别人 404。 */
  suspend fun postSettings(contentId: String): FeedPostSettings {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment("settings").build()
    return get(url, FeedPostSettings.serializer())
  }

  /** 「查看数据」：只有作者能看，别人 404。 */
  suspend fun postInsights(contentId: String): FeedPostInsights {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment("insights").build()
    return get(url, FeedPostInsights.serializer())
  }

  suspend fun appeal(contentId: String, text: String) {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment("appeal").build()
    val body = FeedJson.encodeToString(FeedAppealRequest.serializer(), FeedAppealRequest(text)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url).post(body))
  }

  /**
   * 图片上传（tus 1.0）第一步：报总长度，拿上传 id 和最终的 media key。
   * 413 `too_large`（超过 20 MB）、429 `too_many_uploads`（同时没传完的超过 20 个）。
   */
  suspend fun startUpload(length: Long): FeedUploadStart {
    val builder = Request.Builder().url(url(UPLOADS_PATH))
      .header("Tus-Resumable", TUS_VERSION)
      .header("Upload-Length", length.toString())
      .post(EMPTY_BODY)
    return send(builder, FeedUploadStart.serializer())
  }

  /** 从 [offset] 接着传一块，返回服务端的新偏移。偏移对不上是 409 `offset_mismatch`；最后一块不是图片是 415。 */
  suspend fun uploadChunk(id: String, offset: Long, chunk: ByteArray): Long {
    val url = url(UPLOADS_PATH).newBuilder().addPathSegment(id).build()
    val builder = Request.Builder().url(url)
      .header("Tus-Resumable", TUS_VERSION)
      .header("Upload-Offset", offset.toString())
      .patch(chunk.toRequestBody(TUS_CHUNK))
    return execute(builder) { it.header("Upload-Offset")?.toLongOrNull() ?: (offset + chunk.size) }
  }

  /** 断线后问服务端收到了多少。 */
  suspend fun uploadOffset(id: String): Long {
    val url = url(UPLOADS_PATH).newBuilder().addPathSegment(id).build()
    val builder = Request.Builder().url(url).header("Tus-Resumable", TUS_VERSION).head()
    return execute(builder) { it.header("Upload-Offset")?.toLongOrNull() ?: 0L }
  }

  /**
   * 举报（202）。同一个人对同一对象再报还是同一条。400 `invalid_reason` / `evidence_required` / `relationship_required`，
   * 看不到的内容 404。
   */
  suspend fun report(request: FeedReportRequest) {
    val body = FeedJson.encodeToString(FeedReportRequest.serializer(), request).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/reports")).post(body))
  }

  /** 一个公开主页。看不到（删了、互相屏蔽、未成年人对非好友）时引擎回 404 `not_found`，这里给 null。 */
  suspend fun profile(profileId: String): FeedProfilePage? {
    val url = url("v1/feed/profiles").newBuilder().addPathSegment(profileId).build()
    return try {
      get(url, FeedProfilePage.serializer())
    } catch (e: FeedApiException) {
      if (e.status == 404 && e.code == NOT_FOUND) null else throw e
    }
  }

  /** 主页上的帖子，新的在前，一页 [PROFILE_PAGE] 条。看不到这个主页时 404 `not_found`。 */
  suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsResponse {
    val url = url("v1/feed/profiles").newBuilder()
      .addPathSegment(profileId)
      .addPathSegment("posts")
      .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
      .build()
    return get(url, FeedProfilePostsResponse.serializer())
  }

  /**
   * 关注 / 取消关注（[type] 是 `author`、`topic` 或 `source`），返回新的状态：私密账号对非好友是 `pending`，
   * 取消后是 `none`。关注人之前要先有公开主页（409 `profile_required`）；看不到的人 404 `not_found`。
   */
  suspend fun follow(type: String, id: String, on: Boolean): String {
    val url = url("v1/feed/follow").newBuilder().addPathSegment(type).addPathSegment(id).build()
    val builder = Request.Builder().url(url)
    if (on) builder.put(EMPTY_BODY) else builder.delete()
    return send(builder, FeedFollowResponse.serializer()).state
  }

  /** 在动态里屏蔽 / 取消屏蔽（204）。屏蔽作者会把双方的关注一起删掉。不能屏蔽自己（400 `invalid_target`）。 */
  suspend fun block(type: String, id: String, on: Boolean) {
    val url = url("v1/feed/blocks").newBuilder().addPathSegment(type).addPathSegment(id).build()
    val builder = Request.Builder().url(url)
    if (on) builder.put(EMPTY_BODY) else builder.delete()
    sendNoContent(builder)
  }

  /** 等我同意的关注请求（私密账号才会有）。 */
  suspend fun followRequests(): FeedFollowRequestsResponse = get(url("v1/feed/me/follow-requests"), FeedFollowRequestsResponse.serializer())

  /** 同意或拒绝一条关注请求（204）。对方已经撤回时 404。 */
  suspend fun answerFollowRequest(profileId: String, approve: Boolean) {
    val url = url("v1/feed/me/follow-requests").newBuilder().addPathSegment(profileId).build()
    val body = FeedJson.encodeToString(FeedFollowRequestDecision.serializer(), FeedFollowRequestDecision(approve)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url).post(body))
  }

  /** 我在动态里屏蔽的作者、来源、话题。 */
  suspend fun blocks(): FeedBlocksResponse = get(url("v1/feed/me/blocks"), FeedBlocksResponse.serializer())

  suspend fun followedTopics(): FeedFollowedTopics = get(url("v1/feed/me/followed-topics"), FeedFollowedTopics.serializer())

  suspend fun hideLists(): FeedHideLists = get(url("v1/feed/me/hide-lists"), FeedHideLists.serializer())

  /** 加进或移出 [list]（[HIDE_MUTE] / [HIDE_FROM]）→ 204，可以重复调。没有主页 409 `profile_required`；自己、互相屏蔽的人 404。 */
  suspend fun setHideList(list: String, profileId: String, on: Boolean) {
    require(list == HIDE_MUTE || list == HIDE_FROM) { "unknown hide list $list" }
    val url = url("v1/feed/me/hide-lists").newBuilder().addPathSegment(list).addPathSegment(profileId).build()
    val builder = Request.Builder().url(url)
    if (on) builder.put(EMPTY_BODY) else builder.delete()
    sendNoContent(builder)
  }

  /** 一栏通知，新的在前；[before] 是上一页最后一条的 `created_at`。 */
  suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse {
    val url = url("v1/feed/me/notifications").newBuilder()
      .addQueryParameter("tab", tab)
      .apply { if (before != null) addQueryParameter("before", before) }
      .build()
    return get(url, FeedNotificationsResponse.serializer())
  }

  /** 整栏标为已读（204）。 */
  suspend fun markNotificationsRead(tab: String) {
    val body = FeedJson.encodeToString(FeedNotificationsReadRequest.serializer(), FeedNotificationsReadRequest(tab)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/me/notifications/read")).post(body))
  }

  /** 删一条通知（204）。 */
  suspend fun deleteNotification(id: String) {
    val url = url("v1/feed/me/notifications").newBuilder().addPathSegment(id).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  /** 这条帖子不再通知：全部，或者只关赞（204）。 */
  suspend fun muteNotifications(contentId: String, likesOnly: Boolean) {
    val url = url("v1/feed/me/notification-mutes").newBuilder().addPathSegment(contentId).build()
    val body = FeedJson.encodeToString(FeedNotificationMuteRequest.serializer(), FeedNotificationMuteRequest(likesOnly)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url).put(body))
  }

  /** 投票（每人一次，不能改）。过了截止 409 `poll_closed`，投过 409 `already_voted`。 */
  suspend fun vote(contentId: String, options: List<Int>): FeedPollState {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(contentId).addPathSegment("vote").build()
    val body = FeedJson.encodeToString(FeedVoteRequest.serializer(), FeedVoteRequest(options)).toRequestBody(JSON)
    return send(Request.Builder().url(url).put(body), FeedPollState.serializer())
  }

  /** 关心的人：我设的和设了我的。 */
  suspend fun closePeople(): FeedClosePeopleResponse {
    return get(url("v1/feed/me/close-people"), FeedClosePeopleResponse.serializer())
  }

  /** 设一个人，对方同意前是 `pending`。满 3 个 409 `close_people_full`；不能设的人 404。 */
  suspend fun addClosePerson(profileId: String): FeedClosePersonState {
    val url = url("v1/feed/me/close-people").newBuilder().addPathSegment(profileId).build()
    return send(Request.Builder().url(url).put(EMPTY_BODY), FeedClosePersonState.serializer())
  }

  suspend fun removeClosePerson(profileId: String) {
    val url = url("v1/feed/me/close-people").newBuilder().addPathSegment(profileId).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  /** 回应设了我的人：同意，或者拒绝（同意过的也能这样撤回）。 */
  suspend fun answerClosePerson(ownerProfileId: String, accept: Boolean) {
    val url = url("v1/feed/me/close-people/requests").newBuilder().addPathSegment(ownerProfileId).build()
    val body = FeedJson.encodeToString(FeedClosePersonAnswer.serializer(), FeedClosePersonAnswer(accept)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url).post(body))
  }

  /** 动态通知的来源和类别。没设置过给默认值（所有人、全开）。 */
  suspend fun preferences(): FeedPreferences {
    return get(url("v1/feed/me/preferences"), FeedPreferences.serializer())
  }

  suspend fun putPreferences(update: FeedPreferencesUpdate): FeedPreferences {
    val body = FeedJson.encodeToString(FeedPreferencesUpdate.serializer(), update).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/me/preferences")).put(body), FeedPreferences.serializer())
  }

  suspend fun keywords(): List<FeedServerKeyword> {
    return get(url("v1/feed/me/keywords"), FeedKeywordsResponse.serializer()).keywords
  }

  /** 加一个「少推荐」的词；已经有就改有效期。超过 100 个 409 `too_many_keywords`。 */
  suspend fun putKeyword(keyword: String, days: Int) {
    val body = FeedJson.encodeToString(FeedKeywordRequest.serializer(), FeedKeywordRequest(keyword, days)).toRequestBody(JSON)
    sendNoContent(Request.Builder().url(url("v1/feed/me/keywords")).put(body))
  }

  suspend fun deleteKeyword(keyword: String) {
    val url = url("v1/feed/me/keywords").newBuilder().addPathSegment(keyword).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  suspend fun interests(): FeedInterests {
    return get(url("v1/feed/me/interests"), FeedInterests.serializer())
  }

  /** 删掉一个兴趣标签：之后不会再长回来（引擎留一行置零的记录）。 */
  suspend fun deleteInterest(dimension: String, key: String) {
    val url = url("v1/feed/me/interests").newBuilder().addPathSegment(dimension).addPathSegment(key).build()
    sendNoContent(Request.Builder().url(url).delete())
  }

  /** 重置推荐：算出来的标签全清掉，删过的照样不回来。客户端二次确认（第 12 条）。 */
  suspend fun resetInterests() {
    sendNoContent(Request.Builder().url(url("v1/feed/me/interests/reset")).post(EMPTY_BODY))
  }

  suspend fun notificationSettings(): FeedNotificationSettings {
    return get(url("v1/feed/me/notification-settings"), FeedNotificationSettings.serializer())
  }

  /** 改通知设置；没有公开主页时 409 `profile_required`（只有有主页的人收得到通知）。 */
  suspend fun putNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings {
    val body = FeedJson.encodeToString(FeedNotificationSettings.serializer(), settings).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/me/notification-settings")).put(body), FeedNotificationSettings.serializer())
  }

  /**
   * 报自己这一半好友关系（或撤回整条边）：[assertion] 是 Signal-Server `friend-assertions` 签的 JWT，原样转交。
   * 回 `confirmed`：对方也报了才是 true。
   */
  suspend fun reportFriend(assertion: String): FeedFriendReportResponse {
    val body = FeedJson.encodeToString(FeedFriendReportRequest.serializer(), FeedFriendReportRequest(assertion)).toRequestBody(JSON)
    return send(Request.Builder().url(url("v1/feed/friends")).post(body), FeedFriendReportResponse.serializer())
  }

  /** 搜人和帖子（[scope] 是 `SEARCH_*`）。帖子一页 10 条，带 [cursor] 翻页。 */
  suspend fun search(query: String, scope: String, cursor: String?): FeedSearchResponse {
    val url = url("v1/feed/search").newBuilder()
      .addQueryParameter("q", query)
      .addQueryParameter("scope", scope)
      .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
      .build()
    return get(url, FeedSearchResponse.serializer())
  }

  /** 删自己的帖子（204）。别人的帖子引擎回 404。 */
  /** 帖子作者批准一条等着的回复（第三节第 40 条「审核回复」）。拒绝就是删掉它（[deletePost]）。 */
  suspend fun approveComment(postId: String, commentId: String) {
    val url = url("v1/feed/posts").newBuilder().addPathSegment(postId).addPathSegment("comments").addPathSegment(commentId).addPathSegment("approve").build()
    sendNoContent(Request.Builder().url(url).post(EMPTY_BODY))
  }

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

    const val NOTIFICATIONS_INTERACTIONS = "interactions"
    const val NOTIFICATIONS_FOLLOWS = "follows"
    const val NOTIFICATIONS_SYSTEM = "system"
    const val NOTIFICATIONS_PAGE = 30

    const val SEARCH_ALL = "all"
    const val SEARCH_USERS = "users"
    const val SEARCH_POSTS = "posts"
    const val SEARCH_MAX_QUERY = 50

    const val MAX_CARD_IDS = 100
    const val PROFILE_PAGE = 20

    const val TARGET_AUTHOR = "author"

    /** 「不看 TA 的动态」：TA 的帖子不进我的关注流和推荐流。 */
    const val HIDE_MUTE = "mute"

    /** 「不让 TA 看我的动态」：我的帖子对 TA 都不可见。 */
    const val HIDE_FROM = "hide_from"
    const val TARGET_SOURCE = "source"
    const val TARGET_TOPIC = "topic"
    const val NOT_FOUND = "not_found"
    const val MAX_EVENTS = 100

    const val REACTION_LIKE = "like"
    const val REACTION_SAVE = "save"

    private const val UPLOADS_PATH = "v1/feed/media/uploads"
    private const val TUS_VERSION = "1.0.0"
    private val TUS_CHUNK = "application/offset+octet-stream".toMediaType()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
  }
}
