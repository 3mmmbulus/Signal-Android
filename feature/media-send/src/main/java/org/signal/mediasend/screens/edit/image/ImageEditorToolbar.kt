/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.FoldablePortraitDayPreview
import org.signal.core.ui.compose.FoldablePortraitNightPreview
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.IconButtons.iconToggleButtonColors
import org.signal.core.ui.compose.PhonePortraitDayPreview
import org.signal.core.ui.compose.PhonePortraitNightPreview
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.next
import org.signal.imageeditor.core.model.EditorModel
import org.signal.mediasend.EditorState
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.ImageController
import org.signal.mediasend.screens.edit.MediaEditScreenDialogs
import org.signal.mediasend.screens.edit.MediaEditScreenEvents
import org.signal.mediasend.screens.edit.MediaEditState
import org.signal.mediasend.screens.edit.MediaEditorToolbar
import org.signal.mediasend.screens.edit.MediaEditorToolbarButton
import org.signal.mediasend.screens.edit.MediaEditorToolbarSharedButtons
import org.signal.mediasend.test.TestTags
import java.util.EnumMap

@Composable
internal fun ImageEditorToolbar(
  imageEditorController: ImageController,
  state: MediaEditState,
  editorState: EditorState.Image,
  onEvent: (MediaEditScreenEvents) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  when {
    imageEditorController.shouldDisplayTextColorBar -> {
      TextModeColorBar(
        imageEditorController = imageEditorController,
        modifier = modifier
      )
    }
    !imageEditorController.isUserInEdit -> {
      ImageEditorNoneStateToolbar(imageEditorController, state, editorState, onEvent, modifier, enabled)
    }
    imageEditorController.mode == ImageController.Mode.CROP -> {
      ImageEditorCropAndResizeToolbar(imageEditorController, modifier, enabled)
    }
    imageEditorController.mode == ImageController.Mode.ADJUST -> {
      ImageEditorAdjustToolbar(imageEditorController, modifier, enabled)
    }
    else -> {
      ImageEditorDrawStateToolbar(imageEditorController, onEvent, modifier, enabled)
    }
  }
}

/**
 * Allows user to perform actions while viewing an editable image.
 */
@Composable
private fun ImageEditorNoneStateToolbar(
  imageEditorController: ImageController,
  state: MediaEditState,
  editorState: EditorState.Image,
  onEvent: (MediaEditScreenEvents) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  MediaEditorToolbar(modifier) {
    MediaEditorToolbarButton(
      imageVector = SignalIcons.CropRotate.imageVector,
      onClick = imageEditorController::beginCropAndRotateEdit,
      enabled = enabled
    )

    MediaEditorToolbarButton(
      imageVector = SignalIcons.BrushPen.imageVector,
      onClick = imageEditorController::beginDrawEdit,
      enabled = enabled
    )

    // Tellomi（tellomi/tellomi#1593 E5-3）：调色，在画笔后面（照 Telegram 安卓查看器底栏 `PhotoViewer.itemsLayout` 的次序：裁剪、画笔、调整）。
    MediaEditorToolbarButton(
      imageVector = ImageVector.vectorResource(R.drawable.tellomi_adjust_24),
      onClick = imageEditorController::beginAdjustEdit,
      enabled = enabled,
      contentDescription = stringResource(R.string.ImageEditor_tellomi_adjust),
      modifier = Modifier.testTag(TELLOMI_ADJUST_BUTTON_TAG)
    )

    MediaEditorToolbarSharedButtons(
      state = state,
      editorState = editorState,
      onEvent = onEvent,
      enabled = enabled
    )
  }
}

/**
 * Tellomi（tellomi/tellomi#1593 E5-3、#1597 E5-7）：调色页底栏：两头取消 / 完成，中间是页签——工具列表、曲线
 * （照 Telegram 安卓调整页底栏 `PhotoFilterView` 的 tuneItem / curveItem；模糊页签做了再加）。
 */
@Composable
private fun ImageEditorAdjustToolbar(
  imageEditorController: ImageController,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  MediaEditorToolbar(
    modifier = modifier,
    leading = {
      DiscardButton(imageEditorController, enabled)
    },
    trailing = {
      CommitButton(imageEditorController, enabled)
    }
  ) {
    ImageEditorToggleButton(
      imageVector = ImageVector.vectorResource(R.drawable.tellomi_adjust_24),
      checked = imageEditorController.adjustTab == ImageController.AdjustTab.TOOLS,
      enabled = enabled,
      contentDescription = stringResource(R.string.ImageEditor_tellomi_adjust),
      onCheckChanged = { imageEditorController.selectAdjustTab(ImageController.AdjustTab.TOOLS) }
    )

    ImageEditorToggleButton(
      imageVector = ImageVector.vectorResource(R.drawable.tellomi_curves_24),
      checked = imageEditorController.adjustTab == ImageController.AdjustTab.CURVES,
      enabled = enabled,
      contentDescription = stringResource(R.string.ImageEditor_tellomi_adjust_curves),
      onCheckChanged = { imageEditorController.selectAdjustTab(ImageController.AdjustTab.CURVES) }
    )
  }
}

