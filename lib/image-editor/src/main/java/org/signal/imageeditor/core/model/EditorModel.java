package org.signal.imageeditor.core.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.imageeditor.core.Bounds;
import org.signal.imageeditor.core.ColorableRenderer;
import org.signal.imageeditor.core.Renderer;
import org.signal.imageeditor.core.RendererContext;
import org.signal.imageeditor.core.UndoRedoStackListener;
import org.signal.imageeditor.core.renderers.FaceBlurRenderer;
import org.signal.imageeditor.core.renderers.MultiLineTextRenderer;
import org.signal.imageeditor.core.renderers.UriGlideRenderer;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contains a reference to the root {@link EditorElement}, maintains undo and redo stacks and has a
 * reference to the {@link EditorElementHierarchy}.
 * <p>
 * As such it is the entry point for all operations that change the image.
 */
public final class EditorModel implements Parcelable, RendererContext.Ready {

  public static final int Z_MASK     = -1;
  public static final int Z_DRAWING  = 0;
  public static final int Z_STICKERS = 0;
  public static final int Z_FADE     = 1;
  public static final int Z_TEXT     = 2;
  public static final int Z_TRASH    = 3;
  public static final int Z_CIRCLE   = 4;

  private static final Runnable NULL_RUNNABLE = () -> {
  };

  private static final int MINIMUM_OUTPUT_WIDTH = 1024;

  private static final int   MINIMUM_CROP_PIXEL_COUNT = 100;
  private static final Point MINIMUM_RATIO            = new Point(15, 1);

  @NonNull
  private Runnable invalidate = NULL_RUNNABLE;

  private UndoRedoStackListener undoRedoStackListener;

  private final UndoRedoStacks undoRedoStacks;
  private final UndoRedoStacks cropUndoRedoStacks;
  private final InBoundsMemory inBoundsMemory     = new InBoundsMemory();

  private EditorElementHierarchy editorElementHierarchy;

  private final RectF          visibleViewPort = new RectF();
  private final Point          size;
  private final EditingPurpose editingPurpose;
  private       float          fixedRatio;

  public void setSelected(@Nullable EditorElement editorElement) {
    if (editorElement == null) {
      editorElementHierarchy.removeAllSelectionArtifacts();
    } else {
      Matrix overlayMappingMatrix = findRelativeMatrix(editorElement, editorElementHierarchy.getOverlay());
      editorElementHierarchy.setOrUpdateSelectionThumbsForElement(editorElement, overlayMappingMatrix);
    }
  }

  public void updateSelectionThumbsIfSelected(@NonNull EditorElement editorElement) {
    Matrix overlayMappingMatrix = findRelativeMatrix(editorElement, editorElementHierarchy.getOverlay());
    editorElementHierarchy.updateSelectionThumbsForElement(editorElement, overlayMappingMatrix);
  }

  public void setSelectionVisible(boolean visible) {
    editorElementHierarchy.getSelection()
                          .getFlags()
                          .setVisible(visible)
                          .setChildrenVisible(visible)
                          .persist();
  }

  private enum EditingPurpose {
    IMAGE,
    AVATAR_CAPTURE,
    AVATAR_EDIT,
    WALLPAPER
  }

  private EditorModel(@NonNull Parcel in) {
    ClassLoader classLoader     = getClass().getClassLoader();
    this.editingPurpose         = EditingPurpose.values()[in.readInt()];
    this.fixedRatio             = in.readFloat();
    this.size                   = new Point(in.readInt(), in.readInt());
    //noinspection ConstantConditions
    this.editorElementHierarchy = EditorElementHierarchy.create(in.readParcelable(classLoader));
    this.undoRedoStacks         = in.readParcelable(classLoader);
    this.cropUndoRedoStacks     = in.readParcelable(classLoader);
  }

  public EditorModel(@NonNull EditingPurpose editingPurpose, float fixedRatio, @NonNull EditorElementHierarchy editorElementHierarchy) {
    this.editingPurpose         = editingPurpose;
    this.fixedRatio             = fixedRatio;
    this.size                   = new Point(1024, 1024);
    this.editorElementHierarchy = editorElementHierarchy;
    this.undoRedoStacks         = new UndoRedoStacks(50);
    this.cropUndoRedoStacks     = new UndoRedoStacks(50);
  }

  public static EditorModel create(@ColorInt int blackoutColor) {
    EditorModel model = new EditorModel(EditingPurpose.IMAGE, 0, EditorElementHierarchy.create(blackoutColor));
    model.setCropAspectLock(false);
    return model;
  }

  public static EditorModel createForAvatarCapture(@ColorInt int blackoutColor) {
    EditorModel editorModel = new EditorModel(EditingPurpose.AVATAR_CAPTURE, 1, EditorElementHierarchy.createForCircleEditing(blackoutColor));
    editorModel.setCropAspectLock(true);
    return editorModel;
  }

  public static EditorModel createForAvatarEdit(@ColorInt int blackoutColor) {
    EditorModel editorModel = new EditorModel(EditingPurpose.AVATAR_EDIT, 1, EditorElementHierarchy.createForCircleEditing(blackoutColor));
    editorModel.setCropAspectLock(true);
    return editorModel;
  }

  public static EditorModel createForWallpaperEditing(float fixedRatio, @ColorInt int blackoutColor) {
    EditorModel editorModel = new EditorModel(EditingPurpose.WALLPAPER, fixedRatio, EditorElementHierarchy.createForPinchAndPanCropping(blackoutColor));
    editorModel.setCropAspectLock(true);
    return editorModel;
  }

  public void setInvalidate(@Nullable Runnable invalidate) {
    this.invalidate = invalidate != null ? invalidate : NULL_RUNNABLE;
  }

