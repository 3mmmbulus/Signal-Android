/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import kotlin.math.max
import kotlin.math.min

/**
 * Tellomi（tellomi/tellomi#1109，ADR-0058 §2）：滑动回复改成手指从右往左滑（RTL 镜像），手感参数单位都是 dp。
 *
 * - 阈值照 Telegram：对方的消息 45、自己的消息 60（`ChatMessageBubbleItemNode.swipeToReplyGesture`）；
 * - 阈值内气泡 1:1 跟手，过阈值后橡皮筋（range 100、系数 0.4），整体最多 180；
 * - 松手用投射落点判定（交互审计 A-10，标准第四节）：位移 + 松手速度 × [PROJECTION_SECONDS] 过阈值就回复，快速一甩也算；
 * - 不在系统返回手势的左右边缘区起手（标准第四节「边缘让给系统」）。
 */
object TellomiSwipeToReply {
  const val INCOMING_TRIGGER_DP = 45f
  const val OUTGOING_TRIGGER_DP = 60f
  const val RUBBER_BAND_RANGE_DP = 100f
  const val RUBBER_BAND_FACTOR = 0.4f
  const val MAX_OFFSET_DP = 180f

  /**
   * 投射落点里速度乘的时间：0.1 s，相当于 iOS `UIScrollView.DecelerationRate.fast`（0.99）的投射距离。
   * 1000 dp/s 的一甩多算 100 dp；200 dp/s 的轻微晃动只多算 20 dp，到不了 45 dp 的阈值。
   */
  const val PROJECTION_SECONDS = 0.1f

  @JvmStatic
  fun triggerDp(isOutgoing: Boolean): Float = if (isOutgoing) OUTGOING_TRIGGER_DP else INCOMING_TRIGGER_DP

  /**
   * 手指沿回复方向走了 [fingerOffset] → 气泡跟着走多少（同一单位）。
   * 不超过 [trigger] 时 1:1；超过后橡皮筋，越拉越慢，最多 [maxOffset]。
   */
  @JvmStatic
  fun bubbleOffset(fingerOffset: Float, trigger: Float, rubberBandRange: Float, maxOffset: Float): Float {
    if (fingerOffset <= trigger) {
      return max(fingerOffset, 0f)
    }
    val overflow = fingerOffset - trigger
    val band = (1f - 1f / (overflow * RUBBER_BAND_FACTOR / rubberBandRange + 1f)) * rubberBandRange
    return min(trigger + band, maxOffset)
  }

  /**
   * 手指在 [fingerOffset] 处以 [fingerVelocity]（沿回复方向为正）移动时，气泡的速度：[bubbleOffset] 在这一点的斜率 × 手指速度。
   * 阈值内 1:1；橡皮筋段按斜率变慢；到了上限或还没出发时气泡不动。松手回位的弹簧从这个速度起步。
   */
  @JvmStatic
  fun bubbleVelocity(fingerOffset: Float, fingerVelocity: Float, trigger: Float, rubberBandRange: Float, maxOffset: Float): Float {
    if (fingerOffset < 0f) {
      return 0f
    }
    if (fingerOffset <= trigger) {
      return fingerVelocity
    }
    if (bubbleOffset(fingerOffset, trigger, rubberBandRange, maxOffset) >= maxOffset) {
      return 0f
    }
    val d = (fingerOffset - trigger) * RUBBER_BAND_FACTOR / rubberBandRange + 1f
    return fingerVelocity * RUBBER_BAND_FACTOR / (d * d)
  }

  /** 松手时手指在 [fingerOffset]、速度 [fingerVelocity]（沿回复方向为正，单位 / 秒）：投射落点过 [trigger] 就回复。 */
  @JvmStatic
  fun shouldReply(fingerOffset: Float, fingerVelocity: Float, trigger: Float): Boolean {
    return fingerOffset + fingerVelocity * PROJECTION_SECONDS > trigger
  }

  /** 按下点（窗口坐标）落在系统返回手势的左 / 右边缘区里。没有手势导航时两边宽度都是 0，永远不算。 */
  @JvmStatic
  fun startsInGestureEdge(xInWindow: Float, windowWidth: Int, leftEdge: Int, rightEdge: Int): Boolean {
    return (leftEdge > 0 && xInWindow < leftEdge) || (rightEdge > 0 && xInWindow > windowWidth - rightEdge)
  }

  /** 回复方向在 x 轴上的正负号：从左往右排版时手指往左（负），从右往左排版时往右（正）。 */
  @JvmStatic
  fun replySign(isRtl: Boolean): Float = if (isRtl) 1f else -1f
}
