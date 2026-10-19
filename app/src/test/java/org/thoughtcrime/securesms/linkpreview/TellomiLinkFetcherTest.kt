/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Reason
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Result
import org.thoughtcrime.securesms.linkpreview.TellomiLinkFetcher.Step
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 链接预览抓取器的平台层安全用例（ADR-0063 §4.3 / §4.4 / §6.2，§8.1 第 5、10 行；tellomi/tellomi#1422）。
 * 全部在本机：自签证书的 MockWebServer + 假 DNS，不碰真网络。
 */
class TellomiLinkFetcherTest {

  @get:Rule
  val fixture = TellomiLinkFetchFixture()

  private val d = TellomiLinkFetchFixture.DOMAIN

  private fun fetch(url: String, step: Step = Step.HTML, fetcher: TellomiLinkFetcher = fixture.fetcher()): Result {
    return fetcher.newSession().fetch(url, step)
  }

  private fun assertFailure(expected: Reason, result: Result) {
    assertTrue("expected Failure($expected) but was $result", result is Result.Failure)
    assertEquals(expected, (result as Result.Failure).reason)
  }

  // ---- 请求头、cookie ----

  @Test
  fun `request carries only User-Agent WhatsApp-2, Accept and Accept-Encoding`() {
    fixture.html("https://a.$d/page")

    val result = fetch("https://a.$d/page")

    assertTrue(result.toString(), result is Result.Body)
    val request = fixture.takeRequests().single()
    val names = request.headers.names().map { it.lowercase(Locale.ROOT) }.toSet()
    assertEquals(setOf("host", "user-agent", "accept", "accept-encoding"), names)
    assertEquals("WhatsApp/2", request.getHeader("User-Agent"))
    assertEquals("text/html", request.getHeader("Accept"))
    assertEquals("gzip", request.getHeader("Accept-Encoding"))
  }

