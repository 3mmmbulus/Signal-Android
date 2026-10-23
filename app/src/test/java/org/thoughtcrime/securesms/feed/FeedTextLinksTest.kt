/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTextLinksTest {

  @Test
  fun `https link keeps its url and range`() {
    val text = "看这个 https://tellomi.app/a?b=1 不错"
    val links = FeedTextLinks.find(text)
    assertEquals(1, links.size)
    assertEquals("https://tellomi.app/a?b=1", links[0].url)
    assertEquals("https://tellomi.app/a?b=1", text.substring(links[0].start, links[0].end))
  }

  @Test
  fun `http link stays http`() {
    assertEquals(listOf("http://tellomi.app"), FeedTextLinks.find("go http://tellomi.app now").map { it.url })
  }

  @Test
  fun `bare domain gets https`() {
    assertEquals(listOf("https://www.tellomi.app/x"), FeedTextLinks.find("www.tellomi.app/x").map { it.url })
    assertEquals(listOf("https://tellomi.app"), FeedTextLinks.find("去 tellomi.app 看").map { it.url })
  }

  @Test
  fun `trailing sentence punctuation is not part of the link`() {
    assertEquals(listOf("https://tellomi.app/abc"), FeedTextLinks.find("see https://tellomi.app/abc.").map { it.url })
    assertEquals(listOf("https://tellomi.app/abc"), FeedTextLinks.find("(https://tellomi.app/abc).").map { it.url })
  }

  @Test
  fun `email and phone are not links`() {
    assertTrue(FeedTextLinks.find("写信 someone@tellomi.app 或打 +86 138 0000 0000").isEmpty())
    assertTrue(FeedTextLinks.find("mailto:someone@tellomi.app tel:13800000000").isEmpty())
  }

  @Test
  fun `only http and https schemes are allowed`() {
    assertNull(FeedTextLinks.normalize("ftp://tellomi.app"))
    assertNull(FeedTextLinks.normalize("javascript://x.com"))
    assertNull(FeedTextLinks.normalize("someone@tellomi.app"))
    assertEquals("HTTPS://tellomi.app", FeedTextLinks.normalize("HTTPS://tellomi.app"))
    assertEquals("https://tellomi.app", FeedTextLinks.normalize("tellomi.app"))
  }

  @Test
  fun `several links in order`() {
    val links = FeedTextLinks.find("a https://one.com b two.org c")
    assertEquals(listOf("https://one.com", "https://two.org"), links.map { it.url })
  }

  @Test
  fun `plain text and empty have no links`() {
    assertTrue(FeedTextLinks.find("没有链接的一段话").isEmpty())
    assertTrue(FeedTextLinks.find("").isEmpty())
    assertTrue(FeedTextLinks.find(null).isEmpty())
  }

  @Test
  fun `hit test finds link only inside its range`() {
    val text = "前面 https://tellomi.app 后面"
    val links = FeedTextLinks.find(text)
    val link = links.single()
    assertEquals(link, FeedTextLinks.at(links, link.start))
    assertEquals(link, FeedTextLinks.at(links, link.end - 1))
    assertNull(FeedTextLinks.at(links, link.end))
    assertNull(FeedTextLinks.at(links, 0))
  }

  @Test
  fun `deleted and unavailable notices never contain links`() {
    listOf("原动态已被作者删除", "原动态已不可见", "The original post was deleted by its author").forEach {
      assertTrue(it, FeedTextLinks.find(it).isEmpty())
    }
  }

  @Test
  fun `mention prefix before link keeps offsets`() {
    val text = "@小明 看 https://tellomi.app"
    val link = FeedTextLinks.find(text).single()
    assertEquals("https://tellomi.app", text.substring(link.start, link.end))
  }
}
