/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import org.signal.core.ui.compose.CollectActions
import org.signal.core.ui.compose.LocalChatColorProvider
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.index.ContactIndexRepository
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.contactshare.ContactShareEditActivityV2
import org.thoughtcrime.securesms.contactshare.PickPhoneNumber
import org.thoughtcrime.securesms.contactshare.SharedContactSource
import org.thoughtcrime.securesms.contactshare.screens.editname.ContactNameParts
import org.thoughtcrime.securesms.contactshare.screens.editname.EditContactNameEvent
import org.thoughtcrime.securesms.contactshare.screens.editname.EditContactNameResult
import org.thoughtcrime.securesms.contactshare.screens.editname.EditContactNameScreen
import org.thoughtcrime.securesms.contactshare.screens.editname.EditContactNameViewModel
import org.thoughtcrime.securesms.contactshare.screens.selectcontact.ContactIndexSource
import org.thoughtcrime.securesms.contactshare.screens.selectcontact.SelectContactAction
import org.thoughtcrime.securesms.contactshare.screens.selectcontact.SelectContactEvent
import org.thoughtcrime.securesms.contactshare.screens.selectcontact.SelectContactScreen
import org.thoughtcrime.securesms.contactshare.screens.selectcontact.SelectContactViewModel
import org.thoughtcrime.securesms.contactshare.screens.share.ShareContactAction
import org.thoughtcrime.securesms.contactshare.screens.share.ShareContactEvent
import org.thoughtcrime.securesms.contactshare.screens.share.ShareContactRepository
import org.thoughtcrime.securesms.contactshare.screens.share.ShareContactScreen
import org.thoughtcrime.securesms.contactshare.screens.share.ShareContactViewModel
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.rememberRecipientField
import org.signal.core.ui.R as CoreUiR
import org.signal.mediasend.R as MediaSendR

/**
 * Tellomi（2026-10-16，owner 定 `contactSharingV2` 强制打开）：附件 Sheet 里的「联系人」页。照 Telegram 两端
 * （安卓 `ChatAttachAlert.showLayout(contactsLayout)`，iOS `ChatControllerOpenAttachmentMenu` `.contact`）在同一个 Sheet 里换页，
 * 不再收起 Sheet 另开整屏；iOS 同一页见 Signal-iOS #233。
 *
 * 两步，都在这一页里：先是上游的联系人列表（[SelectContactScreen]，通讯录 + Signal 联系人，没有通讯录权限也能用），
 * 点一个人换成上游的「分享联系人」确认页（[ShareContactScreen]，可改名字、勾要分享的字段）；确认页返回回到列表，
 * 「发送」把联系人卡带回会话页（`onSendContacts`，同整屏那条路），Sheet 收起；列表左上角 ✕ 收起整个 Sheet。
 * 两步的接法同上游 `SelectContactFragment`、`ContactShareEditFragment`（那两个 Fragment 仍是会话里别的入口在用的整屏版）。
 */
@Composable
internal fun TellomiAttachmentContactPage(
  recipientId: Long?,
  onClose: () -> Unit,
  onResult: (Bundle) -> Unit,
  modifier: Modifier = Modifier
) {
  var picked: SharedContactSource? by rememberSaveable { mutableStateOf(null) }

  // 页面在 Sheet 里，顶上不挨状态栏、底下是 dock：不让上游页面的顶栏 / Scaffold 再垫系统栏的高度
  Box(modifier = modifier.consumeWindowInsets(WindowInsets.systemBars).testTag(TellomiAttachmentSheetPages.CONTACT_PAGE_TEST_TAG)) {
    var returnedFromShare by rememberSaveable { mutableStateOf(false) }
    val source = picked
    if (source == null) {
      SelectStep(
        onClose = onClose,
        onPicked = { picked = it },
        returnedFromShare = returnedFromShare,
        onReturnHandled = { returnedFromShare = false }
      )
    } else {
      ShareStep(
        source = source,
        recipientId = recipientId,
        onBack = {
          picked = null
          returnedFromShare = true
        },
        onSend = { contact -> onResult(contactsResult(contact)) }
      )
    }
  }
}

/** 联系人页发出的结果：同整屏那条路的 `ContactShareEditActivityV2` 结果，会话页按同一个 key 读。 */
internal fun contactsResult(contact: Contact): Bundle {
  return Bundle().apply {
    putParcelableArrayList(ContactShareEditActivityV2.KEY_CONTACTS, arrayListOf(contact))
  }
}

