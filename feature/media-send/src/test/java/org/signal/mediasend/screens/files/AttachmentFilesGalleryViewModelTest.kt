/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.signal.core.models.media.MediaFolder
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.R
import org.signal.mediasend.ToastMessage

/**
 * Tellomi（tellomi/tellomi#1263 F-3）：「文件」页「从相册中选择」的整屏相册网格：换相册、勾选（顺序、上限 100）、取消、发送原文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AttachmentFilesGalleryViewModelTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val repository = dependencies.mediaSendRepository
  private val emitted = mutableListOf<MediaSendFlowEvent>()
  private var viewModel: AttachmentFilesGalleryViewModel? = null

  @Before
  fun setUp() {
    coEvery { repository.getFolders() } returns listOf(CAMERA)
    coEvery { repository.getMedia(Media.ALL_MEDIA_BUCKET_ID) } returns RECENTS
    coEvery { repository.getMedia(CAMERA.bucketId) } returns listOf(RECENTS[1])
  }

  @After
  fun tearDown() {
    viewModel?.viewModelScope?.cancel()
    Dispatchers.resetMain()
  }

  @Test
  fun `Given the gallery opens, when it has loaded, then it shows recents and the folders to switch to`() = runTest {
    val vm = viewModel()
    assertThat(vm.state.value.items).isNull()

    advanceUntilIdle()

    assertThat(vm.state.value.folder.bucketId).isEqualTo(Media.ALL_MEDIA_BUCKET_ID)
    assertThat(vm.state.value.items).isEqualTo(RECENTS)
    assertThat(vm.state.value.folders).containsExactly(CAMERA)
  }

  @Test
  fun `Given another folder, when switched to, then its media replaces the grid and the selection is kept`() = runTest {
    val vm = loaded()
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))

    vm.onEvent(AttachmentFilesGalleryEvent.SwitchFolder(CAMERA))
    advanceUntilIdle()

    assertThat(vm.state.value.folder).isEqualTo(CAMERA)
    assertThat(vm.state.value.items).isEqualTo(listOf(RECENTS[1]))
    assertThat(vm.state.value.selected).containsExactly(RECENTS[0])
  }

  @Test
  fun `Given media ticked in some order, when one is ticked again, then it is removed and the rest keep their order`() = runTest {
    val vm = loaded()

    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[2]))
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[1]))
    advanceUntilIdle()
    assertThat(vm.state.value.selectionIndex(RECENTS[0])).isEqualTo(1)

    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))
    advanceUntilIdle()

    assertThat(vm.state.value.selected).containsExactly(RECENTS[2], RECENTS[1])
    assertThat(vm.state.value.selectionIndex(RECENTS[0])).isEqualTo(-1)
  }

  @Test
  fun `Given the limit is reached, when one more is ticked, then it is not added and a toast says the limit`() = runTest {
    val many = (1..AttachmentFilesGalleryState.MAX_SELECTION + 1).map { photo("content://media/$it") }
    coEvery { repository.getMedia(Media.ALL_MEDIA_BUCKET_ID) } returns many
    val vm = loaded()

    many.forEach { vm.onEvent(AttachmentFilesGalleryEvent.Toggle(it)) }
    advanceUntilIdle()

    assertThat(vm.state.value.selected.size).isEqualTo(AttachmentFilesGalleryState.MAX_SELECTION)
    val toast = emitted.filterIsInstance<MediaSendFlowEvent.ShowToast>().single().toast.message
    assertThat(toast).isEqualTo(ToastMessage.Quantity(R.plurals.MediaSelectScreen__at_most_n_items, AttachmentFilesGalleryState.MAX_SELECTION))
  }

  @Test
  fun `Given a selection, when sent, then the originals go back in the order they were ticked`() = runTest {
    val vm = loaded()
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[2]))
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))

    vm.onEvent(AttachmentFilesGalleryEvent.Send)
    advanceUntilIdle()

    assertThat(emitted).containsExactly(
      MediaSendFlowEvent.SendAttachmentFiles(
        MediaSendFlowActivityContract.AttachmentFilesResult(
          recentAttachmentIds = emptyList(),
          pickedUris = emptyList(),
          caption = null,
          galleryUris = listOf<Uri>(RECENTS[2].uri, RECENTS[0].uri)
        )
      )
    )
  }

  @Test
  fun `Given a HEIC is ticked, when sent, then it first asks and sends with the choice`() = runTest {
    val heic = photo("content://media/9", contentType = "image/heic")
    coEvery { repository.getMedia(Media.ALL_MEDIA_BUCKET_ID) } returns RECENTS + heic
    val vm = loaded()
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(heic))
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))

    vm.onEvent(AttachmentFilesGalleryEvent.Send)
    advanceUntilIdle()
    assertThat(vm.state.value.isAskingAboutHeic).isTrue()
    assertThat(emitted).isEmpty()

    vm.onEvent(AttachmentFilesGalleryEvent.HeicChosen(convertToJpeg = true))
    advanceUntilIdle()
    assertThat(vm.state.value.isAskingAboutHeic).isFalse()
    val result = emitted.filterIsInstance<MediaSendFlowEvent.SendAttachmentFiles>().single().result
    assertThat(result.galleryUris).containsExactly(heic.uri, RECENTS[0].uri)
    assertThat(result.convertHeicToJpeg).isTrue()
  }

  @Test
  fun `Given the HEIC question, when dismissed, then nothing is sent and the selection stays`() = runTest {
    val heic = photo("content://media/9", contentType = "image/heif")
    coEvery { repository.getMedia(Media.ALL_MEDIA_BUCKET_ID) } returns listOf(heic)
    val vm = loaded()
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(heic))
    vm.onEvent(AttachmentFilesGalleryEvent.Send)

    vm.onEvent(AttachmentFilesGalleryEvent.HeicDismissed)
    advanceUntilIdle()

    assertThat(vm.state.value.isAskingAboutHeic).isFalse()
    assertThat(vm.state.value.selected).containsExactly(heic)
    assertThat(emitted).isEmpty()
  }

  @Test
  fun `Given nothing is ticked, when send is asked for, then nothing happens`() = runTest {
    val vm = loaded()

    vm.onEvent(AttachmentFilesGalleryEvent.Send)
    advanceUntilIdle()

    assertThat(emitted).isEmpty()
  }

  @Test
  fun `Given a selection, when cancelled, then the flow is asked to close the gallery`() = runTest {
    val vm = loaded()
    vm.onEvent(AttachmentFilesGalleryEvent.Toggle(RECENTS[0]))

    vm.onEvent(AttachmentFilesGalleryEvent.Cancel)
    advanceUntilIdle()

    assertThat(emitted).containsExactly(MediaSendFlowEvent.CloseFilesGallery)
  }

  private fun TestScope.viewModel(): AttachmentFilesGalleryViewModel {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    return AttachmentFilesGalleryViewModel(
      parentEventEmitter = { emitted += it },
      repository = repository,
      ioDispatcher = dispatcher
    ).also { viewModel = it }
  }

  private fun TestScope.loaded(): AttachmentFilesGalleryViewModel {
    return viewModel().also { advanceUntilIdle() }
  }

  private companion object {
    private val CAMERA = MediaFolder(
      thumbnailUri = Uri.EMPTY,
      title = "Camera",
      itemCount = 1,
      bucketId = "camera-bucket",
      folderType = MediaFolder.FolderType.CAMERA
    )

    private val RECENTS = listOf(photo("content://media/1"), photo("content://media/2"), photo("content://media/3"))

    private fun photo(uri: String, contentType: String = "image/jpeg") = Media(
      uri = uri.toUri(),
      contentType = contentType,
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
