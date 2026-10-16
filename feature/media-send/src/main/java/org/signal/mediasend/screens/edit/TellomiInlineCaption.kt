/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Tellomi（两端对齐清单 P-4 第一期）：附件 Sheet 相册页底栏的说明框就地输入——点说明框直接弹键盘，表情键在键盘和表情面板之间切换，
 * 不再弹上游的整屏对话框（`AddMessageDialogFragment`）。照 Telegram 两端：安卓 `ChatAttachAlert` 的 `commentTextView`
 * （`EditTextEmoji`，表情键换出表情面板），iOS 附件菜单底下的 `AttachmentTextInputPanelNode`；同 Tellomi iOS 选图网格的说明框。
 *
 * 富文本、提及、自定义表情、表情面板都在 app 里（和上游对话框同一套），feature 模块不依赖 app，所以由 app 注入
 * （同 [LocalAddAMessageRowTextField]、`AttachmentExtraPages` 的路子）。没注入（别的入口打开的媒体流程、编辑页、相册查看器）
 * 就还是点了弹对话框。
 */
interface TellomiInlineCaption {

  /** 正在输入：说明框有焦点，或者表情面板开着。这时 Sheet 展开到全屏、返回键先收起输入。 */
  val isEditing: Boolean

  /** 表情面板开着（键盘收着，面板垫在底栏下面同样高的位置）。 */
  val isEmojiPanelVisible: Boolean

  /** 群里打了「@」：底栏上面要盖一层选人列表（[MentionsOverlay]）。 */
  val isMentionQueryActive: Boolean

  /**
   * 说明框本身（放在底栏里原来那行字的位置）。
   *
   * @param message 流程里现在的说明（聊天输入框里带过来的、上次写的）；说明框没在输入时跟着它变。
   * @param onMessageChange 每打一个字交回去（带 @、样式的 span）。
   */
  @Composable
  fun Field(message: CharSequence?, hint: String, enabled: Boolean, onMessageChange: (CharSequence?) -> Unit, modifier: Modifier)

  /** 表情键：键盘 ↔ 表情面板。 */
  fun onEmojiToggleClick()

  /** 收起键盘和表情面板（返回键、发出去之后）。 */
  fun stopEditing()

  /** 表情面板（放在底栏下面，[isEmojiPanelVisible] 时有高度）。 */
  @Composable
  fun EmojiPanel(modifier: Modifier)

  /** 「@」选人列表，贴着底栏上沿（[isMentionQueryActive] 时才放进来，免得挡住网格的触摸）。 */
  @Composable
  fun MentionsOverlay(modifier: Modifier)
}

/** app 注入的就地说明框；null = 照上游点了弹对话框。 */
val LocalTellomiInlineCaption = staticCompositionLocalOf<TellomiInlineCaption?> { null }
