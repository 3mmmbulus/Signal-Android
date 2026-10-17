/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tellomi (interaction audit A-29 / Q-07): like both Telegram apps, the scrim stays while you drag and fades only as the sheet goes away. */
class TellomiSheetScrimTest {

  @Test
  fun `while the finger drags the sheet the scrim stays full, even below collapsed`() {
    for (offset in listOf(1f, 0.5f, 0f, -0.25f, -0.6f, -0.95f)) {
      assertEquals(1f, TellomiSheetScrim.dimFraction(offset, settleStartOffset = null), 0f)
    }
  }

  @Test
  fun `released below collapsed and closing, it fades from where it was let go to nothing`() {
    assertEquals(1f, TellomiSheetScrim.dimFraction(-0.25f, settleStartOffset = -0.25f), 0.0001f)
    assertEquals(0.5f, TellomiSheetScrim.dimFraction(-0.625f, settleStartOffset = -0.25f), 0.0001f)
    assertEquals(0f, TellomiSheetScrim.dimFraction(-1f, settleStartOffset = -0.25f), 0f)
  }

  @Test
  fun `released and springing back, it stays full`() {
    assertEquals(1f, TellomiSheetScrim.dimFraction(-0.1f, settleStartOffset = -0.25f), 0f)
    assertEquals(1f, TellomiSheetScrim.dimFraction(0f, settleStartOffset = -0.25f), 0f)
  }

  @Test
  fun `flung shut from above collapsed, it fades over the way from collapsed to hidden`() {
    assertEquals(1f, TellomiSheetScrim.dimFraction(0.3f, settleStartOffset = 0.5f), 0f)
    assertEquals(0.5f, TellomiSheetScrim.dimFraction(-0.5f, settleStartOffset = 0.5f), 0.0001f)
    assertEquals(0f, TellomiSheetScrim.dimFraction(-1f, settleStartOffset = 0.5f), 0f)
  }
}
