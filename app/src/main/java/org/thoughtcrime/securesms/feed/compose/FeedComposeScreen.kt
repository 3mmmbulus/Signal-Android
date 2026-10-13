/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.glide.compose.GlideImage
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedQuotedBox
import org.signal.core.ui.R as CoreUiR

/** 字数剩这么多时开始显示计数（Threads 也是快到上限才显示）。 */
private const val COUNTER_VISIBLE_REMAINING = 50

private val THUMBNAIL_SIZE = 96.dp

internal object FeedComposeTestTags {
  const val PUBLISH = "feed_compose_publish"
  const val ADD_POLL = "feed_compose_add_poll"
  const val REPLIES = "feed_compose_replies"
  const val TOPICS = "feed_compose_topics"
  const val TOPIC = "feed_compose_topic_"
  const val REMOVE_TOPIC = "feed_compose_remove_topic_"
}

/**
 * 发布页：顶部「动态 / 长文」，正文，图片，底部可见范围和字数。
 *
 * 投票在正文下面编辑（[FeedComposePollEditor]），回复选项在底部（[FeedReplySettingsSheet]），话题从底部「#」选（[FeedTopicSheet]，第三节第 22 条）。
 */
@Composable
fun FeedComposeScreen(
  state: FeedComposeState,
  onClose: () -> Unit,
  onKindChanged: (FeedDraftKind) -> Unit,
  onTitleChanged: (String) -> Unit,
  onTextChanged: (String) -> Unit,
  onImagesPicked: (List<Uri>) -> Unit,
  onImageRemoved: (Uri) -> Unit,
  onVisibilityChanged: (FeedVisibility) -> Unit,
  onPublish: () -> Unit,
  onFailureShown: () -> Unit,
  /** 「部分可见 / 不给谁看」：打开选人页，选完由 [FeedComposeViewModel.onAudiencePicked] 换档。 */
  onPickAudience: (FeedVisibility) -> Unit = {},
  pollActions: FeedComposePollActions = FeedComposePollActions(),
  onReplySettingsChanged: (FeedReplySettings) -> Unit = {},
  onTopicToggled: (String) -> Unit = {}
) {
  var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
  var showVisibilitySheet by rememberSaveable { mutableStateOf(false) }
  var showReplySheet by rememberSaveable { mutableStateOf(false) }
  var showTopicSheet by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val failedMessage = stringResource(R.string.TellomiFeedCompose__publish_failed)

  val requestClose = {
    if (!state.hasUnsavedChanges || state.publishing) onClose() else showDiscardDialog = true
  }

  BackHandler(onBack = requestClose)

  val remainingImages = (state.limits.postImages - state.draft.images.size).coerceAtLeast(0)
  val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = remainingImages.coerceAtLeast(2))) { uris ->
    onImagesPicked(uris)
  }
  val pickOneImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) onImagesPicked(listOf(uri))
  }
  val openPicker = {
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    // 系统选图器的多选上限至少是 2；只剩 1 张的位置时用单选
    if (remainingImages > 1) pickImages.launch(request) else pickOneImage.launch(request)
  }

  val windowClosedMessage = stringResource(R.string.TellomiFeedCompose__edit_window_closed)
  LaunchedEffect(state.failed, state.editWindowClosed) {
    if (state.failed || state.editWindowClosed) {
      snackbarHostState.showSnackbar(if (state.editWindowClosed) windowClosedMessage else failedMessage)
      onFailureShown()
    }
  }

  Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
    Box {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding()
          .imePadding()
      ) {
        FeedComposeTopBar(
          kind = state.draft.kind,
          editing = state.editing != null,
          publishEnabled = state.problem == null && !state.publishing,
          publishing = state.publishing,
          onClose = requestClose,
          onKindChanged = onKindChanged,
          onPublish = onPublish
        )

        HorizontalDivider()

        Column(
          modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
          if (state.draft.kind == FeedDraftKind.ARTICLE) {
            FeedComposeField(
              value = state.draft.title,
              onValueChange = onTitleChanged,
              placeholder = stringResource(R.string.TellomiFeedCompose__title_hint),
              titleStyle = true,
              singleLine = true,
              requestFocus = true,
              modifier = Modifier.padding(bottom = 12.dp)
            )
          }

          FeedComposeField(
            value = state.draft.text,
            onValueChange = onTextChanged,
            placeholder = stringResource(
              if (state.draft.kind == FeedDraftKind.ARTICLE) R.string.TellomiFeedCompose__article_hint else R.string.TellomiFeedCompose__post_hint
            ),
            titleStyle = false,
            singleLine = false,
            requestFocus = state.draft.kind == FeedDraftKind.POST,
            modifier = Modifier.heightIn(min = 120.dp)
          )

          if (state.draft.attachedImages.isNotEmpty()) {
            FeedComposeImages(
              images = state.draft.attachedImages,
              canAdd = state.canAddImages,
              onAdd = openPicker,
              onRemove = onImageRemoved,
              modifier = Modifier.padding(top = 12.dp)
            )
          }

          state.draft.attachedPoll?.let { poll ->
            FeedComposePollEditor(poll = poll, actions = pollActions, modifier = Modifier.padding(top = 12.dp))
          }

          state.draft.quote?.let { quote ->
            FeedQuotedBox(authorName = quote.authorName, text = quote.snippet, modifier = Modifier.padding(top = 12.dp))
          }

          if (state.draft.topics.isNotEmpty()) {
            FeedComposeTopics(
              topics = state.draft.topics,
              onRemove = if (state.editing == null) onTopicToggled else null,
              modifier = Modifier.padding(top = 12.dp)
            )
          }
        }

        HorizontalDivider()

        FeedComposeBottomBar(
          state = state,
          onAddImages = openPicker,
          onAddPoll = pollActions.onAdd,
          onRepliesClick = { showReplySheet = true },
          onTopicsClick = { showTopicSheet = true },
          onVisibilityClick = { showVisibilitySheet = true }
        )
      }

      SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).imePadding())
    }
  }

  if (showDiscardDialog) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedCompose__discard_title),
      body = stringResource(R.string.TellomiFeedCompose__discard_body),
      confirm = stringResource(R.string.TellomiFeedCompose__discard),
      dismiss = stringResource(R.string.TellomiFeedCompose__keep_editing),
      onConfirm = onClose,
      onDismiss = { showDiscardDialog = false },
      confirmColor = MaterialTheme.colorScheme.error
    )
  }

  if (showTopicSheet) {
    FeedTopicSheet(
      selected = state.draft.topics,
      onToggle = onTopicToggled,
      onDismiss = { showTopicSheet = false }
    )
  }

  if (showReplySheet) {
    FeedReplySettingsSheet(
      settings = state.draft.replies,
      onChange = onReplySettingsChanged,
      onDismiss = { showReplySheet = false }
    )
  }

  if (showVisibilitySheet) {
    FeedVisibilitySheet(
      selected = state.draft.visibility,
      minorMode = state.minorMode,
      audienceSize = state.draft.audience.size,
      onSelect = {
        if (it.needsAudience) onPickAudience(it) else onVisibilityChanged(it)
        showVisibilitySheet = false
      },
      onDismiss = { showVisibilitySheet = false }
    )
  }
}

