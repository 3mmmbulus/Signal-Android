/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

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
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class FeedProfileViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var profile: FeedMyProfile?, val saveError: Exception? = null) : FeedSource by EmptyFeedSource {
    val saves = mutableListOf<FeedProfileUpdate>()
    override suspend fun myProfile(): FeedMyProfile? = profile
    override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile {
      saveError?.let { throw it }
      saves += update
      return FeedMyProfile(id = "x", displayName = update.displayName!!, bio = update.bio).also { profile = it }
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
  fun `a new profile starts from the chat name and can be created right away`() {
    val source = FakeSource(profile = null)
    val viewModel = FeedProfileViewModel("  小满  ", source, dispatcher)

    assertThat(viewModel.state.value.isNew).isTrue()
    assertThat(viewModel.state.value.name).isEqualTo("小满")
    viewModel.save()

    assertThat(source.saves).containsExactly(FeedProfileUpdate(displayName = "小满", bio = ""))
    assertThat(viewModel.state.value.saved).isTrue()
  }

  @Test
  fun `an existing profile needs a change before saving`() {
    val viewModel = FeedProfileViewModel("chat", FakeSource(FeedMyProfile(id = "x", displayName = "山风", bio = "拍照")), dispatcher)

    assertThat(viewModel.state.value.name).isEqualTo("山风")
    assertThat(viewModel.state.value.canSave).isFalse()
    viewModel.onBioChanged("拍照和跑步")
    assertThat(viewModel.state.value.canSave).isTrue()
  }

  @Test
  fun `blank names and over-long text are not allowed`() {
    val viewModel = FeedProfileViewModel("", FakeSource(profile = null), dispatcher)

    assertThat(viewModel.state.value.canSave).isFalse()
    viewModel.onNameChanged("a".repeat(40))
    viewModel.onBioChanged("b".repeat(300))

    assertThat(viewModel.state.value.name.length).isEqualTo(FeedMyProfile.MAX_NAME_CHARS)
    assertThat(viewModel.state.value.bio.length).isEqualTo(FeedMyProfile.MAX_BIO_CHARS)
  }

  @Test
  fun `a rejected field is its own message`() {
    val viewModel = FeedProfileViewModel("小满", FakeSource(profile = null, saveError = FeedApiException(400, "invalid_field")), dispatcher)

    viewModel.save()

    assertThat(viewModel.state.value.error).isEqualTo(FeedProfileState.Error.INVALID)
    assertThat(viewModel.state.value.saved).isFalse()
  }
}
