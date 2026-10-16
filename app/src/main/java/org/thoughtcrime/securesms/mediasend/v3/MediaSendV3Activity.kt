/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.WindowBreakpoint
import org.signal.core.ui.compose.LocalChatColorProvider
import org.signal.core.ui.compose.LocalChatWallpaper
import org.signal.core.ui.compose.LocalDisplayNameProvider
import org.signal.core.ui.getWindowBreakpoint
import org.signal.emoji.EmojiEventListener
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowHudCommand
import org.signal.mediasend.MediaSendFlowViewModel
import org.signal.mediasend.MediaSendRecipient
import org.signal.mediasend.MediaSendRoute
import org.signal.mediasend.MediaSendScreen
import org.signal.mediasend.screens.edit.LocalAddAMessageRowTextField
import org.signal.mediasend.screens.edit.LocalScheduledSendTimeFormatter
import org.signal.mediasend.screens.edit.LocalTellomiInlineCaption
import org.signal.mediasend.screens.edit.LocalTellomiScheduleIsReminder
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.emoji.EmojiTextView
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.conversation.ReenableScheduledMessagesDialogFragment
import org.thoughtcrime.securesms.conversation.ScheduleMessageDialogCallback
import org.thoughtcrime.securesms.conversation.ScheduleMessageTimePickerBottomSheet
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardEvent
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardEventViewModel
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardPageFragment
import org.thoughtcrime.securesms.keyboard.emoji.search.EmojiSearchFragment
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.MediaSendLauncher
import org.thoughtcrime.securesms.mediasend.v2.QuickRestoreInfoDialog
import org.thoughtcrime.securesms.mediasend.v2.review.AddMessageDialogFragment
import org.thoughtcrime.securesms.mediasend.v2.text.TextStoryPostCreationFragment
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.rememberRecipientField
import org.thoughtcrime.securesms.registration.olddevice.QuickTransferOldDeviceActivity
import org.thoughtcrime.securesms.safety.SafetyNumberBottomSheet
import org.thoughtcrime.securesms.scribbles.StickerSelectActivityContract
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.WindowUtil
import org.signal.core.ui.R as CoreUiR

/**
 * Encapsulates the media send flow for v3.
 */
