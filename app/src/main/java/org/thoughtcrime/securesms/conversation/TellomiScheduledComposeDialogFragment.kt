/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import org.signal.core.util.ByteLimitInputFilter
import org.signal.core.util.getParcelableCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.KeyboardEntryDialogFragment
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQuery
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryChangedListener
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerFragment
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerViewModel
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MessageUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.whispersystems.signalservice.api.messages.SignalServiceMessageLimits
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（tellomi/tellomi#1611，M-4）：定时消息页上的输入框——写一条新的定时消息，或者改一条还没发的。
 *
 * 照 Telegram 两端（只读，代码是自己写的）：TG 的定时消息页就是聊天页，底下就是输入框；发送键是定时的样子，点了先选时间再排
 * （安卓 `ChatActivityEnterView` `isInScheduleMode()` → `input_schedule`，`ChatActivity` `MODE_SCHEDULED` →
 * `AlertsCreator.createScheduleDatePickerDialog`；iOS `ChatControllerNode` `scheduleCurrentMessage`）；长按「编辑」改一条，
 * 发送键换成勾（安卓 `doneButton`，iOS `chatInputPanelApplyIconImage`），到点时刻不变。
 *
 * Tellomi 的做法：定时消息页是会话上的底部弹窗，输入框用 Signal 自己「添加说明」那种键盘对话框（[KeyboardEntryDialogFragment]，
 * 键盘收起就关），@ 用同一个选人列表、链接预览同分享页。写好的交回定时消息页（[ScheduledMessagesViewModel.tellomiPendingDraft] +
 * fragment result），由它弹选时间页、排，或者存改动——选时间页一弹，键盘就收起、这个对话框就关了。
 */
class TellomiScheduledComposeDialogFragment : KeyboardEntryDialogFragment(R.layout.tellomi_scheduled_compose_dialog_fragment) {

  private val sheetViewModel: ScheduledMessagesViewModel by viewModels(
    ownerProducer = { requireParentFragment() },
    factoryProducer = { ScheduledMessagesViewModel.Factory(requireArguments().getLong(ARG_THREAD_ID)) }
  )

  private val linkPreviewViewModel: LinkPreviewViewModel by viewModels(
    factoryProducer = { LinkPreviewViewModel.Factory(LinkPreviewRepository()) }
  )

  private lateinit var mentionsViewModel: MentionsPickerViewModel
  private lateinit var composeText: ComposeText
  private lateinit var submitButton: ImageView
  private lateinit var linkPreviewView: LinkPreviewView

  /** 正在改的那条（新写的为 null）。 */
  private var editing: ConversationMessage? = null
  private var editingHasMedia = false
  private var userDismissedPreview = false
  private var recipient: Recipient? = null

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val editMessageId = requireArguments().getLong(ARG_EDIT_MESSAGE_ID, -1L)
    editing = sheetViewModel.tellomiEditingMessage?.takeIf { it.messageRecord.id == editMessageId }
    if (editMessageId != -1L && editing == null) {
      // 进程重建后丢了正在改的那条：不改了
      dismissAllowingStateLoss()
      return
    }
    val record = editing?.messageRecord as? MmsMessageRecord
    editingHasMedia = record?.slideDeck?.containsMediaSlide() == true

    composeText = view.findViewById(R.id.compose_text)
    submitButton = view.findViewById(R.id.submit_button)
    linkPreviewView = view.findViewById(R.id.link_preview)
    val editHeader: View = view.findViewById(R.id.edit_header)
    val editHeaderLabel: TextView = view.findViewById(R.id.edit_header_label)

    view.findViewById<View>(R.id.dismiss_area).setOnClickListener { dismissAllowingStateLoss() }
    view.findViewById<View>(R.id.edit_header_close).setOnClickListener { dismissAllowingStateLoss() }

