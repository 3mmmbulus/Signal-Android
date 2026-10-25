/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.content.Context
import android.view.View
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.util.isEditMessage

/**
 * Tellomi M7 (tellomi/tellomi#1186，需求 §3.1)：本机第一次发出消息后一次性提示「一个勾：已发出；两个勾：对方已读」（8 秒）。
 * 在气泡上看到自己的消息从发送中变成一个勾时触发（和 iOS 一样，只有在会话里看得见勾时才有意义），记在本机偏好里，只提示一次。
 * 笔记、编辑、故事回复不触发。
 */
object TellomiChecksHint {

  const val PREF_KEY = "tellomi.checks_hint_shown"
  const val DURATION_MS = 8_000

  private var shownThisProcess = false

  @JvmStatic
  fun isEligible(record: MessageRecord): Boolean {
    val isStoryReply = record is MmsMessageRecord && record.parentStoryId != null
    return TellomiMessageStatus.shouldShowChecksHint(
      alreadyShown = false,
      isNoteToSelf = record.toRecipient.isSelf,
      isEdit = record.isEditMessage(),
      isStoryReply = isStoryReply
    )
  }

  @JvmStatic
  fun hasBeenShown(context: Context): Boolean {
    return shownThisProcess || PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false)
  }

  /** 调用方已确认这是同一条消息在屏上从发送中变成一个勾。 */
  @JvmStatic
  fun maybeShow(anchor: View, record: MessageRecord) {
    val context = anchor.context
    if (hasBeenShown(context) || !isEligible(record) || !anchor.isAttachedToWindow) {
      return
    }

    shownThisProcess = true
    PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(PREF_KEY, true) }
    Snackbar.make(anchor, R.string.TellomiChecksHint__one_check_sent_two_checks_read, DURATION_MS)
      .setAnchorView(findComposeBar(anchor))
      .show()
  }

  /**
   * Tellomi M7（#1186）：提示条锚在输入框（会话页的 conversation_input_panel）上方，不盖住输入框。
   * 找不到或输入框没显示（比如被禁言、搜索栏替换）时返回 null，Snackbar 回到默认的底部位置。
   */
  @JvmStatic
  fun findComposeBar(anchor: View): View? {
    val composeBar = anchor.rootView?.findViewById<View>(R.id.conversation_input_panel) ?: return null
    return if (composeBar.isShown) composeBar else null
  }
}
