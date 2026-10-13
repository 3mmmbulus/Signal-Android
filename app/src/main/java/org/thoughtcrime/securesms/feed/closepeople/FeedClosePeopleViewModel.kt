/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.closepeople

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
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedClosePersonEntry
import org.thoughtcrime.securesms.feed.net.FeedSearchUser

/**
 * 「关心的人」（第三节第 42 条）：最多设 3 个，对方同意后才在我的主页上显示；也在这里回应设了我的人。
 *
 * 加人靠搜 ID 或名字（和动态搜索的「用户」一样，只搜得到设了 ID 的人）。
 * 移除、回应都先在界面上改过去，失败再改回来。
 */
class FeedClosePeopleViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val debounceMillis: Long = 350
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedClosePeopleViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedClosePeopleState())
  val state: StateFlow<FeedClosePeopleState> = internalState.asStateFlow()

  private var searchJob: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    internalState.update { it.copy(loading = true, failed = false) }
    viewModelScope.launch {
      try {
        val response = withContext(ioDispatcher) { source.closePeople() }
        internalState.update { it.copy(loading = false, loaded = true, mine = response.mine, requests = response.requests) }
      } catch (e: Exception) {
        Log.w(TAG, "close people failed", e)
        internalState.update { it.copy(loading = false, failed = true) }
      }
    }
  }

  fun onQueryChanged(query: String) {
    internalState.update { it.copy(query = query) }
    searchJob?.cancel()
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
      internalState.update { it.copy(results = emptyList(), searching = false) }
      return
    }
    internalState.update { it.copy(searching = true) }
    searchJob = viewModelScope.launch {
      delay(debounceMillis)
      try {
        val users = withContext(ioDispatcher) { source.search(trimmed, FeedApi.SEARCH_USERS, null) }.users
        internalState.update { it.copy(searching = false, results = users) }
      } catch (e: Exception) {
        Log.w(TAG, "search failed", e)
        internalState.update { it.copy(searching = false, results = emptyList()) }
      }
    }
  }

  fun add(user: FeedSearchUser) {
    val current = internalState.value
    if (current.adding || !current.canAdd || current.mine.any { it.id == user.id }) return
    internalState.update { it.copy(adding = true) }
    viewModelScope.launch {
      try {
        val state = withContext(ioDispatcher) { source.addClosePerson(user.id) }
        val entry = FeedClosePersonEntry(id = user.id, displayName = user.displayName, avatarKey = user.avatarKey, state = state)
        searchJob?.cancel()
        internalState.update {
          it.copy(adding = false, mine = it.mine + entry, query = "", results = emptyList(), searching = false, notice = FeedClosePeopleState.Notice.ADDED)
        }
      } catch (e: Exception) {
        Log.w(TAG, "add failed", e)
        val notice = when {
          e is FeedApiException && e.status == 409 && e.code == "close_people_full" -> FeedClosePeopleState.Notice.FULL
          e is FeedApiException && e.status == 409 && e.code == "profile_required" -> FeedClosePeopleState.Notice.PROFILE_REQUIRED
          e is FeedApiException && e.status == 404 -> FeedClosePeopleState.Notice.NOT_ALLOWED
          else -> FeedClosePeopleState.Notice.FAILED
        }
        internalState.update { it.copy(adding = false, notice = notice) }
      }
    }
  }

  fun remove(entry: FeedClosePersonEntry) {
    val index = internalState.value.mine.indexOfFirst { it.id == entry.id }
    if (index < 0) return
    internalState.update { it.copy(mine = it.mine - entry) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.removeClosePerson(entry.id) }
      } catch (e: Exception) {
        Log.w(TAG, "remove failed", e)
        internalState.update { state ->
          val restored = state.mine.toMutableList().apply { add(index.coerceAtMost(size), entry) }
          state.copy(mine = restored, notice = FeedClosePeopleState.Notice.FAILED)
        }
      }
    }
  }

  /** 同意，或者拒绝 / 撤回已经同意的。 */
  fun answer(entry: FeedClosePersonEntry, accept: Boolean) {
    val before = internalState.value.requests
    if (before.none { it.id == entry.id }) return
    val after = if (accept) {
      before.map { if (it.id == entry.id) it.copy(state = FeedClosePersonEntry.STATE_ACCEPTED) else it }
    } else {
      before.filterNot { it.id == entry.id }
    }
    internalState.update { it.copy(requests = after) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.answerClosePerson(entry.id, accept) }
      } catch (e: FeedApiException) {
        if (e.status == 404) return@launch
        Log.w(TAG, "answer failed: ${e.status} ${e.code}")
        internalState.update { it.copy(requests = before, notice = FeedClosePeopleState.Notice.FAILED) }
      } catch (e: Exception) {
        Log.w(TAG, "answer failed", e)
        internalState.update { it.copy(requests = before, notice = FeedClosePeopleState.Notice.FAILED) }
      }
    }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }
}

data class FeedClosePeopleState(
  val loading: Boolean = false,
  val loaded: Boolean = false,
  val failed: Boolean = false,
  val mine: List<FeedClosePersonEntry> = emptyList(),
  val requests: List<FeedClosePersonEntry> = emptyList(),
  val query: String = "",
  val results: List<FeedSearchUser> = emptyList(),
  val searching: Boolean = false,
  val adding: Boolean = false,
  val notice: Notice? = null
) {
  enum class Notice { ADDED, FULL, PROFILE_REQUIRED, NOT_ALLOWED, FAILED }

  val canAdd: Boolean
    get() = mine.size < FeedClosePersonEntry.MAX

  /** 搜索结果里去掉已经设了的人。 */
  val addableResults: List<FeedSearchUser>
    get() = results.filter { user -> mine.none { it.id == user.id } }
}
