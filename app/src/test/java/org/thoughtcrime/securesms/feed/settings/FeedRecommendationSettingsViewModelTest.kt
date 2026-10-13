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
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRecommendationSettingsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

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
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher)

    viewModel.setPersonalized(false)

    assertThat(viewModel.state.value.preferences!!.personalized).isFalse()
    assertThat(source.calls).containsExactly("save false")
  }

  @Test
  fun `a failed save turns the switch back`() {
    val viewModel = FeedRecommendationSettingsViewModel(FakeSource(fail = true), dispatcher)

    viewModel.setPersonalized(false)

    assertThat(viewModel.state.value.preferences!!.personalized).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.FAILED)
  }

  @Test
  fun `deleting a tag removes it, a failure puts it back`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher)

    viewModel.deleteInterest(FeedInterests.DIM_TOPIC, FeedInterest("photo"))
    assertThat(viewModel.state.value.interests!!.topics.map { it.key }).containsExactly("food")
    assertThat(source.calls).containsExactly("delete topic/photo")

    source.fail = true
    viewModel.deleteInterest(FeedInterests.DIM_TOPIC, FeedInterest("food"))
    assertThat(viewModel.state.value.interests!!.topics.map { it.key }).containsExactly("food")
  }

  @Test
  fun `reset clears the tags and says so`() {
    val viewModel = FeedRecommendationSettingsViewModel(FakeSource(), dispatcher)

    viewModel.reset()

    assertThat(viewModel.state.value.interests!!.isEmpty).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedRecommendationSettingsState.Notice.RESET)
  }

  @Test
  fun `categories go as a whole map, default takes one out`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher)

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
    val mainland = FeedRecommendationSettingsViewModel(FakeSource(region = "cn"), dispatcher)
    mainland.setSensitive(FeedPreferences.SENSITIVE_MORE)
    assertThat(mainland.state.value.preferences!!.sensitive).isEqualTo(FeedPreferences.SENSITIVE_STANDARD)
    assertThat(mainland.state.value.sensitiveChoices).containsExactly(FeedPreferences.SENSITIVE_REDUCE, FeedPreferences.SENSITIVE_STANDARD)

    val overseas = FeedRecommendationSettingsViewModel(FakeSource(region = "intl"), dispatcher)
    assertThat(overseas.state.value.sensitiveChoices).containsExactly(FeedPreferences.SENSITIVE_REDUCE, FeedPreferences.SENSITIVE_STANDARD, FeedPreferences.SENSITIVE_MORE)
  }

  @Test
  fun `pause waits for the server, resume clears it`() {
    val source = FakeSource()
    val viewModel = FeedRecommendationSettingsViewModel(source, dispatcher)

    viewModel.pause()
    assertThat(viewModel.state.value.preferences!!.pausedUntil).isEqualTo("2026-11-12T08:00:00Z")
    viewModel.resume()
    assertThat(viewModel.state.value.preferences!!.pausedUntil).isEqualTo(null)
    assertThat(source.updates.map { it.pauseDays }).containsExactly(30, 0)
  }
}
