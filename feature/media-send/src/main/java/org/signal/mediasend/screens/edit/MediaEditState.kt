/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.net.Uri
import org.signal.core.models.media.Media
import org.signal.mediasend.EditorState
import org.signal.mediasend.MediaRecipientId
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.video.VideoTrimData
import org.thoughtcrime.securesms.video.TellomiVideoQuality
import org.thoughtcrime.securesms.video.TranscodingConfig

/**
 * What the edit screen renders. Everything it edits belongs to the flow, so all of it is a copy kept current through
 * [MediaEditScreenEvents.ParentStateChanged] -- apart from [isSavingMedia], which is this screen's own work.
 */
internal data class MediaEditState(
  val selectedMedia: List<Media> = emptyList(),
  val focusedMedia: Media? = null,
  val editorStateMap: Map<Uri, EditorState> = emptyMap(),
  /** The camera-first capture, which backing out of the editor discards rather than keeps. */
  val cameraFirstCapture: Media? = null,
  /** The single recipient this media is headed to, or null when the destination is still to be chosen. */
  val recipientId: MediaRecipientId? = null,
  val message: CharSequence? = null,
  val sentMediaQuality: SentMediaQuality = SentMediaQuality.STANDARD,
  val videoTranscodingTiers: List<TranscodingConfig.QualityTier> = emptyList(),
  /** Tellomi (tellomi/tellomi#1518 V-7 ②): the upload limit a video's quality level has to fit in. */
  val videoMaxUploadBytes: Long = 0,
  val isStory: Boolean = false,
  val isReply: Boolean = false,
  val isSending: Boolean = false,
  val isTouchEnabled: Boolean = true,
  val isMuteVideoAudioEnabled: Boolean = false,
  val isViewOnceAvailable: Boolean = false,
  val isViewOnceEnabled: Boolean = false,
  /** Whether the focused image is currently being written out to shared storage. */
  val isSavingMedia: Boolean = false,
  /** The album the viewer swipes through, in grid order. Only shown while [isBrowsingAlbum]. */
  val album: List<Media> = emptyList(),
  val albumFocusedMedia: Media? = null,
  /** Whether this screen is the album viewer rather than the editor over the selected media. */
  val isBrowsingAlbum: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1520 C-7）：编辑页是不是直接盖在相机上面（导航的事，由放这一页的地方按返回栈填，不从流程状态来）。
   * 是的话添加键换成「再拍」。
   */
  val isOverCamera: Boolean = false,
  /** Tellomi（#1263，ADR-0068 §4.7）：长按发送里给不给「说明文字上移 / 下移」（[MediaSendFlowState.canMoveCaption]）。 */
  val canMoveCaption: Boolean = false,
  val isCaptionAboveMedia: Boolean = false
) {

  /** 长按发送菜单里说明那一项：null = 不给；true = 现在在上方（显示「下移」）。 */
  val captionAboveMediaOption: Boolean?
    get() = if (canMoveCaption && selectedMedia.isNotEmpty()) isCaptionAboveMedia else null

  val focusedEditorState: EditorState?
    get() = focusedMedia?.uri?.let { editorStateMap[it] }

  /**
   * Whether the selection is nothing but the capture that opened this editor. Backing out then discards it and returns
   * to the camera, rather than leaving an editor with nothing to edit behind.
   */
  val isOnlyCameraFirstCapture: Boolean
    get() = cameraFirstCapture != null && selectedMedia.size == 1 && selectedMedia.firstOrNull() == cameraFirstCapture

  fun getOrCreateVideoTrimData(uri: Uri): VideoTrimData {
    return (editorStateMap[uri] as? EditorState.VideoTrim)?.videoTrimData ?: VideoTrimData()
  }

  /**
   * Tellomi (tellomi/tellomi#1518 V-7 ②): the tiers the focused video will be transcoded with -- its own quality
   * level if one was picked, the batch quality's otherwise. The size hint is derived from these.
   */
  val focusedVideoTranscodingTiers: List<TranscodingConfig.QualityTier>
    get() {
      val trim = (focusedEditorState as? EditorState.VideoTrim)?.videoTrimData
      if (trim?.isMuted == true) {
        // Tellomi (tellomi/tellomi#1518 V-6): a muted video goes as a GIF at 480p.
        val source = focusedMedia?.let { minOf(it.width, it.height) } ?: 0
        return TellomiVideoQuality.mutedTiers(source, videoMaxUploadBytes)
      }
      val shortEdge = trim?.tellomiShortEdge ?: 0
      return if (shortEdge > 0) {
        TellomiVideoQuality.tiersFor(shortEdge, videoMaxUploadBytes) ?: videoTranscodingTiers
      } else {
        videoTranscodingTiers
      }
    }

  /** Everything the flow reports, merged in. [isSavingMedia] is left alone: the flow knows nothing about it. */
  fun withParentState(parentState: MediaSendFlowState): MediaEditState = copy(
    selectedMedia = parentState.selectedMedia,
    focusedMedia = parentState.focusedMedia,
    editorStateMap = parentState.editorStateMap,
    cameraFirstCapture = parentState.cameraFirstCapture,
    recipientId = parentState.recipientId,
    message = parentState.message,
    sentMediaQuality = parentState.sentMediaQuality,
    videoTranscodingTiers = parentState.videoTranscodingTiers,
    videoMaxUploadBytes = parentState.tellomiVideoMaxUploadBytes,
    isStory = parentState.isStory,
    isReply = parentState.isReply,
    isSending = parentState.isSending,
    isTouchEnabled = parentState.isTouchEnabled,
    isMuteVideoAudioEnabled = parentState.isMuteVideoAudioEnabled,
    isViewOnceAvailable = parentState.isViewOnceAvailable,
    isViewOnceEnabled = parentState.isViewOnceEnabled,
    album = parentState.album,
    albumFocusedMedia = parentState.albumFocusedMedia,
    isBrowsingAlbum = parentState.isBrowsingAlbum,
    canMoveCaption = parentState.canMoveCaption,
    isCaptionAboveMedia = parentState.isCaptionAboveMedia
  )
}
