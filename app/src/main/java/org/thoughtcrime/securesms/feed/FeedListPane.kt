/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.compose.FeedComposeActivity
import org.thoughtcrime.securesms.feed.compose.FeedEditTarget
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.mine.FeedMyPostsActivity
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedLimits
import org.thoughtcrime.securesms.feed.notifications.FeedNotificationsActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.feed.report.FeedReportActivity
import org.thoughtcrime.securesms.feed.report.FeedReportTarget
import org.thoughtcrime.securesms.feed.search.FeedSearchActivity
import org.thoughtcrime.securesms.feed.settings.FeedRecommendationSettingsActivity
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
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val networkMessage = stringResource(R.string.TellomiFeed__network_error)
  val actionMessage = stringResource(R.string.TellomiFeed__action_failed)

  val blockedMessage = stringResource(R.string.TellomiFeedPage__blocked_done)
  LaunchedEffect(state.blockedNotice) {
    if (!state.blockedNotice) return@LaunchedEffect
    snackbarHostState.showSnackbar(blockedMessage)
    viewModel.onBlockedNoticeShown()
  }

  LaunchedEffect(state.transientError) {
    val error = state.transientError ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (error == FeedState.TransientError.NETWORK) networkMessage else actionMessage)
    viewModel.onTransientErrorShown()
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

  val savedPendingMessage = stringResource(R.string.TellomiFeedCompose__edit_saved_pending)
  val savedMessage = stringResource(R.string.TellomiFeedCompose__edit_saved)
  LaunchedEffect(state.editNotice) {
    val notice = state.editNotice ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (notice == FeedCreatePostResponse.STATE_PENDING) savedPendingMessage else savedMessage)
    viewModel.onEditNoticeShown()
  }

  val editLauncher = rememberLauncherForActivityResult(FeedComposeActivity.Contract()) { publishState ->
    if (publishState != null) viewModel.onEdited(publishState)
  }

  var confirmDelete by remember { mutableStateOf<FeedCard?>(null) }
  var confirmBlock by remember { mutableStateOf<FeedCard?>(null) }

  // 举报页里选了「只是不想看到」就按不感兴趣处理（第三节第 32 条先分流）
  var reporting by remember { mutableStateOf<FeedCard?>(null) }
  val reportedMessage = stringResource(R.string.TellomiFeedReport__sent)
  var reportSent by remember { mutableStateOf(false) }
  val reportLauncher = rememberLauncherForActivityResult(FeedReportActivity.Contract()) { result ->
    val card = reporting
    reporting = null
    when (result) {
      FeedReportActivity.Result.NOT_INTERESTED -> if (card != null) viewModel.notInterested(card)
      FeedReportActivity.Result.SENT -> reportSent = true
      null -> Unit
    }
  }
  LaunchedEffect(reportSent) {
    if (reportSent) {
      snackbarHostState.showSnackbar(reportedMessage)
      reportSent = false
    }
  }

  // 退到后台或离开动态标签：停掉曝光计时，发掉攒着的事件（契约 6.1）
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onFeedHidden() }
  DisposableEffect(viewModel) { onDispose { viewModel.onFeedHidden() } }

  val density = LocalDensity.current
  val chrome = remember(density) {
    with(density) { FeedChromeState(FeedChromeState.HIDE_AFTER.toPx(), FeedChromeState.SHOW_AFTER.toPx()) }
  }
  DisposableEffect(chrome) {
    FeedChromeState.shared = chrome
    onDispose {
      chrome.reset()
      if (FeedChromeState.shared === chrome) FeedChromeState.shared = null
    }
  }

  Box(modifier = modifier) {
    FeedPager(
      chrome = chrome,
      state = state,
      onTabShown = viewModel::onTabShown,
      onRefresh = viewModel::refresh,
      onLoadMore = viewModel::loadMore,
      onLikeClick = viewModel::toggleLike,
      onNotInterestedClick = viewModel::notInterested,
      onUndoNotInterested = viewModel::undoNotInterested,
      onBlockAuthorClick = { confirmBlock = it },
      onManagePreferences = { context.startActivity(FeedRecommendationSettingsActivity.intent(context)) },
      onCardsVisible = viewModel::onCardsVisible,
      onMyPostsClick = { context.startActivity(FeedMyPostsActivity.intent(context)) },
      onNotificationsClick = { context.startActivity(FeedNotificationsActivity.intent(context)) },
      onSearchClick = { context.startActivity(FeedSearchActivity.intent(context)) },
      onEditClick = { target -> editLauncher.launch(target) },
      onDeleteClick = { confirmDelete = it },
      onOpenClick = { context.startActivity(FeedPostDetailActivity.intent(context, it)) },
      onAuthorClick = { context.startActivity(FeedProfilePageActivity.intent(context, it.profileId)) },
      onVote = viewModel::vote,
      onReportClick = { card ->
        reporting = card
        reportLauncher.launch(FeedReportTarget.content(card.id))
      },
      modifier = Modifier.fillMaxSize()
    )

    // 发布按钮只在右下角（第三节第 45 条），滑动时不动（第 46 条）
    FloatingActionButton(
      onClick = { composeLauncher.launch(null) },
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

  confirmBlock?.let { card ->
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedPage__block_title, card.author?.displayName.orEmpty()),
      body = stringResource(R.string.TellomiFeedPage__block_body),
      confirm = stringResource(R.string.TellomiFeedPage__block),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = { viewModel.blockAuthor(card) },
      onDismiss = { confirmBlock = null }
    )
  }

  confirmDelete?.let { card ->
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedMine__delete_title),
      body = stringResource(R.string.TellomiFeedMine__delete_body),
      confirm = stringResource(R.string.TellomiFeedMine__delete),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = { viewModel.deletePost(card) },
      onDismiss = { confirmDelete = null }
    )
  }
}

