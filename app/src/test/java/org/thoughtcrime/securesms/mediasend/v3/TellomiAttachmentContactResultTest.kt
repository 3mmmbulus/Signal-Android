/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.app.Activity
import android.app.Application
import android.content.Intent
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.mediasend.MediaSendFlowActivityContract
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.mediasend.MediaSendLauncher
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Tellomi（2026-10-16）：附件 Sheet 联系人页发出的联系人卡，经 Activity 结果原样回到会话页（同整屏那条路的结果格式）；
 * 别的页的结果不会被当成联系人。Tellomi 构建强制用新的联系人选择器（owner 定 A）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiAttachmentContactResultTest {

  private val contact = Contact(
    Contact.Name("小林", null, null, null, null, null),
    null,
    listOf(Contact.Phone("+8613800000001", Contact.Phone.Type.MOBILE, null)),
    emptyList(),
    emptyList(),
    null
  )

  @Test
  fun `the contact page result comes back as the contacts to send`() {
    val intent = Intent()
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, MediaSendFlowActivityContract.AttachmentPage.CONTACT.name)
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, contactsResult(contact))

    val parsed = MediaSendLauncher.parseAttachmentContacts(Activity.RESULT_OK, intent)

    assertThat(parsed!!.map { it.name.givenName }).containsExactly("小林")
    assertThat(parsed.single().phoneNumbers.map { it.number }).containsExactly("+8613800000001")
  }

  @Test
  fun `other pages and cancelled results are not contacts`() {
    val poll = Intent()
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, MediaSendFlowActivityContract.AttachmentPage.POLL.name)
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, contactsResult(contact))
    assertThat(MediaSendLauncher.parseAttachmentContacts(Activity.RESULT_OK, poll)).isNull()

    val cancelled = Intent()
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, MediaSendFlowActivityContract.AttachmentPage.CONTACT.name)
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, contactsResult(contact))
    assertThat(MediaSendLauncher.parseAttachmentContacts(Activity.RESULT_CANCELED, cancelled)).isNull()
  }

  @Test
  fun `tellomi builds use the new contact picker by default`() {
    assertThat(RemoteConfig.contactSharingV2).isTrue()
  }
}
