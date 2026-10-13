/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

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
import org.thoughtcrime.securesms.feed.FeedSavedMessages
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.NoteToSelfFeedSavedMessages
import org.thoughtcrime.securesms.feed.isPinLimit
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.setSaved
import org.thoughtcrime.securesms.feed.withLiked
import org.thoughtcrime.securesms.feed.withPollState
import org.thoughtcrime.securesms.feed.withSaved
import org.thoughtcrime.securesms.feed.withSettings

/**
 * 帖子详情：整条帖子 + 评论（`dynamic-feed.md` 第三节第 2、4、10 条；契约第五节）。
 *
 * 打开时先用信息流里带过来的卡片画出来，再用 Card API 取一次最新的（删了、看不到了就显示「已不可见」）。
 * 评论按楼排（[FeedCommentThreads]）；发出去的评论先审后显，审核通过前列表里没有它，只提示一次。
 */
class FeedPostDetailViewModel(
  initial: FeedCard,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val savedMessages: FeedSavedMessages = NoteToSelfFeedSavedMessages
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedPostDetailViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedPostDetailState(post = initial, canComment = initial.viewer.canComment))
  val state: StateFlow<FeedPostDetailState> = internalState.asStateFlow()

  private var commentsJob: Job? = null

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update { it.copy(mediaBaseUrl = config.mediaBaseUrl, showIpRegion = config.ipRegionVisible) }
      } catch (e: Exception) {
        Log.w(TAG, "config failed", e)
      }
    }
    viewModelScope.launch {
      try {
        val fresh = withContext(ioDispatcher) { source.card(initial.id) }
        internalState.update { if (fresh == null) it.copy(postGone = true) else it.copy(post = fresh, canComment = fresh.viewer.canComment) }
      } catch (e: Exception) {
        Log.w(TAG, "card refresh failed, keeping the feed copy", e)
      }
    }
    refreshComments()
  }

  fun refreshComments() {
    commentsJob?.cancel()
    internalState.update { it.copy(loading = true, failed = false) }
    commentsJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.comments(internalState.value.post.id, cursor = null) }
        internalState.update { it.copy(comments = page.comments, cursor = page.cursor, loading = false, loadedOnce = true) }
      } catch (e: Exception) {
        Log.w(TAG, "comments failed", e)
        internalState.update { it.copy(loading = false, failed = true, loadedOnce = true) }
      }
    }
  }

  fun loadMoreComments() {
    val cursor = internalState.value.cursor ?: return
    if (commentsJob?.isActive == true) return
    internalState.update { it.copy(loading = true) }
    commentsJob = viewModelScope.launch {
      try {
        val page = withContext(ioDispatcher) { source.comments(internalState.value.post.id, cursor = cursor) }
        internalState.update { state ->
          val known = state.comments.mapTo(HashSet()) { it.id }
          state.copy(comments = state.comments + page.comments.filterNot { it.id in known }, cursor = page.cursor, loading = false)
        }
      } catch (e: Exception) {
        Log.w(TAG, "comments page failed", e)
        internalState.update { it.copy(loading = false) }
      }
    }
  }

  fun toggleLike() {
    val post = internalState.value.post
    val nowLiked = !post.viewer.liked
    internalState.update { it.copy(post = it.post.withLiked(nowLiked)) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.setLiked(post.id, nowLiked) }
      } catch (e: Exception) {
        Log.w(TAG, "like failed", e)
        internalState.update { it.copy(post = it.post.withLiked(!nowLiked), notice = FeedPostDetailState.Notice.FAILED) }
      }
    }
  }

  private var saving = false

  /** 收藏 / 取消收藏：先改「我的收藏」，再以假名上报（上报失败不改回）。 */
  fun toggleSave() {
    if (saving) return
    saving = true
    val post = internalState.value.post
    val nowSaved = !post.viewer.saved
    internalState.update { it.copy(post = it.post.withSaved(nowSaved)) }
    viewModelScope.launch {
      try {
        savedMessages.setSaved(post, nowSaved, source, ioDispatcher)
        if (nowSaved) internalState.update { it.copy(notice = FeedPostDetailState.Notice.SAVED) }
      } catch (e: Exception) {
        Log.w(TAG, "saved messages failed", e)
        internalState.update { it.copy(post = it.post.withSaved(!nowSaved), notice = FeedPostDetailState.Notice.FAILED) }
      } finally {
        saving = false
      }
    }
  }

  private var voting = false

  /** 投票：等服务端回了票数再换上（投了不能改）。 */
  fun vote(options: List<Int>) {
    if (options.isEmpty() || voting) return
    voting = true
    val postId = internalState.value.post.id
    viewModelScope.launch {
      try {
        val state = withContext(ioDispatcher) { source.vote(postId, options) }
        internalState.update { it.copy(post = it.post.withPollState(state)) }
      } catch (e: Exception) {
        Log.w(TAG, "vote failed", e)
        internalState.update { it.copy(notice = FeedPostDetailState.Notice.FAILED) }
      } finally {
        voting = false
      }
    }
  }

  fun onDraftChanged(text: String) {
    internalState.update { it.copy(draft = text) }
  }

  fun replyTo(comment: FeedCard?) {
    internalState.update { it.copy(replyTarget = comment) }
  }

  fun send() {
    val state = internalState.value
    val text = state.draft.trim()
    if (text.isEmpty() || state.sending || !state.canComment) return

    internalState.update { it.copy(sending = true) }
    viewModelScope.launch {
      try {
        val publishState = withContext(ioDispatcher) { source.postComment(state.post.id, text, state.replyTarget?.id) }
        val pending = publishState == FeedCreatePostResponse.STATE_PENDING
        internalState.update {
          it.copy(
            sending = false,
            draft = "",
            replyTarget = null,
            notice = if (pending) FeedPostDetailState.Notice.PENDING else FeedPostDetailState.Notice.PUBLISHED
          )
        }
        if (!pending) refreshComments()
      } catch (e: FeedApiException) {
        Log.w(TAG, "comment rejected: ${e.status} ${e.code}")
        internalState.update {
          when (e.code) {
            "comments_closed", "not_commentable" -> it.copy(sending = false, canComment = false, notice = FeedPostDetailState.Notice.CLOSED)
            "reply_not_allowed" -> it.copy(sending = false, notice = FeedPostDetailState.Notice.REPLY_NOT_ALLOWED)
            "profile_required" -> it.copy(sending = false, notice = FeedPostDetailState.Notice.PROFILE_REQUIRED)
            "invalid_parent" -> it.copy(sending = false, replyTarget = null, notice = FeedPostDetailState.Notice.PARENT_GONE)
            else -> it.copy(sending = false, notice = FeedPostDetailState.Notice.FAILED)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "comment failed", e)
        internalState.update { it.copy(sending = false, notice = FeedPostDetailState.Notice.FAILED) }
      }
    }
  }

  private val moderating = mutableSetOf<String>()

  /** 帖子作者批准一条等着的回复（第三节第 40 条）：成功后去掉「等你批准」，别人就看得到了。 */
  fun approveComment(comment: FeedCard) = moderate(comment) {
    source.approveComment(internalState.value.post.id, comment.id)
    internalState.update { state -> state.copy(comments = state.comments.map { if (it.id == comment.id) it.copy(held = false) else it }) }
  }

  /** 拒绝 = 删掉这条回复（引擎没有单独的拒绝）。 */
  fun declineComment(comment: FeedCard) = moderate(comment) {
    source.deletePost(comment.id)
    internalState.update { state -> state.copy(comments = state.comments.filterNot { it.id == comment.id }) }
  }

  private fun moderate(comment: FeedCard, action: suspend () -> Unit) {
    if (!comment.held || !moderating.add(comment.id)) return
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { action() }
      } catch (e: Exception) {
        Log.w(TAG, "moderating a reply failed", e)
        internalState.update { it.copy(notice = FeedPostDetailState.Notice.FAILED) }
      } finally {
        moderating -= comment.id
      }
    }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  /** 自己帖子「···」里的置顶、隐藏点赞数（先改，失败改回）和查看数据（第三节第 26 条）。回复选项卡片自己处理。 */
  fun onOwnPostAction(action: FeedOwnPostAction) {
    val post = internalState.value.post
    val update = action.settingsUpdate
    if (update == null) {
      if (action == FeedOwnPostAction.INSIGHTS) loadInsights(post.id)
      return
    }
    internalState.update { it.copy(post = it.post.withSettings(update)) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.updatePostSettings(post.id, update) }
      } catch (e: Exception) {
        Log.w(TAG, "post settings failed", e)
        internalState.update {
          it.copy(
            post = it.post.copy(pinned = post.pinned, stats = it.post.stats.copy(likeCountHidden = post.stats.likeCountHidden)),
            notice = if (e.isPinLimit()) FeedPostDetailState.Notice.PIN_LIMIT else FeedPostDetailState.Notice.FAILED
          )
        }
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

  /** 删掉自己的帖子：删成功了页面关掉；失败留在这里提示。 */
  fun deletePost() {
    val post = internalState.value.post
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.deletePost(post.id) }
        internalState.update { it.copy(deleted = true) }
      } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        internalState.update { it.copy(notice = FeedPostDetailState.Notice.FAILED) }
      }
    }
  }
}

