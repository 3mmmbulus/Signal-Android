/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.EmptyFeedSource
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedNotificationSettingsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource : FeedSource by EmptyFeedSource {
    var stored = FeedNotificationSettings()
    var loadError: Exception? = null
    var saveError: Exception? = null

    /** 每次保存先等这么久（虚拟时间）；用完了就不等。用来让先发的请求比后发的慢。 */
    val saveDelays = ArrayDeque<Long>()
    val saved = mutableListOf<FeedNotificationSettings>()
    var loads = 0

    override suspend fun notificationSettings(): FeedNotificationSettings {
      loads++
      loadError?.let { throw it }
      return stored
    }

    override suspend fun saveNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings {
      val wait = saveDelays.removeFirstOrNull()
      if (wait != null) delay(wait)
      saveError?.let { throw it }
      saved += settings
      stored = settings
      return settings
    }
  }

  private val source = FakeSource()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = FeedNotificationSettingsViewModel(source = source, ioDispatcher = dispatcher)

  @Test
  fun `loads once and saves each change`() {
    val viewModel = viewModel()
    viewModel.load()
    viewModel.load()
    assertThat(source.loads).isEqualTo(1)
    assertThat(viewModel.state.value.settings).isEqualTo(FeedNotificationSettings())

    viewModel.setFromWhom(FeedNotificationSettings.FROM_FRIENDS)
    viewModel.setLikes(false)

    assertThat(source.saved).containsExactly(
      FeedNotificationSettings(fromWhom = "friends"),
      FeedNotificationSettings(fromWhom = "friends", likes = false)
    )
  }

  @Test
  fun `no one turns the categories grey`() {
    val viewModel = viewModel()
    viewModel.load()
    assertThat(viewModel.state.value.categoriesEnabled).isTrue()
    viewModel.setFromWhom(FeedNotificationSettings.FROM_NONE)
    assertThat(viewModel.state.value.categoriesEnabled).isFalse()
  }

  @Test
  fun `without a profile the change is undone and explained`() {
    val viewModel = viewModel()
    viewModel.load()
    source.saveError = FeedApiException(409, code = "profile_required")

    viewModel.setComments(false)

    assertThat(viewModel.state.value.settings?.comments).isEqualTo(true)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedNotificationSettingsState.Notice.PROFILE_REQUIRED)
    viewModel.onNoticeShown()
    assertThat(viewModel.state.value.notice).isNull()
  }

  @Test
  fun `load failure offers a retry`() {
    source.loadError = IOException("offline")
    val viewModel = viewModel()
    viewModel.load()
    assertThat(viewModel.state.value.loadFailed).isTrue()

    source.loadError = null
    viewModel.load()
    assertThat(viewModel.state.value.loadFailed).isFalse()
    assertThat(viewModel.state.value.settings).isEqualTo(FeedNotificationSettings())
  }

  @Test
  fun `unknown source value is ignored`() {
    val viewModel = viewModel()
    viewModel.load()
    viewModel.setFromWhom("strangers")
    assertThat(source.saved.size).isEqualTo(0)
  }

  @Test
  fun `quick toggles reach the engine in order and the newest value wins`() = runTest {
    val viewModel = viewModel()
    viewModel.load()
    // the first request is slow, the second fast: unserialised, the older value arrives last and wins
    source.saveDelays += listOf(100L, 10L)

    viewModel.setLikes(false)
    viewModel.setComments(false)
    advanceUntilIdle()

    val expected = FeedNotificationSettings(likes = false, comments = false)
    assertThat(source.stored).isEqualTo(expected)
    assertThat(source.saved.last()).isEqualTo(expected)
    assertThat(viewModel.state.value.settings).isEqualTo(expected)
  }

  @Test
  fun `toggles made while a save is in flight are coalesced into one request with the latest values`() = runTest {
    val viewModel = viewModel()
    viewModel.load()
    source.saveDelays += 100L

    viewModel.setLikes(false)
    viewModel.setComments(false)
    viewModel.setFollows(false)
    advanceUntilIdle()

    assertThat(source.saved).containsExactly(
      FeedNotificationSettings(likes = false),
      FeedNotificationSettings(likes = false, comments = false, follows = false)
    )
  }

  @Test
  fun `a toggle back to the value the engine already has sends nothing more`() = runTest {
    val viewModel = viewModel()
    viewModel.load()
    source.saveDelays += 100L

    viewModel.setLikes(false)
    viewModel.setLikes(true)
    advanceUntilIdle()

    
    assertThat(source.stored).isEqualTo(FeedNotificationSettings())
    assertThat(viewModel.state.value.settings).isEqualTo(FeedNotificationSettings())
  }

  @Test
  fun `a failed save does not undo a later toggle, and the last failing save puts back what the engine has`() = runTest {
    val viewModel = viewModel()
    viewModel.load()
    source.saveDelays += 100L
    source.saveError = FeedApiException(500, code = null)

    viewModel.setLikes(false)
    viewModel.setComments(false)
    advanceUntilIdle()

    // both requests failed: the screen is back at what the engine has
    assertThat(viewModel.state.value.settings).isEqualTo(FeedNotificationSettings())
    assertThat(viewModel.state.value.notice).isEqualTo(FeedNotificationSettingsState.Notice.SAVE_FAILED)
    assertThat(source.saved.size).isEqualTo(0)
  }
}
