/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

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
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedConfig

/**
 * 动态页的状态：两个分栏各自一份列表、翻页位置和加载状态。
 *
 * - 下拉刷新 = 不带 cursor 重新取，新结果放最上面，按 id 去重（契约 4.1）；
 * - 点赞先改界面，失败再改回来（契约第六节「先改界面、失败再回滚」）；
 * - 不感兴趣：先把这条从两个分栏里拿掉，留一个可以撤销的记录（第三节第 11 条的反馈卡在后面的 PR 做）。
 */
class FeedViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedState())
  val state: StateFlow<FeedState> = internalState.asStateFlow()

  private val loadJobs = mutableMapOf<FeedTab, Job>()

  init {
    viewModelScope.launch {
      val config = try {
        withContext(ioDispatcher) { source.config() }
      } catch (e: Exception) {
        Log.w(TAG, "config failed", e)
        null
      }

      if (config != null) {
        internalState.update { it.copy(config = config, tabs = FeedTab.fromApiNames(config.tabs)) }
      }
    }
  }

  /** 第一次显示某个分栏时调用；已经有内容就不再取。 */
  fun onTabShown(tab: FeedTab) {
    val tabState = internalState.value.tab(tab)
    if (!tabState.loadedOnce && !tabState.loading) {
      load(tab, refresh = true)
    }
  }

  fun refresh(tab: FeedTab) {
    load(tab, refresh = true)
  }

  fun loadMore(tab: FeedTab) {
    val tabState = internalState.value.tab(tab)
    if (tabState.loading || tabState.nextCursor == null) {
      return
    }
    load(tab, refresh = false)
  }

  fun toggleLike(card: FeedCard) {
    val nowLiked = !card.viewer.liked
    updateCard(card.id) { it.withLiked(nowLiked) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setLiked(card.id, nowLiked) }
      } catch (e: Exception) {
        Log.w(TAG, "like failed", e)
        updateCard(card.id) { it.withLiked(!nowLiked) }
        internalState.update { it.copy(transientError = FeedState.TransientError.ACTION_FAILED) }
      }
    }
  }

  fun notInterested(card: FeedCard) {
    internalState.update { state ->
      state.copy(
        tabStates = state.tabStates.mapValues { (_, tabState) -> tabState.copy(cards = tabState.cards.filterNot { it.id == card.id }) },
        lastHidden = card
      )
    }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.notInterested(card.id) }
      } catch (e: Exception) {
        Log.w(TAG, "not interested failed", e)
      }
    }
  }

  fun undoNotInterested() {
    val card = internalState.value.lastHidden ?: return
    internalState.update { it.copy(lastHidden = null) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.undoNotInterested(card.id) }
      } catch (e: Exception) {
        Log.w(TAG, "undo not interested failed", e)
      }
      FeedTab.entries.forEach { refresh(it) }
    }
  }

  fun onTransientErrorShown() {
    internalState.update { it.copy(transientError = null, lastHidden = null) }
  }

  private fun load(tab: FeedTab, refresh: Boolean) {
    loadJobs[tab]?.cancel()
    val cursor = if (refresh) null else internalState.value.tab(tab).nextCursor
    setTab(tab) { it.copy(loading = true, refreshing = refresh && it.loadedOnce) }

    loadJobs[tab] = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.timeline(tab, cursor) }
        val incoming = page.entries.map { it.card }.filter { it.isRenderable }
        setTab(tab) { current ->
          val cards = if (refresh) mergeRefresh(incoming, current.cards) else appendPage(current.cards, incoming)
          current.copy(
            cards = cards,
            nextCursor = page.nextCursor,
            loading = false,
            refreshing = false,
            loadedOnce = true,
            failed = false
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "timeline ${tab.apiName} failed", e)
        setTab(tab) { it.copy(loading = false, refreshing = false, loadedOnce = true, failed = it.cards.isEmpty()) }
        if (internalState.value.tab(tab).cards.isNotEmpty()) {
          internalState.update { it.copy(transientError = FeedState.TransientError.NETWORK) }
        }
      }
    }
  }

  private fun setTab(tab: FeedTab, transform: (FeedTabState) -> FeedTabState) {
    internalState.update { state -> state.copy(tabStates = state.tabStates + (tab to transform(state.tab(tab)))) }
  }

  private fun updateCard(id: String, transform: (FeedCard) -> FeedCard) {
    internalState.update { state ->
      state.copy(
        tabStates = state.tabStates.mapValues { (_, tabState) ->
          tabState.copy(cards = tabState.cards.map { if (it.id == id) transform(it) else it })
        }
      )
    }
  }
}

/** 下拉刷新：新结果在上，旧的里面和新结果重复的去掉（契约 4.1）。 */
internal fun mergeRefresh(incoming: List<FeedCard>, existing: List<FeedCard>): List<FeedCard> {
  val ids = incoming.mapTo(HashSet()) { it.id }
  return incoming + existing.filterNot { it.id in ids }
}

/** 翻页：接在后面，已经有的不重复加。 */
internal fun appendPage(existing: List<FeedCard>, incoming: List<FeedCard>): List<FeedCard> {
  val ids = existing.mapTo(HashSet()) { it.id }
  return existing + incoming.filterNot { it.id in ids }
}

internal fun FeedCard.withLiked(liked: Boolean): FeedCard {
  if (viewer.liked == liked) return this
  val likes = (stats.likes + if (liked) 1 else -1).coerceAtLeast(0)
  return copy(viewer = viewer.copy(liked = liked), stats = stats.copy(likes = likes))
}

data class FeedState(
  val config: FeedConfig? = null,
  val tabs: List<FeedTab> = FeedTab.entries,
  val tabStates: Map<FeedTab, FeedTabState> = emptyMap(),
  val lastHidden: FeedCard? = null,
  val transientError: TransientError? = null
) {
  fun tab(tab: FeedTab): FeedTabState = tabStates[tab] ?: FeedTabState()

  /** 大陆备案前推荐页只有编辑精选 + 时间顺序（R-1.5），界面上不说「为你推荐」。 */
  val isEditorialRecommend: Boolean
    get() = config?.recommendMode != FeedConfig.RECOMMEND_MODE_RANKED

  enum class TransientError { NETWORK, ACTION_FAILED }
}

data class FeedTabState(
  val cards: List<FeedCard> = emptyList(),
  val nextCursor: String? = null,
  val loading: Boolean = false,
  val refreshing: Boolean = false,
  val loadedOnce: Boolean = false,
  val failed: Boolean = false
)
