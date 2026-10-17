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
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：选图里的实况开关——已选里有动态照片才出现、默认关、作用于全部已选；
 * 查看器里可以单改一张（没选的先选上）；取消勾选就拿掉逐张的开关；发送请求带着开着的那几张和视频的位置；按文件、一次性查看的不带。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelLiveTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val mainDispatcher = StandardTestDispatcher()
  private val repository = dependencies.mediaSendRepository
  private val preUploadController: PreUploadController = mockk(relaxed = true)
  private val requests = mutableListOf<SendRequest>()

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
  fun `Given a live photo among the selected, then the switch shows and is off`() = runTest {
    val viewModel = viewModel()
    advanceUntilIdle()

    val state = viewModel.state.value
    assertThat(state.selectedLiveUris).containsExactly(LIVE_A.uri, LIVE_B.uri)
    assertThat(state.isTellomiLiveToggleOn).isFalse()
    val options = MediaSelectState.SendOptions.from(state)
    assertThat(options.canLive).isTrue()
    assertThat(options.hasSelectedLive).isTrue()
    assertThat(options.isLiveToggleOn).isFalse()
    assertThat(options.liveOnUris).isEmpty()

    val onlyStill = viewModel(media = listOf(STILL))
    advanceUntilIdle()
    assertThat(MediaSelectState.SendOptions.from(onlyStill.state.value).hasSelectedLive).isFalse()
  }

  @Test
  fun `Given live photos, when the switch is pressed twice, then all are on and then all are off`() = runTest {
    val viewModel = viewModel()

    send(viewModel, MediaSendFlowEvent.ToggleLive)
    assertThat(viewModel.state.value.isTellomiLiveToggleOn).isTrue()
    assertThat(viewModel.state.value.tellomiLiveDefault).isTrue()
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).liveOnUris).containsExactlyInAnyOrder(LIVE_A.uri, LIVE_B.uri)

    send(viewModel, MediaSendFlowEvent.ToggleLive)
    assertThat(viewModel.state.value.isTellomiLiveToggleOn).isFalse()
    assertThat(viewModel.state.value.selectedLiveVideos).isEmpty()
  }

  @Test
  fun `Given one live photo turned off in the viewer, then the switch is off and pressing it turns all on`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleLive)

    send(viewModel, MediaSendFlowEvent.SetLive(LIVE_B, isOn = false))
    assertThat(viewModel.state.value.isTellomiLiveToggleOn).isFalse()
    assertThat(viewModel.state.value.selectedLiveVideos.keys).containsExactlyInAnyOrder(LIVE_A.uri)

    send(viewModel, MediaSendFlowEvent.ToggleLive)
    assertThat(viewModel.state.value.selectedLiveVideos.keys).containsExactlyInAnyOrder(LIVE_A.uri, LIVE_B.uri)
  }

  @Test
  fun `Given an unselected live photo in the viewer, when its live is turned on, then it is selected and on`() = runTest {
    val viewModel = viewModel(media = listOf(STILL))
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(LIVE_A, listOf(STILL, LIVE_A)))
    assertThat(MediaEditState().withParentState(viewModel.state.value).albumLiveStates).isEqualTo(mapOf(LIVE_A.uri to false))

    send(viewModel, MediaSendFlowEvent.SetLive(LIVE_A, isOn = true))

    assertThat(viewModel.state.value.selectedMedia.map { it.uri }).containsExactly(STILL.uri, LIVE_A.uri)
    assertThat(viewModel.state.value.isTellomiLiveOn(LIVE_A.uri)).isTrue()
    assertThat(MediaEditState().withParentState(viewModel.state.value).albumLiveStates).isEqualTo(mapOf(LIVE_A.uri to true))
  }

  @Test
  fun `Given a live photo set in the viewer, when it is unselected and selected again, then it follows the switch`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.SetLive(LIVE_A, isOn = true))
    assertThat(viewModel.state.value.isTellomiLiveOn(LIVE_A.uri)).isTrue()

    send(viewModel, MediaSendFlowEvent.RemoveMedia(setOf(LIVE_A)))
    assertThat(viewModel.state.value.tellomiLiveOverrides).isEmpty()

    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(LIVE_A))
    assertThat(viewModel.state.value.isTellomiLiveOn(LIVE_A.uri)).isFalse()
  }

  @Test
  fun `Given live photos turned on, when sent, then the request carries them and where their video is`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleLive)
    send(viewModel, MediaSendFlowEvent.SetLive(LIVE_B, isOn = false))

    send(viewModel, MediaSendFlowEvent.NextRequested)

    assertThat(requests.single().liveVideos).isEqualTo(mapOf(LIVE_A.uri to RANGES.getValue(LIVE_A.uri)))
  }

  @Test
  fun `Given live photos turned on, when sent as files or as view once, then no video goes`() = runTest {
    val asFiles = viewModel()
    send(asFiles, MediaSendFlowEvent.ToggleLive)
    send(asFiles, MediaSendFlowEvent.SendNow(asFile = true))
    assertThat(requests.last().liveVideos).isEmpty()

    val viewOnce = viewModel(media = listOf(LIVE_A))
    send(viewOnce, MediaSendFlowEvent.ToggleLive)
    send(viewOnce, MediaSendFlowEvent.ToggleViewOnce)
    send(viewOnce, MediaSendFlowEvent.NextRequested)
    assertThat(requests.last().isViewOnce).isTrue()
    assertThat(requests.last().liveVideos).isEmpty()
  }

  @Test
  fun `Given a flow that is not a known conversation, then live photos are not looked for`() = runTest {
    val viewModel = viewModel(recipientId = null)
    advanceUntilIdle()

    assertThat(viewModel.state.value.tellomiLiveRanges).isEmpty()
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).hasSelectedLive).isFalse()
    assertThat(viewModel.detectLivePhoto(LIVE_A)).isNull()
    coVerify(exactly = 0) { repository.tellomiLivePhotoVideo(any()) }
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
