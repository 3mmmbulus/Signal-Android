/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import android.text.SpannableString
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 两端对齐清单 P-4：附件 Sheet 相册页就地输入的说明每打一个字交给流程（[MediaSendFlowEvent.SetMessage]），和上游对话框交回来的
 * 一样处理：去掉首尾空白，空了就是没有说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelInlineCaptionTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private fun viewModel(): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(),
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = dependencies.mediaSendRepository,
      preUploadController = mockk(relaxed = true),
      isMeteredFlow = flowOf(false)
    )
  }

  @Test
  fun `When the inline caption changes, then the flow carries it trimmed`() {
    val viewModel = viewModel()

    viewModel.onEvent(MediaSendFlowEvent.SetMessage(SpannableString("  Sunset  ")))

    assertThat(viewModel.state.value.message?.toString()).isEqualTo("Sunset")
  }

  @Test
  fun `When the inline caption is cleared, then there is no caption`() {
    val viewModel = viewModel()
    viewModel.onEvent(MediaSendFlowEvent.SetMessage("Sunset"))

    viewModel.onEvent(MediaSendFlowEvent.SetMessage("   "))

    assertThat(viewModel.state.value.message).isNull()
  }
}
