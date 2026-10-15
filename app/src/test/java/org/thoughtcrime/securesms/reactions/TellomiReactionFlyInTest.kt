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

/** Tellomi (interaction audit A-07): the reaction fly-in's arc and its hold on the pill it is flying to. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiReactionFlyInTest {

  private val density = 2f

  @Test
  fun `a target well below is reached with an upward toss`() {
    assertEquals(-TellomiReactionFlyIn.TOSS_SPEED_DP * density, TellomiReactionFlyIn.verticalStartVelocity(200f * density, density), 0f)
  }

  @Test
  fun `a target above or level is flown to without a toss`() {
    assertEquals(0f, TellomiReactionFlyIn.verticalStartVelocity(-150f * density, density), 0f)
    assertEquals(0f, TellomiReactionFlyIn.verticalStartVelocity(10f * density, density), 0f)
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
