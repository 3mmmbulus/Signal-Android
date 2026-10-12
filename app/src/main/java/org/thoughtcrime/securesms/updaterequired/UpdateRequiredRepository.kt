/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import org.signal.core.util.Util
import org.signal.core.util.getDownloadManager
import org.signal.core.util.logging.Log
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateInstaller
import org.thoughtcrime.securesms.apkupdate.ApkUpdateNotifications
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobs.ApkUpdateJob
import org.thoughtcrime.securesms.jobs.UpdateManifestJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」的判定入口。
 *
 * 服务端对本版本回 499（[org.thoughtcrime.securesms.net.RemoteDeprecationDetectorInterceptor]、
 * [org.thoughtcrime.securesms.net.SignalWebSocketHealthMonitor]）或构建过期
 * （[org.thoughtcrime.securesms.jobs.BuildExpirationConfirmationJob]）都会把 `misc.isClientDeprecated` 置真；
 * 装上新版本时 [org.thoughtcrime.securesms.migrations.ApplicationMigrations] 会清掉它。
 */
object UpdateRequired {

  /** 这个版本已经不能收发（上游的「客户端已弃用」）：服务端判定，或本机构建到期。App 此时是只读的。 */
  @JvmStatic
  fun isRequired(): Boolean = SignalStore.misc.isClientDeprecated

  /**
   * 要不要自动盖上阻断页（路由、回到前台、全屏 megaphone 三处都看这里）。owner 2026-09-24 定的三条规则：
   * - 只有服务端判定的（499 或远程配置宣布到期）才盖，服务端随时能撤；本机构建到期只降成只读 + 横幅，不盖。
   * - 用户在阻断页选了「只看聊天记录」之后，这个版本就不再自动盖（上游「不要更新」之后全屏页也不再出现）；
   *   只读横幅上的「立即更新」随时能回到更新页。换了版本（versionCode 变了）这个选择就作废。
   */
  @JvmStatic
  @JvmOverloads
  fun shouldBlock(
    isRequired: Boolean = isRequired(),
    reason: UpdateRequiredState.Reason = reason(),
    readOnlyChosenVersionCode: Int = SignalStore.apkUpdate.readOnlyChosenVersionCode,
    currentVersionCode: Int = BuildConfig.VERSION_CODE
  ): Boolean {
    return isRequired && reason == UpdateRequiredState.Reason.SERVER_REJECTED && readOnlyChosenVersionCode != currentVersionCode
  }

  /** 用户在阻断页选了「暂不更新，只看聊天记录」。 */
  fun chooseReadOnly() {
    SignalStore.apkUpdate.readOnlyChosenVersionCode = BuildConfig.VERSION_CODE
  }

  /**
   * 置真之后 [Util.getTimeUntilBuildExpiry] 恒为 0，分不出原因，所以这里直接比构建时间：
   * 过了构建有效期 = 版本过旧；否则是服务端拒绝（499 或远程配置宣布到期）。
   */
  fun reason(
    serverTime: Long = SignalStore.misc.estimatedServerTime,
    buildTimestamp: Long = BuildConfig.BUILD_TIMESTAMP
  ): UpdateRequiredState.Reason {
    return if (serverTime - buildTimestamp >= Util.getBuildLifespan()) {
      UpdateRequiredState.Reason.BUILD_EXPIRED
    } else {
      UpdateRequiredState.Reason.SERVER_REJECTED
    }
  }

  /** 本机构建过了有效期（Signal 自带的构建过期兜底）。这种只读不归更新清单管（[UpdateManifests.reconcile]）。 */
  @JvmStatic
  fun isBuildExpired(): Boolean = reason() == UpdateRequiredState.Reason.BUILD_EXPIRED

  /**
   * 阻断页上显示的原因（tellomi/tellomi#1491，需求 3.4「按 requiredReason」）：服务端判定的那一类，再按更新清单写的原因细分——
   * 安全、其它各一句；协议和服务端 499 是同一句「此版本已无法与服务器正常通信」。
   *
   * @param manifestReason 更新清单此刻判「必须」时写的原因；不是清单要求的（只是 499）为 null
   */
  @JvmStatic
  @JvmOverloads
  fun displayReason(
    base: UpdateRequiredState.Reason = reason(),
    manifestReason: UpdateManifest.RequiredReason? = UpdateManifests.requiredReason()
  ): UpdateRequiredState.Reason {
    if (base == UpdateRequiredState.Reason.BUILD_EXPIRED) {
      return base
    }
    return when (manifestReason) {
      UpdateManifest.RequiredReason.SECURITY -> UpdateRequiredState.Reason.SECURITY
      UpdateManifest.RequiredReason.OTHER -> UpdateRequiredState.Reason.OTHER
      UpdateManifest.RequiredReason.PROTOCOL, null -> UpdateRequiredState.Reason.SERVER_REJECTED
    }
  }
}

