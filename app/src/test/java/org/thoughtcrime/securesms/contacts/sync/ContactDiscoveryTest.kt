/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.contacts.sync

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.signal.contacts.SystemContactsRepository
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Tellomi（#1346）：CDSI 关着时，授权通讯录后只读同步系统通讯录里的名字。
 * 不查 CDS、不上传、不写系统通讯录；只验证本地库里的名字与 isSystemContact。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ContactDiscoveryTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    mockkStatic(SystemContactsRepository::class)
  }

  @After
  fun tearDown() {
    unmockkStatic(SystemContactsRepository::class)
  }

  @Test
  fun givenContactsPermissionAndNoCdsi_whenIRefreshAll_thenSavedContactNameIsShownAndMarkedAsSystemContact() {
    // GIVEN: 系统通讯录里已经存了一个人（名字「张三」），授权了 READ_CONTACTS
    Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
    every { SystemContactsRepository.getAllSystemContacts(eq(context), any()) } returns contactsOf(
      SystemContactsRepository.ContactDetails(
        givenName = "张",
        familyName = "三",
        numbers = listOf(phoneOf("+15550001111", displayName = "张三"))
      )
    )

    // WHEN
    ContactDiscovery.refreshAll(context, notifyOfNewUsers = false)

    // THEN: 名字写进了本地库，「添加到通讯录」的判断（isSystemContact）变成 true
    val recipient: Recipient = Recipient.externalContact("+15550001111")!!
    assertTrue(recipient.isSystemContact)
    assertTrue(recipient.getDisplayName(context).contains("张三"))
  }

  @Test
  fun givenNoContactsPermission_whenIRefreshAll_thenSystemContactsAreNotRead() {
    // GIVEN: 没有授权 READ_CONTACTS（Robolectric 默认）

    // WHEN
    ContactDiscovery.refreshAll(context, notifyOfNewUsers = false)

    // THEN
    verify(exactly = 0) { SystemContactsRepository.getAllSystemContacts(eq(context), any()) }
  }

  private fun contactsOf(vararg details: SystemContactsRepository.ContactDetails): SystemContactsRepository.ContactIterator {
    val iterator = details.iterator()
    return object : SystemContactsRepository.ContactIterator {
      override fun hasNext(): Boolean = iterator.hasNext()
      override fun next(): SystemContactsRepository.ContactDetails = iterator.next()
    }
  }

  private fun phoneOf(number: String, displayName: String): SystemContactsRepository.ContactPhoneDetails {
    return SystemContactsRepository.ContactPhoneDetails(
      contactUri = Uri.parse("content://com.android.contacts/data/1"),
      displayName = displayName,
      photoUri = null,
      number = number,
      type = 2,
      label = null
    )
  }
}
