/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.compose

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R

/**
 * A consistent ActionMode top-bar for dealing with multiselect scenarios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionModeTopBar(
  title: String,
  onCloseClick: () -> Unit,
  toolbarColor: Color? = null,
  windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
  announceTitleChanges: Boolean = false
) {
  TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = toolbarColor ?: MaterialTheme.colorScheme.surface
    ),
    navigationIcon = {
      IconButtons.IconButton(onClick = onCloseClick) {
        Icon(
          imageVector = SignalIcons.X.imageVector,
          contentDescription = stringResource(R.string.CallScreenTopBar__go_back)
        )
      }
    },
    title = {
      // Tellomi（交互审计 A-15）：标题是「已选 N 条」时，数字一变读屏就念出来（礼貌模式，不打断正在念的）。
      Text(
        text = title,
        modifier = if (announceTitleChanges) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
      )
    },
    windowInsets = windowInsets
  )
}

@PreviewLightDark
@Composable
fun ActionModeTopBarPreview() {
  Previews.Preview {
    ActionModeTopBar(
      title = "1 selected",
      onCloseClick = {}
    )
  }
}
