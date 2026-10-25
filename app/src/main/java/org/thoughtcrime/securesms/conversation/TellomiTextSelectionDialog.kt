/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.text.Selection
import android.text.SpannableString
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.Util
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * Tellomi（审计 4.4）：长按菜单「选择文字」。弹出一个只读的纯文本框，用系统选择手柄选一段，
 * 选择菜单里只有「复制」和「全选」，复制只拷选中的那一段。点框外或返回就退出。
 * 提及和链接按纯文本处理（不带样式和链接），和 iOS #387 一致。
 */
object TellomiTextSelectionDialog {

  fun show(context: Context, text: String): AlertDialog? {
    if (text.isEmpty()) return null
    val pad = ViewUtil.dpToPx(20)
    val textView = TextView(context).apply {
      setText(SpannableString(text), TextView.BufferType.SPANNABLE)
      setTextIsSelectable(true)
      setPadding(pad, pad, pad, pad)
      textSize = 16f
      customSelectionActionModeCallback = CopyOnlyCallback(this, text)
    }
    val scroll = ScrollView(context).apply { addView(textView) }
    val dialog = MaterialAlertDialogBuilder(context)
      .setView(scroll)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnShowListener {
      val r = TellomiPartialTextSelection.initialRange(text)
      if (!r.isEmpty()) {
        textView.requestFocus()
        Selection.setSelection(textView.text as android.text.Spannable, r.first, r.last + 1)
      }
    }
    dialog.show()
    return dialog
  }

  private class CopyOnlyCallback(private val textView: TextView, private val plainText: String) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
      val start = textView.selectionStart
      val end = textView.selectionEnd
      for (i in menu.size() - 1 downTo 0) {
        val item = menu.getItem(i)
        when (item.itemId) {
          android.R.id.copy -> item.isVisible = start != end
          android.R.id.selectAll -> item.isVisible = !TellomiPartialTextSelection.isWholeText(start, end, plainText)
          else -> menu.removeItem(item.itemId)
        }
      }
      return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
      if (item.itemId != android.R.id.copy) return false
      val copied = TellomiPartialTextSelection.textToCopy(textView.selectionStart, textView.selectionEnd, plainText) ?: return true
      Util.copyToClipboard(textView.context, copied)
      mode.finish()
      return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
  }
}
