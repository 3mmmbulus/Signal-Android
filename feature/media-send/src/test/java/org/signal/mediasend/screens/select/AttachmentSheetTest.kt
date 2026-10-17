/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendFlowHudCommand
import org.signal.mediasend.MediaSendFlowState
import org.signal.mediasend.MediaSendFlowViewModel
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags
import kotlin.math.abs

/**
 * Tellomi（tellomi/tellomi#1115，判据「点『+』→ Sheet 从底部进入、聊天留在背景；dock 切换；下滑关闭」）：
 * 附件 Sheet 的外壳（两档、拖动、下拉关闭、点聊天关闭、网格和 Sheet 接力）与底部 dock（五格、让位、置灰、交给会话页）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class AttachmentSheetTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()
  private var dismissRequests = 0

  // region dock

  /**
   * 2026-10-17（D11，照 iOS Signal-iOS#265）：选中块是一块会滑的胶囊——换格时弹簧滑过去（中途在两格之间），不是一下子跳过去。
   */
  @Test
  fun `Given the dock, when the selected page changes, then the pill slides over instead of jumping`() {
    var page by mutableStateOf(MediaSendFlowActivityContract.AttachmentPage.GALLERY)
    composeTestRule.setContent {
      SignalTheme {
        AttachmentDock(entries = DOCK, currentPage = page, onClick = {})
      }
    }
    val galleryLeft = entryLeft(GALLERY)
    val fileLeft = entryLeft(FILE)
    assertThat(abs(pillLeft() - galleryLeft)).isLessThan(1f)

    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.runOnUiThread { page = MediaSendFlowActivityContract.AttachmentPage.FILES }
    composeTestRule.mainClock.advanceTimeByFrame()
    // 一帧一帧看：中途有停在两格之间的帧（滑过去），不是一下子到位
    val frames = (0 until 20).map {
      composeTestRule.mainClock.advanceTimeBy(16)
      pillLeft()
    }
    assertThat(frames.any { it > galleryLeft + 1f && it < fileLeft - 1f }).isTrue()

    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.waitForIdle()
    assertThat(abs(pillLeft() - fileLeft)).isLessThan(1f)
  }

  /**
   * 按住 dock 从「相册」拖到「联系人」松手：「联系人」交给页面（同点它）；页面没换的话选中块弹回「相册」。
   */
  @Test
  fun `Given the dock, when a finger drags from gallery to contact, then contact is handed on and the pill springs back`() {
    val clicked = mutableListOf<MediaSendFlowActivityContract.DockEntry>()
    composeTestRule.setContent {
      SignalTheme {
        AttachmentDock(entries = DOCK, currentPage = MediaSendFlowActivityContract.AttachmentPage.GALLERY, onClick = { clicked += it })
      }
    }
    val dock = composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).getBoundsInRoot()
    val from = entryCenter(GALLERY)
    val to = entryCenter(CONTACT)
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).performTouchInput {
      swipe(
        start = Offset((from.x - dock.left.value).dp.toPx(), height / 2f),
        end = Offset((to.x - dock.left.value).dp.toPx(), height / 2f),
        durationMillis = 300
      )
    }
    composeTestRule.waitForIdle()

    assertThat(clicked).containsExactly(CONTACT)
    assertThat(abs(pillLeft() - entryLeft(GALLERY))).isLessThan(1f)
  }

  /**
   * 换页进来的 dock：选中块从旧 dock 的选中块那里开始（Sheet 记着），再滑到这一页的格子——dock 看起来一直在原处，只有选中块在走。
   */
  @Test
  fun `Given a page switch, when the new page's dock appears, then its pill starts where the old one was and slides to its own entry`() {
    val memory = AttachmentDockPillMemory().apply { position = 0f }
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(
          LocalAttachmentDockTransition provides AttachmentDockTransition(enterFrom = MediaSendFlowActivityContract.AttachmentPage.GALLERY),
          LocalAttachmentDockPillMemory provides memory
        ) {
          AttachmentDock(entries = DOCK, currentPage = MediaSendFlowActivityContract.AttachmentPage.FILES, onClick = {})
        }
      }
    }
    composeTestRule.mainClock.advanceTimeByFrame()
    assertThat(abs(pillLeft() - entryLeft(GALLERY))).isLessThan(1f)

    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.waitForIdle()
    assertThat(abs(pillLeft() - entryLeft(FILE))).isLessThan(1f)
    assertThat(memory.position).isEqualTo(1f)
  }

  private fun pillLeft(): Float = composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK_PILL, useUnmergedTree = true).getBoundsInRoot().left.value

  private fun entryLeft(entry: MediaSendFlowActivityContract.DockEntry): Float = composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(entry.id)).getBoundsInRoot().left.value

  private fun entryCenter(entry: MediaSendFlowActivityContract.DockEntry): Offset {
    val bounds = composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(entry.id)).getBoundsInRoot()
    return Offset((bounds.left.value + bounds.right.value) / 2f, (bounds.top.value + bounds.bottom.value) / 2f)
  }

  @Test
  fun `Given the attachment sheet with nothing selected, when displayed, then the dock shows its entries in order with gallery selected`() {
    setPicker(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).assertIsDisplayed()
    val lefts = DOCK.map { composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(it.id)).assertIsDisplayed().getBoundsInRoot().left.value }
    assertThat(lefts.zipWithNext().all { (a, b) -> a < b }).isTrue()
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(GALLERY.id)).assertIsSelected()
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(FILE.id)).assertIsNotSelected()
  }

  @Test
  fun `Given a selection, when displayed, then the dock gives way to the send bar`() {
    setPicker(selectedMedia = MEDIA.take(2))

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SEND_BAR).assertIsDisplayed()
  }

  @Test
  fun `Given the picker was not opened from the plus button, when displayed, then there is no dock`() {
    setPicker(selectedMedia = emptyList(), dock = emptyList(), inSheet = false)

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).assertDoesNotExist()
  }

  @Test
  fun `Given the dock, when entries other than gallery are clicked, then each is handed on`() {
    setPicker(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(FILE.id)).performClick()
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(LOCATION_COMING_SOON.id)).performClick()
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(CONTACT.id)).performClick()

    assertThat(events.filterIsInstance<MediaSelectScreenEvents.DockEntryClicked>().map { it.entry })
      .containsExactly(FILE, LOCATION_COMING_SOON, CONTACT)
  }

  @Test
  fun `Given the collapsed sheet, when gallery is clicked again, then nothing is handed on and the sheet expands`() {
    val sheet = setPicker(selectedMedia = emptyList())
    assertThat(abs(sheetTop() - COLLAPSED_TOP)).isLessThan(1f)

    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(GALLERY.id)).performClick()
    composeTestRule.waitForIdle()

    assertThat(events.filterIsInstance<MediaSelectScreenEvents.DockEntryClicked>()).isEmpty()
    assertThat(sheet.isExpanded).isTrue()
    assertThat(sheetTop()).isEqualTo(0f)
  }

  @Test
  fun `Given an entry that is coming soon, when displayed, then it reads the message as its state`() {
    setPicker(selectedMedia = emptyList())
    val message = ApplicationProvider.getApplicationContext<Application>().getString(COMING_SOON_MESSAGE)

    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(LOCATION_COMING_SOON.id))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, message))
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry(FILE.id))
      .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
  }

  // endregion

  // region sheet

  @Test
  fun `Given the sheet opens, when it settles, then it covers 73 percent of the height with the chat dimmed above it`() {
    setSheet()

    val sheet = composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_SHEET).getBoundsInRoot()
    assertThat(abs(sheet.top.value - COLLAPSED_TOP)).isLessThan(1f)
    assertThat(abs(sheet.bottom.value - SCREEN_HEIGHT)).isLessThan(1f)
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_SHEET_SCRIM).assertIsDisplayed()
  }

  @Test
  fun `Given the collapsed sheet, when its header is dragged up past the middle, then it expands to full screen`() {
    val sheet = setSheet()

    dragHeader(by = -150f)

    assertThat(sheet.isExpanded).isTrue()
    assertThat(sheetTop()).isEqualTo(0f)
  }

  @Test
  fun `Given the collapsed sheet with nothing selected, when dragged down past the threshold, then it asks to close`() {
    setSheet(canDismissDirectly = true)

    dragHeader(by = 120f)

    assertThat(dismissRequests).isEqualTo(1)
  }

  @Test
  fun `Given a selection, when the sheet is dragged down past the threshold, then it asks to close and springs back`() {
    setSheet(canDismissDirectly = false)

    dragHeader(by = 120f)

    assertThat(dismissRequests).isEqualTo(1)
    assertThat(abs(sheetTop() - COLLAPSED_TOP)).isLessThan(1f)
  }

  @Test
  fun `Given the collapsed sheet, when dragged down only a little, then it springs back without closing`() {
    setSheet(canDismissDirectly = true)

    dragHeader(by = 30f)

    assertThat(dismissRequests).isEqualTo(0)
    assertThat(abs(sheetTop() - COLLAPSED_TOP)).isLessThan(1f)
  }

  @Test
  fun `Given the sheet, when the dimmed chat above it is tapped, then it asks to close`() {
    setSheet()

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_SHEET_SCRIM).performTouchInput {
      click(Offset(x = centerX, y = 100.dp.toPx()))
    }
    composeTestRule.waitForIdle()

    assertThat(dismissRequests).isEqualTo(1)
  }

  @Test
  fun `Given the collapsed sheet, when the list inside is scrolled up, then the sheet takes the scroll before the list`() {
    val list = LazyListState()
    val sheet = setSheet(list = list)

    composeTestRule.onNodeWithTag(LIST).performTouchInput {
      swipe(start = Offset(centerX, 300.dp.toPx()), end = Offset(centerX, 150.dp.toPx()), durationMillis = 800)
    }

    // 松手那一刻：挪的是 Sheet，网格一点没滚
    assertThat(sheet.top).isLessThan(COLLAPSED_TOP - 30f)
    assertThat(list.firstVisibleItemIndex).isEqualTo(0)
    assertThat(list.firstVisibleItemScrollOffset).isEqualTo(0)

    // 停下来一定在两档之一，不会卡在半路
    composeTestRule.waitForIdle()
    val top = sheetTop()
    assertThat(abs(top) < 1f || abs(top - COLLAPSED_TOP) < 1f).isTrue()
  }

  // endregion

  // region view models

  @Test
  fun `Given dock entries, when clicked in the select view model, then coming soon only toasts, gallery does nothing, files switches page and the rest leave the flow`() {
    val emitted = mutableListOf<MediaSendFlowEvent>()
    val viewModel = MediaSelectViewModel(
      parentState = MutableStateFlow(MediaSendFlowState(attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(DOCK))),
      parentEventEmitter = { emitted += it },
      mediaFolder = RECENTS,
      selectionAdditions = emptyFlow(),
      repository = mediaSendDependenciesRule.mediaSendRepository
    )
    assertThat((viewModel.state.value as MediaSelectState.Files).attachmentDock).isEqualTo(DOCK)

    viewModel.onEvent(MediaSelectScreenEvents.DockEntryClicked(GALLERY))
    viewModel.onEvent(MediaSelectScreenEvents.DockEntryClicked(LOCATION_COMING_SOON))
    viewModel.onEvent(MediaSelectScreenEvents.DockEntryClicked(FILE))
    viewModel.onEvent(MediaSelectScreenEvents.DockEntryClicked(CONTACT))
    shadowOf(Looper.getMainLooper()).idle()

    val dockEvents = emitted.filter { it is MediaSendFlowEvent.ShowToast || it is MediaSendFlowEvent.AttachmentDockEntrySelected || it is MediaSendFlowEvent.OpenAttachmentPage }
    assertThat(dockEvents.size).isEqualTo(3)
    assertThat(dockEvents[0]).isInstanceOf(MediaSendFlowEvent.ShowToast::class)
    assertThat(dockEvents[1]).isEqualTo(MediaSendFlowEvent.OpenAttachmentPage(MediaSendFlowActivityContract.AttachmentPage.FILES))
    assertThat(dockEvents[2]).isEqualTo(MediaSendFlowEvent.AttachmentDockEntrySelected(CONTACT.id))
  }

  /** tellomi/tellomi#1121 F-1：「相册」「文件」在同一个 Sheet 里换页（流程状态记着当前页）；「文件」页选好的文件交给会话页去发。 */
  @Test
  fun `Given the attachment sheet, when pages switch and files are chosen, then the flow keeps the page and hands the files on`() {
    val viewModel = MediaSendFlowViewModel(
      savedStateHandle = SavedStateHandle(
        mapOf(
          MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(DOCK)),
          MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
        )
      ),
      repository = mediaSendDependenciesRule.mediaSendRepository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
    assertThat(viewModel.state.value.attachmentPage).isEqualTo(MediaSendFlowActivityContract.AttachmentPage.GALLERY)

    viewModel.onEvent(MediaSendFlowEvent.OpenAttachmentPage(MediaSendFlowActivityContract.AttachmentPage.FILES))
    assertThat(viewModel.state.value.attachmentPage).isEqualTo(MediaSendFlowActivityContract.AttachmentPage.FILES)
    viewModel.onEvent(MediaSendFlowEvent.OpenAttachmentPage(MediaSendFlowActivityContract.AttachmentPage.GALLERY))
    assertThat(viewModel.state.value.attachmentPage).isEqualTo(MediaSendFlowActivityContract.AttachmentPage.GALLERY)

    val files = MediaSendFlowActivityContract.AttachmentFilesResult(recentAttachmentIds = listOf(3L, 1L), pickedUris = emptyList(), caption = "two files")
    viewModel.onEvent(MediaSendFlowEvent.SendAttachmentFiles(files))

    val command = runBlocking { withTimeout(5_000) { viewModel.hudCommands.first() } }
    assertThat(command).isEqualTo(MediaSendFlowHudCommand.SendAttachmentFiles(files))
  }

  @Test
  fun `Given the flow was opened as an attachment sheet, when a dock entry leaves it, then the host is told which one`() {
    val viewModel = MediaSendFlowViewModel(
      savedStateHandle = SavedStateHandle(
        mapOf(
          MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(DOCK)),
          MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
        )
      ),
      repository = mediaSendDependenciesRule.mediaSendRepository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
    assertThat(viewModel.state.value.attachmentSheet?.dock).isEqualTo(DOCK)

    viewModel.onEvent(MediaSendFlowEvent.AttachmentDockEntrySelected(CONTACT.id))

    val command = runBlocking { withTimeout(5_000) { viewModel.hudCommands.first() } }
    assertThat(command).isEqualTo(MediaSendFlowHudCommand.AttachmentDockEntrySelected(CONTACT.id))
  }

  // endregion

  private fun setPicker(
    selectedMedia: List<Media>,
    dock: List<MediaSendFlowActivityContract.DockEntry> = DOCK,
    inSheet: Boolean = true
  ): AttachmentSheetState {
    val sheet = AttachmentSheetState()
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(SCREEN_WIDTH.dp, SCREEN_HEIGHT.dp)) {
          CompositionLocalProvider(LocalAttachmentSheetState provides if (inSheet) sheet else null) {
            val picker: @Composable () -> Unit = {
              MediaSelectScreen(
                state = MediaSelectState.Files(
                  selectedMediaFolder = RECENTS,
                  selectedMediaFolderItems = MEDIA,
                  selectedMedia = selectedMedia,
                  mediaFolders = listOf(RECENTS),
                  attachmentDock = dock
                ),
                onEvent = { events += it }
              )
            }
            if (inSheet) {
              AttachmentSheet(state = sheet, canDismissDirectly = selectedMedia.isEmpty(), onDismissRequest = { dismissRequests++ }) { picker() }
            } else {
              picker()
            }
          }
        }
      }
    }
    composeTestRule.waitForIdle()
    return sheet
  }

  private fun setSheet(canDismissDirectly: Boolean = true, list: LazyListState = LazyListState()): AttachmentSheetState {
    val sheet = AttachmentSheetState()
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(SCREEN_WIDTH.dp, SCREEN_HEIGHT.dp)) {
          AttachmentSheet(state = sheet, canDismissDirectly = canDismissDirectly, onDismissRequest = { dismissRequests++ }) {
            Column(modifier = Modifier.fillMaxSize()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(56.dp)
                  .testTag(HEADER)
              )
              LazyColumn(state = list, modifier = Modifier.testTag(LIST)) {
                items(60) { index ->
                  Text(text = "row $index", modifier = Modifier.height(48.dp))
                }
              }
            }
          }
        }
      }
    }
    composeTestRule.waitForIdle()
    return sheet
  }

  /** 按住顶上那一条慢慢拖 [by] dp（慢到不算甩），松手后等 Sheet 停下。 */
  private fun dragHeader(by: Float) {
    composeTestRule.onNodeWithTag(HEADER).performTouchInput {
      swipe(start = center, end = Offset(centerX, centerY + by.dp.toPx()), durationMillis = 1_000)
    }
    composeTestRule.waitForIdle()
  }

  private fun sheetTop(): Float = composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_SHEET).getBoundsInRoot().top.value

  private companion object {
    private const val SCREEN_WIDTH = 400f
    private const val SCREEN_HEIGHT = 800f

    /** 收起时 Sheet 占 73%（同 iOS 端）：写死，不读实现里的常量，改坏了才会红。 */
    private const val COLLAPSED_TOP = SCREEN_HEIGHT * (1f - 0.73f)
    private const val HEADER = "header"
    private const val LIST = "list"

    private val COMING_SOON_MESSAGE = R.string.MediaSelectScreen__signal_needs_access_to_show_your_photos_and_videos

    private val GALLERY = MediaSendFlowActivityContract.DockEntry(id = "GALLERY", title = R.string.MediaSelectScreen__gallery, icon = R.drawable.symbol_album_tilt_24, page = MediaSendFlowActivityContract.AttachmentPage.GALLERY)
    private val FILE = MediaSendFlowActivityContract.DockEntry(
      id = "FILE",
      title = R.string.MediaSelectScreen__gallery,
      icon = org.signal.core.ui.R.drawable.symbol_file_24,
      page = MediaSendFlowActivityContract.AttachmentPage.FILES
    )
    private val LOCATION_COMING_SOON = MediaSendFlowActivityContract.DockEntry(
      id = "LOCATION",
      title = R.string.MediaSelectScreen__gallery,
      icon = org.signal.core.ui.R.drawable.symbol_file_24,
      comingSoonMessage = COMING_SOON_MESSAGE
    )
    private val CONTACT = MediaSendFlowActivityContract.DockEntry(id = "CONTACT", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_person_circle_24)
    private val DOCK = listOf(GALLERY, FILE, LOCATION_COMING_SOON, CONTACT)

    private val RECENTS = MediaFolder(
      thumbnailUri = "content://recents".toUri(),
      title = "All media",
      itemCount = 8,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val MEDIA: List<Media> = (0 until 8).map { index ->
      Media(
        uri = "content://media/$index".toUri(),
        contentType = "image/jpeg",
        date = index.toLong(),
        width = 100,
        height = 100,
        size = 1024,
        duration = 0,
        isBorderless = false,
        isVideoGif = false,
        bucketId = "bucket",
        caption = null,
        transformProperties = null,
        fileName = null
      )
    }
  }
}
