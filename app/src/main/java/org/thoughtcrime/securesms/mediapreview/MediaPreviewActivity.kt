package org.thoughtcrime.securesms.mediapreview

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.transition.addListener
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.commit
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.signal.core.ui.motion.TellomiMotion
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaController
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaControllerOwner
import org.thoughtcrime.securesms.util.WindowUtil
import java.util.concurrent.TimeUnit

class MediaPreviewActivity : PassphraseRequiredActivity(), VoiceNoteMediaControllerOwner {

  /** Tellomi（#1257）：查看器播放中自动收起控件——碰一下屏幕计时从头来（照 Telegram Android：按下取消、抬手重排）。 */
  var onUserTouch: (() -> Unit)? = null

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    onUserTouch?.invoke()
    return super.dispatchTouchEvent(ev)
  }

  override lateinit var voiceNoteMediaController: VoiceNoteMediaController

  private val viewModel: MediaPreviewViewModel by viewModels()
  private val lifecycleDisposable = LifecycleDisposable()
  private val args by lazy {
    MediaIntentFactory.requireArguments(intent.extras!!)
  }

  private lateinit var transitionImageView: ImageView
  private lateinit var dragDismissLayout: TellomiDragDismissLayout

  private var isWindowStarted = false

  // Tellomi（交互审计 A-20 Android 半边）：拖拽关闭的状态。
  private var hasSharedElementTransition = false
  private var dragOffset = 0f
  private var dragBaseOffset = 0f
  private var dragSpring: SpringAnimation? = null
  private var isDismissingByDrag = false

  override fun attachBaseContext(newBase: Context) {
    delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
    super.attachBaseContext(newBase)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    hasSharedElementTransition = MediaPreviewCache.drawable != null && !args.skipSharedElementTransition
    if (hasSharedElementTransition) {
      val originalCorners = ShapeAppearanceModel.Builder()
        .setTopLeftCornerSize(args.sharedElementArgs.topLeft)
        .setTopRightCornerSize(args.sharedElementArgs.topRight)
        .setBottomRightCornerSize(args.sharedElementArgs.bottomRight)
        .setBottomLeftCornerSize(args.sharedElementArgs.bottomLeft)
        .build()

      setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
      window.sharedElementEnterTransition = MaterialContainerTransform().apply {
        addTarget(SHARED_ELEMENT_TRANSITION_NAME)
        startShapeAppearanceModel = originalCorners
        endShapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(0f).build()
        duration = 250L
        interpolator = PathInterpolatorCompat.create(0.17f, 0.17f, 0f, 1f)
        addListener(
          onStart = {
            transitionImageView.alpha = 1f
            viewModel.setIsInSharedAnimation(true)
          },
          onEnd = {
            viewModel.setIsInSharedAnimation(false)
          }
        )
      }

      window.sharedElementExitTransition = MaterialContainerTransform().apply {
        addTarget(SHARED_ELEMENT_TRANSITION_NAME)
        startShapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(0f).build()
        endShapeAppearanceModel = originalCorners
        duration = 250L
        interpolator = PathInterpolatorCompat.create(0.17f, 0.17f, 0f, 1f)
        addListener(
          onStart = {
            transitionImageView.alpha = 1f
            viewModel.setIsInSharedAnimation(true)
          },
          onEnd = {
            viewModel.setIsInSharedAnimation(false)
          }
        )
      }
    }

    super.onCreate(savedInstanceState, ready)
    lifecycleDisposable.bindTo(this)
    setTheme(R.style.TextSecure_MediaPreview)
    setContentView(R.layout.activity_media_preview)

    transitionImageView = findViewById(R.id.transition_image_view)
    // Tellomi（A-20）：窗口是半透明的（Theme.Signal.MediaPreview.Translucent），拖拽时背景变淡能看到后面的聊天；
    // 上面 setTheme 换上的 TextSecure.MediaPreview 带了不透明的 windowBackground，这里换回透明，平时的黑底由内容自己画。
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dragDismissLayout = findViewById(R.id.media_preview_drag_dismiss_layout)
    dragDismissLayout.callback = DragDismissCallback()
    onBackPressedDispatcher.addCallback(this, PredictiveBackCallback())
    val cacheDrawable = MediaPreviewCache.drawable?.let { RecycledBitmapGuardDrawable(it) }
    if (cacheDrawable != null && !args.skipSharedElementTransition) {
      val bounds = cacheDrawable.bounds
      val aspectRatio = bounds.width().toFloat() / bounds.height()
      val screenRatio = resources.displayMetrics.widthPixels.toFloat() / resources.displayMetrics.heightPixels
      if (aspectRatio > screenRatio) {
        transitionImageView.updateLayoutParams<LayoutParams> {
          width = LayoutParams.MATCH_PARENT
        }
      } else {
        transitionImageView.updateLayoutParams<LayoutParams> {
          height = LayoutParams.MATCH_PARENT
        }
      }

      val originalCallback = cacheDrawable.callback
      transitionImageView.setImageDrawable(cacheDrawable)
      cacheDrawable.callback = originalCallback

      var hasMediaBeenReady = false
      lifecycleDisposable += viewModel.state.map {
        it.isInSharedAnimation to it.loadState
      }.distinctUntilChanged().subscribe { (isInSharedAnimation, loadState) ->
        if (loadState == MediaPreviewState.LoadState.MEDIA_READY) {
          hasMediaBeenReady = true
        }
        if (!isInSharedAnimation && hasMediaBeenReady) {
          transitionImageView.clearAnimation()
          transitionImageView.animate()
            .setInterpolator(PathInterpolatorCompat.create(0.17f, 0.17f, 0f, 1f))
            .setDuration(200)
            .alpha(0f)
        }
      }
    } else {
      transitionImageView.visibility = View.INVISIBLE
      viewModel.setIsInSharedAnimation(false)
    }

    if (Build.VERSION.SDK_INT >= 35) {
      // ViewPager2 reports the selected page while the settle animation is still running, and writing the color
      // mode rebuilds the HWUI surface. Debouncing collapses a flick through a mixed album into a single write.
      // A delayed write can never strand the window in HDR, since it only raises HDR while isWindowStarted.
      lifecycleDisposable += viewModel.state
        .map { it.shouldRenderHdr }
        .distinctUntilChanged()
        .debounce(COLOR_MODE_SETTLE_DELAY_MS, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
        .subscribe { applyWindowColorMode(it) }
    }

    voiceNoteMediaController = VoiceNoteMediaController(this, false)

    WindowUtil.clearLightStatusBar(window)
    WindowUtil.clearLightNavigationBar(window)

    if (savedInstanceState == null) {
      val bundle = Bundle()
      bundle.putParcelable(MediaPreviewFragment.ARGS_KEY, args)
      supportFragmentManager.commit {
        setReorderingAllowed(true)
        add(R.id.fragment_container_view, MediaPreviewFragment::class.java, bundle, FRAGMENT_TAG)
      }
    }
  }

  override fun onStart() {
    super.onStart()
    isWindowStarted = true
    applyWindowColorMode(viewModel.shouldRenderHdr)
  }

  override fun onStop() {
    isWindowStarted = false
    applyWindowColorMode(false)
    super.onStop()
  }

  override fun onPause() {
    super.onPause()
    MediaPreviewCache.drawable = null
  }

  override fun finishAfterTransition() {
    // Deliberately not applyWindowColorMode(false): that would rebuild the HWUI surface on the first frame of the
    // exit transition. onStop() clears it once the transition has finished.
    isWindowStarted = false
    if (viewModel.shouldFinishAfterTransition(args.initialMediaUri)) {
      super.finishAfterTransition()
    } else {
      super.finish()
    }
  }

  private fun previewFragment(): MediaPreviewFragment? {
    return supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? MediaPreviewFragment
  }

  /** Tellomi（A-20）：把拖拽位移画出来——媒体跟手、缩小，按钮和背景变淡；转场用的那张图跟着走，关的时候从这里缩回缩略图。 */
  private fun applyDragOffset(offset: Float) {
    dragOffset = offset
    applyDragVisual(offset, TellomiDragDismiss.progress(offset, resources.displayMetrics.density))
  }

  /** [offset] 竖向位移，[progress] 0…1 决定缩放和变淡（拖拽时由位移算出来，返回手势时就是手势进度）。 */
  private fun applyDragVisual(offset: Float, progress: Float) {
    val scale = TellomiDragDismiss.scale(progress)
    previewFragment()?.applyDragDismiss(offset, scale, progress)
    findViewById<View>(R.id.fragment_container_view).background?.alpha = ((1f - progress) * 255).toInt()
    transitionImageView.translationY = offset
    transitionImageView.scaleX = scale
    transitionImageView.scaleY = scale
  }

  private fun springBack(offset: Float, velocity: Float) {
    if (TellomiMotion.tier(this) == TellomiMotion.Tier.MINIMAL) {
      applyDragOffset(0f)
      previewFragment()?.onDragDismissEnded()
      return
    }
    dragSpring = SpringAnimation(FloatValueHolder(offset)).apply {
      spring = SpringForce(0f)
        .setStiffness(TellomiMotion.Release.stiffness)
        .setDampingRatio(TellomiMotion.Release.dampingRatio)
      setStartVelocity(velocity)
      addUpdateListener { _, value, _ -> applyDragOffset(value) }
      addEndListener { _, canceled, _, _ ->
        dragSpring = null
        if (!canceled) {
          applyDragOffset(0f)
          previewFragment()?.onDragDismissEnded()
        }
      }
      start()
    }
  }

  /**
   * 还停在点开的那一张、开场有共享元素、动效满档：走原来的 finishAfterTransition，从拖到的位置缩回聊天里的缩略图；
   * 否则（翻过页、省电 / 关了动画）顺着甩的方向飞出屏幕，背景淡掉，直接关。
   */
  private fun dismissByDrag(offset: Float, velocity: Float) {
    isDismissingByDrag = true
    val tier = TellomiMotion.tier(this)
    if (hasSharedElementTransition && tier == TellomiMotion.Tier.FULL && viewModel.shouldFinishAfterTransition(args.initialMediaUri)) {
      finishAfterTransition()
      return
    }
    val direction = TellomiDragDismiss.dismissDirection(offset, velocity)
    val target = direction * dragDismissLayout.height.toFloat()
    ValueAnimator.ofFloat(offset, target).apply {
      duration = if (tier == TellomiMotion.Tier.MINIMAL) 0L else FLY_OUT_DURATION_MS
      interpolator = DecelerateInterpolator()
      addUpdateListener { applyDragOffset(it.animatedValue as Float) }
      doOnEnd {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
      }
      start()
    }
  }

  /**
   * Tellomi（A-20 第二刀，审计「查看器 Activity opt-in 预测性返回，返回手势 = 缩小预览」）：Android 14 起返回手势一边滑一边把媒体
   * 缩小、略往下移、背景变淡；松手关（还在点开的那一张就缩回缩略图，否则往下飞出），滑回去取消就弹回。更早的系统没有进度，
   * 只有松手那一下，行为和原来的返回一样。
   */
  private inner class PredictiveBackCallback : OnBackPressedCallback(true) {
    private var backProgress = 0f
    private var settle: ValueAnimator? = null

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
      if (isDismissingByDrag) {
        return
      }
      settle?.cancel()
      dragSpring?.cancel()
      backProgress = 0f
      previewFragment()?.onDragDismissStarted()
      findViewById<View>(R.id.fragment_container_view).background?.mutate()
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
      if (isDismissingByDrag) {
        return
      }
      backProgress = backEvent.progress
      applyDragVisual(TellomiDragDismiss.backOffset(backProgress, resources.displayMetrics.density), backProgress)
    }

    override fun handleOnBackCancelled() {
      if (isDismissingByDrag) {
        return
      }
      val from = backProgress
      backProgress = 0f
      settle = ValueAnimator.ofFloat(from, 0f).apply {
        duration = if (TellomiMotion.tier(this@MediaPreviewActivity) == TellomiMotion.Tier.MINIMAL) 0L else BACK_SETTLE_DURATION_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener {
          val progress = it.animatedValue as Float
          applyDragVisual(TellomiDragDismiss.backOffset(progress, resources.displayMetrics.density), progress)
        }
        doOnEnd { previewFragment()?.onDragDismissEnded() }
        start()
      }
    }

    override fun handleOnBackPressed() {
      if (isDismissingByDrag) {
        return
      }
      val progress = backProgress
      backProgress = 0f
      if (progress <= 0f) {
        // 没有滑动进度（返回键、顶栏的返回、Android 14 以前）：照原来的返回
        finishAfterTransition()
        return
      }
      isDismissingByDrag = true
      val tier = TellomiMotion.tier(this@MediaPreviewActivity)
      if (hasSharedElementTransition && tier == TellomiMotion.Tier.FULL && viewModel.shouldFinishAfterTransition(args.initialMediaUri)) {
        finishAfterTransition()
        return
      }
      val density = resources.displayMetrics.density
      val fromOffset = TellomiDragDismiss.backOffset(progress, density)
      val toOffset = dragDismissLayout.height.toFloat()
      ValueAnimator.ofFloat(0f, 1f).apply {
        duration = if (tier == TellomiMotion.Tier.MINIMAL) 0L else FLY_OUT_DURATION_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener {
          val fraction = it.animatedValue as Float
          applyDragVisual(fromOffset + (toOffset - fromOffset) * fraction, progress + (1f - progress) * fraction)
        }
        doOnEnd {
          finish()
          @Suppress("DEPRECATION")
          overridePendingTransition(0, 0)
        }
        start()
      }
    }
  }

  private inner class DragDismissCallback : TellomiDragDismissLayout.Callback {
    override fun canStartDragDismiss(x: Float, y: Float): Boolean {
      if (isDismissingByDrag || isFinishing) {
        return false
      }
      val location = IntArray(2)
      dragDismissLayout.getLocationOnScreen(location)
      return previewFragment()?.canStartDragDismiss(x + location[0], y + location[1]) == true
    }

    override fun onDragDismissStarted() {
      // 弹回途中又抓住：从它现在的位置接着拖
      dragSpring?.cancel()
      dragBaseOffset = dragOffset
      previewFragment()?.onDragDismissStarted()
      findViewById<View>(R.id.fragment_container_view).background?.mutate()
    }

    override fun onDragDismissMoved(offset: Float) {
      applyDragOffset(dragBaseOffset + offset)
    }

    override fun onDragDismissReleased(offset: Float, velocity: Float) {
      val total = dragBaseOffset + offset
      dragBaseOffset = 0f
      if (TellomiDragDismiss.shouldDismiss(total, velocity, resources.displayMetrics.density)) {
        dismissByDrag(total, velocity)
      } else {
        springBack(total, velocity)
      }
    }
  }

  /**
   * Applies the desired window color mode. This activity is the sole writer of [android.view.Window.setColorMode].
   *
   * The API 35 floor is [android.view.Window.setDesiredHdrHeadroom], and matches the one
   * [org.thoughtcrime.securesms.components.subsampling.UltraHdrSupport.isEligible] applies at decode time.
   */
  private fun applyWindowColorMode(wantHdr: Boolean) {
    if (Build.VERSION.SDK_INT < 35) {
      return
    }

    val target = if (wantHdr && isWindowStarted && isDisplayHdrCapable()) {
      ActivityInfo.COLOR_MODE_HDR
    } else {
      ActivityInfo.COLOR_MODE_DEFAULT
    }

    if (window.colorMode == target) {
      return
    }

    Log.i(TAG, "Changing window color mode. hdr=${target == ActivityInfo.COLOR_MODE_HDR}")
    window.colorMode = target
    window.desiredHdrHeadroom = if (target == ActivityInfo.COLOR_MODE_HDR) MIXED_CONTENT_HDR_HEADROOM else 0f
  }

  /** The API 34 floor is [android.view.Display.isHdrSdrRatioAvailable]'s own, not the preview's. */
  @RequiresApi(34)
  private fun isDisplayHdrCapable(): Boolean {
    return try {
      display?.isHdrSdrRatioAvailable == true
    } catch (e: Exception) {
      Log.w(TAG, "Unable to query display HDR capability.", e)
      false
    }
  }

  companion object {
    private val TAG = Log.tag(MediaPreviewActivity::class)

    private const val FRAGMENT_TAG = "media_preview_fragment"
    const val SHARED_ELEMENT_TRANSITION_NAME = "thumb"

    /** Google's published "mixed content, mostly HDR" headroom. The preview keeps SDR chrome in the same window. */
    private const val MIXED_CONTENT_HDR_HEADROOM = 3f

    /** How long the page selection must stay put before we pay for a color mode change. */
    private const val COLOR_MODE_SETTLE_DELAY_MS = 200L

    /** Tellomi (A-20): flying the dragged media off screen when there is no thumbnail to return to. */
    private const val FLY_OUT_DURATION_MS = 200L

    /** Tellomi (A-20): settling back after a cancelled back gesture. */
    private const val BACK_SETTLE_DURATION_MS = 200L
  }
}
