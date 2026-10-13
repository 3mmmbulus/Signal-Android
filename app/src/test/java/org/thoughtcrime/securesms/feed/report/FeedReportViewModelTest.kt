/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import android.net.Uri
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.EmptyFeedSource
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedReportRequest

@OptIn(ExperimentalCoroutinesApi::class)
class FeedReportViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(val reportError: Exception? = null) : FeedSource by EmptyFeedSource {
    val uploads = mutableListOf<Uri>()
    val reports = mutableListOf<FeedReportRequest>()
    override suspend fun config(): FeedConfig = FeedConfig(enabled = true, region = "cn")
    override suspend fun uploadImage(uri: Uri): String {
      uploads += uri
      return "m/${uploads.size}"
    }
    override suspend fun report(request: FeedReportRequest) {
      reportError?.let { throw it }
      reports += request
    }
  }

  private fun reason(code: String) = FeedReportReasons.groups(emptyMap()).flatMap { it.reasons }.first { it.code == code }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `a plain reason sends without screenshots`() {
    val source = FakeSource()
    val viewModel = FeedReportViewModel(FeedReportTarget.content("p1"), source, dispatcher)

    viewModel.pick(reason("spam"))
    viewModel.onDetailChanged("  一直发广告  ")
    viewModel.submit()

    assertThat(source.reports).containsExactly(FeedReportRequest(targetType = "content", targetId = "p1", group = "false_or_impersonation", reason = "spam", detail = "一直发广告"))
    assertThat(viewModel.state.value.sent).isTrue()
    assertThat(viewModel.state.value.showMainlandHotline).isTrue()
  }

  @Test
  fun `fraud needs a screenshot, uploaded only when sending`() {
    val source = FakeSource()
    val viewModel = FeedReportViewModel(FeedReportTarget.content("p1"), source, dispatcher)
    val shot = mockk<Uri>()

    viewModel.pick(reason("fraud"))
    assertThat(viewModel.state.value.canSubmit).isFalse()
    viewModel.addEvidence(listOf(shot))
    assertThat(source.uploads.isEmpty()).isTrue()
    viewModel.submit()

    assertThat(source.uploads).containsExactly(shot)
    assertThat(source.reports.single().evidenceKeys).isEqualTo(listOf("m/1"))
    assertThat(source.reports.single().relationship).isNull()
  }

  @Test
  fun `my rights also needs the relationship`() {
    val viewModel = FeedReportViewModel(FeedReportTarget.author("a1"), FakeSource(), dispatcher)

    viewModel.pick(reason("portrait"))
    viewModel.addEvidence(listOf(mockk()))
    assertThat(viewModel.state.value.canSubmit).isFalse()
    viewModel.onRelationshipChanged("本人")
    assertThat(viewModel.state.value.canSubmit).isTrue()
  }

  @Test
  fun `content that is gone is its own error`() {
    val viewModel = FeedReportViewModel(FeedReportTarget.content("p1"), FakeSource(FeedApiException(404, "not_found")), dispatcher)

    viewModel.pick(reason("spam"))
    viewModel.submit()

    assertThat(viewModel.state.value.error).isEqualTo(FeedReportState.Error.GONE)
    assertThat(viewModel.state.value.sent).isFalse()
  }

  @Test
  fun `going back keeps what was typed`() {
    val viewModel = FeedReportViewModel(FeedReportTarget.content("p1"), FakeSource(), dispatcher)

    viewModel.pick(reason("spam"))
    viewModel.onDetailChanged("x")
    viewModel.backToReasons()

    assertThat(viewModel.state.value.reason).isNull()
    assertThat(viewModel.state.value.detail).isEqualTo("x")
  }
}
