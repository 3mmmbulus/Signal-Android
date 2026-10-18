/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * Tellomi：双击别人的消息 = 快速回应（owner 2026-09-24 C-01，`docs/product/UX_DIRECTION.md`「体验研究第一轮」③；
 * 10-18 真机「双击对方消息也无法回复表情」）。双击自己的消息照旧是上游的编辑（C-01「保留 Signal」）。
 * iOS 同一刀在 `ConversationViewController+TellomiQuickReaction.swift`。
 *
 * 怎么回应照 Telegram 两端（只读源码，没搬代码）：
 * - Android `ChatActivity.onDoubleTap` → `MediaDataController.getDoubleTapReaction()`（没设过就是第一个回应）→
 *   `selectReaction(…, fromDoubleTap = true)`：`MessageObject.selectReaction` 已经选了就撤回；发出去以后在回应按钮上播
 *   `ReactionsEffectOverlay.SHORT_ANIMATION`（不飞）。
 * - iOS `ChatMessageBubbleItemNode` 双击 → `updateMessageReaction(message, .default)`：已经是这个回应就撤回，否则加上，
 *   等回应加到消息上再在回应按钮上播（`StandaloneReactionAnimation`）。
 *
 * 用的表情是回应条上第一个（用户的第一个回应，默认 ❤️，带上用户选的肤色）；已经是它就撤回。不飞，回应画出来时在胶囊上
 * 爆开一下（[org.thoughtcrime.securesms.reactions.TellomiReactionBurst.playWhenShown]）。
 *
 * 触感：Telegram 两端加回应时都轻触一下，撤回不震——安卓在回应按钮上的效果开始时 `KEYBOARD_TAP`
 * （`ReactionsEffectOverlay.startShortAnimation`），iOS 双击时 `selectionChanged()`。Tellomi 两端都在双击时给一下
 * SELECTION_TICK / selection（和从条上选、落地那一下同一种）。
 */
object TellomiQuickReaction {

  /** 等回应在胶囊里画出来最多这么久；过了就不爆了（写失败、消息被滚走）。和 iOS 一样。 */
  const val BURST_WAIT_MS = 1500L

  /** 双击要做的：用 [emoji] 回应，或者撤回我的回应（[emoji] 就是我那一个，原样，好让撤回对得上）。 */
  data class Choice(val emoji: String, val isRemoving: Boolean)

  /** 双击这条是不是快速回应：只有别人的消息。自己的消息双击照旧是编辑。 */
  @JvmStatic
  fun reactsOnDoubleTap(isOutgoing: Boolean): Boolean = !isOutgoing

  /** 条上第一个表情：用户的第一个回应（没有就是 Signal 默认的第一个），按用户选的肤色显示，和回应条一样。 */
  @JvmStatic
  fun quickEmoji(saved: List<String>, defaults: List<String>, preferredVariation: (String) -> String): String? {
    return (saved.firstOrNull() ?: defaults.firstOrNull())?.let(preferredVariation)
  }

  /** 双击要回应什么：[quickEmoji]；我已经用它回应过（[isSame]：肤色、变体选择符不同也算同一个）就撤回。 */
  @JvmStatic
  fun choose(quickEmoji: String, myEmoji: String?, isSame: (String, String) -> Boolean): Choice {
    return if (myEmoji != null && isSame(myEmoji, quickEmoji)) {
      Choice(emoji = myEmoji, isRemoving = true)
    } else {
      Choice(emoji = quickEmoji, isRemoving = false)
    }
  }
}
