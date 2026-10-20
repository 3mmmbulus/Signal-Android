/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import org.thoughtcrime.securesms.feed.FeedAvatar
import org.thoughtcrime.securesms.feed.FeedInsightsDialog
import org.thoughtcrime.securesms.feed.FeedOfficial
import org.thoughtcrime.securesms.feed.FeedOfficialMark
import org.thoughtcrime.securesms.feed.FeedOwnPostAction
import org.thoughtcrime.securesms.feed.FeedPostCard
import org.thoughtcrime.securesms.feed.LocalFeedEntries
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedRelation
import org.signal.core.ui.R as CoreUiR

object FeedProfilePageTestTags {
  const val FOLLOW = "feed_profile_page_follow"
  const val MORE = "feed_profile_page_more"
  const val MUTE = "feed_profile_page_mute"
  const val HIDE_FROM = "feed_profile_page_hide_from"
  const val NOT_OFFICIAL = "feed_profile_page_not_official"
}

private const val LOAD_MORE_THRESHOLD = 4
private val AVATAR_SIZE = 72.dp

/**
 * 公开主页。头部照 ADR-0071 二·〇「个人主页」：名字和 ID 在左、头像在右，放在一块底色上；下面是圆角卡片里的简介、
 * 兴趣、IP 属地、粉丝 / 关注数，再下面是帖子。按钮现在只有「关注」（「加好友」「发消息」随好友那一刀加）；
 * 举报、屏蔽在右上角「···」（「个人主页按钮」行）。背景图、分栏、滚动动效（附录 10 §5）随后面的 PR。
 */
