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
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedItemRef
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

  /** 发一条。[clientNonce] 每条草稿一个，重试时不变（引擎补上去重后会带上）。 */
  suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse
}

/**
 * 内容引擎。列表只给 id，这里再用 Card API 换成卡片（一次一页，最多 [FeedApi.MAX_CARD_IDS] 个），
 * 不可用的卡片（删了、被屏蔽、没权限看，引擎不说是哪种）直接跳过。
 */
class RemoteFeedSource(private val api: FeedApi) : FeedSource {

  /** 引擎还没有配置接口：能看就用客户端默认值（两栏、编辑精选、默认上限）。 */
  override suspend fun config(): FeedConfig {
    return FeedConfig(enabled = api.status().visible, tabs = FeedTab.entries.map { it.apiName })
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

  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse {
    // 引擎发帖接口还不收图片和 client_nonce（已请补）；在那之前带图的帖子不发
    check(draft.attachedImages.isEmpty()) { "media upload is not wired yet" }
    return api.createPost(draft.toRequest())
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

internal fun FeedDraft.toRequest(): FeedCreatePostRequest {
  return FeedCreatePostRequest(
    kind = kind.apiName,
    title = title.trim().takeIf { kind == FeedDraftKind.ARTICLE && it.isNotEmpty() },
    text = text.trim(),
    visibility = visibility.apiName,
    // 评论权限在后面的 PR 加（第三节第 40 条）
    commentsClosed = false
  )
}

/**
 * 没有可用的内容引擎时用的空来源：配置是「关」，信息流是空的。
 *
 * 内容引擎的主机还没进 RegionProfile（ADR-0071 §十四「网络」），Signal-Server 也还没有 `/v1/tellomi/feed/auth`，
 * 所以服务端下发了 `global.feed.enabled` 的账号目前也只会看到空页面。
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