@Composable
private fun FeedPager(
  state: FeedState,
  chrome: FeedChromeState,
  onTabShown: (FeedTab) -> Unit,
  onRefresh: (FeedTab) -> Unit,
  onLoadMore: (FeedTab) -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onNotInterestedClick: (FeedCard) -> Unit,
  onCardsVisible: (FeedTab, Set<String>) -> Unit,
  onUndoNotInterested: (FeedCard) -> Unit = {},
  onManagePreferences: () -> Unit = {},
  onBlockAuthorClick: (FeedCard) -> Unit = {},
  onMyPostsClick: () -> Unit,
  modifier: Modifier = Modifier,
  onEditClick: (FeedEditTarget) -> Unit = {},
  onDeleteClick: (FeedCard) -> Unit = {},
  onOpenClick: (FeedCard) -> Unit = {},
  onReportClick: (FeedCard) -> Unit = {},
  onAuthorClick: (FeedAuthor) -> Unit = {},
  onVote: (FeedCard, List<Int>) -> Unit = { _, _ -> },
  onNotificationsClick: () -> Unit = {},
  onSearchClick: () -> Unit = {}
) {
  val tabs = state.tabs
  val pagerState = rememberPagerState(pageCount = { tabs.size })
  val scope = rememberCoroutineScope()

  // 换栏时顶栏和底栏都回来
  LaunchedEffect(pagerState.currentPage) { chrome.reset() }

  // 手指拖动和惯性滚动都算；consumed.y < 0 是内容往上走，也就是往更早的内容滚
  val scrollConnection = remember(chrome) {
    object : NestedScrollConnection {
      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        chrome.onScroll(-consumed.y)
        return Offset.Zero
      }
    }
  }

  // 状态栏那一条在外面留，顶栏收起时不跟着没（顶栏自己的 statusBars 内边距因此为 0）
  Column(modifier = modifier.windowInsetsPadding(WindowInsets.statusBars)) {
    AnimatedVisibility(
      visible = chrome.visible,
      enter = expandVertically(animationSpec = tween(FeedChromeState.SHOW_MILLIS), expandFrom = Alignment.Top),
      exit = shrinkVertically(animationSpec = tween(FeedChromeState.HIDE_MILLIS), shrinkTowards = Alignment.Top)
    ) {
      FeedTopBar(
        tabs = tabs,
        selectedIndex = pagerState.currentPage,
        onTabClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        onMyPostsClick = onMyPostsClick,
        onNotificationsClick = onNotificationsClick,
        onSearchClick = onSearchClick
      )
    }

    HorizontalPager(
      state = pagerState,
      key = { tabs[it].apiName },
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .nestedScroll(scrollConnection)
    ) { page ->
      val tab = tabs[page]
      LaunchedEffect(tab) { onTabShown(tab) }

      FeedTimeline(
        tab = tab,
        tabState = state.tab(tab),
        showIpRegion = state.config?.ipRegionVisible == true,
        mediaBaseUrl = state.config?.mediaBaseUrl,
        editWindowSeconds = (state.config?.limits ?: FeedLimits()).editWindowSeconds,
        onEditClick = onEditClick,
        onDeleteClick = onDeleteClick,
        onRefresh = { onRefresh(tab) },
        onLoadMore = { onLoadMore(tab) },
        onLikeClick = onLikeClick,
        onNotInterestedClick = onNotInterestedClick,
        hidden = state.hidden,
        onUndoNotInterested = onUndoNotInterested,
        onManagePreferences = onManagePreferences,
        onBlockAuthorClick = onBlockAuthorClick,
        isCurrentPage = pagerState.currentPage == page,
        onNearTopChanged = chrome::onNearTopChanged,
        onCardsVisible = { onCardsVisible(tab, it) },
        onOpenClick = onOpenClick,
        onReportClick = onReportClick,
        onAuthorClick = onAuthorClick,
        onVote = onVote
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
  mediaBaseUrl: String?,
  editWindowSeconds: Long,
  onEditClick: (FeedEditTarget) -> Unit,
  onDeleteClick: (FeedCard) -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onNotInterestedClick: (FeedCard) -> Unit,
  hidden: Set<String>,
  onUndoNotInterested: (FeedCard) -> Unit,
  onManagePreferences: () -> Unit,
  onBlockAuthorClick: (FeedCard) -> Unit,
  isCurrentPage: Boolean,
  onCardsVisible: (Set<String>) -> Unit,
  onNearTopChanged: (Boolean) -> Unit = {},
  onOpenClick: (FeedCard) -> Unit = {},
  onReportClick: (FeedCard) -> Unit = {},
  onAuthorClick: (FeedAuthor) -> Unit = {},
  onVote: (FeedCard, List<Int>) -> Unit = { _, _ -> }
) {
  val listState = rememberLazyListState()

  // 曝光：露出过半的卡片交给 ViewModel 计时（契约 6.1）；不是当前这一栏时报空
  val cardIds = tabState.cards.mapTo(HashSet()) { it.id }
  LaunchedEffect(listState, isCurrentPage, cardIds) {
    snapshotFlow {
      if (!isCurrentPage) {
        emptySet()
      } else {
        val info = listState.layoutInfo
        info.visibleItemsInfo
          .filter { visibleFraction(it.offset, it.size, info.viewportStartOffset, info.viewportEndOffset) >= FeedImpressionTracker.MIN_VISIBLE_FRACTION }
          .mapNotNullTo(HashSet()) { (it.key as? String)?.takeIf { key -> key in cardIds } }
      }
    }.distinctUntilChanged().collect { onCardsVisible(it) }
  }

  // 离顶 64 dp 内顶栏和底栏一律显示（第三节第 46 条）；只报当前这一栏
  val topPx = with(LocalDensity.current) { FeedChromeState.ALWAYS_SHOW_WITHIN.toPx() }
  LaunchedEffect(listState, isCurrentPage, topPx) {
    if (!isCurrentPage) return@LaunchedEffect
    snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= topPx }
      .distinctUntilChanged()
      .collect { onNearTopChanged(it) }
  }

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
          if (card.id in hidden) {
            FeedNotInterestedCard(
              onUndo = { onUndoNotInterested(card) },
              onManagePreferences = onManagePreferences,
              onUncomfortable = { onReportClick(card) },
              modifier = Modifier.animateItem()
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            return@items
          }
          // 自己的帖子：15 分钟内给「编辑」（第三节第 23 条），一直给「删除」
          val editTarget = if (card.viewer.isAuthor) {
            remember(card, editWindowSeconds) { FeedEditTarget.of(card)?.takeIf { it.isWithinWindow(System.currentTimeMillis(), editWindowSeconds) } }
          } else {
            null
          }
          FeedPostCard(
            card = card,
            showIpRegion = showIpRegion,
            onLikeClick = onLikeClick,
            onNotInterestedClick = onNotInterestedClick,
            modifier = Modifier.animateItem(),
            mediaBaseUrl = mediaBaseUrl,
            onEditClick = editTarget?.let { target -> { onEditClick(target) } },
            onDeleteClick = if (card.viewer.isAuthor) onDeleteClick else null,
            onOpen = onOpenClick,
            onReportClick = if (card.viewer.isAuthor) null else onReportClick,
            onAuthorClick = onAuthorClick,
            onVote = onVote,
            onBlockAuthorClick = if (card.viewer.isAuthor) null else onBlockAuthorClick
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
  onTabClick: (Int) -> Unit,
  onMyPostsClick: () -> Unit,
  onNotificationsClick: () -> Unit = {},
  onSearchClick: () -> Unit = {}
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.statusBars)
      .height(TOP_BAR_HEIGHT)
  ) {
    Row(
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.align(Alignment.Center)
    ) {
      tabs.forEachIndexed { index, tab ->
        FeedTopBarTab(
          label = stringResource(tab.label),
          selected = index == selectedIndex,
          onClick = { onTabClick(index) }
        )
      }
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = 4.dp)
    ) {
      // 附录 10「动态页顶部」：右边搜索 + 通知，搜索在铃铛左边
      Icon(
        imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_search_24),
        contentDescription = stringResource(R.string.TellomiFeedSearch__title),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .clip(CircleShape)
          .clickable(onClick = onSearchClick)
          .padding(10.dp)
          .size(24.dp)
      )

      Icon(
        painter = painterResource(R.drawable.symbol_bell_24),
        contentDescription = stringResource(R.string.TellomiFeedNotifications__title),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .clip(CircleShape)
          .clickable(onClick = onNotificationsClick)
          .padding(10.dp)
          .size(24.dp)
      )

      // 「我的动态」：一级导航有「我」之前先放这里（第三节第 17 条）
      Icon(
        painter = painterResource(R.drawable.symbol_person_24),
        contentDescription = stringResource(R.string.TellomiFeedMine__title),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .clip(CircleShape)
          .clickable(onClick = onMyPostsClick)
          .padding(10.dp)
          .size(24.dp)
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
      chrome = remember { FeedChromeState(0f, 0f) },
      state = FeedState(tabStates = mapOf(FeedTab.RECOMMEND to FeedTabState(loadedOnce = true))),
      onTabShown = {},
      onRefresh = {},
      onLoadMore = {},
      onLikeClick = {},
      onNotInterestedClick = {},
      onCardsVisible = { _, _ -> },
      onMyPostsClick = {},
      modifier = Modifier.fillMaxSize()
    )
  }
}

@Preview
@Composable
private fun FeedTopBarPreview() {
  Previews.Preview {
    FeedTopBar(tabs = FeedTab.entries, selectedIndex = 1, onTabClick = {}, onMyPostsClick = {})
  }
}