    if (editing != null) {
      editHeader.isVisible = true
      editHeaderLabel.setText(R.string.InputPanel_edit_message)
      submitButton.setImageResource(CoreUiR.drawable.symbol_check_24)
      submitButton.contentDescription = getString(R.string.save)
      val body = editing!!.getDisplayBody(requireContext())
      // 同会话输入框改消息（`InputPanel.enterEditMessageMode`）：剧透在输入框里显示成可编辑的样子
      MessageStyler.convertSpoilersToComposeMode(body)
      composeText.setText(body)
      composeText.setSelection(body.length)
      // 改的时候只存得下一条消息正文那么长（再长要另存成长文附件，定时消息原地改不了）；原文先放进去再限
      composeText.filters += ByteLimitInputFilter(SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES)
      record?.linkPreviews?.firstOrNull()?.let { stored ->
        linkPreviewView.isVisible = true
        linkPreviewView.setLinkPreview(Glide.with(this), stored, true)
      }
    } else {
      composeText.filters += ByteLimitInputFilter(MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES)
      // 上次写好了、选时间时取消了：放回来
      sheetViewModel.tellomiPendingDraft?.takeIf { it.editMessageId == null }?.let { pending ->
        composeText.setText(pending.displayText)
        composeText.setSelection(composeText.text?.length ?: 0)
      }
      val isReminder = requireArguments().getBoolean(ARG_IS_REMINDER)
      submitButton.contentDescription = getString(if (isReminder) R.string.TellomiScheduleTimePicker__set_reminder else R.string.ScheduleMessageTimePickerBottomSheet__dialog_title)
    }

    linkPreviewView.setCloseClickedListener {
      userDismissedPreview = true
      linkPreviewViewModel.onUserCancel()
      linkPreviewView.isVisible = false
    }
    if (!editingHasMedia) {
      linkPreviewViewModel.getLinkPreviewState().observe(viewLifecycleOwner) { state ->
        when {
          state.error != null && LinkPreviewView.isShownWithoutPreview(state.error) -> {
            linkPreviewView.isVisible = true
            linkPreviewView.setNoPreview(state.error)
          }
          state.isLoading -> {
            linkPreviewView.isVisible = true
            linkPreviewView.setLoading()
          }
          state.linkPreview.isPresent -> {
            linkPreviewView.isVisible = true
            linkPreviewView.setLinkPreview(Glide.with(this), state.linkPreview.get(), true)
          }
          // 改的时候网址没变：接着显示原来的预览（存的时候也沿用它）
          keepsStoredPreview() -> {
            val stored = (editing?.messageRecord as? MmsMessageRecord)?.linkPreviews?.firstOrNull()
            if (stored != null) {
              linkPreviewView.isVisible = true
              linkPreviewView.setLinkPreview(Glide.with(this), stored, true)
            }
          }
          // 同会话输入框（ADR-0063 §5.1 铁律 2）：取不到预览就不显示预览区
          else -> linkPreviewView.isVisible = false
        }
      }
    }

