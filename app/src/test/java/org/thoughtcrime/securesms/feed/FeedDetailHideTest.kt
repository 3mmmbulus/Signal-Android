/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailScreen
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailState
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailViewModel
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedViewer
import java.time.Instant

/** 详情页「···」里的不感兴趣 / 不看 TA / 屏蔽：成了就关页，失败留在页上提示（第三节第 11、26 条）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedDetailHideTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "周末去爬山", author = FeedAuthor(profileId = "p1", displayName = "山风"))

  private class FakeSource : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    var error: Exception? = null
    override suspend fun card(contentId: String): FeedCard? = null
    override suspend fun notInterested(contentId: String, reason: String?) {
      error?.let { throw it }
      calls += "not-interested $contentId"
    }
    override suspend fun setHideList(list: String, profileId: String, on: Boolean) {
      error?.let { throw it }
      calls += "$list $profileId $on"
    }
    override suspend fun setBlocked(profileId: String, blocked: Boolean) {
      error?.let { throw it }
      calls += "block $profileId $blocked"
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
  fun `each action reports and then leaves`() {
    val source = FakeSource()

    val a = FeedPostDetailViewModel(card, source, dispatcher)
    a.notInterested()
    assertThat(a.state.value.leaving).isEqualTo(FeedPostDetailState.Leave.NOT_INTERESTED)

    val b = FeedPostDetailViewModel(card, source, dispatcher)
    b.muteAuthor()
    assertThat(b.state.value.leaving).isEqualTo(FeedPostDetailState.Leave.MUTED)

    val c = FeedPostDetailViewModel(card, source, dispatcher)
    c.blockAuthor()
    assertThat(c.state.value.leaving).isEqualTo(FeedPostDetailState.Leave.BLOCKED)

    assertThat(source.calls).containsExactly("not-interested c1", "${FeedApi.HIDE_MUTE} p1 true", "block p1 true")
  }

  @Test
  fun `a failure stays on the page and says why`() {
    val source = FakeSource().apply { error = FeedApiException(409, code = "profile_required") }
    val viewModel = FeedPostDetailViewModel(card, source, dispatcher)

    viewModel.muteAuthor()
    assertThat(viewModel.state.value.leaving).isNull()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.HIDE_PROFILE_REQUIRED)

    source.error = IllegalStateException("offline")
    viewModel.blockAuthor()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.FAILED)
  }

  @Test
  fun `saving an edit refreshes the post and says whether it waits for review`() {
    val source = object : FeedSource by EmptyFeedSource {
      var version = 0
      override suspend fun card(contentId: String): FeedCard = card.copy(text = "v${version++}", viewer = FeedViewer(isAuthor = true))
    }
    val viewModel = FeedPostDetailViewModel(card, source, dispatcher)
    assertThat(viewModel.state.value.post.text).isEqualTo("v0")

    viewModel.onEdited(FeedCreatePostResponse.STATE_PENDING)
    assertThat(viewModel.state.value.post.text).isEqualTo("v1")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.EDITED_PENDING)

    viewModel.onEdited(FeedCreatePostResponse.STATE_PUBLISHED)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.EDITED)
  }

  @Test
  fun `my own post offers edit only inside the window`() {
    var edited: String? = null
    var published by mutableStateOf(Instant.now().minusSeconds(60).toString())
    composeTestRule.setContent {
      SignalTheme {
        FeedPostDetailScreen(
          state = FeedPostDetailState(post = card.copy(publishedAt = published, viewer = FeedViewer(isAuthor = true)), loading = false, loadedOnce = true),
          onBack = {},
          onLikeClick = {},
          onRetryComments = {},
          onLoadMoreComments = {},
          onReplyClick = {},
          onDraftChanged = {},
          onSend = {},
          onNoticeShown = {},
          onEditPost = { edited = it.id }
        )
      }
    }
    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Edit").performClick()
    assertThat(edited).isEqualTo("c1")

    published = Instant.now().minusSeconds(3600).toString()
    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
  }

  @Test
  fun `external authors cannot be muted`() {
    val source = FakeSource()
    val external = card.copy(author = FeedAuthor(profileId = "x:9", displayName = "外站", isNative = false))
    val viewModel = FeedPostDetailViewModel(external, source, dispatcher)

    viewModel.muteAuthor()

    assertThat(source.calls).containsExactly()
    assertThat(viewModel.state.value.leaving).isNull()
  }
}
