/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.bytes
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.R
import org.signal.mediasend.screens.select.AttachmentSheet
import org.signal.mediasend.screens.select.AttachmentSheetState
import org.signal.mediasend.screens.select.LocalAttachmentSheetState
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1121，需求 F-1…F-10）：附件 Sheet「文件」页画出来的样子与点下去交出去的事件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class AttachmentFilesScreenTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val events = mutableListOf<AttachmentFilesEvent>()
  private val registry = RecordingRegistry()

  // region entries and loading

  @Test
  fun `Given the files are still loading, when shown, then there are skeleton rows and a search button`() {
    setPage(AttachmentFilesState(files = null, dock = DOCK))

    assertThat(composeTestRule.onAllNodesWithTag(TestTags.ATTACHMENT_FILES_SKELETON).fetchSemanticsNodes().size).isEqualTo(FilesMetrics.SKELETON_ROWS)
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON).assertIsDisplayed()
  }

  @Test
  fun `Given the entries, when select from gallery is tapped, then the view model is asked to open the gallery`() {
    setPage(AttachmentFilesState(files = emptyList(), dock = DOCK))

    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__select_from_gallery)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.attachmentFilesEntry("gallery")).performClick()

    assertThat(events).containsExactly(AttachmentFilesEvent.SelectFromGalleryClicked)
    assertThat(registry.launched).isEmpty()
  }

  @Test
  fun `Given the entries, when select from files is tapped, then the system picker opens for any file and what it returns is handed on`() {
    setPage(AttachmentFilesState(files = emptyList(), dock = DOCK))

    composeTestRule.onNodeWithTag(TestTags.attachmentFilesEntry("files")).performClick()
    composeTestRule.waitForIdle()

    assertThat(registry.launched.map { (it as Array<*>).toList() }).containsExactly(listOf("*/*"))
    val picked = listOf("content://docs/a.pdf".toUri(), "content://docs/b.zip".toUri())
    composeTestRule.runOnUiThread { registry.dispatchResult(registry.lastRequestCode, picked) }
    composeTestRule.waitForIdle()

    assertThat(events).containsExactly(AttachmentFilesEvent.FilesPicked(picked))
  }

  /**
   * 选择器结果里的读授权挂在接收结果的 Activity（Sheet）上，Sheet 一关就收回；会话页要等 Sheet 关掉之后才一个个整份拷贝，
   * 所以 Sheet 一收到就要转成持久授权（会话页发完再放，见 [PickedFileGrantsTest]）。
   */
  @Test
  fun `Given the system picker returns files, then each keeps a persisted read grant so the chat can still read it after the Sheet closes`() {
    setPage(AttachmentFilesState(files = emptyList(), dock = DOCK))

    composeTestRule.onNodeWithTag(TestTags.attachmentFilesEntry("files")).performClick()
    composeTestRule.waitForIdle()
    val picked = listOf("content://docs/a.pdf".toUri(), "content://docs/b.zip".toUri())
    composeTestRule.runOnUiThread { registry.dispatchResult(registry.lastRequestCode, picked) }
    composeTestRule.waitForIdle()

    val kept = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
    assertThat(kept.toSet()).isEqualTo(picked.toSet())
  }

  // owner 2026-09-28：一条都没有时连「最近发送的文件」标题都不显示，看起来像没这个功能 → 标题常驻，空的时候标题下面是插画 + 说明
  // （照 Telegram iOS `AttachmentFileEmptyItem` 的空状态：一张图 + 一句话，独立实现）。

  @Test
  fun `Given no files were ever sent, when shown, then the header stays and an illustration and the server limit are shown with no search`() {
    setPage(AttachmentFilesState(files = emptyList(), maxFileSize = 104_857_600L, dock = DOCK))

    val expected = context.getString(R.string.AttachmentFilesScreen__empty, 104_857_600L.bytes.toUnitString())
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_EMPTY).assert(hasText(expected))
    assertThat(expected.contains("100")).isTrue()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__recently_sent_files)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_EMPTY_ILLUSTRATION, useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun `Given recent files are loading, when shown, then the header is already there and there is no illustration`() {
    setPage(AttachmentFilesState(files = null, dock = DOCK))

    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__recently_sent_files)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_EMPTY_ILLUSTRATION, useUnmergedTree = true).assertDoesNotExist()
  }

  // endregion

  // region recent files

  @Test
  fun `Given recent files, when shown, then each row has its name, size and date, and one no longer on the device says so`() {
    val gone = FILES[1].copy(isOnDevice = false)
    setPage(AttachmentFilesState(files = listOf(FILES[0], gone, FILES[2]), dock = DOCK))

    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__recently_sent_files)).assertIsDisplayed()
    composeTestRule.onNodeWithText(FILES[0].fileName).assertIsDisplayed()
    val detail = "${FILES[0].size.bytes.toUnitString()} · "
    composeTestRule.onNodeWithTag(TestTags.attachmentFile(FILES[0].attachmentId))
      .assert(SemanticsMatcher("「大小 · 日期」") { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.startsWith(detail) && it.text.length > detail.length } })
    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__not_on_device)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON).assertDoesNotExist()
  }

  @Test
  fun `Given a recent file, when tapped or long pressed, then the page is told which`() {
    setPage(AttachmentFilesState(files = FILES.take(3), dock = DOCK))

    composeTestRule.onNodeWithTag(TestTags.attachmentFile(FILES[1].attachmentId)).performClick()
    composeTestRule.onNodeWithTag(TestTags.attachmentFile(FILES[2].attachmentId)).performTouchInput { longClick() }

    assertThat(events).containsExactly(AttachmentFilesEvent.FileClicked(FILES[1]), AttachmentFilesEvent.FileLongClicked(FILES[2]))
  }

  @Test
  fun `Given more than ten files, when shown, then the search button is there`() {
    setPage(AttachmentFilesState(files = FILES.take(11), dock = DOCK))

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON).assertIsDisplayed().performClick()

    assertThat(events).containsExactly(AttachmentFilesEvent.SearchOpened)
  }

  // endregion

  // region selection

  @Test
  fun `Given a selection, when shown, then the title counts it, checks are numbered in order and the dock gives way to caption and send`() {
    setPage(AttachmentFilesState(files = FILES.take(12), dock = DOCK, selection = listOf(FILES[2].attachmentId, FILES[0].attachmentId)))

    composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.attachmentFilesEntry("files")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEND_BAR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.attachmentFileCheck(FILES[2].attachmentId), useUnmergedTree = true)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Number 1")))
    composeTestRule.onNodeWithTag(TestTags.attachmentFileCheck(FILES[0].attachmentId), useUnmergedTree = true)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Number 2")))
    composeTestRule.onNodeWithTag(TestTags.attachmentFileCheck(FILES[1].attachmentId), useUnmergedTree = true)
      .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    composeTestRule.onNodeWithTag(TestTags.attachmentFile(FILES[2].attachmentId)).assertIsSelected()
    composeTestRule.onNodeWithTag(TestTags.attachmentFile(FILES[1].attachmentId)).assertIsNotSelected()
  }

  @Test
  fun `Given a selection, when a caption is typed and send is tapped, then both are handed on`() {
    setPage(AttachmentFilesState(files = FILES.take(3), dock = DOCK, selection = listOf(FILES[0].attachmentId)))

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_CAPTION).performTextInput("hi")
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEND).performClick()

    assertThat(events).containsExactly(AttachmentFilesEvent.CaptionChanged("hi"), AttachmentFilesEvent.SendSelection)
  }

  // endregion

  // region search

  @Test
  fun `Given search results, when shown, then the title bar and dock are gone and long results show three and show more`() {
    val results = FILES.take(5)
    setPage(AttachmentFilesState(files = FILES.take(12), dock = DOCK, searchQuery = "plan", searchedQuery = "plan", searchResults = results))

    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__files)).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_DOCK).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_FIELD).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesScreen__files_you_sent)).assertIsDisplayed()
    results.take(3).forEach { composeTestRule.onNodeWithTag(TestTags.attachmentFile(it.attachmentId)).assertIsDisplayed() }
    composeTestRule.onNodeWithTag(TestTags.attachmentFile(results[3].attachmentId)).assertDoesNotExist()

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SHOW_MORE).performClick()
    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_CLOSE).performClick()

    assertThat(events).containsExactly(AttachmentFilesEvent.ShowMoreResults, AttachmentFilesEvent.SearchClosed)
  }

  @Test
  fun `Given typing in the search field, when text is entered, then the query is handed on`() {
    setPage(AttachmentFilesState(files = FILES.take(12), dock = DOCK, searchQuery = ""))

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_SEARCH_FIELD).performTextInput("q")

    assertThat(events).containsExactly(AttachmentFilesEvent.SearchQueryChanged("q"))
  }

  @Test
  fun `Given nothing matched, when shown, then it says nothing was found for the query`() {
    setPage(AttachmentFilesState(files = FILES.take(12), dock = DOCK, searchQuery = "zzz", searchedQuery = "zzz"))

    composeTestRule.onNodeWithTag(TestTags.ATTACHMENT_FILES_NO_RESULTS)
      .assert(hasText(context.getString(R.string.AttachmentFilesScreen__no_results, "zzz")))
  }

  @Test
  fun `Given search or a selection, when back is pressed, then the page is told`() {
    var state by mutableStateOf(AttachmentFilesState(files = FILES.take(12), dock = DOCK, searchQuery = ""))
    setContent { AttachmentFilesScreen(state = state, onEvent = { events += it }) }

    composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    state = state.copy(searchQuery = null, selection = listOf(FILES[0].attachmentId))
    composeTestRule.waitForIdle()
    composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()

    assertThat(events).containsExactly(AttachmentFilesEvent.Back, AttachmentFilesEvent.Back)
  }

  // endregion

  // region dock and sheet

  @Test
  fun `Given the dock, when shown, then files is the selected entry and gallery is handed on`() {
    setPage(AttachmentFilesState(files = emptyList(), dock = DOCK))

    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry("FILE")).assertIsSelected()
    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry("GALLERY")).assertIsNotSelected().performClick()

    assertThat(events).containsExactly(AttachmentFilesEvent.DockEntryClicked(DOCK[0]))
  }

  @Test
  fun `Given the collapsed sheet, when files is tapped again, then nothing is handed on and the sheet expands`() {
    val sheet = setPage(AttachmentFilesState(files = FILES.take(3), dock = DOCK), inSheet = true)
    assertThat(sheet.isExpanded).isEqualTo(false)

    composeTestRule.onNodeWithTag(TestTags.attachmentDockEntry("FILE")).performClick()
    composeTestRule.waitForIdle()

    assertThat(events).isEmpty()
    assertThat(sheet.isExpanded).isTrue()
  }

  @Test
  fun `Given the collapsed sheet, when search opens, then the sheet expands to full screen`() {
    var state by mutableStateOf(AttachmentFilesState(files = FILES.take(12), dock = DOCK))
    val sheet = AttachmentSheetState()
    setContent(sheet) { AttachmentFilesScreen(state = state, onEvent = { events += it }) }
    assertThat(sheet.isExpanded).isEqualTo(false)

    state = state.copy(searchQuery = "")
    composeTestRule.waitForIdle()

    assertThat(sheet.isExpanded).isTrue()
  }

  // endregion

  // region helpers

  private fun setPage(state: AttachmentFilesState, inSheet: Boolean = false): AttachmentSheetState {
    val sheet = AttachmentSheetState()
    setContent(if (inSheet) sheet else null) { AttachmentFilesScreen(state = state, onEvent = { events += it }) }
    return sheet
  }

  private fun setContent(sheet: AttachmentSheetState? = null, content: @Composable () -> Unit) {
    val owner = object : ActivityResultRegistryOwner {
      override val activityResultRegistry: ActivityResultRegistry = registry
    }
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalAttachmentSheetState provides sheet) {
          Box(modifier = Modifier.size(400.dp, 800.dp)) {
            if (sheet != null) {
              AttachmentSheet(state = sheet, canDismissDirectly = true, onDismissRequest = {}) { content() }
            } else {
              content()
            }
          }
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  /** 记下 `launch` 的输入，不真的打开系统选择器；结果由用例自己 dispatch。 */
  private class RecordingRegistry : ActivityResultRegistry() {
    val launched = mutableListOf<Any?>()
    var lastRequestCode = -1

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
      launched += input
      lastRequestCode = requestCode
    }
  }

  private companion object {
    private val DOCK = listOf(
      MediaSendFlowActivityContract.DockEntry(id = "GALLERY", title = R.string.MediaSelectScreen__gallery, icon = R.drawable.symbol_album_tilt_24, page = AttachmentPage.GALLERY),
      MediaSendFlowActivityContract.DockEntry(id = "FILE", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_file_24, page = AttachmentPage.FILES),
      MediaSendFlowActivityContract.DockEntry(id = "CONTACT", title = R.string.MediaSelectScreen__gallery, icon = org.signal.core.ui.R.drawable.symbol_person_circle_24)
    )

    private val FILES: List<RecentSentFile> = (0 until 20).map { index ->
      RecentSentFile(attachmentId = index.toLong(), fileName = "Quarterly plan $index.pdf", size = 1_024L * 700 * (index + 1), sentAt = 1_790_000_000_000L - index * 3_600_000L, isOnDevice = true)
    }
  }

  // endregion
}
