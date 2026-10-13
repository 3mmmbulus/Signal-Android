/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 动态客户端接口契约（`docs/adr/0071/client-api-2026-10.md`）里的 JSON 结构。字段名与契约逐字相同（snake_case）。
 *
 * 契约第一节「未知字段」：客户端必须忽略不认识的字段，所以解析统一用 [FeedJson]（ignoreUnknownKeys）。
 * 每个可选字段都给默认值，服务端少给一个字段不会让整页解析失败。
 */

/** 契约第三节 `GET /v1/feed/config`。 */
@Serializable
data class FeedConfig(
  val enabled: Boolean = false,
  val region: String? = null,
  val tabs: List<String> = emptyList(),
  @SerialName("recommend_mode") val recommendMode: String = RECOMMEND_MODE_EDITORIAL,
  @SerialName("personalization_toggle_visible") val personalizationToggleVisible: Boolean = false,
  @SerialName("hot_enabled") val hotEnabled: Boolean = false,
  @SerialName("ip_region_visible") val ipRegionVisible: Boolean = false,
  @SerialName("media_base_url") val mediaBaseUrl: String? = null,
  @SerialName("card_ttl_seconds") val cardTtlSeconds: Long = 600,
  val limits: FeedLimits = FeedLimits(),
  @SerialName("minor_mode") val minorMode: Boolean = false
) {
  companion object {
    /** 大陆算法备案之前：编辑精选 + 时间顺序（`dynamic-feed.md` R-1.5）。 */
    const val RECOMMEND_MODE_EDITORIAL = "editorial_chronological"
    const val RECOMMEND_MODE_RANKED = "ranked"
  }
}

@Serializable
data class FeedLimits(
  @SerialName("post_text_chars") val postTextChars: Int = 500,
  @SerialName("post_images") val postImages: Int = 10,
  @SerialName("article_chars") val articleChars: Int = 10_000,
  @SerialName("edit_window_seconds") val editWindowSeconds: Long = 900
)

/** 契约 4.1 `GET /v1/feed/timeline`。 */
@Serializable
data class FeedTimelinePage(
  val entries: List<FeedEntry> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("refreshed_at") val refreshedAt: String? = null
)

@Serializable
data class FeedEntry(
  val id: String,
  val reason: String? = null,
  val card: FeedCard
)

/** 契约 4.2 `POST /v1/feed/cards`。 */
@Serializable
data class FeedCardsRequest(val ids: List<String>)

@Serializable
data class FeedCardsResponse(val cards: List<FeedCardResult> = emptyList())

@Serializable
data class FeedCardResult(
  val id: String,
  val state: String,
  val card: FeedCard? = null
) {
  /** `unavailable` 不区分原因（契约 4.2）。不认识的 state 一律按不可用处理。 */
  val isAvailable: Boolean
    get() = state == STATE_OK && card != null

  companion object {
    const val STATE_OK = "ok"
    const val STATE_UNAVAILABLE = "unavailable"
  }
}

/** 契约第五节：卡片 = `pool_card.card` + 每个人不同的 `viewer` 和计数。 */
@Serializable
data class FeedCard(
  val id: String,
  val kind: String,
  @SerialName("display_mode") val displayMode: String = DISPLAY_TEXT,
  val version: Int = 1,
  @SerialName("published_at") val publishedAt: String? = null,
  val edited: Boolean = false,
  val author: FeedAuthor? = null,
  val title: String? = null,
  val text: String? = null,
  val summary: String? = null,
  val media: List<FeedMedia> = emptyList(),
  val topics: List<FeedTopic> = emptyList(),
  val poll: FeedPoll? = null,
  @SerialName("quote_of") val quoteOf: FeedCardResult? = null,
  @SerialName("source_url") val sourceUrl: String? = null,
  val attribution: String? = null,
  @SerialName("ip_region_label") val ipRegionLabel: String? = null,
  @SerialName("ai_generated") val aiGenerated: Boolean = false,
  val stats: FeedStats = FeedStats(),
  val viewer: FeedViewer = FeedViewer()
) {
  /** P1 客户端只渲染这几种（契约第五节 `kind`）；别的不显示，不报错。 */
  val isRenderable: Boolean
    get() = kind in RENDERABLE_KINDS

  companion object {
    const val KIND_POST = "post"
    const val KIND_ARTICLE = "article"
    const val KIND_COMMENT = "comment"

    const val DISPLAY_TEXT = "text"
    const val DISPLAY_TEXT_IMAGES = "text_images"
    const val DISPLAY_LINK = "link"
    const val DISPLAY_ARTICLE_CARD = "article_card"

    val RENDERABLE_KINDS = setOf(KIND_POST, KIND_ARTICLE, KIND_COMMENT)
  }
}

@Serializable
data class FeedAuthor(
  @SerialName("profile_id") val profileId: String,
  @SerialName("display_name") val displayName: String,
  val handle: String? = null,
  @SerialName("tellomi_number") val tellomiNumber: String? = null,
  @SerialName("avatar_key") val avatarKey: String? = null,
  val badge: String? = null,
  @SerialName("is_external") val isExternal: Boolean = false,
  val platform: String? = null
)

@Serializable
data class FeedMedia(
  val key: String,
  val w: Int = 0,
  val h: Int = 0,
  val alt: String = "",
  val type: String = "image"
)

@Serializable
data class FeedTopic(val slug: String, val label: String)

@Serializable
data class FeedPoll(
  val options: List<FeedPollOption> = emptyList(),
  @SerialName("closes_at") val closesAt: String? = null,
  val multi: Boolean = false,
  @SerialName("my_choice") val myChoice: List<Int> = emptyList()
)

@Serializable
data class FeedPollOption(val text: String, val votes: Int = 0)

@Serializable
data class FeedStats(
  val likes: Int = 0,
  val comments: Int = 0,
  val reposts: Int = 0,
  @SerialName("like_count_hidden") val likeCountHidden: Boolean = false
)

@Serializable
data class FeedViewer(
  val liked: Boolean = false,
  val saved: Boolean = false,
  @SerialName("is_author") val isAuthor: Boolean = false,
  @SerialName("can_comment") val canComment: Boolean = true
)

/** 契约 6.1 `POST /v1/feed/events`：只报日志类事件。 */
@Serializable
data class FeedEventsRequest(val events: List<FeedEventPayload>)

@Serializable
data class FeedEventPayload(
  @SerialName("content_id") val contentId: String,
  val type: String,
  val surface: String,
  val value: Double? = null,
  @SerialName("occurred_at") val occurredAt: String
)

/** 契约 6 不感兴趣。 */
@Serializable
data class FeedNotInterestedRequest(
  @SerialName("target_type") val targetType: String,
  @SerialName("target_id") val targetId: String,
  val reason: String? = null
)

/** 契约 1.1 错误体。 */
@Serializable
data class FeedErrorBody(val error: String? = null, val message: String? = null)
