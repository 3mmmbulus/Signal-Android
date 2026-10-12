/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import org.signal.core.ui.compose.LocalChatColorProvider
import org.signal.glide.compose.GlideImage
import org.signal.glide.compose.GlideImageScaleType
import org.signal.mediasend.R
import org.signal.mediasend.screens.MediaSendMetrics
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

  val recipientChatColor: Color? = state.recipientId?.let { LocalChatColorProvider.current(it.id).value }
  val shownMedia = album.getOrNull(pagerState.currentPage)

  Box(modifier = Modifier.fillMaxSize()) {
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
      snapPosition = SnapPosition.Center
    ) { page ->
      GlideImage(
        model = album[page].uri,
        scaleType = GlideImageScaleType.FIT_CENTER,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
      )
    }

    Box(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .systemBarsPadding()
        .padding(top = 10.dp)
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

    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 10.dp)
        .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      MediaEditControl(faded = false) {
        AddAMessageRow(
          enabled = !state.isSending,
          sendEnabled = !state.isSending && state.selectedMedia.isNotEmpty(),
          canScheduleSend = !state.isStory,
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
