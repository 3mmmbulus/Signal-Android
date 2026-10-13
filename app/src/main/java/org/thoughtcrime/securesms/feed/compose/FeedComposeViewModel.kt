/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedLimits
import java.util.UUID

/**
 * 发布页的状态。字数、图片张数的上限和未成年人模式都来自服务端 `config`（契约第三节），客户端不自己判断区域。
 *
 * [editTarget] 非空时是编辑已发的那条（第三节第 23 条）：只改正文和长文标题，类型、图片、可见范围不动。
 * [quoteTarget] 非空时是引用那条发一条新的（第 26 条）。
 */
class FeedComposeViewModel(
  private val editTarget: FeedEditTarget? = null,
  quoteTarget: FeedQuoteTarget? = null,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val articleDrafts: FeedArticleDrafts = StoredFeedArticleDrafts
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedComposeViewModel::class)
  }

  /** 这条草稿的去重号（契约 7.2 `client_nonce`）：点了发布、网络断了再点，服务端只收一次。 */
  private val clientNonce = UUID.randomUUID().toString()

  private val internalState = MutableStateFlow(
    if (editTarget == null && quoteTarget == null) {
      // 上次没写完的长文接着写（第 25 条）
      val saved = articleDrafts.load()?.takeIf { it.title.isNotBlank() || it.text.isNotBlank() }
      if (saved != null) {
        FeedComposeState(draft = FeedDraft(kind = FeedDraftKind.ARTICLE, title = saved.title, text = saved.text), restoredArticle = true)
      } else {
        FeedComposeState()
      }
    } else if (editTarget == null) {
      FeedComposeState(draft = FeedDraft(quote = quoteTarget))
    } else {
      val draft = FeedDraft(kind = editTarget.draftKind, title = editTarget.title.orEmpty(), text = editTarget.text)
      FeedComposeState(draft = draft, editing = editTarget, original = draft)
    }
  )
  val state: StateFlow<FeedComposeState> = internalState.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update {
          // 新的一条用默认档；恢复出来的长文如果是这个模式下不许的档（未成年人模式不能公开），也换成默认档
          val useDefault = it.editing == null && (it.draft.isEmpty || it.draft.visibility !in FeedVisibility.allowed(config.minorMode))
          val draft = if (useDefault) it.draft.copy(visibility = FeedVisibility.default(config.minorMode)) else it.draft
          it.copy(limits = config.limits, minorMode = config.minorMode, draft = draft)
        }
      } catch (e: Exception) {
        Log.w(TAG, "config failed, using defaults", e)
      }
    }
  }

  fun onKindChanged(kind: FeedDraftKind) {
    // 编辑时不换类型；引用只能是动态
    if (internalState.value.editing == null && internalState.value.draft.quote == null) updateDraft { it.copy(kind = kind) }
  }

  fun onTitleChanged(title: String) = updateDraft { it.copy(title = title) }

  fun onTextChanged(text: String) = updateDraft { it.copy(text = text) }

  /** 加图，超出上限的部分不要（选图器本身也限了张数，这里兜底）。 */
  fun onImagesPicked(uris: List<Uri>) {
    updateDraft { draft ->
      val room = (internalState.value.limits.postImages - draft.images.size).coerceAtLeast(0)
      draft.copy(images = draft.images + uris.filterNot { it in draft.images }.take(room))
    }
  }

  fun onImageRemoved(uri: Uri) = updateDraft { it.copy(images = it.images - uri) }

  fun onVisibilityChanged(visibility: FeedVisibility) {
    if (visibility in FeedVisibility.allowed(internalState.value.minorMode) && !visibility.needsAudience) {
      updateDraft { it.copy(visibility = visibility) }
    }
  }

  /** 正在给哪一档选人（选人页开着时转屏，回来还知道是哪一档）。 */
  var pickingAudienceFor: FeedVisibility? = null

  /** 选人页回来：选了人才换到这一档；一个没选（或者取消）就保持原来的档位。 */
  fun onAudiencePicked(visibility: FeedVisibility, members: List<FeedAudienceMember>) {
    if (!visibility.needsAudience || visibility !in FeedVisibility.allowed(internalState.value.minorMode) || members.isEmpty()) {
      return
    }
    updateDraft { it.copy(visibility = visibility, audience = members) }
  }

  fun onReplySettingsChanged(settings: FeedReplySettings) {
    if (internalState.value.editing == null) updateDraft { it.copy(replies = settings) }
  }

  /** 选上或取消一个话题；满 3 个时再选不进去。编辑时不能改（引擎编辑只收正文和标题）。 */
  fun onTopicToggled(slug: String) {
    if (internalState.value.editing != null) return
    updateDraft { draft ->
      when {
        slug in draft.topics -> draft.copy(topics = draft.topics - slug)
        draft.topics.size >= FeedDraft.MAX_TOPICS -> draft
        else -> draft.copy(topics = draft.topics + slug)
      }
    }
  }

  /** 加投票（编辑时不能加：引擎编辑只收正文和标题）。 */
  fun onPollAdded() {
    if (internalState.value.canAddPoll) updateDraft { it.copy(poll = FeedDraftPoll()) }
  }

  fun onPollRemoved() = updateDraft { it.copy(poll = null) }

  fun onPollOptionChanged(index: Int, text: String) = updatePoll { poll ->
    if (index !in poll.options.indices) poll else poll.copy(options = poll.options.toMutableList().also { it[index] = FeedDraftPoll.clip(text) })
  }

  fun onPollOptionAdded() = updatePoll { if (it.canAddOption) it.copy(options = it.options + "") else it }

  /** 至少留两个格子。 */
  fun onPollOptionRemoved(index: Int) = updatePoll { poll ->
    if (poll.options.size <= FeedDraftPoll.MIN_OPTIONS || index !in poll.options.indices) poll else poll.copy(options = poll.options.filterIndexed { i, _ -> i != index })
  }

  fun onPollMultiChanged(multi: Boolean) = updatePoll { it.copy(multi = multi) }

  fun onPollDurationChanged(duration: FeedPollDuration) = updatePoll { it.copy(duration = duration) }

  fun publish() {
    val current = internalState.value
    if (current.publishing || current.problem != null) {
      return
    }

    internalState.update { it.copy(publishing = true, failed = false, editWindowClosed = false, quoteUnavailable = false) }
    viewModelScope.launch {
      try {
        val target = current.editing
        val response = withContext(ioDispatcher) {
          if (target != null) source.editPost(target.id, current.draft) else source.publish(current.draft, clientNonce)
        }
        if (target == null) articleDrafts.clear()
        internalState.update { it.copy(publishing = false, publishedState = response.state) }
      } catch (e: FeedApiException) {
        Log.w(TAG, "publish failed: ${e.status} ${e.code}")
        val closed = e.status == 409 && (e.code == "edit_window_closed" || e.code == "not_editable")
        val noProfile = e.status == 409 && e.code == "profile_required"
        // 引用的那条在写的时候被删了或看不到了：去掉引用，正文留着，用户可以照普通动态再发
        val quoteGone = e.status == 400 && e.code == "quote_unavailable" && current.draft.quote != null
        internalState.update {
          it.copy(
            publishing = false,
            failed = !closed && !noProfile && !quoteGone,
            editWindowClosed = closed,
            profileRequired = noProfile,
            quoteUnavailable = quoteGone,
            draft = if (quoteGone) it.draft.copy(quote = null) else it.draft
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "publish failed", e)
        internalState.update { it.copy(publishing = false, failed = true) }
      }
    }
  }

  fun onFailureShown() {
    internalState.update { it.copy(failed = false, editWindowClosed = false, quoteUnavailable = false) }
  }

  fun onRestoredArticleShown() {
    internalState.update { it.copy(restoredArticle = false) }
  }

  /** 已经打开了建主页的页面。 */
  fun onProfileRequiredShown() {
    internalState.update { it.copy(profileRequired = false) }
  }

  private fun updatePoll(transform: (FeedDraftPoll) -> FeedDraftPoll) = updateDraft { draft ->
    draft.poll?.let { draft.copy(poll = transform(it)) } ?: draft
  }

  private fun updateDraft(transform: (FeedDraft) -> FeedDraft) {
    val updated = internalState.updateAndGet { it.copy(draft = transform(it.draft)) }
    if (updated.autosavesArticle) {
      val draft = updated.draft
      if (draft.title.isBlank() && draft.text.isBlank()) articleDrafts.clear() else articleDrafts.save(FeedSavedArticle(draft.title, draft.text))
    }
  }
}

