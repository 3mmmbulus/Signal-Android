/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.logout

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.leolin.shortcutbadger.ShortcutBadger
import org.signal.core.util.logging.Log
import org.signal.network.NetworkResult
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.FeedCapabilityStore
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobs.FcmRefreshJob
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkdevice.LinkDeviceRepository
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.util.ConversationUtil

/**
 * Tellomi（ADR-0072，tellomi/tellomi#1414）：主设备「退出登录」。
 *
 * 退出登录只是这台手机上的一个状态，服务端什么都不改：
 * - 退出：（按需）让已链接的设备退出 → `DELETE /v1/accounts/gcm`（失败就不退出）→ 写下持久的「已退出」标记 → 停掉收消息和前台服务 →
 *   回欢迎页。本地数据库、密钥、登录凭据原样留着。
 * - 退出期间：标记就是闸——不连 websocket（两条都不连）、服务端请求只放行验证会话（`/v1/verification/session`）、
 *   要网络的任务一律等着（[NetworkConstraint]）、推送到了也不去拉、不出通知，界面一律路由到欢迎页。
 *   断网这部分借的是现有「被服务端登出」（unauthorized）的那几道闸；界面不借——被登出时还能看聊天，退出登录之后必须看不到。
 *   也不借它的 [org.thoughtcrime.securesms.util.TextSecurePreferences.setUnauthorizedReceived]：那会顺手换掉本机的资料密钥。
 * - 重新登录：同一个号码的验证会话 `verified=true`（开了注册锁的再在本机核对 PIN）→ [completeRelogin]。**不调 `POST /v1/registration`**。
 */
object TellomiLogout {

  private val TAG = Log.tag(TellomiLogout::class.java)

  /** 已退出登录时唯一放行的服务端请求：同一个号码的验证会话（人机验证、推送挑战、短信验证码）。 */
  private const val VERIFICATION_SESSION_PATH = "/v1/verification/session"

  /** 本机现在是不是「已退出登录」。依赖还没初始化时当作不是——那时候什么网络都还没开始。 */
  @JvmStatic
  fun isLoggedOut(): Boolean {
    return AppDependencies.isInitialized && SignalStore.account.tellomiLoggedOut
  }

  /** 已退出登录时这个请求路径能不能发出去。 */
  @JvmStatic
  fun isAllowedWhileLoggedOut(path: String): Boolean {
    return path == VERIFICATION_SESSION_PATH || path.startsWith("$VERIFICATION_SESSION_PATH/")
  }

  sealed interface Result {
    data object Success : Result

    /** 没网（或服务端没应答）：没有退出，App 仍是已登录，提示「退出登录需要联网，请稍后再试。」 */
    data object NeedsNetwork : Result
  }

  /**
   * 退出登录，保留本机聊天记录（ADR-0072 §4.1），按顺序：
   * 1. [unlinkDevices] 时逐个 `DELETE /v1/devices/{id}`；
   * 2. `DELETE /v1/accounts/gcm`——失败就停在这里，不退出；
   * 3. 写下持久的「已退出」标记，停掉收消息与前台服务，清掉通知和会话快捷方式。
   *
   * 第 4 步（回欢迎页）由界面做。
   */
  suspend fun logOut(context: Context, unlinkDevices: Boolean): Result = withContext(Dispatchers.IO) {
    if (unlinkDevices && !unlinkAllDevices()) {
      Log.w(TAG, "[logOut] Couldn't log out the linked devices. Staying logged in.")
      return@withContext Result.NeedsNetwork
    }

    when (val result = SignalNetwork.accountApi.deleteGcmRegistrationId()) {
      is NetworkResult.Success -> Log.i(TAG, "[logOut] The service will no longer push to this device.")
      else -> {
        Log.w(TAG, "[logOut] Couldn't clear the push token (${result.javaClass.simpleName}). Staying logged in.", result.getCause())
        return@withContext Result.NeedsNetwork
      }
    }

    lockLocally(context)
    // 动态的 token 缓存和来源不留给下一次登录（只是内存里的东西，不碰本机数据）
    FeedSources.reset()
    FeedCapabilityStore.clear()
    Result.Success
  }

