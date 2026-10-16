/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

/**
 * [FeedSources] 里「当前区那一份远程来源」的位置：同一个 [key]（区 + 主机）只造一次，换了 key 就造新的；
 * [reset] 把它丢掉，并清掉它背后的 token 缓存（退出登录时用：上一个账号的 token 不该留到下一次登录，
 * 而且 `AppDependencies.resetNetwork()` 之后旧来源手里的 websocket 也已经是过期的）。
 *
 * 造来源时顺带交一个 `clearTokens`（通常是 `CachingFeedTokenSource.clear`）。[reset] 等它清完才返回；
 * 已经在路上的换取会先做完再被清掉。丢掉之后下一次 [getOrCreate] 造一份全新的。
 */
class FeedSourceSlot<S : Any> {

  private class Entry<S>(val key: String, val source: S, val clearTokens: suspend () -> Unit)

  private var entry: Entry<S>? = null

  @Synchronized
  fun getOrCreate(key: String, create: () -> Pair<S, suspend () -> Unit>): S {
    entry?.takeIf { it.key == key }?.let { return it.source }
    val (source, clearTokens) = create()
    entry = Entry(key, source, clearTokens)
    return source
  }

  suspend fun reset() {
    detach()?.clearTokens?.invoke()
  }

  @Synchronized
  private fun detach(): Entry<S>? {
    val old = entry
    entry = null
    return old
  }
}
