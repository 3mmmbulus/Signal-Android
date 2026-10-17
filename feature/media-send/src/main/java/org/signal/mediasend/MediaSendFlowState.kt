/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.graphics.Bitmap
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
  /** Tellomi (tellomi/tellomi#1518 V-7 ②): the upload limit a video's quality level has to fit in. */
  val tellomiVideoMaxUploadBytes: Long = MediaSendDependencies.mediaSendRepository.tellomiVideoMaxUploadBytes,
  /**
   * Per-media editor state keyed by URI (video trim data, image editor data, etc.).
   */
  val editorStateMap: Map<Uri, EditorState> = emptyMap(),
  /**
   * View-once toggle state. Cycles: OFF -> ONCE -> OFF.
   */
  val viewOnceToggleState: ViewOnceToggleState = ViewOnceToggleState.OFF,
  /**
   * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.7）：说明文字画在媒体上方（「···」→「说明文字上移 / 下移」）。只管这一次打开的面板。
   */
  val isCaptionAboveMedia: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1263 spec P-5 / P-6，ADR-0068 §4.4）：「···」→「罩住图片」罩上的已选（按 URI）。取消勾选就拿掉，
   * 之后新勾的不自动罩（同 Telegram 安卓 `addToSelectedPhotos`）。只管这一次打开的面板。
   */
  val tellomiSpoileredUris: Set<Uri> = emptySet(),
  /**
   * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：看过的照片里哪些是动态照片（实况），视频在文件里的哪一段。
   * 勾选的、在查看器里看到的才去读（读文件开头的 XMP）。
   */
  val tellomiLiveRanges: Map<Uri, TellomiMotionPhoto.VideoRange> = emptyMap(),
  /**
   * Tellomi（#1263 P-4）：实况开关。默认关（关 = 按静态图发），只管这一次打开的面板、不记住（Telegram iOS 每次新建；
   * 安卓记进设置，只有一端有，不做）。顶栏开关改它；没逐张改过的实况照片跟着它。
   */
  val tellomiLiveDefault: Boolean = false,
  /** Tellomi（#1263 P-4）：逐张的实况开关：顶栏开关一按，已选的全改成同一个值；查看器里可以单改一张。取消勾选就拿掉。 */
  val tellomiLiveOverrides: Map<Uri, Boolean> = emptyMap(),
  /** Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4 第 3 条）：查看器里能不能设计时（一对一会话）。 */
  val tellomiCanSetViewOnceTimer: Boolean = false,
  /**
   * Tellomi（#1714）：逐张的计时（没有 = 不删除）：[TellomiViewOnceChoice.VIEW_ONCE] 一次性查看，3 / 10 / 30 秒。设了就勾上，
   * 取消勾选就拿掉。只管这一次打开的面板。
   */
  val tellomiViewOnceChoices: Map<Uri, Int> = emptyMap(),
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
   * Tellomi（tellomi/tellomi#1520 C-7）：这次在相机里拍下的，按拍的先后。相机里的胶片条显示其中还选着的
   * （[selectedCameraCaptures]）；已经有拍下的还选着时，再拍就留在相机，不再进编辑页。
   */
  val cameraCaptureUris: List<Uri> = emptyList(),
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
  @IgnoredOnParcel val isBrowsingAlbum: Boolean = false,

  /**
   * Tellomi（两端对齐清单 P-6b）：查看器里改过的那几张，照发出去的样子画的图（查看器原本按 URI 显示原图）。
   * 没改过的不在这里；不随进程回收保存（回来以后查看器重新打开时再画）。
   */
  @IgnoredOnParcel val albumEditPreviews: Map<Uri, Bitmap> = emptyMap()
) : Parcelable {

  /** Tellomi（tellomi/tellomi#1520 C-7）：拍下且还选着的，按选择的顺序；相机里的胶片条显示的就是这些。 */
  val selectedCameraCaptures: List<Media>
    get() = selectedMedia.filter { it.uri in cameraCaptureUris }

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
   * Tellomi（tellomi/tellomi#1263）：发给已知的单个会话、由会话页来发（不是故事、不用先选联系人）。「单独发送」「以文件发送」
   * 「说明文字上移 / 下移」都只在这时候有（拆条 / 文件 / 说明位置标记都由会话页带进消息）。
   */
  val isSentByConversation: Boolean
    get() = recipientId != null && !isStory && !isContactSelectionRequired && mode == MediaSendFlowActivityContract.Mode.SingleRecipient

  /**
   * Tellomi（#1263，ADR-0068 §4.7）：「说明文字上移 / 下移」现在给不给（「···」和长按发送里同一个条件）：写了说明、
   * 由会话页发、不是一次性查看。
   */
  val canMoveCaption: Boolean
    get() = isSentByConversation && !message.isNullOrBlank() && !isViewOnceEnabled

  /** Tellomi（#1263 P-4）：这一张按实况发不发（不是动态照片就是 false）：逐张改过的按它，没改过的跟顶栏开关。 */
  fun isTellomiLiveOn(uri: Uri): Boolean = uri in tellomiLiveRanges && (tellomiLiveOverrides[uri] ?: tellomiLiveDefault)

  /** Tellomi（#1263 P-4）：已选里的动态照片。 */
  val selectedLiveUris: List<Uri>
    get() = selectedMedia.mapNotNull { it.uri.takeIf(tellomiLiveRanges::containsKey) }

  /** Tellomi（#1263 P-4）：顶栏实况开关亮不亮：已选的动态照片全部开着（有一张关着就是关，同 Telegram 安卓 `areLivePhotosEnabled`）。 */
  val isTellomiLiveToggleOn: Boolean
    get() = selectedLiveUris.let { live -> live.isNotEmpty() && live.all(::isTellomiLiveOn) }

  /** Tellomi（#1263 P-4）：要连视频一起发的已选（原 URI → 视频那一段）。 */
  val selectedLiveVideos: Map<Uri, TellomiMotionPhoto.VideoRange>
    get() = selectedMedia.mapNotNull { media -> tellomiLiveRanges[media.uri]?.takeIf { isTellomiLiveOn(media.uri) }?.let { media.uri to it } }.toMap()

  /** Tellomi（#1714）：已选里设了计时的。 */
  val selectedViewOnceChoices: Map<Uri, Int>
    get() = selectedMedia.mapNotNull { media -> tellomiViewOnceChoices[media.uri]?.let { media.uri to it } }.toMap()

  /** Tellomi（#1714）：已选里有计时的（含一次性查看）：不能按文件发、不能定时发送（ADR-0079 §4.4 第 6、7 条）。 */
  val hasTimedSelection: Boolean
    get() = selectedMedia.any { it.uri in tellomiViewOnceChoices }

  /** Tellomi（#1263 P-5）：罩住的已选（取消勾选的不算）。 */
  val selectedSpoileredUris: Set<Uri>
    get() = selectedMedia.mapNotNullTo(mutableSetOf()) { it.uri.takeIf(tellomiSpoileredUris::contains) }

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
