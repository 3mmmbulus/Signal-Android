/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.TellomiCrossBorderNetworkGate
import org.thoughtcrime.securesms.util.RemoteConfig

/** Tellomi（REGION_PROFILE 第六节之二）：[TellomiRegionAutoSwitch] 的接线。 */
object TellomiRegionAutoSwitchRunner {
  private val TAG = Log.tag(TellomiRegionAutoSwitchRunner::class.java)
  private const val PROBE_TIMEOUT_MS = 5_000L

  @JvmStatic
  val instance: TellomiRegionAutoSwitch by lazy {
    TellomiRegionAutoSwitch(
      profiles = { TellomiRegions.profiles() },
      storedId = { SignalStore.tellomiRegion.currentId },
      targetValue = { RemoteConfig.tellomiRegionTarget },
      loadFailures = { SignalStore.tellomiRegion.targetFailures },
      storeFailures = { SignalStore.tellomiRegion.targetFailures = it },
      probe = { TellomiChatProbe.probe(it, PROBE_TIMEOUT_MS) is TellomiRegionSelector.ProbeResult.Ok },
      switchTo = { TellomiRegionSwitcher.instance.switchTo(it) }
    )
  }

  /** 在后台线程评估一次；跨境告知没点之前不碰网络。 */
  @JvmStatic
  fun evaluateAsync(why: String) {
    SignalExecutors.BOUNDED.execute {
      if (TellomiCrossBorderNetworkGate.isBlocking()) {
        return@execute
      }
      try {
        val reason = instance.evaluate()
        if (reason != TellomiRegionAutoSwitch.Reason.OFF && reason != TellomiRegionAutoSwitch.Reason.STAY) {
          Log.i(TAG, "region target ($why): $reason, now ${TellomiRegions.current().id.id}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "region target ($why) failed", e)
      }
    }
  }
}
