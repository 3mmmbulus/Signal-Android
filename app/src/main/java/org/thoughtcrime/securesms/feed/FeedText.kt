/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import java.text.BreakIterator

/**
 * 动态里各处的长度上限（搜索 50、举报说明 500、申诉 500、昵称 32、简介 200、分享摘要 60 ……）。
 * 内容引擎按 Unicode 码点数算（Go 的 `utf8.RuneCountInString`），而 Kotlin 的 `String.take(n)` 按 UTF-16 单位切：
 * 一个 emoji 占两个单位，切在中间会留下半个字符（乱码），而且 emoji 多的文字白白少了一半额度。
 *
 * [takeCodePoints]：最多 [max] 个码点，**不会**切开代理对，也不会切开一个「字符」（组合符号、带肤色或 ZWJ 连起来的
 * emoji、国旗，按 [BreakIterator] 的字符边界），所以结果一定不超过引擎的上限。
 * 只有整个字符本身就比 [max] 还长（极端的叠加符号）时才退回按码点硬切，免得返回空串。
 */
fun String.takeCodePoints(max: Int): String {
  if (max <= 0) return ""
  if (codePointCount(0, length) <= max) return this

  val hardEnd = offsetByCodePoints(0, max)
  val iterator = BreakIterator.getCharacterInstance().also { it.setText(this) }
  val end = if (iterator.isBoundary(hardEnd)) hardEnd else iterator.preceding(hardEnd)
  return if (end <= 0) substring(0, hardEnd) else substring(0, end)
}

/** 超过 [max] 个码点就切短并加「…」（分享 / 引用的摘要）；没超过原样返回。 */
fun String.takeCodePointsWithEllipsis(max: Int): String {
  return if (codePointCount(0, length) > max) takeCodePoints(max) + "…" else this
}
