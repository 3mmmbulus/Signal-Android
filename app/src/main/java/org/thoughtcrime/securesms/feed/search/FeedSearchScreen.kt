/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedAvatar
import org.thoughtcrime.securesms.feed.FeedPostCard
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import org.signal.core.ui.R as CoreUiR

object FeedSearchTestTags {
  const val FIELD = "feed_search_field"
  const val CANCEL = "feed_search_cancel"
}

private const val LOAD_MORE_THRESHOLD = 4

/**
 * 搜索页（ADR-0071 二·〇「搜索（重排）」）：输入框右边「取消」，下面一排范围；空的时候是最近看过的人、最近搜索。
 * 「正在热议」大陆等算法备案，「话题」范围等话题清单，这一版都不画。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedSearchScreen(
  state: FeedSearchState,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onCancel: () -> Unit,
  onSelectScope: (String) -> Unit,
  onRecentQuery: (String) -> Unit,
  onRemoveRecentQuery: (String) -> Unit,
  onClearRecentQueries: () -> Unit,
  onClearRecentPeople: () -> Unit,
  onOpenPerson: (FeedPersonSummary) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onLoadMore: () -> Unit,
  onRetry: () -> Unit,
  autoFocus: Boolean = true,
  onSaveClick: ((FeedCard) -> Unit)? = null,
  onNoticeShown: () -> Unit = {}
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val savedDone = stringResource(R.string.TellomiFeed__saved_done)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    snackbarHostState.showSnackbar(if (notice == FeedSearchState.Notice.SAVED) savedDone else failed)
  }

  Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = { Snackbars.Host(snackbarHostState) }) { padding ->
    Column(
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
        .statusBarsPadding()
        .imePadding()
    ) {
      SearchBar(query = state.query, onQueryChange = onQueryChange, onSubmit = onSubmit, onCancel = onCancel, autoFocus = autoFocus)
      ScopeRow(selected = state.scope, onSelect = onSelectScope)
      if (state.query.isBlank()) {
        EmptyQueryContent(
          state = state,
          onRecentQuery = onRecentQuery,
          onRemoveRecentQuery = onRemoveRecentQuery,
          onClearRecentQueries = onClearRecentQueries,
          onClearRecentPeople = onClearRecentPeople,
          onOpenPerson = onOpenPerson
        )
      } else {
        Results(
          state = state,
          onOpenPerson = onOpenPerson,
          onOpenPost = onOpenPost,
          onLikeClick = onLikeClick,
          onSaveClick = onSaveClick,
          onSeeAllPeople = { onSelectScope(FeedApi.SEARCH_USERS) },
          onLoadMore = onLoadMore,
          onRetry = onRetry
        )
      }
    }
  }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit, autoFocus: Boolean) {
  val focusRequester = remember { FocusRequester() }
  if (autoFocus) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .weight(1f)
        .height(40.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 12.dp)
    ) {
      Icon(
        imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_search_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )
      Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
          Text(
            text = stringResource(R.string.TellomiFeedSearch__hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
          )
        }
        BasicTextField(
          value = query,
          onValueChange = onQueryChange,
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
          modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(FeedSearchTestTags.FIELD)
        )
      }
      if (query.isNotEmpty()) {
        Icon(
          imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_x_24),
          contentDescription = stringResource(R.string.TellomiFeedSearch__clear),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { onQueryChange("") }
        )
      }
    }
    TextButton(onClick = onCancel, modifier = Modifier.testTag(FeedSearchTestTags.CANCEL)) {
      Text(stringResource(R.string.TellomiFeedSearch__cancel))
    }
  }
}

@Composable
private fun ScopeRow(selected: String, onSelect: (String) -> Unit) {
  val scopes = listOf(
    FeedApi.SEARCH_ALL to R.string.TellomiFeedSearch__scope_all,
    FeedApi.SEARCH_USERS to R.string.TellomiFeedSearch__scope_users,
    FeedApi.SEARCH_POSTS to R.string.TellomiFeedSearch__scope_posts
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
    scopes.forEach { (scope, label) ->
      FilterChip(selected = scope == selected, onClick = { onSelect(scope) }, label = { Text(stringResource(label)) })
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyQueryContent(
  state: FeedSearchState,
  onRecentQuery: (String) -> Unit,
  onRemoveRecentQuery: (String) -> Unit,
  onClearRecentQueries: () -> Unit,
  onClearRecentPeople: () -> Unit,
  onOpenPerson: (FeedPersonSummary) -> Unit
) {
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    if (state.recentPeople.isNotEmpty()) {
      item(key = "people-header") { SectionHeader(R.string.TellomiFeedSearch__recent_people, onClear = onClearRecentPeople) }
      item(key = "people") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
          items(state.recentPeople, key = { it.id }) { person ->
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.width(64.dp).clickable { onOpenPerson(person) }
            ) {
              FeedAvatar(author = person.asAuthor(), size = 52.dp)
              Text(
                text = person.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
              )
            }
          }
        }
      }
    }
    if (state.recentQueries.isNotEmpty()) {
      item(key = "queries-header") { SectionHeader(R.string.TellomiFeedSearch__recent_searches, onClear = onClearRecentQueries) }
      item(key = "queries") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
          state.recentQueries.forEach { query ->
            InputChip(
              selected = false,
              onClick = { onRecentQuery(query) },
              label = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
              trailingIcon = {
                Icon(
                  imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_x_24),
                  contentDescription = stringResource(R.string.TellomiFeedSearch__remove, query),
                  modifier = Modifier.size(16.dp).clickable { onRemoveRecentQuery(query) }
                )
              }
            )
          }
        }
      }
    }
    if (state.recentPeople.isEmpty() && state.recentQueries.isEmpty()) {
      item(key = "hint") {
        Text(
          text = stringResource(R.string.TellomiFeedSearch__empty_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(32.dp)
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(title: Int, onClear: (() -> Unit)? = null, action: Int = R.string.TellomiFeedSearch__clear_all) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp)
  ) {
    Text(
      text = stringResource(title),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f)
    )
    if (onClear != null) {
      TextButton(onClick = onClear) { Text(stringResource(action)) }
    }
  }
}

@Composable
private fun Results(
  state: FeedSearchState,
  onOpenPerson: (FeedPersonSummary) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onSaveClick: ((FeedCard) -> Unit)?,
  onSeeAllPeople: () -> Unit,
  onLoadMore: () -> Unit,
  onRetry: () -> Unit
) {
  val results = state.results
  Box(modifier = Modifier.fillMaxSize()) {
    when {
      results == null && state.failed -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.align(Alignment.Center).padding(24.dp)
      ) {
        Text(stringResource(R.string.TellomiFeedSearch__failed), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      results == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      results.users.isEmpty() && results.posts.isEmpty() -> Text(
        text = if (state.loading) "" else stringResource(R.string.TellomiFeedSearch__no_results, results.query),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.Center).padding(32.dp)
      )
      else -> ResultList(
        state = state,
        results = results,
        onOpenPerson = onOpenPerson,
        onOpenPost = onOpenPost,
        onLikeClick = onLikeClick,
        onSaveClick = onSaveClick,
        onSeeAllPeople = onSeeAllPeople,
        onLoadMore = onLoadMore
      )
    }
    if (state.loading && results != null) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(24.dp))
    }
  }
}

@Composable
private fun ResultList(
  state: FeedSearchState,
  results: FeedSearchResults,
  onOpenPerson: (FeedPersonSummary) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onSaveClick: ((FeedCard) -> Unit)?,
  onSeeAllPeople: () -> Unit,
  onLoadMore: () -> Unit
) {
  val listState = rememberLazyListState()
  val nearEnd by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
    }
  }
  LaunchedEffect(nearEnd, results.cursor) {
    if (nearEnd && results.cursor != null) onLoadMore()
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    if (results.users.isNotEmpty()) {
      if (results.scope == FeedApi.SEARCH_ALL) {
        item(key = "users-header") {
          SectionHeader(R.string.TellomiFeedSearch__people, onClear = onSeeAllPeople, action = R.string.TellomiFeedSearch__see_all)
        }
      }
      items(results.users, key = { "u-${it.id}" }) { user -> UserRow(user = user, onClick = { onOpenPerson(user.asPerson()) }) }
    }
    if (results.posts.isNotEmpty()) {
      if (results.scope == FeedApi.SEARCH_ALL) {
        item(key = "posts-header") { SectionHeader(R.string.TellomiFeedSearch__posts) }
      }
      items(results.posts, key = { "p-${it.id}" }) { card ->
        FeedPostCard(
          card = card,
          showIpRegion = state.showIpRegion,
          onLikeClick = onLikeClick,
          onNotInterestedClick = null,
          mediaBaseUrl = state.mediaBaseUrl,
          onOpen = onOpenPost,
          onSaveClick = onSaveClick,
          onAuthorClick = { author ->
            onOpenPerson(FeedPersonSummary(id = author.profileId, handle = author.handle, displayName = author.displayName, avatarKey = author.avatarKey))
          }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      }
    }
    if (state.loadingMore) {
      item(key = "more") {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
      }
    }
  }
}

@Composable
private fun UserRow(user: FeedSearchUser, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
  ) {
    FeedAvatar(author = user.asPerson().asAuthor(), size = 44.dp)
    Column(modifier = Modifier.weight(1f)) {
      Text(text = user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      user.handle?.let {
        Text(
          text = "@$it",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

private fun FeedPersonSummary.asAuthor(): FeedAuthor = FeedAuthor(profileId = id, displayName = displayName, handle = handle, avatarKey = avatarKey)
