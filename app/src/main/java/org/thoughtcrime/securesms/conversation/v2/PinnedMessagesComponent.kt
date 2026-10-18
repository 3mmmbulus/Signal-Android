package org.thoughtcrime.securesms.conversation.v2

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnPreDraw
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.fonts.SignalSymbols
import org.signal.core.ui.fonts.SignalSymbols.getSpannedString
import org.signal.glide.compose.GlideImage
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.emoji.EmojiTextView
import org.thoughtcrime.securesms.contactshare.ContactUtil
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.StickerSlide
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.hasSharedContact
import org.thoughtcrime.securesms.util.hasSticker
import org.thoughtcrime.securesms.util.isPoll
import org.thoughtcrime.securesms.util.isViewOnceMessage
import org.whispersystems.signalservice.api.payments.FormatterOptions
import kotlin.jvm.optionals.getOrDefault
import org.signal.core.ui.R as CoreUiR

/**
 * Displays pinned messages banner on conversation fragment
 *
 * Tellomi (pinned-messages.md 3.4, as in both Telegram apps — see [TellomiPinnedBar]): pins in message order, oldest first;
 * [selectedIndex] is the one shown (it follows the chat as it scrolls). The title says "Pinned message", "Previous message" or
 * "Pinned message #N"; the line on the left shows at most three segments. Tapping jumps to the pin shown ([onTap]); long-press
 * opens all of them. On the right: the list of pins when there are several, × ([onClose]) when there is one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedMessagesBanner(
  messages: List<ConversationMessage> = emptyList(),
  selectedIndex: Int,
  hasWallpaper: Boolean,
  closeUnpins: Boolean,
  onTap: (Long) -> Unit = {},
  onClose: (Long) -> Unit = {},
  onViewAllMessages: () -> Unit = {}
) {
  if (messages.isEmpty()) {
    return
  }
  val index = selectedIndex.coerceIn(0, messages.size - 1)
  val message = messages[index].messageRecord as MmsMessageRecord

  Column(
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(color = if (DynamicTheme.isDarkTheme(LocalContext.current)) Color(0XFF4A4C52) else Color(0XFFCCCFD5))
    ) {}
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .background(if (hasWallpaper) colorResource(R.color.conversation_toolbar_color_wallpaper_scrolled) else SignalTheme.colors.colorSurface2)
        .combinedClickable(
          onClick = { onTap(message.id) },
          onLongClick = onViewAllMessages,
          onClickLabel = stringResource(R.string.PinnedMessage__go_to_message),
          onLongClickLabel = stringResource(R.string.PinnedMessage__view_all_messages)
        )
        .height(IntrinsicSize.Min)
    ) {
      if (messages.size > 1) {
        TellomiPinnedLine(index, messages.size)
      } else {
        Spacer(modifier = Modifier.size(16.dp))
      }

      AnimatedContent(
        modifier = Modifier.weight(1f),
        targetState = index,
        transitionSpec = {
          // Telegram: an older pin comes down from above, a newer one up from below.
          val older = targetState < initialState
          (slideInVertically(tween(TELLOMI_SWAP_MS)) { if (older) -it / 3 else it / 3 } + fadeIn(tween(TELLOMI_SWAP_MS))) togetherWith
            (slideOutVertically(tween(TELLOMI_SWAP_MS)) { if (older) it / 3 else -it / 3 } + fadeOut(tween(TELLOMI_SWAP_MS)))
        }
      ) { shownIndex ->
        val conversationMessage = messages[shownIndex.coerceIn(0, messages.size - 1)]
        val shown = conversationMessage.messageRecord as MmsMessageRecord
        val (glyph, body, showThumbnail) = getMessageMetadata(conversationMessage)

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(vertical = 7.dp)
        ) {
          if (showThumbnail &&
            !shown.hasSticker() &&
            shown.slideDeck.firstSlide?.uri != null &&
            !shown.slideDeck.firstSlide!!.isVideoGif
          ) {
            val firstSlide = shown.slideDeck.firstSlide!!
            if (firstSlide.asAttachment().transformProperties?.tellomiSpoiler == true) {
              // Tellomi (tellomi/tellomi#1263, spec R-1): like a quote, the pinned bar never shows a covered image / video,
              // uncovered here or not (Telegram: the pinned panel's thumbnail is blurred for spoilers). Its blur hash
              // gives nothing away; without one, a plain tile.
              Box(
                modifier = Modifier
                  .padding(end = 8.dp)
                  .size(32.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(Color(0xFF3B3B3B))
              ) {
                firstSlide.placeholderBlur?.let { blurHash ->
                  GlideImage(
                    model = blurHash,
                    modifier = Modifier.fillMaxSize()
                  )
                }
              }
            } else {
              GlideImage(
                model = DecryptableUri(firstSlide.uri!!),
                modifier = Modifier
                  .padding(end = 8.dp)
                  .size(32.dp)
                  .clip(RoundedCornerShape(10.dp))
              )
            }
          }

          Column(
            modifier = Modifier
              .weight(1f)
          ) {
            Text(
              text = tellomiPinnedTitle(shownIndex, messages.size),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.bodySmall
            )

            val displayBody = if (glyph != null) {
              SpannableStringBuilder()
                .append(getSpannedString(LocalContext.current, SignalSymbols.Weight.REGULAR, glyph, -1))
                .append(" ")
                .append(body)
            } else {
              body
            }

            AndroidView(
              factory = ::EmojiTextView
            ) { view ->
              view.enableRenderSpoilers()
              view.text = displayBody
              view.ellipsize = TextUtils.TruncateAt.END
              view.maxLines = 1
              view.doOnPreDraw {
                (it as EmojiTextView).ellipsizeEmojiTextForMaxLines()
              }
            }
          }
        }
      }

      Box(modifier = Modifier.padding(vertical = 7.dp, horizontal = 8.dp).padding(end = 8.dp)) {
        if (messages.size > 1) {
          Icon(
            imageVector = ImageVector.vectorResource(R.drawable.symbol_list_bullet_24),
            contentDescription = stringResource(R.string.PinnedMessage__view_all_messages),
            modifier = Modifier
              .clickable(onClick = onViewAllMessages)
              .padding(vertical = 8.dp),
            tint = MaterialTheme.colorScheme.onSurface
          )
        } else {
          Icon(
            imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_x_24),
            contentDescription = stringResource(if (closeUnpins) R.string.PinnedMessage__unpin_message else R.string.TellomiPinnedBar__hide),
            modifier = Modifier
              .clickable { onClose(message.id) }
              .padding(vertical = 8.dp),
            tint = MaterialTheme.colorScheme.onSurface
          )
        }
      }
    }
  }
}

private const val TELLOMI_SWAP_MS = 250

@Composable
private fun tellomiPinnedTitle(index: Int, size: Int): String {
  val title = TellomiPinnedBar.title(index, size)
  return when (title.kind) {
    TellomiPinnedBar.TitleKind.PINNED -> stringResource(R.string.TellomiPinnedBar__pinned_message)
    TellomiPinnedBar.TitleKind.PREVIOUS -> stringResource(R.string.TellomiPinnedBar__previous_message)
    TellomiPinnedBar.TitleKind.NUMBERED -> stringResource(R.string.TellomiPinnedBar__pinned_message_n, title.number)
  }
}

/**
 * Tellomi (pinned-messages.md 3.4): one segment per pin, oldest at the top, the shown one solid. More than three show three,
 * fading out where there are more (Telegram Android `PinnedLineView`).
 */
