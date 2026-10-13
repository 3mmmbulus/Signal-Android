/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.EmptyFeedSource
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.InMemoryFeedBlockedKeywords
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate
import org.thoughtcrime.securesms.feed.net.FeedServerKeyword

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRecommendationSettingsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val now = 1_800_000_000_000L

  private class FakeSource(var fail: Boolean = false, val region: String = "cn") : FeedSource by EmptyFeedSource {
    val updates = mutableListOf<FeedPreferencesUpdate>()
    override suspend fun config(): FeedConfig = FeedConfig(enabled = true, region = region, personalizationToggleVisible = true)
    var interests = FeedInterests(topics = listOf(FeedInterest("photo", score = 1.0), FeedInterest("food", score = 0.5)))
    val calls = mutableListOf<String>()
    override suspend fun preferences(): FeedPreferences = FeedPreferences()
    override suspend fun savePreferences(update: FeedPreferencesUpdate): FeedPreferences {
      if (fail) error("offline")
      updates += update
      if (update.personalized != null) calls += "save ${update.personalized}"
      return FeedPreferences(
        personalized = update.personalized ?: true,
        categories = update.categories ?: emptyMap(),
        pausedUntil = if (update.pauseDays == FeedPreferences.PAUSE_DAYS) "2026-11-12T08:00:00Z" else null
      )
    }
    override suspend fun interests(): FeedInterests = interests
    override suspend fun deleteInterest(dimension: String, key: String) {
      if (fail) error("offline")
      calls += "delete $dimension/$key"
    }
    val keywords = linkedMapOf<String, Int>("剧透" to 0)
    var keywordError: String? = null
    override suspend fun keywords(): List<FeedServerKeyword> = keywords.map { (k, d) -> FeedServerKeyword(k, if (d == 0) null else "2026-11-12T08:00:00Z") }
    override suspend fun putKeyword(keyword: String, days: Int) {
      keywordError?.let { throw FeedApiException(409, code = it) }
      if (fail) error("offline")
      keywords[keyword] = days
    }
    override suspend fun deleteKeyword(keyword: String) {
      if (fail) error("offline")
      keywords.remove(keyword)
    }
    override suspend fun resetInterests() {
      calls += "reset"
      interests = FeedInterests()
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `turning personalization off is saved`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.setPersonalized(false)

    assertThat(viewModel.state.value.preferences!!.personalized).isFalse()
    assertThat(source.calls).containsExactly("save false")
  }

  @Test
  fun `a failed save turns the switch back`() {
    val viewModel = FeedRecommendationSettingsViewModel(FakeSource(fail = true), dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.setPersonalized(false)

    assertThat(viewModel.state.value.preferences!!.personalized).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.FAILED)
  }

  @Test
  fun `deleting a tag removes it, a failure puts it back`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.deleteInterest(FeedInterests.DIM_TOPIC, FeedInterest("photo"))
    assertThat(viewModel.state.value.interests!!.topics.map { it.key }).containsExactly("food")
    assertThat(source.calls).containsExactly("delete topic/photo")

    source.fail = true
    viewModel.deleteInterest(FeedInterests.DIM_TOPIC, FeedInterest("food"))
    assertThat(viewModel.state.value.interests!!.topics.map { it.key }).containsExactly("food")
  }

  @Test
  fun `reset clears the tags and says so`() {
    val viewModel = FeedRecommendationSettingsViewModel(FakeSource(), dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.reset()

    assertThat(viewModel.state.value.interests!!.isEmpty).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.RESET)
  }

  @Test
  fun `categories go as a whole map, default takes one out`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.setCategory("food", FeedPreferences.CATEGORY_INCREASE)
    viewModel.setCategory("news", FeedPreferences.CATEGORY_REDUCE)
    viewModel.setCategory("food", FeedPreferences.CATEGORY_DEFAULT)

    assertThat(source.updates.map { it.categories }).containsExactly(
      mapOf("food" to "increase"),
      mapOf("food" to "increase", "news" to "reduce"),
      mapOf("news" to "reduce")
    )
    assertThat(viewModel.state.value.preferences!!.categories).isEqualTo(mapOf("news" to "reduce"))
  }

  @Test
  fun `more sensitive content is only offered overseas`() {
    val mainland = FeedRecommendationSettingsViewModel(FakeSource(region = "cn"), dispatcher, InMemoryFeedBlockedKeywords(), { now })
    mainland.setSensitive(FeedPreferences.SENSITIVE_MORE)
    assertThat(mainland.state.value.preferences!!.sensitive).isEqualTo(FeedPreferences.SENSITIVE_STANDARD)
    assertThat(mainland.state.value.sensitiveChoices).containsExactly(FeedPreferences.SENSITIVE_REDUCE, FeedPreferences.SENSITIVE_STANDARD)

    val overseas = FeedRecommendationSettingsViewModel(FakeSource(region = "intl"), dispatcher, InMemoryFeedBlockedKeywords(), { now })
    assertThat(overseas.state.value.sensitiveChoices).containsExactly(FeedPreferences.SENSITIVE_REDUCE, FeedPreferences.SENSITIVE_STANDARD, FeedPreferences.SENSITIVE_MORE)
  }

  @Test
  fun `pause waits for the server, resume clears it`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.pause()
    assertThat(viewModel.state.value.preferences!!.pausedUntil).isEqualTo("2026-11-12T08:00:00Z")
    viewModel.resume()
    assertThat(viewModel.state.value.preferences!!.pausedUntil).isEqualTo(null)
    assertThat(source.updates.map { it.pauseDays }).containsExactly(30, 0)
  }

  @Test
  fun `show-less words go to the server, hide-completely words stay on the phone`() {
    val source = FakeSource()
    val local = InMemoryFeedBlockedKeywords()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, local, { now })
    assertThat(viewModel.state.value.keywords).containsExactly(FeedKeyword("剧透", FeedKeyword.Mode.DOWNRANK, null))

    viewModel.addKeyword(" 减肥 ", FeedKeyword.Mode.DOWNRANK, 7)
    viewModel.addKeyword("考研", FeedKeyword.Mode.BLOCK, 0)

    assertThat(source.keywords.keys.toList()).containsExactly("剧透", "减肥")
    assertThat(source.keywords["减肥"]).isEqualTo(7)
    assertThat(local.active(now).map { it.keyword }).containsExactly("考研")
    assertThat(viewModel.state.value.keywords.map { it.keyword to it.mode }).containsExactly(
      "考研" to FeedKeyword.Mode.BLOCK,
      "减肥" to FeedKeyword.Mode.DOWNRANK,
      "剧透" to FeedKeyword.Mode.DOWNRANK
    )
    assertThat(viewModel.state.value.keywords[1].expiresAt).isEqualTo(now + 7 * 86_400_000L)

    viewModel.removeKeyword(viewModel.state.value.keywords[0])
    viewModel.removeKeyword(viewModel.state.value.keywords.last())
    assertThat(local.active(now)).isEqualTo(emptyList())
    assertThat(source.keywords.keys.toList()).containsExactly("减肥")
    assertThat(viewModel.state.value.keywords.map { it.keyword }).containsExactly("减肥")
  }

  @Test
  fun `bad or too many words say so and change nothing`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })

    viewModel.addKeyword("字".repeat(21), FeedKeyword.Mode.BLOCK, 0)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.INVALID_KEYWORD)
    viewModel.onNoticeShown()

    source.keywordError = "too_many_keywords"
    viewModel.addKeyword("减肥", FeedKeyword.Mode.DOWNRANK, 0)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.TOO_MANY_KEYWORDS)
    assertThat(viewModel.state.value.keywords.map { it.keyword }).containsExactly("剧透")
  }

  @Test
  fun `a failed delete puts the word back`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher, InMemoryFeedBlockedKeywords(), { now })
    source.fail = true

    viewModel.removeKeyword(viewModel.state.value.keywords.single())

    assertThat(viewModel.state.value.keywords.map { it.keyword }).containsExactly("剧透")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.FAILED)
  }
}
