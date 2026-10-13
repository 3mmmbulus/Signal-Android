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
import org.thoughtcrime.securesms.feed.net.FeedConfig
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
        val config = withContext(ioDispatcher) { source.config() }
        val preferences = withContext(ioDispatcher) { source.preferences() }
        val interests = withContext(ioDispatcher) { source.interests() }
        internalState.update {
          it.copy(
            loading = false,
            preferences = preferences,
            interests = interests,
            region = config.region,
            personalizationToggleVisible = config.personalizationToggleVisible
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "recommendation settings failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  fun setPersonalized(on: Boolean) = save(FeedPreferencesUpdate(personalized = on)) { it.copy(personalized = on) }

  /** 推荐风格：保持现状 / 探索更多。 */
  fun setStyle(style: String) {
    if (style in FeedPreferences.STYLES) save(FeedPreferencesUpdate(style = style)) { it.copy(style = style) }
  }

  /** 敏感内容：减少 / 标准；「更多」只有海外有（引擎在大陆回 `not_available_in_region`）。 */
  fun setSensitive(sensitive: String) {
    if (sensitive in internalState.value.sensitiveChoices) save(FeedPreferencesUpdate(sensitive = sensitive)) { it.copy(sensitive = sensitive) }
  }

  /** 某个类别减少 / 默认 / 增加。引擎的 `categories` 是整张表替换，所以发完整的一张。 */
  fun setCategory(slug: String, value: String) {
    val before = internalState.value.preferences ?: return
    if (value !in FeedPreferences.CATEGORY_VALUES) return
    val categories = if (value == FeedPreferences.CATEGORY_DEFAULT) before.categories - slug else before.categories + (slug to value)
    // 引擎收到 "default" 才去掉；直接不带也一样，因为整张表替换
    save(FeedPreferencesUpdate(categories = categories)) { it.copy(categories = categories) }
  }

  /** 暂停推荐 30 天（期间推荐不按你的兴趣，引擎 `feeds.go` 的 `personalized`）；[resume] 提前恢复。 */
  fun pause() = save(FeedPreferencesUpdate(pauseDays = FeedPreferences.PAUSE_DAYS), optimistic = null)

  fun resume() = save(FeedPreferencesUpdate(pauseDays = 0)) { it.copy(pausedUntil = null) }

  /**
   * 先在界面上改过去（[optimistic] 为 null 时等服务端回来再改），存上了用服务端回的，存不上改回原来的。
   */
  private fun save(update: FeedPreferencesUpdate, optimistic: ((FeedPreferences) -> FeedPreferences)?) {
    val before = internalState.value.preferences ?: return
    val after = optimistic?.invoke(before)
    if (after == before) return
    if (after != null) internalState.update { it.copy(preferences = after) }
    viewModelScope.launch {
      try {
        val saved = withContext(ioDispatcher) { source.savePreferences(update) }
        // recent_topics 只在 GET 里有，PUT 回来的没有，留着原来的
        internalState.update { it.copy(preferences = saved.copy(recentTopics = it.preferences?.recentTopics.orEmpty())) }
      } catch (e: Exception) {
        Log.w(TAG, "save preferences failed", e)
        internalState.update { it.copy(preferences = if (after == null || it.preferences == after) before else it.preferences, notice = FeedRecommendationSettingsState.Notice.FAILED) }
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
  val notice: Notice? = null,
  val region: String? = null,
  /** 引擎在按兴趣排序（`recommend_mode == scored`）时才有个性化开关；编辑精选阶段推荐本来就不按个人兴趣。 */
  val personalizationToggleVisible: Boolean = false
) {
  enum class Notice { FAILED, RESET }

  /** 敏感内容的档位：「更多」只给海外（R-1.6）。区域不知道时按大陆算。 */
  val sensitiveChoices: List<String>
    get() = if (region != null && region != FeedConfig.REGION_CN) FeedPreferences.SENSITIVE_VALUES else FeedPreferences.SENSITIVE_VALUES - FeedPreferences.SENSITIVE_MORE
}
