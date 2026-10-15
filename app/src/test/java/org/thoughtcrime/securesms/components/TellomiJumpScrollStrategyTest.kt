/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tellomi (interaction audit A-08): how far and how long a jump to a message glides. */
class TellomiJumpScrollStrategyTest {

  @Test
  fun `the last stretch takes longer the farther it goes, within limits`() {
    assertEquals(TellomiJumpScrollStrategy.MIN_GLIDE_MS, TellomiJumpScrollStrategy.glideDurationMs(0, 2000))
    assertEquals(400, TellomiJumpScrollStrategy.glideDurationMs(2000, 2000))
    assertEquals(TellomiJumpScrollStrategy.MAX_GLIDE_MS, TellomiJumpScrollStrategy.glideDurationMs(20000, 2000))
    assertEquals(TellomiJumpScrollStrategy.MIN_GLIDE_MS, TellomiJumpScrollStrategy.glideDurationMs(500, 0))
  }

  @Test
  fun `a message within a screen is glided to directly`() {
    // 10 items on screen (20..29): 35 is 6 past the last one, inside one screen.
    assertNull(TellomiJumpScrollStrategy.stagingPosition(target = 35, firstVisible = 20, lastVisible = 29, itemCount = 500))
    assertNull(TellomiJumpScrollStrategy.stagingPosition(target = 12, firstVisible = 20, lastVisible = 29, itemCount = 500))
  }

  @Test
  fun `a far message is approached from one screen short of it`() {
    assertEquals(290, TellomiJumpScrollStrategy.stagingPosition(target = 300, firstVisible = 20, lastVisible = 29, itemCount = 500))
    assertEquals(10, TellomiJumpScrollStrategy.stagingPosition(target = 0, firstVisible = 200, lastVisible = 209, itemCount = 500))
  }

  @Test
  fun `the stop is kept inside the list`() {
    assertEquals(499, TellomiJumpScrollStrategy.stagingPosition(target = 495, firstVisible = 600, lastVisible = 609, itemCount = 500))
  }

  @Test
  fun `nothing laid out yet means no staging`() {
    assertNull(TellomiJumpScrollStrategy.stagingPosition(target = 300, firstVisible = -1, lastVisible = -1, itemCount = 500))
  }
}
