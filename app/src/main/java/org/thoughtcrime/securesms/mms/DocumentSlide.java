package org.thoughtcrime.securesms.mms;


import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.models.media.TransformProperties;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.signal.core.ui.util.StorageUtil;

public class DocumentSlide extends Slide {

  public DocumentSlide(@NonNull Attachment attachment) {
    super(attachment);
  }

  public DocumentSlide(@NonNull Context context, @NonNull Uri uri,
                       @NonNull String contentType,  long size,
                       @Nullable String fileName)
  {
    super(constructAttachmentFromUri(context, uri, contentType, size, 0, 0, true, StorageUtil.getCleanFileName(fileName), null, null, null, null, false, false, false, false));
  }

  private DocumentSlide(@NonNull Context context, @NonNull Uri uri,
                        @NonNull String contentType, long size, int width, int height,
                        @Nullable String fileName, @NonNull TransformProperties transformProperties)
  {
    super(constructAttachmentFromUri(context, uri, contentType, size, width, height, true, StorageUtil.getCleanFileName(fileName), null, null, null, null, false, false, false, false, transformProperties));
  }

  /**
   * Tellomi (ADR-0068): the original bytes of an image / video, drawn as a file row on both ends
   * ({@code AttachmentPointer.tellomi.asFile}). Without it the upload compresses the image and the
   * receiver shows an ordinary photo. Width / height go along as usual (the upload adds the blur hash).
   */
  public static @NonNull DocumentSlide tellomiAsFile(@NonNull Context context, @NonNull Uri uri,
                                                     @NonNull String contentType, long size,
                                                     int width, int height, @Nullable String fileName)
  {
    return new DocumentSlide(context, uri, contentType, size, width, height, fileName, TransformProperties.forTellomiAsFile());
  }

  @Override
  public boolean hasDocument() {
    return true;
  }

}
