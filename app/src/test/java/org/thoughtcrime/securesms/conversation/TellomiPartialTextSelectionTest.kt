/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TellomiPartialTextSelectionTest {

  @Test
  fun `copies only the selected part`() {
    assertEquals("world", TellomiPartialTextSelection.textToCopy(6, 11, "hello world"))
  }

  @Test
  fun `copies CJK part`() {
    assertEquals("明天", TellomiPartialTextSelection.textToCopy(2, 4, "我们明天见"))
  }

  @Test
  fun `reversed handles still copy`() {
    assertEquals("hello", TellomiPartialTextSelection.textToCopy(5, 0, "hello world"))
  }

  @Test
  fun `empty selection copies nothing`() {
    assertNull(TellomiPartialTextSelection.textToCopy(3, 3, "hello"))
    assertNull(TellomiPartialTextSelection.textToCopy(0, 0, ""))
  }

  @Test
  fun `never splits an emoji or surrogate pair`() {
    val text = "a👍🏽b"
    // 1 falls inside the 👍🏽 cluster (4 UTF-16 units); widen to the whole cluster
    assertEquals("a👍🏽", TellomiPartialTextSelection.textToCopy(0, 2, text))
    assertEquals("👍🏽b", TellomiPartialTextSelection.textToCopy(2, text.length, text))
  }

  @Test
  fun `never splits a flag`() {
    val text = "x🇨🇳y"
    assertEquals("🇨🇳", TellomiPartialTextSelection.textToCopy(2, 3, text))
  }

  @Test
  fun `out of range is clamped`() {
    assertEquals("abc", TellomiPartialTextSelection.textToCopy(-5, 99, "abc"))
  }

  @Test
  fun `initial range is whole text`() {
    val text = "早上好 👋"
    assertEquals(0 until text.length, TellomiPartialTextSelection.initialRange(text))
    assertTrue(TellomiPartialTextSelection.initialRange("").isEmpty())
  }

  @Test
  fun `whole text detection`() {
    assertTrue(TellomiPartialTextSelection.isWholeText(0, 5, "hello"))
    assertFalse(TellomiPartialTextSelection.isWholeText(1, 5, "hello"))
    assertFalse(TellomiPartialTextSelection.isWholeText(0, 0, ""))
  }

  @Test
  fun `menu shows select text only for non view-once messages with text`() {
    assertTrue(TellomiPartialTextSelection.canSelectText(isViewOnce = false, body = "hi", hasTextSlide = false))
    assertTrue(TellomiPartialTextSelection.canSelectText(isViewOnce = false, body = null, hasTextSlide = true))
    assertFalse(TellomiPartialTextSelection.canSelectText(isViewOnce = true, body = "hi", hasTextSlide = false))
    assertFalse(TellomiPartialTextSelection.canSelectText(isViewOnce = false, body = "  ", hasTextSlide = false))
    assertFalse(TellomiPartialTextSelection.canSelectText(isViewOnce = false, body = null, hasTextSlide = false))
  }
}
