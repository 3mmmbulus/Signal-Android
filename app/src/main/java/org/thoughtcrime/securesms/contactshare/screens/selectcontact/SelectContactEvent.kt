/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.contactshare.screens.selectcontact

import android.net.Uri
import org.thoughtcrime.securesms.contacts.index.ContactIndexRecord

sealed interface SelectContactEvent {
  data object Initialize : SelectContactEvent

  data object BackClicked : SelectContactEvent

  data class QueryChanged(val query: String) : SelectContactEvent {
    /** What the user typed to find someone is as revealing as the name it matches. */
    override fun toString(): String = "QueryChanged(length=${query.length})"
  }

  data class ContactClicked(val contact: ContactIndexRecord) : SelectContactEvent

  data object AllowContactsAccessClicked : SelectContactEvent

  data object DismissContactsAccessClicked : SelectContactEvent

  data object OpenSystemContactPickerClicked : SelectContactEvent

  /** [phoneUri] points at one phone row, and is null when the picker was backed out of. */
  data class SystemContactPicked(val phoneUri: Uri?) : SelectContactEvent {
    /** The uri identifies the person, so only whether there was one gets logged. */
    override fun toString(): String = "SystemContactPicked(picked=${phoneUri != null})"
  }

  data object LearnMoreClicked : SelectContactEvent

  /**
   * Tellomi（2026-10-16）：附件 Sheet 里从「分享联系人」确认页返回到这个列表（整屏那条路选完就关页，不会回来）。
   * 点人时为了交接置的「加载中」要撤掉，搜索词和滚动位置留着。
   */
  data object TellomiReturnedFromShare : SelectContactEvent

  data object PermissionDeniedSheetDismissed : SelectContactEvent
}
