/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.R

/** Telegram 对照 3.3（F12）：锁定录音时暂停 / 继续按钮的逻辑。 */
class TellomiVoicePauseUITest {

  private class FakeControl : TellomiVoicePauseUI.Control {
    override var isPaused: Boolean = false
    var canResume: Boolean = true
    var pauseCalls: Int = 0
    var resumeCalls: Int = 0
    private var elapsed: Long = 0

    override fun elapsedMs(): Long = elapsed

    override fun pauseRecording(): Boolean {
      pauseCalls += 1
      if (isPaused) return false
      isPaused = true
      return true
    }

    override fun resumeRecording(): Boolean {
      resumeCalls += 1
      if (!isPaused || !canResume) return false
      isPaused = false
      return true
    }
  }

  @Test
  fun `icon and action follow state`() {
    assertThat(TellomiVoicePauseUI.iconRes(isPaused = false)).isEqualTo(R.drawable.tellomi_symbol_pause_24)
    assertThat(TellomiVoicePauseUI.iconRes(isPaused = true)).isEqualTo(R.drawable.tellomi_symbol_play_24)
    assertThat(TellomiVoicePauseUI.action(isPaused = false)).isEqualTo(TellomiVoicePauseUI.Action.PAUSE)
    assertThat(TellomiVoicePauseUI.action(isPaused = true)).isEqualTo(TellomiVoicePauseUI.Action.RESUME)
    assertThat(TellomiVoicePauseUI.contentDescriptionRes(false))
      .isNotEqualTo(TellomiVoicePauseUI.contentDescriptionRes(true))
  }

  @Test
  fun `toggle alternates pause and resume`() {
    val control = FakeControl()
    assertThat(TellomiVoicePauseUI.toggle(control)).isTrue()
    assertThat(control.pauseCalls).isEqualTo(1)
    assertThat(TellomiVoicePauseUI.toggle(control)).isFalse()
    assertThat(control.resumeCalls).isEqualTo(1)
    assertThat(control.pauseCalls).isEqualTo(1)
  }

  @Test
  fun `failed resume stays paused`() {
    val control = FakeControl()
    control.isPaused = true
    control.canResume = false
    assertThat(TellomiVoicePauseUI.toggle(control)).isTrue()
  }
}
