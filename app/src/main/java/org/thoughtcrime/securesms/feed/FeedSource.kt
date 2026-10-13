/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.network.NetworkResult
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.compose.FeedReplyPolicy
import org.thoughtcrime.securesms.feed.net.CachingFeedTokenSource
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAudienceToken
import org.thoughtcrime.securesms.feed.net.FeedAuthApi
import org.thoughtcrime.securesms.feed.net.FeedBlockEntry
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedClosePeopleResponse
import org.thoughtcrime.securesms.feed.net.FeedCommentsPage
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreateCommentRequest
import org.thoughtcrime.securesms.feed.net.FeedCreatePostRequest
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedEditPostRequest
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import org.thoughtcrime.securesms.feed.net.FeedFollowRequest
import org.thoughtcrime.securesms.feed.net.FeedImageUploader
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedItemRef
import org.thoughtcrime.securesms.feed.net.FeedMyPostsPage
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings
import org.thoughtcrime.securesms.feed.net.FeedNotificationsResponse
import org.thoughtcrime.securesms.feed.net.FeedPollState
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedProfilePostsPage
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate
import org.thoughtcrime.securesms.feed.net.FeedReportRequest
import org.thoughtcrime.securesms.feed.net.FeedSearchPage
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.region.TellomiRegionProfile
import org.thoughtcrime.securesms.region.TellomiRegions
import java.io.IOException
import java.time.Instant

/**
 * 动态页取数据的地方。界面只认这个接口，不管背后是内容引擎还是本机样例。
 */
interface FeedSource {
  suspend fun config(): FeedConfig

  /** 能不能看（契约 3.1 `status`）：比 [config] 轻，用来决定入口显不显示。 */
  suspend fun isVisible(): Boolean
  suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage
  suspend fun setLiked(contentId: String, liked: Boolean)
  suspend fun notInterested(contentId: String)
  suspend fun undoNotInterested(contentId: String)

  /** 发一条。[clientNonce] 每条草稿一个，重试时不变（引擎补上去重后会带上）。 */
  suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse

  /** 日志类事件（曝光等），一批最多 100 条。 */
  suspend fun reportEvents(events: List<FeedEventPayload>)

  /** 自己的帖子和审核状态（「我的动态」）。 */
  suspend fun myPosts(before: String?): FeedMyPostsPage

  suspend fun deletePost(contentId: String)

  suspend fun preferences(): FeedPreferences
  suspend fun savePreferences(update: FeedPreferencesUpdate): FeedPreferences
  suspend fun interests(): FeedInterests
  suspend fun deleteInterest(dimension: String, key: String)
  suspend fun resetInterests()

  /** 批准一条等着的回复；拒绝用 [deletePost] 删掉它。 */
  suspend fun approveComment(postId: String, commentId: String)

  /** 未通过的帖子申诉一次，进人工复核。 */
  suspend fun appeal(contentId: String, text: String)

  /** 传一张图（举报截图等），返回 media key。 */
  suspend fun uploadImage(uri: Uri): String

  suspend fun report(request: FeedReportRequest)

  /** 改正文 / 长文标题（发布后 15 分钟内）。 */
  suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse

  /** 一条帖子，打开详情时用来校验（契约：打开详情时再调一次 Card API）；不可见时是 null。 */
  suspend fun card(contentId: String): FeedCard?

  suspend fun comments(postId: String, cursor: String?): FeedCommentsPage

  /** 发评论，返回服务端给的 `state`（一般是 `pending`）。 */
  suspend fun postComment(postId: String, text: String, parentId: String?): String

  /** 自己的公开主页；还没建是 null（发帖、评论、关注前要先建，引擎回 409 `profile_required`）。 */
  suspend fun myProfile(): FeedMyProfile?

  suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile

  /** 一个公开主页；看不到（删了、互相屏蔽、未成年人对非好友）是 null。 */
  suspend fun profile(profileId: String): FeedProfilePage?

  suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsPage

  /** 关注 / 取消关注一个人，返回新的状态（`none` / `pending` / `approved`）。 */
  suspend fun setFollowing(profileId: String, following: Boolean): String

  /** 在动态里屏蔽 / 取消屏蔽一个人（第三节第 32 条「屏蔽作者」）。 */
  suspend fun setBlocked(profileId: String, blocked: Boolean)

