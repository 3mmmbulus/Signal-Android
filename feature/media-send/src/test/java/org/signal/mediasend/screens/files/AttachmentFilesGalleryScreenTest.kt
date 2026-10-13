/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.R
import org.signal.mediasend.recentsFolder
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1263 F-3）：「从相册中选择」整屏网格：顶栏相册标题、点格子勾选、底栏「取消 / 发送（N）」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class AttachmentFilesGalleryScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val events = mutableListOf<AttachmentFilesGalleryEvent>()

  @Test
  fun `Given nothing is ticked, when shown, then the title is recents and send is disabled`() {
    setContent(AttachmentFilesGalleryState(folders = listOf(CAMERA), items = MEDIA))

    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_FOLDER_TITLE).assert(hasText(context.getString(R.string.MediaSelectScreen__recents)))
    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_SEND).assertIsNotEnabled()
  }

  @Test
  fun `Given two are ticked, when shown, then send counts them and is enabled`() {
    setContent(AttachmentFilesGalleryState(folders = listOf(CAMERA), items = MEDIA, selected = listOf(MEDIA[2], MEDIA[0])))

    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_SEND).assertIsEnabled()
    composeTestRule.onNodeWithText(context.resources.getQuantityString(R.plurals.AttachmentFilesGallery__send_n, 2, 2)).assertIsDisplayed()
  }

  @Test
  fun `Given the grid, when a tile is tapped, then it is toggled rather than opened`() {
    setContent(AttachmentFilesGalleryState(items = MEDIA))

    composeTestRule.onNodeWithTag(TestTags.filesGalleryTile(MEDIA[1].uri.toString())).performClick()

    assertThat(events).containsExactly(AttachmentFilesGalleryEvent.Toggle(MEDIA[1]))
  }

  @Test
  fun `Given the folder title, when tapped and another folder is picked, then it is switched to`() {
    setContent(AttachmentFilesGalleryState(folders = listOf(recentsFolder(), CAMERA), items = MEDIA))

    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_FOLDER_TITLE).performClick()
    composeTestRule.onNodeWithText(CAMERA.title).performClick()

    assertThat(events).containsExactly(AttachmentFilesGalleryEvent.SwitchFolder(CAMERA))
  }

  @Test
  fun `Given a selection, when cancel and send are tapped, then each is handed on`() {
    setContent(AttachmentFilesGalleryState(items = MEDIA, selected = listOf(MEDIA[0])))

    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_CANCEL).performClick()
    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_SEND).performClick()

    assertThat(events).containsExactly(AttachmentFilesGalleryEvent.Cancel, AttachmentFilesGalleryEvent.Send)
  }

  @Test
  fun `Given an empty folder, when shown, then it says there is nothing to pick`() {
    setContent(AttachmentFilesGalleryState(items = emptyList()))

    composeTestRule.onNodeWithText(context.getString(R.string.AttachmentFilesGallery__no_photos_or_videos)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.FILES_GALLERY_SEND).assertTextEquals(context.getString(R.string.AttachmentFilesGallery__send))
  }

  private fun setContent(state: AttachmentFilesGalleryState) {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(400.dp, 800.dp)) {
          AttachmentFilesGalleryScreen(state = state, onEvent = { events += it })
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    private val CAMERA = MediaFolder(
      thumbnailUri = Uri.EMPTY,
      title = "Camera",
      itemCount = 3,
      bucketId = "camera-bucket",
      folderType = MediaFolder.FolderType.CAMERA
    )

    private val MEDIA = (1..3).map {
      Media(
        uri = "content://media/$it".toUri(),
        contentType = "image/jpeg",
        date = 0,
        width = 100,
        height = 100,
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
}
