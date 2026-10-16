/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FeedCapabilitiesTest {

  private val engineBody = """{"version":1,"features":["feed","events","posting","audience_tokens","comments","reactions","polls","friend_assertions","follow","blocks","profiles","search","topics","notifications","reports"]}"""

  @Test
  fun `a full answer from the engine enables friend assertions`() {
    val caps = FeedCapabilities.parse(200, engineBody)

    assertThat(caps.friendAssertions).isTrue()
    assertThat(caps.supports(FeedCapabilities.FEED)).isTrue()
    assertThat(caps.supports(FeedCapabilities.REPORTS)).isTrue()
    assertThat(caps.supports(FeedCapabilities.MEDIA_UPLOAD)).isFalse()
    assertThat(caps.features.size).isEqualTo(15)
  }

  @Test
  fun `friend_sync is not a feature name`() {
    val caps = FeedCapabilities.parse(200, """{"version":1,"features":["friend_sync","friend_report"]}""")

    assertThat(caps.friendAssertions).isFalse()
  }

  @Test
  fun `404 and other statuses disable everything`() {
    for (status in listOf(404, 204, 301, 401, 403, 429, 500, 503)) {
      assertThat(FeedCapabilities.parse(status, engineBody)).isEqualTo(FeedCapabilities.DISABLED)
      assertThat(FeedCapabilities.parseOrNull(status, engineBody)).isNull()
    }
  }

  @Test
  fun `malformed answers disable everything`() {
    val bad = listOf(
      null, "", "   ", "not json", "{", "[]", "\"x\"", "null", "{}",
      """{"features":["friend_assertions"]}""", // no version
      """{"version":2,"features":["friend_assertions"]}""",
      """{"version":0,"features":["friend_assertions"]}""",
      """{"version":"1","features":["friend_assertions"]}""",
      """{"version":1.5,"features":["friend_assertions"]}""",
      """{"version":null,"features":["friend_assertions"]}""",
      """{"version":1}""",
      """{"version":1,"features":null}""",
      """{"version":1,"features":"friend_assertions"}""",
      """{"version":1,"features":{"friend_assertions":true}}""",
      """{"version":[1],"features":["friend_assertions"]}""",
      """{"version":1,"features":["friend_assertions"]""", // truncated
      "x".repeat(FeedCapabilities.MAX_BODY_CHARS + 1)
    )
    for (body in bad) {
      assertThat(FeedCapabilities.parseOrNull(200, body)).isNull()
      assertThat(FeedCapabilities.parse(200, body)).isEqualTo(FeedCapabilities.DISABLED)
    }
  }

  @Test
  fun `non string elements are ignored, names are case sensitive, unknown names and fields are fine`() {
    val caps = FeedCapabilities.parse(
      200,
      """{"version":1,"extra":{"a":1},"features":["friend_assertions",1,null,true,["x"],{"y":1},"Feed","future_thing"]}"""
    )

    assertThat(caps.features).containsExactlyInAnyOrder("friend_assertions", "Feed", "future_thing")
    assertThat(caps.supports("feed")).isFalse()
    assertThat(caps.friendAssertions).isTrue()
  }

  @Test
  fun `an empty feature list is a valid answer that enables nothing`() {
    val caps = FeedCapabilities.parseOrNull(200, """{"version":1,"features":[]}""")

    assertThat(caps).isNotNull()
    assertThat(caps!!.features).isEmpty()
    assertThat(caps.friendAssertions).isFalse()
  }

  @Test
  fun `checker before any check is disabled`() {
    val checker = FeedCapabilityChecker(fetch = { error("not called") })

    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isFalse()
    assertThat(checker.capabilities()).isEqualTo(FeedCapabilities.DISABLED)
  }

  @Test
  fun `checker enables after a good answer and asks again only after the ttl`() = runBlocking {
    var now = 1_000_000L
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(
      fetch = { calls.incrementAndGet(); FeedCapabilitiesResponse(200, engineBody) },
      clock = { now }
    )

    checker.refreshIfStale()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isTrue()
    now += FeedCapabilityChecker.OK_TTL_MILLIS - 1
    checker.refreshIfStale()
    assertThat(calls.get()).isEqualTo(1)
    now += 1
    checker.refreshIfStale()
    assertThat(calls.get()).isEqualTo(2)
  }

  @Test
  fun `404, malformed, exception and timeout all end up disabled`() = runBlocking {
    val cases = listOf<suspend () -> FeedCapabilitiesResponse>(
      { FeedCapabilitiesResponse(404, "not found") },
      { FeedCapabilitiesResponse(200, "<html>") },
      { FeedCapabilitiesResponse(200, null) },
      { throw IOException("offline") },
      { throw IllegalStateException("boom") },
      { delay(5_000); FeedCapabilitiesResponse(200, engineBody) }
    )
    for (fetch in cases) {
      val checker = FeedCapabilityChecker(fetch = fetch, timeoutMillis = 100)
      assertThat(checker.refreshIfStale()).isEqualTo(FeedCapabilities.DISABLED)
      assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isFalse()
    }
  }

  @Test
  fun `a failure after a success switches back to disabled and a later success recovers`() = runBlocking {
    var now = 5_000_000L
    var answer = FeedCapabilitiesResponse(200, engineBody)
    val checker = FeedCapabilityChecker(fetch = { answer }, clock = { now })

    checker.refreshIfStale()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isTrue()

    now += FeedCapabilityChecker.OK_TTL_MILLIS
    answer = FeedCapabilitiesResponse(404, null)
    checker.refreshIfStale()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isFalse()

    // a failure is only remembered for the (short) fail ttl
    now += FeedCapabilityChecker.FAIL_TTL_MILLIS
    answer = FeedCapabilitiesResponse(200, engineBody)
    checker.refreshIfStale()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isTrue()
  }

  @Test
  fun `a failure is not retried inside the fail ttl`() = runBlocking {
    var now = 1L
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(
      fetch = { calls.incrementAndGet(); throw IOException("offline") },
      clock = { now },
      failTtlMillis = 10_000
    )

    checker.refreshIfStale()
    now += 9_999
    checker.refreshIfStale()
    assertThat(calls.get()).isEqualTo(1)
    now += 1
    checker.refreshIfStale()
    assertThat(calls.get()).isEqualTo(2)
  }

  @Test
  fun `a clock that went backwards counts as stale`() = runBlocking {
    var now = 9_000_000L
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(fetch = { calls.incrementAndGet(); FeedCapabilitiesResponse(200, engineBody) }, clock = { now })

    checker.refreshIfStale()
    now -= 1_000
    checker.refreshIfStale()

    assertThat(calls.get()).isEqualTo(2)
  }

  @Test
  fun `concurrent callers share one request`() = runBlocking {
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(fetch = {
      calls.incrementAndGet()
      delay(50)
      FeedCapabilitiesResponse(200, engineBody)
    })

    val results = (1..8).map { async { checker.refreshIfStale() } }.awaitAll()

    assertThat(calls.get()).isEqualTo(1)
    assertThat(results.all { it.friendAssertions }).isTrue()
  }

  @Test
  fun `clear forgets the answer and asks again`() = runBlocking {
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(fetch = { calls.incrementAndGet(); FeedCapabilitiesResponse(200, engineBody) })

    checker.refreshIfStale()
    checker.clear()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isFalse()
    checker.refreshIfStale()

    assertThat(calls.get()).isEqualTo(2)
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isTrue()
  }

  @Test
  fun `cancelling the caller is not recorded as a failed check`() = runBlocking {
    val calls = AtomicInteger()
    val checker = FeedCapabilityChecker(
      fetch = {
        if (calls.incrementAndGet() == 1) delay(10_000)
        FeedCapabilitiesResponse(200, engineBody)
      },
      clock = { 1_000L },
      timeoutMillis = 60_000
    )

    val job = async { checker.refreshIfStale() }
    delay(50)
    job.cancel()
    val cancelled = try {
      job.await()
      false
    } catch (e: kotlinx.coroutines.CancellationException) {
      true
    }
    assertThat(cancelled).isTrue()
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isFalse()

    // same clock value: if the cancelled attempt had been remembered as a failure, this would not ask again
    checker.refreshIfStale()
    assertThat(calls.get()).isEqualTo(2)
    assertThat(checker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS)).isTrue()
  }
}
