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
import org.thoughtcrime.securesms.feed.FeedBlockedKeywords
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.StoredFeedBlockedKeywords
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedKeywordRequest
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate
import org.thoughtcrime.securesms.feed.parseInstantMillis
import java.util.concurrent.TimeUnit

/**
 * 「动态设置」里推荐那一部分（第三节第 12、28、47 条；《算法推荐管理规定》第 17 条）：
 * 个性化推荐开关、管理兴趣标签（看、删）、重置推荐。
 *
 * 开关和删除都先在界面上改过去，存不上再改回来。
 */
class FeedRecommendationSettingsViewModel(
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val blockedKeywords: FeedBlockedKeywords = StoredFeedBlockedKeywords(),
  private val clock: () -> Long = System::currentTimeMillis
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
        // 关键词拿不到不挡整页，本机的照样显示
        val server = try {
          withContext(ioDispatcher) { source.keywords() }
        } catch (e: Exception) {
          Log.w(TAG, "keywords failed", e)
          null
        }
        internalState.update {
          it.copy(
            loading = false,
            keywords = mergeKeywords(server?.map { k -> FeedKeyword(k.keyword, FeedKeyword.Mode.DOWNRANK, k.expiresAt?.let(::parseInstantMillis)) }),
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

  /**
   * 加一个屏蔽关键词（第三节第 12 条）：「少推荐」交给服务端降权；「完全屏蔽」只存在手机上，信息流里直接不显示。
   * [days] 0 = 永久，30，7。
   */
  fun addKeyword(keyword: String, mode: FeedKeyword.Mode, days: Int) {
    val k = keyword.trim()
    if (k.isEmpty() || k.codePointCount(0, k.length) > FeedKeywordRequest.MAX_CHARS || days !in FeedKeywordRequest.DAYS) {
      internalState.update { it.copy(notice = FeedRecommendationSettingsState.Notice.INVALID_KEYWORD) }
      return
    }
    val expiresAt = if (days == 0) null else clock() + TimeUnit.DAYS.toMillis(days.toLong())
    when (mode) {
      FeedKeyword.Mode.BLOCK -> {
        blockedKeywords.add(k, expiresAt)
        internalState.update { it.copy(keywords = mergeKeywords(it.keywords)) }
      }
      FeedKeyword.Mode.DOWNRANK -> {
        if (internalState.value.keywords.count { it.mode == FeedKeyword.Mode.DOWNRANK } >= FeedKeywordRequest.MAX_KEYWORDS &&
          internalState.value.keywords.none { it.mode == FeedKeyword.Mode.DOWNRANK && it.keyword == k }
        ) {
          internalState.update { it.copy(notice = FeedRecommendationSettingsState.Notice.TOO_MANY_KEYWORDS) }
          return
        }
        viewModelScope.launch {
          try {
            withContext(ioDispatcher) { source.putKeyword(k, days) }
            internalState.update { state ->
              val server = state.keywords.filter { it.mode == FeedKeyword.Mode.DOWNRANK && it.keyword != k }
              state.copy(keywords = mergeKeywords(listOf(FeedKeyword(k, FeedKeyword.Mode.DOWNRANK, expiresAt)) + server))
            }
          } catch (e: Exception) {
            Log.w(TAG, "add keyword failed", e)
            val notice = when {
              e is FeedApiException && e.code == "too_many_keywords" -> FeedRecommendationSettingsState.Notice.TOO_MANY_KEYWORDS
              e is FeedApiException && e.code == "invalid_keyword" -> FeedRecommendationSettingsState.Notice.INVALID_KEYWORD
              else -> FeedRecommendationSettingsState.Notice.FAILED
            }
            internalState.update { it.copy(notice = notice) }
          }
        }
      }
    }
  }

  fun removeKeyword(keyword: FeedKeyword) {
    when (keyword.mode) {
      FeedKeyword.Mode.BLOCK -> {
        blockedKeywords.remove(keyword.keyword)
        internalState.update { it.copy(keywords = mergeKeywords(it.keywords)) }
      }
      FeedKeyword.Mode.DOWNRANK -> {
        val before = internalState.value.keywords
        internalState.update { it.copy(keywords = before - keyword) }
        viewModelScope.launch {
          try {
            withContext(ioDispatcher) { source.deleteKeyword(keyword.keyword) }
          } catch (e: Exception) {
            Log.w(TAG, "delete keyword failed", e)
            internalState.update { it.copy(keywords = before, notice = FeedRecommendationSettingsState.Notice.FAILED) }
          }
        }
      }
    }
  }

  /** 本机「完全屏蔽」的放前面，再接服务端「少推荐」的（[server] 为 null 时沿用当前列表里的）。 */
  private fun mergeKeywords(server: List<FeedKeyword>?): List<FeedKeyword> {
    val now = clock()
    val local = blockedKeywords.active(now).map { FeedKeyword(it.keyword, FeedKeyword.Mode.BLOCK, it.expiresAt) }
    val remote = (server ?: internalState.value.keywords).filter { it.mode == FeedKeyword.Mode.DOWNRANK && (it.expiresAt == null || it.expiresAt > now) }
    return local + remote
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
  val keywords: List<FeedKeyword> = emptyList(),
  val region: String? = null,
  /** 引擎在按兴趣排序（`recommend_mode == scored`）时才有个性化开关；编辑精选阶段推荐本来就不按个人兴趣。 */
  val personalizationToggleVisible: Boolean = false
) {
  enum class Notice { FAILED, RESET, INVALID_KEYWORD, TOO_MANY_KEYWORDS }

  /** 敏感内容的档位：「更多」只给海外（R-1.6）。区域不知道时按大陆算。 */
  val sensitiveChoices: List<String>
    get() = if (region != null && region != FeedConfig.REGION_CN) FeedPreferences.SENSITIVE_VALUES else FeedPreferences.SENSITIVE_VALUES - FeedPreferences.SENSITIVE_MORE
}

/** 设置页里的一个屏蔽关键词。[expiresAt] 毫秒，null = 永久。 */
data class FeedKeyword(val keyword: String, val mode: Mode, val expiresAt: Long? = null) {
  enum class Mode {
    /** 少推荐：服务端降权，换设备还在。 */
    DOWNRANK,

    /** 完全屏蔽：只在这台手机上，信息流里命中的不显示。 */
    BLOCK
  }
}
