/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.about

import android.app.Application
import android.content.res.Configuration
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.help.LicenseScreen
import org.thoughtcrime.securesms.components.settings.app.help.tellomiSourceCodeNotice
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1165）：「关于 Tellomi」页的每一行都做它该做的事，地址一个字都不能错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AboutSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private class RecordingCallbacks : AboutSettingsCallbacks {
    val events = mutableListOf<String>()
    override fun onCopy(text: String) { events += "copy:$text" }
    override fun onCheckForUpdates() { events += "update" }
    override fun onOpenUrl(url: String) { events += "open:$url" }
    override fun onWriteEmail(address: String) { events += "email:$address" }
    override fun onLicensesClick() { events += "licenses" }
    override fun onUpdateNow() { events += "update-now" }
  }

  private fun setContent(newVersion: String? = null): RecordingCallbacks {
    val callbacks = RecordingCallbacks()
    composeTestRule.setContent {
      SignalTheme {
        AboutSettingsScreen(versionName = "0.1.2", versionCode = "175101", callbacks = callbacks, newVersion = newVersion)
      }
    }
    return callbacks
  }

  private fun clickRow(text: String) {
    composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST).performScrollToNode(hasText(text))
    composeTestRule.onNodeWithText(text).performClick()
  }

  @Test
  fun `tapping the version copies the full version`() {
    val callbacks = setContent()

    composeTestRule.onNodeWithText("Version 0.1.2 (build 175101)").performClick()

    assertThat(callbacks.events).containsExactly("copy:0.1.2 (175101)")
  }

  @Test
  fun `email rows copy on tap and write an email on long press`() {
    val callbacks = setContent()

    clickRow("support@tellomi.app")
    clickRow("privacy@tellomi.app")
    clickRow("abuse@tellomi.app")
    composeTestRule.onNodeWithText("support@tellomi.app").performTouchInput { longClick() }

    assertThat(callbacks.events).containsExactly(
      "copy:support@tellomi.app",
      "copy:privacy@tellomi.app",
      "copy:abuse@tellomi.app",
      "email:support@tellomi.app"
    )
  }

  @Test
  fun `the six legal documents open their official pages`() {
    val callbacks = setContent()

    clickRow("Terms of Service")
    clickRow("Privacy Policy")
    clickRow("Personal information we collect")
    clickRow("Third-party sharing and SDKs")
    clickRow("System permissions we use")
    clickRow("Complaints and reporting rules")

    assertThat(callbacks.events).containsExactly(
      "open:https://www.tellomi.app/legal/terms/",
      "open:https://www.tellomi.app/legal/privacy/",
      "open:https://www.tellomi.app/legal/pi-collection/",
      "open:https://www.tellomi.app/legal/third-party/",
      "open:https://www.tellomi.app/legal/permissions/",
      "open:https://www.tellomi.app/legal/complaints/"
    )
  }

  @Test
  fun `website, updates and licenses`() {
    val callbacks = setContent()

    clickRow("Check for updates")
    clickRow("Official website")
    clickRow("Open source licenses")

    assertThat(callbacks.events).containsExactly(
      "update",
      "open:https://www.tellomi.app/",
      "licenses"
    )
  }

  /** tellomi/tellomi#1491（需求 3.4「可选更新」）：有新版本时「更新」一组多一行「有新版本 x」，点了就更新。 */
  @Test
  fun `a newer version adds a row that starts the update`() {
    val callbacks = setContent(newVersion = "0.1.5")

    clickRow("New version 0.1.5")

    assertThat(callbacks.events).containsExactly("update-now")
  }

  @Test
  fun `without a newer version there is no such row`() {
    setContent()

    composeTestRule.onNodeWithText("Tap to update").assertDoesNotExist()
  }

  /** owner 2026-09-25：「源代码 github.com/tellomi」那一行去掉了，源代码的说明挪到「许可证」页最上面（见下面两条）。 */
  @Test
  fun `the source code row is gone`() {
    setContent()
    val list = composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST)

    val scrolledToSourceCode = runCatching { list.performScrollToNode(hasText("Source code")) }
    val scrolledToGitHub = runCatching { list.performScrollToNode(hasText("github", substring = true, ignoreCase = true)) }
    // 对照：同一个列表里真有的行滚得到（证明上面两次是「找不到」，不是「滚不动」）
    list.performScrollToNode(hasText("Open source licenses"))

    assertThat(scrolledToSourceCode.isFailure).isTrue()
    assertThat(scrolledToGitHub.isFailure).isTrue()
  }

  /**
   * 「许可证」页最上面那一句：四种语言都带官网地址和许可证名，都不出现 GitHub（后面空一行再接第三方许可）；
   * 也不写「基于 Signal」（owner 2026-09-26，与官网 /source 同口径，超级仓库 docs/product/specs/about-page.md）。
   */
  @Test
  fun `the licenses page starts with where to get the source code in every language`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertThat(TellomiAboutLinks.SOURCE_CODE_PAGE).isEqualTo("https://www.tellomi.app/source/")
    for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale("zh", "HK"), Locale.TRADITIONAL_CHINESE)) {
      val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
      val lines = tellomiSourceCodeNotice(localized)

      assertThat(lines, locale.toString()).containsExactly(localized.getString(R.string.AboutSettings__tellomi_source_code_notice), "")
      assertThat(lines[0], locale.toString()).contains("www.tellomi.app/source")
      assertThat(lines[0], locale.toString()).contains("GNU AGPLv3")
      assertThat(lines[0].lowercase(), locale.toString()).doesNotContain("github")
      assertThat(lines[0], locale.toString()).doesNotContain("Signal")
    }
  }

  /** 这一句在许可证页上是第一行（许可证页就是把这几行依次排下来） */
  @Test
  fun `the notice is the first line on the licenses page`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val notice = context.getString(R.string.AboutSettings__tellomi_source_code_notice)
    composeTestRule.setContent {
      SignalTheme {
        LicenseScreen(licenseTextLines = tellomiSourceCodeNotice(context) + listOf("Apache License 2.0"))
      }
    }

    composeTestRule.onNodeWithText(notice).assertExists()
    composeTestRule.onNodeWithText("Apache License 2.0").assertExists()
    val noticeTop = composeTestRule.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot.top
    val licenseTop = composeTestRule.onNodeWithText("Apache License 2.0").fetchSemanticsNode().boundsInRoot.top
    assertThat(noticeTop < licenseTop).isTrue()
  }

  @Test
  fun `footer keeps the three attribution lines from the help page`() {
    setContent()

    composeTestRule.onNodeWithTag(AboutSettingsTestTags.LIST).performScrollToNode(hasText("Licensed under the", substring = true))
    composeTestRule.onNodeWithText("Copyright Signal Messenger\nModifications Copyright 重庆半格智能科技有限公司\nLicensed under the GNU AGPLv3").assertExists()
  }

  /** owner 2026-09-24：四种语言都写公司全称（一字不差，繁体也不转字）和 GNU AGPLv3，不再出现品牌名。 */
  @Test
  fun `footer names the company and the full license in every language`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale("zh", "HK"), Locale.TRADITIONAL_CHINESE)) {
      val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
      val footer = listOf(
        R.string.HelpFragment__copyright_signal_messenger,
        R.string.HelpFragment__modifications_copyright_tellomi,
        R.string.HelpFragment__licenced_under_the_agplv3
      ).joinToString("\n") { localized.getString(it) }

      assertThat(footer, locale.toString()).contains("Signal Messenger")
      assertThat(footer, locale.toString()).contains("重庆半格智能科技有限公司")
      assertThat(footer, locale.toString()).contains("GNU AGPLv3")
      assertThat(footer, locale.toString()).doesNotContain("Tellomi")
    }
  }
}
