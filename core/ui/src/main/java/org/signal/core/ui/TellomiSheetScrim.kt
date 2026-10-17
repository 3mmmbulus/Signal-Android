/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui

/**
 * Tellomi（交互审计 A-29 / Q-07）：底部弹层的遮罩什么时候变淡。
 *
 * 照 Telegram 两端：手指拖着的时候遮罩不变；松手后往「关掉」滑下去时，从松手那一刻起跟着弹层淡到 0。
 * - Android `ActionBar/BottomSheet.java`：拖动中只 `invalidate`，关掉时 `dimAnimator` 1 → 0、320 ms，和弹层一起滑走；
 * - iOS `SheetComponent.swift`：`dimView` 只在出现、消失时各淡 0.3 s，不跟手。
 * 2026-10-15 第一版（Signal-Android#302）是拖到收起以下就跟手变淡，两端 Telegram 都不这样；owner 2026-10-14 定聊天类交互
 * 两端 Telegram 都有才做，所以 2026-10-18 改成这样。
 *
 * `BottomSheetCallback.onSlide` 的偏移：收起位置是 0，展开是 1，完全藏起来是 −1。
 */
object TellomiSheetScrim {

  /**
   * [settleStartOffset]：松手开始收尾那一刻的偏移；还在拖、或者停住了时为 null（遮罩满）。
   * 收尾往下走就从那里到「藏起来」线性淡到 0；往回弹（偏移比松手时大）保持满。
   */
  @JvmStatic
  fun dimFraction(slideOffset: Float, settleStartOffset: Float?): Float {
    if (settleStartOffset == null || slideOffset >= 0f) return 1f
    val span = 1f + settleStartOffset.coerceIn(-1f, 0f)
    if (span <= 0f) return 0f
    return ((1f + slideOffset) / span).coerceIn(0f, 1f)
  }
}
