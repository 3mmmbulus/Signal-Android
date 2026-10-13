/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.signal.core.models.media.Media
import org.signal.core.models.media.MediaFolder
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.list.DragToSelectState
import org.signal.core.ui.compose.list.dragToSelect
import org.signal.core.util.ContentTypeUtil
import org.signal.glide.compose.GlideImage
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.R
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.TellomiHeic
import org.signal.mediasend.screens.edit.AddAMessageRow
import org.signal.mediasend.screens.edit.MediaEditScreenEvents
import org.signal.mediasend.test.TestTags
import org.signal.mediasend.util.formatAsClock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tellomi（tellomi/tellomi#1261，需求 docs/product/specs/media-album-forward-picker.md 第三节，照 Telegram 选图面板）：
 * 选图网格——直接是「最近」，相册在顶栏下拉里换；照片与视频 3 列（横屏 4 列）、间距 2dp、正方形。
 *
 * - 顶栏（P-1、P-2）：左 ✕；有选中时 ✕ 右边出现强调色胶囊「✓N」（点它看已选的，暂时进上游的预览编辑页，P-3 另做）；
 *   中间「最近 ⌄」换相册；有选中时右边 ⋮（P-5：以高清 / 标准质量发送、单独发送，点了立即发出）。
 * - 格子（P-8）：右上角编号勾（29dp 触摸区，白色 1.5dp 描边 + 阴影，选中填强调色并显示序号），点勾选上 / 取消；
 *   点照片本身进单张预览 / 编辑（P-10）；视频右下角时长；长按后拖动连续多选（上游原有）。
 * - 受限访问横幅在网格里、跟着网格滚走（P-7）。
 * - 底部（P-9、P-10）：一有选中就出现「添加说明…」+ 表情 + 发送，在网格里直接发，不必经过预览页。
 *
 * Telegram 的实现只读机制、一行都没搬（GPLv2）。
 */