@Composable
private fun SelectStep(
  onClose: () -> Unit,
  onPicked: (SharedContactSource) -> Unit,
  returnedFromShare: Boolean,
  onReturnHandled: () -> Unit
) {
  val context = LocalContext.current
  val viewModel: SelectContactViewModel = viewModel(key = "tellomi-attachment-select-contact") {
    SelectContactViewModel(
      source = ContactIndexSource(ContactIndexRepository(context.applicationContext as Application)),
      savedState = createSavedStateHandle()
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val rows = viewModel.rows.collectAsLazyPagingItems()

  val systemContactPicker = rememberLauncherForActivityResult(PickPhoneNumber) { uri ->
    viewModel.onEvent(SelectContactEvent.SystemContactPicked(uri))
  }

  CollectActions(viewModel.actions) { action ->
    when (action) {
      SelectContactAction.Exit -> onClose()
      SelectContactAction.CouldNotOpenContact -> Toast.makeText(context, R.string.SelectContactScreen__couldnt_open_contact, Toast.LENGTH_SHORT).show()
      is SelectContactAction.ContactResolved -> onPicked(action.source)
      SelectContactAction.LaunchSystemContactPicker -> systemContactPicker.launch(Unit)
    }
  }

  // 点人时上游为了交接置了「加载中」（整屏那条路选完就关页）；从确认页回到列表要撤掉，不然一直转圈
  LaunchedEffect(returnedFromShare) {
    if (returnedFromShare) {
      viewModel.onEvent(SelectContactEvent.TellomiReturnedFromShare)
      onReturnHandled()
    }
  }

  viewModel.contactsPermission.Content()

  SelectContactScreen(
    state = state,
    rows = rows,
    onEvent = viewModel::onEvent,
    navigationIconRes = CoreUiR.drawable.symbol_x_24,
    navigationContentDescription = stringResource(MediaSendR.string.MediaSelectScreen__close)
  )
}

@Composable
private fun ShareStep(source: SharedContactSource, recipientId: Long?, onBack: () -> Unit, onSend: (Contact) -> Unit) {
  val context = LocalContext.current
  // 换一个人就是一份新的确认页状态
  val viewModel: ShareContactViewModel = viewModel(key = "tellomi-attachment-share-contact-${source.hashCode()}") {
    ShareContactViewModel(
      contactSource = source,
      recipientId = recipientId?.let { RecipientId.from(it) },
      repository = ShareContactRepository(),
      savedState = createSavedStateHandle()
    )
  }
  val editorViewModel: EditContactNameViewModel = viewModel(key = "tellomi-attachment-edit-contact-name")
  val state by viewModel.state.collectAsStateWithLifecycle()
  val editorState by editorViewModel.state.collectAsStateWithLifecycle()
  var editingName: ContactNameParts? by rememberSaveable { mutableStateOf(null) }

  CollectActions(viewModel.actions) { action ->
    when (action) {
      ShareContactAction.Exit -> onBack()
      ShareContactAction.InvalidContact -> {
        Toast.makeText(context, R.string.ContactShareEditActivity_invalid_contact, Toast.LENGTH_SHORT).show()
        onBack()
      }
      is ShareContactAction.EditName -> {
        editorViewModel.onEvent(EditContactNameEvent.Initialize(action.parts))
        editingName = action.parts
      }
      is ShareContactAction.Send -> onSend(action.contact)
    }
  }

  CollectActions(editorViewModel.results) { result ->
    if (result is EditContactNameResult.Saved) {
      viewModel.onEvent(ShareContactEvent.NameEdited(result.parts))
    }
    editingName = null
  }

  BackHandler(enabled = editingName != null) {
    editorViewModel.onEvent(EditContactNameEvent.BackClicked)
  }
  BackHandler(enabled = editingName == null) {
    onBack()
  }

  CompositionLocalProvider(
    LocalChatColorProvider provides { id ->
      rememberRecipientField(RecipientId.from(id)) {
        Color(chatColors.asSingleColor())
      }
    }
  ) {
    if (editingName == null) {
      ShareContactScreen(state = state, onEvent = viewModel::onEvent)
    } else {
      EditContactNameScreen(state = editorState, onEvent = editorViewModel::onEvent)
    }
  }
}
