/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedAvatar
import org.thoughtcrime.securesms.feed.FeedInsightsDialog
import org.thoughtcrime.securesms.feed.FeedOwnPostAction
import org.thoughtcrime.securesms.feed.FeedPostCard
import org.thoughtcrime.securesms.feed.compose.FeedEditTarget
import org.thoughtcrime.securesms.feed.localUriOrNull
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.parseInstantMillis
import org.thoughtcrime.securesms.feed.remoteUrlOrNull
import org.thoughtcrime.securesms.util.DateUtils
import java.io.File
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

object FeedPostDetailTestTags {
  const val COMPOSER = "feed_detail_composer"
  const val SEND = "feed_detail_send"
  const val APPROVE = "feed_detail_approve_"
  const val DECLINE = "feed_detail_decline_"
  const val MIC = "feed_detail_mic"
  const val VOICE_RECORDING = "feed_detail_voice_recording"
}

/** 楼里先露几条回复，多的折起来（照 Threads）。 */
internal const val VISIBLE_REPLIES = 3

private const val LOAD_MORE_THRESHOLD = 4
private val COMMENT_AVATAR = 28.dp
private val REPLY_AVATAR = 22.dp
private val REPLY_INDENT = 40.dp

/**
 * 帖子详情页：上面是整条帖子（长文不截断），下面是评论，最底下是评论框。
 *
 * 评论排成楼（第三节第 2 条：交互跟 Threads）：直接评论帖子的按时间正序，回复缩进挂在它下面，超过 [VISIBLE_REPLIES] 条折起来；
 * 回的不是楼主那条时，正文前写「@被回复的人」。评论是 Tellomi 气泡，作者自己的评论用另一种底色（第 4 条；品牌青色定了再换）。
 * 大陆区显示评论的 IP 属地（第 10 条）。回复的排序和谁能回复（Q-11、Q-12）还没定，先按时间、先都能回。
 */
@Composable
fun FeedPostDetailScreen(
  state: FeedPostDetailState,
  onBack: () -> Unit,
  onLikeClick: () -> Unit,
  onRetryComments: () -> Unit,
  onLoadMoreComments: () -> Unit,
  onReplyClick: (FeedCard?) -> Unit,
  onDraftChanged: (String) -> Unit,
  onSend: () -> Unit,
  onNoticeShown: () -> Unit,
  onProfileRequired: () -> Unit = {},
  onAuthorClick: (FeedAuthor) -> Unit = {},
  onVote: (List<Int>) -> Unit = {},
  onApproveComment: (FeedCard) -> Unit = {},
  onDeclineComment: (FeedCard) -> Unit = {},
  onOwnPostAction: (FeedOwnPostAction) -> Unit = {},
  onDeletePost: () -> Unit = {},
  onReportPost: () -> Unit = {},
  onInsightsDismissed: () -> Unit = {},
  onSaveClick: () -> Unit = {},
  onRepostClick: () -> Unit = {},
  onNotInterested: () -> Unit = {},
  onMuteAuthor: () -> Unit = {},
  onBlockAuthor: () -> Unit = {},
  onEditPost: (FeedEditTarget) -> Unit = {},
  onLeave: (FeedPostDetailState.Leave) -> Unit = { onBack() },
  onSendVoice: (File) -> Unit = {},
  onVoiceTooShort: () -> Unit = {}
) {
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
  LaunchedEffect(state.leaving) { state.leaving?.let(onLeave) }
  val noticeText = state.notice?.let { stringResource(it.messageRes()) }
  LaunchedEffect(state.notice) {
    if (state.notice == FeedPostDetailState.Notice.PROFILE_REQUIRED) {
      // 还没建公开主页：直接去建，建好回来接着发
      onNoticeShown()
      onProfileRequired()
    } else if (noticeText != null) {
      snackbarHostState.showSnackbar(noticeText)
      onNoticeShown()
    }
  }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedDetail__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) },
    bottomBar = {
      if (!state.postGone) {
        CommentComposer(
          state = state,
          onDraftChanged = onDraftChanged,
          onSend = onSend,
          onCancelReply = { onReplyClick(null) },
          onSendVoice = onSendVoice,
          onVoiceTooShort = onVoiceTooShort
        )
      }
    }
  ) { padding ->
    if (state.postGone) {
      Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) {
        Text(
          text = stringResource(R.string.TellomiFeedDetail__gone),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      return@Settings
    }

    CommentList(
      state = state,
      onLikeClick = onLikeClick,
      onRetryComments = onRetryComments,
      onLoadMoreComments = onLoadMoreComments,
      onReplyClick = onReplyClick,
      onAuthorClick = onAuthorClick,
      onVote = onVote,
      onApproveComment = onApproveComment,
      onDeclineComment = onDeclineComment,
      onOwnPostAction = onOwnPostAction,
      onDeletePost = onDeletePost,
      onReportPost = onReportPost,
      onSaveClick = onSaveClick,
      onRepostClick = onRepostClick,
      onNotInterested = onNotInterested,
      onMuteAuthor = onMuteAuthor,
      onBlockAuthor = onBlockAuthor,
      onEditPost = onEditPost,
      modifier = Modifier.padding(padding).fillMaxSize()
    )
  }

  state.insights?.let { insights ->
    FeedInsightsDialog(state = insights, onDismiss = onInsightsDismissed)
  }
}

