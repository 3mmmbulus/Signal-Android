/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.Test
import org.thoughtcrime.securesms.feed.compose.FeedQuoteTarget
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard

/** 分享 / 引用的摘要在 60 / 120 个码点处切：emoji 不被切成两半，也不白白少一半额度。 */
class FeedTextCallSitesTest {

  private val grin = "\uD83D\uDE00"
  private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

  private fun card(text: String) = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = text, shareable = true, author = FeedAuthor(profileId = "p1", displayName = "山风"))

  @Test
  fun `the saved message snippet keeps 60 emoji whole`() {
    assertThat(card(grin.repeat(60)).savedMessageBody("L").substringBefore("\n")).isEqualTo("山风：" + grin.repeat(60))
    assertThat(card(grin.repeat(61)).savedMessageBody("L").substringBefore("\n")).isEqualTo("山风：" + grin.repeat(60) + "…")
  }

  @Test
  fun `the saved message snippet does not break a joined emoji at the cut`() {
    val text = "a".repeat(58) + family + "tail"
    // the family would start at code point 58 and end at 63; a cut at 60 must drop it whole
    assertThat(card(text).savedMessageBody("L").substringBefore("\n")).isEqualTo("山风：" + "a".repeat(58) + "…")
  }

  @Test
  fun `the quote snippet is cut at 120 code points`() {
    val target = FeedQuoteTarget.of(card(grin.repeat(121)))

    assertThat(target).isNotNull()
    assertThat(target!!.snippet).isEqualTo(grin.repeat(120) + "…")
    assertThat(FeedQuoteTarget.of(card(grin.repeat(120)))!!.snippet).isEqualTo(grin.repeat(120))
  }
}
