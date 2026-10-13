/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R

object FeedNotInterestedCardTestTags {
  const val UNDO = "feed_not_interested_undo"
  const val PREFERENCES = "feed_not_interested_preferences"
  const val UNCOMFORTABLE = "feed_not_interested_uncomfortable"

  fun reason(reason: FeedNotInterestedReason) = "feed_not_interested_reason_${reason.apiName}"
}

/** 「不感兴趣」的原因（第三节第 11 条；选项 2026-10-13 按推荐先做，等 owner 在决定卡上定）。[apiName] 发给引擎。 */
enum class FeedNotInterestedReason(val apiName: String, @StringRes val label: Int) {
  TOPIC("topic", R.string.TellomiFeedNotInterested__reason_topic),
  AUTHOR("author", R.string.TellomiFeedNotInterested__reason_author),
  SEEN("seen", R.string.TellomiFeedNotInterested__reason_seen),
  LOW_QUALITY("low_quality", R.string.TellomiFeedNotInterested__reason_low_quality)
}

/**
 * 点了「不感兴趣」后原地留下的小反馈卡（第三节第 11 条）：撤销 / 管理内容偏好 / 这条让我不舒服（去举报，第 32 条先分流）。
 *
 * [askReason] 时（约一半，由 ViewModel 抽）下面多一行原因，可以不选；选了换成一句「谢谢」。
 */
@Composable
fun FeedNotInterestedCard(
  onUndo: () -> Unit,
  onManagePreferences: () -> Unit,
  onUncomfortable: () -> Unit,
  modifier: Modifier = Modifier,
  askReason: Boolean = false,
  pickedReason: FeedNotInterestedReason? = null,
  onReason: (FeedNotInterestedReason) -> Unit = {}
) {
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Text(
      text = stringResource(R.string.TellomiFeed__hidden),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface
    )
    FlowRow(modifier = Modifier.padding(top = 4.dp)) {
      TextButton(onClick = onUndo, modifier = Modifier.testTag(FeedNotInterestedCardTestTags.UNDO)) {
        Text(stringResource(R.string.TellomiFeed__undo))
      }
      TextButton(onClick = onManagePreferences, modifier = Modifier.testTag(FeedNotInterestedCardTestTags.PREFERENCES)) {
        Text(stringResource(R.string.TellomiFeedNotInterested__preferences))
      }
      TextButton(onClick = onUncomfortable, modifier = Modifier.testTag(FeedNotInterestedCardTestTags.UNCOMFORTABLE)) {
        Text(stringResource(R.string.TellomiFeedNotInterested__uncomfortable))
      }
    }
    if (askReason) {
      if (pickedReason == null) {
        Text(
          text = stringResource(R.string.TellomiFeedNotInterested__reason_ask),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FeedNotInterestedReason.entries.forEach { reason ->
            SuggestionChip(
              onClick = { onReason(reason) },
              label = { Text(stringResource(reason.label)) },
              modifier = Modifier.testTag(FeedNotInterestedCardTestTags.reason(reason))
            )
          }
        }
      } else {
        Text(
          text = stringResource(R.string.TellomiFeedNotInterested__reason_thanks),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
    }
  }
}
