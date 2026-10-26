/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import androidx.annotation.VisibleForTesting
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.SignalDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历上每天显示一张缩略图——那天的第一张图片或视频。
 *
 * 跟会话「所有媒体」同一套图册过滤（[org.thoughtcrime.securesms.database.MediaTable.getFirstGalleryMediaInRange]）：
 * 阅后即焚、编辑前旧版、贴纸、引用图、以文件发送的图不会出现。
 * 媒体按收到时间（`date_received`）分天；日历能不能选按发出时间分天（[JumpToDateValidator] / `messageExistsOnDays`），
 * 两者跨午夜时可能差一天，只影响缩略图落在哪一格，不影响跳转。对齐 iOS `TellomiConversationDayThumbnails`。
 */
object TellomiConversationDayThumbnails {

  /**
   * [day] 本地 0 点到第二天 0 点（夏令时那天也对）。
   */
  fun dayInterval(day: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return start to end
  }

  fun localDateOf(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
    return Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate()
  }

  /**
   * [days] 里每一天的第一张图片或视频的展示 URI；那天没有就不在结果里。
   */
  fun firstVisualMediaUris(
    threadId: Long,
    days: Collection<LocalDate>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    lookup: (Long, Long) -> DatabaseAttachment? = { start, end ->
      SignalDatabase.media.getFirstGalleryMediaInRange(threadId, start, end)
    }
  ): Map<LocalDate, Uri> {
    val uniqueDays = days.toSet()
    if (uniqueDays.isEmpty()) return emptyMap()
    val result = LinkedHashMap<LocalDate, Uri>()
    for (day in uniqueDays) {
      val (start, end) = dayInterval(day, zoneId)
      val attachment = lookup(start, end) ?: continue
      val uri = displayUri(attachment) ?: continue
      result[day] = uri
    }
    return result
  }

  /**
   * 把按收到时间排序的媒体归到本地天：每天只留第一条。供单测验证分桶，不碰数据库。
   */
  @VisibleForTesting
  fun <T> firstPerLocalDay(
    items: List<Pair<Long, T>>,
    days: Set<LocalDate>,
    zoneId: ZoneId
  ): Map<LocalDate, T> {
    val result = LinkedHashMap<LocalDate, T>()
    for ((receivedMs, item) in items) {
      val day = localDateOf(receivedMs, zoneId)
      if (day in days && day !in result) {
        result[day] = item
      }
    }
    return result
  }

  @VisibleForTesting
  fun displayUri(attachment: DatabaseAttachment): Uri? {
    return attachment.uri ?: attachment.thumbnailUri
  }
}
