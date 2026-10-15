/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.audio.TellomiVoiceLevel
import java.util.function.IntSupplier

/**
 * Tellomi（交互审计 A-12「录音看不到音量」）：按住录音时，跟着手指走的红色录音按钮后面多一圈淡红光晕，
 * 每帧读一次麦克风电平（[TellomiVoiceLevel]），用 [TellomiMotion.Fade] 弹簧平滑后变大变小；
 * 位置、取消时的变淡变小都跟着按钮。「移除动画」档不显示。
 */
class TellomiVoiceLevelHalo(
  private val halo: View,
  private val button: View
) {

  var levelSource: IntSupplier? = null

  private var levelScale = 1f
  private var running = false

  private val spring = SpringAnimation(FloatValueHolder(1f)).apply {
    setSpring(SpringForce(1f).setStiffness(TellomiMotion.Fade.stiffness).setDampingRatio(TellomiMotion.Fade.dampingRatio))
    addUpdateListener { _, value, _ ->
      levelScale = value
      sync()
    }
  }

  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      val amplitude = levelSource?.asInt ?: 0
      spring.animateToFinalPosition(TellomiVoiceLevel.haloScale(TellomiVoiceLevel.level(amplitude)))
      halo.postOnAnimation(this)
    }
  }

  fun show() {
    if (levelSource == null || TellomiMotion.tier(halo.context) == TellomiMotion.Tier.MINIMAL) return

    running = true
    spring.cancel()
    levelScale = 1f
    halo.visibility = View.VISIBLE
    sync()
    halo.removeCallbacks(tick)
    halo.postOnAnimation(tick)
  }

  fun hide() {
    running = false
    halo.removeCallbacks(tick)
    spring.cancel()
    halo.visibility = View.GONE
  }

  /** 按钮移动、缩放、变淡之后调：光晕跟着。 */
  fun sync() {
    if (halo.visibility != View.VISIBLE) return

    halo.translationX = button.translationX
    halo.translationY = button.translationY
    val scale = levelScale * button.scaleX
    halo.scaleX = scale
    halo.scaleY = scale
    halo.alpha = HALO_ALPHA * button.alpha
  }

  companion object {
    const val HALO_ALPHA = 0.3f
  }
}
