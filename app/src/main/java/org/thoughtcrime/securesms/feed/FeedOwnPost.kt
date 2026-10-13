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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.compose.FeedReplyPolicy
import org.thoughtcrime.securesms.feed.compose.FeedReplySettings
import org.thoughtcrime.securesms.feed.compose.FeedReplySettingsContent
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPostInsights
import org.thoughtcrime.securesms.feed.net.FeedPostSettings
import org.thoughtcrime.securesms.feed.net.FeedPostSettingsUpdate

/** 自己帖子「···」里除了编辑、删除以外的几项（`dynamic-feed.md` 第三节第 26 条）。 */
enum class FeedOwnPostAction {
  PIN,
  UNPIN,
  HIDE_LIKES,
  SHOW_LIKES,

  /** 回复选项：打开面板读当前值再改（卡片自己处理，不走 onOwnPostAction）。 */
  REPLY_SETTINGS,
  INSIGHTS;

  val label: Int
    get() = when (this) {
      PIN -> R.string.TellomiFeed__pin
      UNPIN -> R.string.TellomiFeed__unpin
      HIDE_LIKES -> R.string.TellomiFeed__hide_likes
      SHOW_LIKES -> R.string.TellomiFeed__show_likes
      REPLY_SETTINGS -> R.string.TellomiFeedCompose__replies_title
      INSIGHTS -> R.string.TellomiFeed__insights
    }

  /** 要发给引擎的设置；「回复选项」「查看数据」不是一下就改，是 null。 */
  val settingsUpdate: FeedPostSettingsUpdate?
    get() = when (this) {
      PIN -> FeedPostSettingsUpdate(pinned = true)
      UNPIN -> FeedPostSettingsUpdate(pinned = false)
      HIDE_LIKES -> FeedPostSettingsUpdate(likeCountHidden = true)
      SHOW_LIKES -> FeedPostSettingsUpdate(likeCountHidden = false)
      REPLY_SETTINGS, INSIGHTS -> null
    }

  companion object {
    /** 这张卡片「···」里该给哪几项，按菜单顺序。 */
    fun available(card: FeedCard): List<FeedOwnPostAction> {
      if (!card.viewer.isAuthor || card.kind == FeedCard.KIND_COMMENT) return emptyList()
      return listOf(
        if (card.pinned) UNPIN else PIN,
        REPLY_SETTINGS,
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
  const val REPLY_SETTINGS = "feed-reply-settings"
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

data class FeedPostReplySettingsState(
  val loading: Boolean = true,
  val loadFailed: Boolean = false,
  val settings: FeedReplySettings = FeedReplySettings(),
  /** 上一次改动没成功，已经改回原样。 */
  val saveFailed: Boolean = false
)

/**
 * 发布后改「回复选项」（第三节第 26 条）：打开时读当前值，每改一项只发那一项，失败改回来。
 * 新规则只管之后的评论（第 40 条），已有的评论不动。
 */
class FeedPostReplySettingsViewModel(
  private val contentId: String,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedPostReplySettingsViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedPostReplySettingsState())
  val state: StateFlow<FeedPostReplySettingsState> = internalState.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    internalState.update { it.copy(loading = true, loadFailed = false, saveFailed = false) }
    viewModelScope.launch {
      try {
        val settings = withContext(ioDispatcher) { source.postSettings(contentId) }
        internalState.update { it.copy(loading = false, settings = settings.toReplySettings()) }
      } catch (e: Exception) {
        Log.w(TAG, "post settings failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  fun onChange(next: FeedReplySettings) {
    val before = internalState.value
    if (before.loading || before.loadFailed) return
    val update = before.settings.diff(next) ?: return
    internalState.update { it.copy(settings = next, saveFailed = false) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.updatePostSettings(contentId, update) }
      } catch (e: Exception) {
        Log.w(TAG, "update reply settings failed", e)
        internalState.update { it.copy(settings = before.settings, saveFailed = true) }
      }
    }
  }
}

/** 引擎的取值换成面板用的；不认识的 `reply_policy` 当「所有人」。 */
internal fun FeedPostSettings.toReplySettings(): FeedReplySettings {
  return FeedReplySettings(
    policy = FeedReplyPolicy.entries.firstOrNull { it.apiName == replyPolicy } ?: FeedReplyPolicy.EVERYONE,
    commentsClosed = commentsClosed,
    moderation = replyModeration,
    voiceComments = voiceComments
  )
}

/** 只带变了的字段；没变是 null。 */
internal fun FeedReplySettings.diff(next: FeedReplySettings): FeedPostSettingsUpdate? {
  if (this == next) return null
  return FeedPostSettingsUpdate(
    replyPolicy = next.policy.apiName.takeIf { next.policy != policy },
    commentsClosed = next.commentsClosed.takeIf { it != commentsClosed },
    replyModeration = next.moderation.takeIf { it != moderation },
    voiceComments = next.voiceComments.takeIf { it != voiceComments }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPostReplySettingsSheet(contentId: String, onDismiss: () -> Unit) {
  val viewModel: FeedPostReplySettingsViewModel = viewModel(key = "feed-reply-settings-$contentId") { FeedPostReplySettingsViewModel(contentId) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  ModalBottomSheet(onDismissRequest = onDismiss) {
    FeedPostReplySettingsContent(state = state, onChange = viewModel::onChange, onRetry = viewModel::refresh, modifier = Modifier.padding(bottom = 24.dp))
  }
}

@Composable
fun FeedPostReplySettingsContent(
  state: FeedPostReplySettingsState,
  onChange: (FeedReplySettings) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxWidth().testTag(FeedOwnPostTestTags.REPLY_SETTINGS)) {
    when {
      state.loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(24.dp).align(Alignment.CenterHorizontally))
      state.loadFailed -> Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.TellomiFeedTopicFollow__load_failed), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      else -> {
        FeedReplySettingsContent(settings = state.settings, onChange = onChange, showVoiceComments = true)
        Text(
          text = stringResource(R.string.TellomiFeed__reply_settings_future_only),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        if (state.saveFailed) {
          Text(
            text = stringResource(R.string.TellomiFeed__action_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp)
          )
        }
      }
    }
  }
}
