package org.thoughtcrime.securesms.conversation;

import org.thoughtcrime.securesms.util.TellomiCaptionAbove;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.SpannableString;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.util.Conversions;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.mention.MentionAnnotation;
import org.thoughtcrime.securesms.conversation.mutiselect.Multiselect;
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectCollection;
import org.thoughtcrime.securesms.conversation.v2.computed.FormattedDate;
import org.thoughtcrime.securesms.database.BodyRangeUtil;
import org.thoughtcrime.securesms.database.CollapsedState;
import org.thoughtcrime.securesms.database.CollapsibleEvents;
import org.thoughtcrime.securesms.database.MentionUtil;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.SharedContactPresentation;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList;
import org.thoughtcrime.securesms.groups.memberlabel.MemberLabel;
import org.thoughtcrime.securesms.groups.memberlabel.MemberLabelRepository;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard;
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyLocalLookup;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCard;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOnly;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.MessageRecordUtil;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A view level model used to pass arbitrary message related information needed
 * for various presentations.
 */
public class ConversationMessage {

  private static final String TAG = Log.tag(ConversationMessage.class);

  @NonNull  private final MessageRecord             messageRecord;
  @NonNull  private final List<Mention>             mentions;
  @Nullable private final SpannableString           body;
  @NonNull  private final MultiselectCollection     multiselectCollection;
  @NonNull  private final MessageStyler.Result      styleResult;
  @NonNull  private final Recipient                 threadRecipient;
            private final boolean                   hasBeenQuoted;
  @Nullable private final MessageRecord             originalMessage;
  @NonNull  private final ComputedProperties        computedProperties;
  @Nullable private final MemberLabel               memberLabel;
  @Nullable private final MemberLabel               quoteMemberLabel;
  @Nullable private final Recipient                 deletedByRecipient;
  @NonNull  private final SharedContactPresentation sharedContactPresentation;
            private final int                       collapsedSize;
            private final long                      collapsedExpirationInMs;

  private ConversationMessage(@NonNull MessageRecord messageRecord,
                              @Nullable CharSequence body,
                              @Nullable List<Mention> mentions,
                              boolean hasBeenQuoted,
                              @Nullable MessageStyler.Result styleResult,
                              @NonNull Recipient threadRecipient,
                              @Nullable MessageRecord originalMessage,
                              @NonNull ComputedProperties computedProperties,
                              @Nullable MemberLabel memberLabel,
                              @Nullable MemberLabel quoteMemberLabel,
                              @Nullable Recipient deletedByRecipient,
                              @Nullable SharedContactPresentation sharedContactPresentation,
                              int collapsedSize,
                              long collapsedExpirationInMs)
  {
    this.messageRecord             = messageRecord;
    this.hasBeenQuoted             = hasBeenQuoted;
    this.mentions                  = mentions != null ? mentions : Collections.emptyList();
    this.styleResult               = styleResult != null ? styleResult : MessageStyler.Result.none();
    this.threadRecipient           = threadRecipient;
    this.originalMessage           = originalMessage;
    this.computedProperties        = computedProperties;
    this.memberLabel               = memberLabel;
    this.quoteMemberLabel          = quoteMemberLabel;
    this.deletedByRecipient        = deletedByRecipient;
    this.sharedContactPresentation = sharedContactPresentation != null ? sharedContactPresentation : SharedContactPresentation.EMPTY;
    this.collapsedSize             = collapsedSize;
    this.collapsedExpirationInMs   = collapsedExpirationInMs;

    if (body != null) {
      this.body = SpannableString.valueOf(body);
    } else if (messageRecord.getMessageRanges() != null) {
      this.body = SpannableString.valueOf(messageRecord.getBody());
    } else {
      this.body = null;
    }

    if (!this.mentions.isEmpty() && this.body != null) {
      MentionAnnotation.setMentionAnnotations(this.body, this.mentions);
    }

    multiselectCollection = Multiselect.getParts(this);
  }

  public @NonNull MessageRecord getMessageRecord() {
    return messageRecord;
  }

  public @NonNull List<Mention> getMentions() {
    return mentions;
  }

