/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * Tellomi（交互审计 A-07 Android 半边）：消息下面的回应胶囊有变化时给个交代，而不是整排一帧跳变。
 *
 * 照 Telegram 安卓 `ReactionsLayoutInBubble`：按回应（基础表情）对新旧两排——新出现的胶囊从中心缩放 + 淡入，计数变了的数字滚动
 * （加一往上滚、减一往下滚），只换了「是不是我回应的」的轻轻一弹。只在**同一条消息**的回应变了时动（列表行复用给别的消息时不动，
 * 由 [ReactionsConversationView] 判）。关了动画（[TellomiMotion.Tier.MINIMAL]）时新胶囊只淡入、数字不滚。
 */
object TellomiReactionPillAnimator {

  /** 一个胶囊在比较时要看的东西。 */
  data class PillState(val count: Int, val userWasSender: Boolean)

  enum class Change { NONE, ADDED, COUNT_UP, COUNT_DOWN, RESTYLED }

  /** 纯比较（单测用）：[before] 为 null 表示这个回应原来没有胶囊。 */
  @JvmStatic
  fun changeFor(before: PillState?, after: PillState): Change {
    return when {
      before == null -> Change.ADDED
      after.count > before.count -> Change.COUNT_UP
      after.count < before.count -> Change.COUNT_DOWN
      after.userWasSender != before.userWasSender -> Change.RESTYLED
      else -> Change.NONE
    }
  }

  private const val POP_FROM_SCALE = 0.4f
  private const val BUMP_SCALE = 1.12f
  private const val FADE_MS = 150L
  private const val ROLL_MS = 220L
  private const val ROLL_DISTANCE_DP = 8

  @JvmStatic
  fun animate(pill: View, countView: TextView?, change: Change) {
    if (change == Change.NONE) {
      return
    }

    val tier = TellomiMotion.tier(pill.context)
    if (tier == TellomiMotion.Tier.MINIMAL) {
      if (change == Change.ADDED) {
        pill.alpha = 0f
        pill.animate().alpha(1f).setDuration(FADE_MS).start()
      }
      return
    }

    when (change) {
      Change.ADDED -> {
        pill.alpha = 0f
        pill.animate().alpha(1f).setDuration(FADE_MS).start()
        springScale(pill, from = POP_FROM_SCALE, token = TellomiMotion.Emphasis)
      }
      Change.COUNT_UP, Change.COUNT_DOWN -> {
        springScale(pill, from = BUMP_SCALE, token = TellomiMotion.Release)
        if (countView != null && countView.visibility == View.VISIBLE) {
          val distance = ViewUtil.dpToPx(ROLL_DISTANCE_DP).toFloat()
          countView.translationY = if (change == Change.COUNT_UP) distance else -distance
          countView.alpha = 0f
          countView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(ROLL_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        }
      }
      Change.RESTYLED -> springScale(pill, from = BUMP_SCALE, token = TellomiMotion.Release)
      Change.NONE -> Unit
    }
  }

  private fun springScale(view: View, from: Float, token: TellomiMotion.SpringToken) {
    view.scaleX = from
    view.scaleY = from
    listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { property ->
      SpringAnimation(view, property, 1f).apply {
        spring = SpringForce(1f).setStiffness(token.stiffness).setDampingRatio(token.dampingRatio)
        start()
      }
    }
  }
}
