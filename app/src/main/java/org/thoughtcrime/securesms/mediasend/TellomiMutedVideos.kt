/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi（tellomi/tellomi#1518 V-6，owner 2026-10-14 照 Telegram 两端）：静音的视频当 GIF 发，而且**单独成一条消息**，
 * 其余的照常成组（照 TG 安卓 `SendMessagesHelper` 静音那一项不进相册组；Signal Desktop 也只在整条消息只有一个附件时才按 GIF 画）。
 */
object TellomiMutedVideos {

  /** 拆成几条消息（下标），以及哪一条带说明（说明、@、格式，还有说明太长时上游另传的长文本附件）。 */
  data class Split(val groups: List<List<Int>>, val bodyIndex: Int)

  /** 发送端选了静音、所以要当 GIF 单独发的视频。键盘里来的 GIF 视频不算（它们本来就能和别的一起发）。 */
  fun isMutedGif(media: Media): Boolean {
    return media.isVideoGif && media.transformProperties?.videoMuted == true
  }

  /**
   * 不用拆时是 null（没有静音视频，或者只有一个静音视频、没有别的）。拆的时候：其余的（保持顺序）一组在前、带说明；每个静音视频各一组
   * 跟在后面；全是静音视频时说明跟最后一条。说明太长时上游另传的长文本附件跟着带说明的那一组。
   */
  fun split(media: List<Media>): Split? {
    val muted = media.indices.filter { isMutedGif(media[it]) }
    if (muted.isEmpty()) return null
    val longText = media.indices.filter { MediaUtil.isLongTextType(media[it].contentType) }
    val rest = media.indices.filter { it !in muted && it !in longText }
    if (rest.isEmpty() && muted.size == 1) return null

    val groups = mutableListOf<List<Int>>()
    if (rest.isNotEmpty()) {
      groups += rest
    }
    muted.forEach { groups += listOf(it) }
    val bodyIndex = if (rest.isNotEmpty()) 0 else groups.lastIndex
    if (longText.isNotEmpty()) {
      groups[bodyIndex] = groups[bodyIndex] + longText
    }
    return Split(groups, bodyIndex)
  }
}
