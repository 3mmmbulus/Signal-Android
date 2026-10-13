/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * 动态页的「滚动感知」顶栏与底栏（`dynamic-feed.md` 第三节第 46 条）：
 * 往更早的内容滚累计 [HIDE_AFTER] 收起，往回累计 [SHOW_AFTER] 出现，离顶 [ALWAYS_SHOW_WITHIN] 内一律显示；
 * 收起 [HIDE_MILLIS]、出现 [SHOW_MILLIS]。顶栏（动态页自己的「推荐 / 关注」）和主底栏读同一个 [visible]，所以同帧收起。
 * 发布按钮不动；二级页没有底栏，不受影响。
 *
 * 文档写的是 pt，安卓上按 dp 算。
 */
class FeedChromeState(
  private val hideAfterPx: Float,
  private val showAfterPx: Float
) {
  companion object {
    val HIDE_AFTER = 48.dp
    val SHOW_AFTER = 12.dp
    val ALWAYS_SHOW_WITHIN = 64.dp
    const val HIDE_MILLIS = 220
    const val SHOW_MILLIS = 180

    /** 主界面的底栏也要读，所以放一个进程内共享的；离开动态页时 [reset]。 */
    var shared: FeedChromeState? by mutableStateOf(null)
  }

  var visible: Boolean by mutableStateOf(true)
    private set

  /** 当前方向上累计滚了多少（px）；换方向清零。 */
  private var accumulated = 0f
  private var nearTop = true

  /** 当前这一栏离顶部在 [ALWAYS_SHOW_WITHIN] 以内。 */
  fun onNearTopChanged(near: Boolean) {
    nearTop = near
    if (near) {
      visible = true
      accumulated = 0f
    }
  }

  /** [towardOlder] > 0 表示往更早的内容滚（手指往上推），< 0 表示往回。 */
  fun onScroll(towardOlder: Float) {
    if (nearTop || towardOlder == 0f) return
    if (visible) {
      accumulated = if (towardOlder > 0) accumulated + towardOlder else 0f
      if (accumulated >= hideAfterPx) {
        visible = false
        accumulated = 0f
      }
    } else {
      accumulated = if (towardOlder < 0) accumulated - towardOlder else 0f
      if (accumulated >= showAfterPx) {
        visible = true
        accumulated = 0f
      }
    }
  }

  /** 换栏、离开动态页：一律显示。 */
  fun reset() {
    visible = true
    accumulated = 0f
    nearTop = true
  }
}
