/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.os.Bundle
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.mediasend.screens.edit.ScheduleSendOption
import org.signal.mediasend.screens.edit.image.BrushTool
import org.signal.mediasend.screens.edit.video.VideoTrimData
import kotlin.time.Duration

/**
 * Changes to the flow itself, raised by the screens within it. A screen owns what only it renders; the selection, the
 * back stack and the snackbar belong to the flow, so a screen asks for those through [MediaSendFlowViewModel.onEvent].
 */
internal sealed interface MediaSendFlowEvent {
  data class AddMedia(val media: Set<Media>) : MediaSendFlowEvent
  data class RemoveMedia(val media: Set<Media>) : MediaSendFlowEvent
  data class SetFocusedMedia(val media: Media) : MediaSendFlowEvent
  data class ReorderSelectedMedia(val fromIndex: Int, val toIndex: Int) : MediaSendFlowEvent
  data class ShowSnackbar(val snackbar: SnackbarEvent) : MediaSendFlowEvent

  /** Whoever was mid-gesture has stopped, so [MediaSendFlowState.isSelectionRejected] has served its purpose. */
  data object SelectionRejectionShown : MediaSendFlowEvent

  /**
   * Media the camera captured and wrote out, which joins the selection and takes the user on to the editor.
   *
   * @param recordingDuration How long the recording ran, or null for a capture that was not recorded.
   */
  data class MediaCaptured(val media: Media, val recordingDuration: Duration? = null) : MediaSendFlowEvent

  /** Data read from a QR code. Every outcome of acting on it leaves the flow, so none of it is a screen's to handle. */
  data class QrCodeScanned(val data: String) : MediaSendFlowEvent

  /** The user asked to leave, which is confirmed first if it would throw a selection away. */
  data object CloseRequested : MediaSendFlowEvent

  //region Edits, which the flow holds so that they survive the editor being swiped away

  data class SetMediaQuality(val quality: SentMediaQuality) : MediaSendFlowEvent

  /** Tellomi (tellomi/tellomi#1518 V-7 ②): the focused video's own quality level, as a short edge; 0 = the batch quality. */
  data class SetVideoLevel(val shortEdge: Int) : MediaSendFlowEvent
  data class SetBrushWidth(val tool: BrushTool, val fraction: Float) : MediaSendFlowEvent
  data class SetBlurFacesEnabled(val enabled: Boolean) : MediaSendFlowEvent
  data class VideoTrimChanged(val videoTrimData: VideoTrimData, val editingComplete: Boolean) : MediaSendFlowEvent
  data object ToggleViewOnce : MediaSendFlowEvent
  data object ToggleVideoMuted : MediaSendFlowEvent

  //endregion

  //region Requests the flow can only answer by leaving it, through a [MediaSendFlowHudCommand]

  data class AddMessageRequested(val startWithEmojiKeyboard: Boolean) : MediaSendFlowEvent
  data class ScheduleSendRequested(val option: ScheduleSendOption) : MediaSendFlowEvent
  data object StickerRequested : MediaSendFlowEvent

  //endregion

  /** The user is done editing: on to choosing a destination, or straight into the send. */
  data object NextRequested : MediaSendFlowEvent

  data class NavigateToFiles(val mediaFolder: MediaFolder) : MediaSendFlowEvent

  //region Tellomi（tellomi/tellomi#1261 选图面板）

  /** V-1：点已选胶片里的一张，在编辑器里打开它（裁剪、涂鸦、一次性查看等沿用上游）。只对已选中的照片有效。 */
  data class OpenInEditor(val media: Media) : MediaSendFlowEvent

  /** V-1：网格里点一张，进相册查看器——可左右滑看整个相册，不改变选中。 */
  data class OpenAlbumViewer(val media: Media, val album: List<Media>) : MediaSendFlowEvent

  /** V-1：相册查看器滑到了另一张。 */
  data class SetAlbumFocusedMedia(val media: Media) : MediaSendFlowEvent

  /** V-1：相册查看器右上角的勾：选上或取消这一张。 */
  data class ToggleAlbumSelected(val media: Media) : MediaSendFlowEvent

  /** V-1：相册查看器里点选中计数，切到已选胶片。 */
  data object ShowSelectedFilmstrip : MediaSendFlowEvent

