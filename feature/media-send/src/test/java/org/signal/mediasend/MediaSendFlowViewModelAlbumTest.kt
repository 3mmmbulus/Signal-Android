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
import assertk.assertions.isFalse
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
import org.signal.core.models.media.Media

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelAlbumTest {

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
  fun `Given an album, when a picture in it is opened, then the album is browsed and nothing is selected`() = runTest {
    val viewModel = viewModel()

    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = PHOTO_B, album = ALBUM))

    val state = viewModel.state.value
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(state.album).isEqualTo(ALBUM)
    assertThat(state.albumFocusedMedia).isEqualTo(PHOTO_B)
    assertThat(state.selectedMedia).isEmpty()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given a picture is browsed, when it is ticked, then it is selected and the browsing position stays put`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)

    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_B))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).containsExactly(PHOTO_B)
    assertThat(state.albumFocusedMedia).isEqualTo(PHOTO_A)
    assertThat(state.isBrowsingAlbum).isTrue()
  }

  @Test
  fun `Given a picture is ticked, when it is ticked again, then it is deselected and the album stays open`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))

    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).isEmpty()
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given the album is browsed, when the picture is refused, then the album stays open with nothing selected`() = runTest {
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } returns
      MediaFilterResult(filteredMedia = emptyList(), error = MediaFilterError.ItemTooLarge(media = PHOTO_A))
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)

    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).isEmpty()
    assertThat(state.isSelectionRejected).isTrue()
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given the editor with nothing selected, when a picture is refused, then the picker is shown again`() = runTest {
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } returns
      MediaFilterResult(filteredMedia = emptyList(), error = MediaFilterError.ItemTooLarge(media = PHOTO_A))
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.NavigateToEdit)

    send(viewModel, MediaSendFlowEvent.AddMedia(setOf(PHOTO_A)))

    assertThat(viewModel.backStack.lastOrNull() is MediaSendRoute.Select).isTrue()
  }

  @Test
  fun `Given the browsed picture is selected, when the count is tapped, then the filmstrip opens on that picture`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_B))
    send(viewModel, MediaSendFlowEvent.SetAlbumFocusedMedia(PHOTO_B))

    send(viewModel, MediaSendFlowEvent.ShowSelectedFilmstrip)

    val state = viewModel.state.value
    assertThat(state.isBrowsingAlbum).isFalse()
    assertThat(state.focusedMedia).isEqualTo(PHOTO_B)
  }

  @Test
  fun `Given the browsed picture is not selected, when the count is tapped, then the filmstrip opens on the focused selection`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_B))
    send(viewModel, MediaSendFlowEvent.SetFocusedMedia(PHOTO_B))
    send(viewModel, MediaSendFlowEvent.SetAlbumFocusedMedia(PHOTO_C))

    send(viewModel, MediaSendFlowEvent.ShowSelectedFilmstrip)

    val state = viewModel.state.value
    assertThat(state.isBrowsingAlbum).isFalse()
    assertThat(state.focusedMedia).isEqualTo(PHOTO_B)
  }

  @Test
  fun `Given the album is browsed with a picture selected, when that picture is opened in the editor, then the album is left`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))

    send(viewModel, MediaSendFlowEvent.OpenInEditor(PHOTO_A))

    assertThat(viewModel.state.value.isBrowsingAlbum).isFalse()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given a browsed picture that is not selected, when a shortcut is tapped, then it is selected, focused and opened in the editor`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))

    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_C, AlbumEditTool.CROP))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).containsExactly(PHOTO_A, PHOTO_C)
    assertThat(state.focusedMedia).isEqualTo(PHOTO_C)
    assertThat(state.isBrowsingAlbum).isFalse()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given a browsed picture that is already selected, when a shortcut is tapped, then the selection keeps its order and that picture is focused`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_A))
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_B))

    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_A, AlbumEditTool.DRAW))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).containsExactly(PHOTO_A, PHOTO_B)
    assertThat(state.focusedMedia).isEqualTo(PHOTO_A)
    assertThat(state.isBrowsingAlbum).isFalse()
  }

  @Test
  fun `Given the shortcut's picture is refused, then the album stays open with nothing selected`() = runTest {
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } returns
      MediaFilterResult(filteredMedia = emptyList(), error = MediaFilterError.ItemTooLarge(media = PHOTO_B))
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_B)

    send(viewModel, MediaSendFlowEvent.EditFromAlbum(PHOTO_B, AlbumEditTool.CROP))

    val state = viewModel.state.value
    assertThat(state.selectedMedia).isEmpty()
    assertThat(state.isBrowsingAlbum).isTrue()
    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
  }

  @Test
  fun `Given the album is browsed, when it is swiped closed, then it goes back like the back button and keeps the selection`() = runTest {
    val viewModel = viewModel()
    openAlbum(viewModel, focus = PHOTO_A)
    send(viewModel, MediaSendFlowEvent.ToggleAlbumSelected(PHOTO_B))
    val withAlbum = viewModel.backStack.toList()
    assertThat(withAlbum.last()).isEqualTo(MediaSendRoute.Edit)

    send(viewModel, MediaSendFlowEvent.CloseAlbumViewer)

    assertThat(viewModel.backStack.toList()).isEqualTo(withAlbum.dropLast(1))
    assertThat(viewModel.state.value.selectedMedia).containsExactly(PHOTO_B)
  }

  @Test
  fun `Given the editor is open without the album, when a close arrives, then the editor stays`() = runTest {
    val viewModel = viewModel()
    send(viewModel, MediaSendFlowEvent.NavigateToEdit)

    send(viewModel, MediaSendFlowEvent.CloseAlbumViewer)

    assertThat(viewModel.backStack.lastOrNull()).isEqualTo(MediaSendRoute.Edit)
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
    )
  }

  private fun TestScope.openAlbum(viewModel: MediaSendFlowViewModel, focus: Media) {
    send(viewModel, MediaSendFlowEvent.OpenAlbumViewer(media = focus, album = ALBUM))
  }

  private fun TestScope.send(viewModel: MediaSendFlowViewModel, event: MediaSendFlowEvent) {
    viewModel.onEvent(event)
    advanceUntilIdle()
  }

  private companion object {
    private val PHOTO_A = photo("content://media/1")
    private val PHOTO_B = photo("content://media/2")
    private val PHOTO_C = photo("content://media/3")
    private val ALBUM = listOf(PHOTO_A, PHOTO_B, PHOTO_C)

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
