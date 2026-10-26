/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.ui.emoji

/**
 * Tellomi（审计 3.4 / F22）：打字时的表情建议——纯逻辑，对齐 iOS `TellomiEmojiSuggest`（#378）。
 *
 * 规则（行为参考 Telegram，代码自写）：
 * - 光标前以 `:` 开头、后面跟至少 2 个 ASCII 字母/数字/下划线的词（如 `:smi`）：按关键词前缀匹配，替换整个 `:smi`。
 * - 光标前最后一个词（英文按空白/标点切，中文取光标前紧挨的 1–4 个汉字中最长的完整关键词）**完整**等于关键词：给出建议，替换这个词。
 * - 光标不在末尾的词中间、文字含选区、或以 `::` / URL 形式（`http:`）出现时不出建议。
 * - 结果去重、保持表顺序，最多 [MAX_RESULTS] 个。
 */
object TellomiEmojiSuggest {

  const val MAX_RESULTS = 8
  internal const val MIN_COLON_QUERY_LENGTH = 2

  data class Suggestion(
    /** UTF-16 下标，和 EditText selection 一致。 */
    val replacementStart: Int,
    val replacementLength: Int,
    val query: String,
    val emoji: List<String>
  )

  /** 关键词 → 表情。小写英文、中文都放这里；顺序即优先级。 */
  internal val TABLE: List<Pair<String, List<String>>> = listOf(
    "smile" to listOf("😄", "😊", "🙂"),
    "laugh" to listOf("😂", "🤣"),
    "lol" to listOf("😂", "🤣"),
    "love" to listOf("❤️", "😍", "🥰"),
    "heart" to listOf("❤️", "💕"),
    "kiss" to listOf("😘", "💋"),
    "cry" to listOf("😢", "😭"),
    "sad" to listOf("😢", "😞"),
    "angry" to listOf("😠", "😡"),
    "ok" to listOf("👌", "🆗"),
    "yes" to listOf("👍"),
    "no" to listOf("🙅", "👎"),
    "thanks" to listOf("🙏"),
    "thumbsup" to listOf("👍"),
    "clap" to listOf("👏"),
    "fire" to listOf("🔥"),
    "party" to listOf("🎉", "🥳"),
    "cool" to listOf("😎"),
    "think" to listOf("🤔"),
    "sleep" to listOf("😴"),
    "wow" to listOf("😮", "🤩"),
    "cat" to listOf("🐱"),
    "dog" to listOf("🐶"),
    "coffee" to listOf("☕️"),
    "beer" to listOf("🍺"),
    "cake" to listOf("🎂"),
    "sun" to listOf("☀️"),
    "rain" to listOf("🌧️"),
    "哈哈" to listOf("😂", "🤣", "😄"),
    "笑" to listOf("😄", "😂"),
    "爱" to listOf("❤️", "😍"),
    "爱你" to listOf("😘", "❤️"),
    "哭" to listOf("😭", "😢"),
    "难过" to listOf("😢", "😞"),
    "生气" to listOf("😠", "😡"),
    "好的" to listOf("👌", "👍"),
    "好" to listOf("👍", "👌"),
    "谢谢" to listOf("🙏", "😊"),
    "赞" to listOf("👍"),
    "鼓掌" to listOf("👏"),
    "厉害" to listOf("👍", "🔥"),
    "生日快乐" to listOf("🎂", "🎉"),
    "晚安" to listOf("😴", "🌙"),
    "早安" to listOf("☀️", "😊"),
    "思考" to listOf("🤔"),
    "咖啡" to listOf("☕️"),
    "啤酒" to listOf("🍺"),
    "猫" to listOf("🐱"),
    "狗" to listOf("🐶")
  )

  private const val MAX_HAN_KEYWORD_LENGTH = 4

