/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Tellomi（交互审计 A-06 / A-07，owner 2026-10-17「和 Telegram 还不一样」）：长按消息时的回应条，照 Telegram 安卓
 * `ReactionsContainerLayout` 的样子和出场（只取数，不搬代码）。iOS 同一刀是 Signal-iOS#276。
 *
 * - 7 个表情 + 1 个展开键；条目 36 dp，两头各 8 dp，高 44 dp 的胶囊。
 * - 「思考泡泡」尾巴：大圆半径 8 dp、圆心骑在条下沿、离消息那一头 36 dp；小圆半径 4 dp，在大圆外侧斜下方。
 * - 出场：0.25 s、Overshoot(0.5)，条从消息那一头缩放展开（底板从那一头往另一头揭开，先露出 25%）；
 *   表情各自 0.3 s 从 0 弹到原大，延迟 200 ms × |表情中心在条上的位置 − 锚点|，锚点是消息那一头的 80%（另一头是 20%）；
 *   尾巴两个圆在进度过了 25% 之后跟着长出来。
 * - 展开：条就地长成全部表情的面板（下面「原地展开」一段）。
 */
object TellomiReactionStrip {

  /** 7 个表情 + 展开键。 */
  const val SLOT_COUNT = 8
  const val REACTION_COUNT = SLOT_COUNT - 1

  const val ITEM_SIZE_DP = 36f
  const val SIDE_PADDING_DP = 8f
  const val HEIGHT_DP = 44f
  const val EXPAND_BUTTON_SIZE_DP = 30f

  const val BIG_TAIL_RADIUS_DP = 8f
  const val SMALL_TAIL_RADIUS_DP = 4f

  /** 大圆圆心离条在消息那一头的端点多远（Telegram `bigCircleOffset`）。 */
  const val TAIL_OFFSET_DP = 36f

  const val ENTER_DURATION_MS = 250
  const val ENTER_OVERSHOOT_TENSION = 0.5f

  /** 底板最先露出来的那一截，尾巴也是过了这个进度才开始长（Telegram `CLIP_PROGRESS`）。 */
  const val CLIP_PROGRESS = 0.25f

  const val ITEM_ENTER_DURATION_MS = 300
  const val ITEM_ENTER_OVERSHOOT_TENSION = 2f
  private const val ITEM_STAGGER_MS = 200f

  /** 锚点在条上的位置：消息那一头的 80%。 */
  private const val ANCHOR_FRACTION = 0.8f

  /** 显示时补第 7 个的固定候补：Telegram 默认回应里 Signal 默认没有的几个。 */
  val FALLBACK = listOf("🔥", "🙏", "🎉", "👏")

  /** 条的总宽：8 格 × 36 + 两头 8。 */
  val WIDTH_DP: Float = SLOT_COUNT * ITEM_SIZE_DP + SIDE_PADDING_DP * 2

  /**
   * 条上放哪 7 个表情。用户选的 6 个会同步到别的设备和备份，**不改那一组**，只在显示时补第 7 个：
   * 我这次已经回应、但不在 6 个里的 → Signal 默认里被换掉的 → 最近用过的 → 固定候补。和 iOS `TellomiReactionBarSlots` 一样。
   *
   * [isSame] 判断两个表情算不算同一个（肤色、变体选择符）。
   */
  @JvmStatic
  fun reactions(
    saved: List<String>,
    applied: String?,
    signalDefaults: List<String>,
    recents: List<String>,
    fallback: List<String> = FALLBACK,
    isSame: (String, String) -> Boolean = { a, b -> a == b }
  ): List<String> {
    val slots = saved.take(REACTION_COUNT).toMutableList()
    val candidates = listOfNotNull(applied) + signalDefaults + recents + fallback
    for (candidate in candidates) {
      if (slots.size >= REACTION_COUNT) {
        break
      }
      if (slots.none { isSame(it, candidate) }) {
        slots += candidate
      }
    }
    return slots
  }

  /** 第 [index] 格（0 起）在条上的中心位置，0–1，从左往右。 */
  @JvmStatic
  fun slotCenterFraction(index: Int): Float {
    val width = WIDTH_DP
    return (SIDE_PADDING_DP + ITEM_SIZE_DP * (index + 0.5f)) / width
  }

