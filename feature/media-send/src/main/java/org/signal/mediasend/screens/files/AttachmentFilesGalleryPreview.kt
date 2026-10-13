/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.models.media.Media
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.ContentTypeUtil
import org.signal.core.util.bytes
import org.signal.core.util.logging.Log
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.AlbumPage
import org.signal.mediasend.screens.edit.AlbumViewerGestures
import org.signal.mediasend.screens.select.SelectionCheck
import org.signal.mediasend.test.TestTags
import org.signal.mediasend.util.formatAsClock
import kotlin.time.Duration.Companion.milliseconds

private object AttachmentFilesGalleryPreview

private val TAG = Log.tag(AttachmentFilesGalleryPreview::class)

/**
 * Tellomi（tellomi/tellomi#1263 F-3，owner 2026-10-13 照 Telegram）：「从相册中选择」网格点照片打开的预览（文件模式）。
 *
 * 照 Telegram 两端文件模式的预览（只读机制）：安卓 `PhotoViewer.openPhotoForSelect(isDocumentsPicker)` 把裁剪 / 旋转 / 绘制 /
 * 调色 / 剪辑都藏起来，底部换成文件名 + 「尺寸, 时长, 大小」；iOS `TGMediaPickerModernGalleryMixin` asFile 用 captions-only 的编辑上下文，
 * 不能编辑，底部是文件信息。左右滑看整本相册，右上编号勾，单击照片也是勾选（同选图页的查看器 V-1），双击 / 捏合缩放，下滑关闭。
 * 底部说明输入框 + 发送：说明照 Telegram iOS 留着（Telegram 安卓文件模式没有说明；「文件」页两端按 iOS 的结构做）。
 */
@Composable
internal fun AttachmentFilesGalleryPreview(
  items: List<Media>,
  initialIndex: Int,
  selected: List<Media>,
  caption: String,
  onEvent: (AttachmentFilesGalleryEvent) -> Unit
) {
  BackHandler { onEvent(AttachmentFilesGalleryEvent.ClosePreview) }

  val pagerState = rememberPagerState(
    initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
    pageCount = { items.size }
  )
  val density = LocalDensity.current
  var dragOffset by remember { mutableFloatStateOf(0f) }
  // 放大了的那一页不让翻页、不让下滑关闭（同 V-1 查看器）。
  val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
  val isZoomed = zoomedPages[pagerState.currentPage] == true
  val chromeAlpha = 1f - AlbumViewerGestures.dismissProgress(dragOffset, density)
  val shown = items.getOrNull(pagerState.currentPage)

  SignalTheme(isDarkMode = true) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = chromeAlpha))
        .draggable(
          state = rememberDraggableState { delta -> dragOffset += delta },
          orientation = Orientation.Vertical,
          enabled = !isZoomed,
          onDragStopped = { velocity ->
            if (AlbumViewerGestures.shouldDismiss(dragOffset, velocity, density)) {
              onEvent(AttachmentFilesGalleryEvent.ClosePreview)
            } else {
              animate(initialValue = dragOffset, targetValue = 0f) { value, _ -> dragOffset = value }
            }
          }
        )
        .testTag(TestTags.FILES_GALLERY_PREVIEW)
    ) {
      HorizontalPager(
        state = pagerState,
        userScrollEnabled = !isZoomed,
        snapPosition = SnapPosition.Center,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { translationY = dragOffset }
      ) { page ->
        val media = items[page]
        if (LocalInspectionMode.current) {
          Box(modifier = Modifier.fillMaxSize())
        } else {
          AlbumPage(
            media = media,
            onSingleTap = { onEvent(AttachmentFilesGalleryEvent.Toggle(media)) },
            onZoomedChange = { zoomed -> zoomedPages[page] = zoomed }
          )
        }
      }

      Box(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 4.dp, vertical = 4.dp)
          .alpha(chromeAlpha)
      ) {
        IconButton(
          onClick = { onEvent(AttachmentFilesGalleryEvent.ClosePreview) },
          modifier = Modifier
            .align(Alignment.CenterStart)
            .testTag(TestTags.FILES_GALLERY_PREVIEW_BACK)
        ) {
          Icon(imageVector = SignalIcons.ArrowStart.imageVector, contentDescription = stringResource(R.string.MediaSelectScreen__back), tint = Color.White)
        }

        Text(
          text = stringResource(R.string.MediaEditScreen__d_of_d, pagerState.currentPage + 1, items.size),
          style = MaterialTheme.typography.bodyMedium,
          color = Color.White,
          modifier = Modifier.align(Alignment.Center)
        )

        if (shown != null) {
          SelectionCheck(
            selectionIndex = selected.indexOfFirst { it.uri == shown.uri },
            color = MaterialTheme.colorScheme.primary,
            onClick = { onEvent(AttachmentFilesGalleryEvent.Toggle(shown)) },
            touchSize = 48.dp,
            visualSize = 30.dp,
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .testTag(TestTags.FILES_GALLERY_PREVIEW_CHECK)
          )
        }
      }

      Column(
        verticalArrangement = spacedBy(10.dp),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .background(Color.Black.copy(alpha = 0.5f))
          .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
          .padding(horizontal = 12.dp, vertical = 10.dp)
          .alpha(chromeAlpha)
      ) {
        if (shown != null) {
          FileInfo(media = shown)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(8.dp)) {
          CapsuleField(
            value = caption,
            onValueChange = { onEvent(AttachmentFilesGalleryEvent.CaptionChanged(it)) },
            placeholder = stringResource(R.string.AttachmentFilesScreen__add_a_caption),
            height = FilesMetrics.captionFieldHeight,
            imeAction = ImeAction.Default,
            modifier = Modifier
              .weight(1f)
              .testTag(TestTags.FILES_GALLERY_PREVIEW_CAPTION)
          )

          if (shown != null) {
            SendButton(count = selected.size, onClick = { onEvent(AttachmentFilesGalleryEvent.SendFromPreview(shown)) })
          }
        }
      }
    }
  }
}

