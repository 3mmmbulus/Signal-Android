/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import org.junit.Test

/** Tellomi（交互审计 A-06 / A-07，owner 2026-10-17）：长按回应条照 Telegram 安卓的数。和 iOS 同一套第 7 个表情的取法。 */
class TellomiReactionStripTest {

  private val signalDefaults = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

  @Test
  fun `seven reactions and the expand slot, 36 dp items with 8 dp ends`() {
    assertThat(TellomiReactionStrip.SLOT_COUNT).isEqualTo(8)
    assertThat(TellomiReactionStrip.REACTION_COUNT).isEqualTo(7)
    assertThat(TellomiReactionStrip.WIDTH_DP).isEqualTo(304f)
    assertThat(TellomiReactionStrip.HEIGHT_DP).isEqualTo(44f)
  }

  @Test
  fun `the saved six stay first and untouched`() {
    val custom = listOf("🔥", "👍", "👎", "😂", "😮", "🙏")
    val slots = TellomiReactionStrip.reactions(custom, applied = null, signalDefaults = signalDefaults, recents = emptyList())
    assertThat(slots.size).isEqualTo(7)
    assertThat(slots.take(6)).isEqualTo(custom)
  }

  @Test
  fun `an applied reaction outside the six takes the seventh slot`() {
    val slots = TellomiReactionStrip.reactions(signalDefaults, applied = "🤔", signalDefaults = signalDefaults, recents = listOf("😍"))
    assertThat(slots.last()).isEqualTo("🤔")
  }

  @Test
  fun `an applied reaction inside the six is not repeated`() {
    val slots = TellomiReactionStrip.reactions(signalDefaults, applied = "😂", signalDefaults = signalDefaults, recents = listOf("😍"))
    assertThat(slots).isEqualTo(signalDefaults + "😍")
  }

  @Test
  fun `a replaced Signal default comes back seventh, then recents, then the fallback`() {
    val custom = listOf("❤️", "👍", "👎", "😂", "😮", "🔥")
    assertThat(TellomiReactionStrip.reactions(custom, null, signalDefaults, listOf("😍")).last()).isEqualTo("😢")
    assertThat(TellomiReactionStrip.reactions(signalDefaults, null, signalDefaults, listOf("❤️", "😍")).last()).isEqualTo("😍")
    assertThat(TellomiReactionStrip.reactions(signalDefaults, null, signalDefaults, emptyList()).last()).isEqualTo("🔥")
  }

  @Test
  fun `matching uses the caller's notion of the same emoji`() {
    val slots = TellomiReactionStrip.reactions(
      saved = signalDefaults,
      applied = "❤",
      signalDefaults = signalDefaults,
      recents = emptyList(),
      isSame = { a, b -> a.trimEnd('️') == b.trimEnd('️') }
    )
    assertThat(slots.last()).isEqualTo("🔥")
  }

  @Test
  fun `items enter staggered by their distance from 80 percent of the strip on the message's side`() {
    // Anchored on the right: the slot nearest 80% goes first, the far end last (up to about 0.2 s).
    val delays = (0 until TellomiReactionStrip.SLOT_COUNT).map { TellomiReactionStrip.itemEnterDelayMs(it, anchorOnLeft = false) }
    val nearest = delays.indexOf(delays.min())
    assertThat(nearest).isEqualTo(6)
    assertThat(delays.first()).isGreaterThan(delays[nearest])
    assertThat(delays.max()).isLessThan(200L)

    val mirrored = (0 until TellomiReactionStrip.SLOT_COUNT).map { TellomiReactionStrip.itemEnterDelayMs(it, anchorOnLeft = true) }
    assertThat(mirrored).isEqualTo(delays.reversed())
  }

  @Test
  fun `the tail grows only after a quarter of the entrance`() {
    assertThat(TellomiReactionStrip.tailProgress(0f)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.tailProgress(0.25f)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.tailProgress(0.625f)).isEqualTo(0.5f)
    assertThat(TellomiReactionStrip.tailProgress(1.2f)).isEqualTo(1f)
  }

  @Test
  fun `the background uncovers from the message's end, a quarter at first`() {
    assertThat(TellomiReactionStrip.revealedSpan(0f, anchorOnLeft = false)).isEqualTo(0.75f..1f)
    assertThat(TellomiReactionStrip.revealedSpan(0.5f, anchorOnLeft = true)).isEqualTo(0f..0.5f)
    assertThat(TellomiReactionStrip.revealedSpan(1.1f, anchorOnLeft = false)).isEqualTo(0f..1f)
  }

