/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

internal object FeedComposePollTestTags {
  const val OPTION = "feed_compose_poll_option_"
  const val REMOVE_OPTION = "feed_compose_poll_remove_option_"
  const val ADD_OPTION = "feed_compose_poll_add_option"
  const val MULTI = "feed_compose_poll_multi"
  const val REMOVE = "feed_compose_poll_remove"
}

/** 发布页上投票的几个动作；默认什么都不做，方便只关心别的部分的预览和测试。 */
data class FeedComposePollActions(
  val onAdd: () -> Unit = {},
  val onRemove: () -> Unit = {},
  val onOptionChanged: (Int, String) -> Unit = { _, _ -> },
  val onOptionAdded: () -> Unit = {},
  val onOptionRemoved: (Int) -> Unit = {},
  val onMultiChanged: (Boolean) -> Unit = {},
  val onDurationChanged: (FeedPollDuration) -> Unit = {}
)

/**
 * 正文下面的投票编辑块：选项一行一个（超过两个时能删），「添加选项」到 10 个为止，「可多选」开关，截止时间三档。
 *
 * 问题就是上面的正文，所以这里不再有问题输入框。
 */
@Composable
fun FeedComposePollEditor(
  poll: FeedDraftPoll,
  actions: FeedComposePollActions,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.TellomiFeedCompose__poll),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f)
        )
        IconButton(onClick = actions.onRemove, modifier = Modifier.testTag(FeedComposePollTestTags.REMOVE)) {
          Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedCompose__poll_remove), modifier = Modifier.size(20.dp))
        }
      }

      poll.options.forEachIndexed { index, option ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)) {
          PollOptionField(
            value = option,
            onValueChange = { actions.onOptionChanged(index, it) },
            placeholder = stringResource(R.string.TellomiFeedCompose__poll_option_hint, index + 1),
            modifier = Modifier
              .weight(1f)
              .testTag(FeedComposePollTestTags.OPTION + index)
          )
          if (poll.options.size > FeedDraftPoll.MIN_OPTIONS) {
            IconButton(onClick = { actions.onOptionRemoved(index) }, modifier = Modifier.testTag(FeedComposePollTestTags.REMOVE_OPTION + index)) {
              Icon(
                painter = painterResource(R.drawable.symbol_minus_circle_24),
                contentDescription = stringResource(R.string.TellomiFeedCompose__poll_remove_option),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }

      if (poll.canAddOption) {
        TextButton(onClick = actions.onOptionAdded, modifier = Modifier.testTag(FeedComposePollTestTags.ADD_OPTION)) {
          Icon(painter = painterResource(CoreUiR.drawable.symbol_plus_24), contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.TellomiFeedCompose__poll_add_option))
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Text(
          text = stringResource(R.string.TellomiFeedCompose__poll_multi),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f)
        )
        Switch(checked = poll.multi, onCheckedChange = actions.onMultiChanged, modifier = Modifier.testTag(FeedComposePollTestTags.MULTI))
      }

      Text(
        text = stringResource(R.string.TellomiFeedCompose__poll_duration),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedPollDuration.entries.forEach { duration ->
          FilterChip(
            selected = duration == poll.duration,
            onClick = { actions.onDurationChanged(duration) },
            label = { Text(stringResource(duration.label)) }
          )
        }
      }
    }
  }
}

@Composable
private fun PollOptionField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier
) {
  val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = textStyle,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    modifier = modifier,
    decorationBox = { inner ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp)) {
          Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
              Text(text = placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
          }
        }
      }
    }
  )
}
