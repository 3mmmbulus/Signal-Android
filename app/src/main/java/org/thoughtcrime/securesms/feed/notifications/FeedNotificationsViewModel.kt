/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

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
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedUnreadCounts

/**
 * 动态通知（ADR-0071 二·〇「通知」、`dynamic-feed.md` 第三节第 43 条）：互动 / 新关注 / 系统三栏。
 *
 * 一栏第一次加载完就整栏标为已读（这一次仍按服务端的已读状态画小点）。引擎只给 `actor_id` 时，
 * 这里按人查一次主页拿名字，同一个人只查一次。
 */
class FeedNotificationsViewModel(
  initialTab: String = FeedApi.NOTIFICATIONS_INTERACTIONS,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedNotificationsViewModel::class)

    val TABS = listOf(FeedApi.NOTIFICATIONS_INTERACTIONS, FeedApi.NOTIFICATIONS_FOLLOWS, FeedApi.NOTIFICATIONS_SYSTEM)
  }

  private val internalState = MutableStateFlow(FeedNotificationsState(selectedTab = initialTab))
  val state: StateFlow<FeedNotificationsState> = internalState.asStateFlow()

  private val jobs = mutableMapOf<String, Job>()
  private val lookedUp = mutableSetOf<String>()

  init {
    load(initialTab, before = null)
  }

  fun selectTab(tab: String) {
    internalState.update { it.copy(selectedTab = tab) }
    if (!internalState.value.tab(tab).loadedOnce && jobs[tab]?.isActive != true) load(tab, before = null)
  }

  fun refresh() {
    load(internalState.value.selectedTab, before = null)
  }

  fun loadMore() {
    val tab = internalState.value.selectedTab
    val current = internalState.value.tab(tab)
    if (!current.hasMore || jobs[tab]?.isActive == true) return
    load(tab, before = current.items.lastOrNull()?.createdAt ?: return)
  }

  private fun load(tab: String, before: String?) {
    jobs[tab]?.cancel()
    updateTab(tab) { it.copy(loading = before == null, loadingMore = before != null, failed = false) }
    jobs[tab] = viewModelScope.launch {
      val response = try {
        withContext(ioDispatcher) { source.notifications(tab, before) }
      } catch (e: Exception) {
        Log.w(TAG, "notifications failed", e)
        updateTab(tab) { it.copy(loading = false, loadingMore = false, failed = true) }
        return@launch
      }

      val wasLoaded = internalState.value.tab(tab).loadedOnce
      internalState.update { state ->
        val old = state.tab(tab)
        val known = if (before == null) emptySet() else old.items.mapTo(HashSet()) { it.id }
        val items = (if (before == null) emptyList() else old.items) + response.notifications.filterNot { it.id in known }
        val people = state.people + response.notifications.mapNotNull { n -> n.actor?.let { it.id to it } }
        state.copy(
          tabs = state.tabs + (tab to old.copy(items = items, loading = false, loadingMore = false, loadedOnce = true, hasMore = response.notifications.size >= FeedApi.NOTIFICATIONS_PAGE)),
          unread = response.unread,
          people = people
        )
      }
      lookUpActors(response.notifications)
      if (!wasLoaded && response.unread.of(tab) > 0) markRead(tab)
    }
  }

  private fun markRead(tab: String) {
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.markNotificationsRead(tab) }
        internalState.update { it.copy(unread = it.unread.without(tab)) }
      } catch (e: Exception) {
        Log.w(TAG, "mark read failed", e)
      }
    }
  }

  private fun lookUpActors(items: List<FeedNotification>) {
    val missing = items.flatMap { listOfNotNull(if (it.actor == null) it.actorId else null, it.detailProfileId()) }
      .distinct()
      .filter { it !in internalState.value.people && lookedUp.add(it) }
    for (id in missing) {
      viewModelScope.launch {
        try {
          val profile = withContext(ioDispatcher) { source.profile(id) } ?: return@launch
          val person = FeedPersonSummary(id = profile.id, handle = profile.handle, displayName = profile.displayName, avatarKey = profile.avatarKey)
          internalState.update { it.copy(people = it.people + (id to person)) }
        } catch (e: Exception) {
          Log.w(TAG, "actor lookup failed", e)
          lookedUp -= id
        }
      }
    }
  }

  /** 删一行（合并过的赞是几条一起删）。 */
  fun delete(row: FeedNotificationRow) {
    val tab = internalState.value.selectedTab
    val before = internalState.value.tab(tab).items
    updateTab(tab) { it.copy(items = it.items.filterNot { n -> n.id in row.ids }) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { row.ids.forEach { source.deleteNotification(it) } }
        internalState.update { it.copy(notice = FeedNotificationsState.Notice.DELETED) }
      } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        updateTab(tab) { it.copy(items = before) }
        internalState.update { it.copy(notice = FeedNotificationsState.Notice.FAILED) }
      }
    }
  }

  /** 这条帖子不再通知：全部，或者只关赞。已经在列表里的相应几行一起拿掉。 */
  fun mute(row: FeedNotificationRow, likesOnly: Boolean) {
    val contentId = row.contentId ?: return
    val tab = internalState.value.selectedTab
    val before = internalState.value.tab(tab).items
    updateTab(tab) { it.copy(items = it.items.filterNot { n -> n.contentId == contentId && (!likesOnly || n.kind == FeedNotification.KIND_LIKE) }) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.muteNotifications(contentId, likesOnly) }
        internalState.update { it.copy(notice = if (likesOnly) FeedNotificationsState.Notice.MUTED_LIKES else FeedNotificationsState.Notice.MUTED) }
      } catch (e: Exception) {
        Log.w(TAG, "mute failed", e)
        updateTab(tab) { it.copy(items = before) }
        internalState.update { it.copy(notice = FeedNotificationsState.Notice.FAILED) }
      }
    }
  }

  /** 点赞 / 评论通知：先换成卡片再进详情；帖子删了就提示一下。 */
  fun openPost(contentId: String) {
    viewModelScope.launch {
      val card = try {
        withContext(ioDispatcher) { source.card(contentId) }
      } catch (e: Exception) {
        Log.w(TAG, "card failed", e)
        internalState.update { it.copy(notice = FeedNotificationsState.Notice.FAILED) }
        return@launch
      }
      internalState.update { if (card == null) it.copy(notice = FeedNotificationsState.Notice.POST_GONE) else it.copy(openCard = card) }
    }
  }

  fun onCardOpened() {
    internalState.update { it.copy(openCard = null) }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  private fun updateTab(tab: String, change: (FeedNotificationsTab) -> FeedNotificationsTab) {
    internalState.update { it.copy(tabs = it.tabs + (tab to change(it.tab(tab)))) }
  }
}

