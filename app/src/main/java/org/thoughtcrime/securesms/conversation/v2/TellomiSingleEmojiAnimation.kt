/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * Tellomi（审计 4.2）：只发一个表情时放大并播短「弹一下」动画的纯逻辑。
 * 对齐 iOS `TellomiSingleEmojiAnimation`（#380；行为参照 Telegram，代码自写）。
 *
 * 未接界面——接线见 [TellomiSingleEmojiAnimationPlayer]。
 */
object TellomiSingleEmojiAnimation {

  data class Keyframe(
    /** 相对时间，0…1。 */
    val relativeTime: Float,
    /** 相对最终大小的缩放。 */
    val scale: Float
  )

  /** 整段弹动画时长（毫秒）。 */
  const val DURATION_MS = 450L

  /** 缩放关键帧：从 0.6 放大到 1.15 再回落到 1.0。 */
  val KEYFRAMES: List<Keyframe> = listOf(
    Keyframe(0.0f, 0.6f),
    Keyframe(0.55f, 1.15f),
    Keyframe(0.8f, 0.95f),
    Keyframe(1.0f, 1.0f)
  )

  enum class Trigger {
    /** 消息首次出现在屏幕上（刚发出或刚收到）。 */
    APPEARED,

    /** 用户点了这个表情气泡。 */
    TAPPED
  }

  /**
   * @param jumbomojiCount 全是表情且数量在 jumbo 上限内时的个数；否则 null。
   * @param trigger 为何要播。
   * @param reduceMotion 系统「移除动画」/ 动画时长为 0。
   * @param alreadyPlayedOnAppear 这条消息的出现动画是否已经播过。
   */
  fun shouldAnimate(
    jumbomojiCount: Int?,
    trigger: Trigger,
    reduceMotion: Boolean,
    alreadyPlayedOnAppear: Boolean
  ): Boolean {
    if (jumbomojiCount != 1) return false
    if (reduceMotion) return false
    return when (trigger) {
      Trigger.APPEARED -> !alreadyPlayedOnAppear
      Trigger.TAPPED -> true
    }
  }
}
