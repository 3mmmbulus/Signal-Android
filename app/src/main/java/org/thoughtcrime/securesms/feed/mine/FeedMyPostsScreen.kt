/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.mine

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.compose.FeedEditTarget
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.parseInstantMillis
import org.thoughtcrime.securesms.feed.takeCodePoints
import org.thoughtcrime.securesms.util.DateUtils
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

object FeedMyPostsTestTags {
  const val MORE = "feed_my_posts_more"
  const val PEOPLE = "feed_my_posts_people"
  const val APPEAL_TEXT = "feed_my_posts_appeal_text"
}

/**
 * 「我的动态」列表。每条显示状态（审核中 / 未通过 / 已发布 / 已撤回），未通过的写明原因（第三节第 24 条），「···」里能删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedMyPostsScreen(
  state: FeedMyPostsState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onDelete: (FeedMyPost) -> Unit,
  onDeleteFailureShown: () -> Unit,
  onEdit: (FeedEditTarget) -> Unit = {},
  onEditNoticeShown: () -> Unit = {},
  onProfileClick: () -> Unit = {},
  onFollowRequestsClick: () -> Unit = {},
  onBlockedClick: () -> Unit = {},
  onFollowedTopicsClick: () -> Unit = {},
  onSettingsClick: () -> Unit = {},
  onAppeal: (FeedMyPost, String) -> Unit = { _, _ -> },
  onAppealNoticeShown: () -> Unit = {},
  nowMillis: () -> Long = System::currentTimeMillis
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val savedPending = stringResource(R.string.TellomiFeedCompose__edit_saved_pending)
  val saved = stringResource(R.string.TellomiFeedCompose__edit_saved)
  LaunchedEffect(state.editNotice) {
    val notice = state.editNotice ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (notice == FeedMyPost.STATE_PENDING) savedPending else saved)
    onEditNoticeShown()
  }
  val deleteFailed = stringResource(R.string.TellomiFeedMine__delete_failed)
  LaunchedEffect(state.deleteFailed) {
    if (state.deleteFailed) {
      snackbarHostState.showSnackbar(deleteFailed)
      onDeleteFailureShown()
    }
  }

  val appealSent = stringResource(R.string.TellomiFeedMine__appeal_sent)
  val appealFailed = stringResource(R.string.TellomiFeed__action_failed)
  LaunchedEffect(state.appealNotice) {
    val notice = state.appealNotice ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (notice == AppealResult.FAILED) appealFailed else appealSent)
    onAppealNoticeShown()
  }

  var confirmDelete by remember { mutableStateOf<FeedMyPost?>(null) }
  var appealing by remember { mutableStateOf<FeedMyPost?>(null) }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedMine__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) },
    actions = {
      // 公开主页的名字和简介（主页页面做好后挪过去）
      TextButton(onClick = onProfileClick) { Text(stringResource(R.string.TellomiFeedProfile__title)) }
      PeopleMenu(onFollowRequestsClick = onFollowRequestsClick, onBlockedClick = onBlockedClick, onFollowedTopicsClick = onFollowedTopicsClick, onSettingsClick = onSettingsClick)
    }
  ) { padding ->
    PullToRefreshBox(
      isRefreshing = state.refreshing,
      onRefresh = onRefresh,
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
    ) {
      when {
        state.posts.isEmpty() && (state.loading || !state.loadedOnce) -> Centered { CircularProgressIndicator() }
        state.posts.isEmpty() && state.failed -> Centered {
          Text(text = stringResource(R.string.TellomiFeed__load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
          TextButton(onClick = onRefresh) { Text(stringResource(R.string.TellomiFeed__retry)) }
        }
        state.posts.isEmpty() -> Centered {
          Text(
            text = stringResource(R.string.TellomiFeedMine__empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
        }
        else -> MyPostsList(
          state = state,
          onLoadMore = onLoadMore,
          onDeleteClick = { confirmDelete = it },
          onEditClick = onEdit,
          onAppealClick = { appealing = it },
          nowMillis = nowMillis
        )
      }
    }
  }

  confirmDelete?.let { post ->
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedMine__delete_title),
      body = stringResource(R.string.TellomiFeedMine__delete_body),
      confirm = stringResource(R.string.TellomiFeedMine__delete),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = { onDelete(post) },
      onDismiss = { confirmDelete = null }
    )
  }

  appealing?.let { post ->
    AppealDialog(
      onSend = { text ->
        appealing = null
        onAppeal(post, text)
      },
      onDismiss = { appealing = null }
    )
  }
}

/** 申诉只能一次（R-2.1），所以要写理由，发之前说清楚。 */
@Composable
private fun AppealDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.TellomiFeedMine__appeal_title)) },
    text = {
      Column {
        Text(
          text = stringResource(R.string.TellomiFeedMine__appeal_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it.takeCodePoints(FeedMyPost.MAX_APPEAL_CHARS) },
          placeholder = { Text(stringResource(R.string.TellomiFeedMine__appeal_hint)) },
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(FeedMyPostsTestTags.APPEAL_TEXT)
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.TellomiFeedMine__appeal_send)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    }
  )
}

