/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.net.Uri
import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedLimits
import org.thoughtcrime.securesms.feed.net.FeedPoll
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 发布页顶部的两种（`dynamic-feed.md` 第三节第 23 条）。 */
enum class FeedDraftKind(val apiName: String, @StringRes val label: Int) {
  POST("post", R.string.TellomiFeedCompose__kind_post),
  ARTICLE("article", R.string.TellomiFeedCompose__kind_article)
}

/**
 * 帖子可见范围五档（第三节第 39 条；契约 7.2 `visibility`）。
 *
 * 「部分可见」「不给谁看」要选人（[needsAudience]），选人复用快拍「选定的人」组件（[FeedAudiencePickerActivity]）；
 * 没选人不能发。
 */
enum class FeedVisibility(
  val apiName: String,
  @StringRes val label: Int,
  @StringRes val description: Int,
  val needsAudience: Boolean = false
) {
  PUBLIC("public", R.string.TellomiFeedCompose__visibility_public, R.string.TellomiFeedCompose__visibility_public_description),
  FRIENDS("friends", R.string.TellomiFeedCompose__visibility_friends, R.string.TellomiFeedCompose__visibility_friends_description),
  INCLUDE("include", R.string.TellomiFeedCompose__visibility_include, R.string.TellomiFeedCompose__visibility_include_description, needsAudience = true),
  EXCLUDE("exclude", R.string.TellomiFeedCompose__visibility_exclude, R.string.TellomiFeedCompose__visibility_exclude_description, needsAudience = true),
  ONLY_ME("only_me", R.string.TellomiFeedCompose__visibility_only_me, R.string.TellomiFeedCompose__visibility_only_me_description);

  companion object {
    /** 未成年人只能「仅好友 / 仅自己」（第 31 条；契约 7.2 客户端也不给选）。 */
    fun allowed(minorMode: Boolean): List<FeedVisibility> {
      return if (minorMode) listOf(FRIENDS, ONLY_ME) else entries
    }

    /** 默认值：成年人「公开」，未成年人「仅好友」。 */
    fun default(minorMode: Boolean): FeedVisibility {
      return if (minorMode) FRIENDS else PUBLIC
    }
  }
}

/** 正在写的一条。图片是本机的 content Uri，发出时再上传（契约 7.1）。 */
data class FeedDraft(
  val kind: FeedDraftKind = FeedDraftKind.POST,
  val title: String = "",
  val text: String = "",
  val images: List<Uri> = emptyList(),
  val visibility: FeedVisibility = FeedVisibility.PUBLIC,
  /** 「部分可见 / 不给谁看」选的人；别的档位不发出去，切回来还在。 */
  val audience: List<FeedAudienceMember> = emptyList(),
  /** 投票（第三节第 22 条），只有动态能带；切到长文时留着、切回来还在。 */
  val poll: FeedDraftPoll? = null,
  /** 回复选项（第三节第 40 条），动态和长文都有。 */
  val replies: FeedReplySettings = FeedReplySettings()
) {
  val isEmpty: Boolean
    get() = title.isBlank() && text.isBlank() && images.isEmpty() && poll == null

  /** 真正发出去的投票：长文不带。 */
  val attachedPoll: FeedDraftPoll?
    get() = if (kind == FeedDraftKind.POST) poll else null

  /** 真正发出去的图：长文 P1 不带图（第 25 条只有标题和排版），切到长文时已选的图留着、切回来还在。 */
  val attachedImages: List<Uri>
    get() = if (kind == FeedDraftKind.POST) images else emptyList()

  /** 正文字数上限：动态 500，长文 1 万（第 22、25 条；数值以服务端 `config.limits` 为准）。 */
  fun textLimit(limits: FeedLimits): Int {
    return when (kind) {
      FeedDraftKind.POST -> limits.postTextChars
      FeedDraftKind.ARTICLE -> limits.articleChars
    }
  }

  /** 按 Unicode 码点数（一个汉字算 1）。契约没写服务端怎么数，已请内容引擎按同样口径校验。 */
  fun textLength(): Int = text.codePointCount(0, text.length)

  /** 发不出去的原因；能发返回 null。 */
  fun problem(limits: FeedLimits, minorMode: Boolean): Problem? {
    return when {
      kind == FeedDraftKind.ARTICLE && title.isBlank() -> Problem.MISSING_TITLE
      text.isBlank() && attachedImages.isEmpty() && attachedPoll == null -> Problem.EMPTY
      textLength() > textLimit(limits) -> Problem.TOO_LONG
      attachedImages.size > limits.postImages -> Problem.TOO_MANY_IMAGES
      attachedPoll?.isValid == false -> Problem.INVALID_POLL
      visibility !in FeedVisibility.allowed(minorMode) -> Problem.VISIBILITY_NOT_ALLOWED
      visibility.needsAudience && audience.isEmpty() -> Problem.MISSING_AUDIENCE
      visibility.needsAudience && audience.size > MAX_AUDIENCE -> Problem.AUDIENCE_TOO_LARGE
      else -> null
    }
  }

  companion object {
    /** 名单最多 500 人（Signal-Server `MAX_AUDIENCE_MEMBERS`、引擎 `MaxAudienceMembers`）。 */
    const val MAX_AUDIENCE = 500
  }

  enum class Problem {
    EMPTY,
    MISSING_TITLE,
    TOO_LONG,
    TOO_MANY_IMAGES,
    VISIBILITY_NOT_ALLOWED,
    MISSING_AUDIENCE,
    AUDIENCE_TOO_LARGE,

    /** 投票少于 2 个填了的选项，或某一项超过 40 字。 */
    INVALID_POLL,

    /** 编辑时和原来一样，没什么可保存的（只由 [FeedComposeState.problem] 给出）。 */
    UNCHANGED
  }
}