open class MediaSendV3Activity :
  PassphraseRequiredActivity(),
  SafetyNumberBottomSheet.Callbacks,
  TextStoryPostCreationFragment.Callback,
  EmojiKeyboardPageFragment.Callback,
  EmojiEventListener,
  EmojiSearchFragment.Callback,
  ScheduleMessageTimePickerBottomSheet.ScheduleCallback,
  ScheduleMessageDialogCallback {

  private val theme: DynamicTheme by lazy { createDynamicTheme() }

  /** Tellomi（tellomi/tellomi#1115）：附件 Sheet（[MediaSendAttachmentSheetActivity]）换成窗口透明的主题。 */
  protected open fun createDynamicTheme(): DynamicTheme = DynamicNoActionBarTheme()

  /**
   * Tellomi（两端对齐清单 P-4 第一期）：相册页底栏的说明框就地输入（[TellomiInlineCaptionInput]）。只有附件 Sheet 打开（第一期只改 Sheet）；
   * 别的入口还是点了弹上游的对话框。
   */
  protected open val tellomiUsesInlineCaption: Boolean = false

  private val contractArgs: MediaSendFlowActivityContract.Args by lazy { MediaSendFlowActivityContract.Args.fromIntent(intent) }

  /** Tellomi（tellomi/tellomi#1601）：这次是发给自己（收藏）：「定时发送」叫「设置提醒」。 */
  private val tellomiIsNoteToSelf: Boolean by lazy {
    // 只比 id，不在主线程查库。
    contractArgs.recipientId?.let { RecipientId.from(it.id) == Recipient.self().id } == true
  }

  private val viewModel: MediaSendFlowViewModel by viewModels { MediaSendFlowViewModel.Factory(args = contractArgs) }

  private val addMessageCommandViewModel: EmojiKeyboardEventViewModel by viewModels()

  private val stickerLauncher = registerForActivityResult(StickerSelectActivityContract()) { result ->
    viewModel.onStickerSelected(result?.toRenderer())
  }

  override val textStoryDestinations: Set<ContactSearchKey.RecipientSearchKey>
    get() = destinations().toSet()

  override val isAddToGroupStoryFlow: Boolean
    get() = contractArgs.isAddToGroupStoryFlow

  override val textStoryDraftText: CharSequence?
    get() = if (contractArgs.asTextStory) contractArgs.initialMessage else null

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    theme.onCreate(this)

    if (resources.getWindowBreakpoint() !is WindowBreakpoint.Small) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    if (savedInstanceState == null && contractArgs.isForQuickRestore) {
      QuickRestoreInfoDialog.show(supportFragmentManager)
    }

    supportFragmentManager.setFragmentResultListener(AddMessageDialogFragment.REQUEST_KEY, this) { _, bundle ->
      if (bundle.getBoolean(AddMessageDialogFragment.RESULT_INCREMENT_VIEW_ONCE_STATE)) {
        viewModel.toggleViewOnce()
      } else {
        viewModel.setMessage(bundle.getCharSequence(AddMessageDialogFragment.RESULT_MESSAGE, null))
      }
    }

    setContent {
      val context = LocalContext.current
      val isOnCaptureScreen = viewModel.backStack.lastOrNull() is MediaSendRoute.Capture
      val noteToSelf = stringResource(R.string.note_to_self)

      LaunchedEffect(isOnCaptureScreen) {
        onCaptureScreenChanged(isOnCaptureScreen)
      }

      val tellomiInlineCaption = remember {
        if (tellomiUsesInlineCaption) TellomiInlineCaptionInput(this, contractArgs.recipientId?.let { RecipientId.from(it.id) }) else null
      }

      CompositionLocalProvider(
        LocalTellomiInlineCaption provides tellomiInlineCaption,
        LocalAddAMessageRowTextField provides { message, modifier ->
          AndroidView(
            factory = {
              EmojiTextView(it).apply {
                setTextAppearance(CoreUiR.style.Signal_Text_Body)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
              }
            },
            update = { view ->
              view.text = message
            },
            modifier = modifier
          )
        },
        // Tellomi（#1601）：发给自己（收藏）时「定时发送」叫「设置提醒」。
        LocalTellomiScheduleIsReminder provides tellomiIsNoteToSelf,
        LocalScheduledSendTimeFormatter provides { time ->
          DateUtils.getScheduledMessageDateString(context, time)
        },
        LocalDisplayNameProvider provides { id ->
          rememberRecipientField(RecipientId.from(id)) {
            when {
              isUnknown -> ""
              isSelf -> noteToSelf
              else -> getDisplayName(context)
            }
          }
        },
        LocalChatColorProvider provides { id ->
          rememberRecipientField(RecipientId.from(id)) {
            Color(chatColors.asSingleColor())
          }
        },
        // Tellomi（#1261 P-3）：选图面板「只看已选」铺会话的聊天背景。
        LocalChatWallpaper provides { id, modifier -> ChatWallpaperBackground(id, modifier) }
      ) {
        MediaSendScreen(
          contractArgs = contractArgs,
          textStoryEditorSlot = {
            AndroidFragment(
              clazz = TextStoryPostCreationFragment::class.java,
              modifier = Modifier.fillMaxSize()
            )
          },
          sendSlot = {
            AndroidFragment(
              clazz = MediaSendV3ForwardFragment::class.java,
              modifier = Modifier.fillMaxSize()
            )
          },
          onExternalHudCommand = {
            when (it) {
              is MediaSendFlowHudCommand.ShowAddAMessageDialog -> {
                AddMessageDialogFragment.show(
                  fragmentManager = supportFragmentManager,
                  addAMessageDialog = it,
                  destination = contractArgs.recipientId?.let {
                    RecipientId.from(it.id)
                  }
                )
              }

              is MediaSendFlowHudCommand.SelectSticker -> stickerLauncher.launch(Unit)

              is MediaSendFlowHudCommand.PickScheduledSendTime -> {
                ScheduleMessageTimePickerBottomSheet.showSchedule(supportFragmentManager, tellomiIsNoteToSelf)
              }

              is MediaSendFlowHudCommand.ConfirmScheduledSend -> {
                if (!ReenableScheduledMessagesDialogFragment.showIfNeeded(this, supportFragmentManager, null, it.scheduledTime)) {
                  viewModel.onScheduledSendConfirmed(it.scheduledTime)
                }
              }

              is MediaSendFlowHudCommand.GoToConversation -> {
                lifecycleScope.launch(Dispatchers.Default) {
                  val recipient = Recipient.resolved(RecipientId.from(it.recipientId.id))
                  withContext(Dispatchers.Main) {
                    CommunicationActions.startConversation(
                      this@MediaSendV3Activity,
                      recipient,
                      null
                    )
                  }
                }
              }

              MediaSendFlowHudCommand.GoToLinkedDevices -> {
                startActivity(AppSettingsActivity.linkedDevices(this))
                finish()
              }

              is MediaSendFlowHudCommand.GoToQuickTransfer -> {
                startActivity(QuickTransferOldDeviceActivity.intent(this, it.qrData))
                finish()
              }

              is MediaSendFlowHudCommand.FinishWithResult -> finishWithResult(it.payload)

              is MediaSendFlowHudCommand.FinishWithoutResult -> onSentWithoutResult()

              is MediaSendFlowHudCommand.ResolveUntrustedIdentities -> {
                SafetyNumberBottomSheet
                  .forRecipientIdsAndDestinations(it.untrustedRecipientIds.map(RecipientId::from), destinations())
                  .show(supportFragmentManager)
              }

              is MediaSendFlowHudCommand.CloseScreen -> finish()

              is MediaSendFlowHudCommand.AttachmentDockEntrySelected -> {
                setResult(RESULT_OK, Intent().putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_DOCK_ENTRY, it.id))
                finish()
              }

              // Tellomi（tellomi/tellomi#1121）：「文件」页选好的文件带回会话页去发。系统选择器的读授权原本挂在这个 Activity 上、
              // finish 就收回；「文件」页收到结果时已经转成持久授权（PickedFileGrants），会话页整串发完再放，这里不用再转授。
              is MediaSendFlowHudCommand.SendAttachmentFiles -> {
                setResult(RESULT_OK, Intent().putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_FILES, it.result))
                finish()
              }

              // Tellomi（2026-10-16）：Sheet 里 app 画的页（投票）做完了，结果带回会话页。
              is MediaSendFlowHudCommand.AttachmentPageResult -> {
                setResult(
                  RESULT_OK,
                  Intent()
                    .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, it.page.name)
                    .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, it.result)
                )
                finish()
              }
            }
          }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  override fun onSentWithoutResult() {
    setResult(RESULT_OK, Intent())
    finish()
  }

  /**
   * The manifest hard-locks this activity to portrait, which we only want to honor for the capture screen on small
   * windows. Everything else is free to rotate so that the landscape and expanded layouts are reachable on tablets
   * and unfolded foldables.
   */
  private fun onCaptureScreenChanged(isOnCaptureScreen: Boolean) {
    requestedOrientation = if (isOnCaptureScreen && resources.getWindowBreakpoint() is WindowBreakpoint.Small) {
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
      ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Hard-cut rotation while capturing (like Pixel Camera) instead of the system's smooth rotate.
    window.attributes = window.attributes.apply {
      rotationAnimation = if (isOnCaptureScreen) {
        WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
      } else {
        WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE
      }
    }

    if (isOnCaptureScreen) {
      WindowUtil.clearLightStatusBar(window)
      WindowUtil.clearLightNavigationBar(window)
    } else {
      WindowUtil.setLightStatusBarFromTheme(this)
      WindowUtil.setLightNavigationBarFromTheme(this)
    }
  }

  override fun sendAnywayAfterSafetyNumberChangedInBottomSheet(destinations: List<ContactSearchKey.RecipientSearchKey>) {
    viewModel.performSend()
  }

  override fun onMessageResentAfterSafetyNumberChangeInBottomSheet() = error("Unsupported, we do not hand in a message id.")

  override fun onCanceled() = Unit

  override fun openEmojiSearch() {
    addMessageCommandViewModel.onEvent(EmojiKeyboardEvent.OpenEmojiSearch)
  }

  override fun closeEmojiSearch() {
    addMessageCommandViewModel.onEvent(EmojiKeyboardEvent.CloseEmojiSearch)
  }

  override fun onEmojiSelected(emoji: String?) {
    addMessageCommandViewModel.onEvent(EmojiKeyboardEvent.EmojiInsert(emoji))
  }

  override fun onKeyEvent(keyEvent: KeyEvent?) {
    addMessageCommandViewModel.onEvent(EmojiKeyboardEvent.EmojiKeyEvent(keyEvent))
  }

  override fun onScheduleSend(scheduledTime: Long) {
    viewModel.onScheduledSendTimeSelected(scheduledTime)
  }

  override fun onSchedulePermissionsGranted(metricId: String?, scheduledDate: Long) {
    viewModel.onScheduledSendConfirmed(scheduledDate)
  }

  private fun finishWithResult(payload: Parcelable) {
    setResult(RESULT_OK, Intent().putExtra(MediaSendActivityResult.EXTRA_RESULT, payload))
    finish()
  }

  /**
   * Reads from state rather than the launch args so that recipients chosen inside the flow are included.
   */
  private fun destinations(): List<ContactSearchKey.RecipientSearchKey> {
    val state = viewModel.state.value
    val single = state.recipientId?.let { MediaSendRecipient(it, state.isStory) }

    return (listOfNotNull(single) + state.additionalRecipients)
      .distinct()
      .map { ContactSearchKey.RecipientSearchKey(RecipientId.from(it.id.id), it.isStory) }
  }
}
