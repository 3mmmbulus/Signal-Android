/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Intent
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.signal.core.util.Util
import org.thoughtcrime.securesms.R

/**
 * Tellomi (TF43 P1, PM 2026-10-25): long-pressing a web link shows a menu for that link instead of the whole message's
 * menu, after Telegram's behaviour (own implementation): the full URL on top, then Open / Copy link / Share. Copy shows
 * a short "Link copied" snackbar at the bottom. Nothing is copied unless Copy is chosen.
 */
object TellomiLinkMenu {

  enum class Action { OPEN, COPY, SHARE }

  /** Menu order, top to bottom. */
  @JvmField
  val ACTIONS: List<Action> = listOf(Action.OPEN, Action.COPY, Action.SHARE)

  /** Only http(s) links get the menu; `mailto:` / `tel:` keep their old long-press behaviour. */
  @JvmStatic
  fun isWebLink(url: String): Boolean {
    return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
  }

  @JvmStatic
  fun show(widget: View, url: String, open: Runnable) {
    val context = widget.context
    val labels = ACTIONS.map {
      when (it) {
        Action.OPEN -> context.getString(R.string.TellomiLinkCard__action_open)
        Action.COPY -> context.getString(R.string.TellomiFeed__copy_link)
        Action.SHARE -> context.getString(R.string.preferences_share)
      }
    }.toTypedArray()

    MaterialAlertDialogBuilder(context)
      .setTitle(url)
      .setItems(labels) { _, which ->
        when (ACTIONS[which]) {
          Action.OPEN -> open.run()
          Action.COPY -> {
            Util.writeTextToClipboard(context, url)
            Snackbar.make(widget, R.string.TellomiLinks__link_copied, Snackbar.LENGTH_SHORT).show()
          }
          Action.SHARE -> {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            context.startActivity(Intent.createChooser(send, null))
          }
        }
      }
      .show()
  }
}
