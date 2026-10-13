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
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.net.FeedPreferencesUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRecommendationSettingsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var fail: Boolean = false) : FeedSource by EmptyFeedSource {
    var interests = FeedInterests(topics = listOf(FeedInterest("photo", score = 1.0), FeedInterest("food", score = 0.5)))
    val calls = mutableListOf<String>()
    override suspend fun preferences(): FeedPreferences = FeedPreferences()
    override suspend fun savePreferences(update: FeedPreferencesUpdate): FeedPreferences {
      if (fail) error("offline")
      calls += "save ${update.personalized}"
      return FeedPreferences(personalized = update.personalized ?: true)
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
}
