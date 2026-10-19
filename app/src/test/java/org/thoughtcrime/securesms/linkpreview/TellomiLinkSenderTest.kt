/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Hex
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Locale
import java.util.Optional

/**
 * ADR-0063 §4.2 / §4.4 / §5.2 (tellomi/tellomi#1422): the composer's preview with the registry loaded —
 * rust/links' requests go out through the §4.4 fetcher (here to a local server), the answers go back in,
 * and the preview carries the snapshot and `Preview.rich` rust/links assembled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkSenderTest {

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(resource("send-golden.json").decodeToString()).jsonObject
  private val registry by lazy { LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)) }

  private val video = "https://www.bilibili.com/video/BV1YDhJ6ZEL6"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

  private val image: Attachment = UriAttachmentBuilder.build(id = 7, contentType = "image/jpeg")

  private class FakeLookups(val image: Attachment?, val firstParty: TellomiLinkSender.FirstParty = TellomiLinkSender.FirstParty.NotFound) : TellomiLinkSender.Lookups {
    val thumbnails = mutableListOf<Int>()
    val kinds = mutableListOf<String>()

    override fun firstParty(kind: String, url: String): TellomiLinkSender.FirstParty {
      kinds += kind
      return firstParty
    }

    override fun thumbnail(bytes: ByteArray): Attachment? {
      thumbnails += bytes.size
      return image
    }
  }

  private fun sender(lookups: TellomiLinkSender.Lookups, fetcher: TellomiLinkFetcher = fixture.fetcher()): Pair<TellomiLinkSender, TellomiLinkFetcher> {
    return TellomiLinkSender(fetcher, expandShortLinks = { fixture.expandShortLinks }, locale = { Locale.US }, lookups = lookups) to fetcher
  }

  private fun preview(url: String, lookups: TellomiLinkSender.Lookups, fetcher: TellomiLinkFetcher = fixture.fetcher()): TellomiLinkSender.Result? {
    val (sender, f) = sender(lookups, fetcher)
    return sender.preview(registry, url, f.newSession()) { false }
  }

  private fun goldenCase(name: String): JsonObject = golden["send"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

  @Test
  fun `a structured card is assembled from the public API and its image`() {
    val case = goldenCase("App Store public API + image")
    fixture.dns.resolve("itunes.apple.com", "203.0.113.10")
    fixture.dns.resolve("is1-ssl.mzstatic.com", "203.0.113.10")
    fixture.route(
      "https://itunes.apple.com/cn/lookup?id=414478124",
      MockResponse()
        .setHeader("Content-Type", "text/javascript; charset=utf-8")
        .setBody("""{"resultCount":1,"results":[{"artistName":"WeChat","artworkUrl512":"https://is1-ssl.mzstatic.com/image/thumb/512x512bb.jpg","trackName":"微信"}]}""")
    )
    fixture.route("https://is1-ssl.mzstatic.com/image/thumb/512x512bb.jpg", MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(ByteArray(64) { 1 })))
    val lookups = FakeLookups(image)

    val result = preview("https://apps.apple.com/cn/app/wechat/id414478124", lookups) as TellomiLinkSender.Result.Found

    val expected = TellomiLinkSendJob.parseOutcome(case["outcome"]!!.jsonPrimitive.content)!!.preview!!
    assertEquals("https://apps.apple.com/cn/app/wechat/id414478124", result.preview.url)
    assertEquals("微信", result.preview.title)
    assertArrayEquals(Hex.fromStringCondensed(expected.richHex!!), result.preview.rich)
    assertSame(image, result.preview.thumbnail.get())
    assertEquals(listOf(64), lookups.thumbnails)

    val requests = fixture.takeRequests()
    assertEquals(listOf("/cn/lookup?id=414478124", "/image/thumb/512x512bb.jpg"), requests.map { it.path })
    assertEquals(listOf("application/json", "image/*"), requests.map { it.getHeader("Accept") })
    requests.forEach {
      assertEquals("WhatsApp/2", it.getHeader("User-Agent"))
      assertNull(it.getHeader("Cookie"))
    }
  }

  @Test
  fun `an image that cannot be used leaves the card without one`() {
    fixture.dns.resolve("itunes.apple.com", "203.0.113.10")
    fixture.dns.resolve("is1-ssl.mzstatic.com", "203.0.113.10")
    fixture.route(
      "https://itunes.apple.com/cn/lookup?id=414478124",
      MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"resultCount":1,"results":[{"artistName":"WeChat","artworkUrl512":"https://is1-ssl.mzstatic.com/image/thumb/512x512bb.jpg","trackName":"微信"}]}""")
    )
    fixture.route("https://is1-ssl.mzstatic.com/image/thumb/512x512bb.jpg", MockResponse().setHeader("Content-Type", "image/jpeg").setBody("x"))

    val result = preview("https://apps.apple.com/cn/app/wechat/id414478124", FakeLookups(image = null)) as TellomiLinkSender.Result.Found

    assertEquals("微信", result.preview.title)
    assertEquals(Optional.empty<Attachment>(), result.preview.thumbnail)
  }

  /**
   * links-2026101901 moved Taobao to the `claimed` tier (its page is fetched like a generic link), so the golden "brand shell,
   * no request" case is a brand-tier Weibo post now (libsignal 070eaefb7 bridge-golden); the URL is read from the golden.
   */
  @Test
  fun `a brand-tier link sends the platform shell without a request`() {
    val case = goldenCase("brand shell, no request")
    val expected = TellomiLinkSendJob.parseOutcome(case["outcome"]!!.jsonPrimitive.content)!!.preview!!

    val result = preview(case["url"]!!.jsonPrimitive.content, FakeLookups(image)) as TellomiLinkSender.Result.Found

    assertEquals("Weibo", result.preview.title)
    assertArrayEquals(Hex.fromStringCondensed(expected.richHex!!), result.preview.rich)
    assertEquals(Optional.empty<Attachment>(), result.preview.thumbnail)
    assertTrue(fixture.takeRequests().isEmpty())
    assertTrue(fixture.dns.lookups.isEmpty())
  }

  @Test
  fun `an unreachable host is remembered and not asked again`() {
    fixture.dns.fail("www.bilibili.com")
    val fetcher = fixture.fetcher()

    val first = preview(video, FakeLookups(image), fetcher) as TellomiLinkSender.Result.Found
    val second = preview(video, FakeLookups(image), fetcher) as TellomiLinkSender.Result.Found

    assertEquals("Bilibili", first.preview.title)
    assertEquals("Bilibili", second.preview.title)
    assertEquals(listOf("www.bilibili.com"), fetcher.unreachableHosts())
    assertEquals(1, fixture.dns.lookups.count { it == "www.bilibili.com" })
  }

  @Test
  fun `a page that is not there falls back to the platform shell`() {
    fixture.dns.resolve("www.bilibili.com", "203.0.113.10")
    fixture.route("https://www.bilibili.com/video/BV1YDhJ6ZEL6", MockResponse().setResponseCode(404))

    val result = preview(video, FakeLookups(image)) as TellomiLinkSender.Result.Found

    assertEquals("Bilibili", result.preview.title)
    assertNotNull(result.preview.rich)
    assertEquals(listOf("text/html"), fixture.takeRequests().map { it.getHeader("Accept") })
    assertTrue(fixture.fetcher().unreachableHosts().isEmpty())
  }

  @Test
  fun `a short link is expanded by reading its Location only`() {
    fixture.dns.resolve("b23.tv", "203.0.113.10")
    fixture.dns.resolve("www.bilibili.com", "203.0.113.10")
    fixture.redirect("https://b23.tv/abc", video, code = 301)
    fixture.route("https://www.bilibili.com/video/BV1YDhJ6ZEL6", MockResponse().setResponseCode(404))

    val result = preview("https://b23.tv/abc", FakeLookups(image)) as TellomiLinkSender.Result.Found

    assertEquals("Bilibili", result.preview.title)
    // The snapshot URL stays what was typed: the composer and every receiver look for it in the body.
    assertEquals("https://b23.tv/abc", result.preview.url)
    val requests = fixture.takeRequests()
    assertEquals(listOf("b23.tv/abc", "www.bilibili.com/video/BV1YDhJ6ZEL6"), requests.map { it.getHeader("Host")!!.substringBefore(':') + it.path })
    assertEquals("*/*", requests[0].getHeader("Accept"))
  }

  @Test
  fun `with short links off the short link is not requested`() {
    fixture.expandShortLinks = false
    fixture.dns.resolve("b23.tv", "203.0.113.10")
    fixture.redirect("https://b23.tv/abc", video, code = 301)

    val result = preview("https://b23.tv/abc", FakeLookups(image))

    assertTrue(result is TellomiLinkSender.Result.Found || result is TellomiLinkSender.Result.NotAvailable)
    assertTrue(fixture.takeRequests().isEmpty())
  }

  @Test
  fun `a group uses Signal's own lookup for its name and avatar`() {
    val avatar = UriAttachmentBuilder.build(id = 8, contentType = "image/webp")
    val lookups = FakeLookups(image, TellomiLinkSender.FirstParty.Found(LinkPreview(group, "周末爬山群", "12 members", 0, Optional.of(avatar))))

    val result = preview(group, lookups) as TellomiLinkSender.Result.Found

    assertEquals(listOf("tellomi.group"), lookups.kinds)
    assertEquals("周末爬山群", result.preview.title)
    assertSame(avatar, result.preview.thumbnail.get())
    assertNotNull(result.preview.rich)
    assertTrue(fixture.takeRequests().isEmpty())
  }

  @Test
  fun `an inactive group link says so`() {
    val result = preview(group, FakeLookups(image, TellomiLinkSender.FirstParty.Inactive))

    assertSame(TellomiLinkSender.Result.GroupLinkInactive, result)
  }

  @Test
  fun `each fetch result reaches rust-links as what it was`() {
    val d = TellomiLinkFetchFixture.DOMAIN
    fixture.dns.fail("gone.$d")
    fixture.dns.resolve("dead.$d", TellomiLinkFetchFixture.DEAD)
    fixture.route("https://a.$d/missing", MockResponse().setResponseCode(404))
    fixture.redirect("https://a.$d/short", "https://a.$d/long", code = 301)
    fixture.route("https://a.$d/plain", MockResponse().setResponseCode(200))
    fixture.html("https://a.$d/page")
    val session = fixture.fetcher().newSession()
    fun exchange(url: String, step: TellomiLinkFetcher.Step) = TellomiLinkSender.toExchange(url, session.fetch(url, step))

    // DNS / TCP failures: rust/links remembers the host.
    assertSame(TellomiLinkSendJob.Exchange.NetworkError, exchange("https://gone.$d/", TellomiLinkFetcher.Step.HTML))
    assertSame(TellomiLinkSendJob.Exchange.NetworkError, TellomiLinkSender.toExchange("https://dead.$d/", fixture.fetcher().newSession().fetch("https://dead.$d/", TellomiLinkFetcher.Step.HTML)))

    val missing = exchange("https://a.$d/missing", TellomiLinkFetcher.Step.HTML) as TellomiLinkSendJob.Exchange.Response
    assertEquals(404, missing.status)
    assertEquals(0, missing.body.size)

    val short = TellomiLinkSender.toExchange("https://a.$d/short", fixture.fetcher().newSession().fetch("https://a.$d/short", TellomiLinkFetcher.Step.SHORT_LINK)) as TellomiLinkSendJob.Exchange.Response
    assertEquals(301, short.status)
    assertEquals("https://a.$d/long", short.location)

    val notShort = TellomiLinkSender.toExchange("https://a.$d/plain", fixture.fetcher().newSession().fetch("https://a.$d/plain", TellomiLinkFetcher.Step.SHORT_LINK)) as TellomiLinkSendJob.Exchange.Response
    assertEquals(200, notShort.status)
    assertNull(notShort.location)

    val page = TellomiLinkSender.toExchange("https://a.$d/page", fixture.fetcher().newSession().fetch("https://a.$d/page", TellomiLinkFetcher.Step.HTML)) as TellomiLinkSendJob.Exchange.Response
    assertEquals(200, page.status)
    assertEquals("https://a.$d/page", page.finalUrl)
    assertEquals("text/html; charset=utf-8", page.contentType)
    assertTrue(page.body.isNotEmpty())

    // Connected, then refused by the contract: an ordinary failure, the host stays reachable.
    fixture.dns.resolve("lan.$d", "192.168.1.10")
    assertSame(TellomiLinkSendJob.Exchange.Failure, TellomiLinkSender.toExchange("https://lan.$d/", fixture.fetcher().newSession().fetch("https://lan.$d/", TellomiLinkFetcher.Step.HTML)))
  }

  @Test
  fun `a cancelled preview gives nothing`() {
    val (sender, fetcher) = sender(FakeLookups(image))

    assertNull(sender.preview(registry, video, fetcher.newSession()) { true })
    assertTrue(fixture.takeRequests().isEmpty())
  }
}
