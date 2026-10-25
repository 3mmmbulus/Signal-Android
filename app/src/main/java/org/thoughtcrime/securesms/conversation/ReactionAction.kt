/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

/**
 * What the long press menu can ask for.
 */
enum class ReactionAction {
  REPLY,
  EDIT,
  FORWARD,
  RESEND,
  DOWNLOAD,
  COPY,
  MULTISELECT,
  PAYMENT_DETAILS,
  VIEW_INFO,
  DELETE,
  END_POLL,
  PIN_MESSAGE,
  UNPIN_MESSAGE,
  STAR_MESSAGE,
  UNSTAR_MESSAGE,

  /** Tellomi：长按「收藏」，直接存进「我的收藏」（tellomi/tellomi#1174）。 */
  TELLOMI_SAVE_TO_SAVED_MESSAGES,

  /** Tellomi（审计 4.4）：「选择文字」，用系统手柄选一段再复制。 */
  TELLOMI_SELECT_TEXT
}
