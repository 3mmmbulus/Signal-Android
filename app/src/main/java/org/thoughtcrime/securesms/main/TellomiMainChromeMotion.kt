/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-23「底栏、悬浮按钮、横幅瞬出瞬没」）：主界面底部那几样进出时的过渡。
 * 进搜索、多选、归档时它们原来是同一帧删掉，列表高度跟着跳。
 *
 * - 悬浮按钮：滑下 [FAB_TRAVEL_DP] dp + 淡出，回来反过来（Telegram `FragmentFloatingButton` 隐藏时也是下移 40 dp + 淡出）；
 *   位移用 [TellomiMotion.Snap]，透明度用 [TellomiMotion.Fade]。
 * - 底栏、Megaphone：高度收起 / 展开 + 淡出 / 淡入。高度变化会带着列表一起动，用临界阻尼（[TellomiMotion.Scroll]），不回弹。
 *
 * 系统「移除动画」时 Compose 自己把动画缩到零。
 */
object TellomiMainChromeMotion {

  const val FAB_TRAVEL_DP = 40

  fun fabEnter(travelPx: Int): EnterTransition {
    return slideInVertically(TellomiMotion.spec(TellomiMotion.Snap, IntOffset.VisibilityThreshold)) { travelPx } +
      fadeIn(TellomiMotion.spec(TellomiMotion.Fade))
  }

  fun fabExit(travelPx: Int): ExitTransition {
    return slideOutVertically(TellomiMotion.spec(TellomiMotion.Snap, IntOffset.VisibilityThreshold)) { travelPx } +
      fadeOut(TellomiMotion.spec(TellomiMotion.Fade))
  }

  /** 底栏：从上沿展开 / 往上沿收起（底栏贴着屏幕底，收起时列表往下长）。 */
  val barEnter: EnterTransition =
    expandVertically(TellomiMotion.spec(TellomiMotion.Scroll, IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
      fadeIn(TellomiMotion.spec(TellomiMotion.Fade))

  val barExit: ExitTransition =
    shrinkVertically(TellomiMotion.spec(TellomiMotion.Scroll, IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top) +
      fadeOut(TellomiMotion.spec(TellomiMotion.Fade))

  /** Megaphone：同底栏，但贴着悬浮按钮下面，从下沿展开。 */
  val megaphoneEnter: EnterTransition =
    expandVertically(TellomiMotion.spec(TellomiMotion.Scroll, IntSize.VisibilityThreshold), expandFrom = Alignment.Bottom) +
      fadeIn(TellomiMotion.spec(TellomiMotion.Fade))

  val megaphoneExit: ExitTransition =
    shrinkVertically(TellomiMotion.spec(TellomiMotion.Scroll, IntSize.VisibilityThreshold), shrinkTowards = Alignment.Bottom) +
      fadeOut(TellomiMotion.spec(TellomiMotion.Fade))
}
