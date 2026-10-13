/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.settings.FeedTopicNames

/** 发布页底部「#」弹出的话题面板：内容引擎的 36 个类别，最多选 3 个。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTopicSheet(
  selected: List<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    // 36 个类别在小屏上放不下，面板里能滚
    FeedTopicPicker(selected = selected, onToggle = onToggle, modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp))
  }
}

@Composable
internal fun FeedTopicPicker(
  selected: List<String>,
  onToggle: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val full = selected.size >= FeedDraft.MAX_TOPICS
  Column(modifier = modifier) {
    Text(
      text = stringResource(R.string.TellomiFeedCompose__topics_title),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
    Text(
      text = stringResource(R.string.TellomiFeedCompose__topics_limit, selected.size, FeedDraft.MAX_TOPICS),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp)
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
      FeedTopicNames.slugs.forEach { slug ->
        val isSelected = slug in selected
        FilterChip(
          selected = isSelected,
          // 满 3 个时没选的灰掉，选了的还能点掉
          enabled = isSelected || !full,
          onClick = { onToggle(slug) },
          label = { Text("#" + stringResource(FeedTopicNames.of(slug)!!)) },
          modifier = Modifier.testTag(FeedComposeTestTags.TOPIC + slug)
        )
      }
    }
  }
}

/** 正文下面已经选的话题；[onRemove] 为 null（编辑时）只显示不能删。 */
@Composable
internal fun FeedComposeTopics(
  topics: List<String>,
  onRemove: ((String) -> Unit)?,
  modifier: Modifier = Modifier
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
    topics.forEach { slug ->
      val name = FeedTopicNames.of(slug)?.let { stringResource(it) } ?: slug
      InputChip(
        selected = true,
        enabled = onRemove != null,
        onClick = { onRemove?.invoke(slug) },
        label = { Text("#$name") },
        trailingIcon = if (onRemove != null) {
          { Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedCompose__topics_remove, name), modifier = Modifier.size(18.dp)) }
        } else {
          null
        },
        modifier = Modifier.testTag(FeedComposeTestTags.REMOVE_TOPIC + slug)
      )
    }
  }
}
