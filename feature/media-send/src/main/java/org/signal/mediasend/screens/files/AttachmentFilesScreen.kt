/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.signal.core.util.bytes
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.R
import org.signal.mediasend.screens.select.AttachmentDock
import org.signal.mediasend.screens.select.DockMetrics
import org.signal.mediasend.screens.select.LocalAttachmentSheetState
import org.signal.mediasend.screens.select.PickerMetrics
import org.signal.mediasend.screens.select.RoundIconButton
import org.signal.mediasend.test.TestTags
import java.text.DateFormat
import java.util.Date

/**
 * Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页，照 Telegram iOS 的结构（`AttachmentFileController`，只读机制）：
 *
 * - 顶栏：左 ✕、中间「文件」（多选时「已选 N 个」）、右 🔍（最近文件 > 10 条或加载中才有）。
 * - 两行入口（F-2）：从相册中选择（= 原图按文件发，等 #1263，先置灰「即将支持」）/ 从文件中选择（系统选择器，可多选，不申请存储权限）。
 *   Android 第一版没有「扫描文件」（F-5：系统扫描组件依赖 Google Play 服务）。
 * - 最近发送的文件（F-6）：骨架 / 一句说明（上限读服务端配置，F-9）/ 列表；点一行立即发送，长按进多选（F-7、F-8）。
 * - 搜索（F-10）：藏 dock、展开全屏、搜索框贴底在键盘上方；结果超过 4 条先 3 条 +「显示更多」；搜不到写「没有找到」。
 */
@Composable
internal fun AttachmentFilesScreen(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  val sheet = LocalAttachmentSheetState.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val keyboard = LocalSoftwareKeyboardController.current
  val context = LocalContext.current
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    // 读授权挂在这个 Sheet 上，Sheet 一关就收回，会话页却要在之后才一个个整份拷贝：趁 Sheet 还在转成持久授权，会话页发完再放。
    PickedFileGrants.take(context.contentResolver, uris)
    onEvent(AttachmentFilesEvent.FilesPicked(uris))
  }

  BackHandler(enabled = state.isSearching || state.isSelecting) {
    onEvent(AttachmentFilesEvent.Back)
  }

  // 搜索时展开到全屏（同 Telegram：搜索框从底部升起）
  LaunchedEffect(state.isSearching) {
    if (state.isSearching) {
      sheet?.expand()
    }
  }

  // 拖动列表收起键盘
  LaunchedEffect(listState.isScrollInProgress) {
    if (listState.isScrollInProgress) {
      keyboard?.hide()
    }
  }

  Scaffold(
    topBar = {
      if (state.isSearching) {
        // 搜索时顶栏收起，只给抓手留位置
        Spacer(modifier = Modifier.height(FilesMetrics.grabberSpace))
      } else {
        FilesTopBar(state = state, onEvent = onEvent)
      }
    },
    bottomBar = {
      when {
        state.isSearching -> SearchBar(state = state, onEvent = onEvent)
        state.isSelecting -> SendBar(state = state, onEvent = onEvent)
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = modifier.testTag(TestTags.ATTACHMENT_FILES)
  ) { paddingValues ->
    val showDock = !state.isSearching && !state.isSelecting && state.dock.isNotEmpty()
    Box(
      modifier = Modifier
        .padding(paddingValues)
        .fillMaxSize()
    ) {
      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = if (showDock) DockMetrics.stripHeight else 8.dp),
        modifier = Modifier
          .fillMaxSize()
          .testTag(TestTags.ATTACHMENT_FILES_LIST)
      ) {
        if (state.isSearching) {
          searchResults(state = state, onEvent = onEvent)
        } else {
          if (!state.isSelecting) {
            entries(onEvent = onEvent, onSelectFromFiles = { filePicker.launch(arrayOf("*/*")) })
          }
          recentFiles(state = state, onEvent = onEvent)
        }
      }

      if (showDock) {
        AttachmentDock(
          entries = state.dock,
          currentPage = AttachmentPage.FILES,
          onClick = { entry ->
            if (entry.page == AttachmentPage.FILES) {
              // 重复点「文件」：回到顶部并展开到全屏（同 Telegram 重复点当前格）
              scope.launch {
                listState.animateScrollToItem(0)
                sheet?.expand()
              }
            } else {
              onEvent(AttachmentFilesEvent.DockEntryClicked(entry))
            }
          },
          modifier = Modifier.align(Alignment.BottomCenter)
        )
      }
    }
  }
}

