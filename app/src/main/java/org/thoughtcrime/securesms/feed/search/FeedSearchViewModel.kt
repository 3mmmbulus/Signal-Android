/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import org.thoughtcrime.securesms.feed.withLiked

/**
 * 动态搜索（ADR-0071 二·〇「搜索（重排）」、`dynamic-feed.md` 第三节第 34 条）。
 *
 * 边打边搜（停 [debounceMillis] 再发）；按「搜索」键或点开结果才记进本机的最近搜索。
 * 范围：全部（5 个人 + 帖子）/ 用户（20 个人）/ 动态（帖子翻页）。「话题」等话题清单定了再开。
 */
class FeedSearchViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val history: FeedSearchHistory = StoredFeedSearchHistory(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val debounceMillis: Long = 350
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedSearchViewModel::class)
  }

  private val internalState = MutableStateFlow(
    FeedSearchState(recentQueries = history.queries(), recentPeople = history.people())
  )
  val state: StateFlow<FeedSearchState> = internalState.asStateFlow()

  private var searchJob: Job? = null

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update { it.copy(mediaBaseUrl = config.mediaBaseUrl, showIpRegion = config.ipRegionVisible) }
      } catch (e: Exception) {
        Log.w(TAG, "config failed", e)
      }
    }
  }

  fun onQueryChange(query: String) {
    val clipped = query.take(FeedApi.SEARCH_MAX_QUERY)
    internalState.update { it.copy(query = clipped) }
    if (clipped.isBlank()) {
      searchJob?.cancel()
      internalState.update { it.copy(results = null, loading = false, failed = false) }
    } else {
      search(debounce = true)
    }
  }

  /** 键盘上的「搜索」。 */
  fun submit() {
    val q = internalState.value.query.trim()
    if (q.isEmpty()) return
    rememberQuery(q)
    search(debounce = false)
  }

  fun selectScope(scope: String) {
    if (scope == internalState.value.scope) return
    internalState.update { it.copy(scope = scope) }
    if (internalState.value.query.isNotBlank()) search(debounce = false)
  }

  fun useRecentQuery(query: String) {
    internalState.update { it.copy(query = query) }
    submit()
  }

  fun removeRecentQuery(query: String) {
    history.removeQuery(query)
    internalState.update { it.copy(recentQueries = history.queries()) }
  }

  fun clearRecentQueries() {
    history.clearQueries()
    internalState.update { it.copy(recentQueries = history.queries()) }
  }

  fun clearRecentPeople() {
    history.clearPeople()
    internalState.update { it.copy(recentPeople = history.people()) }
  }

  /** 点开一个人：记进「最近看过的人」和最近搜索。 */
  fun onPersonOpened(person: FeedPersonSummary) {
    history.addPerson(person)
    internalState.value.query.trim().takeIf { it.isNotEmpty() }?.let { rememberQuery(it) }
    internalState.update { it.copy(recentPeople = history.people()) }
  }

  fun onPostOpened() {
    internalState.value.query.trim().takeIf { it.isNotEmpty() }?.let { rememberQuery(it) }
  }

  fun loadMore() {
    val results = internalState.value.results ?: return
    val cursor = results.cursor ?: return
    if (searchJob?.isActive == true) return
    val query = results.query
    val scope = results.scope
    internalState.update { it.copy(loadingMore = true) }
    searchJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.search(query, scope, cursor) }
        internalState.update { state ->
          val current = state.results
          if (current == null || current.query != query || current.scope != scope) return@update state.copy(loadingMore = false)
          val known = current.posts.mapTo(HashSet()) { it.id }
          state.copy(
            loadingMore = false,
            results = current.copy(posts = current.posts + page.posts.filterNot { it.id in known }, cursor = page.cursor)
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "search more failed", e)
        internalState.update { it.copy(loadingMore = false) }
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
      }
    }
  }

  fun retry() {
    if (internalState.value.query.isNotBlank()) search(debounce = false)
  }

  private fun search(debounce: Boolean) {
    searchJob?.cancel()
    val query = internalState.value.query.trim()
    val scope = internalState.value.scope
    if (query.isEmpty()) return
    internalState.update { it.copy(loading = true, failed = false, loadingMore = false) }
    searchJob = viewModelScope.launch {
      if (debounce) delay(debounceMillis)
      try {
        val page = withContext(ioDispatcher) { source.search(query, scope, null) }
        internalState.update {
          it.copy(loading = false, results = FeedSearchResults(query = query, scope = scope, users = page.users, posts = page.posts, cursor = page.cursor))
        }
      } catch (e: Exception) {
        Log.w(TAG, "search failed", e)
        internalState.update { it.copy(loading = false, failed = true) }
      }
    }
  }

  private fun rememberQuery(query: String) {
    history.addQuery(query)
    internalState.update { it.copy(recentQueries = history.queries()) }
  }

  private fun updatePost(id: String, change: (FeedCard) -> FeedCard) {
    internalState.update { state ->
      val results = state.results ?: return@update state
      state.copy(results = results.copy(posts = results.posts.map { if (it.id == id) change(it) else it }))
    }
  }
}

/** 一次搜索的结果，记着是哪个词、哪个范围搜出来的。 */
data class FeedSearchResults(
  val query: String,
  val scope: String,
  val users: List<FeedSearchUser> = emptyList(),
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null
)

data class FeedSearchState(
  val query: String = "",
  val scope: String = FeedApi.SEARCH_ALL,
  val results: FeedSearchResults? = null,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val failed: Boolean = false,
  val recentQueries: List<String> = emptyList(),
  val recentPeople: List<FeedPersonSummary> = emptyList(),
  val mediaBaseUrl: String? = null,
  val showIpRegion: Boolean = false
)