/** 名单里的一个人：本机的 recipient id（再打开选人页时预先勾上）和 ACI（找 Signal-Server 签名单用）。 */
data class FeedAudienceMember(val recipientId: Long, val aci: String)

/** 截止时间三档；默认一天（Threads 的投票也是默认 24 小时）。 */
enum class FeedPollDuration(val hours: Long, @StringRes val label: Int) {
  ONE_DAY(24, R.string.TellomiFeedCompose__poll_one_day),
  THREE_DAYS(72, R.string.TellomiFeedCompose__poll_three_days),
  SEVEN_DAYS(168, R.string.TellomiFeedCompose__poll_seven_days)
}

/**
 * 写到一半的投票。问题就是正文（引擎没有单独的问题字段）。
 *
 * 选项 2–10 个、每个最多 40 字（引擎 `postRequest.validate`）；空着的选项发的时候去掉，填了的不到 2 个不能发。
 */
data class FeedDraftPoll(
  val options: List<String> = List(MIN_OPTIONS) { "" },
  val multi: Boolean = false,
  val duration: FeedPollDuration = FeedPollDuration.ONE_DAY
) {
  val filledOptions: List<String>
    get() = options.map { it.trim() }.filter { it.isNotEmpty() }

  val isValid: Boolean
    get() = filledOptions.size in MIN_OPTIONS..MAX_OPTIONS && filledOptions.all { it.codePointCount(0, it.length) <= OPTION_MAX_CHARS }

  val canAddOption: Boolean
    get() = options.size < MAX_OPTIONS

  /** 截止时间在点发布的那一刻算。 */
  fun toApi(now: Instant): FeedPoll {
    return FeedPoll(options = filledOptions, closesAt = now.plus(duration.hours, ChronoUnit.HOURS).toString(), multi = multi)
  }

  companion object {
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10
    const val OPTION_MAX_CHARS = 40

    /** 超过 40 字的部分打不进去（按码点数，一个汉字算 1）。 */
    fun clip(option: String): String {
      return if (option.codePointCount(0, option.length) <= OPTION_MAX_CHARS) option else option.substring(0, option.offsetByCodePoints(0, OPTION_MAX_CHARS))
    }
  }
}

/**
 * 谁能回复。第 40 条写「选项未定」（第五节 Q-12）；先用引擎暂定的三档（`replyPolicies`，数据模型 §5.18），定了再改。
 */
enum class FeedReplyPolicy(val apiName: String, @StringRes val label: Int) {
  EVERYONE("everyone", R.string.TellomiFeedCompose__replies_everyone),
  FRIENDS("friends", R.string.TellomiFeedCompose__replies_friends),
  FOLLOWING("following", R.string.TellomiFeedCompose__replies_following)
}

/** 每条单独设的回复选项：谁能回复、关闭评论、审核回复（作者批准后才公开）。 */
data class FeedReplySettings(
  val policy: FeedReplyPolicy = FeedReplyPolicy.EVERYONE,
  val commentsClosed: Boolean = false,
  val moderation: Boolean = false
) {
  val isDefault: Boolean
    get() = this == FeedReplySettings()
}
