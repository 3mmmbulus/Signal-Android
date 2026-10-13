/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig

class ApkUpdateValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private val TAG = Log.tag(ApkUpdateValues::class.java)

    private const val DOWNLOAD_ID = "apk_update.download_id"
    private const val DIGEST = "apk_update.digest"
    private const val AUTO_UPDATE = "apk_update.auto_update"
    private const val LAST_SUCCESSFUL_CHECK = "apk_update.last_successful_check"
    private const val LAST_APK_UPLOAD_TIME = "apk_update.last_apk_upload_time"
    private const val PENDING_APK_UPLOAD_TIME = "apk_update.pending_apk_upload_time"
    private const val AVAILABLE_UPDATE_VERSION_NAME = "apk_update.available_update_version_name"
    private const val AVAILABLE_UPDATE_VERSION_CODE = "apk_update.available_update_version_code"
    private const val DOWNLOAD_ALLOWS_METERED = "apk_update.download_allows_metered"
    private const val READ_ONLY_CHOSEN_VERSION_CODE = "apk_update.read_only_chosen_version_code"
    private const val UPDATE_MANIFEST = "apk_update.update_manifest"
    private const val UPDATE_MANIFEST_SERIAL = "apk_update.update_manifest_serial"
    private const val UPDATE_MANIFEST_CHECKED_AT = "apk_update.update_manifest_checked_at"
    private const val READ_ONLY_SINCE_MANIFEST_SERIAL = "apk_update.read_only_since_manifest_serial"
    private const val RECOMMENDED_PROMPT_SHOWN_BUILD = "apk_update.recommended_prompt_shown_build"
    private const val UPDATE_BANNER_DISMISSED_AT = "apk_update.update_banner_dismissed_at"
    private const val ABOUT_SEEN_UPDATE_BUILD = "apk_update.about_seen_update_build"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  val downloadId: Long by longValue(DOWNLOAD_ID, -2)
  val digest: ByteArray? get() = store.getBlob(DIGEST, null)
  var autoUpdate: Boolean by booleanValue(AUTO_UPDATE, true)
  var lastSuccessfulCheck: Long by longValue(LAST_SUCCESSFUL_CHECK, 0)

  /** The upload of the last APK we installed */
  var lastApkUploadTime: Long
    get() = getLong(LAST_APK_UPLOAD_TIME, 0)
    set(value) {
      Log.d(TAG, "Setting lastApkUploadTime to $value")
      store.beginWrite().putLong(LAST_APK_UPLOAD_TIME, value).commit()
    }

  /** The upload time of the APK we're trying to install */
  val pendingApkUploadTime: Long by longValue(PENDING_APK_UPLOAD_TIME, 0)

  /**
   * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 要改 1 / 3）：[downloadId] 这条下载是否允许用流量。
   * 阻断页发起的是 true；上游的后台检查只许 Wi-Fi，是 false（旧记录没有这个键，也按 false）。
   */
  val downloadAllowsMetered: Boolean by booleanValue(DOWNLOAD_ALLOWS_METERED, false)

  /**
   * Tellomi（tellomi/tellomi#1138，owner 2026-09-24 规则 1）：用户在「必须更新」阻断页选「暂不更新，只看聊天记录」时的 versionCode。
   * 等于当前 versionCode 就不再自动盖阻断页；装上新版本后自然作废。0 = 没选过。
   */
  var readOnlyChosenVersionCode: Int by integerValue(READ_ONLY_CHOSEN_VERSION_CODE, 0)

  /**
   * Tellomi（tellomi/tellomi#1491）：最后一份采信的更新清单（验过签的原始字节，[org.thoughtcrime.securesms.updaterequired.UpdateManifests]）；
   * 没有则为 null。断网时照它判档（需求 §3.2「缓存」）。
   */
  val updateManifest: ByteArray? get() = store.getBlob(UPDATE_MANIFEST, null)

  /** 上面那份清单的 serial；还没有时为 0。新拉到的清单 serial 比它小就不采信（防重放）。 */
  val updateManifestSerial: Long by longValue(UPDATE_MANIFEST_SERIAL, 0)

  /** 最近一次拉清单（不管采没采信）的时间，用来节流。 */
  var updateManifestCheckedAt: Long by longValue(UPDATE_MANIFEST_CHECKED_AT, 0)

  /**
   * 进入只读（`misc.isClientDeprecated`）时缓存里那份清单的 serial；-1 = 没在只读。
   * 之后拉到一份 serial 更大、而且不判「必须」的清单才放行——这就是「撤销」（需求 §3.2.1）。
   */
  var readOnlySinceManifestSerial: Long by longValue(READ_ONLY_SINCE_MANIFEST_SERIAL, -1)

  /** Tellomi（tellomi/tellomi#1491）：推荐档的首页卡片已经为哪个最新版（latest.build）弹过；同一版本只弹一次（需求 3.4）。 */
  var recommendedPromptShownBuild: Long by longValue(RECOMMENDED_PROMPT_SHOWN_BUILD, 0)

  /** 推荐档横幅上次被关掉的时间；关掉后 3 天内不再出现（需求 3.4）。 */
  var updateBannerDismissedAt: Long by longValue(UPDATE_BANNER_DISMISSED_AT, 0)

  /** 在「关于」里看到过的最新版（latest.build）；设置入口的小红点进一次「关于」就消失（需求 3.4「可选更新」）。 */
  var aboutSeenUpdateBuild: Long by longValue(ABOUT_SEEN_UPDATE_BUILD, 0)

  fun setUpdateManifest(raw: ByteArray, serial: Long) {
    store
      .beginWrite()
      .putBlob(UPDATE_MANIFEST, raw)
      .putLong(UPDATE_MANIFEST_SERIAL, serial)
      .commit()
  }

  /**
   * Tellomi（tellomi/tellomi#1138）：最近一次清单检查看到的、比当前安装更新的版本号（清单的 versionName）；没有则为 null。
   * 只有官网版（MANAGES_APP_UPDATES）会写。「关于」页的「有新版本」读这里。
   *
   * 读的时候再按 versionCode 和当前安装的比一次：装上那个版本之后、下一次检查之前，这里不会还说「有新版本」。
   */
  val availableUpdateVersionName: String?
    get() = getString(AVAILABLE_UPDATE_VERSION_NAME, null).takeIf { getInteger(AVAILABLE_UPDATE_VERSION_CODE, 0) > BuildConfig.VERSION_CODE }

  fun setAvailableUpdate(versionCode: Int, versionName: String?) {
    store
      .beginWrite()
      .putInteger(AVAILABLE_UPDATE_VERSION_CODE, versionCode)
      .putString(AVAILABLE_UPDATE_VERSION_NAME, versionName)
      .commit()
  }

  fun clearAvailableUpdate() {
    store
      .beginWrite()
      .putInteger(AVAILABLE_UPDATE_VERSION_CODE, 0)
      .putString(AVAILABLE_UPDATE_VERSION_NAME, null)
      .commit()
  }

  fun setDownloadAttributes(id: Long, digest: ByteArray?, apkUploadTime: Long, allowsMetered: Boolean = false) {
    Log.d(TAG, "Saving download attributes. id: $id, apkUploadTime: $apkUploadTime, allowsMetered: $allowsMetered")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, id)
      .putBlob(DIGEST, digest)
      .putLong(PENDING_APK_UPLOAD_TIME, apkUploadTime)
      .putBoolean(DOWNLOAD_ALLOWS_METERED, allowsMetered)
      .commit()
  }

  fun clearDownloadAttributes() {
    Log.d(TAG, "Clearing download attributes.")

    store
      .beginWrite()
      .putLong(DOWNLOAD_ID, -1)
      .putBlob(DIGEST, null)
      .putLong(PENDING_APK_UPLOAD_TIME, 0)
      .putBoolean(DOWNLOAD_ALLOWS_METERED, false)
      .commit()
  }
}
