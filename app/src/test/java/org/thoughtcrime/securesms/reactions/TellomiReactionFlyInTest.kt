/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import android.app.Application
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tellomi (interaction audit A-07): the reaction fly-in's curves (Telegram Android's) and its hold on the pill it is flying to. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiReactionFlyInTest {

  @Test
  fun `the flight starts on the source, ends on the target, and goes sideways before it comes down`() {
    val start = TellomiReactionFlyIn.flightProgress(0f)
    val end = TellomiReactionFlyIn.flightProgress(1f)
    assertEquals(0f, start.x, 1e-4f)
    assertEquals(0f, start.y, 1e-4f)
    assertEquals(1f, end.x, 1e-4f)
    assertEquals(1f, end.y, 1e-4f)
    for (step in 1 until 20) {
      val progress = TellomiReactionFlyIn.flightProgress(step / 20f)
      assertTrue("horizontal leads at ${step / 20f}", progress.x > progress.y)
    }
  }

  /** Telegram Android `ReactionsEffectOverlay` (`ONLY_MOVE_ANIMATION`): 16 / 350 a frame, DEFAULT then EASE_OUT_QUINT / DEFAULT. */
  @Test
  fun `it advances by Telegram's step each frame along Telegram's curves`() {
    assertEquals(16f / 350f, TellomiReactionFlyIn.FRAME_STEP, 0f)
    // 100 ms into a 60 Hz flight.
    val progress = TellomiReactionFlyIn.flightProgress(100f / 350f)
    assertEquals(0.961f, progress.x, 0.005f)
    assertEquals(0.787f, progress.y, 0.005f)
  }

  @Test
  fun `the pill keeps its emoji hidden while the reaction flies and gets it back when the flight is cancelled`() {
    val host = FrameLayout(RuntimeEnvironment.getApplication())
    val target = View(RuntimeEnvironment.getApplication())

    val flight = TellomiReactionFlyIn.start(
      host = host,
      messageId = 42L,
      baseEmoji = "❤️",
      drawable = ColorDrawable(Color.RED),
      sourceInWindow = RectF(100f, 900f, 196f, 996f),
      isReadyToLand = { false },
      findTarget = { target }
    )

    assertNotNull(flight)
    assertTrue(TellomiReactionFlyIn.isAwaitingLanding(42L, "❤️"))
    assertFalse("another message", TellomiReactionFlyIn.isAwaitingLanding(43L, "❤️"))
    assertFalse("another emoji", TellomiReactionFlyIn.isAwaitingLanding(42L, "👍"))

    target.alpha = 0f
    val rebound = View(RuntimeEnvironment.getApplication())
    TellomiReactionFlyIn.holdUntilLanded(42L, "❤️", rebound)
    val otherMessage = View(RuntimeEnvironment.getApplication())
    TellomiReactionFlyIn.holdUntilLanded(43L, "❤️", otherMessage)
    assertEquals("a pill rebuilt mid-flight hides its emoji too", 0f, rebound.alpha, 0f)
    assertEquals("other messages are left alone", 1f, otherMessage.alpha, 0f)

    flight!!.cancel()

    assertFalse(TellomiReactionFlyIn.isAwaitingLanding(42L, "❤️"))
    assertEquals("the hidden emoji is put back", 1f, target.alpha, 0f)
    assertEquals("even one the flight could not find any more", 1f, rebound.alpha, 0f)
  }

  @Test
  fun `nothing flies without an emoji picture or a place to start from`() {
    val host = FrameLayout(RuntimeEnvironment.getApplication())

    assertNull(TellomiReactionFlyIn.start(host, 1L, "❤️", null, RectF(0f, 0f, 10f, 10f), { true }, { null }))
    assertNull(TellomiReactionFlyIn.start(host, 1L, "❤️", ColorDrawable(Color.RED), RectF(), { true }, { null }))
  }
}
