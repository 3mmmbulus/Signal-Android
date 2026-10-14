/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.mediasend.screens.edit.image

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.ImageController
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1593 E5-3，Epic #1523）：调色页的工具列表。
 *
 * 照 Telegram 安卓的调整页签（`PhotoFilterView` 的列表 + `PhotoEditToolCell`，只读机制，代码自己写）：每行 40dp，左边 80dp 宽的名字
 * （右对齐、12sp），右边一条滑杆（左边距 96dp、右边距 24dp）；拖的时候名字换成数值（+35 / −35），停手 1 秒后换回名字。
 * 只列已经能画出来的工具（[AVAILABLE_TOOLS]），后面的子 Issue 做好一项加一项；色调两行、模糊 / 曲线页签等那几项做了再加。
 */
@Composable
internal fun AdjustmentsPanel(controller: ImageController, modifier: Modifier = Modifier) {
  // 照 Telegram 安卓：列表区高度固定（约四行半），工具多了在里面上下滚，不往上盖住照片。
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
      .padding(vertical = 4.dp)
      .heightIn(max = 180.dp)
      .verticalScroll(rememberScrollState())
  ) {
    AVAILABLE_TOOLS.forEach { tool ->
      AdjustmentRow(
        tool = tool,
        value = controller.adjustments[tool],
        onValueChange = { controller.setAdjustment(tool, it) }
      )
    }
    // Tellomi（tellomi/tellomi#1596 E5-6）：色调两行放在最后（照 Telegram 安卓 `PhotoFilterView` 的 tintShadowsTool / tintHighlightsTool）。
    TintRow(
      name = stringResource(R.string.ImageEditor_tellomi_adjust_tint_shadows),
      palette = ImageAdjustments.TINT_SHADOWS_PALETTE,
      selected = controller.adjustments.tintShadowsColor,
      onSelect = { controller.setTint(shadows = true, color = it) },
      testTag = "adjustment-tint-shadows"
    )
    TintRow(
      name = stringResource(R.string.ImageEditor_tellomi_adjust_tint_highlights),
      palette = ImageAdjustments.TINT_HIGHLIGHTS_PALETTE,
      selected = controller.adjustments.tintHighlightsColor,
      onSelect = { controller.setTint(shadows = false, color = it) },
      testTag = "adjustment-tint-highlights"
    )
  }
}

/**
 * 色调一行（照 Telegram 安卓 `PhotoEditRadioCell`）：左边 80dp 的名字，右边一排 8 个色块，第一个是「不加」（白色）；没选的是一个圈，
 * 选中的圈里多一个实心点。色值是自己挑的，个数和 Telegram 一样。强度固定 50%，没有强度滑杆（O8）。
 */
@Composable
private fun TintRow(name: String, palette: IntArray, selected: Int, onSelect: (Int) -> Unit, testTag: String) {
  val noneLabel = stringResource(R.string.ImageEditor_tellomi_adjust_tint_none)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(40.dp)
      .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = name,
      color = Color.White,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.width(80.dp)
    )
    Spacer(modifier = Modifier.width(16.dp))
    Row(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      palette.forEachIndexed { index, color ->
        TintSwatch(
          color = color,
          isSelected = color == selected,
          description = if (index == 0) noneLabel else "$name $index",
          onClick = { onSelect(color) },
          modifier = Modifier.testTag("$testTag-$index")
        )
      }
    }
    Spacer(modifier = Modifier.width(24.dp))
  }
}

@Composable
private fun TintSwatch(color: Int, isSelected: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val swatchColor = if (color == ImageAdjustments.NO_TINT) Color.White else Color(color)
  Box(
    modifier = modifier
      .size(28.dp)
      .clip(CircleShape)
      .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.size(20.dp)) {
      val ring = 2.dp.toPx()
      drawCircle(swatchColor, radius = size.minDimension / 2 - ring / 2, style = Stroke(width = ring))
      if (isSelected) {
        drawCircle(swatchColor, radius = size.minDimension / 2 - ring * 2.5f)
      }
    }
  }
}

/** 照 Telegram 安卓 `PhotoFilterView` 列表的顺序，只放已经能画的。 */
internal val AVAILABLE_TOOLS = listOf(
  ImageAdjustments.Tool.ENHANCE,
  ImageAdjustments.Tool.EXPOSURE,
  ImageAdjustments.Tool.CONTRAST,
  ImageAdjustments.Tool.SATURATION,
  ImageAdjustments.Tool.WARMTH,
  ImageAdjustments.Tool.FADE,
  ImageAdjustments.Tool.HIGHLIGHTS,
  ImageAdjustments.Tool.SHADOWS,
  ImageAdjustments.Tool.VIGNETTE,
  ImageAdjustments.Tool.GRAIN,
  ImageAdjustments.Tool.SHARPEN
)

/** 行里显示的数值：照 Telegram 安卓，整数，正的带 +。 */
internal fun adjustmentValueText(value: Float): String {
  val rounded = value.roundToInt()
  return if (rounded > 0) "+$rounded" else rounded.toString()
}

