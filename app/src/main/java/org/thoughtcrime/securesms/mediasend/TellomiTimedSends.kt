/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.net.Uri
import org.signal.core.models.media.Media

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4）：选图里设了计时（含一次性查看）的照片 / 视频怎么发。
 *
 * 照 Telegram 两端（安卓 `SendMessagesHelper`、iOS `TGMediaAssetsController`）：有计时的整批不分组、一张一条。计时的发成一次性查看
 * （带自己的秒数，不带说明——一次性查看不能带说明）；没计时的照常，说明挂在没计时的最后一张上；全都计时时说明是最前面单独一条文字。
 */
object TellomiTimedSends {

  /** 没有计时。 */
  const val NONE = -1

  /** 一次性查看（不带秒数）。 */
  const val VIEW_ONCE = 0

  /**
   * 要发的一条：[index] 是第几张（null = 只有说明文字的那一条）；[isViewOnce] / [seconds] 是这一条的一次性查看和秒数；
   * [hasBody] = 说明挂在这一条上。
   */
  data class Part(val index: Int?, val isViewOnce: Boolean, val seconds: Int?, val hasBody: Boolean)

  @JvmStatic
  fun parts(choices: List<Int>, hasBody: Boolean): List<Part> {
    val lastUntimed = choices.indexOfLast { it == NONE }.takeIf { it >= 0 }
    val parts = mutableListOf<Part>()
    if (lastUntimed == null && hasBody) {
      parts += Part(index = null, isViewOnce = false, seconds = null, hasBody = true)
    }
    choices.forEachIndexed { index, choice ->
      parts += if (choice == NONE) {
        Part(index = index, isViewOnce = false, seconds = null, hasBody = index == lastUntimed && hasBody)
      } else {
        Part(index = index, isViewOnce = true, seconds = choice.takeIf { it > VIEW_ONCE }, hasBody = false)
      }
    }
    return parts
  }

  /** 选的原 URI 上的计时 → 发出去那一份（编辑过、压过画质的换了新 URI）上的计时。 */
  @JvmStatic
  fun renderedChoices(selected: List<Media>, oldToNew: Map<Media, Media>, choices: Map<Uri, Int>): Map<Uri, Int> {
    if (choices.isEmpty()) {
      return emptyMap()
    }
    return selected.mapNotNull { media ->
      val choice = choices[media.uri] ?: return@mapNotNull null
      (oldToNew[media] ?: media).uri to choice
    }.toMap()
  }
}