  public void setUndoRedoStackListener(UndoRedoStackListener undoRedoStackListener) {
    this.undoRedoStackListener = undoRedoStackListener;

    updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()));
  }

  /** Keeps the image within the crop bounds as it rotates */
  public void setMainImageEditorMatrixRotation(float angle, float minScaleDown) {
    setEditorMatrixToRotationMatrixAboutParentsOrigin(editorElementHierarchy.getMainImage(), angle);
    scaleMainImageEditorMatrixToFitInsideCropBounds(minScaleDown, 2f);
    invalidate.run();
  }

  private void scaleMainImageEditorMatrixToFitInsideCropBounds(float minScaleDown, float maxScaleUp) {
    EditorElement mainImage             = editorElementHierarchy.getMainImage();
    Matrix        mainImageLocalBackup  = new Matrix(mainImage.getLocalMatrix());
    Matrix        mainImageEditorBackup = new Matrix(mainImage.getEditorMatrix());

    mainImage.commitEditorMatrix();
    Matrix combinedLocal = new Matrix(mainImage.getLocalMatrix());
    Matrix newLocal = Bisect.bisectToTest(mainImage,
                                          minScaleDown,
                                          maxScaleUp,
                                          this::cropIsWithinMainImageBounds,
                                          (matrix, scale) -> matrix.preScale(scale, scale));

    Matrix invertLocal = new Matrix();
    if (newLocal != null && combinedLocal.invert(invertLocal)) {
      invertLocal.preConcat(newLocal); // L^-1 (L * Scale) -> Scale
      mainImageEditorBackup.preConcat(invertLocal); // add the scale to editor matrix to keep this image within crop
    }
    mainImage.getLocalMatrix().set(mainImageLocalBackup);
    mainImage.getEditorMatrix().set(mainImageEditorBackup);
  }

  /**
   * Sets the editor matrix for the element to a rotation of the degrees but does so that we are rotating around the
   * parents elements origin.
   */
  private void setEditorMatrixToRotationMatrixAboutParentsOrigin(@NonNull EditorElement element, float degrees) {
    Matrix localMatrix  = element.getLocalMatrix();
    Matrix editorMatrix = element.getEditorMatrix();
    localMatrix.invert(editorMatrix);
    editorMatrix.preRotate(degrees);
    editorMatrix.preConcat(localMatrix);
    // Editor Matrix is then: Local^-1 * Rotate(degrees) * Local
    // So you end up with this overall for the element: Local * Local^-1 * Rotate(degrees) * Local
    // Meaning the rotate applies after existing effects of the local matrix
    // Where as simply setting the editor matrix rotate gives this: Local * Rotate(degrees)
    // which rotates around local origin first
  }

  /**
   * Renders tree with the following matrix:
   * <p>
   * viewModelMatrix * matrix * editorMatrix
   * <p>
   * Child nodes are supplied with a viewModelMatrix' = viewModelMatrix * matrix * editorMatrix
   *
   * @param rendererContext Canvas to draw on to.
   * @param renderOnTop     This element will appear on top of the overlay.
   */
  public void draw(@NonNull RendererContext rendererContext, @Nullable EditorElement renderOnTop) {
    updateVignetteFrame(false);
    EditorElement root = editorElementHierarchy.getRoot();
    if (renderOnTop != null) {
      root.forAllInTree(element -> element.getFlags().mark());

      renderOnTop.getFlags().setVisible(false);
    }

    // pass 1
    root.draw(rendererContext);

    if (renderOnTop != null) {
      // hide all
      try {
        root.forAllInTree(element -> element.getFlags().setVisible(renderOnTop == element));

        // pass 2
        root.draw(rendererContext);
      } finally {
        root.forAllInTree(element -> element.getFlags().restore());
      }
    }
  }

  public @Nullable Matrix findElementInverseMatrix(@NonNull EditorElement element, @NonNull Matrix viewMatrix) {
    Matrix inverse = new Matrix();
    if (findElement(element, viewMatrix, inverse)) {
      return inverse;
    }
    return null;
  }

  private @Nullable Matrix findElementMatrix(@NonNull EditorElement element, @NonNull Matrix viewMatrix) {
    Matrix inverse = findElementInverseMatrix(element, viewMatrix);
    if (inverse != null) {
      Matrix regular = new Matrix();
      inverse.invert(regular);
      return regular;
    }
    return null;
  }

  public EditorElement findElementAtPoint(@NonNull PointF point, @NonNull Matrix viewMatrix, @NonNull Matrix outInverseModelMatrix) {
    return editorElementHierarchy.getRoot().findElementAt(point.x, point.y, viewMatrix, outInverseModelMatrix);
  }

  public boolean checkTrashIntersectsPoint(@NonNull PointF point) {
    EditorElement trash = editorElementHierarchy.getTrash();
    if (trash.getFlags().isVisible()) {
      trash.getFlags()
           .setSelectable(true)
           .persist();

      boolean isIntersecting = trash.findElementAt(point.x, point.y, new Matrix(), new Matrix()) != null;

      trash.getFlags()
           .setSelectable(false)
           .persist();

      return isIntersecting;
    } else {
      return false;
    }
  }

  private boolean findElement(@NonNull EditorElement element, @NonNull Matrix viewMatrix, @NonNull Matrix outInverseModelMatrix) {
    return editorElementHierarchy.getRoot().findElement(element, viewMatrix, outInverseModelMatrix) == element;
  }

  /** Serializes the current element tree for later restoration via {@link #restoreFromSnapshot}. */
  public byte[] createSnapshot() {
    return ElementStack.getBytes(editorElementHierarchy.getRoot());
  }

  /** Restores the element tree from a snapshot created by {@link #createSnapshot}. */
  public void restoreFromSnapshot(@NonNull byte[] snapshot) {
    final EditorElement oldRootElement = editorElementHierarchy.getRoot();

    Parcel parcel = Parcel.obtain();
    try {
      parcel.unmarshall(snapshot, 0, snapshot.length);
      parcel.setDataPosition(0);
      EditorElement newRoot = parcel.readParcelable(EditorElement.class.getClassLoader());
      if (newRoot != null) {
        setEditorElementHierarchy(EditorElementHierarchy.create(newRoot));
        restoreStateWithAnimations(oldRootElement, editorElementHierarchy.getRoot(), invalidate, false);
        invalidate.run();
        editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);
        inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
      }
    } finally {
      parcel.recycle();
    }
  }

  public void pushUndoPoint() {
    boolean cropping = isCropping();
    if (cropping && !currentCropIsAcceptable()) {
      return;
    }

    getActiveUndoRedoStacks(cropping).pushState(editorElementHierarchy.getRoot());
  }

  public void updateUndoRedoAvailabilityState() {
    updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()));
  }

  public void clearUndoStack() {
    EditorElement  root     = editorElementHierarchy.getRoot();
    EditorElement  original = root;
    boolean        cropping = isCropping();
    UndoRedoStacks stacks   = getActiveUndoRedoStacks(cropping);
    boolean        didPop   = false;

    while (stacks.canUndo(root)) {
      final EditorElement oldRootElement = root;
      final EditorElement popped         = stacks.getUndoStack().pop(oldRootElement);

      if (popped != null) {
        didPop = true;
        editorElementHierarchy = EditorElementHierarchy.create(popped);
        stacks.getRedoStack().tryPush(oldRootElement);
      } else {
        break;
      }

      root = editorElementHierarchy.getRoot();
    }

    if (didPop) {
      restoreStateWithAnimations(original, editorElementHierarchy.getRoot(), invalidate, cropping);
      invalidate.run();
      editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);
      inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
    }

    updateUndoRedoAvailableState(stacks);
  }

  public void undo() {
    boolean        cropping = isCropping();
    UndoRedoStacks stacks   = getActiveUndoRedoStacks(cropping);

    undoRedo(stacks.getUndoStack(), stacks.getRedoStack(), cropping);

    updateUndoRedoAvailableState(stacks);
  }

  public void redo() {
    boolean        cropping = isCropping();
    UndoRedoStacks stacks   = getActiveUndoRedoStacks(cropping);

    undoRedo(stacks.getRedoStack(), stacks.getUndoStack(), cropping);

    updateUndoRedoAvailableState(stacks);
  }

  private void undoRedo(@NonNull ElementStack fromStack, @NonNull ElementStack toStack, boolean keepEditorState) {
    final EditorElement oldRootElement = editorElementHierarchy.getRoot();
    final EditorElement popped         = fromStack.pop(oldRootElement);

    if (popped != null) {
      setEditorElementHierarchy(EditorElementHierarchy.create(popped));

      toStack.tryPush(oldRootElement);

      restoreStateWithAnimations(oldRootElement, editorElementHierarchy.getRoot(), invalidate, keepEditorState);
      invalidate.run();

      // re-zoom image root as the view port might be different now
      editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);

      inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
    }
  }

  /** Replaces the hierarchy, maintaining any selection if possible */
  private void setEditorElementHierarchy(@NonNull EditorElementHierarchy hierarchy) {
    EditorElement selectedElement = editorElementHierarchy.getSelectedElement();
    editorElementHierarchy = hierarchy;
    setSelected(selectedElement != null ? findById(selectedElement.getId()) : null);
  }

  private static void restoreStateWithAnimations(@NonNull EditorElement fromRootElement, @NonNull EditorElement toRootElement, @NonNull Runnable onInvalidate, boolean keepEditorState) {
    Map<UUID, EditorElement> fromMap = getElementMap(fromRootElement);
    Map<UUID, EditorElement> toMap   = getElementMap(toRootElement);

    for (EditorElement fromElement : fromMap.values()) {
      fromElement.stopAnimation();
      EditorElement toElement = toMap.get(fromElement.getId());
      if (toElement != null) {
        toElement.animateFrom(fromElement.getLocalMatrixAnimating(), onInvalidate);

        if (keepEditorState) {
          toElement.getEditorMatrix().set(fromElement.getEditorMatrix());
          toElement.getFlags().set(fromElement.getFlags());
        }
      } else {
        // element is removed
        EditorElement parentFrom = fromRootElement.parentOf(fromElement);
        if (parentFrom != null) {
          EditorElement toParent = toMap.get(parentFrom.getId());
          if (toParent != null) {
            toParent.addDeletedChildFadingOut(fromElement, onInvalidate);
          }
        }
      }
    }

    for (EditorElement toElement : toMap.values()) {
      if (!fromMap.containsKey(toElement.getId())) {
        // new item
        toElement.animateFadeIn(onInvalidate);
      }
    }
  }

  private void updateUndoRedoAvailableState(UndoRedoStacks currentStack) {
    if (undoRedoStackListener == null) return;

    EditorElement root = editorElementHierarchy.getRoot();

    undoRedoStackListener.onAvailabilityChanged(currentStack.canUndo(root), currentStack.canRedo(root));
  }

  private static Map<UUID, EditorElement> getElementMap(@NonNull EditorElement element) {
    final Map<UUID, EditorElement> result = new HashMap<>();
    element.buildMap(result);
    return result;
  }

  public void addFade() {
    editorElementHierarchy.addFade(invalidate);
  }

  public void removeFade() {
    editorElementHierarchy.removeFade(invalidate);
  }

  public void startCrop() {
    float scaleIn = editingPurpose == EditingPurpose.WALLPAPER ? 1 : 0.8f;

    pushUndoPoint();
    cropUndoRedoStacks.clear(editorElementHierarchy.getRoot());
    editorElementHierarchy.startCrop(invalidate, scaleIn);
    inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
    updateUndoRedoAvailableState(cropUndoRedoStacks);
  }

  public void doneCrop() {
    editorElementHierarchy.doneCrop(visibleViewPort, invalidate);
    updateUndoRedoAvailableState(undoRedoStacks);
  }

  public void setCropAspectLock(boolean locked) {
    EditorFlags flags = editorElementHierarchy.getCropEditorElement().getFlags();
    int currentState  = flags.setAspectLocked(locked).getCurrentState();

    flags.reset();
    flags.setAspectLocked(locked)
         .persist();
    flags.restoreState(currentState);
  }

  public boolean isCropAspectLocked() {
    return editorElementHierarchy.getCropEditorElement().getFlags().isAspectLocked();
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-1): width / height of the crop as it stands on screen.
   */
  public float getCropAspectRatioOnScreen() {
    RectF crop = findCropRelativeToRoot();
    return crop.height() > 0 ? crop.width() / crop.height() : 1f;
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-1): width / height of the whole image the way it is turned now. An odd number of
   * quarter turns swaps it; the fine rotation dial is ignored.
   */
  public float getOrientedImageAspectRatio() {
    if (size.x <= 0 || size.y <= 0) {
      return 1f;
    }
    float ratio = size.x / (float) size.y;
    return isCropQuarterTurned() ? 1f / ratio : ratio;
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-1, as Telegram's crop aspect ratios, {@code CropView.setLockedAspectRatio}): shrinks the
   * crop around its centre to the largest rectangle of {@code widthToHeight} (as seen on screen) that fits in the current
   * crop, and locks the crop to that aspect.
   */
  public void setCropAspectRatio(float widthToHeight) {
    if (!isCropping() || widthToHeight <= 0) {
      return;
    }

    float current = getCropAspectRatioOnScreen();
    float across  = widthToHeight < current ? widthToHeight / current : 1f;
    float down    = widthToHeight > current ? current / widthToHeight : 1f;
    boolean turned = isCropQuarterTurned();

    pushUndoPoint();

    EditorElement crop     = editorElementHierarchy.getCropEditorElement();
    Matrix        newLocal = new Matrix(crop.getLocalMatrix());
    newLocal.preScale(turned ? down : across, turned ? across : down);
    crop.animateLocalTo(newLocal, invalidate);

    setCropAspectLock(true);
    updateUndoRedoAvailableState(cropUndoRedoStacks);
  }

  /** Whether the crop's own x axis runs up or down the screen, i.e. the image has been turned by an odd number of quarter turns. */
  private boolean isCropQuarterTurned() {
    Matrix toRoot = findRelativeMatrix(editorElementHierarchy.getCropEditorElement(), editorElementHierarchy.getRoot());
    if (toRoot == null) {
      return false;
    }
    float[] axis = { 1f, 0f };
    toRoot.mapVectors(axis);
    return Math.abs(axis[1]) > Math.abs(axis[0]);
  }

  public void postEdit(boolean allowScaleToRepairCrop) {
    boolean cropping = isCropping();
    if (cropping) {
      ensureFitsBounds(allowScaleToRepairCrop);
    }

    updateUndoRedoAvailableState(getActiveUndoRedoStacks(cropping));
    invalidate.run();
  }

  /**
   * @param cropping Set to true if cropping is underway.
   * @return The correct stack for the mode of operation.
   */
  private UndoRedoStacks getActiveUndoRedoStacks(boolean cropping) {
    return cropping ? cropUndoRedoStacks : undoRedoStacks;
  }

  private void ensureFitsBounds(boolean allowScaleToRepairCrop) {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    if (mainImage == null) return;

    EditorElement cropEditorElement = editorElementHierarchy.getCropEditorElement();

    if (!currentCropIsAcceptable()) {
      if (allowScaleToRepairCrop) {
        if (!tryToScaleToFit(cropEditorElement, 0.9f)) {
          tryToScaleToFit(mainImage, 2f);
        }
      } else {
        tryToFixTranslationOutOfBounds(mainImage, inBoundsMemory.getLastKnownGoodMainImageMatrix());
      }

      if (!currentCropIsAcceptable()) {
        inBoundsMemory.restore(mainImage, cropEditorElement, invalidate);
      } else {
        inBoundsMemory.push(mainImage, cropEditorElement);
      }
    }

    editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate);
  }

  /**
   * Attempts to scale the supplied element such that {@link #cropIsWithinMainImageBounds} is true.
   * <p>
   * Does not respect minimum scale, so does need a further check to {@link #currentCropIsAcceptable} afterwards.
   *
   * @param element     The element to be scaled. If successful, it will be animated to the correct position.
   * @param scaleAtMost The amount of scale to apply at most. Use < 1 for the crop, and > 1 for the image.
   * @return true if successfully scaled the element. false if the element was left unchanged.
   */
  private boolean tryToScaleToFit(@NonNull EditorElement element, float scaleAtMost) {
    return Bisect.bisectToTest(element,
                        1,
                        scaleAtMost,
                        this::cropIsWithinMainImageBounds,
                        (matrix, scale) -> matrix.preScale(scale, scale),
                        invalidate);
  }

  /**
   * Attempts to translate the supplied element such that {@link #cropIsWithinMainImageBounds} is true.
   * If you supply both x and y, it will attempt to find a fit on the diagonal with vector x, y.
   *
   * @param element          The element to be translated. If successful, it will be animated to the correct position.
   * @param translateXAtMost The maximum translation to apply in the x axis.
   * @param translateYAtMost The maximum translation to apply in the y axis.
   * @return a matrix if successfully translated the element. null if the element unable to be translated to fit.
   */
  private Matrix tryToTranslateToFit(@NonNull EditorElement element, float translateXAtMost, float translateYAtMost) {
    return Bisect.bisectToTest(element,
                               0,
                               1,
                               this::cropIsWithinMainImageBounds,
                               (matrix, factor) -> matrix.postTranslate(factor * translateXAtMost, factor * translateYAtMost));
  }

  /**
   * Tries to fix an element that is out of bounds by adjusting it's translation.
   *
   * @param element               Element to move.
   * @param lastKnownGoodPosition Last known good position of element.
   * @return true iff fixed the element.
   */
  private boolean tryToFixTranslationOutOfBounds(@NonNull EditorElement element, @NonNull Matrix lastKnownGoodPosition) {
    final Matrix  elementMatrix = element.getLocalMatrix();
    final Matrix  original      = new Matrix(elementMatrix);
    final float[] current       = new float[9];
    final float[] lastGood      = new float[9];
    Matrix matrix;

    elementMatrix.getValues(current);
    lastKnownGoodPosition.getValues(lastGood);

    final float xTranslate = current[2] - lastGood[2];
    final float yTranslate = current[5] - lastGood[5];

    if (Math.abs(xTranslate) < Bisect.ACCURACY && Math.abs(yTranslate) < Bisect.ACCURACY) {
      return false;
    }

    float pass1X;
    float pass1Y;

    float pass2X;
    float pass2Y;

    // try the fix by the smallest user translation first
    if (Math.abs(xTranslate) < Math.abs(yTranslate)) {
      // try to bisect along x
      pass1X = -xTranslate;
      pass1Y = 0;

      // then y
      pass2X = 0;
      pass2Y = -yTranslate;
    } else {
      // try to bisect along y
      pass1X = 0;
      pass1Y = -yTranslate;

      // then x
      pass2X = -xTranslate;
      pass2Y = 0;
    }

    matrix = tryToTranslateToFit(element, pass1X, pass1Y);
    if (matrix != null) {
      element.animateLocalTo(matrix, invalidate);
      return true;
    }

    matrix = tryToTranslateToFit(element, pass2X, pass2Y);
    if (matrix != null) {
      element.animateLocalTo(matrix, invalidate);
      return true;
    }

    // apply pass 1 fully
    elementMatrix.postTranslate(pass1X, pass1Y);

    matrix = tryToTranslateToFit(element, pass2X, pass2Y);
    elementMatrix.set(original);

    if (matrix != null) {
      element.animateLocalTo(matrix, invalidate);
      return true;
    }

    return false;
  }

  public void dragDropRelease() {
    editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate);
  }

  /**
   * Pixel count must be no smaller than {@link #MINIMUM_CROP_PIXEL_COUNT} (unless its original size was less than that)
   * and all points must be within the bounds.
   */
  private boolean currentCropIsAcceptable() {
    Point outputSize        = getOutputSize();
    int   outputPixelCount  = outputSize.x * outputSize.y;
    int   minimumPixelCount = Math.min(size.x * size.y, MINIMUM_CROP_PIXEL_COUNT);

    Point thinnestRatio = MINIMUM_RATIO;

    if (compareRatios(size, thinnestRatio) < 0) {
      // original is narrower than the thinnestRatio
      thinnestRatio = size;
    }

    return compareRatios(outputSize, thinnestRatio) >= 0 &&
           outputPixelCount >= minimumPixelCount &&
           cropIsWithinMainImageBounds();
  }

  /**
   * -1 iff a is a narrower ratio than b.
   * +1 iff a is a squarer ratio than b.
   * 0 if the ratios are the same.
   */
  private static int compareRatios(@NonNull Point a, @NonNull Point b) {
    int smallA = Math.min(a.x, a.y);
    int largeA = Math.max(a.x, a.y);

    int smallB = Math.min(b.x, b.y);
    int largeB = Math.max(b.x, b.y);

    return Integer.compare(smallA * largeB, smallB * largeA);
  }

  /**
   * @return true if and only if the current crop rect is fully in the bounds.
   */
  private boolean cropIsWithinMainImageBounds() {
    return Bounds.boundsRemainInBounds(editorElementHierarchy.imageMatrixRelativeToCrop());
  }

  /**
   * Called as edits are underway.
   */
  public void moving(@NonNull EditorElement editorElement) {
    if (!isCropping()) {
      updateSelectionThumbsIfSelected(editorElement);
      return;
    }

    EditorElement mainImage = editorElementHierarchy.getMainImage();
    EditorElement cropEditorElement = editorElementHierarchy.getCropEditorElement();

    if (editorElement == mainImage || editorElement == cropEditorElement) {
      if (currentCropIsAcceptable()) {
        inBoundsMemory.push(mainImage, cropEditorElement);
      }
    }
  }

  /**
   * Fits the model to the area it is displayed in.
   * <p>
   * This is presentation, not an edit, so a model that was unchanged stays unchanged: the view matrix this writes is
   * part of the tree {@link #isChanged()} compares, so without re-baselining, merely laying a model out would report as
   * a change and get the image needlessly re-rendered on send.
   */
  public void setVisibleViewPort(@NonNull RectF visibleViewPort) {
    boolean changedBefore = isChanged();

    this.visibleViewPort.set(visibleViewPort);
    this.editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);

    if (!changedBefore) {
      undoRedoStacks.updateUnchangedState(editorElementHierarchy.getRoot());
    }
  }

  public Set<Integer> getUniqueColorsIgnoringAlpha() {
    final Set<Integer> colors = new LinkedHashSet<>();

    editorElementHierarchy.getRoot().forAllInTree(element -> {
      Renderer renderer = element.getRenderer();
      if (renderer instanceof ColorableRenderer) {
        colors.add(((ColorableRenderer) renderer).getColor() | 0xff000000);
      }
    });

    return colors;
  }

  public static final Creator<EditorModel> CREATOR = new Creator<EditorModel>() {
    @Override
    public EditorModel createFromParcel(Parcel in) {
      return new EditorModel(in);
    }

    @Override
    public EditorModel[] newArray(int size) {
      return new EditorModel[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(editingPurpose.ordinal());
    dest.writeFloat(fixedRatio);
    dest.writeInt(size.x);
    dest.writeInt(size.y);
    dest.writeParcelable(editorElementHierarchy.getRoot(), flags);
    dest.writeParcelable(undoRedoStacks, flags);
    dest.writeParcelable(cropUndoRedoStacks, flags);
  }

  /**
   * Blocking render of the model.
   */
  @WorkerThread
  public @NonNull Bitmap render(@NonNull Context context, @NonNull RendererContext.TypefaceProvider typefaceProvider) {
    return render(context, null, typefaceProvider);
  }

  /**
   * Blocking render of the model.
   */
  @WorkerThread
  public @NonNull Bitmap render(@NonNull Context context, @Nullable Point size, @NonNull RendererContext.TypefaceProvider typefaceProvider) {
    updateVignetteFrame(true);
    EditorElement image      = editorElementHierarchy.getFlipRotate();
    RectF         cropRect   = editorElementHierarchy.getCropRect();
    Point         outputSize = size != null ? size : getOutputSize();

    Bitmap bitmap = Bitmap.createBitmap(outputSize.x, outputSize.y, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(bitmap);
      RendererContext rendererContext = new RendererContext(context, canvas, RendererContext.Ready.NULL, RendererContext.Invalidate.NULL, typefaceProvider);

      RectF bitmapArea = new RectF();
      bitmapArea.right = bitmap.getWidth();
      bitmapArea.bottom = bitmap.getHeight();

      Matrix viewMatrix = new Matrix();
      viewMatrix.setRectToRect(cropRect, bitmapArea, Matrix.ScaleToFit.FILL);

      rendererContext.setIsEditing(false);
      rendererContext.setBlockingLoad(true);

      EditorElement overlay = editorElementHierarchy.getOverlay();
      overlay.getFlags().setVisible(false).setChildrenVisible(false);

      try {
        rendererContext.canvasMatrix.initial(viewMatrix);
        image.draw(rendererContext);
      } finally {
        overlay.getFlags().reset();
      }
    } catch (Exception e) {
      bitmap.recycle();
      throw e;
    }
    return bitmap;
  }

  @NonNull
  private Point getOutputSize() {
    PointF outputSize = editorElementHierarchy.getOutputSize(size);

    int width  = (int) Math.max(MINIMUM_OUTPUT_WIDTH, outputSize.x);
    int height = (int) (width * outputSize.y / outputSize.x);

    return new Point(width, height);
  }

  @NonNull
  public Point getOutputSizeMaxWidth(int maxDimension) {
    PointF outputSize = editorElementHierarchy.getOutputSize(size);

    int width  = Math.min(maxDimension, (int) Math.max(MINIMUM_OUTPUT_WIDTH, outputSize.x));
    int height = (int) (width * outputSize.y / outputSize.x);

    if (height > maxDimension) {
      height = maxDimension;
      width  = (int) (height * outputSize.x / outputSize.y);
    }

    return new Point(width, height);
  }

  @Override
  public void onReady(@NonNull Renderer renderer, @Nullable Matrix cropMatrix, @Nullable Point size) {
    if (cropMatrix != null && size != null && isRendererOfMainImage(renderer)) {
      boolean changedBefore   = isChanged();
      boolean croppingBefore  = isCropping();
      Matrix  imageCropMatrix = editorElementHierarchy.getImageCrop().getLocalMatrix();
      this.size.set(size.x, size.y);
      if (imageCropMatrix.isIdentity()) {
        imageCropMatrix.set(cropMatrix);

        if (editingPurpose == EditingPurpose.AVATAR_CAPTURE || editingPurpose == EditingPurpose.WALLPAPER || editingPurpose == EditingPurpose.AVATAR_EDIT) {
          Matrix userCropMatrix = editorElementHierarchy.getCropEditorElement().getLocalMatrix();
          if (size.x > size.y) {
            userCropMatrix.setScale(fixedRatio * size.y / (float) size.x, 1f);
          } else {
            userCropMatrix.setScale(1f, size.x / (float) size.y);
          }
        }

        editorElementHierarchy.doneCrop(visibleViewPort, null);

        if (!changedBefore) {
          undoRedoStacks.clear(editorElementHierarchy.getRoot());
        }

        switch (editingPurpose) {
          case AVATAR_CAPTURE: {
            startCrop();
            break;
          }
          case WALLPAPER: {
            setFixedRatio(fixedRatio);
            startCrop();
            break;
          }
          default:
            // Tellomi (tellomi/tellomi#1522 E-1): the crop was opened before the image had loaded (the album viewer's crop
            // shortcut goes straight to it), and laying out the loaded image just closed it. Open it again.
            if (croppingBefore && !isCropping()) {
              startCrop();
            }
            break;
        }
      }
    }
  }

  public void setFixedRatio(float r) {
    fixedRatio = r;
    Matrix userCropMatrix = editorElementHierarchy.getCropEditorElement().getLocalMatrix();
    float  w              = size.x;
    float  h              = size.y;
    float imageRatio = w / h;
    if (imageRatio > r) {
      userCropMatrix.setScale(r / imageRatio, 1f);
    } else {
      userCropMatrix.setScale(1f, imageRatio / r);
    }

    editorElementHierarchy.doneCrop(visibleViewPort, null);
    startCrop();
  }

  private boolean isRendererOfMainImage(@NonNull Renderer renderer) {
    EditorElement mainImage         = editorElementHierarchy.getMainImage();
    Renderer      mainImageRenderer = mainImage != null ? mainImage.getRenderer() : null;
    return mainImageRenderer == renderer;
  }

  /**
   * Add a new {@link EditorElement} centered in the current visible crop area.
   *
   * @param element New element to add.
   * @param scale   Initial scale for new element.
   */
  public void addElementCentered(@NonNull EditorElement element, float scale) {
    Matrix localMatrix = element.getLocalMatrix();

    editorElementHierarchy.getMainImageFullMatrix().invert(localMatrix);

    localMatrix.preScale(scale, scale);
    addElement(element);
  }

  /**
   * Add an element to the main image, or if there is no main image, make the new element the main image.
   *
   * @param element New element to add.
   */
  public void addElement(@NonNull EditorElement element) {
    pushUndoPoint();
    addElementWithoutPushUndo(element);
  }

  public void addElementWithoutPushUndo(@NonNull EditorElement element) {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    EditorElement parent    = mainImage != null ? mainImage : editorElementHierarchy.getImageRoot();

    parent.addElement(element);

    if (parent != mainImage) {
      undoRedoStacks.clear(editorElementHierarchy.getRoot());
    }

    updateUndoRedoAvailableState(undoRedoStacks);
  }

  /**
   * Masks each of {@code faces} with a blur, as a single undoable edit.
   *
   * @param faces      Bounds of each face, in the coordinate space of a render of this model at {@code renderSize}.
   * @param renderSize Size of the render the faces were detected in.
   * @param position   The inverse crop position at the time of that render, which is what keeps the masks on the faces
   *                   when the image is cropped or rotated.
   */
  public void addFaceBlurs(@NonNull List<RectF> faces, @NonNull Point renderSize, @NonNull Matrix position) {
    if (faces.isEmpty()) {
      return;
    }

    pushUndoPoint();

    Matrix renderProjection = new Matrix();
    renderProjection.setRectToRect(new RectF(0, 0, renderSize.x, renderSize.y), Bounds.FULL_BOUNDS, Matrix.ScaleToFit.FILL);

    Matrix facePosition = new Matrix(position);
    facePosition.preConcat(renderProjection);

    Matrix faceMatrix = new Matrix();

    for (RectF face : faces) {
      EditorElement element     = new EditorElement(new FaceBlurRenderer(), Z_MASK);
      Matrix        localMatrix = element.getLocalMatrix();

      faceMatrix.setRectToRect(Bounds.FULL_BOUNDS, face, Matrix.ScaleToFit.FILL);

      localMatrix.set(facePosition);
      localMatrix.preConcat(faceMatrix);

      element.getFlags().setEditable(false)
             .setSelectable(false)
             .persist();

      addElementWithoutPushUndo(element);
    }

    invalidate.run();
  }

  public void clearFaceRenderers() {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    if (mainImage != null) {
      boolean hasPushedUndo = false;
      for (int i = mainImage.getChildCount() - 1; i >= 0; i--) {
        if (mainImage.getChild(i).getRenderer() instanceof FaceBlurRenderer) {
          if (!hasPushedUndo) {
            pushUndoPoint();
            hasPushedUndo = true;
          }

          mainImage.deleteChild(mainImage.getChild(i), invalidate);
        }
      }
    }
  }

  public boolean hasFaceRenderer() {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    if (mainImage != null) {
      for (int i = mainImage.getChildCount() - 1; i >= 0; i--) {
        if (mainImage.getChild(i).getRenderer() instanceof FaceBlurRenderer) {
          return true;
        }
      }
    }

    return false;
  }

  public boolean isChanged() {
    return undoRedoStacks.isChanged(editorElementHierarchy.getRoot());
  }

  /**
   * Tellomi（tellomi/tellomi#1591 E5-1）：主图的调色；没有主图、或主图不是一张照片时是默认值。
   */
  public @NonNull ImageAdjustments getAdjustments() {
    UriGlideRenderer renderer = getMainImageRenderer();
    return renderer != null ? renderer.getAdjustments() : ImageAdjustments.DEFAULT;
  }

  /**
   * Tellomi（tellomi/tellomi#1591 E5-1）：换主图的调色；和现在一样就什么都不做。
   * <p>
   * 拖滑杆时第一下传 {@code pushUndoPoint = true}（撤销栈记下拖之前的样子），之后拖动中传 false，一次拖动只占一步撤销。
   * 调色存在主图的 renderer 里，是元素树的一部分，所以撤销 / 重做、{@link #isChanged()}（发出时重新渲染）都自动带上。
   */
  public void setAdjustments(@NonNull ImageAdjustments adjustments, boolean pushUndoPoint) {
    UriGlideRenderer renderer = getMainImageRenderer();
    if (renderer == null || renderer.getAdjustments().equals(adjustments)) {
      return;
    }

    if (pushUndoPoint) {
      pushUndoPoint();
    }
    renderer.setAdjustments(adjustments);
    updateUndoRedoAvailabilityState();
    invalidate.run();
  }

  /**
   * Tellomi（tellomi/tellomi#1593 E5-3）：调色时按住画面看原图（照 Telegram 两端调整页）。只换画布上画的底图：
   * 不进撤销、不算改动，发出去的图不受影响。
   */
  public void setShowsOriginal(boolean showsOriginal) {
    UriGlideRenderer renderer = getMainImageRenderer();
    if (renderer == null || renderer.isShowingOriginal() == showsOriginal) {
      return;
    }

    renderer.setShowingOriginal(showsOriginal);
    invalidate.run();
  }

  /**
   * Tellomi（tellomi/tellomi#1595 E5-5）：暗角跟着画框（裁剪框）走：每次画之前把「主图 → 裁剪框」的矩阵交给主图的 renderer。
   * 画布上裁剪中不更新（拖裁剪框时调色图不跟着一遍遍重画，裁完画一次）；发出时（{@link #render}）总按最后的裁剪。暗角是 0 时不用算。
   */
  private void updateVignetteFrame(boolean evenWhileCropping) {
    UriGlideRenderer renderer = getMainImageRenderer();
    if (renderer == null || renderer.getAdjustments().getVignette() <= 0f || (!evenWhileCropping && isCropping())) {
      return;
    }
    Matrix imageToCrop = findRelativeMatrix(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
    if (imageToCrop != null) {
      renderer.setImageToCrop(imageToCrop);
    }
  }

  private @Nullable UriGlideRenderer getMainImageRenderer() {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    if (mainImage == null) {
      return null;
    }

    Renderer renderer = mainImage.getRenderer();
    return renderer instanceof UriGlideRenderer ? (UriGlideRenderer) renderer : null;
  }

  /**
   * Tellomi（tellomi/tellomi#1597 E5-7）：裁剪框（照片看得见的那块）在画布上的位置，view 坐标；调色的曲线层盖在这上面。
   */
  public @NonNull RectF findCropRectInView(@NonNull Matrix viewMatrix) {
    RectF  dst        = new RectF(Bounds.FULL_BOUNDS);
    Matrix cropToView = findElementMatrix(editorElementHierarchy.getCropEditorElement(), viewMatrix);
    if (cropToView != null) {
      cropToView.mapRect(dst, Bounds.FULL_BOUNDS);
    }
    return dst;
  }

  public RectF findCropRelativeToRoot() {
    return findCropRelativeTo(editorElementHierarchy.getRoot());
  }

  RectF findCropRelativeTo(EditorElement element) {
    return findRelativeBounds(editorElementHierarchy.getCropEditorElement(), element);
  }

  RectF findRelativeBounds(EditorElement from, EditorElement to) {
    Matrix relative = findRelativeMatrix(from, to);

    RectF dst = new RectF(Bounds.FULL_BOUNDS);
    if (relative != null) {
      relative.mapRect(dst, Bounds.FULL_BOUNDS);
    }
    return dst;
  }

  /**
   * Returns a matrix that maps points in the {@param from} element in to points in the {@param to} element.
   *
   * @param from
   * @param to
   * @return
   */
  public @Nullable Matrix findRelativeMatrix(@NonNull EditorElement from, @NonNull EditorElement to) {
    Matrix matrix = findElementInverseMatrix(to, new Matrix());
    Matrix outOf  = findElementMatrix(from, new Matrix());

    if (outOf != null && matrix != null) {
      matrix.preConcat(outOf);
      return matrix;
    }
    return null;
  }

  public void rotate90anticlockwise() {
    flipRotate(-90, 1, 1);
  }

  public void flipHorizontal() {
    flipRotate(0, -1, 1);
  }

  private void flipRotate(float degrees, int scaleX, int scaleY) {
    pushUndoPoint();
    editorElementHierarchy.flipRotate(degrees, scaleX, scaleY, visibleViewPort, invalidate);
    updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()));
  }

  public EditorElement getRoot() {
    return editorElementHierarchy.getRoot();
  }

  public EditorElement getTrash() {
    return editorElementHierarchy.getTrash();
  }

  public @Nullable EditorElement getMainImage() {
    return editorElementHierarchy.getMainImage();
  }

  public void delete(@NonNull EditorElement editorElement) {
    editorElementHierarchy.getImageRoot().forAllInTree(element -> element.deleteChild(editorElement, invalidate));
    setSelected(null);
  }

  public @Nullable EditorElement findById(@NonNull UUID uuid) {
    return getRoot().findElementWithId(uuid);
  }

  /**
   * Changes the temporary view so that the text element is centered in it.
   *
   * @param entity       Entity to center on.
   * @param textRenderer The text renderer, which can make additional adjustments to the zoom matrix
   *                     to leave space for the keyboard for example.
   */
  public void zoomToTextElement(@NonNull EditorElement entity, @NonNull MultiLineTextRenderer textRenderer) {
    Matrix elementInverseMatrix = findElementInverseMatrix(entity, new Matrix());
    if (elementInverseMatrix != null) {
      EditorElement root = editorElementHierarchy.getRoot();

      elementInverseMatrix.preConcat(root.getEditorMatrix());

      textRenderer.applyRecommendedEditorMatrix(elementInverseMatrix);

      root.animateEditorTo(elementInverseMatrix, invalidate);
    }
  }

  public void zoomOut() {
    editorElementHierarchy.getRoot().rollbackEditorMatrix(invalidate);
  }

  public void indicateSelected(@NonNull EditorElement selected) {
    selected.singleScalePulse(invalidate);
  }

  public boolean isCropping() {
    return editorElementHierarchy.getCropEditorElement().getFlags().isVisible();
  }

  /**
   * Returns a matrix that maps bounds to the crop area.
   */
  public Matrix getInverseCropPosition() {
    Matrix matrix = new Matrix();
    matrix.set(findRelativeMatrix(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement()));
    matrix.postConcat(editorElementHierarchy.getFlipRotate().getLocalMatrix());

    Matrix positionRelativeToCrop = new Matrix();
    matrix.invert(positionRelativeToCrop);
    return positionRelativeToCrop;
  }
}
