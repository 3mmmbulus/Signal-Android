/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v2

import android.net.Uri
import org.signal.core.models.media.Media
import org.signal.mediasend.TellomiMotionPhoto
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.TellomiLivePhotoUploadJob
import org.thoughtcrime.securesms.sms.MessageSender.PreUploadResult
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：选图里开着实况的动态照片，发的时候多一次上传（照片里的视频）。
 *
 * 每张排一个 [TellomiLivePhotoUploadJob]，加进这张的预上传结果的任务列表：会话页发消息时发送任务等它（同一批预上传结果发给
 * 几个会话时，复制附件的任务也等它，复制出去的附件带着指针）。没走预上传的（定时发送）按静态图发。
 */
object TellomiLivePhotos {

  /** 原文件和视频在里面的哪一段。 */
  data class Source(val uri: Uri, val range: TellomiMotionPhoto.VideoRange)

  /** 发出去那一份的 URI（编辑过、压过画质的换成了新 URI）→ 原文件和视频那一段。只认静态图。 */
  fun renderedSources(selected: List<Media>, oldToNew: Map<Media, Media>, liveVideos: Map<Uri, TellomiMotionPhoto.VideoRange>): Map<Uri, Source> {
    if (liveVideos.isEmpty()) {
      return emptyMap()
    }
    return selected.mapNotNull { media ->
      val range = liveVideos[media.uri] ?: return@mapNotNull null
      val sent = oldToNew[media] ?: media
      if (MediaUtil.isImageType(sent.contentType)) sent.uri to Source(media.uri, range) else null
    }.toMap()
  }

  /** 预上传结果里开着实况的那几张各排一个上传任务，结果里带上它的任务 id（发送任务等它）。 */
  fun withUploadJobs(
    results: Collection<PreUploadResult>,
    sources: Map<Uri, Source>,
    enqueue: (Job) -> Unit = { AppDependencies.jobManager.add(it) }
  ): List<PreUploadResult> {
    if (sources.isEmpty()) {
      return results.toList()
    }
    return results.map { result ->
      val source = sources[result.media.uri] ?: return@map result
      val job = TellomiLivePhotoUploadJob(result.attachmentId, source.uri, source.range)
      enqueue(job)
      PreUploadResult(result.media, result.attachmentId, result.jobIds + job.id)
    }
  }
}
