/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

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
import org.thoughtcrime.securesms.feed.FeedInsightsState
import org.thoughtcrime.securesms.feed.FeedOwnPostAction
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.isPinLimit
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedRelation
import org.thoughtcrime.securesms.feed.withLiked
import org.thoughtcrime.securesms.feed.withSettings

/**
 * 别人的（或自己的）公开主页：头部、关注、屏蔽、帖子列表（`dynamic-feed.md` 第三节第 8–10、19、29、33 条；
 * ADR-0071 二·〇「个人主页」「个人主页按钮」「统一反馈」）。
 *
 * 关注是乐观的：先改按钮和粉丝数，引擎说不行再改回来。私密账号对非好友先是「已请求」。
 */
class FeedProfilePageViewModel(
  private val profileId: String,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedProfilePageViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedProfilePageState())
  val state: StateFlow<FeedProfilePageState> = internalState.asStateFlow()

  private var loadJob: Job? = null
  private var postsJob: Job? = null

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
    loadJob?.cancel()
    postsJob?.cancel()
    internalState.update { it.copy(loading = true, failed = false) }
    loadJob = viewModelScope.launch {
      val profile = try {
        withContext(ioDispatcher) { source.profile(profileId) }
      } catch (e: Exception) {
        Log.w(TAG, "profile failed", e)
        internalState.update { it.copy(loading = false, failed = true) }
        return@launch
      }
      if (profile == null) {
        internalState.update { it.copy(loading = false, gone = true, profile = null, posts = emptyList(), cursor = null) }
        return@launch
      }
      // 不看 / 不让 TA 看：自己和已屏蔽的人不给这两项
      val hideState = FeedHideState(muted = profile.me.muted, hiddenFrom = profile.me.hiddenFrom).takeUnless { profile.me.self || profile.me.blocked }
      internalState.update { it.copy(loading = false, gone = false, profile = profile, posts = emptyList(), cursor = null, postsLoadedOnce = false, hideState = hideState) }
      if (profile.showsPosts) loadPosts(cursor = null)
    }
  }

  /** 「不看 TA 的动态」/「不让 TA 看我的动态」的加进、移出（[list] 是 [FeedApi.HIDE_MUTE] / [FeedApi.HIDE_FROM]）。先改菜单，失败改回来。 */
  fun setHidden(list: String, on: Boolean) {
    val before = internalState.value.hideState ?: return
    val after = if (list == FeedApi.HIDE_MUTE) before.copy(muted = on) else before.copy(hiddenFrom = on)
    internalState.update { it.copy(hideState = after) }
    viewModelScope.launch {
      val notice = try {
        withContext(ioDispatcher) { source.setHideList(list, profileId, on) }
        when {
          !on -> null
          list == FeedApi.HIDE_MUTE -> FeedProfilePageState.Notice.MUTED
          else -> FeedProfilePageState.Notice.HIDDEN_FROM
        }
      } catch (e: Exception) {
        Log.w(TAG, "hide list failed", e)
        internalState.update { it.copy(hideState = before) }
        if (e is FeedApiException && e.code == "profile_required") FeedProfilePageState.Notice.PROFILE_REQUIRED else FeedProfilePageState.Notice.FAILED
      }
      if (notice != null) internalState.update { it.copy(notice = notice) }
    }
  }

  fun loadMorePosts() {
    val cursor = internalState.value.cursor ?: return
    if (postsJob?.isActive == true) return
    loadPosts(cursor)
  }

  private fun loadPosts(cursor: String?) {
    postsJob?.cancel()
    internalState.update { it.copy(postsLoading = true, postsFailed = false) }
    postsJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.profilePosts(profileId, cursor) }
        internalState.update { state ->
          val known = if (cursor == null) emptySet() else state.posts.mapTo(HashSet()) { it.id }
          val base = if (cursor == null) emptyList() else state.posts
          state.copy(posts = base + page.posts.filterNot { it.id in known }, cursor = page.cursor, postsLoading = false, postsLoadedOnce = true)
        }
      } catch (e: FeedApiException) {
        Log.w(TAG, "posts rejected: ${e.status} ${e.code}")
        // 打开以后被对方屏蔽、删号了：整页换成「看不到」
        if (e.status == 404 && e.code == FeedApi.NOT_FOUND) {
          internalState.update { it.copy(gone = true, profile = null, posts = emptyList(), cursor = null, postsLoading = false) }
        } else {
          internalState.update { it.copy(postsLoading = false, postsFailed = true, postsLoadedOnce = true) }
        }
      } catch (e: Exception) {
        Log.w(TAG, "posts failed", e)
        internalState.update { it.copy(postsLoading = false, postsFailed = true, postsLoadedOnce = true) }
      }
    }
  }

  /** 关注：先改按钮和粉丝数（ADR-0071 附录 10 §5「粉丝数乐观 +1」）。 */
  fun follow() {
    val before = internalState.value.profile ?: return
    if (internalState.value.followBusy || before.me.self || before.me.follow != FeedRelation.FOLLOW_NONE) return
    val guess = if (before.isPrivate && !before.me.friend) FeedRelation.FOLLOW_PENDING else FeedRelation.FOLLOW_APPROVED
    internalState.update { it.copy(profile = before.withFollow(guess), followBusy = true) }

    viewModelScope.launch {
      try {
        val result = withContext(ioDispatcher) { source.setFollowing(profileId, true) }
        val after = before.withFollow(result)
        internalState.update {
          it.copy(
            profile = after,
            followBusy = false,
            notice = if (result == FeedRelation.FOLLOW_PENDING) FeedProfilePageState.Notice.REQUESTED else FeedProfilePageState.Notice.FOLLOWED
          )
        }
        if (!before.showsPosts && after.showsPosts) loadPosts(cursor = null)
      } catch (e: FeedApiException) {
        Log.w(TAG, "follow rejected: ${e.status} ${e.code}")
        when {
          e.code == "profile_required" -> internalState.update { it.copy(profile = before, followBusy = false, notice = FeedProfilePageState.Notice.PROFILE_REQUIRED) }
          e.status == 404 -> internalState.update { it.copy(gone = true, profile = null, posts = emptyList(), cursor = null, followBusy = false) }
          else -> internalState.update { it.copy(profile = before, followBusy = false, notice = FeedProfilePageState.Notice.FAILED) }
        }
      } catch (e: Exception) {
        Log.w(TAG, "follow failed", e)
        internalState.update { it.copy(profile = before, followBusy = false, notice = FeedProfilePageState.Notice.FAILED) }
      }
    }
  }

  /** 取消关注或撤回关注请求（确认框在界面上弹，第三节「统一反馈」）。私密账号取消后帖子跟着收起来。 */
  fun unfollow() {
    val before = internalState.value.profile ?: return
    if (internalState.value.followBusy || before.me.follow == FeedRelation.FOLLOW_NONE) return
    val after = before.withFollow(FeedRelation.FOLLOW_NONE)
    internalState.update {
      it.copy(
        profile = after,
        followBusy = true,
        posts = if (after.showsPosts) it.posts else emptyList(),
        cursor = if (after.showsPosts) it.cursor else null
      )
    }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setFollowing(profileId, false) }
        internalState.update { it.copy(followBusy = false) }
      } catch (e: Exception) {
        Log.w(TAG, "unfollow failed", e)
        internalState.update { it.copy(profile = before, followBusy = false, notice = FeedProfilePageState.Notice.FAILED) }
        if (!after.showsPosts && before.showsPosts) loadPosts(cursor = null)
      }
    }
  }

  /** 屏蔽：双方的关注一起没了，帖子收起来；取消屏蔽后重新拉一遍主页。 */
  fun setBlocked(blocked: Boolean) {
    val before = internalState.value.profile ?: return
    if (before.me.self || internalState.value.followBusy) return
    internalState.update { it.copy(followBusy = true) }

    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setBlocked(profileId, blocked) }
        if (blocked) {
          val wasFollowing = before.me.follow == FeedRelation.FOLLOW_APPROVED
          val after = before.copy(
            followers = if (wasFollowing) (before.followers - 1).coerceAtLeast(0) else before.followers,
            me = before.me.copy(blocked = true, follow = FeedRelation.FOLLOW_NONE, followedBy = false)
          )
          internalState.update { it.copy(profile = after, posts = emptyList(), cursor = null, followBusy = false, notice = FeedProfilePageState.Notice.BLOCKED) }
        } else {
          internalState.update { it.copy(followBusy = false, notice = FeedProfilePageState.Notice.UNBLOCKED) }
          refresh()
        }
      } catch (e: Exception) {
        Log.w(TAG, "block failed", e)
        internalState.update { it.copy(followBusy = false, notice = FeedProfilePageState.Notice.FAILED) }
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
        internalState.update { it.copy(notice = FeedProfilePageState.Notice.FAILED) }
      }
    }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  /**
   * 自己帖子「···」：置顶 / 隐藏点赞数先改界面，失败改回来；置顶变了重新拉第一页，
   * 让置顶的排到最前（引擎排序，最近置顶的在前）。查看数据开一个弹窗。
   */
  fun onOwnPostAction(card: FeedCard, action: FeedOwnPostAction) {
    val update = action.settingsUpdate
    if (update == null) {
      loadInsights(card.id)
      return
    }
    updatePost(card.id) { it.withSettings(update) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.updatePostSettings(card.id, update) }
        if (update.pinned != null) loadPosts(cursor = null)
      } catch (e: Exception) {
        Log.w(TAG, "post settings failed", e)
        updatePost(card.id) { it.copy(pinned = card.pinned, stats = it.stats.copy(likeCountHidden = card.stats.likeCountHidden)) }
        internalState.update { it.copy(notice = if (e.isPinLimit()) FeedProfilePageState.Notice.PIN_LIMIT else FeedProfilePageState.Notice.FAILED) }
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
      internalState.update { if (it.insights == null) it else it.copy(insights = result) }
    }
  }

  fun onInsightsDismissed() {
    internalState.update { it.copy(insights = null) }
  }

  private fun updatePost(id: String, change: (FeedCard) -> FeedCard) {
    internalState.update { state -> state.copy(posts = state.posts.map { if (it.id == id) change(it) else it }) }
  }
}

