/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-15「顶栏 / 底栏 / 输入栏硬切」）：进出多选时，顶栏（会话标题栏 ↔「已选 N 条」栏）、输入栏、底部操作栏和消息列表
 * 用同一个时长、同一条曲线一起换，不再一帧换掉或者一前一后。
 *
 * 照 Telegram 安卓（只读机制）：`ChatActivityBottomViewsVisibilityController` 一个 240 ms 减速的进度同时驱动操作栏淡入、
 * 输入栏淡出并往下挪 54dp（`ChatActivity.onBottomItemsVisibilityChanged`）。这里用 [DURATION_MS]（底部操作栏原来的 250 ms）。
 * 「移除动画」档直接到终点。
 */
object TellomiContextualBars {

  const val DURATION_MS = 250L

  /** 输入栏收起时往下挪、回来时从下面挪上来的距离（同 Telegram 安卓的 54dp）。 */
  const val INPUT_SLIDE_DP = 54

  /** 底部操作栏进出时滑的距离占它自己高度的比例（同上游 `slide_fade_from_bottom` 的 33%）。 */
  const val BAR_SLIDE_FRACTION = 0.33f

  @JvmStatic
  fun interpolator(): TimeInterpolator = DecelerateInterpolator()

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
      .setInterpolator(interpolator())
      .start()
  }

  /** 进出多选时底下这几样在某一刻的样子。 */
  data class BottomFrame(
    val inputAlpha: Float,
    val inputTranslationY: Float,
    val barAlpha: Float,
    val barTranslationY: Float,
    val listTranslationY: Float
  )

  /**
   * 第 [progress]（0 → 1，已经过了曲线）刻：[entering] 是进多选（输入栏走、操作栏来），否则反过来。
   * 列表在换的那一刻已经排到新位置，先用 [listShiftPx] 放回原来的地方，再跟着进度滑到新位置。
   */
  @JvmStatic
  fun bottomFrame(progress: Float, entering: Boolean, inputSlidePx: Float, barSlidePx: Float, listShiftPx: Float): BottomFrame {
    val barShown = if (entering) progress else 1f - progress
    return BottomFrame(
      inputAlpha = 1f - barShown,
      inputTranslationY = inputSlidePx * barShown,
      barAlpha = barShown,
      barTranslationY = barSlidePx * (1f - barShown),
      listTranslationY = listShiftPx * (1f - progress)
    )
  }
}
