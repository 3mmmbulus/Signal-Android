/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.R
import kotlin.math.abs

/**
 * Tellomi（交互审计 A-09「可点的东西按下没反应」）：气泡里可点的那一块（引用、文件、联系人卡、回应胶囊、链接卡片）
 * 手指一按下就有反馈，不等列表的 100 ms 按压延迟、也不等抬手。
 *
 * - 缩放类：按下用 [TellomiMotion.Press] 缩到 [BLOCK_SCALE] / [PILL_SCALE]（标准第三节：内容 0.96–0.97、小件 0.9；按下永远不回弹），
 *   支点在手指处；松开 / 取消 / 滑出触摸阈值用 [TellomiMotion.Release] 弹回。动效最少档不缩放，只压暗。
 * - 叠层类（链接卡片）：卡片的按下态是 card-visual §3.6 的 12% 叠层，这里只是让它在按下那一刻就出来（`setPressed`），
 *   抬手、取消、滑走由卡片自己的触摸处理收回。
 *
 * 整条气泡的长按预缩（100 ms 后缩到 0.95）照旧，那是长按的预告，不是点按反馈。
 */
object TellomiPressFeedback {

  const val BLOCK_SCALE = 0.97f
  const val PILL_SCALE = 0.9f

  /** 动效最少档不缩放，按下的那一块压暗到这个透明度。 */
  const val MINIMAL_ALPHA = 0.6f

  /** 按下 [view]。返回 true 表示是压暗（动效最少档），松开时要传回给 [release]。 */
  @JvmStatic
  fun press(view: View, scale: Float, xInView: Float, yInView: Float): Boolean {
    if (TellomiMotion.tier(view.context) == TellomiMotion.Tier.MINIMAL) {
      view.alpha = MINIMAL_ALPHA
      return true
    }
    view.pivotX = xInView.coerceIn(0f, view.width.toFloat())
    view.pivotY = yInView.coerceIn(0f, view.height.toFloat())
    animate(view, scale, TellomiMotion.Press)
    return false
  }

  @JvmStatic
  fun release(view: View, dimmed: Boolean) {
    if (dimmed) {
      view.alpha = 1f
      return
    }
    animate(view, 1f, TellomiMotion.Release)
  }

  private fun animate(view: View, target: Float, token: TellomiMotion.SpringToken) {
    for ((id, property) in listOf(R.id.tellomi_press_scale_x to DynamicAnimation.SCALE_X, R.id.tellomi_press_scale_y to DynamicAnimation.SCALE_Y)) {
      val spring = spring(view, id) ?: SpringAnimation(view, property).also { view.setTag(id, it) }
      spring.spring = SpringForce(target).setStiffness(token.stiffness).setDampingRatio(token.dampingRatio)
      spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
      spring.start()
    }
  }

  private fun spring(view: View, id: Int): SpringAnimation? = view.getTag(id) as? SpringAnimation

  /**
   * 跟一次触摸：按下时问 [findTarget] 手指下面是哪一块，按下它；松开、取消、滑出触摸阈值时收回。
   * 在气泡根视图的 dispatchTouchEvent 里、交给子视图之前调。
   */
  class Tracker(private val root: View, private val findTarget: (rawX: Float, rawY: Float) -> Target?) {

    /** [scale] 为 null 时是叠层类：只提前进入按下态。 */
    data class Target(val view: View, val scale: Float?)

    private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var pressed: Target? = null
    private var dimmed = false
    private var downX = 0f
    private var downY = 0f
    private val location = IntArray(2)

    fun onTouchEvent(event: MotionEvent) {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          release()
          downX = event.rawX
          downY = event.rawY
          val target = findTarget(event.rawX, event.rawY) ?: return
          target.view.getLocationOnScreen(location)
          val x = event.rawX - location[0]
          val y = event.rawY - location[1]
          if (target.scale != null) {
            dimmed = press(target.view, target.scale, x, y)
          } else if (target.view.isClickable) {
            target.view.drawableHotspotChanged(x, y)
            target.view.isPressed = true
          }
          pressed = target
        }
        MotionEvent.ACTION_MOVE -> {
          if (pressed != null && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
            release()
          }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
      }
    }

    fun release() {
      val target = pressed ?: return
      pressed = null
      if (target.scale != null) {
        release(target.view, dimmed)
      }
    }
  }

  /** 会话顶栏的标题（头像 + 名字 + 副标题）按下缩到这么大（照 Telegram 安卓 `ChatAvatarContainer`：`ButtonBounce.getScale(.02f)`）。 */
  const val TITLE_SCALE = 0.98f

  /**
   * Tellomi（交互审计 A-09「顶栏标题没有按下态」）：整块按下就缩一点、支点在中间，松开 / 取消 / 滑出触摸阈值弹回；动效最少档压暗。
   * 只观察触摸（返回 false），原来的点击照常。
   */
  @JvmStatic
  fun wholeViewTouchListener(scale: Float = TITLE_SCALE): View.OnTouchListener {
    return object : View.OnTouchListener {
      private var pressed = false
      private var dimmed = false
      private var downX = 0f
      private var downY = 0f

      override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            downX = event.rawX
            downY = event.rawY
            dimmed = press(view, scale, view.width / 2f, view.height / 2f)
            pressed = true
          }
          MotionEvent.ACTION_MOVE -> {
            val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
            if (pressed && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
              pressed = false
              release(view, dimmed)
            }
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (pressed) {
              pressed = false
              release(view, dimmed)
            }
          }
        }
        return false
      }
    }
  }

  /** 屏幕坐标 ([rawX], [rawY]) 是否落在 [view] 上（[view] 要正显示着）。 */
  @JvmStatic
  fun hits(view: View?, rawX: Float, rawY: Float): Boolean {
    if (view == null || !view.isShown || view.width == 0 || view.height == 0) return false
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return rawX >= location[0] && rawX < location[0] + view.width && rawY >= location[1] && rawY < location[1] + view.height
  }

  /** 回应条里手指下面的那个胶囊。 */
  @JvmStatic
  fun pillAt(reactions: ViewGroup?, rawX: Float, rawY: Float): View? {
    if (reactions == null || !hits(reactions, rawX, rawY)) return null
    for (i in 0 until reactions.childCount) {
      val pill = reactions.getChildAt(i)
      if (hits(pill, rawX, rawY)) return pill
    }
    return null
  }
}
