/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedPoll
import org.thoughtcrime.securesms.feed.net.FeedPollState
import org.signal.core.ui.R as CoreUiR

object FeedPollTestTags {
  const val OPTION = "feed_poll_option_"
  const val SUBMIT = "feed_poll_submit"
}

/**
 * 帖子里的投票（第三节第 22 条；交互照 Threads，第 2 条）。
 *
 * - 没投、没截止：单选点一项就投；多选先勾，再点「投票」。
 * - 投过或截止了：每项一条按票数比例填色的条，右边百分比，我选的打勾。投了不能改（引擎「每人一次」）。
 * - [onVote] 是 null 的地方（比如搜索结果）只看不投，没投过也不显示票数，点卡片进详情再投。
 */
@Composable
fun FeedPollView(
  poll: FeedPoll,
  state: FeedPollState?,
  onVote: ((List<Int>) -> Unit)?,
  modifier: Modifier = Modifier,
  nowMillis: Long = System.currentTimeMillis()
) {
  val closesAt = poll.closesAt?.let { parseInstantMillis(it) }
  val closed = closesAt != null && closesAt <= nowMillis
  val voted = state?.hasVoted == true
  val showResults = voted || closed
  val counts = state?.counts.orEmpty()
  val total = counts.sum()
  // 连点由 ViewModel 挡（同一条在投的时候不再发）
  var checked by remember(state) { mutableStateOf(setOf<Int>()) }
  val canVote = !showResults && onVote != null

  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
    poll.options.forEachIndexed { index, option ->
      val count = counts.getOrElse(index) { 0 }
      val mine = state?.voted?.contains(index) == true
      val fraction = if (showResults && total > 0) count.toFloat() / total else 0f
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 44.dp)
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
          .then(
            if (canVote) {
              Modifier.clickable {
                if (poll.multi) {
                  checked = if (index in checked) checked - index else checked + index
                } else {
                  onVote?.invoke(listOf(index))
                }
              }
            } else {
              Modifier
            }
          )
          .testTag(FeedPollTestTags.OPTION + index)
      ) {
        if (showResults) {
          Box(modifier = Modifier.matchParentSize()) {
            Box(
              modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            )
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
          if (!showResults && poll.multi && onVote != null) {
            Checkbox(checked = index in checked, onCheckedChange = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
          }
          Text(
            text = option,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          if (mine) {
            Icon(
              imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_check_circle_24),
              contentDescription = stringResource(R.string.TellomiFeedPoll__your_choice),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(start = 6.dp).size(18.dp)
            )
          }
          if (showResults) {
            Text(
              text = "${percent(count, total)}%",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 8.dp)
            )
          }
        }
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = pollFooter(poll = poll, total = total, showVotes = showResults || onVote != null, closesAt = closesAt, nowMillis = nowMillis),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f)
      )
      if (canVote && poll.multi) {
        TextButton(
          onClick = { onVote?.invoke(checked.sorted()) },
          enabled = checked.isNotEmpty(),
          modifier = Modifier.testTag(FeedPollTestTags.SUBMIT)
        ) {
          Text(stringResource(R.string.TellomiFeedPoll__vote))
        }
      }
    }
  }
}

/** 四舍五入的百分比；各项加起来不一定正好 100，和多数产品一样不去凑。 */
internal fun percent(count: Int, total: Int): Int = if (total <= 0) 0 else Math.round(count * 100f / total)

@Composable
private fun pollFooter(poll: FeedPoll, total: Int, showVotes: Boolean, closesAt: Long?, nowMillis: Long): String {
  val parts = mutableListOf<String>()
  if (showVotes) parts += pluralStringResource(R.plurals.TellomiFeedPoll__votes, total, total)
  if (poll.multi) parts += stringResource(R.string.TellomiFeedPoll__multi)
  if (closesAt != null) {
    val left = closesAt - nowMillis
    parts += when {
      left <= 0 -> stringResource(R.string.TellomiFeedPoll__closed)
      left >= DAY_MILLIS -> (left / DAY_MILLIS).toInt().let { pluralStringResource(R.plurals.TellomiFeedPoll__days_left, it, it) }
      left >= HOUR_MILLIS -> (left / HOUR_MILLIS).toInt().let { pluralStringResource(R.plurals.TellomiFeedPoll__hours_left, it, it) }
      else -> ((left / MINUTE_MILLIS).toInt().coerceAtLeast(1)).let { pluralStringResource(R.plurals.TellomiFeedPoll__minutes_left, it, it) }
    }
  }
  return parts.joinToString(" · ")
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
