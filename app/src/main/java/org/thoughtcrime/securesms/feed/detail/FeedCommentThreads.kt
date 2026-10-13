/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import org.thoughtcrime.securesms.feed.net.FeedCard

/**
 * 一条直接评论帖子的评论，和它下面的全部回复（不管回复的是它还是它下面的回复）。
 * [replyTo] 记下每条回复回的是谁：回的不是楼主那条时，界面写「回复 @某人」。
 */
data class FeedCommentThread(
  val root: FeedCard,
  val replies: List<FeedCard> = emptyList(),
  val replyTo: Map<String, FeedCard> = emptyMap()
)

/**
 * 把引擎给的平铺评论（按时间正序，契约第五节）排成楼中楼：楼按时间正序，楼里的回复也按时间正序。
 *
 * 父评论不在这一页（还没加载、被删、被屏蔽）的回复，往上找不到楼主时自己成一楼，不丢。
 */
object FeedCommentThreads {

  fun build(comments: List<FeedCard>): List<FeedCommentThread> {
    val byId = comments.associateBy { it.id }

    fun topOf(comment: FeedCard): FeedCard {
      var current = comment
      val seen = mutableSetOf(current.id)
      while (true) {
        val parent = current.parentId?.let { byId[it] } ?: return current
        if (!seen.add(parent.id)) return current
        current = parent
      }
    }

    val roots = LinkedHashMap<String, FeedCard>()
    val replies = mutableMapOf<String, MutableList<FeedCard>>()
    for (comment in comments) {
      val top = topOf(comment)
      if (top.id == comment.id) {
        roots[comment.id] = comment
      } else {
        roots.putIfAbsent(top.id, top)
        replies.getOrPut(top.id) { mutableListOf() } += comment
      }
    }

    return roots.values.map { root ->
      val threadReplies = replies[root.id].orEmpty()
      val replyTo = threadReplies
        .mapNotNull { reply -> reply.parentId?.let { byId[it] }?.takeIf { it.id != root.id }?.let { reply.id to it } }
        .toMap()
      FeedCommentThread(root = root, replies = threadReplies, replyTo = replyTo)
    }
  }
}
