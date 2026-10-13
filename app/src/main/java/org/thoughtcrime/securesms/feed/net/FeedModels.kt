/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

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
  @SerialName("minor_mode") val minorMode: Boolean = false,
  /** 举报原因表，按组（第三节第 32 条）；文案客户端定。 */
  @SerialName("report_reasons") val reportReasons: Map<String, List<String>> = emptyMap()
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
  /** 开放内容的来源与许可（契约 4.3 是对象，字段还没定稿）；先原样留着，看图页「···」做的时候再解析。 */
  val attribution: JsonElement? = null,
  val rights: JsonElement? = null,
  @SerialName("ip_region_label") val ipRegionLabel: String? = null,
  @SerialName("ai_generated") val aiGenerated: Boolean = false,
  val stats: FeedStats = FeedStats(),
  val viewer: FeedViewer = FeedViewer(),
  /** 评论才有：所属的帖子。 */
  @SerialName("root_id") val rootId: String? = null,
  /** 回复别的评论时才有：被回复的那条评论（直接评论帖子时没有）。 */
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
  @SerialName("comments_closed") val commentsClosed: Boolean,
  /** 每条草稿一个 UUID；同一个再发，引擎回第一次那条，不会多出一条。 */
  @SerialName("client_nonce") val clientNonce: String? = null,
  /** 上传完成的图片 key，按显示顺序。 */
  @SerialName("media_keys") val mediaKeys: List<String>? = null
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
  @SerialName("rejection_reasons") val rejectionReasons: List<String> = emptyList(),
  /** 未通过且还没申诉过（每条一次，R-2.1）。 */
  val appealable: Boolean = false,
  /** 申诉进度：`received` 复核中、`upheld` 维持原决定、`overturned` 改判通过；没申诉过是 null。 */
  @SerialName("appeal_state") val appealState: String? = null
) {
  companion object {
    const val APPEAL_RECEIVED = "received"
    const val APPEAL_UPHELD = "upheld"
    const val APPEAL_OVERTURNED = "overturned"

    /** 申诉理由的上限（引擎 `MaxAppealChars`）。 */
    const val MAX_APPEAL_CHARS = 500

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

/** `GET /v1/feed/posts/{id}/comments`：审核通过、你看得到的评论，按时间正序，每页 50 条；没有 [cursor] 就到底了。 */
@Serializable
data class FeedCommentsResponse(
  val comments: List<FeedCardResult> = emptyList(),
  val cursor: String? = null
)

/** `POST /v1/feed/posts/{id}/comments`：[parentId] 是被回复的评论，直接评论帖子时不带。 */
@Serializable
data class FeedCreateCommentRequest(
  val text: String,
  @SerialName("parent_id") val parentId: String? = null
)

/** 评论也先审后发（R-2.2）：`state` 一般是 `pending`。 */
@Serializable
data class FeedCreateCommentResponse(
  val id: String,
  val state: String
)

/** 界面用：一页评论（已经换成卡片）。 */
data class FeedCommentsPage(
  val comments: List<FeedCard> = emptyList(),
  val cursor: String? = null
)

/** `GET` / `PUT /v1/feed/me/profile`：自己的公开主页（契约第八节）。名字、ID、简介、头像明文存服务端（第三节第 9 条）。 */
@Serializable
data class FeedMyProfile(
  val id: String,
  @SerialName("tellomi_number") val tellomiNumber: String? = null,
  val handle: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null,
  val bio: String? = null,
  @SerialName("shown_interests") val shownInterests: List<String> = emptyList(),
  @SerialName("is_private") val isPrivate: Boolean = false,
  @SerialName("is_minor") val isMinor: Boolean = false,
  val region: String? = null
) {
  companion object {
    /** 引擎的上限（`MaxNameChars`、`MaxBioChars`）。 */
    const val MAX_NAME_CHARS = 32
    const val MAX_BIO_CHARS = 200
  }
}

/** `PUT /v1/feed/me/profile`：第一次调用就是建主页（必须带名字）；没给的字段不变。 */
@Serializable
data class FeedProfileUpdate(
  @SerialName("display_name") val displayName: String? = null,
  val bio: String? = null
)

/** `POST /v1/feed/posts/{id}/appeal`。 */
@Serializable
data class FeedAppealRequest(val text: String)

/** `POST /v1/feed/media/uploads` 的回包：[key] 发帖时放进 `media_keys`。 */
@Serializable
data class FeedUploadStart(val id: String, val key: String)

/** `POST /v1/feed/reports`（第三节第 32 条）。[targetType] 是 `content` 或 `author`。 */
@Serializable
data class FeedReportRequest(
  @SerialName("target_type") val targetType: String,
  @SerialName("target_id") val targetId: String,
  val group: String,
  val reason: String,
  val detail: String? = null,
  @SerialName("evidence_keys") val evidenceKeys: List<String>? = null,
  val relationship: String? = null
) {
  companion object {
    const val TARGET_CONTENT = "content"
    const val TARGET_AUTHOR = "author"
  }
}

/**
 * `GET /v1/feed/profiles/{id}`：别人的（或自己的）公开主页（第三节第 8–10、29、33、42 条；引擎 `profilePage`）。
 * 屏蔽、删了、未成年人主页对非好友都是 404 `not_found`，不区分原因。
 */
@Serializable
data class FeedProfilePage(
  val id: String,
  val handle: String? = null,
  /** 只在没设 handle 时给（第三节第 8 条）。 */
  @SerialName("tellomi_number") val tellomiNumber: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null,
  val bio: String? = null,
  @SerialName("shown_interests") val shownInterests: List<String> = emptyList(),
  /** 不是个人账号时的类别（官方账号等）。 */
  val badge: String? = null,
  @SerialName("is_private") val isPrivate: Boolean = false,
  /** 只有大陆区看大陆区的人才给（第三节第 10 条）。 */
  @SerialName("ip_region_label") val ipRegionLabel: String? = null,
  val followers: Int = 0,
  val following: Int = 0,
  /** 对方在另一个内容区域发帖，帖子到不了这里（R-1.4）。 */
  @SerialName("region_unavailable") val regionUnavailable: Boolean = false,
  val me: FeedRelation = FeedRelation(),
  /** 「关心的人」，对方同意了的才有（第三节第 42 条）。 */
  @SerialName("close_people") val closePeople: List<FeedClosePerson> = emptyList()
) {
  /** 能不能看这个人的帖子：私密账号要是好友或者关注被批准（第三节第 29 条）。 */
  val canSeePosts: Boolean
    get() = me.self || !isPrivate || me.friend || me.follow == FeedRelation.FOLLOW_APPROVED

  /** 主页上的「作者」，给卡片头像这类只认 [FeedAuthor] 的地方用。 */
  fun asAuthor(): FeedAuthor = FeedAuthor(profileId = id, displayName = displayName, handle = handle, tellomiNumber = tellomiNumber, avatarKey = avatarKey, badge = badge)
}

/** 我和这个主页的关系。[follow] 是 `none` / `pending` / `approved`。 */
@Serializable
data class FeedRelation(
  val self: Boolean = false,
  val follow: String = FOLLOW_NONE,
  @SerialName("followed_by") val followedBy: Boolean = false,
  val friend: Boolean = false,
  /** 我屏蔽了对方。 */
  val blocked: Boolean = false
) {
  companion object {
    const val FOLLOW_NONE = "none"
    const val FOLLOW_PENDING = "pending"
    const val FOLLOW_APPROVED = "approved"
  }
}

@Serializable
data class FeedClosePerson(
  val id: String,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null
)

/** `GET /v1/feed/profiles/{id}/posts`：一页 20 条，满一页才有 [cursor]。 */
@Serializable
data class FeedProfilePostsResponse(
  val posts: List<FeedCardResult> = emptyList(),
  val cursor: String? = null
)

data class FeedProfilePostsPage(
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null
)

/** `PUT` / `DELETE /v1/feed/follow/{type}/{id}` 的回包：私密账号是 `pending`，取消后是 `none`。 */
@Serializable
data class FeedFollowResponse(val state: String = FeedRelation.FOLLOW_NONE)