/** DownloadManager 里一条下载的快照。 */
data class UpdateDownloadSnapshot(
  val downloadId: Long,
  val status: Status,
  val bytesSoFar: Long,
  /** 服务端没给长度时为 -1。 */
  val totalBytes: Long,
  /** 这条下载是否允许用流量（阻断页发起的是 true，上游后台检查排的是 false）。 */
  val allowsMetered: Boolean,
  /**
   * 在等网络 / 等 Wi-Fi（PENDING，或 PAUSED 且原因是 QUEUED_FOR_WIFI / WAITING_FOR_NETWORK）。
   * 服务器出错后的 WAITING_TO_RETRY 不算：那种不该说「原本只在连上 Wi-Fi 时下载」（taishi 审查 b14 包 7 不阻塞 3）。
   */
  val waitingForNetwork: Boolean = false
) {
  enum class Status { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED }
}

/** 阻断页用到的系统能力，抽成接口方便单测。 */
interface UpdateRequiredRepository {
  val managesAppUpdates: Boolean

  fun reason(): UpdateRequiredState.Reason

  /** Android 8 起安装 APK 要「安装未知应用」这项特殊权限。 */
  fun canRequestPackageInstalls(): Boolean

  fun isOnline(): Boolean

  /**
   * 同步跑一次必须档的清单检查（允许用流量下载）。检查失败或超时返回 false。
   * [restartStuckDownload]：用户在「重试」上明确点了重来，已经允许流量、却卡着不动的那条下载也删掉重排（taishi 审查 b14 包 7 不阻塞 2）。
   */
  @WorkerThread
  fun runRequiredUpdateCheck(restartStuckDownload: Boolean = false): Boolean

  /** 最近一次清单检查看到的、比当前安装更新的版本号；没有则为 null。 */
  fun availableVersionName(): String?

  @WorkerThread
  fun currentDownload(): UpdateDownloadSnapshot?

  @WorkerThread
  fun install(downloadId: Long)

  /** 用户选了「暂不更新，只看聊天记录」：这个版本不再自动盖阻断页（[UpdateRequired.shouldBlock]）。 */
  fun chooseReadOnly()

  /** 这一页现在是不是自动盖上的阻断页（见 [UpdateRequired.shouldBlock]）；不是的话，出口不用再确认。 */
  fun isBlocking(): Boolean

  /** 更新清单里给这一页用的几样（tellomi/tellomi#1491）；没有清单时为 null。 */
  fun manifestInfo(): UpdateManifestInfo? = null

  /**
   * 同步拉一次更新清单：阻断页每次回到前台都拉，撤销才能尽快生效（需求 3.4「撤销」、判据 7）。拉不到就照缓存。
   *
   * @return 拉完以后是否还要更新（[UpdateRequired.isRequired]）
   */
  @WorkerThread
  fun refreshRequiredState(): Boolean = true
}

/** 更新清单里给阻断页用的几样（tellomi/tellomi#1491）。 */
data class UpdateManifestInfo(
  /** 比当前安装更新的最新版版本号；清单里的最新版不比现在新时为 null。 */
  val newerVersionName: String?,
  /** 最新版安装包的大小（字节）；清单没写时为 null。 */
  val sizeBytes: Long?,
  /** 「了解更新内容」的网页。 */
  val notesUrl: String?
)

class DefaultUpdateRequiredRepository(private val context: Context) : UpdateRequiredRepository {

  companion object {
    private val TAG = Log.tag(DefaultUpdateRequiredRepository::class)

    /** 断网时清单检查会一直等网络；等这么久还没结果就让用户看到「重试」。 */
    private val CHECK_TIMEOUT = 30.seconds
  }

  override val managesAppUpdates: Boolean = BuildConfig.MANAGES_APP_UPDATES