  public @NonNull MultiselectCollection getMultiselectCollection() {
    return multiselectCollection;
  }

  public long getCollapsedExpirationInMs() {
    return collapsedExpirationInMs;
  }

  public boolean hasBeenQuoted() {
    return hasBeenQuoted;
  }

  public @NonNull ComputedProperties getComputedProperties() {
    return computedProperties;
  }

  public @NonNull SharedContactPresentation getSharedContactPresentation() {
    return sharedContactPresentation;
  }

  public @Nullable MemberLabel getMemberLabel() {
    return memberLabel;
  }

  public @Nullable MemberLabel getQuoteMemberLabel() {
    return quoteMemberLabel;
  }

  public @Nullable Recipient getDeletedByRecipient() {
    return deletedByRecipient;
  }

  public int getCollapsedSize() {
    return collapsedSize;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final ConversationMessage that = (ConversationMessage) o;
    return messageRecord.equals(that.messageRecord);
  }

  @Override
  public int hashCode() {
    return messageRecord.hashCode();
  }

  public long getUniqueId(@NonNull MessageDigest digest) {
    String unique = (messageRecord.isMms() ? "MMS::" : "SMS::") + messageRecord.getId();
    byte[] bytes  = digest.digest(unique.getBytes());

    return Conversions.byteArrayToLong(bytes);
  }

  public @NonNull SpannableString getDisplayBody(Context context) {
    return (body != null) ? new SpannableString(body) : messageRecord.getDisplayBody(context);
  }

  public boolean hasStyleLinks() {
    return styleResult.getHasStyleLinks();
  }

  public @Nullable BodyRangeList.BodyRange.Button getBottomButton() {
    return styleResult.getBottomButton();
  }

  public boolean isTextOnly(@NonNull Context context) {
    return MessageRecordUtil.isTextOnly(messageRecord, context) &&
           !hasBeenQuoted() &&
           getBottomButton() == null &&
           // Tellomi (card-visual §3.5): a link sent without a preview is drawn as a card, in the media layout.
           computedProperties.getLocalLinkPreview() == null;
  }

  public boolean isPoll() {
    return MessageRecordUtil.isPoll(messageRecord);
  }

  public long getConversationTimestamp() {
    if (originalMessage != null) {
      return originalMessage.getDateSent();
    }
    return messageRecord.getDateSent();
  }

  public MessageRecord getOriginalMessage() {
    if (originalMessage != null) {
      return originalMessage;
    }
    return messageRecord;
  }

  public boolean hasBeenScheduled() {
    return MessageRecordUtil.isScheduled(messageRecord);
  }

  @NonNull public Recipient getThreadRecipient() {
    return threadRecipient;
  }

  public boolean isActiveCollapsibleHead() {
    return collapsedSize > 1 && CollapsedState.isHead(messageRecord.getCollapsedState());
  }

  public boolean isActiveCollapsedHead() {
    return collapsedSize > 1 && messageRecord.getCollapsedState() == CollapsedState.HEAD_COLLAPSED;
  }

  public static @NonNull FormattedDate getFormattedDate(@NonNull Context context, @NonNull MessageRecord messageRecord) {
    if (MessageRecordUtil.isScheduled(messageRecord)) {
      String time = DateUtils.getOnlyTimeString(context, ((MmsMessageRecord) messageRecord).getScheduledDate());
      return new FormattedDate(false, false, time, time);
    } else {
      return DateUtils.getDatelessRelativeTimeSpanFormattedDate(context, Locale.getDefault(), messageRecord.getTimestamp());
    }
  }

  public static class ComputedProperties {
    private @NonNull FormattedDate formattedDate;

    /** Tellomi (ADR-0063 §5.1 rule 4, card-visual §3.5): what the bubble shows for the message's link. */
    private final @NonNull TellomiLinkOnly.Decision link;

    /** Tellomi (card-visual §5.2): what this device already has for the object of a first-party card, or null. */
    private final @Nullable TellomiFirstPartyCard.Local firstPartyLocal;

