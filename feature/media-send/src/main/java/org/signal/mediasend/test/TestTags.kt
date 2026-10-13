/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.test

/**
 * Test tags for Compose UI testing.
 */
object TestTags {

  // Add A Message Row
  const val ADD_A_MESSAGE_NEXT_BUTTON = "add_a_message_next_button"

  // Media Editor Toolbar
  const val MEDIA_EDITOR_TOOLBAR_QUALITY_BUTTON = "media_editor_toolbar_quality_button"
  const val MEDIA_EDITOR_TOOLBAR_SAVE_BUTTON = "media_editor_toolbar_save_button"
  const val MEDIA_EDITOR_TOOLBAR_ADD_MEDIA_BUTTON = "media_editor_toolbar_add_media_button"
  const val MEDIA_EDITOR_TOOLBAR_TAKE_MORE_BUTTON = "media_editor_toolbar_take_more_button"
  const val MEDIA_EDITOR_TOOLBAR_MUTE_BUTTON = "media_editor_toolbar_mute_button"

  // Media Edit Screen

  /** Tag for the delete affordance the thumbnail row puts on the media at [uri]. */
  fun thumbnailRowDeleteIcon(uri: String): String = "thumbnail_row_delete_icon_$uri"

  // Media Capture Screen
  const val MEDIA_CAPTURE_SCREEN = "media_capture_screen"
  const val MEDIA_CAPTURE_MODE_BAR = "media_capture_mode_bar"
  const val MEDIA_CAPTURE_PHOTO_TOGGLE = "media_capture_photo_toggle"
  const val MEDIA_CAPTURE_VIDEO_TOGGLE = "media_capture_video_toggle"
  const val MEDIA_CAPTURE_TEXT_STORY_TOGGLE = "media_capture_text_story_toggle"
  const val MEDIA_CAPTURE_FILMSTRIP = "media_capture_filmstrip"
  const val MEDIA_CAPTURE_FILMSTRIP_ITEM = "media_capture_filmstrip_item"

  // Shared. The next button is the same control on the capture and select screens, so it has one tag.
  const val MEDIA_SEND_MEDIA_COUNT = "media_send_media_count"
  const val MEDIA_SEND_NEXT_BUTTON = "media_send_next_button"

  // Media Select Screen
  const val MEDIA_SELECT_GRID = "media_select_grid"

  /** Tag for the selected media rail's thumbnail of the media at [uri]. */
  fun selectedMediaThumbnail(uri: String): String = "selected_media_thumbnail_$uri"

  // Tellomi（tellomi/tellomi#1261）选图网格
  const val MEDIA_PICKER_COUNT_PILL = "media_picker_count_pill"
  const val MEDIA_PICKER_FOLDER_TITLE = "media_picker_folder_title"
  const val MEDIA_PICKER_LIMITED_ACCESS = "media_picker_limited_access"
  const val MEDIA_PICKER_SEND_BAR = "media_picker_send_bar"

  /** Tag for the grid tile of the media at [uri]. */
  fun mediaPickerTile(uri: String): String = "media_picker_tile_$uri"

  /** Tag for the numbered check on the grid tile of the media at [uri]. */
  fun mediaPickerCheck(uri: String): String = "media_picker_check_$uri"

  // Tellomi（tellomi/tellomi#1261 P-3）只看已选
  const val MEDIA_PICKER_SELECTED_PREVIEW = "media_picker_selected_preview"
  const val MEDIA_PICKER_PREVIEW_ROW = "media_picker_preview_row"
  const val MEDIA_PICKER_PREVIEW_CAPTION = "media_picker_preview_caption"
  const val MEDIA_PICKER_PREVIEW_CHIP = "media_picker_preview_chip"
  const val MEDIA_PICKER_UNDO_BAR = "media_picker_undo_bar"
  const val MEDIA_PICKER_CAMERA = "media_picker_camera"
  const val MEDIA_PICKER_CAMERA_SPACER = "media_picker_camera_spacer"

  // Tellomi (tellomi/tellomi#1263 F-3): the files page's "choose from gallery" grid.
  const val FILES_GALLERY_FOLDER_TITLE = "files_gallery_folder_title"
  const val FILES_GALLERY_CANCEL = "files_gallery_cancel"
  const val FILES_GALLERY_SEND = "files_gallery_send"
  fun filesGalleryTile(uri: String): String = "files_gallery_tile_$uri"

  /** Tag for the card of the media at [uri] in the selected-only preview. */
  fun mediaPickerPreviewCard(uri: String): String = "media_picker_preview_card_$uri"

  /** Tag for the numbered check on the preview card of the media at [uri]. */
  fun mediaPickerPreviewCheck(uri: String): String = "media_picker_preview_check_$uri"

  // Tellomi（tellomi/tellomi#1115）附件 Sheet
  const val ATTACHMENT_SHEET = "attachment_sheet"
  const val ATTACHMENT_SHEET_SCRIM = "attachment_sheet_scrim"
  const val ATTACHMENT_DOCK = "attachment_dock"

  /** Tag for the attachment sheet dock entry with [id]. */
  fun attachmentDockEntry(id: String): String = "attachment_dock_entry_$id"

  // Tellomi（tellomi/tellomi#1121）附件 Sheet「文件」页
  const val ATTACHMENT_FILES = "attachment_files"
  const val ATTACHMENT_FILES_LIST = "attachment_files_list"
  const val ATTACHMENT_FILES_SEARCH_BUTTON = "attachment_files_search_button"
  const val ATTACHMENT_FILES_SEARCH_FIELD = "attachment_files_search_field"
  const val ATTACHMENT_FILES_SEARCH_CLOSE = "attachment_files_search_close"
  const val ATTACHMENT_FILES_SEND_BAR = "attachment_files_send_bar"
  const val ATTACHMENT_FILES_CAPTION = "attachment_files_caption"
  const val ATTACHMENT_FILES_SEND = "attachment_files_send"
  const val ATTACHMENT_FILES_SKELETON = "attachment_files_skeleton"
  const val ATTACHMENT_FILES_EMPTY = "attachment_files_empty"
  const val ATTACHMENT_FILES_EMPTY_ILLUSTRATION = "attachment_files_empty_illustration"
  const val ATTACHMENT_FILES_SHOW_MORE = "attachment_files_show_more"
  const val ATTACHMENT_FILES_NO_RESULTS = "attachment_files_no_results"

  /** Tag for the entry row [name] (gallery / files) at the top of the files page. */
  fun attachmentFilesEntry(name: String): String = "attachment_files_entry_$name"

  /** Tag for the row of the recently sent file with [attachmentId]. */
  fun attachmentFile(attachmentId: Long): String = "attachment_file_$attachmentId"

  /** Tag for the numbered check on the row of the recently sent file with [attachmentId]. */
  fun attachmentFileCheck(attachmentId: Long): String = "attachment_file_check_$attachmentId"

  // Schedule Send Menu
  const val SCHEDULE_SEND_PICK_TIME_OPTION = "schedule_send_pick_time_option"

  /**
   * Tag for the suggested time at [timeMs], since the suggestions themselves depend on when the menu was opened.
   */
  fun scheduleSendPresetOption(timeMs: Long): String = "schedule_send_preset_option_$timeMs"
}