  /** 等我同意的关注请求（第三节第 29 条：私密账号的关注要本人同意）。 */
  suspend fun followRequests(): List<FeedFollowRequest>

  /** 同意或拒绝一条关注请求。 */
  suspend fun answerFollowRequest(profileId: String, approve: Boolean)

  /** 我在动态里屏蔽的作者、来源、话题（第三节第 44 条，和聊天屏蔽分开）。 */
  suspend fun blocks(): List<FeedBlockEntry>

  /** 从屏蔽列表里取消一项。 */
  suspend fun unblock(entry: FeedBlockEntry)

  /** 一栏通知（`FeedApi.NOTIFICATIONS_*`），带三栏的未读数。 */
  suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse

  suspend fun markNotificationsRead(tab: String)

  suspend fun deleteNotification(id: String)

  /** 一条帖子不再通知（全部 / 只关赞）。 */
  suspend fun muteNotifications(contentId: String, likesOnly: Boolean)

  /** 搜人和帖子（第三节第 34 条），[scope] 是 `FeedApi.SEARCH_*`。 */
  suspend fun search(query: String, scope: String, cursor: String?): FeedSearchPage

  /** 把 Signal-Server 签的好友声明交给引擎（[org.thoughtcrime.securesms.feed.friends.FeedFriendSync]）；返回两边是否都报了。 */
  suspend fun reportFriend(assertion: String): Boolean

  /** 投票，返回新的票数和我的选择。 */
  suspend fun vote(contentId: String, options: List<Int>): FeedPollState

  suspend fun closePeople(): FeedClosePeopleResponse

  /** 返回 `pending` / `accepted`。 */
  suspend fun addClosePerson(profileId: String): String
  suspend fun removeClosePerson(profileId: String)
  suspend fun answerClosePerson(ownerProfileId: String, accept: Boolean)

  suspend fun notificationSettings(): FeedNotificationSettings

  /** 返回引擎存下的设置；没有公开主页时 409 `profile_required`。 */
  suspend fun saveNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings
}

/**
 * 内容引擎。列表只给 id，这里再用 Card API 换成卡片（一次一页，最多 [FeedApi.MAX_CARD_IDS] 个），
 * 不可用的卡片（删了、被屏蔽、没权限看，引擎不说是哪种）直接跳过。
 */
