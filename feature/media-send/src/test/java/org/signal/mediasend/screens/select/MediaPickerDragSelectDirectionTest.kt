/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1521 G-8，照 Telegram 安卓 `ChatAttachAlertPhotoLayout` 的长按拖选）：长按那格决定这一次是选还是取消，
 * 拖过的格子都照它；本来就是那个状态的不动；拖回去只放回这一次改过的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPickerDragSelectDirectionTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()

  @Test
  fun `Given a selected tile, when it is long pressed and dragged across others, then the selected ones it crosses are unselected`() {
    setContent(selected = listOf(MEDIA[0], MEDIA[1]))

    drag(0, 1, 2)

    assertEquals("长按的和拖过的已选都取消，没选的那张也没被选上", emptySet<Media>(), selectionAfter(initial = setOf(MEDIA[0], MEDIA[1])))
    assertEquals("取消时一张都不加", emptyList<Media>(), events.filterIsInstance<MediaSelectScreenEvents.MediaSelected>().flatMap { it.media })
  }

  @Test
  fun `Given an unselected tile, when it is long pressed and dragged across a selected one, then the selected one is left alone`() {
    setContent(selected = listOf(MEDIA[1]))

    drag(0, 1, 2)

    assertEquals(setOf(MEDIA[0], MEDIA[1], MEDIA[2]), selectionAfter(initial = setOf(MEDIA[1])))
    assertEquals("选的时候一张都不取消", emptyList<Media>(), events.filterIsInstance<MediaSelectScreenEvents.MediaUnselected>().flatMap { it.media })
  }

  @Test
  fun `Given a drag that goes back, when it retracts, then only the tiles it changed are put back`() {
    setContent(selected = listOf(MEDIA[1]))

    drag(0, 1, 2, 0)

    assertEquals(setOf(MEDIA[0], MEDIA[1]), selectionAfter(initial = setOf(MEDIA[1])))
  }

  /** 从 [initial] 起把记下的选中 / 取消依次放上去。 */
  private fun selectionAfter(initial: Set<Media>): Set<Media> {
    val selection = initial.toMutableSet()
    events.forEach { event ->
      when (event) {
        is MediaSelectScreenEvents.MediaSelected -> selection += event.media
        is MediaSelectScreenEvents.MediaUnselected -> selection -= event.media
        else -> Unit
      }
    }
    return selection
  }

  private fun drag(vararg tiles: Int) {
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).performTouchInput {
      down(tileCenter(tiles.first()))
      advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
      tiles.drop(1).forEach { moveTo(tileCenter(it)) }
      up()
    }
    composeTestRule.waitForIdle()
  }

  /** 第 [index] 张媒体的格子中心（全部访问：网格的第 0 个孩子就是第 0 张）。 */
  private fun tileCenter(index: Int): Offset {
    val grid = composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).fetchSemanticsNode()
    val tile = grid.children[index]
    val offset = tile.positionInRoot - grid.positionInRoot

    return Offset(offset.x + tile.size.width / 2f, offset.y + tile.size.height / 2f)
  }

  private fun setContent(selected: List<Media>) {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(GRID_WIDTH.dp, GRID_HEIGHT.dp)) {
          MediaSelectScreen(
            state = MediaSelectState.Files(
              selectedMediaFolder = FOLDER,
              selectedMediaFolderItems = MEDIA,
              selectedMedia = selected,
              mediaPermissions = MediaPermissions.FULL
            ),
            onEvent = { events += it }
          )
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    private const val GRID_WIDTH = 400f
    private const val GRID_HEIGHT = 800f

    private val FOLDER = MediaFolder(
      thumbnailUri = "content://folder".toUri(),
      title = "All media",
      itemCount = 8,
      bucketId = "bucket",
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
        fileName = "media_$index.jpg"
      )
    }
  }
}
