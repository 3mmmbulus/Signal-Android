/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import androidx.core.view.animation.PathInterpolatorCompat

/**
 * Tellomi（交互审计 A-37「新消息把会话顶到最上面时没有移动轨迹」）：会话列表一次更新动不动、怎么动。
 *
 * 一次更新里只换了位置、或者只进出一两个会话（来新消息顶上去、新会话出现、归档一个、删一个）：
 * 所有看得见的行一起移到新位置，新出现的那个淡入。曲线、时长和聊天页补位（A-05）同一套。
 * 一次进出很多（切文件夹、翻页、批量归档）不播，直接换；手指还在滑或列表在惯性滚动时也不播。
 * Telegram iOS 的会话列表来新消息时同样带插入动画（`ChatListViewTransition` 的 `.interactiveChanges` → `.AnimateInsertion`）。
 */
object TellomiChatListMotion {

  const val MOVE_DURATION_MS = 250L
  const val ADD_DURATION_MS = 150L

  /** 一次更新里进出（新增 + 移除）超过这么多个就算整批换，不播。 */
  const val MAX_ANIMATED_ENTRANCES_AND_EXITS = 2

  @JvmField
  val MOVE_INTERPOLATOR = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

  @JvmStatic
  fun shouldAnimate(additions: Int, removals: Int, listScrolling: Boolean): Boolean {
    if (listScrolling) return false
    return additions + removals <= MAX_ANIMATED_ENTRANCES_AND_EXITS
  }

  /**
   * 新出现的行只在别的行同时让位（移动或离开）时淡入。列表从空到有（打开 App 时异步加载完）、在末尾追加都直接出现：
   * 否则只有一两个会话的人每次打开都看到列表淡入，会话多的人却没有。
   */
  @JvmStatic
  fun shouldFadeIn(moves: Int, removals: Int): Boolean = moves + removals > 0
}
