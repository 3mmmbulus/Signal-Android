package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
