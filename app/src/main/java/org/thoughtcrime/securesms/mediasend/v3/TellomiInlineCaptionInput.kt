/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.text.InputType
import android.text.SpannableString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.signal.core.util.ByteLimitInputFilter
import org.signal.core.util.EditTextUtil
import org.signal.core.util.StringUtil
import org.signal.mediasend.screens.edit.TellomiInlineCaption
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.v2.ui.warning.tellomiGuardAgainstRecoveryKeyPaste
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQuery
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryChangedListener
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryResultsController
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryViewModel
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerFragment
import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerViewModel
import org.thoughtcrime.securesms.keyboard.KeyboardPage
import org.thoughtcrime.securesms.keyboard.KeyboardPagerViewModel
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardEvent
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardEventViewModel
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.MessageUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（两端对齐清单 P-4 第一期）：[TellomiInlineCaption] 的 app 这一半——附件 Sheet 相册页底栏的说明框就地输入。
 *
 * 能做的同上游的整屏对话框 [org.thoughtcrime.securesms.mediasend.v2.review.AddMessageDialogFragment]，用的也是同一套零件：
 * - [ComposeText]：@ 提及、样式、自定义表情；长度限制（消息正文的字节上限；开着快拍时再加快拍说明的字数上限）；
 *   粘贴自己的恢复密钥先提醒（[tellomiGuardAgainstRecoveryKeyPaste]）；
 * - 表情键换出表情面板（[MediaKeyboard]，只放表情页；高度用刚才键盘的高度），再点换回键盘；面板里的搜索照常用；
 * - 「:关键词」联想表情（[InlineQueryResultsController]）；群里打「@」底栏上面出选人列表（[MentionsPickerFragment]）。
 *
 * 照 Telegram 两端（只读，代码是自己写的）：安卓 `ChatAttachAlert` 的 `commentTextView`（`EditTextEmoji`），iOS
 * `AttachmentTextInputPanelNode`。一次性查看的开关仍在底栏里（同上游）。
 */
