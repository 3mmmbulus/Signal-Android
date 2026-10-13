package org.whispersystems.signalservice.api.util;

import org.signal.core.util.UuidUtil;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId;
import org.whispersystems.signalservice.internal.push.AttachmentPointer;
import org.whispersystems.signalservice.internal.push.TellomiAttachment;

import java.io.IOException;
import java.util.Optional;

import okio.ByteString;

public final class AttachmentPointerUtil {
  public static SignalServiceAttachmentPointer createSignalAttachmentPointer(byte[] pointer) throws InvalidMessageStructureException, IOException {
    return createSignalAttachmentPointer(AttachmentPointer.ADAPTER.decode(pointer));
  }

  public static SignalServiceAttachmentPointer createSignalAttachmentPointer(AttachmentPointer pointer) throws InvalidMessageStructureException {
    if (pointer.cdnNumber == null) {
      throw new InvalidMessageStructureException("AttachmentPointer CDN number not set");
    }

    if (pointer.key == null) {
      throw new InvalidMessageStructureException("AttachmentPointer key not set");
    }

    return new SignalServiceAttachmentPointer(pointer.cdnNumber,
                                              SignalServiceAttachmentRemoteId.from(pointer),
                                              pointer.contentType,
                                              pointer.key.toByteArray(),
                                              pointer.size != null ? Optional.of(pointer.size) : Optional.empty(),
                                              pointer.thumbnail != null ? Optional.of(pointer.thumbnail.toByteArray()): Optional.empty(),
                                              pointer.width != null ? pointer.width : 0,
                                              pointer.height != null ? pointer.height : 0,
                                              pointer.digest != null ? Optional.of(pointer.digest.toByteArray()) : Optional.empty(),
                                              pointer.incrementalMac != null ? Optional.of(pointer.incrementalMac.toByteArray()) : Optional.empty(),
                                              pointer.chunkSize != null ? pointer.chunkSize : 0,
                                              pointer.fileName != null ? Optional.of(pointer.fileName) : Optional.empty(),
                                              ((pointer.flags != null ? pointer.flags : 0) & AttachmentPointer.Flags.VOICE_MESSAGE.getValue()) != 0,
                                              ((pointer.flags != null ? pointer.flags : 0) & AttachmentPointer.Flags.BORDERLESS.getValue()) != 0,
                                              ((pointer.flags != null ? pointer.flags : 0) & AttachmentPointer.Flags.GIF.getValue()) != 0,
                                              pointer.caption != null && !pointer.caption.isEmpty() ? Optional.of(pointer.caption) : Optional.empty(),
                                              pointer.blurHash != null ? Optional.of(pointer.blurHash) : Optional.empty(),
                                              pointer.uploadTimestamp != null ? pointer.uploadTimestamp : 0,
                                              UuidUtil.fromByteStringOrNull(pointer.clientUuid),
                                              isTellomiAsFile(pointer),
                                              isTellomiSpoiler(pointer),
                                              tellomiLivePhotoVideo(pointer));
  }

  /** Tellomi (ADR-0068 §4.1 rule 1): drawn as a file row whatever the content type. */
  private static boolean isTellomiAsFile(AttachmentPointer pointer) {
    return pointer.tellomi != null && Boolean.TRUE.equals(pointer.tellomi.asFile);
  }

  /**
   * Tellomi (ADR-0068 §4.1 rules 1–3): covered until tapped. Only on an image / video, and asFile wins; anything else
   * ignores the field and shows the attachment as usual rather than dropping the message.
   */
  static boolean isTellomiSpoiler(AttachmentPointer pointer) {
    if (pointer.tellomi == null || !Boolean.TRUE.equals(pointer.tellomi.spoiler) || isTellomiAsFile(pointer)) {
      return false;
    }
    String contentType = pointer.contentType != null ? pointer.contentType.toLowerCase(java.util.Locale.ROOT) : "";
    return contentType.startsWith("image/") || contentType.startsWith("video/");
  }

