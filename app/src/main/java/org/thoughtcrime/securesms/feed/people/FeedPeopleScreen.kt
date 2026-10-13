/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.signal.core.ui.R as CoreUiR

object FeedPeopleTestTags {
  const val APPROVE = "feed_people_approve"
  const val DECLINE = "feed_people_decline"
  const val UNBLOCK = "feed_people_unblock"
}

/**
 * 关注请求 / 屏蔽列表。一行：头像、名字、ID，右边是「同意」「拒绝」或「取消屏蔽」；点人进他的主页。
 * 屏蔽来源、话题的那几行没有主页，只写类型。
 */
@Composable
fun FeedPeopleScreen(
  state: FeedPeopleState,
  mode: FeedPeopleMode,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onApprove: (FeedPeopleRow) -> Unit,
  onDecline: (FeedPeopleRow) -> Unit,
  onUnblock: (FeedPeopleRow) -> Unit,
  onOpenProfile: (FeedPersonSummary) -> Unit,
  onNoticeShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val approved = stringResource(R.string.TellomiFeedPeople__approved)
  val declined = stringResource(R.string.TellomiFeedPeople__declined)
  val unblocked = stringResource(R.string.TellomiFeedPage__unblocked_done)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    snackbarHostState.showSnackbar(
      when (notice) {
        FeedPeopleState.Notice.APPROVED -> approved
        FeedPeopleState.Notice.DECLINED -> declined
        FeedPeopleState.Notice.UNBLOCKED -> unblocked
        FeedPeopleState.Notice.FAILED -> failed
      }
    )
  }

  Scaffolds.Settings(
    title = stringResource(
      when (mode) {
        FeedPeopleMode.FOLLOW_REQUESTS -> R.string.TellomiFeedPeople__requests_title
        FeedPeopleMode.BLOCKED -> R.string.TellomiFeedPeople__blocked_title
      }
    ),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      when {
        state.loading && state.rows.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        state.failed && state.rows.isEmpty() -> Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.align(Alignment.Center).padding(24.dp)
        ) {
          Text(stringResource(R.string.TellomiFeedPeople__load_failed), style = MaterialTheme.typography.bodyLarge)
          TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
        }
        state.rows.isEmpty() -> Text(
          text = stringResource(
            when (mode) {
              FeedPeopleMode.FOLLOW_REQUESTS -> R.string.TellomiFeedPeople__requests_empty
              FeedPeopleMode.BLOCKED -> R.string.TellomiFeedPeople__blocked_empty
            }
          ),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.align(Alignment.Center).padding(24.dp)
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
          if (mode == FeedPeopleMode.BLOCKED) {
            item(key = "explain") {
              Text(
                text = stringResource(R.string.TellomiFeedPeople__blocked_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
              )
            }
          }
          items(state.rows, key = { it.key }) { row ->
            PersonRow(
              row = row,
              mode = mode,
              onApprove = { onApprove(row) },
              onDecline = { onDecline(row) },
              onUnblock = { onUnblock(row) },
              onOpenProfile = onOpenProfile
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PersonRow(
  row: FeedPeopleRow,
  mode: FeedPeopleMode,
  onApprove: () -> Unit,
  onDecline: () -> Unit,
  onUnblock: () -> Unit,
  onOpenProfile: (FeedPersonSummary) -> Unit
) {
  val person = row.person
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .then(if (person != null) Modifier.clickable { onOpenProfile(person) } else Modifier)
      .padding(horizontal = 24.dp, vertical = 12.dp)
  ) {
    FeedAvatar(author = person?.let { FeedAuthor(profileId = it.id, displayName = it.displayName, handle = it.handle, avatarKey = it.avatarKey) }, size = 44.dp)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = person?.displayName ?: blockLabel(row),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      val secondary = person?.handle?.let { "@$it" }
      if (secondary != null) {
        Text(
          text = secondary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    when (mode) {
      FeedPeopleMode.FOLLOW_REQUESTS -> {
        OutlinedButton(onClick = onDecline, modifier = Modifier.testTag(FeedPeopleTestTags.DECLINE)) {
          Text(stringResource(R.string.TellomiFeedPeople__decline))
        }
        Button(onClick = onApprove, modifier = Modifier.testTag(FeedPeopleTestTags.APPROVE)) {
          Text(stringResource(R.string.TellomiFeedPeople__approve))
        }
      }
      FeedPeopleMode.BLOCKED -> OutlinedButton(onClick = onUnblock, modifier = Modifier.testTag(FeedPeopleTestTags.UNBLOCK)) {
        Text(stringResource(R.string.TellomiFeedPage__unblock))
      }
    }
  }
}

@Composable
private fun blockLabel(row: FeedPeopleRow): String {
  val id = row.block?.id.orEmpty()
  return when (row.block?.type) {
    FeedApi.TARGET_SOURCE -> stringResource(R.string.TellomiFeedPeople__blocked_source, id)
    FeedApi.TARGET_TOPIC -> stringResource(R.string.TellomiFeedPeople__blocked_topic, id)
    else -> stringResource(R.string.TellomiFeedPeople__blocked_other, id)
  }
}
