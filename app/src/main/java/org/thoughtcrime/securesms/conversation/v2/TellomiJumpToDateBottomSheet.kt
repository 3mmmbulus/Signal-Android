/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import java.time.ZoneId

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历底部弹层（带媒体日缩略图）。
 * 选中一天后通过 [REQUEST_KEY] 回传本地 0 点毫秒时间戳。
 */
class TellomiJumpToDateBottomSheet : ComposeBottomSheetDialogFragment() {

  companion object {
    const val REQUEST_KEY = "tellomi_jump_to_date"
    const val KEY_DAY_MS = "day_ms"

    private const val ARG_THREAD_ID = "thread_id"
    private const val ARG_INITIAL_DAY_MS = "initial_day_ms"
    private const val ARG_EARLIEST_DAY_MS = "earliest_day_ms"

    fun show(
      fragmentManager: FragmentManager,
      threadId: Long,
      initialDayMs: Long,
      earliestDayMs: Long
    ) {
      TellomiJumpToDateBottomSheet().apply {
        arguments = bundleOf(
          ARG_THREAD_ID to threadId,
          ARG_INITIAL_DAY_MS to initialDayMs,
          ARG_EARLIEST_DAY_MS to earliestDayMs
        )
      }.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }

  override val peekHeightPercentage: Float = 0.72f

  private val viewModel: TellomiJumpToDateViewModel by lazy {
    val args = requireArguments()
    ViewModelProvider(
      this,
      TellomiJumpToDateViewModel.Factory(
        threadId = args.getLong(ARG_THREAD_ID),
        initialDayMs = args.getLong(ARG_INITIAL_DAY_MS),
        earliestDayMs = args.getLong(ARG_EARLIEST_DAY_MS)
      )
    )[TellomiJumpToDateViewModel::class.java]
  }

  @Composable
  override fun SheetContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TellomiJumpToDateScreen(
      state = state,
      onMonthVisible = viewModel::onMonthVisible,
      onPick = { day ->
        val dayMs = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_DAY_MS to dayMs))
        dismissAllowingStateLoss()
      },
      onCancel = { dismissAllowingStateLoss() }
    )
  }
}
