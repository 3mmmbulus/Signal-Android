/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import org.signal.core.models.media.Media
import org.signal.core.models.parcelers.NullableCharSequenceParceler

/**
 * Well-defined entry/exit contract for the media sending flow.
 *
 * This intentionally supports a "multi-tier" outcome:
 * - Return a payload to be sent by the caller (single-recipient / conversation-owned send pipeline).
 * - Or send immediately in the flow and return an acknowledgement (multi-recipient / broadcast paths).
 *
 * Since [MediaSendActivity] is abstract, app-layer implementations should either:
 * 1. Extend this contract and override [createIntent] to use their concrete activity class
 * 2. Use the constructor that takes an activity class parameter
 *
 * Example:
 * ```kotlin
 * class MyMediaSendContract : MediaSendActivityContract(MyMediaSendActivity::class.java)
 * ```
 */
class MediaSendFlowActivityContract(private val clazz: Class<out Activity>) : ActivityResultContract<MediaSendFlowActivityContract.Args, MediaSendFlowActivityContract.Result?>() {

  /**
   * Creates the intent to launch the media send activity.
   *
   * Subclasses should override this if not using the constructor parameter.
   */
  override fun createIntent(context: Context, input: Args): Intent {
    return Intent(context, clazz).apply {
      putExtra(EXTRA_ARGS, input)
    }
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Result? {
    if (resultCode != Activity.RESULT_OK || intent == null) return null
    return intent.getParcelableExtra(EXTRA_RESULT)
  }

  @Parcelize
  data class Args(
    val isCameraFirst: Boolean = false,
    /**
     * Optional recipient identifier for single-recipient flows.
     */
    val recipientId: MediaRecipientId? = null,
    /**
     * Recipients known up front for multi-recipient flows, in addition to [recipientId].
     */
    val additionalRecipients: List<MediaSendRecipient> = emptyList(),
    val mode: Mode = Mode.SingleRecipient,
    /**
     * Initial media to populate the selection.
     * For gallery/editor flows, this is the pre-selected media.
     * For camera-first flows, this is typically empty.
     */
    val initialMedia: List<Media> = emptyList(),
    /**
     * Initial message/caption text. Retains mention and styling spans.
     */
    val initialMessage: @WriteWith<NullableCharSequenceParceler> CharSequence? = null,
    /**
     * Whether this is a reply flow (affects UI/constraints).
     */
    val isReply: Boolean = false,
    /**
     * Whether this is a story send flow.
     */
    val isStory: Boolean = false,
    /**
     * Whether this is specifically the "add to group story" flow.
     */
    val isAddToGroupStoryFlow: Boolean = false,
    /**
     * Whether the flow should open directly into the text story editor, seeded with [initialMessage].
     */
    val asTextStory: Boolean = false,
    /**
     * Whether the flow was entered to scan a quick restore QR code, which shows an explanatory sheet.
     */
    val isForQuickRestore: Boolean = false,
    /**
     * Maximum number of media items that can be selected.
     */
    val maxSelection: Int = 32,
    /**
     * Send type identifier (app-layer enum ordinal).
     */
    val sendType: Int = 0,
    /**
     * Tellomi（tellomi/tellomi#1115）：从会话页的「+」打开时非空——选图网格做成半屏 Sheet（聊天露在上面、压暗），
     * 底部 dock 放这里的格子。null 是整屏的选图流程，同上游。
     */
    val attachmentSheet: AttachmentSheet? = null
  ) : Parcelable {
    companion object {
      fun fromIntent(intent: Intent): Args {
        return intent.getParcelableExtra(EXTRA_ARGS) ?: Args()
      }
    }
  }

  /**
   * Tellomi（tellomi/tellomi#1115，照 Telegram 的附件菜单）：附件 Sheet 底部 dock 的格子。文字和图标由打开它的会话页给
   * （app 的资源），点了把 [DockEntry.id] 带回会话页。
   */
  @Parcelize
  data class AttachmentSheet(val dock: List<DockEntry>) : Parcelable

  /**
   * @param page 这一格是 Sheet 里的哪一页（tellomi/tellomi#1121：相册、文件在同一个 Sheet 里换页）；点当前页那格回到顶部、展开到全屏。
   *   null = 不是 Sheet 里的页，点了带回会话页（位置、投票、联系人）。
   * @param comingSoonMessage 非空就置灰：点了只提示这一句，Sheet 留着（Android「位置」等高德，tellomi/tellomi#1124）。
   */
  @Parcelize
  data class DockEntry(
    val id: String,
    @get:StringRes val title: Int,
    @get:DrawableRes val icon: Int,
    val page: AttachmentPage? = null,
    @get:StringRes val comingSoonMessage: Int? = null
  ) : Parcelable

  /** Tellomi（tellomi/tellomi#1121 F-1）：附件 Sheet 里的页。dock 的「相册」「文件」在同一个 Sheet 里换页，不收起 Sheet。 */
  enum class AttachmentPage {
    GALLERY,
    FILES
  }

  /**
   * Tellomi（tellomi/tellomi#1121）：「文件」页选好要发的文件，带回会话页去发——每个一条、说明挂在最后一个。
   *
   * @param recentAttachmentIds 「最近发送的文件」里点的 / 勾的（本机附件行号，按勾选的顺序），F-7、F-8。
   * @param pickedUris 系统文件选择器挑的（可多选），F-4。
   * @param galleryUris 「从相册中选择」勾的相册原文件（按勾选的顺序），F-3；和 [pickedUris] 一样按文件发，但不用申请读权限。
   * @param convertHeicToJpeg F-3 里勾的有 HEIC、用户选了「转成 JPEG」（[org.signal.mediasend.TellomiHeic]）。
   */
  @Parcelize
  data class AttachmentFilesResult(
    val recentAttachmentIds: List<Long>,
    val pickedUris: List<Uri>,
    val caption: String?,
    val galleryUris: List<Uri> = emptyList(),
    val convertHeicToJpeg: Boolean = false
  ) : Parcelable

  /**
   * High-level mode of operation for the flow.
   *
   * Note: We keep this free of app/database types. Callers can wrap their own identifiers as needed.
   */
  sealed interface Mode : Parcelable {
    /** Single known recipient — returns result for caller to send. */
    @Parcelize
    data object SingleRecipient : Mode

    /** Multiple known recipients — sends immediately, returns [Result.Sent]. */
    @Parcelize
    data object MultiRecipient : Mode

    /** User will select contacts during the flow — sends immediately, returns [Result.Sent]. */
    @Parcelize
    data object ChooseAfterMediaSelection : Mode
  }

  /**
   * Result returned when the flow completes successfully.
   *
   * - [ReadyToSend] mirrors the historical "return a result to Conversation to send" pattern.
   * - [Sent] mirrors the historical "broadcast paths send immediately" behavior.
   */
  sealed interface Result : Parcelable {

    /**
     * Caller should send via its canonical pipeline (e.g., conversation-owned send path).
     */
    @Parcelize
    data class ReadyToSend(
      val recipientId: MediaRecipientId,
      val body: String,
      val isViewOnce: Boolean,
      val scheduledTime: Long = -1,
      val payload: Payload
    ) : Result

    /**
     * The flow already performed the send (e.g., multi-recipient broadcast), so there is no payload.
     */
    @Parcelize
    data object Sent : Result
  }

  sealed interface Payload : Parcelable {
    /**
     * Pre-upload handles + dependencies. This is intentionally "handle-only" so the feature module
     * does not need to understand DB details; the app layer can interpret these primitives.
     */
    @Parcelize
    data class PreUploaded(
      val items: List<PreUploadHandle>
    ) : Payload

    /**
     * Local media that the caller should upload/send as part of the normal pipeline.
     */
    @Parcelize
    data class LocalMedia(
      val items: List<SelectedMedia>
    ) : Payload
  }

  /**
   * Mirrors the minimum data the legacy send pipeline needs for pre-upload sends:
   * - an attachment row identifier
   * - job dependency ids that the send jobs should wait on
   *
   * The feature module does not create or interpret these; it only transports them.
   */
  @Parcelize
  data class PreUploadHandle(
    val attachmentId: Long,
    val jobIds: List<String>,
    val mediaUri: Uri
  ) : Parcelable

  /**
   * Feature-level representation of selected media when the caller is responsible for upload/send.
   * This intentionally avoids coupling to app-layer `Media` / `TransformProperties`.
   */
  @Parcelize
  data class SelectedMedia(
    val uri: Uri,
    val contentType: String?,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val duration: Long = 0,
    val isBorderless: Boolean = false,
    val isVideoGif: Boolean = false,
    val caption: String? = null,
    val fileName: String? = null,
    val transform: Transform? = null
  ) : Parcelable

  /**
   * Feature-level transform model for media edits/quality.
   * Kept Parcelable for the Activity result boundary; a pure JVM equivalent can live in `core-models`.
   */
  @Parcelize
  data class Transform(
    val skipTransform: Boolean = false,
    val videoTrim: Boolean = false,
    val videoTrimStartTimeUs: Long = 0,
    val videoTrimEndTimeUs: Long = 0,
    val sentMediaQuality: SentMediaQuality = SentMediaQuality.STANDARD,
    val mp4FastStart: Boolean = false
  ) : Parcelable

  companion object {
    const val EXTRA_ARGS = "org.signal.mediasend.args"
    const val EXTRA_RESULT = "result"

    fun toResultIntent(result: Result): Intent {
      return Intent().apply {
        putExtra(EXTRA_RESULT, result)
      }
    }
  }
}
