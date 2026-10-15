/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tellomi (interaction audit A-29): a bottom sheet's scrim fades as the sheet is dragged down towards closing. */
class TellomiSheetScrimTest {

  @Test
  fun `between collapsed and expanded the scrim stays full`() {
    assertEquals(1f, TellomiSheetScrim.dimFraction(0f), 0f)
    assertEquals(1f, TellomiSheetScrim.dimFraction(0.5f), 0f)
    assertEquals(1f, TellomiSheetScrim.dimFraction(1f), 0f)
  }

  @Test
  fun `below collapsed it fades with the distance to hidden`() {
    assertEquals(0.75f, TellomiSheetScrim.dimFraction(-0.25f), 0.0001f)
    assertEquals(0.5f, TellomiSheetScrim.dimFraction(-0.5f), 0.0001f)
    assertEquals(0f, TellomiSheetScrim.dimFraction(-1f), 0f)
  }
}
