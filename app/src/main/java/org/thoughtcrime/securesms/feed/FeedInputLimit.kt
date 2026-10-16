/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import java.text.BreakIterator

/**
 * 输入框的字数限制（和 iOS 的 `FeedInputLimit` 同一套行为，决定：tellomi测试，2026-10-16）：
 * 评论 500、长文标题 100、昵称 32、简介 200 **到上限就不让再输入**，并给出剩余字数（Telegram 的做法）；
 * 粘贴超长时按码点数截到上限，**不会**切开一个字（emoji、ZWJ 家庭、国旗、带肤色的手势、字母加组合符号）；不做事后截断。
 * 数字和引擎一致，按 Unicode 码点数（Go `utf8.RuneCountInString`），不是 UTF-16 单元。
 *
 * 纯逻辑，不依赖 Android 框架：可以从 `InputFilter` / `TextWatcher` 用 [edit]（区间是 UTF-16 下标，和 `InputFilter.filter` 的 dest / dstart / dend 一样），
 * 也可以从 Compose 的 `onValueChange` 用 [change]（只有「改之前」和「改之后」两段整文本，自己算出改了哪一段）。UI 接线不在这里。
 *
 * 输入法组字（Compose `TextFieldValue.composition != null`、`EditText` 的 composing span，拼音 / 假名候选）：每敲一个键都是整段组字文本的替换，
 * 拦或截会把组字弄坏，所以 `composing = true` 时**一律放行**，剩余数可以是负的（[FeedInputEdit.isOver]）；发送 / 保存 / 发布在超了的时候不可用，
 * 组字结束后再调一次（[change] 同样的文本，`composing = false`，或 [clip]）按码点截一次。这是唯一的「事后」截断，输入没有在组字的时候不会被截。
 */
object FeedInputLimit {
  // 引擎的上限（content-engine `api/*.go`，按码点数）
  /** 评论（`MaxCommentChars = MaxPostChars`）。 */
  const val COMMENT_TEXT = 500

  /** 长文标题（`MaxTitleChars`）。 */
  const val ARTICLE_TITLE = 100

  /** 昵称（`MaxNameChars`）。 */
  const val PROFILE_NAME = 32

  /** 简介（`MaxBioChars`）。 */
  const val PROFILE_BIO = 200

  /** 动态正文、长文正文：以 `GET /v1/feed/config` 的 `limits` 为准，这里是引擎默认值（`MaxPostChars` / `MaxArticleChars`）。 */
  const val POST_TEXT = 500
  const val ARTICLE_TEXT = 10_000
  const val APPEAL_REASON = 500
  const val REPORT_DETAIL = 500
  const val REPORT_RELATIONSHIP = 40
  const val POLL_OPTION = 40
  const val KEYWORD = 20
  const val SEARCH_QUERY = 50

  /** 码点数（和引擎同口径）。 */
  fun count(text: String): Int = text.codePointCount(0, text.length)

  /** 剩余字数（码点）；超了是负数。 */
  fun remaining(text: String, limit: Int): Int = limit - count(text)

  fun fits(text: String, limit: Int): Boolean = count(text) <= limit

  /**
   * 最多 [limit] 个码点，**只在字的边界上截**：放不下的那个字整个去掉（一个字就比 [limit] 还长时得到空串，不留半个）。
   * 已经够短就原样返回；[limit] <= 0 得到空串。
   * （和 [takeCodePoints] 不同：那个遇到一个字比上限还长会退回按码点硬切，这里不会。）
   */
  fun cap(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (count(text) <= limit) return text
    val hardEnd = text.offsetByCodePoints(0, limit)
    val iterator = BreakIterator.getCharacterInstance().also { it.setText(text) }
    val end = if (iterator.isBoundary(hardEnd)) hardEnd else iterator.preceding(hardEnd)
    return if (end <= 0) "" else text.substring(0, end)
  }

  /**
   * `InputFilter` / `TextWatcher` 这种钩子：把 [current] 里 `[start, end)`（UTF-16 下标）换成 [replacement]，结果受 [limit]（码点）限制。
   * - 删除（[replacement] 为空）永远放行。
   * - 放得下：原样放行（[FeedInputEdit.accepted]）。
   * - 放不下：把 [replacement] 按码点截到还剩的位置（[FeedInputEdit.clipped]，调用方要自己设文本）；一个字都放不下（已满，或下一个字比剩余的还大，
   *   如剩 3 个位置却来了个 7 码点的 emoji）就整个拦下（[FeedInputEdit.blocked]，文本不变）。
   * - 区间不合法（越界、反了、劈开一个代理对）：拦下，文本不变。
   * - [composing]：输入法组字中，一律放行（见类型说明）。
   */
  fun edit(current: String, start: Int, end: Int, replacement: String, limit: Int, composing: Boolean = false): FeedInputEdit {
    val limit = limit.coerceAtLeast(0)
    if (!validRange(current, start, end)) {
      return FeedInputEdit(
        current = current,
        text = current,
        inserted = "",
        remaining = remaining(current, limit),
        accepted = false,
        blocked = true,
        clipped = false,
        caret = start.coerceIn(0, current.length)
      )
    }
    val prefix = current.substring(0, start)
    val suffix = current.substring(end)
    val kept = count(prefix) + count(suffix)

    fun built(inserted: String, accepted: Boolean, clipped: Boolean): FeedInputEdit {
      val text = prefix + inserted + suffix
      return FeedInputEdit(
        current = current,
        text = text,
        inserted = inserted,
        remaining = remaining(text, limit),
        accepted = accepted,
        blocked = false,
        clipped = clipped,
        caret = prefix.length + inserted.length
      )
    }

    if (composing || replacement.isEmpty() || kept + count(replacement) <= limit) {
      return built(replacement, accepted = true, clipped = false)
    }
    val room = limit - kept
    val inserted = if (room > 0) cap(replacement, room) else ""
    if (inserted.isEmpty()) {
      return FeedInputEdit(
        current = current,
        text = current,
        inserted = "",
        remaining = remaining(current, limit),
        accepted = false,
        blocked = true,
        clipped = false,
        caret = end
      )
    }
    return built(inserted, accepted = false, clipped = true)
  }