  /**
   * 第 [index] 格出场的延迟（毫秒）。[anchorOnLeft]：消息在左边、条从左头长出来。
   * [index] 按屏幕从左往右数（RTL 时调用方先换算）。
   */
  @JvmStatic
  fun itemEnterDelayMs(index: Int, anchorOnLeft: Boolean): Long {
    val anchor = if (anchorOnLeft) 1f - ANCHOR_FRACTION else ANCHOR_FRACTION
    return (ITEM_STAGGER_MS * abs(slotCenterFraction(index) - anchor)).roundToLong()
  }

  /** 尾巴圆的大小比例：出场进度过了 [CLIP_PROGRESS] 才开始长，到 1 长满。 */
  @JvmStatic
  fun tailProgress(enterProgress: Float): Float {
    val clamped = max(CLIP_PROGRESS, min(enterProgress, 1f))
    return (clamped - CLIP_PROGRESS) / (1f - CLIP_PROGRESS)
  }

  /**
   * 底板在出场进度 [progress] 时露出来的左右边（条宽的比例，0–1）：从锚点那一头往另一头揭开，最少露出 [CLIP_PROGRESS]。
   */
  @JvmStatic
  fun revealedSpan(progress: Float, anchorOnLeft: Boolean): ClosedFloatingPointRange<Float> {
    val shown = max(CLIP_PROGRESS, progress).coerceAtMost(1f)
    return if (anchorOnLeft) 0f..shown else (1f - shown)..1f
  }

  /** 出场时整条缩放的支点（条宽的比例）：消息那一头往里 12.5%。 */
  @JvmStatic
  fun pivotFraction(anchorOnLeft: Boolean): Float = if (anchorOnLeft) 0.125f else 0.875f

  /** 大圆圆心的横坐标（条内，dp）。 */
  @JvmStatic
  fun bigTailCenterXDp(anchorOnLeft: Boolean): Float = if (anchorOnLeft) TAIL_OFFSET_DP else WIDTH_DP - TAIL_OFFSET_DP

  /** 小圆圆心的横坐标（条内，dp）：在大圆朝外那一侧。 */
  @JvmStatic
  fun smallTailCenterXDp(anchorOnLeft: Boolean): Float {
    val big = bigTailCenterXDp(anchorOnLeft)
    return if (anchorOnLeft) big - BIG_TAIL_RADIUS_DP else big + BIG_TAIL_RADIUS_DP
  }

  /** 小圆圆心离条下沿多远（dp）：挂在大圆下面再隔 2 dp。 */
  const val SMALL_TAIL_DROP_DP = BIG_TAIL_RADIUS_DP + 2f + SMALL_TAIL_RADIUS_DP

  // 原地展开（A-06，owner 2026-10-17）：点 ⌄、在 ⌄ 上松手、或按住 ⌄ 往条的开头拉，条就地长成表情面板，不另弹 Sheet。
  // 数取自 Telegram 安卓 `ReactionsContainerLayout`（拉）和 `CustomEmojiReactionsWindow`（长成面板）。

  /** 拉的进度以 42 dp 为 1（Telegram `getPullingLeftProgress`）。 */
  const val PULL_UNIT_DP = 42f

  /**
   * 手指往开头走过这么多才算开始拉，进度从这里起算（相当于 Telegram 列表的 touch slop）。
   * 上游的点按死区有 40 dp，等它判定就太晚了：拉了一半还算点。
   */
  const val PULL_SLOP_DP = 8f

  /** 手指每走 1，条只跟 0.6；进度过了 1 只跟 0.05（Telegram 的 k）。 */
  const val PULL_RESISTANCE = 0.6f
  const val PULL_RESISTANCE_PAST_ONE = 0.05f
  const val PULL_MAX_PROGRESS = 2f

  /** 松手时进度过了 0.95 就展开，否则 150 ms 弹回去。 */
  const val PULL_EXPAND_THRESHOLD = 0.95f
  const val PULL_BACK_DURATION_MS = 150

  /** 拉的时候表情往开头挤，离开头越远挤得越多，进度 1 时最远的挪 46 dp（Telegram `checkPressedProgress`）。 */
  const val PULL_ITEM_SHIFT_DP = 46f

  /** 换算挤的比例时条宽要扣掉的那段（Telegram 的 `getMeasuredWidth() - dp(34)`）。 */
  private const val PULL_SHIFT_WIDTH_INSET_DP = 34f

