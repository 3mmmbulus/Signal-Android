/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.conversation.shared

import org.signal.uicomponents.recentmediarail.RecentMediaRailAction
import org.thoughtcrime.securesms.components.settings.conversation.ConversationSettingsAction
import org.thoughtcrime.securesms.util.TellomiSpoiler

/**
 * Translates something the shared media rail decided needs doing into the settings action that carries it out, or null
 * if the media it refers to has since gone away.
 */
fun RecentMediaRailAction.toConversationSettingsAction(loader: SharedMediaLoader, threadId: Long): ConversationSettingsAction? {
  return when (this) {
    is RecentMediaRailAction.OpenMedia -> {
      loader.recordAt(index)?.let { record ->
        // Tellomi (tellomi/tellomi#1263, spec R-1): opening a covered tile is choosing to see it, so it is uncovered first
        record.attachment?.takeIf { TellomiSpoiler.isCovered(it) }?.let { TellomiSpoiler.reveal(it) }
        ConversationSettingsAction.ShowMediaPreview(record, leftToRight, bounds)
      }
    }
    is RecentMediaRailAction.DownloadMedia -> {
      loader.recordAt(index)?.let { ConversationSettingsAction.DownloadMedia(it) }
    }
    RecentMediaRailAction.ShowMediaUnavailable -> {
      ConversationSettingsAction.ShowMediaNotSentYet
    }
    RecentMediaRailAction.OpenAllMedia -> {
      ConversationSettingsAction.ShowMediaOverview(threadId)
    }
  }
}
