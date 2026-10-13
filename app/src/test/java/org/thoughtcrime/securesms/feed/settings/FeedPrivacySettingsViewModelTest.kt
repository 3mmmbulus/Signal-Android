/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
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
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class FeedPrivacySettingsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var profile: FeedMyProfile?, var fail: Boolean = false) : FeedSource by EmptyFeedSource {
    val updates = mutableListOf<FeedProfileUpdate>()
    override suspend fun myProfile(): FeedMyProfile? = profile
    override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile {
      if (fail) error("offline")
      updates += update
      val p = profile!!
      return p.copy(
        isPrivate = update.isPrivate ?: p.isPrivate,
        guardUntil = when (update.guardDays) {
          null -> p.guardUntil
          0 -> null
          else -> "2026-10-20T08:00:00Z"
        }
      ).also { profile = it }
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

  private fun me() = FeedMyProfile(id = "p1", displayName = "Anan")

  @Test
  fun `private account and harassment guard are saved and read back`() {
    val source = FakeSource(me())
    val viewModel = FeedPrivacySettingsViewModel(source, dispatcher)
    viewModel.load()

    viewModel.setPrivate(true)
    viewModel.setPrivate(true)
    viewModel.setGuardDays(7)
    viewModel.setGuardDays(5)

    assertThat(source.updates).isEqualTo(listOf(FeedProfileUpdate(isPrivate = true), FeedProfileUpdate(guardDays = 7)))
    assertThat(viewModel.state.value.profile!!.isPrivate).isTrue()
    assertThat(viewModel.state.value.profile!!.guardUntil).isEqualTo("2026-10-20T08:00:00Z")

    viewModel.setGuardDays(0)
    assertThat(viewModel.state.value.profile!!.guardUntil).isNull()
  }

  @Test
  fun `turning the guard off when it is already off sends nothing`() {
    val source = FakeSource(me())
    val viewModel = FeedPrivacySettingsViewModel(source, dispatcher)
    viewModel.load()
    viewModel.setGuardDays(0)
    assertThat(source.updates).isEqualTo(emptyList())
  }

  @Test
  fun `no profile yet means nothing to change`() {
    val source = FakeSource(null)
    val viewModel = FeedPrivacySettingsViewModel(source, dispatcher)
    viewModel.load()
    viewModel.setPrivate(true)
    assertThat(viewModel.state.value.loaded).isTrue()
    assertThat(viewModel.state.value.profile).isNull()
    assertThat(source.updates).isEqualTo(emptyList())
  }

  @Test
  fun `a failed save keeps the old value and says so`() {
    val source = FakeSource(me(), fail = true)
    val viewModel = FeedPrivacySettingsViewModel(source, dispatcher)
    viewModel.load()
    viewModel.setPrivate(true)
    assertThat(viewModel.state.value.profile!!.isPrivate).isFalse()
    assertThat(viewModel.state.value.failed).isTrue()
  }

  @Test
  fun `updates carry only what changes, named as the engine names them`() {
    assertThat(FeedJson.encodeToString(FeedProfileUpdate.serializer(), FeedProfileUpdate(isPrivate = false)))
      .isEqualTo("""{"is_private":false}""")
    assertThat(FeedJson.encodeToString(FeedProfileUpdate.serializer(), FeedProfileUpdate(guardDays = 30)))
      .isEqualTo("""{"harassment_guard_days":30}""")
    val profile = FeedJson.decodeFromString(FeedMyProfile.serializer(), """{"id":"p1","display_name":"A","is_private":true,"harassment_guard_until":"2026-10-20T08:00:00Z"}""")
    assertThat(profile.guardUntil).isEqualTo("2026-10-20T08:00:00Z")
  }
}
