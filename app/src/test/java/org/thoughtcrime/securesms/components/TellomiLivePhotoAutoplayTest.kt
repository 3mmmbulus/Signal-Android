/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.TransformProperties
import org.signal.core.ui.CoreUiDependencies
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.keyvalue.InternalValues
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule

/**
 * Spec R-2 (tellomi/tellomi#1263), owner 2026-10-13 as Telegram: in the chat a live photo plays by itself, muted, every
 * time it comes on screen — not once per run, and not again when it is bound again while it is showing. Grids only
 * show the badge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLivePhotoAutoplayTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(InternalValues::class))

  private lateinit var parent: FrameLayout
  private lateinit var view: ThumbnailView
  private val requestManager = mockk<RequestManager>(relaxed = true)

  @Before
  fun setUp() {
    // The thumbnail's transfer controls are Compose
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    val activity = controller.setup().get()
    parent = FrameLayout(activity)
    activity.setContentView(parent)
    view = ThumbnailView(activity)
  }

  @Test
  fun `plays each time it comes on screen, not again when bound again while showing`() {
    parent.addView(view)
    bind(live(1))
    assertEquals(1, view.liveAutoplays)

    // The message updated: bound again, still on screen
    bind(live(1))
    assertEquals(1, view.liveAutoplays)

    // Scrolled away and back
    parent.removeView(view)
    parent.addView(view)
    assertEquals(2, view.liveAutoplays)
  }

  @Test
  fun `bound before it is on screen, it plays once it shows`() {
    bind(live(1))
    assertEquals(0, view.liveAutoplays)
    parent.addView(view)
    assertEquals(1, view.liveAutoplays)
  }

  @Test
  fun `another live photo bound into the same view plays`() {
    parent.addView(view)
    bind(live(1))
    bind(live(2))
    assertEquals(2, view.liveAutoplays)
  }

  @Test
  fun `waits for the still to arrive`() {
    parent.addView(view)
    bind(live(1, transferProgress = AttachmentTable.TRANSFER_PROGRESS_STARTED))
    assertEquals(0, view.liveAutoplays)
    bind(live(1))
    assertEquals(1, view.liveAutoplays)
  }

  @Test
  fun `a plain photo and the media grid don't play`() {
    parent.addView(view)
    bind(photo(1, transformProperties = null))
    assertEquals(0, view.liveAutoplays)

    view.setLivePhotoAutoplay(false)
    bind(live(2))
    parent.removeView(view)
    parent.addView(view)
    assertEquals(0, view.liveAutoplays)
  }

  private fun bind(attachment: DatabaseAttachment) {
    view.setImageResource(requestManager, ImageSlide(attachment), false, false)
  }

  /** The moving half can't be read, so nothing is downloaded or played here; only the decision to play is counted. */
  private fun live(id: Long, transferProgress: Int = AttachmentTable.TRANSFER_PROGRESS_DONE): DatabaseAttachment {
    return photo(id, TransformProperties(tellomiLivePhotoVideo = "not-a-pointer"), transferProgress)
  }

  private fun photo(id: Long, transformProperties: TransformProperties?, transferProgress: Int = AttachmentTable.TRANSFER_PROGRESS_DONE): DatabaseAttachment {
    return DatabaseAttachment(
      attachmentId = AttachmentId(id),
      mmsId = 42L,
      hasData = false,
      hasThumbnail = false,
      contentType = "image/jpeg",
      transferProgress = transferProgress,
      size = 1_000L,
      fileName = "IMG_0001.JPG",
      cdn = Cdn.CDN_3,
      location = null,
      key = null,
      digest = null,
      incrementalDigest = null,
      incrementalMacChunkSize = 0,
      fastPreflightId = null,
      voiceNote = false,
      borderless = false,
      videoGif = false,
      width = 400,
      height = 300,
      quote = false,
      caption = null,
      stickerLocator = null,
      blurHash = null,
      audioHash = null,
      transformProperties = transformProperties,
      displayOrder = 0,
      uploadTimestamp = 0,
      dataHash = null,
      archiveCdn = null,
      thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.NONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE,
      archiveThumbnailTransferState = AttachmentTable.ArchiveTransferState.NONE,
      uuid = null,
      quoteTargetContentType = null,
      metadata = null
    )
  }
}
