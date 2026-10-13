/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.logging.Log
import org.signal.mediasend.MediaSendDependencies
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.R
import org.signal.mediasend.ToastEvent
import org.signal.mediasend.ToastMessage
import java.text.Normalizer
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页。结构与交互照 Telegram iOS 的 `AttachmentFileController` /
 * `AttachmentFileSearchItem`（只读机制，一行没搬），数据只查本机（见 [AttachmentFilesRepository]）。
 *
 * - 点一行 = 立即发送并关掉 Sheet（F-7）；本机已经没有的只提示、不发。
 * - 长按一行进多选（F-8，Android 的习惯：长按直接勾上这一行），勾的顺序带序号；发出时每个一条、说明挂在最后一个。
 * - 搜索（F-10）：本机文件名，大小写、全半角、变音符号都不敏感，0.6 秒防抖。
 * 真正的发送在会话页（[MediaSendFlowEvent.SendAttachmentFiles] 带回去）：和附件键盘发文件走同一条路。
 */
internal class AttachmentFilesViewModel(
  parentState: StateFlow<MediaSendFlowState>,
  private val parentEventEmitter: (MediaSendFlowEvent) -> Unit,
  private val repository: AttachmentFilesRepository = MediaSendDependencies.attachmentFilesRepository,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : EventDrivenViewModel<AttachmentFilesEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(AttachmentFilesViewModel::class)

    /** 本机文件名搜索：[query] 去掉首尾空白后为空就不搜。 */
    fun search(files: List<RecentSentFile>, query: String): List<RecentSentFile> {
      val needle = fold(query.trim())
      if (needle.isEmpty()) {
        return emptyList()
      }
      return files.filter { fold(it.fileName).contains(needle) }
    }

    /** 大小写、全半角（NFKC 把全角字母数字变半角）、变音符号都不敏感，同 iOS 的 `folding`。 */
    private fun fold(text: String): String {
      val halfWidth = Normalizer.normalize(text, Normalizer.Form.NFKC)
      val withoutMarks = Normalizer.normalize(halfWidth, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
      return withoutMarks.lowercase(Locale.ROOT)
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
  }

  private val _state = MutableStateFlow(AttachmentFilesState(dock = parentState.value.attachmentSheet?.dock.orEmpty()))
  val state: StateFlow<AttachmentFilesState> = _state.asStateFlow()

  private var searchJob: Job? = null

  init {
    viewModelScope.launch {
      val loaded = withContext(ioDispatcher) {
        repository.getRecentSentFiles(AttachmentFilesState.RECENT_LIMIT) to repository.getMaxFileSize()
      }
      _state.update { it.copy(files = loaded.first, maxFileSize = loaded.second) }
    }
  }

  override suspend fun processEvent(event: AttachmentFilesEvent) {
    when (event) {
      AttachmentFilesEvent.Close -> if (_state.value.isSelecting) endSelection() else parentEventEmitter(MediaSendFlowEvent.CloseRequested)
      AttachmentFilesEvent.Back -> when {
        _state.value.isSearching -> endSearch()
        _state.value.isSelecting -> endSelection()
      }
      AttachmentFilesEvent.SelectFromGalleryClicked -> parentEventEmitter(MediaSendFlowEvent.OpenFilesGallery)
      is AttachmentFilesEvent.FilesPicked -> if (event.uris.isNotEmpty()) send(pickedUris = event.uris)
      is AttachmentFilesEvent.FileClicked -> onFileClicked(event.file)
      is AttachmentFilesEvent.FileLongClicked -> onFileLongClicked(event.file)
      is AttachmentFilesEvent.CaptionChanged -> _state.update { it.copy(caption = event.caption) }
      AttachmentFilesEvent.SendSelection -> sendSelection()
      AttachmentFilesEvent.SearchOpened -> _state.update { it.copy(searchQuery = "", searchedQuery = "", searchResults = emptyList(), showsAllResults = false) }
      is AttachmentFilesEvent.SearchQueryChanged -> onSearchQueryChanged(event.query)
      AttachmentFilesEvent.SearchClosed -> endSearch()
      AttachmentFilesEvent.ShowMoreResults -> _state.update { it.copy(showsAllResults = true) }
      is AttachmentFilesEvent.DockEntryClicked -> onDockEntryClicked(event.entry)
    }
  }

  private fun onFileClicked(file: RecentSentFile) {
    if (!file.isOnDevice) {
      toast(R.string.AttachmentFilesScreen__cant_send_not_on_device)
      return
    }
    val selection = _state.value.selection
    if (selection != null) {
      val toggled = if (file.attachmentId in selection) selection - file.attachmentId else selection + file.attachmentId
      _state.update { it.copy(selection = toggled) }
      return
    }
    send(recentAttachmentIds = listOf(file.attachmentId))
  }

  private fun onFileLongClicked(file: RecentSentFile) {
    val state = _state.value
    if (!file.isOnDevice || state.isSelecting || state.isSearching) {
      return
    }
    _state.update { it.copy(selection = listOf(file.attachmentId)) }
  }

  private fun sendSelection() {
    val selection = _state.value.selection
    if (selection.isNullOrEmpty()) {
      return
    }
    send(recentAttachmentIds = selection, caption = _state.value.caption.trim().ifEmpty { null })
  }

  private fun send(recentAttachmentIds: List<Long> = emptyList(), pickedUris: List<Uri> = emptyList(), caption: String? = null) {
    parentEventEmitter(
      MediaSendFlowEvent.SendAttachmentFiles(
        MediaSendFlowActivityContract.AttachmentFilesResult(
          recentAttachmentIds = recentAttachmentIds,
          pickedUris = pickedUris,
          caption = caption
        )
      )
    )
  }

  private fun endSelection() {
    _state.update { it.copy(selection = null, caption = "") }
  }

  private fun onSearchQueryChanged(query: String) {
    _state.update { it.copy(searchQuery = query) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      delay(AttachmentFilesState.SEARCH_DEBOUNCE_MS)
      val results = search(_state.value.files.orEmpty(), query)
      _state.update { it.copy(searchedQuery = query, searchResults = results, showsAllResults = false) }
    }
  }

  private fun endSearch() {
    searchJob?.cancel()
    _state.update { it.copy(searchQuery = null, searchedQuery = "", searchResults = emptyList(), showsAllResults = false) }
  }

  /** dock：置灰的只提示；「相册」换回选图页；当前页（文件）由页面自己回到顶部、展开；别的交给会话页。 */
  private fun onDockEntryClicked(entry: MediaSendFlowActivityContract.DockEntry) {
    val comingSoon = entry.comingSoonMessage
    val page = entry.page
    when {
      comingSoon != null -> parentEventEmitter(MediaSendFlowEvent.ShowToast(ToastEvent(SignalIcons.Info, ToastMessage.Text(comingSoon))))
      page == AttachmentPage.FILES -> Unit
      page != null -> parentEventEmitter(MediaSendFlowEvent.OpenAttachmentPage(page))
      else -> parentEventEmitter(MediaSendFlowEvent.AttachmentDockEntrySelected(entry.id))
    }
  }

  private fun toast(message: Int) {
    parentEventEmitter(MediaSendFlowEvent.ShowToast(ToastEvent(SignalIcons.Info, ToastMessage.Text(message))))
  }

  class Factory(
    private val parentState: StateFlow<MediaSendFlowState>,
    private val parentEventEmitter: (MediaSendFlowEvent) -> Unit
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return AttachmentFilesViewModel(parentState, parentEventEmitter) as T
    }
  }
}
