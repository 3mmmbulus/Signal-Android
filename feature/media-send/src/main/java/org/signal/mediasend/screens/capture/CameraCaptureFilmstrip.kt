/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.signal.core.models.media.Media
import org.signal.core.ui.compose.NightPreview
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.ContentTypeUtil
import org.signal.glide.compose.GlideImage
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.rememberPreviewMedia
import org.signal.mediasend.test.TestTags

/** 胶片条上一格的大小：比编辑页底部的已选缩略图大，在取景框上一眼看得清。 */
private val CAPTURE_THUMBNAIL_SIZE = 56.dp
private val CAPTURE_THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)
private val CAPTURE_THUMBNAIL_SPACING = 8.dp

/** 胶片条和它下面变焦条之间的空。 */
private val FILMSTRIP_BOTTOM_MARGIN = 12.dp

private const val FILMSTRIP_FADE_MS = 150

/**
 * Tellomi（tellomi/tellomi#1520 C-7，照 Telegram 聊天相机）：拍下的留在相机时，快门上方一条胶片条，按拍的先后排，新拍的一张自动滚进来；
 * 点一张到编辑页里看它（在那里取消选中、裁剪、加说明），发送键在相机右下角。
 *
 * Telegram reference（只看机制，一行没搬）：Android `ChatAttachAlertPhotoLayout` 的 `cameraPhotoRecyclerView`（快门上方 80dp 的横条）；
 * iOS `TGCameraMainView` 点结果缩略图 `resultPressed(index)` 打开那一张。Tellomi 点一下照 iOS 打开那一张——
 * 安卓那边点一下是切换勾选，可 Tellomi 取消选中会把拍下的文件一起删掉，在这么小的格子上做删除不合适。
 */
@Composable
internal fun CameraCaptureFilmstrip(
  captures: List<Media>,
  visible: Boolean,
  onCaptureClick: (Media) -> Unit,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = visible && captures.isNotEmpty(),
    enter = fadeIn(animationSpec = tween(durationMillis = FILMSTRIP_FADE_MS)),
    exit = fadeOut(animationSpec = tween(durationMillis = FILMSTRIP_FADE_MS)),
    modifier = modifier
  ) {
    val listState = rememberLazyListState()

    // 新拍的一张总在最后，滚过去让它露出来。
    LaunchedEffect(captures.size) {
      if (captures.isNotEmpty()) {
        listState.animateScrollToItem(captures.lastIndex)
      }
    }

    LazyRow(
      state = listState,
      horizontalArrangement = Arrangement.spacedBy(CAPTURE_THUMBNAIL_SPACING),
      contentPadding = PaddingValues(horizontal = 16.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = FILMSTRIP_BOTTOM_MARGIN)
        .testTag(TestTags.MEDIA_CAPTURE_FILMSTRIP)
    ) {
      itemsIndexed(items = captures, key = { _, media -> media.uri }) { index, media ->
        CaptureThumbnail(
          media = media,
          number = index + 1,
          onClick = { onCaptureClick(media) }
        )
      }
    }
  }
}

@Composable
private fun CaptureThumbnail(
  media: Media,
  number: Int,
  onClick: () -> Unit
) {
  val isVideo = ContentTypeUtil.isVideoType(media.contentType)
  val description = if (isVideo) {
    stringResource(R.string.MediaCaptureScreen__captured_video, number)
  } else {
    stringResource(R.string.MediaCaptureScreen__captured_photo, number)
  }

  Box(
    modifier = Modifier
      .size(CAPTURE_THUMBNAIL_SIZE)
      .clip(CAPTURE_THUMBNAIL_SHAPE)
      .border(width = 1.dp, color = Color.White.copy(alpha = 0.6f), shape = CAPTURE_THUMBNAIL_SHAPE)
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .testTag(TestTags.MEDIA_CAPTURE_FILMSTRIP_ITEM)
  ) {
    if (!LocalInspectionMode.current) {
      GlideImage(
        model = media.uri,
        imageSize = DpSize(CAPTURE_THUMBNAIL_SIZE, CAPTURE_THUMBNAIL_SIZE),
        modifier = Modifier.size(CAPTURE_THUMBNAIL_SIZE)
      )
    } else {
      Box(
        modifier = Modifier
          .size(CAPTURE_THUMBNAIL_SIZE)
          .background(color = Color.Gray)
      )
    }

    if (isVideo) {
      Icon(
        imageVector = SignalIcons.Play.imageVector,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
          .align(Alignment.Center)
          .size(24.dp)
          .background(color = Color.Black.copy(alpha = 0.4f), shape = CircleShape)
          .padding(4.dp)
      )
    }
  }
}

@NightPreview
@Composable
private fun CameraCaptureFilmstripPreview() {
  val captures = rememberPreviewMedia(4)

  Previews.Preview {
    CameraCaptureFilmstrip(
      captures = captures,
      visible = true,
      onCaptureClick = {}
    )
  }
}
