/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedCard

/**
 * 打开一条动态的外链（`tell.cc/m#<id>`，内容引擎 docs/api.md「动态的外链」）：用 Card API 取这一张卡，
 * 取到就进详情，`unavailable` 显示「这条动态已不可见」。动态没对你开放（不在内测名单、开关关了）也按不可见处理，
 * 不另说原因，免得链接本身泄露「有这条动态」。
 */
class FeedPostLinkViewModel(
  private val contentId: String?,
  private val feedEnabled: Boolean,
  private val source: () -> FeedSource,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedPostLinkViewModel::class)
  }

  sealed interface State {
    data object Loading : State
    data class Ready(val card: FeedCard) : State
    data object Gone : State
    data object Failed : State
  }

  private val internalState = MutableStateFlow<State>(State.Loading)
  val state: StateFlow<State> = internalState.asStateFlow()

  init {
    load()
  }

  fun load() {
    if (contentId == null || !feedEnabled) {
      internalState.value = State.Gone
      return
    }
    internalState.value = State.Loading
    viewModelScope.launch {
      internalState.value = try {
        val card = withContext(ioDispatcher) { source().card(contentId) }
        if (card != null && card.isRenderable) State.Ready(card) else State.Gone
      } catch (e: Exception) {
        Log.w(TAG, "post link lookup failed", e)
        State.Failed
      }
    }
  }
}