data class FeedPostDetailState(
  val post: FeedCard,
  /** Card API 说这条已经看不到了（删了、被屏蔽、没权限）。 */
  val postGone: Boolean = false,
  val comments: List<FeedCard> = emptyList(),
  val cursor: String? = null,
  val loading: Boolean = true,
  val loadedOnce: Boolean = false,
  val failed: Boolean = false,
  val canComment: Boolean = true,
  val draft: String = "",
  /** 正在回复的评论；null = 直接评论帖子。 */
  val replyTarget: FeedCard? = null,
  val sending: Boolean = false,
  val notice: Notice? = null,
  val mediaBaseUrl: String? = null,
  val showIpRegion: Boolean = false,
  /** 「查看数据」弹窗；null = 没开。 */
  val insights: FeedInsightsState? = null,
  /** 自己删掉了这条，页面该关了。 */
  val deleted: Boolean = false
) {
  val threads: List<FeedCommentThread> by lazy { FeedCommentThreads.build(comments) }

  val canSend: Boolean
    get() = canComment && !sending && draft.isNotBlank()

  enum class Notice {
    /** 发出去了，等审核（大陆区一律先审后发）。 */
    PENDING,
    PUBLISHED,
    CLOSED,
    REPLY_NOT_ALLOWED,

    /** 引擎要先建公开主页才能评论；建主页的页面还没做。 */
    PROFILE_REQUIRED,
    PARENT_GONE,

    /** 置顶已满 3 条。 */
    PIN_LIMIT,

    /** 已存进「我的收藏」。 */
    SAVED,
    FAILED
  }
}
