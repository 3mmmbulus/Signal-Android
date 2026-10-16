/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tellomi (interaction audit A-19): the conversation title's avatar flies to the settings page's large avatar. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiAvatarHeroTest {

  private lateinit var source: View
  private lateinit var target: View

  private fun layout() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    source = View(activity).apply { setBackgroundColor(Color.RED) }
    target = View(activity).apply { setBackgroundColor(Color.BLUE) }
    root.addView(source, FrameLayout.LayoutParams(40, 40).apply { leftMargin = 10; topMargin = 10 })
    root.addView(target, FrameLayout.LayoutParams(100, 100).apply { leftMargin = 200; topMargin = 300 })
    activity.setContentView(root)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun settle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

  private fun windowRect(view: View): Rect {
    val location = IntArray(2)
    view.getLocationInWindow(location)
    return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
  }

  @After
  fun tearDown() {
    TellomiAvatarHero.cancel()
    // 中途停掉的弹簧要让动画调度器再走一帧收尾，不然它挂着，下一个测试的弹簧拿不到帧（单跑能过、连着跑卡住）
    settle(100)
  }

  @Test
  fun `the image moves and grows from the small avatar to the large one`() {
    val from = Rect(10, 10, 50, 50)
    val to = Rect(200, 300, 300, 400)
    assertThat(TellomiAvatarHero.lerpRect(from, to, 0f)).isEqualTo(from)
    assertThat(TellomiAvatarHero.lerpRect(from, to, 1f)).isEqualTo(to)
    assertThat(TellomiAvatarHero.lerpRect(from, to, 0.5f)).isEqualTo(Rect(105, 155, 175, 225))
  }

  @Test
  fun `lifting holds the image where the small avatar is, landing flies it over and reveals the real avatar`() {
    layout()

    TellomiAvatarHero.lift(source)
    assertThat(TellomiAvatarHero.isFlyingForTesting).isTrue()
    assertThat(TellomiAvatarHero.currentBoundsForTesting).isEqualTo(windowRect(source))

    assertThat(TellomiAvatarHero.land(target)).isTrue()
    assertThat(target.alpha).isCloseTo(0f, 0.001f)

    settle(1_500)
    assertThat(TellomiAvatarHero.isFlyingForTesting).isFalse()
    assertThat(target.alpha).isCloseTo(1f, 0.001f)
  }

  @Test
  fun `the large avatar is hidden as soon as it is created while the image is on its way`() {
    layout()
    TellomiAvatarHero.lift(source)

    TellomiAvatarHero.prepareTarget(target)
    assertThat(target.alpha).isCloseTo(0f, 0.001f)

    TellomiAvatarHero.land(target)
    settle(1_500)
    assertThat(target.alpha).isCloseTo(1f, 0.001f)
  }

  @Test
  fun `a hidden large avatar comes back if the flight never lands`() {
    layout()
    TellomiAvatarHero.lift(source)
    TellomiAvatarHero.prepareTarget(target)

    settle(TellomiAvatarHero.WAIT_FOR_TARGET_MS + TellomiAvatarHero.REVEAL_MS + 200)

    assertThat(TellomiAvatarHero.isFlyingForTesting).isFalse()
    assertThat(target.alpha).isCloseTo(1f, 0.001f)
  }

  @Test
  fun `without a settings page the image fades away by itself`() {
    layout()

    TellomiAvatarHero.lift(source)
    settle(TellomiAvatarHero.WAIT_FOR_TARGET_MS + TellomiAvatarHero.REVEAL_MS + 100)

    assertThat(TellomiAvatarHero.isFlyingForTesting).isFalse()
  }

  @Test
  fun `opening settings some other way does not fly anything`() {
    layout()

    assertThat(TellomiAvatarHero.land(target)).isFalse()
    assertThat(target.alpha).isCloseTo(1f, 0.001f)
  }

  @Test
  fun `cancelling mid-flight puts the real avatar back`() {
    layout()
    TellomiAvatarHero.lift(source)
    TellomiAvatarHero.land(target)

    TellomiAvatarHero.cancel()

    assertThat(TellomiAvatarHero.isFlyingForTesting).isFalse()
    assertThat(target.alpha).isCloseTo(1f, 0.001f)
  }
}
