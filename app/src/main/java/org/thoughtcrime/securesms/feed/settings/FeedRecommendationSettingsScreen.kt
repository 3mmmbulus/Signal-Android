/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Snackbars
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.signal.core.ui.R as CoreUiR

object FeedRecommendationSettingsTestTags {
  const val PERSONALIZED = "feed_rec_personalized"
  const val DELETE = "feed_rec_delete_"
  const val RESET = "feed_rec_reset"
}

/**
 * 「动态设置」：个性化推荐开关、兴趣标签（只有自己看得到，能删）、重置推荐（确认两次）。
 *
 * 第 12 条其余几项（推荐风格、类别增减、敏感内容、屏蔽关键词、暂停推荐）后面加在这一页。
 */
@Composable
fun FeedRecommendationSettingsScreen(
  state: FeedRecommendationSettingsState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onPersonalizedChanged: (Boolean) -> Unit,
  onDeleteInterest: (String, FeedInterest) -> Unit,
  onReset: () -> Unit,
  onNoticeShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val noticeText = state.notice?.let {
    stringResource(if (it == FeedRecommendationSettingsState.Notice.RESET) R.string.TellomiFeedRecommendation__reset_done else R.string.TellomiFeed__action_failed)
  }
  LaunchedEffect(state.notice) {
    if (noticeText != null) {
      snackbarHostState.showSnackbar(noticeText)
      onNoticeShown()
    }
  }

  // 0 = 没在确认，1 = 第一次确认，2 = 第二次确认
  var resetStep by rememberSaveable { mutableIntStateOf(0) }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedRecommendation__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    val preferences = state.preferences
    val interests = state.interests
    when {
      preferences == null && state.loadFailed -> Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(text = stringResource(R.string.TellomiFeed__load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
          TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
        }
      }
      preferences == null || interests == null -> Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) {
        CircularProgressIndicator()
      }
      else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
        item {
          Rows.ToggleRow(
            checked = preferences.personalized,
            text = stringResource(R.string.TellomiFeedRecommendation__personalized),
            label = stringResource(R.string.TellomiFeedRecommendation__personalized_description),
            onCheckChanged = onPersonalizedChanged,
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.PERSONALIZED)
          )
        }

        item { Dividers.Default() }
        item { Texts.SectionHeader(stringResource(R.string.TellomiFeedRecommendation__interests)) }
        item {
          Text(
            text = stringResource(R.string.TellomiFeedRecommendation__interests_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Rows.defaultPadding())
          )
        }

        if (interests.isEmpty) {
          item {
            Text(
              text = stringResource(R.string.TellomiFeedRecommendation__interests_empty),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(Rows.defaultPadding())
            )
          }
        } else {
          interestGroup(R.string.TellomiFeedRecommendation__topics, FeedInterests.DIM_TOPIC, interests.topics, onDeleteInterest)
          interestGroup(R.string.TellomiFeedRecommendation__authors, FeedInterests.DIM_AUTHOR, interests.authors, onDeleteInterest)
          interestGroup(R.string.TellomiFeedRecommendation__sources, FeedInterests.DIM_SOURCE, interests.sources, onDeleteInterest)
        }

        item { Dividers.Default() }
        item {
          Rows.TextRow(
            text = {
              Column {
                Text(
                  text = stringResource(R.string.TellomiFeedRecommendation__reset),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.error
                )
                Text(
                  text = stringResource(R.string.TellomiFeedRecommendation__reset_description),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            },
            enabled = !state.resetting,
            onClick = { resetStep = 1 },
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.RESET)
          )
        }
      }
    }
  }

  when (resetStep) {
    1 -> Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedRecommendation__reset_title),
      body = stringResource(R.string.TellomiFeedRecommendation__reset_body),
      confirm = stringResource(R.string.TellomiFeedRecommendation__continue),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = { resetStep = 2 },
      onDismiss = { if (resetStep == 1) resetStep = 0 }
    )
    2 -> Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedRecommendation__reset_confirm_title),
      body = stringResource(R.string.TellomiFeedRecommendation__reset_confirm_body),
      confirm = stringResource(R.string.TellomiFeedRecommendation__reset),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = {
        resetStep = 0
        onReset()
      },
      onDismiss = { resetStep = 0 },
      confirmColor = MaterialTheme.colorScheme.error
    )
  }
}

private fun LazyListScope.interestGroup(
  title: Int,
  dimension: String,
  interests: List<FeedInterest>,
  onDelete: (String, FeedInterest) -> Unit
) {
  if (interests.isEmpty()) return
  item(key = "header-$dimension") {
    Text(
      text = stringResource(title),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
    )
  }
  items(interests, key = { "$dimension-${it.key}" }) { interest ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)
    ) {
      Text(
        text = interestLabel(dimension, interest),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f)
      )
      IconButton(
        onClick = { onDelete(dimension, interest) },
        modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.DELETE + "$dimension-${interest.key}")
      ) {
        Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedRecommendation__delete))
      }
    }
  }
}

@Composable
private fun interestLabel(dimension: String, interest: FeedInterest): String {
  if (dimension == FeedInterests.DIM_TOPIC) {
    return FeedTopicNames.of(interest.key)?.let { stringResource(it) } ?: "#${interest.key}"
  }
  return interest.label?.takeIf { it.isNotBlank() } ?: interest.key
}
