/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tellomi (interaction audit A-15): the bar that comes in when entering or leaving multi-select fades in. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiContextualBarsTest {

  @Test
  fun `the incoming bar starts transparent and ends fully shown`() {
    val bar = View(ApplicationProvider.getApplicationContext())

    TellomiContextualBars.fadeIn(bar)
    assertEquals(0f, bar.alpha, 0f)
    assertEquals(TellomiContextualBars.DURATION_MS, bar.animate().duration)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    assertEquals(1f, bar.alpha, 0f)
  }

  @Test
  fun `fading in again mid-way restarts from transparent and still ends shown`() {
    val bar = View(ApplicationProvider.getApplicationContext())

    TellomiContextualBars.fadeIn(bar)
    TellomiContextualBars.fadeIn(bar)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

    assertEquals(1f, bar.alpha, 0f)
  }
}
