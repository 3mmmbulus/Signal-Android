/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import org.signal.core.util.contentproviders.BlobProvider
import org.signal.mediasend.preupload.PreUploadRepository
import org.signal.mediasend.screens.extra.AttachmentExtraPages
import org.signal.mediasend.screens.files.AttachmentFilesRepository
import org.signal.video.exo.ExoPlayerPool
import org.thoughtcrime.securesms.video.interfaces.MediaInputFactory

/**
 * MediaSend Feature Module dependencies
 */
object MediaSendDependencies {
  private lateinit var _application: Application
  private lateinit var _provider: Provider

  @Synchronized
  fun init(application: Application, provider: Provider) {
    if (this::_application.isInitialized || this::_provider.isInitialized) {
      return
    }

    _application = application
    _provider = provider
  }

  val application
    get() = _application

  val preUploadRepository: PreUploadRepository
    get() = _provider.providePreUploadRepository()

  val mediaSendRepository: MediaSendRepository
    get() = _provider.provideMediaSendRepository()

  val exoPlayerPool: ExoPlayerPool<ExoPlayer>
    get() = _provider.provideExoPlayerPool()

  val blobs: BlobProvider
    get() = _provider.provideBlobs()

  val qrRepository: MediaSendQrRepository
    get() = _provider.provideQrRepository()

  val mediaInputFactory: MediaInputFactory
    get() = _provider.provideMediaInputFactory()

  /** Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页的数据。 */
  val attachmentFilesRepository: AttachmentFilesRepository
    get() = _provider.provideAttachmentFilesRepository()

  /** Tellomi（2026-10-16）：附件 Sheet 里由 app 画的页（投票……），见 [AttachmentExtraPages]。 */
  val attachmentExtraPages: AttachmentExtraPages
    get() = _provider.provideAttachmentExtraPages()

  interface Provider {
    fun provideMediaSendRepository(): MediaSendRepository
    fun providePreUploadRepository(): PreUploadRepository
    fun provideQrRepository(): MediaSendQrRepository
    fun provideExoPlayerPool(): ExoPlayerPool<ExoPlayer>
    fun provideBlobs(): BlobProvider
    fun provideMediaInputFactory(): MediaInputFactory
    fun provideAttachmentFilesRepository(): AttachmentFilesRepository
    fun provideAttachmentExtraPages(): AttachmentExtraPages
  }
}
