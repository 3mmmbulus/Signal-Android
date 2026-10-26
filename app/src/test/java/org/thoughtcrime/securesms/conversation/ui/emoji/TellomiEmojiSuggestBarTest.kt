/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.ui.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telegram 对照 3.4（F22）：输入框上方表情建议栏的逻辑。对齐 iOS TellomiEmojiSuggestBarTest。 */
class TellomiEmojiSuggestBarTest {

  @Test
  fun noSuggestionsHidesBar() {
    assertEquals(emptyList<String>(), TellomiEmojiSuggestBarModel.visibleEmoji("hello there", "hello there".length, "hello there".length))
    assertEquals(emptyList<String>(), TellomiEmojiSuggestBarModel.visibleEmoji("", 0, 0))
  }

  @Test
  fun colonQueryShowsAtMostEight() {
    val text = "hi :smi"
    val emoji = TellomiEmojiSuggestBarModel.visibleEmoji(text, text.length, text.length)
    assertFalse(emoji.isEmpty())
    assertTrue(emoji.size <= 8)
    assertEquals(emoji.toSet().size, emoji.size)
  }

  @Test
  fun rangeSelectionShowsNothing() {
    val text = "hi :smi"
    assertEquals(emptyList<String>(), TellomiEmojiSuggestBarModel.visibleEmoji(text, 3, 7))
  }

  @Test
  fun applyingReplacesQueryAndMovesCursor() {
    val text = "hi :smi"
    val first = TellomiEmojiSuggestBarModel.visibleEmoji(text, text.length, text.length).first()
    val result = TellomiEmojiSuggestBarModel.applying(first, text, text.length, text.length)!!
    assertEquals("hi $first", result.text)
    assertEquals(3, result.replacedStart)
    assertEquals(4, result.replacedLength)
    assertEquals("hi $first".length, result.cursor)
  }

  @Test
  fun applyingUnknownEmojiOrStaleTextIsNull() {
    val text = "hi :smi"
    assertNull(TellomiEmojiSuggestBarModel.applying("🦖🦖", text, text.length, text.length))
    assertNull(TellomiEmojiSuggestBarModel.applying("😄", "hi there", "hi there".length, "hi there".length))
  }

  @Test
  fun barModelVisibleMatchesSuggestion() {
    assertNotNull(TellomiEmojiSuggest.suggestion("ok", 2, 2))
    assertEquals(
      TellomiEmojiSuggest.suggestion("ok", 2, 2)!!.emoji.distinct().take(8),
      TellomiEmojiSuggestBarModel.visibleEmoji("ok", 2, 2)
    )
  }
}
