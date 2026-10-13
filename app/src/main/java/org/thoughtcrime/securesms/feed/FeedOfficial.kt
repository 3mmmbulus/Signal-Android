/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R

/**
 * 官方账号（第三节第 33 条）。认不认官方只看引擎给的 `badge == "official"`，从不按名字（`official-account-and-saved.md` 3.1）；
 * 名字或 ID 里带品牌与身份词、却不是官方的，主页上提示「此账号不是官方账号」（同文 3.3 的词）。
 */
object FeedOfficial {
  const val BADGE_OFFICIAL = "official"

  const val TEST_TAG_MARK = "feed_official_mark"

  /** 3.3 点名的几个词；ADR-0062 的完整词表在服务端注册时已拦住大部分，这里只管提示。 */
  private val IDENTITY_WORDS = listOf("tellomi", "官方", "客服", "安全中心")

  fun isOfficial(badge: String?): Boolean = badge == BADGE_OFFICIAL

  fun looksOfficial(vararg names: String?): Boolean {
    return names.filterNotNull().any { name -> IDENTITY_WORDS.any { name.contains(it, ignoreCase = true) } }
  }
}

/** 名字后面的认证标：Signal 现成的那个（3.1「三端现有的标，不另画」）。 */
@Composable
fun FeedOfficialMark(size: Dp = 16.dp, modifier: Modifier = Modifier) {
  Image(
    painter = painterResource(R.drawable.ic_official_20),
    contentDescription = stringResource(R.string.TellomiFeedPage__badge_official),
    modifier = modifier
      .padding(start = 4.dp)
      .size(size)
      .testTag(FeedOfficial.TEST_TAG_MARK)
  )
}
