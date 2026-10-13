/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.signal.core.ui.R as CoreUiR

object FeedProfileTestTags {
  const val NAME = "feed_profile_name"
  const val BIO = "feed_profile_bio"
  const val SAVE = "feed_profile_save"
}

/**
 * 公开主页的名字和简介。第一次建的时候说清楚这些是公开的、和聊天资料分开（第三节第 9 条）。
 */
@Composable
fun FeedProfileScreen(
  state: FeedProfileState,
  onBack: () -> Unit,
  onNameChanged: (String) -> Unit,
  onBioChanged: (String) -> Unit,
  onSave: () -> Unit,
  onRetry: () -> Unit,
  onErrorShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val invalid = stringResource(R.string.TellomiFeedProfile__invalid)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  LaunchedEffect(state.error) {
    val error = state.error ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (error == FeedProfileState.Error.INVALID) invalid else failed)
    onErrorShown()
  }

  Scaffolds.Settings(
    title = stringResource(if (state.isNew) R.string.TellomiFeedProfile__title_new else R.string.TellomiFeedProfile__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    when {
      state.loading -> Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) { CircularProgressIndicator() }
      state.loadFailed -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(padding).fillMaxSize()
      ) {
        Text(text = stringResource(R.string.TellomiFeed__load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
      }
      else -> Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
          .padding(padding)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = 24.dp, vertical = 8.dp)
      ) {
        Text(
          text = stringResource(R.string.TellomiFeedProfile__public_note),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
          value = state.name,
          onValueChange = onNameChanged,
          label = { Text(stringResource(R.string.TellomiFeedProfile__name)) },
          singleLine = true,
          supportingText = { Text("${state.name.length}/${FeedMyProfile.MAX_NAME_CHARS}") },
          modifier = Modifier.fillMaxWidth().testTag(FeedProfileTestTags.NAME)
        )

        OutlinedTextField(
          value = state.bio,
          onValueChange = onBioChanged,
          label = { Text(stringResource(R.string.TellomiFeedProfile__bio)) },
          minLines = 3,
          maxLines = 6,
          supportingText = { Text("${state.bio.length}/${FeedMyProfile.MAX_BIO_CHARS}") },
          modifier = Modifier.fillMaxWidth().testTag(FeedProfileTestTags.BIO)
        )

        // 没设 ID 的人显示系统号（第三节第 8 条）；ID 和聊天用户名是同一个，在聊天的「用户名」里设
        val profile = state.profile
        val idLine = when {
          profile == null -> null
          !profile.handle.isNullOrBlank() -> "@${profile.handle}"
          !profile.tellomiNumber.isNullOrBlank() -> stringResource(R.string.TellomiFeed__tellomi_number, profile.tellomiNumber)
          else -> null
        }
        if (idLine != null) {
          Text(text = idLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Buttons.LargeTonal(
          onClick = onSave,
          enabled = state.canSave,
          modifier = Modifier.fillMaxWidth().testTag(FeedProfileTestTags.SAVE)
        ) {
          if (state.saving) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
          } else {
            Text(stringResource(if (state.isNew) R.string.TellomiFeedProfile__create else R.string.TellomiFeedCompose__save))
          }
        }
      }
    }
  }
}
