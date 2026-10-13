/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.thoughtcrime.securesms.feed.net.FeedNotification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一行通知。同一条帖子的赞合成一行（ADR-0071 二·〇「通知」：同一条的赞合并），[actorIds] 新的在前。 */
data class FeedNotificationRow(
  val key: String,
  val kind: String,
  val ids: List<String>,
  val actorIds: List<String>,
  val contentId: String?,
  val subjectId: String?,
  val systemType: String?,
  /** 评论等我批准（被「限制」的人、或者开了评论审核）。 */
  val held: Boolean,
  /** `close_person_request` 里设我的人。 */
  val profileId: String?,
  val createdAt: Instant,
  val unread: Boolean
)

enum class FeedNotificationBucket {
  TODAY,
  THIS_WEEK,
  EARLIER
}

data class FeedNotificationSection(
  val bucket: FeedNotificationBucket,
  val rows: List<FeedNotificationRow>
)

/** 合并赞、按时间分组（今天 / 七天内 / 更早）。列表本来就是新的在前，合并后的一行排在最新那个赞的位置。 */
fun groupNotifications(items: List<FeedNotification>, now: Instant, zone: ZoneId): List<FeedNotificationSection> {
  val rows = mutableListOf<FeedNotificationRow>()
  val likeRowIndex = mutableMapOf<String, Int>()

  for (item in items) {
    val createdAt = parseInstant(item.createdAt) ?: continue
    val contentId = item.contentId
    if (item.kind == FeedNotification.KIND_LIKE && contentId != null) {
      val index = likeRowIndex[contentId]
      if (index != null) {
        val row = rows[index]
        rows[index] = row.copy(
          ids = row.ids + item.id,
          actorIds = if (item.actorId == null || item.actorId in row.actorIds) row.actorIds else row.actorIds + item.actorId,
          unread = row.unread || !item.read
        )
        continue
      }
      likeRowIndex[contentId] = rows.size
    }
    val detail = item.detail as? JsonObject
    rows += FeedNotificationRow(
      key = item.id,
      kind = item.kind,
      ids = listOf(item.id),
      actorIds = listOfNotNull(item.actorId),
      contentId = contentId,
      subjectId = item.subjectId,
      systemType = detail?.string("type"),
      held = detail?.boolean("held") == true,
      profileId = detail?.string("profile_id"),
      createdAt = createdAt,
      unread = !item.read
    )
  }

  val today = now.atZone(zone).toLocalDate()
  return rows
    .groupBy { bucketOf(it.createdAt.atZone(zone).toLocalDate(), today) }
    .toSortedMap()
    .map { (bucket, rows) -> FeedNotificationSection(bucket, rows) }
}

/** `close_person_request` 的 `detail.profile_id`。 */
internal fun FeedNotification.detailProfileId(): String? = (detail as? JsonObject)?.string("profile_id")

private fun bucketOf(day: LocalDate, today: LocalDate): FeedNotificationBucket = when {
  !day.isBefore(today) -> FeedNotificationBucket.TODAY
  day.isAfter(today.minusDays(7)) -> FeedNotificationBucket.THIS_WEEK
  else -> FeedNotificationBucket.EARLIER
}

private fun JsonObject.string(key: String): String? = try {
  get(key)?.jsonPrimitive?.contentOrNull
} catch (e: IllegalArgumentException) {
  null
}

private fun JsonObject.boolean(key: String): Boolean? = try {
  get(key)?.jsonPrimitive?.booleanOrNull
} catch (e: IllegalArgumentException) {
  null
}

private fun parseInstant(value: String): Instant? = try {
  Instant.parse(value)
} catch (e: Exception) {
  null
}
