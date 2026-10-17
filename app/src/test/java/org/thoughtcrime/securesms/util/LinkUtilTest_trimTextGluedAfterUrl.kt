/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Same cases as iOS `LinkValidatorTest` (the Tellomi rows). */
@Suppress("ClassName")
@RunWith(Parameterized::class)
class LinkUtilTest_trimTextGluedAfterUrl(private val input: String, private val output: String) {

  @Test
  fun trimTextGluedAfterUrl() {
    assertEquals(output, LinkUtil.trimTextGluedAfterUrl(input))
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<Array<String>> = listOf(
      // cut right after an ASCII letter or digit in the path
      arrayOf("https://b23.tv/abc很好", "https://b23.tv/abc"),
      arrayOf("https://www.163.com/news/article/ABC123.html网易新闻", "https://www.163.com/news/article/ABC123.html"),
      arrayOf("https://signal.org/hello?q=1世界", "https://signal.org/hello?q=1"),
      // right after a delimiter: the link's own segment, left alone
      arrayOf("https://zh.wikipedia.org/wiki/中国", "https://zh.wikipedia.org/wiki/中国"),
      arrayOf("https://signal.org/hello?你好", "https://signal.org/hello?你好"),
      arrayOf("https://signal.org/hello?param=наушники", "https://signal.org/hello?param=наушники"),
      // never in the host (mixed-script hosts are a spoofing risk)
      arrayOf("https://www.wikipediа.org", "https://www.wikipediа.org"),
      arrayOf("https://www.wikipediа.org/wiki/Main", "https://www.wikipediа.org/wiki/Main"),
      arrayOf("https://b23.tv很好", "https://b23.tv很好"),
      // nothing to cut
      arrayOf("https://signal.org/path", "https://signal.org/path"),
      arrayOf("https://中国互联网络信息中心.中国/path", "https://中国互联网络信息中心.中国/path")
    )
  }
}