@Composable
private fun CommentList(
  state: FeedPostDetailState,
  onLikeClick: () -> Unit,
  onRetryComments: () -> Unit,
  onLoadMoreComments: () -> Unit,
  onReplyClick: (FeedCard) -> Unit,
  onAuthorClick: (FeedAuthor) -> Unit,
  onVote: (List<Int>) -> Unit,
  onApproveComment: (FeedCard) -> Unit,
  onDeclineComment: (FeedCard) -> Unit,
  onOwnPostAction: (FeedOwnPostAction) -> Unit,
  onDeletePost: () -> Unit,
  onReportPost: () -> Unit,
  onSaveClick: () -> Unit,
  onRepostClick: () -> Unit,
  onNotInterested: () -> Unit,
  onMuteAuthor: () -> Unit,
  onBlockAuthor: () -> Unit,
  onEditPost: (FeedEditTarget) -> Unit,
  modifier: Modifier = Modifier
) {
  val listState = rememberLazyListState()
  val voicePlayer = rememberFeedVoicePlayer()
  val voicePlayback by voicePlayer.playback.collectAsStateWithLifecycle()
  val onVoiceToggle: (FeedMedia) -> Unit = { media -> voicePlayer.toggle(media, media.localUriOrNull()?.toString() ?: media.remoteUrlOrNull(state.mediaBaseUrl)) }
  val editTarget = remember(state.post, state.editWindowSeconds) {
    FeedEditTarget.of(state.post)?.takeIf { state.post.viewer.isAuthor && it.isWithinWindow(System.currentTimeMillis(), state.editWindowSeconds) }
  }
  var confirmBlock by rememberSaveable { mutableStateOf(false) }
  if (confirmBlock) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedPage__block_title, state.post.author?.displayName.orEmpty()),
      body = stringResource(R.string.TellomiFeedPage__block_body),
      confirm = stringResource(R.string.TellomiFeedPage__block),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = {
        confirmBlock = false
        onBlockAuthor()
      },
      onDismiss = { confirmBlock = false }
    )
  }
  var confirmDelete by rememberSaveable { mutableStateOf(false) }
  if (confirmDelete) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedMine__delete_title),
      body = stringResource(R.string.TellomiFeedMine__delete_body),
      confirm = stringResource(R.string.TellomiFeedMine__delete),
      dismiss = stringResource(android.R.string.cancel),
      confirmColor = MaterialTheme.colorScheme.error,
      onConfirm = {
        confirmDelete = false
        onDeletePost()
      },
      onDismiss = { confirmDelete = false }
    )
  }
  var expandedThreads by rememberSaveable { mutableStateOf(setOf<String>()) }
  val postAuthorId = state.post.author?.profileId
  // 只有帖子作者能批准 / 拒绝等着的回复
  val canModerate = state.post.viewer.isAuthor
  var decliningId by rememberSaveable { mutableStateOf<String?>(null) }

  state.comments.firstOrNull { it.id == decliningId }?.let { comment ->
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedDetail__decline_title),
      body = stringResource(R.string.TellomiFeedDetail__decline_body),
      confirm = stringResource(R.string.TellomiFeedDetail__decline),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = {
        decliningId = null
        onDeclineComment(comment)
      },
      onDismiss = { decliningId = null },
      confirmColor = MaterialTheme.colorScheme.error
    )
  }

  val nearEnd by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
      info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
    }
  }
  LaunchedEffect(nearEnd, state.cursor) {
    if (nearEnd && state.cursor != null) onLoadMoreComments()
  }

  LazyColumn(state = listState, modifier = modifier) {
    item(key = "post") {
      FeedPostCard(
        card = state.post,
        showIpRegion = state.showIpRegion,
        onLikeClick = { onLikeClick() },
        mediaBaseUrl = state.mediaBaseUrl,
        expanded = true,
        onAuthorClick = onAuthorClick,
        onVote = { _, options -> onVote(options) },
        // 「···」：自己的给置顶 / 回复选项 / 隐藏点赞数 / 查看数据 / 删除，别人的给举报（第三节第 26 条）
        onOwnPostAction = { _, action -> onOwnPostAction(action) },
        onDeleteClick = if (state.post.viewer.isAuthor) { _ -> confirmDelete = true } else null,
        // 编辑只在发布后 15 分钟内给（第三节第 23 条），最后由引擎判
        onEditClick = editTarget?.let { target -> { _ -> onEditPost(target) } },
        onReportClick = if (state.post.viewer.isAuthor) null else { _ -> onReportPost() },
        onSaveClick = { onSaveClick() },
        onRepostClick = { onRepostClick() },
        // 别人的帖子：不感兴趣 / 不看 TA / 屏蔽，成了就关页（第三节第 11、26 条）
        onNotInterestedClick = if (state.post.viewer.isAuthor) null else { _ -> onNotInterested() },
        onMuteAuthorClick = if (state.post.viewer.isAuthor) null else { _ -> onMuteAuthor() },
        onBlockAuthorClick = if (state.post.viewer.isAuthor) null else { _ -> confirmBlock = true }
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    item(key = "header") {
      CommentsHeader(state = state, onRetry = onRetryComments)
    }

    state.threads.forEach { thread ->
      item(key = thread.root.id) {
        CommentBubble(
          comment = thread.root,
          replyToName = null,
          isPostAuthor = thread.root.author?.profileId == postAuthorId,
          showIpRegion = state.showIpRegion,
          canReply = state.canComment,
          onReplyClick = onReplyClick,
          isReply = false,
          onAuthorClick = onAuthorClick,
          canModerate = canModerate,
          onApprove = onApproveComment,
          onDecline = { decliningId = it.id },
          voicePlayback = voicePlayback,
          onVoiceToggle = onVoiceToggle
        )
      }

      val expanded = thread.root.id in expandedThreads
      val shown = if (expanded) thread.replies else thread.replies.take(VISIBLE_REPLIES)
      shown.forEach { reply ->
        item(key = reply.id) {
          CommentBubble(
            comment = reply,
            replyToName = thread.replyTo[reply.id]?.author?.displayName,
            isPostAuthor = reply.author?.profileId == postAuthorId,
            showIpRegion = state.showIpRegion,
            canReply = state.canComment,
            onReplyClick = onReplyClick,
            isReply = true,
            onAuthorClick = onAuthorClick,
            canModerate = canModerate,
            onApprove = onApproveComment,
            onDecline = { decliningId = it.id },
            voicePlayback = voicePlayback,
            onVoiceToggle = onVoiceToggle
          )
        }
      }

      val hidden = thread.replies.size - shown.size
      if (hidden > 0) {
        item(key = "${thread.root.id}-more") {
          Text(
            text = pluralStringResource(R.plurals.TellomiFeedDetail__more_replies, hidden, hidden),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .padding(start = 16.dp + COMMENT_AVATAR + REPLY_INDENT - 12.dp, bottom = 8.dp)
              .clip(RoundedCornerShape(50))
              .clickable { expandedThreads = expandedThreads + thread.root.id }
              .padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
    }

    if (state.cursor != null && state.comments.isNotEmpty()) {
      item(key = "loading-more") {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }
    }
  }
}

@Composable
private fun CommentsHeader(state: FeedPostDetailState, onRetry: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    val count = maxOf(state.post.stats.comments, state.comments.size)
    when {
      state.comments.isEmpty() && (state.loading || !state.loadedOnce) -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      state.comments.isEmpty() && state.failed -> {
        Text(
          text = stringResource(R.string.TellomiFeed__load_failed),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      state.comments.isEmpty() -> Text(
        text = stringResource(if (state.canComment) R.string.TellomiFeedDetail__no_comments else R.string.TellomiFeedDetail__comments_off),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      else -> Text(
        text = pluralStringResource(R.plurals.TellomiFeed__comments, count, count),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}

@Composable
private fun CommentBubble(
  comment: FeedCard,
  replyToName: String?,
  isPostAuthor: Boolean,
  showIpRegion: Boolean,
  canReply: Boolean,
  onReplyClick: (FeedCard) -> Unit,
  isReply: Boolean,
  onAuthorClick: (FeedAuthor) -> Unit = {},
  canModerate: Boolean = false,
  onApprove: (FeedCard) -> Unit = {},
  onDecline: (FeedCard) -> Unit = {},
  voicePlayback: FeedVoicePlayback = FeedVoicePlayback(),
  onVoiceToggle: (FeedMedia) -> Unit = {}
) {
  val context = LocalContext.current
  val start = if (isReply) 16.dp + COMMENT_AVATAR + 12.dp else 16.dp

  Row(modifier = Modifier.fillMaxWidth().padding(start = start, end = 16.dp, bottom = 8.dp)) {
    val author = comment.author
    FeedAvatar(
      author = author,
      size = if (isReply) REPLY_AVATAR else COMMENT_AVATAR,
      modifier = if (author != null && author.isNative) Modifier.clip(CircleShape).clickable { onAuthorClick(author) } else Modifier
    )
    Spacer(modifier = Modifier.width(8.dp))

    Column(modifier = Modifier.weight(1f)) {
      // 作者本人的评论换底色（第三节第 4 条「作者气泡青色」；品牌色定了之前先用主题的 primaryContainer）
      val bubbleColor = if (isPostAuthor) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
      val onBubble = if (isPostAuthor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
      Column(
        modifier = Modifier
          .clip(RoundedCornerShape(16.dp))
          .background(bubbleColor)
          .padding(horizontal = 12.dp, vertical = 8.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = comment.author?.displayName.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          if (isPostAuthor) {
            Text(
              text = "  " + stringResource(R.string.TellomiFeedDetail__author),
              style = MaterialTheme.typography.labelSmall,
              color = onBubble
            )
          }
        }

        val primary = MaterialTheme.colorScheme.primary
        val voice = comment.media.firstOrNull { it.isAudio }
        // 语音评论的文字可以空：空的时候只留「@某人」，两样都没有就不占一行
        if (voice == null || replyToName != null || !comment.text.isNullOrBlank()) {
          Text(
            text = buildAnnotatedString {
              if (replyToName != null) {
                withStyle(SpanStyle(color = primary)) { append("@$replyToName ") }
              }
              append(comment.text.orEmpty())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
        if (voice != null) {
          FeedVoiceCommentRow(media = voice, transcript = comment.transcript, playback = voicePlayback, onToggle = onVoiceToggle)
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
      ) {
        val meta = buildList {
          comment.publishedAt?.let { parseInstantMillis(it) }?.let {
            add(DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), it).first)
          }
          val ip = comment.ipRegionLabel
          if (showIpRegion && !ip.isNullOrBlank()) add(stringResource(R.string.TellomiFeed__ip_region, ip))
        }.joinToString(separator = " · ")
        if (meta.isNotEmpty()) {
          Text(text = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (comment.held) {
          // 等作者批准的回复（第 40 条）：作者看到「批准 / 拒绝」，写的人看到「等作者批准」，别人看不到它
          Text(
            text = stringResource(if (canModerate) R.string.TellomiFeedDetail__held_for_you else R.string.TellomiFeedDetail__held_waiting),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
          )
          if (canModerate) {
            ModerationAction(R.string.TellomiFeedDetail__approve, FeedPostDetailTestTags.APPROVE + comment.id, MaterialTheme.colorScheme.primary) { onApprove(comment) }
            ModerationAction(R.string.TellomiFeedDetail__decline, FeedPostDetailTestTags.DECLINE + comment.id, MaterialTheme.colorScheme.error) { onDecline(comment) }
          }
        } else if (canReply) {
          Text(
            text = stringResource(R.string.TellomiFeedDetail__reply),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .clickable { onReplyClick(comment) }
              .padding(horizontal = 4.dp, vertical = 6.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun ModerationAction(label: Int, tag: String, color: Color, onClick: () -> Unit) {
  Text(
    text = stringResource(label),
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    color = color,
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .clickable(onClick = onClick)
      .padding(horizontal = 4.dp, vertical = 6.dp)
      .testTag(tag)
  )
}

@Composable
private fun CommentComposer(
  state: FeedPostDetailState,
  onDraftChanged: (String) -> Unit,
  onSend: () -> Unit,
  onCancelReply: () -> Unit,
  onSendVoice: (File) -> Unit,
  onVoiceTooShort: () -> Unit
) {
  Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

      if (!state.canComment) {
        Text(
          text = stringResource(R.string.TellomiFeedDetail__comments_off),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        return@Column
      }

      val target = state.replyTarget
      if (target != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp)) {
          Text(
            text = stringResource(R.string.TellomiFeedDetail__replying_to, target.author?.displayName.orEmpty()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
          )
          IconButton(onClick = onCancelReply) {
            Icon(
              imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_x_24),
              contentDescription = stringResource(R.string.TellomiFeedDetail__cancel_reply),
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      val voice = rememberFeedVoiceComposer(maxMs = state.voiceMaxSeconds * 1000L, onSendVoice = onSendVoice)
      // 输入框空着就显示话筒（照 Telegram：有字是发送，没字是按住说话）
      val showMic = state.voiceAllowed && state.draft.isBlank() && !state.sending
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        if (voice.recording) {
          FeedVoiceRecordingBar(elapsedMs = voice.elapsedMs, maxMs = state.voiceMaxSeconds * 1000L, slide = voice.slide, modifier = Modifier.weight(1f))
        } else {
          OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChanged,
            placeholder = {
              Text(
                if (target != null) {
                  stringResource(R.string.TellomiFeedDetail__hint_reply, target.author?.displayName.orEmpty())
                } else {
                  stringResource(R.string.TellomiFeedDetail__hint)
                }
              )
            },
            maxLines = 4,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.weight(1f).testTag(FeedPostDetailTestTags.COMPOSER)
          )
        }
        if (showMic || voice.recording) {
          FeedMicButton(voice = voice, onTooShort = onVoiceTooShort)
        } else {
          IconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.testTag(FeedPostDetailTestTags.SEND)) {
            if (state.sending) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Icon(
                imageVector = ImageVector.vectorResource(R.drawable.symbol_send_fill_24),
                contentDescription = stringResource(R.string.TellomiFeedDetail__send),
                tint = if (state.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }
}

private fun FeedPostDetailState.Notice.messageRes(): Int {
  return when (this) {
    FeedPostDetailState.Notice.PENDING -> R.string.TellomiFeedDetail__sent_pending
    FeedPostDetailState.Notice.VOICE_PENDING -> R.string.TellomiFeed__voice_sent_pending
    FeedPostDetailState.Notice.VOICE_OFF -> R.string.TellomiFeed__voice_off
    FeedPostDetailState.Notice.HOLD_TO_RECORD -> R.string.TellomiFeed__voice_hold
    FeedPostDetailState.Notice.PUBLISHED -> R.string.TellomiFeedDetail__sent
    FeedPostDetailState.Notice.CLOSED -> R.string.TellomiFeedDetail__closed_notice
    FeedPostDetailState.Notice.REPLY_NOT_ALLOWED -> R.string.TellomiFeedDetail__reply_not_allowed
    FeedPostDetailState.Notice.PROFILE_REQUIRED -> R.string.TellomiFeedDetail__profile_required
    FeedPostDetailState.Notice.PARENT_GONE -> R.string.TellomiFeedDetail__parent_gone
    FeedPostDetailState.Notice.PIN_LIMIT -> R.string.TellomiFeed__pin_limit
    FeedPostDetailState.Notice.SAVED -> R.string.TellomiFeed__saved_done
    FeedPostDetailState.Notice.REPOSTED -> R.string.TellomiFeed__reposted_done
    FeedPostDetailState.Notice.HIDE_PROFILE_REQUIRED -> R.string.TellomiFeed__hide_profile_required
    FeedPostDetailState.Notice.EDITED -> R.string.TellomiFeedCompose__edit_saved
    FeedPostDetailState.Notice.EDITED_PENDING -> R.string.TellomiFeedCompose__edit_saved_pending
    FeedPostDetailState.Notice.FAILED -> R.string.TellomiFeed__action_failed
  }
}
