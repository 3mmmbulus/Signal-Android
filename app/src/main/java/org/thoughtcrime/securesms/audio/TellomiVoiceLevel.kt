/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

/**
 * Tellomi（交互审计 A-12「录音看不到音量」）：麦克风电平 → 录音按钮后面那圈光晕有多大。
 *
 * 振幅（16 位 PCM 的峰值，0–[MAX_AMPLITUDE]）换成分贝，[FLOOR_DB] 以下算没声音（0），满幅算 1；
 * 光晕从按钮原大（1 倍）长到 [MAX_HALO_SCALE] 倍。Telegram 两端也是录音时按钮外一圈随音量变大
 * （安卓 `ChatActivityEnterView.RecordCircle.setAmplitude`，iOS `ManagedAudioRecorder.micLevel`）。
 */
object TellomiVoiceLevel {

  const val MAX_AMPLITUDE = 32767
  const val FLOOR_DB = -45f
  const val MAX_HALO_SCALE = 1.6f

  @JvmStatic
  fun level(amplitude: Int): Float {
    if (amplitude <= 0) return 0f
    val db = 20f * log10(min(amplitude, MAX_AMPLITUDE).toFloat() / MAX_AMPLITUDE)
    return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
  }

  @JvmStatic
  fun haloScale(level: Float): Float = 1f + (MAX_HALO_SCALE - 1f) * level.coerceIn(0f, 1f)

  /** 一段 16 位小端 PCM 里绝对值最大的样本。 */
  @JvmStatic
  fun peak(pcm16le: ByteArray, length: Int): Int {
    var peak = 0
    var i = 0
    val end = min(length, pcm16le.size)
    while (i + 1 < end) {
      val sample = ((pcm16le[i].toInt() and 0xFF) or (pcm16le[i + 1].toInt() shl 8)).toShort().toInt()
      val magnitude = abs(sample)
      if (magnitude > peak) {
        peak = magnitude
      }
      i += 2
    }
    return min(peak, MAX_AMPLITUDE)
  }
}
