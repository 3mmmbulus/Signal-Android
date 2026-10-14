/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.content.Context
import android.graphics.Paint
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.imageeditor.core.Renderer
import org.signal.imageeditor.core.SelectableRenderer
import org.signal.imageeditor.core.TappableRenderer
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.imageeditor.core.renderers.BezierDrawingRenderer
import org.signal.imageeditor.core.renderers.MultiLineTextRenderer
import org.signal.mediasend.screens.edit.image.BrushTool
import org.signal.mediasend.screens.edit.image.BrushWidthsState
import org.signal.mediasend.screens.edit.image.CropAspectRatio
import org.signal.mediasend.screens.edit.image.DrawBrush
import org.signal.mediasend.screens.edit.image.FaceDetectionResult
import org.signal.mediasend.screens.edit.image.HSVColorBarState
import org.signal.mediasend.screens.edit.image.ImageEditorState
import org.signal.mediasend.screens.edit.image.detectFaces

/**
 * Holds the editor state for a single image (modes, undo, selection, etc.).
 *
 * Videos have no comparable per-item editor state — no undo stack, no modes — so they are rendered directly based
 * on the focused media type rather than through a controller. Trim data lives in the view-model state, and transient
 * trim/seek interaction flows through the screen's event channel.
 */
@Stable
internal class ImageController(
  private val editorModel: EditorModel,
  private val brushWidths: BrushWidthsState = BrushWidthsState()
) {

  companion object {
    /** Size of a new sticker, relative to the visible area of the image. */
    private const val STICKER_SCALE = 0.4f
  }

  val isUserInEdit: Boolean by derivedStateOf { mode.isEditing }

  val imageEditorState = ImageEditorState(editorModel).also {
    it.onGestureCompleted = { drawSessionDirty = true }
  }

  var mode: Mode by mutableStateOf(Mode.NONE)
    private set

  /** Whether the chrome that [Mode.ZOOM] fades has been tapped back into view. */
  var isChromeRevealed: Boolean by mutableStateOf(false)
    private set

  /** Whether the canvas is easing back to its fit, which nothing else may zoom or pan out from under. */
  var isSettlingZoom: Boolean by mutableStateOf(false)
    private set

  var isCropAspectRatioLocked: Boolean by mutableStateOf(editorModel.isCropAspectLocked)
    private set

  val dialRotation: Float
    get() = editorModel.mainImage?.let { Math.toDegrees(it.localRotationAngle.toDouble()).toFloat() } ?: 0f

  private var initialDialScale: Float = editorModel.mainImage?.localScaleX ?: 1f
  private var initialDialImageDegrees: Float = 0f
  private var minDialScaleDown: Float = 1f
  private var drawSessionSnapshot: ByteArray? by mutableStateOf(null)
  private var drawSessionDirty: Boolean by mutableStateOf(false)
  private var modeBeforeStickerInsertion: Mode = Mode.NONE

  /**
   * The mode a transient one falls back to. Selecting, dragging, inserting a sticker and entering text are all things
   * the user does *within* an editing session, so they must not be able to strand that session -- or start one.
   */
  private var restingMode: Mode = Mode.NONE

  var textEditingElement: EditorElement? by mutableStateOf(null)
    private set

  var selectedElement: EditorElement? by mutableStateOf(null)
    private set

  /** Tellomi（tellomi/tellomi#1522 E-4）：正在编辑 / 选中的那段文字的对齐，对齐键的图标跟着它。 */
  var textAlign: MultiLineTextRenderer.Align by mutableStateOf(MultiLineTextRenderer.Align.CENTER)
    private set

  /** Tellomi（tellomi/tellomi#1522 E-2）：吸管开着（画布上出放大镜，松手取色）。 */
  var isPickingColor: Boolean by mutableStateOf(false)
    private set

  /** Tellomi（tellomi/tellomi#1522 E-4）：正在编辑 / 选中的那段文字的字体，字体键跟着它。 */
  var textFont: MultiLineTextRenderer.Font by mutableStateOf(MultiLineTextRenderer.Font.DEFAULT)
    private set

  val textColorBarState = HSVColorBarState()
  val drawColorBarState = HSVColorBarState()

  var showDiscardDialog: Boolean by mutableStateOf(false)
    private set

  /**
   * Whether there is an uncommitted session to commit or discard. Keyed off the snapshot rather than [mode], because
   * the transient modes -- placing a sticker, entering text, dragging -- all sit on top of a live session.
   */
  private val isInDrawSession: Boolean by derivedStateOf { drawSessionSnapshot != null }

  /** Whether the brush itself is the active tool, which is a narrower question than [isInDrawSession]. */
  private val isPaintMode: Boolean by derivedStateOf { mode == Mode.DRAW || mode == Mode.HIGHLIGHT || mode == Mode.BLUR }

  val hasUnsavedChanges: Boolean by derivedStateOf {
    when {
      mode == Mode.CROP -> imageEditorState.undoAvailable
      mode == Mode.TEXT -> (textEditingElement?.renderer as? MultiLineTextRenderer)?.text?.isNotEmpty() == true
      isInDrawSession -> drawSessionDirty
      else -> false
    }
  }

  val shouldDisplayTextColorBar: Boolean by derivedStateOf {
    textEditingElement != null
  }

  val isUserDrawing: Boolean by derivedStateOf { mode == Mode.DRAW || mode == Mode.HIGHLIGHT }
  val isUserBlurring: Boolean by derivedStateOf { mode == Mode.BLUR }

  /** Tellomi（tellomi/tellomi#1593 E5-3）：在调色页。 */
  val isUserAdjusting: Boolean by derivedStateOf { mode == Mode.ADJUST }

  /** Tellomi（tellomi/tellomi#1593 E5-3）：调色页上显示的调色（跟着模型；打开调色页时从模型读）。 */
  var adjustments: ImageAdjustments by mutableStateOf(editorModel.adjustments)
    private set

  /** Tellomi（tellomi/tellomi#1593 E5-3）：正按住画面看原图。 */
  var isShowingOriginal: Boolean by mutableStateOf(false)
    private set

  /** Tellomi（tellomi/tellomi#1597 E5-7、#1598 E5-8）：调色页的页签：工具列表、模糊、曲线（照 Telegram 安卓调整页底栏的 tune / blur / curve）。 */
  var adjustTab: AdjustTab by mutableStateOf(AdjustTab.TOOLS)
    private set

  /** Tellomi（tellomi/tellomi#1597 E5-7）：曲线页签里在改哪一条。 */
  var curveChannel: ImageAdjustments.CurveChannel by mutableStateOf(ImageAdjustments.CurveChannel.ALL)
    private set

  enum class AdjustTab {
    TOOLS,
    BLUR,
    CURVES
  }

  /** 打开调色页时的调色：取消回到它；完成时整段在撤销栈里只占一步。 */
  private var adjustSessionStart: ImageAdjustments? = null

  var isDetectingFaces: Boolean by mutableStateOf(false)
    private set

  /**
   * Whether the blur-faces toggle reads as on. The request alone holds it on for an image with no face to mask, but once
   * there are masks on the model it follows them, so undoing or clearing them turns it back off.
   */
  val isBlurringFaces: Boolean by derivedStateOf {
    // Reading the revision is what re-derives this when masks are added to or removed from the model.
    imageEditorState.revision
    if (hasAppliedFaceMasks) editorModel.hasFaceRenderer() else isFaceBlurRequested
  }

  private var cachedFaceDetection: FaceDetectionResult? = null

  /** Whether the user has asked for the faces in this image to be masked, which is the toggle's own position. */
  private var isFaceBlurRequested: Boolean by mutableStateOf(false)

  /** Whether the request put masks on the model, which only holds for an image a face was actually found in. */
  private var hasAppliedFaceMasks: Boolean by mutableStateOf(false)

  /**
   * Tellomi (tellomi/tellomi#1522 E-2): which brush the draw tool paints with (pen, arrow, neon or eraser). The
   * highlighter keeps upstream's own [Mode.HIGHLIGHT].
   */
  var drawBrush: DrawBrush by mutableStateOf(DrawBrush.PEN)
    private set

  /** The brush the brush menu shows as chosen. */
  val currentBrush: DrawBrush by derivedStateOf { if (mode == Mode.HIGHLIGHT) DrawBrush.HIGHLIGHTER else drawBrush }

  val brushTool: BrushTool? by derivedStateOf {
    when (mode) {
      Mode.DRAW -> drawBrush.tool
      Mode.HIGHLIGHT -> BrushTool.HIGHLIGHTER
      Mode.BLUR -> BrushTool.BLUR
      else -> null
    }
  }

  val brushWidthFraction: Float by derivedStateOf { brushTool?.let { brushWidths[it] } ?: 0f }

  val brushThickness: Float by derivedStateOf { brushTool?.thicknessAt(brushWidthFraction) ?: 0f }

  val isUserEnteringText: Boolean by derivedStateOf { mode == Mode.TEXT }
  val isUserInsertingSticker: Boolean by derivedStateOf { mode == Mode.INSERT_STICKER }

  var isDraggedElementOverTrash: Boolean by mutableStateOf(false)
    private set

  /** Whether back should back out of an editor mode rather than leaving the screen. */
  val canHandleBack: Boolean by derivedStateOf {
    when (mode) {
      // The sticker picker is its own window and owns back while it is up, and DELETE only exists mid-drag.
      Mode.INSERT_STICKER, Mode.DELETE -> false
      Mode.NONE -> selectedElement != null
      else -> true
    }
  }

  /** Whether the chrome is out of the way of a zoomed image, waiting on a tap to come back. */
  val isChromeFadedForZoom: Boolean by derivedStateOf { mode == Mode.ZOOM && !isChromeRevealed }

  /**
   * Backs out of the current mode into the one before it. Draw and crop sessions go through [requestCancelEdit] so that
   * backing out of a dirty one asks first rather than silently throwing the work away.
   */
  suspend fun onBackPressed() {
    // A selection is a level of its own: back gives that up before it gives up the mode.
    if (selectedElement != null) {
      clearSelection()
      return
    }

    when (mode) {
      Mode.TEXT -> finishTextEditing()
      Mode.DRAW, Mode.HIGHLIGHT, Mode.BLUR, Mode.CROP, Mode.ADJUST -> requestCancelEdit()
      Mode.ZOOM -> exitZoomMode()
      Mode.NONE, Mode.INSERT_STICKER, Mode.DELETE -> Unit
    }
  }

  fun requestCancelEdit() {
    if (hasUnsavedChanges) {
      showDiscardDialog = true
    } else {
      cancelEdit()
    }
  }

  fun dismissDiscardDialog() {
    showDiscardDialog = false
  }

  fun confirmDiscardEdit() {
    showDiscardDialog = false
    cancelEdit()
  }

  fun beginDrawEdit() {
    enterDrawMode()
  }

  fun beginCropAndRotateEdit() {
    enterCropMode()
  }

  /** Tellomi（tellomi/tellomi#1593 E5-3）：打开调色页（照 Telegram 安卓 `PhotoFilterView`）。 */
  fun beginAdjustEdit() {
    clearSelection()
    adjustSessionStart = editorModel.adjustments
    adjustments = editorModel.adjustments
    adjustTab = AdjustTab.TOOLS
    curveChannel = ImageAdjustments.CurveChannel.ALL
    transitionTo(Mode.ADJUST)
  }

  /** Tellomi（tellomi/tellomi#1597 E5-7）：换页签。 */
  fun selectAdjustTab(tab: AdjustTab) {
    adjustTab = tab
  }

  /** Tellomi（tellomi/tellomi#1597 E5-7）：曲线页签里换一条（全部 / 红 / 绿 / 蓝）。 */
  fun selectCurveChannel(channel: ImageAdjustments.CurveChannel) {
    curveChannel = channel
  }

  /** Tellomi（tellomi/tellomi#1598 E5-8）：选「关 / 径向 / 线性」：中心、半径、角度留着上次的。 */
  fun selectBlurType(type: ImageAdjustments.BlurType) {
    setBlur(adjustments.blur.copy(type = type))
  }

  /** Tellomi（tellomi/tellomi#1598 E5-8）：拖、捏模糊控件。和拖滑杆一样，只改预览、完成时记一步撤销。 */
  fun setBlur(blur: ImageAdjustments.Blur) {
    val updated = adjustments.copy(blur = blur)
    if (updated == adjustments) {
      return
    }
    adjustments = updated
    editorModel.setAdjustments(updated, false)
  }

  /**
   * Tellomi（tellomi/tellomi#1597 E5-7）：把当前那条曲线的第 [index] 个点（0 = 黑 … 4 = 白）往上挪 [delta]（0…100 的单位，夹在 0…100）。
   * 和拖滑杆一样，只改预览、完成时记一步撤销。
   */
  fun moveCurvePoint(index: Int, delta: Float) {
    val points = adjustments.curves[curveChannel]
    val updated = adjustments.copy(curves = adjustments.curves.with(curveChannel, points.with(index, points[index] + delta)))
    if (updated == adjustments) {
      return
    }
    adjustments = updated
    editorModel.setAdjustments(updated, false)
  }

  /** Tellomi（tellomi/tellomi#1593 E5-3）：拖滑杆。只改预览、不记撤销；完成时再记一步。 */
  fun setAdjustment(tool: ImageAdjustments.Tool, value: Float) {
    val updated = adjustments.with(tool, value)
    if (updated == adjustments) {
      return
    }
    adjustments = updated
    editorModel.setAdjustments(updated, false)
  }

  /** Tellomi（tellomi/tellomi#1596 E5-6）：点色调色块（[ImageAdjustments.NO_TINT] = 不加）。和拖滑杆一样，只改预览、完成时记一步撤销。 */
  fun setTint(shadows: Boolean, color: Int) {
    val updated = if (shadows) adjustments.copy(tintShadowsColor = color) else adjustments.copy(tintHighlightsColor = color)
    if (updated == adjustments) {
      return
    }
    adjustments = updated
    editorModel.setAdjustments(updated, false)
  }

  /** Tellomi（tellomi/tellomi#1593 E5-3）：按住画面看原图（照 Telegram 安卓 `PhotoFilterView.onTouch` → `setShowOriginal`）。 */
  fun setShowsOriginal(showsOriginal: Boolean) {
    isShowingOriginal = showsOriginal
    editorModel.setShowsOriginal(showsOriginal)
  }

  fun cancelEdit() {
    when {
      mode == Mode.TEXT -> {
        finishTextEditing()
      }
      mode == Mode.CROP -> {
        editorModel.clearUndoStack()
        editorModel.doneCrop()
        exitEditMode()
      }
      mode == Mode.ADJUST -> {
        adjustSessionStart?.let { editorModel.setAdjustments(it, false) }
        exitEditMode()
      }
      isInDrawSession -> {
        drawSessionSnapshot?.let { editorModel.restoreFromSnapshot(it) }
        exitEditMode()
      }
      else -> exitEditMode()
    }
  }

  fun commitEdit() {
    when (mode) {
      Mode.TEXT -> finishTextEditing()
      Mode.CROP -> {
        editorModel.doneCrop()
        exitEditMode()
      }
      Mode.ADJUST -> {
        val start = adjustSessionStart
        val final = editorModel.adjustments
        if (start != null && start != final) {
          // 先悄悄回到打开时的样子，再记一步换成最终的：撤销栈里整段调色只有这一步。
          editorModel.setAdjustments(start, false)
          editorModel.setAdjustments(final, true)
        }
        exitEditMode()
      }
      else -> exitEditMode()
    }
  }

  private fun exitEditMode() {
    if (mode == Mode.ADJUST) {
      setShowsOriginal(false)
      adjustSessionStart = null
      adjustments = editorModel.adjustments
    }
    drawSessionSnapshot = null
    drawSessionDirty = false
    selectedElement = null
    transitionTo(Mode.NONE)
    imageEditorState.isDrawing = false
    imageEditorState.isBlur = false
  }

  private fun transitionTo(newMode: Mode) {
    // A zoom only survives in Mode.ZOOM: every other mode has spent two fingers on something else, leaving no way back
    // out of one.
    if (mode == Mode.ZOOM && newMode != Mode.ZOOM) {
      isChromeRevealed = false
      imageEditorState.clearZoom()
    }

    if (!newMode.isTransient) {
      restingMode = newMode.asRestingMode
    }

    mode = newMode
  }

  /**
   * The mode a transient one hands back to. Never [Mode.ZOOM]: leaving it dropped the zoom, so coming back would leave
   * the pager locked around an image sitting at its fit scale.
   */
  private val Mode.asRestingMode: Mode
    get() = if (this == Mode.ZOOM) Mode.NONE else this

  /**
   * Pinches the canvas, opening [Mode.ZOOM] on the way up from the fit scale and closing it again on the way back down,
   * so a pinch that undoes itself hands the pager and the selection back without needing a double tap.
   */
  fun zoomBy(focusX: Float, focusY: Float, scaleFactor: Float, panX: Float, panY: Float) {
    if (isSettlingZoom || (mode != Mode.NONE && mode != Mode.ZOOM)) {
      return
    }

    imageEditorState.zoomBy(focusX, focusY, scaleFactor, panX, panY)

    when {
      imageEditorState.isZoomed && mode == Mode.NONE -> enterZoomMode()
      !imageEditorState.isZoomed && mode == Mode.ZOOM -> transitionTo(Mode.NONE)
    }
  }

  fun panBy(panX: Float, panY: Float) {
    if (isSettlingZoom || mode != Mode.ZOOM) {
      return
    }

    imageEditorState.panBy(panX, panY)
  }

  private fun enterZoomMode() {
    clearSelection()
    transitionTo(Mode.ZOOM)
  }

  /**
   * Eases the canvas back to its fit and then leaves zoom mode. The chrome is revealed up front so that it fades back in
   * alongside the image rather than waiting for it to land.
   */
  suspend fun exitZoomMode() {
    if (mode != Mode.ZOOM || isSettlingZoom) {
      return
    }

    isSettlingZoom = true
    isChromeRevealed = true

    try {
      imageEditorState.animateZoomToFit()
    } finally {
      isSettlingZoom = false
      transitionTo(Mode.NONE)
    }
  }

  fun toggleChromeRevealed() {
    isChromeRevealed = !isChromeRevealed
  }

  /** Ends a transient mode by returning to the session the user was in before it. */
  private fun returnToRestingMode() {
    when (restingMode) {
      Mode.DRAW, Mode.HIGHLIGHT, Mode.BLUR -> {
        transitionTo(restingMode)
        syncDrawingState()
      }

      // The crop is still open on the model, so there is nothing to restart -- only the mode to hand back.
      Mode.CROP -> transitionTo(Mode.CROP)

      else -> exitEditMode()
    }

    imageEditorState.invalidate()
  }

  /** Tellomi (tellomi/tellomi#1522 E-2): picks a brush from the brush menu. */
  fun selectBrush(brush: DrawBrush) {
    val previous = currentBrush
    if (brush != previous) {
      swapBrushColor(from = previous, to = brush)
    }
    if (brush == DrawBrush.HIGHLIGHTER) {
      enterHighlightMode()
    } else {
      drawBrush = brush
      enterDrawMode()
    }
  }

  /** Tellomi (tellomi/tellomi#1522 E-2): the color bar position and picked color each brush was last left with. */
  private val brushColors = mutableMapOf<DrawBrush, Pair<Float, Int?>>()

  /**
   * Tellomi (tellomi/tellomi#1522 E-2, as both Telegram clients): every brush keeps its own color. The color bar is
   * put away for the brush being left and brought back (or set to the brush's default) for the next one; the eraser
   * has no color and leaves the bar alone.
   */
  private fun swapBrushColor(from: DrawBrush, to: DrawBrush) {
    if (from != DrawBrush.ERASER) {
      brushColors[from] = drawColorBarState.fraction to drawColorBarState.customColor
    }
    if (to == DrawBrush.ERASER) return

    val saved = brushColors[to]
    if (saved != null) {
      drawColorBarState.fraction = saved.first
      drawColorBarState.customColor = saved.second
    } else {
      to.defaultColor?.let(drawColorBarState::show)
    }
  }

  fun enterDrawMode() {
    clearSelection()
    snapshotIfNewDrawSession()
    transitionTo(Mode.DRAW)
    syncDrawingState()
  }

  fun enterHighlightMode() {
    clearSelection()
    snapshotIfNewDrawSession()
    transitionTo(Mode.HIGHLIGHT)
    syncDrawingState()
  }

  fun enterBlurMode() {
    clearSelection()
    snapshotIfNewDrawSession()
    transitionTo(Mode.BLUR)
    syncDrawingState()
  }

  private fun snapshotIfNewDrawSession() {
    if (drawSessionSnapshot == null) {
      drawSessionSnapshot = editorModel.createSnapshot()
      drawSessionDirty = false
    }
  }

  fun undo() {
    editorModel.undo()
  }

  fun redo() {
    editorModel.redo()
  }

  fun clearAllEdits() {
    isFaceBlurRequested = false
    hasAppliedFaceMasks = false

    while (imageEditorState.undoAvailable) {
      editorModel.undo()
    }
  }

  /**
   * Masks every face in the image, finding them first unless a previous detection still describes the image as it is now
   * cropped. Suspends for as long as the detection takes, which [isDetectingFaces] reports so the screen can say so.
   */
  suspend fun blurFaces(context: Context) {
    isFaceBlurRequested = true
    hasAppliedFaceMasks = false

    if (isDetectingFaces) {
      return
    }

    val cached = cachedFaceDetection?.takeIf { it.matches(editorModel) }
    if (cached != null) {
      applyFaceBlurs(cached)
      return
    }

    isDetectingFaces = true
    val result = try {
      withContext(Dispatchers.Default) {
        detectFaces(context, editorModel, imageEditorState.typefaceProvider)
      }
    } finally {
      isDetectingFaces = false
    }

    // The toggle can go back off while detection runs, and finding faces after that must not mask them anyway.
    if (isFaceBlurRequested) {
      applyFaceBlurs(result)
    }
  }

  fun clearFaceBlurs() {
    isFaceBlurRequested = false
    hasAppliedFaceMasks = false

    if (!editorModel.hasFaceRenderer()) {
      return
    }

    editorModel.clearFaceRenderers()
    drawSessionDirty = true
    imageEditorState.invalidate()
  }

  private fun applyFaceBlurs(result: FaceDetectionResult) {
    if (result.faces.isEmpty()) {
      // Not worth keeping: a re-run once the image has loaded, or after a crop, may well find something.
      cachedFaceDetection = null
      return
    }

    editorModel.addFaceBlurs(result.faces, result.renderSize, result.cropPosition)
    cachedFaceDetection = result
    hasAppliedFaceMasks = true
    drawSessionDirty = true
    imageEditorState.invalidate()
  }

  fun setDrawColor(color: Int) {
    imageEditorState.drawColor = brushTool?.applyAlpha(color) ?: color
  }

  fun setBrushWidthFraction(fraction: Float) {
    val tool = brushTool ?: return
    brushWidths.set(tool, fraction)
    imageEditorState.drawThickness = tool.thicknessAt(fraction)
  }

  private fun syncDrawingState() {
    // A selected element takes the touch: with the brush live, a sticker the user just placed could not be moved.
    val canPaint = selectedElement == null

    imageEditorState.isDrawing = canPaint
    imageEditorState.isBlur = canPaint && mode == Mode.BLUR
    imageEditorState.drawCap = if (mode == Mode.HIGHLIGHT) Paint.Cap.SQUARE else Paint.Cap.ROUND
    imageEditorState.drawStyle = if (mode == Mode.DRAW) drawBrush.style else BezierDrawingRenderer.Style.NORMAL
    imageEditorState.drawThickness = brushThickness
    setDrawColor(drawColorBarState.color)
  }

  fun enterCropMode() {
    clearSelection()
    editorModel.startCrop()
    initialDialScale = editorModel.mainImage?.localScaleX ?: 1f
    transitionTo(Mode.CROP)
  }

  fun enterTextMode() {
    snapshotIfNewDrawSession()
    val renderer = MultiLineTextRenderer("", textColorBarState.color, MultiLineTextRenderer.Mode.REGULAR, MultiLineTextRenderer.Align.CENTER)
    val element = EditorElement(renderer, EditorModel.Z_TEXT)
    editorModel.addElementCentered(element, 1f)
    beginTextEditing(element)
  }

  private fun beginTextEditing(element: EditorElement) {
    transitionTo(Mode.TEXT)
    textEditingElement = element
    imageEditorState.textEditingElement = element
    (element.renderer as? MultiLineTextRenderer)?.let {
      textAlign = it.align
      textFont = it.font
    }
    editorModel.addFade()
    editorModel.setSelectionVisible(false)
    (element.renderer as? MultiLineTextRenderer)?.setFocused(true)
  }

  fun finishTextEditing() {
    val element = textEditingElement ?: return
    val renderer = element.renderer as? MultiLineTextRenderer
    val hasText = renderer?.text?.isNotEmpty() == true

    renderer?.setFocused(false)
    editorModel.zoomOut()
    editorModel.removeFade()
    editorModel.setSelectionVisible(true)

    textEditingElement = null
    imageEditorState.textEditingElement = null

    if (hasText) {
      drawSessionDirty = true

      // Staying selected keeps the brush off it, so it can still be moved or double-tapped back open.
      selectElement(element)
    } else {
      // Drop just the abandoned element. Restoring the draw session snapshot here would take every stroke made before
      // text entry with it.
      clearSelection()
      editorModel.delete(element)
      editorModel.updateUndoRedoAvailabilityState()
    }

    // Returning to the session that text entry was started from keeps its commit and discard available, rather than
    // banking the work without the user ever confirming it.
    returnToRestingMode()
  }

  fun onTextChanged(text: String) {
    val element = textEditingElement ?: return
    val renderer = element.renderer as? MultiLineTextRenderer ?: return
    renderer.setText(text)
    imageEditorState.invalidate()
  }

  fun onTextSelectionChanged(selStart: Int, selEnd: Int) {
    val element = textEditingElement ?: return
    val renderer = element.renderer as? MultiLineTextRenderer ?: return
    renderer.setSelection(selStart, selEnd)
    editorModel.zoomToTextElement(element, renderer)
    imageEditorState.invalidate()
  }

  fun setTextColor(color: Int) {
    val element = textEditingElement ?: selectedElement
    val renderer = element?.renderer as? MultiLineTextRenderer ?: return
    renderer.color = color
    imageEditorState.invalidate()
  }

  /** Cycles the text element through regular, highlight, semi-transparent (Tellomi), underline, and outline. */
  fun nextTextStyle() {
    val element = textEditingElement ?: selectedElement
    val renderer = element?.renderer as? MultiLineTextRenderer ?: return
    renderer.nextMode()
    imageEditorState.invalidate()
  }

  /** Tellomi（tellomi/tellomi#1522 E-4）：对齐按 左 → 中 → 右 循环（照 Telegram 安卓 `PaintTextOptionsView` 的 `(currentAlign + 1) % 3`）。 */
  /** Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 两端颜色面板的吸管）：打开吸管。 */
  fun startColorPicking() {
    isPickingColor = true
  }

  fun cancelColorPicking() {
    isPickingColor = false
  }

  /**
   * 吸管取到颜色：在写文字（或选中了文字）就给文字，否则给画笔 / 荧光笔；对应的色条记住这个颜色。
   */
  fun pickColor(@ColorInt color: Int) {
    isPickingColor = false
    val opaque = color or 0xFF000000.toInt()
    val element = textEditingElement ?: selectedElement
    if (mode == Mode.TEXT || element?.renderer is MultiLineTextRenderer) {
      textColorBarState.customColor = opaque
      setTextColor(opaque)
    } else {
      drawColorBarState.customColor = opaque
      setDrawColor(opaque)
    }
    imageEditorState.invalidate()
  }

  /** Tellomi（tellomi/tellomi#1522 E-4）：换下一个字体（照 Telegram 两端文字工具的字体列表）。 */
  fun nextTextFont() {
    val element = textEditingElement ?: selectedElement
    val renderer = element?.renderer as? MultiLineTextRenderer ?: return
    renderer.font = renderer.font.next()
    textFont = renderer.font
    imageEditorState.invalidate()
  }

  fun nextTextAlign() {
    val element = textEditingElement ?: selectedElement
    val renderer = element?.renderer as? MultiLineTextRenderer ?: return
    renderer.align = renderer.align.next()
    textAlign = renderer.align
    imageEditorState.invalidate()
  }

  fun onEntityDown(element: EditorElement?) {
    // Crop makes the main image selectable so it can be panned inside the frame, but selecting it would draw the
    // selection guide around the whole image, well outside the crop frame.
    if (mode == Mode.ZOOM || mode == Mode.CROP) {
      return
    }

    if (element != null && element.renderer is SelectableRenderer) {
      selectElement(element)
    } else {
      clearSelection()
    }
  }

  fun onEntitySingleTap(element: EditorElement?) {
    if (mode == Mode.ZOOM) {
      return
    }

    val tappable = element?.renderer as? TappableRenderer ?: return

    tappable.onTapped()
    imageEditorState.invalidate()
  }

  /** Re-opens an existing text element, which is how the user gets back to its color and style controls. */
  fun onEntityDoubleTap(element: EditorElement?) {
    if (mode == Mode.CROP || mode == Mode.ZOOM || element == null || element.renderer !is MultiLineTextRenderer) {
      return
    }

    beginTextEditing(element)
  }

  private fun selectElement(element: EditorElement) {
    (selectedElement?.renderer as? SelectableRenderer)?.onSelected(false)
    (element.renderer as? SelectableRenderer)?.onSelected(true)
    editorModel.setSelected(element)
    selectedElement = element
    (element.renderer as? MultiLineTextRenderer)?.let {
      textAlign = it.align
      textFont = it.font
    }

    if (isPaintMode) {
      syncDrawingState()
    }

    imageEditorState.invalidate()
  }

  private fun clearSelection() {
    val element = selectedElement ?: return

    (element.renderer as? SelectableRenderer)?.onSelected(false)
    editorModel.setSelected(null)
    selectedElement = null

    if (isPaintMode) {
      syncDrawingState()
    }

    imageEditorState.invalidate()
  }

  fun enterStickerMode() {
    // Re-opening the picker must not record INSERT_STICKER as the mode to come back to.
    if (mode != Mode.INSERT_STICKER) {
      modeBeforeStickerInsertion = mode.asRestingMode
    }

    transitionTo(Mode.INSERT_STICKER)
  }

  fun insertSticker(renderer: Renderer) {
    val element = EditorElement(renderer, EditorModel.Z_STICKERS)
    editorModel.addElementCentered(element, STICKER_SCALE)
    drawSessionDirty = true

    // Selecting first leaves the new sticker holding the touch, so a draw session resumed here does not paint over it.
    selectElement(element)
    returnToRestingMode()
  }

  fun cancelStickerInsertion() {
    if (mode == Mode.INSERT_STICKER) {
      transitionTo(modeBeforeStickerInsertion)
    }
  }

  /** Reveals the trash so the drag can end in a delete. */
  fun onDragStarted(element: EditorElement?) {
    if (mode == Mode.CROP || element == null || element.renderer !is SelectableRenderer) {
      return
    }

    selectElement(element)
    isDraggedElementOverTrash = false
    transitionTo(Mode.DELETE)
    setTrashVisible(true)
  }

  fun onDragMoved(element: EditorElement?, isOverTrash: Boolean) {
    if (mode != Mode.DELETE || element == null || isOverTrash == isDraggedElementOverTrash) {
      return
    }

    isDraggedElementOverTrash = isOverTrash

    if (isOverTrash) {
      element.animatePartialFadeOut(imageEditorState::invalidate)
    } else {
      element.animatePartialFadeIn(imageEditorState::invalidate)
    }
  }

  fun onDragEnded(element: EditorElement?, isOverTrash: Boolean) {
    if (mode != Mode.DELETE) {
      return
    }

    isDraggedElementOverTrash = false
    setTrashVisible(false)

    if (element == null || isOverTrash) {
      element?.let {
        editorModel.delete(it)
        editorModel.updateUndoRedoAvailabilityState()
        drawSessionDirty = true
      }

      clearSelection()
      returnToRestingMode()
    } else {
      element.animatePartialFadeIn(imageEditorState::invalidate)

      // Moving something is not an edit mode of its own, so the drag hands back whatever the user was in -- but the
      // element they were just dragging stays selected.
      returnToRestingMode()
      selectElement(element)
    }
  }

  private fun setTrashVisible(visible: Boolean) {
    editorModel.trash.flags
      .setVisible(visible)
      .persist()

    imageEditorState.invalidate()
  }

  fun lockCrop() {
    editorModel.setCropAspectLock(true)
    isCropAspectRatioLocked = true
  }

  /** Tellomi（tellomi/tellomi#1522 E-1）：现在的裁剪框是不是竖的（高大于宽），比例菜单按它倒过来写。 */
  val isCropPortrait: Boolean
    get() = editorModel.cropAspectRatioOnScreen < 1f

  /** Tellomi（tellomi/tellomi#1522 E-1）：裁成这个比例并锁住（照 Telegram 选了比例就 `setLockedAspectRatio`）。 */
  internal fun cropTo(aspectRatio: CropAspectRatio) {
    val widthToHeight = aspectRatio.widthToHeight(editorModel.orientedImageAspectRatio, isCropPortrait)
    editorModel.setCropAspectRatio(widthToHeight)
    isCropAspectRatioLocked = editorModel.isCropAspectLocked
  }

  fun unlockCrop() {
    editorModel.setCropAspectLock(false)
    isCropAspectRatioLocked = false
  }

  fun flip() {
    editorModel.flipHorizontal()
  }

  fun rotate() {
    editorModel.rotate90anticlockwise()
  }

  fun onDialGestureStart() {
    imageEditorState.isGestureActive = true
    val mainImage = editorModel.mainImage ?: return
    initialDialScale = mainImage.localScaleX
    minDialScaleDown = 1f
    editorModel.pushUndoPoint()
    editorModel.updateUndoRedoAvailabilityState()
    initialDialImageDegrees = Math.toDegrees(mainImage.localRotationAngle.toDouble()).toFloat()
  }

  fun onDialRotationChanged(degrees: Float) {
    editorModel.setMainImageEditorMatrixRotation(degrees - initialDialImageDegrees, minDialScaleDown)
  }

  fun onDialGestureEnd() {
    imageEditorState.isGestureActive = false
    val mainImage = editorModel.mainImage ?: return
    mainImage.commitEditorMatrix()
    editorModel.postEdit(true)
    initialDialScale = mainImage.localScaleX
  }

  enum class Mode {
    NONE,
    ZOOM,
    CROP,
    TEXT,
    DRAW,
    HIGHLIGHT,
    BLUR,
    DELETE,
    INSERT_STICKER,

    /** Tellomi（tellomi/tellomi#1593 E5-3）：调色页。 */
    ADJUST;

    /** Whether this is something done within a session rather than a session in its own right. */
    internal val isTransient: Boolean
      get() = this == TEXT || this == DELETE || this == INSERT_STICKER

    /** Whether an editing session is open. [NONE] and [ZOOM] are ways of looking at the image rather than changing it. */
    internal val isEditing: Boolean
      get() = this != NONE && this != ZOOM
  }

  /**
   * The controller for each image in the selection. Owned by the view-model rather than the composition so edits from
   * outside the Edit screen apply right away.
   */
  @Stable
  class Container(
    private val brushWidths: BrushWidthsState = BrushWidthsState()
  ) {
    private val controllers = SnapshotStateMap<Uri, ImageController>()

    fun getOrCreate(uri: Uri, editorModel: EditorModel): ImageController {
      val existing = controllers[uri]
      if (existing != null && existing.editorModel === editorModel) {
        return existing
      }

      // Re-adding a removed item builds it a fresh model, leaving any cached controller editing one nobody renders.
      return ImageController(editorModel, brushWidths).also { controllers[uri] = it }
    }

    fun remove(uri: Uri) {
      controllers.remove(uri)
    }
  }
}
