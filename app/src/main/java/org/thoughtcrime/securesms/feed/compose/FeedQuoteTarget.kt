/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.feed.net.FeedCard

/**
 * 「分享」里的「引用」（第三节第 26 条）：发布页里压在正文下面的那条。只带画小框要的作者名和开头一段，
 * 发出去时只交 [id]（引擎 `quote_of`）。
 */
@Parcelize
data class FeedQuoteTarget(
  val id: String,
  val authorName: String,
  val snippet: String
) : Parcelable {

  companion object {
    private const val SNIPPET_CHARS = 120

    /** 只有能分享的（公开、作者不是私密账号，第 29 条）才能引用；评论不能。 */
    fun of(card: FeedCard): FeedQuoteTarget? {
      if (!card.shareable || (card.kind != FeedCard.KIND_POST && card.kind != FeedCard.KIND_ARTICLE)) return null
      val snippet = (card.title?.takeIf { it.isNotBlank() } ?: card.text.orEmpty()).trim().let {
        if (it.length > SNIPPET_CHARS) it.take(SNIPPET_CHARS) + "…" else it
      }
      return FeedQuoteTarget(id = card.id, authorName = card.author?.displayName.orEmpty(), snippet = snippet)
    }
  }
}
