package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.TextPaint;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import org.signal.core.util.Util;
import org.thoughtcrime.securesms.R;

public class LongClickCopySpan extends URLSpan {
  private static final String PREFIX_MAILTO = "mailto:";
  private static final String PREFIX_TEL = "tel:";

  private boolean isHighlighted;
  @ColorInt
  private int highlightColor;

  private final Integer textColor;
  private final boolean underline;

  public LongClickCopySpan(String url) {
    this(url, null, true);
  }

  public LongClickCopySpan(String url, @ColorInt Integer textColor, boolean underline) {
    super(url);
    this.textColor = textColor;
    this.underline = underline;
  }

  /** Tellomi (TF43 P1): web links get their own menu on long press (see {@link TellomiLinkMenu}). */
  boolean showsLinkMenu() {
    return TellomiLinkMenu.isWebLink(getURL());
  }

  void onLongClick(View widget) {
    // Tellomi (TF43 P1, PM 2026-10-25): no silent copy; show the link menu, Open goes through the normal tap path.
    if (showsLinkMenu()) {
      TellomiLinkMenu.show(widget, getURL(), () -> onClick(widget));
      return;
    }
    Context context = widget.getContext();
    String preparedUrl = prepareUrl(getURL());
    copyUrl(context, preparedUrl);
    if (Build.VERSION.SDK_INT <= 32) {
      Toast.makeText(context, context.getString(R.string.ConversationItem_copied_text, preparedUrl), Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void updateDrawState(@NonNull TextPaint ds) {
    if (ds.getColor() == Color.TRANSPARENT) {
      return;
    }

    super.updateDrawState(ds);
    if (textColor != null) {
      ds.setColor(textColor);
    }
    ds.bgColor = highlightColor;
    ds.setUnderlineText(!isHighlighted && underline);
  }

  void setHighlighted(boolean highlighted, @ColorInt int highlightColor) {
    this.isHighlighted = highlighted;
    this.highlightColor = highlightColor;
  }

  private void copyUrl(Context context, String url) {
    Util.writeTextToClipboard(context, url);
  }

  private String prepareUrl(String url) {
    if (url.startsWith(PREFIX_MAILTO)) {
      return url.substring(PREFIX_MAILTO.length());
    } else if (url.startsWith(PREFIX_TEL)) {
      return url.substring(PREFIX_TEL.length());
    }
    return url;
  }
}
