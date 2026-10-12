/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import org.signal.camera.CameraDependencies
import org.signal.core.models.media.Media
import org.signal.core.models.parcelers.NullableCharSequenceParceler
import org.signal.core.util.ContentTypeUtil
import org.signal.mediasend.screens.edit.image.BrushWidths
import org.thoughtcrime.securesms.video.TranscodingConfig
import kotlin.time.Duration

/**
 * The collective state of the media send flow.
 *
 * Fully [Parcelable] for [SavedStateHandle] persistence — no separate serialization needed.
 */
@Parcelize
data class MediaSendFlowState(
  val isCameraFirst: Boolean = false,
  /**
   * Optional recipient identifier for single-recipient flows.
   */
  val recipientId: MediaRecipientId? = null,
  /**
   * Mode of operation — determines whether we return a result or send immediately.
   */
  val mode: MediaSendFlowActivityContract.Mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
  val selectedMedia: List<Media> = emptyList(),
  /**
   * The currently focused/visible media item in the pager.
   */
  val focusedMedia: Media? = null,
  val isMeteredConnection: Boolean = false,
  val isPreUploadEnabled: Boolean = false,
  val sentMediaQuality: @WriteWith<SentMediaQualityParceler> SentMediaQuality = MediaSendDependencies.mediaSendRepository.sentMediaQuality,
  /**
   * Per-media editor state keyed by URI (video trim data, image editor data, etc.).
   */
  val editorStateMap: Map<Uri, EditorState> = emptyMap(),
  /**
   * View-once toggle state. Cycles: OFF -> ONCE -> OFF.
   */
  val viewOnceToggleState: ViewOnceToggleState = ViewOnceToggleState.OFF,
  /**
   * Optional message/caption text to accompany the media. Retains mention and styling spans.
   */
  val message: @WriteWith<NullableCharSequenceParceler> CharSequence? = null,
  /**
   * If non-null, this media was the first capture from the camera and may be
   * removed if the user backs out of camera-first flow.
   */
  val cameraFirstCapture: Media? = null,
  /**
   * Whether touch interactions are enabled (disabled during animations/transitions).
   */
  val isTouchEnabled: Boolean = true,
  /**
   * Whether a send is currently in flight (prevents duplicate sends).
   */
  val isSending: Boolean = false,
  /**
   * Whether the media has been sent (prevents duplicate sends).
   */
  val isSent: Boolean = false,
  /**
   * Whether this is a story send flow.
   */
  val isStory: Boolean = false,
  /**
   * Send type code (SMS vs Signal). Conventionally 0 == SignalMessageSendType.
   */
  val sendType: Int = 0,
  /**
   * Story send requirements based on current media selection.
   */
  val storySendRequirements: StorySendRequirements = StorySendRequirements.CAN_NOT_SEND,
  /**
   * Maximum number of media items that can be selected.
   */
  val maxSelection: Int = 32,
  /**
   * Whether contact selection is required (for choose-after-media flows).
   */
  val isContactSelectionRequired: Boolean = false,
  /**
   * Whether this is a reply to an existing message.
   */
  val isReply: Boolean = false,
  /**
   * Whether this is the "add to group story" flow.
   */
  val isAddToGroupStoryFlow: Boolean = false,
  /**
   * Additional recipients for multi-recipient sends.
   */
  val additionalRecipients: List<MediaSendRecipient> = emptyList(),
  /**
   * Scheduled send time (-1 for immediate).
   */
  val scheduledTime: Long = -1,

  /**
   * Whether media the user picked has just been refused. Read by whichever screen is picking, so a gesture that keeps
   * handing us items can stop on the first refusal, and cleared by it through
   * [MediaSendFlowViewModel.selectionRejectionShown].
   */
  val isSelectionRejected: @WriteWith<TransientInFlightFlagParceler> Boolean = false,

  val mediaConstraints: @WriteWith<TransientMediaConstraintsParceler> MediaConstraints = MediaSendDependencies.mediaSendRepository.getMediaConstraints(),

  val storiesEnabled: Boolean = CameraDependencies.isStoriesFeatureEnabled(),

  val storyMaxVideoDuration: Duration = MediaSendDependencies.mediaSendRepository.storyMaxVideoDuration,

  /**
   * The transcoding tiers that apply at [sentMediaQuality], which an estimate of a video's upload size is derived from.
   * Re-read whenever the quality changes.
   */
  val videoTranscodingTiers: @WriteWith<TransientVideoTranscodingTiersParceler> List<TranscodingConfig.QualityTier> =
    MediaSendDependencies.mediaSendRepository.getVideoTranscodingTiers(sentMediaQuality),

  /**
   * The image editor's per-tool brush widths. Seeded from storage and written back as the user adjusts them.
   */
  val brushWidths: BrushWidths = MediaSendDependencies.mediaSendRepository.brushWidths,

  /**
   * Whether the control for stripping a video's audio track is available.
   */
  val isMuteVideoAudioEnabled: Boolean = MediaConstraints.isMuteVideoAudioAvailable(),

  /** Tellomi（tellomi/tellomi#1115）：从「+」打开的附件 Sheet；null 是整屏的选图流程，同上游。 */
  val attachmentSheet: MediaSendFlowActivityContract.AttachmentSheet? = null,

  /** Tellomi（tellomi/tellomi#1121 F-1）：附件 Sheet 现在显示哪一页（dock 的「相册」/「文件」）。 */
  val attachmentPage: MediaSendFlowActivityContract.AttachmentPage = MediaSendFlowActivityContract.AttachmentPage.GALLERY,

  /** Tellomi（tellomi/tellomi#1261 V-1）：相册查看器里可左右滑的整个相册（和网格同序）；不随进程回收保存。 */
  @IgnoredOnParcel val album: List<Media> = emptyList(),

  /** Tellomi（tellomi/tellomi#1261 V-1）：相册查看器停在哪一张，和它是否选中无关。 */
  @IgnoredOnParcel val albumFocusedMedia: Media? = null,

  /** Tellomi（tellomi/tellomi#1261 V-1）：编辑页位置上显示的是相册查看器（true）还是已选胶片（false）。 */
  @IgnoredOnParcel val isBrowsingAlbum: Boolean = false
) : Parcelable {

  /**
   * View-once only makes sense for a single, non-document attachment that isn't headed for a story.
   */
  val isViewOnceAvailable: Boolean
    get() = selectedMedia.size == 1 && !isStory && !ContentTypeUtil.isDocumentType(focusedMedia?.contentType)

  /**
   * Whether the current selection will actually be sent as view-once. [viewOnceToggleState] is sticky, so it can
   * outlive the conditions that allowed it to be set, and must always be read alongside [isViewOnceAvailable].
   */
  val isViewOnceEnabled: Boolean
    get() = isViewOnceAvailable && viewOnceToggleState == ViewOnceToggleState.ONCE

  /**
   * No-op parceler for flags tracking work that cannot outlive the process that started it.
   */
  private object TransientInFlightFlagParceler : Parceler<Boolean> {
    override fun create(parcel: Parcel): Boolean = false
    override fun Boolean.write(parcel: Parcel, flags: Int) = Unit
  }

  private object TransientMediaConstraintsParceler : Parceler<MediaConstraints> {
    override fun create(parcel: Parcel): MediaConstraints = MediaSendDependencies.mediaSendRepository.getMediaConstraints()
    override fun MediaConstraints.write(parcel: Parcel, flags: Int) = Unit
  }

  /**
   * Tellomi（tellomi/tellomi#1261，需求 D9）：这次选的画质只属于这一次发送、不写回设置，所以跟着状态一起存，进程被回收后也照样恢复。
   * 默认值（新开的一次）仍取设置里的「发送媒体质量」。
   */
  private object SentMediaQualityParceler : Parceler<SentMediaQuality> {
    override fun create(parcel: Parcel): SentMediaQuality = SentMediaQuality.fromCode(parcel.readInt())
    override fun SentMediaQuality.write(parcel: Parcel, flags: Int) = parcel.writeInt(code)
  }

  /**
   * Derived from the quality, so it is re-read rather than saved. Tellomi（#1261）：恢复后由 MediaSendFlowViewModel 按恢复出来的画质重算。
   */
  private object TransientVideoTranscodingTiersParceler : Parceler<List<TranscodingConfig.QualityTier>> {
    override fun create(parcel: Parcel): List<TranscodingConfig.QualityTier> {
      return MediaSendDependencies.mediaSendRepository.getVideoTranscodingTiers(MediaSendDependencies.mediaSendRepository.sentMediaQuality)
    }

    override fun List<TranscodingConfig.QualityTier>.write(parcel: Parcel, flags: Int) = Unit
  }

  enum class ViewOnceToggleState(val code: Int) {
    OFF(0),
    ONCE(1);

    fun next(): ViewOnceToggleState = when (this) {
      OFF -> ONCE
      ONCE -> OFF
    }

    companion object {
      fun fromCode(code: Int): ViewOnceToggleState = entries.firstOrNull { it.code == code } ?: OFF
    }
  }
}
