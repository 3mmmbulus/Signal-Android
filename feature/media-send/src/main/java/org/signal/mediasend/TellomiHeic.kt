/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.os.Build
import org.signal.core.models.media.Media
import org.signal.core.util.ContentTypeUtil

/**
 * Tellomi（tellomi/tellomi#1263 P-5，ADR-0068）：按文件发送时的 HEIC。电脑端不认 HEIC，所以已选里有 HEIC 先问一句
 * 「是否把照片转成 JPEG？」（保留 HEIC / 转成 JPEG，照 Telegram）。安卓 9 以下解不了 HEIC，转不了就不问，原样发。
 */
object TellomiHeic {

  fun isHeic(contentType: String?): Boolean {
    return ContentTypeUtil.isHeicType(contentType) || ContentTypeUtil.isHeifType(contentType)
  }

  /** 已选里有 HEIC、这台手机也转得了，才问。 */
  fun canConvert(media: List<Media>, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    return sdkInt >= Build.VERSION_CODES.P && media.any { isHeic(it.contentType) }
  }
}
