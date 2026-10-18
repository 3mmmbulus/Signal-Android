/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.conversation.ConversationHeaderView
import kotlin.math.min

/**
 * Reserves space above the [ConversationHeaderView] for the toolbar and adjusts the conversation RecyclerView's translationY so the header is pinned below the
 * toolbar when content is short enough to fit the viewport. The toolbar margin is only contributed when a translation is actually going to be applied; when
 * content overflows, no margin is added and no translation is applied.
 */
class ConversationHeaderPositionDecoration : RecyclerView.ItemDecoration() {
  private val bounds = Rect()

  var toolbarMargin: Int = 0

  override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
    super.getItemOffsets(outRect, view, parent, state)
    if (view is ConversationHeaderView && !parent.canScrollVertically(1)) {
      outRect.top = toolbarMargin
    }
  }

  override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    parent.translationY = targetTranslationY(parent)
  }

  /**
   * The translationY [onDraw] gives the list for its current layout. Tellomi (interaction audit A-05): the item animator
   * reads it before the frame is drawn, so in a conversation too short to scroll the rows can start their slides from
   * where they were on screen — the list's translation changes in the same frame as their layout.
   */
  fun targetTranslationY(parent: RecyclerView): Float {
    if (parent.canScrollVertically(1)) {
      return 0f
    }

    val threadHeaderView: ConversationHeaderView = parent.children
      .filterIsInstance<ConversationHeaderView>()
      .firstOrNull() ?: return 0f

    parent.getDecoratedBoundsWithMargins(threadHeaderView, bounds)
    val margin = bounds.bottom - bounds.top - threadHeaderView.height
    val childTop: Int = threadHeaderView.top - margin
    return min(0, -childTop).toFloat()
  }
}
