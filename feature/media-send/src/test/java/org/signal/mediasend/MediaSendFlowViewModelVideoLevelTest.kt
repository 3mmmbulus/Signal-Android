/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.mediasend.preupload.PreUploadController
import org.signal.mediasend.screens.edit.MediaEditState
import org.signal.mediasend.screens.edit.focusedVideoLevelOptions
import org.signal.mediasend.screens.edit.videoLevelOptions
import org.thoughtcrime.securesms.video.TellomiVideoQuality
import org.thoughtcrime.securesms.video.videoconverter.utils.VideoConstants
import kotlin.time.Duration.Companion.seconds

/**
 * Tellomi（tellomi/tellomi#1518 V-7 ②，照 Telegram 两端每个视频各选画质）：选了档的视频按这一档转码、按这一档能装下的时长剪，
 * 换整批画质不动它；选回整批那档就恢复上游的样子。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaSendFlowViewModelVideoLevelTest {

  @get:Rule
  val dependencies = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val mainDispatcher = StandardTestDispatcher()
  private val repository = dependencies.mediaSendRepository
  private val preUploadController: PreUploadController = mockk(relaxed = true)

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
    every { repository.getMediaConstraints(any()) } returns PreviewMediaConstraints
    coEvery { repository.getStorySendRequirements(any()) } returns emptyMap()
    coEvery { repository.validateAndFilterMedia(any(), any(), any()) } coAnswers {
      MediaFilterResult(filteredMedia = firstArg(), error = null)
    }
    for (each in listOf(repository, MediaSendDependencies.mediaSendRepository)) {
      every { each.tellomiVideoMaxUploadBytes } returns LIMIT
      every { each.isVideoTranscodeAvailable() } returns true
      every { each.getMaxVideoDurationUs(any(), any()) } returns BATCH_MAX.inWholeMicroseconds
      every { each.getVideoTranscodingTiers(any()) } returns listOf(VideoConstants.DEFAULT_LVL1_STANDARD)
    }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `Given a 60 s 1080p video, when 1080p is picked, then it keeps its length and remembers the level`() = runTest {
    val viewModel = viewModel(video(durationSeconds = 60))
    advanceUntilIdle()

    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(1080))

    val trim = trimOf(viewModel)
    assertThat(trim.videoTrimData.tellomiShortEdge).isEqualTo(1080)
    assertThat(trim.videoTrimData.getDuration()).isEqualTo(60.seconds)
    assertThat(trim.maxDurationUs).isEqualTo(MAX_1080.inWholeMicroseconds)
    verify { preUploadController.cancelUpload(any<Media>()) }
  }

  @Test
  fun `Given a 200 s video, when 1080p is picked, then it is trimmed to what 1080p fits, and the batch quality leaves it alone`() = runTest {
    val viewModel = viewModel(video(durationSeconds = 200))
    advanceUntilIdle()

    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(1080))
    assertThat(trimOf(viewModel).videoTrimData.getDuration()).isEqualTo(MAX_1080)
    assertThat(trimOf(viewModel).videoTrimData.isDurationEdited).isTrue()

    viewModel.setSentMediaQuality(SentMediaQuality.HIGH)
    assertThat(trimOf(viewModel).maxDurationUs).isEqualTo(MAX_1080.inWholeMicroseconds)

    // Back to the batch quality: the batch limit applies again.
    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(0))
    assertThat(trimOf(viewModel).videoTrimData.tellomiShortEdge).isEqualTo(0)
    assertThat(trimOf(viewModel).maxDurationUs).isEqualTo(BATCH_MAX.inWholeMicroseconds)
  }

  @Test
  fun `the edit screen offers the levels up to the source, with the batch one picked until another is`() = runTest {
    val viewModel = viewModel(video(durationSeconds = 60))
    advanceUntilIdle()
    val edit = MediaEditState(videoMaxUploadBytes = LIMIT).withParentState(viewModel.state.value)

    val options = edit.focusedVideoLevelOptions()
    assertThat(options.map { it.shortEdge }).isEqualTo(listOf(1080, 720, 480, 270))
    assertThat(options.single { it.isSelected }.shortEdge).isEqualTo(480)
    assertThat(options.single { it.shortEdge == 480 }.storedShortEdge).isEqualTo(0)
    assertThat(options.single { it.shortEdge == 720 }.storedShortEdge).isEqualTo(720)
    assertThat(options.map { it.estimatedBytes }.zipWithNext().all { (a, b) -> a > b }).isTrue()

    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(720))
    val picked = MediaEditState(videoMaxUploadBytes = LIMIT).withParentState(viewModel.state.value)
    assertThat(picked.focusedVideoLevelOptions().single { it.isSelected }.shortEdge).isEqualTo(720)
    assertThat(picked.focusedVideoTranscodingTiers.single().resolution).isEqualTo(720)
  }

  @Test
  fun `a video too small for two levels gets the upstream quality sheet`() {
    val options = videoLevelOptions(
      sourceShortEdge = 360,
      duration = 30.seconds,
      maxUploadBytes = LIMIT,
      batchTiers = listOf(VideoConstants.DEFAULT_LVL1_STANDARD),
      chosenShortEdge = 0
    )
    assertThat(options).isEmpty()
  }

  @Test
  fun `Given a device that cannot transcode, when a level is picked, then nothing changes`() = runTest {
    val viewModel = viewModel(video(durationSeconds = 60))
    advanceUntilIdle()
    every { repository.isVideoTranscodeAvailable() } returns false

    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(1080))

    assertThat(trimOf(viewModel).videoTrimData.tellomiShortEdge).isEqualTo(0)
  }

  @Test
  fun `a picked level stays in the sheet with the batch quality to go back to, even when it is the only one that fits`() {
    // 270p fits ~1037 s at 100 MiB; at 900 s nothing else does.
    val options = videoLevelOptions(
      sourceShortEdge = 1080,
      duration = 900.seconds,
      maxUploadBytes = LIMIT,
      batchTiers = listOf(VideoConstants.DEFAULT_LVL1_STANDARD),
      chosenShortEdge = 270
    )
    assertThat(options.map { it.shortEdge }).isEqualTo(listOf(480, 270))
    assertThat(options.single { it.isSelected }.shortEdge).isEqualTo(270)
    assertThat(options.single { it.shortEdge == 480 }.storedShortEdge).isEqualTo(0)
  }

  @Test
  fun `a batch quality that is not one of the levels is still there, picked`() {
    // A 540p source under a 720p batch tier: the levels stop at 480p, the batch one is offered as it is.
    val options = videoLevelOptions(
      sourceShortEdge = 540,
      duration = 30.seconds,
      maxUploadBytes = LIMIT,
      batchTiers = listOf(VideoConstants.DEFAULT_HIGH),
      chosenShortEdge = 0
    )
    assertThat(options.map { it.shortEdge }).isEqualTo(listOf(720, 480, 270))
    assertThat(options.single { it.isSelected }.shortEdge).isEqualTo(720)
    assertThat(options.single { it.shortEdge == 720 }.storedShortEdge).isEqualTo(0)
  }

  @Test
  fun `Given a level, when the video is muted, then it goes as a GIF at 480p and no level can be picked`() = runTest {
    val viewModel = viewModel(video(durationSeconds = 60))
    advanceUntilIdle()
    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(1080))

    viewModel.onEvent(MediaSendFlowEvent.ToggleVideoMuted)

    val muted = trimOf(viewModel)
    assertThat(muted.videoTrimData.isMuted).isTrue()
    assertThat(muted.videoTrimData.tellomiShortEdge).isEqualTo(0)
    assertThat(muted.maxDurationUs).isEqualTo(MAX_480.inWholeMicroseconds)

    viewModel.onEvent(MediaSendFlowEvent.SetVideoLevel(720))
    assertThat(trimOf(viewModel).videoTrimData.tellomiShortEdge).isEqualTo(0)

    val edit = MediaEditState(videoMaxUploadBytes = LIMIT).withParentState(viewModel.state.value)
    assertThat(edit.focusedVideoLevelOptions()).isEmpty()
    assertThat(edit.focusedVideoTranscodingTiers.single().resolution).isEqualTo(480)
  }

  @Test
  fun `Given a muted 20 minute video, then it is trimmed to what 480p fits`() = runTest {
    // The batch quality allows the whole 20 minutes here; 480p does not.
    for (each in listOf(repository, MediaSendDependencies.mediaSendRepository)) {
      every { each.getMaxVideoDurationUs(any(), any()) } returns 2000.seconds.inWholeMicroseconds
    }
    val viewModel = viewModel(video(durationSeconds = 1200))
    advanceUntilIdle()

    viewModel.onEvent(MediaSendFlowEvent.ToggleVideoMuted)

    assertThat(trimOf(viewModel).videoTrimData.getDuration()).isEqualTo(MAX_480)
  }

  private fun trimOf(viewModel: MediaSendFlowViewModel): EditorState.VideoTrim {
    return viewModel.state.value.editorStateMap[VIDEO_URI] as EditorState.VideoTrim
  }

  private fun viewModel(media: Media): MediaSendFlowViewModel {
    val savedStateHandle = SavedStateHandle(
      mapOf(
        MediaSendFlowViewModel.KEY_ARGS to MediaSendFlowActivityContract.Args(
          recipientId = MediaRecipientId(7L),
          mode = MediaSendFlowActivityContract.Mode.SingleRecipient,
          initialMedia = listOf(media)
        ),
        MediaSendFlowViewModel.KEY_IDENTITY_CHANGES_SINCE to 0L
      )
    )
    return MediaSendFlowViewModel(
      savedStateHandle = savedStateHandle,
      repository = repository,
      preUploadController = preUploadController,
      isMeteredFlow = flowOf(false)
    )
  }

  private companion object {
    private const val LIMIT = 100L * 1024 * 1024
    private val BATCH_MAX = 300.seconds
    private val MAX_1080 = TellomiVideoQuality.maxDurationSeconds(TellomiVideoQuality.level(1080)!!, LIMIT).seconds
    private val MAX_480 = TellomiVideoQuality.maxDurationSeconds(TellomiVideoQuality.level(480)!!, LIMIT).seconds
    private val VIDEO_URI = "content://media/video/1".toUri()

    private fun video(durationSeconds: Long) = Media(
      uri = VIDEO_URI,
      contentType = "video/mp4",
      date = 0,
      width = 1920,
      height = 1080,
      size = 50L * 1024 * 1024,
      duration = durationSeconds * 1000,
      isBorderless = false,
      isVideoGif = false,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      caption = null,
      transformProperties = null,
      fileName = null
    )
  }
}
