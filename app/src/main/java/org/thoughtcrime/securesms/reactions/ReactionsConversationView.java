package org.thoughtcrime.securesms.reactions;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;


import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.EmojiImageView;
import org.signal.emoji.EmojiUtil;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReactionsConversationView extends LinearLayout {

  // Normally 6dp, but we have 1dp left+right margin on the pills themselves
  private static final int OUTER_MARGIN = ViewUtil.dpToPx(5);

  private boolean              outgoing;
  private List<ReactionRecord> records;
  private int                  bubbleWidth;
  private boolean              isSettingBubbleWidth;

  // Tellomi (interaction audit A-07): which message these pills belong to and how each pill looked, so a change to the
  // same message's reactions can be animated while a recycled row bound to another message is not.
  private static final String  OTHER_REACTIONS_KEY = "tellomi:other-reactions";
  private static final long    NO_MESSAGE          = -1;
  private long                 messageId           = NO_MESSAGE;
  private Map<String, TellomiReactionPillAnimator.PillState> pillStates = new HashMap<>();
  // Tellomi (A-07 fly-in): each pill's emoji, by canonical emoji, for a chosen reaction to fly onto.
  private Map<String, View>                                  pillEmojiViews = new HashMap<>();

  public ReactionsConversationView(Context context) {
    super(context);
    init(null);
  }

  public ReactionsConversationView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init(attrs);
  }

  private void init(@Nullable AttributeSet attrs) {
    records = new ArrayList<>();

    if (attrs != null) {
      TypedArray typedArray = getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.ReactionsConversationView, 0, 0);
      outgoing = typedArray.getBoolean(R.styleable.ReactionsConversationView_rcv_outgoing, false);
    }
  }

  public void clear() {
    clear(NO_MESSAGE);
  }

  /** Tellomi (A-07): [messageId] is the message now shown without reactions, so its first reaction can pop in. */
  public void clear(long messageId) {
    this.records.clear();
    this.bubbleWidth = 0;
    this.messageId   = messageId;
    this.pillStates  = new HashMap<>();
    this.pillEmojiViews = new HashMap<>();
    removeAllViews();
  }

  /** Tellomi (A-07 fly-in): the emoji in the pill showing [emoji], if there is one of its own (not folded into "+N"). */
  public @Nullable View findPillEmojiView(@NonNull String emoji) {
    View view = pillEmojiViews.get(EmojiUtil.getCanonicalRepresentation(emoji));
    return view != null && view.getParent() != null ? view : null;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    if (w != oldw && !isSettingBubbleWidth) {
      int bubbleWidth = this.bubbleWidth;
      this.bubbleWidth = -1;

      setBubbleWidth(bubbleWidth);
    }
  }

  public boolean setBubbleWidth(int bubbleWidth) {
    if (bubbleWidth == this.bubbleWidth || getChildCount() == 0) {
      return false;
    }

    isSettingBubbleWidth = true;
    try {
      this.bubbleWidth = bubbleWidth;

      for (int i = 0; i < getChildCount(); i++) {
        View pill = getChildAt(i);
        pill.setVisibility(bubbleWidth == 0 ? INVISIBLE : VISIBLE);
      }

      measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

      int railWidth = getMeasuredWidth();

      if (railWidth < (bubbleWidth - OUTER_MARGIN)) {
        int margin = (bubbleWidth - railWidth - OUTER_MARGIN);

        if (outgoing) {
          setEndMargin(margin);
        } else {
          setStartMargin(margin);
        }
      } else {
        if (outgoing) {
          setEndMargin(OUTER_MARGIN);
        } else {
          setStartMargin(OUTER_MARGIN);
        }
      }

      if (!isInLayout()) {
        requestLayout();
      }

      return true;
    } finally {
      isSettingBubbleWidth = false;
    }
  }

  public boolean setReactions(@NonNull List<ReactionRecord> records) {
    return setReactions(NO_MESSAGE, records);
  }

  /**
   * Tellomi (A-07): with the id of the message these reactions belong to, a change to the same message's reactions is
   * animated (see {@link TellomiReactionPillAnimator}); binding a row to a different message is not.
   */
  public boolean setReactions(long messageId, @NonNull List<ReactionRecord> records) {
    if (records.equals(this.records) && messageId == this.messageId) {
      return false;
    }

    boolean                                            sameMessage = messageId != NO_MESSAGE && messageId == this.messageId;
    Map<String, TellomiReactionPillAnimator.PillState> before      = pillStates;

    this.messageId = messageId;
    bubbleWidth = -1;
    this.records.clear();
    this.records.addAll(records);

    List<Reaction> reactions = buildSortedReactionsList(records);

    removeAllViews();

    Map<String, TellomiReactionPillAnimator.PillState> after      = new HashMap<>();
    Map<String, View>                                  emojiViews = new HashMap<>();

    for (Reaction reaction : reactions) {
      View pill = buildPill(getContext(), this, reaction);
      addView(pill);

      String                                key   = reaction.baseEmoji != null ? reaction.baseEmoji : OTHER_REACTIONS_KEY;
      TellomiReactionPillAnimator.PillState state = new TellomiReactionPillAnimator.PillState(reaction.count, reaction.userWasSender);
      after.put(key, state);

      if (reaction.baseEmoji != null) {
        View emojiView = pill.findViewById(R.id.reactions_pill_emoji);
        emojiViews.put(key, emojiView);

        // Tellomi (A-07): the reaction just chosen is still flying here; its emoji shows when it lands.
        TellomiReactionFlyIn.holdUntilLanded(messageId, reaction.baseEmoji, emojiView);
      }

      if (sameMessage) {
        TellomiReactionPillAnimator.animate(pill, pill.findViewById(R.id.reactions_pill_count), TellomiReactionPillAnimator.changeFor(before.get(key), state));
      }
    }

    pillStates     = after;
    pillEmojiViews = emojiViews;

    return true;
  }

  private static @NonNull List<Reaction> buildSortedReactionsList(@NonNull List<ReactionRecord> records) {
    Map<String, Reaction> counters = new LinkedHashMap<>();
    RecipientId           selfId   = Recipient.self().getId();

    for (ReactionRecord record : records) {
      String   baseEmoji = EmojiUtil.getCanonicalRepresentation(record.getEmoji());
      Reaction info      = counters.get(baseEmoji);

      if (info == null) {
        info = new Reaction(baseEmoji, record.getEmoji(), 1, record.getDateReceived(), selfId.equals(record.getAuthor()));
      } else {
        info.update(record.getEmoji(), record.getDateReceived(), selfId.equals(record.getAuthor()));
      }

      counters.put(baseEmoji, info);
    }

    List<Reaction> reactions = new ArrayList<>(counters.values());

    Collections.sort(reactions, Collections.reverseOrder());

    if (reactions.size() > 3) {
      List<Reaction> shortened = new ArrayList<>(3);
      shortened.add(reactions.get(0));
      shortened.add(reactions.get(1));
      shortened.add(reactions.stream().skip(2).reduce(new Reaction(null, null, 0, 0, false), Reaction::merge));

      return shortened;
    } else {
      return reactions;
    }
  }

  private void setStartMargin(int margin) {
    if (ViewUtil.isLtr(this)) {
      getMarginLayoutParams().leftMargin = margin;
    } else {
      getMarginLayoutParams().rightMargin = margin;
    }
  }

  private void setEndMargin(int margin) {
    if (ViewUtil.isLtr(this)) {
      getMarginLayoutParams().rightMargin = margin;
    } else {
      getMarginLayoutParams().leftMargin = margin;
    }
  }

  private @NonNull MarginLayoutParams getMarginLayoutParams() {
    return (MarginLayoutParams) getLayoutParams();
  }

  private static View buildPill(@NonNull Context context, @NonNull ViewGroup parent, @NonNull Reaction reaction) {
    View           root      = LayoutInflater.from(context).inflate(R.layout.reactions_pill, parent, false);
    EmojiImageView emojiView = root.findViewById(R.id.reactions_pill_emoji);
    TextView       countView = root.findViewById(R.id.reactions_pill_count);
    View           spacer    = root.findViewById(R.id.reactions_pill_spacer);

    if (reaction.displayEmoji != null) {
      emojiView.setImageEmoji(reaction.displayEmoji);

      if (reaction.count > 1) {
        countView.setText(String.valueOf(reaction.count));
      } else {
        countView.setVisibility(GONE);
        spacer.setVisibility(GONE);
      }
    } else {
      emojiView.setVisibility(GONE);
      spacer.setVisibility(GONE);
      countView.setText(context.getString(R.string.ReactionsConversationView_plus, reaction.count));
    }

    if (reaction.userWasSender) {
      root.setBackground(ContextCompat.getDrawable(context, R.drawable.reaction_pill_background_selected));
      countView.setTextColor(ContextCompat.getColor(context, R.color.reactions_pill_selected_text_color));
    } else {
      root.setBackground(ContextCompat.getDrawable(context, R.drawable.reaction_pill_background));
    }

    return root;
  }

  private static class Reaction implements Comparable<Reaction> {
    private String  baseEmoji;
    private String  displayEmoji;
    private int     count;
    private long    lastSeen;
    private boolean userWasSender;

    Reaction(@Nullable String baseEmoji, @Nullable String displayEmoji, int count, long lastSeen, boolean userWasSender) {
      this.baseEmoji     = baseEmoji;
      this.displayEmoji  = displayEmoji;
      this.count         = count;
      this.lastSeen      = lastSeen;
      this.userWasSender = userWasSender;
    }

    void update(@NonNull String displayEmoji, long lastSeen, boolean userWasSender) {
      if (!this.userWasSender) {
        if (userWasSender || lastSeen > this.lastSeen) {
          this.displayEmoji = displayEmoji;
        }
      }

      this.count         = this.count + 1;
      this.lastSeen      = Math.max(this.lastSeen, lastSeen);
      this.userWasSender = this.userWasSender || userWasSender;
    }

    @NonNull Reaction merge(@NonNull Reaction other) {
      this.count         = this.count + other.count;
      this.lastSeen      = Math.max(this.lastSeen, other.lastSeen);
      this.userWasSender = this.userWasSender || other.userWasSender;
      return this;
    }

    @Override
    public int compareTo(Reaction rhs) {
      Reaction lhs = this;

      if (lhs.count != rhs.count) {
        return Integer.compare(lhs.count, rhs.count);
      }

      return Long.compare(lhs.lastSeen, rhs.lastSeen);
    }
  }
}