  /** V-1（D11 / D13）：查看器底部「裁剪」「绘制」：没选就先选上，在编辑器里打开这一张并直接进入对应工具。 */
  data class EditFromAlbum(val media: Media, val tool: AlbumEditTool) : MediaSendFlowEvent

  /** V-2：相册查看器下滑关闭，回到网格（和系统返回一样）。 */
  data object CloseAlbumViewer : MediaSendFlowEvent

  /** P-1：顶栏「最近 ⌄」里换相册。 */
  data class SwitchFolder(val mediaFolder: MediaFolder) : MediaSendFlowEvent

  /** Tellomi（#1263，ADR-0068 §4.7）：「···」→ 说明文字上移 / 下移。 */
  data object ToggleCaptionAboveMedia : MediaSendFlowEvent

  /**
   * P-5：「···」里的立即发送。[quality] 非空时这一次用它发（D9：不写设置）；[separately] 时一张一条、说明挂最后一条。
   * [asFile]（tellomi/tellomi#1263，ADR-0068）时按文件发原图，也是一个一条；[convertHeicToJpeg] 时其中的 HEIC 先转成 JPEG。
   */
  data class SendNow(
    val quality: SentMediaQuality? = null,
    val separately: Boolean = false,
    val asFile: Boolean = false,
    val convertHeicToJpeg: Boolean = false
  ) : MediaSendFlowEvent

  //endregion

  //region Tellomi（tellomi/tellomi#1115 附件 Sheet）
  /** dock 里点了别的格子（文件、位置……）：离开流程，交给打开 Sheet 的会话页。 */
  data class AttachmentDockEntrySelected(val id: String) : MediaSendFlowEvent

  /** 置灰的 dock 格子只提示一句，Sheet 留着。 */
  data class ShowToast(val toast: ToastEvent) : MediaSendFlowEvent

  /** tellomi/tellomi#1121 F-1：dock 的「相册」「文件」在同一个 Sheet 里换页。 */
  data class OpenAttachmentPage(val page: MediaSendFlowActivityContract.AttachmentPage) : MediaSendFlowEvent

  /** tellomi/tellomi#1121：「文件」页选好了要发的文件：离开流程，交给会话页去发。 */
  data class SendAttachmentFiles(val result: MediaSendFlowActivityContract.AttachmentFilesResult) : MediaSendFlowEvent

  /** Tellomi（2026-10-16）：app 画的页（投票……）做完了，[result] 交给会话页（内容由那一页定）。 */
  data class AttachmentPageResult(val page: MediaSendFlowActivityContract.AttachmentPage, val result: Bundle) : MediaSendFlowEvent

  /**
   * tellomi/tellomi#1263 F-3：「文件」页「从相册中选择」：关掉附件 Sheet，整屏推出相册网格（文件模式）——照 Telegram 两端
   * （iOS `attachmentController.dismiss` 后 `presentFileGallery`，安卓 `parentAlert.dismiss` 后推 `PhotoPickerActivity`）。
   */
  data object OpenFilesGallery : MediaSendFlowEvent

  /** tellomi/tellomi#1263 F-3：相册网格里取消 / 返回：Sheet 已经关了，直接回聊天。 */
  data object CloseFilesGallery : MediaSendFlowEvent

  //endregion
  data object NavigateToFolders : MediaSendFlowEvent
  data object NavigateToEdit : MediaSendFlowEvent
  data object NavigateToCamera : MediaSendFlowEvent

  /** Tellomi（tellomi/tellomi#1520 C-7）：编辑页里「再拍」：拍下的都留着，回到相机接着拍（照 Telegram 查看器的「添加照片」）。 */
  data object KeepShooting : MediaSendFlowEvent
  data object NavigateToTextStory : MediaSendFlowEvent

  // Backing out lands somewhere different depending on the screen doing it, so each says which it is.
  data object NavigateBackFromSelect : MediaSendFlowEvent
  data object NavigateBackFromEdit : MediaSendFlowEvent
}

/** 相册查看器底部快捷直接打开的编辑工具（照 Telegram 查看器底栏的 Crop / Paint）。 */
enum class AlbumEditTool {
  CROP,
  DRAW
}