@Composable
fun FeedProfilePageScreen(
  state: FeedProfilePageState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onFollow: () -> Unit,
  onUnfollow: () -> Unit,
  onSetBlocked: (Boolean) -> Unit,
  onReport: () -> Unit,
  onEditProfile: () -> Unit,
  onLoadMorePosts: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onReportPost: (FeedCard) -> Unit,
  onNoticeShown: () -> Unit,
  onProfileRequired: () -> Unit,
  onOwnPostAction: (FeedCard, FeedOwnPostAction) -> Unit = { _, _ -> },
  onInsightsDismissed: () -> Unit = {},
  onSetHidden: (list: String, on: Boolean) -> Unit = { _, _ -> },
  onSaveClick: ((FeedCard) -> Unit)? = null,
  onRepostClick: ((FeedCard) -> Unit)? = null
) {
  val profile = state.profile
  val snackbarHostState = remember { SnackbarHostState() }

  val followed = stringResource(R.string.TellomiFeedPage__followed)
  val requested = stringResource(R.string.TellomiFeedPage__requested_sent)
  val blocked = stringResource(R.string.TellomiFeedPage__blocked_done)
  val unblocked = stringResource(R.string.TellomiFeedPage__unblocked_done)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  val pinLimit = stringResource(R.string.TellomiFeed__pin_limit)
  val mutedDone = stringResource(R.string.TellomiFeed__muted_done)
  val hiddenFromDone = stringResource(R.string.TellomiFeed__hidden_from_done)
  val undo = stringResource(R.string.TellomiFeed__undo)
  val savedDone = stringResource(R.string.TellomiFeed__saved_done)
  val repostedDone = stringResource(R.string.TellomiFeed__reposted_done)
  LaunchedEffect(state.notice) {
    val notice = state.notice ?: return@LaunchedEffect
    onNoticeShown()
    when (notice) {
      FeedProfilePageState.Notice.FOLLOWED, FeedProfilePageState.Notice.REQUESTED -> {
        val message = if (notice == FeedProfilePageState.Notice.FOLLOWED) followed else requested
        if (snackbarHostState.showSnackbar(message, actionLabel = undo) == SnackbarResult.ActionPerformed) onUnfollow()
      }
      FeedProfilePageState.Notice.PROFILE_REQUIRED -> onProfileRequired()
      FeedProfilePageState.Notice.BLOCKED -> snackbarHostState.showSnackbar(blocked)
      FeedProfilePageState.Notice.UNBLOCKED -> snackbarHostState.showSnackbar(unblocked)
      FeedProfilePageState.Notice.PIN_LIMIT -> snackbarHostState.showSnackbar(pinLimit)
      FeedProfilePageState.Notice.MUTED -> snackbarHostState.showSnackbar(mutedDone)
      FeedProfilePageState.Notice.SAVED -> snackbarHostState.showSnackbar(savedDone)
      FeedProfilePageState.Notice.REPOSTED -> snackbarHostState.showSnackbar(repostedDone)
      FeedProfilePageState.Notice.HIDDEN_FROM -> snackbarHostState.showSnackbar(hiddenFromDone)
      FeedProfilePageState.Notice.FAILED -> snackbarHostState.showSnackbar(failed)
    }
  }

  var confirmUnfollow by remember { mutableStateOf(false) }
  var confirmBlock by remember { mutableStateOf(false) }

  Scaffolds.Settings(
    title = profile?.displayName.orEmpty(),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) },
    actions = {
      if (profile != null && !profile.me.self) {
        MoreMenu(
          blocked = profile.me.blocked,
          hideState = state.hideState.takeUnless { profile.me.blocked },
          onSetHidden = onSetHidden,
          onReport = onReport,
          onBlock = { confirmBlock = true },
          onUnblock = { onSetBlocked(false) }
        )
      }
    }
  ) { padding ->
    Box(
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
    ) {
      when {
        profile == null && state.gone -> CenteredMessage(text = stringResource(R.string.TellomiFeedPage__gone))
        profile == null && state.failed -> CenteredMessage(text = stringResource(R.string.TellomiFeed__load_failed), action = stringResource(R.string.TellomiFeed__retry), onAction = onRetry)
        profile == null -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { CircularProgressIndicator() }
        else -> ProfileContent(
          state = state,
          profile = profile,
          onFollowButton = {
            when (profile.me.follow) {
              FeedRelation.FOLLOW_NONE -> onFollow()
              else -> confirmUnfollow = true
            }
          },
          onEditProfile = onEditProfile,
          onUnblock = { onSetBlocked(false) },
          onLoadMorePosts = onLoadMorePosts,
          onRetry = onRetry,
          onLikeClick = onLikeClick,
          onOpenPost = onOpenPost,
          onReportPost = onReportPost,
          onOwnPostAction = onOwnPostAction,
          onSaveClick = onSaveClick,
          onRepostClick = onRepostClick
        )
      }
    }
  }

  state.insights?.let { insights ->
    FeedInsightsDialog(state = insights, onDismiss = onInsightsDismissed)
  }

  if (confirmUnfollow && profile != null) {
    val pending = profile.me.follow == FeedRelation.FOLLOW_PENDING
    Dialogs.SimpleAlertDialog(
      title = if (pending) stringResource(R.string.TellomiFeedPage__withdraw_title) else stringResource(R.string.TellomiFeedPage__unfollow_title, profile.displayName),
      body = stringResource(if (pending) R.string.TellomiFeedPage__withdraw_body else R.string.TellomiFeedPage__unfollow_body),
      confirm = if (pending) stringResource(R.string.TellomiFeedPage__withdraw) else stringResource(R.string.TellomiFeedPage__unfollow),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = onUnfollow,
      onDismiss = { confirmUnfollow = false }
    )
  }

  if (confirmBlock && profile != null) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedPage__block_title, profile.displayName),
      body = stringResource(R.string.TellomiFeedPage__block_body),
      confirm = stringResource(R.string.TellomiFeedPage__block),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = { onSetBlocked(true) },
      onDismiss = { confirmBlock = false }
    )
  }
}