  @Test
  fun `Set-Cookie on a redirect is neither stored nor sent`() {
    fixture.route("https://a.$d/login", MockResponse().setResponseCode(302).setHeader("Location", "https://a.$d/home").addHeader("Set-Cookie", "sid=abc123; Path=/"))
    fixture.route("https://a.$d/home", MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>").addHeader("Set-Cookie", "sid2=def; Path=/"))

    assertTrue(fetch("https://a.$d/login") is Result.Body)
    assertTrue(fetch("https://a.$d/home") is Result.Body)

    val requests = fixture.takeRequests()
    assertEquals(3, requests.size)
    requests.forEach { assertNull("cookie sent to ${it.path}", it.getHeader("Cookie")) }
  }

  // ---- 重定向 ----

  @Test
  fun `follows up to five redirects, re-validating every hop`() {
    for (i in 0 until 5) {
      fixture.redirect("https://h$i.$d/r$i", "https://h${i + 1}.$d/r${i + 1}")
    }
    fixture.html("https://h5.$d/r5")

    val result = fetch("https://h0.$d/r0")

    assertTrue(result.toString(), result is Result.Body)
    assertEquals("https://h5.$d/r5", (result as Result.Body).finalUrl.toString())
    assertEquals(6, fixture.takeRequests().size)
    assertEquals((0..5).map { "h$it.$d" }, fixture.dns.lookups)
  }

  @Test
  fun `a sixth redirect is not followed`() {
    for (i in 0 until 7) {
      fixture.redirect("https://a.$d/r$i", "/r${i + 1}")
    }

    val result = fetch("https://a.$d/r0")

    assertFailure(Reason.REDIRECT_LIMIT, result)
    assertEquals(listOf("/r0", "/r1", "/r2", "/r3", "/r4", "/r5"), fixture.takeRequests().map { it.path })
  }

  @Test
  fun `redirect to a host that resolves to a private address is not followed`() {
    val privateAnswers = mapOf(
      "ten" to "10.1.2.3",
      "cgnat" to "100.64.0.7",
      "loop" to "127.0.0.1",
      "linklocal" to "169.254.169.254",
      "oneseventwo" to "172.16.5.4",
      "oneninetwo" to "192.168.1.1",
      "zero" to "0.0.0.1",
      "ula" to "fd00::1",
      "v6link" to "fe80::1",
      "v6loop" to "::1"
    )
    for ((name, address) in privateAnswers) {
      fixture.dns.resolve("$name.$d", address)
      fixture.redirect("https://a.$d/to-$name", "https://$name.$d/secret")
    }

    for (name in privateAnswers.keys) {
      assertFailure(Reason.BLOCKED_ADDRESS, fetch("https://a.$d/to-$name"))
    }

    val requests = fixture.takeRequests()
    assertEquals(privateAnswers.keys.map { "/to-$it" }, requests.map { it.path })
  }

  @Test
  fun `a host with one private answer among public ones is refused`() {
    fixture.dns.resolve("mixed.$d", "203.0.113.10", "10.0.0.1")
    fixture.html("https://mixed.$d/")

    assertFailure(Reason.BLOCKED_ADDRESS, fetch("https://mixed.$d/"))
    assertEquals(0, fixture.takeRequests().size)
    assertEquals(0, fixture.connectAttempts.get())
  }

  @Test
  fun `redirect to a private IP literal is not followed`() {
    val literals = listOf("https://127.0.0.1/", "https://10.0.0.1/", "https://100.64.1.1/", "https://[::ffff:192.168.0.1]/", "https://[fd00::2]/", "https://169.254.169.254/latest/meta-data/")
    literals.forEachIndexed { i, target -> fixture.redirect("https://a.$d/lit$i", target) }

    literals.indices.forEach { i -> assertFailure(Reason.BLOCKED_ADDRESS, fetch("https://a.$d/lit$i")) }

    assertEquals(literals.indices.map { "/lit$it" }, fixture.takeRequests().map { it.path })
  }

  @Test
  fun `fake-ip proxy range 198_18 is allowed`() {
    fixture.dns.resolve("fakeip.$d", "198.18.0.5")
    fixture.html("https://fakeip.$d/")

    assertTrue(fetch("https://fakeip.$d/") is Result.Body)
  }

  @Test
  fun `redirect to http is not followed`() {
    fixture.redirect("https://a.$d/downgrade", "http://b.$d/plain")

    assertFailure(Reason.NOT_HTTPS, fetch("https://a.$d/downgrade"))
    assertEquals(listOf("/downgrade"), fixture.takeRequests().map { it.path })
    assertEquals(listOf("a.$d"), fixture.dns.lookups)
  }

  @Test
  fun `non-https URL is refused without touching the network`() {
    fixture.html("https://a.$d/")

    assertFailure(Reason.NOT_HTTPS, fetch("http://a.$d/"))
    assertEquals(0, fixture.takeRequests().size)
    assertEquals(emptyList<String>(), fixture.dns.lookups)
    assertEquals(0, fixture.connectAttempts.get())
  }

  @Test
  fun `503 with Retry-After 0 is not retried`() {
    fixture.route("https://a.$d/busy", MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))

    val result = fetch("https://a.$d/busy")

    assertFailure(Reason.HTTP_STATUS, result)
    assertEquals(503, (result as Result.Failure).httpCode)
    assertEquals(1, fixture.takeRequests().size)
  }

  // ---- 体积与 Content-Type ----

