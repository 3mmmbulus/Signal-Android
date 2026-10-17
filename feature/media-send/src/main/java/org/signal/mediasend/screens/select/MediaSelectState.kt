/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.mediasend.MediaRecipientId
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.SentMediaQuality

sealed interface MediaSelectState {

  /** The flow's current selection, as last reported by the parent. */
  val selectedMedia: List<Media>

  /**
   * The single recipient this media is headed to, or null when the destination is still to be chosen. Fixed for the
   * life of the flow, so it is read once at construction rather than mirrored from the parent.
   */
  val recipientId: MediaRecipientId?

  /** How much of the device's media we are allowed to read. */
  val mediaPermissions: MediaPermissions

  /** Whether media just handed to the flow was refused, as last reported by the parent. */
  val isSelectionRejected: Boolean

  /** Whether the media store actually gave us anything to render for this screen. */
  val hasContent: Boolean

  data class Folders(
    val mediaFolders: List<MediaFolder>,
    override val selectedMedia: List<Media>,
    override val recipientId: MediaRecipientId? = null,
    override val mediaPermissions: MediaPermissions = MediaPermissions.FULL,
    override val isSelectionRejected: Boolean = false
  ) : MediaSelectState {
    override val hasContent: Boolean
      get() = mediaFolders.isNotEmpty()
  }

  data class Files(
    val selectedMediaFolder: MediaFolder,
    val selectedMediaFolderItems: List<Media>,
    override val selectedMedia: List<Media>,
    override val recipientId: MediaRecipientId? = null,
    override val mediaPermissions: MediaPermissions = MediaPermissions.FULL,
    override val isSelectionRejected: Boolean = false,
    /** Tellomi（tellomi/tellomi#1261 P-1）：顶栏「最近 ⌄」里能换的相册。 */
    val mediaFolders: List<MediaFolder> = emptyList(),
    /** Tellomi（#1261 P-5、P-9）：底栏（说明 + 发送）与「···」菜单读的父流程状态。 */
    val sendOptions: SendOptions = SendOptions(),
    /** Tellomi（#1261 P-8）：「最近」第一格的相机格。 */
    val cameraAccess: PickerCameraAccess = PickerCameraAccess.NONE,
    /** Tellomi（tellomi/tellomi#1115）：附件 Sheet 底部 dock 的格子；空 = 不是从「+」打开的，没有 dock。 */
    val attachmentDock: List<MediaSendFlowActivityContract.DockEntry> = emptyList()
  ) : MediaSelectState {
    override val hasContent: Boolean
      get() = selectedMediaFolderItems.isNotEmpty()

    /** 相机格只在「最近」（全部媒体）里、而且有相机时才有。 */
    val showsPickerCamera: Boolean
      get() = cameraAccess != PickerCameraAccess.NONE && selectedMediaFolder.bucketId == Media.ALL_MEDIA_BUCKET_ID
  }

  fun withParentState(selectedMedia: List<Media>, isSelectionRejected: Boolean): MediaSelectState = when (this) {
    is Folders -> copy(selectedMedia = selectedMedia, isSelectionRejected = isSelectionRejected)
    is Files -> copy(selectedMedia = selectedMedia, isSelectionRejected = isSelectionRejected)
  }

  fun withMediaPermissions(mediaPermissions: MediaPermissions): MediaSelectState = when (this) {
    is Folders -> copy(mediaPermissions = mediaPermissions)
    is Files -> copy(mediaPermissions = mediaPermissions)
  }

  /** Tellomi（#1261 P-8）：只有网格页用得着；相册列表页原样。 */
  fun withCameraAccess(cameraAccess: PickerCameraAccess): MediaSelectState = when (this) {
    is Folders -> this
    is Files -> copy(cameraAccess = cameraAccess)
  }

  /** Tellomi（#1261）：只有网格页用得着；相册列表页原样。 */
  fun withSendOptions(sendOptions: SendOptions): MediaSelectState = when (this) {
    is Folders -> this
    is Files -> copy(sendOptions = sendOptions)
  }

  /**
   * Tellomi（tellomi/tellomi#1261）：选图网格底栏与「···」要的发送参数，随父流程更新。
   *
   * @param canSendSeparately 「单独发送」只给已知的单个会话（拆成几条消息由会话页来发），故事、要先选联系人的流程都没有。
   * @param canSendAsFile 「以文件发送」（tellomi/tellomi#1263）同上：也是会话页一个一条地发；故事不能按文件发（ADR-0068 §4.4）。
   * @param canMoveCaption 「说明文字上移 / 下移」（#1263，ADR-0068 §4.7）同上：标记由会话页带进消息。
   * @param isCaptionAboveMedia 现在说明在不在上方（菜单里显示「下移」还是「上移」）。
   */
  data class SendOptions(
    val message: CharSequence? = null,
    val sentMediaQuality: SentMediaQuality = SentMediaQuality.STANDARD,
    val isViewOnceAvailable: Boolean = false,
    val isViewOnceEnabled: Boolean = false,
    val isSending: Boolean = false,
    val isReply: Boolean = false,
    val canScheduleSend: Boolean = false,
    val canSendSeparately: Boolean = false,
    val canSendAsFile: Boolean = false,
    val canMoveCaption: Boolean = false,
    val isCaptionAboveMedia: Boolean = false,
    /** Tellomi（#1263 P-5 / P-6）：「罩住图片」：会话里才有（同「以文件发送」）；罩住的已选。 */
    val canSpoiler: Boolean = false,
    val spoileredUris: Set<android.net.Uri> = emptySet(),
    /**
     * Tellomi（#1263 P-4）：实况：会话里才有（同「以文件发送」）。[hasSelectedLive] = 已选里有动态照片（顶栏开关才出现）；
     * [isLiveToggleOn] = 已选的全开着；[liveOnUris] = 开着的已选；[liveDefault] = 没选的动态照片格子的图标跟着它。
     */
    val canLive: Boolean = false,
    val hasSelectedLive: Boolean = false,
    val isLiveToggleOn: Boolean = false,
    val liveOnUris: Set<android.net.Uri> = emptySet(),
    val liveDefault: Boolean = false
  ) {
    companion object {
      fun from(parent: MediaSendFlowState): SendOptions {
        val sentByConversation = parent.isSentByConversation

        return SendOptions(
          message = parent.message,
          sentMediaQuality = parent.sentMediaQuality,
          isViewOnceAvailable = parent.isViewOnceAvailable,
          isViewOnceEnabled = parent.isViewOnceEnabled,
          isSending = parent.isSending,
          isReply = parent.isReply,
          canScheduleSend = !parent.isStory,
          canSendSeparately = sentByConversation,
          canSendAsFile = sentByConversation,
          canMoveCaption = sentByConversation,
          isCaptionAboveMedia = parent.isCaptionAboveMedia,
          canSpoiler = sentByConversation,
          spoileredUris = parent.selectedSpoileredUris,
          canLive = sentByConversation,
          hasSelectedLive = sentByConversation && parent.selectedLiveUris.isNotEmpty(),
          isLiveToggleOn = parent.isTellomiLiveToggleOn,
          liveOnUris = parent.selectedLiveVideos.keys,
          liveDefault = parent.tellomiLiveDefault
        )
      }
    }
  }
}
