package org.thoughtcrime.securesms.components.settings.app

import androidx.compose.runtime.Immutable
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.RemoteConfig

@Immutable
data class AppSettingsState(
  val isPrimaryDevice: Boolean,
  val unreadPaymentsCount: Int,
  val hasExpiredGiftBadge: Boolean,
  val allowUserToGoToDonationManagementScreen: Boolean,
  val userUnregistered: Boolean,
  val clientDeprecated: Boolean,
  val showInternalPreferences: Boolean = RemoteConfig.internalUser,
  val showPayments: Boolean = SignalStore.payments.paymentsAvailability.showPaymentsMenu(),
  val showAppUpdates: Boolean = Environment.IS_NIGHTLY,
  val backupFailureState: BackupFailureState = BackupFailureState.NONE,
  /** Tellomi（tellomi/tellomi#1491）：有新版本、还没在「关于」里看过——「关于 Tellomi」一行上一个小红点。 */
  val hasUnseenUpdate: Boolean = false
) {
  fun isRegisteredAndUpToDate(): Boolean {
    return !userUnregistered && !clientDeprecated
  }
}