@Composable
private fun MoreMenu(
  blocked: Boolean,
  hideState: FeedHideState?,
  onSetHidden: (list: String, on: Boolean) -> Unit,
  onReport: () -> Unit,
  onBlock: () -> Unit,
  onUnblock: () -> Unit
) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag(FeedProfilePageTestTags.MORE)) {
      Icon(
        imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_more_vertical_24),
        contentDescription = stringResource(R.string.TellomiFeed__more)
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (hideState != null) {
        DropdownMenuItem(
          text = { Text(stringResource(if (hideState.muted) R.string.TellomiFeed__unmute_author else R.string.TellomiFeed__mute_author)) },
          modifier = Modifier.testTag(FeedProfilePageTestTags.MUTE),
          onClick = {
            open = false
            onSetHidden(FeedApi.HIDE_MUTE, !hideState.muted)
          }
        )
        DropdownMenuItem(
          text = { Text(stringResource(if (hideState.hiddenFrom) R.string.TellomiFeed__unhide_from_author else R.string.TellomiFeed__hide_from_author)) },
          modifier = Modifier.testTag(FeedProfilePageTestTags.HIDE_FROM),
          onClick = {
            open = false
            onSetHidden(FeedApi.HIDE_FROM, !hideState.hiddenFrom)
          }
        )
      }
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeedReport__title)) },
        onClick = {
          open = false
          onReport()
        }
      )
      DropdownMenuItem(
        text = { Text(stringResource(if (blocked) R.string.TellomiFeedPage__unblock else R.string.TellomiFeedPage__block)) },
        onClick = {
          open = false
          if (blocked) onUnblock() else onBlock()
        }
      )
    }
  }
}

@Composable
private fun ProfileContent(
  state: FeedProfilePageState,
  profile: FeedProfilePage,
  onFollowButton: () -> Unit,
  onEditProfile: () -> Unit,
  onUnblock: () -> Unit,
  onLoadMorePosts: () -> Unit,
  onRetry: () -> Unit,
  onLikeClick: (FeedCard) -> Unit,
  onOpenPost: (FeedCard) -> Unit,
  onReportPost: (FeedCard) -> Unit,
  onOwnPostAction: (FeedCard, FeedOwnPostAction) -> Unit,
  onSaveClick: ((FeedCard) -> Unit)?,
  onRepostClick: ((FeedCard) -> Unit)?
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
    if (nearEnd && state.cursor != null) onLoadMorePosts()
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    item(key = "header") {
      ProfileHeader(profile = profile, showIpRegion = state.showIpRegion)
    }

    item(key = "about") {
      AboutCard(profile = profile, followBusy = state.followBusy, onFollowButton = onFollowButton, onEditProfile = onEditProfile)
    }

    val notice = when {
      profile.me.blocked -> R.string.TellomiFeedPage__blocked_notice
      profile.regionUnavailable -> R.string.TellomiFeedPage__region_unavailable
      !profile.canSeePosts -> R.string.TellomiFeedPage__private_notice
      else -> null
    }
    if (notice != null) {
      item(key = "notice") {
        CenteredMessage(
          text = stringResource(notice),
          action = if (profile.me.blocked) stringResource(R.string.TellomiFeedPage__unblock) else null,
          onAction = onUnblock,
          modifier = Modifier.padding(vertical = 32.dp)
        )
      }
      return@LazyColumn
    }

    when {
      state.posts.isEmpty() && (state.postsLoading || !state.postsLoadedOnce) -> item(key = "posts-loading") {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
      }
      state.posts.isEmpty() && state.postsFailed -> item(key = "posts-failed") {
        CenteredMessage(
          text = stringResource(R.string.TellomiFeed__load_failed),
          action = stringResource(R.string.TellomiFeed__retry),
          onAction = onRetry,
          modifier = Modifier.padding(vertical = 32.dp)
        )
      }
      state.posts.isEmpty() -> item(key = "posts-empty") {
        CenteredMessage(text = stringResource(R.string.TellomiFeedPage__no_posts), modifier = Modifier.padding(vertical = 32.dp))
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
            onOwnPostAction = onOwnPostAction,
            onSaveClick = onSaveClick,
            onRepostClick = onRepostClick
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

/** 底色上一行：名字、ID、认证标在左，头像在右（照抖音，ADR-0071 二·〇「个人主页」）。背景图换成可更换的那天再加。 */
@Composable
private fun ProfileHeader(profile: FeedProfilePage, showIpRegion: Boolean) {
  val colors = MaterialTheme.colorScheme
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface)))
      .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = profile.displayName,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = colors.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
        if (FeedOfficial.isOfficial(profile.badge)) {
          FeedOfficialMark(size = 20.dp)
        }
      }
      Spacer(modifier = Modifier.height(4.dp))
      val id = profile.handle?.let { "@$it" } ?: profile.tellomiNumber?.let { stringResource(R.string.TellomiFeed__tellomi_number, it) }
      val line = listOfNotNull(
        id,
        badgeLabel(profile.badge),
        profile.ipRegionLabel?.takeIf { showIpRegion }?.let { stringResource(R.string.TellomiFeed__ip_region, it) }
      ).joinToString(separator = " · ")
      if (line.isNotEmpty()) {
        Text(text = line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
      }
      if (!FeedOfficial.isOfficial(profile.badge) && FeedOfficial.looksOfficial(profile.displayName, profile.handle)) {
        // 第 33 条：冒充官方的提示（official-account-and-saved.md 3.3）
        Text(
          text = stringResource(R.string.TellomiFeedPage__not_official),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.error,
          modifier = Modifier.padding(top = 4.dp).testTag(FeedProfilePageTestTags.NOT_OFFICIAL)
        )
      }
    }
    Spacer(modifier = Modifier.width(16.dp))
    FeedAvatar(author = profile.asAuthor(), size = AVATAR_SIZE)
  }
}

