/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Previews
import org.signal.glide.compose.GlideImage
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import java.util.Locale
import org.signal.core.ui.R as CoreUiR

private val AVATAR_SIZE = 36.dp
private val CONTENT_START = 16.dp + AVATAR_SIZE + 12.dp

/** 点过赞的红心颜色。 */
private val LIKED_COLOR = Color(0xFFE5484D)

private const val ARTICLE_PREVIEW_LINES = 4

/**
 * 动态里的一条帖子（`kind = post`），照 Threads 的单栏排法（`dynamic-feed.md` 第三节第 2、3 条）：
 * 左边头像，右边名字、正文、图片、操作行。
 *
 * 文字、话题、IP 属地、「已编辑」、AI 生成标记、图片、点赞、评论（点了进详情）、「···」。
 * 转发、分享随后面的 PR 加，现在不画这两个按钮，免得点了没反应。
 */
@Composable
fun FeedPostCard(
  card: FeedCard,
  showIpRegion: Boolean,
  onLikeClick: (FeedCard) -> Unit,
  onNotInterestedClick: ((FeedCard) -> Unit)?,
  modifier: Modifier = Modifier,
  mediaBaseUrl: String? = null,
  /** 自己的帖子：「···」里的编辑（null = 已过 15 分钟或不能改）和删除（第三节第 26 条）。 */
  onEditClick: ((FeedCard) -> Unit)? = null,
  onDeleteClick: ((FeedCard) -> Unit)? = null,
  /** 点卡片 / 评论按钮进详情；详情页里自己是 null。 */
  onOpen: ((FeedCard) -> Unit)? = null,
  /** 详情页：长文不截断。 */
  expanded: Boolean = false,
  /** 别人的帖子：「···」里的举报（第三节第 32 条）。 */
  onReportClick: ((FeedCard) -> Unit)? = null,
  /** 点头像或名字进主页；开放内容的外部作者没有主页。 */
  onAuthorClick: ((FeedAuthor) -> Unit)? = null,
  /** 投票（选项下标）；null 的地方只看不投，点卡片进详情投。 */
  onVote: ((FeedCard, List<Int>) -> Unit)? = null,
  /** 别人的帖子：「···」里的屏蔽作者（第三节第 26 条）；开放内容的外部作者也能屏蔽。 */
  onBlockAuthorClick: ((FeedCard) -> Unit)? = null,
  /** 自己的帖子：「···」里的置顶、隐藏点赞数、查看数据（第三节第 26 条）。 */
  onOwnPostAction: ((FeedCard, FeedOwnPostAction) -> Unit)? = null,
  /** 别人的帖子：「···」里的「不看 TA 的动态」（第三节第 26 条）；开放内容的外部作者没有。 */
  onMuteAuthorClick: ((FeedCard) -> Unit)? = null
) {
  val author = card.author
  val openAuthor: (() -> Unit)? = if (author != null && author.isNative && onAuthorClick != null) {
    { onAuthorClick(author) }
  } else {
    null
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .then(if (onOpen != null) Modifier.clickable { onOpen(card) } else Modifier)
      .padding(top = 12.dp, bottom = 4.dp)
  ) {
    Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
      FeedAvatar(author = card.author, modifier = if (openAuthor != null) Modifier.clip(CircleShape).clickable(onClick = openAuthor) else Modifier)

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        FeedPostHeader(
          card = card,
          showIpRegion = showIpRegion,
          onNotInterestedClick = onNotInterestedClick,
          onEditClick = onEditClick,
          onDeleteClick = onDeleteClick,
          onReportClick = onReportClick,
          onAuthorClick = openAuthor,
          onBlockAuthorClick = onBlockAuthorClick,
          onOwnPostAction = onOwnPostAction,
          onMuteAuthorClick = onMuteAuthorClick
        )

        val title = card.title
        if (!title.isNullOrBlank()) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 12.dp, bottom = 2.dp)
          )
        }

        // 长文在信息流里是卡片（第三节第 25 条）：只露开头几行，点开是阅读页（阅读页在后面的 PR）
        val text = if (card.kind == FeedCard.KIND_ARTICLE && !expanded) card.summary ?: card.text else card.text ?: card.summary
        if (!text.isNullOrBlank()) {
          Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (card.kind == FeedCard.KIND_ARTICLE && !expanded) ARTICLE_PREVIEW_LINES else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp)
          )
        }

        val poll = card.poll
        if (poll != null && poll.options.isNotEmpty()) {
          FeedPollView(
            poll = poll,
            state = card.pollState,
            onVote = onVote?.let { vote -> { options: List<Int> -> vote(card, options) } },
            modifier = Modifier.padding(top = 8.dp, end = 12.dp)
          )
        }

        if (card.topics.isNotEmpty()) {
          Text(
            text = card.topics.joinToString(separator = "  ") { "#${it.label}" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, end = 12.dp)
          )
        }

        if (card.aiGenerated) {
          // 文字和样式等法律复核（dynamic-feed.md 第五节 Q-7），先用占位文案
          Text(
            text = stringResource(R.string.TellomiFeed__ai_generated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }
    }

    if (card.media.isNotEmpty()) {
      FeedMediaRow(media = card.media, mediaBaseUrl = mediaBaseUrl, modifier = Modifier.padding(top = 8.dp))
    }

    FeedActionRow(
      card = card,
      onLikeClick = onLikeClick,
      onCommentClick = onOpen,
      modifier = Modifier.padding(start = CONTENT_START - 12.dp, top = 2.dp)
    )
  }
}

