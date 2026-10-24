/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.rx3.await
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.main.UsernameQrScannerActivity
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.NewConversationViewModel
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import org.thoughtcrime.securesms.recipients.ui.RecipientPickerCallbacks
import org.thoughtcrime.securesms.recipients.ui.RecipientSelection
import org.thoughtcrime.securesms.recipients.ui.findby.FindByActivity
import org.thoughtcrime.securesms.recipients.ui.findby.FindByMode
import org.thoughtcrime.securesms.util.CommunicationActions
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（tellomi/tellomi#1355、#1360）：底栏「好友」。和 iOS 的 `TellomiFriendsViewController` 一样，
 * 复用「新建会话」里的联系人列表（[RecipientPicker] + [NewConversationViewModel]），点一个人就进和他的聊天。
 *
 * - 只列个人：不列群组（群组在「聊天」里），不列自己（和 iOS 一样）。
 * - 搜索框下面（不在搜索时）：按用户名查找、扫描二维码、邀请好友（系统分享面板发下载链接）。
 * - 会话压在「好友」自己的栈上，返回回到好友（[MainNavigationViewModel.chatsDetailRoot]）。
 */
@Composable
fun TellomiFriendsListPane(
  modifier: Modifier = Modifier,
  viewModel: NewConversationViewModel = viewModel { NewConversationViewModel() }
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(uiState.pendingDestination) {
    val recipientId = uiState.pendingDestination ?: return@LaunchedEffect
    viewModel.clearPendingDestination()
    TellomiFriendsTab.openConversation(context, recipientId)
  }

  val findByUsernameLauncher = rememberLauncherForActivityResult(FindByActivity.Contract()) { recipientId ->
    recipientId?.let { viewModel.openConversation(RecipientSelection.WithId(it)) }
  }
  val qrScanLauncher = rememberLauncherForActivityResult(UsernameQrScannerActivity.Contract()) { result ->
    result?.groupInviteUrl?.let { url -> context.findFragmentActivity()?.let { CommunicationActions.handlePotentialGroupLinkUrl(it, url) } }
    result?.recipientId?.let { viewModel.openConversation(RecipientSelection.WithId(it)) }
  }

  val callbacks = remember(viewModel) {
    RecipientPickerCallbacks(
      listActions = object : RecipientPickerCallbacks.ListActions {
        override fun onSearchQueryChanged(query: String) = viewModel.onSearchQueryChanged(query)
        override suspend fun shouldAllowSelection(selection: RecipientSelection): Boolean = true
        override fun onRecipientSelected(selection: RecipientSelection) = viewModel.openConversation(selection)
        override fun onContactsListReset() = viewModel.clearShouldResetContactsList()
      },
      refresh = object : RecipientPickerCallbacks.Refresh {
        override fun onRefresh() = viewModel.refresh()
      }
    )
  }

  RecipientPicker(
    searchQuery = uiState.searchQuery,
    isRefreshing = uiState.isRefreshingContacts,
    shouldResetContactsList = uiState.shouldResetContactsList,
    displayModes = TellomiFriendsTab.DISPLAY_MODES,
    includeRecents = false,
    focusAndShowKeyboard = false,
    callbacks = callbacks,
    modifier = modifier.fillMaxSize(),
    belowSearchBar = {
      if (TellomiFriendsTab.showsHeaderRows(uiState.searchQuery)) {
        FriendsHeaderRows(
          onFindByUsername = { findByUsernameLauncher.launch(FindByMode.USERNAME) },
          onScanQrCode = { qrScanLauncher.launch(Unit) },
          onInviteFriends = { TellomiFriendsTab.shareInvite(context) }
        )
      }
    }
  )
}

@Composable
private fun FriendsHeaderRows(
  onFindByUsername: () -> Unit,
  onScanQrCode: () -> Unit,
  onInviteFriends: () -> Unit
) {
  Column {
    Rows.TextRow(
      text = stringResource(R.string.ContactSelectionListFragment__find_by_username),
      icon = painterResource(CoreUiR.drawable.symbol_at_24),
      onClick = onFindByUsername
    )
    Rows.TextRow(
      text = stringResource(R.string.FindByActivity__qr_scan_button),
      icon = SignalIcons.QrCode.painter,
      onClick = onScanQrCode
    )
    Rows.TextRow(
      text = stringResource(R.string.AppSettingsFragment__invite_your_friends),
      icon = painterResource(R.drawable.symbol_invite_24),
      onClick = onInviteFriends
    )
  }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
  is FragmentActivity -> this
  is ContextWrapper -> baseContext.findFragmentActivity()
  else -> null
}

object TellomiFriendsTab {

  /** 只列个人（已注册的联系人），不列群、不列自己（和 iOS 一样）。 */
  val DISPLAY_MODES: Set<RecipientPicker.DisplayMode> = setOf(RecipientPicker.DisplayMode.PUSH)

  /** 顶部那几行入口只在不搜索时显示。 */
  fun showsHeaderRows(searchQuery: String): Boolean = searchQuery.isBlank()

  /** 和 iOS 同一句邀请：邀请文字 + 下载链接。 */
  fun inviteText(context: Context): String {
    return context.getString(R.string.TellomiFriends__invite_text, context.getString(R.string.install_url))
  }

  fun shareInvite(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, inviteText(context))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.AppSettingsFragment__invite_your_friends)))
  }

  suspend fun openConversation(context: Context, recipientId: RecipientId) {
    deliverConversation(context, ConversationIntents.createBuilder(context, recipientId, -1L).await())
  }

  /** 在主界面里就发 [MainNavigationEvents.GoToDetail]（压在好友栈上）；不在主界面里退回单独开会话页。 */
  fun deliverConversation(context: Context, builder: ConversationIntents.Builder) {
    val sink = context.findSink()
    if (sink != null) {
      sink.onEvent(MainNavigationEvents.GoToDetail(MainDetailRoute.Conversation(builder.toConversationArgs())))
    } else {
      context.startActivity(builder.build())
    }
  }

  private tailrec fun Context.findSink(): MainNavigationEventSink? = when (this) {
    is MainNavigationEventSink -> this
    is ContextWrapper -> baseContext.findSink()
    else -> null
  }
}
