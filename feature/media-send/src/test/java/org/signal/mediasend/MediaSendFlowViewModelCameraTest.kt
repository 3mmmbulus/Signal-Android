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
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/**
 * Tellomi（tellomi/tellomi#1520 C-7，owner 定 O1：照 Telegram 聊天路径）：相机里第一张进编辑页去看，在那里返回就丢掉它；
 * 「再拍」回到相机、拍下的都留着，之后拍的留在相机（胶片条）。快拍照旧每张都进编辑页。
 *
 * Telegram reference：Android `ChatAttachAlertPhotoLayout.openPhotoViewer`（`cameraPhotos.size() > 1` 就留在相机）、
 * `cancelButtonPressed`（只拍了一张就删掉）、`needAddMorePhotos`（回相机接着拍）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelCameraTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val mainDispatcher = StandardTestDispatcher()
  private val repository = dependencies.mediaSendRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
    every { repository.getMediaConstraints(any()) } returns PreviewMediaConstraints
    coEvery { repository.getStorySendRequirements(any()) } returns emptyMap()
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } coAnswers {
      MediaFilterResult(filteredMedia = firstArg(), error = null)
    }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  //region The chat's "+" sheet, where the camera sits over the picker

  @Test
  fun `Given the camera from the picker, when the first capture comes in, then it opens in the editor`() = runTest {
    val viewModel = pickerCamera()

    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))

    val state = viewModel.state.value
    assertThat(viewModel.backStack.toList()).containsExactly(PICKER, MediaSendRoute.Capture.Camera, MediaSendRoute.Edit)
    assertThat(state.selectedMedia).containsExactly(CAPTURE_A)
    assertThat(state.focusedMedia).isEqualTo(CAPTURE_A)
    assertThat(state.cameraFirstCapture).isEqualTo(CAPTURE_A)
    assertThat(state.selectedCameraCaptures).containsExactly(CAPTURE_A)
  }

  @Test
  fun `Given the first capture in the editor, when backing out, then it is thrown away and the camera is back`() = runTest {
    val viewModel = pickerCamera()
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))

    send(viewModel, MediaSendFlowEvent.NavigateBackFromEdit)

    val state = viewModel.state.value
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Capture.Camera)
    assertThat(state.selectedMedia).isEmpty()
    assertThat(state.cameraCaptureUris).isEmpty()
    coVerify { repository.deleteBlobs(listOf(CAPTURE_A)) }
  }

  @Test
  fun `Given the first capture in the editor, when taking more, then it is kept and the camera is back`() = runTest {
    val viewModel = pickerCamera()
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))

    send(viewModel, MediaSendFlowEvent.KeepShooting)

    val state = viewModel.state.value
    assertThat(viewModel.backStack.toList()).containsExactly(PICKER, MediaSendRoute.Capture.Camera)
    assertThat(state.selectedMedia).containsExactly(CAPTURE_A)
    assertThat(state.cameraFirstCapture).isNull()
  }

  @Test
  fun `Given a capture kept in the camera, when the next one comes in, then the camera stays and the filmstrip grows`() = runTest {
    val viewModel = pickerCamera()
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    send(viewModel, MediaSendFlowEvent.KeepShooting)

    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_C))

    val state = viewModel.state.value
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Capture.Camera)
    assertThat(state.selectedMedia).containsExactly(CAPTURE_A, CAPTURE_B, CAPTURE_C)
    assertThat(state.selectedCameraCaptures).containsExactly(CAPTURE_A, CAPTURE_B, CAPTURE_C)
    assertThat(state.focusedMedia).isEqualTo(CAPTURE_C)
  }

  @Test
  fun `Given captures in the filmstrip, when one is tapped, then the editor opens on it`() = runTest {
    val viewModel = pickerCamera()
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    send(viewModel, MediaSendFlowEvent.KeepShooting)
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))

    send(viewModel, MediaSendFlowEvent.OpenInEditor(CAPTURE_A))

    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
    assertThat(viewModel.state.value.focusedMedia).isEqualTo(CAPTURE_A)
  }

  /** Picks from the grid are the user's own and are not the camera's to throw away, so backing out keeps everything. */
  @Test
  fun `Given pictures already picked, when the first capture comes in, then backing out of the editor would keep it`() = runTest {
    val viewModel = pickerCamera(alreadyPicked = PICKED)

    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))

    val state = viewModel.state.value
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
    assertThat(state.selectedMedia).containsExactly(PICKED, CAPTURE_A)
    assertThat(state.cameraFirstCapture).isNull()
    assertThat(state.selectedCameraCaptures).containsExactly(CAPTURE_A)
  }

  @Test
  fun `Given a capture is removed from the selection, then it leaves the filmstrip for good`() = runTest {
    val viewModel = pickerCamera()
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    send(viewModel, MediaSendFlowEvent.KeepShooting)
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))

    send(viewModel, MediaSendFlowEvent.RemoveMedia(setOf(CAPTURE_A)))

    val state = viewModel.state.value
    assertThat(state.cameraCaptureUris).containsExactly(CAPTURE_B.uri)
    assertThat(state.selectedCameraCaptures).containsExactly(CAPTURE_B)
  }

  //endregion

  //region Opened straight into the camera

  @Test
  fun `Given a camera-first chat, when shooting on after the first capture, then later captures stay in the camera`() = runTest {
    val viewModel = viewModel(MediaSendFlowActivityContract.Args(isCameraFirst = true))
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    assertThat(viewModel.backStack.toList()).containsExactly(MediaSendRoute.Capture.Camera, MediaSendRoute.Edit)

    send(viewModel, MediaSendFlowEvent.KeepShooting)
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))

    assertThat(viewModel.backStack.toList()).containsExactly(MediaSendRoute.Capture.Camera)
    assertThat(viewModel.state.value.selectedMedia).containsExactly(CAPTURE_A, CAPTURE_B)
  }

  @Test
  fun `Given a camera-first chat, when backing out of the first capture, then the next capture opens the editor again`() = runTest {
    val viewModel = viewModel(MediaSendFlowActivityContract.Args(isCameraFirst = true))
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    send(viewModel, MediaSendFlowEvent.NavigateBackFromEdit)

    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))

    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
    assertThat(viewModel.state.value.selectedMedia).containsExactly(CAPTURE_B)
    assertThat(viewModel.state.value.cameraFirstCapture).isEqualTo(CAPTURE_B)
  }

  /** A story is still edited one capture at a time, as upstream does. */
  @Test
  fun `Given a story, when another capture comes in, then it still opens in the editor`() = runTest {
    val viewModel = viewModel(MediaSendFlowActivityContract.Args(isCameraFirst = true, isStory = true))
    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_A))
    send(viewModel, MediaSendFlowEvent.KeepShooting)

    send(viewModel, MediaSendFlowEvent.MediaCaptured(CAPTURE_B))

    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
    assertThat(viewModel.state.value.focusedMedia).isEqualTo(CAPTURE_B)
  }

  //endregion

  /** The chat's "+" sheet: the picker grid with the camera opened over it, and [alreadyPicked] ticked beforehand. */
  private fun TestScope.pickerCamera(alreadyPicked: Media? = null): MediaSendFlowViewModel {
    val viewModel = viewModel(MediaSendFlowActivityContract.Args())
    if (alreadyPicked != null) {
      send(viewModel, MediaSendFlowEvent.AddMedia(setOf(alreadyPicked)))
    }
    send(viewModel, MediaSendFlowEvent.NavigateToCamera)
    assertThat(viewModel.backStack.toList()).containsExactly(PICKER, MediaSendRoute.Capture.Camera)
    return viewModel
  }

  private fun viewModel(args: MediaSendFlowActivityContract.Args): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to args,
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = repository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
  }

  private fun TestScope.send(viewModel: MediaSendFlowViewModel, event: MediaSendFlowEvent) {
    viewModel.onEvent(event)
    advanceUntilIdle()
  }

  private companion object {
    private val PICKER = MediaSendRoute.Select.Files(recentsFolder())

    private val CAPTURE_A = media("content://org.signal.blob/capture/1")
    private val CAPTURE_B = media("content://org.signal.blob/capture/2")
    private val CAPTURE_C = media("content://org.signal.blob/capture/3")
    private val PICKED = media("content://media/external/images/9")

    // image/gif skips the image editor model, which Robolectric cannot build (Path.toggleInverseFillType).
    private fun media(uri: String) = Media(
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
