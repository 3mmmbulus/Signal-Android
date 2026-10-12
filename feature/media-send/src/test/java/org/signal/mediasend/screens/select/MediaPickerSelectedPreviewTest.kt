/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.albumcarousel.AlbumCarouselGeometry
import org.signal.core.ui.compose.LocalChatWallpaper
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.MediaRecipientId
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1261 P-3）：真的选图页上点「✓N」切到「只看已选」。
 * 选中状态照 ViewModel 那样随事件更新：选上追加在末尾、取消就拿掉、ReorderSelectedMedia 把 from 挪到 to。
 * Robolectric 里 1dp = 1px；屏幕与网格都是 400 × 800（默认屏幕只有 320 宽，会把网格夹窄），所以行高 = 400 × 0.6 = 240。
 * 在视口外的卡片先滚到看得见再点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class MediaPickerSelectedPreviewTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()
  private var selection by mutableStateOf(emptyList<Media>())

  @Test
  fun `When the count pill is tapped, then the grid gives way to the selected-only preview`() {
    selection = listOf(MEDIA[3], MEDIA[0], MEDIA[5])
    setContent(caption = "今天的照片", recipientId = 42L)

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SELECTED_PREVIEW).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).assertDoesNotExist()
    composeTestRule.onNodeWithTag("wallpaper_42").assertExists()
    assertEquals("顶部只有「拖动可调整顺序」（owner 2026-09-25 选 A：不再有「消息预览」）", listOf("Drag to reorder"), previewChipTexts())
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).assertDoesNotExist()
    composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_CAPTION).assertTextEquals("今天的照片")

    // 卡片按选中的顺序、编号 1…N，位置与宽照 AlbumCarouselGeometry（起点 16、间距 8、按比例、最宽给下一张留 48）。
    val expected = expectedLayout(listOf(MEDIA[3], MEDIA[0], MEDIA[5]))
    listOf(MEDIA[3], MEDIA[0], MEDIA[5]).forEachIndexed { index, media ->
      val bounds = cardBoundsInRow(media)
      assertEquals("第 ${index + 1} 张的左边", expected.itemLefts[index].toFloat(), bounds.first, 1f)
      assertEquals("第 ${index + 1} 张的宽", expected.itemWidths[index].toFloat(), bounds.second, 1f)
      composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCheck(media.uri.toString()))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Number ${index + 1}"))
    }

    composeTestRule.onNodeWithContentDescription("Back").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertIsDisplayed()
    assertTrue("返回不是关闭", events.none { it is MediaSelectScreenEvents.Close })
  }

  @Test
  fun `Given a single selection, then there is no drag hint and the card sits at the end`() {
    selection = listOf(MEDIA[2])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    assertEquals("只有 1 张时顶部什么都不提示", emptyList<String>(), previewChipTexts())
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_CAPTION).assertDoesNotExist()
    val (left, width) = cardBoundsInRow(MEDIA[2])
    assertEquals("放得下时靠右（同自己发的）", GRID_WIDTH - AlbumCarouselGeometry.END_MARGIN_DP, left + width, 1f)
  }

  @Test
  fun `When the system back is pressed in the preview, then the grid comes back`() {
    selection = listOf(MEDIA[3], MEDIA[0])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).assertIsDisplayed()
    assertTrue(events.none { it is MediaSelectScreenEvents.NavigateBack })
  }

  @Test
  fun `When a card is long pressed for 300ms and dragged past the next one, then that is the new order`() {
    // 三张竖图都在视口里（9:16 宽 135、9:16 宽 135、3:4 宽 180），拖动不碰两端的自动滚。
    selection = listOf(MEDIA[4], MEDIA[10], MEDIA[1])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    val first = cardCenterInRow(MEDIA[4])
    val second = cardCenterInRow(MEDIA[10])
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).performTouchInput {
      down(first)
      advanceEventTime(350)
      moveTo(Offset(first.x + 40f, first.y))
      moveTo(Offset(second.x + 30f, first.y))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MediaSelectScreenEvents.ReorderSelectedMedia(fromIndex = 0, toIndex = 1)), events.filterIsInstance<MediaSelectScreenEvents.ReorderSelectedMedia>())
    assertEquals(listOf(MEDIA[10], MEDIA[4], MEDIA[1]), selection)
    assertTrue("拖动不是点按", events.none { it is MediaSelectScreenEvents.OpenSelectedMedia })
  }

  @Test
  fun `When a card is dragged before 300ms, then it scrolls the row instead of reordering`() {
    selection = listOf(MEDIA[3], MEDIA[0], MEDIA[5])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    val first = cardCenterInRow(MEDIA[3])
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).performTouchInput {
      down(first)
      advanceEventTime(100)
      repeat(6) { moveBy(Offset(-40f, 0f)) }
      up()
    }
    composeTestRule.waitForIdle()

    assertTrue(events.none { it is MediaSelectScreenEvents.ReorderSelectedMedia })
    assertEquals(listOf(MEDIA[3], MEDIA[0], MEDIA[5]), selection)
    assertTrue("这一行滚了", rowScrollOffset() > 0f)
  }

  @Test
  fun `When the dragged card is held near the end, then the row scrolls and the card goes last`() {
    selection = listOf(MEDIA[3], MEDIA[9], MEDIA[15], MEDIA[21], MEDIA[27])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    val first = cardCenterInRow(MEDIA[3])
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).performTouchInput {
      down(first)
      advanceEventTime(350)
      moveTo(Offset(GRID_WIDTH - 6f, first.y))
      advanceEventTime(3_000)
      moveBy(Offset(1f, 0f))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(MediaSelectScreenEvents.ReorderSelectedMedia(fromIndex = 0, toIndex = 4)), events.filterIsInstance<MediaSelectScreenEvents.ReorderSelectedMedia>())
    assertTrue("整行往右滚了", rowScrollOffset() > 0f)
  }

  @Test
  fun `When the row is flung, then it rests with a card's left edge at the start`() {
    selection = listOf(MEDIA[3], MEDIA[0], MEDIA[5], MEDIA[9])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    val layout = expectedLayout(selection)
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).performTouchInput {
      swipeLeft(startX = 350f, endX = 50f, durationMillis = 150)
    }
    composeTestRule.waitForIdle()

    val offset = rowScrollOffset()
    assertTrue("停在吸附位 ${layout.snapOffsets.toList()}，实际 $offset", layout.snapOffsets.any { kotlin.math.abs(it - offset) <= 1f })
    assertTrue("甩出去至少走一格", offset > 0f)
  }

  @Test
  fun `When the row is dragged a little and let go slowly, then it settles back on the nearest card`() {
    selection = listOf(MEDIA[3], MEDIA[0], MEDIA[5], MEDIA[9])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    // 第二张的吸附位是 336：慢慢拖 100 松手，离 0 更近，回到第一张。
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).performTouchInput {
      down(Offset(300f, 120f))
      repeat(10) {
        advanceEventTime(60)
        moveBy(Offset(-10f, 0f))
      }
      advanceEventTime(200)
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(0f, rowScrollOffset(), 1f)
  }

  @Test
  fun `When a card is tapped, then it opens in the editor`() {
    selection = listOf(MEDIA[3], MEDIA[0])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCard(MEDIA[0].uri.toString())).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(MediaSelectScreenEvents.OpenSelectedMedia(MEDIA[0])), events.filterIsInstance<MediaSelectScreenEvents.OpenSelectedMedia>())
  }

  @Test
  fun `When cards are unchecked in the preview, then an undo bar counts them and undo puts them back in place`() {
    selection = listOf(MEDIA[3], MEDIA[0], MEDIA[5])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCheck(MEDIA[0].uri.toString())).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(listOf(MEDIA[3], MEDIA[5]), selection)
    composeTestRule.onNodeWithText("1 deselected").assertIsDisplayed()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCheck(MEDIA[5].uri.toString())).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("2 deselected").assertIsDisplayed()
    assertEquals("只剩一张时顶部什么都不提示", emptyList<String>(), previewChipTexts())

    composeTestRule.onNodeWithText("Undo").performClick()
    composeTestRule.waitForIdle()
    assertEquals("放回原来的位置", listOf(MEDIA[3], MEDIA[0], MEDIA[5]), selection)
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_UNDO_BAR).assertDoesNotExist()
  }

  @Test
  fun `Given the undo bar, when 4 seconds pass, then it goes away`() {
    selection = listOf(MEDIA[3], MEDIA[0])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCheck(MEDIA[0].uri.toString())).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_UNDO_BAR).assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(4_100)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_UNDO_BAR).assertDoesNotExist()
  }

  @Test
  fun `When everything is unchecked in the preview, then it goes back to the grid and undo still works there`() {
    selection = listOf(MEDIA[2])
    setContent()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCheck(MEDIA[2].uri.toString())).performScrollTo().performClick()
    composeTestRule.mainClock.advanceTimeBy(400)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).assertIsDisplayed()
    composeTestRule.onNodeWithText("1 deselected").assertIsDisplayed()

    composeTestRule.onNodeWithText("Undo").performClick()
    composeTestRule.waitForIdle()
    assertEquals(listOf(MEDIA[2]), selection)
    composeTestRule.onNodeWithTag(TestTags.MEDIA_SELECT_GRID).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertIsDisplayed()
  }

  /** 卡片在这一行内容坐标里的（左边, 宽）：卡片在屏幕上的位置加上这一行已经滚过的距离。 */
  /** 「只看已选」顶部的小字标签，按从上到下的顺序。 */
  private fun previewChipTexts(): List<String> {
    return composeTestRule.onAllNodesWithTag(TestTags.MEDIA_PICKER_PREVIEW_CHIP, useUnmergedTree = true)
      .fetchSemanticsNodes()
      .sortedBy { it.boundsInRoot.top }
      .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
  }

  private fun cardBoundsInRow(media: Media): Pair<Float, Float> {
    val row = composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).fetchSemanticsNode()
    val card = composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCard(media.uri.toString())).fetchSemanticsNode()
    val left = card.positionInRoot.x - row.positionInRoot.x + rowScrollOffset()
    return left to card.size.width.toFloat()
  }

  /** 卡片中心在这一行节点坐标里的位置（performTouchInput 用）。 */
  private fun cardCenterInRow(media: Media): Offset {
    val row = composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).fetchSemanticsNode()
    val card = composeTestRule.onNodeWithTag(TestTags.mediaPickerPreviewCard(media.uri.toString())).fetchSemanticsNode()
    val offset = card.positionInRoot - row.positionInRoot
    return Offset(offset.x + card.size.width / 2f, offset.y + card.size.height / 2f)
  }

  private fun rowScrollOffset(): Float {
    val row = composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_PREVIEW_ROW).fetchSemanticsNode()
    return row.config[SemanticsProperties.HorizontalScrollAxisRange].value()
  }

  private fun expectedLayout(media: List<Media>): AlbumCarouselGeometry.Layout {
    val configuration = ApplicationProvider.getApplicationContext<Context>().resources.configuration
    val rowHeight = AlbumCarouselGeometry.rowHeightDp(GRID_WIDTH, configuration.screenHeightDp.toFloat(), false)
    return AlbumCarouselGeometry.layout(
      viewportWidth = GRID_WIDTH.toInt(),
      startInset = 16,
      endMargin = AlbumCarouselGeometry.END_MARGIN_DP.toInt(),
      spacing = AlbumCarouselGeometry.ITEM_SPACING_DP.toInt(),
      minNextPeek = AlbumCarouselGeometry.NEXT_ITEM_MIN_PEEK_DP.toInt(),
      rowHeight = rowHeight.toInt(),
      aspectRatios = media.map { AlbumCarouselGeometry.aspectRatio(it.width, it.height) },
      alignEndWhenFits = true
    )
  }

  private fun onEvent(event: MediaSelectScreenEvents) {
    events += event
    when (event) {
      is MediaSelectScreenEvents.MediaSelected -> selection = selection + event.media.filterNot { media -> selection.any { it.uri == media.uri } }
      is MediaSelectScreenEvents.MediaUnselected -> selection = selection.filterNot { media -> event.media.any { it.uri == media.uri } }
      is MediaSelectScreenEvents.ReorderSelectedMedia -> {
        if (event.fromIndex != event.toIndex && event.fromIndex in selection.indices && event.toIndex in selection.indices) {
          selection = selection.toMutableList().apply { add(event.toIndex, removeAt(event.fromIndex)) }
        }
      }
      else -> Unit
    }
  }

  private fun setContent(caption: String? = null, recipientId: Long? = null) {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(
          LocalChatWallpaper provides { id, modifier -> Box(modifier = modifier.testTag("wallpaper_$id")) }
        ) {
          Box(modifier = Modifier.size(GRID_WIDTH.dp, GRID_HEIGHT.dp)) {
            MediaSelectScreen(
              state = MediaSelectState.Files(
                selectedMediaFolder = FOLDER,
                selectedMediaFolderItems = MEDIA,
                selectedMedia = selection,
                recipientId = recipientId?.let { MediaRecipientId(it) },
                mediaPermissions = MediaPermissions.FULL,
                sendOptions = MediaSelectState.SendOptions(message = caption)
              ),
              onEvent = ::onEvent
            )
          }
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
      itemCount = 30,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    /** 比例轮着来：4:3、3:4、1:1、16:9、9:16、3:2。 */
    private val SIZES = listOf(4032 to 3024, 3024 to 4032, 2000 to 2000, 1920 to 1080, 1080 to 1920, 3000 to 2000)

    private val MEDIA: List<Media> = (0 until 30).map { index ->
      Media(
        uri = "content://media/$index".toUri(),
        contentType = "image/jpeg",
        date = index.toLong(),
        width = SIZES[index % SIZES.size].first,
        height = SIZES[index % SIZES.size].second,
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
