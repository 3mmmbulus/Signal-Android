/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tellomi (interaction audit A-29 / Q-07): like both Telegram apps, the attachment sheet's dimmed chat fades in as the
 * sheet comes up, then stays while the sheet is dragged or springs back (closing fades the whole window out).
 */
class AttachmentSheetScrimTest {

  private val height = 2000f
  private val collapsedTop = 540f // 73 % of the height showing

  private fun shown(sheetTop: Float, entering: Boolean) = AttachmentSheetState.scrimShown(height, sheetTop, collapsedTop, entering)

  @Test
  fun `while the sheet comes up the first time, the dim comes in with it`() {
    assertEquals(0f, shown(sheetTop = height, entering = true), 0f)
    assertEquals(0.5f, shown(sheetTop = (height + collapsedTop) / 2, entering = true), 0.0001f)
    assertEquals(1f, shown(sheetTop = collapsedTop, entering = true), 0f)
  }

  @Test
  fun `dragged below collapsed or springing back, the dim stays full`() {
    assertEquals(1f, shown(sheetTop = collapsedTop + 100f, entering = false), 0f)
    assertEquals(1f, shown(sheetTop = collapsedTop + 600f, entering = false), 0f)
    assertEquals(1f, shown(sheetTop = height, entering = false), 0f)
  }

  @Test
  fun `expanded, the dim stays full`() {
    assertEquals(1f, shown(sheetTop = 0f, entering = false), 0f)
    assertEquals(1f, shown(sheetTop = 200f, entering = false), 0f)
  }
}
