/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Tellomi（交互审计 A-20 Android 半边）：查看器外面一层，接住竖向拖动做拖拽关闭（判据在 [TellomiDragDismiss]）。
 *
 * 在 [dispatchTouchEvent] 里看事件而不是 onInterceptTouchEvent：GIF 用的 PhotoView 按下就 requestDisallowInterceptTouchEvent(true)，
 * 拦截那条路会被它挡掉。起手条件：单指、竖向明显大于横向、[Callback.canStartDragDismiss] 说可以（没放大、按下点不在顶栏 / 说明上）；
 * 一旦起手就给孩子们发 ACTION_CANCEL，之后这一轮手势都归拖拽。
 */
class TellomiDragDismissLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  interface Callback {
    /** 按下点 ([x], [y]，本 View 坐标) 能不能开始拖拽关闭。 */
    fun canStartDragDismiss(x: Float, y: Float): Boolean

    fun onDragDismissStarted()

    /** 拖动中，[offset] 是竖向位移（px，向下为正）。 */
    fun onDragDismissMoved(offset: Float)

    /** 松手（[velocity] px/s，向下为正）；被取消时 velocity 为 0。 */
    fun onDragDismissReleased(offset: Float, velocity: Float)
  }

  var callback: Callback? = null

  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
  private var velocityTracker: VelocityTracker? = null
  private var downX = 0f
  private var downY = 0f
  private var startY = 0f
  private var dragging = false
  private var eligible = false

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (dragging) {
      handleDrag(ev)
      return true
    }

    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
        eligible = callback?.canStartDragDismiss(ev.x, ev.y) == true
        velocityTracker?.recycle()
        velocityTracker = if (eligible) VelocityTracker.obtain() else null
        velocityTracker?.addMovement(ev)
      }
      MotionEvent.ACTION_POINTER_DOWN -> eligible = false
      MotionEvent.ACTION_MOVE -> {
        velocityTracker?.addMovement(ev)
        if (eligible && ev.pointerCount == 1 && TellomiDragDismiss.isVerticalStart(ev.x - downX, ev.y - downY, touchSlop)) {
          // 再问一次：起手前那几毫秒里图可能被双击放大了
          if (callback?.canStartDragDismiss(downX, downY) == true) {
            startDrag(ev)
            return true
          }
          eligible = false
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        velocityTracker?.recycle()
        velocityTracker = null
        eligible = false
      }
    }
    return super.dispatchTouchEvent(ev)
  }

  private fun startDrag(ev: MotionEvent) {
    dragging = true
    // 从越过 touchSlop 的那一点接着走，不让媒体一下跳一个 touchSlop
    startY = ev.y - (if (ev.y > downY) touchSlop else -touchSlop)
    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
    super.dispatchTouchEvent(cancel)
    cancel.recycle()
    parent?.requestDisallowInterceptTouchEvent(true)
    callback?.onDragDismissStarted()
    callback?.onDragDismissMoved(ev.y - startY)
  }

  private fun handleDrag(ev: MotionEvent) {
    velocityTracker?.addMovement(ev)
    when (ev.actionMasked) {
      MotionEvent.ACTION_MOVE -> callback?.onDragDismissMoved(ev.y - startY)
      MotionEvent.ACTION_UP -> {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000)
        finishDrag(ev.y - startY, tracker?.yVelocity ?: 0f)
      }
      MotionEvent.ACTION_CANCEL -> finishDrag(ev.y - startY, 0f)
    }
  }

  private fun finishDrag(offset: Float, velocity: Float) {
    dragging = false
    eligible = false
    velocityTracker?.recycle()
    velocityTracker = null
    callback?.onDragDismissReleased(offset, velocity)
  }
}