internal const val TELLOMI_ADJUST_BUTTON_TAG = "tellomi-adjust-button"

@Composable
private fun ImageEditorDrawStateToolbar(
  imageEditorController: ImageController,
  onEvent: (MediaEditScreenEvents) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  MediaEditorToolbar(
    modifier = modifier,
    leading = {
      DiscardButton(imageEditorController, enabled)
    },
    trailing = {
      CommitButton(imageEditorController, enabled)
    }
  ) {
    ImageEditorToggleButton(
      imageVector = SignalIcons.Draw.imageVector,
      checked = imageEditorController.isUserDrawing,
      enabled = enabled,
      onCheckChanged = {
        if (!imageEditorController.isUserDrawing) {
          imageEditorController.enterDrawMode()
        }
      }
    )

    ImageEditorToggleButton(
      imageVector = SignalIcons.Text.imageVector,
      checked = imageEditorController.isUserEnteringText,
      enabled = enabled,
      onCheckChanged = {
        if (!imageEditorController.isUserEnteringText) {
          imageEditorController.enterTextMode()
        }
      }
    )

    ImageEditorToggleButton(
      imageVector = SignalIcons.Sticker.imageVector,
      checked = imageEditorController.isUserInsertingSticker,
      enabled = enabled,
      onCheckChanged = {
        // Unconditional: if a previous pick never delivered a result the mode is still INSERT_STICKER, and gating on it
        // would leave the button dead.
        imageEditorController.enterStickerMode()
        onEvent(MediaEditScreenEvents.StickerClick)
      }
    )

    ImageEditorToggleButton(
      imageVector = SignalIcons.Blur.imageVector,
      checked = imageEditorController.isUserBlurring,
      enabled = enabled,
      onCheckChanged = {
        if (!imageEditorController.isUserBlurring) {
          imageEditorController.enterBlurMode()
        }
      }
    )
  }
}

@Composable
private fun ImageEditorCropAndResizeToolbar(
  imageEditorController: ImageController,
  modifier: Modifier = Modifier,
  enabled: Boolean = true
) {
  MediaEditorToolbar(
    modifier = modifier,
    leading = {
      DiscardButton(imageEditorController, enabled)
    },
    trailing = {
      CommitButton(imageEditorController, enabled)
    }
  ) {
    MediaEditorToolbarButton(
      imageVector = SignalIcons.CropRotate.imageVector,
      onClick = imageEditorController::rotate,
      enabled = enabled
    )

    // Tellomi（tellomi/tellomi#1522 E-1）：长按「翻转」选比例（照 Telegram 安卓 `CropRotationWheel` 的 mirrorButton 长按 →
    // `CropView.showAspectRatioDialog`）；选了以后锁住比例，旁边的锁跟着变成锁上。
    var showAspectRatios by remember { mutableStateOf(false) }
    MediaEditorToolbarButton(
      imageVector = SignalIcons.Flip.imageVector,
      onClick = imageEditorController::flip,
      enabled = enabled,
      onLongClick = { showAspectRatios = true },
      onLongClickLabel = stringResource(R.string.ImageEditorToolbar__aspect_ratio),
      modifier = Modifier.testTag(TestTags.IMAGE_EDITOR_FLIP)
    )
    if (showAspectRatios) {
      CropAspectRatioDialog(
        portrait = remember { imageEditorController.isCropPortrait },
        onSelected = imageEditorController::cropTo,
        onDismiss = { showAspectRatios = false }
      )
    }

    val cropLockImageVector = SignalIcons.CropLock.imageVector
    val cropUnlockImageVector = SignalIcons.CropUnlock.imageVector
    val cropLockContentDescription = stringResource(R.string.ImageEditorToolbar__aspect_ratio_locked)
    val cropUnlockContentDescription = stringResource(R.string.ImageEditorToolbar__aspect_ratio_unlocked)

    IconCrossfadeToggleButton(
      enabled = enabled,
      target = if (imageEditorController.isCropAspectRatioLocked) CropLock.LOCKED else CropLock.UNLOCKED,
      setTarget = { target ->
        when (target) {
          CropLock.LOCKED -> imageEditorController.lockCrop()
          CropLock.UNLOCKED -> imageEditorController.unlockCrop()
        }
      },
      targetToImageMap = remember(cropLockImageVector, cropUnlockImageVector) {
        EnumMap<CropLock, ImageVector>(
          CropLock::class.java
        ).apply {
          put(CropLock.LOCKED, cropLockImageVector)
          put(CropLock.UNLOCKED, cropUnlockImageVector)
        }
      },
      targetToContentDescriptionMap = remember(cropLockContentDescription, cropUnlockContentDescription) {
        EnumMap<CropLock, String>(
          CropLock::class.java
        ).apply {
          put(CropLock.LOCKED, cropLockContentDescription)
          put(CropLock.UNLOCKED, cropUnlockContentDescription)
        }
      }
    )
  }
}

