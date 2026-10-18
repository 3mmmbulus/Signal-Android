/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Tellomi（owner 2026-09-24 C-01）：双击别人的消息 = 用回应条上第一个表情快速回应，再双击撤回（照 Telegram 两端）；
 * 双击自己的消息照旧是编辑。iOS 同一组是 `TellomiQuickReactionTests`。
 */
class TellomiQuickReactionTest {

  private val sameEmoji: (String, String) -> Boolean = { a, b -> a == b }

  @Test
  fun `only someone else's message reacts on a double tap, my own still edits`() {
    assertThat(TellomiQuickReaction.reactsOnDoubleTap(isOutgoing = false)).isTrue()
    assertThat(TellomiQuickReaction.reactsOnDoubleTap(isOutgoing = true)).isFalse()
  }

  @Test
  fun `the quick reaction is the first emoji on the bar, in the skin tone I picked`() {
    val emoji = TellomiQuickReaction.quickEmoji(
      saved = listOf("👍", "❤️", "😂"),
      defaults = listOf("❤️", "👍"),
      preferredVariation = { if (it == "👍") "👍🏽" else it }
    )
    assertThat(emoji).isEqualTo("👍🏽")
  }

  @Test
  fun `with nothing saved it is Signal's first default`() {
    assertThat(TellomiQuickReaction.quickEmoji(saved = emptyList(), defaults = listOf("❤️", "👍"), preferredVariation = { it })).isEqualTo("❤️")
    assertThat(TellomiQuickReaction.quickEmoji(saved = emptyList(), defaults = emptyList(), preferredVariation = { it })).isNull()
  }

  @Test
  fun `a double tap adds the quick reaction`() {
    assertThat(TellomiQuickReaction.choose("❤️", myEmoji = null, isSame = sameEmoji)).isEqualTo(TellomiQuickReaction.Choice("❤️", isRemoving = false))
  }

  /** 一人一条消息只有一个回应：回应过别的，换成快速回应。 */
  @Test
  fun `a double tap over another reaction replaces it`() {
    assertThat(TellomiQuickReaction.choose("❤️", myEmoji = "👍", isSame = sameEmoji)).isEqualTo(TellomiQuickReaction.Choice("❤️", isRemoving = false))
  }

  /** 已经是它：撤回，用我存着的那一个原样（变体选择符不同也算同一个），好让撤回对得上。 */
  @Test
  fun `a double tap again takes mine back as it is stored`() {
    val ignoringVariation: (String, String) -> Boolean = { a, b -> a.trimEnd('️') == b.trimEnd('️') }
    assertThat(TellomiQuickReaction.choose("❤️", myEmoji = "❤", isSame = ignoringVariation)).isEqualTo(TellomiQuickReaction.Choice("❤", isRemoving = true))
  }
}
