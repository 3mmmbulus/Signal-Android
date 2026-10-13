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
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * Tellomi（tellomi/tellomi#1263 P-5「以文件发送」，ADR-0068）：「···」→ 以文件发送，交给会话页的是原图——不等、不用预上传的结果
 * （那是压缩过的），已经在传的停掉、删掉；HEIC 的选择跟着这次发送走。只管这一次：停在安全码确认上没发出去的，
 * 之后点底栏的发送键照常整组发。「说明文字上移」（ADR-0068 §4.7）也在这里测：跟着这次发送走，按文件发的不适用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelSendAsFileTest {

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
  fun `Given two photos, when they are sent as files, then the request is as files without pre-uploads and those are dropped`() = runTest {
    val viewModel = viewModel()

    send(viewModel, MediaSendFlowEvent.SendNow(asFile = true, convertHeicToJpeg = true))

    val request = requests.single()
    assertThat(request.sendAsFile).isTrue()
    assertThat(request.convertHeicToJpeg).isTrue()
    assertThat(request.sendSeparately).isFalse()
    assertThat(request.preUploadResults).isEmpty()
    verify(exactly = 0) { preUploadController.getPreUploadResults(any()) }
    verifyOrder {
      preUploadController.cancelAllUploads()
      preUploadController.deleteAbandonedAttachments()
    }
  }

  @Test
  fun `Given HEIC is kept, when sent as files, then nothing is converted`() = runTest {
    val viewModel = viewModel()

    send(viewModel, MediaSendFlowEvent.SendNow(asFile = true, convertHeicToJpeg = false))

    assertThat(requests.single().sendAsFile).isTrue()
    assertThat(requests.single().convertHeicToJpeg).isFalse()
  }

  @Test
  fun `Given send as files stopped at a safety number change, when the change is accepted, then it is still sent as files`() = runTest {
    coEvery { repository.checkUntrustedIdentities(any(), any()) } returnsMany listOf(listOf(RECIPIENT.id), emptyList())
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.SendNow(asFile = true))
    assertThat(requests).isEmpty()

    viewModel.performSend()
    advanceUntilIdle()

    assertThat(requests.single().sendAsFile).isTrue()
  }

  @Test
  fun `Given send as files stopped at a safety number change, when the send button is used instead, then it is sent as usual`() = runTest {
    coEvery { repository.checkUntrustedIdentities(any(), any()) } returnsMany listOf(listOf(RECIPIENT.id), emptyList())
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.SendNow(asFile = true))

    send(viewModel, MediaSendFlowEvent.NextRequested)

    val request = requests.single()
    assertThat(request.sendAsFile).isFalse()
    verify(exactly = 1) { preUploadController.getPreUploadResults(any()) }
    coVerify(exactly = 1) { repository.send(any()) }
  }

  /** M-2（ADR-0068 §4.7）：「说明文字上移」跟着这次发送走；按文件发的不适用；再点一次就回到下方。 */
  @Test
  fun `Given the caption was moved up, when sent, then the request has it, but not when sent as files`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleCaptionAboveMedia)
    assertThat(viewModel.state.value.isCaptionAboveMedia).isTrue()

    send(viewModel, MediaSendFlowEvent.SendNow(separately = true))
    assertThat(requests.last().captionAboveMedia).isTrue()

    val asFiles = viewModel()
    send(asFiles, MediaSendFlowEvent.ToggleCaptionAboveMedia)
    send(asFiles, MediaSendFlowEvent.SendNow(asFile = true))
    assertThat(requests.last().captionAboveMedia).isFalse()
  }

  @Test
  fun `Given the caption was moved up, when toggled again, then it is below`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleCaptionAboveMedia)
    send(viewModel, MediaSendFlowEvent.ToggleCaptionAboveMedia)

    send(viewModel, MediaSendFlowEvent.NextRequested)

    assertThat(requests.single().captionAboveMedia).isFalse()
  }

  @Test
  fun `Given the files page, when select from gallery opens, then the sheet is gone and cancelling leaves the flow`() = runTest {
    val viewModel = viewModel()
    val commands = mutableListOf<MediaSendFlowHudCommand>()
    val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.hudCommands.collect { commands += it } }
    advanceUntilIdle()

    send(viewModel, MediaSendFlowEvent.OpenFilesGallery)
    assertThat(viewModel.backStack.toList()).containsExactly(MediaSendRoute.FilesGallery)
    assertThat(commands).isEmpty()

    send(viewModel, MediaSendFlowEvent.CloseFilesGallery)
    assertThat(commands).containsExactly(MediaSendFlowHudCommand.CloseScreen)
    collecting.cancel()
  }

  private fun viewModel(): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(
          recipientId = RECIPIENT,
          mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
          initialMedia = listOf(PHOTO_A, PHOTO_B)
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
