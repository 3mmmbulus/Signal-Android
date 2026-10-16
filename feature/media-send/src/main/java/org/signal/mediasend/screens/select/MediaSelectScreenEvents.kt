/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.ScheduleSendOption

sealed interface MediaSelectScreenEvents {

  /** The parent flow's state changed and needs to be merged into this screen's state. */
  data class ParentStateChanged(val parentState: MediaSendFlowState) : MediaSelectScreenEvents {
    // The parent's state carries the message the user is typing and every item they have picked. Only the parts this
    // screen reads are worth logging, and they are the only parts safe to.
    override fun toString(): String = "ParentStateChanged(selectedMedia=${parentState.selectedMedia.size}, isSelectionRejected=${parentState.isSelectionRejected})"
  }

  /** The screen has stopped what the refusal was meant to stop, so the flow can drop it. */
  data object SelectionRejectionShown : MediaSelectScreenEvents

  data class FolderClick(val mediaFolder: MediaFolder?) : MediaSelectScreenEvents
  data class MediaClick(val media: Media) : MediaSelectScreenEvents

  /** A run of media covered by a drag across the grid. Batched, since a drag can cross many tiles in a single frame. */
  data class MediaSelected(val media: Set<Media>) : MediaSelectScreenEvents

  /** A run of media that a drag has retracted back over, undoing its own selection. */
  data class MediaUnselected(val media: Set<Media>) : MediaSelectScreenEvents

  data class SetFocusedMedia(val media: Media) : MediaSelectScreenEvents
  data class ReorderSelectedMedia(val fromIndex: Int, val toIndex: Int) : MediaSelectScreenEvents
  data object NavigateToEdit : MediaSelectScreenEvents
  data object NavigateToCamera : MediaSelectScreenEvents
  data object NavigateBack : MediaSelectScreenEvents

  /** Re-read the gallery and the current permission level, e.g. after coming back from app settings. */
  data object Refresh : MediaSelectScreenEvents

  /** The up-front "Allow access" ask, made when we cannot read anything at all. */
  data object RequestMediaPermissions : MediaSelectScreenEvents

  /** Re-ask while holding selected-photos access, so the user can widen what we can see. */
  data object SelectMorePhotos : MediaSelectScreenEvents

  //region Tellomi（tellomi/tellomi#1261 选图面板）

  /** V-1：点网格里照片本身：进相册查看器，可左右滑看整个相册，不改变选中。点勾仍是 [MediaClick]（选上 / 取消）。 */
  data class OpenMedia(val media: Media) : MediaSelectScreenEvents

  /** V-1：点底部已选胶片里的一张：在编辑器里打开它。 */
  data class OpenSelectedMedia(val media: Media) : MediaSelectScreenEvents

  /** P-1：顶栏「最近 ⌄」里换相册。 */
  data class SwitchFolder(val mediaFolder: MediaFolder) : MediaSelectScreenEvents

  /** P-9：底栏「添加说明…」与表情键。 */
  data class AddMessage(val startWithEmojiKeyboard: Boolean) : MediaSelectScreenEvents

  /** 两端对齐清单 P-4：附件 Sheet 里底栏的说明框就地输入，字改了。 */
  data class MessageChanged(val message: CharSequence?) : MediaSelectScreenEvents

  data object ToggleViewOnce : MediaSelectScreenEvents

  /** P-9 / P-10：底栏的发送键——在网格里直接发，不经预览页。 */
  data object Send : MediaSelectScreenEvents

  /** Tellomi（owner 2026-09-28 D4）：网格发送键长按选了定时发送的时间。 */
  data class ScheduleSend(val option: ScheduleSendOption) : MediaSelectScreenEvents

  /** P-5：「···」→ 以高清 / 标准质量发送（D9：只管这一次）。 */
  data class SendWithQuality(val quality: SentMediaQuality) : MediaSelectScreenEvents

  /** P-5：「···」→ 单独发送。 */
  data object SendSeparately : MediaSelectScreenEvents

  /**
   * P-5（tellomi/tellomi#1263，ADR-0068）：「···」→ 以文件发送。已选里有 HEIC 时先问过了：[convertHeicToJpeg] 是「转成 JPEG」。
   */
  data class SendAsFile(val convertHeicToJpeg: Boolean) : MediaSelectScreenEvents

  /** P-5 / M-2（#1263，ADR-0068 §4.7）：「···」→ 说明文字上移 / 下移（开关，不发送）。 */
  data object ToggleCaptionAboveMedia : MediaSelectScreenEvents

  /** P-1：左上角 ✕。 */
  data object Close : MediaSelectScreenEvents

  //endregion

  /** Tellomi（tellomi/tellomi#1115）：附件 Sheet 的 dock 里点了一格。 */
  data class DockEntryClicked(val entry: MediaSendFlowActivityContract.DockEntry) : MediaSelectScreenEvents
}