  override fun reason(): UpdateRequiredState.Reason = UpdateRequired.displayReason()

  override fun manifestInfo(): UpdateManifestInfo? {
    if (!UpdateManifests.isEnabled) {
      return null
    }
    val manifest = UpdateManifests.current() ?: return null
    val newer = manifest.latest.build > BuildConfig.VERSION_CODE
    return UpdateManifestInfo(
      newerVersionName = manifest.latest.version.takeIf { newer },
      sizeBytes = manifest.latest.size.takeIf { newer },
      notesUrl = manifest.notesUrl
    )
  }

  override fun refreshRequiredState(): Boolean {
    if (UpdateManifests.isEnabled) {
      val result = AppDependencies.jobManager.runSynchronously(UpdateManifestJob(), CHECK_TIMEOUT.inWholeMilliseconds)
      Log.i(TAG, "Update manifest refresh finished: ${result.orElse(null)}")
    }
    return UpdateRequired.isRequired()
  }

  override fun canRequestPackageInstalls(): Boolean {
    return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
  }

  override fun isOnline(): Boolean = NetworkConstraint.isMet(context)

  override fun runRequiredUpdateCheck(restartStuckDownload: Boolean): Boolean {
    val result = AppDependencies.jobManager.runSynchronously(ApkUpdateJob(allowMeteredNetwork = true, restartStuckDownload = restartStuckDownload), CHECK_TIMEOUT.inWholeMilliseconds)
    Log.i(TAG, "Required update check finished: ${result.orElse(null)}")
    return result.orElse(null) == JobTracker.JobState.SUCCESS
  }

  override fun availableVersionName(): String? = SignalStore.apkUpdate.availableUpdateVersionName

  override fun currentDownload(): UpdateDownloadSnapshot? {
    val downloadId = SignalStore.apkUpdate.downloadId
    if (downloadId < 0) {
      return null
    }

    val allowsMetered = SignalStore.apkUpdate.downloadAllowsMetered

    // 部分 ROM 可以停用「下载管理器」，这时 query 会抛异常；阻断页不能因此一打开就崩，按下载失败处理
    // （给「重试」和「去官网下载」，taishi 审查 b14 不阻塞 7）。
    return runCatching {
      context.getDownloadManager().query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
        if (!cursor.moveToFirst()) {
          return@runCatching null
        }

        val status = when (cursor.requireInt(DownloadManager.COLUMN_STATUS)) {
          DownloadManager.STATUS_PENDING -> UpdateDownloadSnapshot.Status.PENDING
          DownloadManager.STATUS_RUNNING -> UpdateDownloadSnapshot.Status.RUNNING
          DownloadManager.STATUS_PAUSED -> UpdateDownloadSnapshot.Status.PAUSED
          DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadSnapshot.Status.SUCCESSFUL
          else -> UpdateDownloadSnapshot.Status.FAILED
        }

        val waitingForNetwork = when (status) {
          UpdateDownloadSnapshot.Status.PENDING -> true
          UpdateDownloadSnapshot.Status.PAUSED -> cursor.requireInt(DownloadManager.COLUMN_REASON).let { reason ->
            reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
          }
          else -> false
        }

        UpdateDownloadSnapshot(
          downloadId = downloadId,
          status = status,
          bytesSoFar = cursor.requireLong(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
          totalBytes = cursor.requireLong(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
          allowsMetered = allowsMetered,
          waitingForNetwork = waitingForNetwork
        )
      }
    }.getOrElse { e ->
      Log.w(TAG, "Couldn't query the download manager.", e)
      UpdateDownloadSnapshot(downloadId, UpdateDownloadSnapshot.Status.FAILED, bytesSoFar = 0, totalBytes = -1, allowsMetered = allowsMetered)
    }
  }

  override fun install(downloadId: Long) {
    // 下载完成时后台已经发过一条「点此安装」通知；这里由用户在阻断页上直接装，不再需要它。
    ApkUpdateNotifications.dismissInstallPrompt(context)
    ApkUpdateInstaller.installOrPromptForInstall(context, downloadId, userInitiated = true)
  }

  override fun isBlocking(): Boolean = UpdateRequired.shouldBlock()

  override fun chooseReadOnly() {
    UpdateRequired.chooseReadOnly()
  }
}
