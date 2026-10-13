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

class FeedEventsTest {

  @Test
  fun `a card counts after one second and only once`() {
    val tracker = FeedImpressionTracker()

    assertThat(tracker.update(setOf("a"), nowMillis = 0)).isEmpty()
    assertThat(tracker.nextCheckAt()).isEqualTo(1_000L)
    assertThat(tracker.update(setOf("a"), nowMillis = 999)).isEmpty()
    assertThat(tracker.update(setOf("a"), nowMillis = 1_000)).containsExactly("a")
    assertThat(tracker.update(setOf("a"), nowMillis = 5_000)).isEmpty()
    assertThat(tracker.nextCheckAt()).isNull()
  }

  @Test
  fun `scrolling away restarts the clock`() {
    val tracker = FeedImpressionTracker()

    tracker.update(setOf("a"), nowMillis = 0)
    tracker.update(emptySet(), nowMillis = 600)
    assertThat(tracker.update(setOf("a"), nowMillis = 700)).isEmpty()
    assertThat(tracker.update(setOf("a"), nowMillis = 1_500)).isEmpty()
    assertThat(tracker.update(setOf("a"), nowMillis = 1_700)).containsExactly("a")
  }

  @Test
  fun `visible fraction`() {
    assertThat(visibleFraction(itemOffset = 0, itemSize = 100, viewportStart = 0, viewportEnd = 1000)).isEqualTo(1f)
    assertThat(visibleFraction(itemOffset = -50, itemSize = 100, viewportStart = 0, viewportEnd = 1000)).isEqualTo(0.5f)
    assertThat(visibleFraction(itemOffset = 960, itemSize = 100, viewportStart = 0, viewportEnd = 1000)).isEqualTo(0.4f)
    assertThat(visibleFraction(itemOffset = 2000, itemSize = 100, viewportStart = 0, viewportEnd = 1000)).isEqualTo(0f)
    assertThat(visibleFraction(itemOffset = 0, itemSize = 0, viewportStart = 0, viewportEnd = 1000)).isEqualTo(0f)
  }

  @Test
  fun `buffer says when a batch is full and drains in chunks of 100`() {
    val buffer = FeedEventBuffer(batchSize = 3)
    val event = impressionEvent("a", FeedTab.RECOMMEND, Instant.EPOCH)

    assertThat(buffer.add(event)).isFalse()
    assertThat(buffer.add(event)).isFalse()
    assertThat(buffer.add(event)).isTrue()
    assertThat(buffer.drain().map { it.size }).containsExactly(3)
    assertThat(buffer.drain()).isEmpty()

    val big = FeedEventBuffer(batchSize = 1000)
    repeat(250) { big.add(event) }
    assertThat(big.drain().map { it.size }).containsExactly(100, 100, 50)
  }

  @Test
  fun `impression event carries the tab as surface`() {
    val event = impressionEvent("a", FeedTab.FOLLOWING, Instant.parse("2026-10-13T08:00:00Z"))

    assertThat(event.type).isEqualTo("impression")
    assertThat(event.surface).isEqualTo("following")
    assertThat(event.occurredAt).isEqualTo("2026-10-13T08:00:00Z")
  }
}
