/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedCard

class FeedBlockedKeywordsTest {

  @Test
  fun `expired words drop out and adding again replaces the old one`() {
    val store = InMemoryFeedBlockedKeywords()
    store.add("剧透", expiresAt = 100)
    store.add("Diet", expiresAt = null)
    store.add("diet", expiresAt = 50)

    assertThat(store.active(now = 10).map { it.keyword }).containsExactly("diet", "剧透")
    assertThat(store.active(now = 200).map { it.keyword }).containsExactly()
  }

  @Test
  fun `cards match on title or text ignoring case`() {
    val words = listOf(FeedLocalKeyword("diet"))
    assertThat(FeedCard(id = "1", kind = FeedCard.KIND_POST, text = "My DIET plan").matchesAny(words)).isTrue()
    assertThat(FeedCard(id = "2", kind = FeedCard.KIND_ARTICLE, title = "Diet notes", text = "x").matchesAny(words)).isTrue()
    assertThat(FeedCard(id = "3", kind = FeedCard.KIND_POST, text = "Sourdough").matchesAny(words)).isFalse()
    assertThat(FeedCard(id = "4", kind = FeedCard.KIND_POST, text = "diet").matchesAny(emptyList())).isFalse()
  }
}
