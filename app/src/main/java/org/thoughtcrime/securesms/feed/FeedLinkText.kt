/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import org.signal.core.util.encourageNewBrowserTab
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOpener
import org.thoughtcrime.securesms.util.CommunicationActions

/**
 * 带可点网址的动态文字（正文、评论、引用框）。点链接按聊天里的方式打开；长按链接出「打开链接 / 复制 / 取消」。
 * 点在链接以外：有 [onTapOutsideLink] 就交给它（引用框点别处仍进那条详情）；长按链接以外交给 [onLongPressOutsideLink]（评论的举报菜单）。
 * 没有链接时不拦点按，外层的 clickable 照常生效。
 */
@Composable
fun FeedLinkText(
  text: AnnotatedString,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyLarge,
  color: Color = Color.Unspecified,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
  onTapOutsideLink: (() -> Unit)? = null,
  onLongPressOutsideLink: (() -> Unit)? = null
) {
  val links = remember(text) { FeedTextLinks.find(text.text) }
  val linkColor = MaterialTheme.colorScheme.primary
  val styled = remember(text, links, linkColor) {
    if (links.isEmpty()) {
      text
    } else {
      buildAnnotatedString {
        append(text)
        links.forEach { addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), it.start, it.end) }
      }
    }
  }
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  var menuUrl by remember { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val outside by rememberUpdatedState(onTapOutsideLink)
  val outsideLong by rememberUpdatedState(onLongPressOutsideLink)

  val gestures = if (links.isEmpty()) {
    Modifier
  } else {
    Modifier.pointerInput(links) {
      detectTapGestures(
        onTap = { position ->
          val hit = layout?.let { FeedTextLinks.at(links, it.getOffsetForPosition(position)) }
          if (hit != null) openFeedLink(context, hit.url) else outside?.invoke()
        },
        onLongPress = { position ->
          val hit = layout?.let { FeedTextLinks.at(links, it.getOffsetForPosition(position)) }
          if (hit != null) menuUrl = hit.url else outsideLong?.invoke()
        }
      )
    }
  }

  Text(
    text = styled,
    style = style,
    color = color,
    maxLines = maxLines,
    overflow = overflow,
    onTextLayout = { layout = it },
    modifier = modifier.then(gestures)
  )

  menuUrl?.let { url ->
    AlertDialog(
      onDismissRequest = { menuUrl = null },
      text = { Text(text = url, style = MaterialTheme.typography.bodyMedium) },
      confirmButton = {
        TextButton(onClick = {
          menuUrl = null
          openFeedLink(context, url)
        }) { Text(stringResource(R.string.TellomiFeed__text_link_open)) }
      },
      dismissButton = {
        TextButton(onClick = {
          menuUrl = null
          copyFeedLink(context, url)
        }) { Text(stringResource(R.string.TellomiFeed__text_link_copy)) }
        TextButton(onClick = { menuUrl = null }) { Text(stringResource(R.string.TellomiFeed__text_link_cancel)) }
      }
    )
  }
}

@Composable
fun FeedLinkText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyLarge,
  color: Color = Color.Unspecified,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
  onTapOutsideLink: (() -> Unit)? = null,
  onLongPressOutsideLink: (() -> Unit)? = null
) = FeedLinkText(AnnotatedString(text), modifier, style, color, maxLines, overflow, onTapOutsideLink, onLongPressOutsideLink)

/** 和聊天里点链接同一条路：群链接、代理链接先认，其余交给 [TellomiLinkOpener]，最后退回浏览器。 */
private fun openFeedLink(context: Context, url: String) {
  val activity = context.findActivity()
  if (activity == null) {
    CommunicationActions.openBrowserLink(context, url)
    return
  }
  if (activity is FragmentActivity &&
    (CommunicationActions.handlePotentialGroupLinkUrl(activity, url) || CommunicationActions.handlePotentialProxyLinkUrl(activity, url))
  ) {
    return
  }
  TellomiLinkOpener.open(activity, url) {
    activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).encourageNewBrowserTab())
  }
}

private fun copyFeedLink(context: Context, url: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.TellomiFeed__copy_link), url))
  Toast.makeText(context, R.string.TellomiLinks__link_copied, Toast.LENGTH_SHORT).show()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
