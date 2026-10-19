/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** PM 2026-10-19 (ADR-0063 §5.6): same terminator table as iOS `LinkValidator.isIriTerminator`. */
@Suppress("ClassName")
@RunWith(Parameterized::class)
class LinkUtilTest_cutAtCjkSentencePunctuation(private val input: String, private val output: String) {

  @Test
  fun cutAtCjkSentencePunctuation() {
    assertEquals(output, LinkUtil.cutAtCjkSentencePunctuation(input))
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<Array<String>> = listOf(
      arrayOf("https://zh.wikipedia.org/wiki/中国，很好", "https://zh.wikipedia.org/wiki/中国"),
      arrayOf("https://zh.wikipedia.org/wiki/中国。", "https://zh.wikipedia.org/wiki/中国"),
      arrayOf("https://www.baidu.com/s?wd=天气！", "https://www.baidu.com/s?wd=天气"),
      arrayOf("https://signal.org/a/中文」后面", "https://signal.org/a/中文"),
      // nothing to cut
      arrayOf("https://zh.wikipedia.org/wiki/中国", "https://zh.wikipedia.org/wiki/中国"),
      arrayOf("https://signal.org/path", "https://signal.org/path"),
      // the host is never cut
      arrayOf("https://b23.tv，很好", "https://b23.tv，很好"),
      arrayOf("signal.org/中国，", "signal.org/中国，")
    )
  }
}
