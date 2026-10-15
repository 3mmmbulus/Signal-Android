/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.thoughtcrime.securesms.R
import kotlin.math.roundToInt

/**
 * Tellomi (interaction audit A-18): the played part of a voice note's waveform follows the playhead to the pixel, so
 * the bar under the playhead is half played instead of the whole bar switching colour at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiWaveformProgressTest {

  @Test
  fun `the bar under the playhead is played on the left and unplayed on the right`() {
    val view = WaveFormSeekBarView(ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Signal_DayNight))
    view.setColors(PLAYED, UNPLAYED, Color.TRANSPARENT)
    view.thumb = null
    view.setPadding(0, 0, 0, 0)
    view.setWaveData(floatArrayOf(1f, 1f))
    // Skip the bars' grow-in animation.
    WaveFormSeekBarView::class.java.getDeclaredField("dataSetTime").apply { isAccessible = true }.setLong(view, 0L)

    view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
    view.layout(0, 0, WIDTH, HEIGHT)

    val barWidth = WaveFormSeekBarView::class.java.getDeclaredField("barWidth").apply { isAccessible = true }.getInt(view)
    check(barWidth >= 6) { "bar too thin to sample inside: $barWidth px" }
    view.max = 1000
    // Playhead in the middle of the first bar.
    view.progress = (1000f * (barWidth / 2f) / WIDTH).roundToInt()

    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))

    assertEquals("left quarter of the first bar", PLAYED, bitmap.getPixel(barWidth / 4, HEIGHT / 2))
    assertEquals("right quarter of the first bar", UNPLAYED, bitmap.getPixel(barWidth - 1 - barWidth / 4, HEIGHT / 2))
    assertEquals("the last bar", UNPLAYED, bitmap.getPixel(WIDTH - barWidth / 2, HEIGHT / 2))
  }

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 120
    const val PLAYED = 0xFFFF0000.toInt()
    const val UNPLAYED = 0xFF0000FF.toInt()
  }
}
