/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.signal.core.models.media.MediaFolder

/**
 * Using @Serializable and NavKey for type-safe navigation with Navigation 3.
 */
@Serializable
sealed interface MediaSendRoute : NavKey {
  @Serializable
  sealed interface Select : MediaSendRoute {
    @Serializable
    data object Folders : Select

    @Serializable
    data class Files(val folder: MediaFolder) : Select
  }

  @Serializable
  sealed interface Capture : MediaSendRoute {
    @Serializable
    data object Chrome : Capture

    @Serializable
    data object Camera : Capture

    @Serializable
    data object TextStory : Capture
  }

  @Serializable
  data object Edit : MediaSendRoute

  @Serializable
  data object Send : MediaSendRoute

  /** Tellomi（tellomi/tellomi#1263 F-3）：附件 Sheet「文件」页的「从相册中选择」：换掉整个返回栈（Sheet 关掉）的整屏相册网格。 */
  @Serializable
  data object FilesGallery : MediaSendRoute
}
