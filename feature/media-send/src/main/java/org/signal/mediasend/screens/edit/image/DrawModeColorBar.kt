/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.IconButtons
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.imageeditor.core.model.EditorModel
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.ImageController
import org.signal.mediasend.test.TestTags

/**
 * Color bar that allows the user to change between highlighter and brush.
 */
@Composable
internal fun DrawModeColorBar(
  imageEditorController: ImageController
) {
  Row(
    verticalAlignment = Alignment.CenterVertically
  ) {
    HSVColorBar(
      state = imageEditorController.drawColorBarState,
      onColorChanged = imageEditorController::setDrawColor,
      orientation = ColorBarOrientation.HORIZONTAL,
      modifier = Modifier.weight(1f, fill = false)
    )

    EyedropperButton(imageEditorController)

    BrushMenuButton(imageEditorController)
  }
}

/**
 * Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 两端画笔一排可选）：画笔键显示当前的笔，点开是笔的菜单——笔、箭头、荧光笔、霓虹、橡皮
 * （Telegram 的顺序；它的「马克笔」就是 Signal 的荧光笔）。上游这里是笔 / 荧光笔来回切。
 */
@Composable
private fun BrushMenuButton(imageEditorController: ImageController) {
  val menuController = remember { DropdownMenus.MenuController() }
  val current = imageEditorController.currentBrush

  Box {
    IconButtons.IconButton(
      onClick = menuController::show,
      colors = IconButtons.iconButtonColors(
        containerColor = SignalTheme.colors.colorSurface5
      ),
      size = 40.dp,
      modifier = Modifier.testTag(TestTags.IMAGE_EDITOR_BRUSH_MENU)
    ) {
      Icon(
        imageVector = current.icon(),
        contentDescription = stringResource(R.string.DrawModeColorBar__choose_brush)
      )
    }

    DropdownMenus.Menu(controller = menuController, offsetX = 0.dp) {
      DrawBrush.entries.forEach { brush ->
        DropdownMenus.Item(
          modifier = Modifier.testTag(TestTags.imageEditorBrush(brush)),
          text = {
            Row(
              horizontalArrangement = spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = brush.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
              )
              Text(text = stringResource(brush.label()))
              if (brush == current) {
                Icon(
                  imageVector = SignalIcons.Check.imageVector,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurface
                )
              }
            }
          },
          onClick = {
            menuController.hide()
            imageEditorController.selectBrush(brush)
          }
        )
      }
    }
  }
}

@Composable
private fun DrawBrush.icon(): ImageVector {
  return when (this) {
    DrawBrush.PEN -> SignalIcons.Draw.imageVector
    DrawBrush.ARROW -> ImageVector.vectorResource(R.drawable.tellomi_symbol_brush_arrow_24)
    DrawBrush.HIGHLIGHTER -> SignalIcons.BrushHighlighter.imageVector
    DrawBrush.NEON -> ImageVector.vectorResource(R.drawable.tellomi_symbol_brush_neon_24)
    DrawBrush.ERASER -> ImageVector.vectorResource(R.drawable.tellomi_symbol_brush_eraser_24)
  }
}

private fun DrawBrush.label(): Int {
  return when (this) {
    DrawBrush.PEN -> R.string.DrawModeColorBar__brush
    DrawBrush.ARROW -> R.string.DrawModeColorBar__arrow
    DrawBrush.HIGHLIGHTER -> R.string.DrawModeColorBar__highlighter
    DrawBrush.NEON -> R.string.DrawModeColorBar__neon
    DrawBrush.ERASER -> R.string.DrawModeColorBar__eraser
  }
}

/**
 * Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 两端颜色面板里的吸管键）：点了打开吸管，在画布上取一个颜色。画笔、文字的色条旁边都有。
 */
@Composable
internal fun EyedropperButton(imageEditorController: ImageController) {
  IconButtons.IconButton(
    onClick = imageEditorController::startColorPicking,
    colors = IconButtons.iconButtonColors(
      containerColor = SignalTheme.colors.colorSurface5
    ),
    size = 40.dp,
    modifier = Modifier.testTag(TestTags.IMAGE_EDITOR_EYEDROPPER_BUTTON)
  ) {
    Icon(
      imageVector = ImageVector.vectorResource(R.drawable.tellomi_symbol_eyedropper_24),
      contentDescription = stringResource(R.string.DrawModeColorBar__eyedropper)
    )
  }
}

@DayNightPreviews
@Composable
private fun DrawModeColorBarPreview() {
  Previews.Preview {
    DrawModeColorBar(
      imageEditorController = remember {
        ImageController(
          editorModel = EditorModel.create(0),
          brushWidths = BrushWidthsState()
        )
      }
    )
  }
}
