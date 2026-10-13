/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.about

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Rows.TextAndLabel
import org.signal.core.ui.compose.Rows.defaultPadding
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.signal.core.util.Util
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.jobs.UpdateManifestJob
import org.thoughtcrime.securesms.updaterequired.UpdateOffers
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Tellomi（tellomi/tellomi#1165，需求 `docs/product/specs/about-page.md` 3.1、3.2）：「关于 Tellomi」从「帮助」里拿出来，
 * 在设置页里与「帮助」平级。顶部图标 + 名称 + 版本（点按复制），下面四组：更新 · 联系我们 · 法律与合规 · 开源，页脚三行署名（#984）。
 * 只放 Tellomi 真有的东西：没有客服电话、没有推荐算法，就不放对应的行。
 */
class AboutSettingsFragment : ComposeFragment() {

  @Composable
  override fun FragmentContent() {
    val navController: NavController = remember { findNavController() }
    val context = LocalContext.current

    val callbacks = remember {
      object : AboutSettingsCallbacks {
        override fun onNavigationClick() {
          navController.popBackStack()
        }

        override fun onCopy(text: String) {
          Util.copyToClipboard(context, text)
          Toast.makeText(context, R.string.HelpSettingsFragment__copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        override fun onCheckForUpdates() {
          if (BuildConfig.MANAGES_APP_UPDATES) {
            // 与「App 更新」页的「检查更新」同一个动作；有新版本时 ApkUpdateJob 自己会提示。顺带重拉一次更新清单（#1491）。
            AppDependencies.jobManager.add(ApkUpdateJob())
            AppDependencies.jobManager.add(UpdateManifestJob())
            Toast.makeText(context, R.string.AboutSettings__tellomi_checking_for_updates, Toast.LENGTH_LONG).show()
          } else {
            // 应用商店装的包由商店更新，这里去下载页
            CommunicationActions.openBrowserLink(context, context.getString(R.string.install_url))
          }
        }

        override fun onOpenUrl(url: String) {
          // 需求写的是「App 内浏览器打开」；App 内浏览器是 #1107（M4），接上之前先用系统浏览器，与「帮助」里的链接相同
          CommunicationActions.openBrowserLink(context, url)
        }

        override fun onWriteEmail(address: String) {
          CommunicationActions.openEmail(context, address, null, null)
        }

        override fun onLicensesClick() {
          navController.safeNavigate(R.id.action_aboutSettingsFragment_to_licenseFragment)
        }

        override fun onUpdateNow() {
          UpdateOffers.startUpdate(requireActivity())
        }
      }
    }

    // Tellomi（tellomi/tellomi#1491，需求 3.4「可选更新」）：有新版本时「更新」一组里多一行「有新版本 x」；进了这一页，设置入口的小红点就消失
    val offer = remember { UpdateOffers.current() }
    LaunchedEffect(offer) {
      UpdateOffers.markSeenInAbout()
    }

    AboutSettingsScreen(
      versionName = BuildConfig.VERSION_NAME,
      versionCode = BuildConfig.VERSION_CODE.toString(),
      callbacks = callbacks,
      newVersion = offer?.version
    )
  }
}

interface AboutSettingsCallbacks {
  fun onNavigationClick() = Unit
  fun onCopy(text: String) = Unit
  fun onCheckForUpdates() = Unit
  fun onOpenUrl(url: String) = Unit
  fun onWriteEmail(address: String) = Unit
  fun onLicensesClick() = Unit
  fun onUpdateNow() = Unit

  object Empty : AboutSettingsCallbacks
}

/** 「关于」里的地址。法律页用官网的规范地址（`tellomi.app/legal/…` 会 301 到 `www`），邮箱照 `docs/product/BRAND.md` 的邮箱表。 */
object TellomiAboutLinks {
  const val WEBSITE = "https://www.tellomi.app/"
  const val WEBSITE_LABEL = "www.tellomi.app"

  /**
   * 源代码的获取页（AGPL-3.0：分发二进制时要让用户拿得到对应源代码）。owner 2026-09-25：「关于」里不放 GitHub 地址，
   * 改成「许可证」页最上面一句说明（[tellomiSourceCodeNotice]），指向官网这一页（官网那页再列各仓库）。
   */
  const val SOURCE_CODE_PAGE = "https://www.tellomi.app/source/"

  const val SUPPORT_EMAIL = "support@tellomi.app"
  const val PRIVACY_EMAIL = "privacy@tellomi.app"
  const val ABUSE_EMAIL = "abuse@tellomi.app"

  data class LegalDocument(@StringRes val title: Int, val url: String)

  val LEGAL_DOCUMENTS = listOf(
    LegalDocument(R.string.AboutSettings__tellomi_terms_of_service, "https://www.tellomi.app/legal/terms/"),
    LegalDocument(R.string.AboutSettings__tellomi_privacy_policy, "https://www.tellomi.app/legal/privacy/"),
    LegalDocument(R.string.AboutSettings__tellomi_personal_information_collected, "https://www.tellomi.app/legal/pi-collection/"),
    LegalDocument(R.string.AboutSettings__tellomi_third_party_sharing, "https://www.tellomi.app/legal/third-party/"),
    LegalDocument(R.string.AboutSettings__tellomi_system_permissions, "https://www.tellomi.app/legal/permissions/"),
    LegalDocument(R.string.AboutSettings__tellomi_complaints_and_reports, "https://www.tellomi.app/legal/complaints/")
  )
}

object AboutSettingsTestTags {
  const val LIST = "about_settings_list"
  const val VERSION = "about_settings_version"
}

@Composable
fun AboutSettingsScreen(
  versionName: String,
  versionCode: String,
  callbacks: AboutSettingsCallbacks,
  newVersion: String? = null
) {
  val fullVersion = "$versionName ($versionCode)"
  val openIcon = ImageVector.vectorResource(R.drawable.symbol_open_20)

  Scaffolds.Settings(
    title = stringResource(R.string.AboutSettings__tellomi_about_tellomi),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    navigationContentDescription = stringResource(id = R.string.Material3SearchToolbar__close)
  ) { contentPadding ->
    LazyColumn(
      modifier = Modifier
        .padding(contentPadding)
        .testTag(AboutSettingsTestTags.LIST)
    ) {
      item {
        Header(
          version = stringResource(R.string.AboutSettings__tellomi_version, versionName, versionCode),
          onVersionClick = { callbacks.onCopy(fullVersion) }
        )
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.AboutSettings__tellomi_section_updates))
      }

      if (newVersion != null) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.TellomiUpdateAvailable__about_new_version, newVersion),
            label = stringResource(R.string.TellomiUpdateAvailable__about_tap_to_update),
            onClick = callbacks::onUpdateNow
          )
        }
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.AboutSettings__tellomi_check_for_updates),
          onClick = callbacks::onCheckForUpdates
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.AboutSettings__tellomi_section_contact))
      }

      item {
        Rows.LinkRow(
          text = stringResource(R.string.AboutSettings__tellomi_official_website),
          label = TellomiAboutLinks.WEBSITE_LABEL,
          icon = openIcon,
          onClick = { callbacks.onOpenUrl(TellomiAboutLinks.WEBSITE) }
        )
      }

      item {
        EmailRow(R.string.AboutSettings__tellomi_contact_support, TellomiAboutLinks.SUPPORT_EMAIL, callbacks)
      }

      item {
        EmailRow(R.string.AboutSettings__tellomi_privacy_and_personal_data, TellomiAboutLinks.PRIVACY_EMAIL, callbacks)
      }

      item {
        EmailRow(R.string.AboutSettings__tellomi_report_abuse, TellomiAboutLinks.ABUSE_EMAIL, callbacks)
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.AboutSettings__tellomi_section_legal))
      }

      TellomiAboutLinks.LEGAL_DOCUMENTS.forEach { document ->
        item {
          Rows.LinkRow(
            text = stringResource(document.title),
            icon = openIcon,
            onClick = { callbacks.onOpenUrl(document.url) }
          )
        }
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.AboutSettings__tellomi_section_open_source))
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.AboutSettings__tellomi_open_source_licenses),
          onClick = callbacks::onLicensesClick
        )
      }

      item {
        Footer()
      }
    }
  }
}

