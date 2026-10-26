/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.mediasend.v2.UntrustedRecords
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * 转发网格（tellomi/tellomi#1259）：选聊天、写附言、发送。
 *
 * 审计 5.4：有带附件且有说明文字的消息时，底栏可「隐藏说明文字」（对齐 iOS #382）。
 *
 * 发送与上游转发面板同一条路：先查对方安全码有没有刚变过（[UntrustedRecords]，变过就停在 [Stage.SafetyConfirmation] 等确认），
 * 再交给 [MultiselectForwardRepository.send]（附言先发，F-7）。
 */
class TellomiForwardGridViewModel(
  private val multiShareArgs: List<MultiShareArgs>,
  private val repository: TellomiForwardTargetsRepository,
  private val selectionLimit: Int = RemoteConfig.shareSelectionLimit.hardLimit,
  private val identityChangesSince: Long = System.currentTimeMillis(),
  private val sender: Sender = DefaultSender,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  data class State(
    val isLoading: Boolean = true,
    /** 网格顺序：我的收藏 → 从搜索里选中的（新的在前，F-9）→ 其余候选 */
    val targets: List<TellomiForwardTarget> = emptyList(),
    /** 按勾选顺序 */
    val selected: List<RecipientId> = emptyList(),
    val selectedTargets: List<TellomiForwardTarget> = emptyList(),
    val isSearchActive: Boolean = false,
    val query: String = "",
    val searchResults: TellomiForwardSearchResults = TellomiForwardSearchResults(),
    val recentContacts: List<TellomiForwardTarget> = emptyList(),
    val message: String = "",
    /** 审计 5.4：有可隐藏的说明文字时底栏显示开关；默认关 */
    val showHideCaptionToggle: Boolean = false,
    val hideCaption: Boolean = false,
    val stage: Stage = Stage.Selection
  )

  sealed interface Stage {
    data object Selection : Stage
    data object LoadingIdentities : Stage
    data class SafetyConfirmation(val identities: List<IdentityRecord>, val destinations: List<ContactSearchKey.RecipientSearchKey>) : Stage
    data object SendPending : Stage
    data class Sent(val result: Result, val recipients: List<TellomiForwardTarget>) : Stage
  }

  enum class Result {
    SUCCESS,
    SOME_FAILED,
    ALL_FAILED
  }

  sealed interface Event {
    data class SelectionLimitReached(val limit: Int) : Event
  }

  /** 发送的两步，用例里换成假的 */
  interface Sender {
    fun checkIdentities(keys: Set<ContactSearchKey.RecipientSearchKey>, changedSince: Long, callback: (List<IdentityRecord>) -> Unit)
    fun send(message: String, multiShareArgs: List<MultiShareArgs>, keys: Set<ContactSearchKey>, onResult: (Result) -> Unit)
  }

  object DefaultSender : Sender {
    override fun checkIdentities(keys: Set<ContactSearchKey.RecipientSearchKey>, changedSince: Long, callback: (List<IdentityRecord>) -> Unit) {
      UntrustedRecords.checkForBadIdentityRecords(keys, changedSince) { callback(it) }
    }

    override fun send(message: String, multiShareArgs: List<MultiShareArgs>, keys: Set<ContactSearchKey>, onResult: (Result) -> Unit) {
      MultiselectForwardRepository.send(
        additionalMessage = message,
        multiShareArgs = multiShareArgs,
        shareContacts = keys,
        resultHandlers = MultiselectForwardRepository.MultiselectForwardResultHandlers(
          onAllMessageSentSuccessfully = { onResult(Result.SUCCESS) },
          onSomeMessagesFailed = { onResult(Result.SOME_FAILED) },
          onAllMessagesFailed = { onResult(Result.ALL_FAILED) }
        )
      )
    }
  }

  private val internalState = MutableStateFlow(
    State(showHideCaptionToggle = TellomiForwardCaptionPolicy.showsHideCaptionToggle(multiShareArgs))
  )
  val state: StateFlow<State> = internalState

  private val internalEvents = MutableSharedFlow<Event>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val events: SharedFlow<Event> = internalEvents

  private var allTargets: List<TellomiForwardTarget> = emptyList()
  private var promoted: List<TellomiForwardTarget> = emptyList()
  private val knownTargets = mutableMapOf<RecipientId, TellomiForwardTarget>()
  private var searchJob: Job? = null

  init {
    viewModelScope.launch {
      val loaded = withContext(ioDispatcher) { repository.load() }
      allTargets = loaded
      loaded.forEach { knownTargets[it.id] = it }
      internalState.update {
        it.copy(
          isLoading = false,
          targets = gridTargets(),
          recentContacts = repository.recentContacts(loaded)
        ).withSelectedTargets()
      }
    }
  }

  // region 选择（F-6）

  /** 点一格：切换选中；超过上限不选并发 [Event.SelectionLimitReached]。在搜索里选中：回到网格，插在「我的收藏」之后（F-9）。 */
  fun onTargetClicked(target: TellomiForwardTarget, fromSearch: Boolean) {
    if (internalState.value.stage != Stage.Selection) {
      return
    }
    knownTargets[target.id] = target
    val selected = internalState.value.selected
    if (target.id in selected) {
      internalState.update { it.copy(selected = selected - target.id).withSelectedTargets() }
      return
    }
    if (selected.size >= selectionLimit) {
      internalEvents.tryEmit(Event.SelectionLimitReached(selectionLimit))
      return
    }
    if (fromSearch && !target.isSavedMessages) {
      promoted = listOf(target) + promoted.filterNot { it.id == target.id }
    }
    internalState.update {
      val next = it.copy(selected = selected + target.id)
      if (fromSearch) {
        next.copy(isSearchActive = false, query = "", searchResults = TellomiForwardSearchResults(), targets = gridTargets())
      } else {
        next
      }.withSelectedTargets()
    }
  }

  private fun gridTargets(): List<TellomiForwardTarget> {
    val promotedIds = promoted.map { it.id }.toSet()
    val savedMessages = allTargets.filter { it.isSavedMessages }
    return savedMessages + promoted + allTargets.filter { !it.isSavedMessages && it.id !in promotedIds }
  }

  private fun State.withSelectedTargets(): State {
    return copy(selectedTargets = selected.mapNotNull { knownTargets[it] })
  }

  // endregion

  // region 搜索（F-9）

  fun setSearchActive(active: Boolean) {
    if (!active) {
      searchJob?.cancel()
    }
    internalState.update {
      if (active) {
        it.copy(isSearchActive = true)
      } else {
        it.copy(isSearchActive = false, query = "", searchResults = TellomiForwardSearchResults())
      }
    }
  }

  fun setQuery(query: String) {
    internalState.update { it.copy(query = query, isSearchActive = true) }
    searchJob?.cancel()
    if (query.isBlank()) {
      internalState.update { it.copy(searchResults = TellomiForwardSearchResults()) }
      return
    }
    val chats = allTargets
    searchJob = viewModelScope.launch {
      val results = withContext(ioDispatcher) { repository.search(query, chats) }
      if (internalState.value.query == query) {
        internalState.update { it.copy(searchResults = results) }
      }
    }
  }

  // endregion

  // region 发送（F-7 / F-8）

  fun setMessage(message: String) {
    internalState.update { it.copy(message = message) }
  }

  /** 审计 5.4：拨动「隐藏说明文字」；没有可隐藏说明时界面不调。 */
  fun setHideCaption(hide: Boolean) {
    if (!internalState.value.showHideCaptionToggle) {
      return
    }
    internalState.update { it.copy(hideCaption = hide) }
  }

  fun send() {
    val current = internalState.value
    if (current.selected.isEmpty() || current.stage != Stage.Selection) {
      return
    }
    checkIdentitiesThenSend(current.selectedTargets.map { it.key }.toCollection(LinkedHashSet()))
  }

  /**
   * 安全码确认框里点了「仍然发送」：按确认框交回来的收件人从头再查一遍（同上游）。
   * 确认框里可以把人移出这次发送，所以不用网格里的选择；进程被回收重建后网格的选择是空的，也只能靠它。
   */
  fun confirmSafetySend(destinations: List<ContactSearchKey.RecipientSearchKey>) {
    checkIdentitiesThenSend(destinations.toCollection(LinkedHashSet()))
  }

  fun cancelSend() {
    internalState.update { it.copy(stage = Stage.Selection) }
  }

  private fun checkIdentitiesThenSend(keys: Set<ContactSearchKey.RecipientSearchKey>) {
    if (keys.isEmpty()) {
      internalState.update { it.copy(stage = Stage.Selection) }
      return
    }
    internalState.update { it.copy(stage = Stage.LoadingIdentities) }
    sender.checkIdentities(keys, identityChangesSince) { identities ->
      if (identities.isEmpty()) {
        performSend(keys)
      } else {
        internalState.update { it.copy(stage = Stage.SafetyConfirmation(identities, keys.toList())) }
      }
    }
  }

  private fun performSend(keys: Set<ContactSearchKey.RecipientSearchKey>) {
    // 收件人为空时上游会直接回「全部发出」：宁可回到选择，也不能假装发出去了
    if (keys.isEmpty()) {
      internalState.update { it.copy(stage = Stage.Selection) }
      return
    }
    val current = internalState.value
    val message = current.message.trim()
    val argsToSend = TellomiForwardCaptionPolicy.outgoingArgs(multiShareArgs, current.hideCaption)
    val recipients = keys.mapNotNull { knownTargets[it.recipientId] }
    internalState.update { it.copy(stage = Stage.SendPending) }
    sender.send(message, argsToSend, keys) { result ->
      internalState.update { it.copy(stage = Stage.Sent(result, recipients)) }
    }
  }

  // endregion
}
