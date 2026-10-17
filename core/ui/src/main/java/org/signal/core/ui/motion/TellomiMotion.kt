/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import org.signal.core.util.AccessibilityUtil
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Tellomi 交互与动效标准（tellomi/tellomi `docs/product/INTERACTION_MOTION.md` 第三节）在 Android 上的数值。
 *
 * 页面代码不写时长和曲线，只从这里取。数值取自 Material 3 MotionScheme 的官方 token：
 * owner 2026-09-26 定的弹簧性格是「活泼」，所以空间类用 Expressive 一档，效果类（透明度、颜色）用 Standard，永不过冲。
 * 两处不是 M3 预设：[Move] 的阻尼按 owner「明显回弹，两端一致」对齐 iOS；[Scroll] 是临界阻尼（M3 没有滚动定位的 token）。
 * 不依赖仍是 alpha 的 Compose Expressive API，常量写在这里。
 *
 * View 体系用同样两个数：`SpringForce().setStiffness(token.stiffness).setDampingRatio(token.dampingRatio)`。
 */
object TellomiMotion {

  /** 一根弹簧：[stiffness] 与 [dampingRatio]（1 = 临界阻尼，不回弹）。 */
  data class SpringToken(val stiffness: Float, val dampingRatio: Float) {
    /** 与 iOS `(duration, bounce)` 对照：duration = 2π / √stiffness（质量 1）。 */
    val perceptualDurationSeconds: Double get() = 2 * PI / sqrt(stiffness.toDouble())

    /** 与 iOS 对照：bounce = 1 − dampingRatio。 */
    val bounce: Float get() = 1f - dampingRatio
  }

  /** 按下缩放。按下永远不回弹：Standard 空间快。 */
  val Press = SpringToken(stiffness = 1400f, dampingRatio = 0.9f)

  /** 按下高亮（透明度 / 颜色）：Effects 快。 */
  val PressHighlight = SpringToken(stiffness = 3800f, dampingRatio = 1f)

  /** 松开弹回：Expressive 空间快。 */
  val Release = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /** 小元素出现 / 消失、胶囊、角标：Expressive 空间快。 */
  val Snap = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /**
   * 菜单、Sheet 落档、共享元素、列表补位：刚度取 Expressive 空间默认，阻尼 0.7——回弹约 5%，看得出来，
   * 与 iOS `move` 同一个回弹（owner 2026-09-26 定「明显回弹，两端一致」；M3 预设的 0.8 只回弹 1.5%）。
   */
  val Move = SpringToken(stiffness = 380f, dampingRatio = 0.7f)

  /** 整屏级（查看器开合）：Expressive 空间慢，回弹约 1.5%，与 iOS `large` 同一个回弹。 */
  val Large = SpringToken(stiffness = 200f, dampingRatio = 0.8f)

  /** 回应落定、一次性成功：Expressive 空间快。 */
  val Emphasis = SpringToken(stiffness = 800f, dampingRatio = 0.6f)

  /** 透明度、颜色：Effects 默认，永不过冲。 */
  val Fade = SpringToken(stiffness = 1600f, dampingRatio = 1f)

  /** 滚动位置（跳转、回到底部）。滚动位置永远不回弹：临界阻尼，不是 M3 预设。 */
  val Scroll = SpringToken(stiffness = 300f, dampingRatio = 1f)

  /** Compose 用的弹簧规格。「移除动画」打开时 Compose 自己把动画缩到零，这里不用再判断。 */
  fun <T> spec(token: SpringToken, visibilityThreshold: T? = null): SpringSpec<T> {
    return spring(dampingRatio = token.dampingRatio, stiffness = token.stiffness, visibilityThreshold = visibilityThreshold)
  }

  /**
   * 动效档位（标准第七节）：
   * - [FULL]：全部动效。实时模糊另要 API 31+（[allowsLiveBlur]）；Android 11 及以下也是完整档，只是不模糊（owner 2026-09-26）。
   * - [SIMPLIFIED]：省电模式、低内存机。关实时模糊、循环 / 背景动画、飞行类动画（例如回应飞入），共享元素改淡入淡出；弹簧照常。
   * - [MINIMAL]：用户关了动画（「移除动画」/ 动画时长缩放为 0），只保留跟手与状态变化。
   */
  enum class Tier { FULL, SIMPLIFIED, MINIMAL }

  /** 判档要用的设备信号；拆出来是为了判档规则能脱离设备单测。 */
  data class DeviceSignals(
    val animatorsEnabled: Boolean,
    val powerSave: Boolean,
    val lowRamDevice: Boolean,
    /** `ActivityManager.getMemoryClass()`，单位 MB；拿不到时为 0。 */
    val memoryClassMb: Int
  )

  /** 每个应用堆上限不超过这个值（MB）的机器按低端处理。 */
  const val LOW_MEMORY_CLASS_MB = 128

  fun tierFor(signals: DeviceSignals): Tier {
    return when {
      !signals.animatorsEnabled -> Tier.MINIMAL
      signals.powerSave -> Tier.SIMPLIFIED
      signals.lowRamDevice -> Tier.SIMPLIFIED
      signals.memoryClassMb in 1..LOW_MEMORY_CLASS_MB -> Tier.SIMPLIFIED
      else -> Tier.FULL
    }
  }

