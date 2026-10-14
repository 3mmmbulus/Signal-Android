package org.thoughtcrime.securesms.keyvalue

class ImageEditorValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val KEY_IMAGE_EDITOR_MARKER_WIDTH = "image.editor.marker.width"
    private const val KEY_IMAGE_EDITOR_HIGHLIGHTER_WIDTH = "image.editor.highlighter.width"
    private const val KEY_IMAGE_EDITOR_BLUR_WIDTH = "image.editor.blur.width"

    // Tellomi (tellomi/tellomi#1522 E-2): the arrow, neon and eraser brushes. Not in the backup list, so backups stay
    // what Signal expects.
    private const val KEY_IMAGE_EDITOR_ARROW_WIDTH = "tellomi.image.editor.arrow.width"
    private const val KEY_IMAGE_EDITOR_NEON_WIDTH = "tellomi.image.editor.neon.width"
    private const val KEY_IMAGE_EDITOR_ERASER_WIDTH = "tellomi.image.editor.eraser.width"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> = listOf(
    KEY_IMAGE_EDITOR_MARKER_WIDTH,
    KEY_IMAGE_EDITOR_HIGHLIGHTER_WIDTH,
    KEY_IMAGE_EDITOR_BLUR_WIDTH
  )

  fun setMarkerPercentage(markerPercentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_MARKER_WIDTH, markerPercentage)
  }

  fun setHighlighterPercentage(highlighterPercentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_HIGHLIGHTER_WIDTH, highlighterPercentage)
  }

  fun setBlurPercentage(blurPercentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_BLUR_WIDTH, blurPercentage)
  }

  fun setArrowPercentage(percentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_ARROW_WIDTH, percentage)
  }

  fun setNeonPercentage(percentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_NEON_WIDTH, percentage)
  }

  fun setEraserPercentage(percentage: Int) {
    putInteger(KEY_IMAGE_EDITOR_ERASER_WIDTH, percentage)
  }

  fun getArrowPercentage(): Int = getInteger(KEY_IMAGE_EDITOR_ARROW_WIDTH, 0)

  fun getNeonPercentage(defaultPercentage: Int): Int = getInteger(KEY_IMAGE_EDITOR_NEON_WIDTH, defaultPercentage)

  fun getEraserPercentage(defaultPercentage: Int): Int = getInteger(KEY_IMAGE_EDITOR_ERASER_WIDTH, defaultPercentage)

  fun getMarkerPercentage(): Int = getInteger(KEY_IMAGE_EDITOR_MARKER_WIDTH, 0)

  fun getHighlighterPercentage(): Int = getInteger(KEY_IMAGE_EDITOR_HIGHLIGHTER_WIDTH, 0)

  fun getBlurPercentage(): Int = getInteger(KEY_IMAGE_EDITOR_BLUR_WIDTH, 0)

  fun getMarkerWidthRange(): Pair<Float, Float> = Pair(0.01f, 0.05f)

  fun getHighlighterWidthRange(): Pair<Float, Float> = Pair(0.03f, 0.08f)

  fun getBlurWidthRange(): Pair<Float, Float> = Pair(0.052f, 0.092f)
}
