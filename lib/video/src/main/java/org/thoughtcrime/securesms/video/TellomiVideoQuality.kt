/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.video

import org.thoughtcrime.securesms.video.TranscodingConfig.QualityTier
import kotlin.math.floor

/**
 * Tellomi（tellomi/tellomi#1518 V-7 ②，照 Telegram 两端「每个视频各选画质」）：视频的画质档。
 *
 * 档按短边分：270 / 480 / 720 / 1080，就是 Telegram 两端共有的长边 480 / 854 / 1280 / 1920 对 16:9 的短边
 * （TG 安卓 `PhotoViewer.calculateResultVideoSize`，TG iOS `TGMediaVideoConverter.maximumSizeForPreset`；
 * TG iOS 多一档长边 640，只有一端有，不做）。码率取两端的中间值：270p 0.6 Mbps（TG 安卓 ≤0.75、iOS 0.4 / 0.7），
 * 480p 1.0（TG 安卓 1.0，和上游「标准」一样），720p 3.0（TG iOS 3.0、安卓 ≤2.6），1080p 6.6（TG iOS 6.6、安卓 ≤6.8）；
 * 音频 128 kbps（上游一样）。
 *
 * 只给不超过原片短边的档（上游的转码器会把短边硬拉到档位，小片子会被放大），原片比最低档还小时只有最低一档。
 * 每档能发的最长时长按上传上限算（90% 留余量），超过就不给这一档，免得发的时候才失败。
 */
object TellomiVideoQuality {

  data class Level(val shortEdge: Int, val videoBitrateMbps: Float) {
    val label: String get() = "${shortEdge}p"
  }

  const val AUDIO_BITRATE_KBPS = 128

  val LEVELS: List<Level> = listOf(
    Level(shortEdge = 270, videoBitrateMbps = 0.6f),
    Level(shortEdge = 480, videoBitrateMbps = 1.0f),
    Level(shortEdge = 720, videoBitrateMbps = 3.0f),
    Level(shortEdge = 1080, videoBitrateMbps = 6.6f)
  )

  private const val SIZE_MARGIN = 0.9

  fun level(shortEdge: Int): Level? = LEVELS.firstOrNull { it.shortEdge == shortEdge }

  /**
   * The levels a video whose short edge is [sourceShortEdge] and whose sent part lasts [durationSeconds] can be sent
   * at, smallest first: never above the source, never longer than the upload limit allows.
   */
  fun availableLevels(sourceShortEdge: Int, durationSeconds: Double, maxUploadBytes: Long): List<Level> {
    if (sourceShortEdge <= 0) return emptyList()
    val fitting = LEVELS.filter { durationSeconds <= maxDurationSeconds(it, maxUploadBytes) }
    val notUpscaled = fitting.filter { it.shortEdge <= sourceShortEdge }
    return notUpscaled.ifEmpty { fitting.take(1) }
  }

  /** The longest a video can last at [level] and still fit in [maxUploadBytes]. */
  fun maxDurationSeconds(level: Level, maxUploadBytes: Long): Int {
    val bitsPerSecond = level.videoBitrateMbps * 1_000_000.0 + AUDIO_BITRATE_KBPS * 1_000.0
    return floor(maxUploadBytes * SIZE_MARGIN * 8 / bitsPerSecond).toInt()
  }

  /** The one transcoding tier for [level], in the form the upstream transcoder takes. */
  fun tier(level: Level, maxUploadBytes: Long): QualityTier {
    return QualityTier(
      resolution = level.shortEdge,
      videoBitrateMbps = level.videoBitrateMbps,
      audioBitrateKbps = AUDIO_BITRATE_KBPS,
      maxDurationSec = maxDurationSeconds(level, maxUploadBytes)
    )
  }

  /** [tier] for a stored short edge, or null if it is not one of the levels. */
  fun tiersFor(shortEdge: Int, maxUploadBytes: Long): List<QualityTier>? {
    return level(shortEdge)?.let { listOf(tier(it, maxUploadBytes)) }
  }
}