  fun signals(context: Context): DeviceSignals {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val animatorsEnabled = if (Build.VERSION.SDK_INT >= 26) {
      ValueAnimator.areAnimatorsEnabled()
    } else {
      !AccessibilityUtil.areAnimationsDisabled(context)
    }
    return DeviceSignals(
      animatorsEnabled = animatorsEnabled,
      powerSave = powerManager?.isPowerSaveMode == true,
      lowRamDevice = activityManager?.isLowRamDevice == true,
      memoryClassMb = activityManager?.memoryClass ?: 0
    )
  }

  fun tier(context: Context): Tier = tierFor(signals(context))

  /**
   * 是否允许实时模糊。owner 2026-09-26 定：Android 长按消息的背景按档位，只有「完整」档模糊，其余只压暗；
   * 实时模糊（RenderEffect）要 API 31+，更早的系统完整档也只压暗。
   */
  fun allowsLiveBlur(tier: Tier, sdkInt: Int = Build.VERSION.SDK_INT): Boolean = tier == Tier.FULL && sdkInt >= Build.VERSION_CODES.S

  /** 是否播放飞行类、循环类等装饰性更强的动效（例如回应表情飞到胶囊）。简化档与最少档只做落点处的状态变化。 */
  fun allowsFlourish(tier: Tier): Boolean = tier == Tier.FULL

  /**
   * 发送动画（M9，设计规范 `bubbles-and-motion-design.md` 第 4 节）：点发送后，新气泡从输入框飞到会话里它的位置。
   *
   * 时长是 owner 2026-09-26 在 #1204 演示页选的 280 ms（Telegram 安卓 250、iOS 300）。其余照 Telegram 两端（只读机制，
   * owner 2026-10-14「聊天类照 Telegram，两端都有就做」）：横向、纵向各一条曲线，横向前快、纵向跟列表，走出一道弧线；
   * 输入框的框变成气泡的框；气泡底色连同尾巴很快淡入，输入框里的字和气泡里的字交叉淡入淡出。iOS `TellomiMotion.sendFlight` 同一组数。
   */
  object SendFlight {
    const val DURATION_MS = 280L

    /** 气泡底色（连同尾巴）由透明到不透明；同一段时间里输入框的字淡出、气泡的字淡入（Telegram 两端约 100 ms）。 */
    const val BUBBLE_FADE_IN_MS = 120L

    /** 横向：前快后慢，三分之一的时间就走完八成多（Telegram iOS `horizontalAnimationCurve`；安卓是同一形状的 EASE_OUT∘EASE_OUT_QUINT）。 */
    val X_CURVE = floatArrayOf(0.23f, 1f, 0.32f, 1f)

    /** 纵向：Telegram 两端列表插入新消息用的同一条曲线（iOS `verticalAnimationControlPoints`、安卓 `ChatListItemAnimator.DEFAULT_INTERPOLATOR`）。 */
    val Y_CURVE = floatArrayOf(0.19919f, 0.01064f, 0.27921f, 0.91025f)

    /** 等新的一行进列表最多等这么久；等不到就不飞（Telegram 安卓等 200 ms，本地库写入慢的机器上给宽一点）。 */
    const val WAIT_FOR_ROW_MS = 600L

    /** 不飞的时候（等不到、页面要走），留在输入框原处的那几行字淡掉、输入框淡回。 */
    const val CANCEL_FADE_MS = 150L

    /** 输入框超过这么多行就不飞（输入框最多显示 5 行，再多它自己滚动，看不全；Telegram 安卓超过 10 行不飞）。 */
    const val MAX_LINES = 5
  }

  /**
   * 传完的图从占位（模糊图 / 缩略图）上交叉淡入（交互审计 A-13）：照 Telegram 两端 150 ms——Android `ImageReceiver`
   * 的 `DEFAULT_CROSSFADE_DURATION`，iOS `TransformImageNode` `.subsequentUpdates` 旧图 0.15 s 淡掉。
   * iOS 同一个数：`TellomiMotion.fadeOutDuration`（`TellomiTransferHandoff`）。Glide 自己的默认是 300 ms。
   */
  const val THUMBNAIL_CROSSFADE_MS = 150

  /** 尾巴交接（设计规范第 3 节）：新消息接到同一组时，上一条的尾巴淡出；它的下角本来就是 4，不用变形。 */
  object TailHandover {
    const val DURATION_MS = 150L

    /** ease-out：`cubic-bezier(0, 0, 0.2, 1)`。 */
    val CURVE = floatArrayOf(0f, 0f, 0.2f, 1f)
  }

  /**
   * 发送动画飞不飞：用户关了动画（最少档）不飞；低内存机不飞（Telegram 安卓低端机也不飞）。
   * 省电模式照飞——设计规范第 10 节「发送动画：省电模式同正常」，这里不用 [allowsFlourish]（它把省电模式也算进简化档）。
   */
  fun allowsSendFlight(signals: DeviceSignals): Boolean {
    return signals.animatorsEnabled && !signals.lowRamDevice && signals.memoryClassMb !in 1..LOW_MEMORY_CLASS_MB
  }
}
