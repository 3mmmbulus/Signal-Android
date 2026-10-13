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
import org.thoughtcrime.securesms.feed.net.FeedLimits
import java.util.UUID

/**
 * 发布页的状态。字数、图片张数的上限和未成年人模式都来自服务端 `config`（契约第三节），客户端不自己判断区域。
 */
class FeedComposeViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedComposeViewModel::class)
  }

  /** 这条草稿的去重号（契约 7.2 `client_nonce`）：点了发布、网络断了再点，服务端只收一次。 */
  private val clientNonce = UUID.randomUUID().toString()

  private val internalState = MutableStateFlow(FeedComposeState())
  val state: StateFlow<FeedComposeState> = internalState.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update {
          val draft = if (it.draft.isEmpty) it.draft.copy(visibility = FeedVisibility.default(config.minorMode)) else it.draft
          it.copy(limits = config.limits, minorMode = config.minorMode, draft = draft)
        }
      } catch (e: Exception) {
        Log.w(TAG, "config failed, using defaults", e)
      }
    }
  }

  fun onKindChanged(kind: FeedDraftKind) = updateDraft { it.copy(kind = kind) }

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

    internalState.update { it.copy(publishing = true, failed = false) }
    viewModelScope.launch {
      try {
        val response = withContext(ioDispatcher) { source.publish(current.draft, clientNonce) }
        internalState.update { it.copy(publishing = false, publishedState = response.state) }
      } catch (e: Exception) {
        Log.w(TAG, "publish failed", e)
        internalState.update { it.copy(publishing = false, failed = true) }
      }
    }
  }

  fun onFailureShown() {
    internalState.update { it.copy(failed = false) }
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
  /** 发出去之后服务端给的 `state`（`pending` / `published`）；非空表示可以关页面了。 */
  val publishedState: String? = null
) {
  val problem: FeedDraft.Problem?
    get() = draft.problem(limits, minorMode)

  val textLimit: Int
    get() = draft.textLimit(limits)

  val canAddImages: Boolean
    get() = draft.kind == FeedDraftKind.POST && draft.images.size < limits.postImages
}
