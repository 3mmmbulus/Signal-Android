/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.horizontalGutters
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.CommunicationActions

object TellomiUpdateRecommendedTestTags {
  const val UPDATE_NOW = "update_recommended_update_now"
  const val LATER = "update_recommended_later"
  const val LEARN_MORE = "update_recommended_learn_more"
}

/**
 * Tellomi（tellomi/tellomi#1491 第二刀，需求 app-update-and-version-policy.md §3.4「推荐更新」、判据 3）：
 * 进首页后弹一次的底部卡片——「发现新版本 x」+ 大小 +「了解更新内容」+「立即更新」/「以后再说」。同一个版本只弹一次，
 * 可以下滑 / 点外面关掉；关掉以后会话列表顶部还有一条可关的横幅（[org.thoughtcrime.securesms.banner.banners.UpdateAvailableBanner]）。
 *
 * Telegram reference（只看机制，一行没搬）：Android 官网版 `UpdateAppAlertDialog`（`TMessagesProj_AppStandalone/.../UpdateAppAlertDialog.java`）
 * 是同一种底部卡片，同一版本只弹一次（`LaunchActivity.checkAppUpdate` 里比 `pendingAppUpdate.version`）。
 */
class TellomiUpdateRecommendedBottomSheet : ComposeBottomSheetDialogFragment() {

  companion object {
    private val TAG = Log.tag(TellomiUpdateRecommendedBottomSheet::class.java)
    private const val FRAGMENT_TAG = "tellomi_update_recommended"

    /**
     * 首屏 `onResume` 里调用，和通知说明页同一个做法：推到下一帧再判，已经有别的底部弹层在就这次不弹，下次回到首屏再说。
     * 弹出去就记下这一版（不管点了什么），同一个版本不再弹。
     */
    @JvmStatic
    fun showIfNeeded(activity: FragmentActivity) {
      if (!UpdateOffers.shouldShowRecommendedSheet(UpdateOffers.current(), SignalStore.apkUpdate.recommendedPromptShownBuild)) {
        return
      }

      activity.window.decorView.post {
        val fragmentManager = activity.supportFragmentManager
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || fragmentManager.isStateSaved) {
          return@post
        }
        // 同一个 onResume 里通知说明页的 show() 是异步提交的，先让它落进 FragmentManager 再看
        runCatching { fragmentManager.executePendingTransactions() }
        if (fragmentManager.fragments.any { it is DialogFragment }) {
          Log.i(TAG, "Another sheet is showing; deferring the update card.")
          return@post
        }
        val offer = UpdateOffers.current()
        if (!UpdateOffers.shouldShowRecommendedSheet(offer, SignalStore.apkUpdate.recommendedPromptShownBuild)) {
          return@post
        }
        SignalStore.apkUpdate.recommendedPromptShownBuild = offer!!.build
        TellomiUpdateRecommendedBottomSheet().show(fragmentManager, FRAGMENT_TAG)
      }
    }
  }

  override val peekHeightPercentage: Float = 1f

  @Composable
  override fun SheetContent() {
    val offer = remember { UpdateOffers.current() }
    if (offer == null) {
      // 两次判断之间清单变了（比如刚撤了推荐）：不显示空卡片
      LaunchedEffect(Unit) { dismissAllowingStateLoss() }
      return
    }

    UpdateRecommendedContent(
      version = offer.version,
      sizeBytes = offer.sizeBytes,
      notesUrl = offer.notesUrl,
      onUpdateNow = {
        UpdateOffers.startUpdate(requireActivity())
        dismissAllowingStateLoss()
      },
      onLater = { dismissAllowingStateLoss() },
      onLearnMore = { url -> CommunicationActions.openBrowserLink(requireContext(), url) }
    )
  }
}

@Composable
fun UpdateRecommendedContent(
  version: String,
  sizeBytes: Long?,
  notesUrl: String?,
  onUpdateNow: () -> Unit = {},
  onLater: () -> Unit = {},
  onLearnMore: (String) -> Unit = {}
) {
  val context = LocalContext.current

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .horizontalGutters()
      .padding(top = 40.dp, bottom = 24.dp)
  ) {
    Icon(
      imageVector = ImageVector.vectorResource(R.drawable.ic_signal_logo_large),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(R.string.TellomiUpdateAvailable__sheet_title, version),
      style = MaterialTheme.typography.titleLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() }
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = if (sizeBytes != null) {
        stringResource(R.string.TellomiUpdateAvailable__sheet_body_with_size, Formatter.formatShortFileSize(context, sizeBytes))
      } else {
        stringResource(R.string.TellomiUpdateAvailable__sheet_body)
      },
      style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    )

    if (notesUrl != null) {
      TextButton(
        onClick = { onLearnMore(notesUrl) },
        modifier = Modifier.testTag(TellomiUpdateRecommendedTestTags.LEARN_MORE)
      ) {
        Text(text = stringResource(R.string.TellomiUpdateRequired__learn_more))
      }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Buttons.LargeTonal(
      onClick = onUpdateNow,
      modifier = Modifier
        .widthIn(min = 220.dp)
        .testTag(TellomiUpdateRecommendedTestTags.UPDATE_NOW)
    ) {
      Text(text = stringResource(R.string.TellomiUpdateRequired__update_now))
    }

    TextButton(
      onClick = onLater,
      modifier = Modifier
        .padding(top = 8.dp)
        .testTag(TellomiUpdateRecommendedTestTags.LATER)
    ) {
      Text(text = stringResource(R.string.TellomiUpdateAvailable__later))
    }
  }
}

@DayNightPreviews
@Composable
private fun UpdateRecommendedContentPreview() {
  Previews.Preview {
    UpdateRecommendedContent(version = "0.1.5", sizeBytes = 120_000_000, notesUrl = "https://www.tellomi.app/changelog/#android")
  }
}
