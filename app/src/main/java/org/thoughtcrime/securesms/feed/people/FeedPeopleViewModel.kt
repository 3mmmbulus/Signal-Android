/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.people

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
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedBlockEntry
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary

/**
 * 「关注请求」和「屏蔽列表」两张名单（`dynamic-feed.md` 第三节第 29、44 条）：同一个页面，按 [mode] 换数据和按钮。
 *
 * 处理完的一行先从名单里拿掉；引擎说不行再放回去。对方已经撤回请求（404）就当处理完了。
 */
class FeedPeopleViewModel(
  val mode: FeedPeopleMode,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedPeopleViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedPeopleState())
  val state: StateFlow<FeedPeopleState> = internalState.asStateFlow()

  private var loadJob: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    loadJob?.cancel()
    internalState.update { it.copy(loading = true, failed = false) }
    loadJob = viewModelScope.launch {
      try {
        val rows = withContext(ioDispatcher) {
          when (mode) {
            FeedPeopleMode.FOLLOW_REQUESTS -> source.followRequests().map { FeedPeopleRow(key = it.profile.id, person = it.profile) }
            FeedPeopleMode.BLOCKED -> source.blocks().map { FeedPeopleRow(key = "${it.type}/${it.id}", person = it.profile, block = it) }
            FeedPeopleMode.MUTED -> source.hideLists().mute.map { FeedPeopleRow(key = it.id, person = it) }
            FeedPeopleMode.HIDDEN_FROM -> source.hideLists().hideFrom.map { FeedPeopleRow(key = it.id, person = it) }
          }
        }
        internalState.update { it.copy(loading = false, rows = rows) }
      } catch (e: Exception) {
        Log.w(TAG, "load failed", e)
        internalState.update { it.copy(loading = false, failed = true) }
      }
    }
  }

  /** 同意或拒绝一条关注请求。 */
  fun answer(row: FeedPeopleRow, approve: Boolean) {
    val person = row.person ?: return
    act(row, if (approve) FeedPeopleState.Notice.APPROVED else FeedPeopleState.Notice.DECLINED) {
      source.answerFollowRequest(person.id, approve)
    }
  }

  /** 取消屏蔽（名单里的一项可以是作者、来源或话题）。 */
  fun unblock(row: FeedPeopleRow) {
    val entry = row.block ?: return
    act(row, FeedPeopleState.Notice.UNBLOCKED) { source.unblock(entry) }
  }

  /** 从「不看 TA」或「不让 TA 看」名单里移出。 */
  fun remove(row: FeedPeopleRow) {
    val person = row.person ?: return
    val list = mode.hideList ?: return
    act(row, FeedPeopleState.Notice.REMOVED) { source.setHideList(list, person.id, on = false) }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  private fun act(row: FeedPeopleRow, done: FeedPeopleState.Notice, call: suspend () -> Unit) {
    val before = internalState.value.rows
    val index = before.indexOfFirst { it.key == row.key }
    if (index < 0) return
    internalState.update { it.copy(rows = before - row) }

    viewModelScope.launch {
      val notice = try {
        withContext(ioDispatcher) { call() }
        done
      } catch (e: FeedApiException) {
        Log.w(TAG, "rejected: ${e.status} ${e.code}")
        if (e.status == 404) done else restore(row, index)
      } catch (e: Exception) {
        Log.w(TAG, "failed", e)
        restore(row, index)
      }
      internalState.update { it.copy(notice = notice) }
    }
  }

  private fun restore(row: FeedPeopleRow, index: Int): FeedPeopleState.Notice {
    internalState.update { state ->
      if (state.rows.any { it.key == row.key }) {
        state
      } else {
        state.copy(rows = state.rows.toMutableList().apply { add(index.coerceAtMost(size), row) })
      }
    }
    return FeedPeopleState.Notice.FAILED
  }
}

enum class FeedPeopleMode(
  /** 对应的引擎名单（`/v1/feed/me/hide-lists/{list}`）；另外两种不是。 */
  val hideList: String? = null
) {
  FOLLOW_REQUESTS,
  BLOCKED,

  /** 「不看 TA 的动态」。 */
  MUTED(FeedApi.HIDE_MUTE),

  /** 「不让 TA 看我的动态」。 */
  HIDDEN_FROM(FeedApi.HIDE_FROM)
}

/** 名单里的一行。屏蔽来源 / 话题时没有 [person]，只有 [block]。 */
data class FeedPeopleRow(
  val key: String,
  val person: FeedPersonSummary? = null,
  val block: FeedBlockEntry? = null
)

data class FeedPeopleState(
  val loading: Boolean = true,
  val failed: Boolean = false,
  val rows: List<FeedPeopleRow> = emptyList(),
  val notice: Notice? = null
) {
  enum class Notice {
    APPROVED,
    DECLINED,
    UNBLOCKED,
    REMOVED,
    FAILED
  }
}
