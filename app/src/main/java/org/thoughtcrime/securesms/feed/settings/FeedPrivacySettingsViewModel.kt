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
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate

/**
 * 「设置 → 隐私」里的动态那一段（第三节第 30 条：动态的设置加进原来的隐私页，不另开「动态隐私」）：
 * 私密账号（第 29 条）和防骚扰模式（3 / 7 / 30 天，到期自动关）。
 *
 * 两样都存在公开主页上；还没建主页时没有可改的，提示先去建。改了等服务端回来再显示（防骚扰的到期日要服务端算）。
 */
class FeedPrivacySettingsViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedPrivacySettingsViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedPrivacySettingsState())
  val state: StateFlow<FeedPrivacySettingsState> = internalState.asStateFlow()

  /** 回到页面时调；读到了就不再读。 */
  fun load() {
    val current = internalState.value
    if (current.loaded || current.loading) return
    internalState.update { it.copy(loading = true, loadFailed = false) }
    viewModelScope.launch {
      try {
        val profile = withContext(ioDispatcher) { source.myProfile() }
        internalState.update { it.copy(loading = false, loaded = true, profile = profile) }
      } catch (e: Exception) {
        Log.w(TAG, "privacy settings failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  /** 界面上已经确认过。 */
  fun setPrivate(on: Boolean) {
    if (internalState.value.profile?.isPrivate == on) return
    save(FeedProfileUpdate(isPrivate = on))
  }

  fun setGuardDays(days: Int) {
    if (days !in FeedMyProfile.GUARD_DAYS) return
    if (days == 0 && internalState.value.profile?.guardUntil == null) return
    save(FeedProfileUpdate(guardDays = days))
  }

  fun onNoticeShown() {
    internalState.update { it.copy(failed = false) }
  }

  private fun save(update: FeedProfileUpdate) {
    if (internalState.value.profile == null || internalState.value.saving) return
    internalState.update { it.copy(saving = true) }
    viewModelScope.launch {
      try {
        val saved = withContext(ioDispatcher) { source.saveProfile(update) }
        internalState.update { it.copy(saving = false, profile = saved) }
      } catch (e: Exception) {
        Log.w(TAG, "save privacy failed", e)
        internalState.update { it.copy(saving = false, failed = true) }
      }
    }
  }
}

data class FeedPrivacySettingsState(
  /** null 且 [loaded]：还没建公开主页。 */
  val profile: FeedMyProfile? = null,
  val loaded: Boolean = false,
  val loading: Boolean = false,
  val loadFailed: Boolean = false,
  val saving: Boolean = false,
  val failed: Boolean = false
)
