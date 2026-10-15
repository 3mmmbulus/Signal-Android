/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (interaction audit A-24): when the archive swipe is armed and how much the icon pops. */
class TellomiArchiveSwipeTest {

  @Test
  fun `armed from the threshold on`() {
    assertFalse(TellomiArchiveSwipe.isArmed(absoluteDx = 539f, itemWidth = 1080, swipeThreshold = 0.5f))
    assertTrue(TellomiArchiveSwipe.isArmed(absoluteDx = 540f, itemWidth = 1080, swipeThreshold = 0.5f))
    assertFalse("no width yet", TellomiArchiveSwipe.isArmed(absoluteDx = 540f, itemWidth = 0, swipeThreshold = 0.5f))
  }

  @Test
  fun `the icon pops by the armed progress`() {
    assertEquals(1f, TellomiArchiveSwipe.iconScaleFactor(0f), 0f)
    assertEquals(TellomiArchiveSwipe.ARMED_ICON_SCALE, TellomiArchiveSwipe.iconScaleFactor(1f), 0.0001f)
    assertEquals(1f, TellomiArchiveSwipe.iconScaleFactor(-0.2f), 0f)
  }
}
