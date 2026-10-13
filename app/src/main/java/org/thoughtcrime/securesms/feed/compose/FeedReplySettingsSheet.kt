/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R

internal object FeedReplySettingsTestTags {
  const val POLICY = "feed_reply_policy_"
  const val CLOSED = "feed_reply_closed"
  const val MODERATION = "feed_reply_moderation"
  const val VOICE = "feed_reply_voice"
}

/** 发布页底部「回复选项」弹出的面板（第三节第 40 条）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedReplySettingsSheet(
  settings: FeedReplySettings,
  onChange: (FeedReplySettings) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    FeedReplySettingsContent(settings = settings, onChange = onChange, modifier = Modifier.padding(bottom = 24.dp))
  }
}

@Composable
internal fun FeedReplySettingsContent(
  settings: FeedReplySettings,
  onChange: (FeedReplySettings) -> Unit,
  modifier: Modifier = Modifier,
  showVoiceComments: Boolean = false
) {
  Column(modifier = modifier) {
    Text(
      text = stringResource(R.string.TellomiFeedCompose__replies_title),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )

    // 关了评论时「谁能回复」没有意义，灰掉但保留选择，打开评论后还在
    val policyEnabled = !settings.commentsClosed
    FeedReplyPolicy.entries.forEach { policy ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = policyEnabled, role = Role.RadioButton) { onChange(settings.copy(policy = policy)) }
          .padding(horizontal = 16.dp, vertical = 4.dp)
          .testTag(FeedReplySettingsTestTags.POLICY + policy.name)
      ) {
        RadioButton(selected = policy == settings.policy, onClick = null, enabled = policyEnabled)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = stringResource(policy.label),
          style = MaterialTheme.typography.bodyLarge,
          color = if (policyEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 10.dp)
        )
      }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    SwitchRow(
      title = stringResource(R.string.TellomiFeedCompose__replies_closed),
      description = stringResource(R.string.TellomiFeedCompose__replies_closed_description),
      checked = settings.commentsClosed,
      onCheckedChange = { onChange(settings.copy(commentsClosed = it)) },
      tag = FeedReplySettingsTestTags.CLOSED
    )
    SwitchRow(
      title = stringResource(R.string.TellomiFeedCompose__replies_moderation),
      description = stringResource(R.string.TellomiFeedCompose__replies_moderation_description),
      checked = settings.moderation,
      enabled = !settings.commentsClosed,
      onCheckedChange = { onChange(settings.copy(moderation = it)) },
      tag = FeedReplySettingsTestTags.MODERATION
    )
    if (showVoiceComments) {
      SwitchRow(
        title = stringResource(R.string.TellomiFeed__voice_comments),
        description = stringResource(R.string.TellomiFeed__voice_comments_description),
        checked = settings.voiceComments,
        enabled = !settings.commentsClosed,
        onCheckedChange = { onChange(settings.copy(voiceComments = it)) },
        tag = FeedReplySettingsTestTags.VOICE
      )
    }
  }
}

@Composable
private fun SwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  tag: String,
  enabled: Boolean = true
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
      .padding(horizontal = 24.dp, vertical = 10.dp)
      .testTag(tag)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(modifier = Modifier.width(16.dp))
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}
