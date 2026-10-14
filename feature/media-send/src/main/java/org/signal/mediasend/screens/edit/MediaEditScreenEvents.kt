/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import org.signal.core.models.media.Media
import org.signal.mediasend.AlbumEditTool
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.image.BrushTool
import org.signal.mediasend.screens.edit.video.VideoTrimData

sealed interface MediaEditScreenEvents {

  /** The parent flow's state changed and needs to be merged into this screen's state. */
  data class ParentStateChanged(val parentState: MediaSendFlowState) : MediaEditScreenEvents {
    // The parent's state carries the message the user is typing and every item they have picked. Only the size of the
    // selection is worth logging, and it is the only part safe to.
    override fun toString(): String = "ParentStateChanged(selectedMedia=${parentState.selectedMedia.size})"
  }

  data class RemoveMedia(val media: Media) : MediaEditScreenEvents
  data class FocusedMediaChanged(val media: Media) : MediaEditScreenEvents
  data class ReorderSelectedMedia(val fromIndex: Int, val toIndex: Int) : MediaEditScreenEvents
  data class AddMessageClick(val startWithEmojiKeyboard: Boolean = false) : MediaEditScreenEvents
  data object StickerClick : MediaEditScreenEvents
  data object NextClick : MediaEditScreenEvents
  data class ScheduleSendClick(val option: ScheduleSendOption) : MediaEditScreenEvents
  data object NavigateBack : MediaEditScreenEvents
  data object NavigateToGallery : MediaEditScreenEvents

  /** Tellomi（tellomi/tellomi#1520 C-7）：编辑页在相机上面时，添加键换成「再拍」：回到相机接着拍，拍下的都留着。 */
  data object TakeMore : MediaEditScreenEvents
  data class SetMediaQuality(val quality: SentMediaQuality) : MediaEditScreenEvents

  /** Tellomi (tellomi/tellomi#1518 V-7 ②): see [org.signal.mediasend.MediaSendFlowEvent.SetVideoLevel]. */
  data class SetVideoLevel(val shortEdge: Int) : MediaEditScreenEvents
  data object ToggleViewOnce : MediaEditScreenEvents

  /** Tellomi（#1263，ADR-0068 §4.7）：长按发送里的「说明文字上移 / 下移」。 */
  data object ToggleCaptionAboveMedia : MediaEditScreenEvents
  data class BrushWidthChanged(val tool: BrushTool, val fraction: Float) : MediaEditScreenEvents
  data class ToggleBlurFaces(val enabled: Boolean) : MediaEditScreenEvents
  data object SaveMedia : MediaEditScreenEvents
  data class VideoTrimChanged(val videoTrimData: VideoTrimData, val editingComplete: Boolean) : MediaEditScreenEvents
  data class VideoSeek(val positionUs: Long, val editingComplete: Boolean) : MediaEditScreenEvents
  data object ToggleVideoMuted : MediaEditScreenEvents

  /** V-1：相册查看器右上角的勾：选上或取消这一张。 */
  data class ToggleAlbumSelected(val media: Media) : MediaEditScreenEvents

  /** V-1：相册查看器滑到了另一张。 */
  data class AlbumFocusedMediaChanged(val media: Media) : MediaEditScreenEvents

  /** V-1：相册查看器里点选中计数，切到已选胶片。 */
  data object ShowSelectedFilmstrip : MediaEditScreenEvents

  /** V-1（D11）：相册查看器底部「裁剪」「绘制」。 */
  data class EditFromAlbum(val media: Media, val tool: AlbumEditTool) : MediaEditScreenEvents

  /** V-2：相册查看器下滑关闭。 */
  data object CloseAlbum : MediaEditScreenEvents
}
