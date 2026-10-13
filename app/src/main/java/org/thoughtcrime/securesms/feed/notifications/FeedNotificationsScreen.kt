/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedAvatar
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

object FeedNotificationsTestTags {
  const val ROW_MORE = "feed_notifications_row_more"
}

private const val LOAD_MORE_THRESHOLD = 5

/**
 * 通知页：三栏（互动 / 新关注 / 系统），栏内按「今天 / 七天内 / 更早」分组，同一条帖子的赞合成一行。
 * 每行右边「···」里是「删除」，互动栏的还有「这条帖子不再通知（全部 / 只关赞）」（ADR 写的是左滑，手势随后补）。
 */
@Composable
fun FeedNotificationsScreen(
  state: FeedNotificationsState,
  onBack: () -> Unit,
  onSelectTab: (String) -> Unit,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
  onOpen: (FeedNotificationRow) -> Unit,
  onDelete: (FeedNotificationRow) -> Unit,
  onMute: (FeedNotificationRow, Boolean) -> Unit,
  onNoticeShown: () -> Unit,
  now: () -> Instant = Instant::now,
  zone: ZoneId = ZoneId.systemDefault()
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val deleted = stringResource(R.string.TellomiFeedNotifications__deleted)
  val muted = stringResource(R.string.TellomiFeedNotifications__muted)
  val mutedLikes = stringResource(R.string.TellomiFeedNotifications__muted_likes)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  val postGone = stringResource(R.string.TellomiFeedNotifications__post_gone)
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    snackbarHostState.showSnackbar(
      when (notice) {
        FeedNotificationsState.Notice.DELETED -> deleted
        FeedNotificationsState.Notice.MUTED -> muted
        FeedNotificationsState.Notice.MUTED_LIKES -> mutedLikes
        FeedNotificationsState.Notice.POST_GONE -> postGone
        FeedNotificationsState.Notice.FAILED -> failed
      }
    )
  }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedNotifications__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      val tabs = FeedNotificationsViewModel.TABS
      SecondaryTabRow(selectedTabIndex = tabs.indexOf(state.selectedTab).coerceAtLeast(0)) {
        tabs.forEach { tab ->
          Tab(
            selected = tab == state.selectedTab,
            onClick = { onSelectTab(tab) },
            text = { TabLabel(tab = tab, unread = state.unread.of(tab)) }
          )
        }
      }
      TabContent(
        state = state,
        onRetry = onRetry,
        onLoadMore = onLoadMore,
        onOpen = onOpen,
        onDelete = onDelete,
        onMute = onMute,
        now = now,
        zone = zone
      )
    }
  }
}

@Composable
private fun TabLabel(tab: String, unread: Int) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      stringResource(
        when (tab) {
          FeedApi.NOTIFICATIONS_FOLLOWS -> R.string.TellomiFeedNotifications__tab_follows
          FeedApi.NOTIFICATIONS_SYSTEM -> R.string.TellomiFeedNotifications__tab_system
          else -> R.string.TellomiFeedNotifications__tab_interactions
        }
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false)
    )
    if (unread > 0) {
      Badge { Text(if (unread > 99) "99+" else unread.toString()) }
    }
  }
}

