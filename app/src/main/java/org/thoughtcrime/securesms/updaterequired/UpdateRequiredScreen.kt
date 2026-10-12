/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.text.format.Formatter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.AllDevicePreviews
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

object UpdateRequiredTestTags {
  const val PRIMARY_BUTTON = "update_required_primary_button"
  const val DOWNLOAD_FROM_WEBSITE = "update_required_download_from_website"
  const val VIEW_CHATS_ONLY = "update_required_view_chats_only"
  const val LEARN_MORE = "update_required_learn_more"
}

/**
 * 「必须更新」阻断页（tellomi/tellomi#1138，需求 3.4）：Tellomi 标 → 标题 → 一句原因（服务端拒绝 / 构建到期 / 更新清单写的安全或其它，#1491）
 * → 版本与大小 → 主按钮 →「了解更新内容」（更新清单给了地址时）。
 * 下载进度画在主按钮里；失败时按钮变「重试」，下方给「去官网下载」。
 * 最下面始终有「暂不更新，只看聊天记录」（owner 2026-09-24 规则 1）：聊天记录只在这台手机上，不能拦死。
 */
@Composable
fun UpdateRequiredScreen(
  state: UpdateRequiredState,
  onEvent: (UpdateRequiredScreenEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier = modifier) {
    // Tellomi（taishi 审查 b14 包 8 不阻塞 3）：整页一起滚。原来只有上半部分能滚，底部的状态、按钮和出口不滚，
    // 横屏 + 大字号 + 失败态时，最先被裁掉的正是最下面的「暂不更新，只看聊天记录」。
    // 放得下时仍是原来的样子：上半部分在剩余空间里居中，按钮贴底（最小高度 = 可见高度，两个弹性空白分掉剩余空间）。
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .displayCutoutPadding()
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .heightIn(min = maxHeight)
          .padding(horizontal = 32.dp, vertical = 24.dp)
      ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_signal_logo_large),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(72.dp)
          )

          Spacer(modifier = Modifier.height(32.dp))

          Text(
            text = stringResource(R.string.TellomiUpdateRequired__title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .fillMaxWidth()
              .semantics { heading() }
          )

          Spacer(modifier = Modifier.height(16.dp))

          Text(
            text = stringResource(
              when (state.reason) {
                UpdateRequiredState.Reason.SERVER_REJECTED -> R.string.TellomiUpdateRequired__reason_server_rejected
                UpdateRequiredState.Reason.BUILD_EXPIRED -> R.string.TellomiUpdateRequired__reason_build_expired
                UpdateRequiredState.Reason.SECURITY -> R.string.TellomiUpdateRequired__reason_security
                UpdateRequiredState.Reason.OTHER -> R.string.TellomiUpdateRequired__reason_other
              }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = stringResource(R.string.TellomiUpdateRequired__chats_kept),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )

          VersionLine(state)
        }

        Spacer(modifier = Modifier.weight(1f))

        StatusMessage(state)

        val inProgress = state.managesAppUpdates && state.download is Download.InProgress
        Buttons.LargePrimary(
          onClick = { onEvent(UpdateRequiredScreenEvent.PrimaryClicked) },
          modifier = Modifier
            .fillMaxWidth()
            .testTag(UpdateRequiredTestTags.PRIMARY_BUTTON)
            // 下载中点了没有作用，读屏要报「不可用」而不是「双击激活」；不用 enabled = false，免得按钮变灰、看不清里面的进度。
            .semantics { if (inProgress) disabled() }
        ) {
          PrimaryButtonContent(state)
        }

        // 装不上（比如签名不符）时系统只发一条通知，页面上也要留一条出路（taishi 审查 b14 不阻塞 8）。
        if (state.managesAppUpdates && (state.download == Download.Failed || state.download == Download.NoNewerVersion || state.download == Download.ReadyToInstall)) {
          TextButton(
            onClick = { onEvent(UpdateRequiredScreenEvent.DownloadFromWebsiteClicked) },
            modifier = Modifier
              .padding(top = 8.dp)
              .testTag(UpdateRequiredTestTags.DOWNLOAD_FROM_WEBSITE)
          ) {
            Text(text = stringResource(R.string.TellomiUpdateRequired__download_from_website))
          }
        }

        // 需求 3.4 的布局：主按钮下面是次级文字链「了解更新内容」（更新清单给了地址才有，tellomi/tellomi#1491）
        if (state.notesUrl != null) {
          TextButton(
            onClick = { onEvent(UpdateRequiredScreenEvent.LearnMoreClicked) },
            modifier = Modifier.testTag(UpdateRequiredTestTags.LEARN_MORE)
          ) {
            Text(text = stringResource(R.string.TellomiUpdateRequired__learn_more))
          }
        }

        TextButton(
          onClick = { onEvent(UpdateRequiredScreenEvent.ViewChatsOnlyClicked) },
          modifier = Modifier.testTag(UpdateRequiredTestTags.VIEW_CHATS_ONLY)
        ) {
          Text(text = stringResource(R.string.TellomiUpdateRequired__view_chats_only))
        }
      }
    }
  }
}

