/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * State information used to display the scroll to next mention and scroll to bottom buttons.
 */
data class ConversationScrollButtonState(
  val hideScrollButtonsForReactionOverlay: Boolean = false,
  val showScrollButtonsForScrollPosition: Boolean = false,
  val willScrollToBottomOnNewMessage: Boolean = true,
  val unreadCount: Int = 0,
  /** Tellomi (chat-telegram-parity F02): unread @mentions, shown on the mention button like both Telegram apps. */
  val mentionCount: Int = 0
) {
  val hasMentions: Boolean
    get() = mentionCount > 0

  val showScrollButtons: Boolean
    get() = !hideScrollButtonsForReactionOverlay && (showScrollButtonsForScrollPosition || (!willScrollToBottomOnNewMessage && unreadCount > 0))
}
