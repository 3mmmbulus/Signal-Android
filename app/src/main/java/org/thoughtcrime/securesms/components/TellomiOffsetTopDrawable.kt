/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

/**
 * Tellomi（交互审计 A-11）：包一层，让底图的上沿临时移动 [topOffset] 像素（正数往下让出一条、负数往上多画一条），底边不动。
 * 输入区高度动画时，输入区的底色和输入气泡的底图跟着动画走；只改画法，不改 View 的位置，不怕动画途中的重新排版。
 * [topOffset] 为 0 时和原图一样。
 */
class TellomiOffsetTopDrawable(drawable: Drawable) : DrawableWrapper(drawable) {

  var topOffset: Int = 0
    set(value) {
      if (field != value) {
        field = value
        applyBounds(bounds)
        invalidateSelf()
      }
    }

  override fun onBoundsChange(bounds: Rect) {
    applyBounds(bounds)
  }

  private fun applyBounds(bounds: Rect) {
    drawable?.setBounds(bounds.left, bounds.top + topOffset, bounds.right, bounds.bottom)
  }
}
