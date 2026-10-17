/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.signal.core.models.media.Media
import org.signal.mediasend.R
import org.signal.mediasend.TellomiMotionPhoto

/**
 * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：选图里的实况照片（安卓 = 动态照片）。照 Telegram 两端的机制（代码是自己写的）：
 *
 * - 顶栏「⋮」左边一个开关，已选里有动态照片才出现，默认关，作用于全部已选（Telegram 安卓 `ChatAttachAlert.updateMotionItem` /
 *   `ChatAttachAlertPhotoLayout.toggleLivePhotos`，iOS `MediaPickerScreen` 的 Live 键）；点了在它下面提示「实况照片已启用 / 已关闭」
 *   （安卓 `showMotionHint` 的 `HintView2`），2.5 秒消失、碰一下屏幕就消失。
 * - 网格里每张动态照片左上角一个图标，开着是实况、关着带斜杠（安卓 `PhotoAttachPhotoCell` 的 `media_live_on` / `media_live_off`，
 *   左上 8 dp）；没选的跟着顶栏开关。
 * - 单张查看器左下角（说明框上面）这一张的开关（安卓 `PhotoViewer.livePhotoButton`）。
 */
internal object PickerLive {
  const val HINT_MILLIS = 2_500L
  const val TOGGLE_TEST_TAG = "picker-live-toggle"
  const val HINT_TEST_TAG = "picker-live-hint"
  const val ALBUM_TEST_TAG = "album-live-button"
  fun badgeTestTag(uri: String) = "picker-live-badge-$uri"
}

/** 格子 / 查看器问「这一张是不是动态照片」用的（流程在 `MediaSendNavigation` 里给；没给就不画实况）。 */
internal val LocalTellomiLiveDetector = staticCompositionLocalOf<(suspend (Media) -> TellomiMotionPhoto.VideoRange?)?> { null }

/** 这一张是不是动态照片（第一次问时读文件开头的 XMP，之后记住）。 */
@Composable
internal fun rememberIsLivePhoto(media: Media): Boolean {
  val detector = LocalTellomiLiveDetector.current
  val isLive by produceState(initialValue = false, media.uri, detector) {
    value = detector != null && TellomiMotionPhoto.canBeMotionPhoto(media.contentType) && detector(media) != null
  }
  return isLive
}

@Composable
private fun liveIcon(isOn: Boolean): ImageVector {
  return ImageVector.vectorResource(if (isOn) R.drawable.tellomi_symbol_live_photo_24 else R.drawable.tellomi_symbol_live_photo_off_24)
}

/** 格子左上角的实况图标（白色，压一层浅黑底，亮图上也看得见）。 */
@Composable
internal fun PickerLiveBadge(isOn: Boolean, testTag: String, modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.32f))
      .testTag(testTag)
      .semantics { stateDescription = if (isOn) "on" else "off" }
  ) {
    Icon(imageVector = liveIcon(isOn), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
  }
}

/** 顶栏「⋮」左边的实况开关（同「⋮」的圆底）。 */
@Composable
internal fun PickerLiveToggle(isOn: Boolean, onClick: () -> Unit) {
  val label = stringResource(R.string.MediaSelectScreen__live_photo)
  val state = stringResource(if (isOn) R.string.MediaSelectScreen__live_photo_on else R.string.MediaSelectScreen__live_photo_off)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(PickerMetrics.roundButtonSize)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick, role = Role.Switch, onClickLabel = label)
      .testTag(PickerLive.TOGGLE_TEST_TAG)
      .semantics {
        contentDescription = label
        stateDescription = state
      }
  ) {
    Icon(imageVector = liveIcon(isOn), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
  }
}

/** 开关之后的提示：深色胶囊，图标 +「实况照片已启用 / 已关闭」。[isOn] 是 null 时不显示。 */
@Composable
internal fun PickerLiveHint(isOn: Boolean?, modifier: Modifier = Modifier) {
  // 淡出时还要画最后一次的文字
  var last by remember { mutableStateOf(false) }
  if (isOn != null) {
    last = isOn
  }
  AnimatedVisibility(
    visible = isOn != null,
    enter = fadeIn(tween(150)),
    exit = fadeOut(tween(200)),
    modifier = modifier
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
      modifier = Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xF21F1F1F))
        .padding(horizontal = 12.dp)
        .testTag(PickerLive.HINT_TEST_TAG)
    ) {
      Icon(imageVector = liveIcon(last), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
      Text(
        text = stringResource(if (last) R.string.MediaSelectScreen__live_photo_on else R.string.MediaSelectScreen__live_photo_off),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium
      )
    }
  }
}

/** 通用的提示胶囊（计时用；同 [PickerLiveHint] 的样子）。[text] 是 null 时不显示。 */
@Composable
internal fun PickerHint(text: String?, icon: ImageVector, modifier: Modifier = Modifier, testTag: String = PickerLive.HINT_TEST_TAG) {
  var last by remember { mutableStateOf("") }
  if (text != null) {
    last = text
  }
  AnimatedVisibility(
    visible = text != null,
    enter = fadeIn(tween(150)),
    exit = fadeOut(tween(200)),
    modifier = modifier
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
      modifier = Modifier
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xF21F1F1F))
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag(testTag)
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
      Text(text = last, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** 单张查看器左下角这一张的开关：半透明黑底胶囊，图标 +「实况 / 实况关闭」。 */
@Composable
internal fun AlbumLiveButton(isOn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(6.dp),
    modifier = modifier
      .height(34.dp)
      .clip(RoundedCornerShape(17.dp))
      .background(Color.Black.copy(alpha = 0.5f))
      .clickable(onClick = onClick, role = Role.Switch)
      .padding(horizontal = 12.dp)
      .testTag(PickerLive.ALBUM_TEST_TAG)
  ) {
    Icon(imageVector = liveIcon(isOn), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    Text(
      text = stringResource(if (isOn) R.string.MediaSelectScreen__live else R.string.MediaSelectScreen__live_off),
      color = Color.White,
      style = MaterialTheme.typography.labelLarge
    )
  }
}
