/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

/**
 * Tellomi（交互审计 A-12「录音：取消和锁定没有进度反馈」）：按住录音时手指走到哪了。
 *
 * - 取消进度：从按下点往取消线（上游：录音栏宽度的一半处，RTL 是 60% 处）走了多少，0–1；
 *   录音按钮和「滑动取消」提示跟着变淡、按钮跟着变小（Telegram 也是按左滑进度缩小录音圆）。
 * - 锁定进度：往上走了锁定距离（`recording_voice_lock_target`）的多少，0–1；锁图标跟着收小（2026-10-18 改：原来是放大到 1.3，
 *   和 Telegram 两端都相反——Android `ChatActivityEnterView` 的锁 `36 dp + 14 dp × moveProgress`，往上滑从 50 dp 收到 36 dp；
 *   iOS `TGModernConversationInputMicButton` 的锁面板随上滑收拢。iOS 这边上游的锁本来就把箭头收拢到锁上）。
 */
object TellomiRecordingGesture {

  /** 取消进度到 1 时：录音按钮淡到这个透明度、缩到这个比例；提示淡到 [HINT_MIN_ALPHA]。 */
  const val BUTTON_MIN_ALPHA = 0.5f
  const val BUTTON_MIN_SCALE = 0.8f
  const val HINT_MIN_ALPHA = 0.3f

  /** 锁定进度到 1 时锁图标收到这个比例（Telegram Android 36 / 50 dp）。 */
  const val LOCK_MIN_SCALE = 0.72f

  /** 两个方向通用：[startX] 按下点，[x] 现在，[cancelX] 取消线（都在同一坐标系）。 */
  @JvmStatic
  fun cancelProgress(startX: Float, x: Float, cancelX: Float): Float {
    val distance = startX - cancelX
    val moved = startX - x
    if (distance == 0f || moved == 0f) return 0f
    return (moved / distance).coerceIn(0f, 1f)
  }

  /** [offsetY] 往上为负，[lockOffsetY] 是锁定距离（负数）。 */
  @JvmStatic
  fun lockProgress(offsetY: Float, lockOffsetY: Float): Float {
    if (lockOffsetY == 0f || offsetY == 0f) return 0f
    return (offsetY / lockOffsetY).coerceIn(0f, 1f)
  }

  /** 锁图标随锁定进度收小：1 → [LOCK_MIN_SCALE]。 */
  @JvmStatic
  fun lockScale(lockProgress: Float): Float = lerp(1f, LOCK_MIN_SCALE, lockProgress.coerceIn(0f, 1f))

  @JvmStatic
  fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress
}
