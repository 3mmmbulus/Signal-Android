/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import kotlin.math.abs

/**
 * Tellomi（交互审计 A-20 Android 半边，`docs/product/specs/interaction-motion-audit.md`）：聊天里的图片 / 视频查看器拖拽关闭的判据。
 *
 * 松手时按「投射落点」判断（审计 A-20：「松手按投射落点判断」；iOS 半边 Signal-iOS#75 同理）：落点 = 位移 + 速度 × [PROJECTION_SECONDS]，
 * 落点离原位 ≥ [DISMISS_DISTANCE_DP] 就关，否则弹回。这样拖远了又甩回来会弹回，从原位一甩（≥ 约 700 dp/s）就关——和选图查看器
 * （Signal-Android#163，V-2：速度 ≥ 700 或位移 ≥ 128）的手感对得上。上下两个方向都能关（Telegram 两端都是）。
 *
 * 纯计算，单位由调用方换好（px 与 px/s 配 [density]）。
 */
object TellomiDragDismiss {

  const val DISMISS_DISTANCE_DP = 128f

  /** 700 dp/s × 0.18 s ≈ 126 dp：从原位一甩 700 dp/s 正好够关。 */
  const val PROJECTION_SECONDS = 0.18f

  /** 背景、按钮淡到底、媒体缩到 [MIN_SCALE] 的距离。 */
  const val FADE_DISTANCE_DP = 256f

  const val MIN_SCALE = 0.85f

  /** 起手：竖向位移超过 touchSlop，且竖向是横向的两倍以上（Telegram 安卓 `PhotoViewer` 竖向起手也要明显大于横向）。 */
  fun isVerticalStart(dx: Float, dy: Float, touchSlop: Float): Boolean {
    return abs(dy) > touchSlop && abs(dy) > abs(dx) * 2
  }

  fun projectedOffset(offsetPx: Float, velocityPxPerSecond: Float): Float {
    return offsetPx + velocityPxPerSecond * PROJECTION_SECONDS
  }

  fun shouldDismiss(offsetPx: Float, velocityPxPerSecond: Float, density: Float): Boolean {
    return abs(projectedOffset(offsetPx, velocityPxPerSecond)) >= DISMISS_DISTANCE_DP * density
  }

  /** 关的话往哪边飞：按投射落点的方向（落点正好是 0 时往下）。 */
  fun dismissDirection(offsetPx: Float, velocityPxPerSecond: Float): Int {
    return if (projectedOffset(offsetPx, velocityPxPerSecond) < 0f) -1 else 1
  }

  /** 0 = 没拖，1 = 拖满 [FADE_DISTANCE_DP]。 */
  fun progress(offsetPx: Float, density: Float): Float {
    return (abs(offsetPx) / (FADE_DISTANCE_DP * density)).coerceIn(0f, 1f)
  }

  fun scale(progress: Float): Float {
    return 1f - (1f - MIN_SCALE) * progress.coerceIn(0f, 1f)
  }
}
