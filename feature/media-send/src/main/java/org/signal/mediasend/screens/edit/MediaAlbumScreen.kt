/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.signal.core.models.media.Media
import org.signal.core.ui.WindowBreakpoint
import org.signal.core.ui.compose.LocalChatColorProvider
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.rememberWindowBreakpoint
import org.signal.glide.compose.GlideImage
import org.signal.glide.compose.GlideImageScaleType
import org.signal.mediasend.AlbumEditTool
import org.signal.mediasend.R
import org.signal.mediasend.screens.MediaSendMetrics
import org.signal.mediasend.screens.edit.image.TellomiAdjustToolbarButton
import org.signal.mediasend.screens.select.SelectionCheck

@Composable
internal fun MediaAlbumScreen(
  state: MediaEditState,
  onEvent: (MediaEditScreenEvents) -> Unit
) {
  val album = state.album
  val currentAlbum by rememberUpdatedState(album)

  val pagerState = rememberPagerState(
    initialPage = album.indexOfFirst { it.uri == state.albumFocusedMedia?.uri }.coerceAtLeast(0),
    pageCount = { album.size }
  )

  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }
      .drop(1)
      .collect { settledPage ->
        currentAlbum.getOrNull(settledPage)?.let { onEvent(MediaEditScreenEvents.AlbumFocusedMediaChanged(it)) }
      }
  }

  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  var dragOffset by remember { mutableFloatStateOf(0f) }
  // 放大了的那一页不让翻页、不让下滑关闭：单指拖动交给图片平移（照 Telegram 安卓 PhotoViewer）。
  val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
  val isZoomed = zoomedPages[pagerState.currentPage] == true
  val chromeAlpha = 1f - AlbumViewerGestures.dismissProgress(dragOffset, density)

  val recipientChatColor: Color? = state.recipientId?.let { LocalChatColorProvider.current(it.id).value }
  val shownMedia = album.getOrNull(pagerState.currentPage)
  val isSmallWindowBreakpoint = rememberWindowBreakpoint() is WindowBreakpoint.Small
  val showEditShortcuts = shownMedia != null && AlbumViewerGestures.isEditableImage(shownMedia.contentType)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .draggable(
        state = rememberDraggableState { delta -> dragOffset += delta },
        orientation = Orientation.Vertical,
        enabled = !isZoomed,
        onDragStopped = { velocity ->
          if (AlbumViewerGestures.shouldDismiss(dragOffset, velocity, density)) {
            onEvent(MediaEditScreenEvents.CloseAlbum)
          } else {
            animate(initialValue = dragOffset, targetValue = 0f) { value, _ -> dragOffset = value }
          }
        }
      )
  ) {
    HorizontalPager(
      state = pagerState,
      userScrollEnabled = !isZoomed,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { translationY = dragOffset },
      snapPosition = SnapPosition.Center
    ) { page ->
      val media = album[page]
      AlbumPage(
        media = media,
        preview = state.albumEditPreviews[media.uri],
        onSingleTap = { onEvent(MediaEditScreenEvents.ToggleAlbumSelected(media)) },
        onZoomedChange = { zoomed -> zoomedPages[page] = zoomed }
      )
    }

    Box(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .systemBarsPadding()
        .padding(top = 10.dp)
        .alpha(chromeAlpha)
    ) {
      Pill(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
          text = stringResource(R.string.MediaEditScreen__d_of_d, pagerState.currentPage + 1, album.size),
          style = MaterialTheme.typography.bodyMedium
        )
      }
    }

    Column(
      horizontalAlignment = Alignment.End,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .systemBarsPadding()
        .alpha(chromeAlpha)
    ) {
      if (shownMedia != null) {
        SelectionCheck(
          selectionIndex = state.selectedMedia.indexOfFirst { it.uri == shownMedia.uri },
          color = recipientChatColor ?: MaterialTheme.colorScheme.primary,
          onClick = { onEvent(MediaEditScreenEvents.ToggleAlbumSelected(shownMedia)) },
          touchSize = 48.dp,
          visualSize = 34.dp
        )
      }

      if (state.selectedMedia.isNotEmpty()) {
        Box(
          modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button) { onEvent(MediaEditScreenEvents.ShowSelectedFilmstrip) }
        ) {
          Pill(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
              text = pluralStringResource(R.plurals.MediaSelectScreen__n_selected, state.selectedMedia.size, state.selectedMedia.size),
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }
      }
    }

    if (!isSmallWindowBreakpoint && showEditShortcuts && shownMedia != null) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 24.dp)
          .alpha(chromeAlpha)
      ) {
        AlbumEditShortcuts(media = shownMedia, onEvent = onEvent)
      }
    }

    // 与编辑页同一套底部摆法：窄屏时工具条在说明行上面，说明行的位置不变（owner 要求相册底栏与编辑页同位）。
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 10.dp)
        .navigationBarsPadding()
        .alpha(chromeAlpha),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      if (isSmallWindowBreakpoint && showEditShortcuts && shownMedia != null) {
        AlbumEditShortcuts(media = shownMedia, onEvent = onEvent)
      }

      MediaEditControl(faded = false) {
        AddAMessageRow(
          enabled = !state.isSending,
          sendEnabled = !state.isSending && state.selectedMedia.isNotEmpty(),
          canScheduleSend = !state.isStory,
          captionAboveMedia = state.captionAboveMediaOption,
          sendPreviewMedia = state.selectedMedia,
          viewOnceAvailable = state.isViewOnceAvailable,
          viewOnce = state.isViewOnceEnabled,
          isReply = state.isReply,
          message = state.message,
          recipientChatColor = recipientChatColor,
          onEvent = onEvent,
          onNextClick = { onEvent(MediaEditScreenEvents.NextClick) },
          modifier = Modifier
            .widthIn(max = MediaSendMetrics.BottomBarMaxWidth)
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 16.dp)
        )
      }
    }
  }
}

