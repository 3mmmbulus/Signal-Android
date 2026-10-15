/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-11「表情 / 键盘图标直接替换」）：同一个按钮换图标时，旧图标淡出、新图标淡入（[CROSSFADE_MS]），
 * 按钮同时从 [POP_FROM_SCALE] 用 [TellomiMotion.Snap] 弹簧弹回原大。透明度照标准不用弹簧。
 *
 * 动效最少档（关了动画）、还没排版或不在窗口上时直接换。
 */
object TellomiIconSwap {

  const val CROSSFADE_MS = 150
  const val POP_FROM_SCALE = 0.7f

  @JvmStatic
  fun swap(view: ImageView, previous: Drawable?, next: Drawable) {
    view.removeCallbacks(view.getTag(TAG_FINISH) as? Runnable)

    if (previous == null || previous === next || !view.isLaidOut || !view.isAttachedToWindow || TellomiMotion.tier(view.context) == TellomiMotion.Tier.MINIMAL) {
      view.setImageDrawable(next)
      view.scaleX = 1f
      view.scaleY = 1f
      return
    }

    val crossfade = TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = true }
    view.setImageDrawable(crossfade)
    crossfade.startTransition(CROSSFADE_MS)

    view.scaleX = POP_FROM_SCALE
    view.scaleY = POP_FROM_SCALE
    pop(view, DynamicAnimation.SCALE_X)
    pop(view, DynamicAnimation.SCALE_Y)

    // Once the crossfade is over, put the plain drawable back so the button holds just the one icon.
    val finish = Runnable {
      if (view.drawable === crossfade) {
        view.setImageDrawable(next)
      }
    }
    view.setTag(TAG_FINISH, finish)
    view.postDelayed(finish, CROSSFADE_MS.toLong())
  }

  private fun pop(view: ImageView, property: DynamicAnimation.ViewProperty) {
    SpringAnimation(view, property, 1f).apply {
      spring = SpringForce(1f).setStiffness(TellomiMotion.Snap.stiffness).setDampingRatio(TellomiMotion.Snap.dampingRatio)
      setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
      start()
    }
  }

  private val TAG_FINISH = org.thoughtcrime.securesms.R.id.tellomi_icon_swap_finish
}
