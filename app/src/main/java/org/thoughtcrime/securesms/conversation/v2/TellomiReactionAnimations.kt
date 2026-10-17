/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-06，owner 2026-10-17「和 Telegram 还不一样」）：长按回应条上会动的表情。iOS 同一刀是 Signal-iOS#292。
 *
 * Telegram 两端一样：条出现时每个表情和自己的弹出用同一个延迟，播一遍「出场」动画，播完停在静止帧，不循环
 * （安卓 `ReactionsContainerLayout.ReactionHolderView.play(delay)`：`enterImageView` 播到最后一帧换成 `loopImageView` 的第 0 帧；
 * iOS `ReactionNode.appear`）。手指停在表情上时 Telegram iOS 只放大不播、安卓没有这一步，所以这里也只在出场时播。
 *
 * 素材是 Google 的 Noto Emoji Animation（CC BY 4.0，原样，不是 Telegram 的），在 `assets/tellomi_reactions/`，
 * 署名在「许可证」页（[ACKNOWLEDGMENT_ASSET]）。每个文件是一整圈、首尾同姿态，带一个 `rest` 标记（Noto 设计的静止姿态）：
 * 从 rest 帧播一整圈回到 rest 帧，停住不跳。
 */
object TellomiReactionAnimations {

  const val ASSET_DIRECTORY = "tellomi_reactions"
  const val ACKNOWLEDGMENT_ASSET = "acknowledgments/noto-emoji-animation.md"
  const val REST_MARKER = "rest"

  /** 表情（去掉 U+FE0F）→ 文件名（码位）。默认条上会出现的 10 个：Signal 默认 6 个和补第 7 个用的 🙏🔥🎉👏。 */
  private val assetNames = mapOf(
    "👍" to "1f44d",
    "❤" to "2764_fe0f",
    "👎" to "1f44e",
    "😂" to "1f602",
    "😮" to "1f62e",
    "😢" to "1f622",
    "🙏" to "1f64f",
    "🔥" to "1f525",
    "🎉" to "1f389",
    "👏" to "1f44f"
  )

  val animatedEmoji: Set<String>
    get() = assetNames.keys

  /** [emoji] 的动画在 assets 里的路径；带肤色的、别的表情是 null（照旧显示静态字形）。 */
  @JvmStatic
  fun assetPath(emoji: String): String? {
    return assetNames[emoji.replace("️", "")]?.let { "$ASSET_DIRECTORY/$it.json" }
  }

  /** 只在满档播（Telegram 关了动画、低端机直接显示静态）；简化档、最少档是静态字形。 */
  @JvmStatic
  fun isPlaybackAllowed(tier: TellomiMotion.Tier): Boolean = TellomiMotion.allowsFlourish(tier)

  /**
   * 从 rest 帧播一整圈回到 rest 帧要播的段（帧号，闭区间）。[lastFrame] 是最后一帧有画面的帧（`endFrame - 1`）。
   * rest 在开头：一段；否则两段，rest → 尾，头 → rest。
   */
  @JvmStatic
  fun appearSegments(restFrame: Float, startFrame: Float, lastFrame: Float): List<ClosedFloatingPointRange<Float>> {
    return if (restFrame <= startFrame || restFrame >= lastFrame) {
      listOf(startFrame..lastFrame)
    } else {
      listOf(restFrame..lastFrame, startFrame..restFrame)
    }
  }
}
