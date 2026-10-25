/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

/**
 * Tellomi（Telegram 对照 3.3 / F12）：录音暂停 / 继续的状态和时长。
 * 只算「真正在录」的时间：暂停期间不计。纯值类型，时间由调用方传入，方便单测。
 * 对齐 iOS TellomiVoicePauseClock（行为参照，不搬代码）。
 */
class TellomiVoicePauseClock {
  sealed class State {
    data object Idle : State()
    data class Recording(val sinceMs: Long) : State()
    data object Paused : State()
    data object Stopped : State()
  }

  var state: State = State.Idle
    private set

  /** 之前各段录音已累计的时长（不含当前正在录的这一段），毫秒。 */
  var accumulatedMs: Long = 0L
    private set

  val isRecording: Boolean get() = state is State.Recording
  val isPaused: Boolean get() = state == State.Paused

  /** 开始录（只能从 idle 开始）。 */
  fun start(nowMs: Long): Boolean {
    if (state != State.Idle) return false
    state = State.Recording(nowMs)
    return true
  }

  /** 暂停（只在录音中生效）。 */
  fun pause(nowMs: Long): Boolean {
    val recording = state as? State.Recording ?: return false
    accumulatedMs += maxOf(0L, nowMs - recording.sinceMs)
    state = State.Paused
    return true
  }

  /** 继续（只在暂停中生效）。 */
  fun resume(nowMs: Long): Boolean {
    if (state != State.Paused) return false
    state = State.Recording(nowMs)
    return true
  }

  /** 结束，返回总时长；idle 或已结束返回 null。 */
  fun stop(nowMs: Long): Long? {
    return when (state) {
      State.Idle, State.Stopped -> null
      else -> {
        val total = elapsed(nowMs)
        accumulatedMs = total
        state = State.Stopped
        total
      }
    }
  }

  /** 到 nowMs 为止的有效录音时长。 */
  fun elapsed(nowMs: Long): Long {
    val recording = state as? State.Recording
    return if (recording != null) {
      accumulatedMs + maxOf(0L, nowMs - recording.sinceMs)
    } else {
      accumulatedMs
    }
  }

  /** 丢弃当前状态，回到 idle（取消/结束录音后复用同一实例）。 */
  fun forceReset() {
    state = State.Idle
    accumulatedMs = 0L
  }

  /** 重置并立刻开始一段新录音。 */
  fun forceResetAndStart(nowMs: Long) {
    forceReset()
    start(nowMs)
  }
}