@Composable
private fun FeedComposeTopBar(
  kind: FeedDraftKind,
  editing: Boolean,
  publishEnabled: Boolean,
  publishing: Boolean,
  onClose: () -> Unit,
  onKindChanged: (FeedDraftKind) -> Unit,
  onPublish: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .padding(horizontal = 4.dp)
  ) {
    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
      Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedCompose__close))
    }

    if (editing) {
      // 编辑时不能换类型（第三节第 23 条只改内容）
      Text(
        text = stringResource(if (kind == FeedDraftKind.ARTICLE) R.string.TellomiFeedCompose__edit_article else R.string.TellomiFeedCompose__edit_post),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.Center)
      )
    } else {
      Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FeedDraftKind.entries.forEach { entry ->
          val selected = entry == kind
          Text(
            text = stringResource(entry.label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable(role = Role.Tab) { onKindChanged(entry) }
              .padding(horizontal = 12.dp, vertical = 8.dp)
          )
        }
      }
    }

    Button(
      onClick = onPublish,
      enabled = publishEnabled,
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = 8.dp)
        .testTag(FeedComposeTestTags.PUBLISH)
    ) {
      if (publishing) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
      } else {
        Text(text = stringResource(if (editing) R.string.TellomiFeedCompose__save else R.string.TellomiFeedCompose__publish))
      }
    }
  }
}

@Composable
private fun FeedComposeField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  titleStyle: Boolean,
  singleLine: Boolean,
  requestFocus: Boolean,
  modifier: Modifier = Modifier
) {
  val focusRequester = remember { FocusRequester() }
  val textStyle = if (titleStyle) {
    MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface)
  } else {
    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
  }

  if (requestFocus) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
  }

  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = singleLine,
    textStyle = textStyle,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    modifier = modifier
      .fillMaxWidth()
      .focusRequester(focusRequester),
    decorationBox = { inner ->
      Box {
        if (value.isEmpty()) {
          Text(text = placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        inner()
      }
    }
  )
}