  /** ⌄ 在拉满（进度 1）时放大到多少：Telegram 在条尾长出一个新表情，这里让 ⌄ 自己鼓起来。 */
  const val PULL_EXPAND_BUTTON_GROWTH = 0.25f

  /** 长成面板：350 ms、`CubicBezierInterpolator.DEFAULT`（0.25, 0.1, 0.25, 1）。 */
  const val EXPAND_DURATION_MS = 350

  /** 面板宽 = 条宽，高最多 300 dp（Telegram 36 × 8 + 12）。 */
  const val PANEL_MAX_HEIGHT_DP = 300f

  /** 面板最矮多少：横屏再加键盘时也留得下搜索、两行表情和分类条。 */
  const val PANEL_MIN_HEIGHT_DP = 160f

  /** 面板顶在条顶上面 96 dp（Telegram 44 + 52）；离上沿至少 16 dp，离下沿（导航条 / 键盘之上）至少 32 dp。 */
  const val PANEL_LIFT_DP = 96f
  const val PANEL_TOP_MARGIN_DP = 16f
  const val PANEL_BOTTOM_MARGIN_DP = 32f

  /** 圆角从条的半高变到 8 dp。 */
  const val PANEL_CORNER_RADIUS_DP = 8f

  /** 条上的表情在展开的前 20% 就淡完（Telegram `1 - progress / 0.2`），面板内容跟着进度淡入。 */
  private const val STRIP_FADE_SPAN = 0.2f

  /**
   * 手指从按下 ⌄ 的地方往条的开头走了 [distance]（和 [unit] 同单位），换算成拉的进度 0–[PULL_MAX_PROGRESS]。
   * Telegram 是一步一步累加的；手指一路往一个方向走时，结果和这里一次算出来的一样。
   */
  @JvmStatic
  fun pullProgress(distance: Float, unit: Float): Float {
    if (distance <= 0f || unit <= 0f) {
      return 0f
    }
    val firstStretch = unit / PULL_RESISTANCE
    val offset = if (distance <= firstStretch) {
      distance * PULL_RESISTANCE
    } else {
      unit + (distance - firstStretch) * PULL_RESISTANCE_PAST_ONE
    }
    return (offset / unit).coerceIn(0f, PULL_MAX_PROGRESS)
  }

  /** 拉到 [progress] 时第 [index] 格（按排版方向从开头数）往开头挪多少 dp。 */
  @JvmStatic
  fun pullShiftDp(index: Int, progress: Float): Float {
    val left = SIDE_PADDING_DP + ITEM_SIZE_DP * index
    val fraction = (left / (WIDTH_DP - PULL_SHIFT_WIDTH_INSET_DP)).coerceIn(0f, 1f)
    return fraction * progress.coerceIn(0f, PULL_MAX_PROGRESS) * PULL_ITEM_SHIFT_DP
  }

  /** 展开进度 [expand] 时条上的表情、尾巴还剩多少不透明度。 */
  @JvmStatic
  fun stripAlphaWhileExpanding(expand: Float): Float = (1f - expand / STRIP_FADE_SPAN).coerceIn(0f, 1f)

  /**
   * 面板放在哪（和 [stripTop]、[top]、[bottom] 同一坐标系、同单位，[density] 把 dp 换过去）：
   * 高 = min(300 dp, 能用的高度)，不低于 [PANEL_MIN_HEIGHT_DP]；顶 = 条顶 − 96 dp，再夹在 [top] + 16 dp 和 [bottom] − 32 dp − 高 之间。
   * [bottom] 是导航条或键盘的上沿。返回 (顶, 高)。
   */
  @JvmStatic
  fun panelVerticalFrame(stripTop: Float, top: Float, bottom: Float, density: Float): Pair<Float, Float> {
    val minTop = top + PANEL_TOP_MARGIN_DP * density
    val maxBottom = bottom - PANEL_BOTTOM_MARGIN_DP * density
    val height = min(PANEL_MAX_HEIGHT_DP * density, max(PANEL_MIN_HEIGHT_DP * density, maxBottom - minTop))
    val wanted = stripTop - PANEL_LIFT_DP * density
    val panelTop = max(minTop, min(wanted, maxBottom - height))
    return panelTop to height
  }
}
