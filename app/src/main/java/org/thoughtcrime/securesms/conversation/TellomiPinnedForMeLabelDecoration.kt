/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（ADR-0081 §4.3，pinned-messages.md 3.3 / 3.5）：置顶列表里「仅为我」置顶的那几条，气泡下面一行小字「仅自己可见」，
 * 免得以为对方也看得到。只画在置顶列表上（会话里的消息样式不动），和气泡同一侧对齐。与 iOS `PinnedMessagesDetailsViewController` 一致。
 */
class TellomiPinnedForMeLabelDecoration(
  context: Context,
  private val messageAt: (adapterPosition: Int) -> ConversationMessage?,
  private val isPinnedForMe: (messageId: Long) -> Boolean
) : RecyclerView.ItemDecoration() {

  private val label = context.getString(R.string.TellomiPinnedList__only_you_can_see)
  private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
    color = ContextCompat.getColor(context, CoreUiR.color.signal_colorOnSurfaceVariant)
  }
  private val gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics)
  private val labelHeight = (paint.fontSpacing + 2 * gap).toInt()

  private fun labelled(parent: RecyclerView, view: View): ConversationMessage? {
    val position = parent.getChildAdapterPosition(view)
    if (position == RecyclerView.NO_POSITION) {
      return null
    }
    return messageAt(position)?.takeIf { isPinnedForMe(it.messageRecord.id) }
  }

  override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
    if (labelled(parent, view) != null) {
      outRect.bottom = labelHeight
    }
  }

  // Over, not under: the chat colorizer paints the list's background after the decorations drawn under the items.
  override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    for (i in 0 until parent.childCount) {
      val child = parent.getChildAt(i)
      val message = labelled(parent, child) ?: continue
      val bubble = child.findViewById<View>(R.id.body_bubble)?.takeIf { it.visibility == View.VISIBLE } ?: child
      // Right under the message: under its reactions when it has some, so the two never overlap.
      val above = child.findViewById<View>(R.id.reactions_view)?.takeIf { it.visibility == View.VISIBLE && it.height > 0 } ?: bubble
      val bubbleLeft = child.left + bubble.leftIn(child)
      val x = if (message.messageRecord.isOutgoing) {
        bubbleLeft + bubble.width - paint.measureText(label)
      } else {
        bubbleLeft.toFloat()
      }
      val top = child.top + above.topIn(child) + above.height + gap
      c.drawText(label, x + child.translationX, top + child.translationY - paint.ascent(), paint)
    }
  }

  private fun View.topIn(ancestor: View): Int {
    var top = 0
    var view: View = this
    while (view !== ancestor) {
      top += view.top
      view = view.parent as? View ?: break
    }
    return top
  }

  private fun View.leftIn(ancestor: View): Int {
    var left = 0
    var view: View = this
    while (view !== ancestor) {
      left += view.left
      view = view.parent as? View ?: break
    }
    return left
  }
}