@Composable
internal fun FeedAvatar(author: FeedAuthor?, size: Dp = AVATAR_SIZE, modifier: Modifier = Modifier) {
  val initial = author?.displayName?.trim()?.firstOrNull()?.toString().orEmpty()
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Text(
      text = initial,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun FeedPostHeader(
  card: FeedCard,
  showIpRegion: Boolean,
  onNotInterestedClick: ((FeedCard) -> Unit)?,
  onEditClick: ((FeedCard) -> Unit)?,
  onDeleteClick: ((FeedCard) -> Unit)?,
  onReportClick: ((FeedCard) -> Unit)?,
  onAuthorClick: (() -> Unit)? = null,
  onBlockAuthorClick: ((FeedCard) -> Unit)? = null,
  onOwnPostAction: ((FeedCard, FeedOwnPostAction) -> Unit)? = null,
  onMuteAuthorClick: ((FeedCard) -> Unit)? = null
) {
  val context = LocalContext.current
  val author = card.author
  var menuOpen by remember { mutableStateOf(false) }

  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = author?.displayName.orEmpty(),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .weight(1f, fill = false)
            .then(if (onAuthorClick != null) Modifier.clickable(onClick = onAuthorClick) else Modifier)
        )

        val published = remember(card.publishedAt) { card.publishedAt?.let { parseInstantMillis(it) } }
        if (published != null) {
          Text(
            text = "  " + DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), published).first,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
          )
        }
      }

      val subtitle = buildSubtitle(card = card, showIpRegion = showIpRegion)
      if (subtitle.isNotEmpty()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    val isAuthor = card.viewer.isAuthor
    val ownActions = if (isAuthor && onOwnPostAction != null) FeedOwnPostAction.available(card) else emptyList()
    if ((!isAuthor && (onNotInterestedClick != null || onReportClick != null)) || (isAuthor && (onEditClick != null || onDeleteClick != null || ownActions.isNotEmpty()))) {
      Box {
        val moreLabel = stringResource(R.string.TellomiFeed__more)
        Icon(
          imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_more_vertical_24),
          contentDescription = moreLabel,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .clip(CircleShape)
            .clickable { menuOpen = true }
            .padding(10.dp)
            .size(20.dp)
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          if (isAuthor) {
            // 顺序照需求：编辑 / 置顶 / 隐藏点赞数 / 查看数据 / 删除
            if (onEditClick != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeedMine__edit)) },
                onClick = {
                  menuOpen = false
                  onEditClick(card)
                }
              )
            }
            for (action in ownActions) {
              DropdownMenuItem(
                text = { Text(stringResource(action.label)) },
                modifier = Modifier.testTag(FeedPostCardTestTags.ownAction(action)),
                onClick = {
                  menuOpen = false
                  onOwnPostAction?.invoke(card, action)
                }
              )
            }
            if (onDeleteClick != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeedMine__delete)) },
                onClick = {
                  menuOpen = false
                  onDeleteClick(card)
                }
              )
            }
          } else {
            if (onNotInterestedClick != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeed__not_interested)) },
                onClick = {
                  menuOpen = false
                  onNotInterestedClick(card)
                }
              )
            }
            if (onMuteAuthorClick != null && author != null && author.isNative) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeed__mute_author)) },
                modifier = Modifier.testTag(FeedPostCardTestTags.MUTE_AUTHOR),
                onClick = {
                  menuOpen = false
                  onMuteAuthorClick(card)
                }
              )
            }
            if (onBlockAuthorClick != null && author != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeedPage__block)) },
                onClick = {
                  menuOpen = false
                  onBlockAuthorClick(card)
                }
              )
            }
            if (onReportClick != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.TellomiFeedReport__title)) },
                onClick = {
                  menuOpen = false
                  onReportClick(card)
                }
              )
            }
          }
        }
      }
    }
  }
}

