/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tellomi (tellomi/tellomi#1263, spec R-1): the cover over a covered image / video, and uncovering it with a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSpoilerViewTest {

  private val view = TellomiSpoilerView(ApplicationProvider.getApplicationContext()).apply {
    measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
    layout(0, 0, 300, 200)
  }

  @Test
  fun `covering shows it and draws without a loaded image`() {
    view.cover(null)

    assertThat(view.isCovering).isTrue()
    assertThat(view.visibility).isEqualTo(View.VISIBLE)
    view.draw(Canvas(Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)))
  }

  @Test
  fun `a tap uncovers it once the reveal has run, and reports it once`() {
    view.cover(null)
    var revealed = 0

    view.reveal(40f, 60f) { revealed++ }
    assertThat(view.isRevealing).isTrue()
    view.reveal(40f, 60f) { revealed++ }

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiSpoilerView.REVEAL_DURATION_MS + 100))

    assertThat(revealed).isEqualTo(1)
    assertThat(view.isCovering).isFalse()
    assertThat(view.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun `nothing to reveal when it was never covered`() {
    view.uncover()
    var revealed = false

    view.reveal(10f, 10f) { revealed = true }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiSpoilerView.REVEAL_DURATION_MS + 100))

    assertThat(revealed).isFalse()
    assertThat(view.isCovering).isFalse()
  }

  @Test
  fun `covering again after a reveal starts over`() {
    view.cover(null)
    view.reveal(0f, 0f) { }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiSpoilerView.REVEAL_DURATION_MS + 100))

    view.cover(null)

    assertThat(view.isCovering).isTrue()
    assertThat(view.isRevealing).isFalse()
  }
}
