/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.ContentTypeUtil
import org.signal.glide.compose.GlideImage
import org.signal.mediasend.R
import org.signal.mediasend.screens.select.PickerMetrics
import org.signal.mediasend.screens.select.SelectionCheck
import org.signal.mediasend.test.TestTags
import org.signal.mediasend.util.formatAsClock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tellomi（tellomi/tellomi#1263 F-3、F-s8）：「文件」页「从相册中选择」——全屏相册网格（文件模式）。
 *
 * 左上返回、中间相册标题（点开换相册）；3 列正方形格子，右上选择圈（带序号）；点照片本身也是勾选（Telegram 两端是开预览，
 * 这里先不做预览）；底栏「取消」/「发送（N）」，一个都没选时发送置灰。
 */
@Composable
internal fun AttachmentFilesGalleryScreen(
  state: AttachmentFilesGalleryState,
  onEvent: (AttachmentFilesGalleryEvent) -> Unit
) {
  BackHandler { onEvent(AttachmentFilesGalleryEvent.Cancel) }

  Scaffold(
    topBar = { GalleryTopBar(state = state, onEvent = onEvent) },
    bottomBar = { GalleryBottomBar(count = state.selected.size, onEvent = onEvent) },
    containerColor = MaterialTheme.colorScheme.surface
  ) { padding ->
    val items = state.items
    when {
      items == null -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(padding)) {
        CircularProgressIndicator()
      }

      items.isEmpty() -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(padding)) {
        Text(
          text = stringResource(R.string.AttachmentFilesGallery__no_photos_or_videos),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      else -> LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 2.dp),
        modifier = Modifier.fillMaxSize().padding(padding)
      ) {
        items(items, key = { it.uri.toString() }) { media ->
          GalleryTile(
            media = media,
            selectionIndex = state.selectionIndex(media),
            onToggle = { onEvent(AttachmentFilesGalleryEvent.Toggle(media)) }
          )
        }
      }
    }
  }
}

@Composable
private fun GalleryTopBar(
  state: AttachmentFilesGalleryState,
  onEvent: (AttachmentFilesGalleryEvent) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .windowInsetsPadding(WindowInsets.statusBars)
      .height(56.dp)
      .padding(horizontal = 4.dp)
  ) {
    IconButton(
      onClick = { onEvent(AttachmentFilesGalleryEvent.Cancel) },
      modifier = Modifier.align(Alignment.CenterStart)
    ) {
      Icon(imageVector = SignalIcons.ArrowStart.imageVector, contentDescription = stringResource(R.string.MediaSelectScreen__back))
    }

    Box(modifier = Modifier.align(Alignment.Center)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clickable(role = Role.DropdownList) { expanded = true }
          .padding(horizontal = 8.dp, vertical = 8.dp)
          .testTag(TestTags.FILES_GALLERY_FOLDER_TITLE)
      ) {
        Text(
          text = folderTitle(state.folder),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Icon(imageVector = SignalIcons.ArrowDropDown.imageVector, contentDescription = null)
      }

      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        state.folders.forEach { folder ->
          DropdownMenuItem(
            text = { Text(text = folderTitle(folder)) },
            onClick = {
              expanded = false
              onEvent(AttachmentFilesGalleryEvent.SwitchFolder(folder))
            }
          )
        }
      }
    }
  }
}

@Composable
private fun folderTitle(folder: MediaFolder): String {
  return if (folder.bucketId == Media.ALL_MEDIA_BUCKET_ID) stringResource(R.string.MediaSelectScreen__recents) else folder.title
}

@Composable
private fun GalleryBottomBar(count: Int, onEvent: (AttachmentFilesGalleryEvent) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .windowInsetsPadding(WindowInsets.navigationBars)
      .height(64.dp)
      .padding(horizontal = 16.dp)
  ) {
    TextButton(
      onClick = { onEvent(AttachmentFilesGalleryEvent.Cancel) },
      modifier = Modifier.testTag(TestTags.FILES_GALLERY_CANCEL)
    ) {
      Text(text = stringResource(android.R.string.cancel))
    }

    Button(
      onClick = { onEvent(AttachmentFilesGalleryEvent.Send) },
      enabled = count > 0,
      modifier = Modifier.testTag(TestTags.FILES_GALLERY_SEND)
    ) {
      Text(text = if (count > 0) pluralStringResource(R.plurals.AttachmentFilesGallery__send_n, count, count) else stringResource(R.string.AttachmentFilesGallery__send))
    }
  }
}

@Composable
private fun GalleryTile(
  media: Media,
  selectionIndex: Int,
  onToggle: () -> Unit
) {
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .background(color = MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onToggle, role = Role.Checkbox)
      .testTag(TestTags.filesGalleryTile(media.uri.toString()))
  ) {
    if (LocalInspectionMode.current) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(color = Previews.rememberRandomColor())
      )
    } else {
      GlideImage(
        model = media.uri,
        imageSize = DpSize(maxWidth, maxHeight),
        modifier = Modifier.fillMaxSize()
      )
    }

    if (ContentTypeUtil.isVideo(media.contentType) && !media.isVideoGif) {
      Text(
        text = remember(media.duration) { media.duration.milliseconds.formatAsClock() },
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 4f)),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 6.dp, bottom = 4.dp)
      )
    }

    SelectionCheck(
      selectionIndex = selectionIndex,
      color = MaterialTheme.colorScheme.primary,
      onClick = onToggle,
      touchSize = PickerMetrics.tileCheckTouchSize,
      visualInset = PickerMetrics.tileCheckVisualInset,
      modifier = Modifier.align(Alignment.TopEnd)
    )
  }
}
