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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.R
import org.signal.mediasend.ToastMessage

/**
 * Tellomi（tellomi/tellomi#1121，需求 F-6…F-10）：附件 Sheet「文件」页的状态与事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AttachmentFilesViewModelTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val emitted = mutableListOf<MediaSendFlowEvent>()
  private var viewModel: AttachmentFilesViewModel? = null

  @After
  fun tearDown() {
    viewModel?.viewModelScope?.cancel()
    Dispatchers.resetMain()
  }

  // region loading

  @Test
  fun `Given the repository, when the page opens, then it shows its files and the server limit`() = runTest {
    val vm = viewModel(files = FILES.take(3), maxFileSize = 102_400_000L)
    assertThat(vm.state.value.files).isNull()
    assertThat(vm.state.value.showsSearchButton).isTrue()

    advanceUntilIdle()

    assertThat(vm.state.value.files).isEqualTo(FILES.take(3))
    assertThat(vm.state.value.maxFileSize).isEqualTo(102_400_000L)
    assertThat(vm.state.value.dock).isEqualTo(DOCK)
  }

  @Test
  fun `Given how many files there are, when shown, then search appears only above ten`() {
    assertThat(AttachmentFilesState(files = FILES.take(10)).showsSearchButton).isFalse()
    assertThat(AttachmentFilesState(files = FILES.take(11)).showsSearchButton).isTrue()
    assertThat(AttachmentFilesState(files = null).showsSearchButton).isTrue()
    assertThat(AttachmentFilesState(files = FILES.take(11), selection = emptyList()).showsSearchButton).isFalse()
    assertThat(AttachmentFilesState(files = FILES.take(11), searchQuery = "").showsSearchButton).isFalse()
  }

  // endregion

  // region sending

  @Test
  fun `Given a file on the device, when tapped, then it is sent right away on its own`() = runTest {
    val vm = loaded(FILES.take(3))

    vm.onEvent(AttachmentFilesEvent.FileClicked(FILES[1]))
    advanceUntilIdle()

    assertThat(sent()).containsExactly(result(recent = listOf(FILES[1].attachmentId)))
  }

  @Test
  fun `Given a file no longer on the device, when tapped, then it is not sent and a hint says why`() = runTest {
    val gone = FILES[0].copy(isOnDevice = false)
    val vm = loaded(listOf(gone) + FILES.drop(1).take(2))

    vm.onEvent(AttachmentFilesEvent.FileClicked(gone))
    vm.onEvent(AttachmentFilesEvent.FileLongClicked(gone))
    advanceUntilIdle()

    assertThat(sent()).isEmpty()
    assertThat(toasts()).containsExactly(R.string.AttachmentFilesScreen__cant_send_not_on_device)
    assertThat(vm.state.value.selection).isNull()
  }

  @Test
  fun `Given a long press, when more files are tapped, then they are picked in order and sent with the caption on the last`() = runTest {
    val vm = loaded(FILES.take(6))

    vm.onEvent(AttachmentFilesEvent.FileLongClicked(FILES[2]))
    vm.onEvent(AttachmentFilesEvent.FileClicked(FILES[0]))
    vm.onEvent(AttachmentFilesEvent.FileClicked(FILES[5]))
    vm.onEvent(AttachmentFilesEvent.FileClicked(FILES[0]))
    vm.onEvent(AttachmentFilesEvent.FileClicked(FILES[1]))
    advanceUntilIdle()

    val state = vm.state.value
    assertThat(state.selection).isEqualTo(listOf(FILES[2], FILES[5], FILES[1]).map { it.attachmentId })
    assertThat(state.selectionNumberOf(FILES[5])).isEqualTo(2)
    assertThat(state.selectionNumberOf(FILES[0])).isEqualTo(0)
    assertThat(sent()).isEmpty()

    vm.onEvent(AttachmentFilesEvent.CaptionChanged("  three files "))
    vm.onEvent(AttachmentFilesEvent.SendSelection)
    advanceUntilIdle()

    assertThat(sent()).containsExactly(result(recent = listOf(FILES[2], FILES[5], FILES[1]).map { it.attachmentId }, caption = "three files"))
  }

  @Test
  fun `Given a selection without a caption, when sent, then there is no caption`() = runTest {
    val vm = loaded(FILES.take(3))

    vm.onEvent(AttachmentFilesEvent.FileLongClicked(FILES[0]))
    vm.onEvent(AttachmentFilesEvent.CaptionChanged("   "))
    vm.onEvent(AttachmentFilesEvent.SendSelection)
    advanceUntilIdle()

    assertThat(sent()).containsExactly(result(recent = listOf(FILES[0].attachmentId), caption = null))
  }

  @Test
  fun `Given files from the system picker, when picked, then they are handed on and cancelling hands on nothing`() = runTest {
    val vm = loaded(emptyList())
    val uris = listOf("content://docs/a.pdf".toUri(), "content://docs/b.zip".toUri())

    vm.onEvent(AttachmentFilesEvent.FilesPicked(emptyList()))
    vm.onEvent(AttachmentFilesEvent.FilesPicked(uris))
    advanceUntilIdle()

    assertThat(sent()).containsExactly(result(picked = uris))
  }

  @Test
  fun `Given select from gallery, when tapped, then the flow opens the gallery in file mode`() = runTest {
    val vm = loaded(emptyList())

    vm.onEvent(AttachmentFilesEvent.SelectFromGalleryClicked)
    advanceUntilIdle()

    assertThat(sent()).isEmpty()
    assertThat(emitted).containsExactly(MediaSendFlowEvent.OpenFilesGallery)
  }

  // endregion

  // region leaving

  @Test
  fun `Given a selection, when close is tapped, then it leaves the selection first and closes the sheet the second time`() = runTest {
    val vm = loaded(FILES.take(3))
    vm.onEvent(AttachmentFilesEvent.FileLongClicked(FILES[0]))
    vm.onEvent(AttachmentFilesEvent.CaptionChanged("draft"))

    vm.onEvent(AttachmentFilesEvent.Close)
    advanceUntilIdle()
    assertThat(vm.state.value.selection).isNull()
    assertThat(vm.state.value.caption).isEqualTo("")
    assertThat(emitted.filterIsInstance<MediaSendFlowEvent.CloseRequested>()).isEmpty()

    vm.onEvent(AttachmentFilesEvent.Close)
    advanceUntilIdle()
    assertThat(emitted).containsExactly(MediaSendFlowEvent.CloseRequested)
  }

  @Test
  fun `Given search or a selection, when back is pressed, then it leaves them`() = runTest {
    val vm = loaded(FILES.take(12))

    vm.onEvent(AttachmentFilesEvent.SearchOpened)
    vm.onEvent(AttachmentFilesEvent.Back)
    advanceUntilIdle()
    assertThat(vm.state.value.isSearching).isFalse()

    vm.onEvent(AttachmentFilesEvent.FileLongClicked(FILES[0]))
    vm.onEvent(AttachmentFilesEvent.Back)
    advanceUntilIdle()
    assertThat(vm.state.value.isSelecting).isFalse()
    assertThat(emitted).isEmpty()
  }

  // endregion

  // region search

  @Test
  fun `Given a query, when typed, then results come after the 600 ms debounce, folded and collapsed to three`() = runTest {
    val named = NAMES.mapIndexed { index, name -> file(100L + index, name) }
    val vm = loaded(named + FILES.take(6))
    vm.onEvent(AttachmentFilesEvent.SearchOpened)
    runCurrent()

    vm.onEvent(AttachmentFilesEvent.SearchQueryChanged("REPORT"))
    runCurrent()
    advanceTimeBy(599)
    runCurrent()
    assertThat(vm.state.value.searchResults).isEmpty()

    advanceTimeBy(2)
    runCurrent()
    val state = vm.state.value
    assertThat(state.searchResults.map { it.fileName }).isEqualTo(NAMES.take(5))
    assertThat(state.visibleSearchResults.map { it.fileName }).isEqualTo(NAMES.take(3))
    assertThat(state.showsShowMore).isTrue()

    vm.onEvent(AttachmentFilesEvent.ShowMoreResults)
    runCurrent()
    assertThat(vm.state.value.visibleSearchResults.size).isEqualTo(5)
    assertThat(vm.state.value.showsShowMore).isFalse()
  }

  @Test
  fun `Given typing continues, when the debounce restarts, then only the last query is searched`() = runTest {
    val vm = loaded(NAMES.mapIndexed { index, name -> file(100L + index, name) })
    vm.onEvent(AttachmentFilesEvent.SearchOpened)

    vm.onEvent(AttachmentFilesEvent.SearchQueryChanged("rep"))
    runCurrent()
    advanceTimeBy(400)
    vm.onEvent(AttachmentFilesEvent.SearchQueryChanged("notes"))
    runCurrent()
    advanceTimeBy(400)
    runCurrent()
    assertThat(vm.state.value.searchResults).isEmpty()

    advanceTimeBy(300)
    runCurrent()
    assertThat(vm.state.value.searchedQuery).isEqualTo("notes")
    assertThat(vm.state.value.searchResults.map { it.fileName }).containsExactly("notes.txt")
  }

  @Test
  fun `Given nothing matches, when searched, then it says so, and a blank query lists nothing without saying so`() = runTest {
    val vm = loaded(FILES.take(12))
    vm.onEvent(AttachmentFilesEvent.SearchOpened)

    vm.onEvent(AttachmentFilesEvent.SearchQueryChanged("nothing like this"))
    advanceUntilIdle()
    assertThat(vm.state.value.showsNoResults).isTrue()

    vm.onEvent(AttachmentFilesEvent.SearchQueryChanged("   "))
    advanceUntilIdle()
    assertThat(vm.state.value.searchResults).isEmpty()
    assertThat(vm.state.value.showsNoResults).isFalse()
  }

  @Test
  fun `Given file names, when folded, then case, full width letters and accents do not matter`() {
    val files = listOf(file(1, "Café Menu.PDF"), file(2, "ＲＥＰＯＲＴ.docx"), file(3, "other.txt"))

    assertThat(AttachmentFilesViewModel.search(files, "cafe menu").map { it.attachmentId }).containsExactly(1L)
    assertThat(AttachmentFilesViewModel.search(files, "report").map { it.attachmentId }).containsExactly(2L)
    assertThat(AttachmentFilesViewModel.search(files, " ")).isEmpty()
  }

  // endregion

  // region dock

  @Test
  fun `Given the dock, when entries are tapped, then gallery switches page, others are handed on and files stays`() = runTest {
    val vm = loaded(emptyList())

    DOCK.forEach { vm.onEvent(AttachmentFilesEvent.DockEntryClicked(it)) }
    advanceUntilIdle()

    assertThat(emitted.filter { it !is MediaSendFlowEvent.ShowToast }).containsExactly(
      MediaSendFlowEvent.OpenAttachmentPage(AttachmentPage.GALLERY),
      MediaSendFlowEvent.AttachmentDockEntrySelected("CONTACT")
    )
    assertThat(toasts()).containsExactly(COMING_SOON)
  }

  // endregion

  // region helpers

  private fun TestScope.viewModel(files: List<RecentSentFile>, maxFileSize: Long = 100L): AttachmentFilesViewModel {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val parentState = MutableStateFlow(MediaSendFlowState(attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(DOCK)))
    return AttachmentFilesViewModel(
      parentState = parentState,
      parentEventEmitter = { emitted += it },
      repository = FakeRepository(files, maxFileSize),
      ioDispatcher = dispatcher
    ).also { viewModel = it }
  }

  private fun TestScope.loaded(files: List<RecentSentFile>): AttachmentFilesViewModel {
    return viewModel(files).also { advanceUntilIdle() }
  }

  private fun sent(): List<MediaSendFlowActivityContract.AttachmentFilesResult> = emitted.filterIsInstance<MediaSendFlowEvent.SendAttachmentFiles>().map { it.result }

  private fun toasts(): List<Int> = emitted.filterIsInstance<MediaSendFlowEvent.ShowToast>().map { (it.toast.message as ToastMessage.Text).id }

  private fun result(recent: List<Long> = emptyList(), picked: List<Uri> = emptyList(), caption: String? = null): MediaSendFlowActivityContract.AttachmentFilesResult {
    return MediaSendFlowActivityContract.AttachmentFilesResult(recentAttachmentIds = recent, pickedUris = picked, caption = caption)
  }

  private class FakeRepository(private val files: List<RecentSentFile>, private val maxFileSize: Long) : AttachmentFilesRepository {
    override suspend fun getRecentSentFiles(limit: Int): List<RecentSentFile> = files.take(limit)
    override fun getMaxFileSize(): Long = maxFileSize
  }

  private companion object {
    private val COMING_SOON = R.string.MediaSelectScreen__signal_needs_access_to_show_your_photos_and_videos

    private val DOCK = listOf(
      MediaSendFlowActivityContract.DockEntry(id = "GALLERY", title = R.string.MediaSelectScreen__gallery, icon = R.drawable.symbol_album_tilt_24, page = AttachmentPage.GALLERY),
      MediaSendFlowActivityContract.DockEntry(id = "FILE", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_file_24, page = AttachmentPage.FILES),
      MediaSendFlowActivityContract.DockEntry(id = "LOCATION", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_file_24, comingSoonMessage = COMING_SOON),
      MediaSendFlowActivityContract.DockEntry(id = "CONTACT", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_person_circle_24)
    )

    private val NAMES = listOf("Report.PDF", "ｒｅｐｏｒｔ-2.pdf", "report 3.docx", "报告 report4.xlsx", "old-REPORT.zip", "notes.txt")

    private val FILES: List<RecentSentFile> = (0 until 20).map { index -> file(index.toLong(), "Quarterly plan $index.pdf") }

    private fun file(id: Long, name: String) = RecentSentFile(attachmentId = id, fileName = name, size = 1_000L * (id + 1), sentAt = 1_790_000_000_000L - id * 60_000L, isOnDevice = true)
  }

  // endregion
}
