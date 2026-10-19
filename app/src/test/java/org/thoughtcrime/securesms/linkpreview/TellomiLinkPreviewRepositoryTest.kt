/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.SignalStoreRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 上游 [LinkPreviewRepository] 的第三方分支确实走 [TellomiLinkFetcher]（ADR-0063 §4.4，tellomi/tellomi#1422）：
 * 请求头是契约里的，取不到就是 PREVIEW_NOT_AVAILABLE（输入框据此不显示预览区）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkPreviewRepositoryTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private val d = TellomiLinkFetchFixture.DOMAIN

  @Before
  fun setUp() {
    SignalStore.settings.isLinkPreviewsEnabled = true
    // Signal's own path: without a registry nothing goes through rust/links.
    TellomiLinkRegistry.setForTesting(null)
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  @Test
  fun `with the registry loaded the preview is assembled by rust-links`() {
    val envelope = requireNotNull(javaClass.classLoader?.getResourceAsStream("links/links-2026101901.json")).use { it.readBytes() }
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(envelope))
    fixture.html("https://b.$d/article", "<html><head><meta property=\"og:title\" content=\"标题\"></head></html>")

    val brand = load("https://item.taobao.com/item.htm?id=100032608854")
    assertEquals("Taobao", brand.first!!.title)
    assertNotNull(brand.first!!.rich)

    val generic = load("https://b.$d/article#part-2")
    assertEquals("https://b.$d/article#part-2", generic.first!!.url)
    assertEquals("标题", generic.first!!.title)
    assertNull(generic.first!!.rich)
    // No og:image: rust/links falls back to the site's touch icon (404 here, so no image).
    assertEquals(listOf("/article" to "text/html", "/apple-touch-icon.png" to "image/*"), fixture.takeRequests().map { it.path to it.getHeader("Accept") })
  }

  @Test
  fun `a web page is fetched through the contract fetcher`() {
    fixture.redirect("https://a.$d/short", "https://b.$d/article")
    fixture.html("https://b.$d/article", "<html><head><meta property=\"og:title\" content=\"标题\"><meta property=\"og:description\" content=\"描述\"></head></html>")

    val outcome = load("https://a.$d/short#ignored")

    assertNull(outcome.second)
    assertEquals("标题", outcome.first!!.title)
    assertEquals("描述", outcome.first!!.description)
    assertEquals("https://a.$d/short#ignored", outcome.first!!.url)
    val requests = fixture.takeRequests()
    assertEquals(listOf("/short", "/article"), requests.map { it.path })
    requests.forEach {
      assertEquals("WhatsApp/2", it.getHeader("User-Agent"))
      assertEquals("text/html", it.getHeader("Accept"))
      assertNull(it.getHeader("Cookie"))
    }
  }

  @Test
  fun `a page that cannot be fetched gives no preview`() {
    fixture.route("https://a.$d/missing", MockResponse().setResponseCode(404))
    fixture.dns.resolve("internal.$d", "192.168.1.10")
    fixture.redirect("https://a.$d/to-lan", "https://internal.$d/admin")

    assertEquals(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE, load("https://a.$d/missing").second)
    assertEquals(LinkPreviewRepository.Error.PREVIEW_NOT_AVAILABLE, load("https://a.$d/to-lan").second)
    assertTrue(fixture.takeRequests().none { it.getHeader("Host") == "internal.$d" })
  }

  private fun load(url: String): Pair<LinkPreview?, LinkPreviewRepository.Error?> {
    val latch = CountDownLatch(1)
    var preview: LinkPreview? = null
    var error: LinkPreviewRepository.Error? = null

    LinkPreviewRepository(fixture.fetcher()).getLinkPreview(
      ApplicationProvider.getApplicationContext(),
      url,
      object : LinkPreviewRepository.Callback {
        override fun onSuccess(linkPreview: LinkPreview) {
          preview = linkPreview
          latch.countDown()
        }

        override fun onError(e: LinkPreviewRepository.Error) {
          error = e
          latch.countDown()
        }
      }
    )

    assertTrue("no callback", latch.await(10, TimeUnit.SECONDS))
    return preview to error
  }
}
