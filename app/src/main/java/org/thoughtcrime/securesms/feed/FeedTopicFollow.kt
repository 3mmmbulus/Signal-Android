/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.settings.FeedTopicNames

/** 话题的显示名：认识的类别用本地文案，不认识的直接显示 slug。 */
@Composable
fun feedTopicLabel(slug: String): String = FeedTopicNames.of(slug)?.let { stringResource(it) } ?: slug

data class FeedTopicFollowState(
  val loading: Boolean = true,
  val loadFailed: Boolean = false,
  val following: Boolean = false,
  /** 关注 / 取消没成功，已经改回原样。 */
  val changeFailed: Boolean = false
)

/**
 * 卡片上点话题弹出的面板：关注 / 取消关注这个话题（第三节第 18 条：话题的新动态进「关注」栏）。
 * 引擎只有「我关注的话题」名单，没有单个话题的状态，打开时查一次名单。
 */
class FeedTopicFollowViewModel(
  val slug: String,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedTopicFollowViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedTopicFollowState())
  val state: StateFlow<FeedTopicFollowState> = internalState.asStateFlow()

  private var job: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    job?.cancel()
    internalState.update { it.copy(loading = true, loadFailed = false, changeFailed = false) }
    job = viewModelScope.launch {
      try {
        val followed = withContext(ioDispatcher) { source.followedTopics() }
        internalState.update { it.copy(loading = false, following = slug in followed) }
      } catch (e: Exception) {
        Log.w(TAG, "followed topics failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  /** 先改按钮，失败改回来。 */
  fun toggle() {
    val before = internalState.value
    if (before.loading || before.loadFailed) return
    val target = !before.following
    internalState.update { it.copy(following = target, changeFailed = false) }
    job?.cancel()
    job = viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setFollowingTopic(slug, target) }
      } catch (e: Exception) {
        Log.w(TAG, "follow topic failed", e)
        internalState.update { it.copy(following = !target, changeFailed = true) }
      }
    }
  }
}

object FeedTopicFollowTestTags {
  const val SHEET = "feed_topic_follow_sheet"
  const val FOLLOW = "feed_topic_follow"
  const val UNFOLLOW = "feed_topic_unfollow"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTopicFollowSheet(topic: FeedTopic, onDismiss: () -> Unit) {
  val viewModel: FeedTopicFollowViewModel = viewModel(key = "feed-topic-${topic.slug}") { FeedTopicFollowViewModel(topic.slug) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  // 同一个页面里再打开时名单可能变过（别处取消了关注），重新查
  LaunchedEffect(topic.slug) { if (!state.loading) viewModel.refresh() }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    FeedTopicFollowContent(
      label = topic.label.ifBlank { feedTopicLabel(topic.slug) },
      state = state,
      onToggle = viewModel::toggle,
      onRetry = viewModel::refresh,
      modifier = Modifier.padding(bottom = 24.dp)
    )
  }
}

@Composable
fun FeedTopicFollowContent(
  label: String,
  state: FeedTopicFollowState,
  onToggle: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(FeedTopicFollowTestTags.SHEET)
  ) {
    Text(text = "#$label", style = MaterialTheme.typography.titleLarge)
    Text(
      text = stringResource(R.string.TellomiFeedTopicFollow__follow_explain),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    when {
      state.loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
      state.loadFailed -> {
        Text(stringResource(R.string.TellomiFeedTopicFollow__load_failed), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      state.following -> OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag(FeedTopicFollowTestTags.UNFOLLOW)) {
        Text(stringResource(R.string.TellomiFeedTopicFollow__following))
      }
      else -> Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag(FeedTopicFollowTestTags.FOLLOW)) {
        Text(stringResource(R.string.TellomiFeedTopicFollow__follow))
      }
    }
    if (state.changeFailed) {
      Text(
        text = stringResource(R.string.TellomiFeed__action_failed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
      )
    }
  }
}
