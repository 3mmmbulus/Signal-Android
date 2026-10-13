/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings

object FeedNotificationSettingsTestTags {
  const val FROM_WHOM = "feed_notification_from_whom"
}

/** 动态那一段要的回调；通知页本身不认识动态。 */
class FeedNotificationSettingsActions(
  val onFromWhom: (String) -> Unit,
  val onLikes: (Boolean) -> Unit,
  val onComments: (Boolean) -> Unit,
  val onFollows: (Boolean) -> Unit,
  val onRetry: () -> Unit
)

/**
 * 插进通知页 LazyColumn 的「动态」一段：谁的通知（所有人 / 我关注的人 / 好友 / 都不接收）和赞、评论、新关注者三个开关
 * （第三节第 43 条）。读不到时只留一行「重试」。
 */
fun LazyListScope.feedNotificationSettingsItems(state: FeedNotificationSettingsState, actions: FeedNotificationSettingsActions) {
  item {
    Dividers.Default()
  }

  item {
    Texts.SectionHeader(stringResource(R.string.TellomiFeedNotificationSettings__header))
  }

  val settings = state.settings
  if (settings == null) {
    item {
      if (state.loadFailed) {
        Rows.TextRow(
          text = stringResource(R.string.TellomiFeedNotificationSettings__load_failed),
          label = stringResource(R.string.TellomiFeedNotificationSettings__retry),
          onClick = actions.onRetry
        )
      } else {
        Rows.TextRow(text = stringResource(R.string.TellomiFeedNotificationSettings__loading), enabled = false)
      }
    }
    return
  }

  item {
    FromWhomRow(settings, actions)
  }

  item {
    Rows.ToggleRow(
      checked = settings.likes,
      text = stringResource(R.string.TellomiFeedNotificationSettings__likes),
      onCheckChanged = actions.onLikes,
      enabled = state.categoriesEnabled
    )
  }

  item {
    Rows.ToggleRow(
      checked = settings.comments,
      text = stringResource(R.string.TellomiFeedNotificationSettings__comments),
      onCheckChanged = actions.onComments,
      enabled = state.categoriesEnabled
    )
  }

  item {
    Rows.ToggleRow(
      checked = settings.follows,
      text = stringResource(R.string.TellomiFeedNotificationSettings__follows),
      onCheckChanged = actions.onFollows,
      enabled = state.categoriesEnabled
    )
  }

  item {
    Text(
      text = stringResource(R.string.TellomiFeedNotificationSettings__system_note),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(Rows.defaultPadding())
    )
  }
}

@Composable
private fun FromWhomRow(settings: FeedNotificationSettings, actions: FeedNotificationSettingsActions) {
  val labels = arrayOf(
    stringResource(R.string.TellomiFeedNotificationSettings__from_everyone),
    stringResource(R.string.TellomiFeedNotificationSettings__from_following),
    stringResource(R.string.TellomiFeedNotificationSettings__from_friends),
    stringResource(R.string.TellomiFeedNotificationSettings__from_none)
  )
  Rows.RadioListRow(
    text = stringResource(R.string.TellomiFeedNotificationSettings__from_whom),
    labels = labels,
    values = FeedNotificationSettings.FROM_VALUES.toTypedArray(),
    selectedValue = settings.fromWhom,
    onSelected = actions.onFromWhom,
    modifier = Modifier.testTag(FeedNotificationSettingsTestTags.FROM_WHOM)
  )
}
