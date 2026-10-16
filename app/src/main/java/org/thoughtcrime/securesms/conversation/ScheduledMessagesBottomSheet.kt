package org.thoughtcrime.securesms.conversation

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.enableEdgeToEdge
import org.signal.core.util.ByteLimitInputFilter
import org.signal.core.util.StreamUtil
import org.signal.core.util.Util
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.concurrent.SimpleTask
import org.signal.core.util.dp
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.components.SignalProgressDialog
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.components.recyclerview.SmoothScrollingLinearLayoutManager
import org.thoughtcrime.securesms.conversation.colors.ColorizerV1
import org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart.Attachments
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQuery
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryChangedListener
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerFragment
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerViewModel
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ItemDecoration
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackController
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackPolicy
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionPlayerHolder
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionRecycler
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModel
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.mms.TextSlide
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MessageUtil
import org.thoughtcrime.securesms.util.StickyHeaderDecoration
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.WindowUtil
import org.thoughtcrime.securesms.util.fragments.findListener
import org.thoughtcrime.securesms.util.hasAudio
import org.thoughtcrime.securesms.util.hasPoll
import org.thoughtcrime.securesms.util.hasSharedContact
import org.thoughtcrime.securesms.util.hasSticker
import org.thoughtcrime.securesms.util.hasTextSlide
import org.thoughtcrime.securesms.util.isViewOnceMessage
import org.thoughtcrime.securesms.util.requireTextSlide
import org.whispersystems.signalservice.api.messages.SignalServiceMessageLimits
import java.io.IOException
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

/**
 * Bottom sheet dialog to view all scheduled messages within a given thread.
 *
 * Tellomi（两端对齐清单 P-5，owner 2026-10-16：两端都是整页）：不再是底部弹窗，是一整页——顶上返回 + 标题（收藏里是「提醒」），
 * 中间列表，底下一条输入框直接写新的 / 改一条（原来另弹的键盘对话框 `TellomiScheduledComposeDialogFragment` 并进来了），
 * 选时间仍是底部弹层 [ScheduleMessageTimePickerBottomSheet]。照 Telegram 两端：定时消息页就是聊天页（安卓 `ChatActivity`
 * `MODE_SCHEDULED`，标题 `ScheduledMessages` / 自己的会话 `Reminders`；iOS 整页 modal），和 iOS 的
 * `TellomiScheduledMessagesViewController` 同一个样子。类名留着上游的，省得改调用处、放大和上游的差异。
 */
