/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedTabTest {

  @Test
  fun `server order is kept`() {
    assertThat(FeedTab.fromApiNames(listOf("following", "recommend"))).containsExactly(FeedTab.FOLLOWING, FeedTab.RECOMMEND)
  }

  @Test
  fun `unknown tabs are skipped, hot is not a tab`() {
    assertThat(FeedTab.fromApiNames(listOf("hot", "recommend", "friends"))).containsExactly(FeedTab.RECOMMEND)
  }

  @Test
  fun `duplicates collapse`() {
    assertThat(FeedTab.fromApiNames(listOf("recommend", "recommend"))).containsExactly(FeedTab.RECOMMEND)
  }

  @Test
  fun `nothing recognised falls back to both tabs`() {
    assertThat(FeedTab.fromApiNames(emptyList())).containsExactly(FeedTab.RECOMMEND, FeedTab.FOLLOWING)
    assertThat(FeedTab.fromApiNames(listOf("hot"))).containsExactly(FeedTab.RECOMMEND, FeedTab.FOLLOWING)
  }

  @Test
  fun `api names match the client api contract`() {
    assertThat(FeedTab.entries.map { it.apiName }).containsExactly("recommend", "following")
  }

  @Test
  fun `tab shows when the server enables it or an internal user forces it`() {
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = false, forcedByInternalUser = false)).isFalse()
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = true, forcedByInternalUser = false)).isTrue()
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = false, forcedByInternalUser = true)).isTrue()
  }
}
