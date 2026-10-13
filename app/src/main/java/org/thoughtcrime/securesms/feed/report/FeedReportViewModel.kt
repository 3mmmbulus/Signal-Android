/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

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
import org.thoughtcrime.securesms.feed.net.FeedReportRequest

/**
 * 举报（第三节第 32 条）：先选原因（最上面先分流「只是不想看到」），再补充说明 / 截图 / 关系，最后提交。
 * 截图在提交时才上传：交了就不能撤（引擎把它们标成 attached）。
 */
class FeedReportViewModel(
  private val target: FeedReportTarget,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedReportViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedReportState(groups = FeedReportReasons.groups(emptyMap())))
  val state: StateFlow<FeedReportState> = internalState.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        val config = withContext(ioDispatcher) { source.config() }
        internalState.update { it.copy(groups = FeedReportReasons.groups(config.reportReasons), region = config.region) }
      } catch (e: Exception) {
        Log.w(TAG, "config failed, using the built-in reasons", e)
      }
    }
  }

  fun pick(reason: FeedReportReason) {
    internalState.update { it.copy(reason = reason, error = null) }
  }

  /** 回到原因列表；已经填的说明留着。 */
  fun backToReasons() {
    internalState.update { it.copy(reason = null) }
  }

  fun onDetailChanged(text: String) {
    internalState.update { it.copy(detail = text.take(FeedReportReason.MAX_DETAIL_CHARS)) }
  }

  fun onRelationshipChanged(text: String) {
    internalState.update { it.copy(relationship = text.take(FeedReportReason.MAX_RELATIONSHIP_CHARS)) }
  }

  fun addEvidence(uris: List<Uri>) {
    internalState.update { state ->
      val room = FeedReportReason.MAX_EVIDENCE - state.evidence.size
      state.copy(evidence = state.evidence + uris.filterNot { it in state.evidence }.take(room.coerceAtLeast(0)))
    }
  }

  fun removeEvidence(uri: Uri) {
    internalState.update { it.copy(evidence = it.evidence - uri) }
  }

  fun submit() {
    val state = internalState.value
    val reason = state.reason ?: return
    if (!state.canSubmit) return

    internalState.update { it.copy(sending = true, error = null) }
    viewModelScope.launch {
      try {
        val keys = withContext(ioDispatcher) {
          if (reason.needsEvidence) state.evidence.map { source.uploadImage(it) } else emptyList()
        }
        val request = FeedReportRequest(
          targetType = target.type,
          targetId = target.id,
          group = reason.group,
          reason = reason.code,
          detail = state.detail.trim().ifEmpty { null },
          evidenceKeys = keys.ifEmpty { null },
          relationship = if (reason.needsRelationship) state.relationship.trim() else null
        )
        withContext(ioDispatcher) { source.report(request) }
        internalState.update { it.copy(sending = false, sent = true) }
      } catch (e: FeedApiException) {
        Log.w(TAG, "report rejected: ${e.status} ${e.code}")
        val error = if (e.status == 404) FeedReportState.Error.GONE else FeedReportState.Error.FAILED
        internalState.update { it.copy(sending = false, error = error) }
      } catch (e: Exception) {
        Log.w(TAG, "report failed", e)
        internalState.update { it.copy(sending = false, error = FeedReportState.Error.FAILED) }
      }
    }
  }

  fun onErrorShown() {
    internalState.update { it.copy(error = null) }
  }
}

data class FeedReportState(
  val groups: List<FeedReportGroup>,
  /** `cn` 时附 12377 入口（第 32 条）。 */
  val region: String? = null,
  /** null = 还在选原因。 */
  val reason: FeedReportReason? = null,
  val detail: String = "",
  val relationship: String = "",
  val evidence: List<Uri> = emptyList(),
  val sending: Boolean = false,
  val sent: Boolean = false,
  val error: Error? = null
) {
  val canSubmit: Boolean
    get() {
      val reason = reason ?: return false
      return !sending &&
        (!reason.needsEvidence || evidence.isNotEmpty()) &&
        (!reason.needsRelationship || relationship.isNotBlank())
    }

  val showMainlandHotline: Boolean
    get() = region == "cn"

  enum class Error {
    /** 内容已经看不到了。 */
    GONE,
    FAILED
  }
}
