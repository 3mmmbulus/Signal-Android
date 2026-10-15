/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

/**
 * Tellomi（交互审计 A-30「操作完成的反馈大多是系统 Toast，和 App 脱节」）：聊天页的提示条。
 *
 * - 锚在输入栏上方：挂在 `tellomi_bulletin_host`（CoordinatorLayout，底边是输入区上沿），键盘起落时跟着输入区走；
 *   原来的 Toast 在屏幕底部（被键盘挡住时更远），原来的 Snackbar 挂在整页底部、压在输入框上面。
 * - 能横滑关掉：Snackbar 在 CoordinatorLayout 里自带 SwipeDismissBehavior。
 * - 可以带一个动作（「查看」「撤销」）。
 * - 停留时长、读屏播报由 Material Snackbar 按系统无障碍设置处理（`getRecommendedTimeoutMillis`、live region）。
 *
 * 机制参考 Telegram 安卓聊天页的 `Bulletin` / `UndoView`：贴在输入框上方、可横滑；样式沿用 App 现有的 Snackbar 主题。
 */
object TellomiBulletin {

  fun show(
    host: CoordinatorLayout,
    text: CharSequence,
    @BaseTransientBottomBar.Duration duration: Int = Snackbar.LENGTH_LONG,
    actionText: CharSequence? = null,
    action: (() -> Unit)? = null
  ): Snackbar {
    val snackbar = Snackbar.make(host, text, duration)
    if (actionText != null && action != null) {
      snackbar.setAction(actionText) { action() }
    }
    snackbar.show()
    return snackbar
  }

  /** Toast 的时长换成提示条的：短的照短，长的照长。 */
  @BaseTransientBottomBar.Duration
  fun durationForToast(toastDuration: Int): Int {
    return if (toastDuration == android.widget.Toast.LENGTH_LONG) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
  }
}