    /** Tellomi (card-visual §3.2 / §3.3): the shape of the card and the colours of its own image, from rust/links. */
    private final @NonNull TellomiLinkVisual.Visual visual;

    /** Tellomi (card-visual §3.3 / §7.3): the domain-only card this message's link gets while its conversation is a message request, or null. */
    private final @Nullable TellomiLinkCard requestLinkCard;

    ComputedProperties(@NonNull FormattedDate formattedDate) {
      this(formattedDate, TellomiLinkOnly.Decision.NONE, null, TellomiLinkVisual.Visual.NONE, null);
    }

    ComputedProperties(@NonNull FormattedDate formattedDate, @NonNull TellomiLinkOnly.Decision link, @Nullable TellomiFirstPartyCard.Local firstPartyLocal, @NonNull TellomiLinkVisual.Visual visual, @Nullable TellomiLinkCard requestLinkCard) {
      this.formattedDate   = formattedDate;
      this.link            = link;
      this.firstPartyLocal = firstPartyLocal;
      this.visual          = visual;
      this.requestLinkCard = requestLinkCard;
    }

    /**
     * The card of this message's link while its conversation is still a message request: the link's domain and nothing the
     * sender wrote, no image, no colour, not clickable (card-visual §3.3 / §7.3). Null: nothing is drawn there.
     */
    public @Nullable TellomiLinkCard getRequestLinkCard() {
      return requestLinkCard;
    }

    /** Null: no decision, the card is shown the way Signal shows it. */
    public @Nullable TellomiLinkVisual.Layout getLinkCardLayout() {
      return visual.getLayout();
    }

    /** Null: the card keeps its default colours. Not to be applied in a message request (card-visual §3.3). */
    public @Nullable TellomiLinkVisual.Tint getLinkCardTint() {
      return visual.getTint();
    }

    /** The bundled icon of a brand shell that has one, drawn in the icon slot; null: none. Shared, so never recycle it. */
    public @Nullable Bitmap getLinkCardIcon() {
      return visual.getIcon();
    }

    public @Nullable TellomiFirstPartyCard.Local getFirstPartyLocal() {
      return firstPartyLocal;
    }

    /** The link card decided for the first preview (or for {@link #getLocalLinkPreview()}), or null. */
    public @Nullable TellomiLinkCard getLinkCard() {
      return link.getCard();
    }

    /** The preview drawn from the URL alone, for a message that is just one link sent without a preview; or null. */
    public @Nullable LinkPreview getLocalLinkPreview() {
      return link.getLocalPreview();
    }

    /** The bubble is the card alone, without the link text under it. */
    public boolean isLinkCardOnly() {
      return link.getCardOnly();
    }

    public synchronized FormattedDate getFormattedDate() {
      return formattedDate;
    }

    public synchronized void setFormattedDate(@NonNull FormattedDate formattedDate) {
      this.formattedDate = formattedDate;
    }
  }

  /**
   * Factory providing multiple ways of creating {@link ConversationMessage}s.
   */
  public static class ConversationMessageFactory {

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord and will update and modify the provided
     * mentions from placeholder to actual. This method may perform database operations to resolve mentions to display names.
     *
     * @param mentions List of placeholder mentions to be used to update the body in the provided MessageRecord.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context,
                                                                        @NonNull MessageRecord messageRecord,
                                                                        @NonNull CharSequence body,
                                                                        @Nullable List<Mention> mentions,
                                                                        boolean hasBeenQuoted,
                                                                        @NonNull Recipient threadRecipient)
    {
      return createWithUnresolvedData(context, messageRecord, body, mentions, hasBeenQuoted, threadRecipient, null, null);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord and will update and modify the provided
     * mentions from placeholder to actual. This method may perform database operations to resolve mentions to display names.
     *
     * @param mentions          List of placeholder mentions to be used to update the body in the provided MessageRecord.
     * @param prefetchedLabels  Pre-fetched member labels keyed by RecipientId, or null to fetch on demand.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context,
                                                                        @NonNull MessageRecord messageRecord,
                                                                        @NonNull CharSequence body,
                                                                        @Nullable List<Mention> mentions,
                                                                        boolean hasBeenQuoted,
                                                                        @NonNull Recipient threadRecipient,
                                                                        @Nullable Map<RecipientId, MemberLabel> prefetchedLabels,
                                                                        @Nullable SharedContactPresentation sharedContactPresentation)
    {
      // Tellomi (ADR-0075 §4.4, as Telegram): with per-media captions the bubble shows one caption, or only the whole
      // message's; the body is then the text for other clients, and its mentions and ranges belong to it.
      String  tellomiCaption      = TellomiCaptionAbove.bubbleCaption(messageRecord);
      boolean tellomiReplacesBody = tellomiCaption != null && !tellomiCaption.contentEquals(body);
      if (tellomiReplacesBody) {
        body     = tellomiCaption;
        mentions = null;
      }

      SpannableString      styledAndMentionBody = null;
      MessageStyler.Result styleResult          = MessageStyler.Result.none();

      MentionUtil.UpdatedBodyAndMentions mentionsUpdate = null;
      if (mentions != null && !mentions.isEmpty()) {
        mentionsUpdate = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, body, mentions);
      }

