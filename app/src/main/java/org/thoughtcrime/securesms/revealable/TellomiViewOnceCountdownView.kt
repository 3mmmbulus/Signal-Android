/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.revealable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import org.signal.core.util.dp

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.3 第 2 条）：计时一次性查看的查看器右上角的倒计时环——满圈开始、顺时针走空、没有数字
 * （照 Telegram 两端 `SecretMediaViewer` / `SecretMediaPreviewController` 右上角的环，只取样子，画法是自己的）。走完调 [onFinished]。
 */
class TellomiViewOnceCountdownView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

  private val strokeWidth = 2.5f.dp
  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = this@TellomiViewOnceCountdownView.strokeWidth
    color = Color.argb(64, 255, 255, 255)
  }
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = this@TellomiViewOnceCountdownView.strokeWidth
    strokeCap = Paint.Cap.ROUND
    color = Color.WHITE
  }
  private val oval = RectF()
  private var animator: ValueAnimator? = null

  /** 还剩的比例（1 = 满圈，0 = 走完）。 */
  var remainingFraction: Float = 1f
    private set

  var durationMillis: Long = 0
    private set

  var onFinished: (() -> Unit)? = null

  /** 开始走 [durationMillis]。 */
  fun start(durationMillis: Long) {
    this.durationMillis = durationMillis
    animator?.cancel()
    animator = ValueAnimator.ofFloat(1f, 0f).apply {
      duration = durationMillis
      interpolator = LinearInterpolator()
      addUpdateListener {
        remainingFraction = it.animatedValue as Float
        invalidate()
      }
      doOnEnd {
        remainingFraction = 0f
        invalidate()
        onFinished?.invoke()
      }
      start()
    }
    contentDescription = resources.getQuantityString(org.thoughtcrime.securesms.R.plurals.TellomiViewOnce__closes_after_seconds, (durationMillis / 1000).toInt(), (durationMillis / 1000).toInt())
  }

  fun cancel() {
    animator?.removeAllListeners()
    animator?.cancel()
    animator = null
  }

  override fun onDetachedFromWindow() {
    cancel()
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val inset = strokeWidth / 2
    oval.set(inset, inset, width - inset, height - inset)
    canvas.drawOval(oval, trackPaint)
    // 从 12 点钟方向起；剩下的那段从「走过的位置」顺时针画到 12 点，走的时候起点顺时针往前追。
    val sweep = 360f * remainingFraction
    canvas.drawArc(oval, -90f + (360f - sweep), sweep, false, ringPaint)
  }
}