/** Tellomi（tellomi/tellomi#1522 E-1）：8 个比例，点一个就裁（同 Telegram 的列表对话框，这里用 Signal 的单选列表）。 */
@Composable
private fun CropAspectRatioDialog(
  portrait: Boolean,
  onSelected: (CropAspectRatio) -> Unit,
  onDismiss: () -> Unit
) {
  val original = stringResource(R.string.ImageEditorToolbar__aspect_ratio_original)
  val square = stringResource(R.string.ImageEditorToolbar__aspect_ratio_square)
  val ratios = CropAspectRatio.entries
  val labels = ratios.map { ratio ->
    when (ratio) {
      CropAspectRatio.ORIGINAL -> original
      CropAspectRatio.SQUARE -> square
      else -> ratio.sides(portrait)?.let { (width, height) -> stringResource(R.string.ImageEditorToolbar__aspect_ratio_x_by_y, width, height) }.orEmpty()
    }
  }
  Dialogs.RadioListDialog(
    onDismissRequest = onDismiss,
    title = stringResource(R.string.ImageEditorToolbar__aspect_ratio),
    labels = labels.toTypedArray(),
    values = ratios.map { it.name }.toTypedArray(),
    selectedIndex = -1,
    onSelected = { index -> onSelected(ratios[index]) }
  )
}

@Composable
private fun CommitButton(imageEditorController: ImageController, enabled: Boolean) {
  MediaEditorToolbarButton(
    imageVector = SignalIcons.Check.imageVector,
    onClick = imageEditorController::commitEdit,
    enabled = enabled,
    colors = IconButtons.iconButtonColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer
    )
  )
}

@Composable
private fun DiscardButton(imageEditorController: ImageController, enabled: Boolean) {
  if (imageEditorController.showDiscardDialog) {
    MediaEditScreenDialogs.DiscardEditsConfirmationDialog(
      onDiscard = imageEditorController::confirmDiscardEdit,
      onDismiss = imageEditorController::dismissDiscardDialog
    )
  }

  MediaEditorToolbarButton(
    imageVector = SignalIcons.X.imageVector,
    onClick = imageEditorController::requestCancelEdit,
    enabled = enabled,
    colors = IconButtons.iconButtonColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
  )
}

@Composable
private inline fun <reified E : Enum<E>> IconCrossfadeToggleButton(
  target: E,
  crossinline setTarget: (E) -> Unit,
  targetToImageMap: EnumMap<E, ImageVector>,
  targetToContentDescriptionMap: EnumMap<E, String>,
  enabled: Boolean = true
) {
  IconButtons.IconButton(
    onClick = { setTarget(target.next()) },
    enabled = enabled
  ) {
    Crossfade(target) { enumValue ->
      Icon(
        imageVector = targetToImageMap[enumValue]!!,
        contentDescription = targetToContentDescriptionMap[enumValue],
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

@Composable
private fun ImageEditorToggleButton(
  imageVector: ImageVector,
  checked: Boolean,
  onCheckChanged: (Boolean) -> Unit,
  contentDescription: String? = null,
  enabled: Boolean = true
) {
  IconButtons.IconToggleButton(
    checked = checked,
    onCheckedChange = onCheckChanged,
    enabled = enabled,
    colors = iconToggleButtonColors(
      checkedContentColor = MaterialTheme.colorScheme.onSurface,
      checkedContainerColor = SignalTheme.colors.colorTransparentInverse2
    )
  ) {
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
  }
}

@PhonePortraitDayPreview
@PhonePortraitNightPreview
@FoldablePortraitDayPreview
@FoldablePortraitNightPreview
@Composable
private fun ImageEditorNoneStateToolbarPreview() {
  Previews.Preview {
    Box(modifier = Modifier.fillMaxSize()) {
      ImageEditorNoneStateToolbar(
        imageEditorController = remember {
          ImageController(EditorModel.create(0))
        },
        state = remember { MediaEditState() },
        editorState = remember { EditorState.Image(EditorModel.create(0)) },
        onEvent = {}
      )
    }
  }
}

@PhonePortraitDayPreview
@PhonePortraitNightPreview
@FoldablePortraitDayPreview
@FoldablePortraitNightPreview
@Composable
private fun ImageEditorDrawStateToolbarPreview() {
  Previews.Preview {
    Box(modifier = Modifier.fillMaxSize()) {
      ImageEditorDrawStateToolbar(
        imageEditorController = remember {
          ImageController(EditorModel.create(0)).apply {
            enterDrawMode()
          }
        },
        onEvent = {}
      )
    }
  }
}

@PhonePortraitDayPreview
@PhonePortraitNightPreview
@FoldablePortraitDayPreview
@FoldablePortraitNightPreview
@Composable
private fun ImageEditorCropAndResizeToolbarPreview() {
  Previews.Preview {
    Box(modifier = Modifier.fillMaxSize()) {
      ImageEditorCropAndResizeToolbar(
        imageEditorController = remember {
          ImageController(EditorModel.create(0)).apply {
            enterCropMode()
          }
        }
      )
    }
  }
}

private enum class CropLock {
  LOCKED, UNLOCKED
}
