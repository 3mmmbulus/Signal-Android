/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.app.Activity
import android.app.Application
import android.content.Intent
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.thoughtcrime.securesms.polls.Poll

/** Tellomi (2026-10-16): a poll sent from the attachment sheet's poll page comes back to the conversation intact. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiAttachmentPollResultTest {

  private val poll = Poll(question = "Lunch?", allowMultipleVotes = false, pollOptions = listOf("Noodles", "Rice"))

  @Test
  fun `the poll page result reads back as the same poll`() {
    val data = Intent()
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, AttachmentPage.POLL.name)
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, poll.toBundle())

    val bundle = MediaSendLauncher.parseAttachmentPoll(Activity.RESULT_OK, data)

    assertThat(bundle).isNotNull()
    assertThat(Poll.fromBundle(bundle!!)).isEqualTo(poll)
  }

  @Test
  fun `a cancelled sheet or another kind of result is not a poll`() {
    val data = Intent()
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE, AttachmentPage.POLL.name)
      .putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_PAGE_RESULT, poll.toBundle())

    assertThat(MediaSendLauncher.parseAttachmentPoll(Activity.RESULT_CANCELED, data)).isNull()
    assertThat(MediaSendLauncher.parseAttachmentPoll(Activity.RESULT_OK, Intent().putExtra(MediaSendLauncher.EXTRA_ATTACHMENT_DOCK_ENTRY, "CONTACT"))).isNull()
    assertThat(MediaSendLauncher.parseAttachmentPoll(Activity.RESULT_OK, null)).isNull()
  }
}