class TellomiInlineCaptionInput(
  private val activity: FragmentActivity,
  private val recipientId: RecipientId?
) : TellomiInlineCaption {

  private var hasFocus by mutableStateOf(false)
  private var emojiPanelVisible by mutableStateOf(false)
  private var mentionQueryActive by mutableStateOf(false)

  /** 上次键盘的高度（键盘上沿到导航栏），表情面板照它垫；还没见过键盘就用 [DEFAULT_PANEL_HEIGHT]。 */
  private var keyboardHeightPx by mutableIntStateOf(0)

  private var composeText: ComposeText? = null
  private var mediaKeyboard: MediaKeyboard? = null
  private var recipient: Recipient? = null

  private val mentionsViewModel: MentionsPickerViewModel by lazy { ViewModelProvider(activity, MentionsPickerViewModel.Factory())[MentionsPickerViewModel::class.java] }
  private val inlineQueryViewModel: InlineQueryViewModel by lazy { ViewModelProvider(activity)[InlineQueryViewModel::class.java] }
  private val emojiEvents: EmojiKeyboardEventViewModel by lazy { ViewModelProvider(activity)[EmojiKeyboardEventViewModel::class.java] }
  private val keyboardPagerViewModel: KeyboardPagerViewModel by lazy { ViewModelProvider(activity)[KeyboardPagerViewModel::class.java] }

  override val isEditing: Boolean
    get() = hasFocus || emojiPanelVisible

  override val isEmojiPanelVisible: Boolean
    get() = emojiPanelVisible

  override val isMentionQueryActive: Boolean
    get() = mentionQueryActive

  @Composable
  override fun Field(message: CharSequence?, hint: String, enabled: Boolean, onMessageChange: (CharSequence?) -> Unit, modifier: Modifier) {
    val currentOnMessageChange by rememberUpdatedState(onMessageChange)
    // 说明框自己的生命周期：底栏收起再出来就是一个新的说明框，联想表情、粘贴提醒的订阅跟着它走，不挂在整个 Activity 上
    val fieldLifecycle = remember { FieldLifecycle() }
    DisposableEffect(fieldLifecycle) {
      onDispose { fieldLifecycle.destroy() }
    }

    AndroidView(
      factory = { context ->
        ComposeText(context).apply {
          id = View.generateViewId()
          background = null
          setTextAppearance(CoreUiR.style.Signal_Text_Body)
          setTextColor(ContextCompat.getColor(context, CoreUiR.color.signal_colorOnSurface))
          setHintTextColor(ContextCompat.getColor(context, CoreUiR.color.signal_colorOnSurfaceVariant))
          gravity = Gravity.CENTER_VERTICAL or Gravity.START
          maxLines = MAX_LINES
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
          imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
          val vertical = ViewUtil.dpToPx(10)
          setPadding(0, vertical, 0, vertical)
          this.hint = hint
          setText(message)
          filters += ByteLimitInputFilter(MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES)
          if (Stories.isFeatureEnabled()) {
            EditTextUtil.addGraphemeClusterLimitFilter(this, Stories.MAX_CAPTION_SIZE)
          }
          doAfterTextChanged { text -> currentOnMessageChange(text?.let { SpannableString(it) }) }
          addOnFocusChangeListener { _, focused -> hasFocus = focused }
          tellomiGuardAgainstRecoveryKeyPaste(activity.supportFragmentManager, fieldLifecycle) {
            ViewUtil.focusAndShowKeyboard(this)
          }
          setUpQueries(this, fieldLifecycle)
          composeText = this
          fieldLifecycle.resume()
        }
      },
      update = { view ->
        view.isEnabled = enabled
        view.hint = hint
        // 正在输入时不拿流程里的说明回写（流程会去掉首尾空白，回写会吃掉刚打的空格）；没在输入时跟着它变（比如发送页改了）
        if (!view.hasFocus() && !emojiPanelVisible) {
          val current = StringUtil.trimSequence(view.text ?: "").toString()
          if (current != (message?.toString() ?: "")) {
            view.setText(message)
          }
        }
      },
      onRelease = { view ->
        view.setInlineQueryChangedListener(null)
        view.setMentionValidator(null)
        view.setOnPasteListener(null)
        if (composeText === view) {
          composeText = null
          hasFocus = false
          mentionQueryActive = false
        }
      },
      modifier = modifier
    )
  }

  /** 「:关键词」联想表情、「@」选人。 */
  private fun setUpQueries(view: ComposeText, lifecycleOwner: LifecycleOwner) {
    InlineQueryResultsController(inlineQueryViewModel, view, activity.findViewById(android.R.id.content), view, lifecycleOwner)

    view.setInlineQueryChangedListener(object : InlineQueryChangedListener {
      override fun onQueryChanged(inlineQuery: InlineQuery) {
        when (inlineQuery) {
          is InlineQuery.Mention -> {
            val inGroup = recipient?.let { it.isPushV2Group && it.isActiveGroup } == true
            mentionQueryActive = inGroup
            mentionsViewModel.onQueryChange(if (inGroup) inlineQuery.query else null)
            inlineQueryViewModel.onQueryChange(inlineQuery)
          }

          is InlineQuery.Emoji -> {
            mentionQueryActive = false
            mentionsViewModel.onQueryChange(null)
            inlineQueryViewModel.onQueryChange(inlineQuery)
          }

          is InlineQuery.NoQuery -> {
            mentionQueryActive = false
            mentionsViewModel.onQueryChange(null)
            inlineQueryViewModel.onQueryChange(inlineQuery)
          }
        }
      }

      override fun clearQuery() {
        onQueryChanged(InlineQuery.NoQuery)
      }
    })

    val selection: Disposable = inlineQueryViewModel.selection
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { replacement -> if (view.hasFocus()) view.replaceText(replacement) }
    lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
      override fun onDestroy(owner: LifecycleOwner) {
        selection.dispose()
      }
    })

    // 选人列表选了一个：换成 @名字（上游对话框开着时它也在收，所以只认有焦点的这个）
    mentionsViewModel.selectedRecipient.observe(lifecycleOwner) { selected ->
      if (view.hasFocus()) {
        view.replaceTextWithMention(selected.getDisplayName(activity), selected.id)
      }
    }

    val id = recipientId ?: return
    Recipient.live(id).observe(lifecycleOwner) { resolved ->
      recipient = resolved
      mentionsViewModel.onRecipientChange(resolved)
      view.setMentionValidator { annotations ->
        if (!resolved.isPushV2Group) {
          annotations
        } else {
          val validRecipientIds: Set<String> = resolved.participantIds.map { MentionAnnotation.idToMentionAnnotationValue(it) }.toSet()
          annotations.filter { !validRecipientIds.contains(it.value) }.toList()
        }
      }
    }
  }

  override fun onEmojiToggleClick() {
    val view = composeText ?: return
    if (emojiPanelVisible) {
      emojiPanelVisible = false
      ViewUtil.focusAndShowKeyboard(view)
    } else {
      keyboardPagerViewModel.setOnlyPage(KeyboardPage.EMOJI)
      // 光标留在说明框里（表情插在这里），键盘收起，面板垫在同样的位置
      view.requestFocus()
      emojiPanelVisible = true
      ViewUtil.hideKeyboard(activity, view)
    }
  }

  override fun stopEditing() {
    emojiPanelVisible = false
    mentionQueryActive = false
    composeText?.let { view ->
      ViewUtil.hideKeyboard(activity, view)
      view.clearFocus()
    }
  }

  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  override fun EmojiPanel(modifier: Modifier) {
    val density = LocalDensity.current
    val imeHeight = WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(imeHeight) {
      if (imeHeight > keyboardHeightPx) {
        keyboardHeightPx = imeHeight
      }
    }

    // 又点了说明框、键盘出来了：面板让位（搜表情时键盘和面板同时在，不收）
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
      if (imeVisible && emojiPanelVisible && mediaKeyboard?.isEmojiSearchMode != true) {
        emojiPanelVisible = false
      }
    }

    if (!emojiPanelVisible) {
      return
    }

    val panelHeightPx = keyboardHeightPx.takeIf { it > 0 } ?: with(density) { DEFAULT_PANEL_HEIGHT.roundToPx() }

    // 面板开着时，表情、退格、搜索这几件事从 Activity 转过来（同上游对话框的那个通道，对话框这时不在）
    LaunchedEffect(Unit) {
      emojiEvents.events.collect { event ->
        when (event) {
          is EmojiKeyboardEvent.EmojiInsert -> composeText?.insertEmoji(event.emoji)
          is EmojiKeyboardEvent.EmojiKeyEvent -> composeText?.dispatchKeyEvent(event.keyEvent)
          EmojiKeyboardEvent.OpenEmojiSearch -> mediaKeyboard?.onOpenEmojiSearch()
          EmojiKeyboardEvent.CloseEmojiSearch -> mediaKeyboard?.onCloseEmojiSearch()
        }
      }
    }

    AndroidView(
      factory = { context ->
        MediaKeyboard(context).apply {
          id = View.generateViewId()
          layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeightPx)
          // 同上游 XML 里的样子：先藏着，接到窗口上再 show（里面的表情页是个 Fragment，要等容器在窗口里才放得进去）
          visibility = View.GONE
          // 搜表情那一页靠 KeyboardAwareLinearLayout 看键盘在不在，键盘一收就自己关。Compose 交给 AndroidView 的 insets 按它的位置
          // 扣过，面板垫在键盘上面、和键盘不重叠，看到的键盘高度就是 0，一打开就关了；这里给它整个窗口的 insets（同上游对话框里的样子）
          ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val windowInsets = ViewCompat.getRootWindowInsets(view) ?: insets
            for (i in 0 until (view as ViewGroup).childCount) {
              ViewCompat.dispatchApplyWindowInsets(view.getChildAt(i), windowInsets)
            }
            WindowInsetsCompat.CONSUMED
          }
          setFragmentManager(activity.supportFragmentManager)
          doOnAttach { show(panelHeightPx, true) }
          mediaKeyboard = this
        }
      },
      update = { view ->
        if (view.isInitialised && !view.isEmojiSearchMode && view.layoutParams.height != panelHeightPx) {
          view.show(panelHeightPx, true)
        }
      },
      onRelease = { view ->
        if (mediaKeyboard === view) {
          mediaKeyboard = null
        }
      },
      modifier = modifier
    )
  }

  @Composable
  override fun MentionsOverlay(modifier: Modifier) {
    AndroidFragment(clazz = MentionsPickerFragment::class.java, modifier = modifier)
  }

  /** 说明框的生命周期：建好时 RESUMED，从界面上拿掉时 DESTROYED。 */
  private class FieldLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
      get() = registry

    fun resume() {
      if (registry.currentState != Lifecycle.State.DESTROYED) {
        registry.currentState = Lifecycle.State.RESUMED
      }
    }

    fun destroy() {
      if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
        registry.currentState = Lifecycle.State.DESTROYED
      }
    }
  }

  companion object {
    private const val MAX_LINES = 5
    private val DEFAULT_PANEL_HEIGHT = 260.dp
  }
}
