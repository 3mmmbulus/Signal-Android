/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */
package org.whispersystems.signalservice.api.messages

import java.util.Optional
import java.util.UUID

/**
 * Represents a received SignalServiceAttachment "handle."  This
 * is a pointer to the actual attachment content, which needs to be
 * retrieved using [SignalServiceMessageReceiver.retrieveAttachment]
 *
 * @author Moxie Marlinspike
 */
class SignalServiceAttachmentPointer @JvmOverloads constructor(
  val cdnNumber: Int,
  val remoteId: SignalServiceAttachmentRemoteId,
  contentType: String?,
  val key: ByteArray?,
  val size: Optional<Int>,
  val preview: Optional<ByteArray>,
  val width: Int,
  val height: Int,
  val digest: Optional<ByteArray>,
  val incrementalDigest: Optional<ByteArray>,
  val incrementalMacChunkSize: Int,
  val fileName: Optional<String>,
  val voiceNote: Boolean,
  val isBorderless: Boolean,
  val isGif: Boolean,
  val caption: Optional<String>,
  val blurHash: Optional<String>,
  val uploadTimestamp: Long,
  val uuid: UUID?,
  /** Tellomi (ADR-0068): `AttachmentPointer.tellomi.asFile` — draw as a file row whatever the content type. */
  val tellomiAsFile: Boolean = false,
  /**
   * Tellomi (ADR-0068 §4.1): `AttachmentPointer.tellomi.spoiler` — cover until tapped. Already checked against the rules:
   * only on an image / video, and never alongside [tellomiAsFile] (which wins).
   */
  val tellomiSpoiler: Boolean = false
) : SignalServiceAttachment(contentType) {
  override fun isStream() = false
  override fun isPointer() = true
}
