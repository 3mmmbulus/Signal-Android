/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.signal.mediasend.MediaSendFlowActivityContract
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R

/**
 * Tellomi（tellomi/tellomi#1115）：附件 Sheet 的 dock——owner 2026-09-23 定的五格与顺序，文字和图标沿用附件键盘，
 * 「相册」「文件」是 Sheet 里的两页（tellomi/tellomi#1121），「位置」在没有地图的构建里置灰（tellomi/tellomi#1235），
 * 点回来的结果认得出是哪一格。
 */
class TellomiAttachmentSheetDockTest {

  @Test
  fun `the dock is gallery, file, location, poll and contact, with all but location as pages of the sheet`() {
    val entries = TellomiAttachmentSheetDock.entries(isLocationAvailable = true)

    assertThat(entries.map { it.id }).containsExactly("GALLERY", "FILE", "LOCATION", "POLL", "CONTACT")
    assertThat(entries.map { it.page }).containsExactly(
      MediaSendFlowActivityContract.AttachmentPage.GALLERY,
      MediaSendFlowActivityContract.AttachmentPage.FILES,
      null,
      MediaSendFlowActivityContract.AttachmentPage.POLL,
      MediaSendFlowActivityContract.AttachmentPage.CONTACT
    )
    assertThat(entries.map { it.title }).containsExactly(
      R.string.AttachmentKeyboard_gallery,
      R.string.AttachmentKeyboard_file,
      R.string.AttachmentKeyboard_location,
      R.string.AttachmentKeyboard_poll,
      R.string.AttachmentKeyboard_contact
    )
    assertThat(entries.map { it.icon }).isEqualTo(TellomiAttachmentSheetDock.buttons.map { it.iconRes })
    assertThat(entries.mapNotNull { it.comingSoonMessage }).containsExactly()
  }

  @Test
  fun `without maps, location is greyed out with the coming soon message and nothing else is`() {
    val entries = TellomiAttachmentSheetDock.entries(isLocationAvailable = false)

    assertThat(entries.filter { it.comingSoonMessage != null }.map { it.id }).containsExactly("LOCATION")
    assertThat(entries.first { it.id == "LOCATION" }.comingSoonMessage).isEqualTo(R.string.TellomiLocation__coming_soon)
  }

  @Test
  fun `by default location follows the same maps check as the attachment keyboard`() {
    val location = TellomiAttachmentSheetDock.entries().first { it.id == "LOCATION" }

    assertThat(location.comingSoonMessage != null).isEqualTo(!BuildConfig.MAPS_AVAILABLE)
  }

  @Test
  fun `a dock result maps back to its button, and anything not in the dock maps to nothing`() {
    assertThat(TellomiAttachmentSheetDock.buttonFor("FILE")).isEqualTo(AttachmentKeyboardButton.FILE)
    assertThat(TellomiAttachmentSheetDock.buttonFor("POLL")).isEqualTo(AttachmentKeyboardButton.POLL)
    assertThat(TellomiAttachmentSheetDock.buttonFor("PAYMENT")).isNull()
    assertThat(TellomiAttachmentSheetDock.buttonFor("nonsense")).isNull()
  }
}
