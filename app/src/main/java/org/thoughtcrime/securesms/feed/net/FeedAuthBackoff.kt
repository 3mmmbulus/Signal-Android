/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * `GET /v1/tellomi/feed/auth` 在服务器上不存在（2026-10-16 起按「Signal-Server 还没部署这个接口」处理）。
 * 这种情况不能当成普通失败每个请求都重试一遍：[NotDeployedBackoffTokenSource] 认出它之后短时间内直接失败、不打网络。
 *
 * 和 iOS 草稿 `FeedAuthFailure` / `CachingFeedTokenSource.notDeployedBackoff` 同一套规则。
 */
object FeedAuthFailure {
  /** [CachingFeedTokenSource.fromSignalServer] 对任何状态码错误抛的代码。 */
  const val CODE_UNAVAILABLE = "auth_unavailable"

  /** 接口没部署；和引擎的 404 `feed_hidden` 不是一回事。 */
  const val CODE_NOT_DEPLOYED = "feed_auth_not_deployed"

  /** 这个路径没有处理器：404 / 405 / 501。401 / 403（账号鉴权）、429、5xx 是别的毛病，按普通错误处理。 */
  val NOT_DEPLOYED_STATUSES = setOf(404, 405, 501)

  /** 是「接口没部署」就返回换了代码的异常，不是返回 null。 */
  fun classify(error: FeedApiException): FeedApiException? {
    if (error.code == CODE_NOT_DEPLOYED) return error
    if (error.code == CODE_UNAVAILABLE && error.status in NOT_DEPLOYED_STATUSES) {
      return FeedApiException(error.status, CODE_NOT_DEPLOYED)
    }
    return null
  }
}

val FeedApiException.isAuthNotDeployed: Boolean
  get() = code == FeedAuthFailure.CODE_NOT_DEPLOYED

/**
 * 包在 [FeedTokenSource]（通常是 [CachingFeedTokenSource]）外面：里面抛出「接口没部署」之后，[backoff] 之内的所有调用
 * （包括引擎 401 之后的 `forceRefresh`）直接抛 [FeedAuthFailure.CODE_NOT_DEPLOYED]，不再碰里面。其他失败不退避。
 * 一次成功就解除。时钟往回拨过（现在早于记录的时刻）当作已过期，再问一次。
 *
 * 和 iOS 的一点差别：iOS 把这套放在缓存里面（缓存里还有没过期的 token 时先用）；这里在缓存外面，退避期间连没过期的缓存也不给。
 * 退避只会在刚刚取不到 token 之后开始，所以实际上没有差别（那时缓存里已经没有能用的）。
 * 整个调用在锁里，并发的调用排队，第二个进来时看到的是退避而不是再打一次网络。
 */
class NotDeployedBackoffTokenSource(
  private val inner: FeedTokenSource,
  private val backoff: Duration = BACKOFF,
  private val clock: () -> Long = System::currentTimeMillis
) : FeedTokenSource {

  companion object {
    val BACKOFF: Duration = Duration.ofMinutes(10)
  }

  private val mutex = Mutex()
  private var notDeployedSinceMillis: Long? = null

  override suspend fun token(forceRefresh: Boolean): String {
    return mutex.withLock {
      val now = clock()
      val since = notDeployedSinceMillis
      if (since != null && now - since in 0 until backoff.toMillis()) {
        throw FeedApiException(404, FeedAuthFailure.CODE_NOT_DEPLOYED)
      }
      try {
        inner.token(forceRefresh).also { notDeployedSinceMillis = null }
      } catch (e: FeedApiException) {
        val classified = FeedAuthFailure.classify(e)
        if (classified != null) {
          notDeployedSinceMillis = now
          throw classified
        }
        throw e
      }
    }
  }

  /** 退出登录、切区、或内部的「再试一次」：解除退避。里面的缓存要调用方自己 `clear()`。 */
  suspend fun resetBackoff() {
    mutex.withLock { notDeployedSinceMillis = null }
  }
}
