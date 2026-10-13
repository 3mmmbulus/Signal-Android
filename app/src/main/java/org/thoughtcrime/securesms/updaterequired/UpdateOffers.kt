/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.PlayStoreUtil
import kotlin.time.Duration.Companion.days

/**
 * Tellomi（tellomi/tellomi#1491 第二刀）：推荐档、可选档在哪里露面（需求 app-update-and-version-policy.md §3.4、判据 3 / 4）。
 *
 * - 推荐：进首页弹一次底部卡片（同一版本只弹一次）+ 会话列表顶部一条可关的横幅（关掉后 3 天再出现）；
 * - 可选（推荐档也算）：不弹；「关于」里显示「有新版本」，设置入口一个小红点，进一次「关于」就消失；
 * - 只读时（必须档、服务端 499、构建到期）都不出：那时说话的是阻断页和只读横幅。
 */
object UpdateOffers {

  private val TAG = Log.tag(UpdateOffers::class)

  /** 横幅关掉以后这么久不再出现。 */
  val BANNER_SNOOZE = 3.days

  /** 清单里比这台新的最新版，以及它属于哪一档（只会是可选或推荐）。 */
  data class Offer(
    val tier: UpdateManifest.Tier,
    val version: String,
    val build: Long,
    val sizeBytes: Long?,
    val notesUrl: String?
  )

  /** 只有官网版有清单；没有新版本、或者处在只读时为 null。 */
  @JvmStatic
  fun current(): Offer? {
    if (!UpdateManifests.isEnabled) {
      return null
    }
    return offer(UpdateManifests.current(), BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis(), SignalStore.misc.isClientDeprecated)
  }

  @VisibleForTesting
  fun offer(manifest: UpdateManifest?, build: Long, nowMillis: Long, readOnly: Boolean): Offer? {
    if (manifest == null || readOnly) {
      return null
    }
    val tier = manifest.tierFor(build, nowMillis)
    if (tier != UpdateManifest.Tier.OPTIONAL && tier != UpdateManifest.Tier.RECOMMENDED) {
      return null
    }
    return Offer(tier = tier, version = manifest.latest.version, build = manifest.latest.build, sizeBytes = manifest.latest.size, notesUrl = manifest.notesUrl)
  }

  /** 推荐档的首页卡片：同一个最新版只弹一次。 */
  @JvmStatic
  fun shouldShowRecommendedSheet(offer: Offer?, shownForBuild: Long): Boolean {
    return offer?.tier == UpdateManifest.Tier.RECOMMENDED && shownForBuild != offer.build
  }

  /** 推荐档的横幅：关掉以后 3 天内不出现（时钟往回拨过就照常出现）。 */
  @JvmStatic
  fun shouldShowBanner(offer: Offer?, dismissedAtMillis: Long, nowMillis: Long): Boolean {
    if (offer?.tier != UpdateManifest.Tier.RECOMMENDED) {
      return false
    }
    val sinceDismissed = nowMillis - dismissedAtMillis
    return dismissedAtMillis <= 0 || sinceDismissed < 0 || sinceDismissed >= BANNER_SNOOZE.inWholeMilliseconds
  }

  /** 设置入口的小红点：有新版本，而且还没在「关于」里看过这一版。 */
  @JvmStatic
  fun shouldShowSettingsDot(offer: Offer?, seenBuild: Long): Boolean {
    return offer != null && seenBuild != offer.build
  }

  /** 打开了「关于」：这一版看过了，小红点消失。 */
  @JvmStatic
  fun markSeenInAbout() {
    current()?.let { SignalStore.apkUpdate.aboutSeenUpdateBuild = it.build }
  }

  /**
   * 推荐卡片、横幅、「关于」里的「更新」：官网版下好了就直接调安装器，没下好就在后台下（允许用流量），下完由上游的通知提示安装；
   * 别的渠道去官网下载页。和阻断页不同，这里不盖页面、不拦人（需求 3.4「推荐更新」：「以后再说」后正常聊天不受影响）。
   */
  @JvmStatic
  fun startUpdate(activity: Activity) {
    if (!BuildConfig.MANAGES_APP_UPDATES) {
      PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(activity)
      return
    }

    val context = activity.applicationContext
    SignalExecutors.BOUNDED.execute {
      val repository = DefaultUpdateRequiredRepository(context)
      val download = repository.currentDownload()
      if (download?.status == UpdateDownloadSnapshot.Status.SUCCESSFUL && repository.availableVersionName() != null) {
        Log.i(TAG, "The update is already downloaded. Installing.")
        repository.install(download.downloadId)
      } else {
        Log.i(TAG, "Starting the update download.")
        AppDependencies.jobManager.add(ApkUpdateJob(allowMeteredNetwork = true))
        ThreadUtil.runOnMain { Toast.makeText(context, R.string.TellomiUpdateAvailable__downloading, Toast.LENGTH_LONG).show() }
      }
    }
  }

  /** 新版本已经下好、可以直接装了（横幅显示「已下载」）。 */
  @JvmStatic
  @WorkerThread
  fun isDownloaded(context: Context): Boolean {
    val repository = DefaultUpdateRequiredRepository(context)
    return repository.availableVersionName() != null && repository.currentDownload()?.status == UpdateDownloadSnapshot.Status.SUCCESSFUL
  }
}
