/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.signal.core.util.Linkifier

/**
 * PM 2026-10-19 (ADR-0063 §5.6): a link with a zero-width / direction-control character in it, or running straight into one,
 * gets no preview. The preview URL here is the one `LinkPreviewUtil` would take (cut at Chinese punctuation and glued text).
 */
@Suppress("ClassName")
@RunWith(Parameterized::class)
class LinkUtilTest_hasHiddenCharacter(private val text: String, private val hidden: Boolean) {

  @Test
  fun hasHiddenCharacter() {
    // Linkifier itself drops a link that runs into a hidden character, so there is no link (and no preview) to check
    val link = Linkifier.findLinks(text).firstOrNull() ?: return assertEquals(text, true, hidden)
    val previewUrl = LinkUtil.trimTextGluedAfterUrl(LinkUtil.cutAtCjkSentencePunctuation(link.url))
    assertEquals(hidden, LinkUtil.hasHiddenCharacter(text, link, previewUrl))
  }

  /** The https-upgrade path previews "https://…" for a link written "http://…" or with no scheme at all. */
  @Test
  fun hasHiddenCharacter_whenRespeltWithHttps() {
    for (written in listOf(text.replaceFirst("https://", "http://"), text.replaceFirst("https://", ""))) {
      val link = Linkifier.findLinks(written).firstOrNull() ?: continue
      if (LinkUtil.cutAtCjkSentencePunctuation(link.url) != link.url) {
        continue // that path does not cut at Chinese punctuation, so the preview URL is a different one
      }
      val previewUrl = LinkUtil.trimTextGluedAfterUrl("https://" + link.url.substringAfter("://"))
      assertEquals(written, hidden, LinkUtil.hasHiddenCharacter(written, link, previewUrl))
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<Array<Any>> = listOf(
      // hidden character inside the link
      arrayOf("https://signal.org/a/中\u2060文", true),
      arrayOf("https://signal.org/a/中\u200E文", true),
      arrayOf("https://signal.org/a/中\u200F文", true),
      arrayOf("看 https://signal.org/a/中\u2060文 吧", true),
      // the preview would be cut right before a hidden character: no preview, not a shortened one
      arrayOf("https://signal.org/a\u2060b", true),
      arrayOf("https://signal.org/abc\u200E很好", true),
      // a hidden character after whitespace or punctuation is not part of the link
      arrayOf("https://zh.wikipedia.org/wiki/中国 \u200B", false),
      arrayOf("https://zh.wikipedia.org/wiki/中国，\u2060很好", false),
      arrayOf("https://signal.org/abc.\u200B", false),
      arrayOf("https://signal.org/abc\n\u200B", false),
      arrayOf("https://signal.org/abc,\u200F next", false),
      arrayOf("https://signal.org/abc!\u2060", false),
      arrayOf("https://signal.org/abc.\u200E", false),
      // every hidden category, not only zero-width / direction marks (U+00AD soft hyphen and others, as iOS / Desktop)
      arrayOf("https://signal.org/a\u00ADb", true),
      arrayOf("https://signal.org/a/中\u00AD文", true),
      arrayOf("https://signal.org/abc\u00AD", true),
      arrayOf("https://signal.org/a\u180Eb", true),
      arrayOf("https://signal.org/a\uFFF9b", true),
      arrayOf("https://signal.org/a\u001Fb", true),
      arrayOf("https://signal.org/abc\u001F", true),
      arrayOf("https://signal.org/a\uE000b", true),
      arrayOf("https://signal.org/a\uDB40\uDC01b", true),
      arrayOf("https://signal.org/abc.\u00AD", false),
      arrayOf("https://zh.wikipedia.org/wiki/中国，\u00AD很好", false),
      // a line break right after a link is a normal end (as iOS isIriTerminator)
      arrayOf("https://signal.org/abc\u0085next", false),
      arrayOf("https://signal.org/a/中文\u2028下一行", false),
      arrayOf("https://signal.org/a/中文\r\n下一行", false),
      // ordinary links are unchanged
      arrayOf("https://zh.wikipedia.org/wiki/中国", false),
      arrayOf("https://zh.wikipedia.org/wiki/中国，很好", false),
      arrayOf("看这个https://b23.tv/abc很好", false),
      arrayOf("https://signal.org/a/😀", false),
      arrayOf("https://signal.org/path?q=1#x", false)
    )
  }
}
