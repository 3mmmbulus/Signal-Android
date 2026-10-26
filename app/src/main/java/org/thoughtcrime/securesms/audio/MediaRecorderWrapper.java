package org.thoughtcrime.securesms.audio;

import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;

import java.io.IOException;

/**
 * Wrap Android's {@link MediaRecorder} for use with voice notes.
 */
public class MediaRecorderWrapper implements Recorder {

  private static final String TAG = Log.tag(MediaRecorderWrapper.class);

  private static final int    SAMPLE_RATE       = 44100;
  private static final int    CHANNELS          = 1;
  private static final int    BIT_RATE          = 32000;

  private volatile MediaRecorder recorder = null;
  
  private ParcelFileDescriptor outputFileDescriptor;

  @Override
  public void start(ParcelFileDescriptor fileDescriptor) throws IOException {
    Log.i(TAG, "Recording voice note using MediaRecorderWrapper.");
    this.outputFileDescriptor = fileDescriptor;

    recorder = new MediaRecorder();

    try {
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
      recorder.setOutputFile(fileDescriptor.getFileDescriptor());
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(getSampleRate());
      recorder.setAudioEncodingBitRate(BIT_RATE);
      recorder.setAudioChannels(CHANNELS);
      recorder.prepare();
      recorder.start();
    } catch (RuntimeException e) {
      Log.w(TAG, "Unable to start recording", e);
      recorder.release();
      recorder = null;
      StreamUtil.close(outputFileDescriptor);
      outputFileDescriptor = null;
      throw new IOException(e);
    }
  }

  @Override
  public int getMaxAmplitude() {
    MediaRecorder current = recorder;
    if (current == null) {
      return 0;
    }

    try {
      return current.getMaxAmplitude();
    } catch (RuntimeException e) {
      // 还没开始或刚被释放：MediaRecorder 抛 IllegalStateException，光晕当没声音。
      return 0;
    }
  }

  @Override
  public void stop() {
    if (recorder == null) {
      return;
    }

    try {
      recorder.stop();
    } catch (RuntimeException e) {
      if (e.getClass() != RuntimeException.class) {
        throw e;
      } else {
        Log.d(TAG, "Recording stopped with no data captured.");
      }
    } finally {
      recorder.release();
      recorder = null;
      StreamUtil.close(outputFileDescriptor);
      outputFileDescriptor = null;
    }
  }


  @Override
  public boolean pause() {
    MediaRecorder current = recorder;
    if (current == null) {
      return false;
    }
    try {
      // MediaRecorder.pause/resume since API 24; we only use this wrapper when SDK >= 26.
      current.pause();
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "Unable to pause recording", e);
      return false;
    }
  }

  @Override
  public boolean resume() {
    MediaRecorder current = recorder;
    if (current == null) {
      return false;
    }
    try {
      current.resume();
      return true;
    } catch (RuntimeException e) {
      Log.w(TAG, "Unable to resume recording", e);
      return false;
    }
  }

  private static int getSampleRate() {
    if ("Xiaomi".equals(Build.MANUFACTURER)) {
      // Recordings sound robotic with the standard sample rate.
      switch(Build.MODEL) {
        case "Mi 9T":
          return 44000;
        case "Mi 9T Pro":
          return 32000;
        default:
      }
    }
    return SAMPLE_RATE;
  }
}
