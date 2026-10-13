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
 */
class FeedComposeViewModel(
  private val editTarget: FeedEditTarget? = null,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedComposeViewModel::class)
  }

  /** 这条草稿的去重号（契约 7.2 `client_nonce`）：点了发布、网络断了再点，服务端只收一次。 */
  private val clientNonce = UUID.randomUUID().toString()

  private val internalState = MutableStateFlow(
    if (editTarget == null) {
      FeedComposeState()
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
          val draft = if (it.draft.isEmpty && it.editing == null) it.draft.copy(visibility = FeedVisibility.default(config.minorMode)) else it.draft
          it.copy(limits = config.limits, minorMode = config.minorMode, draft = draft)
        }
      } catch (e: Exception) {
        Log.w(TAG, "config failed, using defaults", e)
      }
    }
  }

  fun onKindChanged(kind: FeedDraftKind) {
    if (internalState.value.editing == null) updateDraft { it.copy(kind = kind) }
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

  fun publish() {
    val current = internalState.value
    if (current.publishing || current.problem != null) {
      return
    }

    internalState.update { it.copy(publishing = true, failed = false, editWindowClosed = false) }
    viewModelScope.launch {
      try {
        val target = current.editing
        val response = withContext(ioDispatcher) {
          if (target != null) source.editPost(target.id, current.draft) else source.publish(current.draft, clientNonce)
        }
        internalState.update { it.copy(publishing = false, publishedState = response.state) }
      } catch (e: FeedApiException) {
        Log.w(TAG, "publish failed: ${e.status} ${e.code}")
        val closed = e.status == 409 && (e.code == "edit_window_closed" || e.code == "not_editable")
        val noProfile = e.status == 409 && e.code == "profile_required"
        internalState.update { it.copy(publishing = false, failed = !closed && !noProfile, editWindowClosed = closed, profileRequired = noProfile) }
      } catch (e: Exception) {
        Log.w(TAG, "publish failed", e)
        internalState.update { it.copy(publishing = false, failed = true) }
      }
    }
  }

  fun onFailureShown() {
    internalState.update { it.copy(failed = false, editWindowClosed = false) }
  }

  /** 已经打开了建主页的页面。 */
  fun onProfileRequiredShown() {
    internalState.update { it.copy(profileRequired = false) }
  }

  private fun updateDraft(transform: (FeedDraft) -> FeedDraft) {
    internalState.update { it.copy(draft = transform(it.draft)) }
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
  /** 引擎说还没建公开主页（409 `profile_required`）：先去建，建好接着发。 */
  val profileRequired: Boolean = false,
  /** 编辑模式：在改哪一条，和打开时的样子（没改动就不让保存、关页面也不问）。 */
  val editing: FeedEditTarget? = null,
  val original: FeedDraft? = null,
  /** 发出去之后服务端给的 `state`（`pending` / `published`）；非空表示可以关页面了。 */
  val publishedState: String? = null
) {
  /** 编辑时可见范围不动，所以不查可见范围那两项；没改动也不能保存。 */
  val problem: FeedDraft.Problem?
    get() {
      val problem = draft.problem(limits, minorMode)
      return when {
        editing == null -> problem
        problem == FeedDraft.Problem.VISIBILITY_NOT_ALLOWED || problem == FeedDraft.Problem.MISSING_AUDIENCE -> if (isUnchanged) FeedDraft.Problem.UNCHANGED else null
        problem == null && isUnchanged -> FeedDraft.Problem.UNCHANGED
        else -> problem
      }
    }

  val isUnchanged: Boolean
    get() = original != null && original.title.trim() == draft.title.trim() && original.text.trim() == draft.text.trim()

  /** 关页面要不要问「放弃吗」。 */
  val hasUnsavedChanges: Boolean
    get() = if (editing != null) !isUnchanged else !draft.isEmpty

  val textLimit: Int
    get() = draft.textLimit(limits)

  val canAddImages: Boolean
    get() = editing == null && draft.kind == FeedDraftKind.POST && draft.images.size < limits.postImages
}
