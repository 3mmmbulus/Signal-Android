/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import java.time.Instant

class FeedDetailEventsTest {

  private val t0 = 1_800_000_000_000L

  @Test
  fun `open then dwell after 45 seconds`() {
    val tracker = FeedDetailEventTracker()

    assertThat(tracker.opened("a", FeedEventSurface.RECOMMEND, t0).map { it.type }).containsExactly("open")
    assertThat(tracker.isOpen).isTrue()
    val events = tracker.closed(t0 + 45_000)

    assertThat(events.map { it.type }).containsExactly("dwell")
    assertThat(events.single().value).isEqualTo(45.0)
    assertThat(events.single().surface).isEqualTo("recommend")
    assertThat(events.single().contentId).isEqualTo("a")
    assertThat(events.single().occurredAt).isEqualTo(Instant.ofEpochMilli(t0 + 45_000).toString())
    assertThat(tracker.isOpen).isFalse()
  }

  @Test
  fun `defaults follow the decision of 2026-10-16`() {
    assertThat(FeedDetailEventTracker.QUICK_EXIT_MILLIS).isEqualTo(3_000L)
    assertThat(FeedDetailEventTracker.MIN_DWELL_MILLIS).isEqualTo(30_000L)
    // quick_exit is on by default
    val tracker = FeedDetailEventTracker()
    tracker.opened("a", FeedEventSurface.FOLLOWING, t0)
    assertThat(tracker.closed(t0 + 1_000).map { it.type }).containsExactly("quick_exit")
  }

  @Test
  fun `quick exit within three seconds, three seconds inclusive`() {
    val tracker = FeedDetailEventTracker()

    tracker.opened("b", FeedEventSurface.FOLLOWING, t0)
    val fast = tracker.closed(t0 + 1_500)
    assertThat(fast.map { it.type }).containsExactly("quick_exit")
    assertThat(fast.single().value).isEqualTo(1.5)
    assertThat(fast.single().surface).isEqualTo("following")

    tracker.opened("c", FeedEventSurface.FOLLOWING, t0)
    assertThat(tracker.closed(t0 + 3_000).map { it.type }).containsExactly("quick_exit")
  }

  @Test
  fun `between three and thirty seconds nothing more than the open`() {
    val tracker = FeedDetailEventTracker()

    tracker.opened("d", FeedEventSurface.RECOMMEND, t0)
    assertThat(tracker.closed(t0 + 3_001)).isEmpty()

    tracker.opened("d", FeedEventSurface.RECOMMEND, t0)
    assertThat(tracker.closed(t0 + 29_999)).isEmpty()

    tracker.opened("d", FeedEventSurface.RECOMMEND, t0)
    assertThat(tracker.closed(t0 + 30_000).map { it.type }).containsExactly("dwell")
  }

  @Test
  fun `quick exit can be switched off and then reports nothing for a short visit`() {
    val tracker = FeedDetailEventTracker(reportsQuickExit = false)

    tracker.opened("e", FeedEventSurface.RECOMMEND, t0)
    assertThat(tracker.closed(t0 + 1_000)).isEmpty()
  }

  @Test
  fun `closing with nothing open reports nothing`() {
    val tracker = FeedDetailEventTracker()

    assertThat(tracker.closed(t0)).isEmpty()
    tracker.opened("a", FeedEventSurface.RECOMMEND, t0)
    tracker.closed(t0 + 40_000)
    assertThat(tracker.closed(t0 + 50_000)).isEmpty()
  }

  @Test
  fun `opening another closes the previous one first`() {
    val tracker = FeedDetailEventTracker()

    tracker.opened("a", FeedEventSurface.RECOMMEND, t0)
    val events = tracker.opened("b", FeedEventSurface.SEARCH, t0 + 31_000)

    assertThat(events.map { it.type }).containsExactly("dwell", "open")
    assertThat(events.map { it.contentId }).containsExactly("a", "b")
    assertThat(events.map { it.surface }).containsExactly("recommend", "search")
    assertThat(tracker.closed(t0 + 32_000).map { it.type }).containsExactly("quick_exit")
  }

