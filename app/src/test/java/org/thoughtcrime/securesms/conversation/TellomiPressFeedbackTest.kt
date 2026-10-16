/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tellomi (interaction audit A-09): tappable parts of a message react the moment the finger lands. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiPressFeedbackTest {

  private lateinit var root: FrameLayout
  private lateinit var block: View
  private lateinit var pills: LinearLayout

  private fun layout() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    root = FrameLayout(activity)
    block = View(activity).apply { isClickable = true }
    pills = LinearLayout(activity)
    pills.addView(View(activity), LinearLayout.LayoutParams(40, 20))
    pills.addView(View(activity), LinearLayout.LayoutParams(40, 20))
    root.addView(block, FrameLayout.LayoutParams(200, 100))
    root.addView(pills, FrameLayout.LayoutParams(80, 20).apply { topMargin = 150 })
    activity.setContentView(root)
    shadowOf(Looper.getMainLooper()).idle()
  }

  /** The test window sits at the screen origin, so view coordinates are screen coordinates here. */
  private fun event(action: Int, x: Float, y: Float): MotionEvent {
    val now = SystemClock.uptimeMillis()
    return MotionEvent.obtain(now, now, action, x, y, 0)
  }

  private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

  private fun blockTracker() = TellomiPressFeedback.Tracker(root) { rawX, rawY ->
    if (TellomiPressFeedback.hits(block, rawX, rawY)) TellomiPressFeedback.Tracker.Target(block, TellomiPressFeedback.BLOCK_SCALE) else null
  }

  @Test
  fun `a block shrinks as soon as the finger lands and springs back on lift`() {
    layout()
    val tracker = blockTracker()

    tracker.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 50f))
    settle()
    assertThat(block.scaleX).isCloseTo(TellomiPressFeedback.BLOCK_SCALE, 0.005f)

    tracker.onTouchEvent(event(MotionEvent.ACTION_UP, 50f, 50f))
    settle()
    assertThat(block.scaleX).isCloseTo(1f, 0.005f)
  }

  @Test
  fun `moving past the touch slop lets go, so a scroll that starts on a block does not leave it pressed`() {
    layout()
    val tracker = blockTracker()

    tracker.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 50f))
    settle()
    tracker.onTouchEvent(event(MotionEvent.ACTION_MOVE, 50f, 90f))
    settle()

    assertThat(block.scaleX).isCloseTo(1f, 0.005f)
  }

  @Test
  fun `an overlay target only enters its pressed state early, without scaling`() {
    layout()
    val tracker = TellomiPressFeedback.Tracker(root) { _, _ -> TellomiPressFeedback.Tracker.Target(block, null) }

    tracker.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 50f))

    assertThat(block.isPressed).isTrue()
    assertThat(block.scaleX).isEqualTo(1f)
  }

  @Test
  fun `the pill under the finger is found, and nothing outside the reactions row`() {
    layout()
    val location = IntArray(2)
    pills.getLocationOnScreen(location)

    assertThat(TellomiPressFeedback.pillAt(pills, location[0] + 50f, location[1] + 10f)).isSameInstanceAs(pills.getChildAt(1))
    assertThat(TellomiPressFeedback.pillAt(pills, location[0] + 10f, location[1] + 10f)).isSameInstanceAs(pills.getChildAt(0))
    assertThat(TellomiPressFeedback.pillAt(pills, location[0] + 10f, location[1] - 30f)).isNull()
  }

  @Test
  fun `a view that is not shown is never hit`() {
    layout()
    block.visibility = View.GONE
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(TellomiPressFeedback.hits(block, 50f, 50f)).isFalse()
  }

  @Test
  fun `the conversation title shrinks around its centre on touch down and still gets the click`() {
    layout()
    var clicked = false
    block.setOnClickListener { clicked = true }
    block.setOnTouchListener(TellomiPressFeedback.wholeViewTouchListener())

    block.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 20f, 20f))
    settle()
    assertThat(block.scaleX).isCloseTo(TellomiPressFeedback.TITLE_SCALE, 0.005f)
    assertThat(block.pivotX).isCloseTo(100f, 0.5f)
    assertThat(block.pivotY).isCloseTo(50f, 0.5f)

    block.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 20f, 20f))
    settle()
    assertThat(block.scaleX).isCloseTo(1f, 0.005f)
    assertThat(clicked).isTrue()
  }

  @Test
  fun `dragging off the title lets go of it`() {
    layout()
    block.setOnTouchListener(TellomiPressFeedback.wholeViewTouchListener())

    block.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 20f, 20f))
    settle()
    block.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 120f, 20f))
    settle()
    assertThat(block.scaleX).isCloseTo(1f, 0.005f)
  }
}
