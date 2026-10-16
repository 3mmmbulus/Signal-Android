/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedTextTest {

  private val grin = "\uD83D\uDE00" // 😀, one code point, two UTF-16 units
  private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧, 5 code points
  private val thumbsUpDark = "\uD83D\uDC4D\uD83C\uDFFF" // 👍🏿, 2 code points
  private val flag = "\uD83C\uDDE8\uD83C\uDDF3" // 🇨🇳, 2 code points

  private fun String.isWellFormed(): Boolean {
    var i = 0
    while (i < length) {
      val c = this[i]
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
        i += 2
      } else if (Character.isLowSurrogate(c)) {
        return false
      } else {
        i++
      }
    }
    return true
  }

  @Test
  fun `short text is returned as is`() {
    assertThat("abc".takeCodePoints(3)).isEqualTo("abc")
    assertThat("abc".takeCodePoints(10)).isEqualTo("abc")
    assertThat("".takeCodePoints(5)).isEqualTo("")
    assertThat("${grin}${grin}".takeCodePoints(2)).isEqualTo("${grin}${grin}")
  }

  @Test
  fun `ascii is cut at the limit`() {
    assertThat("abcdef".takeCodePoints(3)).isEqualTo("abc")
    assertThat("abcdef".takeCodePoints(0)).isEqualTo("")
    assertThat("abcdef".takeCodePoints(-1)).isEqualTo("")
  }

  @Test
  fun `a surrogate pair is never split and the limit counts code points`() {
    // take(2) ends in the middle of the first emoji
    assertThat("a${grin}${grin}b".take(2).isWellFormed()).isFalse()
    assertThat("a${grin}${grin}b".takeCodePoints(3)).isEqualTo("a${grin}${grin}")
    assertThat("${grin}${grin}${grin}".takeCodePoints(2)).isEqualTo("${grin}${grin}")
    for (n in 0..8) {
      assertThat("${grin}x${grin}y${grin}z${grin}".takeCodePoints(n).isWellFormed()).isTrue()
    }
  }

  @Test
  fun `a joined emoji is not cut into pieces`() {
    // 5 code points; 3 would leave man + joiner + woman (a broken family)
    assertThat("ab${family}cd".takeCodePoints(4)).isEqualTo("ab")
    assertThat("ab${family}cd".takeCodePoints(7)).isEqualTo("ab${family}")
    assertThat("ab${family}cd".takeCodePoints(8)).isEqualTo("ab${family}c")
  }

  @Test
  fun `skin tone modifiers and flags stay with their base`() {
    assertThat("a${thumbsUpDark}b".takeCodePoints(2)).isEqualTo("a")
    assertThat("a${thumbsUpDark}b".takeCodePoints(3)).isEqualTo("a${thumbsUpDark}")
    assertThat("a${flag}b".takeCodePoints(2)).isEqualTo("a")
    assertThat("a${flag}b".takeCodePoints(3)).isEqualTo("a${flag}")
  }

  @Test
  fun `combining marks stay with their letter`() {
    val eAcute = "e\u0301" // é as two code points
    assertThat("a${eAcute}b".takeCodePoints(2)).isEqualTo("a")
    assertThat("a${eAcute}b".takeCodePoints(3)).isEqualTo("a${eAcute}")
  }

  @Test
  fun `a single character longer than the limit falls back to a code point cut instead of an empty string`() {
    val zalgo = "e" + "\u0301".repeat(10)
    assertThat(zalgo.takeCodePoints(4)).isEqualTo("e\u0301\u0301\u0301")
  }

  @Test
  fun `the result never exceeds the limit the engine counts`() {
    val text = "你好${grin}${family}${flag}${thumbsUpDark}e\u0301abc".repeat(5)
    for (n in 0..60) {
      val cut = text.takeCodePoints(n)
      assertThat(cut.codePointCount(0, cut.length) <= n).isTrue()
      assertThat(cut.isWellFormed()).isTrue()
      assertThat(text.startsWith(cut)).isTrue()
    }
  }

  @Test
  fun `ellipsis only when something was cut`() {
    assertThat("abc".takeCodePointsWithEllipsis(3)).isEqualTo("abc")
    assertThat("abcd".takeCodePointsWithEllipsis(3)).isEqualTo("abc…")
    assertThat("${grin}${grin}${grin}".takeCodePointsWithEllipsis(3)).isEqualTo("${grin}${grin}${grin}")
    assertThat("${grin}${grin}${grin}${grin}".takeCodePointsWithEllipsis(3)).isEqualTo("${grin}${grin}${grin}…")
    // 60 emoji is 120 UTF-16 units: the old `length > 60` check would have cut it
    assertThat(grin.repeat(60).takeCodePointsWithEllipsis(60)).isEqualTo(grin.repeat(60))
  }
}
