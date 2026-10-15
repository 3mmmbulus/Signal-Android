/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import org.signal.mediasend.MediaSendFlowActivityContract
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R

/**
 * Tellomi（tellomi/tellomi#1115）：附件 Sheet 底部 dock 的格子——owner 2026-09-23 定：相册 · 文件 · 位置 · 投票 · 联系人
 * （外观照 Telegram，格子用 Signal 现有的能力；GIF 挪到表情面板，#1012）。文字和图标沿用上游附件键盘那几格的（附件键盘本身 2026-10-16 已删，清单 C-9）。
 * 「相册」「文件」「投票」是 Sheet 里的页，在同一个 Sheet 里换（tellomi/tellomi#1121；投票 2026-10-16，照 Telegram 两端）；
 * 别的点了带回会话页。
 * 「位置」在高德接上之前置灰、点了提示即将支持，判据是 `BuildConfig.MAPS_AVAILABLE`（tellomi/tellomi#1235、#1124）。
 */
object TellomiAttachmentSheetDock {

  val buttons: List<AttachmentKeyboardButton> = listOf(
    AttachmentKeyboardButton.GALLERY,
    AttachmentKeyboardButton.FILE,
    AttachmentKeyboardButton.LOCATION,
    AttachmentKeyboardButton.POLL,
    AttachmentKeyboardButton.CONTACT
  )

  fun entries(isLocationAvailable: Boolean = BuildConfig.MAPS_AVAILABLE): List<MediaSendFlowActivityContract.DockEntry> {
    return buttons.map { button ->
      MediaSendFlowActivityContract.DockEntry(
        id = button.name,
        title = button.titleRes,
        icon = button.iconRes,
        page = when (button) {
          AttachmentKeyboardButton.GALLERY -> MediaSendFlowActivityContract.AttachmentPage.GALLERY
          AttachmentKeyboardButton.FILE -> MediaSendFlowActivityContract.AttachmentPage.FILES
          AttachmentKeyboardButton.POLL -> MediaSendFlowActivityContract.AttachmentPage.POLL
          else -> null
        },
        comingSoonMessage = if (button == AttachmentKeyboardButton.LOCATION && !isLocationAvailable) R.string.TellomiLocation__coming_soon else null
      )
    }
  }

  /** 结果里带回来的 [MediaSendFlowActivityContract.DockEntry.id] 对应哪一格；不认识的是 null。 */
  fun buttonFor(id: String): AttachmentKeyboardButton? = buttons.firstOrNull { it.name == id }
}
