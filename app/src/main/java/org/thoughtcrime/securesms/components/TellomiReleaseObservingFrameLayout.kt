/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Tellomi（tellomi/tellomi#1263，spec R-2）：只看不拦——手指抬起或手势被取消时告诉一声（查看器里按住画面放实况，松手就停）。
 * 触摸照常交给里面的视图（缩放、翻页不受影响）。
 */
class TellomiReleaseObservingFrameLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  var onRelease: (() -> Unit)? = null

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
      onRelease?.invoke()
    }
    return super.dispatchTouchEvent(event)
  }
}