  fun suggestion(text: String, selectionStart: Int, selectionEnd: Int): Suggestion? {
    if (selectionStart != selectionEnd) return null
    if (selectionStart < 0 || selectionStart > text.length) return null
    val cursor = selectionStart
    // 光标后紧跟字母/数字/汉字 = 在词中间，不出。
    if (cursor < text.length) {
      val next = text.codePointAt(cursor)
      if (isWordCodePoint(next) || isHan(next)) return null
    }
    val before = text.substring(0, cursor)
    val scalars = before.codePoints().toArray().toList()

    // 1) `:query`
    var i = scalars.size
    while (i > 0 && isAsciiWord(scalars[i - 1])) i -= 1
    val wordLen = scalars.size - i
    if (wordLen > 0) {
      val word = String(scalars.subList(i, scalars.size).toIntArray(), 0, wordLen).lowercase()
      val utf16Len = word.length
      if (i > 0 && scalars[i - 1] == ':'.code) {
        val colonIdx = i - 1
        val precededOk = colonIdx == 0 || isBoundary(scalars[colonIdx - 1])
        if (!precededOk || wordLen < MIN_COLON_QUERY_LENGTH) return null
        val emoji = collect { kw ->
          kw.startsWith(word) && kw.all { ch -> isAsciiWord(ch.code) }
        }
        if (emoji.isEmpty()) return null
        return Suggestion(
          replacementStart = cursor - utf16Len - 1,
          replacementLength = utf16Len + 1,
          query = word,
          emoji = emoji
        )
      }
      // 2) 完整英文词
      if (!(i == 0 || isBoundary(scalars[i - 1]))) return null
      val emoji = collect { it == word }
      if (emoji.isEmpty()) return null
      return Suggestion(
        replacementStart = cursor - utf16Len,
        replacementLength = utf16Len,
        query = word,
        emoji = emoji
      )
    }

    // 3) 中文：光标前连续汉字里，取最长的、完整等于关键词的后缀。
    var h = scalars.size
    while (h > 0 && isHan(scalars[h - 1]) && scalars.size - h < MAX_HAN_KEYWORD_LENGTH) {
      h -= 1
    }
    if (h >= scalars.size) return null
    for (start in h until scalars.size) {
      val candidate = String(scalars.subList(start, scalars.size).toIntArray(), 0, scalars.size - start)
      val emoji = collect { it == candidate }
      if (emoji.isNotEmpty()) {
        val len = candidate.length
        return Suggestion(
          replacementStart = cursor - len,
          replacementLength = len,
          query = candidate,
          emoji = emoji
        )
      }
    }
    return null
  }

  /** 选中一个建议后的新文字和光标位置。 */
  fun apply(suggestion: Suggestion, emoji: String, text: String): Pair<String, Int> {
    val start = suggestion.replacementStart
    val end = start + suggestion.replacementLength
    val out = text.substring(0, start) + emoji + text.substring(end)
    return out to (start + emoji.length)
  }

  private fun collect(match: (String) -> Boolean): List<String> {
    val out = ArrayList<String>(MAX_RESULTS)
    for ((keyword, row) in TABLE) {
      if (!match(keyword)) continue
      for (e in row) {
        if (e !in out) {
          out.add(e)
          if (out.size == MAX_RESULTS) return out
        }
      }
    }
    return out
  }

  internal fun isAsciiWord(cp: Int): Boolean {
    if (cp > 0x7F) return false
    val ch = cp.toChar()
    return ch.isLetterOrDigit() || ch == '_'
  }

  private fun isWordCodePoint(cp: Int): Boolean {
    return Character.isLetterOrDigit(cp) && !isHan(cp)
  }

  private fun isBoundary(cp: Int): Boolean {
    if (Character.isWhitespace(cp) || isHan(cp)) return true
    if (cp == ':'.code || cp == '/'.code) return false
    val type = Character.getType(cp)
    return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
      type == Character.DASH_PUNCTUATION.toInt() ||
      type == Character.START_PUNCTUATION.toInt() ||
      type == Character.END_PUNCTUATION.toInt() ||
      type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
      type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
      type == Character.OTHER_PUNCTUATION.toInt()
  }

  internal fun isHan(cp: Int): Boolean {
    return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
  }
}
