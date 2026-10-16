/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Signal-Server 的 tellomi 接口的错误：404 标记（`FRIEND-ASSERTION-404-MARKER.md`）、`Retry-After`、feed/auth 的限流冷却。 */
class FeedServerErrorsTest {

  private val now = Instant.parse("2026-10-16T00:00:00Z")
  private val marker = """{"error":"account_not_found"}"""
  private val json = "application/json"

  // ---------- 404 标记 ----------

  @Test
  fun `a 404 with a json content type and the account_not_found code is the marker`() {
    assertThat(FeedServerErrors.isAccountNotFound(404, json, marker)).isTrue()
    assertThat(FeedServerErrors.isAccountNotFound(404, "application/json; charset=utf-8", marker)).isTrue()
    assertThat(FeedServerErrors.isAccountNotFound(404, "Application/JSON", marker)).isTrue()
    assertThat(FeedServerErrors.isAccountNotFound(404, json, " \n{ \"error\" : \"account_not_found\" }\n")).isTrue()
    assertThat(FeedServerErrors.isAccountNotFound(404, json, """{"error":"account_not_found","extra":1}""")).isTrue() // 多出来的键忽略
  }

  @Test
  fun `a 404 without that exact marker is not an account that is missing`() {
    val bodies = listOf(
      null, "", "   ", "<html>404</html>", "Not Found", "{", "[]", "null", "\"account_not_found\"",
      """{"error":"not_found"}""", // 引擎风格的 404
      """{"error":"Account_Not_Found"}""",
      """{"error":"account_not_found "}""",
      """{"error":["account_not_found"]}""",
      """{"error":{"code":"account_not_found"}}""",
      """{"error":null}""",
      """{"message":"account_not_found"}""",
      """{"code":"account_not_found"}""",
      """{}"""
    )
    for (body in bodies) {
      assertThat(FeedServerErrors.isAccountNotFound(404, json, body), "body=$body").isFalse()
    }
  }

  @Test
  fun `the header is checked too, a json body served as something else is not the marker`() {
    for (type in listOf(null, "", "text/html", "text/plain", "application/xml", "application/jsonp", "json")) {
      assertThat(FeedServerErrors.isAccountNotFound(404, type, marker), "type=$type").isFalse()
    }
  }

  @Test
  fun `only a 404 can be the marker`() {
    for (status in listOf(200, 400, 401, 403, 409, 422, 429, 500, 503)) {
      assertThat(FeedServerErrors.isAccountNotFound(status, json, marker), "status=$status").isFalse()
    }
  }

  @Test
  fun `friend assertion add 404 with the marker means no account, nothing is thrown`() {
    assertThat(FeedServerErrors.friendAssertion(404, json, marker, null, add = true, now = now)).isNull()
  }

  @Test
  fun `friend assertion add 404 without the marker means the route is unavailable`() {
    val bodies = listOf(null, "", "<html></html>", """{"error":"not_found"}""")
    for (body in bodies) {
      val error = FeedServerErrors.friendAssertion(404, json, body, null, add = true, now = now)
      assertThat(error, "body=$body").isNotNull()
      assertThat(error!!.status).isEqualTo(404)
      assertThat(error.code).isEqualTo(FeedServerErrors.CODE_FRIEND_ROUTE_UNAVAILABLE)
    }
    // JSON 内容但没有 Content-Type 头
    assertThat(FeedServerErrors.friendAssertion(404, null, marker, null, add = true, now = now)!!.code).isEqualTo(FeedServerErrors.CODE_FRIEND_ROUTE_UNAVAILABLE)
  }

  @Test
  fun `friend assertion remove never means no account, even with the marker`() {
    val error = FeedServerErrors.friendAssertion(404, json, marker, null, add = false, now = now)
    assertThat(error!!.code).isEqualTo(FeedServerErrors.CODE_FRIEND_ROUTE_UNAVAILABLE)
  }

