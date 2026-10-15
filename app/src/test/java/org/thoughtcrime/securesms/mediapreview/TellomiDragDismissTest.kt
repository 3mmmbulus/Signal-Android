/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (interaction audit A-20): drag-to-dismiss rules for the chat media viewer. */
class TellomiDragDismissTest {

  private val density = 2f
  private fun dp(value: Float) = value * density

  @Test
  fun `a vertical drag starts only past touch slop and when clearly vertical`() {
    val slop = 16f
    assertFalse(TellomiDragDismiss.isVerticalStart(dx = 0f, dy = 10f, touchSlop = slop))
    assertTrue(TellomiDragDismiss.isVerticalStart(dx = 5f, dy = 30f, touchSlop = slop))
    assertTrue(TellomiDragDismiss.isVerticalStart(dx = -5f, dy = -30f, touchSlop = slop))
    assertFalse("a diagonal drag belongs to the pager", TellomiDragDismiss.isVerticalStart(dx = 20f, dy = 30f, touchSlop = slop))
  }

  @Test
  fun `releasing far enough dismisses, a short slow drag springs back`() {
    assertTrue(TellomiDragDismiss.shouldDismiss(dp(128f), 0f, density))
    assertTrue(TellomiDragDismiss.shouldDismiss(dp(-128f), 0f, density))
    assertFalse(TellomiDragDismiss.shouldDismiss(dp(100f), 0f, density))
  }

  @Test
  fun `a flick of about 700 dp per second from rest dismisses`() {
    assertTrue(TellomiDragDismiss.shouldDismiss(dp(4f), dp(700f), density))
    assertFalse(TellomiDragDismiss.shouldDismiss(dp(4f), dp(500f), density))
  }

  @Test
  fun `dragging far and flicking back toward the start springs back`() {
    assertFalse(TellomiDragDismiss.shouldDismiss(dp(150f), dp(-800f), density))
  }

  @Test
  fun `the media flies off in the direction of the projected landing`() {
    assertEquals(1, TellomiDragDismiss.dismissDirection(dp(150f), 0f))
    assertEquals(-1, TellomiDragDismiss.dismissDirection(dp(-150f), 0f))
    assertEquals(-1, TellomiDragDismiss.dismissDirection(dp(20f), dp(-1500f)))
  }

  @Test
  fun `progress and scale follow the drag distance`() {
    assertEquals(0f, TellomiDragDismiss.progress(0f, density), 0f)
    assertEquals(0.5f, TellomiDragDismiss.progress(dp(128f), density), 0.0001f)
    assertEquals(1f, TellomiDragDismiss.progress(dp(-1000f), density), 0f)
    assertEquals(1f, TellomiDragDismiss.scale(0f), 0f)
    assertEquals(TellomiDragDismiss.MIN_SCALE, TellomiDragDismiss.scale(1f), 0f)
  }

  @Test
  fun `the back gesture moves the media down a little as it progresses`() {
    assertEquals(0f, TellomiDragDismiss.backOffset(0f, density), 0f)
    assertEquals(dp(32f), TellomiDragDismiss.backOffset(0.5f, density), 0.0001f)
    assertEquals(dp(TellomiDragDismiss.BACK_TRAVEL_DP), TellomiDragDismiss.backOffset(1.5f, density), 0f)
  }
}
