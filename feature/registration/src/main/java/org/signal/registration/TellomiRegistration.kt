/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Tellomi（tellomi/tellomi#1210）：注册流程里和香港服务端能力对齐的常量，以及给人看的限流时长。
 */
object TellomiRegistration {

  /**
   * Tellomi（tellomi/tellomi ADR-0078 §3.4）：被人工暂停的号码重新注册时，服务端在号码验证过之后回 422 +
   * `{"tellomiRestriction":{"level":"suspended","until":<秒>,"appeal":…,"case":…}}`（Signal-Server#29）。
   * 解析不出来（别的 422、老服务端）就是 null，照旧显示通用错误。
   */
  data class SuspensionNotice(val untilMillis: Long, val appeal: String, val caseId: String?)

  private val APPEAL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
  private val CASE_ID = Regex("^[0-9A-Za-z._-]{1,64}$")

  @JvmStatic
  fun suspensionNotice(body: String?): SuspensionNotice? {
    if (body.isNullOrBlank()) return null
    return try {
      val restriction = Json.parseToJsonElement(body) as? JsonObject ?: return null
      val raw = restriction["tellomiRestriction"] as? JsonObject ?: return null
      if (raw["level"]?.jsonPrimitive?.content != "suspended") return null
      val until = raw["until"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
      val appeal = raw["appeal"]?.jsonPrimitive?.content?.takeIf { APPEAL.matches(it) } ?: return null
      val caseId = raw["case"]?.jsonPrimitive?.content?.takeIf { CASE_ID.matches(it) }
      SuspensionNotice(TimeUnit.SECONDS.toMillis(until), appeal, caseId)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * 香港服务端没有语音验证码通道（`deploy/hk/enable-aliyun-sms.sh`：`voice: available-only-in-regions: [ZZ]`），
   * 但会话里的 `nextCall` 照样给值，上游于是一直显示「给我打电话」，点了只会报「我们无法致电…」。
   * 接上语音通道再改成 true；服务端哪天对不可用的会话返回 `nextCall = null`，界面也同样不显示。
   */
  const val VOICE_VERIFICATION_AVAILABLE = false

  /**
   * 能收短信验证码的国家码。香港的 registration-service 只给中国大陆配了短信发送器（`sms: [CN]`）。
   *
   * 别的地区请求验证码，服务端回 440 `providerUnavailable` 且 `permanentFailure = false`
   * （registration-service 的 NO_SENDER_AVAILABLE / mayRetry=false 被 Signal-Server 原样当成 permanent 传），
   * 光看响应分不出「地区没开放」和「短信通道临时故障」，所以要结合号码的国家码判断；与 iOS
   * `TSConstants.smsVerificationCallingCodes`（tellomi/Signal-iOS#12）同一张表。服务端的根治见 tellomi/tellomi#1230。
   */
  @JvmField
  val SMS_VERIFICATION_CALLING_CODES: Set<String> = setOf("86")

  /**
   * 没有备份服务（没有消息备份的服务端与 CDN），「从 Tellomi 备份 · 您的免费或付费备份套餐」点进去是死路，
   * 恢复方式选择页不列这一项。与 App 侧 `Environment.Backups` 一致；接上备份服务再改成 true。
   */
  const val REMOTE_BACKUPS_AVAILABLE = false

  /**
   * 只给测试用：上游「远端备份恢复」的端到端用例要走「从 Tellomi 备份」这一项，打开它只为让那些用例照样能跑
   * （那条流程的代码还在，只是界面上没有入口）。生产代码不许写它。
   */
  @VisibleForTesting
  @Volatile
  var remoteBackupsAvailableForTesting: Boolean? = null

  /** 恢复方式选择页列不列「从 Tellomi 备份」。 */
  val isRemoteBackupAvailable: Boolean
    get() = remoteBackupsAvailableForTesting ?: REMOTE_BACKUPS_AVAILABLE

  /**
   * 同一个注册会话最多能发几条验证码短信：香港配置 `send-sms-verification-code.delays: [30s, 1m, 5m]`，列表长度 = 条数
   * （`deploy/hk/enable-aliyun-sms.sh`）。「收不到验证码？」面板用它说清额度（tellomi/tellomi#1214），
   * 与 iOS `TSConstants.smsVerificationCodesPerSession` 同值。
   */
  const val SMS_VERIFICATION_CODES_PER_SESSION = 3

  private val CODE_IN_TEXT = Regex("(?<![0-9])([0-9]{3})[ -]?([0-9]{3})(?![0-9])")

  /**
   * 在一段文字（整条短信、剪贴板里的「123-456」）里找一个完整的 6 位验证码：中间最多一个空格或连字符，
   * 前后不紧挨别的数字（手机号那样的长串不算）。上游只接受「去掉非数字后恰好 6 位」，
   * 整条短信里还有「5 分钟内有效」就凑成 7 位、整串被忽略（tellomi/tellomi#1214）。
   */
  @JvmStatic
  fun verificationCodeIn(text: String): String? = CODE_IN_TEXT.find(text)?.let { it.groupValues[1] + it.groupValues[2] }

  /**
   * 限流等待时长给人看的样子，按界面语言用系统 ICU 排版：中文「1分钟30秒」、英文「1 minute, 30 seconds」。
   * 上游把 Kotlin 的 `Duration.toString()`（「1m 30s」）原样填进文案。
   *
   * 有小时就不再报秒；不足 1 秒按 1 秒算。Android 6（API 23）没有 `android.icu`，退回「1:30」。
   */
  @JvmStatic
  fun retryAfterText(duration: Duration, locale: Locale): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    if (Build.VERSION.SDK_INT < 24) {
      return if (hours > 0) String.format(locale, "%d:%02d:%02d", hours, minutes, seconds) else String.format(locale, "%d:%02d", minutes, seconds)
    }

    val measures = buildList {
      if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
      if (minutes > 0) add(Measure(minutes, MeasureUnit.MINUTE))
      if (seconds > 0 && hours == 0L) add(Measure(seconds, MeasureUnit.SECOND))
    }
    // 中文用 SHORT：WIDE 的秒是「秒钟」，读出来是「1分钟30秒钟」；其余语言用 WIDE：英文 SHORT 是「1 min, 30 sec」
    val width = if (locale.language == Locale.CHINESE.language) MeasureFormat.FormatWidth.SHORT else MeasureFormat.FormatWidth.WIDE
    return MeasureFormat.getInstance(ULocale.forLocale(locale), width).formatMeasures(*measures.toTypedArray())
  }
}
