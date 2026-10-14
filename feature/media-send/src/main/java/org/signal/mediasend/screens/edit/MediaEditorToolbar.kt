/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.WindowBreakpoint
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.copied.androidx.compose.material3.IconButtonColors
import org.signal.core.ui.rememberWindowBreakpoint
import org.signal.mediasend.EditorState
import org.signal.mediasend.R
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.test.TestTags

@Composable
internal fun MediaEditorToolbar(
  modifier: Modifier = Modifier,
  leading: @Composable () -> Unit = {},
  trailing: @Composable () -> Unit = {},
  content: @Composable () -> Unit
) {
  val windowBreakpoint = rememberWindowBreakpoint()
  val isRow = windowBreakpoint is WindowBreakpoint.Small

  if (isRow) {
    Row(modifier = modifier.height(48.dp)) {
      leading()

      Spacer(modifier = Modifier.weight(1f))

      Row(
        modifier = Modifier
          .fillMaxHeight()
          .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(percent = 50))
      ) {
        content()
      }

      Spacer(modifier = Modifier.weight(1f))

      trailing()
    }
  } else {
    Column(modifier = modifier.width(48.dp)) {
      trailing()

      Spacer(modifier = Modifier.size(16.dp))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(percent = 50))
      ) {
        content()
      }

      Spacer(modifier = Modifier.size(16.dp))

      leading()
    }
  }
}

@Composable
internal fun MediaEditorToolbarButton(
  imageVector: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentDescription: String? = null,
  colors: IconButtonColors = IconButtons.iconButtonColors(),
  onLongClick: (() -> Unit)? = null,
  onLongClickLabel: String? = null
) {
  IconButtons.IconButton(
    onClick = onClick,
    enabled = enabled,
    colors = colors,
    modifier = modifier,
    onLongClick = onLongClick,
    onLongClickLabel = onLongClickLabel
  ) {
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
  }
}

@Composable
internal fun MediaEditorToolbarSharedButtons(
  state: MediaEditState,
  editorState: EditorState,
  onEvent: (MediaEditScreenEvents) -> Unit,
  enabled: Boolean = true
) {
  if (isQualityVisible(state, editorState)) {
    var isSelectingQuality by rememberSaveable { mutableStateOf(false) }
    // Tellomi（tellomi/tellomi#1518 V-7 ②，照 Telegram 两端每个视频各选画质）：视频有两档以上可选时，画质键选这一个视频的档；
    // 否则照旧是上游的整批「标准 / 高」。
    val videoLevels = if (editorState is EditorState.VideoTrim) state.focusedVideoLevelOptions() else emptyList()

    if (isSelectingQuality) {
      if (videoLevels.isNotEmpty()) {
        VideoQualityBottomSheet(
          options = videoLevels,
          onLevelSelected = { onEvent(MediaEditScreenEvents.SetVideoLevel(it)) },
          onDismiss = { isSelectingQuality = false }
        )
      } else {
        QualitySelectorBottomSheet(
          quality = state.sentMediaQuality,
          onQualitySelected = { onEvent(MediaEditScreenEvents.SetMediaQuality(it)) },
          onDismiss = { isSelectingQuality = false }
        )
      }
    }

    val isHigh = if (videoLevels.isNotEmpty()) {
      (videoLevels.firstOrNull { it.isSelected }?.shortEdge ?: 0) >= 720
    } else {
      state.sentMediaQuality == SentMediaQuality.HIGH
    }

    MediaEditorToolbarButton(
      imageVector = if (isHigh) {
        SignalIcons.QualityHigh.imageVector
      } else {
        SignalIcons.QualityHighSlash.imageVector
      },
      onClick = { isSelectingQuality = true },
      // Tellomi（tellomi/tellomi#1518 V-6，照 TG 两端）：静音的视频当 GIF 发，画质固定，画质键变灰。
      enabled = enabled && !(editorState is EditorState.VideoTrim && editorState.videoTrimData.isMuted),
      modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_TOOLBAR_QUALITY_BUTTON)
    )
  }

  if (editorState is EditorState.VideoTrim && isMuteVisible(state, editorState)) {
    MediaEditorToolbarButton(
      imageVector = if (editorState.videoTrimData.isMuted) SignalIcons.SpeakerSlash.imageVector else SignalIcons.Speaker.imageVector,
      onClick = { onEvent(MediaEditScreenEvents.ToggleVideoMuted) },
      enabled = enabled,
      modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_TOOLBAR_MUTE_BUTTON)
    )
  }

  if (isSaveVisible(editorState)) {
    MediaEditorToolbarButton(
      imageVector = SignalIcons.Save.imageVector,
      onClick = { onEvent(MediaEditScreenEvents.SaveMedia) },
      enabled = enabled,
      modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_TOOLBAR_SAVE_BUTTON)
    )
  }

  if (isAddMediaVisible(state, editorState)) {
    if (isTakeMoreVisible(state)) {
      // Tellomi（tellomi/tellomi#1520 C-7）：照 Telegram 相机查看器的「添加照片」——回到相机接着拍，拍下的都留着。
      MediaEditorToolbarButton(
        imageVector = SignalIcons.Camera.imageVector,
        onClick = { onEvent(MediaEditScreenEvents.TakeMore) },
        enabled = enabled,
        contentDescription = stringResource(R.string.MediaEditScreen__take_more),
        modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_TOOLBAR_TAKE_MORE_BUTTON)
      )
    } else {
      MediaEditorToolbarButton(
        imageVector = SignalIcons.AlbumPlus.imageVector,
        onClick = { onEvent(MediaEditScreenEvents.NavigateToGallery) },
        enabled = enabled,
        modifier = Modifier.testTag(TestTags.MEDIA_EDITOR_TOOLBAR_ADD_MEDIA_BUTTON)
      )
    }
  }
}

/** Tellomi（tellomi/tellomi#1520 C-7）：编辑页盖在相机上时添加键是「再拍」；快拍照旧去相册。 */
internal fun isTakeMoreVisible(state: MediaEditState): Boolean {
  return state.isOverCamera && !state.isStory
}

/**
 * Gifs are exempt along with documents: an image/gif is never re-encoded on send, and the mp4s we treat as gifs are
 * already sized by whoever handed them to us, so a quality choice there is a control that does nothing.
 */
private fun isQualityVisible(state: MediaEditState, editorState: EditorState): Boolean {
  return !state.isStory &&
    editorState !is EditorState.Document &&
    editorState !is EditorState.Gif &&
    editorState !is EditorState.VideoGif
}

private fun isSaveVisible(editorState: EditorState): Boolean {
  return editorState is EditorState.Image || editorState is EditorState.Gif
}

private fun isMuteVisible(state: MediaEditState, editorState: EditorState): Boolean {
  return state.isMuteVideoAudioEnabled && editorState is EditorState.VideoTrim
}

/**
 * Adding a second attachment would silently drop view-once, so the entry point -- and the selection rail it belongs to
 * -- goes away while it is on.
 */
internal fun isAddMediaVisible(state: MediaEditState, editorState: EditorState?): Boolean {
  return !state.isViewOnceEnabled && editorState !is EditorState.Document
}

/**
 * Whether [MediaEditorToolbarSharedButtons] would render anything, so callers with no buttons of their own can skip the
 * toolbar rather than leave an empty one behind.
 */
internal fun hasSharedToolbarButtons(state: MediaEditState, editorState: EditorState): Boolean {
  return isQualityVisible(state, editorState) || isMuteVisible(state, editorState) || isSaveVisible(editorState) || isAddMediaVisible(state, editorState)
}
