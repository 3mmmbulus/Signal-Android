package org.thoughtcrime.securesms.conversation

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.BottomSheetUtil.requireCoordinatorLayout
import org.signal.core.ui.FixedRoundedCornerBottomSheetDialogFragment
import org.signal.core.util.StreamUtil
import org.signal.core.util.Util
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.concurrent.SimpleTask
import org.signal.core.util.dp
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.SignalProgressDialog
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.components.recyclerview.SmoothScrollingLinearLayoutManager
import org.thoughtcrime.securesms.conversation.colors.ColorizerV1
import org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart.Attachments
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ItemDecoration
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackController
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackPolicy
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionPlayerHolder
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionRecycler
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.mms.TextSlide
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.StickyHeaderDecoration
import org.thoughtcrime.securesms.util.fragments.findListener
import org.thoughtcrime.securesms.util.hasAudio
import org.thoughtcrime.securesms.util.hasPoll
import org.thoughtcrime.securesms.util.hasSharedContact
import org.thoughtcrime.securesms.util.hasSticker
import org.thoughtcrime.securesms.util.hasTextSlide
import org.thoughtcrime.securesms.util.isViewOnceMessage
import org.thoughtcrime.securesms.util.requireTextSlide
import java.io.IOException
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

/**
 * Bottom sheet dialog to view all scheduled messages within a given thread.
 */
