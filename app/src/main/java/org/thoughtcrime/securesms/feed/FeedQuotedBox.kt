/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R

object FeedQuotedBoxTestTags {
  const val QUOTED = "feed_quoted"
  const val QUOTED_GONE = "feed_quoted_gone"
}

/**
 * 被引用的那条，压在正文下面的小框（第三节第 26 条「引用」）：作者名加开头几行，点开是那条的详情。
 * [authorName] 和 [text] 都是 null 时是看不到了，灰字写「原动态已不可见」；[deleted] 时写「原动态已被作者删除」（第 41 条）。
 */
@Composable
fun FeedQuotedBox(
  authorName: String?,
  text: String?,
  modifier: Modifier = Modifier,
  deleted: Boolean = false,
  onClick: (() -> Unit)? = null
) {
  val gone = authorName == null && text == null
  Surface(
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    color = MaterialTheme.colorScheme.surface,
    modifier = modifier
      .fillMaxWidth()
      .testTag(if (gone) FeedQuotedBoxTestTags.QUOTED_GONE else FeedQuotedBoxTestTags.QUOTED)
      .then(if (onClick != null && !gone) Modifier.clickable(onClick = onClick) else Modifier)
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      if (gone) {
        Text(
          text = stringResource(if (deleted) R.string.TellomiFeed__quote_deleted else R.string.TellomiFeed__quote_unavailable),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        if (!authorName.isNullOrBlank()) {
          Text(
            text = authorName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        if (!text.isNullOrBlank()) {
          // 点链接打开链接，点别处仍是打开被引用的那条；「已删除 / 不可见」的灰字不走这里，不会变成链接
          FeedLinkText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = QUOTED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTapOutsideLink = onClick
          )
        }
      }
    }
  }
}

private const val QUOTED_LINES = 3
