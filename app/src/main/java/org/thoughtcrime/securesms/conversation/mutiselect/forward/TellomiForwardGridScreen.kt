/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.signal.core.ui.compose.BottomSheets
import org.signal.core.ui.compose.TextFields
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.avatar.AvatarImage
import org.thoughtcrime.securesms.conversation.RecipientSearchBar
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker

/** 用例定位用（tellomi/tellomi#1259） */
object TellomiForwardGridTags {
  const val GRID = "tellomi-forward-grid"
  const val TITLE = "tellomi-forward-title"
  const val SUBTITLE = "tellomi-forward-subtitle"
  const val SEARCH = "tellomi-forward-search"
  const val SHARE = "tellomi-forward-share"
  const val RECENT_ROW = "tellomi-forward-recent"
  const val NO_RESULTS = "tellomi-forward-no-results"
  const val MESSAGE = "tellomi-forward-message"
  const val HIDE_CAPTION = "tellomi-forward-hide-caption"
  const val SEND = "tellomi-forward-send"
  const val SEND_COUNT = "tellomi-forward-send-count"
  const val SECTION_HEADER = "tellomi-forward-section"
  const val CHECK = "tellomi-forward-check"
  const val RING = "tellomi-forward-ring"

  fun cell(target: TellomiForwardTarget): String = "tellomi-forward-cell-${target.id.toLong()}"
}

/** 几何（F-4），与 Telegram Android 分享面板同一组数（独立实现）：4 列，头像 56 dp，名字 12 sp 两行，选中头像缩到 0.857。 */
object TellomiForwardGridMetrics {
  const val COLUMNS = 4
  val AVATAR_SIZE: Dp = 56.dp
  val RING_SIZE: Dp = 62.dp
  val CHECK_SIZE: Dp = 24.dp
  const val SELECTED_AVATAR_SCALE = 0.857f
  const val NAME_FONT_SIZE_SP = 12
  const val PEEK_HEIGHT_FRACTION = 0.6f
}

interface TellomiForwardGridCallbacks {
  fun onTargetClicked(target: TellomiForwardTarget, fromSearch: Boolean)
  fun onQueryChanged(query: String)
  fun onSearchFocusChanged(focused: Boolean)

  /** 搜索态里按了返回：退出搜索、回到网格，面板不关 */
  fun onSearchCancelled()
  fun onShareClicked()
}

/**
 * 转发网格的面板正文（tellomi/tellomi#1259 F-3 / F-4 / F-6 / F-9）：拖动条 + 标题「转发给」+ 副标题（选择聊天 / 已选名字「、」连接）
 * + 常驻搜索框（右侧「分享到其他 App」）+ 4 列头像网格；搜索态：空查询是最近联系人一排，有查询按「我的收藏 · 聊天 · 联系人 · 群组」分组。
 */
