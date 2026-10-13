/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings

/**
 * 「设置 → 通知」里的动态那一段（第三节第 30、43 条：动态的设置加进原来的通知页，不另开一页）。
 *
 * 打开页面时读一次；改了立刻存，先在界面上改过去，存不上再改回来。
 * 没有公开主页时引擎不让存（只有有主页的人收得到通知），提示先建主页。
 */
class FeedNotificationSettingsViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedNotificationSettingsViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedNotificationSettingsState())
  val state: StateFlow<FeedNotificationSettingsState> = internalState.asStateFlow()

  /** 回到页面时调；读到了就不再读。 */
  fun load() {
    if (internalState.value.settings != null || internalState.value.loading) return
    internalState.update { it.copy(loading = true, loadFailed = false) }
    viewModelScope.launch {
      try {
        val settings = withContext(ioDispatcher) { source.notificationSettings() }
        internalState.update { it.copy(loading = false, settings = settings) }
      } catch (e: Exception) {
        Log.w(TAG, "notification settings failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  fun setFromWhom(value: String) {
    if (value in FeedNotificationSettings.FROM_VALUES) save { it.copy(fromWhom = value) }
  }

  fun setLikes(on: Boolean) = save { it.copy(likes = on) }

  fun setComments(on: Boolean) = save { it.copy(comments = on) }

  fun setFollows(on: Boolean) = save { it.copy(follows = on) }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }

  private fun save(transform: (FeedNotificationSettings) -> FeedNotificationSettings) {
    val before = internalState.value.settings ?: return
    val after = transform(before)
    if (after == before) return
    internalState.update { it.copy(settings = after) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.saveNotificationSettings(after) }
      } catch (e: Exception) {
        Log.w(TAG, "save notification settings failed", e)
        val notice = if (e is FeedApiException && e.status == 409 && e.code == "profile_required") {
          FeedNotificationSettingsState.Notice.PROFILE_REQUIRED
        } else {
          FeedNotificationSettingsState.Notice.SAVE_FAILED
        }
        // 只在还是这一次改的值时改回去，别盖掉后面的改动
        internalState.update { if (it.settings == after) it.copy(settings = before, notice = notice) else it.copy(notice = notice) }
      }
    }
  }
}

data class FeedNotificationSettingsState(
  val settings: FeedNotificationSettings? = null,
  val loading: Boolean = false,
  val loadFailed: Boolean = false,
  val notice: Notice? = null
) {
  enum class Notice { PROFILE_REQUIRED, SAVE_FAILED }

  /** 来源选了「都不接收」时，下面三个开关没有意义，变灰。 */
  val categoriesEnabled: Boolean
    get() = settings != null && settings.fromWhom != FeedNotificationSettings.FROM_NONE
}