@Composable
private fun MyPostsList(
  state: FeedMyPostsState,
  onLoadMore: () -> Unit,
  onDeleteClick: (FeedMyPost) -> Unit,
  onEditClick: (FeedEditTarget) -> Unit,
  onAppealClick: (FeedMyPost) -> Unit,
  nowMillis: () -> Long
) {
  val listState = rememberLazyListState()
  val nearEnd by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
      info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
    }
  }
  LaunchedEffect(nearEnd, state.nextBefore) {
    if (nearEnd && state.nextBefore != null) onLoadMore()
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    items(items = state.posts, key = { it.id }) { post ->
      // 15 分钟内、没被拒的才给「编辑」（第三节第 23 条）；最后由引擎判
      val editTarget = remember(post, state.editWindowSeconds) {
        FeedEditTarget.of(post)?.takeIf { it.isWithinWindow(nowMillis(), state.editWindowSeconds) }
      }
      MyPostRow(post = post, editTarget = editTarget, onDeleteClick = onDeleteClick, onEditClick = onEditClick, onAppealClick = onAppealClick)
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    if (state.nextBefore != null) {
      item(key = "loading-more") {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }
    }
  }
}

@Composable
private fun MyPostRow(
  post: FeedMyPost,
  editTarget: FeedEditTarget?,
  onDeleteClick: (FeedMyPost) -> Unit,
  onEditClick: (FeedEditTarget) -> Unit,
  onAppealClick: (FeedMyPost) -> Unit
) {
  val context = LocalContext.current
  var menuOpen by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StateChip(state = post.state)

      val created = remember(post.createdAt) { post.createdAt?.let { parseInstantMillis(it) } }
      if (created != null) {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), created).first,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Spacer(modifier = Modifier.weight(1f))

      Box {
        Icon(
          imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_more_vertical_24),
          contentDescription = stringResource(R.string.TellomiFeed__more),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .testTag(FeedMyPostsTestTags.MORE)
            .clip(CircleShape)
            .clickable { menuOpen = true }
            .padding(10.dp)
            .size(20.dp)
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          if (editTarget != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.TellomiFeedMine__edit)) },
              leadingIcon = { Icon(imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_edit_24), contentDescription = null) },
              onClick = {
                menuOpen = false
                onEditClick(editTarget)
              }
            )
          }
          if (post.state == FeedMyPost.STATE_REJECTED && post.appealable && post.appealState == null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.TellomiFeedMine__appeal)) },
              onClick = {
                menuOpen = false
                onAppealClick(post)
              }
            )
          }
          DropdownMenuItem(
            text = { Text(stringResource(R.string.TellomiFeedMine__delete)) },
            leadingIcon = { Icon(imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_trash_24), contentDescription = null) },
            onClick = {
              menuOpen = false
              onDeleteClick(post)
            }
          )
        }
      }
    }

    val title = post.title
    if (!title.isNullOrBlank()) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, end = 12.dp)
      )
    }

    val text = post.text
    if (!text.isNullOrBlank()) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (post.kind == FeedCard.KIND_ARTICLE) 4 else 8,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp, end = 12.dp)
      )
    }

    when (post.state) {
      FeedMyPost.STATE_PENDING -> Note(text = stringResource(R.string.TellomiFeedMine__pending_hint), isError = false)
      FeedMyPost.STATE_REJECTED -> {
        val reasons = post.rejectionReasons.map { stringResource(rejectionReasonLabel(it)) }.distinct()
          .ifEmpty { listOf(stringResource(R.string.TellomiFeedMine__reason_guidelines)) }
        Note(text = stringResource(R.string.TellomiFeedMine__rejected_because, reasons.joinToString(if (Locale.getDefault().language == "zh") "、" else ", ")), isError = true)
        when (post.appealState) {
          FeedMyPost.APPEAL_RECEIVED -> Note(text = stringResource(R.string.TellomiFeedMine__appeal_received), isError = false)
          FeedMyPost.APPEAL_UPHELD -> Note(text = stringResource(R.string.TellomiFeedMine__appeal_upheld), isError = false)
        }
      }
    }
  }
}