@Composable
fun TellomiForwardGridContent(
  state: TellomiForwardGridViewModel.State,
  canShare: Boolean,
  callbacks: TellomiForwardGridCallbacks,
  bottomContentPadding: Dp,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  LaunchedEffect(state.isSearchActive) {
    // 搜索收起（在搜索里勾上了一个）：同 Telegram，清空搜索词后收起键盘——焦点一走键盘就收
    if (!state.isSearchActive) {
      focusManager.clearFocus()
    }
  }
  // 搜索态的返回先退出搜索（键盘开着时第一下是收键盘）；不在搜索态时不管，返回照常关面板
  BackHandler(enabled = state.isSearchActive) {
    callbacks.onSearchCancelled()
  }
  Column(modifier = modifier.fillMaxSize()) {
    BottomSheets.Handle(modifier = Modifier.align(Alignment.CenterHorizontally))

    Text(
      text = stringResource(R.string.MultiselectForwardFragment__forward_to),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp)
        .testTag(TellomiForwardGridTags.TITLE)
    )
    val subtitle = if (state.selectedTargets.isEmpty()) {
      stringResource(R.string.TellomiForwardGrid__choose_chats)
    } else {
      state.selectedTargets.joinToString(separator = stringResource(R.string.TellomiForwardGrid__name_separator)) { it.fullName(context) }
    }
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = if (state.selectedTargets.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
        .testTag(TellomiForwardGridTags.SUBTITLE)
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
      RecipientSearchBar(
        hint = stringResource(R.string.TellomiForwardGrid__search),
        query = state.query,
        onQueryChange = callbacks::onQueryChanged,
        onSearch = callbacks::onQueryChanged,
        enabledKeyboardTypes = listOf(RecipientPicker.KeyboardType.Text),
        onFocusChanged = callbacks::onSearchFocusChanged,
        modifier = Modifier
          .weight(1f)
          .testTag(TellomiForwardGridTags.SEARCH)
      )
      if (canShare) {
        IconButton(
          onClick = callbacks::onShareClicked,
          modifier = Modifier.testTag(TellomiForwardGridTags.SHARE)
        ) {
          Icon(
            imageVector = ImageVector.vectorResource(org.signal.core.ui.R.drawable.symbol_share_android_24),
            contentDescription = stringResource(R.string.TellomiForwardGrid__share_to_other_apps),
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
    }

    val selected = state.selected.toSet()
    val contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomContentPadding + 8.dp)
    when {
      !state.isSearchActive -> {
        LazyVerticalGrid(
          columns = GridCells.Fixed(TellomiForwardGridMetrics.COLUMNS),
          contentPadding = contentPadding,
          modifier = Modifier
            .weight(1f)
            .nestedScroll(rememberNestedScrollInteropConnection())
            .testTag(TellomiForwardGridTags.GRID)
        ) {
          items(state.targets, key = { it.id.toLong() }) { target ->
            TargetCell(target = target, isSelected = target.id in selected, onClick = { callbacks.onTargetClicked(target, fromSearch = false) })
          }
        }
      }

      state.query.isBlank() -> {
        Column(modifier = Modifier.weight(1f)) {
          if (state.recentContacts.isNotEmpty()) {
            SectionHeader(stringResource(R.string.TellomiForwardGrid__recent))
            LazyRow(
              contentPadding = PaddingValues(horizontal = 8.dp),
              modifier = Modifier.testTag(TellomiForwardGridTags.RECENT_ROW)
            ) {
              items(state.recentContacts, key = { it.id.toLong() }) { target ->
                TargetCell(
                  target = target,
                  isSelected = target.id in selected,
                  onClick = { callbacks.onTargetClicked(target, fromSearch = true) },
                  modifier = Modifier.width(84.dp)
                )
              }
            }
          }
        }
      }

      state.searchResults.isEmpty -> {
        Text(
          text = stringResource(R.string.TellomiForwardGrid__no_results, state.query.trim()),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = 32.dp, start = 24.dp, end = 24.dp)
            .testTag(TellomiForwardGridTags.NO_RESULTS)
        )
      }

      else -> {
        LazyVerticalGrid(
          columns = GridCells.Fixed(TellomiForwardGridMetrics.COLUMNS),
          contentPadding = contentPadding,
          modifier = Modifier
            .weight(1f)
            .nestedScroll(rememberNestedScrollInteropConnection())
            .testTag(TellomiForwardGridTags.GRID)
        ) {
          val results = state.searchResults
          results.savedMessages?.let { section(title = null, targets = listOf(it), selected = selected, callbacks = callbacks) }
          section(title = R.string.TellomiForwardGrid__chats, targets = results.chats, selected = selected, callbacks = callbacks)
          section(title = R.string.TellomiForwardGrid__contacts, targets = results.contacts, selected = selected, callbacks = callbacks)
          section(title = R.string.TellomiForwardGrid__groups, targets = results.groups, selected = selected, callbacks = callbacks)
        }
      }
    }
  }
}

private fun LazyGridScope.section(
  title: Int?,
  targets: List<TellomiForwardTarget>,
  selected: Set<RecipientId>,
  callbacks: TellomiForwardGridCallbacks
) {
  if (targets.isEmpty()) {
    return
  }
  if (title != null) {
    item(key = "header-$title", span = { GridItemSpan(maxLineSpan) }) {
      SectionHeader(stringResource(title))
    }
  }
  items(targets, key = { "$title-${it.id.toLong()}" }) { target ->
    TargetCell(target = target, isSelected = target.id in selected, onClick = { callbacks.onTargetClicked(target, fromSearch = true) })
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
      .testTag(TellomiForwardGridTags.SECTION_HEADER)
  )
}

