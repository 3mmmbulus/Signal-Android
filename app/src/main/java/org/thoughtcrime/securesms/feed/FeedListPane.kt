/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R

private val TOP_BAR_HEIGHT = 48.dp
private val INDICATOR_WIDTH = 20.dp

/**
 * 「动态」标签的列表面板（M11 P1，`dynamic-feed.md` 第四节 L2-1）。
 *
 * 顶栏只有一行：「推荐 / 关注」居中（附录 10「顶栏一行」，第三节第 45 条去掉了「有什么新鲜事」）。两栏左右滑切换，
 * 滑块和内容一起跟手（`10-feed-prototype-log.md` 第 435 行）。
 *
 * 这一刀只有框架：信息流、卡片、发布按钮、搜索与通知入口随后面的 PR 接上（客户端接口契约
 * `docs/adr/0071/client-api-2026-10.md`）。没接上的入口不画，不放点了没反应的按钮。
 */
@Composable
fun FeedListPane(
  modifier: Modifier = Modifier,
  tabs: List<FeedTab> = FeedTab.entries
) {
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
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) { page ->
      FeedEmptyState(tab = tabs[page])
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
private fun FeedListPanePreview() {
  Previews.Preview {
    FeedListPane(modifier = Modifier.fillMaxSize())
  }
}

@Preview
@Composable
private fun FeedTopBarPreview() {
  Previews.Preview {
    FeedTopBar(tabs = FeedTab.entries, selectedIndex = 1, onTabClick = {})
  }
}
