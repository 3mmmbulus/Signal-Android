/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tellomi (interaction audit A-11): the emoji / keyboard button crossfades its icon instead of swapping it in one frame. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiIconSwapTest {

  private val emoji = ColorDrawable(Color.RED)
  private val keyboard = ColorDrawable(Color.BLUE)

  @Test
  fun `a button that is not on screen yet just takes the new icon`() {
    val view = ImageView(Robolectric.buildActivity(Activity::class.java).setup().get())

    TellomiIconSwap.swap(view, emoji, keyboard)

    assertThat(view.drawable).isSameInstanceAs(keyboard)
    assertThat(view.scaleX).isEqualTo(1f)
  }

  @Test
  fun `the first icon is set without a transition`() {
    val view = laidOutImageView()

    TellomiIconSwap.swap(view, null, emoji)

    assertThat(view.drawable).isSameInstanceAs(emoji)
  }

  @Test
  fun `on screen the icons crossfade and the button ends up holding just the new icon`() {
    val view = laidOutImageView()
    view.setImageDrawable(emoji)

    TellomiIconSwap.swap(view, emoji, keyboard)

    assertThat(view.drawable).isInstanceOf(TransitionDrawable::class)
    assertThat(view.scaleX).isEqualTo(TellomiIconSwap.POP_FROM_SCALE)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiIconSwap.CROSSFADE_MS.toLong() + 50))

    assertThat(view.drawable).isSameInstanceAs(keyboard)
  }

  @Test
  fun `swapping back before the crossfade ends keeps the latest icon`() {
    val view = laidOutImageView()
    view.setImageDrawable(emoji)

    TellomiIconSwap.swap(view, emoji, keyboard)
    TellomiIconSwap.swap(view, keyboard, emoji)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiIconSwap.CROSSFADE_MS.toLong() * 2))

    assertThat(view.drawable).isSameInstanceAs(emoji)
  }

  private fun laidOutImageView(): ImageView {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = ImageView(activity)
    activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(48, 48)) })
    shadowOf(Looper.getMainLooper()).idle()
    return view
  }
}