class RemoteFeedSource(
  private val api: FeedApi,
  /** 读出要上传的图片字节（重编码成 JPEG）；测试里换掉。 */
  private val readImage: (Uri) -> ByteArray = { FeedImageUploader.prepare(AppDependencies.application, it) },
  /** 「部分可见 / 不给谁看」的名单找 Signal-Server 签（`kind`、ACI 列表 → `audience_token`）。 */
  private val signAudience: suspend (kind: String, peerAcis: List<String>) -> String = { _, _ -> throw IllegalStateException("audience lists are not available") }
) : FeedSource {

  private val uploader = FeedImageUploader(api)

  /** 已经传好的图：同一条草稿重试时不再传一遍（引擎的 key 没用在帖子上之前一直能用，一天后清掉）。 */
  private val uploaded = mutableMapOf<Pair<String, Uri>, String>()

  override suspend fun isVisible(): Boolean = api.status().visible

  override suspend fun config(): FeedConfig {
    return api.config()
  }

  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage {
    return when (tab) {
      FeedTab.FOLLOWING -> {
        val page = api.feed(tab.apiName, cursor)
        FeedTimelinePage(entries = hydrate(page.items), nextCursor = page.cursor)
      }
      // 推荐没有 cursor：每次给新的一页（看过的引擎记了曝光），给空了才算到底
      FeedTab.RECOMMEND -> {
        val page = api.feed(tab.apiName, cursor = null)
        FeedTimelinePage(entries = hydrate(page.items), nextCursor = if (page.items.isEmpty()) null else RECOMMEND_MORE)
      }
    }
  }

  override suspend fun setLiked(contentId: String, liked: Boolean) = api.setReaction(contentId, FeedApi.REACTION_LIKE, liked)
  override suspend fun notInterested(contentId: String) = api.notInterested(contentId, reason = null)
  override suspend fun undoNotInterested(contentId: String) = api.undoNotInterested(contentId)
  override suspend fun myPosts(before: String?): FeedMyPostsPage = api.myPosts(before)

  override suspend fun deletePost(contentId: String) = api.deletePost(contentId)
  override suspend fun preferences(): FeedPreferences = api.preferences()
  override suspend fun savePreferences(update: FeedPreferencesUpdate): FeedPreferences = api.putPreferences(update)
  override suspend fun interests(): FeedInterests = api.interests()
  override suspend fun deleteInterest(dimension: String, key: String) = api.deleteInterest(dimension, key)
  override suspend fun resetInterests() = api.resetInterests()
  override suspend fun approveComment(postId: String, commentId: String) = api.approveComment(postId, commentId)

  override suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse = api.editPost(contentId, draft.toEditRequest())

  override suspend fun card(contentId: String): FeedCard? = api.cards(listOf(contentId)).cards.firstOrNull()?.toCard()

  override suspend fun comments(postId: String, cursor: String?): FeedCommentsPage {
    val response = api.comments(postId, cursor)
    return FeedCommentsPage(comments = response.comments.mapNotNull { it.toCard() }, cursor = response.cursor)
  }

  override suspend fun postComment(postId: String, text: String, parentId: String?): String {
    return api.createComment(postId, FeedCreateCommentRequest(text = text.trim(), parentId = parentId)).state
  }

  override suspend fun reportEvents(events: List<FeedEventPayload>) = api.events(events)

  override suspend fun myProfile(): FeedMyProfile? = api.myProfile()

  override suspend fun appeal(contentId: String, text: String) = api.appeal(contentId, text.trim())

  override suspend fun uploadImage(uri: Uri): String {
    val bytes = withContext(Dispatchers.IO) { readImage(uri) }
    return uploader.upload(bytes)
  }

  override suspend fun report(request: FeedReportRequest) = api.report(request)

  override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile = api.saveProfile(update)

  override suspend fun profile(profileId: String): FeedProfilePage? = api.profile(profileId)

  override suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsPage {
    val response = api.profilePosts(profileId, cursor)
    return FeedProfilePostsPage(posts = response.posts.mapNotNull { it.toCard() }, cursor = response.cursor)
  }

  override suspend fun setFollowing(profileId: String, following: Boolean): String = api.follow(FeedApi.TARGET_AUTHOR, profileId, following)

  override suspend fun setBlocked(profileId: String, blocked: Boolean) = api.block(FeedApi.TARGET_AUTHOR, profileId, blocked)

  override suspend fun followRequests(): List<FeedFollowRequest> = api.followRequests().requests

  override suspend fun answerFollowRequest(profileId: String, approve: Boolean) = api.answerFollowRequest(profileId, approve)

  override suspend fun blocks(): List<FeedBlockEntry> = api.blocks().blocks

  override suspend fun unblock(entry: FeedBlockEntry) = api.block(entry.type, entry.id, on = false)

  override suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse = api.notifications(tab, before)

  override suspend fun markNotificationsRead(tab: String) = api.markNotificationsRead(tab)

  override suspend fun deleteNotification(id: String) = api.deleteNotification(id)

  override suspend fun muteNotifications(contentId: String, likesOnly: Boolean) = api.muteNotifications(contentId, likesOnly)

  override suspend fun reportFriend(assertion: String): Boolean = api.reportFriend(assertion).confirmed
  override suspend fun vote(contentId: String, options: List<Int>): FeedPollState = api.vote(contentId, options)
  override suspend fun closePeople(): FeedClosePeopleResponse = api.closePeople()
  override suspend fun addClosePerson(profileId: String): String = api.addClosePerson(profileId).state
  override suspend fun removeClosePerson(profileId: String) = api.removeClosePerson(profileId)
  override suspend fun answerClosePerson(ownerProfileId: String, accept: Boolean) = api.answerClosePerson(ownerProfileId, accept)
  override suspend fun notificationSettings(): FeedNotificationSettings = api.notificationSettings()
  override suspend fun saveNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings = api.putNotificationSettings(settings)

  override suspend fun search(query: String, scope: String, cursor: String?): FeedSearchPage {
    val response = api.search(query, scope, cursor)
    return FeedSearchPage(users = response.users, posts = hydrate(response.items).map { it.card }, cursor = response.cursor)
  }

  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse {
    val keys = draft.attachedImages.map { uri -> uploadOnce(clientNonce, uri) }
    // 名单 10 分钟有效：每次发（包括重试）都现签一张，图片传完再签
    val audience = if (draft.visibility.needsAudience) {
      FeedAudienceToken(signAudience(draft.visibility.apiName, draft.audience.map { it.aci }.distinct()))
    } else {
      null
    }
    val response = api.createPost(draft.toRequest().copy(clientNonce = clientNonce, mediaKeys = keys.ifEmpty { null }, audience = audience))
    synchronized(uploaded) { uploaded.keys.removeAll { it.first == clientNonce } }
    return response
  }

  private suspend fun uploadOnce(clientNonce: String, uri: Uri): String {
    synchronized(uploaded) { uploaded[clientNonce to uri] }?.let { return it }
    val bytes = withContext(Dispatchers.IO) { readImage(uri) }
    val key = uploader.upload(bytes)
    synchronized(uploaded) { uploaded[clientNonce to uri] = key }
    return key
  }

  private companion object {
    /** 推荐「还有下一页」的标记，不发给服务端。 */
    const val RECOMMEND_MORE = "more"
  }

  private suspend fun hydrate(items: List<FeedItemRef>): List<FeedEntry> {
    if (items.isEmpty()) return emptyList()
    val reasons = items.associate { it.id to it.reason }
    return items.map { it.id }.distinct().chunked(FeedApi.MAX_CARD_IDS).flatMap { ids ->
      api.cards(ids).cards.mapNotNull { result ->
        result.toCard()?.let { FeedEntry(id = result.id, reason = reasons[result.id], card = it) }
      }
    }
  }
}