    composeText.doAfterTextChanged { text ->
      updateSubmitButton()
      if (!editingHasMedia) {
        linkPreviewViewModel.onTextChanged(text?.toString() ?: "", composeText.selectionStart, composeText.selectionEnd)
      }
    }
    submitButton.setOnClickListener { submit() }
    updateSubmitButton()
    // 放回来的草稿：预览照取
    if (editing == null && !composeText.text.isNullOrEmpty()) {
      linkPreviewViewModel.onTextChanged(composeText.text.toString(), composeText.selectionStart, composeText.selectionEnd)
    }
    initializeMentions()
  }

  override fun onResume() {
    super.onResume()
    if (::composeText.isInitialized) {
      ViewUtil.focusAndShowKeyboard(composeText)
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    if (::composeText.isInitialized) {
      composeText.setInlineQueryChangedListener(null)
      composeText.setMentionValidator(null)
    }
  }

  /** 新写的要有字；改的要有字，或者那条有图（说明可以删光，同 TG）。 */
  private fun canSubmit(): Boolean {
    return composeText.textTrimmed.isNotBlank() || (editing != null && editingHasMedia)
  }

  private fun updateSubmitButton() {
    val enabled = canSubmit()
    submitButton.isEnabled = enabled
    submitButton.alpha = if (enabled) 1f else 0.4f
  }

  /** 改的时候、还没取到新预览：正文里还是原来那个网址，就沿用原来的预览。 */
  private fun keepsStoredPreview(): Boolean {
    val stored = (editing?.messageRecord as? MmsMessageRecord)?.linkPreviews?.firstOrNull() ?: return false
    return !userDismissedPreview && composeText.textTrimmed.toString().contains(stored.url)
  }

  private fun submit() {
    if (!canSubmit()) return
    val body = composeText.textTrimmed.toString()
    val loaded: List<LinkPreview> = if (editingHasMedia) emptyList() else linkPreviewViewModel.onSend()
    val linkPreviews: List<LinkPreview>? = when {
      // 有图的不带预览（同排定时那条路），也不动它
      editingHasMedia -> null
      loaded.isNotEmpty() -> loaded
      editing != null && keepsStoredPreview() -> null
      else -> emptyList()
    }
    val draft = ScheduledMessagesViewModel.TellomiDraft(
      displayText = SpannableString(composeText.text ?: ""),
      body = body,
      mentions = composeText.mentions,
      bodyRanges = composeText.styling,
      linkPreviews = linkPreviews,
      editMessageId = editing?.messageRecord?.id
    )
    if (editing != null) {
      sheetViewModel.tellomiPendingEdit = draft
    } else {
      sheetViewModel.tellomiPendingDraft = draft
    }
    setFragmentResult(REQUEST_KEY, bundleOf(RESULT_SUBMITTED to true))
    dismissAllowingStateLoss()
  }

  /** @ 选人（同媒体页「添加说明」：群里才有，选了换成 @名字）。 */
  private fun initializeMentions() {
    mentionsViewModel = ViewModelProvider(requireActivity(), MentionsPickerViewModel.Factory()).get(MentionsPickerViewModel::class.java)

    composeText.setInlineQueryChangedListener(object : InlineQueryChangedListener {
      override fun onQueryChanged(inlineQuery: InlineQuery) {
        if (inlineQuery is InlineQuery.Mention && recipient?.let { it.isPushV2Group && it.isActiveGroup } == true) {
          ensureMentionsContainerFilled()
          mentionsViewModel.onQueryChange(inlineQuery.query)
        } else {
          mentionsViewModel.onQueryChange(null)
        }
      }

      override fun clearQuery() {
        onQueryChanged(InlineQuery.NoQuery)
      }
    })

    val recipientId: RecipientId = requireArguments().getParcelableCompat(ARG_RECIPIENT_ID, RecipientId::class.java) ?: return
    Recipient.live(recipientId).observe(viewLifecycleOwner) { recipient ->
      this.recipient = recipient
      mentionsViewModel.onRecipientChange(recipient)
      composeText.setMentionValidator { annotations ->
        if (!recipient.isPushV2Group) {
          annotations
        } else {
          val validRecipientIds: Set<String> = recipient.participantIds.map { MentionAnnotation.idToMentionAnnotationValue(it) }.toSet()
          annotations.filter { !validRecipientIds.contains(it.value) }.toList()
        }
      }
    }

    mentionsViewModel.selectedRecipient.observe(viewLifecycleOwner) { recipient ->
      composeText.replaceTextWithMention(recipient.getDisplayName(requireContext()), recipient.id)
    }
  }

  private fun ensureMentionsContainerFilled() {
    if (childFragmentManager.findFragmentById(R.id.mentions_picker_container) == null) {
      childFragmentManager
        .beginTransaction()
        .replace(R.id.mentions_picker_container, MentionsPickerFragment())
        .commitNowAllowingStateLoss()
    }
  }

  companion object {
    const val TAG = "TellomiScheduledComposeDialog"
    const val REQUEST_KEY = "TellomiScheduledComposeDialog__request"
    const val RESULT_SUBMITTED = "TellomiScheduledComposeDialog__submitted"

    private const val ARG_THREAD_ID = "thread_id"
    private const val ARG_RECIPIENT_ID = "recipient_id"
    private const val ARG_IS_REMINDER = "is_reminder"
    private const val ARG_EDIT_MESSAGE_ID = "edit_message_id"

    /** @param editMessageId 改哪条（先放进 [ScheduledMessagesViewModel.tellomiEditingMessage]）；新写的不传。 */
    fun show(fragmentManager: FragmentManager, threadId: Long, recipientId: RecipientId, isReminder: Boolean, editMessageId: Long = -1L) {
      // 连点两下只开一个
      if (fragmentManager.findFragmentByTag(TAG) != null) return
      TellomiScheduledComposeDialogFragment().apply {
        arguments = bundleOf(
          ARG_THREAD_ID to threadId,
          ARG_RECIPIENT_ID to recipientId,
          ARG_IS_REMINDER to isReminder,
          ARG_EDIT_MESSAGE_ID to editMessageId
        )
      }.show(fragmentManager, TAG)
    }
  }
}
