/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.view.drawToBitmap
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Tellomi（交互审计 A-19「聊天顶栏头像 → 资料页：小头像和大头像之间没有连续性」，安卓半边）：点会话顶栏进会话设置时，
 * 顶栏的小头像飞到设置页的大头像那里，而不是跟着整页淡掉、再在新页里冒出来。
 *
 * 机制照 Telegram 安卓（只读）：`ProfileActivity.onCustomTransitionAnimation` 里一个进度把顶栏的小头像移到资料页头部、
 * 从 42% 长到 138%（`avatarAnimationProgress`）。这里两页是两个 Fragment（设置页是 Compose），所以用窗口的覆盖层：
 * - [lift]：导航那一刻把小头像画成一张图，放在窗口覆盖层上、停在原处——会话页淡出时它不跟着淡；
 * - [prepareTarget] / [land]：设置页的大头像一建出来就先藏起来；排好（画之前）时，覆盖层上的图用 [TellomiMotion.Move]
 *   （标准：共享元素）飞过去、放大，落定后真头像显出来、覆盖层的图在上面淡掉（[REVEAL_MS]）；
 * - 设置页 [WAIT_FOR_TARGET_MS] 内没出来（被别的东西拦了），覆盖层上的图淡掉。
 *
 * 动效最少档不飞，照原来的整页切换。返回不飞（预测性返回照旧可拖）。
 */
object TellomiAvatarHero {

  /** 起飞以后最多等设置页这么久。 */
  const val WAIT_FOR_TARGET_MS = 600L

  /** 落定后覆盖层的图在真头像上面淡掉的时长。 */
  const val REVEAL_MS = 120L

  private class Flight(val host: View, val drawable: BitmapDrawable, val from: Rect) {
    val timeout = Runnable { fadeAway(this) }
    var spring: SpringAnimation? = null
    var fade: ValueAnimator? = null
    var target: WeakReference<View>? = null
  }

  private var flight: Flight? = null

  /** 进度 [progress]（0 起点，1 终点，弹簧会略过一点）时图该在的位置。 */
  @JvmStatic
  fun lerpRect(from: Rect, to: Rect, progress: Float, out: Rect = Rect()): Rect {
    fun lerp(a: Int, b: Int) = (a + (b - a) * progress).roundToInt()
    out.set(lerp(from.left, to.left), lerp(from.top, to.top), lerp(from.right, to.right), lerp(from.bottom, to.bottom))
    return out
  }

  /** [view] 在窗口里的位置（覆盖层挂在根视图上，根视图就在窗口原点）。 */
  private fun rectInWindow(view: View): Rect {
    val location = IntArray(2)
    view.getLocationInWindow(location)
    return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
  }

  /** 导航去设置页之前调：把 [source]（顶栏的小头像）画到窗口覆盖层上、停在原处。 */
  @JvmStatic
  fun lift(source: View) {
    cancel()
    if (TellomiMotion.tier(source.context) == TellomiMotion.Tier.MINIMAL) return
    if (!source.isLaidOut || source.width == 0 || source.height == 0 || !source.isShown) return

    val host = source.rootView
    val from = rectInWindow(source)
    val drawable = BitmapDrawable(source.resources, source.drawToBitmap()).apply { bounds = from }
    host.overlay.add(drawable)
    val newFlight = Flight(host, drawable, from)
    flight = newFlight
    host.postDelayed(newFlight.timeout, WAIT_FOR_TARGET_MS)
  }

  /**
   * 设置页的大头像一建出来就调：有正在飞的图，就先把它藏起来（实测要等到 [land] 才藏的话，它会先露一帧再消失）。
   */
  @JvmStatic
  fun prepareTarget(target: View) {
    val current = flight ?: return
    target.alpha = 0f
    current.target = WeakReference(target)
  }

  /**
   * 设置页排好、画之前调：[target] 是设置页的大头像。有刚起飞的图就飞过去，返回 true；没有（不是从顶栏进来的）返回 false、什么都不做。
   */
  @JvmStatic
  fun land(target: View): Boolean {
    val current = flight ?: return false
    current.host.removeCallbacks(current.timeout)
    if (!target.isLaidOut || target.width == 0 || target.height == 0 || target.rootView !== current.host) {
      target.alpha = 1f
      cancel()
      return false
    }

    val to = rectInWindow(target)
    target.alpha = 0f
    current.target = WeakReference(target)
    val bounds = Rect()
    val spring = SpringAnimation(FloatValueHolder(0f)).apply {
      spring = SpringForce(1f).setStiffness(TellomiMotion.Move.stiffness).setDampingRatio(TellomiMotion.Move.dampingRatio)
      setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
      addUpdateListener { _, value, _ ->
        current.drawable.bounds = lerpRect(current.from, to, value, bounds)
        current.host.invalidate()
      }
      addEndListener { _, canceled, _, _ ->
        if (!canceled && flight === current) {
          reveal(current, target)
        }
      }
    }
    current.spring = spring
    spring.start()
    return true
  }

  /**
   * 落定：真头像马上显出来，覆盖层的图在它上面淡掉（两张都半透明地交叉淡入会叠出一两帧发浅，模拟器逐帧实测）。
   */
  private fun reveal(current: Flight, target: View) {
    current.drawable.bounds = rectInWindow(target)
    target.alpha = 1f
    current.fade = ValueAnimator.ofInt(255, 0).apply {
      duration = REVEAL_MS
      addUpdateListener {
        current.drawable.alpha = it.animatedValue as Int
        current.host.invalidate()
      }
      doOnEnd { finish(current) }
      start()
    }
  }

  /** 设置页没等到：图在原处淡掉（藏起来的大头像放回来）。 */
  private fun fadeAway(current: Flight) {
    if (flight !== current) return
    current.target?.get()?.animate()?.alpha(1f)?.setDuration(REVEAL_MS)?.start()
    current.fade = ValueAnimator.ofInt(255, 0).apply {
      duration = REVEAL_MS
      addUpdateListener {
        current.drawable.alpha = it.animatedValue as Int
        current.host.invalidate()
      }
      doOnEnd { finish(current) }
      start()
    }
  }

  private fun finish(current: Flight) {
    current.host.overlay.remove(current.drawable)
    current.host.invalidate()
    if (flight === current) {
      flight = null
    }
  }

  /** 收起正在飞的（页面没出来、又切走了）：图拿掉。 */
  @JvmStatic
  fun cancel() {
    val current = flight ?: return
    flight = null
    current.host.removeCallbacks(current.timeout)
    current.spring?.cancel()
    current.fade?.cancel()
    current.target?.get()?.alpha = 1f
    current.host.overlay.remove(current.drawable)
    current.host.invalidate()
  }

  val isFlyingForTesting: Boolean get() = flight != null
  val currentBoundsForTesting: Rect? get() = flight?.drawable?.bounds
}
