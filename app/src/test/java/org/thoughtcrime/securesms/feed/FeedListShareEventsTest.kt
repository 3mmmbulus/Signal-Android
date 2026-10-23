/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import java.time.Instant

/** 列表卡片上直接分享也报 `share`（产品经理 2026-10-23）：口径同详情页，surface 是卡片所在的列表。 */
class FeedListShareEventsTest {

  private val at = Instant.ofEpochMilli(1_800_000_000_000L)

  private fun card(shareable: Boolean = true) = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "hi", shareable = shareable, author = FeedAuthor(profileId = "p1", displayName = "山风"))

  @Test
  fun `completed share on a list card reports share with the list surface`() {
    for (surface in FeedEventSurface.entries) {
      val event = FeedListShareEvents.event(card(), surface, FeedShareOutcome.COMPLETED, enabled = true, at = at)
      assertThat(event).isNotNull()
      assertThat(event!!.type).isEqualTo(FeedDetailEvents.TYPE_SHARE)
      assertThat(event.surface).isEqualTo(surface.apiName)
      assertThat(event.contentId).isEqualTo("c1")
      assertThat(event.value).isNull()
    }
  }

  @Test
  fun `a list share is the same payload as a detail share on the same surface`() {
    val list = FeedListShareEvents.event(card(), FeedEventSurface.HOT, FeedShareOutcome.COMPLETED, enabled = true, at = at)
    assertThat(list).isEqualTo(FeedDetailEvents.share("c1", FeedEventSurface.HOT, at))
  }

  @Test
  fun `cancelled share reports nothing`() {
    assertThat(FeedListShareEvents.event(card(), FeedEventSurface.RECOMMEND, FeedShareOutcome.CANCELLED, enabled = true, at = at)).isNull()
  }

  @Test
  fun `not shareable reports nothing`() {
    assertThat(FeedListShareEvents.event(card(shareable = false), FeedEventSurface.RECOMMEND, FeedShareOutcome.COMPLETED, enabled = true, at = at)).isNull()
  }

  @Test
  fun `switch off or engine without events reports nothing`() {
    assertThat(FeedListShareEvents.event(card(), FeedEventSurface.SEARCH, FeedShareOutcome.COMPLETED, enabled = false, at = at)).isNull()
    assertThat(feedDetailEventsEnabled(internalSwitch = false, entries = FeedEntries.ALL)).isEqualTo(false)
    assertThat(feedDetailEventsEnabled(internalSwitch = true, entries = FeedEntries.ALL.copy(events = false))).isEqualTo(false)
  }

  @Test
  fun `no surface reports nothing`() {
    assertThat(FeedListShareEvents.event(card(), null, FeedShareOutcome.COMPLETED, enabled = true, at = at)).isNull()
  }
}
