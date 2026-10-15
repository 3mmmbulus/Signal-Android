/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import org.signal.core.models.media.Media
import org.signal.core.util.getParcelableExtraCompat
import org.signal.mediasend.MediaRecipientId
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendRecipient
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.mediasend.v3.MediaSendAttachmentSheetActivity
import org.thoughtcrime.securesms.mediasend.v3.MediaSendV3Activity
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Single entry point for launching the media send flow.
 */
object MediaSendLauncher {

  @JvmStatic
  fun camera(context: Context): Intent {
    return camera(context, isStory = false)
  }

  @JvmStatic
  fun camera(context: Context, isStory: Boolean): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        isCameraFirst = true,
        mode = MediaSendFlowActivityContract.Mode.ChooseAfterMediaSelection,
        isStory = isStory
      )
    )
  }

  @JvmStatic
  fun camera(
    context: Context,
    recipientId: RecipientId,
    isReply: Boolean
  ): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        isCameraFirst = true,
        mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
        recipientId = recipientId.toMediaRecipientId(),
        isReply = isReply
      )
    )
  }

  fun cameraForQuickRestore(context: Context): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        isCameraFirst = true,
        mode = MediaSendFlowActivityContract.Mode.ChooseAfterMediaSelection,
        isForQuickRestore = true
      )
    )
  }

  fun addToGroupStory(context: Context, recipientId: RecipientId): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        isCameraFirst = true,
        mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
        recipientId = recipientId.toMediaRecipientId(),
        isStory = true,
        isAddToGroupStoryFlow = true
      )
    )
  }

  @JvmStatic
  fun gallery(
    context: Context,
    media: List<Media>,
    recipientId: RecipientId,
    message: CharSequence?,
    isReply: Boolean
  ): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
        recipientId = recipientId.toMediaRecipientId(),
        initialMedia = media,
        initialMessage = message,
        isReply = isReply
      )
    )
  }

  @JvmStatic
  fun editor(
    context: Context,
    media: List<Media>,
    recipientId: RecipientId,
    message: CharSequence?
  ): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
        recipientId = recipientId.toMediaRecipientId(),
        initialMedia = media,
        initialMessage = message
      )
    )
  }

  @JvmStatic
  fun editor(context: Context, media: List<Media>): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        mode = MediaSendFlowActivityContract.Mode.ChooseAfterMediaSelection,
        initialMedia = media
      )
    )
  }

  @JvmStatic
  fun share(
    context: Context,
    media: List<Media>,
    recipientSearchKeys: List<ContactSearchKey.RecipientSearchKey>,
    message: CharSequence?,
    asTextStory: Boolean
  ): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        mode = MediaSendFlowActivityContract.Mode.MultiRecipient,
        additionalRecipients = recipientSearchKeys.map { MediaSendRecipient(it.recipientId.toMediaRecipientId(), it.isStory) },
        initialMedia = media,
        initialMessage = message,
        isStory = recipientSearchKeys.any { it.isStory },
        asTextStory = asTextStory
      )
    )
  }

  /** Tellomi（tellomi/tellomi#1115）：附件 Sheet 里点了 dock 的哪一格（[MediaSendFlowActivityContract.DockEntry.id]）。 */
  const val EXTRA_ATTACHMENT_DOCK_ENTRY = "tellomi.attachment_dock_entry"

  /** Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页要发的文件（[MediaSendFlowActivityContract.AttachmentFilesResult]）。 */
  const val EXTRA_ATTACHMENT_FILES = "tellomi.attachment_files"

  /** Tellomi（2026-10-16）：附件 Sheet 里 app 画的哪一页做完了（[MediaSendFlowActivityContract.AttachmentPage] 的名字）和它的结果。 */
  const val EXTRA_ATTACHMENT_PAGE = "tellomi.attachment_page"
  const val EXTRA_ATTACHMENT_PAGE_RESULT = "tellomi.attachment_page_result"

  /**
   * Tellomi（tellomi/tellomi#1115）：会话页「+」打开的附件 Sheet——选图网格做成半屏 Sheet（聊天露在上面），底部 dock 是 [dock] 里的格子。
   * 发了照片的结果同 [gallery]；点了 dock 的别的格子，结果里带 [EXTRA_ATTACHMENT_DOCK_ENTRY]（[parseAttachmentDockEntry]）。
   */
  fun attachmentSheet(
    context: Context,
    recipientId: RecipientId,
    message: CharSequence?,
    isReply: Boolean,
    dock: List<MediaSendFlowActivityContract.DockEntry>
  ): Intent {
    return v3Intent(
      context,
      MediaSendFlowActivityContract.Args(
        mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
        recipientId = recipientId.toMediaRecipientId(),
        initialMessage = message,
        isReply = isReply,
        attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(dock)
      ),
      MediaSendAttachmentSheetActivity::class.java
    )
  }

  /** 附件 Sheet 里点了 dock 的哪一格；发了照片或者取消了是 null。 */
  fun parseAttachmentDockEntry(resultCode: Int, data: Intent?): String? {
    if (resultCode != Activity.RESULT_OK) {
      return null
    }

    return data?.getStringExtra(EXTRA_ATTACHMENT_DOCK_ENTRY)
  }

  /** Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页选好的文件，交给会话页去发；不是这种结果时是 null。 */
  fun parseAttachmentFiles(resultCode: Int, data: Intent?): MediaSendFlowActivityContract.AttachmentFilesResult? {
    if (resultCode != Activity.RESULT_OK || data == null) {
      return null
    }

    return data.getParcelableExtraCompat(EXTRA_ATTACHMENT_FILES, MediaSendFlowActivityContract.AttachmentFilesResult::class.java)
  }

  /** Tellomi（2026-10-16）：附件 Sheet 投票页发出的投票（[org.thoughtcrime.securesms.polls.Poll.toBundle]）；不是这种结果时是 null。 */
  fun parseAttachmentPoll(resultCode: Int, data: Intent?): Bundle? {
    if (resultCode != Activity.RESULT_OK || data == null) {
      return null
    }

    if (data.getStringExtra(EXTRA_ATTACHMENT_PAGE) != MediaSendFlowActivityContract.AttachmentPage.POLL.name) {
      return null
    }

    return data.getBundleExtra(EXTRA_ATTACHMENT_PAGE_RESULT)
  }

  /**
   * Tellomi（2026-10-16）：附件 Sheet 联系人页确认要发的联系人卡（`ContactShareEditActivityV2.KEY_CONTACTS`，同整屏那条路的结果）；
   * 不是这种结果时是 null。
   */
  fun parseAttachmentContacts(resultCode: Int, data: Intent?): List<org.thoughtcrime.securesms.contactshare.Contact>? {
    if (resultCode != Activity.RESULT_OK || data == null) {
      return null
    }

    if (data.getStringExtra(EXTRA_ATTACHMENT_PAGE) != MediaSendFlowActivityContract.AttachmentPage.CONTACT.name) {
      return null
    }

    val bundle = data.getBundleExtra(EXTRA_ATTACHMENT_PAGE_RESULT) ?: return null
    bundle.classLoader = org.thoughtcrime.securesms.contactshare.Contact::class.java.classLoader
    return androidx.core.os.BundleCompat.getParcelableArrayList(
      bundle,
      org.thoughtcrime.securesms.contactshare.ContactShareEditActivityV2.KEY_CONTACTS,
      org.thoughtcrime.securesms.contactshare.Contact::class.java
    )
  }

  /**
   * Reads the payload the caller is expected to send itself, for either implementation.
   *
   * Returns null when the flow performed the send on its own, as it does whenever the destinations were chosen
   * inside the flow.
   */
  @JvmStatic
  fun parseResult(resultCode: Int, data: Intent?): MediaSendActivityResult? {
    if (resultCode != Activity.RESULT_OK || data == null) {
      return null
    }

    return data.getParcelableExtraCompat(MediaSendActivityResult.EXTRA_RESULT, Parcelable::class.java) as? MediaSendActivityResult
  }

  private fun v3Intent(
    context: Context,
    args: MediaSendFlowActivityContract.Args,
    activity: Class<out Activity> = MediaSendV3Activity::class.java
  ): Intent {
    return Intent(context, activity)
      .putExtra(MediaSendFlowActivityContract.EXTRA_ARGS, args.copy(maxSelection = RemoteConfig.maxAttachmentCount))
  }

  private fun RecipientId.toMediaRecipientId(): MediaRecipientId = MediaRecipientId(toLong())
}
