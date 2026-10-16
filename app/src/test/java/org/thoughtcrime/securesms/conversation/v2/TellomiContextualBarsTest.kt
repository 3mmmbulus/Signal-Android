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

/**
 * Tellomi (interaction audit A-15): when entering or leaving multi-select, the top bar, the input bar, the action bar and the message list
 * change together on one progress, with one duration and one curve.
 */
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

  @Test
  fun `the top bar and the bottom bars share one duration`() {
    // Telegram Android drives its bottom bars with one 240 ms progress; ours keeps the action bar's old 250 ms for all of them.
    assertEquals(250L, TellomiContextualBars.DURATION_MS)
  }

  @Test
  fun `entering multi-select fades the input bar down and out while the action bar comes up and in`() {
    val start = TellomiContextualBars.bottomFrame(0f, entering = true, inputSlidePx = 54f, barSlidePx = 60f, listShiftPx = 73f)
    assertEquals(TellomiContextualBars.BottomFrame(1f, 0f, 0f, 60f, 73f), start)

    val half = TellomiContextualBars.bottomFrame(0.5f, entering = true, inputSlidePx = 54f, barSlidePx = 60f, listShiftPx = 73f)
    assertEquals(0.5f, half.inputAlpha, 0f)
    assertEquals(27f, half.inputTranslationY, 0f)
    assertEquals(0.5f, half.barAlpha, 0f)
    assertEquals(30f, half.barTranslationY, 0f)
    assertEquals(36.5f, half.listTranslationY, 0f)

    val end = TellomiContextualBars.bottomFrame(1f, entering = true, inputSlidePx = 54f, barSlidePx = 60f, listShiftPx = 73f)
    assertEquals(TellomiContextualBars.BottomFrame(0f, 54f, 1f, 0f, 0f), end)
  }

  @Test
  fun `leaving multi-select is the same transition backwards and the list still lands where it is laid out`() {
    val start = TellomiContextualBars.bottomFrame(0f, entering = false, inputSlidePx = 54f, barSlidePx = 60f, listShiftPx = -73f)
    assertEquals(TellomiContextualBars.BottomFrame(0f, 54f, 1f, 0f, -73f), start)

    val end = TellomiContextualBars.bottomFrame(1f, entering = false, inputSlidePx = 54f, barSlidePx = 60f, listShiftPx = -73f)
    assertEquals(1f, end.inputAlpha, 0f)
    assertEquals(0f, end.inputTranslationY, 0f)
    assertEquals(0f, end.barAlpha, 0f)
    assertEquals(60f, end.barTranslationY, 0f)
    assertEquals(0f, end.listTranslationY, 0f)
  }
}
