/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.glide.compose.GlideImage
import org.signal.glide.compose.GlideImageScaleType
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历。有消息的日子才能选（其余灰掉），
 * 有图/视频的日子铺当天第一张缩略图。对齐 iOS `TellomiJumpToDateViewController` / Telegram 两端行为。
 */
object TellomiJumpToDateTestTags {
  const val SCREEN = "tellomi_jump_to_date_screen"
  const val CANCEL = "tellomi_jump_to_date_cancel"
  fun day(day: LocalDate): String = "tellomi_jump_to_date_day_$day"
}

@Composable
fun TellomiJumpToDateScreen(
  state: TellomiJumpToDateViewModel.State,
  onMonthVisible: (LocalDate) -> Unit,
  onPick: (LocalDate) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier
) {
  val gridState = rememberLazyGridState()
  val monthTitleFormatter = remember {
    DateTimeFormatter.ofPattern("yMMMM", Locale.getDefault())
  }

  LaunchedEffect(state.months, state.initialDay) {
    val section = state.months.indexOfFirst { YearMonth.from(it.monthStart) == YearMonth.from(state.initialDay) }
    if (section >= 0) {
      // Each month is: 1 header + itemCount cells. Scroll header into view.
      var index = 0
      for (i in 0 until section) {
        index += 1 + state.months[i].itemCount
      }
      gridState.scrollToItem(index)
    }
  }

  LaunchedEffect(gridState, state.months) {
    snapshotFlow {
      gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        // key is Pair(monthStart, itemOrHeader)
        (info.key as? Pair<*, *>)?.first as? LocalDate
      }.toSet()
    }
      .distinctUntilChanged()
      .collect { visibleMonths ->
        visibleMonths.forEach(onMonthVisible)
      }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .testTag(TellomiJumpToDateTestTags.SCREEN)
  ) {
    BottomSheets.Handle()

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
    ) {
      TextButton(
        onClick = onCancel,
        modifier = Modifier
          .align(Alignment.CenterStart)
          .testTag(TellomiJumpToDateTestTags.CANCEL)
      ) {
        Text(stringResource(android.R.string.cancel))
      }
      Text(
        text = stringResource(R.string.TellomiJumpToDate__title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.align(Alignment.Center)
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      state.weekdaySymbols.forEach { symbol ->
        Text(
          text = symbol,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.weight(1f)
        )
      }
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(7),
      state = gridState,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(440.dp)
    ) {
      state.months.forEach { month ->
        item(
          key = month.monthStart to "header",
          span = { GridItemSpan(maxLineSpan) }
        ) {
          Text(
            text = month.monthStart.format(monthTitleFormatter),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 12.dp, bottom = 6.dp, start = 4.dp)
          )
          // Ensure month is requested even before cells lay out
          LaunchedEffect(month.monthStart) {
            onMonthVisible(month.monthStart)
          }
        }
        itemsIndexed(
          items = (0 until month.itemCount).toList(),
          key = { index, _ -> month.monthStart to index }
        ) { index, _ ->
          val day = month.dayAt(index)
          if (day == null) {
            Box(Modifier.aspectRatio(1f))
          } else {
            DayCell(
              day = day,
              thumbnail = state.thumbnails[day],
              selectable = day in state.selectableDays,
              highlighted = day == state.initialDay,
              isToday = day == state.today,
              onClick = { if (day in state.selectableDays) onPick(day) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DayCell(
  day: LocalDate,
  thumbnail: Uri?,
  selectable: Boolean,
  highlighted: Boolean,
  isToday: Boolean,
  onClick: () -> Unit
) {
  val shape = RoundedCornerShape(8.dp)
  val dateText = remember(day) {
    DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
      .withLocale(Locale.getDefault())
      .format(day)
  }
  val a11y = if (thumbnail != null) {
    stringResource(R.string.TellomiJumpToDate__day_has_media_format, dateText)
  } else {
    dateText
  }

  Box(
    modifier = Modifier
      .aspectRatio(1f)
      .clip(shape)
      .then(
        if (highlighted) {
          Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
        } else {
          Modifier
        }
      )
      .then(
        if (selectable && thumbnail == null) {
          Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        } else {
          Modifier
        }
      )
      .clickable(enabled = selectable, onClick = onClick)
      .testTag(TellomiJumpToDateTestTags.day(day))
      .semantics { contentDescription = a11y },
    contentAlignment = Alignment.Center
  ) {
    if (thumbnail != null) {
      GlideImage(
        model = remember(thumbnail) { DecryptableUri(thumbnail) },
        scaleType = GlideImageScaleType.CENTER_CROP,
        modifier = Modifier.fillMaxSize()
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.25f))
      )
    }
    val labelColor = when {
      thumbnail != null -> Color.White
      !selectable -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      isToday -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
      text = day.dayOfMonth.toString(),
      color = labelColor,
      style = TextStyle(
        fontWeight = if (isToday || highlighted) FontWeight.SemiBold else FontWeight.Normal,
        shadow = if (thumbnail != null) {
          Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 2f)
        } else {
          null
        }
      ),
      textAlign = TextAlign.Center
    )
  }
}

@DayNightPreviews
@Composable
private fun TellomiJumpToDateScreenPreview() {
  val month = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 18))
  Previews.Preview {
    TellomiJumpToDateScreen(
      state = TellomiJumpToDateViewModel.State(
        months = listOf(month),
        weekdaySymbols = TellomiMonthGrid.weekdaySymbols(),
        selectableDays = setOf(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19)),
        thumbnails = emptyMap(),
        initialDay = LocalDate.of(2026, 10, 18),
        today = LocalDate.of(2026, 10, 26)
      ),
      onMonthVisible = {},
      onPick = {},
      onCancel = {}
    )
  }
}