  /**
   * 整段文本被换掉（粘贴后整体设置、恢复草稿、没有区间的输入框）：超过上限就按码点截到上限。
   * [composing] 为 true 时不动（组字结束后再调一次）。
   */
  fun clip(text: String, limit: Int, composing: Boolean = false): FeedInputEdit {
    val limit = limit.coerceAtLeast(0)
    if (composing || fits(text, limit)) {
      return FeedInputEdit(current = text, text = text, inserted = text, remaining = remaining(text, limit), accepted = true, blocked = false, clipped = false, caret = text.length)
    }
    val cut = cap(text, limit)
    return FeedInputEdit(current = text, text = cut, inserted = cut, remaining = remaining(cut, limit), accepted = false, blocked = false, clipped = true, caret = cut.length)
  }

  /**
   * Compose `onValueChange(newText)` 这种只给「改之前」[previous] 和「改之后」[proposed] 整文本的钩子。
   * 先在两段文本里找出共同的开头和结尾（不劈开代理对），中间那一段就是这次的区间和替换内容，再按 [edit] 的规则处理：
   * - [composing] 或 [proposed] 放得下：照收（`accepted`，文本 = [proposed]）。
   * - 只是删除：照收（超了的文本删字也可以，剩余数仍可能是负的）。
   * - [previous] 已经超了（组字刚结束、程序设置过长的文本），又不是单纯的删除：把 [proposed] 整个按码点截到上限（[clip]），
   *   不然「拦下」会把组字时的拼音原样留住。
   * - 其余同 [edit]：截一部分放进去（`clipped`）或整个拦下（`blocked`，文本 = [previous]）。
   * 调用方把 [FeedInputEdit.text] 当作新的状态；Compose 里状态没变，输入框就会还原成原来的字。
   */
  fun change(previous: String, proposed: String, limit: Int, composing: Boolean = false): FeedInputEdit {
    val limit = limit.coerceAtLeast(0)
    if (composing || fits(proposed, limit)) {
      return FeedInputEdit(
        current = previous,
        text = proposed,
        inserted = proposed,
        remaining = remaining(proposed, limit),
        accepted = true,
        blocked = false,
        clipped = false,
        caret = proposed.length
      )
    }
    if (proposed == previous) return clip(proposed, limit).copy(current = previous)

    var p = 0
    val common = minOf(previous.length, proposed.length)
    while (p < common && previous[p] == proposed[p]) p++
    if (p > 0 && previous[p - 1].isHighSurrogate()) p--
    var s = 0
    while (s < common - p && previous[previous.length - 1 - s] == proposed[proposed.length - 1 - s]) s++
    if (s > 0 && previous[previous.length - s].isLowSurrogate()) s--
    val start = p
    val end = previous.length - s
    val replacement = proposed.substring(p, proposed.length - s)
    if (replacement.isEmpty()) {
      return FeedInputEdit(
        current = previous,
        text = proposed,
        inserted = "",
        remaining = remaining(proposed, limit),
        accepted = true,
        blocked = false,
        clipped = false,
        caret = start
      )
    }
    if (!fits(previous, limit)) return clip(proposed, limit).copy(current = previous)
    return edit(previous, start, end, replacement, limit)
  }

  private fun validRange(text: String, start: Int, end: Int): Boolean {
    if (start < 0 || end < start || end > text.length) return false
    if (start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) return false
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) return false
    return true
  }
}

/** 一次输入的结果。 */
data class FeedInputEdit(
  /** 输入之前的文本。 */
  val current: String,
  /** 输入之后该显示的文本（拦下时 = [current]）。 */
  val text: String,
  /** 实际放进去的那一段（可能比用户给的短；拦下是空）。 */
  val inserted: String,
  /** 还能再输入多少个码点；超了（组字中、程序设置过长的文本）是负数。 */
  val remaining: Int,
  /** 用户的改动**原样**生效。 */
  val accepted: Boolean,
  /** 一个字都没放进去（已满 / 放不下 / 区间不合法）；文本没变。 */
  val blocked: Boolean,
  /** 放进去了一部分（粘贴被截）；调用方要自己设文本。 */
  val clipped: Boolean,
  /** 输入后光标的 UTF-16 位置（[blocked] 时是原选区的末尾）。 */
  val caret: Int
) {
  val changed: Boolean get() = text != current

  /** 碰到了上限（拦下或截了）——UI 可以抖一下计数。 */
  val limitHit: Boolean get() = blocked || clipped

  val isOver: Boolean get() = remaining < 0
}
