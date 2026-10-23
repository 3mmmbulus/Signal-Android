/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.rx3.await
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.NewConversationViewModel
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import org.thoughtcrime.securesms.recipients.ui.RecipientPickerCallbacks
import org.thoughtcrime.securesms.recipients.ui.RecipientSelection

/**
 * Tellomi（tellomi/tellomi#1355、#1360）：底栏「好友」。和 iOS 的 `TellomiFriendsViewController` 一样，
 * 复用「新建会话」里的联系人列表（[RecipientPicker] + [NewConversationViewModel]），点一个人就进和他的聊天。
 * 只列联系人和自己，不列群组（群组在「聊天」里）。
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
    val intent = ConversationIntents.createBuilder(context, recipientId, -1L).await().build()
    context.startActivity(intent)
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
    displayModes = setOf(RecipientPicker.DisplayMode.PUSH, RecipientPicker.DisplayMode.SELF),
    includeRecents = false,
    focusAndShowKeyboard = false,
    callbacks = callbacks,
    modifier = modifier.fillMaxSize()
  )
}