internal fun FeedDraft.toEditRequest(): FeedEditPostRequest {
  return FeedEditPostRequest(text = text.trim(), title = title.trim().takeIf { kind == FeedDraftKind.ARTICLE })
}

internal fun FeedDraft.toRequest(now: Instant = Instant.now()): FeedCreatePostRequest {
  return FeedCreatePostRequest(
    kind = kind.apiName,
    title = title.trim().takeIf { kind == FeedDraftKind.ARTICLE && it.isNotEmpty() },
    text = text.trim(),
    visibility = visibility.apiName,
    commentsClosed = replies.commentsClosed,
    // 「所有人」不发，用引擎默认
    replyPolicy = replies.policy.takeIf { it != FeedReplyPolicy.EVERYONE }?.apiName,
    replyModeration = replies.moderation,
    poll = attachedPoll?.toApi(now)
  )
}

/**
 * 没有可用的内容引擎时用的空来源：配置是「关」，信息流是空的。
 */
object EmptyFeedSource : FeedSource {
  override suspend fun config(): FeedConfig = FeedConfig(enabled = false)
  override suspend fun isVisible(): Boolean = false
  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage = FeedTimelinePage()
  override suspend fun setLiked(contentId: String, liked: Boolean) = Unit
  override suspend fun notInterested(contentId: String) = Unit
  override suspend fun undoNotInterested(contentId: String) = Unit
  override suspend fun reportEvents(events: List<FeedEventPayload>) = Unit
  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse = throw IllegalStateException("feed is not available")
  override suspend fun myPosts(before: String?): FeedMyPostsPage = FeedMyPostsPage()
  override suspend fun deletePost(contentId: String) = Unit
  override suspend fun preferences(): FeedPreferences = throw IllegalStateException("feed is not available")
  override suspend fun savePreferences(update: FeedPreferencesUpdate): FeedPreferences = throw IllegalStateException("feed is not available")
  override suspend fun interests(): FeedInterests = throw IllegalStateException("feed is not available")
  override suspend fun deleteInterest(dimension: String, key: String) = throw IllegalStateException("feed is not available")
  override suspend fun resetInterests() = throw IllegalStateException("feed is not available")
  override suspend fun approveComment(postId: String, commentId: String) = throw IllegalStateException("feed is not available")
  override suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse = throw IllegalStateException("feed is not available")
  override suspend fun card(contentId: String): FeedCard? = null
  override suspend fun comments(postId: String, cursor: String?): FeedCommentsPage = FeedCommentsPage()
  override suspend fun postComment(postId: String, text: String, parentId: String?): String = throw IllegalStateException("feed is not available")
  override suspend fun myProfile(): FeedMyProfile? = null
  override suspend fun appeal(contentId: String, text: String) = throw IllegalStateException("feed is not available")
  override suspend fun uploadImage(uri: Uri): String = throw IllegalStateException("feed is not available")
  override suspend fun report(request: FeedReportRequest) = throw IllegalStateException("feed is not available")
  override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile = throw IllegalStateException("feed is not available")
  override suspend fun profile(profileId: String): FeedProfilePage? = null
  override suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsPage = FeedProfilePostsPage()
  override suspend fun setFollowing(profileId: String, following: Boolean): String = throw IllegalStateException("feed is not available")
  override suspend fun setBlocked(profileId: String, blocked: Boolean) = throw IllegalStateException("feed is not available")
  override suspend fun followRequests(): List<FeedFollowRequest> = emptyList()
  override suspend fun answerFollowRequest(profileId: String, approve: Boolean) = throw IllegalStateException("feed is not available")
  override suspend fun blocks(): List<FeedBlockEntry> = emptyList()
  override suspend fun unblock(entry: FeedBlockEntry) = throw IllegalStateException("feed is not available")
  override suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse = FeedNotificationsResponse()
  override suspend fun markNotificationsRead(tab: String) = Unit
  override suspend fun deleteNotification(id: String) = throw IllegalStateException("feed is not available")
  override suspend fun muteNotifications(contentId: String, likesOnly: Boolean) = throw IllegalStateException("feed is not available")
  override suspend fun search(query: String, scope: String, cursor: String?): FeedSearchPage = FeedSearchPage()
  override suspend fun reportFriend(assertion: String): Boolean = throw IllegalStateException("feed is not available")
  override suspend fun vote(contentId: String, options: List<Int>): FeedPollState = throw IllegalStateException("feed is not available")
  override suspend fun closePeople(): FeedClosePeopleResponse = throw IllegalStateException("feed is not available")
  override suspend fun addClosePerson(profileId: String): String = throw IllegalStateException("feed is not available")
  override suspend fun removeClosePerson(profileId: String) = throw IllegalStateException("feed is not available")
  override suspend fun answerClosePerson(ownerProfileId: String, accept: Boolean) = throw IllegalStateException("feed is not available")
  override suspend fun notificationSettings(): FeedNotificationSettings = throw IllegalStateException("feed is not available")
  override suspend fun saveNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings = throw IllegalStateException("feed is not available")
}

