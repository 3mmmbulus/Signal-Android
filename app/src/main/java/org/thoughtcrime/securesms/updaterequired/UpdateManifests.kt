/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.Hex
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.UpdateManifestJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.region.TellomiRegions
import kotlin.time.Duration.Companion.hours

/**
 * Tellomi（tellomi/tellomi#1491）：更新清单在本机这一半——什么时候去拉、拉到以后怎么改只读状态。
 *
 * 只读复用上游的 `misc.isClientDeprecated`：设了它就发不出、收不到，阻断页、只读横幅、输入框、系统通知都是现成的
 * （[UpdateRequired]、`ClientDeprecatedActivity`）。原来只有服务端 499 和构建到期会设它、而且只有换版本才清
 * （需求 §3.3「现状」）；这里加上清单判「必须」时设、清单撤销时清。
 */
object UpdateManifests {

  private val TAG = Log.tag(UpdateManifests::class)

  const val PLATFORM = "android"
  const val CHANNEL = "website"
  const val PATH = "update/v1/android-website.json"

  /**
   * 发布公钥 = Desktop 更新源和链接注册表那一把（`clients/desktop/config/default.json` 的 `updatesPublicKey`）。
   * 私钥只在发版那台 Mac 上（超级仓库 `scripts/release/update_manifest.py`）。
   */
  private const val PUBLIC_KEY_HEX = "0569dca6fde7f7dd8e03eee459d54b385b4067230e4c0452eb0c6fc3d2644f914c"

  /** 回前台时最多这么久拉一次（需求 §3.2「节流 6 小时」）；只读时每次回前台都拉，好让撤销尽快生效。 */
  val CHECK_INTERVAL = 6.hours

  @JvmStatic
  val publicKey: ByteArray
    get() = Hex.fromStringCondensed(PUBLIC_KEY_HEX)

  /** 只有官网版自己管更新（[BuildConfig.MANAGES_APP_UPDATES]）；别的渠道没有这份清单。 */
  @JvmStatic
  val isEnabled: Boolean
    get() = BuildConfig.MANAGES_APP_UPDATES

  /** 清单地址：当前区的更新源主机 + 固定路径（切区后跟着走，同 [TellomiRegions.current]）。 */
  @JvmStatic
  fun manifestUrl(updatesHost: String = TellomiRegions.current().updatesHost): String = "https://$updatesHost/$PATH"

  /** 当前采信的清单（缓存）；没有或读不出时为 null。 */
  @JvmStatic
  fun current(): UpdateManifest? = SignalStore.apkUpdate.updateManifest?.let { UpdateManifest.parseTrusted(it) }

  @JvmStatic
  @JvmOverloads
  fun currentTier(nowMillis: Long = System.currentTimeMillis()): UpdateManifest.Tier {
    if (!isEnabled) {
      return UpdateManifest.Tier.NONE
    }
    return current()?.tierFor(BuildConfig.VERSION_CODE.toLong(), nowMillis) ?: UpdateManifest.Tier.NONE
  }

  /** 缓存的清单此刻判「必须」时写的原因；不判「必须」、没有清单或不是官网版时为 null（阻断页的原因句，[UpdateRequired.displayReason]）。 */
  @JvmStatic
  @JvmOverloads
  fun requiredReason(nowMillis: Long = System.currentTimeMillis()): UpdateManifest.RequiredReason? {
    if (currentTier(nowMillis) != UpdateManifest.Tier.REQUIRED) {
      return null
    }
    return current()?.requiredReason
  }

  /**
   * 回到前台（`ApplicationContext.onForeground`，在后台线程）：先按缓存调一次只读状态（`requiredFrom` 可能刚到），
   * 再按节流去拉。
   */
  @JvmStatic
  @WorkerThread
  fun onForeground() {
    if (!isEnabled) {
      return
    }

    reconcileNow()

    val now = System.currentTimeMillis()
    if (shouldCheck(now, SignalStore.apkUpdate.updateManifestCheckedAt, SignalStore.misc.isClientDeprecated)) {
      AppDependencies.jobManager.add(UpdateManifestJob())
    }
  }

  @VisibleForTesting
  fun shouldCheck(nowMillis: Long, lastCheckedMillis: Long, isReadOnly: Boolean): Boolean {
    if (isReadOnly) {
      return true
    }
    val sinceLast = nowMillis - lastCheckedMillis
    // 时钟往回拨过（sinceLast < 0）也拉一次，免得一直等到「上次」那个未来时间
    return sinceLast < 0 || sinceLast >= CHECK_INTERVAL.inWholeMilliseconds
  }

