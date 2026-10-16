/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.R

/**
 * 两端对齐清单 P-4：附件 Sheet 里底栏的说明框就地输入（[TellomiInlineCaption]）——给了就画注入的说明框、表情键交给它切面板，
 * 不再发「点了弹对话框」；不给（别的入口）还是上游的样子。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiInlineCaptionRowTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val events = mutableListOf<MediaEditScreenEvents>()
  private val changes = mutableListOf<CharSequence?>()

  @Test
  fun `Given an inline caption, then the injected field is shown and typing goes to the flow`() {
    val caption = FakeInlineCaption()
    setContent(inlineCaption = caption, message = "Hello")

    composeTestRule.onNodeWithTag(FIELD_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    composeTestRule.onNodeWithTag(FIELD_TAG).performClick()

    assertEquals(listOf<CharSequence?>("typed"), changes)
    assertTrue("就地输入时点说明框不弹对话框", events.none { it is MediaEditScreenEvents.AddMessageClick })
  }

  @Test
  fun `Given an inline caption, when the emoji key is tapped, then the panel is toggled instead of opening the dialog`() {
    val caption = FakeInlineCaption()
    setContent(inlineCaption = caption)

    composeTestRule.onNodeWithContentDescription(context.getString(R.string.AddAMessageRow__open_emoji_keyboard)).performClick()

    assertEquals(1, caption.toggles)
    assertTrue(events.none { it is MediaEditScreenEvents.AddMessageClick })
    // 面板开着时，同一个键换成「显示键盘」
    composeTestRule.onNodeWithContentDescription(context.getString(R.string.AddAMessageRow__tellomi_show_keyboard)).assertIsDisplayed()
  }

  @Test
  fun `Given no inline caption, when the row is tapped, then the dialog is asked for as before`() {
    setContent(inlineCaption = null)

    composeTestRule.onNodeWithContentDescription(context.getString(R.string.AddAMessageRow__open_emoji_keyboard)).performClick()

    assertEquals(MediaEditScreenEvents.AddMessageClick(startWithEmojiKeyboard = true), events.single())
  }

  @Test
  fun `Given view once is on, then the inline field is not shown`() {
    setContent(inlineCaption = FakeInlineCaption(), viewOnceAvailable = true, viewOnce = true)

    composeTestRule.onNodeWithText(context.getString(R.string.AddAMessageRow__view_once_media)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(FIELD_TAG).assertDoesNotExist()
  }

  private fun setContent(inlineCaption: TellomiInlineCaption?, message: CharSequence? = null, viewOnceAvailable: Boolean = false, viewOnce: Boolean = false) {
    composeTestRule.setContent {
      SignalTheme {
        AddAMessageRow(
          message = message,
          onEvent = { events += it },
          onNextClick = {},
          viewOnceAvailable = viewOnceAvailable,
          viewOnce = viewOnce,
          inlineCaption = inlineCaption,
          onMessageChange = { changes += it }
        )
      }
    }
  }

  private class FakeInlineCaption : TellomiInlineCaption {
    var toggles = 0
    private var panel by mutableStateOf(false)

    override val isEditing: Boolean get() = panel
    override val isEmojiPanelVisible: Boolean get() = panel
    override val isMentionQueryActive: Boolean = false

    @Composable
    override fun Field(message: CharSequence?, hint: String, enabled: Boolean, onMessageChange: (CharSequence?) -> Unit, modifier: Modifier) {
      // 点一下当作打了字
      Text(
        text = message?.toString() ?: hint,
        modifier = modifier
          .testTag(FIELD_TAG)
          .clickable { onMessageChange("typed") }
      )
    }

    override fun onEmojiToggleClick() {
      toggles++
      panel = !panel
    }

    override fun stopEditing() {
      panel = false
    }

    @Composable
    override fun EmojiPanel(modifier: Modifier) = Unit

    @Composable
    override fun MentionsOverlay(modifier: Modifier) = Unit
  }

  companion object {
    private const val FIELD_TAG = "tellomi-inline-caption-field"
  }
}
