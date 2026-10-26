/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.ui.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计 3.4（F22）：打字时的表情建议，纯逻辑。对齐 iOS TellomiEmojiSuggestTest。 */
class TellomiEmojiSuggestTest {

  private fun s(text: String, cursor: Int = text.length): TellomiEmojiSuggest.Suggestion? {
    return TellomiEmojiSuggest.suggestion(text, cursor, cursor)
  }

  @Test
  fun colonPrefixQuery() {
    val r = s("hi :smi")!!
    assertEquals("smi", r.query)
    assertEquals("😄", r.emoji.first())
    assertEquals(3, r.replacementStart)
    assertEquals(4, r.replacementLength)
  }

  @Test
  fun colonQueryNeedsTwoChars() {
    assertNull(s(":s"))
    assertNotNull(s(":lo"))
  }

  @Test
  fun colonQueryNotAfterWordOrUrl() {
    assertNull(s("http:lo"))
    assertNull(s("a::lo"))
    assertNull(s("https://love"))
  }

  @Test
  fun wholeEnglishWord() {
    val r = s("I am OK")!!
    assertEquals("ok", r.query)
    assertEquals(listOf("👌", "🆗"), r.emoji)
    assertEquals(5, r.replacementStart)
    assertEquals(2, r.replacementLength)
    assertNull(s("smi"))
  }

  @Test
  fun chineseLongestSuffix() {
    val r = s("祝你生日快乐")!!
    assertEquals("生日快乐", r.query)
    assertEquals(2, r.replacementStart)
    assertEquals(4, r.replacementLength)
    assertEquals("哈哈", s("哈哈哈")!!.query)
    assertEquals("好", s("太好")!!.query)
  }

  @Test
  fun noSuggestionMidWordOrWithSelection() {
    assertNull(s("okay", cursor = 2))
    assertNull(s("好的呀", cursor = 2))
    assertNull(TellomiEmojiSuggest.suggestion("ok", 0, 2))
    assertNull(s(""))
    assertNull(s("ok "))
  }

  @Test
  fun cursorInMiddleBeforeSpace() {
    val r = s("lol and more", cursor = 3)!!
    assertEquals("lol", r.query)
  }

  @Test
  fun resultsDedupedAndCapped() {
    assertNull(s(":l"))
    assertNull(s(":c"))
    val love = s(":lo")!!
    assertEquals(love.emoji.toSet().size, love.emoji.size)
    assertTrue(love.emoji.size <= TellomiEmojiSuggest.MAX_RESULTS)
  }

  @Test
  fun applyReplacesQueryAndMovesCursor() {
    val text = "好的 :smi"
    val r = s(text)!!
    val (out, cursor) = TellomiEmojiSuggest.apply(r, "😄", text)
    assertEquals("好的 😄", out)
    assertEquals(out.length, cursor)
  }

  @Test
  fun emojiTableHasNoEmptyRowsOrDuplicateKeywords() {
    val kws = TellomiEmojiSuggest.TABLE.map { it.first }
    assertEquals(kws.toSet().size, kws.size)
    assertTrue(TellomiEmojiSuggest.TABLE.all { it.second.isNotEmpty() })
  }
}
