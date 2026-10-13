/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags

/**
 * Covers the send affordance's two gestures: a tap advances the flow, while a long press offers the schedule menu when
 * the flow allows scheduling. Also covers when the row falls back to its placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AddAMessageRowTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val events = mutableListOf<MediaEditScreenEvents>()
  private var nextClicks = 0

  @Test
  fun `Given scheduling is allowed, when the send button is tapped, then the flow advances without scheduling`() {
    setContent(canScheduleSend = true)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performClick()

    assertEquals(1, nextClicks)
    assertNull(events.filterIsInstance<MediaEditScreenEvents.ScheduleSendClick>().firstOrNull())
  }

  @Test
  fun `Given scheduling is allowed, when the send button is long pressed, then a time can be scheduled`() {
    setContent(canScheduleSend = true)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).performClick()

    assertEquals(
      MediaEditScreenEvents.ScheduleSendClick(ScheduleSendOption.PickTime),
      events.filterIsInstance<MediaEditScreenEvents.ScheduleSendClick>().single()
    )
    assertEquals(0, nextClicks)
  }

  @Test
  fun `Given scheduling is not allowed, when the send button is long pressed, then nothing is offered`() {
    setContent(canScheduleSend = false)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }

    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).assertDoesNotExist()
    assertEquals(emptyList<MediaEditScreenEvents>(), events)
  }

  /** Tellomi（#1263，ADR-0068 §4.7 P3）：长按发送菜单最上面是「说明文字上移」；点了交出切换、菜单不收（照 Telegram）。 */
  @Test
  fun `Given a caption that can move, when the send button is long pressed, then moving it comes first and keeps the menu open`() {
    setContent(canScheduleSend = true, captionAboveMedia = false)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).assertTextEquals(context.getString(R.string.MediaSelectScreen__move_caption_up))
    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).performClick()

    assertEquals(listOf<MediaEditScreenEvents>(MediaEditScreenEvents.ToggleCaptionAboveMedia), events)
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).assertExists()
    assertEquals(0, nextClicks)
  }

  @Test
  fun `Given the caption is above, when the send menu opens, then it offers to move it down`() {
    setContent(canScheduleSend = true, captionAboveMedia = true)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }

    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).assertTextEquals(context.getString(R.string.MediaSelectScreen__move_caption_down))
  }

  @Test
  fun `Given no caption to move, when the send menu opens, then only scheduling is offered`() {
    setContent(canScheduleSend = true, captionAboveMedia = null)

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }

    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).assertExists()
  }

  /**
   * Tellomi (tellomi/tellomi#1519 M-5, as Telegram): with media, a long press shows the message as it will be sent next to
   * the send button, with the menu under it, and moving the caption moves it in the preview without closing anything.
   */
  @Test
  fun `Given media, when the send button is long pressed, then the message is previewed next to it and follows the caption position`() {
    var above by mutableStateOf(false)
    setPreviewContent(canScheduleSend = true, message = "Trip", sendPreviewMedia = listOf(media(1), media(2)), captionAboveMedia = { above }, onEvent = {
      if (it == MediaEditScreenEvents.ToggleCaptionAboveMedia) above = !above
    })

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }

    // As Telegram's MessageSendPreview: the bubble ends just before the send button's circle and level with its bottom, the
    // menu starts under that, and both line up on the right.
    val bubble = composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_BUBBLE).getUnclippedBoundsInRoot()
    val menu = composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_MENU).getUnclippedBoundsInRoot()
    val circle = composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_SEND_BUTTON).getUnclippedBoundsInRoot()
    assertEquals(40f, circle.width.value, 0.5f)
    assertEquals((circle.left - 8.dp).value, bubble.right.value, 0.5f)
    assertEquals(circle.bottom.value, bubble.bottom.value, 0.5f)
    assertEquals((bubble.bottom + 8.dp).value, menu.top.value, 0.5f)
    assertEquals(bubble.right.value, menu.right.value, 0.5f)

    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_CAPTION_BELOW, useUnmergedTree = true).assertTextEquals("Trip")
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_CAPTION_ABOVE, useUnmergedTree = true).assertDoesNotExist()

    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).performClick()

    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_CAPTION_ABOVE, useUnmergedTree = true).assertTextEquals("Trip")
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_CAPTION_BELOW, useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.SEND_MENU_CAPTION_POSITION).assertTextEquals(context.getString(R.string.MediaSelectScreen__move_caption_down))
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).assertExists()
    assertEquals(0, nextClicks)
  }

  @Test
  fun `Given the preview is open, when the send button in it is tapped, then the message is sent and the preview closes`() {
    setPreviewContent(canScheduleSend = true, sendPreviewMedia = listOf(media(1)), captionAboveMedia = { false })

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_SEND_BUTTON).performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, nextClicks)
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW).assertDoesNotExist()
  }

  @Test
  fun `Given the preview is open, when the dimmed area is tapped, then it closes without sending`() {
    setPreviewContent(canScheduleSend = true, sendPreviewMedia = listOf(media(1)))

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW_SCRIM).performTouchInput { click(Offset(10f, 10f)) }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW).assertDoesNotExist()
    assertEquals(0, nextClicks)
    assertEquals(emptyList<MediaEditScreenEvents>(), events)
  }

  @Test
  fun `Given the preview is open, when a time is picked, then it is scheduled and the preview closes`() {
    setPreviewContent(canScheduleSend = true, sendPreviewMedia = listOf(media(1)))

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf<MediaEditScreenEvents>(MediaEditScreenEvents.ScheduleSendClick(ScheduleSendOption.PickTime)), events)
    composeTestRule.onNodeWithTag(TestTags.SEND_PREVIEW).assertDoesNotExist()
  }

  @Test
  fun `the preview's caption is the bubble's, as in the conversation`() {
    assertNull(previewCaption(null, listOf(null, "")))
    assertNull(previewCaption("  ", listOf(null)))
    assertEquals("Trip", previewCaption("Trip", listOf(null, null)))
    assertEquals("Sunset", previewCaption(null, listOf(null, "Sunset")))
    assertEquals("Trip", previewCaption("Trip", listOf("Sunset", null)))
    assertNull(previewCaption(null, listOf("Sunset", "Beach")))
  }

  /**
   * The button draws smaller than a finger, so the 48dp of touch target reserved around the circle it draws is the part
   * worth pinning down: a tap just off the circle still counts as one.
   */
  @Test
  fun `when a tap lands off the send button but inside its touch target, then the flow still advances`() {
    setContent()

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON).performTouchInput {
      click(Offset(width / 2f, height + 3.dp.toPx()))
    }

    assertEquals(1, nextClicks)
  }

  @Test
  fun `Given a blank message, when the row is displayed, then the placeholder is shown`() {
    setContent(message = "   ")

    composeTestRule.onNodeWithText("Message").assertExists()
  }

  @Test
  fun `Given a message, when the row is displayed, then the message is shown`() {
    setContent(message = "Check this out")

    composeTestRule.onNodeWithText("Check this out").assertExists()
  }

  @Test
  fun `Given a reply flow with no message, when the row is displayed, then the reply placeholder is shown`() {
    setContent(isReply = true)

    composeTestRule.onNodeWithText("Add a reply").assertExists()
  }

  @Test
  fun `Given a reply flow with a message, when the row is displayed, then the message is shown`() {
    setContent(message = "Check this out", isReply = true)

    composeTestRule.onNodeWithText("Check this out").assertExists()
  }

  private fun setContent(canScheduleSend: Boolean = false, message: CharSequence? = null, isReply: Boolean = false, captionAboveMedia: Boolean? = null) {
    setPreviewContent(canScheduleSend = canScheduleSend, message = message, isReply = isReply, captionAboveMedia = { captionAboveMedia })
  }

  private fun setPreviewContent(
    canScheduleSend: Boolean = false,
    message: CharSequence? = null,
    isReply: Boolean = false,
    sendPreviewMedia: List<Media>? = null,
    captionAboveMedia: () -> Boolean? = { null },
    onEvent: (MediaEditScreenEvents) -> Unit = {}
  ) {
    composeTestRule.setContent {
      SignalTheme {
        // With media, the bar sits at the bottom as it does in the flow, so the preview has room above it.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = if (sendPreviewMedia != null) Alignment.BottomCenter else Alignment.TopCenter) {
          AddAMessageRow(
            message = message,
            onEvent = {
              events += it
              onEvent(it)
            },
            onNextClick = { nextClicks++ },
            canScheduleSend = canScheduleSend,
            isReply = isReply,
            captionAboveMedia = captionAboveMedia(),
            sendPreviewMedia = sendPreviewMedia
          )
        }
      }
    }
  }

  private fun media(id: Int) = Media(
    uri = Uri.parse("content://media/$id"),
    contentType = "image/jpeg",
    date = 0,
    width = 400,
    height = 300,
    size = 1000,
    duration = 0,
    isBorderless = false,
    isVideoGif = false,
    bucketId = null,
    caption = null,
    transformProperties = null,
    fileName = null
  )
}
