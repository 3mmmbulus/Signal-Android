/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

/**
 * Tellomi（tellomi/tellomi#1522 E-1）：裁剪的比例预设，照 Telegram 两端（安卓 `CropView.showAspectRatioDialog`、
 * iOS `TGPhotoCropController`）：原始、正方形，再加 3:2、5:3、4:3、5:4、7:5、16:9，一共 8 项。
 * 裁剪框现在是横的就按「长:短」，竖的就倒过来（3:2 写成 2:3、也按 2:3 裁）。
 */
internal enum class CropAspectRatio(private val long: Int, private val short: Int) {
  ORIGINAL(0, 0),
  SQUARE(1, 1),
  THREE_BY_TWO(3, 2),
  FIVE_BY_THREE(5, 3),
  FOUR_BY_THREE(4, 3),
  FIVE_BY_FOUR(5, 4),
  SEVEN_BY_FIVE(7, 5),
  SIXTEEN_BY_NINE(16, 9);

  /** 菜单里写的「宽:高」；原始和正方形没有。 */
  fun sides(portrait: Boolean): Pair<Int, Int>? = when (this) {
    ORIGINAL, SQUARE -> null
    else -> if (portrait) short to long else long to short
  }

  /** 宽 / 高。[originalWidthToHeight] 是原图在现在这个方向上的宽高比。 */
  fun widthToHeight(originalWidthToHeight: Float, portrait: Boolean): Float = when (this) {
    ORIGINAL -> originalWidthToHeight
    SQUARE -> 1f
    else -> if (portrait) short / long.toFloat() else long / short.toFloat()
  }
}
