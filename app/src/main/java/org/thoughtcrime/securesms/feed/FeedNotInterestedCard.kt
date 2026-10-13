/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
}

/**
 * 点了「不感兴趣」后原地留下的小反馈卡（第三节第 11 条）：撤销 / 管理内容偏好 / 这条让我不舒服（去举报，第 32 条先分流）。
 *
 * 「约一半的时候随机问原因」的概率还没定（第五节 Q-11「卡开工」），这里先不问。
 */
@Composable
fun FeedNotInterestedCard(
  onUndo: () -> Unit,
  onManagePreferences: () -> Unit,
  onUncomfortable: () -> Unit,
  modifier: Modifier = Modifier
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
  }
}
