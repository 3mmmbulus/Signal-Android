/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.extra

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.MediaSendFlowActivityContract.DockEntry
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.MediaSendFlowHudCommand
import org.signal.mediasend.MediaSendFlowViewModel

/**
 * Tellomi (2026-10-16): pages the app draws inside the attachment sheet (the poll) follow the same dock rules as the
 * gallery and files pages, and their result leaves the sheet for the conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AttachmentExtraPagesTest {

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `from the poll page, gallery and files switch pages inside the sheet`() {
    assertThat(dockEventFor(DockEntry("GALLERY", 1, 1, page = AttachmentPage.GALLERY))).isEqualTo(MediaSendFlowEvent.OpenAttachmentPage(AttachmentPage.GALLERY))
    assertThat(dockEventFor(DockEntry("FILE", 1, 1, page = AttachmentPage.FILES))).isEqualTo(MediaSendFlowEvent.OpenAttachmentPage(AttachmentPage.FILES))
  }

  @Test
  fun `a greyed out entry only shows its message and an entry without a page goes back to the conversation`() {
    assertThat(dockEventFor(DockEntry("LOCATION", 1, 1, comingSoonMessage = 7))).isInstanceOf(MediaSendFlowEvent.ShowToast::class)
    assertThat(dockEventFor(DockEntry("CONTACT", 1, 1))).isEqualTo(MediaSendFlowEvent.AttachmentDockEntrySelected("CONTACT"))
  }

  @Test
  fun `a finished page hands its result to the host with the page it came from`() {
    val viewModel = MediaSendFlowViewModel(
      savedStateHandle = SavedStateHandle(
        mapOf(
          MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(attachmentSheet = MediaSendFlowActivityContract.AttachmentSheet(emptyList())),
          MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
        )
      ),
      repository = mediaSendDependenciesRule.mediaSendRepository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
    val result = Bundle().apply { putString("question", "Lunch?") }

    viewModel.onEvent(MediaSendFlowEvent.OpenAttachmentPage(AttachmentPage.POLL))
    assertThat(viewModel.state.value.attachmentPage).isEqualTo(AttachmentPage.POLL)

    viewModel.onEvent(MediaSendFlowEvent.AttachmentPageResult(AttachmentPage.POLL, result))

    val command = runBlocking { withTimeout(5_000) { viewModel.hudCommands.first() } }
    assertThat(command).isInstanceOf(MediaSendFlowHudCommand.AttachmentPageResult::class)
    command as MediaSendFlowHudCommand.AttachmentPageResult
    assertThat(command.page).isEqualTo(AttachmentPage.POLL)
    assertThat(command.result.getString("question")).isEqualTo("Lunch?")
  }
}