  @Test
  fun `values are clamped to the range the engine accepts`() {
    val surface = FeedEventSurface.PROFILE
    val at = Instant.EPOCH

    assertThat(FeedDetailEvents.dwell("a", surface, 100_000.0, at).value).isEqualTo(86_400.0)
    assertThat(FeedDetailEvents.dwell("a", surface, -5.0, at).value).isEqualTo(0.0)
    assertThat(FeedDetailEvents.dwell("a", surface, Double.NaN, at).value).isEqualTo(0.0)
    assertThat(FeedDetailEvents.quickExit("a", surface, Double.POSITIVE_INFINITY, at).value).isEqualTo(0.0)
    assertThat(FeedDetailEvents.dwell("a", surface, 86_400.0, at).value).isEqualTo(86_400.0)

    val tracker = FeedDetailEventTracker()
    tracker.opened("a", surface, t0)
    assertThat(tracker.closed(t0 + 90_000_000L).single().value).isEqualTo(86_400.0)
  }

  @Test
  fun `a clock that went backwards counts as a zero second quick exit`() {
    val tracker = FeedDetailEventTracker()

    tracker.opened("a", FeedEventSurface.RECOMMEND, t0)
    val events = tracker.closed(t0 - 10_000)

    assertThat(events.map { it.type }).containsExactly("quick_exit")
    assertThat(events.single().value).isEqualTo(0.0)
  }

  @Test
  fun `open and share carry no value`() {
    val open = FeedDetailEvents.open("a", FeedEventSurface.HOT, Instant.EPOCH)
    val share = FeedDetailEvents.share("a", FeedEventSurface.HOT, Instant.EPOCH)

    assertThat(open.type).isEqualTo("open")
    assertThat(open.value).isNull()
    assertThat(open.surface).isEqualTo("hot")
    assertThat(share.type).isEqualTo("share")
    assertThat(share.value).isNull()
  }

  @Test
  fun `share is reported only when completed and the card is shareable`() {
    val at = Instant.parse("2026-10-16T08:00:00Z")

    val done = FeedShareOutcome.COMPLETED.event("a", FeedEventSurface.RECOMMEND, shareable = true, at = at)
    assertThat(done?.type).isEqualTo("share")
    assertThat(done?.occurredAt).isEqualTo("2026-10-16T08:00:00Z")
    assertThat(FeedShareOutcome.CANCELLED.event("a", FeedEventSurface.RECOMMEND, shareable = true, at = at)).isNull()
    assertThat(FeedShareOutcome.COMPLETED.event("a", FeedEventSurface.RECOMMEND, shareable = false, at = at)).isNull()
  }

  @Test
  fun `surface follows the tab, and the engine names are exactly these five`() {
    assertThat(FeedEventSurface.of(FeedTab.RECOMMEND)).isEqualTo(FeedEventSurface.RECOMMEND)
    assertThat(FeedEventSurface.of(FeedTab.FOLLOWING)).isEqualTo(FeedEventSurface.FOLLOWING)
    assertThat(FeedEventSurface.entries.map { it.apiName }).containsExactly("recommend", "following", "hot", "search", "profile")
  }

  @Test
  fun `events from the tracker go through the buffer in batches of at most 50`() {
    val buffer = FeedEventBuffer(batchSize = 1000)
    val tracker = FeedDetailEventTracker()
    var now = t0
    repeat(60) { i ->
      tracker.opened("p$i", FeedEventSurface.RECOMMEND, now).forEach(buffer::add)
      now += 31_000
      tracker.closed(now).forEach(buffer::add)
    }

    assertThat(buffer.size).isEqualTo(120)
    assertThat(buffer.drain().map { it.size }).containsExactly(50, 50, 20)
  }
}