      if (messageRecord.getMessageRanges() != null && !tellomiReplacesBody) {
        BodyRangeList bodyRanges = mentionsUpdate == null ? messageRecord.getMessageRanges()
                                                          : BodyRangeUtil.adjustBodyRanges(messageRecord.getMessageRanges(), mentionsUpdate.getBodyAdjustments());

        styledAndMentionBody = SpannableString.valueOf(mentionsUpdate != null ? mentionsUpdate.getBody() : body);
        styleResult          = MessageStyler.style(messageRecord.getDateSent(), bodyRanges, styledAndMentionBody);
      }

      MessageRecord originalMessage = null;
      if (messageRecord.isEditMessage()) {
        try {
          originalMessage = SignalDatabase.messages().getMessageRecord(messageRecord.getOriginalMessageId().getId());
        } catch (NoSuchMessageException e) {
          Log.e(TAG, "Original message of edit message not found!", e);
        }
      }

      FormattedDate formattedDate    = getFormattedDate(context, messageRecord);
      MemberLabel   memberLabel      = getMemberLabel(messageRecord, threadRecipient, prefetchedLabels);
      MemberLabel   quoteMemberLabel = getQuoteMemberLabel(messageRecord, threadRecipient, prefetchedLabels);
      Recipient     deletedBy        = messageRecord.getDeletedBy() != null ? Recipient.resolved(messageRecord.getDeletedBy()) : null;

      int collapsedSize = 0;
      long collapsedExpirationInMs = 0;
      if (CollapsedState.isHead(messageRecord.getCollapsedState())) {
        collapsedSize = SignalDatabase.messages().getCollapsedCount(messageRecord.getId());
        if (CollapsibleEvents.getCollapsibleType(messageRecord.getType(), messageRecord.getMessageExtras()) == CollapsibleEvents.CollapsibleType.DISAPPEARING_TIMER && collapsedSize > 1) {
          collapsedExpirationInMs = SignalDatabase.messages().getDisappearingTimerStateForCollapsedSet(messageRecord.getId());
        }
      }

      SharedContactPresentation resolvedPresentation = sharedContactPresentation != null ? sharedContactPresentation
                                                                                          : resolveSharedContactPresentation(messageRecord);

      TellomiLinkOnly.Decision linkDecision   = TellomiLinkOnly.decide(messageRecord, mentions != null && !mentions.isEmpty());
      TellomiFirstPartyCard.Local firstPartyLocal = TellomiFirstPartyLocalLookup.forMessage(messageRecord, linkDecision);
      TellomiLinkVisual.Visual    linkVisual      = TellomiLinkVisual.forMessage(messageRecord, linkDecision);
      TellomiLinkCard             requestLinkCard = TellomiLinkOnly.decideRequest(messageRecord, mentions != null && !mentions.isEmpty());

