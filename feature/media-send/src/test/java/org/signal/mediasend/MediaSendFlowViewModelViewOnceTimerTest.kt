/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.mediasend.preupload.PreUploadController
import org.signal.mediasend.preupload.PreUploadResult
import org.signal.mediasend.screens.edit.MediaEditState
import org.signal.mediasend.screens.select.MediaSelectState

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4）：查看器里的计时——只在一对一会话里能设；设了就选上；取消勾选拿掉；
 * 有计时的不能按文件发、不能定时发送；发送请求带着各张的计时，计时的不带遮罩、不带实况。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelViewOnceTimerTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val mainDispatcher = StandardTestDispatcher()
  private val repository = dependencies.mediaSendRepository
  private val preUploadController: PreUploadController = mockk(relaxed = true)
  private val requests = mutableListOf<SendRequest>()
  private var oneToOne = true

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
    every { repository.getMediaConstraints(any()) } returns PreviewMediaConstraints
    coEvery { repository.getStorySendRequirements(any()) } returns emptyMap()
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } coAnswers {
      MediaFilterResult(filteredMedia = firstArg(), error = null)
    }
    coEvery { repository.checkUntrustedIdentities(any(), any()) } returns emptyList()
    coEvery { repository.tellomiLivePhotoVideo(any()) } coAnswers { RANGES[firstArg<Media>().uri] }
    coEvery { repository.tellomiCanSetViewOnceTimer(any()) } coAnswers { oneToOne }
    val request = slot<SendRequest>()
    coEvery { repository.send(capture(request)) } coAnswers {
      requests += request.captured
      SendResult.Success
    }
    every { preUploadController.getPreUploadResults(any()) } answers {
      firstArg<(Collection<PreUploadResult>) -> Unit>().invoke(emptyList())
    }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `Given a one to one chat, when a timer is set on an unselected photo, then it is selected and timed`() = runTest {
    val viewModel = viewModel(media = listOf(STILL))
    advanceUntilIdle()
    assertThat(viewModel.state.value.tellomiCanSetViewOnceTimer).isTrue()
    assertThat(MediaEditState().withParentState(viewModel.state.value).canSetViewOnceTimer).isTrue()

    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(LIVE_A, 10))

    assertThat(viewModel.state.value.selectedMedia.map { it.uri }).containsExactly(STILL.uri, LIVE_A.uri)
    assertThat(viewModel.state.value.selectedViewOnceChoices).isEqualTo(mapOf(LIVE_A.uri to 10))
    assertThat(MediaEditState().withParentState(viewModel.state.value).viewOnceChoices).isEqualTo(mapOf(LIVE_A.uri to 10))
  }

  @Test
  fun `Given a timed photo, when Do Not Delete is chosen or it is unselected, then the timer goes`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(STILL, TellomiViewOnceChoice.VIEW_ONCE))
    assertThat(viewModel.state.value.hasTimedSelection).isTrue()

    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(STILL, null))
    assertThat(viewModel.state.value.tellomiViewOnceChoices).isEmpty()
    assertThat(viewModel.state.value.selectedMedia.map { it.uri }).containsExactly(STILL.uri, LIVE_A.uri, LIVE_B.uri)

    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(STILL, 3))
    send(viewModel, MediaSendFlowEvent.RemoveMedia(setOf(STILL)))
    assertThat(viewModel.state.value.tellomiViewOnceChoices).isEmpty()
  }

  @Test
  fun `Given a timed photo, then it cannot be sent as a file or scheduled`() = runTest {
    val viewModel = viewModel()
    advanceUntilIdle()
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).canSendAsFile).isTrue()
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).canScheduleSend).isTrue()

    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(STILL, 30))
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).canSendAsFile).isFalse()
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).canScheduleSend).isFalse()
    assertThat(MediaEditState().withParentState(viewModel.state.value).hasTimedSelection).isTrue()
  }

  @Test
  fun `Given timers, when sent, then the request carries them and timed photos go without spoiler or live`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleLive)
    send(viewModel, MediaSendFlowEvent.ToggleSpoiler)
    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(LIVE_A, 10))

    send(viewModel, MediaSendFlowEvent.NextRequested)

    val request = requests.single()
    assertThat(request.viewOnceChoices).isEqualTo(mapOf(LIVE_A.uri to 10))
    assertThat(request.isViewOnce).isFalse()
    assertThat(request.spoileredUris).isEqualTo(setOf(STILL.uri, LIVE_B.uri))
    assertThat(request.liveVideos.keys).containsExactlyInAnyOrder(LIVE_B.uri)
  }

  @Test
  fun `Given a chat that is not one to one, then timers cannot be set`() = runTest {
    oneToOne = false
    val viewModel = viewModel()
    advanceUntilIdle()
    assertThat(viewModel.state.value.tellomiCanSetViewOnceTimer).isFalse()

    send(viewModel, MediaSendFlowEvent.SetViewOnceChoice(STILL, 10))
    assertThat(viewModel.state.value.tellomiViewOnceChoices).isEmpty()
  }

  private fun viewModel(media: List<Media> = listOf(STILL, LIVE_A, LIVE_B), recipientId: MediaRecipientId? = RECIPIENT): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(
          recipientId = recipientId,
          mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
          initialMedia = media
        ),
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = repository,
      preUploadController = preUploadController,
      isMeteredFlow = flowOf(false)
    )
  }

  private fun TestScope.send(viewModel: MediaSendFlowViewModel, event: MediaSendFlowEvent) {
    advanceUntilIdle()
    viewModel.onEvent(event)
    advanceUntilIdle()
  }

  private companion object {
    private val RECIPIENT = MediaRecipientId(7L)
    private val STILL = photo("content://media/1")
    private val LIVE_A = photo("content://media/2")
    private val LIVE_B = photo("content://media/3")

    private val RANGES = mapOf(
      LIVE_A.uri to TellomiMotionPhoto.VideoRange(offset = 3_000_000, length = 2_000_000),
      LIVE_B.uri to TellomiMotionPhoto.VideoRange(offset = 2_500_000, length = 1_500_000)
    )

    // image/gif skips the image editor model, which Robolectric cannot build (Path.toggleInverseFillType). Whether a
    // photo is a Motion Photo comes from the repository here, so the type doesn't matter.
    private fun photo(uri: String) = Media(
      uri = uri.toUri(),
      contentType = "image/gif",
      date = 0,
      width = 100,
      height = 200,
      size = 1024,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      caption = null,
      transformProperties = null,
      fileName = null
    )
  }
}
