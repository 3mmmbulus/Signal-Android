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
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import org.signal.mediasend.screens.select.MediaSelectState

/**
 * Tellomi（tellomi/tellomi#1263 P-5，ADR-0068 §4.4）：「···」→ 罩住图片 ⇄ 取消遮罩作用于全部已选；取消勾选就拿掉；
 * 发送请求带着罩住的那几张；按文件发、一次性查看的不罩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelSpoilerTest {

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
  fun `Given two photos, when hide with spoiler is toggled twice, then both are covered and then neither`() = runTest {
    val viewModel = viewModel()

    send(viewModel, MediaSendFlowEvent.ToggleSpoiler)
    assertThat(viewModel.state.value.selectedSpoileredUris).containsExactlyInAnyOrder(PHOTO_A.uri, PHOTO_B.uri)
    assertThat(MediaSelectState.SendOptions.from(viewModel.state.value).spoileredUris).containsExactlyInAnyOrder(PHOTO_A.uri, PHOTO_B.uri)

    send(viewModel, MediaSendFlowEvent.ToggleSpoiler)
    assertThat(viewModel.state.value.selectedSpoileredUris).isEmpty()
  }

  @Test
  fun `Given a covered photo, when it is unselected and selected again, then it is not covered`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleSpoiler)

    send(viewModel, MediaSendFlowEvent.RemoveMedia(setOf(PHOTO_A)))
    assertThat(viewModel.state.value.tellomiSpoileredUris).containsExactlyInAnyOrder(PHOTO_B.uri)

    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    assertThat(viewModel.state.value.selectedSpoileredUris).containsExactlyInAnyOrder(PHOTO_B.uri)
  }

  @Test
  fun `Given covered photos, when sent, then the request carries them`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleSpoiler)

    send(viewModel, MediaSendFlowEvent.NextRequested)

    assertThat(requests.single().spoileredUris).containsExactlyInAnyOrder(PHOTO_A.uri, PHOTO_B.uri)
  }

  @Test
  fun `Given covered photos, when sent as files or as view once, then nothing is covered`() = runTest {
    val asFiles = viewModel()
    send(asFiles, MediaSendFlowEvent.ToggleSpoiler)
    send(asFiles, MediaSendFlowEvent.SendNow(asFile = true))
    assertThat(requests.last().spoileredUris).isEmpty()

    val viewOnce = viewModel(media = listOf(PHOTO_A))
    send(viewOnce, MediaSendFlowEvent.ToggleSpoiler)
    send(viewOnce, MediaSendFlowEvent.ToggleViewOnce)
    send(viewOnce, MediaSendFlowEvent.NextRequested)
    assertThat(requests.last().isViewOnce).isEqualTo(true)
    assertThat(requests.last().spoileredUris).isEmpty()
  }

  private fun viewModel(media: List<Media> = listOf(PHOTO_A, PHOTO_B)): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(
          recipientId = RECIPIENT,
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
    private val PHOTO_A = photo("content://media/1")
    private val PHOTO_B = photo("content://media/2")

    // image/gif skips the image editor model, which Robolectric cannot build (Path.toggleInverseFillType).
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
