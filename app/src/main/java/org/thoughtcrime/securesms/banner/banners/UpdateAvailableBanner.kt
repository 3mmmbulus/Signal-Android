/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.banner.banners

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.Banner
import org.thoughtcrime.securesms.banner.ui.compose.Action
import org.thoughtcrime.securesms.banner.ui.compose.DefaultBanner
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.updaterequired.UpdateOffers

/**
 * Tellomi（tellomi/tellomi#1491 第二刀，需求 app-update-and-version-policy.md §3.4「推荐更新」）：会话列表顶部一条细横幅
 * 「新版本 x 可用 · 更新」，可关，关掉后 3 天内不再出现；官网版下好了以后变成「新版本 x 已下载 · 安装」。
 */
class UpdateAvailableBanner(
  private val context: Context,
  private val onUpdateClicked: () -> Unit
) : Banner<UpdateAvailableBanner.Model>() {

  data class Model(val version: String, val downloaded: Boolean)

  private val dismissed = MutableStateFlow(false)

  override val enabled: Boolean
    get() = !dismissed.value && UpdateOffers.shouldShowBanner(UpdateOffers.current(), SignalStore.apkUpdate.updateBannerDismissedAt, System.currentTimeMillis())

  override val dataFlow: Flow<Model>
    get() = flow {
      val offer = UpdateOffers.current() ?: return@flow
      emit(Model(version = offer.version, downloaded = UpdateOffers.isDownloaded(context)))
    }.flowOn(Dispatchers.IO)

  override val stateUpdates: Flow<Unit>
    get() = dismissed.map { }

  @Composable
  override fun DisplayBanner(model: Model, contentPadding: PaddingValues) {
    UpdateBanner(
      model = model,
      contentPadding = contentPadding,
      onDismiss = {
        SignalStore.apkUpdate.updateBannerDismissedAt = System.currentTimeMillis()
        dismissed.value = true
      },
      onUpdate = onUpdateClicked
    )
  }
}

@Composable
private fun UpdateBanner(model: UpdateAvailableBanner.Model, contentPadding: PaddingValues, onDismiss: () -> Unit = {}, onUpdate: () -> Unit = {}) {
  DefaultBanner(
    title = null,
    body = if (model.downloaded) {
      stringResource(R.string.TellomiUpdateAvailable__banner_downloaded, model.version)
    } else {
      stringResource(R.string.TellomiUpdateAvailable__banner, model.version)
    },
    onDismissListener = onDismiss,
    actions = listOf(
      Action(if (model.downloaded) R.string.TellomiUpdateAvailable__install else R.string.TellomiUpdateAvailable__update) {
        onUpdate()
      }
    ),
    paddingValues = contentPadding
  )
}

@DayNightPreviews
@Composable
private fun UpdateBannerPreview() {
  Previews.Preview {
    UpdateBanner(model = UpdateAvailableBanner.Model(version = "0.1.5", downloaded = false), contentPadding = PaddingValues(0.dp))
  }
}

@DayNightPreviews
@Composable
private fun UpdateBannerDownloadedPreview() {
  Previews.Preview {
    UpdateBanner(model = UpdateAvailableBanner.Model(version = "0.1.5", downloaded = true), contentPadding = PaddingValues(0.dp))
  }
}
