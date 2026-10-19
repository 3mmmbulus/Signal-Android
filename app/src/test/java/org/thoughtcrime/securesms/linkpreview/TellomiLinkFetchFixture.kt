/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.rules.ExternalResource
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 本地 https 测试服务（ADR-0063 §8.1 第 5 行「开工前先备好」）：自签证书、假 DNS、所有连接都转到本机 MockWebServer。
 *
 * - 假 DNS 把 `*.fetch-fixture.net` 解析到公网文档地址 [PUBLIC]（不在拦截名单里）；个别主机解析到私网段、fake-ip 段，或者直接解析失败。
 * - 套接字工厂把每一次 connect 改到 MockWebServer 的本机端口：抓取器按生产规则拦 127/8，测试服务又只能跑在本机，这样两边都不用放宽。
 *   解析到 [DEAD] 的主机连到一个已关闭的端口（连接被拒）；解析到 [SLOW_CONNECT] 的连接超时。
 * - 证书只签了 `*.fetch-fixture.net`；`*.other-fixture.org` 用来造 TLS 失败。
 * - 路由按「Host + path」派发，没配的一律 404。
 */
class TellomiLinkFetchFixture : ExternalResource() {

  companion object {
    const val DOMAIN = "fetch-fixture.net"
    val PUBLIC: InetAddress = InetAddress.getByName("203.0.113.10")
    val DEAD: InetAddress = InetAddress.getByName("203.0.113.99")

    /** 解析到这里的主机连接超时（模拟丢包的网络：连接一直建不起来）。 */
    val SLOW_CONNECT: InetAddress = InetAddress.getByName("203.0.113.98")

    /**
     * Real provider hosts the certificate also covers, so the send-side tests can replay rust/links' own requests
     * (tellomi/tellomi#1422). Nothing resolves them unless a test calls [FakeDns.resolve].
     */
    val PROVIDER_HOSTS = listOf("itunes.apple.com", "*.mzstatic.com", "www.bilibili.com", "b23.tv")

    private const val FIXTURE_HTML = "<html><head><meta property=\"og:title\" content=\"Fixture\"></head></html>"

    private val PASSWORD = "fixture".toCharArray()

    private val certificate: Pair<KeyStore, X509Certificate> by lazy { selfSigned() }

    private fun selfSigned(): Pair<KeyStore, X509Certificate> {
      val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
      val name = X500Name("CN=$DOMAIN")
      val now = System.currentTimeMillis()
      val builder = JcaX509v3CertificateBuilder(
        name,
        BigInteger.valueOf(now),
        Date(now - TimeUnit.DAYS.toMillis(1)),
        Date(now + TimeUnit.DAYS.toMillis(1)),
        name,
        keyPair.public
      )
      builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        GeneralNames((listOf("*.$DOMAIN", DOMAIN) + PROVIDER_HOSTS).map { GeneralName(GeneralName.dNSName, it) }.toTypedArray())
      )
      val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)))
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setKeyEntry("server", keyPair.private, PASSWORD, arrayOf(cert))
      }
      return keyStore to cert
    }
  }

  val server = MockWebServer()
  val dns = FakeDns()
  val now = AtomicLong(1_000_000L)
  var networkId: Any? = "wifi-1"
  var expandShortLinks = true
  val connectAttempts = AtomicInteger()

  lateinit var reachability: TellomiLinkReachability
    private set

  private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
  private var closedPort = 0

  private val trustManager: X509TrustManager by lazy {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setCertificateEntry("fixture", certificate.second)
    }
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      .apply { init(trustStore) }
      .trustManagers
      .filterIsInstance<X509TrustManager>()
      .first()
  }

  private val clientSsl: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
  }

  override fun before() {
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(certificate.first, PASSWORD) }.keyManagers
    val serverSsl = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

    server.protocols = listOf(Protocol.HTTP_1_1)
    server.useHttps(serverSsl.socketFactory, false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val key = hostOf(request) + request.path
        return routes[key]?.invoke(request) ?: MockResponse().setResponseCode(404)
      }
    }
    server.start()

    closedPort = ServerSocket(0).use { it.localPort }
    reachability = TellomiLinkReachability(clock = { now.get() }, networkId = { networkId })
  }

  override fun after() {
    server.shutdown()
  }

  /** `https://host/path?query` 的请求由 [response] 回答。 */
  fun route(url: String, response: (RecordedRequest) -> MockResponse) {
    val httpUrl = okhttp3.HttpUrl.Builder().scheme("https").host("placeholder").build().resolve(url)!!
    val path = httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: "")
    routes[httpUrl.host + path] = response
  }

  fun route(url: String, response: MockResponse) = route(url) { response }

  fun redirect(from: String, to: String, code: Int = 302) = route(from, MockResponse().setResponseCode(code).setHeader("Location", to))

  fun html(url: String, body: String = FIXTURE_HTML) = route(url, MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body))

  /** 服务端收到的全部请求（不阻塞）。 */
  fun takeRequests(): List<RecordedRequest> {
    val all = ArrayList<RecordedRequest>()
    while (true) {
      all += server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
    }
    return all
  }

  fun fetcher(limits: TellomiLinkFetcher.Limits = TellomiLinkFetcher.Limits.P1): TellomiLinkFetcher {
    return TellomiLinkFetcher(
      reachability = reachability,
      expandShortLinks = { expandShortLinks },
      limits = limits,
      dns = dns,
      clock = { now.get() },
      transport = TellomiLinkFetcher.Transport(
        socketFactory = RoutingSocketFactory(),
        sslSocketFactory = clientSsl.socketFactory,
        trustManager = trustManager,
        proxy = Proxy.NO_PROXY
      )
    )
  }

  private fun hostOf(request: RecordedRequest): String = request.getHeader("Host")?.substringBefore(':') ?: ""

  inner class FakeDns : Dns {
    val lookups: MutableList<String> = CopyOnWriteArrayList()
    private val answers: MutableMap<String, List<InetAddress>> = Collections.synchronizedMap(HashMap())
    private val failures: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun resolve(host: String, vararg addresses: String) {
      answers[host] = addresses.map { InetAddress.getByName(it) }
    }

    fun resolve(host: String, address: InetAddress) {
      answers[host] = listOf(address)
    }

    fun fail(host: String) {
      failures += host
    }

    override fun lookup(hostname: String): List<InetAddress> {
      lookups += hostname
      if (hostname in failures) {
        throw UnknownHostException("fixture: no such host")
      }
      return answers[hostname] ?: if (hostname.endsWith(".$DOMAIN") || hostname.endsWith(".other-fixture.org")) listOf(PUBLIC) else throw UnknownHostException("fixture: unknown")
    }
  }

  private inner class RoutingSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = RoutingSocket()
    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
  }

  private inner class RoutingSocket : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
      connectAttempts.incrementAndGet()
      val target = endpoint as InetSocketAddress
      if (target.address == SLOW_CONNECT) {
        throw java.net.SocketTimeoutException("fixture: connect timed out")
      }
      val port = if (target.address == DEAD) closedPort else server.port
      super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout)
    }
  }
}
