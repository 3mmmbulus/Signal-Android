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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedPollState
import java.time.Instant
import kotlin.random.Random

/**
 * 动态页的状态：两个分栏各自一份列表、翻页位置和加载状态。
 *
 * - 下拉刷新 = 不带 cursor 重新取，新结果放最上面，按 id 去重（契约 4.1）；
 * - 点赞先改界面，失败再改回来（契约第六节「先改界面、失败再回滚」）；
 * - 不感兴趣：这条原地换成小反馈卡（第三节第 11 条：撤销 / 管理内容偏好 / 这条让我不舒服），刷新后就不在了。
 */
class FeedViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val clock: () -> Long = System::currentTimeMillis,
  private val blockedKeywords: FeedBlockedKeywords = StoredFeedBlockedKeywords(),
  /** 不感兴趣后问不问原因（第三节第 11 条「约一半的时候」）；测试里换掉。 */
  private val askReason: () -> Boolean = { Random.nextBoolean() }
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedState())
  val state: StateFlow<FeedState> = internalState.asStateFlow()

  private val loadJobs = mutableMapOf<FeedTab, Job>()

  private val impressionTrackers = mutableMapOf<FeedTab, FeedImpressionTracker>()
  private val lastVisible = mutableMapOf<FeedTab, Set<String>>()
  private val impressionRechecks = mutableMapOf<FeedTab, Job>()
  private val events = FeedEventBuffer()

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

    viewModelScope.launch {
      while (isActive) {
        delay(FeedEventBuffer.FLUSH_INTERVAL_MILLIS)
        flushEvents()
      }
    }
  }

  /**
   * 列表每次布局变化时调：[visibleIds] 是这一栏现在露出过半的卡片。满 1 秒的记一次曝光；
   * 停着不动时由这里自己约下一次检查，不靠界面再来调。
   */
  fun onCardsVisible(tab: FeedTab, shownIds: Set<String>) {
    // 换成反馈卡的不算曝光
    val hidden = internalState.value.hidden
    val visibleIds = if (hidden.isEmpty()) shownIds else shownIds - hidden
    lastVisible[tab] = visibleIds
    val tracker = impressionTrackers.getOrPut(tab) { FeedImpressionTracker() }
    val now = clock()
    var full = false
    for (id in tracker.update(visibleIds, now)) {
      full = events.add(impressionEvent(id, tab, Instant.ofEpochMilli(now))) || full
    }
    if (full) flushEvents()

    impressionRechecks[tab]?.cancel()
    val nextAt = tracker.nextCheckAt() ?: return
    impressionRechecks[tab] = viewModelScope.launch {
      delay((nextAt - clock()).coerceAtLeast(0))
      onCardsVisible(tab, lastVisible[tab].orEmpty())
    }
  }

  /** 页面看不见了（切到别的标签、退到后台）：停掉计时，发掉攒着的事件。 */
  fun onFeedHidden() {
    FeedTab.entries.forEach { onCardsVisible(it, emptySet()) }
    flushEvents()
  }

  private fun flushEvents() {
    val batches = events.drain()
    if (batches.isEmpty()) return
    viewModelScope.launch {
      for (batch in batches) {
        try {
          withContext(ioDispatcher) { source.reportEvents(batch) }
        } catch (e: Exception) {
          Log.w(TAG, "events dropped: ${batch.size}", e)
        }
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

  private val voting = mutableSetOf<String>()

  /** 投票不能撤回，所以不先改界面：等服务端回了票数再换上。 */
  fun vote(card: FeedCard, options: List<Int>) {
    if (options.isEmpty() || !voting.add(card.id)) return
    viewModelScope.launch {
      try {
        val state = withContext(ioDispatcher) { source.vote(card.id, options) }
        updateCard(card.id) { it.withPollState(state) }
      } catch (e: Exception) {
        Log.w(TAG, "vote failed", e)
        internalState.update { it.copy(transientError = FeedState.TransientError.ACTION_FAILED) }
      } finally {
        voting -= card.id
      }
    }
  }

  fun notInterested(card: FeedCard) {
    if (card.id in internalState.value.hidden) return
    val ask = askReason()
    internalState.update { it.copy(hidden = it.hidden + card.id, reasonAsks = if (ask) it.reasonAsks + (card.id to null) else it.reasonAsks) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.notInterested(card.id) }
      } catch (e: Exception) {
        Log.w(TAG, "not interested failed", e)
      }
    }
  }

  /**
   * 在「···」里屏蔽作者（第三节第 26 条；动态屏蔽和聊天屏蔽分开，第 44 条）：这位作者的卡片从两栏拿掉，
   * 引擎同时删掉双方的关注。界面上已经确认过。失败时提示并重新取。
   */
  fun blockAuthor(card: FeedCard) {
    val author = card.author ?: return
    internalState.update { state ->
      state.copy(tabStates = state.tabStates.mapValues { (_, tabState) -> tabState.copy(cards = tabState.cards.filterNot { it.author?.profileId == author.profileId }) })
    }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setBlocked(author.profileId, true) }
        internalState.update { it.copy(blockedNotice = true) }
      } catch (e: Exception) {
        Log.w(TAG, "block failed", e)
        internalState.update { it.copy(transientError = FeedState.TransientError.ACTION_FAILED) }
        FeedTab.entries.forEach { refresh(it) }
      }
    }
  }

  /**
   * 「不看 TA 的动态」：TA 的帖子先从两栏拿掉，再报给引擎；提示条上能撤销。
   * 没有自己的公开主页时引擎回 409 `profile_required`，单独提示。
   */
  fun muteAuthor(card: FeedCard) {
    val author = card.author?.takeIf { it.isNative } ?: return
    internalState.update { state ->
      state.copy(tabStates = state.tabStates.mapValues { (_, tabState) -> tabState.copy(cards = tabState.cards.filterNot { it.author?.profileId == author.profileId }) })
    }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setHideList(FeedApi.HIDE_MUTE, author.profileId, on = true) }
        internalState.update { it.copy(mutedAuthor = author) }
      } catch (e: Exception) {
        Log.w(TAG, "mute failed", e)
        val profileRequired = e is FeedApiException && e.code == "profile_required"
        internalState.update { it.copy(transientError = if (profileRequired) FeedState.TransientError.PROFILE_REQUIRED else FeedState.TransientError.ACTION_FAILED) }
        FeedTab.entries.forEach { refresh(it) }
      }
    }
  }

  /** 提示条上的「撤销」：移出名单，两栏重新拉。 */
  fun unmuteAuthor(author: FeedAuthor) {
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setHideList(FeedApi.HIDE_MUTE, author.profileId, on = false) }
        FeedTab.entries.forEach { refresh(it) }
      } catch (e: Exception) {
        Log.w(TAG, "unmute failed", e)
        internalState.update { it.copy(transientError = FeedState.TransientError.ACTION_FAILED) }
      }
    }
  }

  fun onMutedNoticeShown() {
    internalState.update { it.copy(mutedAuthor = null) }
  }

  fun onBlockedNoticeShown() {
    internalState.update { it.copy(blockedNotice = false) }
  }

  /** 反馈卡上选了原因：同一条再发一次带上原因；可以不选，选了不能改。发不出去不提示。 */
  fun notInterestedReason(card: FeedCard, reason: FeedNotInterestedReason) {
    val asks = internalState.value.reasonAsks
    if (card.id !in asks || asks[card.id] != null) return
    internalState.update { it.copy(reasonAsks = it.reasonAsks + (card.id to reason)) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.notInterested(card.id, reason.apiName) }
      } catch (e: Exception) {
        Log.w(TAG, "not interested reason failed", e)
      }
    }
  }

  /** 反馈卡上的「撤销」：卡片原样回来。 */
  fun undoNotInterested(card: FeedCard) {
    if (card.id !in internalState.value.hidden) return
    internalState.update { it.copy(hidden = it.hidden - card.id, reasonAsks = it.reasonAsks - card.id) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.undoNotInterested(card.id) }
      } catch (e: Exception) {
        Log.w(TAG, "undo not interested failed", e)
        internalState.update { it.copy(hidden = it.hidden + card.id, transientError = FeedState.TransientError.ACTION_FAILED) }
      }
    }
  }

  fun onTransientErrorShown() {
    internalState.update { it.copy(transientError = null) }
  }

  /** 发布页带回服务端给的 `state`：两栏都刷新，提示「已发布」或「审核中」（R-2.2）。 */
  fun onPublished(publishState: String) {
    internalState.update { it.copy(publishNotice = publishState) }
    FeedTab.entries.forEach { refresh(it) }
  }

  fun onPublishNoticeShown() {
    internalState.update { it.copy(publishNotice = null) }
  }

  /** 编辑保存后：两栏刷新；大陆区提示「审核通过后别人看到新版本」（R-2.3）。 */
  fun onEdited(publishState: String) {
    internalState.update { it.copy(editNotice = publishState) }
    FeedTab.entries.forEach { refresh(it) }
  }

  fun onEditNoticeShown() {
    internalState.update { it.copy(editNotice = null) }
  }

  /** 删自己的帖子：先从两栏拿掉；失败提示一次，再刷新拿回来。 */
  fun deletePost(card: FeedCard) {
    internalState.update { state ->
      state.copy(tabStates = state.tabStates.mapValues { (_, tabState) -> tabState.copy(cards = tabState.cards.filterNot { it.id == card.id }) })
    }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.deletePost(card.id) }
      } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        internalState.update { it.copy(transientError = FeedState.TransientError.ACTION_FAILED) }
        FeedTab.entries.forEach { refresh(it) }
      }
    }
  }

  /**
   * 自己帖子「···」里的置顶、隐藏点赞数（先改界面，失败改回来）和查看数据（第三节第 26 条）。
   * 置顶满 3 条时引擎回 409，单独提示。
   */
  fun onOwnPostAction(card: FeedCard, action: FeedOwnPostAction) {
    val update = action.settingsUpdate
    if (update == null) {
      loadInsights(card.id)
      return
    }
    updateCard(card.id) { it.withSettings(update) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.updatePostSettings(card.id, update) }
      } catch (e: Exception) {
        Log.w(TAG, "post settings failed", e)
        updateCard(card.id) { it.copy(pinned = card.pinned, stats = it.stats.copy(likeCountHidden = card.stats.likeCountHidden)) }
        internalState.update { it.copy(transientError = if (e.isPinLimit()) FeedState.TransientError.PIN_LIMIT else FeedState.TransientError.ACTION_FAILED) }
      }
    }
  }

  private fun loadInsights(contentId: String) {
    internalState.update { it.copy(insights = FeedInsightsState.Loading) }
    viewModelScope.launch {
      val result = try {
        FeedInsightsState.Ready(withContext(ioDispatcher) { source.postInsights(contentId) })
      } catch (e: Exception) {
        Log.w(TAG, "insights failed", e)
        FeedInsightsState.Failed
      }
      // 等的时候关掉了弹窗就不再弹
      internalState.update { if (it.insights == null) it else it.copy(insights = result) }
    }
  }

  fun onInsightsDismissed() {
    internalState.update { it.copy(insights = null) }
  }

  private fun load(tab: FeedTab, refresh: Boolean) {
    loadJobs[tab]?.cancel()
    val cursor = if (refresh) null else internalState.value.tab(tab).nextCursor
    setTab(tab) { it.copy(loading = true, refreshing = refresh && it.loadedOnce) }

    loadJobs[tab] = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.timeline(tab, cursor) }
        // 「完全屏蔽」的词在本机过滤（第三节第 12 条），每页现读，设置页刚改的下一页就生效
        val blocked = blockedKeywords.active(clock())
        val incoming = page.entries.map { it.card }.filter { it.isRenderable && !it.matchesAny(blocked) }
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