@Composable
internal fun MediaPickerFilesScreen(
  state: MediaSelectState.Files,
  onEvent: (MediaSelectScreenEvents) -> Unit,
  gridState: LazyGridState,
  dragToSelectState: DragToSelectState,
  showPlaceholders: Boolean,
  showLimitedAccessBanner: Boolean,
  recipientChatColor: Color?,
  columns: Int,
  showCamera: Boolean,
  gridEntries: List<Media?>
) {
  // P-3：点「✓N」切到「只看已选」；在那里取消的记下原来的位置，4 秒内可以撤销；全部取消 0.3 秒后自动回网格。
  var showSelectedOnly by rememberSaveable { mutableStateOf(false) }
  val deselections = remember { mutableStateListOf<Pair<Media, Int>>() }
  var undoGeneration by remember { mutableIntStateOf(0) }
  LaunchedEffect(undoGeneration) {
    if (deselections.isNotEmpty()) {
      delay(UNDO_DURATION_MS)
      deselections.clear()
    }
  }
  LaunchedEffect(showSelectedOnly, state.selectedMedia.isEmpty()) {
    if (showSelectedOnly && state.selectedMedia.isEmpty()) {
      delay(RETURN_TO_GRID_DELAY_MS)
      showSelectedOnly = false
    }
  }
  BackHandler(enabled = showSelectedOnly) {
    showSelectedOnly = false
  }
  val deselectInPreview: (Media) -> Unit = { media ->
    val index = state.selectedMedia.indexOfFirst { it.uri == media.uri }
    if (index >= 0) {
      deselections += media to index
      undoGeneration++
      onEvent(MediaSelectScreenEvents.MediaUnselected(setOf(media)))
    }
  }
  // 撤销：倒着放回去——先加回末尾，再挪回原来的位置（两个事件在 ViewModel 里按顺序执行）。
  val undoDeselections: () -> Unit = {
    var size = state.selectedMedia.size
    for ((media, index) in deselections.reversed()) {
      onEvent(MediaSelectScreenEvents.MediaSelected(setOf(media)))
      onEvent(MediaSelectScreenEvents.ReorderSelectedMedia(fromIndex = size, toIndex = index.coerceAtMost(size)))
      size++
    }
    deselections.clear()
  }
  // 格子下标 → 媒体：横幅、相机格与它下面的占位是 null，不能选。
  val gridItems = gridEntries

  // Tellomi（tellomi/tellomi#1115）：从「+」打开时这一页是附件 Sheet，底部浮着 dock；选了照片 dock 让位给「说明 + 发送」（同 Telegram）。
  val attachmentSheet = LocalAttachmentSheetState.current
  val showDock = state.attachmentDock.isNotEmpty() && state.selectedMedia.isEmpty() && !showSelectedOnly
  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = {
      PickerTopBar(
        state = state,
        onEvent = onEvent,
        recipientChatColor = recipientChatColor,
        showSelectedOnly = showSelectedOnly,
        onShowSelectedOnly = {
          showSelectedOnly = true
          scope.launch { attachmentSheet?.expand() }
        },
        onBack = { showSelectedOnly = false },
        withStatusBarInset = attachmentSheet == null
      )
    },
    containerColor = MaterialTheme.colorScheme.surface
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .padding(paddingValues)
        .fillMaxSize()
    ) {
      Box(modifier = Modifier.weight(1f)) {
        if (showSelectedOnly) {
          MediaPickerSelectedPreview(
            selectedMedia = state.selectedMedia,
            caption = state.sendOptions.message,
            recipientId = state.recipientId?.id,
            recipientChatColor = recipientChatColor,
            onOpen = { onEvent(MediaSelectScreenEvents.OpenSelectedMedia(it)) },
            onDeselect = deselectInPreview,
            onReorder = { from, to -> onEvent(MediaSelectScreenEvents.ReorderSelectedMedia(from, to)) },
            modifier = Modifier.fillMaxSize()
          )
        } else {
          LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(bottom = if (showDock) DockMetrics.stripHeight else 0.dp),
            horizontalArrangement = spacedBy(PickerMetrics.gridSpacing),
            verticalArrangement = spacedBy(PickerMetrics.gridSpacing),
            userScrollEnabled = !showPlaceholders && !dragToSelectState.isActive,
            modifier = Modifier
              .fillMaxSize()
              .testTag(TestTags.MEDIA_SELECT_GRID)
              .then(
                if (showPlaceholders) {
                  Modifier
                } else {
                  Modifier
                    .swipeToSelect(
                      gridState = gridState,
                      isSelected = { index -> gridItems.getOrNull(index)?.let { media -> state.selectedMedia.any { it.uri == media.uri } } },
                      setSelected = { index, selected ->
                        gridItems.getOrNull(index)?.let { media ->
                          onEvent(if (selected) MediaSelectScreenEvents.MediaSelected(setOf(media)) else MediaSelectScreenEvents.MediaUnselected(setOf(media)))
                        }
                      }
                    )
                    .dragToSelect(dragToSelectState)
                }
              )
          ) {
            if (showLimitedAccessBanner) {
              item(key = LIMITED_ACCESS_BANNER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                LimitedAccessBanner(onEvent)
              }
            }

            if (showPlaceholders) {
              items(PLACEHOLDER_COUNT) {
                MediaTilePlaceholder()
              }
            } else {
              val tile: @Composable (Media) -> Unit = { media ->
                PickerTile(
                  media = media,
                  selectionIndex = state.selectedMedia.indexOfFirst { it.uri == media.uri },
                  recipientChatColor = recipientChatColor,
                  onEvent = onEvent
                )
              }
              if (showCamera) {
                // P-8：第 0 格相机（画成两行高），第一行剩下的几张，第二行第 0 格是被相机盖住的占位，然后其余的。
                val files = state.selectedMediaFolderItems
                item(key = PICKER_CAMERA_KEY) {
                  PickerCameraCell(access = state.cameraAccess, onClick = { onEvent(MediaSelectScreenEvents.NavigateToCamera) })
                }
                items(files.take(columns - 1), key = { it.uri }) { tile(it) }
                item(key = PICKER_CAMERA_SPACER_KEY) {
                  PickerCameraSpacer(onClick = { onEvent(MediaSelectScreenEvents.NavigateToCamera) })
                }
                items(files.drop(columns - 1), key = { it.uri }) { tile(it) }
              } else {
                items(state.selectedMediaFolderItems, key = { it.uri }) { tile(it) }
              }
            }
          }

          if (showPlaceholders) {
            MediaAccessCallToAction(
              mediaPermissions = state.mediaPermissions,
              onEvent = onEvent,
              modifier = Modifier.align(Alignment.Center)
            )
          }
        }

        if (deselections.isNotEmpty()) {
          DeselectionUndoBar(
            count = deselections.size,
            onUndo = undoDeselections,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(horizontal = 16.dp, vertical = 8.dp)
          )
        }

        PickerAttachmentDock(
          visible = showDock,
          entries = state.attachmentDock,
          onClick = { entry ->
            if (entry.page == MediaSendFlowActivityContract.AttachmentPage.GALLERY) {
              // 重复点「相册」：回到网格顶部并展开到全屏（同 Telegram 重复点当前格）
              scope.launch {
                gridState.animateScrollToItem(0)
                attachmentSheet?.expand()
              }
            } else {
              onEvent(MediaSelectScreenEvents.DockEntryClicked(entry))
            }
          }
        )
      }

      PickerSendBar(
        state = state,
        onEvent = onEvent,
        recipientChatColor = recipientChatColor,
        bottomPadding = if (showSelectedOnly) SelectedOnlySendBarBottomPadding else SendBarBottomPadding
      )
    }
  }
}

