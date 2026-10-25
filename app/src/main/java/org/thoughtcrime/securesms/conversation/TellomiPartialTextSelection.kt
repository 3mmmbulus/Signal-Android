/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import java.text.BreakIterator

/**
 * Tellomi（审计 4.4）：「选择文字」的纯逻辑，和 iOS `TellomiPartialTextSelection`（#381）行为一致。
 * 区间用 UTF-16 下标（和 TextView 的 selectionStart / selectionEnd 一致），
 * 收紧时不会切断表情、国旗、组合字符或代理对。
 */
object TellomiPartialTextSelection {

  /**
   * 长按菜单里是否显示「选择文字」：不是一次性消息，且有正文或长文附件
   *（和 iOS #387：`canCopy` 且 `!isViewOnce` 对齐）。
   */
  fun canSelectText(isViewOnce: Boolean, body: String?, hasTextSlide: Boolean): Boolean {
    return !isViewOnce && (hasTextSlide || !body.isNullOrBlank())
  }

  /** 把 [start, end) 夹进文字里，并向外扩到完整的字素边界。 */
  fun snapped(start: Int, end: Int, text: String): IntRange {
    val len = text.length
    if (len == 0) return IntRange.EMPTY
    val lo = start.coerceIn(0, len)
    val hi = end.coerceIn(lo, len)
    val it = BreakIterator.getCharacterInstance().apply { setText(text) }
    val s = when {
      lo == 0 || lo == len || it.isBoundary(lo) -> lo
      else -> {
        val p = it.preceding(lo)
        if (p == BreakIterator.DONE) 0 else p
      }
    }
    val e = when {
      hi == 0 || hi == len || it.isBoundary(hi) -> hi
      else -> {
        val f = it.following(hi)
        if (f == BreakIterator.DONE) len else f
      }
    }
    return s until e
  }

  /** 打开时的初始选区：整段文字。 */
  fun initialRange(text: String): IntRange = snapped(0, text.length, text)

  /** 要放进剪贴板的文字；没选中时返回 null。只拷选中的那一段。 */
  fun textToCopy(start: Int, end: Int, text: String): String? {
    val r = snapped(minOf(start, end), maxOf(start, end), text)
    if (r.isEmpty()) return null
    return text.substring(r.first, r.last + 1)
  }

  /** 是否已经选中了整段文字（这时「全选」没必要再显示）。 */
  fun isWholeText(start: Int, end: Int, text: String): Boolean {
    val r = snapped(minOf(start, end), maxOf(start, end), text)
    return text.isNotEmpty() && r.first == 0 && r.last + 1 == text.length
  }
}
