/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.R

object UpdateAvailableDotTestTags {
  const val DOT = "update_available_dot"
}

/**
 * Tellomi（tellomi/tellomi#1491，需求 app-update-and-version-policy.md §3.4「可选更新」）：设置入口上的小红点——有新版本、
 * 还没在「关于」里看过。读屏读「有新版本」。
 */
@Composable
fun UpdateAvailableDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
  val description = stringResource(R.string.TellomiUpdateAvailable__dot_description)
  Box(
    modifier = modifier
      .size(size)
      .background(color = MaterialTheme.colorScheme.error, shape = CircleShape)
      .semantics { contentDescription = description }
      .testTag(UpdateAvailableDotTestTags.DOT)
  )
}
