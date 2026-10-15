/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

/**
 * Tellomi（交互审计 A-24 Android：「滑动归档……Android 过线没有感觉」）：会话列表右滑归档过阈值时的「上膛」状态。
 *
 * 手指还按着、滑过 [ItemTouchHelper] 的归档阈值（行宽的一半）就算上膛：给一次越过阈值的触感，归档图标弹大到 [ARMED_ICON_SCALE]；
 * 退回阈值以内再给一次、图标弹回。松手时上膛就归档（ItemTouchHelper 本来的判定），所以「上膛」等于「松手就归档」。
 */
object TellomiArchiveSwipe {

  /** 上膛时归档图标在原有缩放上再放大这么多。 */
  const val ARMED_ICON_SCALE = 1.2f

  fun isArmed(absoluteDx: Float, itemWidth: Int, swipeThreshold: Float): Boolean {
    return itemWidth > 0 && absoluteDx >= itemWidth * swipeThreshold
  }

  /** [armedProgress] 0 = 没上膛，1 = 上膛；返回图标要乘的缩放。 */
  fun iconScaleFactor(armedProgress: Float): Float {
    return 1f + (ARMED_ICON_SCALE - 1f) * armedProgress.coerceIn(0f, 1.5f)
  }
}
