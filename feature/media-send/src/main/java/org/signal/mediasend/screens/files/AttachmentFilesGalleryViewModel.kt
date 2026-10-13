/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.util.logging.Log
import org.signal.mediasend.MediaSendDependencies
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendRepository
import org.signal.mediasend.R
import org.signal.mediasend.TellomiHeic
import org.signal.mediasend.ToastEvent
import org.signal.mediasend.ToastMessage
import org.signal.mediasend.recentsFolder

/**
 * Tellomi（tellomi/tellomi#1263 F-3，`attachment-files-location.md`）：「文件」页「从相册中选择」——全屏相册网格的文件模式。
 *
 * 方向照 Telegram 的文件模式相册（Android `PhotoPickerActivity.setDocumentsPicker`、iOS `MediaPickerScreen` asFile →
 * `TGMediaAssetsControllerSendFileIntent`，只读机制）：可以换相册、勾选（带序号，最多 [AttachmentFilesGalleryState.MAX_SELECTION]，
 * 同 Telegram iOS 的 100），底栏「取消 / 发送（N）」。和 Telegram 不同：点照片本身也是勾选，不开预览（Telegram 两端会开）。
 * 勾的里有 HEIC（这台手机也转得了）先问要不要转成 JPEG：同 Telegram iOS 文件模式（`asFile && hasHeic`），也同「···」以文件发送。发出去的是相册里的原文件、原文件名（ADR-0068 `asFile`），一个一条——和「文件」页别的入口走同一条路
 * （[MediaSendFlowActivityContract.AttachmentFilesResult.galleryUris] 带回会话页）。
 */
internal class AttachmentFilesGalleryViewModel(
  private val parentEventEmitter: (MediaSendFlowEvent) -> Unit,
  private val repository: MediaSendRepository = MediaSendDependencies.mediaSendRepository,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : EventDrivenViewModel<AttachmentFilesGalleryEvent>(TAG) {

  companion object {
    private val TAG = Log.tag(AttachmentFilesGalleryViewModel::class)
  }

  private val _state = MutableStateFlow(AttachmentFilesGalleryState())
  val state: StateFlow<AttachmentFilesGalleryState> = _state.asStateFlow()

  init {
    load(recentsFolder())
  }

  override suspend fun processEvent(event: AttachmentFilesGalleryEvent) {
    when (event) {
      is AttachmentFilesGalleryEvent.SwitchFolder -> if (event.folder.bucketId != _state.value.folder.bucketId) load(event.folder)
      is AttachmentFilesGalleryEvent.Toggle -> toggle(event.media)
      AttachmentFilesGalleryEvent.Cancel -> parentEventEmitter(MediaSendFlowEvent.CloseFilesGallery)
      AttachmentFilesGalleryEvent.Send -> if (TellomiHeic.canConvert(_state.value.selected)) {
        _state.update { it.copy(isAskingAboutHeic = true) }
      } else {
        send(convertHeicToJpeg = false)
      }
      is AttachmentFilesGalleryEvent.HeicChosen -> {
        _state.update { it.copy(isAskingAboutHeic = false) }
        send(convertHeicToJpeg = event.convertToJpeg)
      }
      AttachmentFilesGalleryEvent.HeicDismissed -> _state.update { it.copy(isAskingAboutHeic = false) }
    }
  }

  private fun load(folder: MediaFolder) {
    _state.update { it.copy(folder = folder, items = null) }
    viewModelScope.launch {
      val (folders, items) = withContext(ioDispatcher) { repository.getFolders() to repository.getMedia(folder.bucketId) }
      _state.update { current ->
        // 换了相册又换回来时，只认最后一次。
        if (current.folder.bucketId == folder.bucketId) current.copy(folders = folders, items = items) else current
      }
    }
  }

  /** 勾的顺序就是发出去的顺序；再点一次取消；满了提示一句，不再加。 */
  private fun toggle(media: Media) {
    val selected = _state.value.selected
    if (selected.any { it.uri == media.uri }) {
      _state.update { it.copy(selected = selected.filterNot { item -> item.uri == media.uri }) }
      return
    }
    if (selected.size >= AttachmentFilesGalleryState.MAX_SELECTION) {
      parentEventEmitter(
        MediaSendFlowEvent.ShowToast(ToastEvent(SignalIcons.ErrorCircle, ToastMessage.Quantity(R.plurals.MediaSelectScreen__at_most_n_items, AttachmentFilesGalleryState.MAX_SELECTION)))
      )
      return
    }
    _state.update { it.copy(selected = selected + media) }
  }

  private fun send(convertHeicToJpeg: Boolean) {
    val selected = _state.value.selected
    if (selected.isEmpty()) {
      return
    }
    parentEventEmitter(
      MediaSendFlowEvent.SendAttachmentFiles(
        MediaSendFlowActivityContract.AttachmentFilesResult(
          recentAttachmentIds = emptyList(),
          pickedUris = emptyList(),
          caption = null,
          galleryUris = selected.map { it.uri },
          convertHeicToJpeg = convertHeicToJpeg
        )
      )
    )
  }
}

/** Tellomi（#1263 F-3）：「从相册中选择」网格。[items] 为 null 时还在读。 */
internal data class AttachmentFilesGalleryState(
  val folders: List<MediaFolder> = emptyList(),
  val folder: MediaFolder = recentsFolder(),
  val items: List<Media>? = null,
  val selected: List<Media> = emptyList(),
  /** 点了发送、勾的里有 HEIC：等用户选保留 HEIC 还是转成 JPEG。 */
  val isAskingAboutHeic: Boolean = false
) {
  companion object {
    /** F-3：最多选 100（Telegram 文件模式的上限；一个文件一条消息，碰不到一条消息 32 个附件的限制）。 */
    const val MAX_SELECTION = 100
  }

  fun selectionIndex(media: Media): Int = selected.indexOfFirst { it.uri == media.uri }
}

internal sealed interface AttachmentFilesGalleryEvent {
  data class SwitchFolder(val folder: MediaFolder) : AttachmentFilesGalleryEvent
  data class Toggle(val media: Media) : AttachmentFilesGalleryEvent

  /** 左上返回、底栏「取消」、系统返回：回到「文件」页，已选的不要了。 */
  data object Cancel : AttachmentFilesGalleryEvent
  data object Send : AttachmentFilesGalleryEvent

  /** 「是否把照片转成 JPEG？」选了保留 HEIC（false）或转成 JPEG（true）：接着发。 */
  data class HeicChosen(val convertToJpeg: Boolean) : AttachmentFilesGalleryEvent

  /** 点了对话框外面：这次不发，留在网格里。 */
  data object HeicDismissed : AttachmentFilesGalleryEvent
}
