/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/**
 * 话题 slug 的名字。引擎的兴趣标签只给 slug（「topics are named by the client」），这里照内容引擎
 * `configs/categories` 的 36 个类别起名；不认识的 slug 显示成 `#slug`。
 */
object FeedTopicNames {
  private val names: Map<String, Int> = mapOf(
    "ai" to R.string.TellomiFeedTopic__ai,
    "tech" to R.string.TellomiFeedTopic__tech,
    "dev" to R.string.TellomiFeedTopic__dev,
    "games" to R.string.TellomiFeedTopic__games,
    "anime" to R.string.TellomiFeedTopic__anime,
    "film" to R.string.TellomiFeedTopic__film,
    "music" to R.string.TellomiFeedTopic__music,
    "books" to R.string.TellomiFeedTopic__books,
    "photo" to R.string.TellomiFeedTopic__photo,
    "design" to R.string.TellomiFeedTopic__design,
    "food" to R.string.TellomiFeedTopic__food,
    "baking" to R.string.TellomiFeedTopic__baking,
    "explore" to R.string.TellomiFeedTopic__explore,
    "travel" to R.string.TellomiFeedTopic__travel,
    "camping" to R.string.TellomiFeedTopic__camping,
    "fitness" to R.string.TellomiFeedTopic__fitness,
    "running" to R.string.TellomiFeedTopic__running,
    "ballsports" to R.string.TellomiFeedTopic__ballsports,
    "fashion" to R.string.TellomiFeedTopic__fashion,
    "beauty" to R.string.TellomiFeedTopic__beauty,
    "portrait" to R.string.TellomiFeedTopic__portrait,
    "pets" to R.string.TellomiFeedTopic__pets,
    "home" to R.string.TellomiFeedTopic__home,
    "parenting" to R.string.TellomiFeedTopic__parenting,
    "work" to R.string.TellomiFeedTopic__work,
    "frugal" to R.string.TellomiFeedTopic__frugal,
    "cars" to R.string.TellomiFeedTopic__cars,
    "science" to R.string.TellomiFeedTopic__science,
    "health" to R.string.TellomiFeedTopic__health,
    "mood" to R.string.TellomiFeedTopic__mood,
    "funny" to R.string.TellomiFeedTopic__funny,
    "diy" to R.string.TellomiFeedTopic__diy,
    "plants" to R.string.TellomiFeedTopic__plants,
    "city" to R.string.TellomiFeedTopic__city,
    "campus" to R.string.TellomiFeedTopic__campus,
    "news" to R.string.TellomiFeedTopic__news
  )

  @StringRes
  fun of(slug: String): Int? = names[slug]
}
