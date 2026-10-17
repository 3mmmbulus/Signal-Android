/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException

/** 事件上报遇到 429 / 带 Retry-After 的 503：停发剩下的批次，没发出去的留在队列里，等够了再发。 */
class FeedEventBufferThrottleTest {

  private var now = 1_000_000L
  private val at = Instant.parse("2026-10-16T00:00:00Z")

  private fun buffer(max: Int = FeedEventBuffer.MAX_QUEUED) = FeedEventBuffer(clock = { now }, maxQueued = max)
  private fun event(i: Int): FeedEventPayload = impressionEvent("c$i", FeedTab.RECOMMEND, at)
  private fun FeedEventBuffer.fill(count: Int, from: Int = 0) = repeat(count) { add(event(from + it)) }
  private fun List<FeedEventPayload>.ids() = map { it.contentId }

  private class Sender(val failures: Map<Int, Exception> = emptyMap()) {
    val sent = mutableListOf<List<String>>()
    var calls = 0
    suspend fun send(batch: List<FeedEventPayload>) {
      val index = calls++
      failures[index]?.let { throw it }
      sent += batch.map { it.contentId }
    }
  }

  @Test
  fun `a 429 stops the remaining batches and keeps every unsent event queued in order`() = runBlocking {
    val buffer = buffer()
    buffer.fill(120) // 50 + 50 + 20
    val sender = Sender(mapOf(1 to FeedApiException(429, "rate_limited", 30)))

    buffer.flush(sender::send)

    assertThat(sender.calls).isEqualTo(2) // the third batch was never tried
    assertThat(sender.sent.map { it.size }).containsExactly(50)
    assertThat(buffer.size).isEqualTo(70)
    assertThat(buffer.isThrottled()).isTrue()

    // 冷却中 flush 什么都不做，事件还在
    now += 29_999
    val idle = Sender()
    buffer.flush(idle::send)
    assertThat(idle.calls).isEqualTo(0)
    assertThat(buffer.size).isEqualTo(70)

    // 够了：剩下的 70 条按原来的顺序发出去
    now += 1
    buffer.flush(idle::send)
    assertThat(buffer.isThrottled()).isFalse()
    assertThat(idle.sent.map { it.size }).containsExactly(50, 20)
    assertThat(idle.sent.flatten()).isEqualTo((50 until 120).map { "c$it" })
    assertThat(buffer.size).isEqualTo(0)
  }

  @Test
  fun `a 503 with retry-after is the same, a 503 without drops the batch like any failure`() = runBlocking {
    val buffer = buffer()
    buffer.fill(60)
    val sender = Sender(mapOf(0 to FeedApiException(503, null, 10)))
    buffer.flush(sender::send)
    assertThat(buffer.size).isEqualTo(60)
    assertThat(buffer.isThrottled()).isTrue()
    now += 10_000

    val dropped = mutableListOf<Int>()
    val plain = Sender(mapOf(0 to FeedApiException(503, null)))
    buffer.flush(plain::send, onDropped = { batch, _ -> dropped += batch.size })
    assertThat(dropped).containsExactly(50) // the first batch is lost (old rule), the second one still goes
    assertThat(plain.sent.flatten()).isEqualTo((50 until 60).map { "c$it" })
    assertThat(buffer.isThrottled()).isFalse()
    assertThat(buffer.size).isEqualTo(0)
  }

  @Test
  fun `other failures drop that batch and go on with the next one, as before`() = runBlocking {
    val buffer = buffer()
    buffer.fill(110)
    val dropped = mutableListOf<Pair<Int, String>>()
    val sender = Sender(mapOf(0 to IOException("offline"), 1 to FeedApiException(400, "invalid_event")))
    buffer.flush(sender::send, onDropped = { batch, e -> dropped += batch.size to (e.message ?: "") })

    assertThat(sender.calls).isEqualTo(3)
    assertThat(sender.sent.flatten()).isEqualTo((100 until 110).map { "c$it" })
    assertThat(dropped.map { it.first }).containsExactly(50, 50)
    assertThat(buffer.size).isEqualTo(0)
    assertThat(buffer.isThrottled()).isFalse()
  }

  @Test
  fun `429 without retry-after waits the default, a local cooldown answer carries the remaining seconds`() = runBlocking {
    val buffer = buffer()
    buffer.fill(5)
    buffer.flush(Sender(mapOf(0 to FeedApiException(429, null)))::send)
    now += 29_999
    assertThat(buffer.isThrottled()).isTrue()
    now += 1
    assertThat(buffer.isThrottled()).isFalse()

    buffer.flush(Sender(mapOf(0 to FeedApiException(429, FeedApiException.CODE_THROTTLED_LOCALLY, 17)))::send)
    now += 16_999
    assertThat(buffer.isThrottled()).isTrue()
    now += 1
    assertThat(buffer.isThrottled()).isFalse()
    assertThat(buffer.size).isEqualTo(5)
  }

