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
  val mentionCount: Int = 0,
  /**
   * Tellomi (chat-telegram-parity F01): my messages with reactions I haven't seen in the chat yet, oldest first
   * ([org.thoughtcrime.securesms.reactions.TellomiUnseenReactions]).
   */
  val unseenReactionMessageIds: List<Long> = emptyList()
) {
  val hasMentions: Boolean
    get() = mentionCount > 0

  val showScrollButtons: Boolean
    get() = !hideScrollButtonsForReactionOverlay && (showScrollButtonsForScrollPosition || (!willScrollToBottomOnNewMessage && unreadCount > 0))

  /**
   * Tellomi (chat-telegram-parity F01/F02): the mention button shows while there are unread mentions, wherever the list is
   * scrolled — both Telegram apps do (iOS `ChatHistoryNavigationButtons` on `mentionCount != 0`, Android
   * `showMentionDownButton` on `newMentionsCount > 0`). Signal only showed it together with the scroll-to-bottom button.
   */
  val showMentionButton: Boolean
    get() = !hideScrollButtonsForReactionOverlay && hasMentions

  val unseenReactionCount: Int
    get() = unseenReactionMessageIds.size

  /** Tellomi (chat-telegram-parity F01): same rule as [showMentionButton] (Android `updateReactionsMentionButton`, iOS `reactionsCount != 0`). */
  val showReactionButton: Boolean
    get() = !hideScrollButtonsForReactionOverlay && unseenReactionCount > 0
}
