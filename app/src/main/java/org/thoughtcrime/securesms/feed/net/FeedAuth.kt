/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * `GET /v1/tellomi/feed/auth` 的回包（`docs/adr/0071/client-api-2026-10.md` 第二节）。
 * [region] 只用来选主机、做展示上的区域差异，不拿来做审核或过滤。
 */
@Serializable
data class FeedAuthResponse(
  val token: String,
  @SerialName("expires_at") val expiresAt: String,
  val region: String? = null
)

/** 向 Signal-Server 要动态凭证：走已登录的 WebSocket，和别的 `/v1/` 接口一样的账号鉴权。 */
class FeedAuthApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket) {
  fun getAuth(): NetworkResult<FeedAuthResponse> {
    val request = WebSocketRequestMessage.get(PATH)
    return NetworkResult.fromWebSocketRequest(signalWebSocket = authWebSocket, request = request, deserializer = FeedAuthResponse.serializer())
  }

  companion object {
    const val PATH = "/v1/tellomi/feed/auth"
  }
}

/**
 * 内存里缓存 token（契约第二节：不落盘）。离过期不到 [refreshBefore] 时先换新的；
 * 引擎回 401 时 [FeedApi] 用 `forceRefresh = true` 再取一次。并发的请求只发一次换取。
 */
class CachingFeedTokenSource(
  private val refreshBefore: Duration = REFRESH_BEFORE,
  private val clock: () -> Instant = Instant::now,
  private val fetch: suspend () -> FeedAuthResponse
) : FeedTokenSource {

  companion object {
    val REFRESH_BEFORE: Duration = Duration.ofMinutes(5)

    /** 正式的来源：Signal-Server 的 [FeedAuthApi]。 */
    fun fromSignalServer(api: FeedAuthApi): CachingFeedTokenSource {
      return CachingFeedTokenSource {
        withContext(Dispatchers.IO) {
          when (val result = api.getAuth()) {
            is NetworkResult.Success -> result.result
            is NetworkResult.StatusCodeError -> throw FeedApiException(result.code, code = "auth_unavailable")
            is NetworkResult.NetworkError -> throw result.exception
            is NetworkResult.ApplicationError -> throw IOException("feed auth failed", result.throwable)
          }
        }
      }
    }
  }

  private class Cached(val token: String, val expiresAt: Instant)

  private val mutex = Mutex()
  private var cached: Cached? = null

  override suspend fun token(forceRefresh: Boolean): String {
    return mutex.withLock {
      val current = cached
      if (!forceRefresh && current != null && clock() < current.expiresAt - refreshBefore) {
        return@withLock current.token
      }

      val response = fetch()
      val expiresAt = try {
        Instant.parse(response.expiresAt)
      } catch (e: DateTimeParseException) {
        // 过期时间读不懂就不缓存：这次用上，下次再换
        null
      }
      cached = expiresAt?.let { Cached(response.token, it) }
      response.token
    }
  }

  /** 退出登录、切区时丢掉缓存。 */
  suspend fun clear() {
    mutex.withLock { cached = null }
  }
}
