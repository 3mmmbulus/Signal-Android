/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Tellomi（交互审计 A-06 / A-07，owner 2026-10-17「和 Telegram 还不一样」）：长按消息时的回应条，照 Telegram 安卓
 * `ReactionsContainerLayout` 的样子和出场（只取数，不搬代码）。iOS 同一刀是 Signal-iOS#276。
 *
 * - 7 个表情 + 1 个展开键；条目 36 dp，两头各 8 dp，高 44 dp 的胶囊。
 * - 「思考泡泡」尾巴：大圆半径 8 dp、圆心骑在条下沿、离消息那一头 36 dp；小圆半径 4 dp，在大圆外侧斜下方。
 * - 出场：0.25 s、Overshoot(0.5)，条从消息那一头缩放展开（底板从那一头往另一头揭开，先露出 25%）；
 *   表情各自 0.3 s 从 0 弹到原大，延迟 200 ms × |表情中心在条上的位置 − 锚点|，锚点是消息那一头的 80%（另一头是 20%）；
 *   尾巴两个圆在进度过了 25% 之后跟着长出来。
 */
object TellomiReactionStrip {

  /** 7 个表情 + 展开键。 */
  const val SLOT_COUNT = 8
  const val REACTION_COUNT = SLOT_COUNT - 1

  const val ITEM_SIZE_DP = 36f
  const val SIDE_PADDING_DP = 8f
  const val HEIGHT_DP = 44f
  const val EXPAND_BUTTON_SIZE_DP = 30f

  const val BIG_TAIL_RADIUS_DP = 8f
  const val SMALL_TAIL_RADIUS_DP = 4f

  /** 大圆圆心离条在消息那一头的端点多远（Telegram `bigCircleOffset`）。 */
  const val TAIL_OFFSET_DP = 36f

  const val ENTER_DURATION_MS = 250
  const val ENTER_OVERSHOOT_TENSION = 0.5f

  /** 底板最先露出来的那一截，尾巴也是过了这个进度才开始长（Telegram `CLIP_PROGRESS`）。 */
  const val CLIP_PROGRESS = 0.25f

  const val ITEM_ENTER_DURATION_MS = 300
  const val ITEM_ENTER_OVERSHOOT_TENSION = 2f
  private const val ITEM_STAGGER_MS = 200f

  /** 锚点在条上的位置：消息那一头的 80%。 */
  private const val ANCHOR_FRACTION = 0.8f

  /** 显示时补第 7 个的固定候补：Telegram 默认回应里 Signal 默认没有的几个。 */
  val FALLBACK = listOf("🔥", "🙏", "🎉", "👏")

  /** 条的总宽：8 格 × 36 + 两头 8。 */
  val WIDTH_DP: Float = SLOT_COUNT * ITEM_SIZE_DP + SIDE_PADDING_DP * 2

  /**
   * 条上放哪 7 个表情。用户选的 6 个会同步到别的设备和备份，**不改那一组**，只在显示时补第 7 个：
   * 我这次已经回应、但不在 6 个里的 → Signal 默认里被换掉的 → 最近用过的 → 固定候补。和 iOS `TellomiReactionBarSlots` 一样。
   *
   * [isSame] 判断两个表情算不算同一个（肤色、变体选择符）。
   */
  @JvmStatic
  fun reactions(
    saved: List<String>,
    applied: String?,
    signalDefaults: List<String>,
    recents: List<String>,
    fallback: List<String> = FALLBACK,
    isSame: (String, String) -> Boolean = { a, b -> a == b }
  ): List<String> {
    val slots = saved.take(REACTION_COUNT).toMutableList()
    val candidates = listOfNotNull(applied) + signalDefaults + recents + fallback
    for (candidate in candidates) {
      if (slots.size >= REACTION_COUNT) {
        break
      }
      if (slots.none { isSame(it, candidate) }) {
        slots += candidate
      }
    }
    return slots
  }

  /** 第 [index] 格（0 起）在条上的中心位置，0–1，从左往右。 */
  @JvmStatic
  fun slotCenterFraction(index: Int): Float {
    val width = WIDTH_DP
    return (SIDE_PADDING_DP + ITEM_SIZE_DP * (index + 0.5f)) / width
  }

  /**
   * 第 [index] 格出场的延迟（毫秒）。[anchorOnLeft]：消息在左边、条从左头长出来。
   * [index] 按屏幕从左往右数（RTL 时调用方先换算）。
   */
  @JvmStatic
  fun itemEnterDelayMs(index: Int, anchorOnLeft: Boolean): Long {
    val anchor = if (anchorOnLeft) 1f - ANCHOR_FRACTION else ANCHOR_FRACTION
    return (ITEM_STAGGER_MS * abs(slotCenterFraction(index) - anchor)).roundToLong()
  }

  /** 尾巴圆的大小比例：出场进度过了 [CLIP_PROGRESS] 才开始长，到 1 长满。 */
  @JvmStatic
  fun tailProgress(enterProgress: Float): Float {
    val clamped = max(CLIP_PROGRESS, min(enterProgress, 1f))
    return (clamped - CLIP_PROGRESS) / (1f - CLIP_PROGRESS)
  }

  /**
   * 底板在出场进度 [progress] 时露出来的左右边（条宽的比例，0–1）：从锚点那一头往另一头揭开，最少露出 [CLIP_PROGRESS]。
   */
  @JvmStatic
  fun revealedSpan(progress: Float, anchorOnLeft: Boolean): ClosedFloatingPointRange<Float> {
    val shown = max(CLIP_PROGRESS, progress).coerceAtMost(1f)
    return if (anchorOnLeft) 0f..shown else (1f - shown)..1f
  }

  /** 出场时整条缩放的支点（条宽的比例）：消息那一头往里 12.5%。 */
  @JvmStatic
  fun pivotFraction(anchorOnLeft: Boolean): Float = if (anchorOnLeft) 0.125f else 0.875f

  /** 大圆圆心的横坐标（条内，dp）。 */
  @JvmStatic
  fun bigTailCenterXDp(anchorOnLeft: Boolean): Float = if (anchorOnLeft) TAIL_OFFSET_DP else WIDTH_DP - TAIL_OFFSET_DP

  /** 小圆圆心的横坐标（条内，dp）：在大圆朝外那一侧。 */
  @JvmStatic
  fun smallTailCenterXDp(anchorOnLeft: Boolean): Float {
    val big = bigTailCenterXDp(anchorOnLeft)
    return if (anchorOnLeft) big - BIG_TAIL_RADIUS_DP else big + BIG_TAIL_RADIUS_DP
  }

  /** 小圆圆心离条下沿多远（dp）：挂在大圆下面再隔 2 dp。 */
  const val SMALL_TAIL_DROP_DP = BIG_TAIL_RADIUS_DP + 2f + SMALL_TAIL_RADIUS_DP
}
