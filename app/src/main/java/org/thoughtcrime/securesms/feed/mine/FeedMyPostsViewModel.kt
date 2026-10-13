/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.net.FeedMyPost

/**
 * 「我的动态」：自己发过的帖子和审核状态（`dynamic-feed.md` 第三节第 24 条、R-2.2；契约 7.3）。
 */
class FeedMyPostsViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedMyPostsViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedMyPostsState())
  val state: StateFlow<FeedMyPostsState> = internalState.asStateFlow()

  private var loadJob: Job? = null

  init {
    refresh()
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update { it.copy(editWindowSeconds = config.limits.editWindowSeconds) }
      } catch (e: Exception) {
        Log.w(TAG, "config failed, using the default edit window", e)
      }
    }
  }

  /** 编辑保存后：提示一次（大陆区是「审核通过后替换」），重新拉列表看新的状态。 */
  fun onEdited(publishState: String) {
    internalState.update { it.copy(editNotice = publishState) }
    refresh()
  }

  fun onEditNoticeShown() {
    internalState.update { it.copy(editNotice = null) }
  }

  fun refresh() {
    loadJob?.cancel()
    internalState.update { it.copy(loading = true, refreshing = it.loadedOnce, failed = false) }
    loadJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.myPosts(before = null) }
        internalState.update { it.copy(posts = page.posts, nextBefore = page.nextBefore, loading = false, refreshing = false, loadedOnce = true) }
      } catch (e: Exception) {
        Log.w(TAG, "my posts failed", e)
        internalState.update { it.copy(loading = false, refreshing = false, failed = true, loadedOnce = true) }
      }
    }
  }

  fun loadMore() {
    val before = internalState.value.nextBefore ?: return
    if (loadJob?.isActive == true) return
    internalState.update { it.copy(loading = true) }
    loadJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.myPosts(before = before) }
        internalState.update { state ->
          val known = state.posts.mapTo(HashSet()) { it.id }
          state.copy(posts = state.posts + page.posts.filterNot { it.id in known }, nextBefore = page.nextBefore, loading = false)
        }
      } catch (e: Exception) {
        Log.w(TAG, "my posts page failed", e)
        internalState.update { it.copy(loading = false) }
      }
    }
  }

  /** 先从列表里拿掉；删失败放回原位，提示一次。 */
  fun delete(post: FeedMyPost) {
    val index = internalState.value.posts.indexOfFirst { it.id == post.id }
    if (index < 0) return
    internalState.update { it.copy(posts = it.posts.filterNot { p -> p.id == post.id }) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.deletePost(post.id) }
      } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        internalState.update { state ->
          val restored = state.posts.toMutableList().apply { add(index.coerceAtMost(size), post) }
          state.copy(posts = restored, deleteFailed = true)
        }
      }
    }
  }

  fun onDeleteFailureShown() {
    internalState.update { it.copy(deleteFailed = false) }
  }
}

data class FeedMyPostsState(
  val posts: List<FeedMyPost> = emptyList(),
  val nextBefore: String? = null,
  val loading: Boolean = true,
  val loadedOnce: Boolean = false,
  /** 下拉刷新中（已经有内容时）；翻页不算。 */
  val refreshing: Boolean = false,
  val failed: Boolean = false,
  val deleteFailed: Boolean = false,
  /** 发布后多久内能改（config `limits.edit_window_seconds`）。 */
  val editWindowSeconds: Long = 900,
  /** 刚保存的编辑，服务端给的 `state`；提示过就清掉。 */
  val editNotice: String? = null
)
