/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.conversation.v2.TellomiPinnedBar.Segments
import org.thoughtcrime.securesms.conversation.v2.TellomiPinnedBar.Title
import org.thoughtcrime.securesms.conversation.v2.TellomiPinnedBar.TitleKind

/** Tellomi (pinned-messages.md 3.4): the pinned bar — which pin, its title, the line on its left — as in both Telegram apps. */
class TellomiPinnedBarTest {

  private val pins = listOf(100L, 200L, 300L, 400L, 500L)

  @Test
  fun `while scrolling it shows the newest pin at or before the newest message on screen`() {
    assertEquals("at the bottom: the newest", 4, TellomiPinnedBar.pinFor(pins, 900))
    assertEquals(2, TellomiPinnedBar.pinFor(pins, 350))
    assertEquals("on a pinned message", 2, TellomiPinnedBar.pinFor(pins, 300))
    assertEquals("above every pin: the oldest", 0, TellomiPinnedBar.pinFor(pins, 50))
  }

  @Test
  fun `each tap goes to an older pin, and from the oldest back to the newest`() {
    var index = 4
    val shown = mutableListOf(index)
    repeat(5) {
      index = TellomiPinnedBar.nextAfterTap(index, pins.size)
      shown += index
    }
    assertEquals(listOf(4, 3, 2, 1, 0, 4), shown)
  }

  @Test
  fun `titles - one pin, the older of two, numbers from the oldest`() {
    assertEquals(Title(TitleKind.PINNED), TellomiPinnedBar.title(0, 1))
    assertEquals(Title(TitleKind.PINNED), TellomiPinnedBar.title(1, 2))
    assertEquals(Title(TitleKind.PREVIOUS), TellomiPinnedBar.title(0, 2))
    assertEquals("the newest has no number", Title(TitleKind.PINNED), TellomiPinnedBar.title(6, 7))
    assertEquals(Title(TitleKind.NUMBERED, 1), TellomiPinnedBar.title(0, 7))
    assertEquals(Title(TitleKind.NUMBERED, 6), TellomiPinnedBar.title(5, 7))
  }

  @Test
  fun `the line shows at most three segments, the selected one in the middle where it can`() {
    assertEquals(Segments(0, 2, fadeTop = false, fadeBottom = false), TellomiPinnedBar.segments(1, 2))
    assertEquals(Segments(0, 3, fadeTop = false, fadeBottom = false), TellomiPinnedBar.segments(2, 3))
    assertEquals("the oldest of seven", Segments(0, 3, fadeTop = false, fadeBottom = true), TellomiPinnedBar.segments(0, 7))
    assertEquals(Segments(2, 3, fadeTop = true, fadeBottom = true), TellomiPinnedBar.segments(3, 7))
    assertEquals("the newest of seven", Segments(4, 3, fadeTop = true, fadeBottom = false), TellomiPinnedBar.segments(6, 7))
  }

  @Test
  fun `a pin chosen by a tap is let go by the user scrolling, not by the jump`() {
    assertFalse("the jump's own scrolling", TellomiPinnedBar.releasesForcedPin(userDragging = false, newestVisibleBefore = 900, newestVisibleNow = 300, forcedPinDate = 200))
    assertTrue("dragged toward newer messages", TellomiPinnedBar.releasesForcedPin(userDragging = true, newestVisibleBefore = 300, newestVisibleNow = 320, forcedPinDate = 200))
    assertFalse("dragged toward older, not past it yet", TellomiPinnedBar.releasesForcedPin(userDragging = true, newestVisibleBefore = 300, newestVisibleNow = 250, forcedPinDate = 200))
    assertTrue("dragged past it", TellomiPinnedBar.releasesForcedPin(userDragging = true, newestVisibleBefore = 250, newestVisibleNow = 150, forcedPinDate = 200))
  }
}
