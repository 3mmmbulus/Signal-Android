/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    IconButtons.IconButton(
      onClick = {
        if (imageEditorController.mode == ImageController.Mode.DRAW) {
          imageEditorController.enterHighlightMode()
        } else {
          imageEditorController.enterDrawMode()
        }
      },
      colors = IconButtons.iconButtonColors(
        containerColor = SignalTheme.colors.colorSurface5
      ),
      size = 40.dp
    ) {
      Icon(
        imageVector = if (imageEditorController.mode == ImageController.Mode.DRAW) {
          SignalIcons.BrushHighlighter.imageVector
        } else {
          SignalIcons.Draw.imageVector
        },
        contentDescription = stringResource(
          if (imageEditorController.mode == ImageController.Mode.DRAW) {
            R.string.DrawModeColorBar__highlighter
          } else {
            R.string.DrawModeColorBar__brush
          }
        )
      )
    }
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