@Composable
private fun StateChip(state: String) {
  val (label, background, content) = when (state) {
    FeedMyPost.STATE_PENDING -> Triple(R.string.TellomiFeedMine__state_pending, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    FeedMyPost.STATE_REJECTED -> Triple(R.string.TellomiFeedMine__state_rejected, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    FeedMyPost.STATE_WITHDRAWN -> Triple(R.string.TellomiFeedMine__state_withdrawn, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    else -> Triple(R.string.TellomiFeedMine__state_published, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Text(
    text = stringResource(label),
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Medium,
    color = content,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(background)
      .padding(horizontal = 8.dp, vertical = 2.dp)
  )
}

@Composable
private fun Note(text: String, isError: Boolean) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 6.dp, end = 12.dp)
  )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Transparent)
      .padding(32.dp)
  ) {
    content()
  }
}

/**
 * 引擎给作者的未通过原因是固定代码，文案由客户端定（引擎 `docs/api.md`「自己的帖子」）；不认识的代码按「不符合社区规范」显示。
 * 旧版引擎给的是规则代码（`lexicon:<类别>`），也认一下。
 */
@StringRes
internal fun rejectionReasonLabel(code: String): Int {
  return when (code.substringBefore(':')) {
    "prohibited_words", "lexicon" -> R.string.TellomiFeedMine__reason_wording
    "review_unavailable" -> R.string.TellomiFeedMine__reason_review_unavailable
    "account_restricted" -> R.string.TellomiFeedMine__reason_account
    else -> R.string.TellomiFeedMine__reason_guidelines
  }
}

/** 「关注请求」「屏蔽列表」「关注的话题」（第三节第 29、44、18 条）。一级导航改成「我」之后挪进设置。 */
@Composable
private fun PeopleMenu(onFollowRequestsClick: () -> Unit, onBlockedClick: () -> Unit, onFollowedTopicsClick: () -> Unit, onSettingsClick: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag(FeedMyPostsTestTags.PEOPLE)) {
      Icon(
        imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_more_vertical_24),
        contentDescription = stringResource(R.string.TellomiFeed__more)
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedPeople__requests_title)) },
        onClick = {
          open = false
          onFollowRequestsClick()
        }
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedPeople__blocked_title)) },
        onClick = {
          open = false
          onBlockedClick()
        }
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedTopicFollow__followed_title)) },
        onClick = {
          open = false
          onFollowedTopicsClick()
        }
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedRecommendation__title)) },
        onClick = {
          open = false
          onSettingsClick()
        }
      )
    }
  }
}