@Composable
private fun VersionLine(state: UpdateRequiredState) {
  val versionName = state.newVersionName ?: return
  val context = LocalContext.current
  val text = if (state.totalBytes != null) {
    stringResource(R.string.TellomiUpdateRequired__new_version_and_size, versionName, Formatter.formatShortFileSize(context, state.totalBytes))
  } else {
    stringResource(R.string.TellomiUpdateRequired__new_version, versionName)
  }

  Spacer(modifier = Modifier.height(24.dp))
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth()
  )
}

@Composable
private fun StatusMessage(state: UpdateRequiredState) {
  val message = when {
    state.download == Download.NeedsInstallPermission -> R.string.TellomiUpdateRequired__install_permission
    state.download == Download.Failed -> R.string.TellomiUpdateRequired__download_failed
    state.download == Download.NoNewerVersion -> R.string.TellomiUpdateRequired__no_newer_version
    state.download == Download.WaitingForWifi -> R.string.TellomiUpdateRequired__waiting_for_wifi
    state.isOffline && state.managesAppUpdates -> R.string.TellomiUpdateRequired__offline
    else -> null
  } ?: return

  Text(
    text = stringResource(message),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 16.dp)
      .semantics { liveRegion = LiveRegionMode.Polite }
  )
}

@Composable
private fun PrimaryButtonContent(state: UpdateRequiredState) {
  if (!state.managesAppUpdates) {
    Text(text = stringResource(R.string.TellomiUpdateRequired__update_now))
    return
  }

  when (val download = state.download) {
    Download.Idle -> Text(text = stringResource(R.string.TellomiUpdateRequired__update_now))
    Download.NeedsInstallPermission -> Text(text = stringResource(R.string.TellomiUpdateRequired__open_settings))
    Download.ReadyToInstall -> Text(text = stringResource(R.string.TellomiUpdateRequired__install))
    Download.WaitingForWifi -> Text(text = stringResource(R.string.TellomiUpdateRequired__use_mobile_data))
    Download.Failed, Download.NoNewerVersion -> Text(text = stringResource(R.string.TellomiUpdateRequired__retry))
    is Download.InProgress -> {
      // 进度画在按钮里（需求 3.4）。
      if (download.percent == null) {
        CircularProgressIndicator(
          color = MaterialTheme.colorScheme.onPrimary,
          strokeWidth = 2.dp,
          modifier = Modifier.size(18.dp)
        )
      } else {
        CircularProgressIndicator(
          progress = { download.percent / 100f },
          color = MaterialTheme.colorScheme.onPrimary,
          trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
          modifier = Modifier.size(18.dp)
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = if (download.percent == null) {
          stringResource(R.string.TellomiUpdateRequired__preparing)
        } else {
          stringResource(R.string.TellomiUpdateRequired__downloading, download.percent)
        }
      )
    }
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenIdlePreview() {
  Previews.Preview {
    UpdateRequiredScreen(state = UpdateRequiredState(newVersionName = "0.1.3"), onEvent = {})
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenDownloadingPreview() {
  Previews.Preview {
    UpdateRequiredScreen(
      state = UpdateRequiredState(newVersionName = "0.1.3", totalBytes = 78_000_000, download = Download.InProgress(42)),
      onEvent = {}
    )
  }
}

@AllDevicePreviews
@Composable
private fun UpdateRequiredScreenFailedPreview() {
  Previews.Preview {
    UpdateRequiredScreen(state = UpdateRequiredState(reason = UpdateRequiredState.Reason.BUILD_EXPIRED, download = Download.Failed), onEvent = {})
  }
}
