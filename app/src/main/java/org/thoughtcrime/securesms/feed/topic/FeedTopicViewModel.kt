/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.topic

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
import org.thoughtcrime.securesms.feed.FeedSavedMessages
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.NoteToSelfFeedSavedMessages
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.setSaved
import org.thoughtcrime.securesms.feed.withLiked
import org.thoughtcrime.securesms.feed.withSaved

/**
 * 话题页：带这个话题的帖子按时间倒序，头上一个「关注话题」（第三节第 18 条：关注的话题进「关注」栏）。
 * 过滤（屏蔽、不看 TA、不感兴趣、可见范围）都在引擎；屏蔽了这个话题就是空页。
 */
class FeedTopicViewModel(
  val slug: String,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val savedMessages: FeedSavedMessages = NoteToSelfFeedSavedMessages
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedTopicViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedTopicState())
  val state: StateFlow<FeedTopicState> = internalState.asStateFlow()

  private var postsJob: Job? = null
  private var followJob: Job? = null

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update { it.copy(mediaBaseUrl = config.mediaBaseUrl, showIpRegion = config.ipRegionVisible) }
      } catch (e: Exception) {
        Log.w(TAG, "config failed", e)
      }
    }
    refresh()
  }

  fun refresh() {
    load(cursor = null)
  }

  fun loadMore() {
    val cursor = internalState.value.cursor ?: return
    if (postsJob?.isActive == true) return
    load(cursor)
  }

  private fun load(cursor: String?) {
    postsJob?.cancel()
    internalState.update { it.copy(loading = true, failed = false) }
    postsJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.topicPosts(slug, cursor) }
        internalState.update { state ->
          val base = if (cursor == null) emptyList() else state.posts
          val known = base.mapTo(HashSet()) { it.id }
          state.copy(
            posts = base + page.posts.filterNot { it.id in known },
            cursor = page.cursor,
            loading = false,
            loadedOnce = true,
            // 关注按钮在路上时不拿回包盖掉它
            following = if (followJob?.isActive == true) state.following else page.following
          )
        }
      } catch (e: FeedApiException) {
        Log.w(TAG, "topic rejected: ${e.status} ${e.code}")
        internalState.update { it.copy(loading = false, loadedOnce = true, failed = e.status != 404, gone = e.status == 404) }
      } catch (e: Exception) {
        Log.w(TAG, "topic failed", e)
        internalState.update { it.copy(loading = false, loadedOnce = true, failed = true) }
      }
    }
  }

  /** 关注 / 取消关注：先改按钮，失败改回来。关注成功给一条带「撤销」的提示。 */
  fun toggleFollow() {
    val before = internalState.value
    if (!before.loadedOnce || before.gone || followJob?.isActive == true) return
    val target = !before.following
    internalState.update { it.copy(following = target) }
    followJob = viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setFollowingTopic(slug, target) }
        internalState.update { it.copy(notice = if (target) FeedTopicState.Notice.FOLLOWED else FeedTopicState.Notice.UNFOLLOWED) }
      } catch (e: Exception) {
        Log.w(TAG, "follow topic failed", e)
        internalState.update { it.copy(following = !target, notice = FeedTopicState.Notice.FAILED) }
      }
    }
  }

  fun toggleLike(card: FeedCard) {
    val nowLiked = !card.viewer.liked
    updatePost(card.id) { it.withLiked(nowLiked) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setLiked(card.id, nowLiked) }
      } catch (e: Exception) {
        Log.w(TAG, "like failed", e)
        updatePost(card.id) { it.withLiked(!nowLiked) }
        internalState.update { it.copy(notice = FeedTopicState.Notice.FAILED) }
      }
    }
  }

  private val saving = mutableSetOf<String>()

  /** 收藏 / 取消收藏（第三节第 27 条）：先改书签，存不进「我的收藏」就改回来。 */
  fun toggleSave(card: FeedCard) {
    if (!saving.add(card.id)) return
    val nowSaved = !card.viewer.saved
    updatePost(card.id) { it.withSaved(nowSaved) }
    viewModelScope.launch {
      try {
        savedMessages.setSaved(card, nowSaved, source, ioDispatcher)
        if (nowSaved) internalState.update { it.copy(notice = FeedTopicState.Notice.SAVED) }
      } catch (e: Exception) {
        Log.w(TAG, "saved messages failed", e)
        updatePost(card.id) { it.withSaved(!nowSaved) }
        internalState.update { it.copy(notice = FeedTopicState.Notice.FAILED) }
      } finally {
        saving -= card.id
      }
    }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  private fun updatePost(id: String, change: (FeedCard) -> FeedCard) {
    internalState.update { state -> state.copy(posts = state.posts.map { if (it.id == id) change(it) else it }) }
  }
}

data class FeedTopicState(
  val following: Boolean = false,
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null,
  val loading: Boolean = true,
  val loadedOnce: Boolean = false,
  val failed: Boolean = false,
  /** 引擎不认这个话题（slug 不合法）。 */
  val gone: Boolean = false,
  val notice: Notice? = null,
  val mediaBaseUrl: String? = null,
  val showIpRegion: Boolean = false
) {
  enum class Notice {
    /** 底部提示条带「撤销」（第三节「统一反馈」）。 */
    FOLLOWED,
    UNFOLLOWED,

    /** 存进了「我的收藏」。 */
    SAVED,
    FAILED
  }
}
