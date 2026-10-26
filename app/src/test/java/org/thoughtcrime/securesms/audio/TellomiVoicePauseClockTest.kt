/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

/** Telegram 对照 3.3（F12）：录音暂停 / 继续的时长只算真正在录的时间。 */
class TellomiVoicePauseClockTest {

  @Test
  fun `paused time is not counted`() {
    val c = TellomiVoicePauseClock()
    assertThat(c.start(100)).isTrue()
    assertThat(c.pause(103)).isTrue()
    assertThat(c.isPaused).isTrue()
    assertThat(c.elapsed(200)).isEqualTo(3)
    assertThat(c.resume(200)).isTrue()
    assertThat(c.isRecording).isTrue()
    assertThat(c.elapsed(202)).isEqualTo(5)
    assertThat(c.stop(204)).isEqualTo(7)
    assertThat(c.state).isEqualTo(TellomiVoicePauseClock.State.Stopped)
  }

  @Test
  fun `stop while paused keeps recorded time`() {
    val c = TellomiVoicePauseClock()
    c.start(0)
    c.pause(4)
    assertThat(c.stop(60)).isEqualTo(4)
  }

  @Test
  fun `invalid transitions are ignored`() {
    val c = TellomiVoicePauseClock()
    assertThat(c.pause(1)).isFalse()
    assertThat(c.resume(1)).isFalse()
    assertThat(c.stop(1)).isNull()
    c.start(0)
    assertThat(c.start(1)).isFalse()
    assertThat(c.resume(1)).isFalse()
    c.pause(2)
    assertThat(c.pause(3)).isFalse()
    c.stop(3)
    assertThat(c.resume(4)).isFalse()
    assertThat(c.stop(5)).isNull()
  }

  @Test
  fun `clock going backwards never negative`() {
    val c = TellomiVoicePauseClock()
    c.start(10)
    assertThat(c.elapsed(5)).isEqualTo(0)
    c.pause(5)
    assertThat(c.elapsed(5)).isEqualTo(0)
  }

  @Test
  fun `forceResetAndStart begins a fresh take`() {
    val c = TellomiVoicePauseClock()
    c.start(0)
    c.pause(10)
    c.forceResetAndStart(100)
    assertThat(c.isRecording).isTrue()
    assertThat(c.elapsed(105)).isEqualTo(5)
  }
}
