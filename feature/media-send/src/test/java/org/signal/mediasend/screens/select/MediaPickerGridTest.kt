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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.ScheduleSendOption
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1261，需求第三节 P-1、P-2、P-5、P-8…P-10，判据 1、3、5）：选图网格的顶栏、编号勾、底栏与「···」菜单。
 * 替换上游选图页底部「下一步（N）」按钮的用例（那个按钮和缩略条已由顶栏「✓N」与底栏「说明 + 发送」取代）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class MediaPickerGridTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()

  // 判据 1：0 张时顶栏只有 ✕ 和「最近 ⌄」；选 1 张时出现「✓1」和「···」。

  @Test
  fun `Given nothing is selected, when displayed, then the top bar has only close and Recents`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).assertTextContains("Recents")
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertDoesNotExist()
    composeTestRule.onNode(hasContentDescription("More options")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SEND_BAR).assertDoesNotExist()
  }

  @Test
  fun `Given one photo is selected, when displayed, then the count pill says 1 and the more menu is there`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_COUNT_PILL).assertIsDisplayed().assertTextContains("1")
    composeTestRule.onNode(hasContentDescription("More options")).assertIsDisplayed()
  }

  // G-4：视频格子左下角一条深色角标，播放三角 + 时长（照 Telegram 安卓 `PhotoAttachPhotoCell` 的 videoInfoContainer）；照片没有。

  @Test
  fun `Given a video, when displayed, then its tile has a duration badge in the bottom left corner`() {
    val video = MEDIA[2].copy(uri = "content://media/video".toUri(), contentType = "video/mp4", duration = 75_000)
    setContent(selectedMedia = emptyList(), items = MEDIA.take(2) + video)

    val badges = composeTestRule.onAllNodesWithTag(TestTags.MEDIA_PICKER_VIDEO_BADGE, useUnmergedTree = true)
    badges.assertCountEquals(1)
    val badge = badges[0].assert(hasAnyDescendant(hasText("1:15"))).fetchSemanticsNode().boundsInRoot
    val tile = composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(video.uri.toString())).fetchSemanticsNode().boundsInRoot
    val density = composeTestRule.density.density
    assertEquals("离左边 4", 4f, (badge.left - tile.left) / density, 0.5f)
    assertEquals("离下边 4", 4f, (tile.bottom - badge.bottom) / density, 0.5f)
    assertEquals("高 17", 17f, badge.height / density, 0.5f)
  }

  // G-1：没有照片权限时「允许访问」下面有「打开相机」（照 Telegram 安卓 GalleryEmptyView 的 cameraAccessButton），没有相机就不给。

  @Test
  fun `Given no photo access and a camera, when Open Camera is clicked, then the camera is opened`() {
    setContent(selectedMedia = emptyList(), mediaPermissions = MediaPermissions.NONE, cameraAccess = PickerCameraAccess.NOT_GRANTED)

    composeTestRule.onNodeWithText("Allow Access").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_NO_ACCESS_CAMERA).assertIsDisplayed().assertTextContains("Open Camera").performClick()

    assertEquals(listOf(MediaSelectScreenEvents.NavigateToCamera), events.filterIsInstance<MediaSelectScreenEvents.NavigateToCamera>())
  }

  @Test
  fun `Given no photo access and no camera, when displayed, then there is no Open Camera`() {
    setContent(selectedMedia = emptyList(), mediaPermissions = MediaPermissions.NONE)

    composeTestRule.onNodeWithText("Allow Access").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_NO_ACCESS_CAMERA).assertDoesNotExist()
  }

  // P-8：编号勾显示第几张；点勾选上 / 取消，点照片本身进单张预览 / 编辑（P-10）。

  @Test
  fun `Given two items are selected, when displayed, then each check shows its place in the selection`() {
    setContent(selectedMedia = listOf(MEDIA[3], MEDIA[1]))

    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[3].uri.toString())).assertTextContains("1")
    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[1].uri.toString())).assertTextContains("2")
  }

  @Test
  fun `Given a tile, when its check is clicked, then the selection is toggled rather than the editor opened`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.mediaPickerCheck(MEDIA[2].uri.toString())).performClick()

    assertThat(events.filterNot { it is MediaSelectScreenEvents.Refresh }).containsExactly(MediaSelectScreenEvents.MediaClick(MEDIA[2]))
  }

  @Test
  fun `Given a tile, when the photo itself is clicked, then it is opened in the editor`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(MEDIA[2].uri.toString())).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.OpenMedia(MEDIA[2]))
  }

  // owner 2026-09-28：「点右上角的勾，有时会跳到发送预览」——勾的触摸区只有 29dp，手指落在圆旁边一点就算点了照片。
  // 照 Telegram Android `PhotoAttachPhotoCell` 的 checkFrame：右上角 42 × 42 的整块角区都算点勾，角区外才是点照片。

  @Test
  fun `Given a tile, when tapped inside the top-end corner but beside the drawn check, then the selection is toggled`() {
    setContent(selectedMedia = emptyList())

    for (offset in listOf(36 to 36, 41 to 8, 8 to 41, 2 to 2)) {
      events.clear()
      composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(MEDIA[2].uri.toString())).performTouchInput {
        click(Offset(width - offset.first.dp.toPx(), offset.second.dp.toPx()))
      }
      composeTestRule.waitForIdle()

      assertThat(events.filterNot { it is MediaSelectScreenEvents.Refresh }, name = "tap at $offset").containsExactly(MediaSelectScreenEvents.MediaClick(MEDIA[2]))
    }
  }

  @Test
  fun `Given a tile, when tapped just outside the top-end corner, then it is opened in the editor`() {
    setContent(selectedMedia = emptyList())

    for (offset in listOf(46 to 20, 20 to 46)) {
      events.clear()
      composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(MEDIA[2].uri.toString())).performTouchInput {
        click(Offset(width - offset.first.dp.toPx(), offset.second.dp.toPx()))
      }
      composeTestRule.waitForIdle()

      assertThat(events.filterNot { it is MediaSelectScreenEvents.Refresh }, name = "tap at $offset").containsExactly(MediaSelectScreenEvents.OpenMedia(MEDIA[2]))
    }
  }

  // 判据 5：选完在网格里写说明直接发出。

  @Test
  fun `Given a selection, when send is clicked, then it is sent from the grid`() {
    setContent(selectedMedia = MEDIA.take(2))

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_SEND_BAR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON, useUnmergedTree = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.Send)
  }

  // owner 2026-09-28（D4）：长按发送键 →「定时发送」两端都做。安卓上游的定时发送只接在单张预览页的发送键上，
  // 网格里直接发的那一栏没接；照 Telegram（ChatSendMessageContextScreen 的 schedule 项）在网格的发送键上也能长按定时。

  @Test
  fun `Given a selection in a conversation, when the grid send button is long pressed, then a send can be scheduled`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(canScheduleSend = true))

    composeTestRule.onNodeWithTag(TestTags.ADD_A_MESSAGE_NEXT_BUTTON, useUnmergedTree = true).performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(TestTags.SCHEDULE_SEND_PICK_TIME_OPTION).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.ScheduleSend(ScheduleSendOption.PickTime))
    assertThat(events.contains(MediaSelectScreenEvents.Send)).isFalse()
  }

  @Test
  fun `Given a selection, when the caption field is clicked, then the message is asked for`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNodeWithText("Message", useUnmergedTree = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.AddMessage(startWithEmojiKeyboard = false))
  }

  // 判据 3：「···」只有「以高清质量发送」「单独发送」，点了立即发出；D9：默认已是高时换成「以标准质量发送」。

  @Test
  fun `Given two photos in a conversation, when the more menu is opened, then it offers high quality and separately`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(canSendSeparately = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in High Quality").assertIsDisplayed()
    composeTestRule.onNodeWithText("Send Separately").performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SendSeparately)
  }

  @Test
  fun `Given the menu, when send in high quality is picked, then it is sent right away at high quality`() {
    setContent(selectedMedia = MEDIA.take(1))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in High Quality").performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.HIGH))
  }

  // V-7 / O4（照 Telegram 安卓 getSelectedPhotosCount）：选的全是静态图才有高清项。

  @Test
  fun `Given a photo and a video, when the more menu is opened, then there is no high quality`() {
    val video = MEDIA[2].copy(uri = "content://media/video".toUri(), contentType = "video/mp4", duration = 75_000)
    setContent(selectedMedia = listOf(MEDIA[0], video), items = MEDIA.take(2) + video, sendOptions = MediaSelectState.SendOptions(canSendSeparately = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send Separately").assertIsDisplayed()
    composeTestRule.onNodeWithText("Send in High Quality").assertDoesNotExist()
  }

  @Test
  fun `Given the default is already high, when the more menu is opened, then it offers standard quality instead`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(sentMediaQuality = SentMediaQuality.HIGH))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send in Standard Quality").performClick()
    composeTestRule.onNodeWithText("Send in High Quality").assertDoesNotExist()

    assertThat(events).contains(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.STANDARD))
  }

  @Test
  fun `Given one photo, when the more menu is opened, then there is no send separately`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(canSendSeparately = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send Separately").assertDoesNotExist()
  }

  // #1263（P-5，判据 7）：「···」第一项是「以文件发送」，多张时「以文件发送（N 个）」，点了立即发出；开了一次性查看就没有。
  // 已选里有 HEIC 先问「是否把照片转成 JPEG？」（保留 HEIC / 转成 JPEG），点外面这次不发。

  @Test
  fun `Given two photos in a conversation, when the more menu is opened, then send as files comes first with the count`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(canSendSeparately = true, canSendAsFile = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    val asFiles = composeTestRule.onNodeWithText("Send as 2 Files").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
    val highQuality = composeTestRule.onNodeWithText("Send in High Quality").fetchSemanticsNode().boundsInRoot.top
    val separately = composeTestRule.onNodeWithText("Send Separately").fetchSemanticsNode().boundsInRoot.top
    assertThat(asFiles < highQuality && highQuality < separately).isTrue()

    composeTestRule.onNodeWithText("Send as 2 Files").performClick()

    assertThat(sentAsFile()).containsExactly(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = false))
  }

  @Test
  fun `Given one photo in a conversation, when the more menu is opened, then it says send as file without a count`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(canSendAsFile = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send as File").performClick()

    assertThat(sentAsFile()).containsExactly(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = false))
  }

  @Test
  fun `Given view once is on, when the more menu is opened, then there is no send as file`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(canSendAsFile = true, isViewOnceAvailable = true, isViewOnceEnabled = true))

    composeTestRule.onNode(hasContentDescription("More options")).assertDoesNotExist()
    composeTestRule.onNodeWithText("Send as File").assertDoesNotExist()
  }

  @Test
  fun `Given a flow that is not a known conversation, when the more menu is opened, then there is no send as file`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(canSendAsFile = false))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send as 2 Files").assertDoesNotExist()
    composeTestRule.onNodeWithText("Send in High Quality").assertIsDisplayed()
  }

  @Test
  fun `Given a HEIC photo, when send as file is picked and HEIC is kept, then it is sent as it is`() {
    setContent(selectedMedia = listOf(MEDIA[0], HEIC), sendOptions = MediaSelectState.SendOptions(canSendAsFile = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send as 2 Files").performClick()
    composeTestRule.onNodeWithText("Convert the photos to JPEG?").assertIsDisplayed()
    assertThat(sentAsFile()).isEmpty()

    composeTestRule.onNodeWithText("Keep HEIC").performClick()

    assertThat(sentAsFile()).containsExactly(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = false))
    composeTestRule.onNodeWithText("Convert the photos to JPEG?").assertDoesNotExist()
  }

  @Test
  fun `Given a HEIC photo, when send as file is picked and converting is chosen, then it is sent as JPEG`() {
    setContent(selectedMedia = listOf(HEIC), sendOptions = MediaSelectState.SendOptions(canSendAsFile = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Send as File").performClick()
    composeTestRule.onNodeWithText("Convert to JPEG").performClick()

    assertThat(sentAsFile()).containsExactly(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = true))
  }

  // M-2（#1263，ADR-0068 §4.7）：写了说明，「···」最后（分隔线下面）是「说明文字上移」；已经在上方就是「下移」；点了是开关，不发送。

  @Test
  fun `Given a caption, when the more menu is opened, then the caption can be moved up`() {
    setContent(selectedMedia = MEDIA.take(2), sendOptions = MediaSelectState.SendOptions(message = "caption", canSendSeparately = true, canMoveCaption = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    val separately = composeTestRule.onNodeWithText("Send Separately").fetchSemanticsNode().boundsInRoot.top
    val moveUp = composeTestRule.onNodeWithText("Move Caption Up").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
    assertThat(separately < moveUp).isTrue()
    composeTestRule.onNodeWithText("Move Caption Up").performClick()

    assertThat(events.filterIsInstance<MediaSelectScreenEvents.ToggleCaptionAboveMedia>().size).isEqualTo(1)
    assertThat(sentAsFile()).isEmpty()
  }

  @Test
  fun `Given the caption is above, when the more menu is opened, then it can be moved down`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(message = "caption", canMoveCaption = true, isCaptionAboveMedia = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Move Caption Down").assertIsDisplayed()
    composeTestRule.onNodeWithText("Move Caption Up").assertDoesNotExist()
  }

  @Test
  fun `Given no caption, when the more menu is opened, then there is no caption item`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(message = "  ", canMoveCaption = true))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Move Caption Up").assertDoesNotExist()
  }

  // #1263（P-5 / P-6，ADR-0068 §4.4）：分隔线下面「说明文字上移」后面是「罩住图片」；全罩了是「取消遮罩」；罩了就没有以文件发送；
  // 罩住的格子盖一层遮罩。

  @Test
  fun `Given a conversation, when the more menu is opened, then hide with spoiler comes last and toggles`() {
    setContent(
      selectedMedia = MEDIA.take(2),
      sendOptions = MediaSelectState.SendOptions(message = "caption", canSendAsFile = true, canMoveCaption = true, canSpoiler = true)
    )

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    val moveUp = composeTestRule.onNodeWithText("Move Caption Up").fetchSemanticsNode().boundsInRoot.top
    val hide = composeTestRule.onNodeWithText("Hide with Spoiler").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
    assertThat(moveUp < hide).isTrue()
    composeTestRule.onNodeWithText("Hide with Spoiler").performClick()

    assertThat(events.filterIsInstance<MediaSelectScreenEvents.ToggleSpoiler>().size).isEqualTo(1)
    assertThat(sentAsFile()).isEmpty()
  }

  @Test
  fun `Given every selected photo is covered, when the more menu is opened, then it offers remove spoiler and no send as file`() {
    setContent(
      selectedMedia = MEDIA.take(2),
      sendOptions = MediaSelectState.SendOptions(canSendAsFile = true, canSpoiler = true, spoileredUris = MEDIA.take(2).map { it.uri }.toSet())
    )

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Remove Spoiler").assertIsDisplayed()
    composeTestRule.onNodeWithText("Hide with Spoiler").assertDoesNotExist()
    composeTestRule.onNodeWithText("Send as 2 Files").assertDoesNotExist()
  }

  @Test
  fun `Given one of two is covered, when the more menu is opened, then it still offers hide with spoiler`() {
    setContent(
      selectedMedia = MEDIA.take(2),
      sendOptions = MediaSelectState.SendOptions(canSendAsFile = true, canSpoiler = true, spoileredUris = setOf(MEDIA[0].uri))
    )

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Hide with Spoiler").assertIsDisplayed()
    composeTestRule.onNodeWithText("Send as 2 Files").assertDoesNotExist()
  }

  @Test
  fun `Given covered photos, when displayed, then only their tiles are covered`() {
    setContent(
      selectedMedia = MEDIA.take(3),
      sendOptions = MediaSelectState.SendOptions(canSpoiler = true, spoileredUris = setOf(MEDIA[0].uri, MEDIA[2].uri))
    )

    composeTestRule.onAllNodesWithTag(PickerSpoiler.TEST_TAG, useUnmergedTree = true).assertCountEquals(2)
  }

  @Test
  fun `Given a flow that is not a known conversation, when the more menu is opened, then there is no spoiler`() {
    setContent(selectedMedia = MEDIA.take(1), sendOptions = MediaSelectState.SendOptions(canSpoiler = false))

    composeTestRule.onNode(hasContentDescription("More options")).performClick()
    composeTestRule.onNodeWithText("Hide with Spoiler").assertDoesNotExist()
  }

  @Test
  fun `spoiler overlay timings and density`() {
    assertThat(PickerSpoiler.FADE_MILLIS).isEqualTo(200)
    assertThat(PickerSpoiler.scrimAlpha(sdk = 30) > PickerSpoiler.scrimAlpha(sdk = 31)).isTrue()
    assertThat(PickerSpoiler.particleCount(120f, 120f)).isEqualTo(73)
    assertThat(PickerSpoiler.particleCount(10f, 10f)).isEqualTo(24)
  }

  // P-1：「最近 ⌄」里换相册。

  @Test
  fun `Given the folder title, when another folder is picked, then the grid switches to it`() {
    setContent(selectedMedia = emptyList())

    composeTestRule.onNodeWithTag(TestTags.MEDIA_PICKER_FOLDER_TITLE).performClick()
    composeTestRule.onNodeWithText("Camera", substring = true).performClick()

    assertThat(events).contains(MediaSelectScreenEvents.SwitchFolder(CAMERA))
  }

  /** 网格一出来会先发一个 Refresh，只看「以文件发送」。 */
  private fun sentAsFile(): List<MediaSelectScreenEvents.SendAsFile> = events.filterIsInstance<MediaSelectScreenEvents.SendAsFile>()

  private fun setContent(
    selectedMedia: List<Media>,
    sendOptions: MediaSelectState.SendOptions = MediaSelectState.SendOptions(),
    items: List<Media> = MEDIA,
    mediaPermissions: MediaPermissions = MediaPermissions.FULL,
    cameraAccess: PickerCameraAccess = PickerCameraAccess.NONE
  ) {
    composeTestRule.setContent {
      SignalTheme {
        Box(modifier = Modifier.size(SCREEN_WIDTH.dp, SCREEN_HEIGHT.dp)) {
          MediaSelectScreen(
            state = MediaSelectState.Files(
              selectedMediaFolder = RECENTS,
              selectedMediaFolderItems = items,
              selectedMedia = selectedMedia,
              mediaFolders = listOf(RECENTS, CAMERA),
              sendOptions = sendOptions,
              mediaPermissions = mediaPermissions,
              cameraAccess = cameraAccess
            ),
            onEvent = { events += it }
          )
        }
      }
    }

    composeTestRule.waitForIdle()
  }

  private companion object {
    private const val SCREEN_WIDTH = 400f
    private const val SCREEN_HEIGHT = 800f

    private val RECENTS = MediaFolder(
      thumbnailUri = "content://recents".toUri(),
      title = "All media",
      itemCount = 8,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val CAMERA = MediaFolder(
      thumbnailUri = "content://camera".toUri(),
      title = "Camera",
      itemCount = 3,
      bucketId = "camera",
      folderType = MediaFolder.FolderType.CAMERA
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

    private val HEIC: Media = MEDIA[1].copy(uri = "content://media/heic".toUri(), contentType = "image/heic")
  }
}
