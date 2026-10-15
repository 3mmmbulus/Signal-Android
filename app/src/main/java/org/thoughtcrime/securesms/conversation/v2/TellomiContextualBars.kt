/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.view.View
import android.view.animation.DecelerateInterpolator
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-15「顶栏直接切换」）：进出多选时，顶栏（会话标题栏 ↔「已选 N 条」栏）不再一帧换掉——
 * 换上来的那条在 [DURATION_MS] 里淡入，和底部操作栏的滑入（`slide_fade_from_bottom`，`config_shortAnimTime`）同一时长。
 * 只改透明度，不碰位置和可见性（判断「是不是在多选」照旧看可见性）。「移除动画」档直接显示。
 */
object TellomiContextualBars {

  const val DURATION_MS = 200L

  @JvmStatic
  fun fadeIn(bar: View) {
    bar.animate().cancel()

    if (TellomiMotion.tier(bar.context) == TellomiMotion.Tier.MINIMAL) {
      bar.alpha = 1f
      return
    }

    bar.alpha = 0f
    bar.animate()
      .alpha(1f)
      .setDuration(DURATION_MS)
      .setInterpolator(DecelerateInterpolator())
      .start()
  }
}
