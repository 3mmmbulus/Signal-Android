/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
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
import org.signal.mediasend.TellomiMotionPhoto
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1263 spec P-4）：选图网格里的实况——顶栏「⋮」左边的开关（已选里有动态照片才有）、点了的提示
 * （2.5 秒、碰一下就消失）、动态照片格子左上角的图标（选了的按自己的开关，没选的跟顶栏开关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class MediaPickerLiveTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaSelectScreenEvents>()

  @Test
  fun `Given no live photo among the selected, then there is no live switch`() {
    setContent(selectedMedia = listOf(STILL), sendOptions = MediaSelectState.SendOptions(canLive = true))
    composeTestRule.onNodeWithTag(PickerLive.TOGGLE_TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun `Given a live photo among the selected, when the switch is pressed, then it says so for two and a half seconds`() {
    setContent(selectedMedia = listOf(STILL, LIVE_A), sendOptions = MediaSelectState.SendOptions(canLive = true, hasSelectedLive = true))

    composeTestRule.onNodeWithTag(PickerLive.TOGGLE_TEST_TAG)
      .assertIsDisplayed()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Live Photo turned off"))
      .performClick()

    assertThat(events.filterIsInstance<MediaSelectScreenEvents.ToggleLive>().size).isEqualTo(1)
    composeTestRule.onNodeWithText("Live Photo turned on").assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(PickerLive.HINT_MILLIS + 500)
    composeTestRule.onNodeWithTag(PickerLive.HINT_TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun `Given the hint is showing, when the screen is touched anywhere, then it goes away`() {
    setContent(selectedMedia = listOf(LIVE_A), sendOptions = MediaSelectState.SendOptions(canLive = true, hasSelectedLive = true, isLiveToggleOn = true))
    composeTestRule.onNodeWithTag(PickerLive.TOGGLE_TEST_TAG).performClick()
    composeTestRule.onNodeWithText("Live Photo turned off").assertIsDisplayed()

    composeTestRule.onNodeWithTag(TestTags.mediaPickerTile(STILL.uri.toString())).performClick()
    composeTestRule.mainClock.advanceTimeBy(500)

    composeTestRule.onNodeWithTag(PickerLive.HINT_TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun `Given live photos, when displayed, then they have a live badge that follows their switch`() {
    setContent(
      selectedMedia = listOf(LIVE_A),
      sendOptions = MediaSelectState.SendOptions(canLive = true, hasSelectedLive = true, isLiveToggleOn = true, liveOnUris = setOf(LIVE_A.uri), liveDefault = false)
    )

    composeTestRule.waitUntil(timeoutMillis = 2_000) {
      composeTestRule.onAllNodesWithTag(PickerLive.badgeTestTag(LIVE_B.uri.toString()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    // Selected and on; not selected, so it follows the switch (off); an ordinary photo has none.
    badge(LIVE_A).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "on"))
    badge(LIVE_B).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "off"))
    badge(STILL).assertDoesNotExist()
  }

  @Test
  fun `Given a flow that is not a conversation, then no live badges`() {
    setContent(selectedMedia = listOf(LIVE_A), sendOptions = MediaSelectState.SendOptions(canLive = false))
    composeTestRule.mainClock.advanceTimeBy(1_000)
    badge(LIVE_A).assertDoesNotExist()
    composeTestRule.onNodeWithTag(PickerLive.TOGGLE_TEST_TAG).assertDoesNotExist()
  }

  private fun badge(media: Media) = composeTestRule.onNodeWithTag(PickerLive.badgeTestTag(media.uri.toString()), useUnmergedTree = true)

  private fun setContent(selectedMedia: List<Media>, sendOptions: MediaSelectState.SendOptions) {
    val detector: suspend (Media) -> TellomiMotionPhoto.VideoRange? = { media -> RANGES[media.uri] }
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalTellomiLiveDetector provides detector) {
          Box(modifier = Modifier.size(400.dp, 800.dp)) {
            MediaSelectScreen(
              state = MediaSelectState.Files(
                selectedMediaFolder = RECENTS,
                selectedMediaFolderItems = listOf(STILL, LIVE_A, LIVE_B),
                selectedMedia = selectedMedia,
                mediaFolders = listOf(RECENTS),
                sendOptions = sendOptions
              ),
              onEvent = { events += it }
            )
          }
        }
      }
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    private val RECENTS = MediaFolder(
      thumbnailUri = "content://recents".toUri(),
      title = "All media",
      itemCount = 3,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      folderType = MediaFolder.FolderType.NORMAL
    )

    private val STILL = photo("content://media/1")
    private val LIVE_A = photo("content://media/2")
    private val LIVE_B = photo("content://media/3")

    private val RANGES = mapOf(
      LIVE_A.uri to TellomiMotionPhoto.VideoRange(3_000_000, 2_000_000),
      LIVE_B.uri to TellomiMotionPhoto.VideoRange(2_000_000, 1_000_000)
    )

    private fun photo(uri: String) = Media(
      uri = uri.toUri(),
      contentType = "image/jpeg",
      date = 0,
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