  @Test
  fun `HTML over 2 MiB is rejected, exactly 2 MiB is accepted`() {
    val max = 2 * 1024 * 1024
    fixture.route("https://a.$d/max", MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(ByteArray(max) { 'a'.code.toByte() })))
    fixture.route("https://a.$d/over", MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(ByteArray(max + 1) { 'a'.code.toByte() })))
    fixture.route("https://a.$d/over-chunked", MockResponse().setHeader("Content-Type", "text/html").setChunkedBody(Buffer().write(ByteArray(max + 1) { 'a'.code.toByte() }), 64 * 1024))

    assertEquals(max, (fetch("https://a.$d/max") as Result.Body).bytes.size)
    assertFailure(Reason.TOO_LARGE, fetch("https://a.$d/over"))
    assertFailure(Reason.TOO_LARGE, fetch("https://a.$d/over-chunked"))
  }

  @Test
  fun `gzip bomb is rejected by its decompressed size`() {
    val bomb = gzip(ByteArray(3 * 1024 * 1024) { ' '.code.toByte() })
    assertTrue("compressed size ${bomb.size}", bomb.size < 64 * 1024)
    fixture.route("https://a.$d/bomb", MockResponse().setHeader("Content-Type", "text/html").setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bomb)))

    assertFailure(Reason.TOO_LARGE, fetch("https://a.$d/bomb"))
  }

  @Test
  fun `small gzip page is decompressed`() {
    val html = "<html><head><meta property=\"og:title\" content=\"压缩\"></head></html>"
    fixture.route("https://a.$d/gz", MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip(html.toByteArray()))))

    val result = fetch("https://a.$d/gz")

    assertEquals(html, (result as Result.Body).text())
  }

  @Test
  fun `unknown content encoding is rejected`() {
    fixture.route("https://a.$d/br", MockResponse().setHeader("Content-Type", "text/html").setHeader("Content-Encoding", "br").setBody("xx"))

    assertFailure(Reason.CONTENT_ENCODING, fetch("https://a.$d/br"))
  }

  @Test
  fun `JSON is capped at 256 KiB`() {
    val max = 256 * 1024
    fixture.route("https://api.$d/ok", MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().write(ByteArray(max) { '1'.code.toByte() })))
    fixture.route("https://api.$d/over", MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().write(ByteArray(max + 1) { '1'.code.toByte() })))

    assertTrue(fetch("https://api.$d/ok", Step.JSON) is Result.Body)
    assertFailure(Reason.TOO_LARGE, fetch("https://api.$d/over", Step.JSON))
  }

  @Test
  fun `Content-Type whitelist per step`() {
    fixture.route("https://a.$d/json", MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
    fixture.route("https://a.$d/ldjson", MockResponse().setHeader("Content-Type", "application/ld+json").setBody("{}"))
    fixture.route("https://a.$d/html", MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html></html>"))
    fixture.route("https://a.$d/xhtml", MockResponse().setHeader("Content-Type", "application/xhtml+xml").setBody("<html></html>"))
    fixture.route("https://a.$d/none", MockResponse().removeHeader("Content-Type").setBody("<html></html>"))
    fixture.route("https://a.$d/png", MockResponse().setHeader("Content-Type", "image/png").setBody("PNG"))

    assertFailure(Reason.CONTENT_TYPE, fetch("https://a.$d/json", Step.HTML))
    assertFailure(Reason.CONTENT_TYPE, fetch("https://a.$d/xhtml", Step.HTML))
    assertFailure(Reason.CONTENT_TYPE, fetch("https://a.$d/none", Step.HTML))
    assertFailure(Reason.CONTENT_TYPE, fetch("https://a.$d/html", Step.JSON))
    assertFailure(Reason.CONTENT_TYPE, fetch("https://a.$d/html", Step.IMAGE))
    assertTrue(fetch("https://a.$d/html", Step.HTML) is Result.Body)
    assertTrue(fetch("https://a.$d/json", Step.JSON) is Result.Body)
    assertTrue(fetch("https://a.$d/ldjson", Step.JSON) is Result.Body)
    assertTrue(fetch("https://a.$d/png", Step.IMAGE) is Result.Body)

    val directImage = fetch("https://a.$d/png", Step.HTML) as Result.Failure
    assertEquals(Reason.CONTENT_TYPE, directImage.reason)
    assertTrue(directImage.isDirectImage())
    assertEquals("https://a.$d/png", directImage.finalUrl.toString())
  }

  // ---- 超时与预算 ----

  @Test
  fun `a server that never answers times out at the request limit`() {
    fixture.route("https://a.$d/slow", MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>").setHeadersDelay(2, TimeUnit.SECONDS))
    val fetcher = fixture.fetcher(TellomiLinkFetcher.Limits.P1.copy(requestTimeoutMs = 400))

    val start = System.nanoTime()
    val result = fetch("https://a.$d/slow", fetcher = fetcher)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    assertFailure(Reason.TIMEOUT, result)
    assertTrue("took $elapsedMs ms", elapsedMs < 1_500)
  }

  @Test
  fun `a body that trickles in times out at the request limit`() {
    fixture.route("https://a.$d/trickle", MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(ByteArray(64 * 1024))).throttleBody(1024, 1, TimeUnit.SECONDS))
    val fetcher = fixture.fetcher(TellomiLinkFetcher.Limits.P1.copy(requestTimeoutMs = 400))

    val start = System.nanoTime()
    val result = fetch("https://a.$d/trickle", fetcher = fetcher)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    assertFailure(Reason.TIMEOUT, result)
    assertTrue("took $elapsedMs ms", elapsedMs < 1_500)
  }

  @Test
  fun `at most three metadata requests and one image request per link`() {
    fixture.html("https://a.$d/")
    fixture.route("https://img.$d/i.png", MockResponse().setHeader("Content-Type", "image/png").setBody("PNG"))
    val session = fixture.fetcher().newSession()

    repeat(3) { assertTrue(session.fetch("https://a.$d/", Step.HTML) is Result.Body) }
    assertFailure(Reason.BUDGET, session.fetch("https://a.$d/", Step.HTML))
    assertTrue(session.fetch("https://img.$d/i.png", Step.IMAGE) is Result.Body)
    assertFailure(Reason.BUDGET, session.fetch("https://img.$d/i.png", Step.IMAGE))

    assertEquals(4, fixture.takeRequests().size)
  }

  @Test
  fun `nothing is requested once the per-link budget of 10 s is spent`() {
    fixture.html("https://a.$d/")
    val session = fixture.fetcher().newSession()

    assertTrue(session.fetch("https://a.$d/", Step.HTML) is Result.Body)
    fixture.now.addAndGet(10_000)
    assertFailure(Reason.BUDGET, session.fetch("https://a.$d/", Step.HTML))

    assertEquals(1, fixture.takeRequests().size)
  }

  @Test
  fun `cancel stops an in-flight request`() {
    fixture.route("https://a.$d/slow", MockResponse().setHeader("Content-Type", "text/html").setBody("x").setHeadersDelay(2, TimeUnit.SECONDS))
    val session = fixture.fetcher().newSession()

    Thread {
      Thread.sleep(300)
      session.cancel()
    }.start()
    val start = System.nanoTime()
    val result = session.fetch("https://a.$d/slow", Step.HTML)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000

    assertFailure(Reason.CANCELLED, result)
    assertTrue("took $elapsedMs ms", elapsedMs < 1_500)
    assertFailure(Reason.CANCELLED, session.fetch("https://a.$d/slow", Step.HTML))
  }

  // ---- 短链 ----

  @Test
  fun `short link reads only Location and never follows it`() {
    fixture.route("https://s.$d/abc", MockResponse().setResponseCode(301).setHeader("Location", "https://target.$d/video/1?x=1").setHeader("Content-Type", "text/html").setBody("<html>ignored</html>"))
    fixture.redirect("https://s.$d/plain", "http://target.$d/video/2")

    val result = fetch("https://s.$d/abc", Step.SHORT_LINK)
    val plain = fetch("https://s.$d/plain", Step.SHORT_LINK)

    assertEquals("https://target.$d/video/1?x=1", (result as Result.Location).location.toString())
    assertEquals("http://target.$d/video/2", (plain as Result.Location).location.toString())
    assertEquals(listOf("/abc", "/plain"), fixture.takeRequests().map { it.path })
    assertFalse(fixture.dns.lookups.contains("target.$d"))
  }

  @Test
  fun `short link that does not redirect gives nothing`() {
    fixture.html("https://s.$d/page")

    assertFailure(Reason.NOT_REDIRECT, fetch("https://s.$d/page", Step.SHORT_LINK))
  }

  @Test
  fun `short link expansion switched off sends nothing`() {
    fixture.redirect("https://s.$d/abc", "https://target.$d/")
    fixture.expandShortLinks = false

    assertFailure(Reason.DISABLED, fetch("https://s.$d/abc", Step.SHORT_LINK))
    assertEquals(0, fixture.takeRequests().size)
    assertEquals(emptyList<String>(), fixture.dns.lookups)
  }

  // ---- 本机可达性记录（§4.3） ----

  @Test
  fun `a host whose DNS fails is not requested again on the second try`() {
    fixture.dns.fail("blocked.$d")

    assertFailure(Reason.UNREACHABLE, fetch("https://blocked.$d/"))
    assertFailure(Reason.SKIPPED_UNREACHABLE, fetch("https://blocked.$d/other"))
    assertEquals(listOf("blocked.$d"), fixture.dns.lookups)

    fixture.now.addAndGet(TellomiLinkReachability.TTL_MS)
    assertFailure(Reason.UNREACHABLE, fetch("https://blocked.$d/"))
    assertEquals(listOf("blocked.$d", "blocked.$d"), fixture.dns.lookups)
  }

  @Test
  fun `a refused connection marks the host until the network changes`() {
    fixture.dns.resolve("dead.$d", TellomiLinkFetchFixture.DEAD)

    assertFailure(Reason.UNREACHABLE, fetch("https://dead.$d/"))
    val attempts = fixture.connectAttempts.get()
    assertFailure(Reason.SKIPPED_UNREACHABLE, fetch("https://dead.$d/"))
    assertEquals(attempts, fixture.connectAttempts.get())

    fixture.networkId = "cellular-1"
    assertFailure(Reason.UNREACHABLE, fetch("https://dead.$d/"))
    assertTrue(fixture.connectAttempts.get() > attempts)
  }

  @Test
  fun `a redirect into an unreachable host is skipped without a request`() {
    fixture.dns.fail("blocked.$d")
    assertFailure(Reason.UNREACHABLE, fetch("https://blocked.$d/"))
    fixture.redirect("https://a.$d/go", "https://blocked.$d/x")

    assertFailure(Reason.SKIPPED_UNREACHABLE, fetch("https://a.$d/go"))
    assertEquals(listOf("blocked.$d", "a.$d"), fixture.dns.lookups)
  }

  @Test
  fun `a TLS failure marks the host`() {
    fixture.html("https://evil.other-fixture.org/")

    assertFailure(Reason.UNREACHABLE, fetch("https://evil.other-fixture.org/"))
    assertFailure(Reason.SKIPPED_UNREACHABLE, fetch("https://evil.other-fixture.org/"))
  }

  @Test
  fun `HTTP errors and slow servers do not mark the host`() {
    fixture.route("https://a.$d/err", MockResponse().setResponseCode(500))
    fixture.route("https://b.$d/slow", MockResponse().setHeader("Content-Type", "text/html").setBody("x").setHeadersDelay(3, TimeUnit.SECONDS))
    val fetcher = fixture.fetcher(TellomiLinkFetcher.Limits.P1.copy(requestTimeoutMs = 300))

    assertFailure(Reason.HTTP_STATUS, fetch("https://a.$d/err", fetcher = fetcher))
    assertFailure(Reason.HTTP_STATUS, fetch("https://a.$d/err", fetcher = fetcher))
    assertFailure(Reason.TIMEOUT, fetch("https://b.$d/slow", fetcher = fetcher))
    assertFailure(Reason.TIMEOUT, fetch("https://b.$d/slow", fetcher = fetcher))

    assertEquals(4, fixture.server.requestCount)
  }

  @Test
  fun `a server that accepts and then drops the connection is not marked`() {
    fixture.route("https://a.$d/drop", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    assertFailure(Reason.IO, fetch("https://a.$d/drop"))
    assertFailure(Reason.IO, fetch("https://a.$d/drop"))
    assertEquals(2, fixture.server.requestCount)
  }

  // ---- 公众号 Safari UA（产品经理 2026-10-19 批准） ----

  @Test
  fun `only mp_weixin_qq_com page requests use the Safari UA, exact match`() {
    val safari = TellomiLinkFetcher.BROWSER_USER_AGENT
    assertEquals(setOf("mp.weixin.qq.com"), TellomiLinkFetcher.BROWSER_USER_AGENT_HOSTS)
    fun ua(url: String, step: Step) = TellomiLinkFetcher.userAgentFor(url.toHttpUrl(), step)

    assertEquals(safari, ua("https://mp.weixin.qq.com/s/_chjr0XiTqoDg3Qg6JzU_g", Step.HTML))
    assertEquals(safari, ua("https://MP.WEIXIN.QQ.COM/s?__biz=x", Step.HTML))
    for (step in listOf(Step.JSON, Step.IMAGE, Step.SHORT_LINK)) {
      assertEquals("WhatsApp/2", ua("https://mp.weixin.qq.com/s/abc", step))
    }
    for (other in listOf("https://weixin.qq.com/", "https://qq.com/", "https://x.mp.weixin.qq.com/s/abc", "https://mp.weixin.qq.com.evil.cn/s/abc", "https://mmbiz.qpic.cn/a.jpg")) {
      assertEquals(other, "WhatsApp/2", ua(other, Step.HTML))
    }
  }

  // ---- 生产参数 ----

  @Test
  fun `production limits are the ADR-0063 section 4_4 numbers`() {
    val p1 = TellomiLinkFetcher.Limits.P1
    assertEquals(5_000L, p1.connectTimeoutMs)
    assertEquals(10_000L, p1.requestTimeoutMs)
    assertEquals(10_000L, p1.linkBudgetMs)
    assertEquals(5, p1.maxRedirects)
    assertEquals(3, p1.maxMetadataRequests)
    assertEquals(1, p1.maxImageRequests)
    assertEquals(2L * 1024 * 1024, p1.htmlMaxBytes)
    assertEquals(256L * 1024, p1.jsonMaxBytes)
    assertEquals(TimeUnit.MINUTES.toMillis(30), TellomiLinkReachability.TTL_MS)
    assertEquals("global", TellomiLinkReachability.P1_REGION_PRIOR)
  }

  private fun gzip(bytes: ByteArray): ByteArray {
    val out = Buffer()
    GzipSink(out).buffer().use { it.write(bytes) }
    return out.readByteArray()
  }
}
