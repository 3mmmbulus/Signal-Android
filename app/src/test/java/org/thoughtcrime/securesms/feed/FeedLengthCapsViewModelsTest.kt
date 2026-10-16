/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.mine.FeedMyPostsViewModel
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.net.FeedMyPostsPage
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate
import org.thoughtcrime.securesms.feed.profile.FeedProfileViewModel

/**
 * 长度上限按码点算（和引擎的 `utf8.RuneCountInString` 一致），切的时候不把 emoji 切成两半：昵称 32、简介 200、申诉 500。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedLengthCapsViewModelsTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val grin = "\uD83D\uDE00"

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class ProfileSource(val profile: FeedMyProfile?) : FeedSource by EmptyFeedSource {
    override suspend fun myProfile(): FeedMyProfile? = profile
    override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile = throw IllegalStateException()
  }

  private class PostsSource(val rejected: FeedMyPost) : FeedSource by EmptyFeedSource {
    val appeals = mutableListOf<String>()
    override suspend fun appeal(contentId: String, text: String) {
      appeals += text
    }
    override suspend fun myPosts(before: String?): FeedMyPostsPage = FeedMyPostsPage(posts = listOf(rejected))
  }

  @Test
  fun `name and bio caps count code points and never split an emoji`() {
    val viewModel = FeedProfileViewModel("", ProfileSource(FeedMyProfile(id = "x", displayName = "a")), dispatcher)

    // 32 emoji are 64 UTF-16 units but exactly the engine's 32 characters: all of them are kept
    viewModel.onNameChanged(grin.repeat(32))
    assertThat(viewModel.state.value.name).isEqualTo(grin.repeat(32))
    // one more is dropped whole, never cut into half an emoji
    viewModel.onNameChanged(grin.repeat(33))
    assertThat(viewModel.state.value.name).isEqualTo(grin.repeat(32))
    viewModel.onNameChanged("x" + grin.repeat(32))
    assertThat(viewModel.state.value.name).isEqualTo("x" + grin.repeat(31))

    viewModel.onBioChanged(grin.repeat(201))
    assertThat(viewModel.state.value.bio).isEqualTo(grin.repeat(200))
  }

  @Test
  fun `the suggested chat name is cut on a character boundary`() {
    val viewModel = FeedProfileViewModel("  " + grin.repeat(40) + "  ", ProfileSource(null), dispatcher)

    assertThat(viewModel.state.value.name).isEqualTo(grin.repeat(32))
  }

  @Test
  fun `appeal text is capped at 500 code points and an emoji is never cut in half`() {
    val rejected = FeedMyPost(id = "r", state = FeedMyPost.STATE_REJECTED, text = "r", appealable = true)
    val source = PostsSource(rejected)
    val viewModel = FeedMyPostsViewModel(source, dispatcher)
    viewModel.loadMore()

    // 500 emoji are 1000 UTF-16 units: all of them go out
    viewModel.appeal(rejected, grin.repeat(500))
    assertThat(source.appeals.last()).isEqualTo(grin.repeat(500))

    // 501: the last one is dropped whole
    viewModel.appeal(rejected, "  " + grin.repeat(501))
    assertThat(source.appeals.last()).isEqualTo(grin.repeat(500))
  }
}