/** Tellomi（tellomi/tellomi#1115）：附件 Sheet 的 dock 浮在网格底部，出现 / 让位时从底下滑进滑出。 */
@Composable
private fun BoxScope.PickerAttachmentDock(
  visible: Boolean,
  entries: List<MediaSendFlowActivityContract.DockEntry>,
  onClick: (MediaSendFlowActivityContract.DockEntry) -> Unit
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn() + slideInVertically { it },
    exit = fadeOut() + slideOutVertically { it },
    modifier = Modifier.align(Alignment.BottomCenter)
  ) {
    AttachmentDock(entries = entries, currentPage = MediaSendFlowActivityContract.AttachmentPage.GALLERY, onClick = onClick)
  }
}

private const val UNDO_DURATION_MS = 4_000L
private const val PICKER_CAMERA_KEY = "tellomi-picker-camera"
private const val PICKER_CAMERA_SPACER_KEY = "tellomi-picker-camera-spacer"

/**
 * P-8：相机格。在网格里只占一格，但画成两行高（含中间的间距），盖住第二行第 0 格的占位——照 Telegram Android
 * （PhotoAttachCameraCell 占一格、取景画成 itemSize * 2 + GAP）。有权限是实时取景 + 右上角小相机图标，没权限是居中的相机图标。
 */
@Composable
private fun PickerCameraCell(access: PickerCameraAccess, onClick: () -> Unit) {
  val description = stringResource(R.string.MediaSelectScreen__go_to_camera)
  val spacing = PickerMetrics.gridSpacing
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .layout { measurable, constraints ->
        val side = constraints.maxWidth
        val tall = side * 2 + spacing.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = tall, maxHeight = tall))
        layout(side, side) { placeable.place(0, 0) }
      }
      .background(Color(0xFF1A1A1A))
      .clickable(onClick = onClick, onClickLabel = description, role = Role.Button)
      .semantics { contentDescription = description }
      .testTag(TestTags.MEDIA_PICKER_CAMERA)
  ) {
    if (access == PickerCameraAccess.GRANTED) {
      LocalPickerCameraViewfinder.current(Modifier.fillMaxSize())
      // 取景可能很亮：小图标垫一个半透明深色圆底。
      Icon(
        imageVector = SignalIcons.Camera.imageVector,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 3.dp, end = 3.dp)
          .size(26.dp)
          .background(color = Color.Black.copy(alpha = 0.3f), shape = CircleShape)
          .padding(4.dp)
      )
    } else {
      Icon(
        imageVector = SignalIcons.Camera.imageVector,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.align(Alignment.Center)
      )
    }
  }
}

/** 相机格下面那一格：什么都不画（相机盖在上面），点了同样进拍照页。 */
@Composable
private fun PickerCameraSpacer(onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clickable(interactionSource = null, indication = null, onClick = onClick)
      .testTag(TestTags.MEDIA_PICKER_CAMERA_SPACER)
  )
}
private const val RETURN_TO_GRID_DELAY_MS = 300L

/** 受限访问横幅占掉网格的第一格（整行），拖动多选时要把它算回去。 */
internal const val LIMITED_ACCESS_BANNER_KEY = "tellomi-limited-access-banner"