  @Test
  fun `friend assertion other statuses keep their status and carry retry-after only for 429 and 503`() {
    val limited = FeedServerErrors.friendAssertion(429, json, "{}", "30", add = true, now = now)!!
    assertThat(limited.status).isEqualTo(429)
    assertThat(limited.code).isEqualTo(FeedServerErrors.CODE_ASSERTION_UNAVAILABLE)
    assertThat(limited.retryAfterSeconds).isEqualTo(30L)
    assertThat(limited.isThrottled).isTrue()

    val dated = FeedServerErrors.friendAssertion(503, json, null, "Fri, 16 Oct 2026 00:01:00 GMT", add = true, now = now)!!
    assertThat(dated.retryAfterSeconds).isEqualTo(60L)
    assertThat(dated.isThrottled).isTrue()

    val busy = FeedServerErrors.friendAssertion(503, json, null, null, add = true, now = now)!!
    assertThat(busy.retryAfterSeconds).isNull()
    assertThat(busy.isThrottled).isFalse() // 503 without Retry-After is an ordinary outage

    val broken = FeedServerErrors.friendAssertion(500, json, null, "30", add = true, now = now)!!
    assertThat(broken.retryAfterSeconds).isNull() // a 500 has nothing to do with waiting
    assertThat(broken.isThrottled).isFalse()
    assertThat(FeedServerErrors.friendAssertion(401, json, null, null, add = true, now = now)!!.status).isEqualTo(401)
  }

  // ---------- audience-lists（没有标记：404 只可能是没有这个接口） ----------

  @Test
  fun `audience list 429 and 503 are try-later, the rest stays an invalid audience`() {
    val limited = FeedServerErrors.audienceList(429, "9", now)
    assertThat(limited.code).isEqualTo(FeedServerErrors.CODE_AUDIENCE_THROTTLED)
    assertThat(limited.retryAfterSeconds).isEqualTo(9L)
    assertThat(limited.isThrottled).isTrue()

    assertThat(FeedServerErrors.audienceList(503, "30", now).isThrottled).isTrue()
    assertThat(FeedServerErrors.audienceList(503, null, now).code).isEqualTo(FeedServerErrors.CODE_AUDIENCE_THROTTLED)
    for (status in listOf(400, 401, 404, 422, 500)) {
      val error = FeedServerErrors.audienceList(status, "30", now)
      assertThat(error.code, "status=$status").isEqualTo(FeedServerErrors.CODE_INVALID_AUDIENCE)
      assertThat(error.retryAfterSeconds).isNull()
    }
  }

  // ---------- feed/auth ----------

  @Test
  fun `auth errors keep the auth_unavailable code, 404 is still classified as not deployed, 429 is throttled`() {
    val missing = FeedServerErrors.auth(404, null, now)
    assertThat(missing.code).isEqualTo(FeedAuthFailure.CODE_UNAVAILABLE)
    assertThat(FeedAuthFailure.classify(missing)!!.code).isEqualTo(FeedAuthFailure.CODE_NOT_DEPLOYED)
    assertThat(missing.isThrottled).isFalse()

    val limited = FeedServerErrors.auth(429, "20", now)
    assertThat(limited.retryAfterSeconds).isEqualTo(20L)
    assertThat(limited.isThrottled).isTrue()
    assertThat(FeedAuthFailure.classify(limited)).isNull()
    assertThat(FeedServerErrors.auth(401, "20", now).retryAfterSeconds).isNull()
  }

  private suspend fun failure(block: suspend () -> Any?): FeedApiException {
    try {
      block()
    } catch (e: FeedApiException) {
      return e
    }
    throw AssertionError("expected a FeedApiException")
  }

  private class ScriptedTokens(private val answers: MutableList<() -> String>) : FeedTokenSource {
    var calls = 0
    override suspend fun token(forceRefresh: Boolean): String {
      calls++
      return answers.removeFirst()()
    }
  }

  private var clockMillis = 5_000_000L
  private var random = 0.0

  private fun throttleSource(inner: FeedTokenSource) = ThrottleBackoffTokenSource(inner, FeedCooldown({ clockMillis }, { random }))

