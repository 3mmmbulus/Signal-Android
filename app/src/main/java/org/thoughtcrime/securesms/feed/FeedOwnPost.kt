/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPostInsights
import org.thoughtcrime.securesms.feed.net.FeedPostSettingsUpdate

/** 自己帖子「···」里除了编辑、删除以外的几项（`dynamic-feed.md` 第三节第 26 条）。 */
enum class FeedOwnPostAction {
  PIN,
  UNPIN,
  HIDE_LIKES,
  SHOW_LIKES,
  INSIGHTS;

  val label: Int
    get() = when (this) {
      PIN -> R.string.TellomiFeed__pin
      UNPIN -> R.string.TellomiFeed__unpin
      HIDE_LIKES -> R.string.TellomiFeed__hide_likes
      SHOW_LIKES -> R.string.TellomiFeed__show_likes
      INSIGHTS -> R.string.TellomiFeed__insights
    }

  /** 要发给引擎的设置；「查看数据」不改设置，是 null。 */
  val settingsUpdate: FeedPostSettingsUpdate?
    get() = when (this) {
      PIN -> FeedPostSettingsUpdate(pinned = true)
      UNPIN -> FeedPostSettingsUpdate(pinned = false)
      HIDE_LIKES -> FeedPostSettingsUpdate(likeCountHidden = true)
      SHOW_LIKES -> FeedPostSettingsUpdate(likeCountHidden = false)
      INSIGHTS -> null
    }

  companion object {
    /** 这张卡片「···」里该给哪几项，按菜单顺序。 */
    fun available(card: FeedCard): List<FeedOwnPostAction> {
      if (!card.viewer.isAuthor || card.kind == FeedCard.KIND_COMMENT) return emptyList()
      return listOf(
        if (card.pinned) UNPIN else PIN,
        if (card.stats.likeCountHidden) SHOW_LIKES else HIDE_LIKES,
        INSIGHTS
      )
    }
  }
}

/** 先改界面用：把设置套到卡片上。 */
internal fun FeedCard.withSettings(update: FeedPostSettingsUpdate): FeedCard {
  return copy(
    pinned = update.pinned ?: pinned,
    stats = stats.copy(likeCountHidden = update.likeCountHidden ?: stats.likeCountHidden)
  )
}

/** 设置没改成时的提示：置顶满了单独说，其他一律「操作失败」。 */
internal fun Exception.isPinLimit(): Boolean = this is FeedApiException && code == "too_many_pinned"

/** 「查看数据」弹窗的状态。 */
sealed interface FeedInsightsState {
  data object Loading : FeedInsightsState
  data class Ready(val insights: FeedPostInsights) : FeedInsightsState
  data object Failed : FeedInsightsState
}

object FeedOwnPostTestTags {
  const val INSIGHTS_DIALOG = "feed-insights-dialog"
}

@Composable
fun FeedInsightsDialog(state: FeedInsightsState, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.TellomiFeed__insights)) },
    text = {
      Box(modifier = Modifier.fillMaxWidth().testTag(FeedOwnPostTestTags.INSIGHTS_DIALOG)) {
        when (state) {
          FeedInsightsState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.Center))
          FeedInsightsState.Failed -> Text(stringResource(R.string.TellomiFeedInsights__load_failed))
          is FeedInsightsState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InsightRow(R.string.TellomiFeedInsights__opens, state.insights.opens)
            InsightRow(R.string.TellomiFeedInsights__likes, state.insights.likes)
            InsightRow(R.string.TellomiFeedInsights__comments, state.insights.comments)
            InsightRow(R.string.TellomiFeedInsights__reposts, state.insights.reposts)
            InsightRow(R.string.TellomiFeedInsights__saves, state.insights.saves)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
    }
  )
}

@Composable
private fun InsightRow(label: Int, value: Int) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Text(text = value.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
