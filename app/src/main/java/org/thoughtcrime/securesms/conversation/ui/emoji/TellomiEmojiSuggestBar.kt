/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.ui.emoji

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * Tellomi（审计 3.4 / F22）：输入框上方表情建议栏的纯逻辑，对齐 iOS `TellomiEmojiSuggestBarModel`（#384）。
 */
object TellomiEmojiSuggestBarModel {

  const val MAX_VISIBLE = TellomiEmojiSuggest.MAX_RESULTS

  /** 当前文字和光标下要显示的表情（去重、最多 8 个）；没有建议时为空，栏隐藏。 */
  fun visibleEmoji(text: String, selectionStart: Int, selectionEnd: Int): List<String> {
    val suggestion = TellomiEmojiSuggest.suggestion(text, selectionStart, selectionEnd) ?: return emptyList()
    return suggestion.emoji.distinct().take(MAX_VISIBLE)
  }

  /**
   * 点了某个表情：在当前文字里替换掉查询词，返回新文字、被替换的起止和新光标位置。
   */
  fun applying(
    emoji: String,
    text: String,
    selectionStart: Int,
    selectionEnd: Int
  ): Applied? {
    val suggestion = TellomiEmojiSuggest.suggestion(text, selectionStart, selectionEnd) ?: return null
    if (emoji !in suggestion.emoji) return null
    val (out, cursor) = TellomiEmojiSuggest.apply(suggestion, emoji, text)
    return Applied(
      text = out,
      replacedStart = suggestion.replacementStart,
      replacedLength = suggestion.replacementLength,
      cursor = cursor
    )
  }

  data class Applied(
    val text: String,
    val replacedStart: Int,
    val replacedLength: Int,
    val cursor: Int
  )
}

/**
 * 输入框上方一行横向的表情按钮；没有建议时隐藏。对齐 iOS `TellomiEmojiSuggestBar`。
 */
class TellomiEmojiSuggestBar @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

  companion object {
    val HEIGHT_DP = 40
  }

  var onSelect: ((String) -> Unit)? = null

  fun interface OnSelectListener {
    fun onSelect(emoji: String)
  }

  /** Java 侧用：点建议栏里的表情。 */
  fun setOnSelectListener(listener: OnSelectListener?) {
    onSelect = listener?.let { l -> { emoji -> l.onSelect(emoji) } }
  }

  private val stack: LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val pad = ViewUtil.dpToPx(6)
    setPadding(pad, 0, pad, 0)
  }

  private var emoji: List<String> = emptyList()

  init {
    isHorizontalScrollBarEnabled = false
    overScrollMode = OVER_SCROLL_NEVER
    visibility = GONE
    contentDescription = context.getString(R.string.TellomiEmojiSuggest__accessibility_label)
    addView(
      stack,
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    )
  }

  /**
   * 换一组表情；返回显示状态是否变了（调用方据此重新算输入栏高度）。
   */
  fun update(newEmoji: List<String>): Boolean {
    val wasHidden = visibility != VISIBLE
    if (newEmoji != emoji) {
      emoji = newEmoji
      stack.removeAllViews()
      val padH = ViewUtil.dpToPx(6)
      val padV = ViewUtil.dpToPx(2)
      for (item in newEmoji) {
        val button = TextView(context).apply {
          text = item
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
          setPadding(padH, padV, padH, padV)
          gravity = Gravity.CENTER
          background = ContextCompat.getDrawable(context, R.drawable.tellomi_emoji_suggest_item_bg)
          contentDescription = item
          setOnClickListener { onSelect?.invoke(item) }
        }
        stack.addView(
          button,
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
          )
        )
      }
      scrollTo(0, 0)
    }
    visibility = if (newEmoji.isEmpty()) GONE else VISIBLE
    val nowHidden = visibility != VISIBLE
    return wasHidden != nowHidden
  }
}
