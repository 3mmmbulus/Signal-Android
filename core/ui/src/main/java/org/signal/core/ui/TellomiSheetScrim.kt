/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui

/**
 * Tellomi（交互审计 A-29）：底部弹层的遮罩跟着拖动变淡。
 *
 * `BottomSheetCallback.onSlide` 的偏移：收起位置是 0，展开是 1，完全藏起来是 −1。
 * 收起到展开之间遮罩不变；从收起往下拖时按离「藏起来」还有多远变淡，到 −1 时没有遮罩。
 * Telegram 的 `BottomSheet` 也是关掉时遮罩跟着弹层一起淡出。
 */
object TellomiSheetScrim {

  @JvmStatic
  fun dimFraction(slideOffset: Float): Float {
    if (slideOffset >= 0f) return 1f
    return (1f + slideOffset).coerceIn(0f, 1f)
  }
}