object FeedSources {

  private var remote: Pair<String, RemoteFeedSource>? = null

  /**
   * 内部用户强制打开动态标签时用本机样例（[SampleFeedSource]），看得到卡片、能点赞，不发任何网络请求；
   * 其余情况连当前区的内容引擎（RegionProfile 的 `feed`），凭证从 Signal-Server 取。
   *
   * 引擎或凭证接口不通时，[FeedViewModel] 读不到配置，页面停在空状态；入口本身由远程配置 `global.feed.enabled` 管。
   */
  @JvmStatic
  fun current(): FeedSource {
    return if (TellomiFeed.isSampleDataEnabled()) SampleFeedSource else engine()
  }

  /** 当前区的内容引擎（不看样例开关）。 */
  @JvmStatic
  fun engine(): FeedSource = remoteFor(TellomiRegions.current())

  /**
   * 每个区一份：切区后 [TellomiRegions.current] 换了，下次取到的是新区的主机和新的凭证缓存（token 里带着区）。
   */
  @Synchronized
  private fun remoteFor(region: TellomiRegionProfile): FeedSource {
    val baseUrl = region.feed.toHttpUrlOrNull() ?: return EmptyFeedSource
    val key = "${region.id.id} ${region.feed}"
    remote?.takeIf { it.first == key }?.let { return it.second }

    val tokens = CachingFeedTokenSource.fromSignalServer(FeedAuthApi(AppDependencies.authWebSocket))
    val source = RemoteFeedSource(
      api = FeedApi(baseUrl, AppDependencies.okHttpClient, tokens),
      signAudience = { kind, acis -> signAudienceList(kind, acis) }
    )
    remote = key to source
    return source
  }

  private suspend fun signAudienceList(kind: String, peerAcis: List<String>): String {
    return withContext(Dispatchers.IO) {
      when (val result = FeedAuthApi(AppDependencies.authWebSocket).audienceList(kind, peerAcis)) {
        is NetworkResult.Success -> result.result.audienceToken
        is NetworkResult.StatusCodeError -> throw FeedApiException(result.code, code = "invalid_audience")
        is NetworkResult.NetworkError -> throw result.exception
        is NetworkResult.ApplicationError -> throw IOException("audience list failed", result.throwable)
      }
    }
  }
}
