/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Tellomi (interaction audit A-25): when a chat list row enters / leaves multiselect or is ticked, the check circle fades and grows in
 * (or fades and shrinks out) while the avatar and text slide aside, starting from the layout callback (the list is laid out by Compose
 * after pre-draw, so anything waiting for pre-draw starts a frame late).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiListSelectionTransitionTest {

  private lateinit var row: LinearLayout
  private lateinit var check: View
  private lateinit var avatar: View

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    check = View(context).apply { visibility = View.GONE }
    avatar = View(context)
    row.addView(check, LinearLayout.LayoutParams(CHECK, CHECK))
    row.addView(avatar, LinearLayout.LayoutParams(AVATAR, AVATAR))
    layOut()
  }

  @Test
  fun `entering multiselect slides the avatar aside and grows the check circle in`() {
    TellomiListSelectionTransition.run(row, check) { check.visibility = View.VISIBLE }
    layOut()

    assertThat(avatar.left).isEqualTo(CHECK)
    assertThat(avatar.translationX).isEqualTo(-CHECK.toFloat())
    assertThat(check.alpha).isEqualTo(0f)
    assertThat(check.scaleX).isEqualTo(TellomiListSelectionTransition.CHECK_START_SCALE)

    advance(TellomiListSelectionTransition.DURATION_MS * 2)
    assertThat(avatar.translationX).isEqualTo(0f)
    assertThat(check.alpha).isEqualTo(1f)
    assertThat(check.scaleX).isEqualTo(1f)
  }

  @Test
  fun `leaving multiselect slides the avatar back and fades the old check circle out on the overlay`() {
    check.visibility = View.VISIBLE
    layOut()

    TellomiListSelectionTransition.run(row, check) { check.visibility = View.GONE }
    layOut()

    assertThat(avatar.left).isEqualTo(0)
    assertThat(avatar.translationX).isEqualTo(CHECK.toFloat())

    advance(TellomiListSelectionTransition.DURATION_MS * 2)
    assertThat(avatar.translationX).isEqualTo(0f)
    assertThat(check.alpha).isEqualTo(1f)
  }

  @Test
  fun `ticking a row pops the new check in place without moving anything`() {
    check.visibility = View.VISIBLE
    layOut()

    TellomiListSelectionTransition.run(row, check) { check.requestLayout() }
    layOut()

    assertThat(avatar.translationX).isEqualTo(0f)
    assertThat(check.alpha).isEqualTo(0f)

    advance(TellomiListSelectionTransition.DURATION_MS * 2)
    assertThat(check.alpha).isEqualTo(1f)
    assertThat(check.scaleX).isEqualTo(1f)
  }

  @Test
  fun `a row reused for another chat lands at the end at once`() {
    TellomiListSelectionTransition.run(row, check) { check.visibility = View.VISIBLE }
    layOut()
    advance(TellomiListSelectionTransition.DURATION_MS / 3)

    TellomiListSelectionTransition.cancel(row)

    assertThat(avatar.translationX).isEqualTo(0f)
    assertThat(check.alpha).isEqualTo(1f)
    assertThat(check.scaleX).isEqualTo(1f)
  }

  @Test
  fun `the row's own layout transition is put back after the visibility change`() {
    val transition = android.animation.LayoutTransition()
    row.layoutTransition = transition

    var duringChange: Any? = Unit
    TellomiListSelectionTransition.run(row, check) {
      duringChange = row.layoutTransition
      check.visibility = View.VISIBLE
    }

    assertThat(duringChange).isEqualTo(null)
    assertThat(row.layoutTransition).isEqualTo(transition)
  }

  private fun layOut() {
    row.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(AVATAR, View.MeasureSpec.EXACTLY))
    row.layout(0, 0, WIDTH, AVATAR)
  }

  private fun advance(millis: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
  }

  companion object {
    private const val CHECK = 24
    private const val AVATAR = 48
    private const val WIDTH = 400
  }
}
