/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.dismissWithAnimation
import org.signal.core.util.bytes
import org.signal.mediasend.EditorState
import org.signal.mediasend.MediaConstraints
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags
import org.thoughtcrime.securesms.video.TellomiVideoQuality
import org.thoughtcrime.securesms.video.TranscodingConfig
import org.thoughtcrime.securesms.video.TranscodingQuality
import kotlin.time.Duration

/**
 * Tellomi（tellomi/tellomi#1518 V-7 ②）：每个视频各选画质里的一档。[storedShortEdge] 是选它时记下的值：和整批画质一样的那档记 0
 * （照旧走上游的整批档位和码率），其余记短边。
 */
internal data class VideoLevelOption(
  val shortEdge: Int,
  val label: String,
  val estimatedBytes: Long,
  val isSelected: Boolean,
  val storedShortEdge: Int
)

/**
 * 这个视频能选的档，从高到低（照 Telegram 两端：查看器里每个视频各选，档数看原片大小，只有一档时不给选）。
 *
 * - 档只给不超过原片短边、并且这么长装得下上传上限的（[TellomiVideoQuality.availableLevels]）；不到两档返回空，
 *   这时画质键照旧是上游的「标准 / 高」整批选择。
 * - 选中的是这个视频自己选过的档；没选过就是整批画质对应的那档。
 * - 每档写上大概多大：整批那档按上游的档位算，其余按这一档算（时长 × 码率，和上游的大小提示同一个算法）。
 */
internal fun videoLevelOptions(
  sourceShortEdge: Int,
  duration: Duration,
  maxUploadBytes: Long,
  batchTiers: List<TranscodingConfig.QualityTier>,
  chosenShortEdge: Int
): List<VideoLevelOption> {
  val durationSeconds = duration.inWholeMilliseconds / 1000.0
  val available = TellomiVideoQuality.availableLevels(sourceShortEdge, durationSeconds, maxUploadBytes)
  // A level that was picked stays reachable even once fewer than two fit, so it can always be taken back.
  if (available.size < 2 && chosenShortEdge == 0) return emptyList()

  val batchShortEdge = if (batchTiers.isNotEmpty()) TranscodingConfig.getQualityTier(batchTiers, duration).resolution else 0
  val effective = if (chosenShortEdge > 0) chosenShortEdge else batchShortEdge
  val millis = duration.inWholeMilliseconds

  val levels = available.map { level ->
    val isBatch = level.shortEdge == batchShortEdge
    val tiers = if (isBatch) batchTiers else listOf(TellomiVideoQuality.tier(level, maxUploadBytes))
    VideoLevelOption(
      shortEdge = level.shortEdge,
      label = level.label,
      estimatedBytes = TranscodingQuality.createFromQualityTiers(tiers, millis).byteCountEstimate.toLong(),
      isSelected = level.shortEdge == effective,
      storedShortEdge = if (isBatch) 0 else level.shortEdge
    )
  }

  // The batch quality's own size when it is not one of the levels (or no longer fits as one): always there to go back to.
  val batch = if (batchShortEdge > 0 && levels.none { it.storedShortEdge == 0 }) {
    VideoLevelOption(
      shortEdge = batchShortEdge,
      label = "${batchShortEdge}p",
      estimatedBytes = TranscodingQuality.createFromQualityTiers(batchTiers, millis).byteCountEstimate.toLong(),
      isSelected = chosenShortEdge == 0,
      storedShortEdge = 0
    )
  } else {
    null
  }

  return (levels + listOfNotNull(batch)).sortedByDescending { it.shortEdge }
}

/** [videoLevelOptions] for the focused video, or empty when it is not a video that can be transcoded at a level. */
internal fun MediaEditState.focusedVideoLevelOptions(): List<VideoLevelOption> {
  // Without a transcoder the video goes as it is, so there is no level to pick.
  if (!MediaConstraints.isVideoTranscodeAvailable()) return emptyList()
  val media = focusedMedia ?: return emptyList()
  val trim = (focusedEditorState as? EditorState.VideoTrim)?.videoTrimData ?: return emptyList()
  // A muted video goes as a GIF at a fixed size (V-6).
  if (trim.isMuted) return emptyList()
  val shortEdge = minOf(media.width, media.height)
  val duration = if (trim.endTimeUs > trim.startTimeUs) trim.getDuration() else return emptyList()
  return videoLevelOptions(
    sourceShortEdge = shortEdge,
    duration = duration,
    maxUploadBytes = videoMaxUploadBytes,
    batchTiers = videoTranscodingTiers,
    chosenShortEdge = trim.tellomiShortEdge
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoQualityBottomSheet(
  options: List<VideoLevelOption>,
  onLevelSelected: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState()
  val scope = rememberCoroutineScope()

  BottomSheets.BottomSheet(
    onDismissRequest = { sheetState.dismissWithAnimation(scope, onComplete = onDismiss) },
    sheetState = sheetState
  ) {
    VideoQualitySheetContent(
      options = options,
      onLevelSelected = { storedShortEdge ->
        sheetState.dismissWithAnimation(scope) {
          onDismiss()
          onLevelSelected(storedShortEdge)
        }
      }
    )
  }
}

/** The levels side by side, in the look of upstream's Standard / High sheet. */
@Composable
internal fun VideoQualitySheetContent(
  options: List<VideoLevelOption>,
  onLevelSelected: (Int) -> Unit
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_VIDEO_QUALITY_SHEET)) {
    Text(
      text = stringResource(id = R.string.VideoQualitySheet__video_quality),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(top = 20.dp, bottom = 14.dp)
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
    ) {
      options.forEach { option ->
        Button(
          modifier = Modifier
            .defaultMinSize(minHeight = 60.dp)
            .weight(1f)
            .testTag(TestTags.videoQualityLevel(option.shortEdge)),
          onClick = { onLevelSelected(option.storedShortEdge) },
          shape = RoundedCornerShape(percent = 25),
          colors = if (option.isSelected) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.textButtonColors(),
          elevation = if (option.isSelected) ButtonDefaults.filledTonalButtonElevation() else null,
          // Up to four side by side: the same narrow padding for all, so the size fits on one line.
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = option.label, color = MaterialTheme.colorScheme.onSurface)
            Text(
              text = "≈ ${option.estimatedBytes.bytes.toUnitString()}",
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun VideoQualitySheetContentPreview() {
  Previews.Preview {
    VideoQualitySheetContent(
      options = listOf(
        VideoLevelOption(1080, "1080p", 52_000_000, isSelected = false, storedShortEdge = 1080),
        VideoLevelOption(720, "720p", 24_000_000, isSelected = false, storedShortEdge = 720),
        VideoLevelOption(480, "480p", 8_000_000, isSelected = true, storedShortEdge = 0),
        VideoLevelOption(270, "270p", 5_000_000, isSelected = false, storedShortEdge = 270)
      ),
      onLevelSelected = {}
    )
  }
}