  @Test
  fun `retry-after is clamped to an hour and a longer wait is never shortened by a later throttle`() = runBlocking {
    val buffer = buffer()
    buffer.fill(3)
    buffer.flush(Sender(mapOf(0 to FeedApiException(429, null, 99_999)))::send)
    now += 3_599_999
    assertThat(buffer.isThrottled()).isTrue()
    now += 1
    assertThat(buffer.isThrottled()).isFalse()

    buffer.flush(Sender(mapOf(0 to FeedApiException(429, null, 600)))::send)
    // 已经在冷却（只是发起方绕过了 flush 的检查）又来一次更短的：保持更晚的那个
    now += 60_000
    val twice = buffer()
    twice.fill(1)
    twice.flush(Sender(mapOf(0 to FeedApiException(429, null, 600)))::send)
    now += 1_000
    twice.flush(Sender(mapOf(0 to FeedApiException(429, null, 5)))::send) // 冷却中，flush 不会调 send
    now += 300_000
    assertThat(twice.isThrottled()).isTrue()
    now += 300_000
    assertThat(twice.isThrottled()).isFalse()
  }

  @Test
  fun `while throttled add does not ask for a flush even when a batch is full`() {
    val buffer = buffer()
    buffer.fill(49)
    assertThat(buffer.add(event(49))).isTrue() // 满 50：该发了
    runBlocking { buffer.flush(Sender(mapOf(0 to FeedApiException(429, null, 30)))::send) }
    assertThat(buffer.size).isEqualTo(50)
    assertThat(buffer.add(event(50))).isFalse() // 冷却中：不再要求马上发
    now += 30_000
    assertThat(buffer.add(event(51))).isTrue()
  }

  @Test
  fun `the queue is capped, the oldest events go first`() = runBlocking {
    val buffer = buffer(max = 100)
    buffer.fill(150)
    assertThat(buffer.size).isEqualTo(100)
    val sender = Sender(mapOf(0 to FeedApiException(429, null, 30)))
    buffer.flush(sender::send)
    buffer.fill(20, from = 150) // 冷却时又攒了 20 条
    assertThat(buffer.size).isEqualTo(100) // 50 + 50 + 20 → 超出 100 的最旧的丢掉
    now += 30_000
    val ok = Sender()
    buffer.flush(ok::send)
    assertThat(ok.sent.flatten()).isEqualTo((70 until 170).map { "c$it" })
  }

  @Test
  fun `cancelling the sender puts the unsent batches back and rethrows`() = runBlocking {
    val buffer = buffer()
    buffer.fill(80)
    val sender = Sender(mapOf(1 to CancellationException("scope gone")))
    assertFailure { buffer.flush(sender::send) }.isInstanceOf(CancellationException::class)
    assertThat(buffer.size).isEqualTo(30)
    assertThat(buffer.isThrottled()).isFalse()
    val rest = Sender()
    buffer.flush(rest::send)
    assertThat(rest.sent.flatten()).isEqualTo((50 until 80).map { "c$it" })
  }

  @Test
  fun `nothing queued and nothing happens`() = runBlocking {
    val sender = Sender()
    buffer().flush(sender::send)
    assertThat(sender.calls).isEqualTo(0)
    assertThat(sender.sent).isEmpty()
  }

  @Test
  fun `a clock that went backwards does not keep the buffer throttled`() = runBlocking {
    val buffer = buffer()
    buffer.fill(3)
    buffer.flush(Sender(mapOf(0 to FeedApiException(429, null, 600)))::send)
    now -= 3_600_000
    assertThat(buffer.isThrottled()).isFalse()
  }

  @Test
  fun `events added while a batch is in flight stay behind the re-queued older ones`() = runBlocking {
    val buffer = buffer()
    buffer.fill(60)
    buffer.flush({ batch ->
      buffer.add(event(900)) // the app keeps producing events while the request is on its way
      throw FeedApiException(429, null, 30)
    })
    assertThat(buffer.size).isEqualTo(61)
    now += 30_000
    val ok = Sender()
    buffer.flush(ok::send)
    assertThat(ok.sent.flatten()).isEqualTo((0 until 60).map { "c$it" } + "c900")
  }

  @Test
  fun `two overlapping flushes that are both throttled keep the later end time`() = runBlocking {
    val buffer = buffer()
    buffer.fill(60)
    buffer.flush({ _ ->
      // 第二个 flush 在第一个的请求还在路上时开始、先被限流（600 秒）
      buffer.add(event(901))
      buffer.flush({ throw FeedApiException(429, null, 600) })
      throw FeedApiException(429, null, 5) // 第一个后被限流，但只让等 5 秒：不能把 600 秒缩成 5 秒
    })
    now += 5_000
    assertThat(buffer.isThrottled()).isTrue()
    now += 594_999
    assertThat(buffer.isThrottled()).isTrue()
    now += 1
    assertThat(buffer.isThrottled()).isFalse()
  }
}
