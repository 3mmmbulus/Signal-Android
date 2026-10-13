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
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate

/**
 * 「动态设置」里推荐那一部分（第三节第 12、28、47 条；《算法推荐管理规定》第 17 条）：
 * 个性化推荐开关、管理兴趣标签（看、删）、重置推荐。
 *
 * 开关和删除都先在界面上改过去，存不上再改回来。
 */
class FeedRecommendationSettingsViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedRecommendationSettingsViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedRecommendationSettingsState())
  val state: StateFlow<FeedRecommendationSettingsState> = internalState.asStateFlow()

  init {
    load()
  }

  fun load() {
    internalState.update { it.copy(loading = true, loadFailed = false) }
    viewModelScope.launch {
      try {
        val preferences = withContext(ioDispatcher) { source.preferences() }
        val interests = withContext(ioDispatcher) { source.interests() }
        internalState.update { it.copy(loading = false, preferences = preferences, interests = interests) }
      } catch (e: Exception) {
        Log.w(TAG, "recommendation settings failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  fun setPersonalized(on: Boolean) {
    val before = internalState.value.preferences ?: return
    if (before.personalized == on) return
    internalState.update { it.copy(preferences = before.copy(personalized = on)) }
    viewModelScope.launch {
      try {
        val saved = withContext(ioDispatcher) { source.savePreferences(FeedPreferencesUpdate(personalized = on)) }
        internalState.update { it.copy(preferences = saved) }
      } catch (e: Exception) {
        Log.w(TAG, "save personalized failed", e)
        internalState.update { it.copy(preferences = it.preferences?.copy(personalized = before.personalized), notice = FeedRecommendationSettingsState.Notice.FAILED) }
      }
    }
  }

  fun deleteInterest(dimension: String, interest: FeedInterest) {
    val before = internalState.value.interests ?: return
    internalState.update { it.copy(interests = before.without(dimension, interest.key)) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.deleteInterest(dimension, interest.key) }
      } catch (e: Exception) {
        Log.w(TAG, "delete interest failed", e)
        internalState.update { it.copy(interests = before, notice = FeedRecommendationSettingsState.Notice.FAILED) }
      }
    }
  }

  /** 界面上已经确认过两次。 */
  fun reset() {
    if (internalState.value.resetting) return
    internalState.update { it.copy(resetting = true) }
    viewModelScope.launch {
      try {
        withContext(ioDispatcher) { source.resetInterests() }
        val interests = withContext(ioDispatcher) { source.interests() }
        internalState.update { it.copy(resetting = false, interests = interests, notice = FeedRecommendationSettingsState.Notice.RESET) }
      } catch (e: Exception) {
        Log.w(TAG, "reset failed", e)
        internalState.update { it.copy(resetting = false, notice = FeedRecommendationSettingsState.Notice.FAILED) }
      }
    }
  }

  fun onNoticeShown() {
    internalState.update { it.copy(notice = null) }
  }
}

data class FeedRecommendationSettingsState(
  val preferences: FeedPreferences? = null,
  val interests: FeedInterests? = null,
  val loading: Boolean = false,
  val loadFailed: Boolean = false,
  val resetting: Boolean = false,
  val notice: Notice? = null
) {
  enum class Notice { FAILED, RESET }
}
