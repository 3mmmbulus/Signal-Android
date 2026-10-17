/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.view.MotionEvent
import assertk.all
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import org.junit.Test

/**
 * Tellomi（交互审计 A-06，owner 2026-10-17）：按住 ⌄ 往条的开头拉，照 Telegram 安卓的阻力和门槛把条原地长成表情面板；
 * 其余的滑动还是上游的「滑到哪个表情就选哪个」。
 */
class TellomiReactionPullTest {

  companion object {
    private const val EMOJI_COUNT = 8
    private const val DEAD_ZONE_SIZE = 20f
    private const val PULL_UNIT = 42f

    /** 8 dp of the 42, in these units. */
    private const val SLOP = 8f

    /** Eight 100 wide segments from 100 to 900; the expand slot is 800–900. */
    private val LTR = ReactionScrubber.Geometry(
      stripStart = 100f,
      stripEnd = 900f,
      stripTop = 200f,
      stripBottom = 300f,
      scrubTop = 200f,
      scrubBottom = 900f,
      isStripVisible = true
    )

    /** The same strip right to left: the expand slot is 100–200 and the start is to the right. */
    private val RTL = LTR.copy(stripStart = 900f, stripEnd = 100f)
  }

  /** Open, with the long press's own gesture already over, as after the thumb lifted with nothing chosen. */
  private fun scrubber(geometry: ReactionScrubber.Geometry = LTR, pullUnit: Float = PULL_UNIT): ReactionScrubber {
    val scrubber = ReactionScrubber(EMOJI_COUNT, DEAD_ZONE_SIZE, pullUnit)
    scrubber.geometry = geometry
    scrubber.open()
    scrubber.apply(MotionEvent.ACTION_MOVE, 500f, 1000f)
    scrubber.apply(MotionEvent.ACTION_UP, 500f, 1000f)
    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.TAP)
    return scrubber
  }

  @Test
  fun `a drag from the expand slot toward the start pulls instead of scrubbing, counted from past the slop`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_MOVE, 850f - SLOP - 35f, 250f)

    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.PULL)
    assertThat(outcome).isInstanceOf(ReactionScrubber.Outcome.Pull::class).prop(ReactionScrubber.Outcome.Pull::progress).isCloseTo(0.5f, 1e-4f)
  }

  @Test
  fun `the pull starts well inside the tap dead zone`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 850f - SLOP - 1f, 250f)

    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.PULL)
  }

  @Test
  fun `a wobble smaller than the slop is still a tap`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 845f, 252f)
    val outcome = scrubber.apply(MotionEvent.ACTION_UP, 845f, 252f)

    assertThat(outcome).isEqualTo(ReactionScrubber.Outcome.Commit(consumed = true, index = EMOJI_COUNT - 1))
  }

  @Test
  fun `letting go past 0 95 commits the expand slot`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 800f, 250f)
    // 70 past the slop is a progress of 1.
    scrubber.apply(MotionEvent.ACTION_MOVE, 850f - SLOP - 70f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_UP, 850f - SLOP - 70f, 250f)

    assertThat(outcome).isEqualTo(ReactionScrubber.Outcome.Commit(consumed = true, index = EMOJI_COUNT - 1))
  }

  @Test
  fun `letting go short springs back and waits for the next touch`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 800f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_UP, 800f, 250f)

    assertThat(outcome).isInstanceOf(ReactionScrubber.Outcome.Pull::class).all {
      prop(ReactionScrubber.Outcome.Pull::isReleased).isTrue()
      prop(ReactionScrubber.Outcome.Pull::progress).isEqualTo(0f)
    }
    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.TAP)
    assertThat(scrubber.isShowing).isTrue()
    assertThat(scrubber.selectedIndex).isEqualTo(ReactionScrubber.NO_SELECTION)
  }

  @Test
  fun `a tap on the expand slot commits it`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_UP, 852f, 250f)

    assertThat(outcome).isEqualTo(ReactionScrubber.Outcome.Commit(consumed = true, index = EMOJI_COUNT - 1))
  }

  @Test
  fun `a drag from any other slot still scrubs`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 750f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_MOVE, 650f, 250f)

    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.SCRUB)
    assertThat(outcome).isEqualTo(ReactionScrubber.Outcome.Scrubbing(consumed = true, previousIndex = 6, index = 5))
  }

  @Test
  fun `leaving the expand slot across the strip or away from the start does not pull`() {
    val across = scrubber()
    across.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    across.apply(MotionEvent.ACTION_MOVE, 830f, 400f)
    assertThat(across.phase).isEqualTo(ReactionScrubber.Phase.SCRUB)

    val outward = scrubber()
    outward.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    outward.apply(MotionEvent.ACTION_MOVE, 890f, 250f)
    assertThat(outward.phase).isEqualTo(ReactionScrubber.Phase.SCRUB)
  }

  @Test
  fun `right to left the start is the other way`() {
    val scrubber = scrubber(RTL)

    scrubber.apply(MotionEvent.ACTION_DOWN, 150f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_MOVE, 150f + SLOP + 35f, 250f)

    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.PULL)
    assertThat(outcome).isInstanceOf(ReactionScrubber.Outcome.Pull::class).prop(ReactionScrubber.Outcome.Pull::progress).isCloseTo(0.5f, 1e-4f)
  }

  @Test
  fun `without a pull unit the expand slot only scrubs, as upstream`() {
    val scrubber = scrubber(pullUnit = 0f)

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 780f, 250f)

    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.SCRUB)
  }

  @Test
  fun `a cancelled pull dismisses`() {
    val scrubber = scrubber()

    scrubber.apply(MotionEvent.ACTION_DOWN, 850f, 250f)
    scrubber.apply(MotionEvent.ACTION_MOVE, 800f, 250f)
    val outcome = scrubber.apply(MotionEvent.ACTION_CANCEL, 800f, 250f)

    assertThat(outcome).isEqualTo(ReactionScrubber.Outcome.Dismiss(consumed = true))
    assertThat(scrubber.phase).isEqualTo(ReactionScrubber.Phase.HIDDEN)
  }
}