@Composable
private fun TellomiPinnedLine(index: Int, size: Int) {
  val window = TellomiPinnedBar.segments(index, size)
  val selected = MaterialTheme.colorScheme.onSurface
  val other = if (DynamicTheme.isDarkTheme(LocalContext.current)) MaterialTheme.colorScheme.secondaryContainer else SignalTheme.colors.colorTransparentInverse2
  val fade = Brush.verticalGradient(
    0f to if (window.fadeTop) Color.Transparent else Color.Black,
    0.25f to Color.Black,
    0.75f to Color.Black,
    1f to if (window.fadeBottom) Color.Transparent else Color.Black
  )

  Column(
    modifier = Modifier
      .fillMaxHeight()
      .padding(vertical = 8.dp)
      .padding(horizontal = 7.dp)
      .width(2.dp)
      .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
      .drawWithContent {
        drawContent()
        drawRect(brush = fade, blendMode = BlendMode.DstIn)
      }
  ) {
    for (i in window.first until window.first + window.count) {
      val color by animateColorAsState(targetValue = if (i == index) selected else other, animationSpec = tween(TELLOMI_SWAP_MS), label = "pinSegment")
      Box(
        modifier = Modifier
          .padding(vertical = 2.dp)
          .fillMaxWidth()
          .weight(1f)
          .background(color = color, shape = RoundedCornerShape(16.dp))
      )
    }
  }
}

/**
 * Given the type of message, returns the associated glyph, body, and whether or not a thumbnail should be rendered with it
 */
@Composable
fun getMessageMetadata(conversationMessage: ConversationMessage): Triple<SignalSymbols.Glyph?, SpannableString, Boolean> {
  val context = LocalContext.current
  val message = conversationMessage.messageRecord as MmsMessageRecord
  val slide = message.slideDeck.firstSlide
  return if (slide is StickerSlide) {
    Triple(SignalSymbols.Glyph.STICKER, SpannableString(stringResource(R.string.PinnedMessage__sticker)), false)
  } else if (slide is AudioSlide) {
    Triple(SignalSymbols.Glyph.AUDIO, SpannableString(stringResource(R.string.PinnedMessage__voice)), false)
  } else if (slide is DocumentSlide) {
    Triple(SignalSymbols.Glyph.FILE, SpannableString(slide.fileName.getOrDefault(stringResource(R.string.DocumentView_unnamed_file))), false)
  } else if (message.isViewOnceMessage()) {
    Triple(SignalSymbols.Glyph.VIEW_ONCE, SpannableString(stringResource(R.string.PinnedMessage__view_once)), false)
  } else if (message.isPoll()) {
    Triple(SignalSymbols.Glyph.POLL, SpannableString(stringResource(R.string.Poll__poll_question, message.body)), false)
  } else if (message.hasSharedContact()) {
    Triple(SignalSymbols.Glyph.PERSON_CIRCLE, SpannableString(ContactUtil.getDisplayName(message.sharedContacts.first())), false)
  } else if (message.isPaymentNotification && message.payment != null) {
    Triple(SignalSymbols.Glyph.CREDIT_CARD, SpannableString(message.payment!!.amount.toString(FormatterOptions.defaults())), false)
  } else if (slide?.isVideoGif == true) {
    Triple(SignalSymbols.Glyph.GIF_RECTANGLE, SpannableString(stringResource(R.string.PinnedMessage__gif)), false)
  } else if (slide is ImageSlide && message.body.isEmpty()) {
    Triple(null, SpannableString(stringResource(R.string.PinnedMessage__photo)), true)
  } else if (slide is VideoSlide && message.body.isEmpty()) {
    Triple(null, SpannableString(stringResource(R.string.PinnedMessage__video)), true)
  } else {
    Triple(null, conversationMessage.getDisplayBody(context), true)
  }
}
