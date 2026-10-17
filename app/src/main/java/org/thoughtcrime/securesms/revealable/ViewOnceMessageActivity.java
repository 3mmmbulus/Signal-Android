package org.thoughtcrime.securesms.revealable;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;

import org.signal.core.util.logging.Log;
import org.signal.glide.decryptableuri.DecryptableUri;
import org.signal.video.VideoPlayer;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter;
import org.thoughtcrime.securesms.util.TellomiViewOnceTimer;
import org.thoughtcrime.securesms.util.WindowUtil;

import java.util.concurrent.TimeUnit;

public class ViewOnceMessageActivity extends PassphraseRequiredActivity implements VideoPlayer.PlayerStateCallback {

  private static final String TAG = Log.tag(ViewOnceMessageActivity.class);

  private static final String KEY_MESSAGE_ID = "message_id";
  private static final String KEY_URI        = "uri";

  private ImageView                image;
  private VideoPlayer              video;
  private View                     closeButton;
  private TextView                 duration;
  private ViewOnceMessageViewModel viewModel;
  private Uri                      uri;

  /** Tellomi（tellomi/tellomi#1714，ADR-0079 §4.3）：计时的一次性查看开几秒（null = 普通一次性查看，不限时）。 */
  private Integer                      tellomiSeconds;
  private TellomiViewOnceCountdownView tellomiCountdown;

  private final Handler  handler                = new Handler(Looper.getMainLooper());
  private final Runnable durationUpdateRunnable = () -> {
    long timeLeft = TimeUnit.MILLISECONDS.toSeconds(video.getDuration() - video.getPlaybackPosition());
    long minutes  = timeLeft / 60;
    long seconds  = timeLeft % 60;

    duration.setText(getString(R.string.ViewOnceMessageActivity_video_duration, minutes, seconds));
    scheduleDurationUpdate();
  };

  public static Intent getIntent(@NonNull Context context, long messageId, @NonNull Uri uri) {
    Intent intent = new Intent(context, ViewOnceMessageActivity.class);
    intent.putExtra(KEY_MESSAGE_ID, messageId);
    intent.putExtra(KEY_URI, uri);
    return intent;
  }

  @Override
  protected void attachBaseContext(@NonNull Context newBase) {
    getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    super.attachBaseContext(newBase);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    super.onCreate(savedInstanceState, ready);
    setContentView(R.layout.view_once_message_activity);

    WindowUtil.clearLightStatusBar(getWindow());
    WindowUtil.clearLightNavigationBar(getWindow());

    this.image       = findViewById(R.id.view_once_image);
    this.video       = findViewById(R.id.view_once_video);
    this.duration    = findViewById(R.id.view_once_duration);
    this.closeButton = findViewById(R.id.view_once_close_button);
    this.uri         = getIntent().getParcelableExtra(KEY_URI);
    this.tellomiCountdown = findViewById(R.id.view_once_countdown);
    tellomiCountdown.setOnFinished(() -> {
      finish();
      return kotlin.Unit.INSTANCE;
    });
    SystemWindowInsetsSetter.attach(tellomiCountdown, this, WindowInsetsCompat.Type.statusBars(), SystemWindowInsetsSetter.ApplyMode.MARGIN);

    SystemWindowInsetsSetter.attach(closeButton, this, WindowInsetsCompat.Type.statusBars(), SystemWindowInsetsSetter.ApplyMode.MARGIN);
    SystemWindowInsetsSetter.attach(duration, this, WindowInsetsCompat.Type.statusBars(), SystemWindowInsetsSetter.ApplyMode.MARGIN);

    closeButton.setOnClickListener(v -> finish());

    initViewModel(getIntent().getLongExtra(KEY_MESSAGE_ID, -1), uri);
  }

  @Override
  protected void onStop() {
    super.onStop();
    cancelDurationUpdate();
    video.cleanup();
    AppDependencies.getBlobs().delete(this, uri);
    finish();
  }

  @Override
  public void onPlayerReady() {
    handler.post(durationUpdateRunnable);
    // Tellomi（ADR-0079 §4.3）：计时的视频开 max(时长, N) 秒。
    tellomiStartCountdown(video.getDuration());
  }

  /** Tellomi（#1714）：开始倒计时（只一次）；走完自己关。 */
  private void tellomiStartCountdown(@Nullable Long videoDurationMillis) {
    if (tellomiSeconds == null || tellomiCountdown.getDurationMillis() > 0) {
      return;
    }
    tellomiCountdown.setVisibility(View.VISIBLE);
    tellomiCountdown.start(TellomiViewOnceTimer.viewerDurationMillis(tellomiSeconds, videoDurationMillis != null && videoDurationMillis > 0 ? videoDurationMillis : null));
  }

  private void initViewModel(long messageId, @NonNull Uri uri) {
    ViewOnceMessageRepository repository = new ViewOnceMessageRepository(this);

    viewModel = new ViewModelProvider(this, new ViewOnceMessageViewModel.Factory(messageId, repository)).get(ViewOnceMessageViewModel.class);

    viewModel.getMessage().observe(this, (message) -> {
      if (message == null) return;

      if (message.isPresent()) {
        tellomiSeconds = TellomiViewOnceTimer.seconds(message.get());
        displayMedia(uri);
      } else {
        image.setImageDrawable(null);
        finish();
      }
    });
  }

  private void displayMedia(@NonNull Uri uri) {
    if (MediaUtil.isVideoType(PartAuthority.getAttachmentContentType(this, uri))) {
      displayVideo(uri);
    } else {
      displayImage(uri);
    }
  }

  private void displayVideo(@NonNull Uri uri) {
    video.setVisibility(View.VISIBLE);
    image.setVisibility(View.GONE);
    duration.setVisibility(View.VISIBLE);
    if (tellomiSeconds != null) {
      // 环在右上角，时长往左让一让。
      ((android.widget.FrameLayout.LayoutParams) duration.getLayoutParams()).setMarginEnd((int) org.signal.core.util.DimensionUnit.DP.toPixels(52));
    }

    video.setWindow(getWindow());
    video.setPlayerStateCallbacks(this);
    video.setExoPlayerPool(AppDependencies.getExoPlayerPool());
    video.setVideoSource(uri, true, TAG);

    video.hideControls();
    video.loopForever();
  }

  private void displayImage(@NonNull Uri uri) {
    video.setVisibility(View.GONE);
    image.setVisibility(View.VISIBLE);
    duration.setVisibility(View.GONE);
    tellomiStartCountdown(null);

    Glide.with(this)
            .load(new DecryptableUri(uri))
            .fitCenter()
            .into(image);
  }

  private void scheduleDurationUpdate() {
    handler.postDelayed(durationUpdateRunnable, 100);
  }

  private void cancelDurationUpdate() {
    handler.removeCallbacks(durationUpdateRunnable);
  }

  private class ViewOnceGestureListener extends GestureDetector.SimpleOnGestureListener {

    private final View view;

    private ViewOnceGestureListener(View view) {
      this.view = view;
    }

    @Override
    public boolean onDown(MotionEvent e) {
      return true;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
      view.performClick();
      return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      finish();
      return true;
    }
  }
}