@Composable
private fun Header(version: String, onVersionClick: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 24.dp, bottom = 16.dp)
  ) {
    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(RoundedCornerShape(18.dp))
    ) {
      Image(painter = painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.size(72.dp))
      Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = stringResource(R.string.app_name),
      style = MaterialTheme.typography.titleLarge
    )

    Spacer(modifier = Modifier.height(4.dp))

    // 与「帮助」里原来的版本行一样可以复制，只是改成点按（需求 3.2：点按复制完整版本号）
    Text(
      text = version,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onVersionClick)
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .testTag(AboutSettingsTestTags.VERSION)
    )
  }
}

/** 邮箱行：点了复制（右侧写着「点击复制」），长按写邮件。 */
@Composable
private fun EmailRow(@StringRes title: Int, address: String, callbacks: AboutSettingsCallbacks) {
  Rows.TextRow(
    text = {
      TextAndLabel(
        text = stringResource(title),
        label = address
      )
      Text(
        text = stringResource(R.string.AboutSettings__tellomi_tap_to_copy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp)
      )
    },
    onClick = { callbacks.onCopy(address) },
    onLongClick = { callbacks.onWriteEmail(address) }
  )
}

@Composable
private fun Footer() {
  val context = LocalContext.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(defaultPadding()),
    verticalAlignment = Alignment.CenterVertically
  ) {
    TextAndLabel(
      label = StringBuilder().apply {
        // Tellomi（#984，owner 2026-09-23 定）：三行并列，从「帮助」原样搬过来，一字不改。
        //   1) 上游署名 —— AGPL 要求派生作品保留原作者的版权声明，不能换成我们自己；
        //   2) 我们对修改部分的署名：营业执照上的公司全称，不写品牌名（owner 2026-09-24，docs/legal/dev/SOURCE_COPYRIGHT.md；
        //      键名里的 tellomi 是历史名字，没改）；
        //   3) 许可证：正式名称 GNU AGPLv3（上游原文，和 LICENSE 第一行一致）。
        // iOS 的 ABOUT_SECTION_FOOTER_TELLOMI 要和这三行一字一句对齐。
        append(context.getString(R.string.HelpFragment__copyright_signal_messenger))
        append("\n")
        append(context.getString(R.string.HelpFragment__modifications_copyright_tellomi))
        append("\n")
        append(context.getString(R.string.HelpFragment__licenced_under_the_agplv3))
      }.toString()
    )
  }
}

@DayNightPreviews
@Composable
private fun AboutSettingsScreenPreview() {
  Previews.Preview {
    AboutSettingsScreen(
      versionName = "0.1.2",
      versionCode = "175101",
      callbacks = AboutSettingsCallbacks.Empty
    )
  }
}
