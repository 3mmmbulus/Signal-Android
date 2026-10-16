/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import org.signal.core.ui.motion.TellomiMotion
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Tellomi（交互审计 A-25）：会话列表进出多选、勾选 / 取消时，这一行的勾选圈淡入 + 放大（消失时淡出 + 缩小），
 * 头像和文字跟着让位滑过去。原来勾选圈直接切显隐，头像和文字一帧跳 24dp。
 *
 * 照 Telegram 安卓 `DialogCell` 选择时勾选框带动画出现（`CheckBox2.setChecked(…, animated)`）；Tellomi 的勾选圈占着位置、
 * 头像要让开，所以这一行自己做：时长同多选底部操作栏的进场（[DURATION_MS]，`SignalBottomActionBar` 的 250 ms）。动效最少档直接切。
 *
 * 不用 `TransitionManager` / 行上原有的 `animateLayoutChanges`：会话列表挂在 Compose 的 `AndroidView` 里，Compose 在
 * `dispatchDraw` 里才给它排版，比画前回调（preDraw）晚——两者都靠 preDraw 抓终点或收尾，模拟器逐帧实测前者先画一帧终点
 * 再跳回起点，后者的让位动画根本没跑（这就是原来「一帧跳过去」的原因）。这里在排版回调里设起点，排版总在画之前。
 */
object TellomiListSelectionTransition {

  const val DURATION_MS = 250L

  /** 勾选圈出现时从这么大长到原大，消失时缩到这么大。 */
  const val CHECK_START_SCALE = 0.6f

  private val running = WeakHashMap<ViewGroup, Run>()

  /**
   * 改勾选圈显隐之前调：记下这一行每个子视图现在画在哪儿、勾选圈现在的样子；[changes] 里改显隐（期间关掉行上的
   * `LayoutTransition`，免得它也去动同一批视图）；这一行下一次排版时从记下的位置动到新位置。
   */
  @JvmStatic
  fun run(row: ViewGroup, check: View, changes: Runnable) {
    val previous = running.remove(row)
    if (TellomiMotion.tier(row.context) == TellomiMotion.Tier.MINIMAL) {
      previous?.cancel()
      changes.run()
      return
    }

    // 正在动的接着从眼下画的位置动
    val from = HashMap<View, Rect>()
    for (child in row.children) {
      if (child.isVisible) {
        from[child] = Rect(child.left, child.top, child.right, child.bottom).apply {
          offset(child.translationX.roundToInt(), child.translationY.roundToInt())
        }
      }
    }
    val checkSnapshot = if (check.isVisible && check.isLaidOut && check.width > 0 && check.height > 0) {
      BitmapDrawable(check.resources, check.drawToBitmap()).apply { bounds = from.getValue(check) }
    } else {
      null
    }
    previous?.cancel()

    val transition = row.layoutTransition
    row.layoutTransition = null
    changes.run()
    row.layoutTransition = transition

    val newRun = Run(row, check, from, checkSnapshot)
    running[row] = newRun
    row.addOnLayoutChangeListener(newRun)
  }

  /** 这一行换去显示别的会话（复用）：正在动的直接落到终点。 */
  @JvmStatic
  fun cancel(row: ViewGroup) {
    running.remove(row)?.cancel()
  }

  private class Run(
    private val row: ViewGroup,
    private val check: View,
    private val from: Map<View, Rect>,
    private val checkSnapshot: BitmapDrawable?
  ) : View.OnLayoutChangeListener {

    private var animator: Animator? = null
    private var overlayAdded = false

    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
      row.removeOnLayoutChangeListener(this)
      val parts = ArrayList<Animator>()

      for (child in row.children) {
        if (!child.isVisible) continue
        val before = from[child]
        if (before == null || (child === check && checkSnapshot != null)) {
          // 新出现的（进多选时的勾选圈），或勾选圈换了样子（选上 / 取消）：淡入 + 放大
          child.alpha = 0f
          child.scaleX = CHECK_START_SCALE
          child.scaleY = CHECK_START_SCALE
          parts += ObjectAnimator.ofPropertyValuesHolder(
            child,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)
          )
        } else if (before.left != child.left || before.top != child.top) {
          // 挪了位置的：从原来画的地方滑过去
          child.translationX = (before.left - child.left).toFloat()
          child.translationY = (before.top - child.top).toFloat()
          parts += ObjectAnimator.ofPropertyValuesHolder(
            child,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)
          )
        }
      }

      // 勾选圈原来的样子：在覆盖层上淡出 + 缩小（退出多选时它已经 GONE，选上 / 取消时新样子在它下面长出来）
      if (checkSnapshot != null) {
        val start = Rect(checkSnapshot.bounds)
        row.overlay.add(checkSnapshot)
        overlayAdded = true
        parts += ValueAnimator.ofFloat(0f, 1f).apply {
          addUpdateListener {
            val fraction = it.animatedValue as Float
            checkSnapshot.alpha = ((1f - fraction) * 255).roundToInt()
            checkSnapshot.bounds = scaled(start, 1f - (1f - CHECK_START_SCALE) * fraction)
          }
        }
      }

      if (parts.isEmpty()) {
        finish()
        return
      }
      animator = AnimatorSet().apply {
        playTogether(parts)
        duration = DURATION_MS
        interpolator = FastOutSlowInInterpolator()
        addListener(object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            finish()
          }
        })
        start()
      }
    }

    /** 落到终点：位置归位、勾选圈原大不透明、覆盖层的图拿掉。 */
    fun cancel() {
      row.removeOnLayoutChangeListener(this)
      animator?.cancel()
      finish()
    }

    private fun finish() {
      for (child in row.children) {
        child.translationX = 0f
        child.translationY = 0f
      }
      check.alpha = 1f
      check.scaleX = 1f
      check.scaleY = 1f
      if (overlayAdded) {
        row.overlay.remove(checkSnapshot!!)
        overlayAdded = false
      }
      if (running[row] === this) {
        running.remove(row)
      }
    }
  }

  /** 以 [rect] 的中心缩放 [scale]。 */
  private fun scaled(rect: Rect, scale: Float): Rect {
    val halfWidth = rect.width() * scale / 2f
    val halfHeight = rect.height() * scale / 2f
    return Rect(
      (rect.exactCenterX() - halfWidth).roundToInt(),
      (rect.exactCenterY() - halfHeight).roundToInt(),
      (rect.exactCenterX() + halfWidth).roundToInt(),
      (rect.exactCenterY() + halfHeight).roundToInt()
    )
  }
}
