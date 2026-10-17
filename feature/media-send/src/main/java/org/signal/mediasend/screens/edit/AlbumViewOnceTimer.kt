/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.signal.core.ui.compose.SignalIcons
import org.signal.mediasend.R
import org.signal.mediasend.TellomiViewOnceChoice

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4 第 1 条）：单张查看器说明框右侧的计时键（照 Telegram 安卓 `CaptionPhotoViewer` 的
 * timer 键、iOS `MessageInputPanelComponent` 的 timeoutButton，只取机制）。没设是计时图标，设了是强调色圆里的「1 / 3 / 10 / 30」；
 * 点开菜单：最上面一句说明，下面五项——一次性查看 / 3 秒 / 10 秒 / 30 秒 / 不删除，当前那项打勾。
 */
internal object AlbumViewOnceTimer {
  const val BUTTON_TEST_TAG = "album-view-once-timer"
  const val MENU_HEADER_TEST_TAG = "album-view-once-timer-header"
  const val HINT_TEST_TAG = "album-view-once-timer-hint"
  fun itemTestTag(choice: Int?) = "album-view-once-timer-item-${choice ?: "none"}"
}

@Composable
internal fun AlbumViewOnceTimerButton(choice: Int?, accentColor: Color, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }
  val label = stringResource(R.string.MediaSelectScreen__timer)
  val state = viewOnceChoiceTitle(choice)
  Box(modifier = modifier) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = label) { expanded = true }
        .testTag(AlbumViewOnceTimer.BUTTON_TEST_TAG)
        .semantics {
          contentDescription = label
          stateDescription = state
        }
    ) {
      if (choice == null) {
        Icon(
          imageVector = ImageVector.vectorResource(R.drawable.tellomi_symbol_timer_24),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(22.dp)
        )
      } else {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(26.dp)
            .background(accentColor, CircleShape)
        ) {
          Text(text = TellomiViewOnceChoice.badge(choice), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      Text(
        text = stringResource(R.string.MediaSelectScreen__timer_menu_title),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .widthIn(max = 260.dp)
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .testTag(AlbumViewOnceTimer.MENU_HEADER_TEST_TAG)
      )
      for (option in TellomiViewOnceChoice.MENU_ORDER) {
        DropdownMenuItem(
          text = { Text(viewOnceChoiceTitle(option)) },
          trailingIcon = if (option == choice) {
            { Icon(imageVector = SignalIcons.Check.imageVector, contentDescription = null) }
          } else {
            null
          },
          onClick = {
            expanded = false
            onSelect(option)
          },
          modifier = Modifier.testTag(AlbumViewOnceTimer.itemTestTag(option))
        )
      }
    }
  }
}

@Composable
internal fun viewOnceChoiceTitle(choice: Int?): String {
  return when (choice) {
    null -> stringResource(R.string.MediaSelectScreen__timer_do_not_delete)
    TellomiViewOnceChoice.VIEW_ONCE -> stringResource(R.string.MediaSelectScreen__timer_view_once)
    else -> pluralStringResource(R.plurals.MediaSelectScreen__timer_n_seconds, choice, choice)
  }
}

/** 选了值变了之后的提示（ADR-0079 §4.4 第 1 条），照片、视频分开说。 */
@Composable
internal fun viewOnceChoiceHint(choice: Int?, isVideo: Boolean): String {
  return when (choice) {
    null -> stringResource(if (isVideo) R.string.MediaSelectScreen__timer_hint_video_keep else R.string.MediaSelectScreen__timer_hint_photo_keep)
    TellomiViewOnceChoice.VIEW_ONCE -> stringResource(if (isVideo) R.string.MediaSelectScreen__timer_hint_video_view_once else R.string.MediaSelectScreen__timer_hint_photo_view_once)
    else -> pluralStringResource(if (isVideo) R.plurals.MediaSelectScreen__timer_hint_video_n_seconds else R.plurals.MediaSelectScreen__timer_hint_photo_n_seconds, choice, choice)
  }
}
