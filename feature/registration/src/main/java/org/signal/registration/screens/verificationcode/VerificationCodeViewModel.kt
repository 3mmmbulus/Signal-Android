/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.verificationcode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.network.api.RegistrationApiV2.RegisterAccountError
import org.signal.network.api.RegistrationApiV2.RequestVerificationCodeError
import org.signal.network.api.RegistrationApiV2.SessionMetadata
import org.signal.network.api.RegistrationApiV2.SubmitVerificationCodeError
import org.signal.network.api.RegistrationApiV2.VerificationCodeTransport
import org.signal.registration.PendingRestoreOption
import org.signal.registration.PreExistingRegistrationData
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.TellomiRegistration
import org.signal.registration.VerificationCodeRequest
import org.signal.registration.screens.util.navigateBack
import org.signal.registration.screens.util.navigateTo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class VerificationCodeViewModel(
  private val repository: RegistrationRepository,
  private val parentState: StateFlow<RegistrationFlowState>,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit,
  smsCodeEvents: Flow<String> = emptyFlow(),
  private val clock: () -> Long = { System.currentTimeMillis() }
) : EventDrivenViewModel<VerificationCodeScreenEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(VerificationCodeViewModel::class)

    private const val CODE_LENGTH = VerificationCodeState.CODE_LENGTH

    /**
     * How old the in-progress registration data can be before we assume the verification session has expired and
     * restart the flow. Checked whenever the screen is foregrounded.
     */
    private val IN_PROGRESS_DATA_TIMEOUT = 15.minutes

    /**
     * Cold [Flow] of verification codes automatically retrieved from incoming SMS messages via the Play Services SMS
     * retriever. Registers a [BroadcastReceiver] for [SmsRetriever.SMS_RETRIEVED_ACTION] while collected, and
     * unregisters it when collection stops.
     */
    fun smsCodeFlow(context: Context): Flow<String> = callbackFlow {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) {
            return
          }

          val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status
          when (status?.statusCode) {
            CommonStatusCodes.SUCCESS -> {
              val code = VerificationCodeParser.parse(intent.extras?.getString(SmsRetriever.EXTRA_SMS_MESSAGE))
              if (code != null) {
                Log.i(TAG, "Received verification code via SMS retriever.")
                trySend(code)
              } else {
                Log.w(TAG, "Could not parse verification code from retrieved SMS.")
              }
            }
            CommonStatusCodes.TIMEOUT -> Log.w(TAG, "Timed out waiting for the verification SMS to arrive.")
            else -> Log.w(TAG, "SMS retriever broadcast had an unexpected status code: ${status?.statusCode}")
          }
        }
      }

      ContextCompat.registerReceiver(context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION), SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
      awaitClose { context.unregisterReceiver(receiver) }
    }
  }

  private val _state = MutableStateFlow(VerificationCodeState())
  val state: StateFlow<VerificationCodeState> = _state.asStateFlow()

  init {
    _state
      .onEach { Log.d(TAG, "[State] $it") }
      .launchIn(viewModelScope)

    parentState
      .onEach { onEvent(VerificationCodeScreenEvents.ParentStateChanged(it)) }
      .launchIn(viewModelScope)

    viewModelScope.launch {
      smsCodeEvents.collect { code ->
        onEvent(VerificationCodeScreenEvents.CodeAutoFilled(code))
      }
    }
  }

  override suspend fun processEvent(event: VerificationCodeScreenEvents) {
    applyEvent(_state.value, event) { _state.value = it }
  }

  @VisibleForTesting
  suspend fun applyEvent(state: VerificationCodeState, event: VerificationCodeScreenEvents, stateEmitter: (VerificationCodeState) -> Unit) {
    val result = when (event) {
      is VerificationCodeScreenEvents.ParentStateChanged -> applyParentState(state, event.parentState)
      is VerificationCodeScreenEvents.CodeEntered -> submitCode(state, event.code, stateEmitter)
      is VerificationCodeScreenEvents.DigitChanged -> applyDigitChanged(state, event.index, event.value, stateEmitter)
      is VerificationCodeScreenEvents.CodeAutoFilled -> state.copy(autoFillCode = event.code)
      is VerificationCodeScreenEvents.ConsumeAutoFillCode -> state.copy(autoFillCode = null)
      is VerificationCodeScreenEvents.WrongNumber -> state.also { parentEventEmitter.navigateTo(RegistrationRoute.PhoneNumberEntry) }
      is VerificationCodeScreenEvents.SessionExpiredDialogDismissed -> {
        // Tellomi（tellomi/tellomi#1214，taishi 审查 b8-v2 不阻塞 1）：会话已失效时先清掉父状态里的旧会话（号码保留），
        // 手机号页再点「下一步」才会开新会话；不清的话会复用旧会话 → 404 → 整个流程被重置回欢迎页，没有任何提示。
        // 「验证码已不能用」那种会话还在，照旧复用。
        // 发 SessionExpired 的同时记下「正在因会话过期退回」：父状态马上会变成没有会话，本页 ViewModel 在出栈动画结束前还在收，
        // 不能把它当成要重置整个流程（taishi 审查 b19 要改 1）。
        val leavingForExpiredSession = state.dialogs.sessionExpired
        if (leavingForExpiredSession) {
          parentEventEmitter(RegistrationFlowEvent.SessionExpired)
        }
        state.copy(
          dialogs = state.dialogs.copy(sessionExpired = false, codeNoLongerValid = false),
          leavingForExpiredSession = state.leavingForExpiredSession || leavingForExpiredSession
        ).also { parentEventEmitter.navigateBack() }
      }
      is VerificationCodeScreenEvents.TellomiSuspendedDialogDismissed -> state.copy(dialogs = state.dialogs.copy(tellomiSuspended = null))
      is VerificationCodeScreenEvents.ResendSms -> applyResendCode(state, VerificationCodeTransport.SMS)
      is VerificationCodeScreenEvents.CallMe -> applyResendCode(state, VerificationCodeTransport.VOICE)
      is VerificationCodeScreenEvents.HavingTrouble -> state.copy(showContactSupportSheet = true)
      is VerificationCodeScreenEvents.DismissContactSupport -> state.copy(showContactSupportSheet = false)
      is VerificationCodeScreenEvents.ContactSupportDialog -> state.copy(showContactSupportDialog = true)
      is VerificationCodeScreenEvents.DismissContactSupportDialog -> state.copy(showContactSupportDialog = false)
      is VerificationCodeScreenEvents.NetworkErrorSnackbarDismissed -> state.copy(snackbars = state.snackbars.copy(networkError = false))
      is VerificationCodeScreenEvents.UnknownErrorSnackbarDismissed -> state.copy(snackbars = state.snackbars.copy(unknownError = false))
      is VerificationCodeScreenEvents.RateLimitedSnackbarDismissed -> state.copy(snackbars = state.snackbars.copy(rateLimitedRetryAfter = null))
      is VerificationCodeScreenEvents.NetworkErrorDialogDismissed -> state.copy(dialogs = state.dialogs.copy(networkError = false))
      is VerificationCodeScreenEvents.UnknownErrorDialogDismissed -> state.copy(dialogs = state.dialogs.copy(unknownError = false))
      is VerificationCodeScreenEvents.RateLimitedDialogDismissed -> state.copy(dialogs = state.dialogs.copy(rateLimitedRetryAfter = null))
      is VerificationCodeScreenEvents.UnableToSendSmsDialogDismissed -> state.copy(dialogs = state.dialogs.copy(unableToSendSms = false))
      is VerificationCodeScreenEvents.CouldNotRequestCodeWithSelectedTransportDialogDismissed -> state.copy(dialogs = state.dialogs.copy(couldNotRequestCodeWithSelectedTransport = false))
      is VerificationCodeScreenEvents.ProviderRejectedDialogDismissed -> state.copy(dialogs = state.dialogs.copy(providerRejectedTransport = null))
      is VerificationCodeScreenEvents.IncorrectVerificationCodeSnackbarDismissed -> state.copy(snackbars = state.snackbars.copy(incorrectVerificationCode = false))
      is VerificationCodeScreenEvents.RegistrationErrorSnackbarDismissed -> state.copy(snackbars = state.snackbars.copy(registrationError = false))
      is VerificationCodeScreenEvents.CountdownTick -> applyCountdownTick(state)
      is VerificationCodeScreenEvents.Foregrounded -> applyForegrounded(state)
    }
    stateEmitter(result)
  }

  /**
   * If the in-progress registration data has grown older than [IN_PROGRESS_DATA_TIMEOUT], the verification session has
   * likely expired server-side. Rather than let the user enter a code only to fail, restart the flow from the beginning.
   */
  private suspend fun applyForegrounded(state: VerificationCodeState): VerificationCodeState {
    val lastUpdated = repository.getInProgressRegistrationDataLastUpdated() ?: return state
    val age = (clock() - lastUpdated).milliseconds

    if (age >= IN_PROGRESS_DATA_TIMEOUT) {
      Log.w(TAG, "[Foregrounded] In-progress registration data is stale (${age.inWholeMilliseconds}ms old). Restarting the flow.")
      parentEventEmitter(RegistrationFlowEvent.ResetState)
      return state
    }

    return recomputeCountdowns(state)
  }

  /**
   * Tellomi（tellomi/tellomi#1214，ADR-0051 §二「App 回前台重算」，taishi 审查 b8）：倒计时靠界面每秒减一，App 在后台
   * 被系统冻结时就停住，回来显示的剩余时间比实际长。回到前台时按请求验证码时记下的截止时刻重算。
   * 只动本来就在倒计时的那一路；没有记下截止时刻（或不是这个号码的）就保持原样，免得把倒计时重置成整段。
   */
  private fun recomputeCountdowns(state: VerificationCodeState): VerificationCodeState {
    val parent = parentState.value
    val now = clock().milliseconds

    fun remaining(request: VerificationCodeRequest?, current: Duration?): Duration? {
      if (current == null) {
        return null
      }
      val deadline = request?.takeIf { it.e164 == parent.sessionE164 }?.nextAllowedRequestTime?.milliseconds ?: return current
      return (deadline - now).coerceAtLeast(0.seconds)
    }

    return state.copy(
      rateLimits = state.rateLimits.copy(
        smsResendTimeRemaining = remaining(parent.lastSmsVerificationCodeRequest, state.rateLimits.smsResendTimeRemaining),
        callRequestTimeRemaining = remaining(parent.lastCallVerificationCodeRequest, state.rateLimits.callRequestTimeRemaining)
      )
    )
  }

  private fun applyParentState(state: VerificationCodeState, parentState: RegistrationFlowState): VerificationCodeState {
    // Tellomi（tellomi/tellomi#1214，taishi 审查 b19 要改 1）：关掉「会话已过期」框以后，父状态先清了会话、号码还在，
    // 本页正在退回手机号页；这时不重置，否则号码也跟着清掉、整个流程回到欢迎页。
    if (parentState.sessionMetadata == null && parentState.sessionE164 != null && state.leavingForExpiredSession) {
      return state
    }

    if (parentState.sessionMetadata == null || parentState.sessionE164 == null) {
      Log.w(TAG, "Parent state is missing session metadata or e164! Resetting.")
      parentEventEmitter(RegistrationFlowEvent.ResetState)
      return state
    }

    val sessionChanged = state.sessionMetadata?.id != parentState.sessionMetadata.id

    val rateLimits = if (sessionChanged) {
      initializeRateLimits(parentState.sessionMetadata, parentState)
    } else {
      state.rateLimits
    }

    return state.copy(
      sessionMetadata = parentState.sessionMetadata,
      e164 = parentState.sessionE164,
      rateLimits = rateLimits,
      leavingForExpiredSession = false
    )
  }

  /**
   * Decrements countdown timers by 1 second, ensuring they don't go below 0.
   */
  private fun applyCountdownTick(state: VerificationCodeState): VerificationCodeState {
    return state.copy(
      rateLimits = SmsAndCallRateLimits(
        smsResendTimeRemaining = state.rateLimits.smsResendTimeRemaining?.minus(1.seconds)?.coerceAtLeast(0.seconds),
        callRequestTimeRemaining = state.rateLimits.callRequestTimeRemaining?.minus(1.seconds)?.coerceAtLeast(0.seconds)
      )
    )
  }

  /**
   * Interprets the raw [value] reported by the digit field at [index] and updates the digits and focus accordingly:
   *
   * - an empty [value] is a backspace, deleting a digit and moving focus back
   * - a single digit is recorded and focus advances, submitting once the full code is present
   * - multi-character input (e.g. a pasted "123-456" or an auto-filled SMS code) populates every field at once and
   *   submits, all in this single reducer pass
   */
  private suspend fun applyDigitChanged(
    inputState: VerificationCodeState,
    index: Int,
    value: String,
    stateEmitter: (VerificationCodeState) -> Unit
  ): VerificationCodeState {
    check(index in inputState.digits.indices) { "[DigitChanged] Out of bounds index $index." }

    if (value.isEmpty()) {
      return deleteDigit(inputState, index)
    }

    val currentValue = inputState.digits[index]
    val remainder = if (currentValue.isNotEmpty()) value.replaceFirst(currentValue, "") else value
    val addedDigits = remainder.filter { it.isDigit() }

    // Tellomi（tellomi/tellomi#1214）：错码提示是行内的，重新输入就收起。
    val state = if (addedDigits.isNotEmpty() && inputState.snackbars.incorrectVerificationCode) {
      inputState.copy(snackbars = inputState.snackbars.copy(incorrectVerificationCode = false))
    } else {
      inputState
    }

    return when {
      addedDigits.isEmpty() -> state

      addedDigits.length == 1 -> {
        val updated = state.copy(
          digits = state.digits.toMutableList().also { it[index] = addedDigits },
          focusedDigitIndex = (index + 1).coerceAtMost(CODE_LENGTH - 1)
        )

        if (updated.isComplete && !updated.isSubmittingCode) {
          submitCode(updated, updated.code, stateEmitter)
        } else {
          updated
        }
      }

      addedDigits.length == CODE_LENGTH -> applyFullCode(state, addedDigits, stateEmitter)

      // Tellomi（tellomi/tellomi#1214）：整条短信粘进来时里面还有别的数字（「5 分钟内有效」），先从原文里找完整的验证码。
      else -> applyFullCode(state, TellomiRegistration.verificationCodeIn(remainder) ?: addedDigits, stateEmitter)
    }
  }

  /**
   * Populates every digit field from a full pasted or auto-filled [code] in a single reducer pass and submits it.
   * Multi-character input that isn't a complete code is ignored.
   */
  private suspend fun applyFullCode(
    state: VerificationCodeState,
    code: String,
    stateEmitter: (VerificationCodeState) -> Unit
  ): VerificationCodeState {
    if (code.length != CODE_LENGTH) {
      Log.w(TAG, "[DigitChanged] Ignoring multi-character input containing ${code.length} digits.")
      return state
    }

    val updated = state.copy(
      digits = code.map { it.toString() },
      focusedDigitIndex = CODE_LENGTH - 1
    )

    return if (!updated.isSubmittingCode) {
      submitCode(updated, updated.code, stateEmitter)
    } else {
      updated
    }
  }

  /**
   * Deletes the digit at [index] (or the previous one, if [index] is already empty), shifts any following digits left
   * to fill the gap, and moves focus back.
   */
  private fun deleteDigit(state: VerificationCodeState, index: Int): VerificationCodeState {
    val deleteAt = if (state.digits[index].isNotEmpty()) index else index - 1
    if (deleteAt < 0) {
      return state
    }

    val newDigits = state.digits.toMutableList().apply {
      for (j in deleteAt until CODE_LENGTH - 1) {
        this[j] = this[j + 1]
      }
      this[CODE_LENGTH - 1] = ""
    }

    return state.copy(digits = newDigits, focusedDigitIndex = (index - 1).coerceAtLeast(0))
  }

  /**
   * Emits an intermediate submitting state and then runs the submission, clearing the submitting flag when done.
   */
  private suspend fun submitCode(state: VerificationCodeState, code: String, stateEmitter: (VerificationCodeState) -> Unit): VerificationCodeState {
    stateEmitter(state.copy(isSubmittingCode = true))
    return applyCodeEntered(state, code).copy(isSubmittingCode = false)
  }

  private suspend fun applyCodeEntered(inputState: VerificationCodeState, code: String): VerificationCodeState {
    var state = inputState
    var sessionMetadata = state.sessionMetadata ?: return state.also {
      parentEventEmitter(RegistrationFlowEvent.ResetState)
    }

    // TODO should we be checking on whether we need to do more captcha stuff?

    val result = repository.submitVerificationCode(sessionMetadata.id, code)

    sessionMetadata = when (result) {
      is RequestResult.Success -> {
        result.result
      }
      is RequestResult.NonSuccess -> {
        when (val error = result.error) {
          is SubmitVerificationCodeError.InvalidSessionIdOrVerificationCode -> {
            Log.w(TAG, "[SubmitCode] Invalid sessionId or verification code entered. This is distinct from an *incorrect* verification code. Body: ${error.message}")
            val newAttempts = state.incorrectCodeAttempts + 1
            return state.copy(snackbars = state.snackbars.copy(incorrectVerificationCode = true), incorrectCodeAttempts = newAttempts, digits = VerificationCodeState.emptyDigits(), focusedDigitIndex = 0)
          }
          is SubmitVerificationCodeError.SessionNotFound -> {
            // Tellomi（tellomi/tellomi#1214）：上游直接退回手机号页，用户不知道为什么；先弹框说「验证已过期」，关掉再退回。
            Log.w(TAG, "[SubmitCode] Session not found: ${error.message}. Telling the user it expired before navigating back.")
            return state.copy(dialogs = state.dialogs.copy(sessionExpired = true))
          }
          is SubmitVerificationCodeError.SessionAlreadyVerifiedOrNoCodeRequested -> {
            if (error.session.verified) {
              Log.i(TAG, "[SubmitCode] Session already had number verified, continuing with registration.")
              error.session
            } else {
              Log.w(TAG, "[SubmitCode] No code was requested for this session? Need to have user re-submit.")
              // Tellomi（tellomi/tellomi#1214）：同上，先说清楚再退回。
              return state.copy(dialogs = state.dialogs.copy(codeNoLongerValid = true))
            }
          }
          is SubmitVerificationCodeError.RateLimited -> {
            Log.w(TAG, "[SubmitCode] Rate limited  (retryAfter: ${error.retryAfter}).")
            return state.copy(snackbars = state.snackbars.copy(rateLimitedRetryAfter = error.retryAfter))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[SubmitCode] Network error.", result.networkError)
        return state.copy(snackbars = state.snackbars.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[SubmitCode] Unknown error when submitting verification code.", result.cause)
        return state.copy(snackbars = state.snackbars.copy(unknownError = true))
      }
    }

    state = state.copy(sessionMetadata = sessionMetadata)

    if (!sessionMetadata.verified) {
      Log.w(TAG, "[SubmitCode] Verification code was incorrect.")
      val newAttempts = state.incorrectCodeAttempts + 1
      return state.copy(snackbars = state.snackbars.copy(incorrectVerificationCode = true), incorrectCodeAttempts = newAttempts, digits = VerificationCodeState.emptyDigits(), focusedDigitIndex = 0)
    }

    parentEventEmitter(RegistrationFlowEvent.VerificationCodeAccepted(code))

    // Tellomi（ADR-0072 §4.2）：本机是主动退出登录的，这个验证会话只用来证明本人，到这里就够了——不往下注册。
    val loggedOutAccount = parentState.value.preExistingRegistrationData?.takeIf { it.loggedOut }
    if (loggedOutAccount != null) {
      return applyReloginVerified(state, loggedOutAccount)
    }

    // Attempt to register
    val registerResult = repository.registerAccountWithSession(e164 = state.e164, sessionId = sessionMetadata.id, skipDeviceTransfer = true)

    return when (registerResult) {
      is RequestResult.Success -> {
        val (response, keyMaterial, aci) = registerResult.result

        parentEventEmitter(RegistrationFlowEvent.Registered(aci, keyMaterial.accountEntropyPool, response.storageCapable, phoneNumberless = response.e164 == null))

        val pendingRestore = pendingRestoreNavigation()
        when {
          pendingRestore != null -> {
            Log.i(TAG, "[Register] A restore was deferred until after SMS verification. Resuming it now.")
            parentEventEmitter.navigateTo(pendingRestore)
          }
          response.reregistration && parentState.value.pendingRestoreOption == null && parentState.value.preExistingRegistrationData == null -> parentEventEmitter.navigateTo(RegistrationRoute.ArchiveRestoreSelection.forPostRegisterWithPinUnknown())
          response.storageCapable -> parentEventEmitter.navigateTo(RegistrationRoute.PinEntryForSvrRestore)
          else -> parentEventEmitter.navigateTo(RegistrationRoute.PinCreate)
        }
        state
      }
      is RequestResult.NonSuccess -> {
        when (val error = registerResult.error) {
          is RegisterAccountError.SessionNotFoundOrNotVerified -> {
            // Tellomi（tellomi/tellomi#1214，taishi 审查 b19 不阻塞 1）：上游一声不响地退回，手机号页还会复用这个死会话、再被重置回欢迎页。
            // 和提交验证码 / 重发时一样先弹框说清楚，关掉后走 SessionExpired（清会话、留号码）再退回。
            Log.w(TAG, "[Register] Session not found or not verified: ${error.message}. Telling the user it expired before navigating back.")
            state.copy(dialogs = state.dialogs.copy(sessionExpired = true))
          }
          is RegisterAccountError.DeviceTransferPossible -> {
            error("[Register] Got told a device transfer is possible. We should never get into this state. Resetting.")
          }
          is RegisterAccountError.RegistrationLock -> {
            Log.w(TAG, "[Register] Reglocked.")
            parentEventEmitter.navigateTo(
              RegistrationRoute.PinEntryForRegistrationLock(
                timeRemaining = error.data.timeRemaining,
                svrCredentials = error.data.svr2Credentials
              )
            )
            state
          }
          is RegisterAccountError.RateLimited -> {
            Log.w(TAG, "[Register] Rate limited (retryAfter: ${error.retryAfter}).")
            state.copy(snackbars = state.snackbars.copy(rateLimitedRetryAfter = error.retryAfter))
          }
          is RegisterAccountError.InvalidRequest -> {
            // Tellomi（ADR-0078 §3.4）：被暂停的号码回 422 + 说明，弹窗说清楚；别的 422 照旧
            val suspension = TellomiRegistration.suspensionNotice(error.message)
            if (suspension != null) {
              Log.w(TAG, "[Register] This number is suspended.")
              state.copy(dialogs = state.dialogs.copy(tellomiSuspended = suspension))
            } else {
              Log.w(TAG, "[Register] Invalid request when registering account: ${error.message}")
              state.copy(snackbars = state.snackbars.copy(registrationError = true))
            }
          }
          is RegisterAccountError.RegistrationRecoveryPasswordIncorrect -> {
            error("[Register] Got told the registration recovery password incorrect. We don't use the RRP in this flow, and should never get this error. Resetting. Message: ${error.message}")
          }
          is RegisterAccountError.InvalidReceiptCredentialPresentation,
          RegisterAccountError.TotpMissingOrIncorrect,
          RegisterAccountError.PostQuantumRatchetRequired -> {
            Log.w(TAG, "[Register] Unexpected error when registering account: $error")
            state.copy(snackbars = state.snackbars.copy(registrationError = true))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[Register] Network error.", registerResult.networkError)
        state.copy(snackbars = state.snackbars.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[Register] Unknown error when registering account.", registerResult.cause)
        state.copy(snackbars = state.snackbars.copy(unknownError = true))
      }
    }
  }

  /**
   * Tellomi（ADR-0072 §4.2）：已退出登录的账号，号码验证通过之后的去向。**不调 `POST /v1/registration`**——同一个号码再注册，
   * 服务端走 reclaimAccount，排队的消息全清。
   *
   * - 会话的号码必须就是本机账号的号码；手机号页已经把别的号码拦去「清空本机」，这里是兜底，对不上就报错、什么都不做。
   * - 开了注册锁：去本机核对 PIN（[RegistrationRoute.PinEntryForRelogin]）。
   * - 否则直接解锁本机，结束流程。
   */
  private suspend fun applyReloginVerified(state: VerificationCodeState, loggedOutAccount: PreExistingRegistrationData): VerificationCodeState {
    if (loggedOutAccount.e164 != state.e164) {
      Log.w(TAG, "[Relogin] The verified number doesn't match the logged-out account. Not registering and not unlocking.")
      return state.copy(snackbars = state.snackbars.copy(registrationError = true))
    }

    if (loggedOutAccount.registrationLockEnabled) {
      Log.i(TAG, "[Relogin] Number verified. Registration lock is on, so checking the PIN locally before unlocking.")
      parentEventEmitter.navigateTo(RegistrationRoute.PinEntryForRelogin)
      return state
    }

    Log.i(TAG, "[Relogin] Number verified. Unlocking this device without re-registering.")
    repository.completeRelogin()
    parentEventEmitter.navigateTo(RegistrationRoute.FullyComplete)
    return state
  }

  /**
   * If the user pre-selected a restore (see [RegistrationFlowState.pendingRestoreOption]) and it hasn't run yet,
   * returns the restore screen to resume it now that the account is registered; otherwise null. Used to pick a
   * restore back up after it was deferred to SMS verification (e.g. a local backup that belongs to a different account).
   */
  private fun pendingRestoreNavigation(): RegistrationRoute? {
    val aep = parentState.value.unverifiedRestoredAep ?: return null
    return when (parentState.value.pendingRestoreOption) {
      PendingRestoreOption.LocalBackup -> RegistrationRoute.LocalBackupRestore(isPreRegistration = false, aep = aep)
      PendingRestoreOption.RemoteBackup -> RegistrationRoute.RemoteRestore(aep)
      null -> null
    }
  }

  private suspend fun applyResendCode(
    state: VerificationCodeState,
    transport: VerificationCodeTransport
  ): VerificationCodeState {
    if (state.sessionMetadata == null) {
      parentEventEmitter(RegistrationFlowEvent.ResetState)
      return state
    }

    val result = repository.requestVerificationCode(
      sessionId = state.sessionMetadata.id,
      smsAutoRetrieveCodeSupported = repository.registerSmsListener(),
      transport = transport
    )

    return when (result) {
      is RequestResult.Success -> {
        Log.i(TAG, "[RequestCode][$transport] Successfully requested verification code.")
        parentEventEmitter(RegistrationFlowEvent.VerificationCodeRequested.from(state.e164, transport, result.result, clock()))
        parentEventEmitter(RegistrationFlowEvent.SessionUpdated(result.result))
        state.copy(
          sessionMetadata = result.result,
          rateLimits = computeRateLimits(result.result)
        )
      }
      is RequestResult.NonSuccess -> {
        when (val error = result.error) {
          is RequestVerificationCodeError.InvalidRequest -> {
            Log.w(TAG, "[RequestCode][$transport] Invalid request: ${error.message}")
            state.copy(dialogs = state.dialogs.copy(unknownError = true))
          }
          is RequestVerificationCodeError.RateLimited -> {
            Log.w(TAG, "[RequestCode][$transport] Rate limited (retryAfter: ${error.retryAfter}).")
            // Tellomi（tellomi/tellomi#1214，taishi 审查 b8-v2 不阻塞 2）：照手机号页 navigateToCodeEntryAfterRateLimit 记下截止时刻
            // （ADR-0051 §二 F：被限流时采用 retry_after）。不记的话父状态里还是上一次的截止时刻，正是「重新发送」刚亮起那一刻，
            // 回到前台按它重算，倒计时就成了 0，按钮提前亮起，再点又是「请稍后再试」。
            val now = clock()
            val retryAt = now + error.retryAfter.inWholeMilliseconds
            val nextSmsAllowedTimestamp = if (transport == VerificationCodeTransport.SMS) retryAt else error.session.nextSms?.let { now + it.seconds.inWholeMilliseconds }
            val nextCallAllowedTimestamp = if (transport == VerificationCodeTransport.VOICE) retryAt else error.session.nextCall?.let { now + it.seconds.inWholeMilliseconds }
            parentEventEmitter(
              RegistrationFlowEvent.VerificationCodeRequested(
                e164 = state.e164,
                nextSmsAllowedTimestamp = nextSmsAllowedTimestamp,
                nextCallAllowedTimestamp = nextCallAllowedTimestamp
              )
            )
            parentEventEmitter(RegistrationFlowEvent.SessionUpdated(error.session))
            // 界面上的倒计时也从这一对截止时刻算（taishi 审查 b19 不阻塞 2）。原来用会话的 nextSms / nextCall，
            // 和 retryAfter 不等时，回前台按截止时刻重算会跳一下。
            state.copy(
              dialogs = state.dialogs.copy(rateLimitedRetryAfter = error.retryAfter),
              sessionMetadata = error.session,
              rateLimits = SmsAndCallRateLimits(
                smsResendTimeRemaining = nextSmsAllowedTimestamp?.let { (it - now).milliseconds.coerceAtLeast(0.seconds) },
                callRequestTimeRemaining = nextCallAllowedTimestamp?.let { (it - now).milliseconds.coerceAtLeast(0.seconds) }
              )
            )
          }
          is RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport -> {
            Log.w(TAG, "[RequestCode][$transport] Could not fulfill with requested transport.")
            parentEventEmitter(RegistrationFlowEvent.SessionUpdated(error.session))
            state.copy(
              dialogs = state.dialogs.copy(couldNotRequestCodeWithSelectedTransport = true),
              sessionMetadata = error.session,
              rateLimits = computeRateLimits(error.session)
            )
          }
          is RequestVerificationCodeError.InvalidSessionId -> {
            // Tellomi（tellomi/tellomi#1214，taishi 审查 b8）：等短信的人点「重新发送」正是最容易撞上过期的时候，
            // 先说清楚再退回（关掉对话框走 SessionExpiredDialogDismissed → navigateBack），不再一声不响地跳走。
            Log.w(TAG, "[RequestCode][$transport] Invalid session ID: ${error.message}. Explaining, then navigating back to phone number entry.")
            state.copy(dialogs = state.dialogs.copy(sessionExpired = true))
          }
          is RequestVerificationCodeError.MissingRequestInformationOrAlreadyVerified -> {
            Log.w(TAG, "[RequestCode][$transport] Missing request information or already verified.")
            parentEventEmitter(RegistrationFlowEvent.SessionUpdated(error.session))
            state.copy(
              dialogs = state.dialogs.copy(unableToSendSms = true),
              sessionMetadata = error.session,
              rateLimits = computeRateLimits(error.session)
            )
          }
          is RequestVerificationCodeError.SessionNotFound -> {
            // Tellomi（tellomi/tellomi#1214，taishi 审查 b8）：同上，先说清楚再退回。
            Log.w(TAG, "[RequestCode][$transport] Session not found: ${error.message}. Explaining, then navigating back to phone number entry.")
            state.copy(dialogs = state.dialogs.copy(sessionExpired = true))
          }
          is RequestVerificationCodeError.ThirdPartyServiceError -> {
            Log.w(TAG, "[RequestCode][$transport] Third party service error. ${error.data}")
            state.copy(dialogs = state.dialogs.copy(providerRejectedTransport = transport))
          }
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[RequestCode][$transport] Network error.", result.networkError)
        state.copy(dialogs = state.dialogs.copy(networkError = true))
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[RequestCode][$transport] Unknown application error.", result.cause)
        state.copy(dialogs = state.dialogs.copy(unknownError = true))
      }
    }
  }

  /**
   * Builds the countdowns from a freshly-returned session. A null [SessionMetadata.nextSms]/[SessionMetadata.nextCall]
   * means the server won't permit that request, which we surface as a null remaining time (an unavailable button)
   * rather than a countdown.
   */
  private fun computeRateLimits(session: SessionMetadata): SmsAndCallRateLimits {
    return SmsAndCallRateLimits(
      smsResendTimeRemaining = session.nextSms?.seconds?.coerceAtLeast(0.seconds),
      callRequestTimeRemaining = session.nextCall?.seconds?.coerceAtLeast(0.seconds)
    )
  }

  /**
   * Seeds the resend countdowns when we first see a session. Prefers the absolute timestamps recorded when the codes
   * were actually requested (which remain accurate across leaving and re-entering this screen), falling back to
   * anchoring the session's relative nextSms/nextCall values to now. A null value with no recorded request means the
   * transport is unavailable, surfaced as a null remaining time.
   */
  private fun initializeRateLimits(session: SessionMetadata, parentState: RegistrationFlowState): SmsAndCallRateLimits {
    val now = clock().milliseconds

    val nextSmsAvailableAt: Duration? = parentState.lastSmsVerificationCodeRequest
      ?.takeIf { it.e164 == parentState.sessionE164 }
      ?.nextAllowedRequestTime?.milliseconds
      ?: session.nextSms?.let { now + it.seconds }

    val nextCallAvailableAt: Duration? = parentState.lastCallVerificationCodeRequest
      ?.takeIf { it.e164 == parentState.sessionE164 }
      ?.nextAllowedRequestTime?.milliseconds
      ?: session.nextCall?.let { now + it.seconds }

    return SmsAndCallRateLimits(
      smsResendTimeRemaining = nextSmsAvailableAt?.minus(now)?.coerceAtLeast(0.seconds),
      callRequestTimeRemaining = nextCallAvailableAt?.minus(now)?.coerceAtLeast(0.seconds)
    )
  }
}
