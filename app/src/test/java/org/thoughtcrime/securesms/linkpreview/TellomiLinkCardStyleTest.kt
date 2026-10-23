/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle.Kind
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle.Layout
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle.Text

/**
 * The unified link card style (PM 2026-10-19, after Apple Messages; DESIGN.md §9): which of the four styles, where the compass
 * goes, and where the two lines come from. Same cases as iOS `TellomiLinkCardStyleTest.swift` (tellomi/Signal-iOS aa97c012).
 */
class TellomiLinkCardStyleTest {

  private fun kind(layout: Layout?, hasImage: Boolean, hasTitle: Boolean, isPlainLink: Boolean = false): Kind = TellomiLinkCardStyle.kind(layout, hasImage, hasTitle, isPlainLink)

  @Test
  fun `a large image needs an image and the large layout`() {
    assertEquals(Kind.LARGE_IMAGE, kind(Layout.LARGE_IMAGE, hasImage = true, hasTitle = true))
    assertEquals(Kind.LARGE_IMAGE, kind(Layout.LARGE_IMAGE, hasImage = true, hasTitle = false))
  }

  @Test
  fun `the large layout without its image falls back to a strip`() {
    assertEquals(Kind.TEXT_STRIP, kind(Layout.LARGE_IMAGE, hasImage = false, hasTitle = true))
    assertEquals(Kind.DOMAIN_STRIP, kind(Layout.LARGE_IMAGE, hasImage = false, hasTitle = false))
  }

  @Test
  fun `a small image is an icon strip, with or without a title`() {
    assertEquals(Kind.ICON_STRIP, kind(Layout.ICON, hasImage = true, hasTitle = true))
    assertEquals(Kind.ICON_STRIP, kind(Layout.ICON, hasImage = true, hasTitle = false))
  }

  @Test
  fun `no layout decision with an image never guesses large`() {
    assertEquals(Kind.ICON_STRIP, kind(null, hasImage = true, hasTitle = true))
  }

  @Test
  fun `no image is the title strip, or the domain strip without a title`() {
    assertEquals(Kind.TEXT_STRIP, kind(Layout.NO_IMAGE, hasImage = false, hasTitle = true))
    assertEquals(Kind.TEXT_STRIP, kind(null, hasImage = false, hasTitle = true))
    assertEquals(Kind.DOMAIN_STRIP, kind(Layout.NO_IMAGE, hasImage = false, hasTitle = false))
    assertEquals(Kind.TEXT_STRIP, kind(Layout.NO_IMAGE, hasImage = true, hasTitle = true))
  }

  @Test
  fun `no layout decision without an image is the title strip or the domain strip, with the compass unless claimed`() {
    assertEquals(Kind.TEXT_STRIP, kind(null, hasImage = false, hasTitle = true))
    assertEquals(Kind.DOMAIN_STRIP, kind(null, hasImage = false, hasTitle = false))
    assertTrue(TellomiLinkCardStyle.showsCompass(kind(null, hasImage = false, hasTitle = true), isClaimed = false))
    assertFalse(TellomiLinkCardStyle.showsCompass(kind(null, hasImage = false, hasTitle = true), isClaimed = true))
    assertTrue(TellomiLinkCardStyle.showsCompass(kind(null, hasImage = false, hasTitle = false), isClaimed = true))
  }

  @Test
  fun `a plain link is always the domain strip`() {
    for (layout in listOf(Layout.LARGE_IMAGE, Layout.ICON, Layout.NO_IMAGE, null)) {
      for (hasImage in listOf(true, false)) {
        assertEquals(Kind.DOMAIN_STRIP, kind(layout, hasImage, hasTitle = true, isPlainLink = true))
      }
    }
  }