@Composable
private fun FeedComposeImages(
  images: List<Uri>,
  canAdd: Boolean,
  onAdd: () -> Unit,
  onRemove: (Uri) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
    items(images, key = { it.toString() }) { uri ->
      Box(
        modifier = Modifier
          .size(THUMBNAIL_SIZE)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
      ) {
        GlideImage(model = uri, modifier = Modifier.fillMaxSize())

        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .clickable(role = Role.Button) { onRemove(uri) }
        ) {
          Icon(
            imageVector = SignalIcons.X.imageVector,
            contentDescription = stringResource(R.string.TellomiFeedCompose__remove_image),
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }

    if (canAdd) {
      item {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onAdd)
        ) {
          Icon(
            painter = painterResource(CoreUiR.drawable.symbol_plus_24),
            contentDescription = stringResource(R.string.TellomiFeedCompose__add_images),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@Composable
private fun FeedComposeBottomBar(
  state: FeedComposeState,
  onAddImages: () -> Unit,
  onAddPoll: () -> Unit,
  onRepliesClick: () -> Unit,
  onTopicsClick: () -> Unit,
  onVisibilityClick: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    // 编辑时图片和可见范围不改（引擎只收正文和标题）
    if (state.draft.kind == FeedDraftKind.POST && state.editing == null) {
      IconButton(onClick = onAddImages, enabled = state.canAddImages) {
        Icon(
          painter = painterResource(CoreUiR.drawable.symbol_photo_24),
          contentDescription = stringResource(R.string.TellomiFeedCompose__add_images)
        )
      }
      IconButton(onClick = onAddPoll, enabled = state.canAddPoll, modifier = Modifier.testTag(FeedComposeTestTags.ADD_POLL)) {
        Icon(
          painter = painterResource(R.drawable.symbol_poll_24),
          contentDescription = stringResource(R.string.TellomiFeedCompose__poll_add)
        )
      }
    }

    if (state.editing == null) {
      IconButton(onClick = onTopicsClick, modifier = Modifier.testTag(FeedComposeTestTags.TOPICS)) {
        Icon(
          painter = painterResource(R.drawable.symbol_number_24),
          contentDescription = stringResource(R.string.TellomiFeedCompose__topics_title),
          tint = if (state.draft.topics.isEmpty()) LocalContentColor.current else MaterialTheme.colorScheme.primary
        )
      }
      IconButton(onClick = onRepliesClick, modifier = Modifier.testTag(FeedComposeTestTags.REPLIES)) {
        Icon(
          painter = painterResource(R.drawable.symbol_reply_24),
          contentDescription = stringResource(R.string.TellomiFeedCompose__replies_title),
          // 改过回复选项时图标换成主色，提醒这条不是默认设置
          tint = if (state.draft.replies.isDefault) LocalContentColor.current else MaterialTheme.colorScheme.primary
        )
      }
      TextButton(onClick = onVisibilityClick) {
        Icon(imageVector = SignalIcons.Visible.imageVector, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        val visibility = state.draft.visibility
        val label = stringResource(visibility.label)
        Text(
          text = if (visibility.needsAudience) {
            pluralStringResource(R.plurals.TellomiFeedCompose__visibility_with_count, state.draft.audience.size, label, state.draft.audience.size)
          } else {
            label
          },
          color = if (state.problem == FeedDraft.Problem.AUDIENCE_TOO_LARGE) MaterialTheme.colorScheme.error else Color.Unspecified
        )
      }
    } else {
      Spacer(modifier = Modifier.height(48.dp))
    }

    Spacer(modifier = Modifier.weight(1f))

    val remaining = state.textLimit - state.draft.textLength()
    if (remaining <= COUNTER_VISIBLE_REMAINING) {
      Text(
        text = remaining.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = if (remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 12.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedVisibilitySheet(
  selected: FeedVisibility,
  minorMode: Boolean,
  audienceSize: Int,
  onSelect: (FeedVisibility) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
      Text(
        text = stringResource(R.string.TellomiFeedCompose__visibility_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
      )

      val allowed = FeedVisibility.allowed(minorMode)
      allowed.forEach { visibility ->
        val tooLarge = visibility == selected && visibility.needsAudience && audienceSize > FeedDraft.MAX_AUDIENCE
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton) { onSelect(visibility) }
            .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
          RadioButton(selected = visibility == selected, onClick = null)
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
              text = stringResource(visibility.label),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = if (tooLarge) stringResource(R.string.TellomiFeedCompose__audience_too_large, FeedDraft.MAX_AUDIENCE) else stringResource(visibility.description),
              style = MaterialTheme.typography.bodyMedium,
              color = if (tooLarge) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      if (minorMode) {
        Text(
          text = stringResource(R.string.TellomiFeedCompose__visibility_minor_mode),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun FeedComposeScreenPreview() {
  Previews.Preview {
    FeedComposeScreen(
      state = FeedComposeState(draft = FeedDraft(text = "今天的云很低，像是伸手就能摸到。")),
      onClose = {},
      onKindChanged = {},
      onTitleChanged = {},
      onTextChanged = {},
      onImagesPicked = {},
      onImageRemoved = {},
      onVisibilityChanged = {},
      onPublish = {},
      onFailureShown = {}
    )
  }
}

@DayNightPreviews
@Composable
private fun FeedComposeArticlePreview() {
  Previews.Preview {
    FeedComposeScreen(
      state = FeedComposeState(draft = FeedDraft(kind = FeedDraftKind.ARTICLE)),
      onClose = {},
      onKindChanged = {},
      onTitleChanged = {},
      onTextChanged = {},
      onImagesPicked = {},
      onImageRemoved = {},
      onVisibilityChanged = {},
      onPublish = {},
      onFailureShown = {}
    )
  }
}