  /**
   * 拉到一份清单（[UpdateManifestJob]）：按采信规则判（[UpdateManifest.accept]），采信就存下，然后调只读状态。
   *
   * @return 是否采信
   */
  @JvmStatic
  @WorkerThread
  fun onFetched(data: ByteArray, signatureHex: String): Boolean {
    SignalStore.apkUpdate.updateManifestCheckedAt = System.currentTimeMillis()

    val accepted = UpdateManifest.accept(data, signatureHex, publicKey, PLATFORM, CHANNEL, SignalStore.apkUpdate.updateManifestSerial)
    if (accepted == null) {
      reconcileNow()
      return false
    }

    SignalStore.apkUpdate.setUpdateManifest(data, accepted.serial)
    Log.i(TAG, "Accepted update manifest ${accepted.serial}: latest ${accepted.latest.build}, recommended ${accepted.recommendedMinBuild}, required ${accepted.requiredMinBuild}.")
    reconcileNow()
    return true
  }

  /** 按缓存的清单和现在的时间调只读状态。 */
  @JvmStatic
  @WorkerThread
  fun reconcileNow() {
    if (!isEnabled) {
      return
    }

    val result = reconcile(
      manifest = current(),
      build = BuildConfig.VERSION_CODE.toLong(),
      nowMillis = System.currentTimeMillis(),
      readOnly = SignalStore.misc.isClientDeprecated,
      buildExpired = UpdateRequired.isBuildExpired(),
      readOnlySinceSerial = SignalStore.apkUpdate.readOnlySinceManifestSerial
    )

    if (result.readOnly && !SignalStore.misc.isClientDeprecated) {
      Log.w(TAG, "The update manifest requires an update. This version is now read-only.")
      SignalStore.misc.isClientDeprecated = true
    }

    SignalStore.apkUpdate.readOnlySinceManifestSerial = result.readOnlySinceSerial

    if (result.released) {
      Log.i(TAG, "The required update was withdrawn. Leaving read-only and reconnecting.")
      SignalStore.misc.isClientDeprecated = false
      // 上一次在阻断页选的「只看聊天记录」只对那一次有效；以后再要求更新时阻断页照常出现
      SignalStore.apkUpdate.readOnlyChosenVersionCode = 0
      AppDependencies.resetNetwork()
    }
  }

  data class Reconciled(
    /** 调完以后是否只读。 */
    val readOnly: Boolean,
    /** 要存的 [org.thoughtcrime.securesms.keyvalue.ApkUpdateValues.readOnlySinceManifestSerial]。 */
    val readOnlySinceSerial: Long,
    /** 这一次从只读放行了。 */
    val released: Boolean
  )

  /**
   * 纯函数（单测用）。规则（需求 §3.2.1「撤销」、§3.3「现状」）：
   * - 清单判「必须」→ 只读；
   * - 已经只读、而且不是本机构建到期：记下当时缓存里那份清单的 serial（服务端 499 置只读时这里不在场，第一次看到时补记）；
   *   之后拿到一份 serial 更大、不判「必须」的清单 → 放行。服务端如果还在拒，下一次请求的 499 会再置回只读。
   * - 本机构建到期的只读不归清单管（owner 2026-09-24：到期只降成只读，换新版才解）。
   */
  @VisibleForTesting
  fun reconcile(
    manifest: UpdateManifest?,
    build: Long,
    nowMillis: Long,
    readOnly: Boolean,
    buildExpired: Boolean,
    readOnlySinceSerial: Long
  ): Reconciled {
    val tier = manifest?.tierFor(build, nowMillis) ?: UpdateManifest.Tier.NONE

    if (tier == UpdateManifest.Tier.REQUIRED) {
      return Reconciled(
        readOnly = true,
        readOnlySinceSerial = if (readOnly && readOnlySinceSerial >= 0) readOnlySinceSerial else manifest!!.serial,
        released = false
      )
    }

    if (!readOnly) {
      return Reconciled(readOnly = false, readOnlySinceSerial = -1, released = false)
    }

    if (buildExpired) {
      return Reconciled(readOnly = true, readOnlySinceSerial = readOnlySinceSerial, released = false)
    }

    if (readOnlySinceSerial < 0) {
      return Reconciled(readOnly = true, readOnlySinceSerial = manifest?.serial ?: 0, released = false)
    }

    if (manifest != null && manifest.serial > readOnlySinceSerial) {
      return Reconciled(readOnly = false, readOnlySinceSerial = -1, released = true)
    }

    return Reconciled(readOnly = true, readOnlySinceSerial = readOnlySinceSerial, released = false)
  }
}
