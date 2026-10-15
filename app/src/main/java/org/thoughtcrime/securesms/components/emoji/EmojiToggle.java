package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;

import org.signal.core.util.ContextUtil;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.TellomiIconSwap;
import org.thoughtcrime.securesms.keyboard.KeyboardPage;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

public class EmojiToggle extends AppCompatImageButton implements MediaKeyboard.MediaKeyboardListener {

  private Drawable emojiToggle;
  private Drawable stickerToggle;
  private Drawable gifToggle;

  private Drawable mediaToggle;
  private Drawable imeToggle;

  /** Tellomi（交互审计 A-11）：正在显示（或正淡入）的那张图标；换图标有交叉淡入，期间 getDrawable() 是过渡用的组合图。 */
  private Drawable shownToggle;


  public EmojiToggle(Context context) {
    super(context);
    initialize();
  }

  public EmojiToggle(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public EmojiToggle(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize();
  }

  public void setToMedia() {
    show(mediaToggle);
  }

  public void setToIme() {
    show(imeToggle);
  }

  /** Tellomi（交互审计 A-11）：表情 ↔ 键盘图标交叉淡入 + 弹一下，不再直接替换。 */
  private void show(@NonNull Drawable toggle) {
    if (toggle == shownToggle) return;

    Drawable previous = shownToggle;
    shownToggle = toggle;
    TellomiIconSwap.swap(this, previous, toggle);
  }

  private void initialize() {
    this.emojiToggle   = ContextUtil.requireDrawable(getContext(), R.drawable.symbol_emoji_24);
    this.stickerToggle = ContextUtil.requireDrawable(getContext(), org.signal.core.ui.R.drawable.symbol_sticker_24);
    this.gifToggle     = ContextUtil.requireDrawable(getContext(), R.drawable.symbol_gif_24);
    this.imeToggle     = ContextUtil.requireDrawable(getContext(), R.drawable.symbol_keyboard_24);
    this.mediaToggle   = emojiToggle;

    setToMedia();
  }

  public void attach(MediaKeyboard drawer) {
    drawer.setKeyboardListener(this);
  }

  public void setStickerMode(@NonNull KeyboardPage page) {
    switch (page) {
      case EMOJI:
        mediaToggle = emojiToggle;
        break;
      case STICKER:
        mediaToggle = stickerToggle;
        break;
      case GIF:
        mediaToggle = gifToggle;
        break;
    }

    if (shownToggle != imeToggle) {
      setToMedia();
    }
  }

  public boolean isStickerMode() {
    return this.mediaToggle == stickerToggle;
  }

  @Override public void onShown() {
    setToIme();
  }

  @Override public void onHidden() {
    setToMedia();
  }

  @Override
  public void onKeyboardChanged(@NonNull KeyboardPage page) {
    setStickerMode(page);
    switch (page) {
      case EMOJI:
        TextSecurePreferences.setMediaKeyboardMode(getContext(), TextSecurePreferences.MediaKeyboardMode.EMOJI);
        break;
      case STICKER:
        TextSecurePreferences.setMediaKeyboardMode(getContext(), TextSecurePreferences.MediaKeyboardMode.STICKER);
        break;
      case GIF:
        TextSecurePreferences.setMediaKeyboardMode(getContext(), TextSecurePreferences.MediaKeyboardMode.GIF);
        break;
    }
  }
}