internal object PickerMetrics {
  val gridSpacing = 2.dp
  val topBarHeight = 56.dp
  val roundButtonSize = 44.dp
  val countPillHeight = 44.dp
  val checkTouchSize = 29.dp
  val checkVisualSize = 24.dp
  val checkBorder = 1.5.dp

  /**
   * 网格格子里的勾：右上角 42 × 42 的整块角区都算点勾，圆离上、右各 6（照 Telegram Android `PhotoAttachPhotoCell`：
   * checkFrame 42dp、CheckBox2 离上右 5 + 1）。29 的触摸区手指落在圆旁边一点就进了预览（owner 2026-09-28）。
   */
  val tileCheckTouchSize = 42.dp
  val tileCheckVisualInset = 6.dp
}

@Composable
private fun PickerTopBar(
  state: MediaSelectState.Files,
  onEvent: (MediaSelectScreenEvents) -> Unit,
  recipientChatColor: Color?,
  showSelectedOnly: Boolean,
  onShowSelectedOnly: () -> Unit,
  onBack: () -> Unit,
  withStatusBarInset: Boolean = true
) {
  val count = state.selectedMedia.size
  val menuItems = rememberMoreMenuItems(state)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      // 附件 Sheet 里顶栏不在屏幕最上面（全屏时 Sheet 的顶边就在状态栏下面），不用让状态栏
      .then(if (withStatusBarInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
      .height(PickerMetrics.topBarHeight)
      .padding(horizontal = 8.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
      modifier = Modifier.align(Alignment.CenterStart)
    ) {
      // P-3：「只看已选」里 ✕ 变返回，「✓N」「最近 ⌄」隐藏。
      RoundIconButton(
        icon = if (showSelectedOnly) SignalIcons.ArrowStart else SignalIcons.X,
        contentDescription = stringResource(if (showSelectedOnly) R.string.MediaSelectScreen__back else R.string.MediaSelectScreen__close),
        onClick = { if (showSelectedOnly) onBack() else onEvent(MediaSelectScreenEvents.Close) }
      )

      AnimatedVisibility(visible = count > 0 && !showSelectedOnly, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        SelectedCountPill(
          count = count,
          color = recipientChatColor ?: MaterialTheme.colorScheme.primary,
          onClick = onShowSelectedOnly
        )
      }
    }

    if (!showSelectedOnly) {
      FolderTitle(
        state = state,
        onEvent = onEvent,
        modifier = Modifier.align(Alignment.Center)
      )
    }

    AnimatedVisibility(
      visible = count > 0 && menuItems.isNotEmpty(),
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.CenterEnd)
    ) {
      MoreMenuButton(items = menuItems, selectedMedia = state.selectedMedia, onEvent = onEvent)
    }
  }
}

@Composable
internal fun RoundIconButton(
  icon: SignalIcons,
  contentDescription: String,
  onClick: () -> Unit
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(PickerMetrics.roundButtonSize)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick, role = Role.Button, onClickLabel = contentDescription)
      .semantics { this.contentDescription = contentDescription }
  ) {
    Icon(imageVector = icon.imageVector, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
  }
}

/** P-2：强调色胶囊「✓N」，高 44、数字等宽（照 Telegram iOS `SelectedButtonNode`：勾 + 数字、左右留白）。 */
@Composable
private fun SelectedCountPill(
  count: Int,
  color: Color,
  onClick: () -> Unit
) {
  val description = pluralStringResource(R.plurals.MediaSelectScreen__n_selected, count, count)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(2.dp),
    modifier = Modifier
      .height(PickerMetrics.countPillHeight)
      .clip(RoundedCornerShape(percent = 50))
      .background(color)
      .clickable(onClick = onClick, role = Role.Button)
      .semantics { contentDescription = description }
      .padding(start = 8.dp, end = 14.dp)
      .testTag(TestTags.MEDIA_PICKER_COUNT_PILL)
  ) {
    Icon(imageVector = SignalIcons.Check.imageVector, contentDescription = null, tint = Color.White)
    Text(
      text = "$count",
      color = Color.White,
      style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    )
  }
}