/** 「@handle · IP 属地：四川 · 已编辑」；没设 ID 的人显示「Tellomi 号：xxxx」（第三节第 8 条）。 */
@Composable
private fun buildSubtitle(card: FeedCard, showIpRegion: Boolean): String {
  val author = card.author
  val parts = buildList {
    when {
      !author?.handle.isNullOrBlank() -> add("@${author!!.handle}")
      !author?.tellomiNumber.isNullOrBlank() -> add(stringResource(R.string.TellomiFeed__tellomi_number, author!!.tellomiNumber!!))
    }
    val ip = card.ipRegionLabel
    if (showIpRegion && !ip.isNullOrBlank()) {
      add(stringResource(R.string.TellomiFeed__ip_region, ip))
    }
    if (card.edited) {
      add(stringResource(R.string.TellomiFeed__edited))
    }
    if (card.pinned) {
      add(stringResource(R.string.TellomiFeed__pinned))
    }
  }
  return parts.joinToString(separator = " · ")
}

/**
 * 多图横滑，可以拖到最左、不做整张吸附（`10-feed-prototype-log.md` 第 28 行）。第一张和正文左对齐。
 * 只有一张时按原图比例占满宽度（比例限制在 3:4 到 16:9 之间）。
 */
@Composable
private fun FeedMediaRow(media: List<FeedMedia>, mediaBaseUrl: String?, modifier: Modifier = Modifier) {
  if (media.size == 1) {
    val item = media.first()
    Box(modifier = modifier.padding(start = CONTENT_START, end = 16.dp)) {
      FeedMediaPlaceholder(item = item, mediaBaseUrl = mediaBaseUrl, modifier = Modifier.fillMaxWidth().aspectRatio(clampedRatio(item)))
    }
    return
  }

  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = CONTENT_START, end = 16.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    itemsIndexed(media) { _, item ->
      FeedMediaPlaceholder(item = item, mediaBaseUrl = mediaBaseUrl, modifier = Modifier.height(220.dp).aspectRatio(clampedRatio(item)))
    }
  }
}

@Composable
private fun FeedMediaPlaceholder(item: FeedMedia, mediaBaseUrl: String?, modifier: Modifier = Modifier) {
  val description = item.alt.ifBlank { stringResource(R.string.TellomiFeed__image) }
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .semantics { contentDescription = description }
  ) {
    // 本机样例里自己刚发的图是本地 Uri；内容引擎的图是 config.media_base_url + key（经 Glide 的 OkHttp，走内容代理）
    val model: Any? = item.localUriOrNull() ?: item.remoteUrlOrNull(mediaBaseUrl)
    if (model != null) {
      GlideImage(model = model, modifier = Modifier.matchParentSize())
    }
  }
}

/** 样例来源把本机图片的 content Uri 当 key 用；内容引擎给的 key 不会是这个形式。 */
internal fun FeedMedia.localUriOrNull(): Uri? {
  return if (key.startsWith("content://") || key.startsWith("file://")) Uri.parse(key) else null
}

/**
 * 引擎的媒体 key 拼到 `media_base_url`（契约 3.2，引擎保证以 `/` 结尾）后面。只认 https 的基址；
 * key 像完整地址、以 `/` 开头或带 `..` 的不拼，免得一张卡片把图片请求带到别的主机或路径上。
 */
internal fun FeedMedia.remoteUrlOrNull(mediaBaseUrl: String?): String? {
  if (mediaBaseUrl == null || !mediaBaseUrl.startsWith("https://") || !mediaBaseUrl.endsWith("/")) return null
  if (key.isBlank() || key.contains("://") || key.startsWith("/") || key.split('/').any { it == ".." }) return null
  return mediaBaseUrl + key
}

