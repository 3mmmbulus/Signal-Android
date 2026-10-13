/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /** 引擎的大陆区域（敏感内容「更多」不开放，R-1.6）。 */
    const val REGION_CN = "cn"
  }
}

@Serializable
data class FeedLimits(
  @SerialName("post_text_chars") val postTextChars: Int = 500,
  @SerialName("post_images") val postImages: Int = 10,
  @SerialName("article_chars") val articleChars: Int = 10_000,
  @SerialName("edit_window_seconds") val editWindowSeconds: Long = 900,
  @SerialName("voice_comment_max_seconds") val voiceCommentMaxSeconds: Int = 60
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
  val reason: String? = null,
  /** `reason == "repost"` 时：转发的人（profile id），第三节第 26 条。 */
  @SerialName("reposted_by") val repostedBy: String? = null
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
  val poll: FeedPollState? = null,
  /** 等帖子作者批准的回复（第 40 条），只有写的人和帖子作者看得到。 */
  val held: Boolean = false,
  /** 能不能给「复制链接 / 分享到其他 App」：公开动态、作者不是私密账号（第三节第 29 条）。没带就当不能。 */
  val shareable: Boolean = false,
  /** 作者置顶了（第三节第 26 条）；主页第一页排在最前。 */
  val pinned: Boolean = false,
  /** `card.quote_of` 那条，引擎直接带上（只一层）；老引擎不带，由来源另取。 */
  val quoted: FeedQuotedResult? = null
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
      viewer = base.viewer.copy(
        liked = me?.liked ?: base.viewer.liked,
        saved = me?.saved ?: base.viewer.saved,
        isAuthor = me?.author ?: base.viewer.isAuthor,
        reposted = me?.reposted ?: base.viewer.reposted
      ),
      pollState = poll,
      held = held,
      shareable = shareable,
      pinned = pinned,
      quoted = quoted?.toQuoted()
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
  /** 语音评论的服务端转写；转写还没接上时是空串，空的不显示。 */
  val transcript: String = "",
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
  @Transient val pollState: FeedPollState? = null,
  /** 外层 `held`：这条回复还在等帖子作者批准。 */
  @Transient val held: Boolean = false,
  /** 外层 `shareable`：给不给「复制链接 / 分享到其他 App」。 */
  @Transient val shareable: Boolean = false,
  /** 外层 `pinned`：作者置顶了。 */
  @Transient val pinned: Boolean = false,
  /** [quoteOf] 那条的卡片，由来源另取一次 Card API 填上；取不到时 [FeedQuotedCard.card] 是 null（第 41 条：显示「原动态已不可见」）。 */
  @Transient val quoted: FeedQuotedCard? = null,
  /** 关注流里因为有人转发才出现的（`reason == "repost"`）：卡片顶上写「谁 转发了」。 */
  @Transient val repostedBy: FeedRepostedBy? = null
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

/**
 * 被引用的那条：[card] 为 null 是看不到了；[deleted] 是作者删了（第 41 条「原动态已被作者删除」），
 * 别的原因（屏蔽、隐藏、不给你看）不分。
 */
data class FeedQuotedCard(val id: String, val card: FeedCard?, val deleted: Boolean = false)

/** 转发这条的人；名字取不到时只写「转发」。 */
data class FeedRepostedBy(val profileId: String, val displayName: String?)

/** Card API 外层的 `quoted`：`ok` 带卡片，`deleted` / `unavailable` 只有 id。 */
@Serializable
data class FeedQuotedResult(
  val id: String,
  val state: String,
  val card: FeedCard? = null,
  val stats: FeedStats? = null
) {
  fun toQuoted(): FeedQuotedCard {
    val base = card?.takeIf { state == FeedCardResult.STATE_OK }
    return FeedQuotedCard(id = id, card = base?.copy(id = id, stats = stats ?: base.stats), deleted = state == STATE_DELETED)
  }

  companion object {
    const val STATE_DELETED = "deleted"
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
  val type: String = "image",
  /** 语音评论（`type = "audio"`）的时长，服务端从文件头读。 */
  @SerialName("duration_ms") val durationMs: Long = 0
) {
  val isAudio: Boolean get() = type == TYPE_AUDIO

  companion object {
    const val TYPE_AUDIO = "audio"
  }
}

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
) {
  val hasVoted: Boolean
    get() = voted.isNotEmpty()
}

/** `PUT /v1/feed/posts/{id}/vote`：选项下标，单选只能一个；每人一次，投了不能改。 */
@Serializable
data class FeedVoteRequest(val options: List<Int>)

@Serializable
data class FeedStats(
  val likes: Int = 0,
  val comments: Int = 0,
  val reposts: Int = 0,
  /** 作者隐藏了点赞数：别人拿到的 [likes] 是 0，作者自己照常拿到数字（第三节第 26 条）。 */
  @SerialName("likes_hidden") val likeCountHidden: Boolean = false
)

/** Card API 外层的 `me`。[author] 只在是我发的时候出现。 */
@Serializable
data class FeedMine(val liked: Boolean = false, val saved: Boolean = false, val author: Boolean = false, val reposted: Boolean = false)

/** 界面用：我和这条的关系。[liked]、[saved]、[isAuthor] 来自 [FeedMine]，[canComment] 引擎还没给。 */
@Serializable
data class FeedViewer(
  val liked: Boolean = false,
  val saved: Boolean = false,
  @SerialName("is_author") val isAuthor: Boolean = false,
  @SerialName("can_comment") val canComment: Boolean = true,
  /** 我转发到动态了（第三节第 26 条）。 */
  val reposted: Boolean = false
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
 * `audience` 只在「部分可见 / 不给谁看」时带：Signal-Server 签的名单（客户端不接触别人的假名）。
 */
@Serializable
data class FeedCreatePostRequest(
  val kind: String,
  val title: String? = null,
  val text: String,
  val visibility: String,
  @SerialName("comments_closed") val commentsClosed: Boolean,
  /** `everyone` / `friends` / `following`（引擎暂定，Q-12）；null 用引擎默认（所有人）。 */
  @SerialName("reply_policy") val replyPolicy: String? = null,
  /** 审核回复：别人的回复等作者批准后才公开（第 40 条）。 */
  @SerialName("reply_moderation") val replyModeration: Boolean = false,
  /** 每条草稿一个 UUID；同一个再发，引擎回第一次那条，不会多出一条。 */
  @SerialName("client_nonce") val clientNonce: String? = null,
  /** 上传完成的图片 key，按显示顺序。 */
  @SerialName("media_keys") val mediaKeys: List<String>? = null,
  val audience: FeedAudienceToken? = null,
  /** 只有 `kind == "post"` 能带（引擎 `invalid_poll`）。 */
  val poll: FeedPoll? = null,
  /** 话题 slug，最多 3 个（引擎 `invalid_topics`）。 */
  val topics: List<String>? = null,
  /** 引用的那条（第三节第 26 条「引用」）。 */
  @SerialName("quote_of") val quoteOf: String? = null
)

/** 发帖时的 `audience`：`{"audience_token": "<Signal-Server 签的 JWT>"}`，`kind` 要和 `visibility` 一致。 */
@Serializable
data class FeedAudienceToken(@SerialName("audience_token") val audienceToken: String)

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
  val region: String? = null,
  /** 防骚扰模式到什么时候（第三节第 30 条）；没开是 null。 */
  @SerialName("harassment_guard_until") val guardUntil: String? = null
) {
  companion object {
    /** 防骚扰模式能选的天数；0 = 关（引擎 `harassmentGuardDays`）。 */
    val GUARD_DAYS = listOf(0, 3, 7, 30)

    /** 引擎的上限（`MaxNameChars`、`MaxBioChars`）。 */
    const val MAX_NAME_CHARS = 32
    const val MAX_BIO_CHARS = 200
  }
}

/** `PUT /v1/feed/me/profile`：第一次调用就是建主页（必须带名字）；没给的字段不变。 */
@Serializable
data class FeedProfileUpdate(
  @SerialName("display_name") val displayName: String? = null,
  val bio: String? = null,
  /** 私密账号（第三节第 29 条）；改回公开时引擎把待处理的关注请求都通过。 */
  @SerialName("is_private") val isPrivate: Boolean? = null,
  /** 防骚扰模式开几天，0 = 关（第三节第 30 条）。 */
  @SerialName("harassment_guard_days") val guardDays: Int? = null
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
  val blocked: Boolean = false,
  /** 我「不看 TA 的动态」。 */
  val muted: Boolean = false,
  /** 我「不让 TA 看我的动态」。 */
  @SerialName("hidden_from") val hiddenFrom: Boolean = false
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

/** 关注请求、屏蔽列表里的一个人。 */
@Serializable
data class FeedPersonSummary(
  val id: String,
  val handle: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null
)

/** `GET /v1/feed/me/hide-lists`：「不看 TA」和「不让 TA 看」两张名单，最近加的在前。 */
@Serializable
data class FeedHideLists(
  val mute: List<FeedPersonSummary> = emptyList(),
  @SerialName("hide_from") val hideFrom: List<FeedPersonSummary> = emptyList()
)

/** `GET /v1/feed/topics/{slug}/posts`：话题页，带这个话题的帖子按时间倒序；[following] 是我有没有关注它。 */
@Serializable
data class FeedTopicPostsResponse(
  val items: List<FeedItemRef> = emptyList(),
  val cursor: String? = null,
  val following: Boolean = false
)

/** 话题页的一页：卡片已经补好。 */
data class FeedTopicPage(
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null,
  val following: Boolean = false
)

/** `GET /v1/feed/me/followed-topics`：我关注的话题，最近关注的在前。 */
@Serializable
data class FeedFollowedTopics(val topics: List<FeedFollowedTopic> = emptyList())

@Serializable
data class FeedFollowedTopic(val slug: String, @SerialName("followed_at") val followedAt: String? = null)

/** `GET /v1/feed/me/follow-requests`：别人想关注我的私密账号。 */
@Serializable
data class FeedFollowRequestsResponse(
  val requests: List<FeedFollowRequest> = emptyList()
)

@Serializable
data class FeedFollowRequest(
  val profile: FeedPersonSummary,
  @SerialName("requested_at") val requestedAt: String? = null
)

/** `GET /v1/feed/me/blocks`：作者、来源、话题都在里面；只有作者带 [FeedBlockEntry.profile]。 */
@Serializable
data class FeedBlocksResponse(
  val blocks: List<FeedBlockEntry> = emptyList()
)

@Serializable
data class FeedBlockEntry(
  val type: String,
  val id: String,
  @SerialName("created_at") val createdAt: String? = null,
  val profile: FeedPersonSummary? = null
)

@Serializable
data class FeedFollowRequestDecision(
  val approve: Boolean
)

/**
 * `GET /v1/feed/me/notifications?tab=`：一页 30 条，新的在前。引擎只给 [FeedNotification.actorId]；
 * 带了 [FeedNotification.actor] 就直接用，没带客户端自己查主页。
 */
@Serializable
data class FeedNotificationsResponse(
  val notifications: List<FeedNotification> = emptyList(),
  val unread: FeedUnreadCounts = FeedUnreadCounts()
)

@Serializable
data class FeedUnreadCounts(
  val interactions: Int = 0,
  val follows: Int = 0,
  val system: Int = 0
)

@Serializable
data class FeedNotification(
  val id: String,
  val kind: String,
  @SerialName("actor_id") val actorId: String? = null,
  @SerialName("content_id") val contentId: String? = null,
  @SerialName("subject_id") val subjectId: String? = null,
  val detail: JsonElement? = null,
  @SerialName("created_at") val createdAt: String,
  val read: Boolean = false,
  val actor: FeedPersonSummary? = null
) {
  companion object {
    const val KIND_LIKE = "like"
    const val KIND_COMMENT = "comment"
    const val KIND_REPLY = "reply"
    const val KIND_FOLLOW = "follow"
    const val KIND_FOLLOW_REQUEST = "follow_request"
    const val KIND_SYSTEM = "system"

    const val SYSTEM_REJECTED = "rejected"
    const val SYSTEM_EDIT_REJECTED = "edit_rejected"
    const val SYSTEM_TAKEN_DOWN = "taken_down"
    const val SYSTEM_APPEAL_UPHELD = "appeal_upheld"
    const val SYSTEM_APPEAL_OVERTURNED = "appeal_overturned"
    const val SYSTEM_CLOSE_PERSON_REQUEST = "close_person_request"
  }
}

@Serializable
data class FeedNotificationsReadRequest(
  val tab: String
)

/** `PATCH /v1/feed/posts/{id}/settings`：只带要改的字段，null 不发。 */
@Serializable
data class FeedPostSettingsUpdate(
  val pinned: Boolean? = null,
  @SerialName("like_count_hidden") val likeCountHidden: Boolean? = null,
  @SerialName("reply_policy") val replyPolicy: String? = null,
  @SerialName("comments_closed") val commentsClosed: Boolean? = null,
  @SerialName("reply_moderation") val replyModeration: Boolean? = null,
  @SerialName("voice_comments") val voiceComments: Boolean? = null
)

/** `GET /v1/feed/posts/{id}/settings`：自己帖子当前的设置，只有作者拿得到。 */
@Serializable
data class FeedPostSettings(
  val pinned: Boolean = false,
  @SerialName("like_count_hidden") val likeCountHidden: Boolean = false,
  @SerialName("reply_policy") val replyPolicy: String = "everyone",
  @SerialName("comments_closed") val commentsClosed: Boolean = false,
  @SerialName("reply_moderation") val replyModeration: Boolean = false,
  @SerialName("voice_comments") val voiceComments: Boolean = true
)

/** `GET /v1/feed/posts/{id}/insights`：「查看数据」，只有作者拿得到。 */
@Serializable
data class FeedPostInsights(
  val opens: Int = 0,
  val likes: Int = 0,
  val comments: Int = 0,
  val reposts: Int = 0,
  val saves: Int = 0
)

@Serializable
data class FeedNotificationMuteRequest(
  @SerialName("likes_only") val likesOnly: Boolean
)

/** `GET /v1/feed/search`：人只在 `all`（5 个）/ `users`（20 个）里有，不翻页；帖子给 id，再走 /cards。 */
@Serializable
data class FeedSearchResponse(
  val users: List<FeedSearchUser> = emptyList(),
  val items: List<FeedItemRef> = emptyList(),
  val cursor: String? = null
)

@Serializable
data class FeedSearchUser(
  val id: String,
  val handle: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null,
  val badge: String? = null
) {
  fun asPerson(): FeedPersonSummary = FeedPersonSummary(id = id, handle = handle, displayName = displayName, avatarKey = avatarKey)
}

/** 界面用的一页搜索结果：帖子已经换成卡片。 */
data class FeedSearchPage(
  val users: List<FeedSearchUser> = emptyList(),
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null
)

/** `POST /v1/feed/friends`：Signal-Server 签的好友声明原样交给引擎（数据模型 2.4）。 */
@Serializable
data class FeedFriendReportRequest(val assertion: String)

/** 两边都报了才 `confirmed`；只报了自己这一半是 false。 */
@Serializable
data class FeedFriendReportResponse(val confirmed: Boolean = false)

/**
 * `GET` / `PUT /v1/feed/me/notification-settings`（第三节第 43 条）。系统通知不受这些设置影响。
 * PUT 是整个对象覆盖，引擎把缺的布尔值当 false，所以默认值也要写出去（[EncodeDefault]）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FeedNotificationSettings(
  @EncodeDefault @SerialName("from_whom") val fromWhom: String = FROM_EVERYONE,
  @EncodeDefault val likes: Boolean = true,
  /** 评论和回复。 */
  @EncodeDefault val comments: Boolean = true,
  /** 新关注者和关注请求。 */
  @EncodeDefault val follows: Boolean = true
) {
  companion object {
    const val FROM_EVERYONE = "everyone"
    const val FROM_FOLLOWING = "following"
    const val FROM_FRIENDS = "friends"
    const val FROM_NONE = "none"
    val FROM_VALUES = listOf(FROM_EVERYONE, FROM_FOLLOWING, FROM_FRIENDS, FROM_NONE)
  }
}

/** `GET /v1/feed/me/close-people`（第三节第 42 条）：我设的人，和设了我的人。 */
@Serializable
data class FeedClosePeopleResponse(
  val mine: List<FeedClosePersonEntry> = emptyList(),
  val requests: List<FeedClosePersonEntry> = emptyList()
)

@Serializable
data class FeedClosePersonEntry(
  val id: String,
  @SerialName("display_name") val displayName: String,
  @SerialName("avatar_key") val avatarKey: String? = null,
  val state: String = STATE_PENDING
) {
  val accepted: Boolean
    get() = state == STATE_ACCEPTED

  companion object {
    const val STATE_PENDING = "pending"
    const val STATE_ACCEPTED = "accepted"

    /** 最多设 3 个（含待同意）。 */
    const val MAX = 3
  }
}

@Serializable
data class FeedClosePersonState(val state: String = FeedClosePersonEntry.STATE_PENDING)

@Serializable
data class FeedClosePersonAnswer(val accept: Boolean)

/** `GET /v1/feed/me/preferences`：内容偏好（第三节第 12、47 条）。[pausedUntil] 非空 = 暂停中。 */
@Serializable
data class FeedPreferences(
  val personalized: Boolean = true,
  val style: String = "keep",
  val sensitive: String = "standard",
  val categories: Map<String, String> = emptyMap(),
  @SerialName("paused_until") val pausedUntil: String? = null,
  @SerialName("recent_topics") val recentTopics: List<FeedRecentTopic> = emptyList()
) {
  companion object {
    const val STYLE_KEEP = "keep"
    const val STYLE_EXPLORE = "explore"
    val STYLES = listOf(STYLE_KEEP, STYLE_EXPLORE)

    const val SENSITIVE_REDUCE = "reduce"
    const val SENSITIVE_STANDARD = "standard"
    const val SENSITIVE_MORE = "more"
    val SENSITIVE_VALUES = listOf(SENSITIVE_REDUCE, SENSITIVE_STANDARD, SENSITIVE_MORE)

    const val CATEGORY_REDUCE = "reduce"
    const val CATEGORY_DEFAULT = "default"
    const val CATEGORY_INCREASE = "increase"
    val CATEGORY_VALUES = listOf(CATEGORY_REDUCE, CATEGORY_DEFAULT, CATEGORY_INCREASE)

    /** 引擎只收 30（暂停）或 0（恢复）。 */
    const val PAUSE_DAYS = 30
  }
}

/** 最近 30 天看过的内容里，某个话题占多少（0–1）。 */
@Serializable
data class FeedRecentTopic(val key: String, val share: Double = 0.0)

/** `PUT /v1/feed/me/preferences`：只发要改的字段（null 不发）。 */
@Serializable
data class FeedPreferencesUpdate(
  val personalized: Boolean? = null,
  val style: String? = null,
  val sensitive: String? = null,
  val categories: Map<String, String>? = null,
  @SerialName("pause_days") val pauseDays: Int? = null
)

/**
 * `GET /v1/feed/me/interests`：推荐用的兴趣标签，按行为算出，只有自己看得到（第 28、47 条；《算法推荐管理规定》第 17 条）。
 * 每类最多 30 个，强的在前。
 */
@Serializable
data class FeedInterests(
  val topics: List<FeedInterest> = emptyList(),
  val authors: List<FeedInterest> = emptyList(),
  val sources: List<FeedInterest> = emptyList()
) {
  val isEmpty: Boolean
    get() = topics.isEmpty() && authors.isEmpty() && sources.isEmpty()

  fun without(dimension: String, key: String): FeedInterests {
    return when (dimension) {
      DIM_TOPIC -> copy(topics = topics.filterNot { it.key == key })
      DIM_AUTHOR -> copy(authors = authors.filterNot { it.key == key })
      DIM_SOURCE -> copy(sources = sources.filterNot { it.key == key })
      else -> this
    }
  }

  companion object {
    const val DIM_TOPIC = "topic"
    const val DIM_AUTHOR = "author"
    const val DIM_SOURCE = "source"
  }
}

/** 一个标签：话题是 slug（名字客户端自己起），作者、来源带 [label]。[score] 是同类里相对最强那个的比例。 */
@Serializable
data class FeedInterest(
  val key: String,
  val label: String? = null,
  val score: Double = 0.0
)

/** `GET /v1/feed/me/keywords`：「少推荐」的词（「完全屏蔽」的不离开手机）。 */
@Serializable
data class FeedKeywordsResponse(val keywords: List<FeedServerKeyword> = emptyList())

@Serializable
data class FeedServerKeyword(
  val keyword: String,
  /** null = 永久。 */
  @SerialName("expires_at") val expiresAt: String? = null
)

/** `PUT /v1/feed/me/keywords`：[days] 0 = 永久、7、30。0 也要发出去。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FeedKeywordRequest(
  val keyword: String,
  @EncodeDefault val days: Int = 0
) {
  companion object {
    const val MAX_CHARS = 20
    const val MAX_KEYWORDS = 100
    val DAYS = listOf(0, 30, 7)
  }
}
