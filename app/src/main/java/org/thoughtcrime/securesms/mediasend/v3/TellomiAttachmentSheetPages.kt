/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.screens.extra.AttachmentExtraPages
import org.signal.mediasend.screens.extra.AttachmentSheetPageTopBar
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.v2.CreatePollScreen
import org.thoughtcrime.securesms.polls.Poll

/**
 * Tellomi（2026-10-16）：附件 Sheet 里由 app 画的页。照 Telegram 两端，dock 的「投票」不再收起 Sheet、另开整屏对话框，
 * 而是在同一个 Sheet 里换成这一页（安卓 `ChatAttachAlert.showLayout(pollLayout)`，iOS `ChatControllerOpenAttachmentMenu` `.poll`）。
 * 投票的内容沿用上游的 [CreatePollScreen]；发出后带回会话页去发（`ConversationFragment.sendPoll`），✕ 收起整个 Sheet。
 */
object TellomiAttachmentSheetPages : AttachmentExtraPages {

  const val POLL_PAGE_TEST_TAG = "tellomi-attachment-poll-page"

  @Composable
  override fun Content(page: AttachmentPage, onClose: () -> Unit, onResult: (Bundle) -> Unit, modifier: Modifier) {
    when (page) {
      AttachmentPage.POLL -> PollPage(onClose = onClose, onResult = onResult, modifier = modifier)
      AttachmentPage.GALLERY, AttachmentPage.FILES -> Unit
    }
  }
}

@Composable
private fun PollPage(onClose: () -> Unit, onResult: (Bundle) -> Unit, modifier: Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val keyboard = LocalSoftwareKeyboardController.current

  Scaffold(
    topBar = {
      Column {
        AttachmentSheetPageTopBar(title = stringResource(R.string.CreatePollFragment__new_poll), onClose = onClose)
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.surface,
    contentWindowInsets = WindowInsets(0),
    modifier = modifier.testTag(TellomiAttachmentSheetPages.POLL_PAGE_TEST_TAG)
  ) { paddingValues: PaddingValues ->
    CreatePollScreen(
      paddingValues = paddingValues,
      onSend = { question, allowMultiple, options ->
        keyboard?.hide()
        onResult(Poll(question, allowMultiple, options).toBundle())
      },
      onShowErrorSnackbar = { hasQuestion, hasOptions ->
        val message = when {
          !hasQuestion && !hasOptions -> R.string.CreatePollFragment__add_question_option
          !hasQuestion -> R.string.CreatePollFragment__add_question
          else -> R.string.CreatePollFragment__add_option
        }
        scope.launch { snackbarHostState.showSnackbar(context.getString(message)) }
      }
    )
  }
}
