/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.AlbumEditTool
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.R

/**
 * Tellomi：相册查看器底部的快捷——三个键，次序裁剪 · 绘制 · 调整（对齐 iOS `TellomiPhotoPickerAlbumViewer`），
 * 「调整」和编辑页的调整键同一个（同图标、同读屏文字）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumEditShortcutsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<MediaEditScreenEvents>()

  @Test
  fun `Given a still picture, then the shortcuts are crop, draw and adjust in that order`() {
    setContent()

    val buttons = composeTestRule.onAllNodes(hasClickAction())
    buttons.assertCountEquals(3)
    for (index in 0 until 3) {
      buttons[index].performClick()
    }

    assertThat(events).containsExactly(
      MediaEditScreenEvents.EditFromAlbum(PHOTO, AlbumEditTool.CROP),
      MediaEditScreenEvents.EditFromAlbum(PHOTO, AlbumEditTool.DRAW),
      MediaEditScreenEvents.EditFromAlbum(PHOTO, AlbumEditTool.ADJUST)
    )
  }

  @Test
  fun `Given a still picture, when adjust is tapped, then the picture opens in the adjust tool`() {
    setContent()

    val adjust = composeTestRule.onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Application>().getString(R.string.ImageEditor_tellomi_adjust))
    adjust.assertIsDisplayed()
    adjust.performClick()

    assertThat(events).containsExactly(MediaEditScreenEvents.EditFromAlbum(PHOTO, AlbumEditTool.ADJUST))
  }

  private fun setContent() {
    composeTestRule.setContent {
      SignalTheme {
        AlbumEditShortcuts(media = PHOTO, onEvent = { events += it })
      }
    }
  }

  private companion object {
    private val PHOTO = Media(
      uri = "content://media/1".toUri(),
      contentType = "image/jpeg",
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
