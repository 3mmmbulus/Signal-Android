/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.common.net.InetAddresses
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.GzipSource
import okio.buffer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.LinkUtil
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.min

/**
 * 第三方链接预览的抓取器，按 ADR-0063 §4.4 的抓取契约（tellomi/tellomi#1422；§8.1 第 5 行）。
 *
 * - 请求头只有 `User-Agent: WhatsApp/2`、`Accept`、`Accept-Encoding: gzip`（外加 HTTP/1.1 必须的 `Host`）；不存、不带 cookie。
 * - 只抓 https。重定向自己跟：每个请求最多 5 跳，每一跳重做 https / 域名（[LinkUtil.isValidPreviewUrl]）/ 私网校验；
 *   私网按 [TellomiLinkAddressPolicy]：IP 字面量在发请求前查，域名在自定义 [Dns] 里查——解析结果直接用来连接，查和用之间没有时间差。
 * - 连接 5 s、单个请求（含全部跳转和读正文）10 s；每条链接（一个 [Session]）总共 10 s、最多 3 个元数据请求（短链展开算 1 个）+ 1 个图片请求。
 *   OkHttp 自己的重试（连接失败重试、503 `Retry-After: 0`、408……）一律关掉：一跳只许一个网络请求。
 * - `Content-Type` 按步骤白名单；体积按**解压后**的字节数算：HTML 2 MiB、JSON 256 KiB，图片沿用上游的 2 MiB。只认 gzip 和不压缩。
 *   JSON / 图片超了就放弃；**HTML 超了只留开头 2 MiB 照常交回解析**（OG 在 `<head>`，靠前；产品经理 2026-10-19 定）。
 * - 短链只读 `Location`，不读正文、不跟随（http 的 `Location` 也只拿来识别）；「展开短链接」关着时一个请求都不发。
 * - 网络层明确失败（DNS、连接被拒 / 被重置、TLS——连上之前的失败）记进 [TellomiLinkReachability]，同一 host 之后直接跳过；
 *   **超时（含连接超时）不记**（产品经理 2026-10-19 定，ADR-0063 §4.4）。
 * - 日志只记步骤、结果类别、状态码和跳数，**不记 URL、host，也不记异常**（异常信息里可能带 URL，§6.5）。
 *
 * 结构化步骤「重定向后仍在同一个 provider」要按注册表核，那是 `rust/links` 的事：这里把最终 URL 放在结果里交回去。
 * JSON 的嵌套深度（≤ 32）也在 crate 里解析时查。
 */
