package org.thoughtcrime.securesms.mediapreview;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.components.TellomiLivePhotoPlayerView;
import org.thoughtcrime.securesms.components.TellomiReleaseObservingFrameLayout;
import org.thoughtcrime.securesms.components.ZoomingImageView;
import org.signal.core.util.concurrent.LifecycleDisposable;
import org.signal.core.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TellomiLivePhoto;

import kotlin.Unit;

public final class ImageMediaPreviewPageFragment extends MediaPreviewPageFragment {

  private MediaPreviewPlayerControlView bottomBarControlView;
  private ZoomingImageView              zoomingImageView;

  private MediaPreviewViewModel viewModel;
  private LifecycleDisposable   lifecycleDisposable;

  /** Tellomi (spec R-2): set when this page is a live photo. */
  private @Nullable DatabaseAttachment         liveAttachment;
  private @Nullable TellomiLivePhotoPlayerView liveVideo;
  private           boolean                    liveActive;
  private           boolean                    liveHolding;
  private           boolean                    liveAutoplayed;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState)
  {
    View           view           = inflater.inflate(R.layout.media_preview_image_fragment, container, false);
    RequestManager requestManager = Glide.with(requireActivity());
    Bundle         arguments      = requireArguments();
    Uri            uri            = arguments.getParcelable(DATA_URI);
    String         contentType    = arguments.getString(DATA_CONTENT_TYPE);

    zoomingImageView    = view.findViewById(R.id.zooming_image_view);
    viewModel           = new ViewModelProvider(requireActivity()).get(MediaPreviewViewModel.class);
    lifecycleDisposable = new LifecycleDisposable();

    lifecycleDisposable.bindTo(getViewLifecycleOwner());

    if (!MediaUtil.isImageType(contentType)) {
      throw new AssertionError("This fragment can only display images");
    }

    zoomingImageView.setOnGainmapDetectedListener(() -> viewModel.setHdrCapable(uri));

    //noinspection ConstantConditions
    zoomingImageView.setImageUri(requestManager, uri, contentType, () -> events.onMediaReady());

    zoomingImageView.setOnClickListener(v -> events.singleTapOnMedia());
    zoomingImageView.setOnLongClickListener(v -> {
      // Tellomi (spec R-2): on a live photo, holding plays it with sound until let go (Telegram iOS gallery)
      if (liveAttachment != null) {
        liveHolding = true;
        startLive();
        return true;
      }
      return events.singleTapOnMedia();
    });

    liveAttachment = arguments.getParcelable(DATA_LIVE_PHOTO);
    if (liveAttachment != null) {
      liveVideo = view.findViewById(R.id.live_photo_video_view);
      liveVideo.setCropToFill(false);
      liveVideo.setOnStopped(() -> {
        setLiveActive(false);
        return Unit.INSTANCE;
      });
      ((TellomiReleaseObservingFrameLayout) view).setOnRelease(() -> {
        if (liveHolding) {
          liveHolding = false;
          stopLive();
        }
        return Unit.INSTANCE;
      });
    }

    lifecycleDisposable.add(viewModel.getState().distinctUntilChanged().subscribe(state -> {
      if (zoomingImageView != null) {
        zoomingImageView.setAlpha(state.isInSharedAnimation() ? 0f : 1f);
      }
    }));

    return view;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Tellomi (spec R-2): a live photo plays once, muted, the first time its page shows (Telegram iOS
    // `UniversalVideoGalleryItem.playLivePhotoAutoplayIfNeeded`)
    if (liveAttachment != null && !liveAutoplayed) {
      liveAutoplayed = true;
      fetchLive(uri -> {
        if (liveVideo != null && !liveActive) {
          liveVideo.play(uri, false);
        }
      });
    }
  }

  /** Tellomi (spec R-2): the viewer's live button — plays the moving half looping with sound, or stops it. */
  public void toggleLive() {
    if (liveActive) {
      stopLive();
    } else {
      startLive();
    }
  }

  public boolean isLivePhoto() {
    return liveAttachment != null;
  }

  public boolean isLiveActive() {
    return liveActive;
  }

  private void startLive() {
    setLiveActive(true);
    fetchLive(uri -> {
      if (liveVideo != null && liveActive) {
        liveVideo.play(uri, true);
      }
    });
  }

  private void stopLive() {
    if (liveVideo != null) {
      liveVideo.stop(true);
    }
    setLiveActive(false);
  }

  private void setLiveActive(boolean active) {
    if (liveActive != active) {
      liveActive = active;
      events.onLivePhotoActiveChanged(this, active);
    }
  }

  private void fetchLive(@NonNull java.util.function.Consumer<Uri> onReady) {
    DatabaseAttachment attachment = liveAttachment;
    if (attachment == null) {
      return;
    }
    android.content.Context context = requireContext().getApplicationContext();
    SimpleTask.run(getViewLifecycleOwner().getLifecycle(),
                   () -> TellomiLivePhoto.fetchVideo(context, attachment),
                   uri -> {
                     if (uri != null) {
                       onReady.accept(uri);
                     } else {
                       setLiveActive(false);
                     }
                   });
  }

  @Override
  public void onDestroyView() {
    liveVideo = null;
    if (zoomingImageView != null) {
      zoomingImageView.setOnGainmapDetectedListener(null);
      zoomingImageView = null;
    }
    super.onDestroyView();
  }

  @Override
  public void cleanUp() {
    bottomBarControlView = null;
  }

  @Override
  public boolean canStartDragDismiss() {
    // Tellomi (interaction audit A-20): a zoomed image pans; only a resting one can be dragged away.
    return zoomingImageView == null || zoomingImageView.isAtMinimumZoom();
  }

  @Override
  public void pause() {
    if (liveVideo != null) {
      liveVideo.stop(false);
    }
    liveHolding = false;
    setLiveActive(false);
  }

  @Override
  public void setBottomButtonControls(MediaPreviewPlayerControlView playerControlView) {
    bottomBarControlView = playerControlView;
  }
}