  /**
   * 「退出并删除本机数据」的前半段（ADR-0072 §4.3）：尽量让已链接的设备退出、注销推送令牌，没网也继续。
   * 后半段是界面上现有的 `clearApplicationUserData()`。
   */
  suspend fun prepareForLocalDataDeletion(unlinkDevices: Boolean) = withContext(Dispatchers.IO) {
    if (unlinkDevices && !unlinkAllDevices()) {
      Log.w(TAG, "[prepareForLocalDataDeletion] Couldn't log out every linked device. Deleting local data anyway.")
    }

    val result = SignalNetwork.accountApi.deleteGcmRegistrationId()
    if (result !is NetworkResult.Success) {
      Log.w(TAG, "[prepareForLocalDataDeletion] Couldn't clear the push token (${result.javaClass.simpleName}). Deleting local data anyway.", result.getCause())
    }
  }

  /**
   * 同一个号码的验证会话已经 `verified=true`（开了注册锁的也核对过 PIN），解锁本机（ADR-0072 §4.2 第 4 步）：
   * 去掉「已退出」标记 → 重新登记推送令牌 → 连上 websocket 收排队的消息。不调注册接口。
   */
  fun completeRelogin(context: Context) {
    Log.i(TAG, "[completeRelogin] Unlocking this device after re-verifying the same number.")

    SignalStore.account.setTellomiLoggedOut(false)
    SignalStore.account.tellomiReloginPinFailed = 0
    SignalStore.account.tellomiReloginPinLockedUntil = 0

    // 退出时 DELETE /v1/accounts/gcm 把 fetchesMessages 置成了 false；只靠常连收消息的设备要靠这一步改回来。
    AppDependencies.jobManager.add(RefreshAttributesJob())
    if (SignalStore.account.fcmEnabled) {
      AppDependencies.jobManager.add(FcmRefreshJob())
    }
    // 退出期间别人给我们发消息会用掉一次性预密钥，回来先补上。
    AppDependencies.jobManager.add(PreKeysSyncJob.create())

    AppDependencies.resetNetwork()
    AppDependencies.startNetwork()
    AppDependencies.jobManager.onConstraintMet("TellomiRelogin")
  }

  /** 写下标记，然后把已经在跑的东西停掉。标记同步写盘，写完之后任何新的连接和任务都过不了闸。 */
  private fun lockLocally(context: Context) {
    SignalStore.account.setTellomiLoggedOut(true)
    Log.i(TAG, "[lockLocally] Logged out. Stopping message retrieval and clearing anything that shows content.")

    IncomingMessageObserver.stopForegroundService(context)
    AppDependencies.resetNetwork()

    NotificationManagerCompat.from(context).cancelAll()
    try {
      ShortcutBadger.removeCount(context)
    } catch (e: Throwable) {
      Log.w(TAG, "[lockLocally] Couldn't clear the launcher badge.", e)
    }
    ConversationUtil.clearAllShortcuts(context)
    ShortcutManagerCompat.removeAllDynamicShortcuts(context)
  }

  /** 让所有已链接的设备退出；任何一台没成功（多半是没网）就返回 false。 */
  private suspend fun unlinkAllDevices(): Boolean {
    return try {
      val devices = LinkDeviceRepository.loadDevices() ?: return false
      val allRemoved = devices.all { LinkDeviceRepository.removeDevice(it.id) }
      if (allRemoved) {
        SignalStore.account.isMultiDevice = false
      }
      allRemoved
    } catch (e: Exception) {
      Log.w(TAG, "[unlinkAllDevices] Failed.", e)
      false
    }
  }
}
