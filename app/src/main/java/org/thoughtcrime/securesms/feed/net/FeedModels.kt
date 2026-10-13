/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * 内容引擎（`tellomi/content-engine` `internal/engine/api`）的 JSON 结构，字段名与引擎逐字相同（snake_case）。
 * 契约文档 `docs/adr/0071/client-api-2026-10.md` 按引擎实际的接口修订。
 *
 * 客户端必须忽略不认识的字段，所以解析统一用 [FeedJson]（ignoreUnknownKeys）。
 * 每个可选字段都给默认值，服务端少给一个字段不会让整页解析失败。
 */

/**
 * 动态的配置。引擎还没有 `GET /v1/feed/config`（已请内容引擎线程补），在那之前客户端用这里的默认值，
 * 只从 `GET /v1/feed/status` 拿「能不能看」。
 */
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

    /** 打分排序（ADR §10.4 / §10.5）；只有这时显示个性化开关（第三节第 47 条）。 */
    const val RECOMMEND_MODE_SCORED = "scored"
  }
}

@Serializable
data class FeedLimits(
  @SerialName("post_text_chars") val postTextChars: Int = 500,
  @SerialName("post_images") val postImages: Int = 10,
  @SerialName("article_chars") val articleChars: Int = 10_000,
  @SerialName("edit_window_seconds") val editWindowSeconds: Long = 900
)

/** `GET /v1/feed/status`：H-1 内测名单和紧急开关都过了才是 true。 */
@Serializable
data class FeedStatus(val visible: Boolean = false)

/**
 * `GET /v1/feed/recommend`、`GET /v1/feed/following[?cursor=]`：只给 id，卡片再走 [FeedCardsRequest]。
 * 推荐每次给新的一页、没有 cursor；关注按时间倒序，有 cursor 就还有下一页。
 */
@Serializable
data class FeedIdsPage(
  val items: List<FeedItemRef> = emptyList(),
  val cursor: String? = null
)

@Serializable
data class FeedItemRef(
  val id: String,
  val reason: String? = null
)

/** 界面用的一页：已经把 id 换成了卡片。不是线上的 JSON。 */
data class FeedTimelinePage(
  val entries: List<FeedEntry> = emptyList(),
  val nextCursor: String? = null
)

data class FeedEntry(
  val id: String,
  val reason: String? = null,
  val card: FeedCard
)

/** `POST /v1/feed/cards`，一次最多 100 个 id。 */
@Serializable
data class FeedCardsRequest(val ids: List<String>)

@Serializable
data class FeedCardsResponse(val cards: List<FeedCardResult> = emptyList())

/**
 * 一张卡片的结果。`card` 是审核时定下的展示字段（`pool_card.card`），每个人不同的计数和「我赞过没有」在外层。
 */
@Serializable
data class FeedCardResult(
  val id: String,
  val state: String,
  val version: Int = 1,
  val card: FeedCard? = null,
  val stats: FeedStats? = null,
  val me: FeedMine? = null,
  val poll: FeedPollState? = null
) {
  /** `unavailable` 不区分原因。不认识的 state 一律按不可用处理。 */
  val isAvailable: Boolean
    get() = state == STATE_OK && card != null

  /** 把外层的计数、我的状态合进卡片，界面只看 [FeedCard]。 */
  fun toCard(): FeedCard? {
    val base = card?.takeIf { isAvailable } ?: return null
    return base.copy(
      id = id,
      version = version,
      stats = stats ?: base.stats,
      viewer = base.viewer.copy(liked = me?.liked ?: base.viewer.liked, saved = me?.saved ?: base.viewer.saved),
      pollState = poll
    )
  }

  companion object {
    const val STATE_OK = "ok"
    const val STATE_UNAVAILABLE = "unavailable"
  }
}