class ScheduledMessagesBottomSheet :
  FixedRoundedCornerBottomSheetDialogFragment(),
  ScheduleMessageTimePickerBottomSheet.RescheduleCallback,
  ScheduleMessageTimePickerBottomSheet.ScheduleCallback {

  override val peekHeightPercentage: Float = 0.66f
  override val themeResId: Int = R.style.Widget_Signal_FixedRoundedCorners_Messages

  private var firstRender: Boolean = true
  private var deleteDialog: AlertDialog? = null

  private lateinit var messageAdapter: ConversationAdapter
  private lateinit var callback: ConversationBottomSheetCallback

  /** Tellomi（#1611）：会话现在能发，定时消息页上才能写新的 / 改。 */
  private var tellomiCanWrite = false
  private lateinit var tellomiConversationRecipient: Recipient
  private var tellomiComposeHint: TextView? = null

  private val viewModel: ScheduledMessagesViewModel by viewModels(
    factoryProducer = {
      val threadId = requireArguments().getLong(KEY_THREAD_ID)
      ScheduledMessagesViewModel.Factory(threadId)
    }
  )

  private val disposables: LifecycleDisposable = LifecycleDisposable()

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    val view = inflater.inflate(R.layout.scheduled_messages_bottom_sheet, container, false)
    disposables.bindTo(viewLifecycleOwner)
    return view
  }

  @SuppressLint("WrongThread")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val conversationRecipientId = RecipientId.from(arguments?.getString(KEY_CONVERSATION_RECIPIENT_ID, null) ?: throw IllegalArgumentException())
    val conversationRecipient = Recipient.resolved(conversationRecipientId)

    callback = findListener<ConversationBottomSheetCallback>() ?: run {
      dismissAllowingStateLoss()
      return
    }

    tellomiConversationRecipient = conversationRecipient
    tellomiCanWrite = callback.tellomiCanWriteScheduledMessages()
    tellomiInitializeCompose(view)

    @Suppress("DEPRECATION")
    val colorizer = ColorizerV1()

    messageAdapter = ConversationAdapter(requireContext(), viewLifecycleOwner, Glide.with(this), Locale.getDefault(), ConversationAdapterListener(), conversationRecipient.hasWallpaper, colorizer).apply {
      setCondensedMode(ConversationItemDisplayMode.Condensed(ConversationItemDisplayMode.MessageMode.SCHEDULED))
    }

    val stickyHeaders = StickyHeaderDecoration(messageAdapter, false, false, ConversationAdapter.HEADER_TYPE_INLINE_DATE)

    val list: RecyclerView = view.findViewById<RecyclerView>(R.id.scheduled_list).apply {
      layoutManager = SmoothScrollingLinearLayoutManager(requireContext(), true)
      adapter = messageAdapter
      itemAnimator = null

      doOnNextLayout {
        // Adding this without waiting for a layout pass would result in an indeterminate amount of padding added to the top of the view
        addItemDecoration(stickyHeaders)
      }
    }

    val recyclerViewColorizer = RecyclerViewColorizer(list)

    val emptyView: TextView = view.findViewById(R.id.tellomi_empty)
    emptyView.setText(if (conversationRecipient.isSelf) R.string.TellomiScheduledMessages__reminders_empty else R.string.TellomiScheduledMessages__empty)
    disposables += viewModel.getMessages(requireContext()).subscribe { messages ->
      // Tellomi（#1611）：会话能不能发可能晚到（重建后会话页的状态后到）：每次刷新再看一眼
      tellomiRefreshCanWrite(view)
      // 能写的时候空了也不关（同 TG 两端：空的定时消息页还在，底下能写新的）
      emptyView.isVisible = messages.isEmpty() && tellomiCanWrite
      if (messages.isEmpty()) {
        deleteDialog?.dismiss()
        if (!tellomiCanWrite) {
          dismissAllowingStateLoss()
        }
      }

      messageAdapter.submitList(messages) {
        if (firstRender) {
          (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(messages.size - 1, 100)

          firstRender = false
        } else if (!list.canScrollVertically(1)) {
          list.layoutManager?.scrollToPosition(0)
        }

        stickyHeaders.invalidateCache()
        list.invalidateItemDecorations()
      }
      recyclerViewColorizer.setChatColors(conversationRecipient.chatColors)
    }

    initializeGiphyMp4(view.findViewById(R.id.video_container)!!, list)
  }

  private fun initializeGiphyMp4(videoContainer: ViewGroup, list: RecyclerView): GiphyMp4ProjectionRecycler {
    val maxPlayback = GiphyMp4PlaybackPolicy.maxSimultaneousPlaybackInConversation()
    val holders = GiphyMp4ProjectionPlayerHolder.injectVideoViews(
      requireContext(),
      viewLifecycleOwner.lifecycle,
      videoContainer,
      maxPlayback
    )
    val callback = GiphyMp4ProjectionRecycler(holders)

    GiphyMp4PlaybackController.attach(list, callback, maxPlayback)
    list.addItemDecoration(GiphyMp4ItemDecoration(callback), 0)

    return callback
  }

  private fun showScheduledMessageContextMenu(view: View, conversationMessage: ConversationMessage) {
    SignalContextMenu.Builder(view, requireCoordinatorLayout())
      .offsetX(12.dp)
      .offsetY(12.dp)
      .preferredVerticalPosition(SignalContextMenu.VerticalPosition.ABOVE)
      .show(getMenuActionItems(conversationMessage))
  }

  private fun getMenuActionItems(message: ConversationMessage): List<ActionItem> {
    val canCopy = message.multiselectCollection.toSet().any { it !is Attachments && message.messageRecord.body.isNotEmpty() }
    val items: MutableList<ActionItem> = ArrayList()
    items.add(ActionItem(CoreUiR.drawable.symbol_trash_24, resources.getString(R.string.conversation_selection__menu_delete), action = { handleDeleteMessage(message.messageRecord) }))
    if (canCopy) {
      items.add(ActionItem(CoreUiR.drawable.symbol_copy_android_24, resources.getString(R.string.conversation_selection__menu_copy), action = { handleCopyMessage(message) }))
    }
    // Tellomi（#1611）：「编辑」（同 TG 两端）。不能带字的不给改（同上游改消息的条件 `MessageConstraintsUtil.isValidEditMessageSend`：
    // 阅后即焚、语音、名片、贴纸、投票）；长文（正文另存成附件的）原地改不了
    val record = message.messageRecord as? MmsMessageRecord
    val canEdit = record != null &&
      !record.isViewOnceMessage() &&
      !record.hasAudio() &&
      !record.hasSharedContact() &&
      !record.hasSticker() &&
      !record.hasPoll() &&
      !record.hasTextSlide()
    if (tellomiCanWrite && canEdit) {
      items.add(ActionItem(CoreUiR.drawable.symbol_edit_24, resources.getString(R.string.conversation_selection__menu_edit), action = { tellomiEdit(message) }))
    }
    items.add(ActionItem(R.drawable.symbol_send_24, resources.getString(R.string.ScheduledMessagesBottomSheet_menu_send_now), action = { handleSendMessageNow(message.messageRecord) }))
    items.add(ActionItem(R.drawable.symbol_calendar_24, resources.getString(R.string.ScheduledMessagesBottomSheet_menu_reschedule), action = { handleRescheduleMessage(message.messageRecord) }))
    return items
  }

  private fun handleRescheduleMessage(messageRecord: MessageRecord) {
    // Tellomi（#1601）：收藏里的定时消息是提醒，选时间页写「设置提醒」。
    val isReminder = messageRecord.toRecipient.isSelf
    ScheduleMessageTimePickerBottomSheet.showReschedule(childFragmentManager, messageRecord.id, (messageRecord as MmsMessageRecord).scheduledDate, isReminder)
  }

  private fun handleSendMessageNow(messageRecord: MessageRecord) {
    viewModel.rescheduleMessage(messageRecord.id, System.currentTimeMillis())
  }

  private fun handleDeleteMessage(messageRecord: MessageRecord) {
    deleteDialog?.dismiss()
    deleteDialog = buildDeleteScheduledMessageConfirmationDialog(messageRecord)
      .setOnDismissListener { deleteDialog = null }
      .show()
  }

  private fun handleCopyMessage(message: ConversationMessage) {
    SimpleTask.run(
      viewLifecycleOwner.lifecycle,
      { getMessageText(message) },
      { bodies: CharSequence? ->
        if (!Util.isEmpty(bodies)) {
          Util.copyToClipboard(requireContext(), bodies!!)
        }
      }
    )
  }

  private fun buildDeleteScheduledMessageConfirmationDialog(messageRecord: MessageRecord): AlertDialog.Builder {
    return MaterialAlertDialogBuilder(requireContext())
      .setTitle(resources.getString(R.string.ScheduledMessagesBottomSheet_delete_dialog_message))
      .setCancelable(true)
      .setPositiveButton(R.string.ScheduledMessagesBottomSheet_delete_dialog_action) { _: DialogInterface?, _: Int ->
        deleteMessage(messageRecord.id)
      }
      .setNegativeButton(android.R.string.cancel, null)
  }

  private fun getMessageText(message: ConversationMessage): CharSequence {
    if (message.messageRecord.hasTextSlide()) {
      val textSlide: TextSlide = message.messageRecord.requireTextSlide()
      if (textSlide.uri == null) {
        return message.getDisplayBody(requireContext())
      }
      try {
        PartAuthority.getAttachmentStream(requireContext(), textSlide.uri!!).use { stream ->
          val body = StreamUtil.readFullyAsString(stream)
          return ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(requireContext(), message.messageRecord, body, message.threadRecipient)
            .getDisplayBody(requireContext())
        }
      } catch (e: IOException) {
        Log.w(TAG, "Failed to read text slide data.")
      }
    }
    return ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(requireContext(), message.messageRecord, message.threadRecipient).getDisplayBody(requireContext())
  }

  private fun deleteMessage(messageId: Long) {
    context?.let { context ->
      val progressDialog = SignalProgressDialog.show(
        context = context,
        message = resources.getString(R.string.ScheduledMessagesBottomSheet_deleting_progress_message),
        indeterminate = true
      )
      SimpleTask.run({
        SignalDatabase.messages.deleteScheduledMessage(messageId)
      }, {
        progressDialog.dismiss()
      })
    }
  }

  override fun onReschedule(scheduledTime: Long, messageId: Long) {
    viewModel.rescheduleMessage(messageId, scheduledTime)
  }

  // region Tellomi（tellomi/tellomi#1611，M-4）：定时消息页上写新的、改还没发的

  private fun tellomiInitializeCompose(view: View) {
    tellomiComposeHint = view.findViewById(R.id.tellomi_compose_hint)
    val open = View.OnClickListener {
      TellomiScheduledComposeDialogFragment.show(childFragmentManager, requireArguments().getLong(KEY_THREAD_ID), tellomiConversationRecipient.id, tellomiConversationRecipient.isSelf)
    }
    tellomiComposeHint?.setOnClickListener(open)
    view.findViewById<View>(R.id.tellomi_compose_schedule).setOnClickListener(open)
    tellomiUpdateComposeHint()
    tellomiRefreshCanWrite(view)

    childFragmentManager.setFragmentResultListener(TellomiScheduledComposeDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, _ ->
      val edit = viewModel.tellomiPendingEdit
      if (edit != null) {
        viewModel.tellomiPendingEdit = null
        viewModel.tellomiEditingMessage = null
        disposables += viewModel.tellomiSaveEdit(edit).subscribe(
          { saved ->
            if (!saved) {
              Toast.makeText(requireContext(), R.string.TellomiScheduledMessages__edit_already_sent, Toast.LENGTH_LONG).show()
            }
          },
          { error ->
            Log.w(TAG, "Couldn't save an edit to a scheduled message", error)
            Toast.makeText(requireContext(), R.string.TellomiScheduledMessages__edit_failed, Toast.LENGTH_LONG).show()
          }
        )
      } else if (viewModel.tellomiPendingDraft != null) {
        // 新写的：先选时间（同 TG 两端在定时消息页点发送）；取消了，写的字留着，下次点输入框放回去
        tellomiUpdateComposeHint()
        ScheduleMessageTimePickerBottomSheet.showSchedule(childFragmentManager, tellomiConversationRecipient.isSelf)
      }
    }
  }

  /** 输入框跟着会话能不能发；能写的时候整张展开，列表长了自己缩、输入框总在底下。 */
  private fun tellomiRefreshCanWrite(view: View) {
    val canWrite = callback.tellomiCanWriteScheduledMessages()
    val changed = canWrite != tellomiCanWrite
    tellomiCanWrite = canWrite
    view.findViewById<View>(R.id.tellomi_compose_bar).isVisible = canWrite
    if (canWrite && (changed || !tellomiExpanded)) {
      tellomiExpanded = true
      (dialog as? BottomSheetDialog)?.behavior?.apply {
        skipCollapsed = true
        state = BottomSheetBehavior.STATE_EXPANDED
      }
    }
  }

  private var tellomiExpanded = false

  private fun tellomiUpdateComposeHint() {
    tellomiComposeHint?.text = viewModel.tellomiPendingDraft?.takeIf { it.editMessageId == null }?.displayText
  }

  private fun tellomiEdit(message: ConversationMessage) {
    viewModel.tellomiEditingMessage = message
    TellomiScheduledComposeDialogFragment.show(
      childFragmentManager,
      requireArguments().getLong(KEY_THREAD_ID),
      tellomiConversationRecipient.id,
      tellomiConversationRecipient.isSelf,
      editMessageId = message.messageRecord.id
    )
  }

  override fun onScheduleSend(scheduledTime: Long) {
    val draft = viewModel.tellomiPendingDraft?.takeIf { it.editMessageId == null } ?: return
    viewModel.tellomiPendingDraft = null
    tellomiUpdateComposeHint()
    callback.tellomiScheduleText(draft.body, draft.mentions, draft.bodyRanges, draft.linkPreviews ?: emptyList(), scheduledTime)
  }

  // endregion

  private inner class ConversationAdapterListener : ConversationAdapter.ItemClickListener by callback.getConversationAdapterListener() {
    override fun onQuoteClicked(messageRecord: MmsMessageRecord) {
      dismissAllowingStateLoss()
      callback.getConversationAdapterListener().onQuoteClicked(messageRecord)
    }

    override fun onScheduledIndicatorClicked(view: View, conversationMessage: ConversationMessage) {
      showScheduledMessageContextMenu(view, conversationMessage)
    }

    override fun onGroupMemberClicked(recipientId: RecipientId, groupId: GroupId) {
      dismissAllowingStateLoss()
      callback.getConversationAdapterListener().onGroupMemberClicked(recipientId, groupId)
    }

    override fun onVoiceNotePlay(uri: Uri, messageId: Long, position: Double) {
      callback.getConversationAdapterListener().onSingleVoiceNotePlay(uri, messageId, position)
    }

    override fun onItemLongClick(itemView: View?, item: MultiselectPart?) = Unit
    override fun onItemClick(item: MultiselectPart?) = Unit
  }

  companion object {
    private val TAG = Log.tag(ScheduledMessagesBottomSheet::class.java)

    private const val KEY_THREAD_ID = "thread_id"
    private const val KEY_CONVERSATION_RECIPIENT_ID = "conversation_recipient_id"

    @JvmStatic
    fun show(fragmentManager: FragmentManager, threadId: Long, conversationRecipientId: RecipientId) {
      val args = Bundle().apply {
        putLong(KEY_THREAD_ID, threadId)
        putString(KEY_CONVERSATION_RECIPIENT_ID, conversationRecipientId.serialize())
      }

      val fragment = ScheduledMessagesBottomSheet().apply {
        arguments = args
      }

      fragment.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }
}