  @Test
  fun `the compass is only for a card with nothing, never for a claimed card with a title`() {
    assertFalse(TellomiLinkCardStyle.showsCompass(Kind.ICON_STRIP, isClaimed = true))
    assertFalse(TellomiLinkCardStyle.showsCompass(Kind.TEXT_STRIP, isClaimed = true))
    assertTrue(TellomiLinkCardStyle.showsCompass(Kind.TEXT_STRIP, isClaimed = false))
    assertTrue(TellomiLinkCardStyle.showsCompass(Kind.DOMAIN_STRIP, isClaimed = false))
    assertTrue(TellomiLinkCardStyle.showsCompass(Kind.DOMAIN_STRIP, isClaimed = true))
    assertFalse(TellomiLinkCardStyle.showsCompass(Kind.ICON_STRIP, isClaimed = false))
    assertFalse(TellomiLinkCardStyle.showsCompass(Kind.LARGE_IMAGE, isClaimed = false))
  }

  @Test
  fun `only the icon strip takes colours from its image`() {
    assertTrue(TellomiLinkCardStyle.isTinted(Kind.ICON_STRIP))
    assertFalse(TellomiLinkCardStyle.isTinted(Kind.LARGE_IMAGE))
    assertFalse(TellomiLinkCardStyle.isTinted(Kind.TEXT_STRIP))
    assertFalse(TellomiLinkCardStyle.isTinted(Kind.DOMAIN_STRIP))
  }

  @Test
  fun `the text keeps the title and the domain`() {
    assertEquals(Text("百度一下，你就知道", "baidu.com"), TellomiLinkCardStyle.text("百度一下，你就知道", "baidu.com", "https://www.baidu.com/"))
  }

  @Test
  fun `the text collapses white space and drops an empty title`() {
    assertEquals(Text("China UnionPay | 中国银联", "unionpay.com"), TellomiLinkCardStyle.text("  China UnionPay |\r\n   中国银联 ", "unionpay.com", null))
    assertEquals(Text("京东 自营", "jd.com"), TellomiLinkCardStyle.text("京东\u3000自营", "jd.com", null))
    assertEquals(Text(null, "jd.com"), TellomiLinkCardStyle.text(" \n ", "jd.com", null))
    assertEquals(Text(null, "jd.com"), TellomiLinkCardStyle.text(null, "jd.com", null))
  }

  @Test
  fun `a title equal to the domain is not repeated`() {
    assertEquals(Text(null, "baidu.com"), TellomiLinkCardStyle.text("Baidu.com", "baidu.com", null))
  }

  @Test
  fun `the domain falls back to the host`() {
    assertEquals(Text("Apple", "apple.com"), TellomiLinkCardStyle.text("Apple", null, "https://www.Apple.com/cn/"))
    assertEquals(Text(null, "jd.com"), TellomiLinkCardStyle.text(null, " ", "jd.com/abc"))
    assertNull(TellomiLinkCardStyle.text("x", null, null))
    assertNull(TellomiLinkCardStyle.text("x", null, "https://"))
  }

  @Test
  fun `the host for display`() {
    assertEquals("163.com", TellomiLinkCardStyle.hostForDisplay("https://WWW.163.com/news"))
    assertEquals("m.jd.com", TellomiLinkCardStyle.hostForDisplay("m.jd.com"))
    assertEquals("www.", TellomiLinkCardStyle.hostForDisplay("https://www."))
    assertNull(TellomiLinkCardStyle.hostForDisplay(""))
  }

  @Test
  fun `the numbers are the ones DESIGN md section 9 gives`() {
    assertEquals(0xFFE9E9EB.toInt(), TellomiLinkCardStyle.NEUTRAL_LIGHT)
    assertEquals(0xFF2C2C2E.toInt(), TellomiLinkCardStyle.NEUTRAL_DARK)
    assertEquals(36, TellomiLinkCardStyle.ICON_DP)
    assertEquals(8, TellomiLinkCardStyle.ICON_RADIUS_DP)
    assertEquals(26, TellomiLinkCardStyle.COMPASS_DP)
    assertEquals(listOf(14, 9, 10, 9), TellomiLinkCardStyle.STRIP_PADDING_DP.toList())
    assertEquals(listOf(14, 9, 14, 10), TellomiLinkCardStyle.STRIP_NO_TRAILING_PADDING_DP.toList())
    assertEquals(2, TellomiLinkCardStyle.MAX_TITLE_LINES)
  }
}
