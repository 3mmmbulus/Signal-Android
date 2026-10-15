/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (interaction audit A-12): the record button's halo follows the microphone level. */
class TellomiVoiceLevelTest {

  @Test
  fun `silence and very quiet input leave the halo at the button's size`() {
    assertEquals(0f, TellomiVoiceLevel.level(0), 0f)
    // -50 dB is below the -45 dB floor.
    assertEquals(0f, TellomiVoiceLevel.level(104), 0f)
    assertEquals(1f, TellomiVoiceLevel.haloScale(0f), 0f)
  }

  @Test
  fun `full scale input grows the halo to its largest`() {
    assertEquals(1f, TellomiVoiceLevel.level(32767), 0.0001f)
    assertEquals(1f, TellomiVoiceLevel.level(40000), 0.0001f)
    assertEquals(TellomiVoiceLevel.MAX_HALO_SCALE, TellomiVoiceLevel.haloScale(1f), 0.0001f)
  }

  @Test
  fun `level grows with loudness in decibels`() {
    val quiet = TellomiVoiceLevel.level(1000)
    val talking = TellomiVoiceLevel.level(8000)
    assertTrue(quiet in 0f..talking)
    assertTrue(talking < 1f)
    // Half scale is about -6 dB: 39 / 45 of the way up.
    assertEquals(39f / 45f, TellomiVoiceLevel.level(16384), 0.01f)
  }

  @Test
  fun `the peak of a pcm buffer is the loudest sample either way`() {
    val samples = shortArrayOf(10, -300, 2000, -12000, 500)
    assertEquals(12000, TellomiVoiceLevel.peak(pcm(samples), samples.size * 2))
    assertEquals(300, TellomiVoiceLevel.peak(pcm(samples), 4))
    assertEquals(32767, TellomiVoiceLevel.peak(pcm(shortArrayOf(Short.MIN_VALUE)), 2))
    assertEquals(0, TellomiVoiceLevel.peak(ByteArray(0), 0))
  }

  private fun pcm(samples: ShortArray): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, s ->
      bytes[i * 2] = (s.toInt() and 0xFF).toByte()
      bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }
    return bytes
  }
}
