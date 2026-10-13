/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedClosePerson
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedRelation
import java.io.File

/** 公开主页：头部、关注按钮、私密 / 跨区 / 屏蔽的提示。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedProfilePageScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var follows = 0
  private var unfollows = 0
  private var blocked: Boolean? = null

  private val author = FeedAuthor(profileId = "p1", displayName = "Shanfeng", handle = "shanfeng")
  private val profile = FeedProfilePage(
    id = "p1",
    handle = "shanfeng",
    displayName = "Shanfeng",
    bio = "Likes clouds and long walks.",
    shownInterests = listOf("Outdoors"),
    ipRegionLabel = "Sichuan",
    followers = 128,
    following = 40,
    me = FeedRelation(followedBy = true),
    closePeople = listOf(FeedClosePerson(id = "p2", displayName = "Xiaoman"))
  )

  private fun show(state: FeedProfilePageState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedProfilePageScreen(
          state = state,
          onBack = {},
          onRetry = {},
          onFollow = { follows++ },
          onUnfollow = { unfollows++ },
          onSetBlocked = { blocked = it },
          onReport = {},
          onEditProfile = {},
          onLoadMorePosts = {},
          onLikeClick = {},
          onOpenPost = {},
          onReportPost = {},
          onNoticeShown = {},
          onProfileRequired = {}
        )
      }
    }
  }

  @Test
  fun `header shows the id, region, counts and a follow back button`() {
    val posts = listOf(FeedCard(id = "a", kind = FeedCard.KIND_POST, author = author, text = "The clouds were low today."))
    show(FeedProfilePageState(profile = profile, loading = false, posts = posts, postsLoadedOnce = true, showIpRegion = true))

    composeTestRule.onNodeWithText("@shanfeng · IP: Sichuan").assertExists()
    composeTestRule.onNodeWithText("128 followers", substring = true).assertExists()
    composeTestRule.onNodeWithText("Follows you", substring = true).assertExists()
    composeTestRule.onNodeWithText("Close people: Xiaoman").assertExists()
    composeTestRule.onNodeWithText("The clouds were low today.").assertExists()
    screenshot("profile_page.png")

    composeTestRule.onNodeWithText("Follow back").performClick()
    assertThat(follows).isEqualTo(1)
  }

  @Test
  fun `unfollowing asks first`() {
    show(FeedProfilePageState(profile = profile.copy(me = FeedRelation(follow = FeedRelation.FOLLOW_APPROVED)), loading = false, postsLoadedOnce = true))

    composeTestRule.onNodeWithTag(FeedProfilePageTestTags.FOLLOW).performClick()
    assertThat(unfollows).isEqualTo(0)
    composeTestRule.onNodeWithText("Unfollow").performClick()
    assertThat(unfollows).isEqualTo(1)
  }

  @Test
  fun `a private account explains why there are no posts`() {
    show(FeedProfilePageState(profile = profile.copy(isPrivate = true, me = FeedRelation(follow = FeedRelation.FOLLOW_PENDING)), loading = false))

    composeTestRule.onNodeWithText("Requested").assertExists()
    composeTestRule.onNodeWithText("This account is private", substring = true).assertExists()
    screenshot("profile_page_private.png")
  }

  @Test
  fun `another region's author says the content is unavailable`() {
    show(FeedProfilePageState(profile = profile.copy(regionUnavailable = true), loading = false))

    composeTestRule.onNodeWithText("This content isn't available in your region").assertExists()
  }

  @Test
  fun `a blocked account can be unblocked from the page`() {
    show(FeedProfilePageState(profile = profile.copy(me = FeedRelation(blocked = true)), loading = false))

    composeTestRule.onNodeWithTag(FeedProfilePageTestTags.FOLLOW).assertDoesNotExist()
    composeTestRule.onNodeWithText("Unblock").performClick()
    assertThat(blocked).isEqualTo(false)
  }

  @Test
  fun `blocking from the menu asks first`() {
    show(FeedProfilePageState(profile = profile, loading = false, postsLoadedOnce = true))

    composeTestRule.onNodeWithTag(FeedProfilePageTestTags.MORE).performClick()
    composeTestRule.onNodeWithText("Block").performClick()
    composeTestRule.onNodeWithText("Block Shanfeng?").assertExists()
    composeTestRule.onNodeWithText("Block").performClick()
    assertThat(blocked == true).isTrue()
  }

  private fun screenshot(name: String) {
    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
