/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.net.Uri
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs

/**
 * Tellomi（tellomi/tellomi#1259 F-6 / F-7 / F-9，判据「转发面板」3、4）：转发网格的选择、搜索与发送。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiForwardGridViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private val saved = target(1, isSavedMessages = true)
  private val lin = target(2)
  private val wang = target(3)
  private val group = target(4, isGroup = true)
  private val far = target(5)
  private val never = target(9)

  private class FakeSender : TellomiForwardGridViewModel.Sender {
    var identities: MutableList<List<IdentityRecord>> = mutableListOf()
    val checked = mutableListOf<List<ContactSearchKey.RecipientSearchKey>>()
    val sent = mutableListOf<Pair<String, List<ContactSearchKey>>>()
    val sentArgs = mutableListOf<List<MultiShareArgs>>()
    var result = TellomiForwardGridViewModel.Result.SUCCESS

    override fun checkIdentities(keys: Set<ContactSearchKey.RecipientSearchKey>, changedSince: Long, callback: (List<IdentityRecord>) -> Unit) {
      checked += keys.toList()
      callback(if (identities.isEmpty()) emptyList() else identities.removeAt(0))
    }

    override fun send(message: String, multiShareArgs: List<MultiShareArgs>, keys: Set<ContactSearchKey>, onResult: (TellomiForwardGridViewModel.Result) -> Unit) {
      sent += message to keys.toList()
      sentArgs += multiShareArgs
      onResult(result)
    }
  }

  private lateinit var sender: FakeSender

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    sender = FakeSender()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun target(id: Long, isSavedMessages: Boolean = false, isGroup: Boolean = false): TellomiForwardTarget {
    val recipient = mockk<Recipient>(relaxed = true)
    every { recipient.id } returns RecipientId.from(id)
    every { recipient.isGroup } returns isGroup
    return TellomiForwardTarget(recipient, isSavedMessages)
  }

  private fun viewModel(
    limit: Int = 5,
    searchResults: TellomiForwardSearchResults = TellomiForwardSearchResults(),
    multiShareArgs: List<MultiShareArgs> = emptyList()
  ): TellomiForwardGridViewModel {
    val repository = mockk<TellomiForwardTargetsRepository>()
    every { repository.load() } returns listOf(saved, lin, wang, group, far)
    every { repository.recentContacts(any()) } answers { firstArg<List<TellomiForwardTarget>>().filter { !it.isSavedMessages && !it.recipient.isGroup } }
    every { repository.search(any(), any()) } returns searchResults
    return TellomiForwardGridViewModel(
      multiShareArgs = multiShareArgs,
      repository = repository,
      selectionLimit = limit,
      identityChangesSince = 0,
      sender = sender,
      ioDispatcher = dispatcher
    )
  }

  private val TellomiForwardGridViewModel.snapshot get() = state.value

  @Test
  fun `loads the grid with saved messages first and people for the recent row`() {
    val viewModel = viewModel()

    assertThat(viewModel.snapshot.isLoading).isFalse()
    assertThat(viewModel.snapshot.targets).containsExactly(saved, lin, wang, group, far)
    assertThat(viewModel.snapshot.recentContacts).containsExactly(lin, wang, far)
  }

  /** F-6：点头像切换选中，按勾选顺序。 */
  @Test
  fun `tapping toggles and keeps the order they were picked`() {
    val viewModel = viewModel()

    viewModel.onTargetClicked(wang, fromSearch = false)
    viewModel.onTargetClicked(saved, fromSearch = false)
    viewModel.onTargetClicked(lin, fromSearch = false)
    assertThat(viewModel.snapshot.selectedTargets).containsExactly(wang, saved, lin)

    viewModel.onTargetClicked(saved, fromSearch = false)
    assertThat(viewModel.snapshot.selectedTargets).containsExactly(wang, lin)
  }

  /** 判据 3：选第 6 个不选上，发「最多选 N 个」的事件。 */
  @Test
  fun `picking past the limit is refused with an event`() {
    val viewModel = viewModel(limit = 2)
    val events = mutableListOf<TellomiForwardGridViewModel.Event>()
    val collector = CoroutineScope(dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
      viewModel.events.collect { events += it }
    }

    viewModel.onTargetClicked(lin, fromSearch = false)
    viewModel.onTargetClicked(wang, fromSearch = false)
    viewModel.onTargetClicked(far, fromSearch = false)

    assertThat(viewModel.snapshot.selected).containsExactly(lin.id, wang.id)
    assertThat(events).containsExactly(TellomiForwardGridViewModel.Event.SelectionLimitReached(2))
    collector.cancel()
  }

  /** F-9：在搜索里勾一个 → 回到网格，插在「我的收藏」之后并保持选中；没聊过的人也能选。 */
  @Test
  fun `picking in search returns to the grid with it right after saved messages`() {
    val viewModel = viewModel(searchResults = TellomiForwardSearchResults(contacts = listOf(never)))

    viewModel.setSearchActive(true)
    viewModel.setQuery("Nev")
    assertThat(viewModel.snapshot.searchResults.contacts).containsExactly(never)

    viewModel.onTargetClicked(never, fromSearch = true)
    viewModel.setSearchActive(true)
    viewModel.onTargetClicked(far, fromSearch = true)

    assertThat(viewModel.snapshot.isSearchActive).isFalse()
    assertThat(viewModel.snapshot.query).isEqualTo("")
    assertThat(viewModel.snapshot.targets).containsExactly(saved, far, never, lin, wang, group)
    assertThat(viewModel.snapshot.selectedTargets).containsExactly(never, far)
  }

  /** F-7：发送按勾选顺序交出聊天，附言去掉首尾空白；发完阶段是 Sent 并带着收件人（提示条用）。 */
  @Test
  fun `send hands over the chats in order with the trimmed message`() {
    val viewModel = viewModel()
    viewModel.onTargetClicked(group, fromSearch = false)
    viewModel.onTargetClicked(saved, fromSearch = false)
    viewModel.setMessage("  看看这个  ")

    viewModel.send()

    assertThat(sender.checked.single()).containsExactly(group.key, saved.key)
    assertThat(sender.sent.single().first).isEqualTo("看看这个")
    assertThat(sender.sent.single().second).containsExactly(group.key, saved.key)
    val stage = viewModel.snapshot.stage
    assertThat(stage).isInstanceOf(TellomiForwardGridViewModel.Stage.Sent::class)
    stage as TellomiForwardGridViewModel.Stage.Sent
    assertThat(stage.result).isEqualTo(TellomiForwardGridViewModel.Result.SUCCESS)
    assertThat(stage.recipients).containsExactly(group, saved)
  }

  /** F-8：对方安全码刚变过——先停在确认，点「仍然发送」再从头查一遍后发出；取消就回到选择。 */
  @Test
  fun `a changed safety number asks first`() {
    val viewModel = viewModel()
    sender.identities = mutableListOf(listOf(mockk(relaxed = true)))
    viewModel.onTargetClicked(lin, fromSearch = false)

    viewModel.send()
    assertThat(viewModel.snapshot.stage).isInstanceOf(TellomiForwardGridViewModel.Stage.SafetyConfirmation::class)
    assertThat(sender.sent).isEmpty()

    viewModel.cancelSend()
    assertThat(viewModel.snapshot.stage).isEqualTo(TellomiForwardGridViewModel.Stage.Selection)

    sender.identities = mutableListOf(listOf(mockk(relaxed = true)))
    viewModel.send()
    val confirmation = viewModel.snapshot.stage as TellomiForwardGridViewModel.Stage.SafetyConfirmation
    viewModel.confirmSafetySend(confirmation.destinations)
    assertThat(sender.checked.size).isEqualTo(3)
    assertThat(sender.sent.size).isEqualTo(1)
  }

  /** F-8：确认框里把人移出了这次发送——「仍然发送」只发给留下的人，提示里也只有留下的人。 */
  @Test
  fun `send anyway goes only to the chats left in the safety sheet`() {
    val viewModel = viewModel()
    sender.identities = mutableListOf(listOf(mockk(relaxed = true)))
    viewModel.onTargetClicked(lin, fromSearch = false)
    viewModel.onTargetClicked(wang, fromSearch = false)
    viewModel.send()
    val confirmation = viewModel.snapshot.stage as TellomiForwardGridViewModel.Stage.SafetyConfirmation
    assertThat(confirmation.destinations).containsExactly(lin.key, wang.key)

    viewModel.confirmSafetySend(listOf(wang.key))

    assertThat(sender.checked.last()).containsExactly(wang.key)
    assertThat(sender.sent.single().second).containsExactly(wang.key)
    val stage = viewModel.snapshot.stage as TellomiForwardGridViewModel.Stage.Sent
    assertThat(stage.recipients).containsExactly(wang)
  }

  /** 进程被回收后确认框自己恢复了，网格的选择却是空的：「仍然发送」照确认框里的人发（同上游）。 */
  @Test
  fun `send anyway after the process was recreated still sends to the chats in the sheet`() {
    val viewModel = viewModel()

    viewModel.confirmSafetySend(listOf(lin.key))

    assertThat(sender.sent.single().second).containsExactly(lin.key)
    val stage = viewModel.snapshot.stage as TellomiForwardGridViewModel.Stage.Sent
    assertThat(stage.recipients).containsExactly(lin)
  }

  /** 收件人为空时不交给发送（上游对空收件人会回「全部发出」），回到选择。 */
  @Test
  fun `send anyway with nobody left sends nothing and goes back to selection`() {
    val viewModel = viewModel()
    sender.identities = mutableListOf(listOf(mockk(relaxed = true)))
    viewModel.onTargetClicked(lin, fromSearch = false)
    viewModel.send()

    viewModel.confirmSafetySend(emptyList())

    assertThat(sender.sent).isEmpty()
    assertThat(viewModel.snapshot.stage).isEqualTo(TellomiForwardGridViewModel.Stage.Selection)
  }

  /** F-9：没选就退出搜索（返回键）：回到网格，搜索词清掉，网格顺序和已选的都不变。 */
  @Test
  fun `leaving search without picking returns to the grid`() {
    val viewModel = viewModel(searchResults = TellomiForwardSearchResults(contacts = listOf(never)))
    viewModel.onTargetClicked(wang, fromSearch = false)
    viewModel.setSearchActive(true)
    viewModel.setQuery("Nev")

    viewModel.setSearchActive(false)

    assertThat(viewModel.snapshot.isSearchActive).isFalse()
    assertThat(viewModel.snapshot.query).isEqualTo("")
    assertThat(viewModel.snapshot.searchResults.isEmpty).isTrue()
    assertThat(viewModel.snapshot.targets).containsExactly(saved, lin, wang, group, far)
    assertThat(viewModel.snapshot.selectedTargets).containsExactly(wang)
  }

  @Test
  fun `nothing is sent without a selection`() {
    val viewModel = viewModel()

    viewModel.send()

    assertThat(sender.checked).isEmpty()
    assertThat(viewModel.snapshot.stage == TellomiForwardGridViewModel.Stage.Selection).isTrue()
  }

  /** 审计 5.4：带附件+说明时显示开关，默认关；拨动后发送去掉说明，附言照常。 */
  @Test
  fun `hide caption strips draft text on send but keeps the comment`() {
    val mediaArgs = MultiShareArgs.Builder(setOf())
      .withMedia(listOf(photo()))
      .withDraftText("海边")
      .build()
    val viewModel = viewModel(multiShareArgs = listOf(mediaArgs))

    assertThat(viewModel.snapshot.showHideCaptionToggle).isTrue()
    assertThat(viewModel.snapshot.hideCaption).isFalse()

    viewModel.onTargetClicked(lin, fromSearch = false)
    viewModel.setMessage("看看")
    viewModel.setHideCaption(true)
    assertThat(viewModel.snapshot.hideCaption).isTrue()
    viewModel.send()

    assertThat(sender.sent.map { it.first }).containsExactly("看看")
    assertThat(sender.sentArgs.single().single().draftText).isNull()
  }

  @Test
  fun `hide caption toggle stays hidden for text-only forwards`() {
    val textArgs = MultiShareArgs.Builder(setOf()).withDraftText("你好").build()
    val viewModel = viewModel(multiShareArgs = listOf(textArgs))

    assertThat(viewModel.snapshot.showHideCaptionToggle).isFalse()
    viewModel.setHideCaption(true)
    assertThat(viewModel.snapshot.hideCaption).isFalse()

    viewModel.onTargetClicked(lin, fromSearch = false)
    viewModel.send()
    assertThat(sender.sentArgs.single().single().draftText).isEqualTo("你好")
  }

  private fun photo(): Media {
    return Media(
      uri = Uri.parse("content://org.thoughtcrime.securesms/part/1"),
      contentType = "image/jpeg",
      date = 0,
      width = 100,
      height = 100,
      size = 10,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = null,
      caption = null,
      transformProperties = null,
      fileName = null
    )
  }
}
