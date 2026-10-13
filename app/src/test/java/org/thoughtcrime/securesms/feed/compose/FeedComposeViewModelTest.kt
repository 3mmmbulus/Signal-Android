/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import assertk.assertThat
import assertk.assertions.containsExactly
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
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FeedComposeViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(val editError: Exception? = null) : FeedSource by EmptyFeedSource {
    val edits = mutableListOf<Pair<String, FeedDraft>>()
    override suspend fun config(): FeedConfig = FeedConfig(enabled = true, minorMode = true)
    override suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse {
      editError?.let { throw it }
      edits += contentId to draft
      return FeedCreatePostResponse(id = contentId, state = FeedCreatePostResponse.STATE_PENDING, version = 2)
    }
  }

  private val target = FeedEditTarget(id = "p1", kind = "article", title = "标题", text = "正文", publishedAt = null)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `edit starts from the post and needs a change`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(), ioDispatcher = dispatcher)

    val state = viewModel.state.value
    assertThat(state.draft.kind).isEqualTo(FeedDraftKind.ARTICLE)
    assertThat(state.draft.title).isEqualTo("标题")
    assertThat(state.problem).isEqualTo(FeedDraft.Problem.UNCHANGED)
    assertThat(state.hasUnsavedChanges).isFalse()

    viewModel.onKindChanged(FeedDraftKind.POST)
    assertThat(viewModel.state.value.draft.kind).isEqualTo(FeedDraftKind.ARTICLE)
  }

  @Test
  fun `edit ignores visibility rules, even in minor mode`() {
    val source = FakeSource()
    val viewModel = FeedComposeViewModel(editTarget = target, source = source, ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    assertThat(viewModel.state.value.problem).isNull()
    viewModel.publish()

    assertThat(source.edits.map { it.first }).containsExactly("p1")
    assertThat(viewModel.state.value.publishedState).isEqualTo(FeedCreatePostResponse.STATE_PENDING)
  }

  @Test
  fun `closed window is its own message, not a generic failure`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(FeedApiException(409, "edit_window_closed")), ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    viewModel.publish()

    assertThat(viewModel.state.value.editWindowClosed).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
  }

  @Test
  fun `missing profile asks to set one up, not a generic failure`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(FeedApiException(409, "profile_required")), ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    viewModel.publish()

    assertThat(viewModel.state.value.profileRequired).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
    viewModel.onProfileRequiredShown()
    assertThat(viewModel.state.value.profileRequired).isFalse()
  }

  @Test
  fun `window check uses the publish time`() {
    val published = Instant.parse("2026-10-13T12:00:00Z")
    val card = target.copy(publishedAt = published.toString())

    assertThat(card.isWithinWindow(published.plusSeconds(900).toEpochMilli(), 900)).isTrue()
    assertThat(card.isWithinWindow(published.plusSeconds(901).toEpochMilli(), 900)).isFalse()
    assertThat(target.isWithinWindow(0, 900)).isTrue()
  }

  @Test
  fun `picking people switches to include, an empty pick keeps the old choice`() {
    val adult = object : FeedSource by EmptyFeedSource {
      override suspend fun config(): FeedConfig = FeedConfig(enabled = true, minorMode = false)
    }
    val viewModel = FeedComposeViewModel(editTarget = null, source = adult, ioDispatcher = dispatcher)
    viewModel.onTextChanged("hi")

    viewModel.onAudiencePicked(FeedVisibility.INCLUDE, emptyList())
    assertThat(viewModel.state.value.draft.visibility).isEqualTo(FeedVisibility.PUBLIC)

    viewModel.onVisibilityChanged(FeedVisibility.INCLUDE)
    assertThat(viewModel.state.value.draft.visibility).isEqualTo(FeedVisibility.PUBLIC)

    val people = listOf(FeedAudienceMember(1, "aci-1"), FeedAudienceMember(2, "aci-2"))
    viewModel.onAudiencePicked(FeedVisibility.INCLUDE, people)
    assertThat(viewModel.state.value.draft.visibility).isEqualTo(FeedVisibility.INCLUDE)
    assertThat(viewModel.state.value.draft.audience).isEqualTo(people)
    assertThat(viewModel.state.value.problem).isEqualTo(null)
  }

  @Test
  fun `minors cannot pick people`() {
    val viewModel = FeedComposeViewModel(editTarget = null, source = FakeSource(), ioDispatcher = dispatcher)
    viewModel.onAudiencePicked(FeedVisibility.EXCLUDE, listOf(FeedAudienceMember(1, "aci-1")))
    assertThat(viewModel.state.value.draft.visibility).isEqualTo(FeedVisibility.FRIENDS)
  }

  @Test
  fun `poll options can be added up to ten and removed down to two`() {
    val viewModel = FeedComposeViewModel(source = FakeSource(), ioDispatcher = dispatcher)

    viewModel.onPollOptionAdded()
    assertThat(viewModel.state.value.draft.poll).isNull()

    viewModel.onPollAdded()
    viewModel.onPollOptionChanged(0, "爬山")
    viewModel.onPollOptionChanged(1, "公园")
    repeat(10) { viewModel.onPollOptionAdded() }
    assertThat(viewModel.state.value.draft.poll!!.options.size).isEqualTo(FeedDraftPoll.MAX_OPTIONS)

    repeat(10) { viewModel.onPollOptionRemoved(2) }
    assertThat(viewModel.state.value.draft.poll!!.options).containsExactly("爬山", "公园")
    viewModel.onPollOptionRemoved(0)
    assertThat(viewModel.state.value.draft.poll!!.options).containsExactly("爬山", "公园")

    viewModel.onPollMultiChanged(true)
    viewModel.onPollDurationChanged(FeedPollDuration.SEVEN_DAYS)
    val poll = viewModel.state.value.draft.poll!!
    assertThat(poll.multi).isTrue()
    assertThat(poll.duration).isEqualTo(FeedPollDuration.SEVEN_DAYS)
    assertThat(viewModel.state.value.canAddPoll).isFalse()
    assertThat(viewModel.state.value.problem).isNull()

    viewModel.onPollRemoved()
    assertThat(viewModel.state.value.draft.poll).isNull()
    assertThat(viewModel.state.value.problem).isEqualTo(FeedDraft.Problem.EMPTY)
  }

  @Test
  fun `editing cannot add a poll`() {
    val viewModel = FeedComposeViewModel(editTarget = target.copy(kind = "post", title = null), source = FakeSource(), ioDispatcher = dispatcher)

    viewModel.onPollAdded()

    assertThat(viewModel.state.value.draft.poll).isNull()
  }

  @Test
  fun `reply options change a new post but not an edit`() {
    val settings = FeedReplySettings(policy = FeedReplyPolicy.FOLLOWING, moderation = true)

    val viewModel = FeedComposeViewModel(source = FakeSource(), ioDispatcher = dispatcher)
    viewModel.onReplySettingsChanged(settings)
    assertThat(viewModel.state.value.draft.replies).isEqualTo(settings)

    val editing = FeedComposeViewModel(editTarget = target, source = FakeSource(), ioDispatcher = dispatcher)
    editing.onReplySettingsChanged(settings)
    assertThat(editing.state.value.draft.replies).isEqualTo(FeedReplySettings())
  }

  @Test
  fun `topics toggle, stop at three, and stay put while editing`() {
    val viewModel = FeedComposeViewModel(source = FakeSource(), ioDispatcher = dispatcher)
    listOf("food", "travel", "photo", "music").forEach(viewModel::onTopicToggled)
    assertThat(viewModel.state.value.draft.topics).isEqualTo(listOf("food", "travel", "photo"))
    viewModel.onTopicToggled("travel")
    viewModel.onTopicToggled("music")
    assertThat(viewModel.state.value.draft.topics).isEqualTo(listOf("food", "photo", "music"))

    val editing = FeedComposeViewModel(editTarget = target, source = FakeSource(), ioDispatcher = dispatcher)
    editing.onTopicToggled("food")
    assertThat(editing.state.value.draft.topics).isEqualTo(emptyList())
  }
}
