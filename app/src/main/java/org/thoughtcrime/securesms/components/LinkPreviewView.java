package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;

import com.bumptech.glide.RequestManager;

import org.signal.core.ui.view.Stub;
import org.signal.ringrtc.CallLinkRootKey;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.avatar.fallback.FallbackAvatar;
import org.thoughtcrime.securesms.avatar.fallback.FallbackAvatarDrawable;
import org.thoughtcrime.securesms.calls.links.CallLinks;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.conversation.colors.AvatarColorHash;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository;
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardAccessibility;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkDisplay;
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual;
import org.thoughtcrime.securesms.mms.ImageSlide;
import org.thoughtcrime.securesms.mms.SlidesClickedListener;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.core.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import okhttp3.HttpUrl;

/**
 * The view shown in the compose box or conversation that represents the state of the link preview.
 */
public class LinkPreviewView extends FrameLayout {

  private static final int TYPE_CONVERSATION = 0;
  /** Tellomi: the avatar or cover of a first-party card (card-visual §5.2: 56). */
  private static final int FIRST_PARTY_AVATAR_DP = 56;
  private static final int DEFAULT_THUMBNAIL_DP  = 72;
  /**
   * The icon card's geometry is Telegram's small link-preview image (owner 2026-09-30; Telegram Android
   * {@code ChatMessageCell} {@code smallImageSide} = 48dp, {@code smallSideMargin} = 10dp; Telegram iOS
   * {@code ImageCorners(radius: 4.0)}), not card-visual §3.2's 44dp / radius 10 / centred. The image's end edge is at the
   * card's end padding, the text's start edge is at its start padding (Telegram: the image's right edge is the text area's
   * right edge); only the top offset is fixed, and it is the card's own 6dp top padding (Telegram's {@code inlineMediaEdgeInset}).
   */
  private static final int ICON_IMAGE_DP         = 48;
  private static final int ICON_IMAGE_RADIUS_DP  = 4;
  private static final int ICON_TEXT_GAP_DP      = 10;
  /** {@code layout_marginEnd} of the title in link_preview.xml. */
  private static final int TITLE_END_MARGIN_DP   = 8;
  private static final int SECONDARY_TEXT_ALPHA  = 0xB3;
  /** The description's line limits: a card's sub line is one line, cut off at its end (card-visual §3.7); the compose box keeps two. */
  private static final int CONVERSATION_DESCRIPTION_MAX_LINES = 1;
  private static final int COMPOSE_DESCRIPTION_MAX_LINES      = 2;
  private static final int TYPE_COMPOSE      = 1;
  /** A pressed or focused card is covered by its text colour at 12% (Material 3's state layers), card-visual §3.6 / §3.8. */
  private static final int STATE_LAYER_ALPHA = 0x1F;

  private ViewGroup                   container;
  private Stub<OutlinedThumbnailView> thumbnail;
  private TextView                    title;
  private View                        linkIcon;
  private AvatarImageView             firstPartyAvatar;
  private TextView                    action;
  private ColorStateList              titleColors;
  private TextView                    description;
  private ColorStateList              descriptionColors;
  private TextView                    site;
  private ColorStateList              siteColors;
  private View                        divider;
  private View                        closeButton;
  private View                        spinner;
  private TextView                    noPreview;

  private int                           type;
  private int                           defaultRadius;
  private boolean                       iconLayout;
  /** Tellomi (DESIGN.md §9): the unified card style is on this view; undone by {@link #resetTellomiCardStyle}, since views are reused. */
  private boolean                       cardStyled;
  private ConstraintLayout.LayoutParams xmlTitleParams;
  private ConstraintLayout.LayoutParams xmlSiteParams;
  private ConstraintLayout.LayoutParams xmlLinkIconParams;
  private float                         xmlTitleTextSize;
  private float                         xmlSiteTextSize;
  private Typeface                      xmlTitleTypeface;
  private int[]                         xmlContainerPadding;
  private ColorStateList                xmlLinkIconTint;
  /** Tellomi (card-visual §3.6): what a screen reader says for the whole card, and whether its action is a button. */
  private TellomiLinkCardAccessibility.Strings accessibilityStrings;
  private boolean                       actionIsButton;
  private CornerMask                    cornerMask;
  private CloseClickedListener          closeClickedListener;
  private LinkPreviewViewThumbnailState thumbnailState = new LinkPreviewViewThumbnailState();

  public LinkPreviewView(Context context) {
    super(context);
    init(null);
  }

