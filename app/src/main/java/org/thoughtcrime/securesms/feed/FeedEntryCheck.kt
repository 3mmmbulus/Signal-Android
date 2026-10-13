/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * 动态入口跟着内容引擎走（契约 3.1）：回到前台时如果离上次问已经过了 [STALE_AFTER_MILLIS]，问一次 `status`，
 * 记住 `visible`；变了就让底栏重画。入口隐藏随这次拉取生效（R-2.11），不另作时间承诺。
 *
 * 网络失败不改记住的值（不因为一次断网把入口藏起来，也不因此放出来）；引擎回 404 `feed_hidden` 算「不能看」。
 */
class FeedEntryCheck(
  private val shouldAsk: () -> Boolean,
  private val source: () -> FeedSource,
  private val state: State,
  private val clock: () -> Long = System::currentTimeMillis
) {

  interface State {
    var visible: Boolean
    var checkedAt: Long
  }

  companion object {
    private val TAG = Log.tag(FeedEntryCheck::class)

    const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

    @JvmStatic
    fun forCurrentProcess(): FeedEntryCheck {
      return FeedEntryCheck(
        shouldAsk = TellomiFeed::shouldAskEngine,
        source = FeedSources::engine,
        state = object : State {
          override var visible: Boolean
            get() = SignalStore.tellomiFeed.engineVisible
            set(value) {
              SignalStore.tellomiFeed.engineVisible = value
            }
          override var checkedAt: Long
            get() = SignalStore.tellomiFeed.engineCheckedAt
            set(value) {
              SignalStore.tellomiFeed.engineCheckedAt = value
            }
        }
      )
    }
  }

  /** 过期了才问；返回记住的 `visible` 有没有变（变了要刷新底栏）。会走网络，别在主线程调。 */
  suspend fun refreshIfStale(): Boolean {
    if (!shouldAsk()) return false
    val now = clock()
    if (state.checkedAt != 0L && now - state.checkedAt in 0 until STALE_AFTER_MILLIS) return false

    val visible = try {
      source().isVisible()
    } catch (e: FeedApiException) {
      if (!e.isFeedDisabled) {
        Log.w(TAG, "status failed: ${e.status} ${e.code}")
        return false
      }
      false
    } catch (e: Exception) {
      Log.w(TAG, "status failed", e)
      return false
    }

    val changed = visible != state.visible
    state.visible = visible
    state.checkedAt = now
    return changed
  }
}