class TellomiLinkFetcher(
  private val reachability: TellomiLinkReachability,
  private val expandShortLinks: () -> Boolean,
  private val limits: Limits = Limits.P1,
  private val dns: Dns = Dns.SYSTEM,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val transport: Transport = Transport()
) {

  companion object {
    private val TAG = Log.tag(TellomiLinkFetcher::class.java)

    const val USER_AGENT = "WhatsApp/2"

    /** 整个进程一份：可达性记录要跨链接共享。 */
    @JvmStatic
    val default: TellomiLinkFetcher by lazy { create(AppDependencies.application) }

    private fun create(context: Context): TellomiLinkFetcher {
      val connectivity = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
      val clock = { SystemClock.elapsedRealtime() }
      return TellomiLinkFetcher(
        reachability = TellomiLinkReachability(clock = clock, networkId = { connectivity?.activeNetwork?.networkHandle }),
        expandShortLinks = { SignalStore.tellomiLinks.expandShortLinks },
        clock = clock
      )
    }
  }

  data class Limits(
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 10_000,
    val linkBudgetMs: Long = 10_000,
    val maxRedirects: Int = 5,
    val maxMetadataRequests: Int = 3,
    val maxImageRequests: Int = 1,
    val htmlMaxBytes: Long = 2L * 1024 * 1024,
    val jsonMaxBytes: Long = 256L * 1024,
    val imageMaxBytes: Long = 2L * 1024 * 1024
  ) {
    fun maxBytes(step: Step): Long {
      return when (step) {
        Step.HTML -> htmlMaxBytes
        Step.JSON -> jsonMaxBytes
        Step.IMAGE -> imageMaxBytes
        Step.SHORT_LINK -> 0
      }
    }

    companion object {
      /** ADR-0063 §4.4 的数。 */
      @JvmField
      val P1 = Limits()
    }
  }

  /** 只给测试用：把连接改到本机测试服务、信任自签证书、不走系统代理。生产用默认值。 */
  class Transport(
    val socketFactory: SocketFactory? = null,
    val sslSocketFactory: SSLSocketFactory? = null,
    val trustManager: X509TrustManager? = null,
    val proxy: Proxy? = null
  )

  enum class Step(val accept: String) {
    /** 页面（OG / JSON-LD / title-meta）：只收 `text/html`。 */
    HTML("text/html"),

    /** 公开 API / oEmbed：只收 `application/json` 与 `application/<x>+json`。 */
    JSON("application/json"),

    /** 短链展开：只读 `Location`，不读正文。 */
    SHORT_LINK("*/*"),

    /** 预览图：只收 `image/<x>`。 */
    IMAGE("image/*");

    fun accepts(contentType: MediaType?): Boolean {
      if (contentType == null) {
        return false
      }
      val type = contentType.type.lowercase(Locale.ROOT)
      val subtype = contentType.subtype.lowercase(Locale.ROOT)
      return when (this) {
        HTML -> type == "text" && subtype == "html"
        JSON -> type == "application" && (subtype == "json" || subtype.endsWith("+json"))
        IMAGE -> type == "image"
        SHORT_LINK -> false
      }
    }
  }

  /**
   * One request the way `rust/links` asked for it (tellomi/tellomi#1422 send side): its `Accept`, the content
   * types it takes, its size and redirect limits, and what is left of its time. Never looser than [limits]:
   * each value is capped by the fetcher's own. A bare [Step] keeps the fetcher's defaults.
   */
  data class Spec(
    val step: Step,
    val accept: String = step.accept,
    /** Lower-case `type/subtype`; null → the [Step]'s own rule. */
    val contentTypes: Set<String>? = null,
    val maxBytes: Long? = null,
    val maxRedirects: Int? = null,
    val timeoutMs: Long? = null
  ) {
    fun accepts(contentType: MediaType?): Boolean {
      if (contentTypes == null) {
        return step.accepts(contentType)
      }
      if (contentType == null) {
        return false
      }
      return "${contentType.type}/${contentType.subtype}".lowercase(Locale.ROOT) in contentTypes
    }
  }

  enum class Reason {
    NOT_HTTPS,
    INVALID_URL,
    BLOCKED_ADDRESS,
    UNREACHABLE,
    SKIPPED_UNREACHABLE,
    REDIRECT_LIMIT,
    BAD_REDIRECT,
    NOT_REDIRECT,
    HTTP_STATUS,
    CONTENT_TYPE,
    CONTENT_ENCODING,
    TOO_LARGE,
    TIMEOUT,
    BUDGET,
    DISABLED,
    CANCELLED,
    IO
  }

  /** 结果的 `toString()` 一律不带 URL，谁把它打进日志都不会泄露。 */
  sealed class Result {
    class Body(
      val finalUrl: HttpUrl,
      val contentType: MediaType,
      val bytes: ByteArray,
      /** HTML 超过上限、只读了开头 `htmlMaxBytes` 字节。 */
      val truncated: Boolean = false
    ) : Result() {
      /** 按 `Content-Type` 的 charset 解码；没有就照上游看页面里的 `charset=`，再没有用 UTF-8。 */
      fun text(): String {
        val charset = contentType.charset(null) ?: sniffCharset(bytes) ?: StandardCharsets.UTF_8
        return String(bytes, charset)
      }

      override fun toString(): String = "Body(${bytes.size} bytes)"
    }

    /** 短链展开的结果：`Location` 解析成绝对地址，**没有**被请求过；[status] 是那个 3xx。 */
    class Location(val location: HttpUrl, val status: Int) : Result() {
      override fun toString(): String = "Location"
    }

    class Failure(
      val reason: Reason,
      val finalUrl: HttpUrl? = null,
      val contentType: MediaType? = null,
      val httpCode: Int = 0,
      /** 连上之前就明确失败（DNS、TCP 被拒 / 被重置、TLS；**不含**超时）：§4.3 的网络层失败。 */
      val beforeConnect: Boolean = false
    ) : Result() {
      /** 页面步骤拿到的是一张图（链接直接指向图片）：上游把它当预览图用，这里把最终地址交回去，由图片步骤去取。 */
      fun isDirectImage(): Boolean = reason == Reason.CONTENT_TYPE && finalUrl != null && contentType?.type == "image"

      override fun toString(): String = "Failure($reason)"
    }
  }

  private val baseClient: OkHttpClient = OkHttpClient.Builder()
    .cache(null)
    .cookieJar(CookieJar.NO_COOKIES)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(limits.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(limits.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(limits.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .eventListener(HopEvents)
    .addNetworkInterceptor(OneNetworkRequestPerHop)
    .apply {
      transport.socketFactory?.let { socketFactory(it) }
      if (transport.sslSocketFactory != null && transport.trustManager != null) {
        sslSocketFactory(transport.sslSocketFactory, transport.trustManager)
      }
      transport.proxy?.let { proxy(it) }
    }
    .build()

  /** 一条链接一个 session：10 s 与 3 + 1 个请求的预算按 session 算。 */
  fun newSession(): Session = Session()

  /** 当前网络上已知不可达的 host（§4.3），作为 `unreachable_hosts` 交给 `rust/links`。 */
  fun unreachableHosts(): List<String> = reachability.unreachableHosts()

  inner class Session internal constructor() {
    private val deadline = clock() + limits.linkBudgetMs
    private var metadataRequests = 0
    private var imageRequests = 0

    @Volatile
    private var cancelled = false

    @Volatile
    private var inFlight: Call? = null

    fun fetch(url: String, step: Step): Result = fetch(url, Spec(step))

    fun fetch(url: String, spec: Spec): Result {
      val outcome = fetchInternal(url, spec)
      log(spec.step, outcome.result, outcome.hops)
      return outcome.result
    }

    fun cancel() {
      cancelled = true
      inFlight?.cancel()
    }

    private fun fetchInternal(url: String, spec: Spec): Outcome {
      val step = spec.step
      if (cancelled) {
        return Outcome(Result.Failure(Reason.CANCELLED))
      }
      if (step == Step.SHORT_LINK && !expandShortLinks()) {
        return Outcome(Result.Failure(Reason.DISABLED))
      }

      var current: HttpUrl = url.trim().toHttpUrlOrNull() ?: return Outcome(Result.Failure(Reason.INVALID_URL))
      val requestDeadline = clock() + min(limits.requestTimeoutMs, spec.timeoutMs ?: Long.MAX_VALUE)
      val maxRedirects = min(limits.maxRedirects, spec.maxRedirects ?: Int.MAX_VALUE)
      var reserved = false
      var hops = 0

      while (true) {
        validate(current)?.let { return Outcome(Result.Failure(it), hops) }
        if (reachability.isUnreachable(current.host)) {
          return Outcome(Result.Failure(Reason.SKIPPED_UNREACHABLE), hops)
        }

        val now = clock()
        if (!reserved) {
          if (now >= deadline || !reserve(step)) {
            return Outcome(Result.Failure(Reason.BUDGET), hops)
          }
          reserved = true
        }
        val remaining = min(requestDeadline, deadline) - now
        if (remaining <= 0) {
          return Outcome(Result.Failure(Reason.TIMEOUT), hops)
        }

        when (val hop = exchange(current, spec, remaining)) {
          is Hop.Done -> return Outcome(hop.result, hops)
          is Hop.Redirect -> {
            hops++
            if (hops > maxRedirects) {
              return Outcome(Result.Failure(Reason.REDIRECT_LIMIT), hops)
            }
            current = hop.next
          }
        }
      }
    }

    @Synchronized
    private fun reserve(step: Step): Boolean {
      return if (step == Step.IMAGE) {
        if (imageRequests >= limits.maxImageRequests) false else true.also { imageRequests++ }
      } else {
        if (metadataRequests >= limits.maxMetadataRequests) false else true.also { metadataRequests++ }
      }
    }

    private fun exchange(url: HttpUrl, spec: Spec, timeoutMs: Long): Hop {
      val state = HopState()
      val request = Request.Builder()
        .url(url.newBuilder().fragment(null).build())
        .header("User-Agent", USER_AGENT)
        .header("Accept", spec.accept)
        .header("Accept-Encoding", "gzip")
        .tag(HopState::class.java, state)
        .get()
        .build()

      val call = baseClient.newBuilder()
        .dns(TargetCheckingDns(dns, url.host))
        .build()
        .newCall(request)
      call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)

      inFlight = call
      if (cancelled) {
        call.cancel()
      }

      return try {
        call.execute().use { response -> read(response, url, spec) }
      } catch (e: BlockedAddressException) {
        Hop.Done(Result.Failure(Reason.BLOCKED_ADDRESS))
      } catch (e: IOException) {
        when {
          cancelled -> Hop.Done(Result.Failure(Reason.CANCELLED))
          // 连上之前就超时：不算「明确不可达」，不记（产品经理 2026-10-19 定，ADR-0063 §4.4）
          !state.connected && e is InterruptedIOException -> Hop.Done(Result.Failure(Reason.TIMEOUT))
          !state.connected -> {
            // 连上之前就明确失败：DNS、TCP 被拒 / 被重置、TLS。这是 §4.3 说的网络层失败。
            reachability.markUnreachable(url.host)
            Hop.Done(Result.Failure(Reason.UNREACHABLE, beforeConnect = true))
          }
          e is InterruptedIOException -> Hop.Done(Result.Failure(Reason.TIMEOUT))
          else -> Hop.Done(Result.Failure(Reason.IO))
        }
      } finally {
        inFlight = null
      }
    }

    private fun read(response: Response, url: HttpUrl, spec: Spec): Hop {
      val step = spec.step
      if (response.isRedirect) {
        val location = response.header("Location") ?: return Hop.Done(Result.Failure(Reason.BAD_REDIRECT, httpCode = response.code))
        val next = url.resolve(location) ?: return Hop.Done(Result.Failure(Reason.BAD_REDIRECT, httpCode = response.code))
        return if (step == Step.SHORT_LINK) Hop.Done(Result.Location(next, response.code)) else Hop.Redirect(next)
      }
      if (step == Step.SHORT_LINK) {
        return Hop.Done(Result.Failure(Reason.NOT_REDIRECT, httpCode = response.code))
      }
      if (!response.isSuccessful) {
        return Hop.Done(Result.Failure(Reason.HTTP_STATUS, finalUrl = url, httpCode = response.code))
      }

      val body = response.body
      val contentType = body.contentType()
      if (contentType == null || !spec.accepts(contentType)) {
        return Hop.Done(Result.Failure(Reason.CONTENT_TYPE, finalUrl = url, contentType = contentType))
      }

      val max = min(limits.maxBytes(step), spec.maxBytes ?: Long.MAX_VALUE)
      // HTML 不因为大而放弃：只读开头 max 字节（产品经理 2026-10-19 定，ADR-0063 §4.4）
      val truncates = step == Step.HTML
      val source: BufferedSource = when (response.header("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "identity" -> {
          if (!truncates && body.contentLength() > max) {
            return Hop.Done(Result.Failure(Reason.TOO_LARGE))
          }
          body.source()
        }
        "gzip" -> GzipSource(body.source()).buffer()
        else -> return Hop.Done(Result.Failure(Reason.CONTENT_ENCODING))
      }

      val buffer = Buffer()
      source.use {
        while (it.read(buffer, 8192) != -1L) {
          if (buffer.size > max) {
            if (truncates) {
              // 留下开头正好 max 字节，剩下的不读了（关掉 source 就断开）
              return Hop.Done(Result.Body(url, contentType, buffer.readByteArray(max), truncated = true))
            }
            return Hop.Done(Result.Failure(Reason.TOO_LARGE))
          }
        }
      }
      return Hop.Done(Result.Body(url, contentType, buffer.readByteArray()))
    }
  }

  /** 每一跳发请求前的校验：只许 https，域名过上游的规则，IP 字面量不许落在私网段（域名的解析结果在 [TargetCheckingDns] 里查）。 */
  private fun validate(url: HttpUrl): Reason? {
    if (!url.isHttps) {
      return Reason.NOT_HTTPS
    }
    if (InetAddresses.isInetAddress(url.host) && TellomiLinkAddressPolicy.isBlocked(InetAddresses.forString(url.host))) {
      return Reason.BLOCKED_ADDRESS
    }
    if (!LinkUtil.isValidPreviewUrl(url.toString())) {
      return Reason.INVALID_URL
    }
    return null
  }

  private fun log(step: Step, result: Result, hops: Int) {
    when (result) {
      is Result.Body -> Log.i(TAG, "${step.name}: ok, ${result.bytes.size} bytes, $hops redirect(s)")
      is Result.Location -> Log.i(TAG, "${step.name}: got a location")
      is Result.Failure -> {
        val code = if (result.httpCode > 0) " (${result.httpCode})" else ""
        Log.w(TAG, "${step.name}: ${result.reason}$code after $hops redirect(s)")
      }
    }
  }

  private class Outcome(val result: Result, val hops: Int = 0)

  private sealed class Hop {
    class Done(val result: Result) : Hop()
    class Redirect(val next: HttpUrl) : Hop()
  }

  private class HopState {
    @Volatile
    var connected = false
    val networkRequests = AtomicInteger()
  }

  /** 只对这一跳的目标主机查解析结果；别的查询（例如系统 HTTP 代理自己的主机名）照常放行。 */
  private class TargetCheckingDns(private val delegate: Dns, private val targetHost: String) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      val addresses = delegate.lookup(hostname)
      if (hostname.equals(targetHost, ignoreCase = true) && addresses.any { TellomiLinkAddressPolicy.isBlocked(it) }) {
        throw BlockedAddressException()
      }
      return addresses
    }
  }

  private class BlockedAddressException : IOException("Resolved to a blocked address range")

  /** 连上以后的失败不算网络层失败（§4.3：HTTP 状态码、服务端慢都不记）。 */
  private object HopEvents : EventListener() {
    override fun connectionAcquired(call: Call, connection: Connection) {
      call.request().tag(HopState::class.java)?.connected = true
    }
  }

  /**
   * 一跳只许一个网络请求：去掉响应里的 `Retry-After`（不然 OkHttp 会对 `503 Retry-After: 0` 自动重发），
   * 其余自动重发（408 / 421 等）要是还发生就直接断掉。另外去掉 OkHttp 自己加的 `Connection: Keep-Alive`，请求头只剩契约里那几个。
   */
  private object OneNetworkRequestPerHop : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val state = chain.request().tag(HopState::class.java)
      if (state != null && state.networkRequests.incrementAndGet() > 1) {
        throw IOException("Only one network request per hop")
      }
      val response = chain.proceed(chain.request().newBuilder().removeHeader("Connection").build())
      return response.newBuilder().removeHeader("Retry-After").build()
    }
  }
}

private fun sniffCharset(bytes: ByteArray): Charset? {
  val matcher = CHARSET_SNIFF.matcher(String(bytes, StandardCharsets.ISO_8859_1))
  if (!matcher.find()) {
    return null
  }
  return try {
    Charset.forName(matcher.group(1))
  } catch (e: Exception) {
    null
  }
}

private val CHARSET_SNIFF: Pattern = Pattern.compile("charset=[\"']?([a-zA-Z0-9\\\\-]+)[\"']?")