  public LinkPreviewView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init(attrs);
  }

  private void init(@Nullable AttributeSet attrs) {
    inflate(getContext(), R.layout.link_preview, this);

    container     = findViewById(R.id.linkpreview_container);
    thumbnail     = new Stub<>(findViewById(R.id.linkpreview_thumbnail));
    title         = findViewById(R.id.linkpreview_title);
    linkIcon      = findViewById(R.id.linkpreview_link_icon);
    firstPartyAvatar = findViewById(R.id.linkpreview_first_party_avatar);
    action        = findViewById(R.id.linkpreview_action);
    titleColors   = title.getTextColors();
    description   = findViewById(R.id.linkpreview_description);
    site          = findViewById(R.id.linkpreview_site);
    descriptionColors = description.getTextColors();
    siteColors    = site.getTextColors();
    divider       = findViewById(R.id.linkpreview_divider);
    spinner       = findViewById(R.id.linkpreview_progress_wheel);
    closeButton   = findViewById(R.id.linkpreview_close);
    noPreview     = findViewById(R.id.linkpreview_no_preview);
    defaultRadius = getResources().getDimensionPixelSize(R.dimen.thumbnail_default_radius);
    cornerMask    = new CornerMask(this);
    xmlTitleParams      = new ConstraintLayout.LayoutParams((ConstraintLayout.LayoutParams) title.getLayoutParams());
    xmlSiteParams       = new ConstraintLayout.LayoutParams((ConstraintLayout.LayoutParams) site.getLayoutParams());
    xmlLinkIconParams   = new ConstraintLayout.LayoutParams((ConstraintLayout.LayoutParams) linkIcon.getLayoutParams());
    xmlTitleTextSize    = title.getTextSize();
    xmlSiteTextSize     = site.getTextSize();
    xmlTitleTypeface    = title.getTypeface();
    xmlLinkIconTint     = ImageViewCompat.getImageTintList((ImageView) linkIcon);
    accessibilityStrings = TellomiLinkCardAccessibility.Strings.from(getContext());
    xmlContainerPadding  = new int[] { container.getPaddingStart(), container.getPaddingTop(), container.getPaddingEnd(), container.getPaddingBottom() };

    if (attrs != null) {
      TypedArray typedArray   = getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.LinkPreviewView, 0, 0);
      type = typedArray.getInt(R.styleable.LinkPreviewView_linkpreview_type, 0);
      typedArray.recycle();
    }

    if (type == TYPE_COMPOSE) {
      container.setBackgroundColor(Color.TRANSPARENT);
      container.setPadding(0, 0, 0, 0);
      divider.setVisibility(VISIBLE);
      closeButton.setVisibility(VISIBLE);
      title.setMaxLines(2);
      description.setMaxLines(2);

      closeButton.setOnClickListener(v -> {
        if (closeClickedListener != null) {
          closeClickedListener.onCloseClicked();
        }
      });
    } else {
      // Tellomi (card-visual §3.6, §3.8): a card in a bubble is one node for a screen reader, which says the whole card in one
      // sentence, so its parts are not read again; a pressed or focused card gets a translucent layer, never another background.
      ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
      ViewCompat.setImportantForAccessibility(container, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
        @Override
        public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          if (actionIsButton) {
            info.setClassName(Button.class.getName());
          }
        }
      });
      showStateLayer(titleColors.getDefaultColor());
    }

    setWillNotDraw(false);
  }

  @Override
  public void onDrawForeground(Canvas canvas) {
    super.onDrawForeground(canvas);
    if (type == TYPE_COMPOSE || getForeground() == null) return;

    // The layer of a pressed card stops at the bubble's rounded corners, like the card itself.
    cornerMask.mask(canvas);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    super.dispatchDraw(canvas);
    if (type == TYPE_COMPOSE) return;

    cornerMask.mask(canvas);
  }

  public void setLoading() {
    title.setVisibility(GONE);
    site.setVisibility(GONE);
    description.setVisibility(GONE);
    thumbnail.setVisibility(GONE);
    spinner.setVisibility(VISIBLE);
    noPreview.setVisibility(INVISIBLE);
  }

  public void setNoPreview(@Nullable LinkPreviewRepository.Error customError) {
    title.setVisibility(GONE);
    site.setVisibility(GONE);
    thumbnail.setVisibility(GONE);
    spinner.setVisibility(GONE);
    // Tellomi：只有群邀请失效那一句还显示；调用方在 isShownWithoutPreview() 为 false 时整个预览区都不显示
    noPreview.setVisibility(isShownWithoutPreview(customError) ? VISIBLE : GONE);
    noPreview.setText(getLinkPreviewErrorString(customError));
  }

  public void setLinkPreview(@NonNull RequestManager requestManager, @NonNull LinkPreview linkPreview, boolean showThumbnail) {
    setLinkPreview(requestManager, linkPreview, showThumbnail, true, false);
  }

  public void setLinkPreview(@NonNull RequestManager requestManager, @NonNull LinkPreview linkPreview, boolean showThumbnail, boolean showDescription, boolean scheduleMessageMode) {
    showPlainLink(false, false);
    showFirstParty(false);
    showTellomiVisual(false);
    spinner.setVisibility(GONE);
    noPreview.setVisibility(GONE);

    CallLinkRootKey callLinkRootKey = CallLinks.isCallLink(linkPreview.getUrl()) ? CallLinks.parseUrl(linkPreview.getUrl()) : null;
    if (!Util.isEmpty(linkPreview.getTitle())) {
      title.setText(linkPreview.getTitle());
      title.setVisibility(VISIBLE);
    } else if (callLinkRootKey != null) {
      title.setText(R.string.Recipient_signal_call);
      title.setVisibility(VISIBLE);
    } else {
      title.setVisibility(GONE);
    }

    if (showDescription && !Util.isEmpty(linkPreview.getDescription())) {
      description.setText(linkPreview.getDescription());
      description.setVisibility(VISIBLE);
    } else if (callLinkRootKey != null) {
      description.setText(R.string.LinkPreviewView__use_this_link_to_join_a_signal_call);
      description.setVisibility(VISIBLE);
    } else {
      description.setVisibility(GONE);
    }

    String domain = null;

    if (!Util.isEmpty(linkPreview.getUrl())) {
      HttpUrl url = HttpUrl.parse(linkPreview.getUrl());
      if (url != null) {
        domain = url.topPrivateDomain();
      }
    }

    if (domain != null && linkPreview.getDate() > 0) {
      site.setText(getContext().getString(R.string.LinkPreviewView_domain_date, domain, formatDate(linkPreview.getDate())));
      site.setVisibility(VISIBLE);
    } else if (domain != null) {
      site.setText(domain);
      site.setVisibility(VISIBLE);
    } else if (linkPreview.getDate() > 0) {
      site.setText(formatDate(linkPreview.getDate()));
      site.setVisibility(VISIBLE);
    } else {
      site.setVisibility(GONE);
    }

    if (showThumbnail && linkPreview.getThumbnail().isPresent()) {
      thumbnail.setVisibility(VISIBLE);
      thumbnailState.applyState(thumbnail);
      thumbnail.get().setImageResource(requestManager, new ImageSlide(linkPreview.getThumbnail().get()), type == TYPE_CONVERSATION && !scheduleMessageMode, false);
      thumbnail.get().showSecondaryText(false);
      thumbnail.get().setOutlineEnabled(true);
    } else if (callLinkRootKey != null) {
      thumbnail.setVisibility(VISIBLE);
      thumbnailState.applyState(thumbnail);
      thumbnail.get().setImageDrawable(
          requestManager,
          new FallbackAvatarDrawable(
              getContext(),
              new FallbackAvatar.Resource.CallLink(AvatarColorHash.forCallLink(callLinkRootKey.getKeyBytes()))
          ).circleCrop()
      );
      thumbnail.get().showSecondaryText(false);
      thumbnail.get().setOutlineEnabled(false);
    } else {
      thumbnail.setVisibility(GONE);
    }

    boolean thumbnailVisible = (showThumbnail && linkPreview.getThumbnail().isPresent()) || callLinkRootKey != null;
    alignTitleWithThumbnail(thumbnailVisible);

    // What a card nothing decided on is read as (card-visual §3.6): the preview's own title and the domain shown above.
    String readTitle = !Util.isEmpty(linkPreview.getTitle()) ? linkPreview.getTitle()
                                                               : callLinkRootKey != null ? getContext().getString(R.string.Recipient_signal_call) : null;
    describeCard(TellomiLinkCardAccessibility.link(readTitle, domain, accessibilityStrings), false);
  }

  /**
   * Tellomi (ADR-0063 §5.1, §4.8): after {@link #setLinkPreview}, show the text rust/links decided for
   * this preview's level instead of the sender's. Null leaves Signal's display as is.
   */
  public void applyTellomiDisplay(@Nullable TellomiLinkDisplay display, boolean showDescription) {
    showPlainLink(display != null && display.getPlainLink(), display != null && display.getLookalike());
    if (display == null) {
      return;
    }

    describeCard(TellomiLinkCardAccessibility.link(display.getTitle(), display.getAccessibilityDomain(), accessibilityStrings), false);

    if (!Util.isEmpty(display.getTitle())) {
      setTitleText(display.getTitle(), display.getOfficialBadge());
      title.setVisibility(VISIBLE);
    } else {
      title.setVisibility(GONE);
    }

    if (showDescription && !Util.isEmpty(display.getDescription())) {
      description.setText(display.getDescription());
      description.setVisibility(VISIBLE);
    } else {
      description.setVisibility(GONE);
    }

    if (!Util.isEmpty(display.getDomain())) {
      site.setText(display.getDomain());
      site.setVisibility(VISIBLE);
    } else if (display.getPlainLink()) {
      // The domain is the title already (card-visual §3.7).
      site.setVisibility(GONE);
    }
  }

  /**
   * Tellomi (card-visual §3.3 / §7.3): the card of a link received in a conversation that is still a message request: the
   * link's registrable domain as its only line, the link icon at the end of it, the default colours, and nothing the sender
   * wrote (no title, description, image, avatar or action). It takes no preview, so nothing but the domain (and whether it
   * imitates a well-known one, drawn red, ADR-0063 §6.1) can reach the view, and no link-specific look (a call link's avatar)
   * can appear. Replaces {@link #setLinkPreview} and {@link #applyTellomiDisplay}, and undoes what any other card left on this
   * (recycled) view. Only for conversation bubbles.
   */
  public void setDomainOnly(@NonNull String domain, boolean lookalike) {
    if (type == TYPE_COMPOSE) {
      return;
    }

    showFirstParty(false);
    showTellomiVisual(false);
    spinner.setVisibility(GONE);
    noPreview.setVisibility(GONE);
    thumbnail.setVisibility(GONE);
    description.setVisibility(GONE);
    site.setVisibility(GONE);
    alignTitleWithThumbnail(false);
    title.setText(domain);
    title.setVisibility(VISIBLE);
    showPlainLink(true, lookalike);

    // It opens nothing, so a touch on it is not a press on a card (card-visual §3.6).
    hideStateLayer();
    describeCard(TellomiLinkCardAccessibility.link(null, domain, accessibilityStrings), false);
  }

  /**
   * Tellomi (card-visual §5.2): after {@link #setLinkPreview}, draw the card of a Tellomi object: an avatar or cover, the
   * title, one subtitle line, and the action under a hairline across the card (no domain line). Only for conversation
   * bubbles. Users and groups this device knows show their own avatar; the others keep what {@link #setLinkPreview} put
   * there (a group's or pack's picture from the preview, the call link's icon) or get a placeholder.
   */
  public void applyTellomiFirstParty(@NonNull RequestManager requestManager,
                                     @NonNull TellomiFirstPartyCard.Display display,
                                     @Nullable Recipient avatarRecipient,
                                     boolean hasSnapshotThumbnail)
  {
    if (type == TYPE_COMPOSE) {
      return;
    }

    // The card is read as one sentence, and its action is a button (card-visual §3.6).
    describeCard(TellomiLinkCardAccessibility.firstParty(display, accessibilityStrings), true);

    showPlainLink(false, false);
    setTitleText(display.getTitle(), display.getOfficialBadge());
    title.setVisibility(VISIBLE);

    if (!Util.isEmpty(display.getSubtitle())) {
      description.setText(display.getSubtitle());
      description.setVisibility(VISIBLE);
    } else {
      description.setVisibility(GONE);
    }
    site.setVisibility(GONE);

    boolean avatarVisible = false;
    if (avatarRecipient != null && (display.getType() == TellomiFirstPartyCard.Type.USER || display.getType() == TellomiFirstPartyCard.Type.GROUP)) {
      firstPartyAvatar.setAvatar(requestManager, avatarRecipient, false);
      firstPartyAvatar.setVisibility(VISIBLE);
      thumbnail.setVisibility(GONE);
      avatarVisible = true;
    } else if (display.getType() == TellomiFirstPartyCard.Type.OFFICIAL) {
      showThumbnailDrawable(requestManager, AppCompatResources.getDrawable(getContext(), R.mipmap.ic_launcher));
    } else if (!hasSnapshotThumbnail && (display.getType() == TellomiFirstPartyCard.Type.USER || display.getType() == TellomiFirstPartyCard.Type.GROUP)) {
      AvatarColor      color    = AvatarColorHash.forCallLink(display.getTitle().getBytes(StandardCharsets.UTF_8));
      FallbackAvatar   fallback = display.getType() == TellomiFirstPartyCard.Type.USER ? new FallbackAvatar.Resource.Person(color) : new FallbackAvatar.Resource.Group(color);
      showThumbnailDrawable(requestManager, new FallbackAvatarDrawable(getContext(), fallback).circleCrop());
    }

    if (!avatarVisible) {
      firstPartyAvatar.setVisibility(GONE);
    }
    boolean leadingVisible = avatarVisible || (thumbnail.resolved() && thumbnail.getVisibility() == VISIBLE);
    int     leadingId      = avatarVisible ? R.id.linkpreview_first_party_avatar : R.id.linkpreview_thumbnail;
    resizeThumbnail(FIRST_PARTY_AVATAR_DP);
    alignTitleWithLeading(leadingVisible, leadingId);
    stackSubtitleUnderTitle(leadingVisible, leadingId);

    if (display.getType() == TellomiFirstPartyCard.Type.CALL) {
      // A call link already has Signal's own "Join" button under the bubble; a second one would say the same.
      action.setVisibility(GONE);
      divider.setVisibility(GONE);
      return;
    }

    int start  = -container.getPaddingStart();
    int end    = -container.getPaddingEnd();
    int bottom = -container.getPaddingBottom();
    setHorizontalMargins(divider, start, end, 0);
    setHorizontalMargins(action, start, end, bottom);
    action.setText(display.getAction());
    action.setVisibility(VISIBLE);
    divider.setVisibility(VISIBLE);
  }

  /**
   * Tellomi (card-visual §3.9, ADR-0063 §九.6): after {@link #setLinkPreview}, before {@link #applyTellomiLayout}, the icon
   * a brand shell ships with the app, in the slot the card's own image would have. It is never the sender's image, and it
   * comes from the package, so nothing is fetched. Null leaves the card as it is (name and domain only). Only for
   * conversation bubbles.
   */
  public void applyTellomiBrandIcon(@NonNull RequestManager requestManager, @Nullable Bitmap icon) {
    if (type == TYPE_COMPOSE || icon == null || firstPartyAvatar.getVisibility() == VISIBLE) {
      return;
    }

    showThumbnailDrawable(requestManager, new BitmapDrawable(getResources(), icon));
    // The brand's own image, like a sender's icon: a hairline keeps a white icon from melting into a light card.
    thumbnail.get().setOutlineEnabled(true);
  }

  /**
   * Tellomi (card-visual §3.2, §3.7): after {@link #applyTellomiDisplay}, the shape of the card.
   *
   * The icon card: the title, the sub line (when there is one) and the domain on the left, the card's own image as a
   * {@link #ICON_IMAGE_DP} square with {@link #ICON_IMAGE_RADIUS_DP} corners at the top right, at the card's end padding (the
   * mirror of the text's start padding) and its top padding, the text column ending {@link #ICON_TEXT_GAP_DP} before it and
   * starting at the top. The whole text column is narrowed by the image; Telegram wraps only the first lines around it. The card
   * is at least the image and its paddings tall.
   *
   * The no-image card: the link icon at the end of the title line, the way the plain-link card has it.
   *
   * Other layouts are left as they are. Only for conversation bubbles.
   */
  public void applyTellomiLayout(@Nullable TellomiLinkVisual.Layout layout) {
    applyTellomiLayout(layout, true);
  }

  /**
   * As {@link #applyTellomiLayout(TellomiLinkVisual.Layout)}; {@code showLinkIcon} false leaves the link icon off the no-image card
   * ({@link TellomiLinkVisual#showsLinkIcon}: a claimed-tier card is its title and domain only, PM 2026-10-19 evening).
   */
  public void applyTellomiLayout(@Nullable TellomiLinkVisual.Layout layout, boolean showLinkIcon) {
    if (type == TYPE_COMPOSE) {
      return;
    }

    if (layout == TellomiLinkVisual.Layout.NO_IMAGE) {
      linkIcon.setVisibility(showLinkIcon ? VISIBLE : GONE);
      return;
    }

    if (layout != TellomiLinkVisual.Layout.ICON || !thumbnail.resolved() || thumbnail.getVisibility() != VISIBLE || firstPartyAvatar.getVisibility() == VISIBLE) {
      return;
    }

    iconLayout = true;
    resizeThumbnail(ICON_IMAGE_DP);
    int radius = ViewUtil.dpToPx(ICON_IMAGE_RADIUS_DP);
    thumbnailState = thumbnailState.copy(radius, radius, radius, radius, thumbnailState.getDownloadListener());
    thumbnailState.applyState(thumbnail);

    ConstraintLayout.LayoutParams thumbnailParams = (ConstraintLayout.LayoutParams) thumbnail.get().getLayoutParams();
    thumbnailParams.startToStart   = ConstraintLayout.LayoutParams.UNSET;
    thumbnailParams.endToEnd       = ConstraintLayout.LayoutParams.PARENT_ID;
    thumbnailParams.topToTop       = ConstraintLayout.LayoutParams.PARENT_ID;
    thumbnailParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
    thumbnail.get().setLayoutParams(thumbnailParams);

    ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    boolean subLine = description.getVisibility() == VISIBLE && !Util.isEmpty(description.getText());

    titleParams.topToTop           = ConstraintLayout.LayoutParams.PARENT_ID;
    titleParams.bottomToBottom     = ConstraintLayout.LayoutParams.UNSET;
    titleParams.bottomToTop        = subLine ? R.id.linkpreview_description : R.id.linkpreview_site;
    titleParams.startToEnd         = ConstraintLayout.LayoutParams.UNSET;
    titleParams.startToStart       = ConstraintLayout.LayoutParams.PARENT_ID;
    titleParams.endToStart         = R.id.linkpreview_thumbnail;
    titleParams.verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
    titleParams.verticalBias       = 0f;
    titleParams.setMarginStart(0);
    titleParams.setMarginEnd(ViewUtil.dpToPx(ICON_TEXT_GAP_DP));
    title.setLayoutParams(titleParams);

    if (subLine) {
      ConstraintLayout.LayoutParams descriptionParams = (ConstraintLayout.LayoutParams) description.getLayoutParams();
      descriptionParams.topToBottom    = R.id.linkpreview_title;
      descriptionParams.bottomToTop    = R.id.linkpreview_site;
      descriptionParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
      descriptionParams.startToStart   = R.id.linkpreview_title;
      descriptionParams.endToEnd       = R.id.linkpreview_title;
      descriptionParams.topMargin      = ViewUtil.dpToPx(2);
      description.setLayoutParams(descriptionParams);
    } else {
      description.setVisibility(GONE);
    }

    ConstraintLayout.LayoutParams siteParams = (ConstraintLayout.LayoutParams) site.getLayoutParams();
    siteParams.topToBottom     = subLine ? R.id.linkpreview_description : R.id.linkpreview_title;
    siteParams.bottomToBottom  = ConstraintLayout.LayoutParams.PARENT_ID;
    siteParams.startToStart    = R.id.linkpreview_title;
    siteParams.endToEnd        = R.id.linkpreview_title;
    siteParams.topMargin       = ViewUtil.dpToPx(2);
    site.setLayoutParams(siteParams);
  }

  /**
   * Tellomi (PM 2026-10-19, after Apple Messages; DESIGN.md §9; iOS {@code CVLinkPreviewViewAdapterTellomiStrip} /
   * {@code ...Large}): last, after {@link #applyTellomiDisplay}, {@link #applyTellomiBrandIcon}, {@link #applyTellomiLayout} and
   * {@link #applyTellomiTint}, draw a third-party card in one of the four styles of {@link TellomiLinkCardStyle}. Only the title
   * (at most two lines, bold) and the domain; no sub line.
   *
   * <ul>
   *   <li>{@code LARGE_IMAGE}: the bar under the image (drawn by the bubble's media view): title and domain, neutral grey;</li>
   *   <li>{@code ICON_STRIP}: title and domain on the left, the image as a 36dp square with 8dp corners on the right, centred;
   *       the colours the tint gave (neutral grey without one);</li>
   *   <li>{@code TEXT_STRIP}: neutral grey, title and domain, nothing on the right;</li>
   *   <li>{@code DOMAIN_STRIP}: neutral grey, the domain as the only line (title size, regular weight), the compass on the right.</li>
   * </ul>
   *
   * {@code tinted}: {@link #applyTellomiTint} gave this card its image's colours; they are kept on an icon strip only.
   * A null kind or text leaves the card as it is (first-party cards, group invites, call links, the compose box).
   */
  public void applyTellomiCardStyle(@Nullable TellomiLinkCardStyle.Kind kind, boolean showCompass, @Nullable TellomiLinkCardStyle.Text text, boolean lookalike, boolean tinted) {
    if (type == TYPE_COMPOSE || kind == null || text == null || firstPartyAvatar.getVisibility() == VISIBLE) {
      return;
    }
    if (kind == TellomiLinkCardStyle.Kind.ICON_STRIP && (!thumbnail.resolved() || thumbnail.getVisibility() != VISIBLE)) {
      return;
    }

    cardStyled = true;
    description.setVisibility(GONE);

    boolean iconStrip   = kind == TellomiLinkCardStyle.Kind.ICON_STRIP;
    boolean domainOnly  = text.getTitle() == null;
    boolean hasCompass  = showCompass && (kind == TellomiLinkCardStyle.Kind.TEXT_STRIP || kind == TellomiLinkCardStyle.Kind.DOMAIN_STRIP);
    boolean hasTrailing = iconStrip || hasCompass;

    int titleColor;
    int secondaryColor;
    if (tinted && TellomiLinkCardStyle.isTinted(kind)) {
      titleColor     = title.getCurrentTextColor();
      secondaryColor = site.getCurrentTextColor();
    } else {
      container.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.tellomi_link_card_neutral));
      titleColor     = ContextCompat.getColor(getContext(), org.signal.core.ui.R.color.signal_colorOnSurface);
      secondaryColor = ContextCompat.getColor(getContext(), org.signal.core.ui.R.color.signal_colorOnSurfaceVariant);
      showStateLayer(titleColor);
    }
    int dangerColor = ContextCompat.getColor(getContext(), org.signal.core.ui.R.color.signal_colorError);

    int[] padding = kind == TellomiLinkCardStyle.Kind.LARGE_IMAGE ? TellomiLinkCardStyle.LARGE_IMAGE_TEXT_PADDING_DP
                                                                    : hasTrailing ? TellomiLinkCardStyle.STRIP_PADDING_DP
                                                                                  : TellomiLinkCardStyle.STRIP_NO_TRAILING_PADDING_DP;
    container.setPaddingRelative(ViewUtil.dpToPx(padding[0]), ViewUtil.dpToPx(padding[1]), ViewUtil.dpToPx(padding[2]), ViewUtil.dpToPx(padding[3]));

    int trailingDp = iconStrip ? TellomiLinkCardStyle.ICON_DP : TellomiLinkCardStyle.COMPASS_DP;
    container.setMinimumHeight(kind == TellomiLinkCardStyle.Kind.LARGE_IMAGE ? 0 : ViewUtil.dpToPx(trailingDp + padding[1] + padding[3]));

    // The title line, or the domain as the only line.
    title.setVisibility(VISIBLE);
    title.setMaxLines(domainOnly ? 1 : TellomiLinkCardStyle.MAX_TITLE_LINES);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TellomiLinkCardStyle.TITLE_SP);
    if (domainOnly) {
      title.setText(text.getDomain());
      title.setTypeface(Typeface.create(xmlTitleTypeface, Typeface.NORMAL));
      title.setTextColor(lookalike ? dangerColor : secondaryColor);
      site.setVisibility(GONE);
    } else {
      title.setTypeface(Typeface.create(xmlTitleTypeface, Typeface.BOLD));
      title.setTextColor(lookalike ? dangerColor : titleColor);
      site.setText(text.getDomain());
      site.setTextSize(TypedValue.COMPLEX_UNIT_SP, TellomiLinkCardStyle.DOMAIN_SP);
      site.setTextColor(secondaryColor);
      site.setVisibility(VISIBLE);
    }

    int trailingId = iconStrip ? R.id.linkpreview_thumbnail : hasCompass ? R.id.linkpreview_link_icon : ConstraintLayout.LayoutParams.PARENT_ID;

    // The trailing slot: the icon, or the compass, centred on the strip at the end padding.
    if (iconStrip) {
      linkIcon.setVisibility(GONE);
      // The image keeps its own corners, not the bubble's (see setCorners).
      iconLayout = true;
      resizeThumbnail(TellomiLinkCardStyle.ICON_DP);
      int radius = ViewUtil.dpToPx(TellomiLinkCardStyle.ICON_RADIUS_DP);
      thumbnailState = thumbnailState.copy(radius, radius, radius, radius, thumbnailState.getDownloadListener());
      thumbnailState.applyState(thumbnail);
      centreAtEnd(thumbnail.get());
    } else if (hasCompass) {
      ImageView compass = (ImageView) linkIcon;
      compass.setImageResource(R.drawable.ic_tellomi_compass_26);
      ImageViewCompat.setImageTintList(compass, ColorStateList.valueOf(secondaryColor));
      ViewGroup.LayoutParams size = compass.getLayoutParams();
      size.width  = ViewUtil.dpToPx(TellomiLinkCardStyle.COMPASS_DP);
      size.height = ViewUtil.dpToPx(TellomiLinkCardStyle.COMPASS_DP);
      compass.setLayoutParams(size);
      centreAtEnd(compass);
      compass.setVisibility(VISIBLE);
    } else {
      linkIcon.setVisibility(GONE);
    }

    // Title and domain: one packed column, centred on the strip, from the start padding to the trailing slot.
    ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    titleParams.topToTop           = ConstraintLayout.LayoutParams.PARENT_ID;
    titleParams.topToBottom        = ConstraintLayout.LayoutParams.UNSET;
    titleParams.bottomToTop        = domainOnly ? ConstraintLayout.LayoutParams.UNSET : R.id.linkpreview_site;
    titleParams.bottomToBottom     = domainOnly ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
    titleParams.startToEnd         = ConstraintLayout.LayoutParams.UNSET;
    titleParams.startToStart       = ConstraintLayout.LayoutParams.PARENT_ID;
    titleParams.endToStart         = trailingId == ConstraintLayout.LayoutParams.PARENT_ID ? ConstraintLayout.LayoutParams.UNSET : trailingId;
    titleParams.endToEnd           = trailingId == ConstraintLayout.LayoutParams.PARENT_ID ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
    titleParams.verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
    titleParams.verticalBias       = 0.5f;
    titleParams.setMarginStart(0);
    titleParams.setMarginEnd(hasTrailing ? ViewUtil.dpToPx(TellomiLinkCardStyle.TRAILING_GAP_DP) : 0);
    title.setLayoutParams(titleParams);

    if (!domainOnly) {
      ConstraintLayout.LayoutParams siteParams = (ConstraintLayout.LayoutParams) site.getLayoutParams();
      siteParams.topToBottom    = R.id.linkpreview_title;
      siteParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
      siteParams.startToStart   = R.id.linkpreview_title;
      siteParams.endToEnd       = R.id.linkpreview_title;
      siteParams.topMargin      = ViewUtil.dpToPx(TellomiLinkCardStyle.LINE_GAP_DP);
      site.setLayoutParams(siteParams);
    }
  }

  /** Whether the card's own image (or a brand shell's bundled icon) is shown in the thumbnail slot. */
  public boolean hasTellomiThumbnail() {
    return thumbnail.resolved() && thumbnail.getVisibility() == VISIBLE;
  }

  /** The view at the card's end edge, centred top to bottom. */
  private static void centreAtEnd(@NonNull View view) {
    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) view.getLayoutParams();
    params.startToStart   = ConstraintLayout.LayoutParams.UNSET;
    params.startToEnd     = ConstraintLayout.LayoutParams.UNSET;
    params.endToStart     = ConstraintLayout.LayoutParams.UNSET;
    params.endToEnd       = ConstraintLayout.LayoutParams.PARENT_ID;
    params.topToTop       = ConstraintLayout.LayoutParams.PARENT_ID;
    params.topToBottom    = ConstraintLayout.LayoutParams.UNSET;
    params.bottomToTop    = ConstraintLayout.LayoutParams.UNSET;
    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
    params.setMarginEnd(0);
    view.setLayoutParams(params);
  }

  /** Undoes {@link #applyTellomiCardStyle}: back to the layout file. The thumbnail is put back by {@link #showTellomiVisual}. */
  private void resetTellomiCardStyle() {
    if (!cardStyled) {
      return;
    }
    cardStyled = false;
    title.setLayoutParams(new ConstraintLayout.LayoutParams(xmlTitleParams));
    site.setLayoutParams(new ConstraintLayout.LayoutParams(xmlSiteParams));
    linkIcon.setLayoutParams(new ConstraintLayout.LayoutParams(xmlLinkIconParams));
    ((ImageView) linkIcon).setImageResource(R.drawable.ic_link_24);
    ImageViewCompat.setImageTintList((ImageView) linkIcon, xmlLinkIconTint);
    title.setTextSize(TypedValue.COMPLEX_UNIT_PX, xmlTitleTextSize);
    title.setTypeface(xmlTitleTypeface);
    title.setMaxLines(2);
    title.setTextColor(titleColors);
    site.setTextSize(TypedValue.COMPLEX_UNIT_PX, xmlSiteTextSize);
    container.setPaddingRelative(xmlContainerPadding[0], xmlContainerPadding[1], xmlContainerPadding[2], xmlContainerPadding[3]);
    container.setMinimumHeight(0);
    if (thumbnail.resolved()) {
      ConstraintLayout.LayoutParams thumbnailParams = (ConstraintLayout.LayoutParams) thumbnail.get().getLayoutParams();
      thumbnailParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
      thumbnail.get().setLayoutParams(thumbnailParams);
    }
  }

  /**
   * Tellomi (card-visual §3.3): the whole card in the colours taken from its own image; null keeps the default colours.
   * Whether to apply them (never in a message request) and which set (light or dark) is decided by the caller.
   */
  public void applyTellomiTint(@Nullable TellomiLinkVisual.Colors colors) {
    if (type == TYPE_COMPOSE || colors == null) {
      return;
    }

    container.setBackgroundColor(colors.getBackground());
    title.setTextColor(colors.getText());
    description.setTextColor(withAlpha(colors.getText(), SECONDARY_TEXT_ALPHA));
    site.setTextColor(withAlpha(colors.getText(), SECONDARY_TEXT_ALPHA));

    // Black on a light card, white on a dark one: pressed, it is darkened or lightened, and keeps its own colour (§3.6, §3.8).
    showStateLayer(colors.getText());
  }

  /**
   * Tellomi (card-visual §3.6, §3.8): a pressed or focused card is covered by {@code color} at 12%, which is the card's text
   * colour, black or white, or the default one on a neutral card. The card's background is not touched. Conversation bubbles only.
   */
  private void showStateLayer(@ColorInt int color) {
    if (type == TYPE_COMPOSE) {
      return;
    }

    ColorDrawable     on    = new ColorDrawable(withAlpha(color, STATE_LAYER_ALPHA));
    StateListDrawable layer = new StateListDrawable();
    layer.addState(new int[] { android.R.attr.state_pressed }, on);
    layer.addState(new int[] { android.R.attr.state_focused }, on);
    layer.addState(StateSet.WILD_CARD, new ColorDrawable(Color.TRANSPARENT));
    setForeground(layer);
  }

  private void hideStateLayer() {
    setForeground(null);
  }

  /** What a screen reader says for the whole card, and whether the card's action is a button. Conversation bubbles only. */
  private void describeCard(@NonNull String description, boolean button) {
    if (type == TYPE_COMPOSE) {
      return;
    }

    actionIsButton = button;
    setContentDescription(description);
  }

  private static int withAlpha(int color, int alpha) {
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
  }

  /** Undone for every other card, since views are reused. */
  private void showTellomiVisual(boolean visual) {
    if (visual) {
      return;
    }

    resetTellomiCardStyle();
    iconLayout = false;
    if (type != TYPE_COMPOSE) {
      container.setBackgroundColor(ContextCompat.getColor(getContext(), org.signal.core.ui.R.color.signal_neutralSurface));
      showStateLayer(titleColors.getDefaultColor());
    }
    description.setTextColor(descriptionColors);
    site.setTextColor(siteColors);

    ConstraintLayout.LayoutParams thumbnailParams = thumbnail.resolved() ? (ConstraintLayout.LayoutParams) thumbnail.get().getLayoutParams() : null;
    if (thumbnailParams != null) {
      thumbnailParams.startToStart   = ConstraintLayout.LayoutParams.PARENT_ID;
      thumbnailParams.endToEnd       = ConstraintLayout.LayoutParams.UNSET;
      thumbnailParams.topToTop       = ConstraintLayout.LayoutParams.PARENT_ID;
      thumbnailParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
      thumbnail.get().setLayoutParams(thumbnailParams);
    }

    ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    titleParams.endToStart = R.id.linkpreview_link_icon;
    titleParams.setMarginEnd(ViewUtil.dpToPx(TITLE_END_MARGIN_DP));
    title.setLayoutParams(titleParams);

    ConstraintLayout.LayoutParams siteParams = (ConstraintLayout.LayoutParams) site.getLayoutParams();
    siteParams.topToBottom    = R.id.linkpreview_description;
    siteParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
    siteParams.startToStart   = ConstraintLayout.LayoutParams.PARENT_ID;
    siteParams.endToEnd       = ConstraintLayout.LayoutParams.PARENT_ID;
    siteParams.topMargin      = ViewUtil.dpToPx(2);
    site.setLayoutParams(siteParams);
  }

  /** The title and the subtitle sit one above the other, centered beside the avatar (Telegram's card, card-visual §5.2). */
  private void stackSubtitleUnderTitle(boolean leadingVisible, int leadingId) {
    if (!leadingVisible) {
      return;
    }

    ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    titleParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
    titleParams.bottomToTop    = R.id.linkpreview_description;
    titleParams.verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
    title.setLayoutParams(titleParams);

    ConstraintLayout.LayoutParams descriptionParams = (ConstraintLayout.LayoutParams) description.getLayoutParams();
    descriptionParams.topToBottom     = R.id.linkpreview_title;
    descriptionParams.bottomToBottom  = leadingId;
    descriptionParams.startToStart    = R.id.linkpreview_title;
    descriptionParams.topMargin       = ViewUtil.dpToPx(2);
    description.setLayoutParams(descriptionParams);
  }

  /** Puts the description back where the layout file has it, under the header. */
  private void resetDescriptionPlacement() {
    ConstraintLayout.LayoutParams descriptionParams = (ConstraintLayout.LayoutParams) description.getLayoutParams();
    descriptionParams.topToBottom    = R.id.linkpreview_header_barrier;
    descriptionParams.bottomToTop    = ConstraintLayout.LayoutParams.UNSET;
    descriptionParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
    descriptionParams.startToStart   = ConstraintLayout.LayoutParams.PARENT_ID;
    descriptionParams.endToEnd       = ConstraintLayout.LayoutParams.PARENT_ID;
    descriptionParams.topMargin      = ViewUtil.dpToPx(8);
    description.setLayoutParams(descriptionParams);
    description.setMaxLines(type == TYPE_COMPOSE ? COMPOSE_DESCRIPTION_MAX_LINES : CONVERSATION_DESCRIPTION_MAX_LINES);
  }

  private void resizeThumbnail(int dp) {
    if (!thumbnail.resolved()) {
      return;
    }
    ViewGroup.LayoutParams params = thumbnail.get().getLayoutParams();
    params.width  = ViewUtil.dpToPx(dp);
    params.height = ViewUtil.dpToPx(dp);
    thumbnail.get().setLayoutParams(params);
  }

  private void showThumbnailDrawable(@NonNull RequestManager requestManager, @Nullable Drawable drawable) {
    if (drawable == null) {
      return;
    }
    thumbnail.setVisibility(VISIBLE);
    thumbnailState.applyState(thumbnail);
    thumbnail.get().setImageDrawable(requestManager, drawable);
    thumbnail.get().showSecondaryText(false);
    thumbnail.get().setOutlineEnabled(false);
  }

  private static void setHorizontalMargins(@NonNull View view, int start, int end, int bottom) {
    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) view.getLayoutParams();
    params.setMarginStart(start);
    params.setMarginEnd(end);
    params.bottomMargin = bottom;
    view.setLayoutParams(params);
  }

  /** Undone for every other card, since views are reused. */
  private void showFirstParty(boolean firstParty) {
    if (firstParty) {
      return;
    }
    firstPartyAvatar.setVisibility(GONE);
    action.setVisibility(GONE);
    resizeThumbnail(DEFAULT_THUMBNAIL_DP);
    resetDescriptionPlacement();
    resetTitleChain();
    if (type != TYPE_COMPOSE) {
      divider.setVisibility(GONE);
      setHorizontalMargins(divider, 0, 0, 0);
    }
    setHorizontalMargins(action, 0, 0, 0);
  }

  /**
   * Tellomi (card-visual §3.7): the no-image card: a link icon at the end of the title line, and the domain in the
   * danger colour when it imitates a well-known one (ADR-0063 §6.1). Undone for every other card, since views are reused.
   */
  private void showPlainLink(boolean plainLink, boolean lookalike) {
    linkIcon.setVisibility(plainLink ? VISIBLE : GONE);
    if (plainLink && lookalike) {
      title.setTextColor(ContextCompat.getColor(getContext(), org.signal.core.ui.R.color.signal_colorError));
    } else {
      title.setTextColor(titleColors);
    }
  }

  private void resetTitleChain() {
    ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    titleParams.bottomToTop        = ConstraintLayout.LayoutParams.UNSET;
    titleParams.verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_SPREAD;
    titleParams.verticalBias       = 0.5f;
    title.setLayoutParams(titleParams);
  }

  private void setTitleText(@NonNull String text, boolean officialBadge) {
    if (officialBadge) {
      SpannableStringBuilder styled = new SpannableStringBuilder(text).append("  ");
      int                    start  = styled.length();
      styled.append(getContext().getString(R.string.TellomiLinkCard__official_badge));
      styled.setSpan(new ForegroundColorSpan(site.getCurrentTextColor()), start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(new RelativeSizeSpan(0.85f), start, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      title.setText(styled);
    } else {
      title.setText(text);
    }
  }

  private void alignTitleWithThumbnail(boolean thumbnailVisible) {
    alignTitleWithLeading(thumbnailVisible, R.id.linkpreview_thumbnail);
  }

  private void alignTitleWithLeading(boolean leadingVisible, int leadingId) {
    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) title.getLayoutParams();
    if (leadingVisible) {
      params.topToTop       = leadingId;
      params.bottomToBottom = leadingId;
      params.startToEnd     = leadingId;
      params.startToStart   = ConstraintLayout.LayoutParams.UNSET;
      params.setMarginStart(ViewUtil.dpToPx(8));
    } else {
      params.topToTop       = ConstraintLayout.LayoutParams.PARENT_ID;
      params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
      params.startToEnd     = ConstraintLayout.LayoutParams.UNSET;
      params.startToStart   = ConstraintLayout.LayoutParams.PARENT_ID;
      params.setMarginStart(0);
    }
    title.setLayoutParams(params);
  }

  public void setCorners(int topStart, int topEnd) {
    if (iconLayout) {
      // The image is not at the bubble's corner any more: it keeps its own rounding.
      cornerMask.setRadii(ViewUtil.isRtl(this) ? topEnd : topStart, ViewUtil.isRtl(this) ? topStart : topEnd, 0, 0);
      postInvalidate();
      return;
    }

    if (ViewUtil.isRtl(this)) {
      cornerMask.setRadii(topEnd, topStart, 0, 0);
      thumbnailState = thumbnailState.copy(
          defaultRadius,
          topEnd,
          defaultRadius,
          defaultRadius,
          thumbnailState.getDownloadListener()
      );
      thumbnailState.applyState(thumbnail);
    } else {
      cornerMask.setRadii(topStart, topEnd, 0, 0);
      thumbnailState = thumbnailState.copy(
          topStart,
          defaultRadius,
          defaultRadius,
          defaultRadius,
          thumbnailState.getDownloadListener()
      );
      thumbnailState.applyState(thumbnail);
    }
    postInvalidate();
  }

  /**
   * Tellomi (card-visual §3.5): after {@link #setCorners}, round the bottom corners too, for a card that ends the
   * bubble.
   */
  public void setBottomCorners(int bottomStart, int bottomEnd) {
    if (ViewUtil.isRtl(this)) {
      cornerMask.setBottomLeftRadius(bottomEnd);
      cornerMask.setBottomRightRadius(bottomStart);
    } else {
      cornerMask.setBottomLeftRadius(bottomStart);
      cornerMask.setBottomRightRadius(bottomEnd);
    }
    postInvalidate();
  }

  public void setCloseClickedListener(@Nullable CloseClickedListener closeClickedListener) {
    this.closeClickedListener = closeClickedListener;
  }

  public void setDownloadClickedListener(SlidesClickedListener listener) {
    thumbnailState = thumbnailState.withDownloadListener(listener);
    thumbnailState.applyState(thumbnail);
  }

  /**
   * Tellomi（ADR-0063 §5.1 铁律 2，tellomi/tellomi#1422）：取不到预览就不显示预览区，输入框和分享页都一样，
   * 不再出「No link preview available」。唯一的例外是群邀请确定已失效：照上游提示发送者「This group link is not active」。
   */
  public static boolean isShownWithoutPreview(@Nullable LinkPreviewRepository.Error error) {
    return error == LinkPreviewRepository.Error.GROUP_LINK_INACTIVE;
  }

  private @StringRes static int getLinkPreviewErrorString(@Nullable LinkPreviewRepository.Error customError) {
    return customError == LinkPreviewRepository.Error.GROUP_LINK_INACTIVE ? R.string.LinkPreviewView_this_group_link_is_not_active
                                                                          : R.string.LinkPreviewView_no_link_preview_available;
  }

  private static String formatDate(long date) {
    DateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
    return dateFormat.format(date);
  }

  public interface CloseClickedListener {
    void onCloseClicked();
  }
}
