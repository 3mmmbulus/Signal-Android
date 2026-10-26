/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import org.signal.core.ui.motion.TellomiMotion
import org.signal.emoji.EmojiProvider
import org.signal.emoji.JumboEmoji

/**
 * Tellomi（审计 4.2 UI）：在 View 上播 [TellomiSingleEmojiAnimation]，并用内存记住
 * 哪些消息已经播过「出现」动画（滚动回来不再播）。点一下总是重播。
 * 对齐 iOS `TellomiSingleEmojiAnimationPlayer`（#385）。
 */
class TellomiSingleEmojiAnimationPlayer {

  private val playedOnAppear = HashSet<Long>()

  fun hasPlayedOnAppear(messageId: Long): Boolean = playedOnAppear.contains(messageId)

  /**
   * 若应播出现动画则记为已播并返回 true。
   */
  fun consumeAppear(messageId: Long, jumbomojiCount: Int?, reduceMotion: Boolean): Boolean {
    val shouldPlay = TellomiSingleEmojiAnimation.shouldAnimate(
      jumbomojiCount = jumbomojiCount,
      trigger = TellomiSingleEmojiAnimation.Trigger.APPEARED,
      reduceMotion = reduceMotion,
      alreadyPlayedOnAppear = hasPlayedOnAppear(messageId)
    )
    if (shouldPlay) {
      playedOnAppear.add(messageId)
    }
    return shouldPlay
  }

  fun shouldPlayOnTap(jumbomojiCount: Int?, reduceMotion: Boolean): Boolean {
    return TellomiSingleEmojiAnimation.shouldAnimate(
      jumbomojiCount = jumbomojiCount,
      trigger = TellomiSingleEmojiAnimation.Trigger.TAPPED,
      reduceMotion = reduceMotion,
      alreadyPlayedOnAppear = true
    )
  }

  companion object {
    @JvmField
    val shared = TellomiSingleEmojiAnimationPlayer()

    private val TAG_ANIMATOR = org.thoughtcrime.securesms.R.id.tellomi_single_emoji_anim

    /**
     * 正文是否「单个表情」：全是表情且正好 1 个（jumbo 放大那一类）。
     * 多于 1 个也放大但不弹动画（对齐 iOS `jumbomojiCount == 1`）。
     */
    @JvmStatic
    fun jumbomojiCount(text: CharSequence?): Int? {
      if (text.isNullOrEmpty()) return null
      val candidates = EmojiProvider.getCandidates(text) ?: return null
      if (!candidates.allEmojis) return null
      val size = candidates.size()
      if (size <= 0 || size > JumboEmoji.MAX_JUMBOJI_COUNT) return null
      return size
    }

    @JvmStatic
    fun reduceMotion(context: android.content.Context): Boolean {
      return TellomiMotion.tier(context) == TellomiMotion.Tier.MINIMAL
    }

    @MainThread
    @JvmStatic
    fun play(on: View) {
      cancel(on)
      val animator = makeAnimator(on)
      on.setTag(TAG_ANIMATOR, animator)
      animator.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (on.getTag(TAG_ANIMATOR) === animator) {
            on.setTag(TAG_ANIMATOR, null)
          }
          on.scaleX = 1f
          on.scaleY = 1f
        }

        override fun onAnimationCancel(animation: Animator) {
          if (on.getTag(TAG_ANIMATOR) === animator) {
            on.setTag(TAG_ANIMATOR, null)
          }
          on.scaleX = 1f
          on.scaleY = 1f
        }
      })
      animator.start()
    }

    @MainThread
    @JvmStatic
    fun cancel(on: View) {
      val existing = on.getTag(TAG_ANIMATOR) as? Animator
      if (existing != null) {
        existing.cancel()
        on.setTag(TAG_ANIMATOR, null)
      }
      on.scaleX = 1f
      on.scaleY = 1f
    }

    @VisibleForTesting
    internal fun makeAnimator(view: View): ObjectAnimator {
      val xKeys = TellomiSingleEmojiAnimation.KEYFRAMES.map {
        android.animation.Keyframe.ofFloat(it.relativeTime, it.scale)
      }.toTypedArray()
      val yKeys = TellomiSingleEmojiAnimation.KEYFRAMES.map {
        android.animation.Keyframe.ofFloat(it.relativeTime, it.scale)
      }.toTypedArray()
      return ObjectAnimator.ofPropertyValuesHolder(
        view,
        PropertyValuesHolder.ofKeyframe(View.SCALE_X, *xKeys),
        PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *yKeys)
      ).apply {
        duration = TellomiSingleEmojiAnimation.DURATION_MS
        interpolator = DecelerateInterpolator()
      }
    }

    /** 仅测试用：清空 shared 的出现记录。 */
    @VisibleForTesting
    internal fun resetSharedForTests() {
      shared.playedOnAppear.clear()
    }
  }
}