class ScheduledMessagesBottomSheet :
  DialogFragment(),
  ScheduleMessageTimePickerBottomSheet.RescheduleCallback,
  ScheduleMessageTimePickerBottomSheet.ScheduleCallback {

  private var firstRender: Boolean = true
  private var deleteDialog: AlertDialog? = null

  private lateinit var messageAdapter: ConversationAdapter
  private lateinit var callback: ConversationBottomSheetCallback

  /** Tellomi（#1611）：会话现在能发，定时消息页上才能写新的 / 改。 */
  private var tellomiCanWrite = false
  private lateinit var tellomiConversationRecipient: Recipient

  private val viewModel: ScheduledMessagesViewModel by viewModels(
    factoryProducer = {
      val threadId = requireArguments().getLong(KEY_THREAD_ID)
      ScheduledMessagesViewModel.Factory(threadId)
    }
  )

  private val disposables: LifecycleDisposable = LifecycleDisposable()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setStyle(STYLE_NO_FRAME, R.style.Signal_DayNight_Dialog_FullScreen)
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    return super.onCreateDialog(savedInstanceState).apply {
      window?.enableEdgeToEdge()
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    val view = inflater.inflate(R.layout.scheduled_messages_bottom_sheet, container, false)
    disposables.bindTo(viewLifecycleOwner)
    return view
  }

  override fun onResume() {
    super.onResume()
    requireDialog().window?.let { WindowUtil.initializeScreenshotSecurity(requireContext(), it) }
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
    tellomiInitializePage(view)
    tellomiInitializeCompose(view, savedInstanceState)

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
    SignalContextMenu.Builder(view, requireView() as ViewGroup)
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

  // region Tellomi（tellomi/tellomi#1611 M-4；两端对齐清单 P-5）：整页的顶栏；底下的输入框写新的、改还没发的

  private val tellomiLinkPreviewViewModel: LinkPreviewViewModel by viewModels(
    factoryProducer = { LinkPreviewViewModel.Factory(LinkPreviewRepository()) }
  )

  private lateinit var tellomiComposeText: ComposeText
  private lateinit var tellomiSubmitButton: ImageView
  private lateinit var tellomiLinkPreviewView: LinkPreviewView
  private lateinit var tellomiEditHeader: View
  private lateinit var tellomiBaseFilters: Array<InputFilter>
  private var tellomiUserDismissedPreview = false
  private var tellomiRecipient: Recipient? = null

  /** 改一条时系统返回键先退出「编辑」（同会话输入框改消息），不关整页。 */
  private val tellomiCancelEditOnBack = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      tellomiExitEdit()
    }
  }

  private fun tellomiInitializePage(view: View) {
    // 整页铺到系统栏下面：顶上让出状态栏，底下让出导航栏或键盘（输入框跟着键盘走）
    SystemWindowInsetsSetter.attach(view, viewLifecycleOwner, SystemWindowInsetsSetter.SAFE_AREA_WITH_KEYBOARD)
    view.findViewById<Toolbar>(R.id.toolbar).apply {
      setTitle(if (tellomiConversationRecipient.isSelf) R.string.TellomiScheduledMessages__reminders_title else R.string.TellomiScheduledMessages__title)
      setNavigationOnClickListener { dismissAllowingStateLoss() }
    }
    (requireDialog() as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, tellomiCancelEditOnBack)
  }

  private fun tellomiInitializeCompose(view: View, savedInstanceState: Bundle?) {
    tellomiComposeText = view.findViewById(R.id.compose_text)
    tellomiSubmitButton = view.findViewById(R.id.submit_button)
    tellomiLinkPreviewView = view.findViewById(R.id.link_preview)
    tellomiEditHeader = view.findViewById(R.id.edit_header)
    tellomiBaseFilters = tellomiComposeText.filters
    view.findViewById<View>(R.id.edit_header_close).setOnClickListener { tellomiExitEdit() }

    tellomiLinkPreviewView.setCloseClickedListener {
      tellomiUserDismissedPreview = true
      tellomiLinkPreviewViewModel.onUserCancel()
      tellomiLinkPreviewView.isVisible = false
    }
    tellomiLinkPreviewViewModel.getLinkPreviewState().observe(viewLifecycleOwner) { state ->
      // 改一条有图的：不带预览（同排定时那条路），也不动它
      if (tellomiEditingHasMedia()) {
        return@observe
      }
      when {
        state.error != null && LinkPreviewView.isShownWithoutPreview(state.error) -> {
          tellomiLinkPreviewView.isVisible = true
          tellomiLinkPreviewView.setNoPreview(state.error)
        }
        state.isLoading -> {
          tellomiLinkPreviewView.isVisible = true
          tellomiLinkPreviewView.setLoading()
        }
        state.linkPreview.isPresent -> {
          tellomiLinkPreviewView.isVisible = true
          tellomiLinkPreviewView.setLinkPreview(Glide.with(this), state.linkPreview.get(), true)
        }
        // 改的时候网址没变：接着显示原来的预览（存的时候也沿用它）
        tellomiKeepsStoredPreview() -> tellomiShowStoredPreview()
        // 同会话输入框（ADR-0063 §5.1 铁律 2）：取不到预览就不显示预览区
        else -> tellomiLinkPreviewView.isVisible = false
      }
    }

    tellomiComposeText.doAfterTextChanged { text ->
      tellomiUpdateSubmitButton()
      if (!tellomiEditingHasMedia()) {
        tellomiLinkPreviewViewModel.onTextChanged(text?.toString() ?: "", tellomiComposeText.selectionStart, tellomiComposeText.selectionEnd)
      }
    }
    tellomiSubmitButton.setOnClickListener { tellomiSubmit() }
    tellomiInitializeMentions()

    val editing = viewModel.tellomiEditingMessage
    if (editing != null) {
      // 转屏重建：输入框里的字系统已经放回去了，只把「编辑」的样子摆回来
      tellomiShowEditUi(editing, setText = savedInstanceState == null)
    } else {
      tellomiShowNewUi(text = null)
    }
    tellomiRefreshCanWrite(view)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    if (::tellomiComposeText.isInitialized) {
      tellomiComposeText.setInlineQueryChangedListener(null)
      tellomiComposeText.setMentionValidator(null)
    }
  }

  /** 输入框跟着会话能不能发（不能发了就退出「编辑」）。 */
  private fun tellomiRefreshCanWrite(view: View) {
    tellomiCanWrite = callback.tellomiCanWriteScheduledMessages()
    view.findViewById<View>(R.id.tellomi_compose_bar).isVisible = tellomiCanWrite
    if (!tellomiCanWrite && viewModel.tellomiEditingMessage != null) {
      tellomiExitEdit()
    }
  }

  /** 写新的：发送键是日历（点了先选时间），长度照一条消息的上限。 */
  private fun tellomiShowNewUi(text: CharSequence?) {
    tellomiEditHeader.isVisible = false
    tellomiSubmitButton.setImageResource(R.drawable.symbol_calendar_24)
    tellomiSubmitButton.contentDescription = getString(if (tellomiConversationRecipient.isSelf) R.string.TellomiScheduleTimePicker__set_reminder else R.string.ScheduleMessageTimePickerBottomSheet__dialog_title)
    tellomiComposeText.filters = tellomiBaseFilters + ByteLimitInputFilter(MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES)
    if (text != null) {
      tellomiComposeText.setText(text)
      tellomiComposeText.setSelection(tellomiComposeText.text?.length ?: 0)
    }
    tellomiCancelEditOnBack.isEnabled = false
    tellomiUpdateSubmitButton()
  }

  /** 改一条：顶上「编辑消息」+ ✕，发送键换成勾（同 TG 两端），到点时刻不变。 */
  private fun tellomiShowEditUi(message: ConversationMessage, setText: Boolean) {
    tellomiEditHeader.isVisible = true
    tellomiSubmitButton.setImageResource(CoreUiR.drawable.symbol_check_24)
    tellomiSubmitButton.contentDescription = getString(R.string.save)
    tellomiUserDismissedPreview = false
    if (setText) {
      tellomiComposeText.filters = tellomiBaseFilters
      val body = message.getDisplayBody(requireContext())
      // 同会话输入框改消息（`InputPanel.enterEditMessageMode`）：剧透在输入框里显示成可编辑的样子
      MessageStyler.convertSpoilersToComposeMode(body)
      tellomiComposeText.setText(body)
      tellomiComposeText.setSelection(tellomiComposeText.text?.length ?: 0)
    }
    // 改的时候只存得下一条消息正文那么长（再长要另存成长文附件，定时消息原地改不了）；原文先放进去再限
    tellomiComposeText.filters = tellomiBaseFilters + ByteLimitInputFilter(SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES)
    if (tellomiEditingHasMedia()) {
      tellomiLinkPreviewView.isVisible = false
    } else if (tellomiKeepsStoredPreview()) {
      tellomiShowStoredPreview()
    }
    tellomiCancelEditOnBack.isEnabled = true
    tellomiUpdateSubmitButton()
  }

  private fun tellomiEdit(message: ConversationMessage) {
    if (viewModel.tellomiEditingMessage == null) {
      // 写了一半的新消息先收起来，改完 / 不改了放回去（同会话输入框改消息）
      viewModel.tellomiStashedText = SpannableString(tellomiComposeText.text ?: "")
    }
    viewModel.tellomiEditingMessage = message
    tellomiShowEditUi(message, setText = true)
    ViewUtil.focusAndShowKeyboard(tellomiComposeText)
  }

  private fun tellomiExitEdit() {
    viewModel.tellomiEditingMessage = null
    val stashed = viewModel.tellomiStashedText ?: ""
    viewModel.tellomiStashedText = null
    tellomiShowNewUi(stashed)
  }

  private fun tellomiEditingHasMedia(): Boolean {
    return (viewModel.tellomiEditingMessage?.messageRecord as? MmsMessageRecord)?.slideDeck?.containsMediaSlide() == true
  }

  /** 新写的要有字；改的要有字，或者那条有图（说明可以删光，同 TG）。 */
  private fun tellomiCanSubmit(): Boolean {
    return tellomiComposeText.textTrimmed.isNotBlank() || (viewModel.tellomiEditingMessage != null && tellomiEditingHasMedia())
  }

  private fun tellomiUpdateSubmitButton() {
    val enabled = tellomiCanSubmit()
    tellomiSubmitButton.isEnabled = enabled
    tellomiSubmitButton.alpha = if (enabled) 1f else 0.4f
  }

  /** 改的时候、还没取到新预览：正文里还是原来那个网址，就沿用原来的预览。 */
  private fun tellomiKeepsStoredPreview(): Boolean {
    val stored = (viewModel.tellomiEditingMessage?.messageRecord as? MmsMessageRecord)?.linkPreviews?.firstOrNull() ?: return false
    return !tellomiUserDismissedPreview && tellomiComposeText.textTrimmed.toString().contains(stored.url)
  }

  private fun tellomiShowStoredPreview() {
    val stored = (viewModel.tellomiEditingMessage?.messageRecord as? MmsMessageRecord)?.linkPreviews?.firstOrNull() ?: return
    tellomiLinkPreviewView.isVisible = true
    tellomiLinkPreviewView.setLinkPreview(Glide.with(this), stored, true)
  }

  /** 现在显示的预览。不取走（`LinkPreviewViewModel.onSend` 会清掉）：选时间取消了，预览还在输入框上面。 */
  private fun tellomiCurrentLinkPreviews(): List<LinkPreview> {
    val state = tellomiLinkPreviewViewModel.getLinkPreviewState().value ?: return emptyList()
    return if (state.linkPreview.isPresent) listOf(state.linkPreview.get()) else emptyList()
  }

  private fun tellomiSubmit() {
    if (!tellomiCanSubmit()) return
    val editing = viewModel.tellomiEditingMessage
    val editingHasMedia = tellomiEditingHasMedia()
    val loaded: List<LinkPreview> = if (editingHasMedia) emptyList() else tellomiCurrentLinkPreviews()
    val linkPreviews: List<LinkPreview>? = when {
      // 有图的不带预览（同排定时那条路），也不动它
      editingHasMedia -> null
      loaded.isNotEmpty() -> loaded
      editing != null && tellomiKeepsStoredPreview() -> null
      else -> emptyList()
    }
    val draft = ScheduledMessagesViewModel.TellomiDraft(
      displayText = SpannableString(tellomiComposeText.text ?: ""),
      body = tellomiComposeText.textTrimmed.toString(),
      mentions = tellomiComposeText.mentions,
      bodyRanges = tellomiComposeText.styling,
      linkPreviews = linkPreviews,
      editMessageId = editing?.messageRecord?.id
    )
    if (editing != null) {
      tellomiSaveEdit(draft)
    } else {
      // 新写的：先选时间（同 TG 两端在定时消息页点发送）；取消了，字和预览都还在输入框里
      viewModel.tellomiPendingDraft = draft
      ViewUtil.hideKeyboard(requireContext(), tellomiComposeText)
      ScheduleMessageTimePickerBottomSheet.showSchedule(childFragmentManager, tellomiConversationRecipient.isSelf)
    }
  }

  private fun tellomiSaveEdit(draft: ScheduledMessagesViewModel.TellomiDraft) {
    disposables += viewModel.tellomiSaveEdit(draft).subscribe(
      { saved ->
        if (!saved) {
          Toast.makeText(requireContext(), R.string.TellomiScheduledMessages__edit_already_sent, Toast.LENGTH_LONG).show()
        }
        tellomiExitEdit()
      },
      { error ->
        // 没存上：留在「编辑」里，改的字还在，可以再点一次
        Log.w(TAG, "Couldn't save an edit to a scheduled message", error)
        Toast.makeText(requireContext(), R.string.TellomiScheduledMessages__edit_failed, Toast.LENGTH_LONG).show()
      }
    )
  }

  override fun onScheduleSend(scheduledTime: Long) {
    val draft = viewModel.tellomiPendingDraft?.takeIf { it.editMessageId == null } ?: return
    viewModel.tellomiPendingDraft = null
    callback.tellomiScheduleText(draft.body, draft.mentions, draft.bodyRanges, draft.linkPreviews ?: emptyList(), scheduledTime)
    // 排好了：输入框和预览清掉（同发出去）
    if (viewModel.tellomiEditingMessage == null) {
      tellomiComposeText.setText("")
    }
    tellomiLinkPreviewViewModel.onSend()
    tellomiUserDismissedPreview = false
  }

  /** @ 选人（同会话输入框：群里才有，选了换成 @名字）。 */
  private fun tellomiInitializeMentions() {
    val mentionsViewModel = ViewModelProvider(requireActivity(), MentionsPickerViewModel.Factory()).get(MentionsPickerViewModel::class.java)

    tellomiComposeText.setInlineQueryChangedListener(object : InlineQueryChangedListener {
      override fun onQueryChanged(inlineQuery: InlineQuery) {
        if (inlineQuery is InlineQuery.Mention && tellomiRecipient?.let { it.isPushV2Group && it.isActiveGroup } == true) {
          tellomiEnsureMentionsContainerFilled()
          mentionsViewModel.onQueryChange(inlineQuery.query)
        } else {
          mentionsViewModel.onQueryChange(null)
        }
      }

      override fun clearQuery() {
        onQueryChanged(InlineQuery.NoQuery)
      }
    })

    Recipient.live(tellomiConversationRecipient.id).observe(viewLifecycleOwner) { recipient ->
      tellomiRecipient = recipient
      mentionsViewModel.onRecipientChange(recipient)
      tellomiComposeText.setMentionValidator { annotations ->
        if (!recipient.isPushV2Group) {
          annotations
        } else {
          val validRecipientIds: Set<String> = recipient.participantIds.map { MentionAnnotation.idToMentionAnnotationValue(it) }.toSet()
          annotations.filter { !validRecipientIds.contains(it.value) }.toList()
        }
      }
    }

    mentionsViewModel.selectedRecipient.observe(viewLifecycleOwner) { recipient ->
      tellomiComposeText.replaceTextWithMention(recipient.getDisplayName(requireContext()), recipient.id)
    }
  }

  private fun tellomiEnsureMentionsContainerFilled() {
    if (childFragmentManager.findFragmentById(R.id.mentions_picker_container) == null) {
      childFragmentManager
        .beginTransaction()
        .replace(R.id.mentions_picker_container, MentionsPickerFragment())
        .commitNowAllowingStateLoss()
    }
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
