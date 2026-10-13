package org.thoughtcrime.securesms.mediapreview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Annotation
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.text.getSpans
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.kotlin.subscribeBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.Media
import org.signal.core.ui.logging.LoggingFragment
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.signal.core.util.requireDrawable
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.AttachmentSaver
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.conversation.mutiselect.forward.TellomiForwardGridBottomSheet
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.withAttachments
import org.thoughtcrime.securesms.databinding.FragmentMediaPreviewBinding
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediapreview.caption.ExpandingCaptionView
import org.thoughtcrime.securesms.mediapreview.mediarail.AlbumScrubberView
import org.thoughtcrime.securesms.mediasend.MediaSendLauncher
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DeleteDialog
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import org.thoughtcrime.securesms.util.OffloadedMediaDialogUtil
import org.thoughtcrime.securesms.util.SaveAttachmentUtil
import org.thoughtcrime.securesms.util.SpanUtil
import org.thoughtcrime.securesms.util.TellomiLivePhoto
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.visible
import java.util.Locale
import kotlin.math.roundToInt
import org.signal.core.ui.R as CoreUiR

class MediaPreviewFragment :
  LoggingFragment(R.layout.fragment_media_preview),
  MediaPreviewPageFragment.Events {

  private val lifecycleDisposable = LifecycleDisposable()
  private val binding by ViewBinderDelegate(FragmentMediaPreviewBinding::bind)
  private val viewModel: MediaPreviewViewModel by viewModels(ownerProducer = {
    requireActivity()
  })

  // Tellomi（#1257，照 Telegram Android）：播放中控件自动收起，见 autoHideControlsTick。
  private val autoHideHandler = Handler(Looper.getMainLooper())
  private var autoHideIdleSinceMs = 0L
  private var isScrubbingVideo = false
  private val autoHideTick = object : Runnable {
    override fun run() {
      autoHideControlsTick()
      autoHideHandler.postDelayed(this, autoHideTickMs)
    }
  }
  private val args: MediaIntentFactory.MediaPreviewArgs by lazy { MediaIntentFactory.requireArguments(requireArguments()) }

  private lateinit var pagerAdapter: MediaPreviewAdapter
  private lateinit var fullscreenHelper: FullscreenHelper

  // Tellomi（#1257）：底部缩略条当前显示的那一组（及它所在的消息）；四角按钮在第一次轻点之前保持隐藏。
  private var currentAlbum: List<Media> = emptyList()
  private var currentAlbumMessageId: Long = -1
  private var chromeHiddenUntilTap = false

  // Tellomi（#1257，owner 2026-09-25，照 Telegram）：这次查看器里的倍速（翻到下一个视频也沿用，不写全局）、拖进度条时的取帧器、倍速面板。
  private var playbackSpeed = 1f
  private var frameExtractor: VideoFrameExtractor? = null
  private var speedPopup: PlaybackSpeedPopup? = null

  /** 本组缩略条在播放控件里（照 Telegram 在进度条下面、按钮上面）。 */
  private val albumScrubber: AlbumScrubberView
    get() = binding.mediaPreviewPlaybackControls.findViewById(R.id.media_preview_album_scrubber)
  private var dbChangeObserver: DatabaseObserver.Observer? = null

  override fun onAttach(context: Context) {
    super.onAttach(context)
    fullscreenHelper = FullscreenHelper(requireActivity(), true)
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    lifecycleDisposable.bindTo(viewLifecycleOwner)
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    initializeViewModel(args)
    initializeToolbar(binding.toolbar)
    bindToolbar(args.fromRecipientId, args.threadRecipientId, args.outgoing, args.showThread, args.date, args.messageId)
    bindInitialPlaybackControls(args)
    bindInitialCaption(args)
    initializeViewPager()
    initializeAlbumRail()
    initializeFullScreenUi()
    hideChromeUntilTapIfNeeded()
    anchorPaddingToBottomInsets(binding.mediaPreviewDetailsContainer)
    lifecycleDisposable +=
      viewModel
        .state
        .distinctUntilChanged { t1, t2 ->
          // All fields except [isInSharedAnimation] and [hdrCapableUris], neither of which bindCurrentState renders.
          (
            t1.mediaRecords == t2.mediaRecords &&
              t1.loadState == t2.loadState &&
              t1.position == t2.position &&
              t1.showThread == t2.showThread &&
              t1.allMediaInAlbumRail == t2.allMediaInAlbumRail &&
              t1.leftIsRecent == t2.leftIsRecent &&
              t1.albums == t2.albums &&
              t1.messageBodies == t2.messageBodies
            )
        }
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe {
          bindCurrentState(it)
        }
  }

  private fun initializeViewModel(args: MediaIntentFactory.MediaPreviewArgs) {
    if (!MediaUtil.isImageType(args.initialMediaType) && !MediaUtil.isVideoType(args.initialMediaType)) {
      Log.w(TAG, "Unsupported media type sent to MediaPreviewFragment, finishing.")
      Snackbar.make(binding.root, R.string.MediaPreviewActivity_unssuported_media_type, Snackbar.LENGTH_LONG)
        .setAction(R.string.MediaPreviewActivity_dismiss_due_to_error) {
          activity?.finish()
        }.show()
    }
    viewModel.initialize(args.showThread, args.allMediaInRail, args.leftIsRecent)
    val sorting = MediaTable.Sorting.deserialize(args.sorting.ordinal)
    val startingAttachmentId = PartAuthority.requireAttachmentId(args.initialMediaUri)
    val threadId = args.threadId
    val appContext = requireContext().applicationContext
    viewModel.fetchAttachments(appContext, startingAttachmentId, threadId, sorting)
    val dbObserver = DatabaseObserver.Observer { viewModel.refetchAttachments(appContext, startingAttachmentId, threadId, sorting) }
    AppDependencies.databaseObserver.registerAttachmentUpdatedObserver(dbObserver)
    this.dbChangeObserver = dbObserver
  }

  @SuppressLint("RestrictedApi")
  private fun initializeToolbar(toolbar: MaterialToolbar) {
    toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    toolbar.setTitleTextAppearance(requireContext(), CoreUiR.style.Signal_Text_TitleMedium)
    toolbar.setSubtitleTextAppearance(requireContext(), CoreUiR.style.Signal_Text_BodyMedium)
    (binding.toolbar.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
    binding.toolbar.inflateMenu(R.menu.media_preview)
  }

  private fun initializeViewPager() {
    binding.mediaPager.offscreenPageLimit = OFFSCREEN_PAGE_LIMIT_DEFAULT
    binding.mediaPager.setPageTransformer(MarginPageTransformer(ViewUtil.dpToPx(24)))
    pagerAdapter = MediaPreviewAdapter(this)
    binding.mediaPager.adapter = pagerAdapter
    binding.mediaPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
      override fun onPageSelected(position: Int) {
        super.onPageSelected(position)
        if (position != viewModel.currentPosition) {
          noteAutoHideActivity()
        }
        viewModel.setCurrentPage(position)
      }
    })
  }

  private fun initializeAlbumRail() {
    // Tellomi（#1257）：拖动缩略条时直接跳到那一张（不做翻页动画，跟手）。
    albumScrubber.onItemSelected = { index ->
      currentAlbum.getOrNull(index)?.let { jumpViewPagerToMedia(it, smooth = false) }
    }
  }

  /**
   * Tellomi（#1257，owner 2026-09-25，对照 Telegram）：打开时什么都不显示（四角按钮、缩略条、k / N、视频的播放键与进度条），
   * 轻点一起出现、再点一起收起；视频照常自动播放。开着读屏时照常显示，不然找不到转发 / 保存。
   */
  private fun hideChromeUntilTapIfNeeded() {
    val touchExploration = requireContext().getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    if (touchExploration) {
      return
    }

    chromeHiddenUntilTap = true
    binding.toolbarLayout.alpha = 0f
    binding.toolbarLayout.visibility = View.INVISIBLE
    binding.mediaPreviewCenterControls.alpha = 0f
    binding.mediaPreviewCenterControls.visibility = View.INVISIBLE
    fullscreenHelper.hideSystemUI()
  }

  private fun initializeFullScreenUi() {
    fullscreenHelper.configureToolbarLayout(binding.toolbarCutoutSpacer, binding.toolbar)
    fullscreenHelper.showAndHideWithSystemUI(requireActivity().window, binding.toolbarLayout, binding.mediaPreviewDetailsContainer, binding.mediaPreviewCenterControls)
  }

  private fun bindCurrentState(currentState: MediaPreviewState) {
    if (currentState.position < 0 && currentState.mediaRecords.isEmpty()) {
      onMediaNotAvailable()
      return
    }
    when (currentState.loadState) {
      MediaPreviewState.LoadState.DATA_LOADED -> bindDataLoadedState(currentState)
      MediaPreviewState.LoadState.MEDIA_READY -> bindMediaReadyState(currentState)
      else -> Unit
    }
  }

  private fun bindDataLoadedState(currentState: MediaPreviewState) {
    val currentPosition = currentState.position

    val backingItems = currentState.mediaRecords.mapNotNull { it.attachment }
    if (backingItems.isEmpty() || currentPosition < 0) {
      onMediaNotAvailable()
      return
    }
    pagerAdapter.updateBackingItems(backingItems)

    if (binding.mediaPager.currentItem != currentPosition) {
      binding.mediaPager.setCurrentItem(currentPosition, false)
    }

    val currentItem: MediaTable.MediaRecord = currentState.mediaRecords[currentPosition]
    bindTextViews(currentItem, currentState.showThread, currentState.messageBodies)
    bindMenuItems(currentItem)
    bindLivePhotoButton(currentItem)
  }

  /**
   * These are binding steps that need a reference to the actual fragment within the pager.
   * This is not available until after a page has been chosen by the ViewPager, and we receive the
   * {@link OnPageChangeCallback}.
   */
  private fun bindMediaReadyState(currentState: MediaPreviewState) {
    val currentPosition: Int = currentState.position
    if (currentState.mediaRecords.isEmpty() || currentPosition < 0) {
      onMediaNotAvailable()
      return
    }

    val currentItem: MediaTable.MediaRecord = currentState.mediaRecords[currentPosition]
    val currentItemTag: String? = pagerAdapter.getFragmentTag(currentPosition)

    childFragmentManager.fragments.forEach { fragment ->
      if (fragment.tag != currentItemTag) {
        (fragment as? MediaPreviewPageFragment)?.pause()
      }
    }

    bindTextViews(currentItem, currentState.showThread, currentState.messageBodies)
    bindMenuItems(currentItem)
    bindLivePhotoButton(currentItem)
    tryBindMediaPreviewPlaybackControls(currentItem, currentPosition)

    bindAlbumRail(currentState.currentAlbum, currentItem)

    crossfadeViewIn(binding.mediaPreviewDetailsContainer)
  }

  private fun bindTextViews(currentItem: MediaTable.MediaRecord, showThread: Boolean, messageBodies: Map<Long, SpannableString>) {
    bindToolbar(currentItem.recipientId, currentItem.threadRecipientId, currentItem.isOutgoing, showThread, currentItem.date, currentItem.attachment?.mmsId)

    val caption = currentItem.attachment?.caption
    val messageId = currentItem.attachment?.mmsId
    if (caption != null) {
      bindCaptionView(SpannableString(caption))
    } else {
      bindCaptionView(messageBodies[messageId] ?: initialBodyForMessage(messageId))
    }
  }

  /**
   * The body passed in via arguments for the initially-opened message. Used as a fallback until the background
   * body resolution populates [MediaPreviewState.messageBodies], so the caption never blanks out after the
   * instant render.
   */
  private fun initialBodyForMessage(messageId: Long?): SpannableString? {
    return if (messageId != null && messageId == args.messageId) {
      args.initialMessageBody?.let { SpannableString(it) }
    } else {
      null
    }
  }

  private fun bindInitialCaption(args: MediaIntentFactory.MediaPreviewArgs) {
    val caption = args.initialCaption
    if (caption != null) {
      bindCaptionView(SpannableString(caption))
    } else {
      bindCaptionView(args.initialMessageBody?.let { SpannableString(it) })
    }
  }

  private fun bindToolbar(fromRecipientId: RecipientId, threadRecipientId: RecipientId, isOutgoing: Boolean, showThread: Boolean, date: Long, messageId: Long?) {
    val title = getTitleText(fromRecipientId, threadRecipientId, isOutgoing, showThread)
    val (subtitle, subtitleContentDesc) = getSubTitleText(date)
    binding.toolbar.title = title
    binding.toolbar.subtitle = subtitle
    binding.toolbar.contentDescription = "$title $subtitleContentDesc"
    if (messageId != null && messageId > 0) {
      binding.toolbar.setOnClickListener { v ->
        lifecycleDisposable += viewModel.jumpToFragment(v.context, messageId).subscribeBy(
          onSuccess = {
            startActivity(it)
            requireActivity().finish()
          },
          onError = {
            Log.e(TAG, "Could not find message position for message ID: $messageId", it)
            Toast.makeText(v.context, R.string.MediaPreviewActivity_error_finding_message, Toast.LENGTH_LONG).show()
          }
        )
      }
    }
  }

  private fun bindCaptionView(displayBody: SpannableString?) {
    val caption: ExpandingCaptionView = binding.mediaPreviewCaption
    if (displayBody.isNullOrEmpty()) {
      caption.visible = false
    } else {
      caption.expandedHeight = calculateExpandedHeight()
      caption.fullCaptionText = displayBody.removeMentionAnnotations()
      caption.visible = true
    }
  }

  private fun calculateExpandedHeight(): Int {
    val height: Int = view?.height ?: return ViewUtil.dpToPx(requireContext(), EXPANDED_CAPTION_HEIGHT_FALLBACK_DP)
    return ((height - binding.toolbar.height - binding.mediaPreviewPlaybackControls.height) * EXPANDED_CAPTION_HEIGHT_PERCENT).roundToInt()
  }

  private fun bindMenuItems(currentItem: MediaTable.MediaRecord) {
    val menu: Menu = binding.toolbar.menu
    if (currentItem.threadId == MediaIntentFactory.NOT_IN_A_THREAD.toLong()) {
      menu.findItem(R.id.delete).isVisible = false
    }

    // Tellomi（#1257）：从会话里打开时可以「回复」（引用整条消息）
    val replyAttachment = currentItem.attachment
    menu.findItem(R.id.reply)?.isVisible = replyAttachment != null &&
      replyAttachment.mmsId > 0 &&
      currentItem.threadId > 0 &&
      currentItem.threadId == MediaPreviewCache.replyTargetThreadId

    binding.toolbar.setOnMenuItemClickListener {
      when (it.itemId) {
        R.id.reply -> replyToCurrentItem(currentItem)
        R.id.share -> currentItem.attachment?.uri?.let { uri ->
          pauseCurrentMediaIfVideo()
          share(uri, currentItem.contentType)
        }
        R.id.edit -> editMediaItem(currentItem)
        R.id.save -> saveToDisk(currentItem)
        R.id.delete -> deleteMedia(currentItem)
        android.R.id.home -> requireActivity().finish()
        else -> return@setOnMenuItemClickListener false
      }
      return@setOnMenuItemClickListener true
    }
  }

  private fun bindInitialPlaybackControls(args: MediaIntentFactory.MediaPreviewArgs) {
    if (!isContentTypeSupported(args.initialMediaType)) {
      return
    }
    val mediaMode: MediaPreviewPlayerControlView.MediaMode = if (args.isVideoGif) {
      MediaPreviewPlayerControlView.MediaMode.IMAGE
    } else {
      MediaPreviewPlayerControlView.MediaMode.fromString(args.initialMediaType)
    }
    binding.mediaPreviewPlaybackControls.setMediaMode(mediaMode)
    bindShareAndForwardButtons(args.threadId, args.initialMediaDataUri, args.initialMediaType)
    crossfadeViewIn(binding.mediaPreviewDetailsContainer)
  }

  private fun bindMediaPreviewPlaybackControls(currentItem: MediaTable.MediaRecord, currentFragment: MediaPreviewPageFragment?) {
    val mediaType: MediaPreviewPlayerControlView.MediaMode = if (currentItem.attachment?.videoGif == true) {
      MediaPreviewPlayerControlView.MediaMode.IMAGE
    } else {
      MediaPreviewPlayerControlView.MediaMode.fromString(currentItem.contentType)
    }
    binding.mediaPreviewPlaybackControls.setMediaMode(mediaType)
    bindShareAndForwardButtons(currentItem.threadId, currentItem.attachment?.uri, currentItem.contentType)
    bindVideoControls(currentItem, mediaType == MediaPreviewPlayerControlView.MediaMode.VIDEO)
    currentFragment?.setBottomButtonControls(binding.mediaPreviewPlaybackControls)
    binding.mediaPreviewCenterControls.bind(binding.mediaPreviewPlaybackControls.player)
    currentFragment?.autoPlayIfNeeded()
  }

  /**
   * Tellomi（#1257，owner 2026-09-25「多个视频点开时完全参考 Telegram」）：中间的播放 / 暂停、倍速（本次查看器沿用）、
   * 删除（相册里先问「这一个 / 全部」）、拖进度条时的预览帧。
   */
  private fun bindVideoControls(currentItem: MediaTable.MediaRecord, isVideo: Boolean) {
    val controls = binding.mediaPreviewPlaybackControls
    binding.mediaPreviewCenterControls.setIsVideo(isVideo)
    controls.playbackSpeed = playbackSpeed
    controls.onPlayerChanged = { player -> binding.mediaPreviewCenterControls.bind(player) }
    controls.setSpeedButtonListener { showSpeedPopup() }
    controls.setDeleteButtonVisible(currentItem.threadId != MediaIntentFactory.NOT_IN_A_THREAD.toLong())
    controls.setDeleteButtonListener { deleteWithAlbumChoice(currentItem) }
    controls.scrubListener = if (isVideo) currentItem.attachment?.attachmentId?.let { scrubPreviewListener(it) } else null
  }

  private fun scrubPreviewListener(attachmentId: AttachmentId): MediaPreviewPlayerControlView.ScrubListener {
    return object : MediaPreviewPlayerControlView.ScrubListener {
      override fun onScrubStart(positionMs: Long, thumbCenterXOnScreen: Float, pillTopOnScreen: Float) {
        isScrubbingVideo = true
        frameExtractor?.release()
        frameExtractor = VideoFrameExtractor(attachmentId, ViewUtil.dpToPx(VideoScrubPreviewView.LONG_SIDE_DP))
        binding.mediaPreviewScrubPreview.showAt(thumbCenterXOnScreen, pillTopOnScreen)
        requestScrubFrame(positionMs)
      }

      override fun onScrubMove(positionMs: Long, thumbCenterXOnScreen: Float, pillTopOnScreen: Float) {
        binding.mediaPreviewScrubPreview.moveTo(thumbCenterXOnScreen, pillTopOnScreen)
        requestScrubFrame(positionMs)
      }

      override fun onScrubStop(positionMs: Long) {
        isScrubbingVideo = false
        noteAutoHideActivity()
        binding.mediaPreviewScrubPreview.dismiss()
        frameExtractor?.release()
        frameExtractor = null
      }
    }
  }

  private fun requestScrubFrame(positionMs: Long) {
    frameExtractor?.request(positionMs) { bitmap ->
      if (view != null) {
        binding.mediaPreviewScrubPreview.setFrame(bitmap)
      }
    }
  }

  private fun showSpeedPopup() {
    speedPopup?.dismiss()
    speedPopup = PlaybackSpeedPopup(requireContext(), playbackSpeed) { speed ->
      playbackSpeed = speed
      if (view != null) {
        binding.mediaPreviewPlaybackControls.playbackSpeed = speed
      }
    }.also { it.showAbove(binding.mediaPreviewPlaybackControls.speedButtonView) }
  }

  /** 相册里的一张：先问「这一张 / 全部 N 张」（照 Telegram）；「全部」走会话里长按删除的同一个对话框（仅自己 / 所有人）。 */
  private fun deleteWithAlbumChoice(currentItem: MediaTable.MediaRecord) {
    val attachment = currentItem.attachment ?: return
    val album = currentAlbum
    if (album.size <= 1 || currentAlbumMessageId != attachment.mmsId) {
      deleteMedia(currentItem)
      return
    }

    pauseCurrentMediaIfVideo()
    val (thisLabel, allLabel) = albumChoiceLabels(currentItem.contentType, album)
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.delete)
      .setItems(arrayOf(thisLabel, allLabel)) { _, which ->
        if (which == 0) {
          deleteMedia(currentItem)
        } else {
          deleteWholeMessage(attachment.mmsId)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun deleteWholeMessage(messageId: Long) {
    viewLifecycleOwner.lifecycleScope.launch {
      val record = withContext(Dispatchers.IO) { SignalDatabase.messages.getMessageRecordOrNull(messageId) } ?: return@launch
      lifecycleDisposable += DeleteDialog.show(requireActivity(), setOf(record))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { (deleted, _) ->
          if (deleted) {
            activity?.finish()
          }
        }
    }
  }

  private fun bindShareAndForwardButtons(threadId: Long, uri: Uri?, contentType: String?) {
    if (uri == null) {
      binding.mediaPreviewPlaybackControls.setForwardButtonListener(null)
      return
    }
    binding.mediaPreviewPlaybackControls.setForwardButtonListener {
      pauseCurrentMediaIfVideo()
      forwardWithAlbumChoice(threadId, uri, contentType)
    }
  }

  /**
   * Tellomi（#1257 / 需求 F-11，owner 2026-09-25）：看的是相册里的一张时，先问「这张 / 全部 N 张」。
   * 「全部」转发整条消息（全部图片与说明），和长按消息转发一样。
   */
  private fun forwardWithAlbumChoice(threadId: Long, uri: Uri, contentType: String?) {
    val album = currentAlbum
    val messageId = currentAlbumMessageId
    if (album.size <= 1 || messageId <= 0) {
      forward(threadId, uri, contentType)
      return
    }

    val (thisLabel, allLabel) = albumChoiceLabels(contentType, album)

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.conversation_selection__menu_forward)
      .setItems(arrayOf(thisLabel, allLabel)) { _, which ->
        if (which == 0) {
          forward(threadId, uri, contentType)
        } else {
          forwardWholeMessage(messageId)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** 「这张图片 / 这个视频」与「全部 N 张 / N 个 / N 项」（转发、删除共用）。 */
  private fun albumChoiceLabels(contentType: String?, album: List<Media>): Pair<String, String> {
    val thisLabel = getString(
      if (MediaUtil.isVideoType(contentType)) R.string.MediaPreviewFragment__forward_this_video else R.string.MediaPreviewFragment__forward_this_photo
    )
    val allLabel = when {
      album.all { MediaUtil.isVideoType(it.contentType) } -> resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_videos, album.size, album.size)
      album.none { MediaUtil.isVideoType(it.contentType) } -> resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_photos, album.size, album.size)
      else -> resources.getQuantityString(R.plurals.MediaPreviewFragment__forward_all_d_items, album.size, album.size)
    }
    return thisLabel to allLabel
  }

  private fun forwardWholeMessage(messageId: Long) {
    val appContext = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
      val conversationMessage = withContext(Dispatchers.IO) {
        val record = SignalDatabase.messages.getMessageRecordOrNull(messageId)?.withAttachments() ?: return@withContext null
        val threadRecipient = SignalDatabase.threads.getRecipientForThreadId(record.threadId) ?: return@withContext null
        ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(appContext, record, threadRecipient)
      }

      if (conversationMessage == null) {
        Toast.makeText(appContext, R.string.MediaPreviewActivity_error_finding_message, Toast.LENGTH_LONG).show()
        return@launch
      }

      MultiselectForwardFragmentArgs.create(requireContext(), conversationMessage.multiselectCollection.toSet()) { args ->
        // Tellomi（#1259）：「全部」也打开头像网格，和「这一张」同一个面板
        TellomiForwardGridBottomSheet.show(childFragmentManager, args)
      }
    }
  }

  private fun pauseCurrentMediaIfVideo() {
    (getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem) as? VideoMediaPreviewPageFragment)?.pause()
  }

  private fun tryBindMediaPreviewPlaybackControls(
    currentItem: MediaTable.MediaRecord,
    currentPosition: Int,
    maxRetries: Int = 5,
    delayMillis: Long = 50L
  ) {
    viewLifecycleOwner.lifecycleScope.launch {
      repeat(maxRetries) { attempt ->
        if (!isActive) return@launch
        val mediaFragment = getMediaPreviewFragmentFromChildFragmentManager(currentPosition)
        bindMediaPreviewPlaybackControls(currentItem, mediaFragment)

        if (mediaFragment != null) return@launch
        delay(delayMillis)
      }
    }
  }

  private fun bindAlbumRail(albumThumbnailMedia: List<Media>, currentItem: MediaTable.MediaRecord) {
    val scrubber = albumScrubber
    if (albumThumbnailMedia.size > 1) {
      currentAlbum = albumThumbnailMedia
      currentAlbumMessageId = currentItem.attachment?.mmsId ?: -1
      val selected = albumThumbnailMedia.indexOfFirst { it.uri == currentItem.attachment?.uri }.coerceAtLeast(0)
      scrubber.setItems(Glide.with(this), albumThumbnailMedia, selected)
      scrubber.visible = true
    } else {
      // owner 2026-09-25：只有一张时不显示缩略条（k / N 也一起不显示）。
      currentAlbum = emptyList()
      currentAlbumMessageId = -1
      scrubber.visible = false
    }
  }

  private fun crossfadeViewIn(view: View, duration: Long = 200): Boolean {
    if (chromeHiddenUntilTap && view == binding.mediaPreviewDetailsContainer) {
      return false
    }

    return if (!view.isVisible && fullscreenHelper.isSystemUiVisible) {
      val viewPropertyAnimator = view.animate()
        .alpha(1f)
        .setDuration(duration)
        .withStartAction {
          view.visibility = View.VISIBLE
        }
      viewPropertyAnimator.interpolator = PathInterpolator(0.17f, 0.17f, 0f, 1f)
      viewPropertyAnimator.start()
      true
    } else {
      false
    }
  }

  private fun getMediaPreviewFragmentFromChildFragmentManager(currentPosition: Int): MediaPreviewPageFragment? {
    return childFragmentManager.findFragmentByTag(pagerAdapter.getFragmentTag(currentPosition)) as? MediaPreviewPageFragment
  }

  private fun jumpViewPagerToMedia(media: Media, smooth: Boolean = true) {
    val position = pagerAdapter.findItemPosition(media)
    binding.mediaPager.setCurrentItem(position, smooth)
  }

  private fun getTitleText(fromRecipientId: RecipientId, threadRecipientId: RecipientId, isOutgoing: Boolean, showThread: Boolean): String {
    val recipient: Recipient = Recipient.live(fromRecipientId).get()
    val defaultFromString: String = if (isOutgoing) {
      getString(R.string.MediaPreviewActivity_you)
    } else {
      recipient.getDisplayName(requireContext())
    }
    if (!showThread) {
      return defaultFromString
    }

    val threadRecipient = Recipient.live(threadRecipientId).get()
    return if (isOutgoing) {
      if (threadRecipient.isSelf) {
        getString(R.string.note_to_self)
      } else {
        getString(R.string.MediaPreviewActivity_you_to_s, threadRecipient.getDisplayName(requireContext()))
      }
    } else {
      if (threadRecipient.isGroup) {
        getString(R.string.MediaPreviewActivity_s_to_s, defaultFromString, threadRecipient.getDisplayName(requireContext()))
      } else {
        getString(R.string.MediaPreviewActivity_s_to_you, defaultFromString)
      }
    }
  }

  private fun getSubTitleText(date: Long): Pair<CharSequence, CharSequence> {
    val (text, contentDesc) = if (date > 0) {
      DateUtils.getExtendedRelativeTimeSpanString(requireContext(), Locale.getDefault(), date)
    } else {
      Pair(getString(R.string.MediaPreviewActivity_draft), getString(R.string.MediaPreviewActivity_draft))
    }
    val builder = SpannableStringBuilder(text)

    val onSurfaceColor = ContextCompat.getColor(requireContext(), CoreUiR.color.signal_colorOnSurface)
    val chevron = requireContext().requireDrawable(R.drawable.ic_chevron_end_24)
    chevron.colorFilter = PorterDuffColorFilter(onSurfaceColor, PorterDuff.Mode.SRC_IN)

    SpanUtil.appendCenteredImageSpan(builder, chevron, 10, 10)
    return Pair(builder, contentDesc)
  }

  private fun anchorPaddingToBottomInsets(viewToAnchor: View) {
    ViewCompat.setOnApplyWindowInsetsListener(viewToAnchor) { view: View, windowInsetsCompat: WindowInsetsCompat ->
      view.setPadding(
        windowInsetsCompat.systemWindowInsetLeft,
        view.paddingTop,
        windowInsetsCompat.systemWindowInsetRight,
        windowInsetsCompat.systemWindowInsetBottom
      )
      windowInsetsCompat
    }
  }

  override fun singleTapOnMedia(): Boolean {
    if (chromeHiddenUntilTap) {
      chromeHiddenUntilTap = false
      fullscreenHelper.showSystemUI()
      return true
    }
    fullscreenHelper.toggleUiVisibility()
    return true
  }

  override fun onMediaNotAvailable() {
    val context = context ?: return
    Toast.makeText(context, R.string.MediaPreviewActivity_media_no_longer_available, Toast.LENGTH_LONG).show()
    activity?.finish()
  }

  override fun onMediaReady() {
    viewModel.setMediaReady()
  }

  /**
   * Tellomi (tellomi/tellomi#1263, spec R-2): on a live photo, the live button top-left (Telegram iOS gallery's LIVE: tap to play
   * looping with sound, tap again to stop). Holding the picture does the same on the page itself.
   */
  private fun bindLivePhotoButton(item: MediaTable.MediaRecord) {
    val button = binding.tellomiLivePhotoButton
    button.isVisible = TellomiLivePhoto.isLivePhoto(item.attachment)
    button.isSelected = (getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem) as? ImageMediaPreviewPageFragment)?.isLiveActive == true
    button.setOnClickListener {
      (getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem) as? ImageMediaPreviewPageFragment)?.toggleLive()
    }
  }

  override fun onLivePhotoActiveChanged(page: MediaPreviewPageFragment, active: Boolean) {
    if (page === getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem)) {
      binding.tellomiLivePhotoButton.isSelected = active
    }
  }

  override fun onPlaying() {
    // 上游是开始播放 2 秒后收起一次；改成 autoHideControlsTick 的空闲计时（照 Telegram：控件露着、正在播、连续 3 秒没被打断才收）。
    noteAutoHideActivity()
  }

  override fun onStopped(tag: String?) {
    if (tag == null) {
      return
    }

    if (pagerAdapter.getFragmentTag(viewModel.currentPosition) == tag) {
      // Tellomi（#1257，照 Telegram）：超过 30 秒、不循环的视频放完了，把控件叫出来（正中是播放键）。
      if (binding.mediaPreviewPlaybackControls.player?.playbackState == Player.STATE_ENDED) {
        chromeHiddenUntilTap = false
        fullscreenHelper.showSystemUI()
      }
    }
  }

  override fun onDestroy() {
    // Tellomi（#1257）：查看器真的关了（不是转屏重建）就复位「从哪个会话打开」，
    // 免得之后从会话设置的媒体条等别处打开同一个会话的查看器也显示「回复」。
    if (activity?.isFinishing == true) {
      MediaPreviewCache.replyTargetThreadId = -1
    }
    super.onDestroy()
    val observer = dbChangeObserver
    if (observer != null) {
      AppDependencies.databaseObserver.unregisterObserver(observer)
      dbChangeObserver = null
    }
  }

  override fun unableToPlayMedia() {
    val context = context ?: return
    Toast.makeText(context, R.string.MediaPreviewActivity_unable_to_play_media, Toast.LENGTH_LONG).show()
    activity?.finish()
  }

  private fun forward(threadId: Long, uri: Uri, contentType: String?) {
    MultiselectForwardFragmentArgs.create(
      context = requireContext(),
      threadId = threadId,
      mediaUri = uri,
      contentType = contentType
    ) { args: MultiselectForwardFragmentArgs ->
      // Tellomi（#1259 F-1 / F-2）：查看器的转发也打开头像网格；查看器固定夜间模式，网格跟着是深色（「这张 / 全部 N 张」在 #1257 查看器里做）
      TellomiForwardGridBottomSheet.show(childFragmentManager, args)
    }
  }

  private fun share(uri: Uri, contentType: String?) {
    val publicUri = PartAuthority.getAttachmentPublicUri(uri)
    val mimeType = Intent.normalizeMimeType(contentType)
    val shareIntent = ShareCompat.IntentBuilder(requireActivity())
      .setStream(publicUri)
      .setType(mimeType)
      .createChooserIntent()
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    if (Build.VERSION.SDK_INT < 34) {
      shareIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(requireContext(), ShareActivity::class.java)))
    }

    try {
      startActivity(shareIntent)
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No activity existed to share the media.", e)
      Toast.makeText(requireContext(), R.string.MediaPreviewActivity_cant_find_an_app_able_to_share_this_media, Toast.LENGTH_LONG).show()
    }
  }

  private fun saveToDisk(mediaItem: MediaTable.MediaRecord) {
    val attachment = mediaItem.attachment
    if (attachment != null && !attachment.hasData && SignalStore.backup.optimizeStorage) {
      OffloadedMediaDialogUtil.showAllOffloaded(requireContext())
      return
    }

    val uri = attachment?.uri
    val contentType = attachment?.contentType
    if (uri == null || contentType == null) {
      Log.w(TAG, "Unable to save attachment with null URI or contentType.")
      return
    }

    val saveAttachment = SaveAttachmentUtil.SaveAttachment(
      uri = uri,
      contentType = contentType,
      date = if (mediaItem.date > 0) mediaItem.date else System.currentTimeMillis(),
      fileName = null
    )

    lifecycleScope.launch {
      AttachmentSaver(this@MediaPreviewFragment).saveAttachments(setOf(saveAttachment))
    }
  }

  private fun deleteMedia(mediaItem: MediaTable.MediaRecord) {
    val attachment: DatabaseAttachment = mediaItem.attachment ?: return

    val messageRecord = SignalDatabase.messages.getMessageRecord(attachment.mmsId)
    val isNoteToSelf = messageRecord.isOutgoing && messageRecord.toRecipient.isSelf

    MaterialAlertDialogBuilder(requireContext()).apply {
      setIcon(R.drawable.symbol_error_triangle_fill_24)
      setTitle(R.string.MediaPreviewActivity_media_delete_confirmation_title)
      setMessage(R.string.MediaPreviewActivity_media_delete_confirmation_message)
      setCancelable(true)
      setNegativeButton(android.R.string.cancel, null)

      val deleteButtonLabel = if (isNoteToSelf) {
        R.string.ConversationFragment_delete
      } else {
        R.string.ConversationFragment_delete_for_me
      }

      setPositiveButton(deleteButtonLabel) { _, _ ->
        lifecycleDisposable += viewModel.localDelete(requireContext(), attachment)
          .observeOn(AndroidSchedulers.mainThread())
          .subscribeBy(
            onComplete = {
              requireActivity().finish()
            },
            onError = {
              Log.e(TAG, "Delete failed!", it)
              Toast.makeText(requireContext(), R.string.MediaPreviewFragment_media_delete_error, Toast.LENGTH_LONG).show()
              requireActivity().finish()
            }
          )
      }

      if (canRemotelyDelete(attachment, messageRecord) && !isNoteToSelf) {
        setNeutralButton(R.string.ConversationFragment_delete_for_everyone) { _, _ ->
          lifecycleDisposable += viewModel.remoteDelete(attachment)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
              onComplete = {
                requireActivity().finish()
              },
              onError = {
                Log.e(TAG, "Delete failed!", it)
                Toast.makeText(requireContext(), R.string.MediaPreviewFragment_media_delete_error, Toast.LENGTH_LONG).show()
                requireActivity().finish()
              }
            )
        }
      }
    }.show()
  }

  private fun canRemotelyDelete(attachment: DatabaseAttachment, messageRecord: MessageRecord): Boolean {
    val mmsId = attachment.mmsId
    val attachmentCount = SignalDatabase.attachments.getAttachmentsForMessage(mmsId).size
    return attachmentCount <= 1 && MessageConstraintsUtil.isValidRemoteDeleteSend(listOf(messageRecord), System.currentTimeMillis())
  }

  /** Tellomi（#1257）：记下「回复哪条消息」，关掉查看器；会话页回到前台时接手（ConversationFragment.onResume）。 */
  private fun replyToCurrentItem(currentItem: MediaTable.MediaRecord) {
    val attachment = currentItem.attachment ?: return
    pauseCurrentMediaIfVideo()
    MediaPreviewCache.pendingReply = MediaPreviewCache.PendingReply(currentItem.threadId, attachment.mmsId)
    requireActivity().finish()
  }

  private fun editMediaItem(currentItem: MediaTable.MediaRecord) {
    val media = currentItem.toMedia()
    if (media == null) {
      val rootView = view
      if (rootView != null) {
        Snackbar.make(rootView, R.string.MediaPreviewFragment_edit_media_error, Snackbar.LENGTH_INDEFINITE).show()
      } else {
        Toast.makeText(requireContext(), R.string.MediaPreviewFragment_edit_media_error, Toast.LENGTH_LONG).show()
      }
      return
    }
    startActivity(MediaSendLauncher.editor(context = requireContext(), media = listOf(media)))
  }

  override fun onResume() {
    super.onResume()
    noteAutoHideActivity()
    autoHideHandler.removeCallbacks(autoHideTick)
    autoHideHandler.postDelayed(autoHideTick, autoHideTickMs)
    (activity as? MediaPreviewActivity)?.onUserTouch = { noteAutoHideActivity() }
  }

  override fun onPause() {
    super.onPause()
    autoHideHandler.removeCallbacks(autoHideTick)
    (activity as? MediaPreviewActivity)?.onUserTouch = null
    getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem)?.pause()
    speedPopup?.dismiss()
  }

  override fun onDestroyView() {
    speedPopup?.dismiss()
    speedPopup = null
    frameExtractor?.release()
    frameExtractor = null
    super.onDestroyView()
    viewModel.onDestroyView()
  }

  private fun noteAutoHideActivity() {
    autoHideIdleSinceMs = SystemClock.uptimeMillis()
  }

  /**
   * Tellomi（#1257，照 Telegram Android `PhotoViewer`：`scheduleActionBarHide` 3 秒，按下取消、抬手重排，子菜单开着不收，开着无障碍不排）：
   * 控件露着时每 250ms 看一次，连续 3 秒没被打断才收起。没在播、正在拖进度条、「⋮」或倍速菜单开着、窗口没焦点（对话框 / 弹出菜单）、
   * 开着 TalkBack 都算打断，计时从头来；碰一下屏幕（`MediaPreviewActivity.dispatchTouchEvent`）也从头来。照片不收（只对正在播的视频）。
   */
  private fun autoHideControlsTick() {
    if (view == null || chromeHiddenUntilTap || !fullscreenHelper.isSystemUiVisible || isAutoHideBlocked()) {
      noteAutoHideActivity()
      return
    }
    if (SystemClock.uptimeMillis() - autoHideIdleSinceMs >= autoHideDelayMs) {
      fullscreenHelper.hideSystemUI()
      noteAutoHideActivity()
    }
  }

  private fun isAutoHideBlocked(): Boolean {
    val player = binding.mediaPreviewPlaybackControls.player
    if (player == null || !player.isPlaying) return true
    if (isScrubbingVideo || speedPopup?.isShowing == true || binding.toolbar.isOverflowMenuShowing) return true
    if (!requireActivity().hasWindowFocus()) return true
    return isTouchExplorationEnabled()
  }

  private fun isTouchExplorationEnabled(): Boolean {
    touchExplorationOverrideForTesting?.let { return it }
    return requireContext().getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
  }

  companion object {
    /** 播放中控件自动收起：产品里 3 秒、每 250ms 看一次；测试（AlbumViewerScreenshots）可以改。 */
    var autoHideDelayMs = 3_000L
    var autoHideTickMs = 250L
    var touchExplorationOverrideForTesting: Boolean? = null

    private const val EXPANDED_CAPTION_HEIGHT_FALLBACK_DP = 400
    private const val EXPANDED_CAPTION_HEIGHT_PERCENT: Float = 0.7F

    private val TAG = Log.tag(MediaPreviewFragment::class.java)

    const val ARGS_KEY: String = "args"

    @JvmStatic
    fun isContentTypeSupported(contentType: String?): Boolean {
      return MediaUtil.isImageType(contentType) || MediaUtil.isVideoType(contentType)
    }
  }
}

private fun SpannableString.removeMentionAnnotations(): CharSequence {
  val spans: Array<out Annotation> = this.getSpans()
  spans.forEach {
    if (MentionAnnotation.isMentionAnnotation(it)) {
      this.removeSpan(it)
    }
  }
  return this
}