  @Test
  fun `a throttled auth call cools down locally for the retry-after and then asks again`() = runBlocking {
    val inner = ScriptedTokens(mutableListOf({ throw FeedApiException(429, "auth_unavailable", 20) }, { "t2" }))
    val source = throttleSource(inner)

    assertThat(failure { source.token(false) }.status).isEqualTo(429)
    assertThat(inner.calls).isEqualTo(1)

    clockMillis += 5_000
    assertThat(failure { source.token(false) }.code).isEqualTo(ThrottleBackoffTokenSource.CODE_AUTH_THROTTLED)
    assertThat(failure { source.token(true) }.retryAfterSeconds).isEqualTo(15L) // 剩 15 秒，forceRefresh 也一样
    assertThat(inner.calls).isEqualTo(1) // 没有碰 Signal-Server

    clockMillis += 15_000
    assertThat(source.token(false)).isEqualTo("t2")
    assertThat(inner.calls).isEqualTo(2)
  }

  @Test
  fun `auth 503 with retry-after throttles, 503 without and other failures do not`() = runBlocking {
    val inner = ScriptedTokens(
      mutableListOf(
        { throw FeedApiException(503, "auth_unavailable") },
        { throw FeedApiException(500, "auth_unavailable", null) },
        { throw IOException("offline") },
        { throw FeedApiException(503, "auth_unavailable", 30) }
      )
    )
    val source = throttleSource(inner)
    repeat(3) { try { source.token(false) } catch (e: Exception) { } }
    assertThat(inner.calls).isEqualTo(3) // 每次都问到了里面：没有退避
    failure { source.token(false) }
    assertThat(failure { source.token(false) }.code).isEqualTo(ThrottleBackoffTokenSource.CODE_AUTH_THROTTLED)
    assertThat(inner.calls).isEqualTo(4)
  }

  @Test
  fun `a throttle without retry-after backs off exponentially with jitter, a success resets it`() = runBlocking {
    val inner = ScriptedTokens(
      mutableListOf(
        { throw FeedApiException(429, "auth_unavailable") },
        { throw FeedApiException(429, "auth_unavailable") },
        { "ok" },
        { throw FeedApiException(429, "auth_unavailable") },
        { "again" }
      )
    )
    val source = throttleSource(inner)
    random = 0.0
    failure { source.token(false) }
    clockMillis += 14_999
    assertThat(failure { source.token(false) }.code).isEqualTo(ThrottleBackoffTokenSource.CODE_AUTH_THROTTLED)
    clockMillis += 1 // 第一次：30 秒的下半区 = 15 秒
    assertThat(failure { source.token(false) }.code).isEqualTo("auth_unavailable") // 又问了一次，又被限流
    clockMillis += 29_999
    assertThat(failure { source.token(false) }.code).isEqualTo(ThrottleBackoffTokenSource.CODE_AUTH_THROTTLED)
    clockMillis += 1 // 第二次：60 秒的下半区 = 30 秒
    assertThat(source.token(false)).isEqualTo("ok")
    failure { source.token(false) } // 成功清了连续次数：回到 15 秒
    clockMillis += 14_999
    failure { source.token(false) } // still cooling down: the streak was cleared by the success, so it is 15 s and not 60 s
    assertThat(inner.calls).isEqualTo(4)
    clockMillis += 1
    assertThat(source.token(false)).isEqualTo("again")
  }

  @Test
  fun `reset lifts the cooldown`() = runBlocking {
    val inner = ScriptedTokens(mutableListOf({ throw FeedApiException(429, "auth_unavailable", 600) }, { "fresh" }))
    val source = throttleSource(inner)
    failure { source.token(false) }
    source.reset()
    assertThat(source.token(false)).isEqualTo("fresh")
  }

  @Test
  fun `stacked on the not-deployed backoff a 404 is not a throttle`() = runBlocking {
    val inner = ScriptedTokens(mutableListOf({ throw FeedServerErrors.auth(404, null, now) }, { "t" }))
    val stacked = throttleSource(NotDeployedBackoffTokenSource(inner, clock = { clockMillis }))
    assertThat(failure { stacked.token(false) }.code).isEqualTo(FeedAuthFailure.CODE_NOT_DEPLOYED)
    // 没部署的退避挡住第二次（里面没再被问），但那不是限流的冷却
    assertThat(failure { stacked.token(false) }.code).isEqualTo(FeedAuthFailure.CODE_NOT_DEPLOYED)
    assertThat(inner.calls).isEqualTo(1)
  }
}
