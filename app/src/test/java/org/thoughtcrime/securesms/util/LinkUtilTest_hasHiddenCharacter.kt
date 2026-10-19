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
    val link = Linkifier.findLinks(text).first()
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
      // ordinary links are unchanged
      arrayOf("https://zh.wikipedia.org/wiki/中国", false),
      arrayOf("https://zh.wikipedia.org/wiki/中国，很好", false),
      arrayOf("看这个https://b23.tv/abc很好", false),
      arrayOf("https://signal.org/a/😀", false),
      arrayOf("https://signal.org/path?q=1#x", false)
    )
  }
}
