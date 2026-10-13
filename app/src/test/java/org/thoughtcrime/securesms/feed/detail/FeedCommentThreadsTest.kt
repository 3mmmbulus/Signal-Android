/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedCard

class FeedCommentThreadsTest {

  private fun comment(id: String, parent: String? = null) = FeedCard(id = id, kind = FeedCard.KIND_COMMENT, text = id, rootId = "p", parentId = parent)

  @Test
  fun `replies at any depth hang under their top level comment in time order`() {
    val threads = FeedCommentThreads.build(
      listOf(comment("a"), comment("b"), comment("a1", "a"), comment("c"), comment("a2", "a1"), comment("b1", "b"))
    )

    assertThat(threads.map { it.root.id }).containsExactly("a", "b", "c")
    assertThat(threads[0].replies.map { it.id }).containsExactly("a1", "a2")
    assertThat(threads[1].replies.map { it.id }).containsExactly("b1")
    assertThat(threads[2].replies).isEmpty()
  }

  @Test
  fun `a reply to a reply remembers who it answers, a reply to the top comment does not`() {
    val threads = FeedCommentThreads.build(listOf(comment("a"), comment("a1", "a"), comment("a2", "a1")))

    assertThat(threads[0].replyTo.keys.toList()).containsExactly("a2")
    assertThat(threads[0].replyTo["a2"]!!.id).isEqualTo("a1")
  }

  @Test
  fun `a reply whose parent is missing becomes its own thread`() {
    val threads = FeedCommentThreads.build(listOf(comment("a"), comment("x1", "gone")))

    assertThat(threads.map { it.root.id }).containsExactly("a", "x1")
  }

  @Test
  fun `a parent cycle does not loop`() {
    val threads = FeedCommentThreads.build(listOf(comment("a", "b"), comment("b", "a")))

    assertThat(threads.flatMap { listOf(it.root.id) + it.replies.map { r -> r.id } }.toSet()).isEqualTo(setOf("a", "b"))
  }
}
