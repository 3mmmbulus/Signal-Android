/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1518 V-7 ②）：视频画质档——不超过原片、装得下上传上限。
 */
class TellomiVideoQualityTest {

  private val limit = 100L * 1024 * 1024

  private fun edges(levels: List<TellomiVideoQuality.Level>) = levels.map { it.shortEdge }

  @Test
  fun `levels go up to the source and no further`() {
    assertEquals(listOf(270, 480, 720, 1080), edges(TellomiVideoQuality.availableLevels(1080, 30.0, limit)))
    assertEquals(listOf(270, 480, 720, 1080), edges(TellomiVideoQuality.availableLevels(2160, 30.0, limit)))
    assertEquals(listOf(270, 480, 720), edges(TellomiVideoQuality.availableLevels(720, 30.0, limit)))
    assertEquals(listOf(270, 480), edges(TellomiVideoQuality.availableLevels(540, 30.0, limit)))
    assertEquals(listOf(270), edges(TellomiVideoQuality.availableLevels(360, 30.0, limit)))
    assertEquals(emptyList<Int>(), edges(TellomiVideoQuality.availableLevels(0, 30.0, limit)))
  }

  @Test
  fun `a level is only offered when the video fits the upload limit at it`() {
    val max1080 = TellomiVideoQuality.maxDurationSeconds(TellomiVideoQuality.level(1080)!!, limit)
    val max720 = TellomiVideoQuality.maxDurationSeconds(TellomiVideoQuality.level(720)!!, limit)
    // 100 MiB × 0.9 × 8 / (6.6 Mbps + 128 kbps) ≈ 112 s; at 3.0 Mbps ≈ 241 s.
    assertEquals(112, max1080)
    assertEquals(241, max720)

    assertEquals(listOf(270, 480, 720, 1080), edges(TellomiVideoQuality.availableLevels(1080, max1080.toDouble(), limit)))
    assertEquals(listOf(270, 480, 720), edges(TellomiVideoQuality.availableLevels(1080, max1080 + 1.0, limit)))
    assertEquals(listOf(270, 480), edges(TellomiVideoQuality.availableLevels(1080, max720 + 1.0, limit)))
  }

  @Test
  fun `the tier is the level's short edge and bitrate with its own maximum duration`() {
    val tier = TellomiVideoQuality.tier(TellomiVideoQuality.level(480)!!, limit)
    assertEquals(480, tier.resolution)
    assertEquals(1.0f, tier.videoBitrateMbps, 0f)
    assertEquals(TellomiVideoQuality.AUDIO_BITRATE_KBPS, tier.audioBitrateKbps)
    assertTrue(tier.maxDurationSec > 600)

    assertEquals(listOf(tier), TellomiVideoQuality.tiersFor(480, limit))
    assertNull(TellomiVideoQuality.tiersFor(500, limit))
  }

  @Test
  fun `the sizes match Telegram's long edges for 16 by 9`() {
    // TG: long edges 480 / 854 / 1280 / 1920.
    assertEquals(listOf(270, 480, 720, 1080), TellomiVideoQuality.LEVELS.map { it.shortEdge })
    assertEquals(listOf("270p", "480p", "720p", "1080p"), TellomiVideoQuality.LEVELS.map { it.label })
  }
}
