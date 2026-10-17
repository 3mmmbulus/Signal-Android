/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Snackbars
import org.signal.core.util.logging.Log
import org.signal.mediasend.MediaSendDependencies
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.MediaSendRepository
import org.signal.mediasend.R
import org.signal.mediasend.SnackbarEvent
import org.signal.mediasend.ToastEvent
import org.signal.mediasend.ToastMessage

/**
 * Drives the select screen, for one appearance of it: either the list of folders, or the contents of a single folder.
 *
 * The gallery and how much of it we are allowed to read are only of interest to this screen, so they live here. The
 * selection is not -- the rest of the flow sends it -- so it arrives as [MediaSelectScreenEvents.ParentStateChanged] and
 * is rendered from a copy in this screen's own state. Anything this screen wants done to it goes back out as a
 * [MediaSendFlowEvent].
 *
 * @param mediaFolder The folder whose contents to show, or null for the list of folders.
 */
internal class MediaSelectViewModel(
  private val parentState: StateFlow<MediaSendFlowState>,
  private val parentEventEmitter: (MediaSendFlowEvent) -> Unit,
  mediaFolder: MediaFolder?,
  /** Passed straight through: the rail follows it, and nothing about it is this screen's to decide. */
  val selectionAdditions: Flow<Media>,
  private val repository: MediaSendRepository = MediaSendDependencies.mediaSendRepository
) : EventDrivenViewModel<MediaSelectScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(MediaSelectViewModel::class)
  }

  private val _state: MutableStateFlow<MediaSelectState> = MutableStateFlow(
    if (mediaFolder != null) {
      MediaSelectState.Files(
        selectedMediaFolder = mediaFolder,
        selectedMediaFolderItems = emptyList(),
        selectedMedia = emptyList(),
        recipientId = parentState.value.recipientId,
        attachmentDock = parentState.value.attachmentSheet?.dock.orEmpty()
      )
    } else {
      MediaSelectState.Folders(
        mediaFolders = emptyList(),
        selectedMedia = emptyList(),
        recipientId = parentState.value.recipientId
      )
    }
  )

  val state: StateFlow<MediaSelectState> = _state.asStateFlow()

  /** Hosted here, since this is the only screen that asks for the gallery's permissions. */
  val readMediaPermission = MediaPermissionController()

  init {
    parentState
      // Tellomi（#1261）：网格底栏还要跟着说明、画质、一次性查看、是否在发送走。
      .distinctUntilChangedBy { listOf(it.selectedMedia, it.isSelectionRejected, MediaSelectState.SendOptions.from(it).copy(message = it.message?.toString())) }
      .onEach { onEvent(MediaSelectScreenEvents.ParentStateChanged(it)) }
      .launchIn(viewModelScope)

    refresh()
  }

  override suspend fun processEvent(event: MediaSelectScreenEvents) {
    when (event) {
      is MediaSelectScreenEvents.ParentStateChanged -> _state.update {
        it.withParentState(event.parentState.selectedMedia, event.parentState.isSelectionRejected)
          .withSendOptions(MediaSelectState.SendOptions.from(event.parentState))
      }
      MediaSelectScreenEvents.SelectionRejectionShown -> parentEventEmitter(MediaSendFlowEvent.SelectionRejectionShown)
      is MediaSelectScreenEvents.FolderClick -> event.mediaFolder?.let { parentEventEmitter(MediaSendFlowEvent.NavigateToFiles(it)) }
      is MediaSelectScreenEvents.MediaClick -> applyMediaClickEvent(event.media)
      is MediaSelectScreenEvents.MediaSelected -> parentEventEmitter(MediaSendFlowEvent.AddMedia(event.media))
      is MediaSelectScreenEvents.MediaUnselected -> parentEventEmitter(MediaSendFlowEvent.RemoveMedia(event.media))
      is MediaSelectScreenEvents.SetFocusedMedia -> parentEventEmitter(MediaSendFlowEvent.SetFocusedMedia(event.media))
      is MediaSelectScreenEvents.ReorderSelectedMedia -> parentEventEmitter(MediaSendFlowEvent.ReorderSelectedMedia(event.fromIndex, event.toIndex))
      MediaSelectScreenEvents.NavigateToEdit -> parentEventEmitter(MediaSendFlowEvent.NavigateToEdit)
      MediaSelectScreenEvents.NavigateToCamera -> parentEventEmitter(MediaSendFlowEvent.NavigateToCamera)
      MediaSelectScreenEvents.NavigateBack -> parentEventEmitter(MediaSendFlowEvent.NavigateBackFromSelect)
      MediaSelectScreenEvents.Refresh -> refresh()
      MediaSelectScreenEvents.RequestMediaPermissions -> requestReadMediaPermissions(reportDenial = true)
      MediaSelectScreenEvents.SelectMorePhotos -> requestReadMediaPermissions(reportDenial = false)
      is MediaSelectScreenEvents.OpenMedia -> openAlbumViewer(event.media)
      is MediaSelectScreenEvents.OpenSelectedMedia -> parentEventEmitter(MediaSendFlowEvent.OpenInEditor(event.media))
      is MediaSelectScreenEvents.SwitchFolder -> parentEventEmitter(MediaSendFlowEvent.SwitchFolder(event.mediaFolder))
      is MediaSelectScreenEvents.AddMessage -> parentEventEmitter(MediaSendFlowEvent.AddMessageRequested(event.startWithEmojiKeyboard))
      is MediaSelectScreenEvents.MessageChanged -> parentEventEmitter(MediaSendFlowEvent.SetMessage(event.message))
      MediaSelectScreenEvents.ToggleViewOnce -> parentEventEmitter(MediaSendFlowEvent.ToggleViewOnce)
      MediaSelectScreenEvents.Send -> parentEventEmitter(MediaSendFlowEvent.NextRequested)
      is MediaSelectScreenEvents.ScheduleSend -> parentEventEmitter(MediaSendFlowEvent.ScheduleSendRequested(event.option))
      is MediaSelectScreenEvents.SendWithQuality -> parentEventEmitter(MediaSendFlowEvent.SendNow(quality = event.quality))
      MediaSelectScreenEvents.SendSeparately -> parentEventEmitter(MediaSendFlowEvent.SendNow(separately = true))
      is MediaSelectScreenEvents.SendAsFile -> parentEventEmitter(MediaSendFlowEvent.SendNow(asFile = true, convertHeicToJpeg = event.convertHeicToJpeg))
      MediaSelectScreenEvents.ToggleCaptionAboveMedia -> parentEventEmitter(MediaSendFlowEvent.ToggleCaptionAboveMedia)
      MediaSelectScreenEvents.ToggleSpoiler -> parentEventEmitter(MediaSendFlowEvent.ToggleSpoiler)
      MediaSelectScreenEvents.ToggleLive -> parentEventEmitter(MediaSendFlowEvent.ToggleLive)
      MediaSelectScreenEvents.Close -> parentEventEmitter(MediaSendFlowEvent.CloseRequested)
      is MediaSelectScreenEvents.DockEntryClicked -> onDockEntryClicked(event.entry)
    }
  }

  /**
   * Tellomi（tellomi/tellomi#1115、#1121）：置灰的格子只提示一句；Sheet 里的别的页（文件）在同一个 Sheet 里换过去；
   * 别的交给会话页。当前页（相册）那一格由网格自己处理（回到顶部、展开）。
   */
  private fun onDockEntryClicked(entry: MediaSendFlowActivityContract.DockEntry) {
    val comingSoon = entry.comingSoonMessage
    val page = entry.page
    when {
      comingSoon != null -> parentEventEmitter(MediaSendFlowEvent.ShowToast(ToastEvent(SignalIcons.Info, ToastMessage.Text(comingSoon))))
      page == MediaSendFlowActivityContract.AttachmentPage.GALLERY -> Unit
      page != null -> parentEventEmitter(MediaSendFlowEvent.OpenAttachmentPage(page))
      else -> parentEventEmitter(MediaSendFlowEvent.AttachmentDockEntrySelected(entry.id))
    }
  }

  /** Tellomi（tellomi/tellomi#1261 V-1）：相册查看器里的相册就是这个文件夹的全部照片，和网格同序。 */
  private fun openAlbumViewer(media: Media) {
    val album = (_state.value as? MediaSelectState.Files)?.selectedMediaFolderItems.orEmpty()
    parentEventEmitter(MediaSendFlowEvent.OpenAlbumViewer(media, album))
  }

  private fun applyMediaClickEvent(media: Media) {
    if (_state.value.selectedMedia.any { it.uri == media.uri }) {
      parentEventEmitter(MediaSendFlowEvent.RemoveMedia(setOf(media)))
    } else {
      parentEventEmitter(MediaSendFlowEvent.AddMedia(setOf(media)))
    }
  }

  /**
   * Re-reads what this screen shows from the media store, along with the level of access we currently have. Widening
   * selected-photos access adds items to a folder without changing which folder it is, so the contents have to be
   * re-read even when it looks like nothing about the folder has changed.
   */
  private fun refresh() {
    viewModelScope.launch {
      val mediaPermissions = MediaPermissions.current()
      val cameraAccess = PickerCameraAccess.current()

      val reloaded: MediaSelectState = when (val snapshot = _state.value) {
        is MediaSelectState.Folders -> snapshot.copy(mediaFolders = repository.getFolders())
        is MediaSelectState.Files -> snapshot.copy(
          selectedMediaFolderItems = repository.getMedia(snapshot.selectedMediaFolder.bucketId),
          // Tellomi（#1261 P-1）：顶栏下拉里的相册。
          mediaFolders = repository.getFolders()
        )
      }

      // Only what the parent reports can have changed while we were reading, and that is not ours to overwrite.
      _state.update { current ->
        reloaded
          .withMediaPermissions(mediaPermissions)
          .withCameraAccess(cameraAccess)
          .withParentState(current.selectedMedia, current.isSelectionRejected)
          .let { if (current is MediaSelectState.Files) it.withSendOptions(current.sendOptions) else it }
      }
    }
  }

  /**
   * Prompts for the gallery's read permissions and refreshes on any result, granted or not: selected-photos access
   * comes back as a denial of the broad permissions but still changes what we can see.
   */
  private fun requestReadMediaPermissions(reportDenial: Boolean) {
    viewModelScope.launch {
      val denied = readMediaPermission.request(permanentDenialSheet = reportDenial)

      if (reportDenial && denied) {
        parentEventEmitter(
          MediaSendFlowEvent.ShowSnackbar(
            SnackbarEvent(
              message = R.string.MediaSelectScreen__signal_needs_access_to_show_your_photos_and_videos,
              duration = Snackbars.Duration.LONG
            )
          )
        )
      }

      refresh()
    }
  }
}