      return new ConversationMessage(messageRecord,
                                     styledAndMentionBody != null ? styledAndMentionBody : mentionsUpdate != null ? mentionsUpdate.getBody() : body,
                                     mentionsUpdate != null ? mentionsUpdate.getMentions() : null,
                                     hasBeenQuoted,
                                     styleResult,
                                     threadRecipient,
                                     originalMessage,
                                     new ComputedProperties(formattedDate, linkDecision, firstPartyLocal, linkVisual, requestLinkCard),
                                     memberLabel,
                                     quoteMemberLabel,
                                     deletedBy,
                                     resolvedPresentation,
                                     collapsedSize,
                                     collapsedExpirationInMs);
    }

    @WorkerThread
    private static @Nullable SharedContactPresentation resolveSharedContactPresentation(@NonNull MessageRecord messageRecord) {
      if (!(messageRecord instanceof MmsMessageRecord)) {
        return null;
      }

      List<Contact> contacts = ((MmsMessageRecord) messageRecord).getSharedContacts();

      return contacts.isEmpty() ? null : SharedContactPresentation.resolve(contacts.get(0));
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord, and will query for potential mentions. If mentions
     * are found, the body of the provided message will be updated and modified to match actual mentions. This will perform
     * database operations to query for mentions and then to resolve mentions to display names.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context, @NonNull MessageRecord messageRecord, @NonNull Recipient threadRecipient) {
      return createWithUnresolvedData(context, messageRecord, messageRecord.getDisplayBody(context), threadRecipient);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord and body, and will query for potential mentions. If mentions
     * are found, the body of the provided message will be updated and modified to match actual mentions. This will perform
     * database operations to query for mentions and then to resolve mentions to display names.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context, @NonNull MessageRecord messageRecord, boolean hasBeenQuoted, @NonNull Recipient threadRecipient) {
      List<Mention> mentions = messageRecord.isMms() ? SignalDatabase.mentions().getMentionsForMessage(messageRecord.getId())
                                                     : null;
      return createWithUnresolvedData(context, messageRecord, messageRecord.getDisplayBody(context), mentions, hasBeenQuoted, threadRecipient);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord and body, and will query for potential mentions. If mentions
     * are found, the body of the provided message will be updated and modified to match actual mentions. This will perform
     * database operations to query for mentions and then to resolve mentions to display names.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context, @NonNull MessageRecord messageRecord, @NonNull CharSequence body, @NonNull Recipient threadRecipient) {
      boolean       hasBeenQuoted = SignalDatabase.messages().isQuoted(messageRecord);
      List<Mention> mentions      = SignalDatabase.mentions().getMentionsForMessage(messageRecord.getId());

      return createWithUnresolvedData(context, messageRecord, body, mentions, hasBeenQuoted, threadRecipient);
    }

    @WorkerThread
    private static @Nullable MemberLabel getMemberLabel(@NonNull MessageRecord messageRecord, @NonNull Recipient threadRecipient, @Nullable Map<RecipientId, MemberLabel> prefetchedLabels) {
      if (messageRecord.isOutgoing() || !threadRecipient.isPushV2Group()) {
        return null;
      }

      if (prefetchedLabels != null) {
        return prefetchedLabels.get(messageRecord.getFromRecipient().getId());
      }

      return MemberLabelRepository.getInstance().getLabelSync(threadRecipient.requireGroupId().requireV2(), messageRecord.getFromRecipient());
    }

    @WorkerThread
    private static @Nullable MemberLabel getQuoteMemberLabel(@NonNull MessageRecord messageRecord, @NonNull Recipient threadRecipient, @Nullable Map<RecipientId, MemberLabel> prefetchedLabels) {
      if (!threadRecipient.isPushV2Group() || !(messageRecord instanceof final MmsMessageRecord mmsMessage) || mmsMessage.getQuote() == null) {
        return null;
      }

      if (prefetchedLabels != null) {
        return prefetchedLabels.get(mmsMessage.getQuote().getAuthor());
      }

      Recipient quoteAuthor = Recipient.resolved(mmsMessage.getQuote().getAuthor());
      return MemberLabelRepository.getInstance().getLabelSync(threadRecipient.requireGroupId().requireV2(), quoteAuthor);
    }
  }
}