/** P-1：中间「最近 ⌄」，点开换相册（「全部媒体」叫「最近」）。 */
@Composable
private fun FolderTitle(
  state: MediaSelectState.Files,
  onEvent: (MediaSelectScreenEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  var expanded by remember { mutableStateOf(false) }
  val title = folderTitle(state.selectedMediaFolder)

  Box(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clip(RoundedCornerShape(percent = 50))
        .clickable(enabled = state.mediaFolders.isNotEmpty(), role = Role.Button, onClick = { expanded = true })
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag(TestTags.MEDIA_PICKER_FOLDER_TITLE)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 160.dp)
      )
      Icon(imageVector = SignalIcons.ArrowDropDown.imageVector, contentDescription = null)
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      state.mediaFolders.forEach { folder ->
        DropdownMenuItem(
          text = { Text(text = "${folderTitle(folder)}  ${folder.itemCount}") },
          trailingIcon = if (folder.bucketId == state.selectedMediaFolder.bucketId) {
            { Icon(imageVector = SignalIcons.Check.imageVector, contentDescription = null) }
          } else {
            null
          },
          onClick = {
            expanded = false
            if (folder.bucketId != state.selectedMediaFolder.bucketId) {
              onEvent(MediaSelectScreenEvents.SwitchFolder(folder))
            }
          }
        )
      }
    }
  }
}

@Composable
private fun folderTitle(folder: MediaFolder): String {
  return if (folder.bucketId == Media.ALL_MEDIA_BUCKET_ID) stringResource(R.string.MediaSelectScreen__recents) else folder.title
}

/**
 * P-5 的「···」里有哪几项，按这个顺序：以文件发送（#1263，已知的单个会话、没开一次性查看）；以高清 / 标准质量发送（已选里有照片）；
 * 单独发送（已选 ≥ 2、且是已知的单个会话）。
 */
private enum class MoreMenuItem {
  SEND_AS_FILE,
  SEND_HIGH_QUALITY,
  SEND_STANDARD_QUALITY,
  SEND_SEPARATELY
}

@Composable
private fun rememberMoreMenuItems(state: MediaSelectState.Files): List<MoreMenuItem> {
  val hasPhoto = state.selectedMedia.any { ContentTypeUtil.isImageType(it.contentType) && !ContentTypeUtil.isGif(it.contentType) }
  val options = state.sendOptions
  return remember(hasPhoto, state.selectedMedia.size, options.sentMediaQuality, options.canSendSeparately, options.canSendAsFile, options.isViewOnceEnabled) {
    buildList {
      // 一次性查看的只能是照片 / 视频本身，文件没有一次性查看（遮罩那一刀再加「开了遮罩就没有」，ADR-0068 §4.4）。
      if (state.selectedMedia.isNotEmpty() && options.canSendAsFile && !options.isViewOnceEnabled) {
        add(MoreMenuItem.SEND_AS_FILE)
      }
      if (hasPhoto && !options.isViewOnceEnabled) {
        // D9：默认已经是「高」时这一项换成「以标准质量发送」。
        add(if (options.sentMediaQuality == SentMediaQuality.HIGH) MoreMenuItem.SEND_STANDARD_QUALITY else MoreMenuItem.SEND_HIGH_QUALITY)
      }
      if (state.selectedMedia.size >= 2 && options.canSendSeparately) {
        add(MoreMenuItem.SEND_SEPARATELY)
      }
    }
  }
}