/** 发送：圆形，勾了的时候右上角一个数字（照 Telegram 预览里发送键上的计数）。 */
@Composable
private fun SendButton(count: Int, onClick: () -> Unit) {
  val sendLabel = stringResource(R.string.AttachmentFilesScreen__send)
  Box {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(FilesMetrics.captionFieldHeight)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .clickable(role = Role.Button, onClickLabel = sendLabel, onClick = onClick)
        .semantics { contentDescription = sendLabel }
        .testTag(TestTags.FILES_GALLERY_PREVIEW_SEND)
    ) {
      Icon(imageVector = SignalIcons.SendFill.imageVector, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
    }
    if (count > 0) {
      Text(
        text = "$count",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .graphicsLayer { translationX = 6.dp.toPx(); translationY = -6.dp.toPx() }
          .clip(CircleShape)
          .background(Color.White)
          .padding(horizontal = 5.dp)
      )
    }
  }
}

/** 文件名一行 + 「尺寸 · 时长 · 大小」一行（照 Telegram 安卓 `docNameTextView` / `docInfoTextView`）。 */
@Composable
private fun FileInfo(media: Media) {
  val context = LocalContext.current
  val nameAndSize by produceState(initialValue = (media.fileName ?: "") to media.size, media.uri) {
    value = withContext(Dispatchers.IO) { readNameAndSize(context, media) }
  }
  val (name, size) = nameAndSize

  Column(modifier = Modifier.fillMaxWidth()) {
    if (name.isNotEmpty()) {
      Text(text = name, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
    Text(
      text = fileDetails(media, size),
      style = MaterialTheme.typography.bodyMedium,
      color = Color.White.copy(alpha = 0.7f),
      maxLines = 1
    )
  }
}

/** 「4032×3024 · 0:42 · 2.4 MB」：知道的才写。 */
internal fun fileDetails(media: Media, size: Long): String {
  return listOfNotNull(
    if (media.width > 0 && media.height > 0) "${media.width}×${media.height}" else null,
    if (ContentTypeUtil.isVideo(media.contentType) && media.duration > 0) media.duration.milliseconds.formatAsClock() else null,
    if (size > 0) size.bytes.toUnitString() else null
  ).joinToString(" · ")
}

/** 相册里的文件名与大小（大小以文件本身为准）；读不到的用 [Media] 里记的。 */
private fun readNameAndSize(context: Context, media: Media): Pair<String, Long> {
  val fallback = (media.fileName ?: "") to media.size
  return try {
    context.contentResolver.query(media.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use fallback
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
      val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
      val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
      (name ?: fallback.first) to (if (size > 0) size else fallback.second)
    } ?: fallback
  } catch (e: Exception) {
    Log.w(TAG, "Couldn't read the file name", e)
    fallback
  }
}