/** 投完票：换成服务端给的票数和我的选择。 */
internal fun FeedCard.withPollState(state: FeedPollState): FeedCard = copy(pollState = state)

data class FeedState(
  val config: FeedConfig? = null,
  val tabs: List<FeedTab> = FeedTab.entries,
  val tabStates: Map<FeedTab, FeedTabState> = emptyMap(),
  /** 点了「不感兴趣」、正显示成反馈卡的。 */
  val hidden: Set<String> = emptySet(),
  /** 反馈卡上问原因的那些：值是选了的原因，还没选是 null。 */
  val reasonAsks: Map<String, FeedNotInterestedReason?> = emptyMap(),
  /** 刚屏蔽了一位作者，提示「已屏蔽」。 */
  val blockedNotice: Boolean = false,
  /** 刚「不看 TA」的作者，提示条带「撤销」。 */
  val mutedAuthor: FeedAuthor? = null,
  val transientError: TransientError? = null,
  val publishNotice: String? = null,
  /** 刚保存的编辑，服务端给的 `state`。 */
  val editNotice: String? = null,
  /** 「查看数据」弹窗；null = 没开。 */
  val insights: FeedInsightsState? = null
) {
  fun tab(tab: FeedTab): FeedTabState = tabStates[tab] ?: FeedTabState()

  /** 大陆备案前推荐页只有编辑精选 + 时间顺序（R-1.5），界面上不说「为你推荐」。 */
  val isEditorialRecommend: Boolean
    get() = config?.recommendMode != FeedConfig.RECOMMEND_MODE_SCORED

  enum class TransientError { NETWORK, ACTION_FAILED, PIN_LIMIT, PROFILE_REQUIRED }
}

data class FeedTabState(
  val cards: List<FeedCard> = emptyList(),
  val nextCursor: String? = null,
  val loading: Boolean = false,
  val refreshing: Boolean = false,
  val loadedOnce: Boolean = false,
  val failed: Boolean = false
)
