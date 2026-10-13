/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedConfig
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
}

/** 内容引擎（契约 `docs/adr/0071/client-api-2026-10.md`）。 */
class RemoteFeedSource(private val api: FeedApi) : FeedSource {
  override suspend fun config(): FeedConfig = api.config()
  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage = api.timeline(tab.apiName, cursor)
  override suspend fun setLiked(contentId: String, liked: Boolean) = api.setReaction(contentId, FeedApi.REACTION_LIKE, liked)
  override suspend fun notInterested(contentId: String) = api.notInterested(contentId, reason = null)
  override suspend fun undoNotInterested(contentId: String) = api.undoNotInterested(contentId)
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
