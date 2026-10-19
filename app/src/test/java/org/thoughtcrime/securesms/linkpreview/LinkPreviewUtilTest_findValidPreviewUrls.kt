package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.links.LinkRegistry

@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class LinkPreviewUtilTest_findValidPreviewUrls {

  @Test
  fun no_links() {
    val links = LinkPreviewUtil.findValidPreviewUrls("No links")

    Assert.assertEquals(0, links.size().toLong())
    Assert.assertSame(LinkPreviewUtil.Links.EMPTY, links)
  }

  @Test
  fun contains_a_link() {
    val links = LinkPreviewUtil.findValidPreviewUrls("https://signal.org")

    Assert.assertEquals(1, links.size().toLong())

    Assert.assertTrue(links.containsUrl("https://signal.org"))
  }

  @Test
  fun does_not_contain_link() {
    val links = LinkPreviewUtil.findValidPreviewUrls("https://signal.org")

    Assert.assertEquals(1, links.size().toLong())

    Assert.assertFalse(links.containsUrl("https://signal.org/page"))
  }

  @Test
  fun contains_two_links() {
    val links = LinkPreviewUtil.findValidPreviewUrls("Links https://signal.org https://android.com")

    Assert.assertEquals(2, links.size().toLong())

    Assert.assertTrue(links.containsUrl("https://signal.org"))
    Assert.assertTrue(links.containsUrl("https://android.com"))
  }

  @Test
  fun link_trailing_slash_insensitivity() {
    val links = LinkPreviewUtil.findValidPreviewUrls("Links https://signal.org/ https://android.com")

    Assert.assertEquals(2, links.size().toLong())

    Assert.assertTrue(links.containsUrl("https://signal.org"))
    Assert.assertTrue(links.containsUrl("https://android.com"))
    Assert.assertTrue(links.containsUrl("https://signal.org/"))
    Assert.assertTrue(links.containsUrl("https://android.com/"))
  }

  @Test
  fun link_trailing_slash_insensitivity_where_last_url_has_trailing_slash() {
    val links = LinkPreviewUtil.findValidPreviewUrls("Links https://signal.org https://android.com/")

    Assert.assertEquals(2, links.size().toLong())

    Assert.assertTrue(links.containsUrl("https://signal.org"))
    Assert.assertTrue(links.containsUrl("https://android.com"))
    Assert.assertTrue(links.containsUrl("https://signal.org/"))
    Assert.assertTrue(links.containsUrl("https://android.com/"))
  }

  @Test
  fun multiple_trailing_slashes_are_not_stripped() {
    val links = LinkPreviewUtil.findValidPreviewUrls("Link https://android.com/")

    Assert.assertEquals(1, links.size().toLong())

    Assert.assertTrue(links.containsUrl("https://android.com"))
    Assert.assertTrue(links.containsUrl("https://android.com/"))
    Assert.assertFalse(links.containsUrl("https://android.com//"))
  }

  /** Tellomi (2026-10-17): text glued around the link is not part of the preview URL. */
  @Test
  fun cjk_text_glued_around_the_link_is_not_part_of_the_preview_url() {
    val links = LinkPreviewUtil.findValidPreviewUrls("看这个https://b23.tv/abc很好")

    Assert.assertEquals(1, links.size().toLong())
    Assert.assertTrue(links.containsUrl("https://b23.tv/abc"))
    Assert.assertFalse(links.containsUrl("https://b23.tv/abc很好"))
  }

  /** Tellomi (2026-10-17): a CJK path segment right after a delimiter is the link's own, kept as upstream. */
  @Test
  fun cjk_path_segment_after_a_slash_is_kept() {
    val links = LinkPreviewUtil.findValidPreviewUrls("https://zh.wikipedia.org/wiki/中国")

    Assert.assertEquals(1, links.size().toLong())
    Assert.assertTrue(links.containsUrl("https://zh.wikipedia.org/wiki/中国"))
  }

  /** Tellomi (PM 2026-10-19, ADR-0063 §5.6): Chinese in the path or query gets a preview; the raw IRI is the preview URL. */
  @Test
  fun cjk_in_path_or_query_gets_a_preview() {
    val cases = mapOf(
      "看 https://www.baidu.com/s?wd=天气 吧" to "https://www.baidu.com/s?wd=天气",
      "https://zh.wikipedia.org/wiki/中国，很好" to "https://zh.wikipedia.org/wiki/中国",
      "https://zh.wikipedia.org/wiki/中国。" to "https://zh.wikipedia.org/wiki/中国",
      "https://zh.wikipedia.org/wiki/中国\u3000很好" to "https://zh.wikipedia.org/wiki/中国",
      "看这个https://b23.tv/abc，很好" to "https://b23.tv/abc",
      "https://signal.org/a/中文#第二节" to "https://signal.org/a/中文#第二节"
    )
    for ((text, url) in cases) {
      val links = LinkPreviewUtil.findValidPreviewUrls(text)
      Assert.assertEquals(text, 1, links.size().toLong())
      Assert.assertTrue(text, links.containsUrl(url))
    }
  }

  /** Hidden characters around / inside the link end it or reject it (Linkifier), so they are never in a preview URL. */
  @Test
  fun hidden_characters_never_reach_a_preview_url() {
    for (text in listOf("https://signal.org/a/中\u200B文", "https://signal.org/a/中\u2066文")) {
      val links = LinkPreviewUtil.findValidPreviewUrls(text)
      Assert.assertFalse(text, links.containsUrl("https://signal.org/a/中\u200B文"))
      Assert.assertFalse(text, links.containsUrl("https://signal.org/a/中\u2066文"))
    }
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  private fun withBundledRegistry() {
    val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("links/links-2026092702.json")).use { it.readBytes() }
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(bytes))
  }

  /** Tellomi (owner 2026-10-17): http:// or no scheme is previewed over https when the bundled registry claims it. */
  @Test
  fun tellomi_http_and_schemeless_links_the_registry_claims_are_previewed_over_https() {
    withBundledRegistry()
    for ((text, url) in listOf(
      "看 b23.tv/abc" to "https://b23.tv/abc",
      "看 http://b23.tv/abc" to "https://b23.tv/abc",
      "看 www.bilibili.com/video/BV1GJ411x7h7 吧" to "https://www.bilibili.com/video/BV1GJ411x7h7",
      "http://xhslink.com/a/b很好" to "https://xhslink.com/a/b"
    )) {
      val links = LinkPreviewUtil.findValidPreviewUrls(text)
      Assert.assertEquals(text, url, links.findFirst().map { it.url }.orElse(null))
    }
  }

  /** …not when nothing in the registry claims it, nor for our own hosts (the official badge needs https in the body). */
  @Test
  fun tellomi_http_links_the_registry_does_not_claim_get_no_preview() {
    withBundledRegistry()
    for (text in listOf("http://signal.org", "signal.org/hello", "http://tell.cc/g/abc")) {
      Assert.assertEquals(text, 0, LinkPreviewUtil.findValidPreviewUrls(text).size().toLong())
    }
    val mixed = LinkPreviewUtil.findValidPreviewUrls("http://a.com https://signal.org/x")
    Assert.assertEquals("https://signal.org/x", mixed.findFirst().get().url)
  }

  /** Without a registry nothing is upgraded (as upstream). */
  @Test
  fun tellomi_without_a_registry_http_links_get_no_preview() {
    TellomiLinkRegistry.setForTesting(null)
    Assert.assertEquals(0, LinkPreviewUtil.findValidPreviewUrls("看 http://b23.tv/abc").size().toLong())
  }
}