  @Test
  fun `the tail hangs 36 dp in from the message's end, the small circle further out`() {
    assertThat(TellomiReactionStrip.bigTailCenterXDp(anchorOnLeft = true)).isEqualTo(36f)
    assertThat(TellomiReactionStrip.smallTailCenterXDp(anchorOnLeft = true)).isEqualTo(28f)
    assertThat(TellomiReactionStrip.bigTailCenterXDp(anchorOnLeft = false)).isEqualTo(268f)
    assertThat(TellomiReactionStrip.smallTailCenterXDp(anchorOnLeft = false)).isEqualTo(276f)
  }

  @Test
  fun `a pull follows the finger at 0 6 until a progress of 1, then at 0 05, capped at 2`() {
    val unit = 42f
    assertThat(TellomiReactionStrip.pullProgress(0f, unit)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.pullProgress(-30f, unit)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.pullProgress(35f, unit)).isCloseTo(0.5f, 1e-4f)
    // 42 / 0.6 = 70 of finger travel brings the progress to 1.
    assertThat(TellomiReactionStrip.pullProgress(70f, unit)).isCloseTo(1f, 1e-4f)
    // Past 1 only 0.05 of the travel counts: another 84 adds 0.1.
    assertThat(TellomiReactionStrip.pullProgress(154f, unit)).isCloseTo(1.1f, 1e-4f)
    assertThat(TellomiReactionStrip.pullProgress(10_000f, unit)).isEqualTo(2f)
  }

  @Test
  fun `letting go opens the panel only past 0 95, about 66 dp of travel`() {
    val unit = TellomiReactionStrip.PULL_UNIT_DP
    assertThat(TellomiReactionStrip.pullProgress(66f, unit)).isLessThan(TellomiReactionStrip.PULL_EXPAND_THRESHOLD)
    assertThat(TellomiReactionStrip.pullProgress(67f, unit)).isGreaterThan(TellomiReactionStrip.PULL_EXPAND_THRESHOLD)
  }

  @Test
  fun `pulling crowds the emoji toward the start, the far ones most, 46 dp at most per unit of progress`() {
    assertThat(TellomiReactionStrip.pullShiftDp(0, 0f)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.pullShiftDp(0, 1f)).isCloseTo(46f * 8f / 270f, 1e-4f)
    assertThat(TellomiReactionStrip.pullShiftDp(6, 1f)).isCloseTo(46f * 224f / 270f, 1e-4f)
    assertThat(TellomiReactionStrip.pullShiftDp(6, 1f)).isGreaterThan(TellomiReactionStrip.pullShiftDp(5, 1f))
    assertThat(TellomiReactionStrip.pullShiftDp(6, 5f)).isCloseTo(2f * 46f * 224f / 270f, 1e-4f)
  }

  @Test
  fun `the strip's emoji are gone a fifth of the way into the panel`() {
    assertThat(TellomiReactionStrip.stripAlphaWhileExpanding(0f)).isEqualTo(1f)
    assertThat(TellomiReactionStrip.stripAlphaWhileExpanding(0.1f)).isCloseTo(0.5f, 1e-4f)
    assertThat(TellomiReactionStrip.stripAlphaWhileExpanding(0.2f)).isEqualTo(0f)
    assertThat(TellomiReactionStrip.stripAlphaWhileExpanding(1f)).isEqualTo(0f)
  }

  @Test
  fun `the panel is 300 dp tall and 96 dp above the strip where there is room`() {
    val (top, height) = TellomiReactionStrip.panelVerticalFrame(stripTop = 400f, top = 0f, bottom = 800f, density = 1f)
    assertThat(height).isEqualTo(300f)
    assertThat(top).isEqualTo(304f)
  }

  @Test
  fun `near the top it stays 16 dp clear of the edge`() {
    val (top, _) = TellomiReactionStrip.panelVerticalFrame(stripTop = 60f, top = 0f, bottom = 800f, density = 2f)
    assertThat(top).isEqualTo(32f)
  }

  @Test
  fun `near the bottom it stays 32 dp above the keyboard or navigation bar`() {
    val (top, height) = TellomiReactionStrip.panelVerticalFrame(stripTop = 700f, top = 0f, bottom = 760f, density = 1f)
    assertThat(top + height).isEqualTo(728f)
  }

  @Test
  fun `in a short window it shrinks to what fits, but not below the minimum`() {
    val (top, height) = TellomiReactionStrip.panelVerticalFrame(stripTop = 150f, top = 0f, bottom = 200f, density = 1f)
    assertThat(height).isEqualTo(TellomiReactionStrip.PANEL_MIN_HEIGHT_DP)
    assertThat(top).isEqualTo(16f)

    val (_, roomy) = TellomiReactionStrip.panelVerticalFrame(stripTop = 150f, top = 0f, bottom = 300f, density = 1f)
    assertThat(roomy).isEqualTo(300f - 16f - 32f)
  }
}