/** 一格：选中时头像缩小 + 强调色环 + 右下角勾 + 名字变强调色（F-6；同 Telegram Android 的选中态，独立实现）。 */
@Composable
private fun TargetCell(
  target: TellomiForwardTarget,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val progress by animateFloatAsState(
    targetValue = if (isSelected) 1f else 0f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    label = "selection"
  )
  val accent = MaterialTheme.colorScheme.primary
  val sheetColor = SignalTheme.colors.colorSurface1
  val name = target.shortName(context)

  // 只用一个 selectable：点、选中态、读出的名字都在同一个读屏节点上（clickable + 另一个合并 semantics 会拆成两个节点）
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .testTag(TellomiForwardGridTags.cell(target))
      .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
      .padding(top = 8.dp, bottom = 6.dp)
  ) {
    Box(modifier = Modifier.size(TellomiForwardGridMetrics.RING_SIZE)) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .alpha(progress.coerceIn(0f, 1f))
          .border(2.dp, accent, CircleShape)
          .then(if (isSelected) Modifier.testTag(TellomiForwardGridTags.RING) else Modifier)
      )
      AvatarImage(
        recipient = target.recipient,
        useProfile = !target.isSavedMessages,
        modifier = Modifier
          .align(Alignment.Center)
          .size(TellomiForwardGridMetrics.AVATAR_SIZE)
          .graphicsLayer {
            val scale = 1f - (1f - TellomiForwardGridMetrics.SELECTED_AVATAR_SCALE) * progress
            scaleX = scale
            scaleY = scale
          }
      )
      if (isSelected || progress > 0f) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(TellomiForwardGridMetrics.CHECK_SIZE)
            .graphicsLayer {
              scaleX = progress
              scaleY = progress
            }
            .background(sheetColor, CircleShape)
            .padding(2.dp)
            .background(accent, CircleShape)
            .then(if (isSelected) Modifier.testTag(TellomiForwardGridTags.CHECK) else Modifier)
        ) {
          Icon(
            imageVector = ImageVector.vectorResource(R.drawable.symbol_check_compact_bold_16),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
          )
        }
      }
    }
    Text(
      text = name,
      fontSize = TellomiForwardGridMetrics.NAME_FONT_SIZE_SP.sp,
      lineHeight = 15.sp,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, end = 4.dp, top = 4.dp)
    )
  }
}

/**
 * 选中后的底部（F-7 / 审计 5.4）：可选的「隐藏说明文字」开关 + 附言「添加消息…」+ 带已选数量的「发送」。
 * 附言作为单独一条文字先于转发内容发出；开关只在有可隐藏说明时出现，默认关。
 */
@Composable
fun TellomiForwardGridBottomBar(
  message: String,
  selectedCount: Int,
  isSendEnabled: Boolean,
  onMessageChanged: (String) -> Unit,
  onSend: () -> Unit,
  showHideCaptionToggle: Boolean = false,
  hideCaption: Boolean = false,
  onHideCaptionChanged: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
    if (showHideCaptionToggle) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 4.dp)
      ) {
        Text(
          text = stringResource(R.string.TellomiForwardGrid__hide_caption),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f)
        )
        Switch(
          checked = hideCaption,
          onCheckedChange = onHideCaptionChanged,
          modifier = Modifier.testTag(TellomiForwardGridTags.HIDE_CAPTION)
        )
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      TextFields.TextField(
        value = message,
        onValueChange = onMessageChanged,
        placeholder = { Text(text = stringResource(R.string.TellomiForwardGrid__add_a_message)) },
        shape = RoundedCornerShape(24.dp),
        maxLines = 4,
        colors = TextFieldDefaults.colors(
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
          errorIndicatorColor = Color.Transparent
        ),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        modifier = Modifier
          .weight(1f)
          .heightIn(min = 44.dp)
          .testTag(TellomiForwardGridTags.MESSAGE)
      )
      Button(
        onClick = onSend,
        enabled = isSendEnabled,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier
          .heightIn(min = 44.dp)
          .testTag(TellomiForwardGridTags.SEND)
      ) {
        Text(text = stringResource(R.string.ShareActivity__send), fontWeight = FontWeight.SemiBold)
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .padding(start = 8.dp)
            .heightIn(min = 22.dp)
            .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            .padding(horizontal = 7.dp)
        ) {
          Text(
            text = selectedCount.toString(),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(TellomiForwardGridTags.SEND_COUNT)
          )
        }
      }
    }
  }
}