@Composable
private fun MoreMenuButton(
  items: List<MoreMenuItem>,
  selectedMedia: List<Media>,
  onEvent: (MediaSelectScreenEvents) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }
  var askToConvertHeic by remember { mutableStateOf(false) }

  Box {
    RoundIconButton(
      icon = SignalIcons.MoreVertical,
      contentDescription = stringResource(R.string.MediaSelectScreen__more_options),
      onClick = { expanded = true }
    )

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      items.forEach { item ->
        val (label, icon) = when (item) {
          MoreMenuItem.SEND_AS_FILE -> sendAsFileLabel(selectedMedia.size) to SignalIcons.File
          MoreMenuItem.SEND_HIGH_QUALITY -> stringResource(R.string.MediaSelectScreen__send_in_high_quality) to SignalIcons.QualityHigh
          MoreMenuItem.SEND_STANDARD_QUALITY -> stringResource(R.string.MediaSelectScreen__send_in_standard_quality) to SignalIcons.QualityHighSlash
          MoreMenuItem.SEND_SEPARATELY -> stringResource(R.string.MediaSelectScreen__send_separately) to SignalIcons.Photo
        }
        DropdownMenuItem(
          text = { Text(text = label) },
          leadingIcon = { Icon(imageVector = icon.imageVector, contentDescription = null) },
          onClick = {
            expanded = false
            when (item) {
              MoreMenuItem.SEND_AS_FILE -> if (TellomiHeic.canConvert(selectedMedia)) {
                askToConvertHeic = true
              } else {
                onEvent(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = false))
              }
              MoreMenuItem.SEND_HIGH_QUALITY -> onEvent(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.HIGH))
              MoreMenuItem.SEND_STANDARD_QUALITY -> onEvent(MediaSelectScreenEvents.SendWithQuality(SentMediaQuality.STANDARD))
              MoreMenuItem.SEND_SEPARATELY -> onEvent(MediaSelectScreenEvents.SendSeparately)
            }
          }
        )
      }
    }
  }

  if (askToConvertHeic) {
    // P-5（#1263）：已选里有 HEIC 时先问（Tellomi Desktop 不认 HEIC）。默认「保留 HEIC」；点外面 = 这次不发。
    Dialogs.SimpleAlertDialog(
      title = "",
      body = stringResource(R.string.MediaSelectScreen__convert_photos_to_jpeg),
      confirm = stringResource(R.string.MediaSelectScreen__keep_heic),
      dismiss = stringResource(R.string.MediaSelectScreen__convert_to_jpeg),
      onConfirm = { onEvent(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = false)) },
      onDeny = { onEvent(MediaSelectScreenEvents.SendAsFile(convertHeicToJpeg = true)) },
      onDismiss = { askToConvertHeic = false }
    )
  }
}

/** P-5：一个时「以文件发送」，多个时「以文件发送（N 个）」。 */
@Composable
private fun sendAsFileLabel(count: Int): String {
  return if (count > 1) {
    pluralStringResource(R.plurals.MediaSelectScreen__send_as_n_files, count, count)
  } else {
    stringResource(R.string.MediaSelectScreen__send_as_file)
  }
}

/**
 * P-8：一格。点照片本身进单张预览 / 编辑；右上角的勾选上 / 取消；视频右下角时长。
 */
@Composable
private fun PickerTile(
  media: Media,
  selectionIndex: Int,
  recipientChatColor: Color?,
  onEvent: (MediaSelectScreenEvents) -> Unit
) {
  val openLabel = stringResource(R.string.MediaSelectScreen__open_media)

  // Square regardless of what is in it, so the drag gesture can hit it before the thumbnail has loaded.
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .background(color = MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = { onEvent(MediaSelectScreenEvents.OpenMedia(media)) }, onClickLabel = openLabel, role = Role.Button)
      .testTag(TestTags.mediaPickerTile(media.uri.toString()))
  ) {
    if (LocalInspectionMode.current) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(color = Previews.rememberRandomColor())
      )
    } else {
      GlideImage(
        model = media.uri,
        imageSize = DpSize(maxWidth, maxHeight),
        modifier = Modifier.fillMaxSize()
      )
    }

    if (ContentTypeUtil.isVideo(media.contentType) && !media.isVideoGif) {
      Text(
        text = remember(media.duration) { media.duration.milliseconds.formatAsClock() },
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 4f)),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 6.dp, bottom = 4.dp)
      )
    }

    SelectionCheck(
      selectionIndex = selectionIndex,
      color = recipientChatColor ?: MaterialTheme.colorScheme.primary,
      onClick = { onEvent(MediaSelectScreenEvents.MediaClick(media)) },
      touchSize = PickerMetrics.tileCheckTouchSize,
      visualInset = PickerMetrics.tileCheckVisualInset,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .testTag(TestTags.mediaPickerCheck(media.uri.toString()))
    )
  }
}

/**
 * 编号勾：白色 1.5dp 描边 + 阴影；没选是半透明的空圈，选中填强调色并显示第几张（照 Telegram 两端的编号勾，24dp 同 Telegram Android CheckBox2）。
 * [touchSize] 是整个触摸区；给了 [visualInset] 时圆贴触摸区的右上角、离上右各这么多，否则居中。
 */
