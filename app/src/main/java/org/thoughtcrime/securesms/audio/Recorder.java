package org.thoughtcrime.securesms.audio;

import android.os.ParcelFileDescriptor;

import java.io.IOException;

/**
 * Simple abstraction of the interface for the original voice note recording and the new.
 */
public interface Recorder {
  void start(ParcelFileDescriptor fileDescriptor) throws IOException;
  void stop();

  /**
   * Tellomi（交互审计 A-12）：上次调用以来麦克风的最大振幅（0–32767），录音按钮的光晕按它变大变小。
   * 没在录或读不到时返回 0。会从主线程调，和 [start] / [stop] 不在同一个线程。
   */
  int getMaxAmplitude();

  /**
   * Tellomi（Telegram 对照 3.3 / F12）：暂停录音；接在同一个文件后面继续。
   * @return 是否生效
   */
  boolean pause();

  /**
   * Tellomi（Telegram 对照 3.3 / F12）：从暂停继续录音。
   * @return 是否生效
   */
  boolean resume();
}
