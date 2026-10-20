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

/** I-13 详情事件的页面生命周期，口径对齐 iOS `TellomiFeedEventsWiringTests`。 */
class FeedDetailEventSessionTest {

  private val t0 = 1_800_000_000_000L

  private fun types(session: FeedDetailEventSession) = session.buffer.drain().flatten().map { it.type }

  @Test
  fun `open is reported once per page, even after background and return`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.RECOMMEND, enabled = true)

    session.open(t0)
    session.open(t0 + 1_000) // 盖上别的页面再回来 / 回到前台
    assertThat(session.isOpen).isTrue()

    assertThat(types(session)).containsExactly("open")
  }

  @Test
  fun `close after 45 seconds reports dwell and close only happens once`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.FOLLOWING, enabled = true)
    session.open(t0)

    assertThat(session.close(t0 + 45_000)).isTrue()
    assertThat(session.close(t0 + 90_000)).isFalse()

    val events = session.buffer.drain().flatten()
    assertThat(events.map { it.type }).containsExactly("open", "dwell")
    assertThat(events.last().value).isEqualTo(45.0)
    assertThat(events.last().surface).isEqualTo("following")
  }

  @Test
  fun `quick exit at three seconds inclusive, nothing between three and thirty`() {
    val fast = FeedDetailEventSession("p", FeedEventSurface.SEARCH, enabled = true)
    fast.open(t0)
    fast.close(t0 + 3_000)
    assertThat(types(fast)).containsExactly("open", "quick_exit")

    val middle = FeedDetailEventSession("p", FeedEventSurface.SEARCH, enabled = true)
    middle.open(t0)
    middle.close(t0 + 10_000)
    assertThat(types(middle)).containsExactly("open")
  }

  @Test
  fun `after closing, the same page never reopens`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.RECOMMEND, enabled = true)
    session.open(t0)
    session.close(t0 + 1_000)
    session.open(t0 + 2_000)

    assertThat(types(session)).containsExactly("open", "quick_exit")
    assertThat(session.isOpen).isFalse()
  }

  @Test
  fun `disabled flag or missing surface reports nothing`() {
    val off = FeedDetailEventSession("p", FeedEventSurface.RECOMMEND, enabled = false)
    off.open(t0)
    off.shareFinished("p", shareable = true, outcome = FeedShareOutcome.COMPLETED, nowMillis = t0)
    off.close(t0 + 60_000)
    assertThat(off.buffer.size).isEqualTo(0)

    val noSurface = FeedDetailEventSession("p", null, enabled = true)
    noSurface.open(t0)
    noSurface.shareFinished("p", shareable = true, outcome = FeedShareOutcome.COMPLETED, nowMillis = t0)
    noSurface.close(t0 + 60_000)
    assertThat(noSurface.buffer.size).isEqualTo(0)
  }

  @Test
  fun `close without open reports nothing`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.RECOMMEND, enabled = true)
    assertThat(session.close(t0)).isFalse()
    assertThat(session.buffer.drain()).isEmpty()
  }

  @Test
  fun `share only when completed, shareable and for this post`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.PROFILE, enabled = true)

    session.shareFinished("p", shareable = true, outcome = FeedShareOutcome.CANCELLED, nowMillis = t0)
    session.shareFinished("p", shareable = false, outcome = FeedShareOutcome.COMPLETED, nowMillis = t0)
    session.shareFinished("quoted", shareable = true, outcome = FeedShareOutcome.COMPLETED, nowMillis = t0)
    session.shareFinished("p", shareable = true, outcome = FeedShareOutcome.COMPLETED, nowMillis = t0)

    val events = session.buffer.drain().flatten()
    assertThat(events.map { it.type }).containsExactly("share")
    assertThat(events.single().surface).isEqualTo("profile")
    assertThat(events.single().value).isNull()
  }

  @Test
  fun `closing without flush keeps events buffered`() {
    val session = FeedDetailEventSession("p", FeedEventSurface.RECOMMEND, enabled = true)
    session.open(t0)
    session.close(t0 + 31_000)

    assertThat(session.buffer.size).isEqualTo(2)
  }

  @Test
  fun `surface round trips through its api name`() {
    FeedEventSurface.entries.forEach { assertThat(FeedEventSurface.fromApiName(it.apiName)).isEqualTo(it) }
    assertThat(FeedEventSurface.fromApiName(null)).isNull()
    assertThat(FeedEventSurface.fromApiName("notifications")).isNull()
  }
}
