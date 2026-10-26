/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.Test

/** 审计 4.2：单个表情弹动画决策 + 关键帧形状。 */
class TellomiSingleEmojiAnimationTest {

  @Test
  fun `only single emoji animates`() {
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = null,
        trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
        reduceMotion = false,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 0,
        trigger = TellomiSingleEmojiAnimation.Trigger.TAPPED,
        reduceMotion = false,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 1,
        trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
        reduceMotion = false,
        alreadyPlayedOnAppear = false
      )
    ).isTrue()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 2,
        trigger = TellomiSingleEmojiAnimation.Trigger.TAPPED,
        reduceMotion = false,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 5,
        trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
        reduceMotion = false,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
  }

  @Test
  fun `reduce motion disables`() {
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 1,
        trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
        reduceMotion = true,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 1,
        trigger = TellomiSingleEmojiAnimation.Trigger.TAPPED,
        reduceMotion = true,
        alreadyPlayedOnAppear = false
      )
    ).isFalse()
  }

  @Test
  fun `appear plays once tap always replays`() {
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 1,
        trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
        reduceMotion = false,
        alreadyPlayedOnAppear = true
      )
    ).isFalse()
    assertThat(
      TellomiSingleEmojiAnimation.shouldAnimate(
        jumbomojiCount = 1,
        trigger = TellomiSingleEmojiAnimation.Trigger.TAPPED,
        reduceMotion = false,
        alreadyPlayedOnAppear = true
      )
    ).isTrue()
  }

  @Test
  fun `keyframes shape`() {
    assertThat(TellomiSingleEmojiAnimation.KEYFRAMES.first().relativeTime).isEqualTo(0f)
    assertThat(TellomiSingleEmojiAnimation.KEYFRAMES.last().relativeTime).isEqualTo(1f)
    assertThat(TellomiSingleEmojiAnimation.KEYFRAMES.last().scale).isEqualTo(1f)
    val times = TellomiSingleEmojiAnimation.KEYFRAMES.map { it.relativeTime }
    assertThat(times).isEqualTo(times.sorted())
    assertThat(TellomiSingleEmojiAnimation.DURATION_MS).isGreaterThan(0L)
    assertThat(TellomiSingleEmojiAnimation.DURATION_MS).isLessThanOrEqualTo(600L)
  }
}
