/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiMotion

/**
 * Tellomi（交互审计 A-27）：会话列表 ⇄ 搜索结果换适配器时，结果区淡入，不再整块瞬换。
 *
 * 照 Telegram 安卓 `DialogsActivity.showSearch`（`searchAnimator`）：进搜索时搜索结果从 1.05 缩回 1、淡入，200 ms；
 * 退出时会话列表从 0.95 放大回 1、淡入，180 ms；都是 `CubicBezierInterpolator.EASE_OUT`（0, 0, 0.58, 1）。
 * Telegram 低端机只淡不缩，这里对应简化档；最少档（用户关了动画）不做过渡。
 * Telegram 是两层视图同时交叉淡入淡出；Tellomi 只有一个列表换适配器，所以旧内容直接让位、新内容按上面的参数淡入。
 */
object TellomiSearchResultsTransition {

  const val SHOW_SEARCH_DURATION_MS = 200L
  const val HIDE_SEARCH_DURATION_MS = 180L
  const val SEARCH_START_SCALE = 1.05f
  const val LIST_START_SCALE = 0.95f

  private val EASE_OUT by lazy { PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f) }

  data class Spec(val animate: Boolean, val durationMs: Long, val startScale: Float)

  fun specFor(toSearch: Boolean, tier: TellomiMotion.Tier): Spec {
    val duration = if (toSearch) SHOW_SEARCH_DURATION_MS else HIDE_SEARCH_DURATION_MS
    return when (tier) {
      TellomiMotion.Tier.FULL -> Spec(animate = true, durationMs = duration, startScale = if (toSearch) SEARCH_START_SCALE else LIST_START_SCALE)
      TellomiMotion.Tier.SIMPLIFIED -> Spec(animate = true, durationMs = duration, startScale = 1f)
      TellomiMotion.Tier.MINIMAL -> Spec(animate = false, durationMs = 0L, startScale = 1f)
    }
  }

  /**
   * 列表刚换上 [adapter] 时调。搜索结果是异步分页到的，换上时往往还是空的：这时淡入等于对着空白播完、结果到了再整块出现
   * （模拟器录屏 2026-10-17 就是这样）。所以适配器还空着时先把列表藏起来，等第一批结果插入再淡入；[timeoutMs] 到了还没有
   * （例如真的没有结果）也照样淡入。返回取消用的句柄：在等的时候又换了适配器、或者页面销毁，先调它。
   */
  @JvmStatic
  fun playWhenFilled(list: View, adapter: RecyclerView.Adapter<*>, toSearch: Boolean, timeoutMs: Long): Runnable {
    if (adapter.itemCount > 0 || !specFor(toSearch, TellomiMotion.tier(list.context)).animate) {
      play(list, toSearch)
      return Runnable { }
    }
    list.animate().cancel()
    list.alpha = 0f
    return PendingFadeIn(list, adapter, toSearch, timeoutMs).also { it.start() }
  }

  private class PendingFadeIn(
    private val list: View,
    private val adapter: RecyclerView.Adapter<*>,
    private val toSearch: Boolean,
    private val timeoutMs: Long
  ) : RecyclerView.AdapterDataObserver(), Runnable {

    private var finished = false
    private val timeout = Runnable { finish(play = true) }

    // 主线程 Handler 而不是 list.postDelayed：没挂到窗口上的视图，postDelayed 只排队不执行。
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
      adapter.registerAdapterDataObserver(this)
      handler.postDelayed(timeout, timeoutMs)
    }

    override fun onChanged() = onData()

    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onData()

    private fun onData() {
      if (adapter.itemCount > 0) {
        finish(play = true)
      }
    }

    /** 取消：不再等，列表先复原成完全不透明（下一次换适配器要淡入的话会重新从 0 开始）。 */
    override fun run() {
      if (!finished) {
        finish(play = false)
        list.alpha = 1f
      }
    }

    private fun finish(play: Boolean) {
      if (finished) {
        return
      }
      finished = true
      adapter.unregisterAdapterDataObserver(this)
      handler.removeCallbacks(timeout)
      if (play) {
        play(list, toSearch)
      }
    }
  }

  /** 换上的内容按 [specFor] 淡入；[toSearch] = 换成的是搜索结果。 */
  @JvmStatic
  fun play(list: View, toSearch: Boolean) {
    val spec = specFor(toSearch, TellomiMotion.tier(list.context))
    list.animate().cancel()
    if (!spec.animate) {
      list.alpha = 1f
      list.scaleX = 1f
      list.scaleY = 1f
      return
    }
    list.alpha = 0f
    list.scaleX = spec.startScale
    list.scaleY = spec.startScale
    list.animate()
      .alpha(1f)
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(spec.durationMs)
      .setInterpolator(EASE_OUT)
      .start()
  }
}
