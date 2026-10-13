/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostRequest
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage

/**
 * 动态页取数据的地方。界面只认这个接口，不管背后是内容引擎还是本机样例。
 */
interface FeedSource {
  suspend fun config(): FeedConfig
  suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage
  suspend fun setLiked(contentId: String, liked: Boolean)
  suspend fun notInterested(contentId: String)
  suspend fun undoNotInterested(contentId: String)

  /** 发一条（契约 7.2）。[clientNonce] 每条草稿一个，重试时不变。 */
  suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse
}

/** 内容引擎（契约 `docs/adr/0071/client-api-2026-10.md`）。 */
class RemoteFeedSource(private val api: FeedApi) : FeedSource {
  override suspend fun config(): FeedConfig = api.config()
  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage = api.timeline(tab.apiName, cursor)
  override suspend fun setLiked(contentId: String, liked: Boolean) = api.setReaction(contentId, FeedApi.REACTION_LIKE, liked)
  override suspend fun notInterested(contentId: String) = api.notInterested(contentId, reason = null)
  override suspend fun undoNotInterested(contentId: String) = api.undoNotInterested(contentId)

  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse {
    // 图片要先走 tus 上传拿到 key（契约 7.1），上传在接内容引擎地址的 PR 里做；在那之前带图的帖子不发。
    check(draft.attachedImages.isEmpty()) { "media upload is not wired yet" }
    return api.createPost(draft.toRequest(mediaKeys = emptyList(), clientNonce = clientNonce))
  }
}

internal fun FeedDraft.toRequest(mediaKeys: List<String>, clientNonce: String): FeedCreatePostRequest {
  return FeedCreatePostRequest(
    kind = kind.apiName,
    title = title.trim().takeIf { kind == FeedDraftKind.ARTICLE && it.isNotEmpty() },
    text = text.trim(),
    mediaKeys = mediaKeys,
    // 话题和评论权限在后面的 PR 加（第三节第 22、40 条）
    topics = emptyList(),
    visibility = visibility.apiName,
    commentsClosed = false,
    replyModeration = false,
    clientNonce = clientNonce
  )
}

/**
 * 没有可用的内容引擎时用的空来源：配置是「关」，信息流是空的。
 *
 * 内容引擎的主机还没进 RegionProfile（ADR-0071 §十四「网络」），Signal-Server 也还没有 `/v1/tellomi/feed/auth`
 * （契约第十一节第 1 条），所以服务端下发了 `global.feed.enabled` 的账号目前也只会看到空页面。
 */
object EmptyFeedSource : FeedSource {
  override suspend fun config(): FeedConfig = FeedConfig(enabled = false)
  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage = FeedTimelinePage()
  override suspend fun setLiked(contentId: String, liked: Boolean) = Unit
  override suspend fun notInterested(contentId: String) = Unit
  override suspend fun undoNotInterested(contentId: String) = Unit
  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse = throw IllegalStateException("feed is not available")
}

object FeedSources {
  /**
   * 内部用户强制打开动态标签时用本机样例（[SampleFeedSource]），看得到卡片、能点赞；其余情况在内容引擎接上之前用 [EmptyFeedSource]。
   * 样例只在本机内存里，不发任何网络请求。
   */
  @JvmStatic
  fun current(): FeedSource {
    return if (TellomiFeed.isSampleDataEnabled()) SampleFeedSource else EmptyFeedSource
  }
}
