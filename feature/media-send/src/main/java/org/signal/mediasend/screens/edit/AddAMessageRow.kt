/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.models.media.Media
import org.signal.core.ui.compose.ColorProfiles
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags

/**
 * The circle the arrow is drawn in. [IconButtons.IconButton] keeps the 48dp of layout that
 * [androidx.compose.material3.minimumInteractiveComponentSize] reserves around it, so what a finger can hit stays a full
 * touch target wide while the button reads as 40dp.
 */
private val NextButtonSize = 40.dp

/**
 * The bar itself, stated rather than left to what is inside it: the icon buttons it carries each reserve a 48dp touch
 * target, which would otherwise make the bar 4dp taller than it is meant to be.
 */
private val InputBarHeight = 44.dp

/**
 * Because we need to be able to support stuff like mentions, styled text, and custom emoji, we need to allow
 * the users of this feature to inject their own text-field.
 */
val LocalAddAMessageRowTextField = compositionLocalOf<@Composable (CharSequence, Modifier) -> Unit> {
  { message, modifier ->
    Text(
      text = message.toString(),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier
    )
  }
}

/**
 * @param recipientChatColor The chat color of the single recipient this media is headed to, or null when the destination
 *   is still to be chosen. Non-null makes the trailing button a chat-color-tinted send button rather than a themed
 *   "next" arrow.
 * @param captionAboveMedia Tellomi（tellomi/tellomi#1263，ADR-0068 §4.7 P3）：长按发送菜单顶上的「说明文字上移 / 下移」；
 *   null = 不给，true = 说明现在在上方（显示「下移」）。照 Telegram 两端长按发送菜单的第一项（iOS
 *   `ChatSendMessageContextScreen` 的 captionPosition、安卓 `ChatAttachAlert` 的 CaptionAbove / CaptionBelow 开关），点了菜单不收。
 * @param sendPreviewMedia Tellomi（tellomi/tellomi#1519 M-5，照 Telegram）：要发的媒体；给了，长按发送就是整屏的
 *   「发出去是什么样」预览加菜单（[SendPreviewOverlay]），不给还是原来贴着发送键的小菜单。
 */
