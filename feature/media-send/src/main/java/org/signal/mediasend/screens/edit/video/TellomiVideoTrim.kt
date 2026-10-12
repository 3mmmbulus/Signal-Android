/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.video

/**
 * 视频裁剪的两个门槛（tellomi/tellomi#1518 V-4；owner 2026-10-13 定 O3：照 Telegram）：裁出的片段最短 1 秒
 * （TG `TGVideoScrubberMinimumTrimDuration`），视频本身短于 1.5 秒不给裁（TG `TGVideoEditMinimumTrimmableDuration`），
 * 这时只能拖播放位置。两端同一组数，iOS 在 `VideoEditorModel`。
 */
object TellomiVideoTrim {
  const val MINIMUM_RANGE_US: Long = 1_000_000L
  const val MINIMUM_TRIMMABLE_DURATION_US: Long = 1_500_000L

  @JvmStatic
  fun isTrimmable(durationUs: Long): Boolean = durationUs >= MINIMUM_TRIMMABLE_DURATION_US
}
