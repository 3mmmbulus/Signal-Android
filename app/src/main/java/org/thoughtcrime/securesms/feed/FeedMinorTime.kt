/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 未成年人模式的时间限制（`dynamic-feed.md` 第三节第 31 条）：动态每天最多 40 分钟，22:00–6:00 不能用。
 * 是不是未成年人模式由引擎 `config.minor_mode` 说了算；时间按手机本地时间算，只管动态标签，聊天不受影响。
 */
object FeedMinorTime {
  const val DAILY_LIMIT_MILLIS = 40 * 60_000L
  const val NIGHT_START_HOUR = 22
  const val NIGHT_END_HOUR = 6

  /** 多久记一次用了多少（也是到点后多快锁上）。 */
  const val TICK_MILLIS = 15_000L

  const val TEST_TAG_LOCK = "feed_minor_lock"

  enum class Lock { NIGHT, DAILY }

  fun lock(now: LocalDateTime, usedMillisToday: Long): Lock? {
    return when {
      now.hour >= NIGHT_START_HOUR || now.hour < NIGHT_END_HOUR -> Lock.NIGHT
      usedMillisToday >= DAILY_LIMIT_MILLIS -> Lock.DAILY
      else -> null
    }
  }
}

/** 今天在动态上用了多久。换了一天从零算。 */
interface FeedMinorUsage {
  fun usedMillis(day: LocalDate): Long
  fun add(day: LocalDate, millis: Long)
}

open class InMemoryFeedMinorUsage : FeedMinorUsage {
  protected var day: LocalDate? = null
  protected var used: Long = 0

  override fun usedMillis(day: LocalDate): Long = if (day == this.day) used else 0

  override fun add(day: LocalDate, millis: Long) {
    if (millis <= 0) return
    used = usedMillis(day) + millis
    this.day = day
    onChanged()
  }

  protected open fun onChanged() = Unit
}

/** 存在本机（`SignalStore.tellomiFeed`），关掉 App 再开不清零。 */
class StoredFeedMinorUsage : InMemoryFeedMinorUsage() {
  init {
    val values = SignalStore.tellomiFeed
    day = values.minorUsageDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    used = values.minorUsedMillis
  }

  override fun onChanged() {
    val values = SignalStore.tellomiFeed
    values.minorUsageDay = day?.toString()
    values.minorUsedMillis = used
  }
}

/**
 * 动态标签开着（前台）时每 [FeedMinorTime.TICK_MILLIS] 记一次时，返回现在该不该锁。不是未成年人模式时一直是 null、不计时。
 */
@Composable
fun rememberFeedMinorLock(
  minorMode: Boolean,
  usage: () -> FeedMinorUsage = { StoredFeedMinorUsage() },
  clock: () -> LocalDateTime = LocalDateTime::now
): FeedMinorTime.Lock? {
  if (!minorMode) return null
  val store = remember { usage() }
  fun current(): FeedMinorTime.Lock? {
    val now = clock()
    return FeedMinorTime.lock(now, store.usedMillis(now.toLocalDate()))
  }
  var lock by remember { mutableStateOf(current()) }
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, store) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      var last = SystemClock.elapsedRealtime()
      while (true) {
        lock = current()
        delay(FeedMinorTime.TICK_MILLIS)
        val now = SystemClock.elapsedRealtime()
        // 锁着的时候不算用了
        if (lock == null) store.add(clock().toLocalDate(), now - last)
        last = now
      }
    }
  }
  return lock
}

/** 锁上时动态标签整页换成这一页。 */
@Composable
fun FeedMinorLockScreen(lock: FeedMinorTime.Lock, modifier: Modifier = Modifier) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 32.dp)
      .testTag(FeedMinorTime.TEST_TAG_LOCK)
  ) {
    Icon(
      imageVector = ImageVector.vectorResource(R.drawable.symbol_timer_24),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(48.dp)
    )
    Text(
      text = stringResource(if (lock == FeedMinorTime.Lock.NIGHT) R.string.TellomiFeedMinor__night_title else R.string.TellomiFeedMinor__daily_title),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 16.dp)
    )
    Text(
      text = stringResource(if (lock == FeedMinorTime.Lock.NIGHT) R.string.TellomiFeedMinor__night_body else R.string.TellomiFeedMinor__daily_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp)
    )
  }
}

/**
 * 动态里单独打开的页面（详情、主页、话题、搜索、发布……）用它代替 `setContent`：未成年人模式到点了整页换成锁屏页，
 * 用时也算进同一份。是不是未成年人模式用动态页上一次拿到的 `config`（[org.thoughtcrime.securesms.keyvalue.TellomiFeedValues.minorMode]）。
 * 举报和推荐设置不套：举报和隐私设置任何时候都要能用。
 */
fun ComponentActivity.setFeedContent(content: @Composable () -> Unit) {
  setContent {
    val minorMode = remember { SignalStore.tellomiFeed.minorMode }
    val lock = rememberFeedMinorLock(minorMode = minorMode)
    if (lock == null) {
      // 能力检查（契约 3.0）：没部署的入口不画；拿不到按没部署
      val entries by remember { FeedCapabilityStore.flow() }.collectAsState()
      CompositionLocalProvider(LocalFeedEntries provides entries) {
        content()
      }
    } else {
      SignalTheme {
        Surface {
          FeedMinorLockScreen(lock)
        }
      }
    }
  }
}