internal object FilesMetrics {
  val entryRowHeight = 56.dp
  val fileRowHeight = 64.dp
  val grabberSpace = 20.dp
  val checkSize = 24.dp
  val searchFieldHeight = 48.dp
  val captionFieldHeight = 40.dp
  const val SKELETON_ROWS = 6
  const val DISABLED_ALPHA = 0.38f
}

@Composable
private fun FilesTopBar(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit
) {
  val selectedCount = state.selection?.size ?: 0
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .height(PickerMetrics.topBarHeight)
      .padding(horizontal = 8.dp)
  ) {
    Box(modifier = Modifier.align(Alignment.CenterStart)) {
      RoundIconButton(
        icon = SignalIcons.X,
        contentDescription = stringResource(R.string.MediaSelectScreen__close),
        onClick = { onEvent(AttachmentFilesEvent.Close) }
      )
    }

    Text(
      text = if (state.isSelecting && selectedCount > 0) {
        pluralStringResource(R.plurals.MediaSelectScreen__n_selected, selectedCount, selectedCount)
      } else {
        stringResource(R.string.AttachmentFilesScreen__files)
      },
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.align(Alignment.Center)
    )

    if (state.showsSearchButton) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .testTag(TestTags.ATTACHMENT_FILES_SEARCH_BUTTON)
      ) {
        RoundIconButton(
          icon = SignalIcons.Search,
          contentDescription = stringResource(R.string.AttachmentFilesScreen__search),
          onClick = { onEvent(AttachmentFilesEvent.SearchOpened) }
        )
      }
    }
  }
}

/** F-2：两行入口，图标与文字用主题强调色；「从相册中选择」打开整屏相册网格（F-3，#1263）。 */
private fun LazyListScope.entries(
  onEvent: (AttachmentFilesEvent) -> Unit,
  onSelectFromFiles: () -> Unit
) {
  item(key = "entry-gallery") {
    EntryRow(
      icon = SignalIcons.Photo,
      title = stringResource(R.string.AttachmentFilesScreen__select_from_gallery),
      onClick = { onEvent(AttachmentFilesEvent.SelectFromGalleryClicked) },
      modifier = Modifier.testTag(TestTags.attachmentFilesEntry("gallery"))
    )
  }
  item(key = "entry-files") {
    EntryRow(
      icon = SignalIcons.File,
      title = stringResource(R.string.AttachmentFilesScreen__select_from_files),
      onClick = onSelectFromFiles,
      modifier = Modifier.testTag(TestTags.attachmentFilesEntry("files"))
    )
  }
  item(key = "entry-divider") {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun EntryRow(
  icon: SignalIcons,
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(16.dp),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = FilesMetrics.entryRowHeight)
      .clickable(onClick = onClick, role = Role.Button)
      .padding(horizontal = 24.dp, vertical = 8.dp)
  ) {
    Icon(
      imageVector = icon.imageVector,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(24.dp)
    )
    Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
  }
}

/**
 * F-6、F-9：「最近发送的文件」标题常驻（owner 2026-09-28：空的时候不显示标题，看起来像没有这个功能）；
 * 加载中是骨架；一条都没有时是插画 + 一句说明（上限读服务端配置，照 Telegram iOS `AttachmentFileEmptyItem` 的结构，独立实现）；否则列表。
 */
private fun LazyListScope.recentFiles(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit
) {
  val files = state.files
  item(key = "recent-header") {
    Texts.SectionHeader(text = stringResource(R.string.AttachmentFilesScreen__recently_sent_files))
  }
  when {
    files == null -> items(FilesMetrics.SKELETON_ROWS) { SkeletonRow() }

    files.isEmpty() -> item(key = "empty") {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)
      ) {
        Icon(
          imageVector = SignalIcons.File.imageVector,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .size(56.dp)
            .testTag(TestTags.ATTACHMENT_FILES_EMPTY_ILLUSTRATION)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.AttachmentFilesScreen__empty, state.maxFileSize.bytes.toUnitString()),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag(TestTags.ATTACHMENT_FILES_EMPTY)
        )
      }
    }

    else -> {
      items(files, key = { "recent-${it.attachmentId}" }) { file ->
        FileRow(
          file = file,
          showsDate = true,
          selectionNumber = state.selectionNumberOf(file),
          onClick = { onEvent(AttachmentFilesEvent.FileClicked(file)) },
          onLongClick = { onEvent(AttachmentFilesEvent.FileLongClicked(file)) }
        )
      }
    }
  }
}