/**
 * 查看器底部「裁剪」「绘制」「调整」：照 Telegram 安卓查看器底栏的 Crop / Paint / Tune（`PhotoViewer.itemsLayout`），
 * 三个键、次序与 iOS `TellomiPhotoPickerAlbumViewer` 一致；按钮与编辑页的工具条同款，「调整」就是编辑页那一个。
 */
@Composable
internal fun AlbumEditShortcuts(
  media: Media,
  onEvent: (MediaEditScreenEvents) -> Unit
) {
  MediaEditorToolbar {
    MediaEditorToolbarButton(
      imageVector = SignalIcons.CropRotate.imageVector,
      onClick = { onEvent(MediaEditScreenEvents.EditFromAlbum(media, AlbumEditTool.CROP)) }
    )
    MediaEditorToolbarButton(
      imageVector = SignalIcons.BrushPen.imageVector,
      onClick = { onEvent(MediaEditScreenEvents.EditFromAlbum(media, AlbumEditTool.DRAW)) }
    )
    TellomiAdjustToolbarButton(
      onClick = { onEvent(MediaEditScreenEvents.EditFromAlbum(media, AlbumEditTool.ADJUST)) }
    )
  }
}

/**
 * 查看器里的一页：单击切换选中（照 Telegram 安卓 `onSingleTapConfirmed` → 勾选），双击放大 / 还原，双指捏合缩放。
 * 没放大时单指拖动不消费，留给翻页（横）和下滑关闭（竖）。「文件」页相册网格的预览（#1263 F-3）也用它。
 */
@Composable
internal fun AlbumPage(
  media: Media,
  onSingleTap: () -> Unit,
  onZoomedChange: (Boolean) -> Unit,
  // Tellomi（两端对齐清单 P-6b）：这张改过（裁剪、涂鸦、调色……），照发出去的样子画的图；null = 显示原图
  preview: Bitmap? = null
) {
  val scope = rememberCoroutineScope()
  var size by remember { mutableStateOf(IntSize.Zero) }
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val currentOnSingleTap by rememberUpdatedState(onSingleTap)
  val currentOnZoomedChange by rememberUpdatedState(onZoomedChange)
  val isZoomed by remember { derivedStateOf { scale > 1f } }

  LaunchedEffect(isZoomed) { currentOnZoomedChange(isZoomed) }
  DisposableEffect(Unit) { onDispose { currentOnZoomedChange(false) } }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onSizeChanged { size = it }
      .pointerInput(media.uri) {
        detectTapGestures(
          onTap = { currentOnSingleTap() },
          onDoubleTap = { tap ->
            val (targetScale, targetOffset) = AlbumViewerGestures.doubleTapTarget(scale, tap, size)
            val startScale = scale
            val startOffset = offset
            scope.launch {
              animate(initialValue = 0f, targetValue = 1f) { fraction, _ ->
                scale = startScale + (targetScale - startScale) * fraction
                offset = lerp(startOffset, targetOffset, fraction)
              }
            }
          }
        )
      }
      .pointerInput(media.uri) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
              val zoom = event.calculateZoom()
              val pan = event.calculatePan()
              if (zoom != 1f || pan != Offset.Zero) {
                val (newScale, newOffset) = AlbumViewerGestures.transform(scale, offset, zoom, pan, event.calculateCentroid(useCurrent = true), size)
                scale = newScale
                offset = newOffset
                event.changes.forEach { if (it.positionChanged()) it.consume() }
              }
            }
          } while (event.changes.any { it.pressed })
        }
      }
  ) {
    val imageModifier = Modifier
      .fillMaxSize()
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
      }
    if (preview != null) {
      Image(
        bitmap = remember(preview) { preview.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = imageModifier
      )
    } else {
      GlideImage(
        model = media.uri,
        scaleType = GlideImageScaleType.FIT_CENTER,
        contentScale = ContentScale.Fit,
        modifier = imageModifier
      )
    }
  }
}
