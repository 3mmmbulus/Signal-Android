/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.compose.mediakeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tellomi (interaction audit A-35): opening our keyboard from nothing, or closing it to nothing, the content gives up only as much
 * of the bottom as the sliding sheet shows, so the input bar rides on its top edge; swapping with the system keyboard keeps the
 * whole height claimed.
 */
class MediaKeyboardClaimedSpaceTest {

  private val heightPx = 800

  private fun claimed(
    requested: Boolean = false,
    showing: Boolean = false,
    followSheet: Boolean = false,
    systemKeyboardTakingOverSpace: Boolean = false,
    sheetShowingPx: Int? = null,
    backProgress: Float = 0f
  ): Int {
    return claimedKeyboardSpacePx(
      requested = requested,
      showing = showing,
      followSheet = followSheet,
      systemKeyboardTakingOverSpace = systemKeyboardTakingOverSpace,
      heightPx = heightPx,
      sheetShowingPx = { sheetShowingPx },
      backProgress = backProgress
    )
  }

  @Test
  fun `opening from nothing claims only what the sheet shows`() {
    assertEquals(0, claimed(requested = true, showing = true, followSheet = true, sheetShowingPx = 0))
    assertEquals(300, claimed(requested = true, showing = true, followSheet = true, sheetShowingPx = 300))
  }

  @Test
  fun `opening from nothing before the sheet is laid out claims all of it`() {
    assertEquals(heightPx, claimed(requested = true, followSheet = true, sheetShowingPx = null))
  }

  @Test
  fun `swapping in from the system keyboard claims all of it at once`() {
    assertEquals(heightPx, claimed(requested = true, showing = false, followSheet = false, sheetShowingPx = 0))
  }

  @Test
  fun `once open the whole height stays claimed`() {
    assertEquals(heightPx, claimed(requested = true, showing = true, followSheet = false, sheetShowingPx = heightPx))
  }

  @Test
  fun `closing to nothing gives the space back as the sheet slides away`() {
    assertEquals(500, claimed(requested = false, showing = true, sheetShowingPx = 500))
    assertEquals(0, claimed(requested = false, showing = true, sheetShowingPx = null))
  }

  @Test
  fun `closing to the system keyboard keeps the whole height while it comes up`() {
    assertEquals(heightPx, claimed(requested = false, showing = true, systemKeyboardTakingOverSpace = true, sheetShowingPx = 200))
  }

  @Test
  fun `a back gesture gives the space back as it carries the keyboard off`() {
    assertEquals(200, claimed(requested = true, showing = true, backProgress = 0.75f))
    // Let go: the keyboard is already off screen while the sheet hides, nothing comes back.
    assertEquals(0, claimed(requested = false, showing = true, sheetShowingPx = heightPx, backProgress = 1f))
  }

  @Test
  fun `nothing of ours on screen claims nothing`() {
    assertEquals(0, claimed())
  }
}