data class FeedComposeState(
  val draft: FeedDraft = FeedDraft(),
  val limits: FeedLimits = FeedLimits(),
  val minorMode: Boolean = false,
  val publishing: Boolean = false,
  val failed: Boolean = false,
  /** 编辑时引擎说过了 15 分钟（或这条已经不能改了）。 */
  val editWindowClosed: Boolean = false,
  /** 引擎说引用的那条已经看不到了（400 `quote_unavailable`），引用已从草稿里去掉。 */
  val quoteUnavailable: Boolean = false,
  /** 引擎说还没建公开主页（409 `profile_required`）：先去建，建好接着发。 */
  val profileRequired: Boolean = false,
  /** 编辑模式：在改哪一条，和打开时的样子（没改动就不让保存、关页面也不问）。 */
  val editing: FeedEditTarget? = null,
  val original: FeedDraft? = null,
  /** 打开时恢复了上次没写完的长文，提示一句。 */
  val restoredArticle: Boolean = false,
  /** 发出去之后服务端给的 `state`（`pending` / `published`）；非空表示可以关页面了。 */
  val publishedState: String? = null
) {
  /** 编辑时可见范围不动，所以不查可见范围那两项；没改动也不能保存。 */
  val problem: FeedDraft.Problem?
    get() {
      val problem = draft.problem(limits, minorMode)
      return when {
        editing == null -> problem
        problem == FeedDraft.Problem.VISIBILITY_NOT_ALLOWED || problem == FeedDraft.Problem.MISSING_AUDIENCE || problem == FeedDraft.Problem.AUDIENCE_TOO_LARGE -> if (isUnchanged) FeedDraft.Problem.UNCHANGED else null
        problem == null && isUnchanged -> FeedDraft.Problem.UNCHANGED
        else -> problem
      }
    }

  val isUnchanged: Boolean
    get() = original != null && original.title.trim() == draft.title.trim() && original.text.trim() == draft.text.trim()

  /**
   * 新写的长文（不是编辑、不是引用）边写边存在本机（第 25 条）。
   * 动态那一栏选了图或加了投票的不算：那些不存，关页面要问。
   */
  val autosavesArticle: Boolean
    get() = editing == null && draft.quote == null && draft.kind == FeedDraftKind.ARTICLE && draft.images.isEmpty() && draft.poll == null

  /** 关页面要不要问「放弃吗」。自动保存的长文不问。 */
  val hasUnsavedChanges: Boolean
    get() = if (editing != null) !isUnchanged else !draft.isEmpty && !autosavesArticle

  val textLimit: Int
    get() = draft.textLimit(limits)

  val canAddImages: Boolean
    get() = editing == null && draft.kind == FeedDraftKind.POST && draft.images.size < limits.postImages

  val canAddPoll: Boolean
    get() = editing == null && draft.kind == FeedDraftKind.POST && draft.poll == null
}
