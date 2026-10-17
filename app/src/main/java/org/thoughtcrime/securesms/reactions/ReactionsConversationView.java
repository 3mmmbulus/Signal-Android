package org.thoughtcrime.securesms.reactions;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;

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

/**
 * The reaction pills under a message.
 *
 * Tellomi (interaction audit A-07, owner 2026-10-17): every reaction gets its own pill, wrapping onto more rows when they do
 * not fit, as Telegram shows them on both platforms ({@code ReactionsLayoutInBubble}); the old rail stopped at two pills and
 * folded the rest into "+N". Rows line up on the side the pills already sit on, towards the middle of the screen. The same
 * change on iOS is Signal-iOS#284.
 */
public class ReactionsConversationView extends FlexboxLayout {

  // Normally 6dp, but we have 1dp left+right margin on the pills themselves
  private static final int OUTER_MARGIN = ViewUtil.dpToPx(5);

  /** Tellomi (A-07): the gap between two rows of pills. */
  @VisibleForTesting
  static final int ROW_GAP = ViewUtil.dpToPx(2);

  /** Tellomi (A-07): rows are at least this wide (about three pills) even under a short bubble. */
  @VisibleForTesting
  static final int MIN_RAIL_WIDTH = ViewUtil.dpToPx(136);

  /** Tellomi (A-07): how long a pill takes to cross-fade between "mine" and not. */
  private static final int FILL_CROSSFADE_MS = 200;

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

    setFlexDirection(FlexDirection.ROW);
    setFlexWrap(FlexWrap.WRAP);
    setAlignItems(AlignItems.CENTER);
    // Outgoing pills hang from the bubble's start, incoming ones from its end: rows line up on that side.
    setJustifyContent(outgoing ? JustifyContent.FLEX_START : JustifyContent.FLEX_END);

    GradientDrawable rowGap = new GradientDrawable();
    rowGap.setColor(Color.TRANSPARENT);
    rowGap.setSize(0, ROW_GAP);
    setDividerDrawableHorizontal(rowGap);
    setShowDividerHorizontal(SHOW_DIVIDER_MIDDLE);
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
    this.maxRailWidth   = 0;
    setTranslationX(0f);
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

      // Tellomi (A-07): the pills wrap at the bubble's width (but keep about three to a row), so a long row stays under the
      // message instead of running off the screen.
      maxRailWidth = maxRailWidth(bubbleWidth, availableRailWidth());
      measure(MeasureSpec.makeMeasureSpec(maxRailWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

      int railWidth = getMeasuredWidth();

      if (!(getParent() instanceof RelativeLayout)) {
        // The conversation's newer rows pin the rail to the bubble's edge towards the middle of the screen and the margins
        // below do nothing there; a rail wider than the bubble is moved further towards the middle instead.
        setTranslationX(overflowShift(railWidth, bubbleWidth, outgoing, ViewUtil.isLtr(this)));
      }

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

  /** Tellomi (A-07): the widest the rail may grow; 0 until a bubble width is known. */
  private int maxRailWidth;

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int mode = MeasureSpec.getMode(widthMeasureSpec);
    if (maxRailWidth > 0 && (mode == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(widthMeasureSpec) > maxRailWidth)) {
      widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxRailWidth, MeasureSpec.AT_MOST);
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  /** Tellomi (A-07): the bubble's width less the margins either side, at least [MIN_RAIL_WIDTH], at most what the row has. */
  @VisibleForTesting
  static int maxRailWidth(int bubbleWidth, int available) {
    int width = Math.max(bubbleWidth - OUTER_MARGIN * 2, MIN_RAIL_WIDTH);
    return available > 0 ? Math.min(width, available) : width;
  }

  /**
   * Tellomi (A-07): how far to move a rail that is wider than its bubble, towards the middle of the screen. Outgoing pills
   * start at the bubble's start edge and incoming ones end at its end edge, so the overflow goes the other way.
   */
  @VisibleForTesting
  static float overflowShift(int railWidth, int bubbleWidth, boolean outgoing, boolean ltr) {
    int overflow = railWidth - (bubbleWidth - OUTER_MARGIN * 2);
    if (overflow <= 0 || bubbleWidth <= 0) {
      return 0f;
    }
    boolean towardsLeft = outgoing == ltr;
    return towardsLeft ? -overflow : overflow;
  }

  /** Tellomi (A-07): the width the row of pills may take at most: the message row's, less the margins either side. */
  private int availableRailWidth() {
    if (!(getParent() instanceof View)) {
      return 0;
    }
    View parent = (View) getParent();
    return Math.max(0, parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight() - OUTER_MARGIN * 2);
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

    List<Reaction> reactions = buildSortedReactionsList(records, Recipient.self().getId());

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
        TellomiReactionPillAnimator.PillState previous = before.get(key);
        TellomiReactionPillAnimator.animate(pill, pill.findViewById(R.id.reactions_pill_count), TellomiReactionPillAnimator.changeFor(previous, state));
        // Tellomi (A-07): becoming or no longer being mine cross-fades the fill instead of swapping it in one frame.
        if (previous != null && previous.getUserWasSender() != state.getUserWasSender()) {
          crossFadeFill(pill, previous.getUserWasSender());
        }
      }
    }

    pillStates     = after;
    pillEmojiViews = emojiViews;

    return true;
  }

  private void crossFadeFill(@NonNull View pill, boolean wasSelected) {
    Drawable from = ContextCompat.getDrawable(getContext(), wasSelected ? R.drawable.reaction_pill_background_selected : R.drawable.reaction_pill_background);
    Drawable to   = pill.getBackground();
    if (from == null || to == null) {
      return;
    }
    TransitionDrawable transition = new TransitionDrawable(new Drawable[] { from, to });
    transition.setCrossFadeEnabled(true);
    pill.setBackground(transition);
    transition.startTransition(FILL_CROSSFADE_MS);
  }

  /** Tellomi (A-07): one pill per reaction, most popular first; no longer folded into "+N" after two. */
  @VisibleForTesting
  static @NonNull List<Reaction> buildSortedReactionsList(@NonNull List<ReactionRecord> records, @NonNull RecipientId selfId) {
    Map<String, Reaction> counters = new LinkedHashMap<>();

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

    return reactions;
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

  @VisibleForTesting
  static class Reaction implements Comparable<Reaction> {
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

    @VisibleForTesting
    @Nullable String getBaseEmoji() {
      return baseEmoji;
    }

    @VisibleForTesting
    int getCount() {
      return count;
    }

    @VisibleForTesting
    boolean isUserWasSender() {
      return userWasSender;
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