/** 卡片展示字段（引擎 `moderation/worker.go` 写进 `pool_card.card` 的那些）。[stats]、[viewer] 由 [FeedCardResult.toCard] 填。 */
@Serializable
data class FeedCard(
  /** 引擎的 `card` 里没有 id（在 Card API 外层），由 [FeedCardResult.toCard] 填。 */
  val id: String = "",
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
  /** 被引用的帖子 id；要显示时再走一次 Card API。 */
  @SerialName("quote_of") val quoteOf: String? = null,
  @SerialName("source_url") val sourceUrl: String? = null,
  val attribution: String? = null,
  @SerialName("ip_region_label") val ipRegionLabel: String? = null,
  @SerialName("ai_generated") val aiGenerated: Boolean = false,
  val stats: FeedStats = FeedStats(),
  val viewer: FeedViewer = FeedViewer(),
  @SerialName("parent_id") val parentId: String? = null,
  @Transient val pollState: FeedPollState? = null
) {
  /** P1 客户端只渲染这几种 `kind`；别的（快拍、视频等）不显示，不报错。 */
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
  @SerialName("id") val profileId: String,
  @SerialName("display_name") val displayName: String,
  val handle: String? = null,
  /** 只在没设 handle 时给；界面显示「Tellomi 号：xxxx」（第三节第 8 条）。 */
  @SerialName("tellomi_number") val tellomiNumber: String? = null,
  @SerialName("avatar_key") val avatarKey: String? = null,
  /** 开放内容的作者（`native = false`）给的是外部头像地址。 */
  @SerialName("avatar_url") val avatarUrl: String? = null,
  val badge: String? = null,
  /** false = 开放内容的外部作者（`external_author`）。 */
  @SerialName("native") val isNative: Boolean = true,
  val provider: String? = null
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

/** 投票的题面（卡片里，审核时定下）。 */
@Serializable
data class FeedPoll(
  val options: List<String> = emptyList(),
  @SerialName("closes_at") val closesAt: String? = null,
  val multi: Boolean = false
)

/** 投票的票数和我的选择（Card API 外层，每个人不同）。 */
@Serializable
data class FeedPollState(
  val counts: List<Int> = emptyList(),
  val voted: List<Int> = emptyList()
)

@Serializable
data class FeedStats(
  val likes: Int = 0,
  val comments: Int = 0,
  val reposts: Int = 0,
  @SerialName("like_count_hidden") val likeCountHidden: Boolean = false
)

/** Card API 外层的 `me`。 */
@Serializable
data class FeedMine(val liked: Boolean = false, val saved: Boolean = false)

/** 界面用：我和这条的关系。[liked]、[saved] 来自 [FeedMine]，其余引擎还没给。 */
@Serializable
data class FeedViewer(
  val liked: Boolean = false,
  val saved: Boolean = false,
  @SerialName("is_author") val isAuthor: Boolean = false,
  @SerialName("can_comment") val canComment: Boolean = true
)

/** `POST /v1/feed/events`：只报日志类事件（曝光、打开、停留、快速退出、分享）。 */
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

/** `POST /v1/feed/not-interested`。原因最多 40 字。 */
@Serializable
data class FeedNotInterestedRequest(
  @SerialName("content_id") val contentId: String,
  val reason: String? = null
)

/** 错误体 `{"error": "…"}`。 */
@Serializable
data class FeedErrorBody(val error: String? = null, val message: String? = null)

/**
 * `POST /v1/feed/posts`。引擎按 `DisallowUnknownFields` 解析，多一个字段就 400，所以这里只放引擎认的字段；
 * `client_nonce`（重试去重）和 `media_keys`（图片）已请引擎补，补上之前带图的帖子发不了。
 * `audience`（部分可见 / 不给谁看的假名名单）等选人组件接上再加。
 */
@Serializable
data class FeedCreatePostRequest(
  val kind: String,
  val title: String? = null,
  val text: String,
  val visibility: String,
  @SerialName("comments_closed") val commentsClosed: Boolean
)

@Serializable
data class FeedCreatePostResponse(
  val id: String,
  val state: String,
  val version: Int = 1
) {
  companion object {
    /** 大陆先审后发（R-2.2）：作者看到「审核中」，别人看不到。 */
    const val STATE_PENDING = "pending"
    const val STATE_PUBLISHED = "published"
  }
}

/**
 * `GET /v1/feed/me/posts` 的一条（契约 7.3）：作者自己看的，带审核状态和未通过原因。
 * [rejectionReasons] 只有作者看得到（第三节第 24 条）；引擎现在给的是规则代码（`lexicon:…`、`uncertain`），界面按代码转成文案。
 */
@Serializable
data class FeedMyPost(
  val id: String,
  val kind: String = FeedCard.KIND_POST,
  val state: String,
  val version: Int = 1,
  val title: String? = null,
  val text: String? = null,
  val visibility: String? = null,
  @SerialName("created_at") val createdAt: String? = null,
  @SerialName("edited_at") val editedAt: String? = null,
  @SerialName("rejection_reasons") val rejectionReasons: List<String> = emptyList()
) {
  companion object {
    const val STATE_PENDING = FeedCreatePostResponse.STATE_PENDING
    const val STATE_PUBLISHED = FeedCreatePostResponse.STATE_PUBLISHED
    const val STATE_REJECTED = "rejected"
    const val STATE_WITHDRAWN = "withdrawn"
  }
}

/** 每页 30 条，新的在前；[nextBefore] 是下一页的 `?before=`，没有就是最后一页。 */
@Serializable
data class FeedMyPostsPage(
  val posts: List<FeedMyPost> = emptyList(),
  @SerialName("next_before") val nextBefore: String? = null
)

/** `PATCH /v1/feed/posts/{id}`：只带改了的字段，帖子不带标题。回包和发帖一样（`state` 是 `pending`）。 */
@Serializable
data class FeedEditPostRequest(
  val text: String,
  val title: String? = null
)
