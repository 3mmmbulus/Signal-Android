/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * owner 2026-10-17 (ADR-0063 addendum): http:// and scheme-less links get a card over https when the registry claims the https
 * link. Same cases as iOS `LinkValidatorTest` / `TellomiLinkClassifierTest`.
 */
class TellomiLinkHttpsUpgradeTest {

  private fun upgrade(detected: String, claimed: Boolean = true): String? = TellomiLinkHttpsUpgrade.httpsUpgrade(detected) { claimed }

  private fun body(body: String, previewUrl: String = "https://b23.tv/abc", claimed: Boolean = true): String {
    return TellomiLinkHttpsUpgrade.body(body, previewUrl) { claimed }
  }

  @Test
  fun `an http or scheme-less link the registry claims is previewed over https`() {
    assertEquals("https://b23.tv/abc", upgrade("http://b23.tv/abc"))
    assertEquals("https://b23.tv/abc", upgrade("HTTP://b23.tv/abc"))
    assertEquals("glued text is trimmed as for https", "https://xhslink.com/a/b", upgrade("http://xhslink.com/a/b很好"))
  }

  @Test
  fun `nothing else is upgraded`() {
    assertNull(upgrade("http://b23.tv/abc", claimed = false))
    assertNull("already https: the upstream path handles it", upgrade("https://b23.tv/abc"))
    assertNull(upgrade("ftp://b23.tv/abc"))
    assertNull(upgrade("http://"))
    assertNull("the upstream rules still apply", upgrade("http://example.org/a"))
    assertNull("mixed-script host", upgrade("http://www.wikipediа.org/wiki/Main"))
  }

  @Test
  fun `the registry is asked about the https link only`() {
    val asked = ArrayList<String>()
    TellomiLinkHttpsUpgrade.httpsUpgrade("http://b23.tv/abc很好") { asked += it; true }
    assertEquals(listOf("https://b23.tv/abc"), asked)
  }

  @Test
  fun `the body given to rust-links spells the claimed link https`() {
    assertEquals("看 https://b23.tv/abc 很好", body("看 http://b23.tv/abc 很好"))
    assertEquals("看 https://b23.tv/abc", body("看 HTTP://b23.tv/abc"))
    assertEquals("看 https://b23.tv/abc 很好", body("看 b23.tv/abc 很好"))
    assertEquals("看这个https://b23.tv/abc很好", body("看这个b23.tv/abc很好"))
    assertEquals("（https://b23.tv/abc）", body("（b23.tv/abc）"))
    assertEquals("https://b23.tv/abc", body("b23.tv/abc", "https://b23.tv/abc/"))
    assertEquals("evil-b23.tv/abc 和 https://b23.tv/abc", body("evil-b23.tv/abc 和 b23.tv/abc"))
    assertEquals("https://b23.tv/abcdef 和 https://b23.tv/abc", body("b23.tv/abcdef 和 b23.tv/abc"))
  }

  @Test
  fun `everything else in the body is left alone`() {
    assertEquals("看 b23.tv/abc", body("看 b23.tv/abc", claimed = false))
    assertEquals("看 https://b23.tv/abc", body("看 https://b23.tv/abc"))
    assertEquals("evil-b23.tv/abc", body("evil-b23.tv/abc"))
    assertEquals("xb23.tv/abc", body("xb23.tv/abc"))
    assertEquals("me@b23.tv/abc", body("me@b23.tv/abc"))
    assertEquals("ftp://b23.tv/abc", body("ftp://b23.tv/abc"))
    assertEquals("https://x.com/b23.tv/abc", body("https://x.com/b23.tv/abc"))
    assertEquals("没有链接", body("没有链接"))
    assertEquals("http://b23.tv/abc", body("http://b23.tv/abc", "http://b23.tv/abc"))
  }

  @Test
  fun `the registry is asked only when there is something to upgrade`() {
    val asked = ArrayList<String>()
    val claims: (String) -> Boolean = { asked += it; true }
    TellomiLinkHttpsUpgrade.body("看 https://b23.tv/abc", "https://b23.tv/abc", claims)
    TellomiLinkHttpsUpgrade.body("没有链接", "https://b23.tv/abc", claims)
    assertEquals(emptyList<String>(), asked)
    TellomiLinkHttpsUpgrade.body("b23.tv/abc http://b23.tv/abc", "https://b23.tv/abc", claims)
    assertEquals(listOf("https://b23.tv/abc"), asked)
  }
}
