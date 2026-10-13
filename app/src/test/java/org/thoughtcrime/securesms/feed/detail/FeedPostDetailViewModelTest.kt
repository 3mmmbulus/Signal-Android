/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

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
import org.thoughtcrime.securesms.feed.FeedInsightsState
import org.thoughtcrime.securesms.feed.FeedOwnPostAction
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCommentsPage
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedPoll
import org.thoughtcrime.securesms.feed.net.FeedPollState
import org.thoughtcrime.securesms.feed.net.FeedPostInsights
import org.thoughtcrime.securesms.feed.net.FeedPostSettingsUpdate
import org.thoughtcrime.securesms.feed.net.FeedViewer
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FeedPostDetailViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val post = FeedCard(id = "p", kind = FeedCard.KIND_POST, text = "old")

  private fun comment(id: String, parent: String? = null) = FeedCard(id = id, kind = FeedCard.KIND_COMMENT, text = id, rootId = "p", parentId = parent)

  private class FakeSource(
    var fresh: FeedCard?,
    val pages: Map<String?, FeedCommentsPage>,
    var postResult: () -> String = { FeedCreatePostResponse.STATE_PENDING },
    var voteResult: () -> FeedPollState = { FeedPollState(counts = listOf(1, 0), voted = listOf(0)) }
  ) : FeedSource by EmptyFeedSource {
    val votes = mutableListOf<List<Int>>()
    val moderated = mutableListOf<String>()
    var moderationError: Exception? = null
    override suspend fun approveComment(postId: String, commentId: String) {
      moderationError?.let { throw it }
      moderated += "approve $postId/$commentId"
    }
    override suspend fun deletePost(contentId: String) {
      moderationError?.let { throw it }
      moderated += "delete $contentId"
    }
    override suspend fun vote(contentId: String, options: List<Int>): FeedPollState {
      votes += options
      return voteResult()
    }
    val updates = mutableListOf<FeedPostSettingsUpdate>()
    var settingsError: Exception? = null
    override suspend fun updatePostSettings(contentId: String, update: FeedPostSettingsUpdate) {
      settingsError?.let { throw it }
      updates += update
    }
    override suspend fun postInsights(contentId: String) = FeedPostInsights(opens = 9)
    val posted = mutableListOf<Pair<String, String?>>()
    override suspend fun card(contentId: String): FeedCard? = fresh
    override suspend fun comments(postId: String, cursor: String?): FeedCommentsPage = pages[cursor] ?: FeedCommentsPage()
    val voices = mutableListOf<Pair<String, String?>>()
    var voiceResult: () -> String = { FeedCreatePostResponse.STATE_PENDING }
    override suspend fun postVoiceComment(postId: String, audio: File, parentId: String?): String {
      val result = voiceResult()
      voices += audio.readText() to parentId
      return result
    }
    override suspend fun postComment(postId: String, text: String, parentId: String?): String {
      val result = postResult()
      posted += text to parentId
      return result
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
  fun `refreshes the card and pages the comments`() {
    val source = FakeSource(
      fresh = post.copy(text = "new"),
      pages = mapOf(
        null to FeedCommentsPage(comments = listOf(comment("a"), comment("b")), cursor = "n"),
        "n" to FeedCommentsPage(comments = listOf(comment("b"), comment("a1", "a")))
      )
    )
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.loadMoreComments()

    val state = viewModel.state.value
    assertThat(state.post.text).isEqualTo("new")
    assertThat(state.comments.map { it.id }).containsExactly("a", "b", "a1")
    assertThat(state.threads.map { it.root.id }).containsExactly("a", "b")
    assertThat(state.cursor).isNull()
  }

  @Test
  fun `a post that is gone says so`() {
    val viewModel = FeedPostDetailViewModel(post, FakeSource(fresh = null, pages = emptyMap()), dispatcher)

    assertThat(viewModel.state.value.postGone).isTrue()
  }

  @Test
  fun `sending a reply clears the box and says it is in review`() {
    val source = FakeSource(fresh = post, pages = mapOf(null to FeedCommentsPage(comments = listOf(comment("a")))))
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.replyTo(comment("a"))
    viewModel.onDraftChanged("  thanks  ")
    viewModel.send()

    assertThat(source.posted).containsExactly("thanks" to "a")
    val state = viewModel.state.value
    assertThat(state.draft).isEqualTo("")
    assertThat(state.replyTarget).isNull()
    assertThat(state.notice).isEqualTo(FeedPostDetailState.Notice.PENDING)
  }

  @Test
  fun `closed comments turn the box off and keep the draft`() {
    val source = FakeSource(fresh = post, pages = emptyMap(), postResult = { throw FeedApiException(403, "comments_closed") })
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.onDraftChanged("hello")
    viewModel.send()

    val state = viewModel.state.value
    assertThat(state.canComment).isFalse()
    assertThat(state.draft).isEqualTo("hello")
    assertThat(state.notice).isEqualTo(FeedPostDetailState.Notice.CLOSED)
  }

  @Test
  fun `a missing profile is reported, not a generic failure`() {
    val source = FakeSource(fresh = post, pages = emptyMap(), postResult = { throw FeedApiException(409, "profile_required") })
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.onDraftChanged("hello")
    viewModel.send()

    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.PROFILE_REQUIRED)
    assertThat(viewModel.state.value.canComment).isTrue()
  }

  private fun recording(): File = File.createTempFile("voice", ".m4a").apply { writeText("audio") }

  @Test
  fun `a voice comment is sent with the reply target and always says it is in review`() {
    val source = FakeSource(fresh = post, pages = mapOf(null to FeedCommentsPage(comments = listOf(comment("a")))))
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)
    val audio = recording()

    viewModel.replyTo(comment("a"))
    viewModel.sendVoice(audio)

    assertThat(source.voices).containsExactly("audio" to "a")
    assertThat(audio.exists()).isFalse()
    val state = viewModel.state.value
    assertThat(state.replyTarget).isNull()
    assertThat(state.sending).isFalse()
    assertThat(state.notice).isEqualTo(FeedPostDetailState.Notice.VOICE_PENDING)
  }

  @Test
  fun `voice comments turned off hide the mic, a rejected file says it failed`() {
    val source = FakeSource(fresh = post, pages = emptyMap()).apply { voiceResult = { throw FeedApiException(403, "voice_comments_off") } }
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.sendVoice(recording())
    assertThat(viewModel.state.value.voiceAllowed).isFalse()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.VOICE_OFF)

    val later = recording()
    viewModel.sendVoice(later)
    assertThat(later.exists()).isFalse()
    assertThat(source.voices.isEmpty()).isTrue()

    val other = FakeSource(fresh = post, pages = emptyMap()).apply { voiceResult = { throw FeedApiException(415, "unsupported_media") } }
    val second = FeedPostDetailViewModel(post, other, dispatcher)
    val audio = recording()
    second.sendVoice(audio)
    assertThat(second.state.value.notice).isEqualTo(FeedPostDetailState.Notice.FAILED)
    assertThat(second.state.value.voiceAllowed).isTrue()
    assertThat(audio.exists()).isFalse()
  }

  @Test
  fun `a tap on the mic says to hold it`() {
    val viewModel = FeedPostDetailViewModel(post, FakeSource(fresh = post, pages = emptyMap()), dispatcher)
    viewModel.onVoiceTooShort()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.HOLD_TO_RECORD)
  }

  @Test
  fun `blank drafts are not sent`() {
    val source = FakeSource(fresh = post, pages = emptyMap())
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.onDraftChanged("   ")
    viewModel.send()

    assertThat(source.posted.isEmpty()).isTrue()
  }

  @Test
  fun `voting shows the counts the engine returns`() {
    val poll = post.copy(poll = FeedPoll(options = listOf("a", "b")))
    val source = FakeSource(fresh = poll, pages = emptyMap())
    val viewModel = FeedPostDetailViewModel(poll, source, dispatcher)

    viewModel.vote(listOf(0))

    assertThat(source.votes).containsExactly(listOf(0))
    assertThat(viewModel.state.value.post.pollState).isEqualTo(FeedPollState(counts = listOf(1, 0), voted = listOf(0)))
    assertThat(viewModel.state.value.notice).isNull()
  }

  @Test
  fun `a refused vote says it failed and leaves the poll as it was`() {
    val poll = post.copy(poll = FeedPoll(options = listOf("a", "b")))
    val source = FakeSource(fresh = poll, pages = emptyMap(), voteResult = { throw FeedApiException(409, code = "already_voted") })
    val viewModel = FeedPostDetailViewModel(poll, source, dispatcher)

    viewModel.vote(listOf(1))

    assertThat(viewModel.state.value.post.pollState).isNull()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.FAILED)
  }

  @Test
  fun `approving shows the reply, declining removes it, only held ones`() {
    val held = comment("h").copy(held = true)
    val other = comment("x").copy(held = true)
    val source = FakeSource(fresh = post, pages = mapOf(null to FeedCommentsPage(comments = listOf(comment("a"), held, other))))
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.approveComment(held)
    viewModel.declineComment(other)
    viewModel.approveComment(comment("a"))

    assertThat(source.moderated).containsExactly("approve p/h", "delete x")
    assertThat(viewModel.state.value.comments.map { it.id to it.held }).containsExactly("a" to false, "h" to false)
  }

  @Test
  fun `a failed approval keeps the reply waiting`() {
    val held = comment("h").copy(held = true)
    val source = FakeSource(fresh = post, pages = mapOf(null to FeedCommentsPage(comments = listOf(held))))
    source.moderationError = FeedApiException(404, code = "not_found")
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    viewModel.approveComment(held)

    assertThat(viewModel.state.value.comments.single().held).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.FAILED)
  }

  @Test
  fun `my post's pin and insights work from the detail page, a fourth pin turns back`() {
    val mine = post.copy(viewer = FeedViewer(isAuthor = true))
    val source = FakeSource(fresh = mine, pages = emptyMap())
    val viewModel = FeedPostDetailViewModel(mine, source, dispatcher)

    viewModel.onOwnPostAction(FeedOwnPostAction.HIDE_LIKES)
    assertThat(viewModel.state.value.post.stats.likeCountHidden).isTrue()
    viewModel.onOwnPostAction(FeedOwnPostAction.INSIGHTS)
    assertThat((viewModel.state.value.insights as FeedInsightsState.Ready).insights.opens).isEqualTo(9)

    source.settingsError = FeedApiException(409, code = "too_many_pinned")
    viewModel.onOwnPostAction(FeedOwnPostAction.PIN)
    assertThat(viewModel.state.value.post.pinned).isFalse()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.PIN_LIMIT)
    assertThat(source.updates).containsExactly(FeedPostSettingsUpdate(likeCountHidden = true))
  }

  @Test
  fun `deleting my post closes the page, a failed delete stays`() {
    val source = FakeSource(fresh = post, pages = emptyMap())
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)

    source.moderationError = IllegalStateException("offline")
    viewModel.deletePost()
    assertThat(viewModel.state.value.deleted).isFalse()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.FAILED)

    source.moderationError = null
    viewModel.deletePost()
    assertThat(viewModel.state.value.deleted).isTrue()
    assertThat(source.moderated).containsExactly("delete p")
  }
}