@Composable
private fun FeedActionRow(
  card: FeedCard,
  onLikeClick: (FeedCard) -> Unit,
  onCommentClick: ((FeedCard) -> Unit)?,
  modifier: Modifier = Modifier
) {
  val liked = card.viewer.liked
  val likeLabel = if (liked) stringResource(R.string.TellomiFeed__unlike) else stringResource(R.string.TellomiFeed__like)

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clip(RoundedCornerShape(50))
        .clickable { onLikeClick(card) }
        .semantics { contentDescription = likeLabel }
        .heightIn(min = 40.dp)
        .padding(horizontal = 12.dp)
    ) {
      Icon(
        imageVector = ImageVector.vectorResource(if (liked) R.drawable.tellomi_heart_fill_24 else R.drawable.symbol_heart_24),
        contentDescription = null,
        tint = if (liked) LIKED_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )

      // 隐藏了点赞数：别人拿到的是 0；作者自己照常看到数字
      if ((!card.stats.likeCountHidden || card.viewer.isAuthor) && card.stats.likes > 0) {
        Text(
          text = card.stats.likes.toString(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp)
        )
      }
    }

    // 信息流里评论按钮一直在（点了进详情）；详情页里只显示数
    if (onCommentClick != null || card.stats.comments > 0) {
      val commentLabel = stringResource(R.string.TellomiFeedDetail__comment)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clip(RoundedCornerShape(50))
          .then(if (onCommentClick != null) Modifier.clickable(onClickLabel = commentLabel) { onCommentClick(card) } else Modifier)
          .heightIn(min = 40.dp)
          .padding(horizontal = 12.dp)
      ) {
        Icon(
          imageVector = ImageVector.vectorResource(R.drawable.symbol_chat_24),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp)
        )
        if (card.stats.comments > 0) {
          Text(
            text = pluralStringResource(R.plurals.TellomiFeed__comments, card.stats.comments, card.stats.comments),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
          )
        }
      }
    }

    if (card.shareable) {
      FeedShareButton(card)
    }
  }
}

object FeedPostCardTestTags {
  const val SHARE = "feed_card_share"
  const val COPY_LINK = "feed_card_copy_link"
  const val MUTE_AUTHOR = "feed_card_mute_author"

  fun ownAction(action: FeedOwnPostAction) = "feed_card_own_${action.name.lowercase()}"
}

/**
 * 「分享」：复制链接、分享到其他 App（第三节第 26 条的后两项；发到聊天、转发、引用、快拍还没做）。
 * 只有 [FeedCard.shareable] 才画：私密账号和非公开动态没有外链（第 29 条）。
 */
@Composable
private fun FeedShareButton(card: FeedCard) {
  val context = LocalContext.current
  var open by remember { mutableStateOf(false) }
  val shareLabel = stringResource(R.string.TellomiFeed__share)
  Box {
    Icon(
      imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_share_android_24),
      contentDescription = shareLabel,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .clip(CircleShape)
        .clickable { open = true }
        .testTag(FeedPostCardTestTags.SHARE)
        .padding(10.dp)
        .size(20.dp)
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeed__copy_link)) },
        onClick = {
          open = false
          FeedShare.copyLink(context, card)
        },
        modifier = Modifier.testTag(FeedPostCardTestTags.COPY_LINK)
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.TellomiFeed__share_other_apps)) },
        onClick = {
          open = false
          FeedShare.shareToApps(context, card)
        }
      )
    }
  }
}

/** 图片比例限制在 3:4（竖）到 16:9（横）之间，免得一张极长的图把整屏占掉。 */
internal fun clampedRatio(item: FeedMedia): Float {
  if (item.w <= 0 || item.h <= 0) return 1f
  return (item.w.toFloat() / item.h.toFloat()).coerceIn(3f / 4f, 16f / 9f)
}

internal fun parseInstantMillis(value: String): Long? {
  return try {
    Instant.parse(value).toEpochMilli()
  } catch (e: Exception) {
    null
  }
}

@Preview
@Composable
private fun FeedPostCardPreview() {
  Previews.Preview {
    FeedPostCard(
      card = FeedCard(
        id = "p",
        kind = FeedCard.KIND_POST,
        publishedAt = Instant.now().minusSeconds(600).toString(),
        author = FeedAuthor(profileId = "a", displayName = "山风", handle = "shanfeng"),
        text = "今天的云很低，像是伸手就能摸到。",
        media = listOf(FeedMedia(key = "a", w = 1080, h = 1440), FeedMedia(key = "b", w = 1080, h = 1080)),
        ipRegionLabel = "四川",
        stats = FeedStats(likes = 12, comments = 3)
      ),
      showIpRegion = true,
      onLikeClick = {},
      onNotInterestedClick = {}
    )
  }
}
