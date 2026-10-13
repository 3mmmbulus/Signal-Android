/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.closepeople

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedAvatar
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedClosePersonEntry
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import org.signal.core.ui.R as CoreUiR

object FeedClosePeopleTestTags {
  const val SEARCH = "feed_close_people_search"
  const val ADD = "feed_close_people_add"
  const val REMOVE = "feed_close_people_remove"
  const val ACCEPT = "feed_close_people_accept"
  const val DECLINE = "feed_close_people_decline"
}

/**
 * 「关心的人」页：上面是我设的（最多 3 个，标「等对方同意」/「已同意」），没满时可以搜人添加；
 * 下面是设了我的人，同意 / 拒绝，同意过的可以撤回。点人进他的主页。
 */
@Composable
fun FeedClosePeopleScreen(
  state: FeedClosePeopleState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onQueryChanged: (String) -> Unit,
  onAdd: (FeedSearchUser) -> Unit,
  onRemove: (FeedClosePersonEntry) -> Unit,
  onAnswer: (FeedClosePersonEntry, Boolean) -> Unit,
  onOpenProfile: (String) -> Unit,
  onNoticeShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val messages = mapOf(
    FeedClosePeopleState.Notice.ADDED to stringResource(R.string.TellomiFeedClosePeople__added),
    FeedClosePeopleState.Notice.FULL to stringResource(R.string.TellomiFeedClosePeople__full, FeedClosePersonEntry.MAX),
    FeedClosePeopleState.Notice.PROFILE_REQUIRED to stringResource(R.string.TellomiFeedNotificationSettings__profile_required),
    FeedClosePeopleState.Notice.NOT_ALLOWED to stringResource(R.string.TellomiFeedClosePeople__not_allowed),
    FeedClosePeopleState.Notice.FAILED to stringResource(R.string.TellomiFeed__action_failed)
  )
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    snackbarHostState.showSnackbar(messages.getValue(notice))
  }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedClosePeople__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      when {
        !state.loaded && state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        !state.loaded && state.failed -> Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.align(Alignment.Center).padding(24.dp)
        ) {
          Text(stringResource(R.string.TellomiFeed__load_failed), style = MaterialTheme.typography.bodyLarge)
          TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
        }
        else -> LazyColumn(modifier = Modifier.fillMaxSize().imePadding()) {
          item(key = "explain") {
            Text(
              text = stringResource(R.string.TellomiFeedClosePeople__explain, FeedClosePersonEntry.MAX),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
          }

          item(key = "mine_header") {
            Texts.SectionHeader(stringResource(R.string.TellomiFeedClosePeople__mine, state.mine.size, FeedClosePersonEntry.MAX))
          }
          if (state.mine.isEmpty()) {
            item(key = "mine_empty") { EmptyLine(stringResource(R.string.TellomiFeedClosePeople__mine_empty)) }
          }
          items(state.mine, key = { "mine_${it.id}" }) { entry ->
            PersonRow(
              name = entry.displayName,
              secondary = stringResource(if (entry.accepted) R.string.TellomiFeedClosePeople__accepted else R.string.TellomiFeedClosePeople__pending),
              author = entry.toAuthor(),
              onClick = { onOpenProfile(entry.id) }
            ) {
              OutlinedButton(onClick = { onRemove(entry) }, modifier = Modifier.testTag(FeedClosePeopleTestTags.REMOVE)) {
                Text(stringResource(R.string.TellomiFeedClosePeople__remove))
              }
            }
          }

          if (state.canAdd) {
            item(key = "search") {
              OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.TellomiFeedClosePeople__search_hint)) },
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp, vertical = 8.dp)
                  .testTag(FeedClosePeopleTestTags.SEARCH)
              )
            }
            if (state.searching) {
              item(key = "searching") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator()
                }
              }
            } else if (state.query.isNotBlank() && state.addableResults.isEmpty()) {
              item(key = "no_results") { EmptyLine(stringResource(R.string.TellomiFeedClosePeople__no_results)) }
            }
            items(state.addableResults, key = { "result_${it.id}" }) { user ->
              PersonRow(
                name = user.displayName,
                secondary = user.handle?.let { "@$it" },
                author = FeedAuthor(profileId = user.id, displayName = user.displayName, handle = user.handle, avatarKey = user.avatarKey),
                onClick = { onOpenProfile(user.id) }
              ) {
                Button(onClick = { onAdd(user) }, enabled = !state.adding, modifier = Modifier.testTag(FeedClosePeopleTestTags.ADD)) {
                  Text(stringResource(R.string.TellomiFeedClosePeople__add))
                }
              }
            }
          }

          item(key = "requests_divider") { Dividers.Default() }
          item(key = "requests_header") {
            Texts.SectionHeader(stringResource(R.string.TellomiFeedClosePeople__requests))
          }
          if (state.requests.isEmpty()) {
            item(key = "requests_empty") { EmptyLine(stringResource(R.string.TellomiFeedClosePeople__requests_empty)) }
          }
          items(state.requests, key = { "request_${it.id}" }) { entry ->
            PersonRow(
              name = entry.displayName,
              secondary = stringResource(if (entry.accepted) R.string.TellomiFeedClosePeople__request_accepted else R.string.TellomiFeedClosePeople__request_pending),
              author = entry.toAuthor(),
              onClick = { onOpenProfile(entry.id) }
            ) {
              if (entry.accepted) {
                OutlinedButton(onClick = { onAnswer(entry, false) }, modifier = Modifier.testTag(FeedClosePeopleTestTags.DECLINE)) {
                  Text(stringResource(R.string.TellomiFeedClosePeople__withdraw))
                }
              } else {
                OutlinedButton(onClick = { onAnswer(entry, false) }, modifier = Modifier.testTag(FeedClosePeopleTestTags.DECLINE)) {
                  Text(stringResource(R.string.TellomiFeedPeople__decline))
                }
                Button(onClick = { onAnswer(entry, true) }, modifier = Modifier.testTag(FeedClosePeopleTestTags.ACCEPT)) {
                  Text(stringResource(R.string.TellomiFeedClosePeople__accept))
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun FeedClosePersonEntry.toAuthor(): FeedAuthor = FeedAuthor(profileId = id, displayName = displayName, avatarKey = avatarKey)

@Composable
private fun EmptyLine(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
  )
}

@Composable
private fun PersonRow(
  name: String,
  secondary: String?,
  author: FeedAuthor,
  onClick: () -> Unit,
  actions: @Composable () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 12.dp)
  ) {
    FeedAvatar(author = author, size = 44.dp)
    Column(modifier = Modifier.weight(1f)) {
      Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    actions()
  }
}
