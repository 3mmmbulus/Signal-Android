/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.net.FeedViewer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 本机样例：内部用户在内容引擎接上之前看界面用。人名、内容全是虚构的，守 `internal-beta-content-generator.md` 4.1
 * （不涉政、不点名真人与品牌、不编造新闻）。只在内存里，点赞和不感兴趣重启 App 就没了。
 */
object SampleFeedSource : FeedSource {

  private const val PAGE_SIZE = 8

  private val liked = mutableSetOf<String>()
  private val hidden = mutableSetOf<String>()

  /** 自己在样例里发的，放在两栏最上面。 */
  private val mine = mutableListOf<FeedCard>()
  private val published = mutableMapOf<String, FeedCreatePostResponse>()

  private val me = FeedAuthor(profileId = "s-me", displayName = "我", tellomiNumber = "1000000001")

  private val authors = listOf(
    FeedAuthor(profileId = "s-1", displayName = "山风", handle = "shanfeng"),
    FeedAuthor(profileId = "s-2", displayName = "小满", handle = null, tellomiNumber = "3820145967"),
    FeedAuthor(profileId = "s-3", displayName = "阿禾在路上", handle = "ahe"),
    FeedAuthor(profileId = "s-4", displayName = "周末烘焙", handle = "weekend_bake"),
    FeedAuthor(profileId = "s-5", displayName = "夜跑的猫", handle = "nightcat")
  )

  private val texts = listOf(
    "今天的云很低，像是伸手就能摸到。下班路上停下来看了五分钟。",
    "第一次自己做酸面包，外皮裂得刚刚好，就是有点酸过头了。",
    "楼下新开了一家修自行车的小铺，老板说他修了三十年车。",
    "雨后的石板路，踩上去会响。",
    "夜跑第 100 天，配速没变快，但心情变好了。",
    "整理旧照片，翻到小时候在外婆家院子里的那棵枇杷树。",
    "周末去山里住了一晚，早上被鸟叫醒，比闹钟好听多了。",
    "学会了一个新词：「回甘」。喝完这杯茶才懂。",
    "阳台的薄荷又长疯了，谁要来摘一点？",
    "地铁上看完一本小说，最后一页没舍得翻。",
    "今天试着一整天不看手机，坚持到了下午三点。",
    "给猫换了新猫砂，它研究了半个小时才肯进去。"
  )

  private val topics = listOf(
    FeedTopic(slug = "daily", label = "日常"),
    FeedTopic(slug = "food", label = "美食"),
    FeedTopic(slug = "outdoors", label = "户外"),
    FeedTopic(slug = "reading", label = "读书")
  )

  private val ipLabels = listOf("四川", "重庆", "浙江", "广东", "云南")

  private val recommend: List<FeedCard> by lazy { (0 until 24).map { sampleCard(it) } }
  private val following: List<FeedCard> by lazy { recommend.filter { it.author?.profileId == "s-1" || it.author?.profileId == "s-3" } }

  override suspend fun config(): FeedConfig {
    return FeedConfig(
      enabled = true,
      region = "cn",
      tabs = FeedTab.entries.map { it.apiName },
      recommendMode = FeedConfig.RECOMMEND_MODE_EDITORIAL,
      ipRegionVisible = true
    )
  }

  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage {
    val all = synchronized(this) { mine.toList() } + when (tab) {
      FeedTab.RECOMMEND -> recommend
      FeedTab.FOLLOWING -> following
    }.filter { it.id !in hidden }

    val start = cursor?.toIntOrNull()?.coerceIn(0, all.size) ?: 0
    val end = (start + PAGE_SIZE).coerceAtMost(all.size)
    val page = all.subList(start, end).map { card ->
      val isLiked = card.id in liked
      val likes = card.stats.likes + if (isLiked) 1 else 0
      FeedEntry(id = card.id, card = card.copy(viewer = card.viewer.copy(liked = isLiked), stats = card.stats.copy(likes = likes)))
    }

    return FeedTimelinePage(
      entries = page,
      nextCursor = if (end < all.size) end.toString() else null,
      refreshedAt = Instant.now().toString()
    )
  }

  override suspend fun setLiked(contentId: String, liked: Boolean) {
    if (liked) this.liked += contentId else this.liked -= contentId
  }

  override suspend fun notInterested(contentId: String) {
    hidden += contentId
  }

  override suspend fun undoNotInterested(contentId: String) {
    hidden -= contentId
  }

  /** 同一个 [clientNonce] 重复发只算一次（契约 7.2 的去重）。 */
  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse = synchronized(this) {
    published[clientNonce]?.let { return it }

    val id = "mine-${mine.size}"
    val card = FeedCard(
      id = id,
      kind = draft.kind.apiName,
      displayMode = when {
        draft.kind == FeedDraftKind.ARTICLE -> FeedCard.DISPLAY_ARTICLE_CARD
        draft.attachedImages.isNotEmpty() -> FeedCard.DISPLAY_TEXT_IMAGES
        else -> FeedCard.DISPLAY_TEXT
      },
      publishedAt = Instant.now().toString(),
      author = me,
      title = draft.title.trim().takeIf { draft.kind == FeedDraftKind.ARTICLE },
      text = draft.text.trim(),
      media = draft.attachedImages.map { FeedMedia(key = it.toString()) },
      ipRegionLabel = ipLabels.first(),
      viewer = FeedViewer(isAuthor = true)
    )
    mine.add(0, card)

    FeedCreatePostResponse(id = id, state = FeedCreatePostResponse.STATE_PUBLISHED).also { published[clientNonce] = it }
  }

  private fun sampleCard(index: Int): FeedCard {
    val author = authors[index % authors.size]
    val imageCount = when (index % 4) {
      1 -> 1
      3 -> 3
      else -> 0
    }

    return FeedCard(
      id = "sample-$index",
      kind = FeedCard.KIND_POST,
      displayMode = if (imageCount > 0) FeedCard.DISPLAY_TEXT_IMAGES else FeedCard.DISPLAY_TEXT,
      publishedAt = Instant.now().minus((index * 37L + 5), ChronoUnit.MINUTES).toString(),
      edited = index == 5,
      author = author,
      text = texts[index % texts.size],
      media = (0 until imageCount).map { FeedMedia(key = "sample/$index/$it", w = 1080, h = if (it % 2 == 0) 1440 else 1080, alt = "") },
      topics = listOf(topics[index % topics.size]),
      ipRegionLabel = ipLabels[index % ipLabels.size],
      stats = FeedStats(likes = (index * 7) % 50, comments = (index * 3) % 12, reposts = index % 4),
      viewer = FeedViewer()
    )
  }
}
