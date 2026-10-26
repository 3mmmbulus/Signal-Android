/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/**
 * Tellomi（Telegram 对照 3.3 / F12）：锁定录音时暂停 / 继续按钮的纯逻辑。
 * 对齐 iOS TellomiVoicePauseUI（行为参照，不搬代码）。
 */
object TellomiVoicePauseUI {

  enum class Action {
    PAUSE,
    RESUME
  }

  /** 正在录的这条，给暂停按钮和计时标签用。 */
  interface Control {
    val isPaused: Boolean

    /** 有效录音时长（暂停不计），毫秒。 */
    fun elapsedMs(): Long
    fun pauseRecording(): Boolean
    fun resumeRecording(): Boolean
  }

  @DrawableRes
  fun iconRes(isPaused: Boolean): Int {
    return if (isPaused) R.drawable.tellomi_symbol_play_24 else R.drawable.tellomi_symbol_pause_24
  }

  @StringRes
  fun contentDescriptionRes(isPaused: Boolean): Int {
    return if (isPaused) {
      R.string.TellomiVoicePause__resume_recording
    } else {
      R.string.TellomiVoicePause__pause_recording
    }
  }

  fun action(isPaused: Boolean): Action = if (isPaused) Action.RESUME else Action.PAUSE

  /** 点一下：录音中 → 暂停，暂停中 → 继续。返回点完以后是否处于暂停。 */
  fun toggle(control: Control): Boolean {
    when (action(control.isPaused)) {
      Action.PAUSE -> control.pauseRecording()
      Action.RESUME -> control.resumeRecording()
    }
    return control.isPaused
  }
}
