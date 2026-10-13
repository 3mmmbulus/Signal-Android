/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tellomi（tellomi/tellomi#1263，spec R-1）：盖在被罩住的图片 / 视频上面的那一层：模糊的图 + 一层压暗 + 缓慢飘动、闪烁的白色小点。
 *
 * 照 Telegram 的机制（安卓 `ChatMessageCell` 的 `blurredPhotoImage` + 媒体遮罩粒子 + `startRevealMedia`，只读）：
 * 揭示时从点下去的位置画一个越来越大的圆，圆里露出原图、圆外照旧罩着，粒子往手指那边收、淡掉；完了这一层就不画了。
 * 实现是自己写的：粒子是普通 Canvas 画的点（Telegram 用 OpenGL），揭示时长按 spec 的「约 0.45 秒」。
 * 系统关了动画（开发者选项 / 无障碍）时粒子不动，揭示直接完成。
 */
class TellomiSpoilerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {

  companion object {
    @VisibleForTesting
    const val REVEAL_DURATION_MS = 450L

    private const val SCRIM_ALPHA = 0x33

    /**
     * Under the blurred picture: until it has loaded, or where it is transparent, the sharp picture under this layer
     * must not show through.
     */
    private const val BACKING_COLOR = 0xFF3B3B3B.toInt()
    private const val PARTICLE_AREA_DP = 14f
    private const val MIN_PARTICLES = 24
    private const val MAX_PARTICLES = 420
  }

  private class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val radius: Float,
    val period: Float,
    val phase: Float
  )

  private val density = resources.displayMetrics.density
  private val scrimPaint = Paint().apply { color = Color.argb(SCRIM_ALPHA, 0, 0, 0) }
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val revealPath = Path()
  private val startTime = SystemClock.uptimeMillis()

  private var particles: List<Particle> = emptyList()
  private var particlesForSize = 0 to 0
  private var cover: Drawable? = null

  private var revealAnimator: ValueAnimator? = null
  private var revealProgress = 0f
  private var revealX = 0f
  private var revealY = 0f
  private var revealRadius = 0f

  /** 还罩着（没开始揭示，或者正在揭示）。 */
  val isCovering: Boolean
    get() = visibility == VISIBLE && revealProgress < 1f

  /** 正在揭示：这期间再点不算（不开查看器、不重新开始）。 */
  val isRevealing: Boolean
    get() = revealAnimator?.isRunning == true

  /** 罩上：[blurred] 是模糊过的图（还没加载好时可以先给 null，先画不透明的底、压暗和粒子）。 */
  fun cover(blurred: Drawable?) {
    revealAnimator?.cancel()
    revealAnimator = null
    revealProgress = 0f
    setCoverDrawable(blurred)
    visibility = VISIBLE
    invalidate()
  }

  fun setCoverDrawable(blurred: Drawable?) {
    cover?.callback = null
    cover = blurred
    blurred?.callback = this
    invalidate()
  }

  /** 不罩（没有遮罩，或者已经揭示过）。 */
  fun uncover() {
    revealAnimator?.cancel()
    revealAnimator = null
    revealProgress = 1f
    setCoverDrawable(null)
    visibility = GONE
  }

  /** 从 ([x], [y]) 揭示；完了调 [onRevealed]。 */
  fun reveal(x: Float, y: Float, onRevealed: () -> Unit) {
    if (!isCovering || isRevealing) {
      return
    }
    revealX = x.coerceIn(0f, width.toFloat())
    revealY = y.coerceIn(0f, height.toFloat())
    revealRadius = max(
      max(hypot(revealX, revealY), hypot(width - revealX, revealY)),
      max(hypot(revealX, height - revealY), hypot(width - revealX, height - revealY))
    )

    if (!ValueAnimator.areAnimatorsEnabled()) {
      uncover()
      onRevealed()
      return
    }

    revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = REVEAL_DURATION_MS
      interpolator = FastOutSlowInInterpolator()
      addUpdateListener {
        revealProgress = it.animatedValue as Float
        invalidate()
      }
      addListener(object : AnimatorListenerAdapter() {
        private var cancelled = false

        override fun onAnimationCancel(animation: Animator) {
          cancelled = true
        }

        override fun onAnimationEnd(animation: Animator) {
          if (!cancelled) {
            uncover()
            onRevealed()
          }
        }
      })
      start()
    }
  }

  override fun verifyDrawable(who: Drawable): Boolean = who === cover || super.verifyDrawable(who)

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    if (revealAnimator?.isRunning == true) {
      revealAnimator?.end()
    }
  }

  override fun onDraw(canvas: Canvas) {
    if (!isCovering) {
      return
    }

    val save = canvas.save()
    if (revealProgress > 0f) {
      revealPath.reset()
      revealPath.addCircle(revealX, revealY, revealRadius * revealProgress, Path.Direction.CW)
      if (Build.VERSION.SDK_INT >= 26) {
        canvas.clipOutPath(revealPath)
      } else {
        @Suppress("DEPRECATION")
        canvas.clipPath(revealPath, Region.Op.DIFFERENCE)
      }
    }

    canvas.drawColor(BACKING_COLOR)
    cover?.let {
      it.setBounds(0, 0, width, height)
      it.draw(canvas)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    drawParticles(canvas)
    canvas.restoreToCount(save)

    if (ValueAnimator.areAnimatorsEnabled()) {
      postInvalidateOnAnimation()
    }
  }

  private fun drawParticles(canvas: Canvas) {
    if (width == 0 || height == 0) {
      return
    }
    if (particlesForSize != width to height) {
      particles = makeParticles(width, height)
      particlesForSize = width to height
    }

    val seconds = if (ValueAnimator.areAnimatorsEnabled()) (SystemClock.uptimeMillis() - startTime) / 1000f else 0f
    val pull = revealProgress
    for (particle in particles) {
      var x = wrap(particle.x * width + particle.vx * seconds, width.toFloat())
      var y = wrap(particle.y * height + particle.vy * seconds, height.toFloat())
      if (pull > 0f) {
        // 揭示时往手指那边收（spec R-1「粒子吸向手指」）
        x += (revealX - x) * pull * 0.6f
        y += (revealY - y) * pull * 0.6f
      }
      val twinkle = 0.5f + 0.5f * sin(2f * PI.toFloat() * seconds / particle.period + particle.phase)
      dotPaint.alpha = ((0.3f + 0.7f * twinkle) * (1f - pull) * 255).toInt().coerceIn(0, 255)
      canvas.drawCircle(x, y, particle.radius, dotPaint)
    }
  }

  private fun makeParticles(width: Int, height: Int): List<Particle> {
    val cell = PARTICLE_AREA_DP * density
    val count = ((width * height) / (cell * cell)).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
    val random = Random(width * 31 + height)
    return List(count) {
      val speed = (4f + random.nextFloat() * 10f) * density
      val angle = random.nextFloat() * 2f * PI.toFloat()
      Particle(
        x = random.nextFloat(),
        y = random.nextFloat(),
        vx = speed * kotlin.math.cos(angle),
        vy = speed * sin(angle),
        radius = (0.5f + random.nextFloat() * 0.7f) * density,
        period = 1.2f + random.nextFloat() * 1.4f,
        phase = random.nextFloat() * 2f * PI.toFloat()
      )
    }
  }

  private fun wrap(value: Float, size: Float): Float {
    val wrapped = value % size
    return if (wrapped < 0) wrapped + size else wrapped
  }
}
