/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4）：查看器里这一张的计时——一次性查看，或打开后看 3 / 10 / 30 秒；「不删除」= 没有（null）。
 * 照 Telegram 两端说明框右侧计时键的五项菜单（安卓 `CaptionPhotoViewer`、iOS `MessageInputPanelComponent`），只取机制。
 */
object TellomiViewOnceChoice {
  const val VIEW_ONCE = 0

  /** 菜单里的次序（最后一项「不删除」是 null）。 */
  val MENU_ORDER: List<Int?> = listOf(VIEW_ONCE, 3, 10, 30, null)

  fun isValid(choice: Int): Boolean = choice == VIEW_ONCE || choice in setOf(3, 10, 30)

  /** 计时键上显示的：一次性查看「1」，计时「3」「10」「30」。 */
  fun badge(choice: Int): String = if (choice == VIEW_ONCE) "1" else choice.toString()
}
