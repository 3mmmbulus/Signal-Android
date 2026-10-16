/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedCooldownTest {

  private var now = 1_000_000L
  private var random = 0.0
  private val cooldown = FeedCooldown(clock = { now }, random = { random })

  @Test
  fun `not cooling down at the start`() {
    assertThat(cooldown.isCoolingDown()).isFalse()
    assertThat(cooldown.remainingMillis()).isEqualTo(0L)
    assertThat(cooldown.remainingSeconds()).isEqualTo(0L)
  }

  @Test
  fun `a retry-after header is waited exactly, without jitter`() {
    random = 0.99
    assertThat(cooldown.onThrottled(10)).isEqualTo(10_000L)
    assertThat(cooldown.isCoolingDown()).isTrue()
    assertThat(cooldown.remainingSeconds()).isEqualTo(10L)
    now += 9_001
    assertThat(cooldown.isCoolingDown()).isTrue()
    assertThat(cooldown.remainingSeconds()).isEqualTo(1L) // rounded up, never 0 while cooling
    now += 999
    assertThat(cooldown.isCoolingDown()).isFalse()
  }

  @Test
  fun `the header is clamped to 1 and 3600 seconds`() {
    assertThat(cooldown.onThrottled(0)).isEqualTo(1_000L)
    cooldown.reset()
    assertThat(cooldown.onThrottled(99_999)).isEqualTo(3_600_000L)
  }

  @Test
  fun `without a header the wait is exponential with equal jitter`() {
    random = 0.0
    assertThat(cooldown.onThrottled(null)).isEqualTo(15_000L) // base 30 s, lower half
    assertThat(cooldown.onThrottled(null)).isEqualTo(30_000L) // 60 s raw
    assertThat(cooldown.onThrottled(null)).isEqualTo(60_000L)
    random = 1.0
    assertThat(cooldown.onThrottled(null)).isEqualTo(240_000L) // streak 4: 240 s raw, top of the range
  }

  @Test
  fun `the exponential wait is capped at one hour`() {
    random = 1.0
    var last = 0L
    repeat(40) { last = cooldown.onThrottled(null) }
    assertThat(last).isEqualTo(3_600_000L)
    random = 0.0
    assertThat(cooldown.onThrottled(null)).isEqualTo(1_800_000L)
  }

  @Test
  fun `a success resets the streak`() {
    random = 0.0
    cooldown.onThrottled(null)
    cooldown.onThrottled(null)
    now += 10_000_000
    cooldown.onSuccess()
    assertThat(cooldown.onThrottled(null)).isEqualTo(15_000L)
  }

  @Test
  fun `a later shorter wait never shortens a running cooldown, a longer one extends it`() {
    cooldown.onThrottled(100)
    now += 10_000
    cooldown.onThrottled(5)
    assertThat(cooldown.remainingSeconds()).isEqualTo(90L)
    cooldown.onThrottled(200)
    assertThat(cooldown.remainingSeconds()).isEqualTo(200L)
  }

  @Test
  fun `a clock that went backwards does not lock the cooldown in`() {
    cooldown.onThrottled(60)
    now -= 3_600_000
    assertThat(cooldown.isCoolingDown()).isFalse()
    assertThat(cooldown.remainingMillis()).isEqualTo(0L)
  }

  @Test
  fun `reset forgets everything`() {
    cooldown.onThrottled(60)
    cooldown.reset()
    assertThat(cooldown.isCoolingDown()).isFalse()
    random = 0.0
    assertThat(cooldown.onThrottled(null)).isEqualTo(15_000L)
  }
}
