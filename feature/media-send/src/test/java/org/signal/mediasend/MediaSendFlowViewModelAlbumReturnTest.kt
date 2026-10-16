/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.coEvery
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
import org.robolectric.annotation.GraphicsMode
import org.signal.core.models.media.Media
import org.signal.mediasend.screens.edit.ImageController

/**
 * 两端对齐清单 P-6b：从查看器底部打开的工具（裁剪、绘制、调整）用完——完成或取消——回到查看器、停在这一张；改过的那张查看器里照改过的
 * 样子显示（另画一张图），没改过的还是原图。同 Telegram 两端、Tellomi iOS（#234）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaSendFlowViewModelAlbumReturnTest {

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

  @Test
  fun `Given a tool opened from the album, when it is cancelled, then the album comes back on that picture`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_B, album = ALBUM))
    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_B, AlbumEditTool.DRAW))
    assertThat(viewModel.state.value.isBrowsingAlbum).isFalse()

    controllerOf(viewModel, PHOTO_B).cancelEdit()
    settle(viewModel) { it.isBrowsingAlbum }

    val state = viewModel.state.value
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(state.albumFocusedMedia?.uri).isEqualTo(PHOTO_B.uri)
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
    // 有没有另画一张，和发送时会不会按编辑重画是同一个判断（`EditorModel.isChanged`）：查看器里看到的就是发出去的样子
    assertThat(state.albumEditPreviews.containsKey(PHOTO_B.uri)).isEqualTo(modelOf(viewModel, PHOTO_B).isChanged)
  }

  @Test
  fun `Given an unedited picture, when the album is opened, then it shows the original`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_A, album = ALBUM))
    settle(viewModel) { false }

    assertThat(viewModel.state.value.albumEditPreviews[PHOTO_A.uri]).isNull()
  }

  @Test
  fun `Given a crop opened from the album, when it is done with a change, then the album comes back showing the edited picture`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_A, album = ALBUM))
    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_A, AlbumEditTool.CROP))
    val controller = controllerOf(viewModel, PHOTO_A)
    assertThat(controller.mode).isEqualTo(ImageController.Mode.CROP)

    modelOf(viewModel, PHOTO_A).rotate90anticlockwise()
    controller.commitEdit()
    settle(viewModel) { it.isBrowsingAlbum }

    val state = viewModel.state.value
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(state.albumFocusedMedia?.uri).isEqualTo(PHOTO_A.uri)
    assertThat(state.albumEditPreviews[PHOTO_A.uri]).isNotNull()
  }

  @Test
  fun `Given a tool opened from the album, when the editor is left first, then the album does not come back`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_A, album = ALBUM))
    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_A, AlbumEditTool.DRAW))

    // 编辑页被关掉（返回到网格），这时工具才结束
    viewModel.backStack.pop()
    controllerOf(viewModel, PHOTO_A).cancelEdit()
    Snapshot.sendApplyNotifications()
    advanceUntilIdle()

    assertThat(viewModel.state.value.isBrowsingAlbum).isFalse()
  }

  @Test
  fun `Given a picture edited in the editor, when the album is opened, then that picture shows as edited and a removed one loses its preview`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_A, album = ALBUM))
    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_A, AlbumEditTool.CROP))
    modelOf(viewModel, PHOTO_A).rotate90anticlockwise()
    controllerOf(viewModel, PHOTO_A).commitEdit()
    settle(viewModel) { it.albumEditPreviews.containsKey(PHOTO_A.uri) }

    // 关掉查看器再从网格打开：改过的那张重新画
    send(viewModel, MediaSendFlowEvent.CloseAlbumViewer)
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_B, album = ALBUM))
    settle(viewModel) { it.albumEditPreviews.containsKey(PHOTO_A.uri) }
    assertThat(viewModel.state.value.albumEditPreviews.containsKey(PHOTO_A.uri)).isTrue()

    // 取消选中：编辑没了，预览也跟着没
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    assertThat(viewModel.state.value.albumEditPreviews.containsKey(PHOTO_A.uri)).isFalse()
  }

  private fun viewModel(): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(),
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = repository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    ).apply {
      // 真画要用 Glide 读原图，单测环境没有：换成一张小图，只看「改过才画、画了才回」
      albumEditPreviewRenderer = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
    }
  }

  private fun modelOf(viewModel: MediaSendFlowViewModel, media: Media) = (viewModel.state.value.editorStateMap[media.uri] as EditorState.Image).model

  private fun controllerOf(viewModel: MediaSendFlowViewModel, media: Media): ImageController {
    return viewModel.imageControllers.getOrCreate(media.uri, modelOf(viewModel, media))
  }

  private fun TestScope.send(viewModel: MediaSendFlowViewModel, event: MediaSendFlowEvent) {
    viewModel.onEvent(event)
    advanceUntilIdle()
  }

  /**
   * 工具结束是编辑器里的 Compose 状态，真机上每一帧都会发出变化通知，这里手动发；改过的那张在后台线程上画，画完才切回查看器，所以多等几轮。
   */
  private fun TestScope.settle(viewModel: MediaSendFlowViewModel, done: (MediaSendFlowState) -> Boolean) {
    repeat(100) {
      Snapshot.sendApplyNotifications()
      advanceUntilIdle()
      if (done(viewModel.state.value)) {
        return
      }
      Thread.sleep(20)
    }
  }

  private companion object {
    // 要真的图像编辑模型，只能是静态图（GIF 不建模型）
    private val PHOTO_A = photo("content://media/11")
    private val PHOTO_B = photo("content://media/12")
    private val ALBUM = listOf(PHOTO_A, PHOTO_B)

    private fun photo(uri: String) = Media(
      uri = uri.toUri(),
      contentType = "image/jpeg",
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
