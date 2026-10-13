/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.net.Uri
import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedLimits

/** 发布页顶部的两种（`dynamic-feed.md` 第三节第 23 条）。 */
enum class FeedDraftKind(val apiName: String, @StringRes val label: Int) {
  POST("post", R.string.TellomiFeedCompose__kind_post),
  ARTICLE("article", R.string.TellomiFeedCompose__kind_article)
}

/**
 * 帖子可见范围五档（第三节第 39 条；契约 7.2 `visibility`）。
 *
 * 「部分可见」「不给谁看」要选人（[needsAudience]），选人复用快拍「选定的人」组件，在后面的 PR 接上；
 * 在那之前这两档显示但不能选，不让用户发出一条没有名单的帖子。
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
  val visibility: FeedVisibility = FeedVisibility.PUBLIC
) {
  val isEmpty: Boolean
    get() = title.isBlank() && text.isBlank() && images.isEmpty()

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
      text.isBlank() && attachedImages.isEmpty() -> Problem.EMPTY
      textLength() > textLimit(limits) -> Problem.TOO_LONG
      attachedImages.size > limits.postImages -> Problem.TOO_MANY_IMAGES
      visibility !in FeedVisibility.allowed(minorMode) -> Problem.VISIBILITY_NOT_ALLOWED
      visibility.needsAudience -> Problem.MISSING_AUDIENCE
      else -> null
    }
  }

  enum class Problem {
    EMPTY,
    MISSING_TITLE,
    TOO_LONG,
    TOO_MANY_IMAGES,
    VISIBILITY_NOT_ALLOWED,
    MISSING_AUDIENCE,

    /** 编辑时和原来一样，没什么可保存的（只由 [FeedComposeState.problem] 给出）。 */
    UNCHANGED
  }
}
