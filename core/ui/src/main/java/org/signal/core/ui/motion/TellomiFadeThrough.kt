/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

/**
 * Tellomi（交互审计 A-23「切 Tab、进归档都是瞬换」）：同一层级里换内容（底栏切 Tab、进出归档）用 Fade Through——
 * 旧内容 [OUT_MS] 先淡出，新内容随后 [IN_MS] 淡入、从 [IN_SCALE] 轻轻放大到原大，不滑、不推，表示「换了一样东西」而不是「往里走一层」。
 * 时长取 M3 的 fade through（约 90 ms 出 / 210 ms 入）；放大幅度很小，和 Telegram iOS 切 Tab（`TabBarController.updateSelectedIndex`
 * 旧页缩约 3 pt、新页 0.1 s 淡入）同一个分量。「移除动画」打开时 Compose 自己把时长缩到零。
 */
object TellomiFadeThrough {

  const val OUT_MS = 90
  const val IN_MS = 210
  const val IN_SCALE = 0.98f

  fun transform(): ContentTransform {
    val enter = fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing)) +
      scaleIn(initialScale = IN_SCALE, animationSpec = tween(IN_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing))
    val exit = fadeOut(tween(OUT_MS, easing = FastOutLinearInEasing))
    return ContentTransform(targetContentEnter = enter, initialContentExit = exit, sizeTransform = null)
  }
}
