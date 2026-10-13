/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedPostCard
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.signal.core.ui.R as CoreUiR

object FeedTopicTestTags {
  const val HEADER = "feed_topic_header"
  const val FOLLOW = "feed_topic_follow"
  const val UNFOLLOW = "feed_topic_unfollow"
}

private const val LOAD_MORE_THRESHOLD = 4

/**
 * 话题页：头上 #话题名、一句「关注后新动态进关注栏」和关注按钮，下面是帖子（新的在前）。
 * 卡片上点同一个话题不再开新页，点别的话题开那个话题的页。
 */
@Composable
fun FeedTopicScreen(
  label: String,
  state: FeedTopicState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onToggleFollow: () -> Unit,
  onLoadMore: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onReportPost: (FeedCard) -> Unit,
  onTopicClick: (FeedTopic) -> Unit,
  onNoticeShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val followed = stringResource(R.string.TellomiFeedTopicPage__followed)
  val unfollowed = stringResource(R.string.TellomiFeedTopicFollow__unfollowed)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  val undo = stringResource(R.string.TellomiFeed__undo)
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    when (notice) {
      FeedTopicState.Notice.FOLLOWED -> if (snackbarHostState.showSnackbar(followed, actionLabel = undo) == SnackbarResult.ActionPerformed) onToggleFollow()
      FeedTopicState.Notice.UNFOLLOWED -> snackbarHostState.showSnackbar(unfollowed)
      FeedTopicState.Notice.FAILED -> snackbarHostState.showSnackbar(failed)
    }
  }

  Scaffolds.Settings(
    title = "#$label",
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      if (state.gone) {
        CenteredMessage(text = stringResource(R.string.TellomiFeedTopicPage__gone))
      } else {
        TopicContent(
          label = label,
          state = state,
          onRetry = onRetry,
          onToggleFollow = onToggleFollow,
          onLoadMore = onLoadMore,
          onLikeClick = onLikeClick,
          onOpenPost = onOpenPost,
          onReportPost = onReportPost,
          onTopicClick = onTopicClick
        )
      }
    }
  }
}

@Composable
private fun TopicContent(
  label: String,
  state: FeedTopicState,
  onRetry: () -> Unit,
  onToggleFollow: () -> Unit,
  onLoadMore: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onReportPost: (FeedCard) -> Unit,
  onTopicClick: (FeedTopic) -> Unit
) {
  val listState = rememberLazyListState()
  val nearEnd by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
      info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
    }
  }
  LaunchedEffect(nearEnd, state.cursor) {
    if (nearEnd && state.cursor != null) onLoadMore()
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    item(key = "header") {
      TopicHeader(label = label, following = state.following, enabled = state.loadedOnce, onToggleFollow = onToggleFollow)
    }
    when {
      state.posts.isEmpty() && state.loading -> item(key = "loading") {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
      }
      state.posts.isEmpty() && state.failed -> item(key = "failed") {
        CenteredMessage(text = stringResource(R.string.TellomiFeed__load_failed), action = stringResource(R.string.TellomiFeed__retry), onAction = onRetry)
      }
      state.posts.isEmpty() -> item(key = "empty") {
        CenteredMessage(text = stringResource(R.string.TellomiFeedTopicPage__empty))
      }
      else -> {
        items(items = state.posts, key = { it.id }) { card ->
          FeedPostCard(
            card = card,
            showIpRegion = state.showIpRegion,
            onLikeClick = onLikeClick,
            onNotInterestedClick = null,
            mediaBaseUrl = state.mediaBaseUrl,
            onOpen = onOpenPost,
            onReportClick = if (card.viewer.isAuthor) null else onReportPost,
            onTopicClick = onTopicClick
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (state.cursor != null) {
          item(key = "loading-more") {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator() }
          }
        }
      }
    }
  }
}

@Composable
private fun TopicHeader(label: String, following: Boolean, enabled: Boolean, onToggleFollow: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface)))
      .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)
      .testTag(FeedTopicTestTags.HEADER)
  ) {
    Text(text = "#$label", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
    Text(
      text = stringResource(R.string.TellomiFeedTopicFollow__follow_explain),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant
    )
    if (following) {
      OutlinedButton(onClick = onToggleFollow, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(FeedTopicTestTags.UNFOLLOW)) {
        Text(stringResource(R.string.TellomiFeedTopicFollow__following))
      }
    } else {
      Button(onClick = onToggleFollow, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(FeedTopicTestTags.FOLLOW)) {
        Text(stringResource(R.string.TellomiFeedTopicFollow__follow))
      }
    }
  }
}

@Composable
private fun CenteredMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 32.dp, vertical = 48.dp)
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    if (action != null) {
      TextButton(onClick = onAction) { Text(action) }
    }
  }
}