  /**
   * Tellomi (ADR-0068 §4.1 rules 1–3, spec R-2): the moving half of a live photo. Only on a still image and never with
   * asFile; the nested pointer must be a video with CDN info and a key, one level only (it can't carry its own
   * livePhotoVideo). Anything else ignores the field: the still shows as an ordinary picture.
   */
  static byte[] tellomiLivePhotoVideo(AttachmentPointer pointer) {
    if (pointer.tellomi == null || pointer.tellomi.livePhotoVideo == null || isTellomiAsFile(pointer)) {
      return null;
    }
    if (!lowerCase(pointer.contentType).startsWith("image/")) {
      return null;
    }
    AttachmentPointer video = pointer.tellomi.livePhotoVideo;
    if (!lowerCase(video.contentType).startsWith("video/")) {
      return null;
    }
    if (video.tellomi != null && video.tellomi.livePhotoVideo != null) {
      return null;
    }
    if (video.cdnNumber == null || video.key == null || (video.cdnKey == null && video.cdnId == null)) {
      return null;
    }
    return video.encode();
  }

  private static String lowerCase(String contentType) {
    return contentType != null ? contentType.toLowerCase(java.util.Locale.ROOT) : "";
  }

  public static AttachmentPointer createAttachmentPointer(SignalServiceAttachmentPointer attachment) {
    AttachmentPointer.Builder builder = new AttachmentPointer.Builder()
                                                             .cdnNumber(attachment.getCdnNumber())
                                                             .contentType(attachment.getContentType())
                                                             .key(ByteString.of(attachment.getKey()))
                                                             .digest(ByteString.of(attachment.getDigest().get()))
                                                             .size(attachment.getSize().get())
                                                             .uploadTimestamp(attachment.getUploadTimestamp());

    if (attachment.getIncrementalDigest().isPresent()) {
      builder.incrementalMac(ByteString.of(attachment.getIncrementalDigest().get()));
    }

    if (attachment.getIncrementalMacChunkSize() > 0) {
      builder.chunkSize(attachment.getIncrementalMacChunkSize());
    }

    if (attachment.getRemoteId() instanceof SignalServiceAttachmentRemoteId.V2) {
      builder.cdnId(((SignalServiceAttachmentRemoteId.V2) attachment.getRemoteId()).getCdnId());
    }

    if (attachment.getRemoteId() instanceof SignalServiceAttachmentRemoteId.V4) {
      builder.cdnKey(((SignalServiceAttachmentRemoteId.V4) attachment.getRemoteId()).getCdnKey());
    }

    if (attachment.getFileName().isPresent()) {
      builder.fileName(attachment.getFileName().get());
    }

    if (attachment.getPreview().isPresent()) {
      builder.thumbnail(ByteString.of(attachment.getPreview().get()));
    }

    if (attachment.getWidth() > 0) {
      builder.width(attachment.getWidth());
    }

    if (attachment.getHeight() > 0) {
      builder.height(attachment.getHeight());
    }

    int flags = 0;

    if (attachment.getVoiceNote()) {
      flags |= AttachmentPointer.Flags.VOICE_MESSAGE.getValue();
    }

    if (attachment.isBorderless()) {
      flags |= AttachmentPointer.Flags.BORDERLESS.getValue();
    }

    if (attachment.isGif()) {
      flags |= AttachmentPointer.Flags.GIF.getValue();
    }

    builder.flags(flags);

    if (attachment.getCaption().isPresent()) {
      builder.caption(attachment.getCaption().get());
    }

    if (attachment.getBlurHash().isPresent()) {
      builder.blurHash(attachment.getBlurHash().get());
    }

    if (attachment.getUuid() != null) {
      builder.clientUuid(UuidUtil.toByteString(attachment.getUuid()));
    }

    // Tellomi (ADR-0068): only set when true, so ordinary attachments stay byte-identical to upstream.
    if (attachment.getTellomiAsFile()) {
      builder.tellomi(new TellomiAttachment.Builder().asFile(true).build());
    }

    return builder.build();
  }
}
