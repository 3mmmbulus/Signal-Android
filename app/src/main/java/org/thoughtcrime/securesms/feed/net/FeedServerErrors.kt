/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * 把 Signal-Server 的 tellomi 接口（`/v1/tellomi/feed/auth`、`friend-assertions`、`audience-lists`，走已登录的 WebSocket）的错误回包
 * 变成 [FeedApiException]：状态码、机器可读代码、`Retry-After`（[FeedRetryAfter]，夹在 1…3600 秒）。纯函数，WebSocket 那一头只负责把状态码、
 * `Content-Type`、`Retry-After` 头和正文字符串递进来。
 */
object FeedServerErrors {
  /** 好友声明：对方没有账号（服务端 `POST friend-assertions` 的 404 标记，见 `FRIEND-ASSERTION-404-MARKER.md`）。 */
  const val ACCOUNT_NOT_FOUND = "account_not_found"

  /** 好友声明：这台服务器没有这个接口（404 但不是上面的标记）。 */
  const val CODE_FRIEND_ROUTE_UNAVAILABLE = "assertion_route_unavailable"

  /** 好友声明：别的失败（429 / 5xx / 其他）。 */
  const val CODE_ASSERTION_UNAVAILABLE = "assertion_unavailable"

  /** 名单：被限流 / 服务端暂时不可用（不是名单本身有问题）。 */
  const val CODE_AUDIENCE_THROTTLED = "audience_throttled"
  const val CODE_INVALID_AUDIENCE = "invalid_audience"

  /**
   * `POST friend-assertions` 的 404 是不是「对方没有账号」：**Content-Type 是 JSON，正文是一个对象，`error` 的值是字符串 `account_not_found`**，
   * 三条都满足才算。空正文、HTML、不是 JSON、没有这个 code 的 JSON（包括引擎风格的 `not_found`）、读不懂的正文、`error` 不是字符串、
   * 正文是 JSON 但 `Content-Type` 是 `text/html` ——都不是（路由不存在）。对象里多出来的键忽略。
   */
  @JvmStatic
  fun isAccountNotFound(status: Int, contentType: String?, body: String?): Boolean {
    if (status != 404 || body == null || body.isBlank()) return false
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    if (mime != "application/json") return false
    val root = try {
      FeedJson.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
      return false
    }
    val error = (root as? JsonObject)?.get("error") as? JsonPrimitive ?: return false
    return error.isString && error.content == ACCOUNT_NOT_FOUND
  }

  /** 429，或带 `Retry-After` 的 503：才带上 `Retry-After`；别的状态码即使带了这个头也不理（401 / 404 / 500 与等待无关）。 */
  @JvmStatic
  fun retryAfterFor(status: Int, retryAfterHeader: String?, now: Instant): Long? {
    if (status != 429 && status != 503) return null
    return FeedRetryAfter.parse(retryAfterHeader, now)
  }

  /** `GET feed/auth` 的状态码错误。 */
  @JvmStatic
  fun auth(status: Int, retryAfterHeader: String?, now: Instant = Instant.now()): FeedApiException {
    return FeedApiException(status, FeedAuthFailure.CODE_UNAVAILABLE, retryAfterFor(status, retryAfterHeader, now))
  }

  /**
   * `POST` / `DELETE friend-assertions` 的状态码错误（成功不走这里）。
   * 加好友（[add]）时 404 只有带标记才算「对方没有账号」：这种情况返回 null（调用方跳过这个人，可以记为已处理）；
   * 其他 404 抛 [CODE_FRIEND_ROUTE_UNAVAILABLE]（接口不可用，不能记为已处理）。撤回（DELETE）从不回 `account_not_found`，404 一律是接口不可用。
   */
  @JvmStatic
  fun friendAssertion(status: Int, contentType: String?, body: String?, retryAfterHeader: String?, add: Boolean, now: Instant = Instant.now()): FeedApiException? {
    if (status == 404) {
      if (add && isAccountNotFound(status, contentType, body)) return null
      return FeedApiException(404, CODE_FRIEND_ROUTE_UNAVAILABLE)
    }
    return FeedApiException(status, CODE_ASSERTION_UNAVAILABLE, retryAfterFor(status, retryAfterHeader, now))
  }

  /** `POST audience-lists` 的状态码错误：429 / 503 是「稍后再试」，其他（400 / 422 / 404 / 401）和以前一样当名单不对。 */
  @JvmStatic
  fun audienceList(status: Int, retryAfterHeader: String?, now: Instant = Instant.now()): FeedApiException {
    val retryAfter = retryAfterFor(status, retryAfterHeader, now)
    val throttled = status == 429 || status == 503
    return FeedApiException(status, if (throttled) CODE_AUDIENCE_THROTTLED else CODE_INVALID_AUDIENCE, retryAfter)
  }
}

/**
 * 包在 [FeedTokenSource] 外面（和 [NotDeployedBackoffTokenSource] 叠着用）：里面抛出被限流（429，或带 `Retry-After` 的 503）之后，
 * [cooldown] 之内所有调用（包括引擎 401 之后的 `forceRefresh`）本地直接抛 429 `auth_throttled`（`retryAfterSeconds` = 还要等几秒），不碰 Signal-Server。
 * 等多久按 `Retry-After`，没有就指数退避加抖动（[FeedCooldown]）。一次成功清掉连续次数。其他失败不退避。
 */
class ThrottleBackoffTokenSource(
  private val inner: FeedTokenSource,
  private val cooldown: FeedCooldown = FeedCooldown()
) : FeedTokenSource {

  companion object {
    const val CODE_AUTH_THROTTLED = "auth_throttled"
  }

  override suspend fun token(forceRefresh: Boolean): String {
    if (cooldown.isCoolingDown()) {
      throw FeedApiException(429, CODE_AUTH_THROTTLED, cooldown.remainingSeconds())
    }
    try {
      return inner.token(forceRefresh).also { cooldown.onSuccess() }
    } catch (e: FeedApiException) {
      if (e.isThrottled) cooldown.onThrottled(e.retryAfterSeconds)
      throw e
    }
  }

  /** 退出登录、切区：解除。 */
  fun reset() = cooldown.reset()
}