@Composable
internal fun SelectionCheck(
  selectionIndex: Int,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  touchSize: Dp = PickerMetrics.checkTouchSize,
  visualSize: Dp = PickerMetrics.checkVisualSize,
  visualInset: Dp? = null
) {
  val isSelected = selectionIndex >= 0
  val description = stringResource(R.string.MediaSelectScreen__select)
  val stateText = if (isSelected) pluralStringResource(R.plurals.MediaSelectScreen__selected_number_n, selectionIndex + 1, selectionIndex + 1) else ""

  Box(
    contentAlignment = if (visualInset != null) Alignment.TopEnd else Alignment.Center,
    modifier = modifier
      .size(touchSize)
      .clickable(onClick = onClick, role = Role.Checkbox)
      .semantics {
        contentDescription = description
        selected = isSelected
        stateDescription = stateText
      }
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .then(if (visualInset != null) Modifier.padding(top = visualInset, end = visualInset) else Modifier)
        .size(visualSize)
        .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
        .background(color = if (isSelected) color else Color.Black.copy(alpha = 0.12f), shape = CircleShape)
        .border(width = PickerMetrics.checkBorder, color = Color.White, shape = CircleShape)
    ) {
      if (isSelected) {
        Text(
          text = "${selectionIndex + 1}",
          color = Color.White,
          style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        )
      }
    }
  }
}

/** P-7：受限访问横幅，在网格里（整行）、跟着网格滚走；「管理」是高 28、圆角 14 的小胶囊。 */
@Composable
private fun LimitedAccessBanner(onEvent: (MediaSelectScreenEvents) -> Unit) {
  val menuController = remember { DropdownMenus.MenuController() }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .testTag(TestTags.MEDIA_PICKER_LIMITED_ACCESS)
  ) {
    Text(
      text = stringResource(R.string.MediaSelectScreen__you_have_limited_access),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier
        .weight(1f)
        .padding(end = 12.dp)
    )

    Box {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(28.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .clickable(role = Role.Button, onClick = menuController::toggle)
          .padding(horizontal = 12.dp)
      ) {
        Text(text = stringResource(R.string.MediaSelectScreen__manage), style = MaterialTheme.typography.labelLarge)
      }

      ManageAccessMenu(menuController = menuController, onEvent = onEvent)
    }
  }
}

private val SendBarBottomPadding = 12.dp

// 与相册查看页底栏对齐：行内 16dp + 外层 10dp。
private val SelectedOnlySendBarBottomPadding = 26.dp

/** P-9：一有选中就出现「添加说明…」+ 表情 + 发送；聊天输入框里已打的字随流程带过来（上游的 initialMessage）。 */
@Composable
private fun PickerSendBar(
  state: MediaSelectState.Files,
  onEvent: (MediaSelectScreenEvents) -> Unit,
  recipientChatColor: Color?,
  bottomPadding: Dp
) {
  val options = state.sendOptions

  AnimatedVisibility(
    visible = state.selectedMedia.isNotEmpty(),
    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    modifier = Modifier.fillMaxWidth()
  ) {
    AddAMessageRow(
      message = options.message,
      onEvent = { event ->
        when (event) {
          is MediaEditScreenEvents.AddMessageClick -> onEvent(MediaSelectScreenEvents.AddMessage(event.startWithEmojiKeyboard))
          MediaEditScreenEvents.ToggleViewOnce -> onEvent(MediaSelectScreenEvents.ToggleViewOnce)
          // owner 2026-09-28（D4）：网格的发送键长按也能定时发送（照 Telegram 长按发送的 schedule 项）。
          is MediaEditScreenEvents.ScheduleSendClick -> onEvent(MediaSelectScreenEvents.ScheduleSend(event.option))
          else -> Unit
        }
      },
      onNextClick = { onEvent(MediaSelectScreenEvents.Send) },
      enabled = !options.isSending,
      canScheduleSend = options.canScheduleSend,
      viewOnceAvailable = options.isViewOnceAvailable,
      viewOnce = options.isViewOnceEnabled,
      isReply = options.isReply,
      recipientChatColor = recipientChatColor,
      modifier = Modifier
        .background(MaterialTheme.colorScheme.surface)
        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding)
        .testTag(TestTags.MEDIA_PICKER_SEND_BAR)
    )
  }
}