@Composable
private fun badgeLabel(badge: String?): String? {
  return when (badge) {
    // 官方用名字后面的认证标（FeedOfficialMark），这里不再写字
    "verified" -> stringResource(R.string.TellomiFeedPage__badge_verified)
    "org" -> stringResource(R.string.TellomiFeedPage__badge_org)
    "channel" -> stringResource(R.string.TellomiFeedPage__badge_channel)
    else -> null
  }
}

@Composable
private fun AboutCard(profile: FeedProfilePage, followBusy: Boolean, onFollowButton: () -> Unit, onEditProfile: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(colors.surfaceContainerLow)
      .padding(16.dp)
  ) {
    val bio = profile.bio
    if (!bio.isNullOrBlank()) {
      Text(text = bio, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
    if (profile.shownInterests.isNotEmpty()) {
      Text(
        text = profile.shownInterests.joinToString(separator = "  ") { "#$it" },
        style = MaterialTheme.typography.bodyMedium,
        color = colors.primary
      )
    }

    val counts = listOf(
      pluralStringResource(R.plurals.TellomiFeedPage__followers, profile.followers, profile.followers),
      pluralStringResource(R.plurals.TellomiFeedPage__following, profile.following, profile.following)
    ).joinToString(separator = "   ")
    val followsYou = if (profile.me.followedBy && !profile.me.self) "   " + stringResource(R.string.TellomiFeedPage__follows_you) else ""
    Text(text = counts + followsYou, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)

    if (profile.closePeople.isNotEmpty()) {
      Text(
        text = stringResource(R.string.TellomiFeedPage__close_people, profile.closePeople.joinToString(separator = "、") { it.displayName }),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
      )
    }

    Spacer(modifier = Modifier.height(4.dp))
    when {
      profile.me.self -> OutlinedButton(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.TellomiFeedPage__edit_profile))
      }
      profile.me.blocked -> Unit
      // 引擎没部署关注（契约 3.0）：不画关注按钮
      !LocalFeedEntries.current.follow -> Unit
      profile.me.follow == FeedRelation.FOLLOW_NONE -> Button(
        onClick = onFollowButton,
        enabled = !followBusy,
        modifier = Modifier.fillMaxWidth().testTag(FeedProfilePageTestTags.FOLLOW)
      ) {
        Text(stringResource(if (profile.me.followedBy) R.string.TellomiFeedPage__follow_back else R.string.TellomiFeedPage__follow))
      }
      else -> OutlinedButton(
        onClick = onFollowButton,
        enabled = !followBusy,
        modifier = Modifier.fillMaxWidth().testTag(FeedProfilePageTestTags.FOLLOW)
      ) {
        Text(stringResource(if (profile.me.follow == FeedRelation.FOLLOW_PENDING) R.string.TellomiFeedPage__requested else R.string.TellomiFeedPage__following_button))
      }
    }
  }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
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
