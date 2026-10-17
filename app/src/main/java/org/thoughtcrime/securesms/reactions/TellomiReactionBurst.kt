/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tellomi（交互审计 A-07，owner 2026-10-17）：回应快落到胶囊上时的「爆开」。iOS 同一刀是 Signal-iOS#280，数一样。
 *
 * Telegram 两端在这一刻都在胶囊周围播一段效果（安卓 `ReactionsEffectOverlay`：飞到 70% 起效果）。那是 Telegram 自己的
 * Lottie 文件，不能用；这里是 Tellomi 自己画的：一圈细环从表情的 0.6 倍放到 1.8 倍并淡掉（0.35 s），十颗小号的同一个表情
 * 向外散开、往上飘、缩小淡掉，0.7 s 播完。
 *
 * - 粒子 / 「烟花」原本不做（PRODUCT_REQUIREMENTS TR-PLAT-16），回应爆开是 ADR-0058 写明的唯一例外。
 * - 只在完整档播（交互与动效标准第七节：简化档关点缀类动效；回应飞入本来也只在完整档飞）。
 */
object TellomiReactionBurst {

  data class Particle(
    /** 朝哪个方向飞（弧度，0 = 向右，往下为正）。 */
    val angle: Float,
    /** 飞多远（px）。 */
    val distance: Float,
    /** 粒子大小相对落点表情的比例。 */
    val scale: Float,
    /** 晚多久出发（秒）。 */
    val delay: Float
  )

  const val PARTICLE_COUNT = 10
  const val DURATION_MS = 700L
  const val RING_DURATION_MS = 350L
  /** 粒子飞出的距离 = 表情大小 × 这个数（再按粒子错开 ±20%）。 */
  const val REACH_FACTOR = 2.2f
  /** 散开的同时整体往上飘这么多（dp）。 */
  const val FLOAT_UP_DP = 14f

  /** 回应飞到全程的这么多时开始爆开（Telegram 安卓 70%）。 */
  const val TRIGGER_PROGRESS = 0.7f

  /** 飞了 [startDistance] 里的多少、还剩 [remainingDistance] 时，该不该开始爆开（走过 [TRIGGER_PROGRESS]）。 */
  @JvmStatic
  fun shouldTrigger(startDistance: Float, remainingDistance: Float): Boolean {
    if (startDistance <= 0f) return true
    return 1f - remainingDistance / startDistance >= TRIGGER_PROGRESS
  }

  /** 粒子的方向和远近：黄金角错开，看起来随机但每次一样（和 iOS 同一个公式）。 */
  @JvmStatic
  fun particles(count: Int = PARTICLE_COUNT, emojiSize: Float): List<Particle> {
    val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
    return (0 until count).map { index ->
      val wobble = ((index * 7) % 5) / 4f
      Particle(
        angle = index * goldenAngle,
        distance = emojiSize * REACH_FACTOR * (0.8f + 0.4f * wobble),
        scale = 0.35f + 0.15f * wobble,
        delay = (index % 3) * 0.02f
      )
    }
  }

  /** 在 [host] 的 overlay 上、[centerX] / [centerY]（host 坐标）处播一次。[emoji] 是一份新的表情图（不能和飞行的那份共用）。 */
  @JvmStatic
  fun play(host: View, emoji: Drawable, centerX: Float, centerY: Float, emojiSize: Float, ringColor: Int) {
    val drawable = BurstDrawable(
      emoji = emoji,
      centerX = centerX,
      centerY = centerY,
      emojiSize = emojiSize,
      density = host.resources.displayMetrics.density,
      ringColor = ringColor
    )
    drawable.setBounds(0, 0, host.width, host.height)
    host.overlay.add(drawable)
    ValueAnimator.ofFloat(0f, 1f).apply {
      duration = DURATION_MS
      interpolator = LinearInterpolator()
      addUpdateListener {
        drawable.time = it.animatedValue as Float
        host.invalidate()
      }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          host.overlay.remove(drawable)
        }

        override fun onAnimationCancel(animation: Animator) {
          host.overlay.remove(drawable)
        }
      })
      start()
    }
  }

  /** 0–1 段内的线性插值，[keyTimes] 递增。 */
  @JvmStatic
  fun keyframe(t: Float, keyTimes: FloatArray, values: FloatArray): Float {
    if (t <= keyTimes.first()) return values.first()
    for (i in 1 until keyTimes.size) {
      if (t <= keyTimes[i]) {
        val span = keyTimes[i] - keyTimes[i - 1]
        val f = if (span <= 0f) 1f else (t - keyTimes[i - 1]) / span
        return values[i - 1] + (values[i] - values[i - 1]) * f
      }
    }
    return values.last()
  }

  private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

  private class BurstDrawable(
    private val emoji: Drawable,
    private val centerX: Float,
    private val centerY: Float,
    private val emojiSize: Float,
    private val density: Float,
    ringColor: Int
  ) : Drawable() {

    /** 0–1，整段 [DURATION_MS] 的进度。 */
    var time = 0f

    private val particles = particles(emojiSize = emojiSize)
    private val totalSeconds = DURATION_MS / 1000f
    private val ringFraction = RING_DURATION_MS.toFloat() / DURATION_MS
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2f * density
      color = ringColor
    }
    private val ringAlpha = android.graphics.Color.alpha(ringColor)

    override fun draw(canvas: Canvas) {
      val ringT = (time / ringFraction).coerceIn(0f, 1f)
      if (ringT < 1f) {
        ringPaint.alpha = (ringAlpha * 0.9f * (1f - ringT)).roundToInt()
        canvas.drawCircle(centerX, centerY, emojiSize * (0.6f + 1.2f * easeOut(ringT)), ringPaint)
      }

      val elapsed = time * totalSeconds
      val floatUp = FLOAT_UP_DP * density
      for (particle in particles) {
        val life = totalSeconds - particle.delay
        val t = ((elapsed - particle.delay) / life).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val travel = easeOut(t)
        val x = centerX + cos(particle.angle) * particle.distance * travel
        val y = centerY + sin(particle.angle) * particle.distance * travel - floatUp * travel
        val scale = keyframe(t, floatArrayOf(0f, 0.3f, 1f), floatArrayOf(particle.scale * 0.4f, particle.scale, particle.scale * 0.6f))
        val alpha = keyframe(t, floatArrayOf(0f, 0.1f, 0.55f, 1f), floatArrayOf(0f, 1f, 1f, 0f))
        val half = emojiSize * scale / 2f
        emoji.setBounds((x - half).roundToInt(), (y - half).roundToInt(), (x + half).roundToInt(), (y + half).roundToInt())
        emoji.alpha = (alpha * 255).roundToInt()
        emoji.draw(canvas)
      }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }
}
