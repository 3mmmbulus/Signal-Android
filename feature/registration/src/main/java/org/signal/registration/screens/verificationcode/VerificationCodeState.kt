/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.verificationcode

import org.signal.network.api.RegistrationApiV2.SessionMetadata
import org.signal.network.api.RegistrationApiV2.VerificationCodeTransport
import org.signal.registration.TellomiRegistration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class VerificationCodeState(
  val sessionMetadata: SessionMetadata? = null,
  val e164: String = "",
  val isSubmittingCode: Boolean = false,
  val rateLimits: SmsAndCallRateLimits = SmsAndCallRateLimits(),
  val incorrectCodeAttempts: Int = 0,
  val autoFillCode: String? = null,
  val digits: List<String> = List(CODE_LENGTH) { "" },
  val focusedDigitIndex: Int = 0,
  val showContactSupportSheet: Boolean = false,
  val showContactSupportDialog: Boolean = false,
  val snackbars: Snackbars = Snackbars(),
  val dialogs: Dialogs = Dialogs(),
  /**
   * Tellomi（tellomi/tellomi#1214，taishi 审查 b19 要改 1）：关掉「会话已过期」框、正在退回手机号页。这一页的 ViewModel 要等出栈动画
   * 结束才销毁，这期间父状态里的会话已经清空；有这个标记时不把「没有会话」当成要重置整个流程。收到新会话时清掉。
   */
  val leavingForExpiredSession: Boolean = false
) {
  override fun toString(): String = "VerificationCodeState(sessionMetadata=$sessionMetadata, e164=$e164, isSubmittingCode=$isSubmittingCode, rateLimits=$rateLimits, incorrectCodeAttempts=$incorrectCodeAttempts, autoFillCode=${autoFillCode?.let { "present" }}, digitsEntered=${digits.count { it.isNotEmpty() }}, focusedDigitIndex=$focusedDigitIndex, showContactSupportSheet=$showContactSupportSheet,  showContactSupportDialog=$showContactSupportDialog, snackbars=$snackbars, dialogs=$dialogs)"

  /**
   * The full code as currently entered. Only meaningful when [isComplete] is true.
   */
  val code: String get() = digits.joinToString("")

  /**
   * True once every digit field has a value.
   */
  val isComplete: Boolean get() = digits.size == CODE_LENGTH && digits.all { it.isNotEmpty() }

  companion object {
    const val CODE_LENGTH = 6

    /**
     * A fully empty set of digits, used to reset the fields.
     */
    fun emptyDigits(): List<String> = List(CODE_LENGTH) { "" }
  }

  /** Transient errors from submitting a code or registering, shown as snackbars. Cleared once shown and dismissed. */
  data class Snackbars(
    val networkError: Boolean = false,
    val unknownError: Boolean = false,
    val rateLimitedRetryAfter: Duration? = null,
    val incorrectVerificationCode: Boolean = false,
    val registrationError: Boolean = false
  )

  /** Errors from requesting a verification code (resend SMS / call me), shown as modal dialogs so they aren't missed. */
  data class Dialogs(
    val networkError: Boolean = false,
    val unknownError: Boolean = false,
    val rateLimitedRetryAfter: Duration? = null,
    val unableToSendSms: Boolean = false,
    val couldNotRequestCodeWithSelectedTransport: Boolean = false,
    /** Nonnull when the delivery provider rejected the request. Carries the failed transport for accurate wording. */
    val providerRejectedTransport: VerificationCodeTransport? = null,
    /** Tellomi（tellomi/tellomi#1214）：会话过期（404）。上游不声不响地退回手机号页；先说清楚，关掉再退回。 */
    val sessionExpired: Boolean = false,
    /** Tellomi（tellomi/tellomi#1214）：这个会话里已经不能再提交验证码（没发过码，或者已经失效）。同上。 */
    val codeNoLongerValid: Boolean = false,
    /** Tellomi（ADR-0078 §3.4）：这个号码被暂停了，注册被拒（422）。说明到什么时候、去哪申诉。 */
    val tellomiSuspended: TellomiRegistration.SuspensionNotice? = null
  )

  /**
   * Returns true if the user can resend SMS right now (a timer exists and has expired). False while a timer is still
   * counting down, and also when SMS resend is unavailable ([SmsAndCallRateLimits.smsResendTimeRemaining] is null).
   */
  fun canResendSms(): Boolean = rateLimits.smsResendTimeRemaining.let { it != null && it <= 0.seconds }

  /**
   * Returns true if the user can request a call right now (a timer exists and has expired). False while a timer is
   * still counting down, and also when calls are unavailable ([SmsAndCallRateLimits.callRequestTimeRemaining] is null).
   */
  fun canRequestCall(): Boolean = rateLimits.callRequestTimeRemaining.let { it != null && it <= 0.seconds }

  /**
   * The SMS resend cooldown to display as a countdown, or null when there is nothing to count down — either because
   * resend is available now or because it is unavailable.
   */
  fun smsResendCountdown(): Duration? = rateLimits.smsResendTimeRemaining?.takeIf { it > 0.seconds }

  /**
   * The call request cooldown to display as a countdown, or null when there is nothing to count down — either because
   * a call can be requested now or because it is unavailable.
   */
  fun callRequestCountdown(): Duration? = rateLimits.callRequestTimeRemaining?.takeIf { it > 0.seconds }

  /**
   * Returns true if the "Having Trouble" button should be shown.
   *
   * Tellomi（tellomi/tellomi#1214，ADR-0051 §二）：一进页面就显示。上游要连错 3 次才出现，可收不到短信的人没有码可交，
   * 得先用假码烧掉每个会话 5 次里的 3 次才看得到这些出路（taishi 审查 b8）。
   */
  fun shouldShowHavingTrouble(): Boolean = true
}

/**
 * Rate limit data for SMS resend and phone call request countdown timers.
 *
 * For each transport: `null` means the server won't allow that request at all (its button is disabled with no
 * countdown), [kotlin.time.Duration.ZERO] means it can be requested now, and a positive value is the countdown until
 * it becomes available.
 */
data class SmsAndCallRateLimits(
  val smsResendTimeRemaining: Duration? = 0.seconds,
  val callRequestTimeRemaining: Duration? = 0.seconds
)