@Composable
private fun AdjustmentRow(tool: ImageAdjustments.Tool, value: Float, onValueChange: (Float) -> Unit) {
  val name = stringResource(tool.titleRes)
  var showValue by remember { mutableStateOf(false) }
  var changes by remember { mutableIntStateOf(0) }

  LaunchedEffect(changes) {
    if (changes > 0) {
      delay(1000)
      showValue = false
    }
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(40.dp)
      .testTag("adjustment-row-${tool.name}"),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.CenterEnd) {
      Crossfade(targetState = showValue, label = "adjustment-label") { valueShown ->
        Text(
          text = if (valueShown) adjustmentValueText(value) else name,
          color = if (valueShown) ADJUST_ACCENT else Color.White,
          fontSize = 12.sp,
          maxLines = 1,
          textAlign = TextAlign.End,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    Spacer(modifier = Modifier.width(16.dp))
    AdjustmentSeekBar(
      value = value,
      range = tool.range,
      contentDescription = name,
      onValueChange = {
        showValue = true
        changes++
        onValueChange(it)
      },
      modifier = Modifier
        .weight(1f)
        .fillMaxHeight()
        .testTag("adjustment-slider-${tool.name}")
    )
    Spacer(modifier = Modifier.width(24.dp))
  }
}

/**
 * 滑杆：2dp 的轨道，从 0 填到当前值（双极的从中间往两边填），16dp 的白色圆钮。按住任意位置左右拖，按拖动的距离改值（不会一按就跳到
 * 手指下）；整数一格。
 */
@Composable
internal fun AdjustmentSeekBar(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  contentDescription: String,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val currentValue by rememberUpdatedState(value)
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  val trackColor = Color.White.copy(alpha = 0.3f)
  val fillColor = ADJUST_ACCENT
  val span = range.endInclusive - range.start

  Canvas(
    modifier = modifier
      .pointerInput(range) {
        val thumbPx = THUMB_SIZE.toPx()
        var startValue = 0f
        var dragged = 0f
        detectHorizontalDragGestures(
          onDragStart = {
            startValue = currentValue
            dragged = 0f
          },
          onHorizontalDrag = { change, dragAmount ->
            change.consume()
            dragged += dragAmount
            val usable = (size.width - thumbPx).coerceAtLeast(1f)
            val newValue = (startValue + dragged / usable * span).coerceIn(range).roundToInt().toFloat()
            if (newValue != currentValue) {
              currentOnValueChange(newValue)
            }
          }
        )
      }
      .semantics {
        this.contentDescription = contentDescription
        progressBarRangeInfo = ProgressBarRangeInfo(value, range)
        setProgress { target ->
          currentOnValueChange(target.coerceIn(range).roundToInt().toFloat())
          true
        }
      }
  ) {
    val thumbPx = THUMB_SIZE.toPx()
    val usable = size.width - thumbPx
    fun x(v: Float) = thumbPx / 2 + (v - range.start) / span * usable
    val y = size.height / 2
    val track = TRACK_HEIGHT.toPx()
    drawLine(trackColor, Offset(thumbPx / 2, y), Offset(size.width - thumbPx / 2, y), strokeWidth = track, cap = StrokeCap.Round)
    val origin = x(0f.coerceIn(range))
    val current = x(value)
    drawLine(fillColor, Offset(origin, y), Offset(current, y), strokeWidth = track, cap = StrokeCap.Round)
    drawCircle(Color.White, radius = thumbPx / 2, center = Offset(current, y))
  }
}

private val THUMB_SIZE = 16.dp

/** 深色面板上的强调色：主题色在这里太暗，换一个亮一点的蓝（和 Telegram 安卓调整页数值、填充的作用一样）。 */
private val ADJUST_ACCENT = Color(0xFF5DB0FF)
private val TRACK_HEIGHT = 2.dp

internal val ImageAdjustments.Tool.titleRes: Int
  get() = when (this) {
    ImageAdjustments.Tool.SKIN -> R.string.ImageEditor_tellomi_adjust_skin
    ImageAdjustments.Tool.ENHANCE -> R.string.ImageEditor_tellomi_adjust_enhance
    ImageAdjustments.Tool.EXPOSURE -> R.string.ImageEditor_tellomi_adjust_exposure
    ImageAdjustments.Tool.CONTRAST -> R.string.ImageEditor_tellomi_adjust_contrast
    ImageAdjustments.Tool.SATURATION -> R.string.ImageEditor_tellomi_adjust_saturation
    ImageAdjustments.Tool.WARMTH -> R.string.ImageEditor_tellomi_adjust_warmth
    ImageAdjustments.Tool.FADE -> R.string.ImageEditor_tellomi_adjust_fade
    ImageAdjustments.Tool.HIGHLIGHTS -> R.string.ImageEditor_tellomi_adjust_highlights
    ImageAdjustments.Tool.SHADOWS -> R.string.ImageEditor_tellomi_adjust_shadows
    ImageAdjustments.Tool.VIGNETTE -> R.string.ImageEditor_tellomi_adjust_vignette
    ImageAdjustments.Tool.GRAIN -> R.string.ImageEditor_tellomi_adjust_grain
    ImageAdjustments.Tool.SHARPEN -> R.string.ImageEditor_tellomi_adjust_sharpen
  }
