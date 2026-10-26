/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（tellomi/tellomi#1259 F-3 / F-4 / F-6 / F-7 / F-9）：转发网格的界面。
 *
 * 头像用 Compose 的检查模式画占位（真头像走 AvatarImageView + Glide，Robolectric 里不画）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiForwardGridScreenTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val saved = target(1, "Saved Messages", isSavedMessages = true)
  private val people = (2L..9L).map { target(it, "Friend$it") }

  private class Recorder : TellomiForwardGridCallbacks {
    val clicked = mutableListOf<Pair<RecipientId, Boolean>>()
    val queries = mutableListOf<String>()
    var shareClicks = 0
    var searchCancels = 0

    override fun onTargetClicked(target: TellomiForwardTarget, fromSearch: Boolean) {
      clicked += target.id to fromSearch
    }

    override fun onQueryChanged(query: String) {
      queries += query
    }

    override fun onSearchFocusChanged(focused: Boolean) = Unit

    override fun onSearchCancelled() {
      searchCancels++
    }

    override fun onShareClicked() {
      shareClicks++
    }
  }

  private fun target(id: Long, name: String, isSavedMessages: Boolean = false): TellomiForwardTarget {
    val recipient = mockk<Recipient>(relaxed = true)
    every { recipient.id } returns RecipientId.from(id)
    every { recipient.getShortDisplayName(any()) } returns name
    every { recipient.getDisplayName(any()) } returns name
    return TellomiForwardTarget(recipient, isSavedMessages)
  }

  private fun show(state: TellomiForwardGridViewModel.State, canShare: Boolean = true, recorder: Recorder = Recorder()): Recorder {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        SignalTheme(isDarkMode = false) {
          TellomiForwardGridContent(state = state, canShare = canShare, callbacks = recorder, bottomContentPadding = 0.dp)
        }
      }
    }
    return recorder
  }

  private fun gridState(selected: List<TellomiForwardTarget> = emptyList()): TellomiForwardGridViewModel.State {
    return TellomiForwardGridViewModel.State(
      isLoading = false,
      targets = listOf(saved) + people,
      selected = selected.map { it.id },
      selectedTargets = selected
    )
  }

  /** F-4：4 列——第 5 格换到第二行；第一格是我的收藏；点一格交给回调。 */
  @Test
  fun `four columns with saved messages first`() {
    val recorder = show(gridState())

    val first = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(saved)).getUnclippedBoundsInRoot()
    val fourth = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[2])).getUnclippedBoundsInRoot()
    val fifth = composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[3])).getUnclippedBoundsInRoot()
    assertThat(fourth.top).isEqualTo(first.top)
    assertThat(fifth.top).isGreaterThan(first.top)
    assertThat(fifth.left).isEqualTo(first.left)
    assertThat(TellomiForwardGridMetrics.COLUMNS).isEqualTo(4)

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[0])).performClick()
    assertThat(recorder.clicked).containsExactly(people[0].id to false)
  }

  /** F-3 / F-6：没选时副标题「选择聊天」；选中后副标题是名字（「、」/「, 」连接），选中的格子是选中态、有环有勾。 */
  @Test
  fun `selected cells are marked and the subtitle lists their names`() {
    show(gridState(selected = listOf(people[1], saved)))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SUBTITLE).assertTextEquals("Friend3, Saved Messages")
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[1])).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true))
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[0])).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, false))
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.CHECK)).isEqualTo(2)
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.RING)).isEqualTo(2)
  }

  @Test
  fun `the subtitle asks to select chats before any selection`() {
    show(gridState())

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SUBTITLE).assertTextEquals("Select chats")
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.TITLE).assertTextEquals("Forward to")
  }

  /** F-9：搜索态空查询是「最近联系人」一排，点一个按「从搜索里选」交给回调。 */
  @Test
  fun `empty search shows a row of recent contacts`() {
    val recorder = show(gridState().copy(isSearchActive = true, recentContacts = people.take(3)))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.RECENT_ROW).assertIsDisplayed()
    composeTestRule.onNodeWithText("Recent").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[2])).performClick()
    assertThat(recorder.clicked).containsExactly(people[2].id to true)
  }

  /** F-9：有查询按「我的收藏 · 聊天 · 联系人 · 群组」分组，自上而下就是这个顺序；搜不到的写法见下一条。 */
  @Test
  fun `search results are grouped in order`() {
    show(
      gridState().copy(
        isSearchActive = true,
        query = "Fri",
        searchResults = TellomiForwardSearchResults(savedMessages = saved, chats = listOf(people[0]), contacts = listOf(people[5]), groups = listOf(people[7]))
      )
    )
    val tops = listOf(
      composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(saved)),
      composeTestRule.onNodeWithText("Chats"),
      composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[0])),
      composeTestRule.onNodeWithText("Contacts"),
      composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[5])),
      composeTestRule.onNodeWithText("Groups"),
      composeTestRule.onNodeWithTag(TellomiForwardGridTags.cell(people[7]))
    ).map { it.assertIsDisplayed().getUnclippedBoundsInRoot().top }
    assertThat(tops.zipWithNext().all { (above, below) -> below > above }).isTrue()
  }

  @Test
  fun `search with no results says so`() {
    show(gridState().copy(isSearchActive = true, query = "zzzz"))

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.NO_RESULTS).assertTextEquals("No results for “zzzz”")
  }

  /** F-9：搜索态按返回是退出搜索（交给回调），不关面板；不在搜索态时网格不拦返回，返回照常关面板。 */
  @Test
  fun `back leaves search mode`() {
    val recorder = show(gridState().copy(isSearchActive = true, recentContacts = people.take(3)))

    composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

    assertThat(recorder.searchCancels).isEqualTo(1)
  }

  @Test
  fun `back is left to the sheet outside search mode`() {
    show(gridState())

    composeTestRule.runOnIdle {
      assertThat(composeTestRule.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }
  }

  /** F-3 / F-10：搜索框右边的「分享到其他 App」只在能分享时出现。 */
  @Test
  fun `share icon only when there is something to share`() {
    val recorder = show(gridState(), canShare = true)
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SHARE).performClick()
    assertThat(recorder.shareClicks).isEqualTo(1)
  }

  @Test
  fun `no share icon when nothing can be shared`() {
    show(gridState(), canShare = false)
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.SHARE)).isEqualTo(0)
  }

  /** F-7：底部「添加消息…」+ 带数量的「发送」。 */
  @Test
  fun `the bottom bar shows the count on send`() {
    var sends = 0
    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiForwardGridBottomBar(message = "", selectedCount = 3, isSendEnabled = true, onMessageChanged = {}, onSend = { sends++ })
      }
    }

    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SEND_COUNT, useUnmergedTree = true).assertTextEquals("3")
    composeTestRule.onNodeWithText("Add a message…").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.SEND).performClick()
    assertThat(sends).isEqualTo(1)
  }

  /** 审计 5.4：没有可隐藏说明时底栏不出现「隐藏说明文字」开关。 */
  @Test
  fun `hide caption switch is absent when nothing to hide`() {
    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiForwardGridBottomBar(
          message = "",
          selectedCount = 1,
          isSendEnabled = true,
          onMessageChanged = {},
          onSend = {},
          showHideCaptionToggle = false
        )
      }
    }
    assertThat(composeTestRule.onAllNodesWithTagCount(TellomiForwardGridTags.HIDE_CAPTION)).isEqualTo(0)
    composeTestRule.onNodeWithText("Add a message…").assertIsDisplayed()
  }

  /** 审计 5.4：有说明文字时开关出现且默认关；拨动交给回调。 */
  @Test
  fun `hide caption switch defaults off and reports changes`() {
    val reported = mutableListOf<Boolean>()
    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiForwardGridBottomBar(
          message = "",
          selectedCount = 2,
          isSendEnabled = true,
          onMessageChanged = {},
          onSend = {},
          showHideCaptionToggle = true,
          hideCaption = false,
          onHideCaptionChanged = { reported += it }
        )
      }
    }
    composeTestRule.onNodeWithText("Hide Caption").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiForwardGridTags.HIDE_CAPTION).assertIsDisplayed().performClick()
    assertThat(reported).containsExactly(true)
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int {
    return onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
  }
}
