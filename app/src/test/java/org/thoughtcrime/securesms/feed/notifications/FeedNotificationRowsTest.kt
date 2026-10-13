/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedNotification
import java.time.Instant
import java.time.ZoneOffset

class FeedNotificationRowsTest {

  private val now = Instant.parse("2026-10-13T12:00:00Z")

  private fun n(id: String, kind: String, actor: String?, content: String?, at: String, read: Boolean = true, detail: JsonObject? = null) = FeedNotification(id = id, kind = kind, actorId = actor, contentId = content, createdAt = at, read = read, detail = detail)

  @Test
  fun `likes on the same post merge into the newest row`() {
    val items = listOf(
      n("1", FeedNotification.KIND_LIKE, "a", "post", "2026-10-13T11:00:00Z"),
      n("2", FeedNotification.KIND_COMMENT, "b", "post", "2026-10-13T10:00:00Z"),
      n("3", FeedNotification.KIND_LIKE, "c", "post", "2026-10-13T09:00:00Z", read = false),
      n("4", FeedNotification.KIND_LIKE, "a", "post", "2026-10-13T08:00:00Z"),
      n("5", FeedNotification.KIND_LIKE, "d", "other", "2026-10-13T07:00:00Z")
    )

    val rows = groupNotifications(items, now, ZoneOffset.UTC).single().rows

    assertThat(rows.map { it.key }).containsExactly("1", "2", "5")
    assertThat(rows[0].ids).containsExactly("1", "3", "4")
    assertThat(rows[0].actorIds).containsExactly("a", "c")
    assertThat(rows[0].unread).isTrue()
  }

  @Test
  fun `rows are grouped into today, this week and earlier`() {
    val items = listOf(
      n("1", FeedNotification.KIND_FOLLOW, "a", null, "2026-10-13T01:00:00Z"),
      n("2", FeedNotification.KIND_FOLLOW, "b", null, "2026-10-08T01:00:00Z"),
      n("3", FeedNotification.KIND_FOLLOW, "c", null, "2026-10-01T01:00:00Z")
    )

    val sections = groupNotifications(items, now, ZoneOffset.UTC)

    assertThat(sections.map { it.bucket }).containsExactly(FeedNotificationBucket.TODAY, FeedNotificationBucket.THIS_WEEK, FeedNotificationBucket.EARLIER)
  }

  @Test
  fun `detail fields are read, held comments and system types`() {
    val items = listOf(
      n("1", FeedNotification.KIND_REPLY, "a", "post", "2026-10-13T11:00:00Z", detail = JsonObject(mapOf("held" to JsonPrimitive(true), "parent_id" to JsonPrimitive("m1")))),
      n("2", FeedNotification.KIND_SYSTEM, null, "post", "2026-10-13T10:00:00Z", detail = JsonObject(mapOf("type" to JsonPrimitive("taken_down"))))
    )

    val rows = groupNotifications(items, now, ZoneOffset.UTC).single().rows

    assertThat(rows[0].held).isTrue()
    assertThat(rows[1].systemType).isEqualTo("taken_down")
  }
}