@Composable
private fun TabContent(
  state: FeedNotificationsState,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
  onOpen: (FeedNotificationRow) -> Unit,
  onDelete: (FeedNotificationRow) -> Unit,
  onMute: (FeedNotificationRow, Boolean) -> Unit,
  now: () -> Instant,
  zone: ZoneId
) {
  val tab = state.tab(state.selectedTab)
  val sections = remember(tab.items) { groupNotifications(tab.items, now(), zone) }

  Box(modifier = Modifier.fillMaxSize()) {
    when {
      tab.items.isEmpty() && (tab.loading || !tab.loadedOnce) && !tab.failed -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      tab.items.isEmpty() && tab.failed -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.align(Alignment.Center).padding(24.dp)
      ) {
        Text(stringResource(R.string.TellomiFeedNotifications__load_failed), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      tab.items.isEmpty() -> Text(
        text = stringResource(
          when (state.selectedTab) {
            FeedApi.NOTIFICATIONS_FOLLOWS -> R.string.TellomiFeedNotifications__empty_follows
            FeedApi.NOTIFICATIONS_SYSTEM -> R.string.TellomiFeedNotifications__empty_system
            else -> R.string.TellomiFeedNotifications__empty_interactions
          }
        ),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.Center).padding(32.dp)
      )
      else -> {
        val listState = rememberLazyListState()
        val nearEnd by remember {
          derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
          }
        }
        LaunchedEffect(nearEnd, tab.hasMore) {
          if (nearEnd && tab.hasMore) onLoadMore()
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
          sections.forEach { section ->
            item(key = "header-${section.bucket}") { SectionHeader(section.bucket) }
            items(section.rows, key = { it.key }) { row ->
              NotificationRow(
                row = row,
                people = state.people,
                onOpen = { onOpen(row) },
                onDelete = { onDelete(row) },
                onMute = { likesOnly -> onMute(row, likesOnly) }
              )
            }
          }
          if (tab.loadingMore) {
            item(key = "more") {
              Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(bucket: FeedNotificationBucket) {
  Text(
    text = stringResource(
      when (bucket) {
        FeedNotificationBucket.TODAY -> R.string.TellomiFeedNotifications__today
        FeedNotificationBucket.THIS_WEEK -> R.string.TellomiFeedNotifications__this_week
        FeedNotificationBucket.EARLIER -> R.string.TellomiFeedNotifications__earlier
      }
    ),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
  )
}

@Composable
private fun NotificationRow(
  row: FeedNotificationRow,
  people: Map<String, FeedPersonSummary>,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
  onMute: (Boolean) -> Unit
) {
  val context = LocalContext.current
  val actor = row.actorIds.firstOrNull()?.let { people[it] }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpen)
      .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
  ) {
    if (row.kind == FeedNotification.KIND_SYSTEM && row.systemType != FeedNotification.SYSTEM_CLOSE_PERSON_REQUEST) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
      ) {
        Icon(
          imageVector = ImageVector.vectorResource(R.drawable.symbol_bell_24),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
      }
    } else {
      val person = actor ?: row.profileId?.let { people[it] }
      FeedAvatar(author = person?.let { FeedAuthor(profileId = it.id, displayName = it.displayName, handle = it.handle, avatarKey = it.avatarKey) }, size = 44.dp)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = notificationText(row, people), style = MaterialTheme.typography.bodyLarge)
      Text(
        text = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), row.createdAt.toEpochMilli()).first,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (row.unread) {
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
    RowMenu(canMute = row.contentId != null && row.kind != FeedNotification.KIND_SYSTEM, onDelete = onDelete, onMute = onMute)
  }
}

@Composable
private fun RowMenu(canMute: Boolean, onDelete: () -> Unit, onMute: (Boolean) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag(FeedNotificationsTestTags.ROW_MORE)) {
      Icon(
        imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_more_vertical_24),
        contentDescription = stringResource(R.string.TellomiFeed__more),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (canMute) {
        DropdownMenuItem(
          text = { Text(stringResource(R.string.TellomiFeedNotifications__mute_all)) },
          onClick = {
            open = false
            onMute(false)
          }
        )
        DropdownMenuItem(
          text = { Text(stringResource(R.string.TellomiFeedNotifications__mute_likes)) },
          onClick = {
            open = false
            onMute(true)
          }
        )
      }
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedNotifications__delete)) },
        onClick = {
          open = false
          onDelete()
        }
      )
    }
  }
}

@Composable
private fun notificationText(row: FeedNotificationRow, people: Map<String, FeedPersonSummary>): String {
  val someone = stringResource(R.string.TellomiFeedNotifications__someone)
  val name = row.actorIds.firstOrNull()?.let { people[it]?.displayName } ?: someone
  return when (row.kind) {
    FeedNotification.KIND_LIKE -> if (row.actorIds.size > 1) {
      val others = row.actorIds.size - 1
      pluralStringResource(R.plurals.TellomiFeedNotifications__like_many, others, name, others)
    } else {
      stringResource(R.string.TellomiFeedNotifications__like, name)
    }
    FeedNotification.KIND_COMMENT -> stringResource(if (row.held) R.string.TellomiFeedNotifications__comment_held else R.string.TellomiFeedNotifications__comment, name)
    FeedNotification.KIND_REPLY -> stringResource(if (row.held) R.string.TellomiFeedNotifications__reply_held else R.string.TellomiFeedNotifications__reply, name)
    FeedNotification.KIND_FOLLOW -> stringResource(R.string.TellomiFeedNotifications__follow, name)
    FeedNotification.KIND_FOLLOW_REQUEST -> stringResource(R.string.TellomiFeedNotifications__follow_request, name)
    FeedNotification.KIND_SYSTEM -> when (row.systemType) {
      FeedNotification.SYSTEM_REJECTED -> stringResource(R.string.TellomiFeedNotifications__system_rejected)
      FeedNotification.SYSTEM_EDIT_REJECTED -> stringResource(R.string.TellomiFeedNotifications__system_edit_rejected)
      FeedNotification.SYSTEM_TAKEN_DOWN -> stringResource(R.string.TellomiFeedNotifications__system_taken_down)
      FeedNotification.SYSTEM_APPEAL_UPHELD -> stringResource(R.string.TellomiFeedNotifications__system_appeal_upheld)
      FeedNotification.SYSTEM_APPEAL_OVERTURNED -> stringResource(R.string.TellomiFeedNotifications__system_appeal_overturned)
      FeedNotification.SYSTEM_CLOSE_PERSON_REQUEST -> stringResource(
        R.string.TellomiFeedNotifications__system_close_person,
        row.profileId?.let { people[it]?.displayName } ?: someone
      )
      else -> stringResource(R.string.TellomiFeedNotifications__system_other)
    }
    else -> stringResource(R.string.TellomiFeedNotifications__system_other)
  }
}
