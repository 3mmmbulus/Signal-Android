/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.util.DisplayMetrics
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.util.doAfterNextLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tellomi（交互审计 A-08 Android：「跳到某条消息……Android 瞬跳」）：点引用、搜索结果、@ 提及跳到某条消息时滑过去，不再一下跳到。
 *
 * - 近的（一屏以内）：平滑滚到那条消息，落点和上游 [ScrollToPositionDelegate.JumpToPositionStrategy] 一样（离底边三分之一）。
 * - 远的：先瞬间停到离目标一屏的地方，再平滑滚完最后一屏——不长途滚，也看得出「它就在上面那里」。
 * - 已经完整露在屏幕上：不滚（同上游）。
 * - 滚完才调 [onArrived]（高亮脉冲）：脉冲在消息还没滚进屏幕时就开始的话，只看得到尾巴。
 * - [TellomiMotion] 最少档（省电 / 关了动画）照上游瞬跳。
 *
 * 末段时长照 Telegram 安卓的比例：(距离 ÷ 列表高 + 1) × 200 ms，限在 [MIN_GLIDE_MS]–[MAX_GLIDE_MS]，减速曲线（滚动位置不回弹）。
 */
class TellomiJumpScrollStrategy(
  private val onArrived: (Int) -> Unit
) : ScrollToPositionDelegate.ScrollStrategy {

  override fun performScroll(recyclerView: RecyclerView, layoutManager: LinearLayoutManager, position: Int, smooth: Boolean) {
    if (!smooth || TellomiMotion.tier(recyclerView.context) == TellomiMotion.Tier.MINIMAL) {
      ScrollToPositionDelegate.JumpToPositionStrategy.performScroll(recyclerView, layoutManager, position, smooth)
      onArrived(position)
      return
    }

    val child: View? = layoutManager.findViewByPosition(position)
    if (child != null && layoutManager.isViewPartiallyVisible(child, true, false)) {
      onArrived(position)
      return
    }

    val offset = recyclerView.height / 3
    val staging = stagingPosition(
      target = position,
      firstVisible = layoutManager.findFirstVisibleItemPosition(),
      lastVisible = layoutManager.findLastVisibleItemPosition(),
      itemCount = layoutManager.itemCount
    )

    if (staging != null) {
      layoutManager.scrollToPositionWithOffset(staging, offset)
      recyclerView.doAfterNextLayout { glide(recyclerView, layoutManager, position, offset) }
    } else {
      glide(recyclerView, layoutManager, position, offset)
    }
  }

  private fun glide(recyclerView: RecyclerView, layoutManager: LinearLayoutManager, position: Int, offset: Int) {
    val scroller = object : LinearSmoothScroller(recyclerView.context) {
      // The same landing as scrollToPositionWithOffset(position, offset): measured from the end edge when the list is reversed.
      override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
        return if (layoutManager.reverseLayout) (boxEnd - offset) - viewEnd else (boxStart + offset) - viewStart
      }

      override fun calculateTimeForDeceleration(dx: Int): Int {
        return glideDurationMs(abs(dx), recyclerView.height)
      }

      override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
        return SEEK_MS_PER_INCH / displayMetrics.densityDpi
      }

      override fun onStop() {
        super.onStop()
        onArrived(position)
      }
    }
    scroller.targetPosition = position
    layoutManager.startSmoothScroll(scroller)
  }

  companion object {
    const val MIN_GLIDE_MS = 300
    const val MAX_GLIDE_MS = 900

    /** Farther than this many screens away, the list first stops one screen short of the message. */
    private const val FAR_SCREENS = 1

    /** How fast the scroller looks for a message it has not laid out yet (LinearSmoothScroller's own default is 25). */
    private const val SEEK_MS_PER_INCH = 40f

    /** The last stretch: (distance ÷ list height + 1) × 200 ms, within [MIN_GLIDE_MS]–[MAX_GLIDE_MS]. */
    fun glideDurationMs(distancePx: Int, listHeightPx: Int): Int {
      if (listHeightPx <= 0) {
        return MIN_GLIDE_MS
      }
      return ((distancePx.toFloat() / listHeightPx + 1f) * 200f).roundToInt().coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
    }

    /**
     * Where to stop first for a far jump — one screen's worth of items short of [target], on the side the list is coming
     * from — or null when [target] is near enough to glide to directly.
     */
    fun stagingPosition(target: Int, firstVisible: Int, lastVisible: Int, itemCount: Int): Int? {
      if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION || itemCount <= 0) {
        return null
      }
      val screen = max(1, lastVisible - firstVisible + 1)
      val distance = when {
        target < firstVisible -> firstVisible - target
        target > lastVisible -> target - lastVisible
        else -> 0
      }
      if (distance <= screen * FAR_SCREENS) {
        return null
      }
      val staging = if (target > lastVisible) target - screen else target + screen
      return staging.coerceIn(0, itemCount - 1)
    }
  }
}
