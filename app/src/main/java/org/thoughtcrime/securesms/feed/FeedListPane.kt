/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.compose.FeedComposeActivity
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.signal.core.ui.R as CoreUiR

private val TOP_BAR_HEIGHT = 48.dp
private val INDICATOR_WIDTH = 20.dp

/** 离列表底部还剩几条时取下一页。 */
private const val LOAD_MORE_THRESHOLD = 4

/**
 * 「动态」标签的列表面板（M11 P1，`dynamic-feed.md` 第四节 L2-1）。
 *
 * 顶栏只有一行：「推荐 / 关注」居中（附录 10「顶栏一行」，第三节第 45 条去掉了「有什么新鲜事」）。两栏左右滑切换，
 * 滑块和内容一起跟手（`10-feed-prototype-log.md` 第 435 行）。
 *
 * 每栏一个信息流（契约 `docs/adr/0071/client-api-2026-10.md` 4.1）：下拉刷新、滑到底自动取下一页、点赞、「不感兴趣」可撤销。
 * 右下角是发布按钮；搜索与通知入口随后面的 PR 接上，没接上的入口不画，不放点了没反应的按钮。
 */
@Composable
fun FeedListPane(
  modifier: Modifier = Modifier,
  viewModel: FeedViewModel = viewModel { FeedViewModel() }
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val undoLabel = stringResource(R.string.TellomiFeed__undo)
  val hiddenMessage = stringResource(R.string.TellomiFeed__hidden)
  val networkMessage = stringResource(R.string.TellomiFeed__network_error)
  val actionMessage = stringResource(R.string.TellomiFeed__action_failed)

  LaunchedEffect(state.lastHidden, state.transientError) {
    val error = state.transientError
    when {
      error != null -> {
        snackbarHostState.showSnackbar(if (error == FeedState.TransientError.NETWORK) networkMessage else actionMessage)
        viewModel.onTransientErrorShown()
      }
      state.lastHidden != null -> {
        val result = snackbarHostState.showSnackbar(message = hiddenMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoNotInterested() else viewModel.onTransientErrorShown()
      }
    }
  }

  val publishedMessage = stringResource(R.string.TellomiFeedCompose__published)
  val pendingMessage = stringResource(R.string.TellomiFeedCompose__pending)
  LaunchedEffect(state.publishNotice) {
    val notice = state.publishNotice ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (notice == FeedCreatePostResponse.STATE_PENDING) pendingMessage else publishedMessage)
    viewModel.onPublishNoticeShown()
  }

  val composeLauncher = rememberLauncherForActivityResult(FeedComposeActivity.Contract()) { publishState ->
    if (publishState != null) viewModel.onPublished(publishState)
  }

  Box(modifier = modifier) {
    FeedPager(
      state = state,
      onTabShown = viewModel::onTabShown,
      onRefresh = viewModel::refresh,
      onLoadMore = viewModel::loadMore,
      onLikeClick = viewModel::toggleLike,
      onNotInterestedClick = viewModel::notInterested,
      modifier = Modifier.fillMaxSize()
    )

    // 发布按钮只在右下角（第三节第 45 条），滑动时不动（第 46 条）
    FloatingActionButton(
      onClick = { composeLauncher.launch(Unit) },
      shape = CircleShape,
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(16.dp)
    ) {
      Icon(
        painter = painterResource(CoreUiR.drawable.symbol_edit_24),
        contentDescription = stringResource(R.string.TellomiFeedCompose__new_post)
      )
    }

    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun FeedPager(
  state: FeedState,
  onTabShown: (FeedTab) -> Unit,
  onRefresh: (FeedTab) -> Unit,
  onLoadMore: (FeedTab) -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onNotInterestedClick: (FeedCard) -> Unit,
  modifier: Modifier = Modifier
) {
  val tabs = state.tabs
  val pagerState = rememberPagerState(pageCount = { tabs.size })
  val scope = rememberCoroutineScope()

  Column(modifier = modifier) {
    FeedTopBar(
      tabs = tabs,
      selectedIndex = pagerState.currentPage,
      onTabClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
    )

    HorizontalPager(
      state = pagerState,
      key = { tabs[it].apiName },
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) { page ->
      val tab = tabs[page]
      LaunchedEffect(tab) { onTabShown(tab) }

      FeedTimeline(
        tab = tab,
        tabState = state.tab(tab),
        showIpRegion = state.config?.ipRegionVisible == true,
        onRefresh = { onRefresh(tab) },
        onLoadMore = { onLoadMore(tab) },
        onLikeClick = onLikeClick,
        onNotInterestedClick = onNotInterestedClick
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTimeline(
  tab: FeedTab,
  tabState: FeedTabState,
  showIpRegion: Boolean,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onNotInterestedClick: (FeedCard) -> Unit
) {
  val listState = rememberLazyListState()

  val nearEnd by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
      info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
    }
  }

  LaunchedEffect(nearEnd, tabState.nextCursor) {
    if (nearEnd && tabState.nextCursor != null) onLoadMore()
  }

  PullToRefreshBox(
    isRefreshing = tabState.refreshing,
    onRefresh = onRefresh,
    modifier = Modifier.fillMaxSize()
  ) {
    when {
      tabState.cards.isEmpty() && (tabState.loading || !tabState.loadedOnce) -> FeedLoading()
      tabState.cards.isEmpty() && tabState.failed -> FeedErrorState(onRetry = onRefresh)
      tabState.cards.isEmpty() -> FeedEmptyState(tab = tab)
      else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items = tabState.cards, key = { it.id }) { card ->
          FeedPostCard(
            card = card,
            showIpRegion = showIpRegion,
            onLikeClick = onLikeClick,
            onNotInterestedClick = onNotInterestedClick,
            modifier = Modifier.animateItem()
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        if (tabState.nextCursor != null) {
          item(key = "loading-more") {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
              CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FeedLoading() {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
    CircularProgressIndicator()
  }
}

@Composable
private fun FeedErrorState(onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 40.dp)
  ) {
    Text(
      text = stringResource(R.string.TellomiFeed__load_failed),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(onClick = onRetry) {
      Text(stringResource(R.string.TellomiFeed__retry))
    }
  }
}

@Composable
private fun FeedTopBar(
  tabs: List<FeedTab>,
  selectedIndex: Int,
  onTabClick: (Int) -> Unit
) {
  Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.statusBars)
      .height(TOP_BAR_HEIGHT)
  ) {
    tabs.forEachIndexed { index, tab ->
      FeedTopBarTab(
        label = stringResource(tab.label),
        selected = index == selectedIndex,
        onClick = { onTabClick(index) }
      )
    }
  }
}

@Composable
private fun FeedTopBarTab(
  label: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  val indicatorWidth by animateDpAsState(targetValue = if (selected) INDICATOR_WIDTH else 0.dp, label = "feed-tab-indicator")

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Tab,
        onClick = onClick
      )
      .semantics { this.selected = selected }
      .padding(horizontal = 14.dp, vertical = 6.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(4.dp))

    Box(
      modifier = Modifier
        .width(indicatorWidth)
        .height(2.dp)
        .clip(RoundedCornerShape(1.dp))
        .background(MaterialTheme.colorScheme.onSurface)
    )
  }
}

@Composable
private fun FeedEmptyState(tab: FeedTab) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 40.dp)
  ) {
    Icon(
      imageVector = ImageVector.vectorResource(R.drawable.tellomi_feed_28),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(48.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(tab.emptyTitle),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(tab.emptyBody),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
  }
}

@DayNightPreviews
@Composable
private fun FeedPagerEmptyPreview() {
  Previews.Preview {
    FeedPager(
      state = FeedState(tabStates = mapOf(FeedTab.RECOMMEND to FeedTabState(loadedOnce = true))),
      onTabShown = {},
      onRefresh = {},
      onLoadMore = {},
      onLikeClick = {},
      onNotInterestedClick = {},
      modifier = Modifier.fillMaxSize()
    )
  }
}

@Preview
@Composable
private fun FeedTopBarPreview() {
  Previews.Preview {
    FeedTopBar(tabs = FeedTab.entries, selectedIndex = 1, onTabClick = {})
  }
}
