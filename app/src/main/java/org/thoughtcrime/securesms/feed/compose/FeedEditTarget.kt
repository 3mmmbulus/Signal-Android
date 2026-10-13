/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.parseInstantMillis

/**
 * 要编辑的那条（第三节第 23 条：发布后 15 分钟内可改，标「已编辑」，大陆区保存后重新审核）。
 * 只能改正文和长文标题（引擎 `PATCH /v1/feed/posts/{id}`），图片和可见范围不改。
 */
@Parcelize
data class FeedEditTarget(
  val id: String,
  val kind: String,
  val title: String?,
  val text: String,
  /** 发布时间（ISO-8601）；引擎按它算 15 分钟，客户端只用来决定入口显不显示。 */
  val publishedAt: String?
) : Parcelable {

  val draftKind: FeedDraftKind
    get() = FeedDraftKind.entries.firstOrNull { it.apiName == kind } ?: FeedDraftKind.POST

  /** 时间读不懂就给入口，最后由引擎判（409 `edit_window_closed`）。 */
  fun isWithinWindow(nowMillis: Long, windowSeconds: Long): Boolean {
    val published = publishedAt?.let { parseInstantMillis(it) } ?: return true
    return nowMillis - published <= windowSeconds * 1000
  }

  companion object {
    fun of(card: FeedCard): FeedEditTarget? {
      if (card.kind != FeedCard.KIND_POST && card.kind != FeedCard.KIND_ARTICLE) return null
      return FeedEditTarget(id = card.id, kind = card.kind, title = card.title, text = card.text.orEmpty(), publishedAt = card.publishedAt)
    }

    /** 未通过和已撤回的不能改（引擎 409 `not_editable`）。 */
    fun of(post: FeedMyPost): FeedEditTarget? {
      if (post.state == FeedMyPost.STATE_REJECTED || post.state == FeedMyPost.STATE_WITHDRAWN) return null
      if (post.kind != FeedCard.KIND_POST && post.kind != FeedCard.KIND_ARTICLE) return null
      return FeedEditTarget(id = post.id, kind = post.kind, title = post.title, text = post.text.orEmpty(), publishedAt = post.createdAt)
    }
  }
}
