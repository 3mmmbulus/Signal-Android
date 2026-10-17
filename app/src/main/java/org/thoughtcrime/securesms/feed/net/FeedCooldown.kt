/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

/**
 * 被限流（429，或带 `Retry-After` 的 503）之后的「冷却」：这段时间里不再发请求，本地直接失败。
 *
 * - [onThrottled]：服务端给了 `Retry-After`（已经夹到 1…3600 秒，见 [FeedRetryAfter]）就等那么久，原样，不加抖动；
 *   没给就指数退避：第 n 次连续被限流等 `min(cap, base × 2^(n-1))` 的一半加上另一半里的随机数（「equal jitter」），夹在 1…3600 秒。
 *   同一时刻有几次限流回来，取最晚的那个结束时间，不会因为后到的短等待把长等待缩短。
 * - [onSuccess]：一次成功的响应清掉连续次数（冷却本身不提前结束）。
 * 时钟和随机数都可以注入（测试用）；时钟往回拨过（现在早于冷却开始）按已经结束算，不会被锁在冷却里。
 */
class FeedCooldown(
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: () -> Double = Math::random,
  private val baseMillis: Long = BASE_MILLIS,
  private val capMillis: Long = CAP_MILLIS
) {
  companion object {
    /** 没有 `Retry-After` 时第一次等多久（上限，实际在一半到全部之间）。 */
    const val BASE_MILLIS = 30_000L

    /** 指数退避的封顶，和 `Retry-After` 的上限一样：1 小时。 */
    const val CAP_MILLIS = FeedRetryAfter.MAX_SECONDS * 1000
  }

  private var untilMillis = 0L
  private var startedMillis = 0L
  private var streak = 0

  /** 冷却还剩多少毫秒；没有在冷却是 0。 */
  @Synchronized
  fun remainingMillis(): Long {
    val now = clock()
    if (now < startedMillis) return 0L // 时钟往回拨过
    return (untilMillis - now).coerceAtLeast(0L)
  }

  fun isCoolingDown(): Boolean = remainingMillis() > 0L

  /** 还要等几秒（向上取整，至少 1）；没有在冷却是 0。给 `FeedApiException.retryAfterSeconds` 用。 */
  fun remainingSeconds(): Long {
    val millis = remainingMillis()
    return if (millis <= 0L) 0L else FeedRetryAfter.clamp((millis + 999) / 1000)
  }

  /** 记一次限流；返回这次要等的毫秒数。[retryAfterSeconds] 是 [FeedRetryAfter.parse] 的结果（可以是 null）。 */
  @Synchronized
  fun onThrottled(retryAfterSeconds: Long?): Long {
    val now = clock()
    streak++
    val wait = if (retryAfterSeconds != null) {
      FeedRetryAfter.clamp(retryAfterSeconds) * 1000
    } else {
      val exponent = (streak - 1).coerceAtMost(30)
      val raw = minOf(capMillis, baseMillis * (1L shl exponent).coerceAtLeast(1L)).coerceAtLeast(1L)
      val jittered = raw / 2 + (raw / 2 * random().coerceIn(0.0, 1.0)).toLong()
      jittered.coerceIn(FeedRetryAfter.MIN_SECONDS * 1000, FeedRetryAfter.MAX_SECONDS * 1000)
    }
    val end = now + wait
    if (now >= untilMillis || now < startedMillis) {
      startedMillis = now
      untilMillis = end
    } else if (end > untilMillis) {
      untilMillis = end
    }
    return wait
  }

  @Synchronized
  fun onSuccess() {
    streak = 0
  }

  /** 退出登录、切区：什么都不记得。 */
  @Synchronized
  fun reset() {
    untilMillis = 0L
    startedMillis = 0L
    streak = 0
  }
}