/** 换一个关注状态，粉丝数跟着「已关注」进出变。 */
internal fun FeedProfilePage.withFollow(follow: String): FeedProfilePage {
  val was = me.follow == FeedRelation.FOLLOW_APPROVED
  val now = follow == FeedRelation.FOLLOW_APPROVED
  val delta = (if (now) 1 else 0) - (if (was) 1 else 0)
  return copy(followers = (followers + delta).coerceAtLeast(0), me = me.copy(follow = follow))
}

/** 主页下面画不画帖子列表：看得到、没被我屏蔽、不在另一个内容区域（R-1.4）。 */
internal val FeedProfilePage.showsPosts: Boolean
  get() = canSeePosts && !me.blocked && !regionUnavailable

data class FeedProfilePageState(
  val profile: FeedProfilePage? = null,
  val loading: Boolean = true,
  val failed: Boolean = false,
  /** 看不到这个主页（删了、互相屏蔽、未成年人对非好友），引擎不说是哪种。 */
  val gone: Boolean = false,
  val posts: List<FeedCard> = emptyList(),
  val cursor: String? = null,
  val postsLoading: Boolean = false,
  val postsLoadedOnce: Boolean = false,
  val postsFailed: Boolean = false,
  /** 关注、取消关注、屏蔽在路上，按钮先不响应第二下。 */
  val followBusy: Boolean = false,
  val notice: Notice? = null,
  val mediaBaseUrl: String? = null,
  val showIpRegion: Boolean = false,
  /** 「查看数据」弹窗；null = 没开。 */
  val insights: FeedInsightsState? = null,
  /** 别人的主页：我对 TA 的「不看 / 不让看」；还没查到是 null。 */
  val hideState: FeedHideState? = null
) {
  enum class Notice {
    /** 底部提示条带「撤销」（第三节「统一反馈」：关注能撤销）。 */
    FOLLOWED,
    REQUESTED,

    /** 关注人之前要先建自己的公开主页。 */
    PROFILE_REQUIRED,
    BLOCKED,
    UNBLOCKED,

    /** 置顶满 3 条。 */
    PIN_LIMIT,
    MUTED,
    HIDDEN_FROM,
    FAILED
  }
}

data class FeedHideState(val muted: Boolean, val hiddenFrom: Boolean)