@Composable
fun AddAMessageRow(
  message: CharSequence?,
  onEvent: (MediaEditScreenEvents) -> Unit,
  onNextClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  sendEnabled: Boolean = enabled,
  canScheduleSend: Boolean = false,
  viewOnceAvailable: Boolean = false,
  viewOnce: Boolean = false,
  isReply: Boolean = false,
  recipientChatColor: Color? = null,
  captionAboveMedia: Boolean? = null,
  sendPreviewMedia: List<Media>? = null
) {
  var previewShown by remember { mutableStateOf(false) }
  var sendButtonBounds by remember { mutableStateOf(Rect.Zero) }

  Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp))
        .weight(1f)
        .height(InputBarHeight)
        .then(
          if (viewOnce) {
            // A view-once send cannot carry a body, so the row becomes a static label rather than an entry point.
            Modifier
          } else {
            Modifier.clickable(enabled = enabled, onClickLabel = stringResource(if (isReply) R.string.AddAMessageRow__add_a_reply else R.string.AddAMessageRow__add_a_message), onClick = { onEvent(MediaEditScreenEvents.AddMessageClick()) }, role = Role.Button)
          }
        )
    ) {
      if (viewOnce) {
        Text(
          text = stringResource(R.string.AddAMessageRow__view_once_media),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp)
        )
      } else {
        IconButtons.IconButton(
          enabled = enabled,
          onClick = { onEvent(MediaEditScreenEvents.AddMessageClick(startWithEmojiKeyboard = true)) }
        ) {
          Icon(
            painter = SignalIcons.Emoji.painter,
            contentDescription = stringResource(R.string.AddAMessageRow__open_emoji_keyboard)
          )
        }

        LocalAddAMessageRowTextField.current(
          message?.takeIf { it.isNotBlank() } ?: stringResource(if (isReply) R.string.AddAMessageRow__add_a_reply else R.string.AddAMessageRow__message),
          Modifier
            .weight(1f)
            .height(InputBarHeight)
            .padding(end = if (viewOnceAvailable) 0.dp else 16.dp, top = 10.dp, bottom = 10.dp)
        )
      }

      if (viewOnceAvailable) {
        IconButtons.IconButton(
          enabled = enabled,
          onClick = { onEvent(MediaEditScreenEvents.ToggleViewOnce) }
        ) {
          Icon(
            painter = if (viewOnce) SignalIcons.ViewOnce.painter else SignalIcons.ViewOnceInfinite.painter,
            contentDescription = stringResource(R.string.AddAMessageRow__toggle_view_once)
          )
        }
      }
    }

    Box(
      modifier = Modifier
        .onGloballyPositioned { sendButtonBounds = it.boundsOnScreen() }
        // 预览开着时发送键画在预览里（可能挪上去了），这里的先藏起来（同 Telegram `anchorSendButton.setAlpha(0f)`）
        .alpha(if (previewShown) 0f else 1f)
    ) {
      val scheduleSendMenuController = remember { DropdownMenus.MenuController() }

      val nextButtonColor = recipientChatColor ?: MaterialTheme.colorScheme.primaryContainer
      val onNextButtonColor = if (recipientChatColor != null) SignalTheme.colors.colorOnCustom else MaterialTheme.colorScheme.onPrimaryContainer
      val showPreview = !sendPreviewMedia.isNullOrEmpty()

      NextButton(
        enabled = sendEnabled,
        onClick = onNextClick,
        onLongClick = when {
          !canScheduleSend && captionAboveMedia == null -> null
          showPreview -> ({ previewShown = true })
          else -> scheduleSendMenuController::show
        },
        containerColor = nextButtonColor,
        contentColor = onNextButtonColor,
        isSend = recipientChatColor != null,
        modifier = Modifier.testTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON)
      )

      if (previewShown && sendPreviewMedia != null) {
        SendPreviewOverlay(
          media = sendPreviewMedia,
          // 一次性查看的不带说明
          message = if (viewOnce) null else message?.toString(),
          bubbleColor = nextButtonColor,
          onBubbleColor = onNextButtonColor,
          sendButtonBounds = sendButtonBounds,
          captionAboveMedia = captionAboveMedia,
          showScheduleOptions = canScheduleSend,
          onToggleCaptionPosition = { onEvent(MediaEditScreenEvents.ToggleCaptionAboveMedia) },
          onScheduleOption = { onEvent(MediaEditScreenEvents.ScheduleSendClick(it)) },
          onDismiss = { previewShown = false }
        ) { hide ->
          NextButton(
            enabled = sendEnabled,
            onClick = {
              hide()
              onNextClick()
            },
            onLongClick = null,
            containerColor = nextButtonColor,
            contentColor = onNextButtonColor,
            isSend = recipientChatColor != null,
            modifier = Modifier.testTag(TestTags.SEND_PREVIEW_SEND_BUTTON)
          )
        }
      }

      ScheduleSendMenu(
        controller = scheduleSendMenuController,
        onOptionClick = { onEvent(MediaEditScreenEvents.ScheduleSendClick(it)) },
        showScheduleOptions = canScheduleSend,
        captionAboveMedia = captionAboveMedia,
        onToggleCaptionPosition = { onEvent(MediaEditScreenEvents.ToggleCaptionAboveMedia) }
      )
    }
  }
}

@Composable
private fun NextButton(
  enabled: Boolean,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  containerColor: Color,
  contentColor: Color,
  isSend: Boolean,
  modifier: Modifier = Modifier
) {
  IconButtons.IconButton(
    enabled = enabled,
    onClick = onClick,
    size = NextButtonSize,
    onLongClick = onLongClick,
    onLongClickLabel = stringResource(R.string.AddAMessageRow__schedule_send),
    colors = IconButtons.iconButtonColors(
      containerColor = containerColor,
      contentColor = contentColor,
      disabledContainerColor = ColorProfiles.disabledContainer(containerColor),
      disabledContentColor = ColorProfiles.disabledContent()
    ),
    modifier = modifier.padding(start = 12.dp)
  ) {
    Icon(
      painter = if (isSend) SignalIcons.SendFill.painter else SignalIcons.ArrowEnd.painter,
      contentDescription = stringResource(if (isSend) R.string.AddAMessageRow__send else R.string.AddAMessageRow__next)
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {}
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowViewOnceAvailablePreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      viewOnceAvailable = true
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowViewOncePreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      viewOnceAvailable = true,
      viewOnce = true
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowReplyPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      isReply = true
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowKnownRecipientPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      recipientChatColor = Color(0xFF3B7845)
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowDisabledPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      enabled = false
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowDisabledKnownRecipientPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      enabled = false,
      recipientChatColor = Color(0xFF3B7845)
    )
  }
}

/** A chat color that starts out pale, where a plain interpolation towards the chrome would barely move. */
@DayNightPreviews
@Composable
private fun AddAMessageRowDisabledPaleRecipientPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = null,
      onEvent = {},
      onNextClick = {},
      enabled = false,
      recipientChatColor = Color(0xFFF5E0A0)
    )
  }
}

@DayNightPreviews
@Composable
private fun AddAMessageRowLongContentPreview() {
  Previews.Preview {
    AddAMessageRow(
      message = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
      onEvent = {},
      onNextClick = {}
    )
  }
}
