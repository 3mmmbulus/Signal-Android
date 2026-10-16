/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.Test

/** iOS `TellomiFeedInputLimitTests` 的 Kotlin 版（同样的用例，区间用 UTF-16 下标 `[start, end)`），加上 Compose 用的 [FeedInputLimit.change]。 */
class FeedInputLimitTest {

  private val flag = "\uD83C\uDDE8\uD83C\uDDF3" // 🇨🇳，2 个码点
  private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 👨‍👩‍👧‍👦，7 个码点
  private val grin = "\uD83D\uDE00" // 😀，1 个码点
  private val thumbs = "\uD83D\uDC4D\uD83C\uDFFD" // 👍🏽，2 个码点

  private fun hasLoneSurrogate(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
        i += 2
        continue
      }
      if (c.isSurrogate()) return true
      i++
    }
    return false
  }

  private fun append(current: String, replacement: String, limit: Int, composing: Boolean = false) =
    FeedInputLimit.edit(current, current.length, current.length, replacement, limit, composing)

  @Test
  fun `named limits match the engine and the limits already used elsewhere`() {
    assertThat(FeedInputLimit.COMMENT_TEXT).isEqualTo(500)
    assertThat(FeedInputLimit.ARTICLE_TITLE).isEqualTo(100)
    assertThat(FeedInputLimit.PROFILE_NAME).isEqualTo(32)
    assertThat(FeedInputLimit.PROFILE_BIO).isEqualTo(200)
    assertThat(FeedInputLimit.POST_TEXT).isEqualTo(500)
    assertThat(FeedInputLimit.ARTICLE_TEXT).isEqualTo(10_000)
    assertThat(FeedInputLimit.APPEAL_REASON).isEqualTo(500)
    assertThat(FeedInputLimit.REPORT_DETAIL).isEqualTo(500)
    assertThat(FeedInputLimit.REPORT_RELATIONSHIP).isEqualTo(40)
    assertThat(FeedInputLimit.POLL_OPTION).isEqualTo(40)
    assertThat(FeedInputLimit.KEYWORD).isEqualTo(20)
    assertThat(FeedInputLimit.SEARCH_QUERY).isEqualTo(50)
  }

  @Test
  fun `typing below the limit is accepted as is`() {
    val e = append("abc", "d", 5)
    assertThat(e.accepted).isTrue()
    assertThat(e.blocked).isFalse()
    assertThat(e.clipped).isFalse()
    assertThat(e.text).isEqualTo("abcd")
    assertThat(e.inserted).isEqualTo("d")
    assertThat(e.remaining).isEqualTo(1)
    assertThat(e.caret).isEqualTo(4)
    assertThat(e.limitHit).isFalse()
  }

  @Test
  fun `typing exactly up to the limit is accepted`() {
    val e = append("abcd", "e", 5)
    assertThat(e.accepted).isTrue()
    assertThat(e.remaining).isEqualTo(0)
  }

  @Test
  fun `typing at the limit is blocked and the text stays unchanged`() {
    val e = append("abcde", "f", 5)
    assertThat(e.accepted).isFalse()
    assertThat(e.blocked).isTrue()
    assertThat(e.clipped).isFalse()
    assertThat(e.text).isEqualTo("abcde")
    assertThat(e.inserted).isEqualTo("")
    assertThat(e.remaining).isEqualTo(0)
    assertThat(e.caret).isEqualTo(5)
    assertThat(e.changed).isFalse()
    assertThat(e.limitHit).isTrue()
  }

  @Test
  fun `deleting is always allowed even when the text is over the limit`() {
    val over = "a".repeat(12) // 程序设置的过长文本
    val e = FeedInputLimit.edit(over, 11, 12, "", 10)
    assertThat(e.accepted).isTrue()
    assertThat(e.text.length).isEqualTo(11)
    assertThat(e.remaining).isEqualTo(-1)
    assertThat(e.isOver).isTrue()
  }

  @Test
  fun `a paste is clipped to the limit by code point`() {
    val e = append("ab", "cdefgh", 5)
    assertThat(e.accepted).isFalse()
    assertThat(e.blocked).isFalse()
    assertThat(e.clipped).isTrue()
    assertThat(e.text).isEqualTo("abcde")
    assertThat(e.inserted).isEqualTo("cde")
    assertThat(e.remaining).isEqualTo(0)
    assertThat(e.caret).isEqualTo(5)
    assertThat(e.changed).isTrue()
  }

  @Test
  fun `pasted emoji flags and ZWJ sequences are clipped by code point and never split`() {
    // 😀 = 1 个码点（2 个 UTF-16 单元）
    var e = FeedInputLimit.edit("", 0, 0, grin.repeat(10), 4)
    assertThat(e.text).isEqualTo(grin.repeat(4))
    assertThat(e.clipped).isTrue()
    // 🇨🇳 = 2 个码点：上限 5 只放得下 2 面旗（4 个码点），第 3 面放不下就整个去掉
    e = FeedInputLimit.edit("", 0, 0, flag.repeat(5), 5)
    assertThat(e.text).isEqualTo(flag.repeat(2))
    assertThat(e.remaining).isEqualTo(1)
    // 家庭 emoji = 7 个码点
    e = FeedInputLimit.edit("", 0, 0, family.repeat(3), 15)
    assertThat(e.text).isEqualTo(family.repeat(2))
    assertThat(e.remaining).isEqualTo(1)
    // 带肤色的手势 = 2 个码点
    e = FeedInputLimit.edit("", 0, 0, thumbs.repeat(3), 5)
    assertThat(e.text).isEqualTo(thumbs.repeat(2))
    // 剩 3 个位置，来了个 7 码点的 emoji：一个字都放不下 → 拦下，不留半个
    e = append("ab", family, 5)
    assertThat(e.blocked).isTrue()
    assertThat(e.text).isEqualTo("ab")
    // 同样的 emoji 剩 7 个位置刚好放得下
    e = append("ab", family, 9)
    assertThat(e.accepted).isTrue()
    assertThat(e.remaining).isEqualTo(0)
  }

  @Test
  fun `replacing a selected range counts the removed text as freed room`() {
    // "abcde" 满了；选中 "bc" 换成 "XYZ"：保留 3，余地 2 → 只放进 "XY"
    var e = FeedInputLimit.edit("abcde", 1, 3, "XYZ", 5)
    assertThat(e.clipped).isTrue()
    assertThat(e.text).isEqualTo("aXYde")
    assertThat(e.inserted).isEqualTo("XY")
    assertThat(e.caret).isEqualTo(3)
    // 满了，选中 1 个字换成 1 个字：放得下（不是 +1）
    e = FeedInputLimit.edit("abcde", 2, 3, "Z", 5)
    assertThat(e.accepted).isTrue()
    assertThat(e.text).isEqualTo("abZde")
    // 选中整段换成过长的粘贴：从 0 开始截
    e = FeedInputLimit.edit("abcde", 0, 5, "0123456789", 5)
    assertThat(e.text).isEqualTo("01234")
    assertThat(e.clipped).isTrue()
    // 选区里有一个多码点的字：它的码点数算作释放的余地（UTF-16 区间 = 4）
    val text = "a" + flag + "b"
    e = FeedInputLimit.edit(text, 1, 5, "XY", 4)
    assertThat(e.accepted).isTrue() // 保留 a、b 共 2 个，加 2 个 = 4
    assertThat(e.text).isEqualTo("aXYb")
  }

  @Test
  fun `inserting in the middle keeps the prefix and suffix`() {
    val e = FeedInputLimit.edit("abXYZ", 2, 2, "12345", 8)
    assertThat(e.text).isEqualTo("ab123XYZ")
    assertThat(e.inserted).isEqualTo("123")
    assertThat(e.caret).isEqualTo(5)
  }

  @Test
  fun `an invalid range is blocked`() {
    val ranges = listOf(9 to 9, 2 to 7, -1 to 0, 3 to 1, 0 to 4)
    for ((start, end) in ranges) {
      val e = FeedInputLimit.edit("abc", start, end, "x", 10)
      assertThat(e.blocked).isTrue()
      assertThat(e.text).isEqualTo("abc")
    }
    // 区间劈开一个代理对
    val e = FeedInputLimit.edit("a${grin}b", 2, 2, "x", 10)
    assertThat(e.blocked).isTrue()
    assertThat(e.text).isEqualTo("a${grin}b")
  }

  /** 组字（输入法的候选）：拦或截会把组字弄坏，所以放行，剩余数可以是负的；组字结束后 `clip` 再按码点截一次。 */
  @Test
  fun `composing text is never blocked or clipped but is clipped once when composition ends`() {
    val e = append("abcde", "nihao", 5, composing = true)
    assertThat(e.accepted).isTrue()
    assertThat(e.blocked).isFalse()
    assertThat(e.clipped).isFalse()
    assertThat(e.text).isEqualTo("abcdenihao")
    assertThat(e.remaining).isEqualTo(-5)
    assertThat(e.isOver).isTrue()

    assertThat(FeedInputLimit.clip(e.text, 5, composing = true).text).isEqualTo(e.text) // 还在组字：不动
    val done = FeedInputLimit.clip(e.text, 5)
    assertThat(done.clipped).isTrue()
    assertThat(done.text).isEqualTo("abcde")
    assertThat(done.remaining).isEqualTo(0)
  }

  @Test
  fun `clip of a whole text`() {
    assertThat(FeedInputLimit.clip("abc", 5).accepted).isTrue()
    assertThat(FeedInputLimit.clip("abc", 5).remaining).isEqualTo(2)
    val cut = FeedInputLimit.clip(family.repeat(3), 20)
    assertThat(cut.text).isEqualTo(family.repeat(2))
    assertThat(cut.clipped).isTrue()
    assertThat(FeedInputLimit.clip("", 5).remaining).isEqualTo(5)
    assertThat(FeedInputLimit.clip("abc", 0).text).isEqualTo("")
    assertThat(FeedInputLimit.clip("abc", -3).text).isEqualTo("")
  }

  @Test
  fun `remaining count is in code points`() {
    assertThat(FeedInputLimit.remaining("", 32)).isEqualTo(32)
    assertThat(FeedInputLimit.remaining("字字字", 32)).isEqualTo(29)
    assertThat(FeedInputLimit.remaining(flag, 32)).isEqualTo(30)
    assertThat(FeedInputLimit.remaining(family, 32)).isEqualTo(25)
    assertThat(FeedInputLimit.remaining(grin, 32)).isEqualTo(31) // UTF-16 单元数是 2，码点数是 1
    assertThat(FeedInputLimit.remaining("a".repeat(35), 32)).isEqualTo(-3)
    assertThat(FeedInputLimit.fits("abc", 3)).isTrue()
    assertThat(FeedInputLimit.fits("abcd", 3)).isFalse()
  }

  @Test
  fun `cap drops a character that does not fit instead of cutting it`() {
    assertThat(FeedInputLimit.cap(family, 3)).isEqualTo("") // 一个字就比上限长：空串，不是半个家庭
    assertThat(FeedInputLimit.cap(family, 7)).isEqualTo(family)
    assertThat(FeedInputLimit.cap("e\u0301e\u0301", 3)).isEqualTo("e\u0301") // é = e + 组合符 = 2 个码点，第二个放不下
    assertThat(FeedInputLimit.cap("abc", 0)).isEqualTo("")
  }

  /** 性质：任何输入之后（非组字），文本不会超过上限，剩余数不是负的。 */
  @Test
  fun `no non-composing edit ever ends over the limit`() {
    val pieces = listOf("", "a", "字", grin, flag, family, "e\u0301", "hello world $flag $family")
    for (limit in listOf(0, 1, 3, 7, 12)) {
      for (current in pieces) {
        if (!FeedInputLimit.fits(current, limit)) continue
        for (replacement in pieces) {
          for (loc in listOf(0, current.length)) {
            val e = FeedInputLimit.edit(current, loc, loc, replacement, limit)
            assertThat(FeedInputLimit.count(e.text), "$current+$replacement @$limit").isLessThanOrEqualTo(limit)
            assertThat(e.remaining).isGreaterThanOrEqualTo(0)
          }
        }
      }
    }
  }

  // ---- change(previous, proposed)：Compose onValueChange 用，从整文本算出区间 ----

  @Test
  fun `change accepts typing below the limit`() {
    val e = FeedInputLimit.change("abc", "abcd", 5)
    assertThat(e.accepted).isTrue()
    assertThat(e.text).isEqualTo("abcd")
    assertThat(e.remaining).isEqualTo(1)
  }

  @Test
  fun `change blocks a typed character at the limit and keeps the previous text`() {
    val atEnd = FeedInputLimit.change("abcde", "abcdef", 5)
    assertThat(atEnd.blocked).isTrue()
    assertThat(atEnd.text).isEqualTo("abcde")
    // 在中间输入：也是拦下，不是把末尾的字吃掉
    val inMiddle = FeedInputLimit.change("abcde", "abXcde", 5)
    assertThat(inMiddle.blocked).isTrue()
    assertThat(inMiddle.text).isEqualTo("abcde")
  }

  @Test
  fun `change clips a paste and keeps the text after the caret`() {
    val e = FeedInputLimit.change("abXYZ", "ab12345XYZ", 8)
    assertThat(e.clipped).isTrue()
    assertThat(e.text).isEqualTo("ab123XYZ")
    assertThat(e.caret).isEqualTo(5)
  }

  @Test
  fun `change clips an emoji paste by code point at the limit`() {
    val e = FeedInputLimit.change("", family.repeat(3), 15)
    assertThat(e.text).isEqualTo(family.repeat(2))
    assertThat(e.clipped).isTrue()
    val full = FeedInputLimit.change("ab", "ab$family", 5)
    assertThat(full.blocked).isTrue()
    assertThat(full.text).isEqualTo("ab")
  }

  @Test
  fun `change never splits a surrogate pair when finding the edited range`() {
    // 在 😀 后面粘贴一个同样以 😀 开头的文本：共同前缀不能停在代理对中间
    val e = FeedInputLimit.change(grin, grin + grin + "abc", 3)
    assertThat(e.text).isEqualTo(grin + grin + "a")
    // 末尾相同的低位代理
    val e2 = FeedInputLimit.change("a$grin", "a\uD83D\uDE01$grin", 2)
    assertThat(e2.blocked || e2.clipped).isTrue()
    assertThat(FeedInputLimit.count(e2.text)).isLessThanOrEqualTo(2)
    assertThat(hasLoneSurrogate(e2.text)).isFalse()
  }

  @Test
  fun `change finds the edited range on whole code points when only half of a pair is common`() {
    // 开头：😀 和 😁 共用高位代理，共同前缀不能停在代理对中间，否则区间不合法被拦下
    val head = FeedInputLimit.change(grin, "\uD83D\uDE01".repeat(4), 3)
    assertThat(head.clipped).isTrue()
    assertThat(head.text).isEqualTo("\uD83D\uDE01".repeat(3))
    // 结尾：两个不同的 emoji 共用低位代理，共同后缀同理
    val tail = FeedInputLimit.change(grin, "abc\uD83E\uDE00", 3)
    assertThat(tail.clipped).isTrue()
    assertThat(tail.text).isEqualTo("abc")
  }

  @Test
  fun `change allows deleting while over the limit and composing passes through`() {
    val over = "a".repeat(12)
    val deleted = FeedInputLimit.change(over, "a".repeat(11), 10)
    assertThat(deleted.accepted).isTrue()
    assertThat(deleted.remaining).isEqualTo(-1)

    val composing = FeedInputLimit.change("abcde", "abcdenihao", 5, composing = true)
    assertThat(composing.accepted).isTrue()
    assertThat(composing.remaining).isEqualTo(-5)
  }

  @Test
  fun `change settles an over-limit text once when composition ends`() {
    // 组字结束：文本没变（还超着），再调一次 → 按码点截
    val settled = FeedInputLimit.change("abcdenihao", "abcdenihao", 5)
    assertThat(settled.clipped).isTrue()
    assertThat(settled.text).isEqualTo("abcde")
    // 组字结束时输入法把拼音换成了汉字（文本变了，仍然超）：也是整段截，不是拦下后留着拼音
    val replaced = FeedInputLimit.change("abcdenihao", "abcde你好吗", 5)
    assertThat(replaced.clipped).isTrue()
    assertThat(replaced.text).isEqualTo("abcde")
    // 组字结束后的汉字放得下：照收
    val fits = FeedInputLimit.change("abcdenihao", "abc你好", 5)
    assertThat(fits.accepted).isTrue()
    assertThat(fits.text).isEqualTo("abc你好")
  }

  @Test
  fun `change never ends over the limit when not composing and the previous text fit`() {
    val pieces = listOf("", "a", "字", grin, flag, family, "e\u0301", "hello world $flag $family")
    for (limit in listOf(0, 1, 3, 7, 12)) {
      for (previous in pieces) {
        if (!FeedInputLimit.fits(previous, limit)) continue
        for (insert in pieces) {
          for (at in listOf(0, previous.length)) {
            val proposed = previous.substring(0, at) + insert + previous.substring(at)
            val e = FeedInputLimit.change(previous, proposed, limit)
            assertThat(FeedInputLimit.count(e.text), "$previous+$insert @$limit").isLessThanOrEqualTo(limit)
          }
        }
      }
    }
  }
}