internal fun FeedUnreadCounts.of(tab: String): Int = when (tab) {
  FeedApi.NOTIFICATIONS_FOLLOWS -> follows
  FeedApi.NOTIFICATIONS_SYSTEM -> system
  else -> interactions
}

internal fun FeedUnreadCounts.without(tab: String): FeedUnreadCounts = when (tab) {
  FeedApi.NOTIFICATIONS_FOLLOWS -> copy(follows = 0)
  FeedApi.NOTIFICATIONS_SYSTEM -> copy(system = 0)
  else -> copy(interactions = 0)
}

data class FeedNotificationsTab(
  val items: List<FeedNotification> = emptyList(),
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val failed: Boolean = false,
  val loadedOnce: Boolean = false,
  val hasMore: Boolean = false
)

data class FeedNotificationsState(
  val selectedTab: String = FeedApi.NOTIFICATIONS_INTERACTIONS,
  val tabs: Map<String, FeedNotificationsTab> = emptyMap(),
  val unread: FeedUnreadCounts = FeedUnreadCounts(),
  /** actor id → 名字和头像（引擎带了或者查过主页的）。 */
  val people: Map<String, FeedPersonSummary> = emptyMap(),
  val notice: Notice? = null,
  /** 要打开详情的帖子（[FeedNotificationsViewModel.openPost] 换好的）。 */
  val openCard: FeedCard? = null
) {
  fun tab(tab: String): FeedNotificationsTab = tabs[tab] ?: FeedNotificationsTab()

  enum class Notice {
    DELETED,
    MUTED,
    MUTED_LIKES,
    POST_GONE,
    FAILED
  }
}