/** F-10：结果分组「我发送的文件」、行里只写大小；超过 4 条先 3 条 +「显示更多」；搜不到写「没有找到」。 */
private fun LazyListScope.searchResults(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit
) {
  if (state.showsNoResults) {
    item(key = "no-results") {
      Text(
        text = stringResource(R.string.AttachmentFilesScreen__no_results, state.searchedQuery.trim()),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp, vertical = 24.dp)
          .testTag(TestTags.ATTACHMENT_FILES_NO_RESULTS)
      )
    }
    return
  }
  if (state.searchResults.isEmpty()) {
    return
  }
  item(key = "search-header") {
    Texts.SectionHeader(text = stringResource(R.string.AttachmentFilesScreen__files_you_sent))
  }
  items(state.visibleSearchResults, key = { "result-${it.attachmentId}" }) { file ->
    FileRow(
      file = file,
      showsDate = false,
      selectionNumber = null,
      onClick = { onEvent(AttachmentFilesEvent.FileClicked(file)) },
      onLongClick = null
    )
  }
  if (state.showsShowMore) {
    item(key = "show-more") {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = FilesMetrics.entryRowHeight)
          .clickable(role = Role.Button) { onEvent(AttachmentFilesEvent.ShowMoreResults) }
          .padding(horizontal = 24.dp)
          .testTag(TestTags.ATTACHMENT_FILES_SHOW_MORE)
      ) {
        Icon(imageVector = SignalIcons.ArrowDropDown.imageVector, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = stringResource(R.string.AttachmentFilesScreen__show_more), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}

/**
 * 一行文件：40dp 扩展名图标 + 文件名（太长中间省略）+「大小 · 日期 时间」（按系统地区格式）。
 * 本机已经没有的置灰、写「已不在本机」（F-7）；多选时左边多一个带序号的勾（F-8）。
 *
 * @param selectionNumber 多选时第几个（从 1 起）；0 = 多选中但没勾；null = 不在多选。
 */
@Composable
private fun FileRow(
  file: RecentSentFile,
  showsDate: Boolean,
  selectionNumber: Int?,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?
) {
  val locale = LocalConfiguration.current.locales[0]
  val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
  val detail = when {
    !file.isOnDevice -> stringResource(R.string.AttachmentFilesScreen__not_on_device)
    showsDate -> "${file.size.bytes.toUnitString()} · ${dateFormat.format(Date(file.sentAt))}"
    else -> file.size.bytes.toUnitString()
  }
  val isChecked = (selectionNumber ?: 0) > 0

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .height(FilesMetrics.fileRowHeight)
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) stringResource(R.string.MediaSelectScreen__select) else null
      )
      .semantics { if (selectionNumber != null) selected = isChecked }
      .padding(horizontal = 16.dp)
      .alpha(if (file.isOnDevice) 1f else FilesMetrics.DISABLED_ALPHA)
      .testTag(TestTags.attachmentFile(file.attachmentId))
  ) {
    if (selectionNumber != null) {
      SelectionCheck(number = selectionNumber, modifier = Modifier.testTag(TestTags.attachmentFileCheck(file.attachmentId)))
    }
    FileTypeIcon(fileName = file.fileName)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = file.fileName,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis
      )
      Text(
        text = detail,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** 多选的勾：没勾是空心圈，勾了是强调色实心圈 + 序号（同选图页）。 */
@Composable
private fun SelectionCheck(number: Int, modifier: Modifier = Modifier) {
  val isChecked = number > 0
  val description = if (isChecked) pluralStringResource(R.plurals.MediaSelectScreen__selected_number_n, number, number) else null
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(FilesMetrics.checkSize)
      .clip(CircleShape)
      .then(
        if (isChecked) {
          Modifier.background(MaterialTheme.colorScheme.primary)
        } else {
          Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
        }
      )
      .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
  ) {
    if (isChecked) {
      Text(text = number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** 加载中的骨架行：灰色图标块 + 两条灰条。 */
@Composable
private fun SkeletonRow() {
  val block = MaterialTheme.colorScheme.surfaceVariant
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .height(FilesMetrics.fileRowHeight)
      .padding(horizontal = 16.dp)
      .testTag(TestTags.ATTACHMENT_FILES_SKELETON)
  ) {
    Box(
      modifier = Modifier
        .size(FileTypeIconMetrics.side)
        .clip(RoundedCornerShape(8.dp))
        .background(block)
    )
    Column(verticalArrangement = spacedBy(8.dp)) {
      Box(
        modifier = Modifier
          .width(160.dp)
          .height(10.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(block)
      )
      Box(
        modifier = Modifier
          .width(100.dp)
          .height(8.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(block.copy(alpha = 0.6f))
      )
    }
  }
}

/** F-8：多选时底栏换成「添加说明…」+ 发送；底色铺到底，输入行在键盘上方。 */
@Composable
private fun SendBar(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit
) {
  val canSend = !state.selection.isNullOrEmpty()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(8.dp),
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .testTag(TestTags.ATTACHMENT_FILES_SEND_BAR)
  ) {
    CapsuleField(
      value = state.caption,
      onValueChange = { onEvent(AttachmentFilesEvent.CaptionChanged(it)) },
      placeholder = stringResource(R.string.AttachmentFilesScreen__add_a_caption),
      height = FilesMetrics.captionFieldHeight,
      imeAction = ImeAction.Default,
      modifier = Modifier
        .weight(1f)
        .testTag(TestTags.ATTACHMENT_FILES_CAPTION)
    )
    val sendLabel = stringResource(R.string.AttachmentFilesScreen__send)
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(FilesMetrics.captionFieldHeight)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .clickable(enabled = canSend, role = Role.Button, onClickLabel = sendLabel) { onEvent(AttachmentFilesEvent.SendSelection) }
        .semantics { contentDescription = sendLabel }
        .alpha(if (canSend) 1f else FilesMetrics.DISABLED_ALPHA)
        .testTag(TestTags.ATTACHMENT_FILES_SEND)
    ) {
      Icon(imageVector = SignalIcons.SendFill.imageVector, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
    }
  }
}

/** F-10：搜索框贴底在键盘上方：胶囊输入框（左放大镜）+ 旁边圆形 ✕；打开时自动聚焦。 */
@Composable
private fun SearchBar(
  state: AttachmentFilesState,
  onEvent: (AttachmentFilesEvent) -> Unit
) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(8.dp),
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
      .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    CapsuleField(
      value = state.searchQuery.orEmpty(),
      onValueChange = { onEvent(AttachmentFilesEvent.SearchQueryChanged(it)) },
      placeholder = stringResource(R.string.AttachmentFilesScreen__search),
      height = FilesMetrics.searchFieldHeight,
      imeAction = ImeAction.Search,
      leadingIcon = SignalIcons.Search,
      modifier = Modifier
        .weight(1f)
        .focusRequester(focusRequester)
        .testTag(TestTags.ATTACHMENT_FILES_SEARCH_FIELD)
    )
    Box(modifier = Modifier.testTag(TestTags.ATTACHMENT_FILES_SEARCH_CLOSE)) {
      RoundIconButton(
        icon = SignalIcons.X,
        contentDescription = stringResource(R.string.MediaSelectScreen__close),
        onClick = { onEvent(AttachmentFilesEvent.SearchClosed) }
      )
    }
  }
}

@Composable
private fun CapsuleField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  height: Dp,
  imeAction: ImeAction,
  modifier: Modifier = Modifier,
  leadingIcon: SignalIcons? = null
) {
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = imeAction),
    modifier = modifier,
    decorationBox = { innerTextField ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
        modifier = Modifier
          .height(height)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(horizontal = 14.dp)
      ) {
        if (leadingIcon != null) {
          Icon(imageVector = leadingIcon.imageVector, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
          if (value.isEmpty()) {
            Text(text = placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
          }
          innerTextField()
        }
      }
    }
  )
}
