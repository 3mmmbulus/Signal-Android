/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.animation.doOnEnd
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ConversationLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar.Duration
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.signal.core.models.database.StickerRecord
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.ui.getWindowSizeClass
import org.signal.core.ui.isSplitPane
import org.signal.core.ui.logging.LoggingFragment
import org.signal.core.ui.motion.TellomiMotion
import org.signal.core.ui.permissions.Permissions
import org.signal.core.ui.view.Stub
import org.signal.core.util.ByteLimitInputFilter
import org.signal.core.util.Debouncer
import org.signal.core.util.DrawableUtil
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.Result
import org.signal.core.util.ThreadUtil
import org.signal.core.util.bytes
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.concurrent.addTo
import org.signal.core.util.dp
import org.signal.core.util.encourageNewBrowserTab
import org.signal.core.util.logging.Log
import org.signal.core.util.orNull
import org.signal.core.util.requireDrawable
import org.signal.core.util.requireParcelableCompat
import org.signal.core.util.setActionItemTint
import org.signal.donations.InAppPaymentType
import org.signal.emoji.EmojiEventListener
import org.signal.emoji.EmojiProvider
import org.signal.emoji.EmojiUtil
import org.signal.mediasend.MediaSendFlowActivityContract
import org.signal.mediasend.screens.files.PickedFileGrants
import org.signal.ringrtc.CallLinkRootKey
import org.thoughtcrime.securesms.BlockUnblockDialog
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.MuteDialog
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.AttachmentSaver
import org.thoughtcrime.securesms.audio.AudioRecorder
import org.thoughtcrime.securesms.backup.v2.ui.subscription.BackupUpgradeAvailabilityChecker
import org.thoughtcrime.securesms.backup.v2.ui.warning.guardAgainstRecoveryKeyPaste
import org.thoughtcrime.securesms.badges.gifts.OpenableGift
import org.thoughtcrime.securesms.badges.gifts.OpenableGiftItemDecoration
import org.thoughtcrime.securesms.badges.gifts.viewgift.received.ViewReceivedGiftBottomSheet
import org.thoughtcrime.securesms.badges.gifts.viewgift.sent.ViewSentGiftBottomSheet
import org.thoughtcrime.securesms.billing.upgrade.UpgradeToStartMediaBackupSheet
import org.thoughtcrime.securesms.calls.YouAreAlreadyInACallSnackbar
import org.thoughtcrime.securesms.components.AnimatingToggle
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.ConversationSearchBottomBar
import org.thoughtcrime.securesms.components.HidingLinearLayout
import org.thoughtcrime.securesms.components.InputPanel
import org.thoughtcrime.securesms.components.ProgressCardDialogFragment
import org.thoughtcrime.securesms.components.RotatedTiledDrawable
import org.thoughtcrime.securesms.components.ScrollToPositionDelegate
import org.thoughtcrime.securesms.components.SendButton
import org.thoughtcrime.securesms.components.SignalProgressDialog
import org.thoughtcrime.securesms.components.TellomiJumpScrollStrategy
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.components.compose.ActionModeTopBarView
import org.thoughtcrime.securesms.components.compose.mediakeyboard.MediaKeyboardController
import org.thoughtcrime.securesms.components.compose.mediakeyboard.MediaKeyboardEvents
import org.thoughtcrime.securesms.components.compose.mediakeyboard.MediaKeyboardKey
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.components.location.SignalPlace
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalBottomActionBar
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.CheckoutFlowActivity
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.DonateToSignalFragment
import org.thoughtcrime.securesms.components.snackbars.makeSnackbar
import org.thoughtcrime.securesms.components.spoiler.SpoilerAnnotation
import org.thoughtcrime.securesms.components.voice.VoiceNoteDraft
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaControllerOwner
import org.thoughtcrime.securesms.components.voice.VoiceNotePlaybackState
import org.thoughtcrime.securesms.components.voice.VoiceNotePlayerView
import org.thoughtcrime.securesms.compose.FragmentBackPressedInfo
import org.thoughtcrime.securesms.compose.FragmentBackPressedInfoProvider
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey.RecipientSearchKey
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.contactshare.ContactUtil
import org.thoughtcrime.securesms.contactshare.SharedContactDetailsActivityV2
import org.thoughtcrime.securesms.contactshare.SharedContactSource
import org.thoughtcrime.securesms.contactshare.resolveOrCreateSignalRecipient
import org.thoughtcrime.securesms.conversation.AttachmentKeyboardButton
import org.thoughtcrime.securesms.conversation.BadDecryptLearnMoreDialog
import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.conversation.ConversationArgs
import org.thoughtcrime.securesms.conversation.ConversationBottomSheetCallback
import org.thoughtcrime.securesms.conversation.ConversationData
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationIntents.ConversationScreenType
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.conversation.ConversationItemSelection
import org.thoughtcrime.securesms.conversation.ConversationItemSwipeCallback
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ConversationOptionsMenu
import org.thoughtcrime.securesms.conversation.ConversationSearchViewModel
import org.thoughtcrime.securesms.conversation.ConversationUpdateTick
import org.thoughtcrime.securesms.conversation.MarkReadHelper
import org.thoughtcrime.securesms.conversation.MenuState
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.conversation.MessageStyler.getStyling
import org.thoughtcrime.securesms.conversation.PinnedMessagesBottomSheet
import org.thoughtcrime.securesms.conversation.ReactionAction
import org.thoughtcrime.securesms.conversation.ReenableScheduledMessagesDialogFragment
import org.thoughtcrime.securesms.conversation.ScheduleMessageContextMenu
import org.thoughtcrime.securesms.conversation.ScheduleMessageDialogCallback
import org.thoughtcrime.securesms.conversation.ScheduleMessageTimePickerBottomSheet
import org.thoughtcrime.securesms.conversation.ScheduleMessageTimePickerBottomSheet.Companion.showSchedule
import org.thoughtcrime.securesms.conversation.ScheduledMessagesBottomSheet
import org.thoughtcrime.securesms.conversation.ScheduledMessagesRepository
import org.thoughtcrime.securesms.conversation.ShowAdminsBottomSheetDialog
import org.thoughtcrime.securesms.conversation.TellomiPressFeedback
import org.thoughtcrime.securesms.conversation.clicklisteners.PollVotesFragment
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer
import org.thoughtcrime.securesms.conversation.drafts.DraftRepository
import org.thoughtcrime.securesms.conversation.drafts.DraftRepository.ShareOrDraftData
import org.thoughtcrime.securesms.conversation.drafts.DraftViewModel
import org.thoughtcrime.securesms.conversation.mutiselect.ConversationItemAnimator
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectItemDecoration
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.Multiselectable
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardBottomSheet
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardRepository
import org.thoughtcrime.securesms.conversation.mutiselect.forward.TellomiForwardGridBottomSheet
import org.thoughtcrime.securesms.conversation.quotes.MessageQuotesBottomSheet
import org.thoughtcrime.securesms.conversation.ui.edit.EditMessageHistoryDialog
import org.thoughtcrime.securesms.conversation.ui.error.EnableCallNotificationSettingsDialog
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQuery
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryChangedListener
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryReplacement
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryResultsControllerV2
import org.thoughtcrime.securesms.conversation.ui.inlinequery.InlineQueryViewModelV2
import org.thoughtcrime.securesms.conversation.v2.computed.ConversationMessageComputeWorkers
import org.thoughtcrime.securesms.conversation.v2.data.AvatarDownloadStateCache
import org.thoughtcrime.securesms.conversation.v2.data.ConversationMessageElement
import org.thoughtcrime.securesms.conversation.v2.data.DeletedMessageTombstoneCache
import org.thoughtcrime.securesms.conversation.v2.groups.ConversationGroupCallViewModel
import org.thoughtcrime.securesms.conversation.v2.groups.ConversationGroupViewModel
import org.thoughtcrime.securesms.conversation.v2.items.ChatColorsDrawable
import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.database.model.InMemoryMessageRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Quote
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.database.withAttachments
import org.thoughtcrime.securesms.databinding.V2ConversationBackgroundBinding
import org.thoughtcrime.securesms.databinding.V2ConversationFragmentBinding
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.GroupCallPeekEvent
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ItemDecoration
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackController
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4PlaybackPolicy
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionPlayerHolder
import org.thoughtcrime.securesms.giph.mp4.GiphyMp4ProjectionRecycler
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupMigrationMembershipChange
import org.thoughtcrime.securesms.groups.memberlabel.MemberLabelActivity
import org.thoughtcrime.securesms.groups.memberlabel.MemberLabelEducationSheet
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason
import org.thoughtcrime.securesms.groups.ui.GroupErrors
import org.thoughtcrime.securesms.groups.ui.LeaveGroupDialog
import org.thoughtcrime.securesms.groups.ui.invitesandrequests.ManagePendingAndRequestingMembersActivity
import org.thoughtcrime.securesms.groups.ui.invitesandrequests.invite.GroupLinkInviteFriendsBottomSheetDialogFragment
import org.thoughtcrime.securesms.groups.ui.managegroup.dialogs.GroupDescriptionDialog
import org.thoughtcrime.securesms.groups.ui.migration.GroupsV1MigrationInfoBottomSheetDialogFragment
import org.thoughtcrime.securesms.groups.ui.migration.GroupsV1MigrationSuggestionsDialog
import org.thoughtcrime.securesms.groups.v2.GroupBlockJoinRequestResult
import org.thoughtcrime.securesms.invites.InviteActions
import org.thoughtcrime.securesms.jobs.AttachmentBackfill
import org.thoughtcrime.securesms.jobs.ServiceOutageDetectionJob
import org.thoughtcrime.securesms.keyboard.KeyboardPage
import org.thoughtcrime.securesms.keyboard.KeyboardPagerViewModel
import org.thoughtcrime.securesms.keyboard.KeyboardUtil
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardPageFragment
import org.thoughtcrime.securesms.keyboard.emoji.search.EmojiSearchFragment
import org.thoughtcrime.securesms.keyboard.gif.GifKeyboardPageFragment
import org.thoughtcrime.securesms.keyboard.sticker.StickerKeyboardPageFragment
import org.thoughtcrime.securesms.keyboard.sticker.StickerSearchDialogFragment
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModelV2
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOpener
import org.thoughtcrime.securesms.longmessage.LongMessageFragment
import org.thoughtcrime.securesms.main.MainDetailRoute
import org.thoughtcrime.securesms.main.MainListRoute
import org.thoughtcrime.securesms.main.MainNavigationEventSink
import org.thoughtcrime.securesms.main.MainNavigationEvents
import org.thoughtcrime.securesms.main.MainNavigationViewModel
import org.thoughtcrime.securesms.main.MainSnackbarHostKey
import org.thoughtcrime.securesms.mediaoverview.MediaOverviewActivity
import org.thoughtcrime.securesms.mediapreview.MediaIntentFactory
import org.thoughtcrime.securesms.mediapreview.MediaPreviewActivity
import org.thoughtcrime.securesms.mediapreview.MediaPreviewCache
import org.thoughtcrime.securesms.mediapreview.TellomiMediaReturnTarget
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.TellomiMutedVideos
import org.thoughtcrime.securesms.mediasend.TellomiTimedSends
import org.thoughtcrime.securesms.megaphone.ClientDeprecatedActivity
import org.thoughtcrime.securesms.messagerequests.MessageRequestRepository
import org.thoughtcrime.securesms.mms.AttachmentManager
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.GifSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.PushMediaConstraints
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.mms.StickerSlide
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.nicknames.NicknameActivity
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.payments.preferences.PaymentsActivity
import org.thoughtcrime.securesms.polls.Poll
import org.thoughtcrime.securesms.polls.PollOption
import org.thoughtcrime.securesms.polls.PollRecord
import org.thoughtcrime.securesms.profiles.manage.EditProfileActivity
import org.thoughtcrime.securesms.profiles.spoofing.ReviewCardDialogFragment
import org.thoughtcrime.securesms.ratelimit.RecaptchaProofBottomSheetFragment
import org.thoughtcrime.securesms.ratelimit.RecaptchaRequiredEvent
import org.thoughtcrime.securesms.reactions.ReactionsBottomSheetDialogFragment
import org.thoughtcrime.securesms.reactions.ReactionsConversationView
import org.thoughtcrime.securesms.reactions.TellomiReactionFlyIn
import org.thoughtcrime.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientExporter
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.ui.about.AboutSheet
import org.thoughtcrime.securesms.recipients.ui.bottomsheet.RecipientBottomSheetDialogFragment
import org.thoughtcrime.securesms.recipients.ui.disappearingmessages.RecipientDisappearingMessagesActivity
import org.thoughtcrime.securesms.registration.ui.RegistrationActivity
import org.thoughtcrime.securesms.revealable.ViewOnceMessageActivity
import org.thoughtcrime.securesms.revealable.ViewOnceUtil
import org.thoughtcrime.securesms.safety.SafetyNumberBottomSheet
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.stickers.StickerEventListener
import org.thoughtcrime.securesms.stickers.StickerLocator
import org.thoughtcrime.securesms.stickers.StickerPackInstallEvent
import org.thoughtcrime.securesms.stickers.manage.StickerManagementScreen
import org.thoughtcrime.securesms.stickers.preview.StickerPackPreviewActivity
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.viewer.StoryViewerActivity
import org.thoughtcrime.securesms.updaterequired.UpdateRequired
import org.thoughtcrime.securesms.util.BubbleUtil
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DeleteDialog
import org.thoughtcrime.securesms.util.Dialogs
import org.thoughtcrime.securesms.util.DoubleClickDebouncer
import org.thoughtcrime.securesms.util.FileProviderUtil
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import org.thoughtcrime.securesms.util.MessageConstraintsUtil.getEditMessageThresholdHours
import org.thoughtcrime.securesms.util.MessageConstraintsUtil.isValidEditMessageSend
import org.thoughtcrime.securesms.util.MessageUtil
import org.thoughtcrime.securesms.util.PlayStoreUtil
import org.thoughtcrime.securesms.util.Projection
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.SignalLocalMetrics
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.UriUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.atMidnight
import org.thoughtcrime.securesms.util.atUTC
import org.thoughtcrime.securesms.util.doAfterNextLayout
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.getPoll
import org.thoughtcrime.securesms.util.getQuote
import org.thoughtcrime.securesms.util.getRecordQuoteType
import org.thoughtcrime.securesms.util.hasAudio
import org.thoughtcrime.securesms.util.hasGiftBadge
import org.thoughtcrime.securesms.util.hasLinkPreview
import org.thoughtcrime.securesms.util.hasNonTextSlide
import org.thoughtcrime.securesms.util.isValidReactionTarget
import org.thoughtcrime.securesms.util.padding
import org.thoughtcrime.securesms.util.setIncognitoKeyboardEnabled
import org.thoughtcrime.securesms.util.toMillis
import org.thoughtcrime.securesms.util.viewModel
import org.thoughtcrime.securesms.util.visible
import org.thoughtcrime.securesms.verify.VerifyIdentityActivity
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperDimLevelUtil
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.Optional
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import org.signal.core.ui.R as CoreUiR

/**
 * A single unified fragment for Conversations.
 */
class ConversationFragment :
  LoggingFragment(),
  ReactWithAnyEmojiBottomSheetDialogFragment.Callback,
  ReactionsBottomSheetDialogFragment.Callback,
  EmojiKeyboardPageFragment.Callback,
  EmojiEventListener,
  GifKeyboardPageFragment.Host,
  StickerEventListener,
  StickerKeyboardPageFragment.Callback,
  MediaKeyboard.MediaKeyboardListener,
  EmojiSearchFragment.Callback,
  ScheduleMessageTimePickerBottomSheet.ScheduleCallback,
  ScheduleMessageDialogCallback,
  ConversationBottomSheetCallback,
  SafetyNumberBottomSheet.Callbacks,
  EnableCallNotificationSettingsDialog.Callback,
  MultiselectForwardBottomSheet.Callback,
  DoubleTapEditEducationSheet.Callback,
  FragmentBackPressedInfoProvider {

  companion object {
    private val TAG = Log.tag(ConversationFragment::class.java)
    private val POLL_SPINNER_DELAY = 500.milliseconds
    private val PIN_SPINNER_DELAY = 500.milliseconds

    private const val ACTION_PINNED_SHORTCUT = "action_pinned_shortcut"
    private const val SAVED_STATE_IS_SEARCH_REQUESTED = "is_search_requested"
    private const val EMOJI_SEARCH_FRAGMENT_TAG = "EmojiSearchFragment"
    private const val MESSAGE_DETAILS_TAG = "MessageDetailsFragment"

    /** Tellomi (A-06): blur radius behind the long-press overlay once its shade is fully in. */
    private const val OVERLAY_BLUR_RADIUS_DP = 12f

    private const val SCROLL_HEADER_ANIMATION_DURATION: Long = 100L
    private const val SCROLL_HEADER_CLOSE_DELAY: Long = SCROLL_HEADER_ANIMATION_DURATION * 4
    private const val IS_SCROLLED_TO_BOTTOM_THRESHOLD: Int = 2

    private val RECEIVE_CONTENT_MIME_TYPES = arrayOf(
      "image/jpeg",
      "image/png",
      "image/gif",
      "image/webp",
      "image/heic",
      "image/heif",
      "image/avif"
    )
  }

  private val args: ConversationArgs by lazy {
    ConversationIntents.readArgsFromBundle(requireArguments())
  }

  private val conversationRecipientRepository: ConversationRecipientRepository by viewModel {
    ConversationRecipientRepository(args.threadId)
  }

  private val messageRequestRepository: MessageRequestRepository by lazy {
    MessageRequestRepository(requireContext())
  }

  private val checkoutLauncher by lazy {
    registerForActivityResult(CheckoutFlowActivity.Contract()) {}
  }

  private val disposables = LifecycleDisposable()
  private val backgroundBinding by ViewBinderDelegate(bindingFactory = { V2ConversationBackgroundBinding.bind(conversationBackground) })
  private val binding by ViewBinderDelegate(bindingFactory = { V2ConversationFragmentBinding.bind(conversationContent) }, onBindingWillBeDestroyed = { _binding ->
    _binding.conversationInputPanel.embeddedTextEditor.apply {
      setOnEditorActionListener(null)
      setCursorPositionChangedListener(null)
      setOnKeyListener(null)
      removeTextChangedListener(composeTextEventsListener)
      setStylingChangedListener(null)
      setOnClickListener(null)
      ViewCompat.setOnReceiveContentListener(this, null, null)
    }

    dataObserver?.let {
      adapter.unregisterAdapterDataObserver(it)
    }

    scrollListener?.let {
      _binding.conversationItemRecycler.removeOnScrollListener(it)
    }
    scrollListener = null

    _binding.conversationItemRecycler.adapter = null

    textDraftSaveDebouncer.clear()
  })

  private val viewModel: ConversationViewModel by viewModel {
    ConversationViewModel(
      threadId = args.threadId,
      requestedStartingPosition = args.startingPosition,
      repository = ConversationRepository(localContext = requireContext(), isInBubble = args.conversationScreenType == ConversationScreenType.BUBBLE),
      recipientRepository = conversationRecipientRepository,
      messageRequestRepository = messageRequestRepository,
      scheduledMessagesRepository = ScheduledMessagesRepository()
    )
  }

  private val linkPreviewViewModel: LinkPreviewViewModelV2 by viewModel {
    LinkPreviewViewModelV2(it.createSavedStateHandle(), enablePlaceholder = false)
  }

  private val groupCallViewModel: ConversationGroupCallViewModel by viewModel {
    ConversationGroupCallViewModel(conversationRecipientRepository)
  }

  private val conversationGroupViewModel: ConversationGroupViewModel by viewModels(
    factoryProducer = {
      ConversationGroupViewModel.Factory(conversationRecipientRepository)
    }
  )

  private val messageRequestViewModel: MessageRequestViewModel by viewModel {
    MessageRequestViewModel(args.threadId, conversationRecipientRepository, messageRequestRepository)
  }

  private val draftViewModel: DraftViewModel by viewModel {
    DraftViewModel(threadId = args.threadId, repository = DraftRepository(conversationArguments = args))
  }

  private val searchViewModel: ConversationSearchViewModel by viewModel {
    ConversationSearchViewModel(getString(R.string.note_to_self))
  }

  private val keyboardPagerViewModel: KeyboardPagerViewModel by activityViewModels()

  private val stickerViewModel: StickerSuggestionsViewModel by viewModel {
    StickerSuggestionsViewModel()
  }

  private val inlineQueryViewModel: InlineQueryViewModelV2 by viewModel {
    InlineQueryViewModelV2(conversationRecipientRepository)
  }

  private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels { MainNavigationViewModel.Factory() }

  private val inlineQueryController: InlineQueryResultsControllerV2 by lazy {
    InlineQueryResultsControllerV2(
      this,
      args.threadId,
      inlineQueryViewModel,
      inputPanel,
      (conversationContent as ViewGroup),
      composeText
    )
  }

  private val voiceNotePlayerListener: VoiceNotePlayerView.Listener by lazy {
    VoiceNotePlayerViewListener()
  }

  private val conversationTooltips = ConversationTooltips(this)
  private val colorizer = ColorizerV2()
  private val textDraftSaveDebouncer = Debouncer(500)
  private val doubleTapToEditDebouncer = DoubleClickDebouncer(200)
  private val recentEmojis: RecentEmojiPageModel by lazy { RecentEmojiPageModel(AppDependencies.application, TextSecurePreferences.RECENT_STORAGE_KEY) }
  private val nicknameEditActivityLauncher = registerForActivityResult(NicknameActivity.Contract()) {}
  private val handler = Handler(Looper.getMainLooper())

  private lateinit var layoutManager: ConversationLayoutManager
  private lateinit var markReadHelper: MarkReadHelper
  private lateinit var giphyMp4ProjectionRecycler: GiphyMp4ProjectionRecycler
  private lateinit var addToContactsLauncher: ActivityResultLauncher<Intent>
  private lateinit var conversationActivityResultContracts: ConversationActivityResultContracts
  private lateinit var scrollToPositionDelegate: ScrollToPositionDelegate
  private lateinit var adapter: ConversationAdapterV2
  private var dragSelect: TellomiDragSelect? = null
  private lateinit var typingIndicatorAdapter: ConversationTypingIndicatorAdapter
  private lateinit var recyclerViewColorizer: RecyclerViewColorizer
  private lateinit var attachmentManager: AttachmentManager
  private lateinit var multiselectItemDecoration: MultiselectItemDecoration
  private lateinit var openableGiftItemDecoration: OpenableGiftItemDecoration
  private lateinit var conversationHeaderPositionDecoration: ConversationHeaderPositionDecoration
  private lateinit var conversationItemDecorations: ConversationItemDecorations
  private lateinit var optionsMenuCallback: ConversationOptionsMenuCallback

  private lateinit var mainNavigationEventSink: MainNavigationEventSink

  private var animationsAllowed = false
  private var pinnedShortcutReceiver: BroadcastReceiver? = null
  private var searchMenuItem: MenuItem? = null

  private var isSearchRequested: Boolean = false
    set(value) {
      field = value
      viewModel.setIsSearchRequested(value)
    }

  private var previousPage: KeyboardPage? = null
  private var previousPages: Set<KeyboardPage>? = null
  private var reShowScheduleMessagesBar: Boolean = false
  private var composeTextEventsListener: ComposeTextEventsListener? = null
  private var dataObserver: DataObserver? = null
  private var menuProvider: ConversationOptionsMenu.Provider? = null
  private var scrollListener: ScrollListener? = null
  private var keyboardEvents: KeyboardEvents? = null
  private var progressDialog: ProgressCardDialogFragment? = null
  private var firstPinRender: Boolean = true
  private var skipNextBackPressHandling: Boolean = false
  private var collapsibleEventScrollPosition: CollapsibleEventScrollPosition? = null
  private var releaseNotesLayoutApplied: Boolean = false
  private var releaseNotesWallpaperApplied: Boolean = false

  private var applyToolbarPaddingRunnable: Runnable? = null

  // Tellomi (interaction audit A-08): glides to the message instead of jumping, and pulses it once it is there.
  private val jumpAndPulseScrollStrategy = TellomiJumpScrollStrategy(
    onArrived = { position ->
      isJumpInFlight = false
      adapter.pulseAtPosition(position)
    }
  )

  /** Tellomi (A-08): a jump is gliding; passing through the bottom on the way does not count as having gone back there. */
  private var isJumpInFlight = false

  /**
   * Tellomi (A-08, "跳走了回不来"): the message whose quote was just tapped. The scroll-to-bottom button's next tap goes back to
   * it before going to the bottom, as Telegram's down arrow does; reaching the bottom forgets it.
   */
  private var quoteReturnMessageId: Long? = null

  private val motionEventRelay: MotionEventRelay by viewModels(ownerProducer = { requireActivity() })

  /** The conversation's view hierarchy, hosted inside the ComposeView returned by [onCreateView]. */
  private lateinit var conversationContent: View

  /** The wallpaper, drawn behind [conversationContent]. */
  private lateinit var conversationBackground: View

  private val chatScreenViewModel: ChatScreenViewModel by viewModels()

  /** Stable across recomposition, so view code can ask for a keyboard from a click listener. */
  private val mediaKeyboardController: MediaKeyboardController by lazy(LazyThreadSafetyMode.NONE) {
    MediaKeyboardController(
      initialKeyboardHeightPx = chatScreenViewModel.getStoredKeyboardHeight(
        isLandscape = isLandscape(),
        minimumPx = resources.getDimensionPixelSize(R.dimen.default_custom_keyboard_size)
      )
    )
  }

  /** Colours for the scrims ChatScreen paints. */
  private val chatScrims = ChatScrimState()

  private val container: ChatInputController by lazy(LazyThreadSafetyMode.NONE) {
    ChatInputController(requireContext(), mediaKeyboardController)
  }

  private val inputPanel: InputPanel
    get() = binding.conversationInputPanel.root

  /** Tellomi（交互审计 A-11）：输入区高度变化（多一行、链接预览）时输入区和列表一起滑过去。 */
  private var composerHeightAnimator: TellomiComposerHeightAnimator? = null

  /** Tellomi（交互审计 A-15）：多选操作栏该不该显示、正在走的那一次过渡、第几次（过时的回调认出来就不做）。 */
  private var selectionBarsShown = false
  private var selectionBarsAnimator: ValueAnimator? = null
  private var selectionBarsGeneration = 0

  private val composeText: ComposeText
    get() = binding.conversationInputPanel.embeddedTextEditor

  private val sendButton: SendButton
    get() = binding.conversationInputPanel.sendButton

  private val sendEditButton: ImageButton
    get() = binding.conversationInputPanel.sendEditButton

  private val bottomActionBar: SignalBottomActionBar
    get() = binding.conversationBottomActionBar

  private val searchNav: ConversationSearchBottomBar
    get() = binding.conversationSearchBottomBar.root

  private val actionModeTopBarView: ActionModeTopBarView
    get() = binding.actionModeTopBar

  private val scheduledMessagesStub: Stub<View> by lazy { Stub(binding.scheduledMessagesStub) }

  private val reactionOverlay: ChatReactionOverlayController by lazy(LazyThreadSafetyMode.NONE) {
    ChatReactionOverlayController(
      context = requireContext(),
      hapticView = { chatHost },
      menuAnchor = { reactionMenuAnchor },
      onReactionSelected = { messageRecord, emoji, flyInSource ->
        if (flyInSource != null) {
          startReactionFlyIn(messageRecord.id, emoji, flyInSource)
        }
        announceReaction(messageRecord, emoji)
        reactionOverlay.hide()
        disposables += viewModel.updateReaction(messageRecord, emoji).subscribe()
      },
      onActionSelected = { action -> reactionActionListener?.onActionSelected(action) },
      onStartHide = { focusedView -> reactionHideListener?.startHide(focusedView) },
      onHidden = { reactionHideListener?.onHide() },
      onShadeChanged = ::applyOverlayBlur
    )
  }

  /** Set for the life of one long press, so the overlay's callbacks reach that message. */
  private var reactionActionListener: ReactionsToolbarListener? = null
  private var reactionHideListener: ReactionOverlayHideListener? = null

  private lateinit var reactionMenuAnchor: View
  private lateinit var chatHost: ViewGroup

  private lateinit var voiceMessageRecordingDelegate: VoiceMessageRecordingDelegate

  private val internalDidFirstFrameRender = MutableStateFlow(false)
  val didFirstFrameRender: StateFlow<Boolean> = internalDidFirstFrameRender

  //region Android Lifecycle

  override fun onAttach(context: Context) {
    super.onAttach(context)
    mainNavigationEventSink = context as MainNavigationEventSink
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    SignalLocalMetrics.ConversationOpen.start()
    registerForResults()
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    conversationBackground = inflater.inflate(R.layout.v2_conversation_background, container, false)
    conversationContent = inflater.inflate(R.layout.v2_conversation_fragment, container, false)

    val composition = ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        SignalTheme {
          ChatScreen(
            controller = mediaKeyboardController,
            onEvent = ::onMediaKeyboardEvent,
            scrims = chatScrims,
            isBubble = args.conversationScreenType == ConversationScreenType.BUBBLE,
            conversationView = conversationContent,
            overlayController = reactionOverlay
          )
        }
      }
    }

    // Not the ComposeView itself: showAsDropDown measures from an anchor's bottom and flips a popup
    // that will not fit below it.
    reactionMenuAnchor = Space(requireContext())

    // Behind the composition rather than inside it: an AndroidView would put the wallpaper in
    // Compose's hit path, and a second interop view there costs the conversation its
    // ACTION_HOVER_EXIT. See stylus-hover-interop.md.
    chatHost = FrameLayout(requireContext()).apply {
      addView(conversationBackground)
      addView(composition)
      addView(reactionMenuAnchor, FrameLayout.LayoutParams(0, 0))
    }

    return chatHost
  }

  /**
   * Where [target]'s row sits in the overlay's coordinate space. [Projection] walks the layout
   * positions; translations are not part of that walk, so they are added here.
   *
   * The overlay is a composition, so there is nothing to project into. It reports where it starts
   * instead, which is the only reliable measure of its padding: a bubble consumes the insets it is
   * padded by, leaving it flush with the root.
   */
  private fun overlayOriginOf(target: InteractiveConversationElement, recycler: RecyclerView): PointF {
    val projection = Projection.relativeToViewWithCommonRoot(target.root, chatHost, null)
    val hostOrigin = IntArray(2).also { chatHost.getLocationInWindow(it) }
    val overlayOrigin = reactionOverlay.originInWindow
    val origin = PointF(
      projection.x + target.root.translationX - (overlayOrigin.x - hostOrigin[0]),
      projection.y + target.root.translationY + recycler.translationY - (overlayOrigin.y - hostOrigin[1])
    )
    projection.release()

    return origin
  }

  private fun onMediaKeyboardEvent(event: MediaKeyboardEvents) {
    when (event) {
      is MediaKeyboardEvents.SystemKeyboardVisibilityChanged -> container.onKeyboardVisibilityChanged(event.visible)
      MediaKeyboardEvents.SystemKeyboardAnimationEnded -> container.onKeyboardAnimationEnded()
      is MediaKeyboardEvents.SystemKeyboardHeightMeasured -> chatScreenViewModel.setKeyboardHeight(isLandscape(), event.heightPx)
      is MediaKeyboardEvents.KeyboardShown -> container.onInputShown(event.key)
      MediaKeyboardEvents.KeyboardHidden -> container.onInputHidden()
      MediaKeyboardEvents.DismissedByBack -> Unit
    }
  }

  private fun isLandscape(): Boolean {
    return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    viewModel.resetBackPressedState()
    binding.toolbar.isBackInvokedCallbackEnabled = false
    disposables.bindTo(viewLifecycleOwner)

    if (requireActivity() is ConversationActivity) {
      FullscreenHelper(requireActivity()).showSystemUI()
    }

    markReadHelper = MarkReadHelper(ConversationId.forConversation(args.threadId), requireContext(), viewLifecycleOwner, args.isIncognito)
    markReadHelper.ignoreViewReveals()

    attachmentManager = AttachmentManager(requireContext(), conversationContent, AttachmentManagerListener())

    initializeConversationThreadUi()

    val conversationToolbarOnScrollHelper = ConversationToolbarOnScrollHelper(
      requireActivity(),
      listOf(binding.toolbarBackground),
      { color -> chatScrims.statusBarColor = color },
      viewModel::wallpaperSnapshot,
      { viewModel.recipientSnapshot?.isReleaseNotes == true },
      viewLifecycleOwner,
      incognito = args.isIncognito
    )
    conversationToolbarOnScrollHelper.attach(binding.conversationItemRecycler)
    composerHeightAnimator = TellomiComposerHeightAnimator(inputPanel, binding.conversationItemRecyclerFrame, composeText).also { it.attach() }
    presentConversationTitle(viewModel.recipientSnapshot)
    viewModel.recipientSnapshot?.takeIf { it.isGroup }?.let { group ->
      presentGroupConversationSubtitle(tellomiGroupMemberSubtitle(resources, group))
    }
    presentActionBarMenu()
    presentStoryRing()

    observeConversationThread()
    observePlaintextExportState()

    viewModel
      .inputReadyState
      .distinctUntilChanged()
      .subscribeBy(
        onNext = this::presentInputReadyState
      )
      .addTo(disposables)

    childFragmentManager.setFragmentResultListener(MemberLabelEducationSheet.RESULT_EDIT_MEMBER_LABEL, viewLifecycleOwner) { _, bundle ->
      val groupId = bundle.requireParcelableCompat(MemberLabelEducationSheet.KEY_GROUP_ID, GroupId.V2::class.java)
      startActivity(MemberLabelActivity.createIntent(requireContext(), groupId))
    }

    childFragmentManager.setFragmentResultListener(AboutSheet.RESULT_EDIT_MEMBER_LABEL, viewLifecycleOwner) { _, bundle ->
      val groupId = bundle.requireParcelableCompat(AboutSheet.RESULT_GROUP_ID, GroupId.V2::class.java)
      startActivity(MemberLabelActivity.createIntent(requireContext(), groupId))
    }

    initializeMediaKeyboard()

    binding.conversationVideoContainer.setClipToOutline(true)

    SpoilerAnnotation.resetRevealedSpoilers()

    val mediaListener = InputPanelMediaListener()
    ViewCompat.setOnReceiveContentListener(composeText, RECEIVE_CONTENT_MIME_TYPES) { _, payload ->
      val split = payload.partition { item -> item.uri != null }
      val uriContent = split.first

      if (uriContent != null) {
        val clip = uriContent.clip
        val mimeType = if (clip.description.mimeTypeCount > 0) {
          clip.description.getMimeType(0)
        } else {
          null
        }
        val uri = clip.getItemAt(0).uri
        if (uri != null) {
          if (UriUtil.isValidExternalUri(requireContext(), uri)) {
            mediaListener.onMediaSelected(uri, mimeType)
          } else {
            Log.w(TAG, "Ignoring received content with a non-external URI.")
          }
        }
      }

      split.second
    }

    binding.conversationItemRecycler.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
      viewModel.onChatBoundsChanged(Rect(left, top, right, bottom))
    }

    // Tellomi：跟着顶栏背景走（它铺到「我的收藏」分类栏的底边，没有分类栏时就是顶栏底边，#1174）
    binding.toolbarBackground.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
      // Bug: ConstraintLayout can provide a negative value for the toolbar causing RV layout problems
      if (bottom < 0) return@addOnLayoutChangeListener

      // Bug: LinearLayoutManger can get stuck and not layout children under Compose's AndroidFragment if updated too quickly.
      val rv = binding.conversationItemRecycler
      applyToolbarPaddingRunnable?.let { rv.removeCallbacks(it) }
      val runnable = Runnable {
        if (view == null) return@Runnable
        rv.padding(top = bottom)
        if (bottom != oldBottom && ::conversationHeaderPositionDecoration.isInitialized) {
          val newMargin = bottom + 16.dp
          if (conversationHeaderPositionDecoration.toolbarMargin != newMargin) {
            conversationHeaderPositionDecoration.toolbarMargin = newMargin
            rv.invalidateItemDecorations()
          }
        }
      }
      applyToolbarPaddingRunnable = runnable
      rv.post(runnable)
    }

    binding.conversationItemRecycler.addItemDecoration(ChatColorsDrawable.ChatColorsItemDecoration)
  }

  override fun onViewStateRestored(savedInstanceState: Bundle?) {
    super.onViewStateRestored(savedInstanceState)

    isSearchRequested = savedInstanceState?.getBoolean(SAVED_STATE_IS_SEARCH_REQUESTED, false) ?: args.isWithSearchOpen
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putBoolean(SAVED_STATE_IS_SEARCH_REQUESTED, isSearchRequested)
  }

  override fun onStart() {
    super.onStart()
    recomputeMessageDates(forceUpdate = true)
  }

  override fun onResume() {
    super.onResume()

    EventBus.getDefault().register(this)

    groupCallViewModel.peekGroupCall()

    if (!args.conversationScreenType.isInBubble) {
      AppDependencies.messageNotifier.setVisibleThread(ConversationId.forConversation(args.threadId))
    } else {
      AppDependencies.messageNotifier.setVisibleBubbleThread(ConversationId.forConversation(args.threadId))
    }

    viewModel.updateIdentityRecordsInBackground()

    if (args.isFirstTimeInSelfCreatedGroup) {
      conversationGroupViewModel.checkJustSelfInGroup().subscribeBy(
        onSuccess = {
          GroupLinkInviteFriendsBottomSheetDialogFragment.show(childFragmentManager, it)
        }
      ).addTo(disposables)
    }

    conversationGroupViewModel.updateGroupStateIfNeeded()

    if (inputPanel.voiceNoteDraft != null) {
      updateToggleButtonState()
    }

    if (SignalStore.rateLimit.needsRecaptcha()) {
      RecaptchaProofBottomSheetFragment.show(childFragmentManager)
    }

    MediaPreviewCache.consumePendingReply(args.threadId)?.let { replyToMessageFromViewer(it) }
  }

  /**
   * Tellomi（#1257）：在查看器里点了「回复」——和长按回复一样引用整条消息，缩略图照上游取第一项
   * （owner 2026-09-26：不做「回复这一张」，协议只能引用整条消息，要让对方看到那一张就得放宽收件方的防伪）。
   */
  private fun replyToMessageFromViewer(reply: MediaPreviewCache.PendingReply) {
    val recipient = viewModel.recipientSnapshot ?: return
    val appContext = requireContext().applicationContext

    viewLifecycleOwner.lifecycleScope.launch {
      val message = withContext(Dispatchers.IO) {
        val record = SignalDatabase.messages.getMessageRecordOrNull(reply.messageId)?.withAttachments() ?: return@withContext null
        ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(appContext, record, recipient)
      } ?: return@launch

      val canReply = !isActionModeStarted() &&
        MenuState.canReplyToMessage(
          recipient,
          MenuState.isActionMessage(message.messageRecord),
          message.messageRecord,
          viewModel.hasMessageRequestState,
          conversationGroupViewModel.isNonAdminInAnnouncementGroup()
        )
      if (!canReply) {
        return@launch
      }

      val (slideDeck, body) = viewModel.getSlideDeckAndBodyForReply(requireContext(), message)

      if (inputPanel.inEditMessageMode()) {
        inputPanel.exitEditMessageMode()
      }

      inputPanel.setQuote(
        Glide.with(this@ConversationFragment),
        message.messageRecord.dateSent,
        message.messageRecord.fromRecipient,
        body,
        slideDeck,
        message.messageRecord.getRecordQuoteType()
      )
      inputPanel.clickOnComposeInput()
    }
  }

  override fun onPause() {
    super.onPause()

    ConversationUtil.refreshRecipientShortcuts()

    if (!args.conversationScreenType.isInBubble) {
      AppDependencies.messageNotifier.clearVisibleThread(ConversationId.forConversation(args.threadId))
    } else {
      AppDependencies.messageNotifier.clearVisibleBubbleThread()
      AppDependencies.messageNotifier.updateNotification(requireContext())
    }

    if (activity?.isFinishing == true) {
      activity?.overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_end)
    }

    inputPanel.onPause()

    EventBus.getDefault().unregister(this)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    inlineQueryController.onWindowSizeClassChanged(resources.getWindowSizeClass())
  }

  override fun onDestroyView() {
    selectionBarsGeneration++
    selectionBarsShown = false
    selectionBarsAnimator?.let {
      selectionBarsAnimator = null
      it.removeAllListeners()
      it.cancel()
    }
    composerHeightAnimator?.detach()
    composerHeightAnimator = null
    viewModel.collapseAllEvents()
    keyboardEvents?.let {
      container.removeInputListener(it)
      container.removeKeyboardStateListener(it)
    }
    keyboardEvents = null

    // Fragment-scoped, so anything still waiting on a hide would outlive the binding.
    container.clearListeners()

    if (!requireActivity().isChangingConfigurations) {
      (requireActivity().supportFragmentManager.findFragmentByTag(MESSAGE_DETAILS_TAG) as? DialogFragment)?.dismissAllowingStateLoss()
      DeletedMessageTombstoneCache.clearThread(args.threadId)
    }

    super.onDestroyView()
    if (pinnedShortcutReceiver != null) {
      requireActivity().unregisterReceiver(pinnedShortcutReceiver)
    }
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  override fun startActivity(intent: Intent) {
    try {
      super.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      // Only the kind of failure: the exception's message is the intent, which for a link holds its address (ADR-0063 §6.5).
      Log.w(TAG, "No activity to handle an intent: ${e.javaClass.simpleName}")
      toast(
        toastTextId = R.string.ConversationActivity_there_is_no_app_available_to_handle_this_link_on_your_device,
        toastDuration = Toast.LENGTH_LONG
      )
    }
  }

  private fun openLink(url: String) {
    // Tellomi (ADR-0063 §4.9 / §6.1): the card and the text both come here; open per rust/links' open plan.
    TellomiLinkOpener.open(requireActivity(), url) {
      startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).encourageNewBrowserTab())
    }
  }

  //endregion

  //region Fragment callbacks and listeners

  override fun getConversationAdapterListener(): ConversationAdapter.ItemClickListener {
    return adapter.clickListener
  }

  /** Tellomi（#1611）：会话输入框能用（没退群、不是只许管理员发言、消息请求已接受……）。定时消息页的输入框跟着它。 */
  private var tellomiInputEnabled = false

  /** Tellomi（#1611）：定时消息页上写的那条在等开精确闹钟权限 / 确认安全码。 */
  private var tellomiPendingScheduledText: (() -> Unit)? = null

  override fun tellomiCanWriteScheduledMessages(): Boolean = tellomiInputEnabled

  /** Tellomi（#1611，M-4）：定时消息页上写好、选好时间的一条。同 [sendMessage] 的检查（精确闹钟权限、安全码变了先确认），不碰输入框。 */
  override fun tellomiScheduleText(body: String, mentions: List<Mention>, bodyRanges: BodyRangeList?, linkPreviews: List<LinkPreview>, scheduledTime: Long) {
    val threadRecipient = viewModel.recipientSnapshot ?: return
    if (!tellomiInputEnabled) {
      Log.w(TAG, "The chat can no longer be written to; not scheduling.")
      return
    }
    val schedule = {
      disposables += viewModel.sendMessage(
        metricId = null,
        threadRecipient = threadRecipient,
        body = body,
        slideDeck = null,
        scheduledDate = scheduledTime,
        messageToEdit = null,
        quote = null,
        mentions = mentions,
        bodyRanges = bodyRanges,
        contacts = emptyList(),
        linkPreviews = linkPreviews,
        preUploadResults = emptyList(),
        isViewOnce = false
      ).subscribeBy(onError = { Log.w(TAG, "Couldn't schedule a message from the scheduled list", it) })
    }
    // 精确闹钟权限没开：开了以后（`onSchedulePermissionsGranted`）排这一条
    if (ReenableScheduledMessagesDialogFragment.showIfNeeded(requireContext(), childFragmentManager, null, scheduledTime)) {
      tellomiPendingScheduledText = { tellomiScheduleText(body, mentions, bodyRanges, linkPreviews, scheduledTime) }
      return
    }
    if (viewModel.identityRecordsState.hasRecentSafetyNumberChange()) {
      tellomiPendingScheduledText = schedule
      handleRecentSafetyNumberChange(viewModel.identityRecordsState.getRecentSafetyNumberChangeRecords())
      return
    }
    schedule()
  }

  override fun jumpToMessage(messageRecord: MessageRecord) {
    viewModel
      .moveToMessage(messageRecord)
      .subscribeBy {
        moveToPosition(it)
      }
      .addTo(disposables)
  }

  override fun unpin(conversationMessage: ConversationMessage) {
    handleUnpinMessage(conversationMessage.messageRecord.id)
  }

  override fun copy(conversationMessage: ConversationMessage) {
    handleCopyMessage(conversationMessage.multiselectCollection.toSet())
  }

  override fun delete(conversationMessage: ConversationMessage) {
    handleDeleteMessages(conversationMessage.multiselectCollection.toSet())
  }

  override fun save(conversationMessage: ConversationMessage) {
    handleSaveAttachment(conversationMessage.messageRecord as MmsMessageRecord)
  }

  override fun onReactWithAnyEmojiDialogDismissed() {
    reactionOverlay.hide()
  }

  override fun onReactWithAnyEmojiSelected(emoji: String) {
    reactionOverlay.hide()
  }

  override fun onReactionsDialogDismissed() {
    clearFocusedItem()
  }

  override fun openEmojiSearch() {
    val fragment = childFragmentManager.findFragmentByTag(EMOJI_SEARCH_FRAGMENT_TAG)
    if (fragment == null) {
      childFragmentManager.commit {
        add(R.id.emoji_search_container, EmojiSearchFragment(), EMOJI_SEARCH_FRAGMENT_TAG)
      }
    }
  }

  override fun closeEmojiSearch() {
    val fragment = childFragmentManager.findFragmentByTag(EMOJI_SEARCH_FRAGMENT_TAG)
    if (fragment != null) {
      childFragmentManager.commit(allowStateLoss = true) {
        remove(fragment)
      }
    }
  }

  override fun onEmojiSelected(emoji: String?) {
    if (emoji != null) {
      inputPanel.onEmojiSelected(emoji)
      recentEmojis.onCodePointSelected(emoji)
    }
  }

  override fun onKeyEvent(keyEvent: KeyEvent?) {
    if (keyEvent != null) {
      inputPanel.onKeyEvent(keyEvent)
    }
  }

  override fun openStickerSearch() {
    StickerSearchDialogFragment.show(childFragmentManager)
  }

  override fun onStickerSelected(sticker: StickerRecord) {
    sendSticker(
      stickerRecord = sticker,
      clearCompose = false
    )
  }

  override fun onStickerManagementClicked() {
    StickerManagementScreen.show(this)
    container.hideInput()
  }

  override fun isMms(): Boolean {
    return false
  }

  override fun openGifSearch() {
    val recipientId = viewModel.recipientSnapshot?.id ?: return
    conversationActivityResultContracts.launchGifSearch(recipientId, composeText.textTrimmed)
  }

  override fun onGifSelectSuccess(blobUri: Uri, width: Int, height: Int) {
    setMedia(
      uri = blobUri,
      mediaType = SlideFactory.MediaType.from(AppDependencies.blobs.getMimeType(blobUri))!!,
      width = width,
      height = height,
      videoGif = true
    )
  }

  override fun onShown() {
    inputPanel.mediaKeyboardListener.onShown()
  }

  override fun onHidden() {
    inputPanel.mediaKeyboardListener.onHidden()
    closeEmojiSearch()
  }

  override fun onKeyboardChanged(page: KeyboardPage) {
    inputPanel.mediaKeyboardListener.onKeyboardChanged(page)
  }

  override fun onScheduleSend(scheduledTime: Long) {
    sendMessage(scheduledDate = scheduledTime)
  }

  override fun onSchedulePermissionsGranted(metricId: String?, scheduledDate: Long) {
    // Tellomi（#1611）：权限是为定时消息页上写的那条开的，就排它，不发输入框里的字
    val pendingScheduledText = tellomiPendingScheduledText
    tellomiPendingScheduledText = null
    pendingScheduledText?.invoke() ?: sendMessage(scheduledDate = scheduledDate)
  }

  override fun sendAnywayAfterSafetyNumberChangedInBottomSheet(destinations: List<RecipientSearchKey>) {
    Log.d(TAG, "onSendAnywayAfterSafetyNumberChange")
    // Tellomi（#1611）：确认安全码是为了定时消息页上写的那条，就排它，不发输入框里的字
    val pendingScheduledText = tellomiPendingScheduledText
    tellomiPendingScheduledText = null
    viewModel
      .updateIdentityRecords()
      .subscribeBy(
        onError = { t -> Log.w(TAG, "Error sending", t) },
        onComplete = { pendingScheduledText?.invoke() ?: sendMessage() }
      )
      .addTo(disposables)
  }

  override fun onMessageResentAfterSafetyNumberChangeInBottomSheet() {
    Log.d(TAG, "onMessageResentAfterSafetyNumberChange")
    viewModel.updateIdentityRecordsInBackground()
  }

  override fun onCanceled() {
    tellomiPendingScheduledText = null
  }

  override fun onCallNotificationSettingsDialogDismissed() {
    adapter.notifyDataSetChanged()
  }

  override fun onFinishForwardAction() {
    finishActionMode()
  }

  override fun onDismissForwardSheet() = Unit

  override fun getFragmentBackPressedInfo(): Flow<FragmentBackPressedInfo> {
    return viewModel.backPressedState.map {
      if (it.shouldHandleBackPressed()) {
        FragmentBackPressedInfo.Enabled({
          handleBackPressed()
        })
      } else {
        FragmentBackPressedInfo.Disabled
      }
    }
  }

  private fun handleBackPressed() {
    if (skipNextBackPressHandling) {
      skipNextBackPressHandling = false
      return
    }

    Log.d(TAG, "handleBackPressed()")
    val state = viewModel.backPressedState.value

    when {
      state.isReactionDelegateShowing -> reactionOverlay.hide()

      state.isSearchRequested -> searchMenuItem?.collapseActionView()

      state.isInActionMode -> finishActionMode()

      else -> {
        // State has changed since the back handler was enabled. Let the back press proceed
        // to the next handler by triggering onBackPressed again after setting a skip flag
        // to avoid infinite recursion.
        Log.d(TAG, "handleBackPressed() - state changed, forwarding back press")
        skipNextBackPressHandling = true
        requireActivity().onBackPressedDispatcher.onBackPressed()
      }
    }
  }

  //endregion

  private fun startActionMode() {
    viewModel.setIsInActionMode(true)

    actionModeTopBarView.isVisible = true
    actionModeTopBarView.onCloseClick = this::finishActionMode
    actionModeTopBarView.title = calculateSelectedItemCount()
    TellomiContextualBars.fadeIn(actionModeTopBarView)

    searchMenuItem?.collapseActionView()
    binding.toolbar.isInvisible = true
    if (scheduledMessagesStub.isVisible) {
      reShowScheduleMessagesBar = true
      scheduledMessagesStub.visibility = View.GONE
    }

    setCorrectActionModeMenuVisibility()
    binding.conversationItemRecycler.invalidateItemDecorations()
  }

  /** Tellomi（交互审计 A-15）：拖选途中每改一次都更新标题和底栏；全取消了等松手再退出多选。 */
  private fun onDragSelectionChanged(finished: Boolean) {
    if (!isActionModeStarted()) {
      return
    }

    binding.conversationItemRecycler.invalidateItemDecorations()
    if (adapter.selectedItems.isEmpty()) {
      if (finished) {
        finishActionMode()
      }
    } else {
      setCorrectActionModeMenuVisibility()
      setActionModeTitle(calculateSelectedItemCount())
    }
  }

  private fun setActionModeTitle(title: String) {
    actionModeTopBarView.title = title
  }

  private fun isActionModeStarted(): Boolean {
    return actionModeTopBarView.isVisible
  }

  private fun finishActionMode() {
    viewModel.setIsInActionMode(false)

    actionModeTopBarView.isVisible = false

    adapter.clearSelection()
    setBottomActionBarVisibility(false)

    binding.toolbar.isInvisible = false
    TellomiContextualBars.fadeIn(binding.toolbar)
    if (reShowScheduleMessagesBar) {
      scheduledMessagesStub.visibility = View.VISIBLE
      reShowScheduleMessagesBar = false
    }

    binding.conversationItemRecycler.invalidateItemDecorations()
  }

  private fun observeConversationThread() {
    var firstRender = true
    disposables += viewModel
      .conversationThreadState
      .subscribeOn(Schedulers.io())
      .doOnSuccess { state ->
        SignalLocalMetrics.ConversationOpen.onDataLoaded()
        conversationItemDecorations.selfRecipientId = Recipient.self().id
        conversationItemDecorations.setUnreadState(state.meta.unreadCount, state.meta.firstUnreadId)
        colorizer.onGroupMembershipChanged(state.meta.groupMemberAcis)
      }
      .observeOn(AndroidSchedulers.mainThread())
      .doOnSuccess { state ->
        updateMessageRequestAcceptedState(state.meta.messageRequestData.isMessageRequestAccepted)
        moveToStartPosition(state.meta)
      }
      .flatMapObservable { it.items.data }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy(onNext = {
        if (firstRender) {
          SignalLocalMetrics.ConversationOpen.onDataPostedToMain()
        }

        adapter.submitList(it) {
          scrollToPositionDelegate.notifyListCommitted()
          conversationItemDecorations.currentItems = it
          // Tellomi（#1206）：未读线上下两条不算同一组（在主线程读：未读状态第一次是在后台线程设的）
          adapter.tellomiUnreadAnchorId = conversationItemDecorations.tellomiUnreadAnchorId

          if (firstRender) {
            firstRender = false
            binding.conversationItemRecycler.doAfterNextLayout {
              SignalLocalMetrics.ConversationOpen.onRenderFinished()
              (context as? MainActivity)?.onFirstRender()
              doAfterFirstRender()
            }
          }

          if (collapsibleEventScrollPosition != null) {
            val scrollState = collapsibleEventScrollPosition!!
            val offset = binding.conversationItemRecycler.height - scrollState.top - scrollState.height
            layoutManager.scrollToPositionWithOffset(scrollState.position, offset)
            collapsibleEventScrollPosition = null
          }
        }
      })
  }

  private fun doAfterFirstRender() {
    Log.d(TAG, "doAfterFirstRender")

    if (!isAdded || view == null) {
      Log.w(TAG, "Bailing, fragment no longer added")
      return
    }

    activity?.supportStartPostponedEnterTransition()
    internalDidFirstFrameRender.update { true }

    if (requireActivity() is ConversationActivity) {
      val backPressedCallback = BackPressedCallback()
      requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

      lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
          viewModel.backPressedState.collectLatest {
            backPressedCallback.isEnabled = it.shouldHandleBackPressed()
          }
        }
      }
    }

    mainNavigationViewModel.snackbarRegistry.register(
      MainSnackbarHostKey.Chat,
      viewLifecycleOwner
    ) { state ->
      makeSnackbar(state)
      true
    }

    menuProvider?.afterFirstRenderMode = true

    viewLifecycleOwner.lifecycle.addObserver(LastScrolledPositionUpdater(adapter, layoutManager, viewModel))

    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
      var wasTerminated: Boolean? = null
      viewModel
        .groupRecordFlow
        .collect { record ->
          val isTerminated = record.isTerminated
          if (wasTerminated == false && isTerminated) {
            val terminatedByRecipientId = record.terminatedByRecipientId
            if (terminatedByRecipientId == null || terminatedByRecipientId != Recipient.self().id) {
              val context = context ?: return@collect
              val adminName = terminatedByRecipientId?.let { Recipient.resolved(it).getDisplayName(context) }
              withContext(Dispatchers.Main) {
                TerminatedGroupBottomSheetDialog.show(childFragmentManager, adminName)
              }
            }
          }
          wasTerminated = isTerminated
        }
    }

    disposables += viewModel.recipient
      .observeOn(AndroidSchedulers.mainThread())
      .distinctUntilChanged { r1, r2 -> r1 === r2 || r1.hasSameContent(r2) }
      .subscribeBy(onNext = this::onRecipientChanged)

    // Tellomi：「我的收藏」顶栏下方的分类（#1174）
    disposables += binding.tellomiSavedCategoriesBar.bind(viewModel.recipient, args.threadId)

    // Tellomi（两端差异清单第 10 项）：人数按群的全部成员算；titleViewParticipants 只取了前 10 个，只够拼名字
    disposables += viewModel.recipient
      .filter { it.isGroup }
      .observeOn(AndroidSchedulers.mainThread())
      .map { tellomiGroupMemberSubtitle(resources, it) }
      .distinctUntilChanged()
      .subscribeBy(onNext = this::presentGroupConversationSubtitle)

    disposables += viewModel.scrollButtonState
      .subscribeBy(onNext = this::presentScrollButtons)

    disposables += viewModel
      .groupMemberServiceIds
      .subscribeBy(onNext = {
        colorizer.onGroupMembershipChanged(it)
        adapter.updateNameColors()
      })

    val disabledInputListener = DisabledInputListener()
    binding.conversationDisabledInput.listener = disabledInputListener

    val sendButtonListener = SendButtonListener()
    composeTextEventsListener = ComposeTextEventsListener()

    composeText.apply {
      setOnEditorActionListener(sendButtonListener)

      setCursorPositionChangedListener(composeTextEventsListener)
      setOnKeyListener(composeTextEventsListener)
      addTextChangedListener(composeTextEventsListener)
      setStylingChangedListener(composeTextEventsListener)
      setOnClickListener(composeTextEventsListener)
      guardAgainstRecoveryKeyPaste(this@ConversationFragment)
      filters += ByteLimitInputFilter(MessageUtil.MAX_TOTAL_BODY_SIZE_BYTES)
    }

    sendButton.apply {
      setPopupContainer(binding.root)
      setOnClickListener(sendButtonListener)
      setScheduledSendListener(sendButtonListener)
      isEnabled = true
    }

    sendEditButton.setOnClickListener { handleSendEditMessage() }

    val attachListener = { _: View ->
      // Tellomi（tellomi/tellomi#1115）：「+」不再弹键盘位的附件面板，改成照 Telegram 的附件 Sheet（相册网格 + 底部 dock）
      openAttachmentSheet()
    }
    binding.conversationInputPanel.attachButton.setOnClickListener(attachListener)
    binding.conversationInputPanel.inlineAttachmentButton.setOnClickListener(attachListener)

    presentGroupCallJoinButton()

    binding.scrollToBottom.setOnClickListener {
      binding.conversationItemRecycler.stopScroll()
      val returnTo = quoteReturnMessageId
      quoteReturnMessageId = null
      if (returnTo == null) {
        scrollToPositionDelegate.resetScrollPosition()
      } else {
        disposables += viewModel.moveToMessage(returnTo).subscribeBy { position ->
          if (position >= 0) {
            moveToPosition(position)
          } else {
            scrollToPositionDelegate.resetScrollPosition()
          }
        }
      }
    }

    binding.scrollToMention.setOnClickListener {
      binding.conversationItemRecycler.stopScroll()
      scrollToNextMention()
    }

    dataObserver = DataObserver()
    adapter.registerAdapterDataObserver(dataObserver!!)

    keyboardEvents = KeyboardEvents().also {
      container.addInputListener(it)
      container.addKeyboardStateListener(it)
    }

    motionEventRelay.setDrain(MotionEventRelayDrain(this))

    val audioRecorder = AudioRecorder(requireContext(), inputPanel)
    voiceMessageRecordingDelegate = VoiceMessageRecordingDelegate(
      this,
      audioRecorder,
      VoiceMessageRecordingSessionCallbacks()
    )
    // Tellomi（交互审计 A-12）：按住录音时按钮后面的光晕跟着麦克风电平变。
    inputPanel.setVoiceLevelSource { audioRecorder.maxAmplitude }

    val conversationBannerListener = ConversationBannerListener()
    binding.conversationBanner.listener = conversationBannerListener

    lifecycleScope.launch {
      viewModel
        .getBannerFlows(
          context = requireContext(),
          groupJoinClickListener = conversationBannerListener::reviewJoinRequestsAction,
          onSuggestionAddMembers = {
            conversationGroupViewModel.groupRecordSnapshot?.let { groupRecord ->
              GroupsV1MigrationSuggestionsDialog.show(childFragmentManager, groupRecord.id.requireV2(), groupRecord.gv1MigrationSuggestions)
            }
          },
          onSuggestionNoThanks = conversationGroupViewModel::onSuggestedMembersBannerDismissed,
          bubbleClickListener = conversationBannerListener::changeBubbleSettingAction
        )
        .distinctUntilChanged()
        .flowWithLifecycle(viewLifecycleOwner.lifecycle)
        .flowOn(Dispatchers.Main)
        .collect {
          binding.conversationBanner.collectAndShowBanners(it)
        }
    }

    lifecycleScope.launch {
      viewModel
        .pinnedMessages
        .combine(viewModel.wallpaper) { messages, wallpaper -> messages to wallpaper }
        .flowWithLifecycle(viewLifecycleOwner.lifecycle)
        .flowOn(Dispatchers.Main)
        .collect { (messages, wallpaper) ->
          presentPinnedMessage(pinnedMessages = messages, hasWallpaper = wallpaper != null)
        }
    }

    lifecycleScope.launch {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        val recipient = viewModel.recipientSnapshot
        if (recipient != null) {
          AvatarDownloadStateCache.forRecipient(recipient.id).collect {
            when (it) {
              AvatarDownloadStateCache.DownloadState.NONE,
              AvatarDownloadStateCache.DownloadState.IN_PROGRESS,
              AvatarDownloadStateCache.DownloadState.FINISHED -> {
                viewModel.updateThreadHeader()
              }

              AvatarDownloadStateCache.DownloadState.FAILED -> {
                snackbar(R.string.ConversationFragment_photo_failed)
                presentConversationTitle(recipient)
                viewModel.onAvatarDownloadFailed()
              }
            }
          }
        }
      }
    }

    lifecycleScope.launch {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        AttachmentBackfill.failures.collect { failure ->
          if (failure.threadId == args.threadId) {
            showAttachmentBackfillFailureDialog(failure.reason)
          }
        }
      }
    }

    if (TextSecurePreferences.getServiceOutage(context)) {
      AppDependencies.jobManager.add(ServiceOutageDetectionJob())
    }

    viewModel
      .identityRecordsObservable
      .distinctUntilChanged()
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy { presentIdentityRecordsState(it) }
      .addTo(disposables)

    viewModel
      .getRequestReviewState()
      .subscribeBy { presentRequestReviewState(it) }
      .addTo(disposables)

    ConversationItemSwipeCallback(
      SwipeAvailabilityProvider(),
      this::handleReplyToMessage
    ).attachToRecyclerView(binding.conversationItemRecycler)

    viewModel
      .inputReadyState
      .take(1)
      .flatMapMaybe { inputReadyState ->
        draftViewModel.loadShareOrDraftData()
          .map { inputReadyState to it }
      }
      .subscribeBy { (inputReadyState, data) -> handleShareOrDraftData(inputReadyState, data) }
      .addTo(disposables)

    disposables.add(
      draftViewModel
        .state
        .distinctUntilChanged { previous, next -> previous.voiceNoteDraft == next.voiceNoteDraft }
        .subscribe {
          if (!voiceMessageRecordingDelegate.hasActiveSession()) {
            inputPanel.voiceNoteDraft = it.voiceNoteDraft
          }
          updateToggleButtonState()
        }
    )

    initializeSearch()
    initializeLinkPreviews()
    initializeStickerSuggestions()
    initializeInlineSearch()

    inputPanel.setListener(InputPanelListener())

    viewModel
      .getScheduledMessagesCount()
      .subscribeBy { count -> handleScheduledMessagesCountChange(count) }
      .addTo(disposables)

    presentTypingIndicator()

    getVoiceNoteMediaController().finishPostpone()

    getVoiceNoteMediaController()
      .voiceNotePlayerViewState
      .observe(viewLifecycleOwner) { state: Optional<VoiceNotePlayerView.State> ->
        if (state.isPresent) {
          binding.conversationBanner.showVoiceNotePlayer(state.get(), voiceNotePlayerListener)
        } else {
          binding.conversationBanner.clearVoiceNotePlayer()
        }
      }

    getVoiceNoteMediaController().voiceNotePlaybackState.observe(viewLifecycleOwner, inputPanel.playbackStateObserver)

    val conversationUpdateTick = ConversationUpdateTick { recomputeMessageDates() }

    viewLifecycleOwner.lifecycle.addObserver(conversationUpdateTick)

    if (args.conversationScreenType.isInPopup) {
      container.showSoftkey(composeText)
      binding.conversationInputPanel.quickAttachmentToggle.disable()
    }
  }

  private fun recomputeMessageDates(forceUpdate: Boolean = false) {
    disposables += ConversationMessageComputeWorkers
      .recomputeFormattedDate(
        context = requireContext(),
        items = adapter.currentList.filterIsInstance<ConversationMessageElement>(),
        forceUpdate = forceUpdate
      )
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy { adapter.updateTimestamps() }
  }

  private fun initializeInlineSearch() {
    inlineQueryController.onWindowSizeClassChanged(resources.getWindowSizeClass())

    composeText.apply {
      setInlineQueryChangedListener(object : InlineQueryChangedListener {
        override fun onQueryChanged(inlineQuery: InlineQuery) {
          inlineQueryViewModel.onQueryChange(inlineQuery)
        }
      })

      setMentionValidator { annotations ->
        val recipient = viewModel.recipientSnapshot ?: return@setMentionValidator annotations

        val validIds = recipient.participantIds
          .map { MentionAnnotation.idToMentionAnnotationValue(it) }
          .toSet()

        annotations.filterNot { validIds.contains(it.value) }
      }
    }

    inlineQueryViewModel
      .selection
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { r: InlineQueryReplacement -> composeText.replaceText(r) }
      .addTo(disposables)
  }

  private fun presentPinnedMessage(pinnedMessages: List<ConversationMessage>, hasWallpaper: Boolean) {
    if (pinnedMessages.isNotEmpty()) {
      binding.conversationBanner.showPinnedMessageStub(messages = pinnedMessages, canUnpin = conversationGroupViewModel.canEditGroupInfo(), hasWallpaper = hasWallpaper, shouldAnimate = !firstPinRender)
    } else {
      binding.conversationBanner.hidePinnedMessageStub()
    }
    firstPinRender = false
  }

  private fun presentTypingIndicator() {
    typingIndicatorAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
      override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        if (positionStart == 0 && itemCount == 1 && layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
          scrollToPositionDelegate.resetScrollPosition()
        }
      }
    })

    AppDependencies.typingStatusRepository.getTypists(args.threadId).observe(viewLifecycleOwner) {
      val recipient = viewModel.recipientSnapshot ?: return@observe

      typingIndicatorAdapter.setState(
        ConversationTypingIndicatorAdapter.State(
          typists = it.typists,
          isGroupThread = recipient.isGroup,
          hasWallpaper = recipient.hasWallpaper,
          isReplacedByIncomingMessage = it.isReplacedByIncomingMessage
        )
      )
    }
  }

  private fun presentStoryRing() {
    if (SignalStore.story.isFeatureDisabled) {
      return
    }

    disposables += viewModel.storyRingState.subscribeBy {
      binding.conversationTitleView.conversationTitleView.setStoryRingFromState(it)
    }

    binding.conversationTitleView.conversationTitleView.setOnStoryRingClickListener {
      val recipient: Recipient = viewModel.recipientSnapshot ?: return@setOnStoryRingClickListener
      val args = StoryViewerArgs.Builder(recipient.id, recipient.shouldHideStory)
        .isFromQuote(true)
        .build()

      startActivity(StoryViewerActivity.createIntent(requireContext(), args))
    }
  }

  private fun presentInputReadyState(inputReadyState: InputReadyState) {
    presentConversationTitle(inputReadyState.conversationRecipient)

    val disabledInputView = binding.conversationDisabledInput
    val isReleaseNotes = inputReadyState.conversationRecipient.isReleaseNotes
    if (isReleaseNotes) {
      applyReleaseNotesLayout()
    }

    var inputDisabled = true
    when {
      inputReadyState.isClientExpired || inputReadyState.isUnauthorized -> disabledInputView.showAsExpiredOrUnauthorized(inputReadyState.isClientExpired, inputReadyState.isUnauthorized)
      args.isIncognito -> disabledInputView.showAsIncognito()
      inputReadyState.isTerminatedGroup -> disabledInputView.showAsTerminatedGroup()
      !inputReadyState.messageRequestState.isAccepted -> disabledInputView.showAsMessageRequest(inputReadyState.conversationRecipient, inputReadyState.messageRequestState)
      inputReadyState.isRequestingMember == true -> disabledInputView.showAsRequestingMember()
      inputReadyState.isActiveGroup == false -> disabledInputView.showAsNoLongerAMember()
      inputReadyState.isAnnouncementGroup == true && inputReadyState.isAdmin == false -> disabledInputView.showAsAnnouncementGroupAdminsOnly()
      isReleaseNotes -> Unit
      inputReadyState.shouldShowInviteToSignal() -> disabledInputView.showAsInviteToSignal(requireContext(), inputReadyState.conversationRecipient, inputReadyState.threadContainsSms)
      else -> inputDisabled = false
    }

    inputPanel.setHideForMessageRequestState(inputDisabled)
    tellomiInputEnabled = !inputDisabled && !isReleaseNotes

    if (inputDisabled && !isReleaseNotes) {
      chatScrims.navigationBarColor = disabledInputView.color
    } else if (!inputDisabled) {
      disabledInputView.clear()
    }

    composeText.setMessageSendType(MessageSendType.SignalMessageSendType)

    invalidateOptionsMenu()
  }

  private fun applyReleaseNotesLayout() {
    if (releaseNotesLayoutApplied) {
      return
    }
    releaseNotesLayoutApplied = true

    binding.conversationReleaseNotesFloatingLabel.visible = true
    binding.conversationDisabledInput.visible = false

    val navBarInset = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    binding.conversationItemRecycler.updatePadding(bottom = ViewUtil.dpToPx(72) + navBarInset)
    chatScrims.navigationBarColor = Color.TRANSPARENT

    ConstraintSet().apply {
      clone(binding.root)
      connect(binding.conversationItemRecyclerFrame.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
      applyTo(binding.root)
    }
  }

  private fun presentIdentityRecordsState(identityRecordsState: IdentityRecordsState) {
    binding.conversationTitleView.root.setVerified(identityRecordsState.isVerified)

    if (identityRecordsState.isUnverified) {
      binding.conversationBanner.showUnverifiedBanner(identityRecordsState.identityRecords)
    } else {
      binding.conversationBanner.clearUnverifiedBanner()
    }
  }

  private fun presentRequestReviewState(requestReviewState: RequestReviewState) {
    if (requestReviewState.shouldShowReviewBanner()) {
      binding.conversationBanner.showReviewBanner(requestReviewState)
    } else {
      binding.conversationBanner.clearRequestReview()
    }
  }

  private fun setMedia(uri: Uri, mediaType: SlideFactory.MediaType, width: Int = 0, height: Int = 0, borderless: Boolean = false, videoGif: Boolean = false) {
    val recipientId: RecipientId = viewModel.recipientSnapshot?.id ?: return

    if (mediaType == SlideFactory.MediaType.VCARD) {
      conversationActivityResultContracts.launchVCardShareEditor(uri, recipientId)
    } else {
      val mimeType = MediaUtil.getMimeType(requireContext(), uri) ?: mediaType.toFallbackMimeType()
      val media = Media(
        uri = uri,
        contentType = mimeType,
        date = 0,
        width = width,
        height = height,
        size = 0,
        duration = 0,
        isBorderless = borderless,
        isVideoGif = videoGif,
        bucketId = null,
        caption = null,
        transformProperties = TransformProperties.forSentMediaQuality(SignalStore.settings.sentMediaQuality.code),
        fileName = null
      )
      conversationActivityResultContracts.launchMediaEditor(listOf(media), recipientId, composeText.textTrimmed)
    }
  }

  private fun registerForResults() {
    addToContactsLauncher = registerForActivityResult(AddToContactsContract()) {}
    conversationActivityResultContracts = ConversationActivityResultContracts(this, ActivityResultCallbacks())
  }

  private fun observePlaintextExportState() {
    var progressDialog: SignalProgressDialog? = null

    lifecycleScope.launch {
      viewModel.plaintextExportState.collectLatest { state ->
        val exporting = state is ConversationViewModel.PlaintextExportState.Preparing || state is ConversationViewModel.PlaintextExportState.InProgress
        if (exporting) {
          requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        when (state) {
          is ConversationViewModel.PlaintextExportState.None -> {
            progressDialog?.dismiss()
            progressDialog = null
          }

          is ConversationViewModel.PlaintextExportState.Preparing -> {
            progressDialog = SignalProgressDialog.show(
              context = requireContext(),
              title = getString(R.string.conversation_export__exporting),
              message = getString(R.string.conversation_export__preparing),
              indeterminate = true,
              cancelable = false,
              negativeButtonText = getString(android.R.string.cancel),
              negativeButtonListener = { _, _ -> viewModel.cancelExport() }
            )
          }

          is ConversationViewModel.PlaintextExportState.InProgress -> {
            progressDialog?.let {
              it.isIndeterminate = false
              it.progress = state.percent
              it.setMessage(state.status)
            }
          }

          is ConversationViewModel.PlaintextExportState.Complete -> {
            progressDialog?.dismiss()
            progressDialog = null

            val uri = FileProviderUtil.getUriFor(requireContext(), state.zipFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
              type = "application/zip"
              putExtra(Intent.EXTRA_STREAM, uri)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.conversation_export__export_complete))
            if (Build.VERSION.SDK_INT < 34) {
              chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(requireContext(), ShareActivity::class.java)))
            }
            startActivity(chooserIntent)

            viewModel.clearPlaintextExportState()
          }

          is ConversationViewModel.PlaintextExportState.Failed -> {
            progressDialog?.dismiss()
            progressDialog = null
            toast(R.string.conversation_export__export_failed, toastDuration = Toast.LENGTH_LONG)
            viewModel.clearPlaintextExportState()
          }

          is ConversationViewModel.PlaintextExportState.Cancelled -> {
            progressDialog?.dismiss()
            progressDialog = null
            toast(R.string.conversation_export__export_cancelled, toastDuration = Toast.LENGTH_SHORT)
            viewModel.clearPlaintextExportState()
          }
        }
      }
    }
  }

  private fun onRecipientChanged(recipient: Recipient) {
    presentWallpaper(recipient)
    presentConversationTitle(recipient)
    presentChatColors(recipient.chatColors)
    invalidateOptionsMenu()
    updateMessageRequestAcceptedState(!viewModel.hasMessageRequestState)

    recyclerViewColorizer.setChatColors(recipient.chatColors)
  }

  @MainThread
  private fun updateMessageRequestAcceptedState(isMessageRequestAccepted: Boolean) {
    adapter.setMessageRequestIsAccepted(isMessageRequestAccepted)
  }

  private fun invalidateOptionsMenu() {
    if (searchMenuItem?.isActionViewExpanded != true || !isSearchRequested) {
      binding.toolbar.invalidateMenu()
    }
  }

  private fun presentActionBarMenu() {
    if (!args.conversationScreenType.isInPopup) {
      optionsMenuCallback = ConversationOptionsMenuCallback()
      menuProvider = ConversationOptionsMenu.Provider(optionsMenuCallback, disposables)
      binding.toolbar.addMenuProvider(menuProvider!!)
      invalidateOptionsMenu()
    }

    when (args.conversationScreenType) {
      ConversationScreenType.NORMAL -> presentNavigationIconForNormal()

      ConversationScreenType.BUBBLE,
      ConversationScreenType.POPUP -> presentNavigationIconForBubble()
    }
  }

  private fun presentNavigationIconForNormal() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        mainNavigationViewModel.isFullScreenPane.collect { isFullScreenPane ->
          updateNavigationIconForNormal(isFullScreenPane)
        }
      }
    }
  }

  private fun updateNavigationIconForNormal(isFullScreenPane: Boolean) {
    if (!resources.isSplitPane() || isFullScreenPane) {
      binding.toolbar.setNavigationIcon(CoreUiR.drawable.symbol_arrow_start_24)
      binding.toolbar.navigationIcon?.setTint(
        ContextCompat.getColor(
          requireContext(),
          if (viewModel.wallpaperSnapshot != null) CoreUiR.color.signal_colorNeutralInverse else CoreUiR.color.signal_colorOnSurface
        )
      )
      binding.toolbar.setNavigationContentDescription(R.string.ConversationFragment__content_description_back_button)
      binding.toolbar.setNavigationOnClickListener {
        container.hideKeyboard(composeText)
        requireActivity().onBackPressedDispatcher.onBackPressed()
      }
      binding.toolbar.setContentInsetsRelative(
        46.dp,
        binding.toolbar.contentInsetEnd
      )
    } else {
      binding.toolbar.navigationIcon = null
      binding.toolbar.setContentInsetsRelative(
        24.dp,
        binding.toolbar.contentInsetEnd
      )
    }
  }

  private fun presentNavigationIconForBubble() {
    binding.toolbar.navigationIcon = DrawableUtil.tint(
      requireContext().requireDrawable(R.drawable.ic_notification),
      ContextCompat.getColor(requireContext(), R.color.signal_accent_primary)
    )

    binding.toolbar.setNavigationContentDescription(R.string.ConversationFragment__content_description_launch_signal_button)

    binding.toolbar.setNavigationOnClickListener {
      startActivity(MainActivity.clearTop(requireContext()))
    }
  }

  private fun presentGroupConversationSubtitle(subtitle: String) {
    val titleView = binding.conversationTitleView.root

    if (subtitle.isBlank()) {
      titleView.setGroupRecipientSubtitle(null)
      return
    }

    titleView.setGroupRecipientSubtitle(subtitle)
  }

  private fun presentConversationTitle(recipient: Recipient?) {
    if (recipient == null) {
      return
    }

    val titleView = binding.conversationTitleView.root

    titleView.setTitle(Glide.with(this), recipient)

    if (recipient.expiresInSeconds > 0) {
      titleView.showExpiring(recipient)
    } else {
      titleView.clearExpiring()
    }

    if (!args.conversationScreenType.isInPopup) {
      titleView.setOnClickListener {
        optionsMenuCallback.handleConversationSettings()
      }
      // Tellomi（交互审计 A-09）：按下就缩一点，不等抬手
      titleView.setOnTouchListener(TellomiPressFeedback.wholeViewTouchListener())
    }

    if (recipient.isSystemContact) {
      titleView.setOnLongClickListener {
        startActivity(Intent(Intent.ACTION_VIEW, recipient.contactUri))
        return@setOnLongClickListener true
      }
    }
  }

  private fun presentWallpaper(recipient: Recipient) {
    val chatWallpaper = recipient.wallpaper
    if (recipient.isReleaseNotes) {
      applyReleaseNotesWallpaper()
    } else {
      applyChatWallpaper(chatWallpaper)
    }

    val wallpaperEnabled = chatWallpaper != null || recipient.isReleaseNotes

    val toolbarTint = ContextCompat.getColor(
      requireContext(),
      if (wallpaperEnabled) {
        CoreUiR.color.signal_colorNeutralInverse
      } else {
        CoreUiR.color.signal_colorOnSurface
      }
    )

    binding.toolbar.setTitleTextColor(toolbarTint)
    binding.toolbar.setActionItemTint(toolbarTint)
    binding.toolbar.navigationIcon?.setTint(toolbarTint)

    backgroundBinding.conversationWallpaper.visible = wallpaperEnabled
    binding.scrollToBottom.setWallpaperEnabled(wallpaperEnabled)
    binding.scrollToMention.setWallpaperEnabled(wallpaperEnabled)
    binding.conversationDisabledInput.setWallpaperEnabled(wallpaperEnabled)
    inputPanel.setWallpaperEnabled(wallpaperEnabled)

    val stateChanged = adapter.onHasWallpaperChanged(wallpaperEnabled)
    conversationItemDecorations.hasWallpaper = wallpaperEnabled
    conversationItemDecorations.isReleaseNotes = recipient.isReleaseNotes
    if (stateChanged) {
      binding.conversationItemRecycler.invalidateItemDecorations()
    }

    binding.scrollDateHeader.setBackgroundResource(
      if (wallpaperEnabled) R.drawable.sticky_date_header_background_wallpaper else R.drawable.sticky_date_header_background
    )

    binding.scrollDateHeader.setTextColor(
      ContextCompat.getColor(
        requireContext(),
        if (wallpaperEnabled) R.color.sticky_header_foreground_wallpaper else CoreUiR.color.signal_colorOnSurfaceVariant
      )
    )

    if (!inputPanel.isHidden) {
      setNavBarBackgroundColor(wallpaperEnabled)
    }
  }

  private fun applyReleaseNotesWallpaper() {
    if (releaseNotesWallpaperApplied) {
      return
    }
    releaseNotesWallpaperApplied = true

    val tinted = DrawableUtil.tint(
      AppCompatResources.getDrawable(requireContext(), R.drawable.release_chat_background)!!,
      ContextCompat.getColor(requireContext(), R.color.release_notes_background_pattern)
    )
    val bitmap = DrawableUtil.toBitmap(tinted, tinted.intrinsicWidth, tinted.intrinsicHeight)

    backgroundBinding.conversationWallpaper.scaleType = ImageView.ScaleType.MATRIX
    backgroundBinding.conversationWallpaper.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.release_notes_background))
    backgroundBinding.conversationWallpaper.setImageDrawable(RotatedTiledDrawable(bitmap, -45f))
    backgroundBinding.conversationWallpaperDim.visible = false
  }

  private fun applyChatWallpaper(chatWallpaper: ChatWallpaper?) {
    if (chatWallpaper != null) {
      chatWallpaper.loadInto(backgroundBinding.conversationWallpaper)
      ChatWallpaperDimLevelUtil.applyDimLevelForNightMode(backgroundBinding.conversationWallpaperDim, chatWallpaper)
    } else {
      backgroundBinding.conversationWallpaperDim.visible = false
    }
  }

  private fun setNavBarBackgroundColor(hasWallpaper: Boolean) {
    val navColor = if (hasWallpaper) {
      R.color.conversation_navigation_wallpaper
    } else {
      CoreUiR.color.signal_colorBackground
    }

    chatScrims.navigationBarColor = ContextCompat.getColor(requireContext(), navColor)
  }

  private fun presentChatColors(chatColors: ChatColors) {
    recyclerViewColorizer.setChatColors(chatColors)
    binding.scrollToMention.setUnreadCountBackgroundTint(chatColors.asSingleColor())
    binding.scrollToBottom.setUnreadCountBackgroundTint(chatColors.asSingleColor())
    binding.conversationInputPanel.buttonToggle.background.apply {
      colorFilter = PorterDuffColorFilter(chatColors.asSingleColor(), PorterDuff.Mode.MULTIPLY)
      invalidateSelf()
    }
  }

  private fun presentScrollButtons(scrollButtonState: ConversationScrollButtonState) {
    Log.d(TAG, "Update scroll state $scrollButtonState")
    binding.scrollToBottom.setUnreadCount(scrollButtonState.unreadCount)
    binding.scrollToMention.setUnreadCount(0)
    binding.scrollToMention.isShown = scrollButtonState.hasMentions && scrollButtonState.showScrollButtons
    binding.scrollToBottom.isShown = scrollButtonState.showScrollButtons
  }

  private fun presentGroupCallJoinButton() {
    binding.conversationGroupCallJoin.setOnClickListener {
      handleVideoCall()
    }

    disposables += groupCallViewModel
      .state
      .distinctUntilChanged()
      .subscribeBy {
        binding.conversationGroupCallJoin.visible = it.ongoingCall
        binding.conversationGroupCallJoin.setText(if (it.hasCapacity) R.string.ConversationActivity_join else R.string.ConversationActivity_full)
        invalidateOptionsMenu()
      }
  }

  private fun handlePinMessage(conversationMessage: ConversationMessage) {
    if (viewModel.pinnedMessages.value.size >= RemoteConfig.pinLimit) {
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(resources.getString(R.string.ConversationFragment__replace_title))
        .setMessage(resources.getString(R.string.ConversationFragment__replace_body))
        .setPositiveButton(R.string.ConversationFragment__replace) { _, _ ->
          showPinForDialog(conversationMessage)
        }
        .setNegativeButton(R.string.ConversationFragment__cancel) { dialog, _ -> dialog.dismiss() }
        .show()
    } else {
      showPinForDialog(conversationMessage)
    }
  }

  private fun showPinForDialog(conversationMessage: ConversationMessage) {
    val threadRecipient = viewModel.recipientSnapshot ?: return
    var selection = 1
    val labels = resources.getStringArray(R.array.ConversationFragment__pinned_for_labels)
    val values = resources.getIntArray(R.array.ConversationFragment__pinned_for_values)

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(resources.getString(R.string.ConversationFragment__keep_pinned))
      .setSingleChoiceItems(labels, selection) { dialog, which ->
        selection = which
      }
      .setPositiveButton(android.R.string.ok) { dialog, _ ->
        if (conversationMessage.messageRecord.expiresIn > 0 && SignalStore.uiHints.shouldDisplayPinnedSheet()) {
          PinDisappearingMessageBottomSheet.show(childFragmentManager)
          SignalStore.uiHints.incrementSeenPinnedSheetCount()
        }
        disposables += viewModel
          .pinMessage(
            messageRecord = conversationMessage.messageRecord,
            duration = if (values[selection] == -1) kotlin.time.Duration.INFINITE else values[selection].days,
            threadRecipient = threadRecipient
          )
          .doOnSubscribe {
            handler.postDelayed({ showSpinner() }, PIN_SPINNER_DELAY.inWholeMilliseconds)
          }
          .doFinally {
            handler.removeCallbacksAndMessages(null)
            hideSpinner()
          }
          .subscribeBy(
            onError = {
              Log.w(TAG, "Error received during pin message!", it)
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.PinnedMessage__couldnt_pin)
                .setMessage(getString(R.string.PinnedMessage__check_connection))
                .setPositiveButton(android.R.string.ok) { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() }
                .show()
            }
          )
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel) { dialog, _ ->
        dialog.dismiss()
      }
      .show()
  }

  private fun handleUnpinMessage(messageId: Long) {
    disposables += viewModel
      .unpinMessage(messageId)
      .doOnSubscribe {
        handler.postDelayed({ showSpinner() }, PIN_SPINNER_DELAY.inWholeMilliseconds)
      }
      .doFinally {
        handler.removeCallbacksAndMessages(null)
        hideSpinner()
      }
      .subscribeBy(
        onError = {
          Log.w(TAG, "Error received during unpin message!", it)
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.PinnedMessage__couldnt_unpin)
            .setMessage(getString(R.string.PinnedMessage__check_connection))
            .setPositiveButton(android.R.string.ok) { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() }
            .show()
        }
      )
  }

  private fun handleStarMessages(messageIds: Set<Long>) {
    disposables += viewModel
      .setMessagesStarred(messageIds, true)
      .subscribeBy(
        onError = { Log.w(TAG, "Error starring message!", it) }
      )
  }

  private fun handleUnstarMessages(messageIds: Set<Long>) {
    disposables += viewModel
      .setMessagesStarred(messageIds, false)
      .subscribeBy(
        onError = { Log.w(TAG, "Error unstarring message!", it) }
      )
  }

  private fun handleVideoCall() {
    val recipient = viewModel.recipientSnapshot ?: return
    if (!recipient.isGroup) {
      CommunicationActions.startVideoCall(this, recipient) {
        YouAreAlreadyInACallSnackbar.show(requireView())
      }
      return
    }

    val hasActiveGroupCall: Single<Boolean> = groupCallViewModel.state.map { it.ongoingCall }.firstOrError()
    val isNonAdminInAnnouncementGroup: Boolean = conversationGroupViewModel.isNonAdminInAnnouncementGroup()
    val cannotCreateGroupCall = hasActiveGroupCall.map { active ->
      recipient to (recipient.isPushV2Group && !active && isNonAdminInAnnouncementGroup)
    }

    disposables += cannotCreateGroupCall
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { (recipient, notAllowed) ->
        if (notAllowed) {
          ConversationDialogs.displayCannotStartGroupCallDueToPermissionsDialog(requireContext())
        } else {
          CommunicationActions.startVideoCall(this, recipient) {
            YouAreAlreadyInACallSnackbar.show(requireView())
          }
        }
      }
  }

  private fun handleBlockJoinRequest(recipient: Recipient) {
    disposables += conversationGroupViewModel.blockJoinRequests(recipient).subscribeBy { result ->
      if (result.isFailure()) {
        val failureReason = GroupErrors.getUserDisplayMessage((result as GroupBlockJoinRequestResult.Failure).reason)
        Toast.makeText(requireContext(), failureReason, Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(requireContext(), R.string.ConversationFragment__blocked, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun handleShareOrDraftData(inputReadyState: InputReadyState, data: ShareOrDraftData) {
    if (inputReadyState.isAnnouncementGroup == true && inputReadyState.isAdmin == false) {
      Toast.makeText(requireContext(), R.string.MultiselectForwardFragment__only_admins_can_send_messages_to_this_group, Toast.LENGTH_SHORT).show()
      draftViewModel.clearDraft()
      return
    } else if (inputReadyState.shouldClearDraft()) {
      draftViewModel.clearDraft()
      return
    }

    when (data) {
      is ShareOrDraftData.SendKeyboardImage -> sendMessageWithoutComposeInput(slide = data.slide, clearCompose = false)

      is ShareOrDraftData.SendSticker -> sendMessageWithoutComposeInput(slide = data.slide, clearCompose = true)

      is ShareOrDraftData.SetText -> {
        composeText.setDraftText(data.text)
        inputPanel.clickOnComposeInput()
      }

      is ShareOrDraftData.SetLocation -> attachmentManager.setLocation(data.location, PushMediaConstraints(null))

      is ShareOrDraftData.SetEditMessage -> {
        composeText.setDraftText(data.draftText)
        inputPanel.enterEditMessageMode(Glide.with(this), data.messageEdit, true, data.clearQuote)
      }

      is ShareOrDraftData.SetMedia -> {
        composeText.setDraftText(data.text)
        setMedia(data.media, data.mediaType)
      }

      is ShareOrDraftData.SetQuote -> {
        composeText.setDraftText(data.draftText)
        handleReplyToMessage(data.quote)
      }

      is ShareOrDraftData.StartSendMedia -> {
        val recipientId = viewModel.recipientSnapshot?.id ?: return
        conversationActivityResultContracts.launchMediaEditor(data.mediaList, recipientId, data.text)
      }
    }
  }

  /** Tellomi（两端对齐清单 P-5）：上一次收到的定时消息条数；-1 = 还没收到第一次。 */
  private var tellomiLastScheduledCount = -1

  private fun handleScheduledMessagesCountChange(count: Int) {
    // Tellomi（两端对齐清单 P-5）：会话里刚排了一条（条数变多），打开这个会话的定时消息页——同 Telegram 两端（安卓 `ChatActivity`
    // `didReceiveNewMessages` 收到刚发出的定时消息就 `openScheduledMessages`；iOS 同），同 Tellomi iOS 排好就打开。
    // 第一次收到的是原有的条数，不算；定时消息页已经开着（在页里写的）就不再开；会话页不在前台（比如还在媒体页）不开。
    val previousCount = tellomiLastScheduledCount
    tellomiLastScheduledCount = count
    if (previousCount >= 0 && count > previousCount && isResumed && childFragmentManager.fragments.none { it is ScheduledMessagesBottomSheet }) {
      viewModel.recipientSnapshot?.let { recipient ->
        container.runAfterAllHidden(composeText) {
          ScheduledMessagesBottomSheet.show(childFragmentManager, args.threadId, recipient.id)
        }
      }
    }

    if (count <= 0) {
      scheduledMessagesStub.visibility = View.GONE
      reShowScheduleMessagesBar = false
    } else {
      scheduledMessagesStub.get().apply {
        visibility = View.VISIBLE

        findViewById<View>(R.id.scheduled_messages_show_all)
          .setOnClickListener {
            val recipient = viewModel.recipientSnapshot ?: return@setOnClickListener
            container.runAfterAllHidden(composeText) {
              ScheduledMessagesBottomSheet.show(childFragmentManager, args.threadId, recipient.id)
            }
          }

        findViewById<TextView>(R.id.scheduled_messages_text).text = resources.getQuantityString(R.plurals.conversation_scheduled_messages_bar__number_of_messages, count, count)
      }
      reShowScheduleMessagesBar = true
    }
  }

  private fun handleSendEditMessage() {
    if (!inputPanel.inEditMessageMode()) {
      Log.w(TAG, "Not in edit message mode, unknown state, forcing re-exit")
      inputPanel.exitEditMessageMode()
      return
    }

    val editMessage = inputPanel.editMessage
    if (editMessage == null) {
      Log.w(TAG, "No edit message found, forcing exit")
      inputPanel.exitEditMessageMode()
      return
    }

    if (!isValidEditMessageSend(editMessage, System.currentTimeMillis())) {
      Log.i(TAG, "Edit message no longer valid")
      val editDurationHours = getEditMessageThresholdHours()
      Dialogs.showAlertDialog(requireContext(), null, resources.getQuantityString(R.plurals.ConversationActivity_edit_message_too_old, editDurationHours, editDurationHours))
      return
    }

    if (editMessage.body == composeText.editableText.toString().trim() &&
      editMessage.getQuote()?.displayText?.toString() == inputPanel.quote.map { it.text }.orNull() &&
      editMessage.messageRanges == composeText.styling &&
      editMessage.hasLinkPreview() == inputPanel.hasLinkPreview()
    ) {
      Log.d(TAG, "Updated message matches original, exiting edit mode")
      inputPanel.exitEditMessageMode()
      return
    }

    sendMessage()
  }

  private fun getVoiceNoteMediaController() = requireListener<VoiceNoteMediaControllerOwner>().voiceNoteMediaController

  private fun initializeConversationThreadUi() {
    layoutManager = ConversationLayoutManager(requireContext())
    binding.conversationItemRecycler.setHasFixedSize(false)
    binding.conversationItemRecycler.layoutManager = layoutManager
    scrollListener = ScrollListener()
    binding.conversationItemRecycler.addOnScrollListener(scrollListener!!)

    adapter = ConversationAdapterV2(
      lifecycleOwner = viewLifecycleOwner,
      requestManager = Glide.with(this),
      clickListener = ConversationItemClickListener(),
      hasWallpaper = args.hasWallpaper,
      colorizer = colorizer,
      startExpirationTimeout = viewModel::startExpirationTimeout,
      chatColorsDataProvider = viewModel::chatColorsSnapshot,
      displayDialogFragment = { it.show(childFragmentManager, null) }
    )

    typingIndicatorAdapter = ConversationTypingIndicatorAdapter(Glide.with(this))

    scrollToPositionDelegate = ScrollToPositionDelegate(
      recyclerView = binding.conversationItemRecycler,
      canJumpToPosition = adapter::canJumpToPosition
    )

    adapter.setPagingController(viewModel.pagingController)

    recyclerViewColorizer = RecyclerViewColorizer(binding.conversationItemRecycler)
    viewModel.recipientSnapshot?.chatColors?.let { recyclerViewColorizer.setChatColors(it) }

    binding.conversationItemRecycler.adapter = ConcatAdapter(typingIndicatorAdapter, adapter)
    // Tellomi（交互审计 A-15）：多选模式里长按一条后不抬手往上 / 往下拖，连着选。
    dragSelect = TellomiDragSelect(binding.conversationItemRecycler, adapter, ::onDragSelectionChanged).also {
      binding.conversationItemRecycler.addOnItemTouchListener(it)
    }
    multiselectItemDecoration = MultiselectItemDecoration(
      requireContext()
    ) { viewModel.wallpaperSnapshot }

    openableGiftItemDecoration = OpenableGiftItemDecoration(requireContext())
    binding.conversationItemRecycler.addItemDecoration(openableGiftItemDecoration)

    binding.conversationItemRecycler.addItemDecoration(multiselectItemDecoration)
    viewLifecycleOwner.lifecycle.addObserver(multiselectItemDecoration)

    giphyMp4ProjectionRecycler = initializeGiphyMp4()

    val layoutTransitionListener = BubbleLayoutTransitionListener(binding.conversationItemRecycler)
    viewLifecycleOwner.lifecycle.addObserver(layoutTransitionListener)

    binding.conversationItemRecycler.itemAnimator = ConversationItemAnimator(
      isInMultiSelectMode = adapter.selectedItems::isNotEmpty,
      shouldPlayMessageAnimations = {
        animationsAllowed && scrollToPositionDelegate.isListCommitted() && binding.conversationItemRecycler.scrollState == RecyclerView.SCROLL_STATE_IDLE
      },
      isParentFilled = {
        binding.conversationItemRecycler.canScrollVertically(1) || binding.conversationItemRecycler.canScrollVertically(-1)
      },
      shouldUseSlideAnimation = { viewHolder ->
        true
      }
    )

    conversationHeaderPositionDecoration = ConversationHeaderPositionDecoration()

    val statusBarInset = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
    conversationHeaderPositionDecoration.toolbarMargin = statusBarInset + resources.getDimensionPixelSize(R.dimen.signal_m3_toolbar_height) + 16.dp
    binding.conversationItemRecycler.addItemDecoration(conversationHeaderPositionDecoration)

    conversationItemDecorations = ConversationItemDecorations(hasWallpaper = args.hasWallpaper)
    binding.conversationItemRecycler.addItemDecoration(conversationItemDecorations, 0)
  }

  private fun initializeGiphyMp4(): GiphyMp4ProjectionRecycler {
    val maxPlayback = GiphyMp4PlaybackPolicy.maxSimultaneousPlaybackInConversation()
    val holders = GiphyMp4ProjectionPlayerHolder.injectVideoViews(
      requireContext(),
      viewLifecycleOwner.lifecycle,
      binding.conversationVideoContainer,
      maxPlayback
    )

    val callback = GiphyMp4ProjectionRecycler(holders)
    GiphyMp4PlaybackController.attach(binding.conversationItemRecycler, callback, maxPlayback)
    binding.conversationItemRecycler.addItemDecoration(
      GiphyMp4ItemDecoration(callback),
      0
    )
    return callback
  }

  private fun initializeSearch() {
    searchViewModel.searchResults.observe(viewLifecycleOwner) { result ->
      if (result == null) {
        return@observe
      }

      if (result.results.isNotEmpty()) {
        val messageResult = result.results[result.position]
        disposables += viewModel
          .moveToDate(messageResult.receivedTimestampMs)
          .observeOn(AndroidSchedulers.mainThread())
          .subscribeBy {
            moveToPosition(it)
          }
      }

      searchNav.setData(result.position, result.results.size)
    }

    searchNav.setEventListener(SearchEventListener())

    disposables += viewModel.searchQuery.subscribeBy {
      adapter.updateSearchQuery(it)
    }
  }

  private fun initializeLinkPreviews() {
    linkPreviewViewModel.linkPreviewState
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy { state ->
        if (state.isLoading) {
          inputPanel.setLinkPreviewLoading()
        } else if (state.hasLinks() && !state.linkPreview.isPresent) {
          inputPanel.setLinkPreviewNoPreview(state.error)
        } else {
          inputPanel.setLinkPreview(Glide.with(this), state.linkPreview)
        }

        updateToggleButtonState()
      }
      .addTo(disposables)
  }

  private fun initializeMediaKeyboard() {
    val keyboardMode: TextSecurePreferences.MediaKeyboardMode = TextSecurePreferences.getMediaKeyboardMode(requireContext())
    val stickerIntro: Boolean = !TextSecurePreferences.hasSeenStickerIntroTooltip(requireContext())

    keyboardPagerViewModel.resetPages()
    inputPanel.showMediaKeyboardToggle(true)

    val keyboardPage = when (keyboardMode) {
      TextSecurePreferences.MediaKeyboardMode.EMOJI -> KeyboardPage.EMOJI
      TextSecurePreferences.MediaKeyboardMode.STICKER -> KeyboardPage.STICKER
      TextSecurePreferences.MediaKeyboardMode.GIF -> if (RemoteConfig.gifAvailable) KeyboardPage.GIF else KeyboardPage.STICKER
    }

    inputPanel.setMediaKeyboardToggleMode(keyboardPage)
    keyboardPagerViewModel.switchToPage(keyboardPage)

    if (stickerIntro) {
      TextSecurePreferences.setMediaKeyboardMode(requireContext(), TextSecurePreferences.MediaKeyboardMode.STICKER)
      inputPanel.setMediaKeyboardToggleMode(KeyboardPage.STICKER)
      conversationTooltips.displayStickerIntroductionTooltip(inputPanel.mediaKeyboardToggleAnchorView) {
        EventBus.getDefault().removeStickyEvent(StickerPackInstallEvent::class.java)
      }
    }
  }

  private fun initializeStickerSuggestions() {
    stickerViewModel.stickers
      .subscribeBy(onNext = inputPanel::setStickerSuggestions)
      .addTo(disposables)
  }

  private fun updateLinkPreviewState() {
    if (viewModel.isPushAvailable && !attachmentManager.isAttachmentPresent && context != null && inputPanel.editMessage?.hasNonTextSlide() != true) {
      linkPreviewViewModel.onEnabled()
      linkPreviewViewModel.onTextChanged(composeText.textTrimmed.toString(), composeText.selectionStart, composeText.selectionEnd)
    } else {
      linkPreviewViewModel.onUserCancel()
    }
  }

  private fun updateToggleButtonState() {
    val buttonToggle: AnimatingToggle = binding.conversationInputPanel.buttonToggle
    val quickAttachment: HidingLinearLayout = binding.conversationInputPanel.quickAttachmentToggle
    val inlineAttachment: HidingLinearLayout = binding.conversationInputPanel.inlineAttachmentContainer

    when {
      inputPanel.isRecordingInLockedMode -> {
        buttonToggle.display(sendButton)
        quickAttachment.show()
        inlineAttachment.hide(true)
        inputPanel.setTrailingControls(quickAttachment)
      }

      inputPanel.inEditMessageMode() -> {
        buttonToggle.display(sendEditButton)
        quickAttachment.hide(false)
        inlineAttachment.hide(false)
        inputPanel.setTrailingControls(null)
      }

      // An in-progress recording snapshots itself into the draft, but its UI lives in quickAttachment and must stay visible.
      draftViewModel.voiceNoteDraft != null && !inputPanel.isRecordingInProgress -> {
        buttonToggle.display(sendButton)
        quickAttachment.hide(true)
        inlineAttachment.hide(true)
        inputPanel.setTrailingControls(null)
      }

      composeText.text.isNullOrBlank() && !attachmentManager.isAttachmentPresent -> {
        buttonToggle.display(binding.conversationInputPanel.attachButton)
        quickAttachment.show()
        inlineAttachment.hide(true)
        inputPanel.setTrailingControls(quickAttachment)
      }

      else -> {
        buttonToggle.display(sendButton)
        quickAttachment.hide(true)

        if (!attachmentManager.isAttachmentPresent && !linkPreviewViewModel.hasLinkPreviewUi) {
          inlineAttachment.show()
          inputPanel.setTrailingControls(inlineAttachment)
        } else {
          inlineAttachment.hide(true)
          inputPanel.setTrailingControls(null)
        }
      }
    }
  }

  private fun sendSticker(
    stickerRecord: StickerRecord,
    clearCompose: Boolean
  ) {
    val stickerLocator = StickerLocator(stickerRecord.packId, stickerRecord.packKey, stickerRecord.stickerId, stickerRecord.emoji)
    val slide = StickerSlide(
      requireContext(),
      stickerRecord.uri,
      stickerRecord.size,
      stickerLocator,
      stickerRecord.contentType
        .takeIf { it.isNotBlank() }
        ?: MediaUtil.IMAGE_WEBP
    )

    val quote = if (SignalStore.labs.stickerReplies) {
      inputPanel.quote.orNull()
    } else {
      null
    }

    sendMessageWithoutComposeInput(slide = slide, quote = quote, clearCompose = clearCompose)

    if (quote != null) {
      inputPanel.clearQuote()
    }

    viewModel.updateStickerLastUsedTime(stickerRecord, System.currentTimeMillis().milliseconds)
  }

  private fun sendMessageWithoutComposeInput(
    slide: Slide? = null,
    contacts: List<Contact> = emptyList(),
    quote: QuoteModel? = null,
    clearCompose: Boolean = true,
    scheduledDate: Long = -1
  ) {
    sendMessage(
      slideDeck = slide?.let { SlideDeck().apply { addSlide(slide) } },
      contacts = contacts,
      clearCompose = clearCompose,
      body = "",
      mentions = emptyList(),
      bodyRanges = null,
      messageToEdit = null,
      quote = quote,
      linkPreviews = emptyList(),
      bypassPreSendSafetyNumberCheck = true,
      scheduledDate = scheduledDate
    )
  }

  private fun sendPoll(recipient: Recipient, poll: Poll) {
    val send = viewModel.sendPoll(recipient, poll)

    disposables += send
      .subscribeBy(
        onComplete = { onSendComplete() },
        onError = { Log.w(TAG, "Error received during poll send!", it) }
      )
  }

  private fun sendMessage(
    body: String = composeText.editableText.toString().trim(),
    mentions: List<Mention> = composeText.mentions,
    bodyRanges: BodyRangeList? = composeText.styling,
    messageToEdit: MessageId? = inputPanel.editMessageId,
    quote: QuoteModel? = inputPanel.quote.orNull(),
    scheduledDate: Long = -1,
    slideDeck: SlideDeck? = if (attachmentManager.isAttachmentPresent) attachmentManager.buildSlideDeck() else null,
    contacts: List<Contact> = emptyList(),
    clearCompose: Boolean = true,
    linkPreviews: List<LinkPreview> = linkPreviewViewModel.onSend(),
    preUploadResults: List<MessageSender.PreUploadResult> = emptyList(),
    bypassPreSendSafetyNumberCheck: Boolean = false,
    isViewOnce: Boolean = false,
    captionAboveMedia: Boolean = false,
    afterSendComplete: () -> Unit = {}
  ) {
    val threadRecipient = viewModel.recipientSnapshot

    if (threadRecipient == null) {
      Log.w(TAG, "Unable to send due to invalid thread recipient")
      toast(R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation, Toast.LENGTH_LONG)
      return
    }

    if (scheduledDate != -1L && ReenableScheduledMessagesDialogFragment.showIfNeeded(requireContext(), childFragmentManager, null, scheduledDate)) {
      return
    }

    if (inputPanel.isRecordingInLockedMode) {
      inputPanel.releaseRecordingLockAndSend()
      return
    }

    if (slideDeck == null) {
      val voiceNote: DraftTable.Draft? = draftViewModel.voiceNoteDraft
      if (voiceNote != null) {
        sendMessageWithoutComposeInput(
          slide = AudioSlide.createFromVoiceNoteDraft(voiceNote),
          quote = quote,
          clearCompose = true,
          scheduledDate = scheduledDate
        )
        return
      }
    }

    if (body.isBlank() && slideDeck?.containsMediaSlide() != true && preUploadResults.isEmpty() && contacts.isEmpty()) {
      Log.i(TAG, "Unable to send due to empty message")
      toast(R.string.ConversationActivity_message_is_empty_exclamation)
      return
    }

    if (viewModel.identityRecordsState.hasRecentSafetyNumberChange() && !bypassPreSendSafetyNumberCheck) {
      Log.i(TAG, "Unable to send due to SNC")
      handleRecentSafetyNumberChange(viewModel.identityRecordsState.getRecentSafetyNumberChangeRecords())
      return
    }

    val metricId = viewModel.recipientSnapshot?.let { if (it.isGroup) SignalLocalMetrics.GroupMessageSend.start() else SignalLocalMetrics.IndividualMessageSend.start() }

    val send: Completable = viewModel.sendMessage(
      metricId = metricId,
      threadRecipient = threadRecipient,
      body = body,
      slideDeck = slideDeck,
      scheduledDate = scheduledDate,
      messageToEdit = messageToEdit,
      quote = quote,
      mentions = mentions,
      bodyRanges = bodyRanges,
      contacts = contacts,
      linkPreviews = linkPreviews,
      preUploadResults = preUploadResults,
      isViewOnce = isViewOnce,
      captionAboveMedia = captionAboveMedia
    )

    disposables += send
      .doOnSubscribe {
        if (clearCompose) {
          AppDependencies.typingStatusSender.onTypingStopped(args.threadId)
          composeTextEventsListener?.typingStatusEnabled = false
          composeText.setText("")
          composeTextEventsListener?.typingStatusEnabled = true
          attachmentManager.clear(Glide.with(this@ConversationFragment), false)
          inputPanel.clearQuote()
        }
        scrollToPositionDelegate.markListCommittedVersion()
      }
      .subscribeBy(
        onComplete = {
          onSendComplete()
          afterSendComplete()
        },
        onError = {
          Log.w(TAG, "Error received during send!", it)
          toast(R.string.ConversationActivity_error_sending_media)
        }
      )
  }

  private fun onSendComplete() {
    if (isDetached || activity?.isFinishing == true) {
      return
    }

    scrollToPositionDelegate.resetScrollPositionAfterMarkListVersionSurpassed()
    attachmentManager.cleanup()

    updateLinkPreviewState()

    draftViewModel.clearDraft()

    inputPanel.exitEditMessageMode()

    if (args.conversationScreenType.isInPopup) {
      activity?.finish()
    }
  }

  private fun handleRecentSafetyNumberChange(changedRecords: List<IdentityRecord>) {
    val recipient = viewModel.recipientSnapshot ?: return
    SafetyNumberBottomSheet
      .forIdentityRecordsAndDestination(changedRecords, RecipientSearchKey(recipient.id, false))
      .show(childFragmentManager)
  }

  /**
   * Tellomi（交互审计 A-30）：页面还在时改用锚在输入栏上方、能横滑关掉的提示条（[TellomiBulletin]）；
   * 页面已经离开（异步结果回来晚了）才退回系统 Toast。
   */
  private fun toast(@StringRes toastTextId: Int, toastDuration: Int = Toast.LENGTH_SHORT) {
    ThreadUtil.runOnMain {
      val context = context
      if (context == null) {
        Log.w(TAG, "Dropping toast without context.")
      } else if (isAdded && view != null) {
        TellomiBulletin.show(binding.tellomiBulletinHost, context.getString(toastTextId), TellomiBulletin.durationForToast(toastDuration))
      } else {
        Toast.makeText(context, toastTextId, toastDuration).show()
      }
    }
  }

  private fun snackbar(
    @StringRes text: Int,
    @Duration duration: Int = Snackbar.LENGTH_LONG
  ) {
    TellomiBulletin.show(binding.tellomiBulletinHost, getString(text), duration)
  }

  private fun maybeShowSwipeToReplyTooltip() {
    if (!TextSecurePreferences.hasSeenSwipeToReplyTooltip(requireContext())) {
      // Tellomi（tellomi/tellomi#1109）：回复改成手指往开头那边滑（从左往右排版时往左），提示跟着换。
      val tooltipText = if (ViewUtil.isLtr(requireContext())) {
        R.string.ConversationFragment_you_can_swipe_to_the_left_reply
      } else {
        R.string.ConversationFragment_you_can_swipe_to_the_right_reply
      }

      snackbar(tooltipText)

      TextSecurePreferences.setHasSeenSwipeToReplyTooltip(requireContext(), true)
    }
  }

  private fun calculateSelectedItemCount(): String {
    val count = adapter.selectedItems.map(MultiselectPart::conversationMessage).distinct().count()
    return requireContext().resources.getQuantityString(R.plurals.conversation_context__s_selected, count, count)
  }

  private fun getSelectedConversationMessage(): ConversationMessage {
    val records = adapter.selectedItems.map(MultiselectPart::conversationMessage).distinct().toSet()
    if (records.size == 1) {
      return records.first()
    }

    error("More than one conversation message in set.")
  }

  private fun setCorrectActionModeMenuVisibility() {
    val selectedParts = adapter.selectedItems

    if (isActionModeStarted() && selectedParts.isEmpty()) {
      finishActionMode()
      return
    }

    val recipient = viewModel.recipientSnapshot ?: return
    val menuState = MenuState.getMenuState(
      recipient,
      selectedParts,
      viewModel.hasMessageRequestState,
      conversationGroupViewModel.isNonAdminInAnnouncementGroup(),
      conversationGroupViewModel.canEditGroupInfo()
    )

    val items = arrayListOf<ActionItem>()

    if (menuState.shouldShowReplyAction()) {
      items.add(
        ActionItem(R.drawable.symbol_reply_24, resources.getString(R.string.conversation_selection__menu_reply)) {
          maybeShowSwipeToReplyTooltip()
          handleReplyToMessage(getSelectedConversationMessage())
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowEditAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_edit_24, resources.getString(R.string.conversation_selection__menu_edit)) {
          handleEditMessage(getSelectedConversationMessage())
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowForwardAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_forward_24, resources.getString(R.string.conversation_selection__menu_forward)) {
          handleForwardMessageParts(selectedParts)
        }
      )
    }

    if (menuState.shouldShowSaveAttachmentAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_save_android_24, resources.getString(R.string.conversation_selection__menu_save)) {
          handleSaveAttachment(getSelectedConversationMessage().messageRecord as MmsMessageRecord)
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowCopyAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_copy_android_24, resources.getString(R.string.conversation_selection__menu_copy)) {
          handleCopyMessage(selectedParts)
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowDetailsAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_info_24, resources.getString(R.string.conversation_selection__menu_message_details)) {
          handleDisplayDetails(getSelectedConversationMessage())
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowStarMessage()) {
      items.add(
        ActionItem(R.drawable.symbol_star_outline_24, resources.getString(R.string.conversation_selection__menu_star)) {
          handleStarMessages(selectedParts.map { it.conversationMessage.messageRecord.id }.toSet())
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowUnstarMessage()) {
      items.add(
        ActionItem(R.drawable.symbol_star_outline_24, resources.getString(R.string.conversation_selection__menu_unstar)) {
          handleUnstarMessages(selectedParts.map { it.conversationMessage.messageRecord.id }.toSet())
          finishActionMode()
        }
      )
    }

    if (menuState.shouldShowDeleteAction()) {
      items.add(
        ActionItem(CoreUiR.drawable.symbol_trash_24, resources.getString(R.string.conversation_selection__menu_delete)) {
          handleDeleteMessages(selectedParts)
          finishActionMode()
        }
      )
    }

    bottomActionBar.setItems(items)
    setBottomActionBarVisibility(true)
  }

  private fun setBottomActionBarVisibility(isVisible: Boolean) {
    if (view == null) {
      return
    }

    // Tellomi（交互审计 A-15）：看的是「该不该显示」，不是操作栏现在可不可见——退出的过渡里它还可见。
    if (isVisible == selectionBarsShown) {
      return
    }
    selectionBarsShown = isVisible
    val generation = ++selectionBarsGeneration

    // 上一次还没换完又切了：先落到它的终点，再从那里开始这一次。
    selectionBarsAnimator?.end()

    if (isVisible) {
      animationsAllowed = false
      bottomActionBar.alpha = 0f
      bottomActionBar.isVisible = true
      container.hideInput()

      bottomActionBar.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          if (bottomActionBar.measuredHeight == 0) {
            return
          }

          bottomActionBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
          if (generation != selectionBarsGeneration || view == null) {
            return
          }

          // 列表底边让到操作栏上沿：最下面那条停在操作栏上面，和平时停在输入栏上面一样。输入栏先不收（要淡出），收起放到最后。
          val frame = binding.conversationItemRecyclerFrame
          val extra = (frame.bottom - bottomActionBar.top).coerceAtLeast(0)
          tellomiRunSelectionBars(
            entering = true,
            generation = generation,
            changeLayout = {
              ViewUtil.setPaddingBottom(binding.conversationItemRecycler, resources.getDimensionPixelSize(R.dimen.conversation_bottom_padding) + extra)
            },
            onEnd = {
              // 换完：输入栏真的收起，列表多出来的那截用底边距补上——底边距的位置不变，列表不动。
              val inputHeight = inputPanel.height
              tellomiKeepListInPlace {
                inputPanel.setHideForSelection(true)
                ViewUtil.setPaddingBottom(binding.conversationItemRecycler, binding.conversationItemRecycler.paddingBottom + inputHeight)
              }
              animationsAllowed = true
            }
          )
        }
      })
    } else {
      // 列表整体跟着进度滑；这一步不让条目自己的位移动画再滑一遍（实测两份位移叠在一起，先往反方向跳 73 px）。
      animationsAllowed = false
      tellomiRunSelectionBars(
        entering = false,
        generation = generation,
        changeLayout = {
          inputPanel.setHideForSelection(false)
          ViewUtil.setPaddingBottom(binding.conversationItemRecycler, resources.getDimensionPixelSize(R.dimen.conversation_bottom_padding))
        },
        onEnd = {
          bottomActionBar.isVisible = false
          animationsAllowed = true
        }
      )
    }
  }

  /**
   * Tellomi（交互审计 A-15）：先把列表排到新位置（[changeLayout]），列表这一次排完（还没画）就量出最下面那条挪了多少，用列表外层的位移把它放回原处；
   * 然后一个进度（[TellomiContextualBars.DURATION_MS]）同时驱动输入栏、操作栏和列表（[TellomiContextualBars.bottomFrame]），
   * 和顶栏的淡入同一时长、同一条曲线。「移除动画」档直接到终点。
   */
  private fun tellomiRunSelectionBars(entering: Boolean, generation: Int, changeLayout: () -> Unit, onEnd: () -> Unit) {
    val recycler = binding.conversationItemRecycler
    val anchor = tellomiListAnchor()
    if (!entering) {
      // 输入栏一出现就是透明、在下面的，不会先闪一帧
      inputPanel.alpha = 0f
      inputPanel.translationY = TellomiContextualBars.INPUT_SLIDE_DP.dp.toFloat()
    }
    changeLayout()

    // 量要等列表按新布局排完：这里常在别的 View 的排版回调里，同一轮的画之前列表还没重排（实测量到 0、下一帧列表跳一下）。
    recycler.requestLayout()
    recycler.doOnNextLayout {
      if (generation != selectionBarsGeneration || view == null) {
        return@doOnNextLayout
      }

      val shift = tellomiListAnchorShift(anchor)
      val inputSlide = TellomiContextualBars.INPUT_SLIDE_DP.dp.toFloat()
      val barSlide = bottomActionBar.height * TellomiContextualBars.BAR_SLIDE_FRACTION
      val applyProgress = { progress: Float ->
        val frame = TellomiContextualBars.bottomFrame(progress, entering, inputSlide, barSlide, listShiftPx = -shift)
        inputPanel.alpha = frame.inputAlpha
        inputPanel.translationY = frame.inputTranslationY
        bottomActionBar.alpha = frame.barAlpha
        bottomActionBar.translationY = frame.barTranslationY
        tellomiSetListOffset(frame.listTranslationY)
      }
      val finish = {
        inputPanel.alpha = 1f
        inputPanel.translationY = 0f
        bottomActionBar.alpha = 1f
        bottomActionBar.translationY = 0f
        tellomiSetListOffset(0f)
        onEnd()
      }

      if (TellomiMotion.tier(requireContext()) == TellomiMotion.Tier.MINIMAL) {
        finish()
        return@doOnNextLayout
      }

      applyProgress(0f)
      selectionBarsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TellomiContextualBars.DURATION_MS
        interpolator = TellomiContextualBars.interpolator()
        addUpdateListener { applyProgress(it.animatedValue as Float) }
        doOnEnd { animator ->
          if (selectionBarsAnimator === animator) {
            selectionBarsAnimator = null
          }
          finish()
        }
        start()
      }
    }
  }

  /** Tellomi（交互审计 A-15）：最下面看得见的那条（倒排列表里第一个可见的）和它现在的位置。 */
  private fun tellomiListAnchor(): Pair<Int, Int>? {
    val layoutManager = binding.conversationItemRecycler.layoutManager as? LinearLayoutManager ?: return null
    val position = layoutManager.findFirstVisibleItemPosition()
    if (position == RecyclerView.NO_POSITION) {
      return null
    }
    val anchorView = layoutManager.findViewByPosition(position) ?: return null
    return position to anchorView.top
  }

  /** 排版之后，那条往下挪了多少（往上是负的）。 */
  private fun tellomiListAnchorShift(anchor: Pair<Int, Int>?): Float {
    val (position, top) = anchor ?: return 0f
    val layoutManager = binding.conversationItemRecycler.layoutManager as? LinearLayoutManager ?: return 0f
    val anchorView = layoutManager.findViewByPosition(position) ?: return 0f
    return (anchorView.top - top).toFloat()
  }

  /** 改了布局以后把列表放回原来看到的位置（列表这一次排完、还没画就滚回去）。 */
  private fun tellomiKeepListInPlace(changeLayout: () -> Unit) {
    val recycler = binding.conversationItemRecycler
    val anchor = tellomiListAnchor()
    changeLayout()
    recycler.requestLayout()
    recycler.doOnNextLayout {
      val shift = tellomiListAnchorShift(anchor).toInt()
      if (shift != 0) {
        recycler.scrollBy(0, shift)
      }
    }
  }

  /** 列表外层的位移：和输入区增高的动画（A-11）共用一层，加在一起。 */
  private fun tellomiSetListOffset(offset: Float) {
    val composerAnimator = composerHeightAnimator
    if (composerAnimator != null) {
      composerAnimator.externalListOffset = offset
    } else {
      binding.conversationItemRecyclerFrame.translationY = offset
    }
  }

  private fun isUnopenedGift(itemView: View, messageRecord: MessageRecord): Boolean {
    if (itemView is OpenableGift) {
      val projection = (itemView as OpenableGift).getOpenableGiftProjection(false)
      if (projection != null) {
        projection.release()
        return !openableGiftItemDecoration.hasOpenedGiftThisSession(messageRecord.id)
      }
    }

    return false
  }

  private fun clearFocusedItem() {
    multiselectItemDecoration.setFocusedItem(null)
    binding.conversationItemRecycler.invalidateItemDecorations()
  }

  private fun handleReaction(
    conversationMessage: ConversationMessage,
    snapshot: ReactionOverlaySnapshot,
    focusedView: View?
  ) {
    reactionOverlay.show(
      conversationRecipient = viewModel.recipientSnapshot!!,
      conversationMessage = conversationMessage,
      snapshot = snapshot,
      isNonAdminInAnnouncementGroup = conversationGroupViewModel.isNonAdminInAnnouncementGroup(),
      canEditGroupInfo = conversationGroupViewModel.canEditGroupInfo(),
      focusedView = focusedView
    )
    viewModel.setIsReactionDelegateShowing(true)
    // Tellomi (A-06): the field keeps focus while the keyboard stays up, or the keyboard would be left typing into nothing.
    if (!keepsKeyboardForReaction) {
      composeText.clearFocus()
    }
  }

  /** Tellomi (A-06, Q-05 = A): the current long press left the system keyboard up. */
  private var keepsKeyboardForReaction = false

  //region Message Request Helpers

  @SuppressLint("CheckResult")
  private fun onReportSpam() {
    val recipient = viewModel.recipientSnapshot
    if (recipient == null) {
      Log.w(TAG, "[onBlockClicked] No recipient!")
      return
    }

    BlockUnblockDialog.showReportSpamFor(
      requireContext(),
      recipient,
      {
        val disabledInput = binding.conversationDisabledInput
        messageRequestViewModel
          .onReportSpam()
          .doOnSubscribe { disabledInput.showBusy() }
          .doOnTerminate {
            disabledInput.hideBusy()
            viewModel.refreshInputReadyState()
          }
          .subscribeBy {
            Log.d(TAG, "report spam complete")
            toast(R.string.ConversationFragment_reported_as_spam)
          }
      },
      if (recipient.isBlocked) {
        null
      } else {
        Runnable {
          val disabledInput = binding.conversationDisabledInput
          messageRequestViewModel
            .onBlockAndReportSpam()
            .doOnSubscribe { disabledInput.showBusy() }
            .doOnTerminate {
              disabledInput.hideBusy()
              viewModel.refreshInputReadyState()
            }
            .subscribeBy { result ->
              when (result) {
                is Result.Success -> {
                  Log.d(TAG, "report spam complete")
                  toast(R.string.ConversationFragment_reported_as_spam_and_blocked)
                }

                is Result.Failure -> {
                  Log.d(TAG, "report spam failed ${result.failure}")
                  toast(GroupErrors.getUserDisplayMessage(result.failure))
                }
              }
            }
        }
      }
    )
  }

  @SuppressLint("CheckResult")
  private fun onBlock() {
    val recipient = viewModel.recipientSnapshot
    if (recipient == null) {
      Log.w(TAG, "[onBlockClicked] No recipient!")
      return
    }

    BlockUnblockDialog.showBlockFor(
      requireContext(),
      recipient
    ) {
      messageRequestViewModel
        .onBlock()
        .subscribeWithShowProgress("block")
    }
  }

  @SuppressLint("CheckResult")
  private fun onUnblock() {
    val recipient = viewModel.recipientSnapshot
    if (recipient == null) {
      Log.w(TAG, "[onUnblockClicked] No recipient!")
      return
    }

    BlockUnblockDialog.showUnblockFor(
      requireContext(),
      recipient
    ) {
      messageRequestViewModel
        .onUnblock()
        .subscribeWithShowProgress("unblock")
    }
  }

  private fun onMessageRequestAccept() {
    messageRequestViewModel
      .onAccept()
      .subscribeWithShowProgress("accept message request")
  }

  private fun onDeleteConversation() {
    val recipient = viewModel.recipientSnapshot
    if (recipient == null) {
      Log.w(TAG, "[onDeleteConversation] No recipient!")
      return
    }

    ConversationDialogs.displayDeleteDialog(requireContext(), recipient) {
      messageRequestViewModel
        .onDelete()
        .doAfterSuccess { mainNavigationEventSink.onEvent(MainNavigationEvents.ExitDetail) }
        .subscribeWithShowProgress("delete message request")
    }
  }

  private fun Single<Result<Unit, GroupChangeFailureReason>>.subscribeWithShowProgress(logMessage: String): Disposable {
    val disabledInput = binding.conversationDisabledInput
    return doOnSubscribe { disabledInput.showBusy() }
      .doOnTerminate {
        disabledInput.hideBusy()
        viewModel.refreshInputReadyState()
      }
      .subscribeBy { result ->
        when (result) {
          is Result.Success -> Log.d(TAG, "$logMessage complete")

          is Result.Failure -> {
            Log.d(TAG, "$logMessage failed ${result.failure}")
            toast(GroupErrors.getUserDisplayMessage(result.failure))
          }
        }
      }
  }

  private inner class BackPressedCallback : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      handleBackPressed()
    }
  }

  // endregion

  //region Message action handling

  private fun handleReplyToMessage(conversationMessage: ConversationMessage) {
    if (isSearchRequested) {
      searchMenuItem?.collapseActionView()
    }

    if (inputPanel.inEditMessageMode()) {
      inputPanel.exitEditMessageMode()
    }

    val (slideDeck, body) = viewModel.getSlideDeckAndBodyForReply(requireContext(), conversationMessage)
    val author = conversationMessage.messageRecord.fromRecipient

    inputPanel.setQuote(
      Glide.with(this),
      conversationMessage.messageRecord.dateSent,
      author,
      body,
      slideDeck,
      conversationMessage.messageRecord.getRecordQuoteType()
    )

    inputPanel.clickOnComposeInput()
  }

  private fun handleEditMessage(conversationMessage: ConversationMessage) {
    val isNoteToSelf = viewModel.recipientSnapshot?.isSelf ?: false
    if (!isNoteToSelf && !MessageConstraintsUtil.isWithinMaxEdits(conversationMessage.messageRecord)) {
      Log.i(TAG, "Too many edits to the message")
      Dialogs.showAlertDialog(requireContext(), null, resources.getQuantityString(R.plurals.ConversationActivity_edit_message_too_many_edits, MessageConstraintsUtil.MAX_EDIT_COUNT, MessageConstraintsUtil.MAX_EDIT_COUNT))

      return
    }

    if (isSearchRequested) {
      searchMenuItem?.collapseActionView()
    }

    viewModel.resolveMessageToEdit(conversationMessage)
      .subscribeBy { updatedMessage ->
        inputPanel.enterEditModeIfPossible(Glide.with(this), updatedMessage, false, false)
      }
      .addTo(disposables)
  }

  private fun handleForwardMessageParts(messageParts: Set<MultiselectPart>) {
    inputPanel.clearQuote()

    MultiselectForwardFragmentArgs.create(requireContext(), messageParts) { args ->
      // Tellomi（#1259 F-1）：长按 / 多选的「转发」打开头像网格
      TellomiForwardGridBottomSheet.show(childFragmentManager, args)
    }
  }

  /**
   * Tellomi：长按「收藏」——不开转发面板，直接发到「我的收藏」，提示「已收藏」可点「查看」（#1174）。
   * 内容和转发一样（MultiselectForwardFragmentArgs），只是收件人固定是自己。
   */
  private fun handleSaveToSavedMessages(messageParts: Set<MultiselectPart>) {
    MultiselectForwardFragmentArgs.create(requireContext(), messageParts) { args ->
      MultiselectForwardRepository.send(
        additionalMessage = "",
        multiShareArgs = args.multiShareArgs,
        shareContacts = setOf(RecipientSearchKey(Recipient.self().id, false)),
        resultHandlers = MultiselectForwardRepository.MultiselectForwardResultHandlers(
          onAllMessageSentSuccessfully = { ThreadUtil.runOnMain { showSavedToSavedMessages() } },
          onSomeMessagesFailed = { toast(R.string.ConversationFragment__tellomi_couldnt_save) },
          onAllMessagesFailed = { toast(R.string.ConversationFragment__tellomi_couldnt_save) }
        )
      )
    }
  }

  private fun showSavedToSavedMessages() {
    // 发送是异步的，回来时页面可能已经进了返回栈（fragment 还在、view 没了），这时取 binding 会抛
    if (!isAdded || view == null) {
      return
    }

    TellomiBulletin.show(
      host = binding.tellomiBulletinHost,
      text = getString(R.string.ConversationFragment__tellomi_saved_to_saved_messages),
      actionText = getString(R.string.ConversationFragment__tellomi_view_saved_messages)
    ) {
      CommunicationActions.startConversation(requireContext(), Recipient.self(), null)
    }
  }

  private fun handleSaveAttachment(record: MmsMessageRecord) {
    if (record.isViewOnce) {
      error("Cannot save a view-once message")
    }

    lifecycleScope.launch {
      AttachmentSaver(this@ConversationFragment).saveAttachments(record)
    }
  }

  private fun handleCopyMessage(messageParts: Set<MultiselectPart>) {
    viewModel.copyToClipboard(requireContext(), messageParts).subscribe().addTo(disposables)
  }

  private fun handleResend(conversationMessage: ConversationMessage) {
    viewModel.resendMessage(conversationMessage).subscribe()
  }

  private fun handleEnterMultiselect(conversationMessage: ConversationMessage) {
    val parts = conversationMessage.multiselectCollection.toSet()
    parts.forEach { adapter.toggleSelection(it) }
    startActionMode()
  }

  private fun handleViewPaymentDetails(conversationMessage: ConversationMessage) {
    val record: MmsMessageRecord = conversationMessage.messageRecord as? MmsMessageRecord ?: return
    val payment = record.payment
    if (payment == null || record.isPaymentTombstone) {
      showPaymentTombstoneLearnMoreDialog()
      return
    }
    if (record.isPaymentNotification) {
      startActivity(PaymentsActivity.navigateToPaymentDetails(requireContext(), payment.uuid))
    }
  }

  private fun showPaymentTombstoneLearnMoreDialog() {
    val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
    dialogBuilder
      .setTitle(R.string.PaymentTombstoneLearnMoreDialog_title)
      .setMessage(R.string.PaymentTombstoneLearnMoreDialog_message)
      .setPositiveButton(android.R.string.ok, null)

    dialogBuilder.show()
  }

  private fun showAttachmentBackfillFailureDialog(reason: AttachmentBackfill.FailureReason) {
    val messageRes = when (reason) {
      AttachmentBackfill.FailureReason.TIMEOUT -> R.string.ConversationFragment_attachment_backfill_timeout
      AttachmentBackfill.FailureReason.NOT_FOUND -> R.string.ConversationFragment_attachment_backfill_not_found
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.ConversationFragment_attachment_backfill_failed_title)
      .setMessage(messageRes)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun handleDisplayDetails(conversationMessage: ConversationMessage) {
    val recipientSnapshot = viewModel.recipientSnapshot ?: return
    val messageDetails = MainDetailRoute.Chats.MessageDetails(recipientSnapshot.id, MessageId(conversationMessage.messageRecord.id))
    mainNavigationEventSink.onEvent(MainNavigationEvents.GoToDetail(messageDetails))
  }

  private fun handleDeleteMessages(messageParts: Set<MultiselectPart>) {
    val records = messageParts.map(MultiselectPart::getMessageRecord).toSet()

    disposables += DeleteDialog.show(
      context = requireContext(),
      messageRecords = records,
      title = requireContext().resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_title, records.size, records.size),
      message = requireContext().resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_body, records.size, records.size),
      isAdmin = conversationGroupViewModel.isAdmin()
    ).observeOn(AndroidSchedulers.mainThread())
      .subscribe { (deleted: Boolean, _: Boolean) ->
        if (!deleted) return@subscribe
        val editMessageId = inputPanel.editMessageId?.id
        if (editMessageId != null && records.any { it.id == editMessageId }) {
          inputPanel.exitEditMessageMode()
        }
      }
  }

  private fun handleEndPoll(pollId: Long?) {
    if (pollId == null) {
      Log.w(TAG, "Unable to find poll to end $pollId")
      return
    }

    if (conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true) {
      MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.conversation_activity__group_action_not_allowed_group_ended)
        .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
        .show()
      return
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(getString(R.string.Poll__end_poll_title))
      .setMessage(getString(R.string.Poll__end_poll_body))
      .setPositiveButton(R.string.Poll__end_poll) { _, _ ->
        val endPoll = viewModel.endPoll(pollId)

        disposables += endPoll
          .doOnSubscribe {
            handler.postDelayed({ showSpinner() }, POLL_SPINNER_DELAY.inWholeMilliseconds)
          }
          .doFinally {
            handler.removeCallbacksAndMessages(null)
            hideSpinner()
          }
          .subscribeBy(
            onError = {
              Log.w(TAG, "Error received during poll end!", it)
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.Poll__couldnt_end_poll)
                .setMessage(getString(R.string.Poll__check_connection))
                .setPositiveButton(android.R.string.ok) { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() }
                .show()
            }
          )
      }
      .setNegativeButton(android.R.string.cancel) { _, _ -> }
      .show()
  }

  private fun showSpinner() {
    progressDialog = ProgressCardDialogFragment.create(getString(R.string.Poll__waiting_for_network))
    progressDialog?.show(parentFragmentManager, null)
  }

  private fun hideSpinner() {
    progressDialog?.dismissAllowingStateLoss()
    progressDialog = null
  }

  private fun viewPinnedMessage(messageId: Long) {
    disposables += viewModel
      .moveToMessage(messageId)
      .subscribeBy(
        onSuccess = { moveToPosition(it) },
        onError = { Toast.makeText(requireContext(), R.string.PinnedMessage__not_found, Toast.LENGTH_LONG).show() }
      )
  }

  private inner class SwipeAvailabilityProvider : ConversationItemSwipeCallback.SwipeAvailabilityProvider {
    override fun isSwipeAvailable(conversationMessage: ConversationMessage): Boolean {
      val recipient = viewModel.recipientSnapshot ?: return false

      return !isActionModeStarted() &&
        MenuState.canReplyToMessage(
          recipient,
          MenuState.isActionMessage(conversationMessage.messageRecord),
          conversationMessage.messageRecord,
          viewModel.hasMessageRequestState,
          conversationGroupViewModel.isNonAdminInAnnouncementGroup()
        )
    }
  }

  //endregion

  //region Scroll Handling

  private fun moveToStartPosition(meta: ConversationData) {
    if (meta.getStartPosition() == 0) {
      layoutManager.scrollToPositionWithOffset(0, 0) {
        animationsAllowed = true
        markReadHelper.stopIgnoringViewReveals(MarkReadHelper.getLatestTimestamp(adapter, layoutManager).orNull())
      }
    } else {
      binding.toolbar.viewTreeObserver.addOnGlobalLayoutListener(StartPositionScroller(meta))
    }
  }

  /** Helper to scroll the conversation to the correct position and offset based on toolbar height and the type of position */
  private inner class StartPositionScroller(private val meta: ConversationData) : ViewTreeObserver.OnGlobalLayoutListener {

    override fun onGlobalLayout() {
      if (!isAdded || view == null) {
        return
      }

      val rect = Rect()
      binding.toolbar.getGlobalVisibleRect(rect)
      val toolbarOffset = rect.bottom
      binding.toolbar.viewTreeObserver.removeOnGlobalLayoutListener(this)

      val startPosition = meta.getStartPosition()
      Log.d(TAG, "Scrolling to start position $startPosition")

      if (!meta.messageRequestData.isMessageRequestAccepted) {
        // Always scroll to the top to show header in MR state
        layoutManager.scrollToPositionTopAligned(meta.threadSize, toolbarOffset) {
          animationsAllowed = true
          markReadHelper.stopIgnoringViewReveals(MarkReadHelper.getLatestTimestamp(adapter, layoutManager).orNull())
        }
      } else if (meta.shouldScrollToFirstUnread()) {
        // Land the divider just below the toolbar.
        layoutManager.scrollToPositionTopAligned(startPosition, toolbarOffset) {
          animationsAllowed = true
          markReadHelper.stopIgnoringViewReveals(MarkReadHelper.getLatestTimestamp(adapter, layoutManager).orNull())
        }
      } else {
        val offset = when {
          startPosition == 0 -> 0
          meta.shouldJumpToMessage() -> (binding.conversationItemRecycler.height - toolbarOffset) / 4
          else -> binding.conversationItemRecycler.height
        }
        layoutManager.scrollToPositionWithOffset(startPosition, offset) {
          animationsAllowed = true
          markReadHelper.stopIgnoringViewReveals(MarkReadHelper.getLatestTimestamp(adapter, layoutManager).orNull())
          if (meta.shouldJumpToMessage()) {
            binding.conversationItemRecycler.post {
              adapter.pulseAtPosition(startPosition)
            }
          }
        }
      }
    }
  }

  /**
   * Requests a jump to the desired position, and ensures that the position desired will be visible on the screen.
   */
  private fun moveToPosition(position: Int) {
    isJumpInFlight = true
    scrollToPositionDelegate.requestScrollPosition(
      position = position,
      smooth = true,
      scrollStrategy = jumpAndPulseScrollStrategy
    )
  }

  private fun scrollToNextMention() {
    disposables += viewModel.getNextMentionPosition().subscribeBy {
      moveToPosition(it)
    }
  }

  /**
   * The methods used in this method are taken directly from View.canScrollVertically(-1)'s code path.
   */
  private fun isScrolledToBottom(): Boolean {
    return with(binding.conversationItemRecycler) {
      val offset = computeVerticalScrollOffset()
      val range = computeVerticalScrollRange() - computeVerticalScrollExtent()
      val delta = range - offset

      delta <= IS_SCROLLED_TO_BOTTOM_THRESHOLD
    }
  }

  private fun isScrolledPastButtonThreshold(): Boolean {
    return layoutManager.findFirstVisibleItemPosition() > 4
  }

  private fun shouldScrollToBottom(): Boolean {
    return isScrolledToBottom() || layoutManager.findFirstVisibleItemPosition() <= 0
  }

  /**
   * The open search view's text field, or null when search is closed. Focusing the [SearchView] itself
   * does not bring the keyboard up, since the keyboard follows the focused field rather than its host.
   */
  private fun expandedSearchField(): EditText? {
    return searchMenuItem
      ?.takeIf { it.isActionViewExpanded }
      ?.actionView
      ?.findViewById(androidx.appcompat.R.id.search_src_text)
  }

  private fun closeChatSearch() {
    isSearchRequested = false
    searchViewModel.onSearchClosed()
    searchNav.visible = false
    inputPanel.setHideForSearch(false)
    viewModel.setSearchQuery(null)
    binding.conversationDisabledInput.visible = true
    invalidateOptionsMenu()
  }

  private fun scrollToBottom() {
    layoutManager.scrollToPositionWithOffset(0, 0)
    scrollListener?.onScrolled(binding.conversationItemRecycler, 0, 0)
  }

  /**
   * Controls animation and visibility of the scrollDateHeader.
   */
  private inner class ScrollDateHeaderHelper {

    private val slideIn = AnimationUtils.loadAnimation(
      requireContext(),
      R.anim.slide_from_top
    ).apply {
      duration = SCROLL_HEADER_ANIMATION_DURATION
    }

    private val slideOut = AnimationUtils.loadAnimation(
      requireContext(),
      R.anim.conversation_scroll_date_header_slide_to_top
    ).apply {
      duration = SCROLL_HEADER_ANIMATION_DURATION
    }

    private var pendingHide = false

    fun show() {
      if (binding.scrollDateHeader.text.isNullOrEmpty()) {
        return
      }

      if (pendingHide) {
        pendingHide = false
      } else {
        ViewUtil.animateIn(binding.scrollDateHeader, slideIn)
      }
    }

    fun bind(message: ConversationMessage?) {
      if (message != null) {
        binding.scrollDateHeader.text = DateUtils.getConversationDateHeaderString(requireContext(), Locale.getDefault(), message.conversationTimestamp)
      }
    }

    fun hide() {
      pendingHide = true

      val header = binding.scrollDateHeader

      header.postDelayed({
        if (pendingHide) {
          pendingHide = false
          ViewUtil.animateOut(header, slideOut)
        }
      }, SCROLL_HEADER_CLOSE_DELAY)
    }
  }

  private inner class ScrollListener : RecyclerView.OnScrollListener() {

    private var wasAtBottom = true
    private val scrollDateHeaderHelper = ScrollDateHeaderHelper()

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
      if (isScrolledToBottom()) {
        if (!isJumpInFlight) {
          quoteReturnMessageId = null
        }
        viewModel.setShowScrollButtonsForScrollPosition(showScrollButtons = false, willScrollToBottomOnNewMessage = true)
      } else if (isScrolledPastButtonThreshold()) {
        viewModel.setShowScrollButtonsForScrollPosition(showScrollButtons = true, willScrollToBottomOnNewMessage = false)
      } else {
        viewModel.setShowScrollButtonsForScrollPosition(showScrollButtons = false, willScrollToBottomOnNewMessage = shouldScrollToBottom())
      }

      presentComposeDivider()

      val message = adapter.getConversationMessage(layoutManager.findLastVisibleItemPosition())
      scrollDateHeaderHelper.bind(message)

      val timestamp = MarkReadHelper.getLatestTimestamp(adapter, layoutManager)
      timestamp.ifPresent(markReadHelper::onViewsRevealed)
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
      if (newState != RecyclerView.SCROLL_STATE_IDLE) {
        scrollDateHeaderHelper.show()
      } else {
        scrollDateHeaderHelper.hide()
      }
    }

    private fun presentComposeDivider() {
      val isAtBottom = isScrolledToBottom()
      val suppress = viewModel.recipientSnapshot?.isReleaseNotes == true
      if ((isAtBottom && !wasAtBottom) || suppress) {
        ViewUtil.fadeOut(binding.composeDivider, 50, View.INVISIBLE)
      } else if (wasAtBottom && !isAtBottom) {
        ViewUtil.fadeIn(binding.composeDivider, 500)
      }

      wasAtBottom = isAtBottom
    }
  }

  private inner class DataObserver : RecyclerView.AdapterDataObserver() {
    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
      if (positionStart == 0 && shouldScrollToBottom()) {
        scrollToBottom()
      }
    }

    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
      if (!isActionModeStarted()) {
        return
      }

      val expired: Set<MultiselectPart> = adapter
        .selectedItems
        .filter { it.isExpired() }
        .toSet()

      adapter.removeFromSelection(expired)

      if (adapter.selectedItems.isEmpty()) {
        finishActionMode()
      } else {
        setActionModeTitle(calculateSelectedItemCount())
      }
    }

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
      scrollListener?.onScrolled(binding.conversationItemRecycler, 0, 0)
    }
  }

  fun handleMoveToQuotePosition(quoteId: Long, authorId: RecipientId) {
    disposables += viewModel.getQuotedMessagePosition(quoteId, authorId)
      .subscribeBy {
        if (it >= 0) {
          moveToPosition(it)
        } else {
          toast(R.string.ConversationFragment_quoted_message_no_longer_available)
        }
      }
  }

  //endregion Scroll Handling

  // region Conversation Callbacks

  private inner class ConversationItemClickListener : ConversationAdapter.ItemClickListener {
    override fun onQuoteClicked(messageRecord: MmsMessageRecord) {
      val quote: Quote? = messageRecord.quote
      if (quote == null) {
        Log.w(TAG, "onQuoteClicked: Received an event but there is no quote.")
        return
      }

      if (quote.isOriginalMissing) {
        Log.i(TAG, "onQuoteClicked: Original message is missing.")
        toast(R.string.ConversationFragment_quoted_message_not_found)
        return
      }

      val parentStoryId = messageRecord.parentStoryId
      if (parentStoryId != null) {
        startActivity(
          StoryViewerActivity.createIntent(
            requireContext(),
            StoryViewerArgs.Builder(quote.author, Recipient.resolved(quote.author).shouldHideStory)
              .withStoryId(parentStoryId.asMessageId().id)
              .isFromQuote(true)
              .build()
          )
        )

        return
      }

      quoteReturnMessageId = messageRecord.id
      handleMoveToQuotePosition(quote.id, quote.author)
    }

    override fun onLinkPreviewClicked(linkPreview: LinkPreview) {
      activity ?: return
      openLink(linkPreview.url)
    }

    override fun onQuotedIndicatorClicked(messageRecord: MessageRecord) {
      context ?: return
      activity ?: return
      val recipientId = viewModel.recipientSnapshot?.id ?: return

      container.runAfterAllHidden(composeText) {
        MessageQuotesBottomSheet.show(
          childFragmentManager,
          MessageId(messageRecord.id),
          recipientId
        )
      }
    }

    override fun onMoreTextClicked(conversationRecipientId: RecipientId, messageId: Long, isMms: Boolean) {
      context ?: return
      LongMessageFragment.create(messageId, isMms).show(childFragmentManager, null)
    }

    override fun onStickerClicked(stickerLocator: StickerLocator) {
      context ?: return
      startActivity(StickerPackPreviewActivity.getIntent(stickerLocator.packId, stickerLocator.packKey))
    }

    override fun onViewOnceMessageClicked(messageRecord: MmsMessageRecord) {
      if (!messageRecord.isViewOnce) {
        error("Non-revealable message clicked.")
      }

      if (!ViewOnceUtil.isViewable(messageRecord)) {
        val toastText = if (messageRecord.isOutgoing) {
          R.string.ConversationFragment_view_once_media_is_deleted_after_sending
        } else {
          R.string.ConversationFragment_you_already_viewed_this_message
        }

        toast(toastText)
        return
      }

      disposables += viewModel.getTemporaryViewOnceUri(messageRecord).subscribeBy(
        onSuccess = {
          container.hideAll(composeText)
          startActivity(ViewOnceMessageActivity.getIntent(requireContext(), messageRecord.id, it))
        },
        onComplete = {
          toast(R.string.ConversationFragment_failed_to_open_message)
        }
      )
    }

    override fun onSharedContactDetailsClicked(contact: Contact, avatarTransitionView: View) {
      val activity = activity ?: return
      ViewCompat.setTransitionName(avatarTransitionView, "avatar")
      val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, avatarTransitionView, "avatar").toBundle()
      activity.startActivity(SharedContactDetailsActivityV2.getIntent(activity, contact), bundle)
    }

    override fun onAddToContactsClicked(contact: Contact) {
      disposables += AddToContactsContract.createIntentAndLaunch(
        this@ConversationFragment,
        addToContactsLauncher,
        contact
      )
    }

    override fun onMessageSharedContactClicked(contact: Contact, choices: MutableList<Recipient>) {
      val context = context ?: return

      if (choices.isNotEmpty()) {
        ContactUtil.selectRecipientThroughDialog(context, choices, Locale.getDefault()) { recipient: Recipient ->
          CommunicationActions.startConversation(context, recipient, null)
        }
        return
      }

      // A card that carries only an ACI has nobody to pick between and no row yet, so tapping it is
      // what seeds one.
      viewLifecycleOwner.lifecycleScope.launch {
        val recipientId = withContext(SignalDispatchers.IO) { contact.resolveOrCreateSignalRecipient() } ?: return@launch
        CommunicationActions.startConversation(context, Recipient.resolved(recipientId), null)
      }
    }

    /** A number gets an SMS, an email only card gets an email, since those are the only two ways to reach them. */
    override fun onInviteSharedContactClicked(contact: Contact) {
      val context = context ?: return
      val inviteText = getString(R.string.InviteActivity_lets_switch_to_signal, getString(R.string.install_url))
      val numbers = contact.phoneNumbers.map { it.number }
      val email = contact.emails.firstOrNull()?.email

      if (numbers.isNotEmpty()) {
        ContactUtil.selectNumberThroughDialog(context, numbers, Locale.getDefault()) { number ->
          val intent = Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri()).putExtra("sms_body", inviteText)

          try {
            context.startActivity(intent)
          } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No messaging app to send an invite with.", e)
          }
        }
      } else if (email != null) {
        CommunicationActions.openEmail(context, email, getString(R.string.SharedContactDetailsScreen__join_me_on_signal), inviteText)
      }
    }

    override fun onReactionClicked(multiselectPart: MultiselectPart, messageId: Long, isMms: Boolean) {
      context ?: return
      val reactionsTag = "REACTIONS"
      if (parentFragmentManager.findFragmentByTag(reactionsTag) == null) {
        ReactionsBottomSheetDialogFragment.create(messageId, isMms, conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true).show(childFragmentManager, reactionsTag)
      }
    }

    override fun onGroupMemberClicked(recipientId: RecipientId, groupId: GroupId) {
      context ?: return
      RecipientBottomSheetDialogFragment.show(childFragmentManager, recipientId, groupId)
    }

    override fun onItemDoubleClick(item: MultiselectPart) {
      Log.d(TAG, "onItemDoubleClick")
      onDoubleTapToEdit(item.conversationMessage)
    }

    private fun onDoubleTapToEdit(conversationMessage: ConversationMessage) {
      if (!isValidEditMessageSend(conversationMessage.getMessageRecord(), System.currentTimeMillis())) {
        return
      }

      if (SignalStore.uiHints.hasSeenDoubleTapEditEducationSheet) {
        onDoubleTapEditEducationSheetNext(conversationMessage)
        return
      }

      DoubleTapEditEducationSheet(conversationMessage).show(childFragmentManager, DoubleTapEditEducationSheet.KEY)
    }

    override fun onPaymentTombstoneClicked() {
      this@ConversationFragment.showPaymentTombstoneLearnMoreDialog()
    }

    override fun onDisplayMediaNoLongerAvailableSheet() {
      viewLifecycleOwner.lifecycleScope.launch {
        val isUpgradeAvailable = BackupUpgradeAvailabilityChecker.isUpgradeAvailable(requireContext())

        if (SignalStore.backup.areBackupsEnabled && isUpgradeAvailable) {
          UpgradeToStartMediaBackupSheet().show(parentFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
        } else {
          MediaNoLongerAvailableBottomSheet().show(parentFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
        }
      }
    }

    override fun onMessageWithErrorClicked(messageRecord: MessageRecord) {
      val recipientId = viewModel.recipientSnapshot?.id ?: return
      if (conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true) {
        ConversationDialogs.displayTerminatedGroupSendFailedDialog(requireContext(), messageRecord)
      } else if (messageRecord.isFailedAdminDelete) {
        val canRetry = MessageConstraintsUtil.isValidAdminDeleteSend(message = messageRecord, currentTime = System.currentTimeMillis(), isAdmin = conversationGroupViewModel.isAdmin(), isResend = true)
        if (messageRecord.isIdentityMismatchFailure && canRetry) {
          SafetyNumberBottomSheet
            .forIncomingMessageRecord(messageRecord, viewModel.recipientSnapshot!!)
            .show(childFragmentManager)
        } else {
          ConversationDialogs.displayDeletionFailedDialog(requireContext(), messageRecord, canRetry)
        }
      } else if (messageRecord.isIdentityMismatchFailure) {
        SafetyNumberBottomSheet
          .forOutgoingMessageRecord(requireContext(), messageRecord)
          .show(childFragmentManager)
      } else if (messageRecord.hasFailedWithNetworkFailures()) {
        ConversationDialogs.displayMessageCouldNotBeSentDialog(requireContext(), messageRecord)
      } else {
        mainNavigationEventSink.onEvent(MainNavigationEvents.GoToDetail(MainDetailRoute.Chats.MessageDetails(recipientId, MessageId(messageRecord.id))))
      }
    }

    override fun onMessageWithRecaptchaNeededClicked(messageRecord: MessageRecord) {
      RecaptchaProofBottomSheetFragment.show(childFragmentManager)
    }

    override fun onIncomingIdentityMismatchClicked(recipientId: RecipientId) {
      SafetyNumberBottomSheet.forRecipientId(recipientId).show(parentFragmentManager)
    }

    override fun onRegisterVoiceNoteCallbacks(onPlaybackStartObserver: Observer<VoiceNotePlaybackState>) {
      getVoiceNoteMediaController()
        .voiceNotePlaybackState
        .observe(viewLifecycleOwner, onPlaybackStartObserver)
    }

    override fun onUnregisterVoiceNoteCallbacks(onPlaybackStartObserver: Observer<VoiceNotePlaybackState>) {
      getVoiceNoteMediaController()
        .voiceNotePlaybackState
        .removeObserver(onPlaybackStartObserver)
    }

    override fun onVoiceNotePause(uri: Uri) {
      getVoiceNoteMediaController().pausePlayback(uri)
    }

    override fun onVoiceNotePlay(uri: Uri, messageId: Long, position: Double) {
      getVoiceNoteMediaController().startConsecutivePlayback(uri, messageId, position)
    }

    override fun onSingleVoiceNotePlay(uri: Uri, messageId: Long, position: Double) {
      getVoiceNoteMediaController().startSinglePlayback(uri, messageId, position)
    }

    override fun onVoiceNoteSeekTo(uri: Uri, position: Double) {
      getVoiceNoteMediaController().seekToPosition(uri, position)
    }

    override fun onVoiceNotePlaybackSpeedChanged(uri: Uri, speed: Float) {
      getVoiceNoteMediaController().setPlaybackSpeed(uri, speed)
    }

    override fun onGroupMigrationLearnMoreClicked(membershipChange: GroupMigrationMembershipChange) {
      GroupsV1MigrationInfoBottomSheetDialogFragment.show(parentFragmentManager, membershipChange)
    }

    override fun onChatSessionRefreshLearnMoreClicked() {
      ConversationDialogs.displayChatSessionRefreshLearnMoreDialog(requireContext())
    }

    override fun onBadDecryptLearnMoreClicked(author: RecipientId) {
      val isGroup = viewModel.recipientSnapshot?.isGroup ?: return
      val recipientName = Recipient.resolved(author).getDisplayName(requireContext())
      BadDecryptLearnMoreDialog.show(parentFragmentManager, recipientName, isGroup)
    }

    override fun onSafetyNumberLearnMoreClicked(recipient: Recipient) {
      ConversationDialogs.displaySafetyNumberLearnMoreDialog(this@ConversationFragment, recipient)
    }

    override fun onShowUnverifiedProfileSheet(forGroup: Boolean) {
      UnverifiedProfileNameBottomSheet.show(parentFragmentManager, forGroup)
    }

    override fun onUpdateSignalClicked() {
      PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext())
    }

    override fun onViewResultsClicked(pollId: Long) {
      if (parentFragmentManager.findFragmentByTag(PollVotesFragment.POLL_VOTES_FRAGMENT_TAG) == null) {
        PollVotesFragment.create(pollId, parentFragmentManager)

        parentFragmentManager.setFragmentResultListener(PollVotesFragment.RESULT_KEY, requireActivity()) { _, bundle ->
          val shouldEndPoll = bundle.getBoolean(PollVotesFragment.RESULT_KEY, false)
          if (shouldEndPoll) {
            handleEndPoll(pollId)
          }
        }
      }
    }

    override fun onViewPollClicked(messageId: Long) {
      disposables += viewModel
        .moveToMessage(messageId)
        .subscribeBy(
          onSuccess = { moveToPosition(it) },
          onError = { Toast.makeText(requireContext(), R.string.Poll__unable_poll, Toast.LENGTH_LONG).show() }
        )
    }

    override fun onToggleVote(poll: PollRecord, pollOption: PollOption, isChecked: Boolean) {
      if (conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true) {
        MaterialAlertDialogBuilder(requireContext())
          .setMessage(R.string.conversation_activity__group_action_not_allowed_group_ended)
          .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
          .show()
        return
      }
      viewModel.toggleVote(poll, pollOption, isChecked)
    }

    override fun onViewPinnedMessage(messageId: Long) {
      viewPinnedMessage(messageId)
    }

    override fun onJoinGroupCallClicked() {
      val activity = activity ?: return
      val recipient = viewModel.recipientSnapshot ?: return
      CommunicationActions.startVideoCall(activity, recipient) {
        YouAreAlreadyInACallSnackbar.show(requireView())
      }
    }

    override fun onInviteFriendsToGroupClicked(groupId: GroupId.V2) {
      if (conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true) {
        MaterialAlertDialogBuilder(requireContext())
          .setMessage(R.string.conversation_activity__group_action_not_allowed_group_ended)
          .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
          .show()
      } else {
        GroupLinkInviteFriendsBottomSheetDialogFragment.show(requireActivity().supportFragmentManager, groupId)
      }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onEnableCallNotificationsClicked() {
      EnableCallNotificationSettingsDialog.fixAutomatically(requireContext())
      if (EnableCallNotificationSettingsDialog.shouldShow(requireContext())) {
        EnableCallNotificationSettingsDialog.show(childFragmentManager)
      } else {
        adapter.notifyDataSetChanged()
      }
    }

    override fun onPlayInlineContent(conversationMessage: ConversationMessage?) {
      adapter.playInlineContent(conversationMessage)
    }

    override fun onInMemoryMessageClicked(messageRecord: InMemoryMessageRecord) {
      ConversationDialogs.displayInMemoryMessageDialog(requireContext(), messageRecord)
    }

    override fun onViewGroupDescriptionChange(groupId: GroupId?, description: String, isMessageRequestAccepted: Boolean) {
      if (groupId != null) {
        GroupDescriptionDialog.show(childFragmentManager, groupId, description, isMessageRequestAccepted)
      }
    }

    override fun onChangeNumberUpdateContact(recipient: Recipient) {
      startActivity(RecipientExporter.export(recipient).asAddContactIntent())
    }

    override fun onChangeProfileNameUpdateContact(recipient: Recipient) {
      if (recipient.isSystemContact) {
        startActivity(
          Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(recipient.contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
          }
        )
      } else {
        nicknameEditActivityLauncher.launch(NicknameActivity.Args(recipientId = recipient.id, focusNoteFirst = false))
      }
    }

    override fun onCallToAction(action: String) {
      when (action) {
        "gift_badge" -> checkoutLauncher.launch(InAppPaymentType.ONE_TIME_GIFT)
        "username_edit" -> startActivity(EditProfileActivity.getIntentForUsernameEdit(requireContext()))
        "calls_tab" -> startActivity(MainActivity.clearTopAndOpenTab(requireContext(), MainListRoute.Calls))
        "chat_folder" -> startActivity(AppSettingsActivity.chatFolders(requireContext()))
        "remote_backups" -> {
          if (SignalStore.backup.areBackupsEnabled) {
            startActivity(AppSettingsActivity.remoteBackups(requireContext()))
          } else {
            startActivity(AppSettingsActivity.backupsSettings(requireContext(), launchCheckoutFlow = true))
          }
        }
      }
    }

    override fun onDonateClicked() {
      requireActivity()
        .supportFragmentManager
        .beginTransaction()
        .add(DonateToSignalFragment.Dialog.create(InAppPaymentType.ONE_TIME_DONATION), "one_time_nav")
        .commitNow()
    }

    override fun onBlockJoinRequest(recipient: Recipient) {
      if (conversationGroupViewModel.groupRecordSnapshot?.isTerminated == true) {
        MaterialAlertDialogBuilder(requireContext())
          .setMessage(R.string.conversation_activity__group_action_not_allowed_group_ended)
          .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
          .show()
      } else {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ConversationFragment__block_request)
          .setMessage(getString(R.string.ConversationFragment__s_will_not_be_able_to_join_or_request_to_join_this_group_via_the_group_link, recipient.getDisplayName(requireContext())))
          .setNegativeButton(R.string.ConversationFragment__cancel, null)
          .setPositiveButton(R.string.ConversationFragment__block_request_button) { _, _ -> handleBlockJoinRequest(recipient) }
          .show()
      }
    }

    override fun onRecipientNameClicked(target: RecipientId) {
      context ?: return
      disposables += viewModel.recipient.firstOrError().observeOn(AndroidSchedulers.mainThread()).subscribeBy {
        RecipientBottomSheetDialogFragment.show(
          parentFragmentManager,
          target,
          it.groupId.orElse(null)
        )
      }
    }

    override fun onInviteToSignalClicked() {
      InviteActions.inviteUserToSignal(
        requireContext(),
        this@ConversationFragment::startActivity
      )
    }

    override fun onActivatePaymentsClicked() {
      startActivity(Intent(requireContext(), PaymentsActivity::class.java))
    }

    override fun onSendPaymentClicked(recipientId: RecipientId) {
      val recipient = viewModel.recipientSnapshot ?: return
      AttachmentManager.selectPayment(this@ConversationFragment, recipient)
    }

    override fun onScheduledIndicatorClicked(view: View, conversationMessage: ConversationMessage) = Unit

    override fun onUrlClicked(url: String): Boolean {
      if (CommunicationActions.handlePotentialGroupLinkUrl(requireActivity(), url) ||
        CommunicationActions.handlePotentialProxyLinkUrl(requireActivity(), url)
      ) {
        return true
      }

      openLink(url)
      return true
    }

    override fun onViewGiftBadgeClicked(messageRecord: MessageRecord) {
      if (!messageRecord.hasGiftBadge()) {
        return
      }

      if (messageRecord.isOutgoing) {
        ViewSentGiftBottomSheet.show(childFragmentManager, (messageRecord as MmsMessageRecord))
      } else {
        ViewReceivedGiftBottomSheet.show(childFragmentManager, (messageRecord as MmsMessageRecord))
      }
    }

    override fun onGiftBadgeRevealed(messageRecord: MessageRecord) {
      viewModel.markGiftBadgeRevealed(messageRecord)
    }

    override fun goToMediaPreview(parent: ConversationItem, sharedElement: View, args: MediaIntentFactory.MediaPreviewArgs) {
      if (this@ConversationFragment.args.conversationScreenType.isInBubble) {
        val recipient = viewModel.recipientSnapshot ?: return
        val intent = ConversationIntents.createBuilderSync(requireActivity(), recipient.id, viewModel.threadId)
          .withStartingPosition(binding.conversationItemRecycler.getChildAdapterPosition(parent))
          .build()

        requireActivity().startActivity(intent)
        requireActivity().startActivity(MediaIntentFactory.create(requireActivity(), args.skipSharedElementTransition(true)))
        return
      }

      if (args.isVideoGif) {
        val adapterPosition: Int = binding.conversationItemRecycler.getChildAdapterPosition(parent)
        val holder: GiphyMp4ProjectionPlayerHolder? = giphyMp4ProjectionRecycler.getCurrentHolder(adapterPosition)
        if (holder != null) {
          parent.showProjectionArea()
          holder.hide()
        }
      }

      container.hideAll(composeText)

      sharedElement.transitionName = MediaPreviewActivity.SHARED_ELEMENT_TRANSITION_NAME
      MediaPreviewCache.replyTargetThreadId = args.threadId
      // Tellomi (interaction audit A-20): if the viewer is closed on another page, it comes back to that page's thumbnail.
      TellomiMediaReturnTarget.register(mediaReturnFinder)
      requireActivity().setExitSharedElementCallback(TellomiMediaReturnTarget.ExitCallback(sharedElement))
      val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), sharedElement, MediaPreviewActivity.SHARED_ELEMENT_TRANSITION_NAME)
      requireActivity().startActivity(MediaIntentFactory.create(requireActivity(), args), options.toBundle())
    }

    override fun onEditedIndicatorClicked(conversationMessage: ConversationMessage) {
      val messageRecord = conversationMessage.messageRecord
      if (conversationMessage.messageRecord.isOutgoing) {
        if (!doubleTapToEditDebouncer.onClick { EditMessageHistoryDialog.show(childFragmentManager, messageRecord.toRecipient.id, messageRecord) }) {
          onDoubleTapToEdit(conversationMessage)
        }
      } else {
        EditMessageHistoryDialog.show(childFragmentManager, messageRecord.fromRecipient.id, messageRecord)
      }
    }

    override fun onExpandEvents(messageId: Long, itemView: View, collapsedSize: Int) {
      val position = binding.conversationItemRecycler.getChildAdapterPosition(itemView)
      if (position != RecyclerView.NO_POSITION && position != 0) {
        collapsibleEventScrollPosition = CollapsibleEventScrollPosition(position = position + (collapsedSize - 1), top = itemView.top, height = itemView.height)
      }
      viewModel.onExpandEvents(messageId)
    }

    override fun onCollapseEvents(messageId: Long, itemView: View, collapsedSize: Int) {
      val position = binding.conversationItemRecycler.getChildAdapterPosition(itemView)
      if (position != RecyclerView.NO_POSITION) {
        collapsibleEventScrollPosition = CollapsibleEventScrollPosition(position = position - (collapsedSize - 1), top = itemView.top, height = itemView.height)
      }
      viewModel.onCollapseEvents(messageId)
    }

    override fun onItemClick(item: MultiselectPart) {
      if (isActionModeStarted()) {
        if (item.conversationMessage.isActiveCollapsedHead) {
          viewModel.onExpandEvents(item.conversationMessage.messageRecord.id)
        } else {
          adapter.toggleSelection(item)
        }
        binding.conversationItemRecycler.invalidateItemDecorations()

        if (adapter.selectedItems.isEmpty()) {
          finishActionMode()
        } else {
          setCorrectActionModeMenuVisibility()
          setActionModeTitle(calculateSelectedItemCount())
        }
      }
    }

    override fun onItemLongClick(itemView: View, item: MultiselectPart) {
      Log.d(TAG, "onItemLongClick")
      if (isActionModeStarted()) {
        // Tellomi（交互审计 A-15）：多选模式里长按 = 切换这一条并拿起拖选。
        dragSelect?.start(itemView)
        return
      }

      if (item.getMessageRecord().isInMemoryMessageRecord) {
        return
      }

      val messageRecord = item.getMessageRecord()
      val recipient = viewModel.recipientSnapshot ?: return

      if (isUnopenedGift(itemView, messageRecord)) {
        return
      }

      if (messageRecord.isValidReactionTarget() &&
        !recipient.isBlocked &&
        !viewModel.hasMessageRequestState &&
        (!recipient.isGroup || recipient.isActiveGroup) &&
        adapter.selectedItems.isEmpty()
      ) {
        val target: InteractiveConversationElement? = if (itemView is InteractiveConversationElement) {
          itemView
        } else {
          val viewHolder = binding.conversationItemRecycler.getChildViewHolder(itemView)
          if (viewHolder is InteractiveConversationElement) {
            viewHolder
          } else {
            null
          }
        }

        if (target != null) {
          // Tellomi (interaction audit A-06, owner Q-05 = A): the system keyboard stays where it is and the overlay is placed
          // above it. Upstream put it away here and brought it back on dismiss, so the bottom of the chat jumped twice.
          // The in-app keyboards (emoji, attachments) still close, as do floating keyboards that claim no space.
          keepsKeyboardForReaction = container.isKeyboardShowing && !container.isInputShowing
          if (!keepsKeyboardForReaction) {
            container.hideAll(composeText)
          }
          showReactionOverlay(item, target, focusedView = null)
        }
      } else if (item.conversationMessage.isActiveCollapsedHead) {
        viewModel.onExpandEvents(item.conversationMessage.messageRecord.id)
      } else {
        clearFocusedItem()
        adapter.toggleSelection(item)
        startActionMode()
      }
    }

    /** Snapshots [target] and hands it to the reaction overlay. */
    private fun showReactionOverlay(
      item: MultiselectPart,
      target: InteractiveConversationElement,
      focusedView: View?
    ) {
      if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity == null || activity?.isFinishing == true) {
        return
      }

      if (reactionOverlay.isShowing) {
        // Nothing below would be undone by a hide that never comes.
        Log.w(TAG, "Long press while the reaction overlay is still showing. Ignoring.")
        return
      }

      val messageRecord = item.getMessageRecord()

      // Held, not re-read: teardown has to work from a screen that is already going.
      val recycler = binding.conversationItemRecycler

      multiselectItemDecoration.setFocusedItem(MultiselectPart.Message(item.conversationMessage))
      recycler.invalidateItemDecorations()
      recycler.suppressLayout(true)

      val audioUri = messageRecord.getAudioUriForLongClick()
      if (audioUri != null) {
        getVoiceNoteMediaController().pausePlayback(audioUri)
      }

      val childAdapterPosition = target.getAdapterPosition(recycler)
      var mp4Holder: GiphyMp4ProjectionPlayerHolder? = null
      var videoBitmap: Bitmap? = null
      if (childAdapterPosition != RecyclerView.NO_POSITION) {
        mp4Holder = giphyMp4ProjectionRecycler.getCurrentHolder(childAdapterPosition)
        if (mp4Holder?.isVisible == true) {
          mp4Holder.pause()
          videoBitmap = mp4Holder.bitmap
          mp4Holder.hide()
        }
      }

      val snapshot = ConversationItemSelection.snapshotView(target, recycler, messageRecord, videoBitmap)

      val bodyBubble = target.bubbleView
      val snapshotMetrics = target.getSnapshotStrategy()?.snapshotMetrics ?: InteractiveConversationElement.SnapshotMetrics(
        snapshotOffset = bodyBubble.x,
        contextMenuPadding = bodyBubble.x
      )

      val origin = overlayOriginOf(target, recycler)
      val overlaySnapshot = ReactionOverlaySnapshot(
        bitmap = snapshot.asImageBitmap(),
        bubbleX = origin.x + snapshotMetrics.snapshotOffset,
        bubbleY = origin.y + bodyBubble.y,
        bubbleWidth = bodyBubble.width,
        contextMenuX = origin.x + snapshotMetrics.contextMenuPadding,
        isMessageOnLeft = messageRecord.isOutgoing xor ViewUtil.isLtr(recycler),
        returnPosition = {
          if (view == null || target.root.parent == null || target.conversationMessage.messageRecord.id != messageRecord.id) {
            null
          } else {
            val current = overlayOriginOf(target, recycler)
            Offset(current.x + snapshotMetrics.snapshotOffset, current.y + bodyBubble.y)
          }
        }
      )

      bodyBubble.visibility = View.INVISIBLE
      target.reactionsView.visibility = View.INVISIBLE

      val quotedIndicatorVisible = target.quotedIndicatorView?.visibility == View.VISIBLE
      if (quotedIndicatorVisible) {
        ViewUtil.fadeOut(target.quotedIndicatorView!!, 150, View.INVISIBLE)
      }

      viewModel.setHideScrollButtonsForReactionOverlay(true)

      reactionActionListener = ReactionsToolbarListener(item.conversationMessage)
      reactionHideListener = object : ReactionOverlayHideListener {
        override fun startHide(focusedView: View?) {
          // Ahead of the started check: a dismiss while stopped would leave the chat dimmed.
          multiselectItemDecoration.hideShade(recycler)

          if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity == null || activity?.isFinishing == true) {
            return
          }

          val searchField = expandedSearchField()
          if (searchField != null && focusedView == searchField) {
            // The input panel is gone while search is open, so composeText cannot take the keyboard back.
            container.showSoftkey(searchField)
          } else if (focusedView == composeText) {
            container.showSoftkey(composeText)
          }
        }

        override fun onHide() {
          viewModel.setIsReactionDelegateShowing(false)

          // Likewise: otherwise the list stays frozen and the message invisible.
          recycler.suppressLayout(false)
          multiselectItemDecoration.setFocusedItem(null)
          recycler.invalidateItemDecorations()
          bodyBubble.visibility = View.VISIBLE
          target.reactionsView.visibility = View.VISIBLE
          viewModel.setHideScrollButtonsForReactionOverlay(false)

          if (quotedIndicatorVisible && target.quotedIndicatorView != null) {
            ViewUtil.fadeIn(target.quotedIndicatorView!!, 150)
          }

          if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity == null || activity?.isFinishing == true) {
            return
          }

          if (audioUri != null) {
            getVoiceNoteMediaController().resumePlayback(audioUri, messageRecord.id)
          }

          if (mp4Holder != null) {
            mp4Holder.show()
            mp4Holder.resume()
          }
        }
      }

      handleReaction(item.conversationMessage, overlaySnapshot, focusedView)
    }

    override fun onShowGroupDescriptionClicked(groupName: String, description: String, shouldLinkifyWebLinks: Boolean) {
      GroupDescriptionDialog.show(childFragmentManager, groupName, description, shouldLinkifyWebLinks)
    }

    override fun onJoinCallLink(callLinkRootKey: CallLinkRootKey) {
      CommunicationActions.startVideoCall(this@ConversationFragment, callLinkRootKey) {
        YouAreAlreadyInACallSnackbar.show(requireView())
      }
    }

    override fun onShowSafetyTips(forGroup: Boolean) {
      SafetyTipsBottomSheetDialog.show(childFragmentManager, forGroup)
    }

    override fun onReportSpamLearnMoreClicked() {
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.ConversationFragment_reported_spam)
        .setMessage(R.string.ConversationFragment_reported_spam_message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    }

    override fun onMessageRequestAcceptOptionsClicked() {
      val recipient: Recipient? = viewModel.recipientSnapshot

      if (recipient != null) {
        MaterialAlertDialogBuilder(requireContext())
          .setMessage(getString(R.string.ConversationFragment_you_accepted_a_message_request_from_s, recipient.getDisplayName(requireContext())))
          .setPositiveButton(R.string.ConversationFragment_block) { _, _ -> onBlock() }
          .setNegativeButton(R.string.ConversationFragment_report_spam) { _, _ -> onReportSpam() }
          .setNeutralButton(R.string.ConversationFragment__cancel, null)
          .show()
      }
    }

    private fun MessageRecord.getAudioUriForLongClick(): Uri? {
      if (!hasAudio()) {
        return null
      }

      val playbackState = getVoiceNoteMediaController().voiceNotePlaybackState.value
      if (playbackState == null || !playbackState.isPlaying) {
        return null
      }

      val uri = (this as MmsMessageRecord).slideDeck.audioSlide?.uri
      return uri.takeIf { it == playbackState.uri }
    }
  }

  private inner class ConversationOptionsMenuCallback : ConversationOptionsMenu.Callback {

    override fun getSnapshot(): ConversationOptionsMenu.Snapshot {
      val recipient: Recipient? = viewModel.recipientSnapshot
      return ConversationOptionsMenu.Snapshot(
        recipient = recipient,
        isPushAvailable = viewModel.isPushAvailable,
        canShowAsBubble = viewModel.canShowAsBubble(requireContext()),
        isActiveGroup = recipient?.isActiveGroup == true,
        isActiveV2Group = recipient?.let { it.isActiveGroup && it.isPushV2Group } == true,
        isInActiveGroup = recipient?.isActiveGroup == false,
        hasActiveGroupCall = groupCallViewModel.hasOngoingGroupCallSnapshot,
        distributionType = args.distributionType,
        threadId = args.threadId,
        messageRequestState = viewModel.messageRequestState,
        isInBubble = args.conversationScreenType.isInBubble
      )
    }

    override fun isTextHighlighted(): Boolean {
      return composeText.isTextHighlighted
    }

    override fun onOptionsMenuCreated(menu: Menu) {
      searchMenuItem = menu.findItem(R.id.menu_search)

      val searchView: SearchView = searchMenuItem!!.actionView as SearchView
      val queryListener: SearchView.OnQueryTextListener = object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String): Boolean {
          searchViewModel.onQueryUpdated(query, args.threadId, true)
          searchNav.showLoading()
          viewModel.setSearchQuery(query)
          return true
        }

        override fun onQueryTextChange(newText: String): Boolean {
          searchViewModel.onQueryUpdated(newText, args.threadId, false)
          searchNav.showLoading()
          viewModel.setSearchQuery(newText)
          return true
        }
      }

      searchMenuItem!!.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
        override fun onMenuItemActionExpand(item: MenuItem): Boolean {
          searchView.setIncognitoKeyboardEnabled(TextSecurePreferences.isIncognitoKeyboardEnabled(requireContext()))
          searchView.setOnQueryTextListener(queryListener)
          isSearchRequested = true
          searchViewModel.onSearchOpened()
          searchNav.visible = true
          searchNav.setData(0, 0)
          inputPanel.setHideForSearch(true)
          viewModel.onChatSearchOpened()
          binding.conversationDisabledInput.visible = false

          (0 until menu.size()).forEach {
            if (menu.getItem(it) != searchMenuItem) {
              menu.getItem(it).isVisible = false
            }
          }

          return true
        }

        override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
          searchView.setOnQueryTextListener(null)
          closeChatSearch()
          return true
        }
      })

      searchView.maxWidth = Integer.MAX_VALUE

      if (isSearchRequested) {
        if (searchMenuItem!!.expandActionView()) {
          searchViewModel.onSearchOpened()
        }
      }

      val toolbarTextAndIconColor = ContextCompat.getColor(
        requireContext(),
        if (viewModel.wallpaperSnapshot != null) {
          CoreUiR.color.signal_colorNeutralInverse
        } else {
          CoreUiR.color.signal_colorOnSurface
        }
      )

      binding.toolbar.setActionItemTint(toolbarTextAndIconColor)
    }

    override fun handleVideo() {
      this@ConversationFragment.handleVideoCall()
    }

    override fun handleDial() {
      val recipient: Recipient = viewModel.recipientSnapshot ?: return
      CommunicationActions.startVoiceCall(this@ConversationFragment, recipient) {
        YouAreAlreadyInACallSnackbar.show(requireView())
      }
    }

    override fun handleViewMedia() {
      startActivity(MediaOverviewActivity.forThread(requireContext(), args.threadId))
    }

    override fun handleAddShortcut() {
      val recipient: Recipient = viewModel.recipientSnapshot ?: return
      Log.i(TAG, "Creating home screen shortcut for recipient ${recipient.id}")

      if (pinnedShortcutReceiver == null) {
        pinnedShortcutReceiver = object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent?) {
            toast(
              toastTextId = R.string.ConversationActivity_added_to_home_screen,
              toastDuration = Toast.LENGTH_LONG
            )
          }
        }

        ContextCompat.registerReceiver(requireActivity(), pinnedShortcutReceiver, IntentFilter(ACTION_PINNED_SHORTCUT), ContextCompat.RECEIVER_EXPORTED)
      }

      viewModel.getContactPhotoIcon(requireContext(), Glide.with(this@ConversationFragment))
        .subscribe { infoCompat ->
          val intent = Intent(ACTION_PINNED_SHORTCUT).apply { `package` = requireContext().packageName }
          val callback = PendingIntent.getBroadcast(requireContext(), 902, intent, PendingIntentFlags.mutable())
          ShortcutManagerCompat.requestPinShortcut(requireContext(), infoCompat, callback.intentSender)
        }
        .addTo(disposables)
    }

    override fun handleSearch() {
      searchViewModel.onSearchOpened()
    }

    override fun handleAddToContacts() {
      val recipient = viewModel.recipientSnapshot?.takeIf { it.isIndividual } ?: return

      AddToContactsContract.createIntentAndLaunch(
        fragment = this@ConversationFragment,
        launcher = addToContactsLauncher,
        recipient = recipient
      )
    }

    override fun handleManageGroup() {
      viewModel.recipientSnapshot?.let { recipient ->
        container.hideKeyboard(composeText)
        mainNavigationEventSink.onEvent(MainNavigationEvents.GoToDetail(MainDetailRoute.Chats.ConversationSettings(recipient.id)))
      }
    }

    override fun handleLeavePushGroup() {
      val recipient = viewModel.recipientSnapshot
      if (recipient == null) {
        toast(R.string.ConversationActivity_invalid_recipient, toastDuration = Toast.LENGTH_LONG)
        return
      }

      LeaveGroupDialog.handleLeavePushGroup(
        requireActivity(),
        recipient.requireGroupId().requirePush()
      ) { requireActivity().finish() }
    }

    override fun handleInviteLink() {
      val recipient = viewModel.recipientSnapshot ?: return

      InviteActions.inviteUserToSignal(
        context = requireContext(),
        launchIntent = this@ConversationFragment::startActivity
      )
    }

    override fun handleMuteNotifications() {
      // Tellomi（交互审计 A-39）：时长菜单锚在右上角「···」上，在刚收起的那个菜单原处（按钮下方、离右边 16 dp）展开。等它收完再弹。
      val toolbar = binding.toolbar
      toolbar.post {
        if (view == null) {
          return@post
        }

        val overflow = toolbar.children.filterIsInstance<ActionMenuView>().firstOrNull()?.takeIf { it.childCount > 0 }?.let { it.getChildAt(it.childCount - 1) }
        val anchor = overflow ?: toolbar
        val menu = SignalContextMenu.Builder(anchor, binding.root)
          .preferredHorizontalPosition(SignalContextMenu.HorizontalPosition.END)
          .offsetX(ViewUtil.dpToPx(16))

        MuteDialog.showAnchored(menu, childFragmentManager, viewLifecycleOwner, viewModel::muteConversation)
      }
    }

    override fun handleUnmuteNotifications() {
      viewModel.muteConversation(0L)
    }

    override fun handleConversationSettings() {
      viewModel.recipientSnapshot?.let { recipient ->
        if (!viewModel.hasMessageRequestState || recipient.isBlocked) {
          container.hideKeyboard(composeText)
          // Tellomi（交互审计 A-19）：顶栏的小头像飞到设置页的大头像那里
          binding.conversationTitleView.root.findViewById<View>(R.id.contact_photo_image)?.let { TellomiAvatarHero.lift(it) }
          mainNavigationEventSink.onEvent(MainNavigationEvents.GoToDetail(MainDetailRoute.Chats.ConversationSettings(recipient.id)))
        }
      }
    }

    override fun handleSelectMessageExpiration() {
      val recipient = viewModel.recipientSnapshot ?: return
      if (recipient.isPushGroup && !recipient.isActiveGroup) {
        return
      }

      startActivity(RecipientDisappearingMessagesActivity.forRecipient(requireContext(), recipient.id))
    }

    override fun handleCreateBubble() {
      val recipientId = viewModel.recipientSnapshot?.id ?: return

      BubbleUtil.displayAsBubble(requireContext(), recipientId, args.threadId)
      requireActivity().finish()
    }

    override fun handleGoHome() {
      requireActivity().finish()
    }

    override fun showExpiring(recipient: Recipient) = Unit
    override fun clearExpiring() = Unit

    override fun handleFormatText(id: Int) {
      composeText.handleFormatText(id)
    }

    override fun handleBlock() {
      onBlock()
    }

    override fun handleUnblock() {
      onUnblock()
    }

    override fun handleReportSpam() {
      onReportSpam()
    }

    override fun handleMessageRequestAccept() {
      onMessageRequestAccept()
    }

    override fun handleDeleteConversation() {
      onDeleteConversation()
    }

    override fun handleExportChat() {
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.ChatExportDialogs__export_chat_history_title)
        .setMessage(R.string.ChatExportDialogs__export_confirm_body)
        .setPositiveButton(R.string.ChatExportDialogs__export_with_media) { _, _ ->
          viewModel.startPlaintextExport(requireContext().applicationContext, includeMedia = true)
        }
        .setNeutralButton(R.string.ChatExportDialogs__export_without_media) { _, _ ->
          viewModel.startPlaintextExport(requireContext().applicationContext, includeMedia = false)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  /**
   * Tellomi (interaction audit A-07, Android half of Signal-iOS#79): the chosen emoji flies from the reaction strip onto
   * its pill under the message once the overlay has closed and the pill is on screen. See [TellomiReactionFlyIn].
   */
  private fun startReactionFlyIn(messageId: Long, emoji: String, source: RectF) {
    if (!TellomiMotion.allowsFlourish(TellomiMotion.tier(requireContext()))) {
      return
    }

    TellomiReactionFlyIn.start(
      host = requireActivity().window.decorView,
      messageId = messageId,
      baseEmoji = EmojiUtil.getCanonicalRepresentation(emoji),
      drawable = EmojiProvider.getEmojiDrawable(requireContext(), emoji, true),
      sourceInWindow = source,
      isReadyToLand = { !reactionOverlay.isShowing },
      findTarget = { findReactionPillEmoji(messageId, emoji) }
    )
  }

  /**
   * Tellomi (interaction audit A-07, "读屏公告「已回应 ❤️」"): TalkBack hears what the reaction did — the pill changing says
   * nothing on its own. Taking back your own reaction with the same emoji removes it, as [ConversationViewModel.updateReaction] does.
   */
  private fun announceReaction(messageRecord: MessageRecord, emoji: String) {
    val removing = messageRecord.reactions.any { it.author == Recipient.self().id && it.emoji == emoji }
    val text = if (removing) {
      getString(R.string.ConversationFragment__tellomi_reaction_removed)
    } else {
      getString(R.string.ConversationFragment__tellomi_reacted_with, emoji)
    }

    @Suppress("DEPRECATION")
    binding.root.announceForAccessibility(text)
  }

  /** Tellomi (A-06): whether this long press blurs the conversation; decided when the shade starts, not every frame. */
  private var overlayBlurAllowed: Boolean? = null

  /**
   * Tellomi (interaction audit A-06, owner Q-03 = B "按档位模糊"): with the long-press overlay up, the conversation and its
   * wallpaper behind the shade blur in step with it — only where live blur is affordable ([TellomiMotion.allowsLiveBlur]:
   * the full motion tier on Android 12+). The overlay itself is drawn by the composition above, so it stays sharp.
   */
  private fun applyOverlayBlur(progress: Float) {
    if (Build.VERSION.SDK_INT < 31 || view == null) {
      return
    }

    if (progress <= 0f) {
      overlayBlurAllowed = null
      conversationContent.setRenderEffect(null)
      conversationBackground.setRenderEffect(null)
      return
    }

    val allowed = overlayBlurAllowed ?: TellomiMotion.allowsLiveBlur(TellomiMotion.tier(requireContext())).also { overlayBlurAllowed = it }
    if (!allowed) {
      return
    }

    val radius = progress * OVERLAY_BLUR_RADIUS_DP * resources.displayMetrics.density
    val effect = if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    conversationContent.setRenderEffect(effect)
    conversationBackground.setRenderEffect(effect)
  }

  /** Tellomi (A-20): held here because [TellomiMediaReturnTarget] only keeps a weak reference to it. */
  private val mediaReturnFinder: (Uri) -> ThumbnailView? = { uri -> findMediaThumbnail(uri) }

  /** Tellomi (A-20): the thumbnail showing [uri] in a row on screen, if there is one. */
  private fun findMediaThumbnail(uri: Uri): ThumbnailView? {
    if (view == null) {
      return null
    }

    val recycler = binding.conversationItemRecycler
    for (index in 0 until recycler.childCount) {
      recycler.getChildAt(index).findThumbnail(uri)?.let { return it }
    }

    return null
  }

  private fun View.findThumbnail(uri: Uri): ThumbnailView? {
    if (this is ThumbnailView) {
      return takeIf { slide?.displayUri == uri }
    }

    if (this is ViewGroup) {
      for (index in 0 until childCount) {
        getChildAt(index).findThumbnail(uri)?.let { return it }
      }
    }

    return null
  }

  /** Tellomi (A-07): the emoji in [emoji]'s pill under message [messageId], if that message is on screen. */
  private fun findReactionPillEmoji(messageId: Long, emoji: String): View? {
    if (view == null) {
      return null
    }

    val recycler = binding.conversationItemRecycler
    for (index in 0 until recycler.childCount) {
      val child = recycler.getChildAt(index)
      // V2 rows are Multiselectable through their view holder, older rows through the view (as MultiselectItemDecoration does).
      val item = recycler.getChildViewHolder(child) as? Multiselectable ?: child as? Multiselectable
      if (item?.conversationMessage?.messageRecord?.id == messageId) {
        return child.findReactionsView()?.findPillEmojiView(emoji)
      }
    }

    return null
  }

  private fun View.findReactionsView(): ReactionsConversationView? {
    if (this is ReactionsConversationView) {
      return this
    }

    if (this is ViewGroup) {
      for (index in 0 until childCount) {
        getChildAt(index).findReactionsView()?.let { return it }
      }
    }

    return null
  }

  private inner class MotionEventRelayDrain(lifecycleOwner: LifecycleOwner) : MotionEventRelay.Drain {
    private val lifecycle = lifecycleOwner.lifecycle

    override fun accept(motionEvent: MotionEvent): Boolean {
      return if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        reactionOverlay.applyTouchEvent(motionEvent)
      } else {
        false
      }
    }
  }

  private inner class ReactionsToolbarListener(
    private val conversationMessage: ConversationMessage
  ) {
    fun onActionSelected(action: ReactionAction) {
      when (action) {
        ReactionAction.REPLY -> handleReplyToMessage(conversationMessage)
        ReactionAction.EDIT -> handleEditMessage(conversationMessage)
        ReactionAction.FORWARD -> handleForwardMessageParts(conversationMessage.multiselectCollection.toSet())
        ReactionAction.TELLOMI_SAVE_TO_SAVED_MESSAGES -> handleSaveToSavedMessages(conversationMessage.multiselectCollection.toSet())
        ReactionAction.RESEND -> handleResend(conversationMessage)
        ReactionAction.DOWNLOAD -> handleSaveAttachment(conversationMessage.messageRecord as MmsMessageRecord)
        ReactionAction.COPY -> handleCopyMessage(conversationMessage.multiselectCollection.toSet())
        ReactionAction.MULTISELECT -> handleEnterMultiselect(conversationMessage)
        ReactionAction.PAYMENT_DETAILS -> handleViewPaymentDetails(conversationMessage)
        ReactionAction.VIEW_INFO -> handleDisplayDetails(conversationMessage)
        ReactionAction.DELETE -> handleDeleteMessages(conversationMessage.multiselectCollection.toSet())
        ReactionAction.END_POLL -> handleEndPoll(conversationMessage.messageRecord.getPoll()?.id)
        ReactionAction.PIN_MESSAGE -> handlePinMessage(conversationMessage)
        ReactionAction.UNPIN_MESSAGE -> handleUnpinMessage(conversationMessage.messageRecord.id)
        ReactionAction.STAR_MESSAGE -> handleStarMessages(setOf(conversationMessage.messageRecord.id))
        ReactionAction.UNSTAR_MESSAGE -> handleUnstarMessages(setOf(conversationMessage.messageRecord.id))
      }
    }
  }
  // endregion Conversation Callbacks

  //region Activity Results Callbacks

  private inner class ActivityResultCallbacks : ConversationActivityResultContracts.Callbacks {
    override fun onSendContacts(contacts: List<Contact>) {
      if (contacts.isEmpty()) {
        return
      }

      sendMessageWithoutComposeInput(
        contacts = contacts,
        clearCompose = false
      )
    }

    override fun onMediaSend(result: MediaSendActivityResult?) {
      if (result == null) {
        return
      }

      val recipientSnapshot = viewModel.recipientSnapshot
      if (result.recipientId != recipientSnapshot?.id) {
        Log.w(TAG, "Result's recipientId did not match ours! Result: " + result.recipientId + ", Ours: " + recipientSnapshot?.id)
        toast(R.string.ConversationActivity_error_sending_media)
        return
      }

      // Tellomi（tellomi/tellomi#1714，ADR-0079）：有计时的（含一次性查看）整批一张一条（照 Telegram 两端）。
      if (result.hasTellomiTimers && result.isPushPreUpload) {
        sendMediaTimed(result)
        return
      }

      // Tellomi（tellomi/tellomi#1261 P-5「单独发送」）：一张一条，说明挂最后一条。「以文件发送」（#1263）也是一个一条。
      if (result.sendSeparately || result.sendAsFile) {
        sendMediaSeparately(result)
        return
      }

      // Tellomi（tellomi/tellomi#1518 V-6，owner 2026-10-14 照 TG）：静音当 GIF 发的视频单独成一条，其余照常成组。
      val tellomiSplit = TellomiMutedVideos.split(if (result.isPushPreUpload) result.preUploadResults.map { it.media } else result.nonUploadedMedia)
      if (tellomiSplit != null) {
        sendMediaInGroups(result, tellomiSplit)
        return
      }

      if (result.isPushPreUpload) {
        sendPreUploadMediaMessage(result)
        return
      }

      val slides: List<Slide> = mediaSlides(result.nonUploadedMedia)

      sendMessage(
        body = result.body,
        mentions = result.mentions,
        bodyRanges = result.bodyRanges,
        messageToEdit = null,
        quote = if (result.isViewOnce) null else inputPanel.quote.orNull(),
        scheduledDate = result.scheduledTime,
        slideDeck = SlideDeck().apply { slides.forEach { addSlide(it) } },
        contacts = emptyList(),
        clearCompose = true,
        linkPreviews = emptyList(),
        isViewOnce = result.isViewOnce,
        bypassPreSendSafetyNumberCheck = true,
        captionAboveMedia = result.captionAboveMedia
      ) {
        viewModel.deleteSlideData(slides)
      }
    }

    private fun mediaSlides(media: List<Media>): List<Slide> {
      return media.mapNotNull {
        when {
          // Tellomi（tellomi/tellomi#1263「以文件发送」，ADR-0068）：原图按文件发，对方画成文件行。
          it.transformProperties?.tellomiAsFile == true -> {
            DocumentSlide.tellomiAsFile(requireContext(), it.uri, it.contentType ?: MediaUtil.OCTET, it.size, it.width, it.height, it.fileName)
          }

          MediaUtil.isVideoType(it.contentType) -> VideoSlide(requireContext(), it.uri, it.size, it.isVideoGif, it.width, it.height, it.caption, it.transformProperties)

          MediaUtil.isGif(it.contentType) -> GifSlide(requireContext(), it.uri, it.size, it.width, it.height, it.isBorderless, it.caption)

          MediaUtil.isImageType(it.contentType) -> ImageSlide(requireContext(), it.uri, it.contentType, it.size, it.width, it.height, it.isBorderless, it.caption, null, it.transformProperties)

          MediaUtil.isDocumentType(it.contentType) -> {
            DocumentSlide(requireContext(), it.uri, it.contentType!!, it.size, it.fileName)
          }

          else -> {
            Log.w(TAG, "Asked to send an unexpected mimeType: '${it.contentType}'. Skipping.")
            null
          }
        }
      }
    }

    /**
     * Tellomi（tellomi/tellomi#1261 P-5「单独发送」，照 Telegram 的 SendWithoutGrouping）：一张一条消息，说明（与 @、样式）挂在最后一条，
     * 引用挂在第一条。一条发完（写进库）再发下一条，时间戳各不相同、顺序就是选的顺序。
     *
     * 整串一次建好交给 [TellomiSendInOrder]，订阅不进 [disposables]（那个绑在 view 上）：发到一半离开会话、弹窗会话发完第一条就
     * finish，剩下的照样发完。所以前置检查（收件人、定时消息）只在这里做一次，输入框和引用在这里同步清掉（同 [sendMessage] 的
     * clearCompose）；[onSendComplete] 只在第一条写进库、页面还在时调一次。
     */
    @SuppressLint("CheckResult")
    private fun sendMediaSeparately(result: MediaSendActivityResult) {
      val threadRecipient = viewModel.recipientSnapshot
      if (threadRecipient == null) {
        Log.w(TAG, "Unable to send due to invalid thread recipient")
        toast(R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation, Toast.LENGTH_LONG)
        return
      }

      if (result.scheduledTime != -1L && ReenableScheduledMessagesDialogFragment.showIfNeeded(requireContext(), childFragmentManager, null, result.scheduledTime)) {
        return
      }

      val quote = if (result.isViewOnce) null else inputPanel.quote.orNull()
      val preUploadParts: List<MessageSender.PreUploadResult> = result.preUploadResults
      val allSlides: List<Slide> = if (result.isPushPreUpload) emptyList() else mediaSlides(result.nonUploadedMedia)

      // 按文件发的原图不压缩，可能超过文件上限（大视频）：超过的不发，发完剩下的再提示「文件太大」并写明上限（同「文件」页）。
      val maxFileSize = PushMediaConstraints(null).documentMaxSize
      val (slideParts, tooLarge) = if (result.sendAsFile) allSlides.partition { it.fileSize <= maxFileSize } else allSlides to emptyList()
      if (tooLarge.isNotEmpty()) {
        viewModel.deleteSlideData(tooLarge)
        MaterialAlertDialogBuilder(requireContext())
          .setMessage(getString(R.string.TellomiAttachmentFiles__too_large, tooLarge.first().fileName.orNull().orEmpty(), maxFileSize.bytes.toUnitString()))
          .setPositiveButton(android.R.string.ok, null)
          .show()
      }

      val count = if (result.isPushPreUpload) preUploadParts.size else slideParts.size
      if (count == 0) {
        if (tooLarge.isEmpty()) {
          Log.i(TAG, "Unable to send due to empty message")
          toast(R.string.ConversationActivity_message_is_empty_exclamation)
        }
        return
      }

      val parts: List<Completable> = (0 until count).map { index ->
        val isFirst = index == 0
        val isLast = index == count - 1
        val slide = slideParts.getOrNull(index)
        viewModel.sendMessage(
          metricId = null,
          threadRecipient = threadRecipient,
          body = if (isLast) result.body else "",
          slideDeck = slide?.let { SlideDeck().apply { addSlide(it) } },
          scheduledDate = result.scheduledTime,
          messageToEdit = null,
          quote = if (isFirst) quote else null,
          mentions = if (isLast) result.mentions else emptyList(),
          bodyRanges = if (isLast) result.bodyRanges else null,
          contacts = emptyList(),
          linkPreviews = emptyList(),
          preUploadResults = if (result.isPushPreUpload) listOf(preUploadParts[index]) else emptyList(),
          isViewOnce = false,
          // 说明只挂在最后一条，「说明在上方」也只给它（按文件发的不适用：结果里本来就是 false）
          captionAboveMedia = isLast && result.captionAboveMedia
        ).doOnComplete {
          // 离开会话后 view 已经没了，onSendComplete 里滚动、清草稿都不用做了。
          if (isFirst && isAdded && view != null) {
            onSendComplete()
          }
          slide?.let { viewModel.deleteSlideData(listOf(it)) }
        }
      }

      AppDependencies.typingStatusSender.onTypingStopped(args.threadId)
      composeTextEventsListener?.typingStatusEnabled = false
      composeText.setText("")
      composeTextEventsListener?.typingStatusEnabled = true
      attachmentManager.clear(Glide.with(this@ConversationFragment), false)
      inputPanel.clearQuote()
      scrollToPositionDelegate.markListCommittedVersion()

      TellomiSendInOrder.inOrder(parts).subscribeBy(
        onError = {
          Log.w(TAG, "Error received during send!", it)
          toast(R.string.ConversationActivity_error_sending_media)
        }
      )
    }

    /**
     * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4）：有计时的一张一条地发（[TellomiTimedSends.parts]）：计时的是一次性查看、带秒数、
     * 不带说明；没计时的照常，说明挂在没计时的最后一张；全都计时时说明是最前面单独一条文字。引用挂在第一条不是一次性查看的上
     * （一次性查看不能带引用）。不能定时发送（选图里有计时就不给）。发送顺序、清输入框同 [sendMediaSeparately]。
     */
    @SuppressLint("CheckResult")
    private fun sendMediaTimed(result: MediaSendActivityResult) {
      val threadRecipient = viewModel.recipientSnapshot
      if (threadRecipient == null) {
        Log.w(TAG, "Unable to send due to invalid thread recipient")
        toast(R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation, Toast.LENGTH_LONG)
        return
      }

      val quote = inputPanel.quote.orNull()
      val parts = TellomiTimedSends.parts(result.tellomiViewOnceChoices, hasBody = result.body.isNotBlank())
      val quotedPart = parts.indexOfFirst { !it.isViewOnce }

      val sends: List<Completable> = parts.mapIndexed { position, part ->
        viewModel.sendMessage(
          metricId = null,
          threadRecipient = threadRecipient,
          body = if (part.hasBody) result.body else "",
          slideDeck = null,
          scheduledDate = -1,
          messageToEdit = null,
          quote = if (position == quotedPart) quote else null,
          mentions = if (part.hasBody) result.mentions else emptyList(),
          bodyRanges = if (part.hasBody) result.bodyRanges else null,
          contacts = emptyList(),
          linkPreviews = emptyList(),
          preUploadResults = part.index?.let { listOf(result.preUploadResults[it]) } ?: emptyList(),
          isViewOnce = part.isViewOnce,
          captionAboveMedia = part.hasBody && part.index != null && result.captionAboveMedia,
          tellomiViewOnceSeconds = part.seconds
        ).doOnComplete {
          if (position == 0 && isAdded && view != null) {
            onSendComplete()
          }
        }
      }

      AppDependencies.typingStatusSender.onTypingStopped(args.threadId)
      composeTextEventsListener?.typingStatusEnabled = false
      composeText.setText("")
      composeTextEventsListener?.typingStatusEnabled = true
      attachmentManager.clear(Glide.with(this@ConversationFragment), false)
      inputPanel.clearQuote()
      scrollToPositionDelegate.markListCommittedVersion()

      TellomiSendInOrder.inOrder(sends).subscribeBy(
        onError = {
          Log.w(TAG, "Error received during send!", it)
          toast(R.string.ConversationActivity_error_sending_media)
        }
      )
    }

    /**
     * Tellomi（tellomi/tellomi#1518 V-6）：按 [groups] 一组一条地发（其余的相册一条在前，每个静音视频各一条在后）。说明、@、格式和
     * 「说明在上方」跟着第一组（其余的那组）；全是静音视频时跟着最后一条。引用回复挂第一条。
     */
    private fun sendMediaInGroups(result: MediaSendActivityResult, split: TellomiMutedVideos.Split) {
      val threadRecipient = viewModel.recipientSnapshot
      if (threadRecipient == null) {
        Log.w(TAG, "Unable to send due to invalid thread recipient")
        toast(R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation, Toast.LENGTH_LONG)
        return
      }

      if (result.scheduledTime != -1L && ReenableScheduledMessagesDialogFragment.showIfNeeded(requireContext(), childFragmentManager, null, result.scheduledTime)) {
        return
      }

      val quote = if (result.isViewOnce) null else inputPanel.quote.orNull()
      // One slide per item (null for a type there is no slide for), so the indices in the split still line up.
      val slidesByIndex: List<Slide?> = if (result.isPushPreUpload) emptyList() else result.nonUploadedMedia.map { mediaSlides(listOf(it)).firstOrNull() }

      val parts: List<Completable> = split.groups.mapIndexedNotNull { index, group ->
        val isFirst = index == 0
        val hasBody = index == split.bodyIndex
        val slides = if (result.isPushPreUpload) emptyList() else group.mapNotNull { slidesByIndex.getOrNull(it) }
        if (!result.isPushPreUpload && slides.isEmpty() && !hasBody) {
          return@mapIndexedNotNull null
        }
        viewModel.sendMessage(
          metricId = null,
          threadRecipient = threadRecipient,
          body = if (hasBody) result.body else "",
          slideDeck = if (slides.isEmpty()) null else SlideDeck().apply { slides.forEach { addSlide(it) } },
          scheduledDate = result.scheduledTime,
          messageToEdit = null,
          quote = if (isFirst) quote else null,
          mentions = if (hasBody) result.mentions else emptyList(),
          bodyRanges = if (hasBody) result.bodyRanges else null,
          contacts = emptyList(),
          linkPreviews = emptyList(),
          preUploadResults = if (result.isPushPreUpload) group.map { result.preUploadResults[it] } else emptyList(),
          isViewOnce = false,
          captionAboveMedia = hasBody && result.captionAboveMedia
        ).doOnComplete {
          if (isFirst && isAdded && view != null) {
            onSendComplete()
          }
          if (slides.isNotEmpty()) {
            viewModel.deleteSlideData(slides)
          }
        }
      }

      AppDependencies.typingStatusSender.onTypingStopped(args.threadId)
      composeTextEventsListener?.typingStatusEnabled = false
      composeText.setText("")
      composeTextEventsListener?.typingStatusEnabled = true
      attachmentManager.clear(Glide.with(this@ConversationFragment), false)
      inputPanel.clearQuote()
      scrollToPositionDelegate.markListCommittedVersion()

      TellomiSendInOrder.inOrder(parts).subscribeBy(
        onError = {
          Log.w(TAG, "Error received during send!", it)
          toast(R.string.ConversationActivity_error_sending_media)
        }
      )
    }

    private fun sendPreUploadMediaMessage(result: MediaSendActivityResult) {
      sendMessage(
        body = result.body,
        mentions = result.mentions,
        bodyRanges = result.bodyRanges,
        messageToEdit = null,
        quote = if (result.isViewOnce) null else inputPanel.quote.orNull(),
        scheduledDate = result.scheduledTime,
        slideDeck = null,
        contacts = emptyList(),
        clearCompose = true,
        linkPreviews = emptyList(),
        preUploadResults = result.preUploadResults,
        isViewOnce = result.isViewOnce,
        bypassPreSendSafetyNumberCheck = true,
        captionAboveMedia = result.captionAboveMedia
      )
    }

    override fun onContactSelect(source: SharedContactSource?) {
      val recipient = viewModel.recipientSnapshot
      if (source != null && recipient != null) {
        conversationActivityResultContracts.launchContactShareEditor(source, recipient.id)
      }
    }

    override fun onLocationSelected(place: SignalPlace?, uri: Uri?) {
      if (place != null && uri != null) {
        attachmentManager.setLocation(place, uri)
        draftViewModel.setLocationDraft(place)
      } else {
        Log.w(TAG, "Location missing thumbnail")
      }
    }

    override fun onFileSelected(uri: Uri?) {
      if (uri != null) {
        setMedia(uri, SlideFactory.MediaType.DOCUMENT)
      }
    }

    override fun onAttachmentSheetButton(button: AttachmentKeyboardButton) {
      val recipient = viewModel.recipientSnapshot ?: return
      onAttachmentButton(button, recipient)
    }

    override fun onAttachmentSheetFiles(result: MediaSendFlowActivityContract.AttachmentFilesResult) {
      sendAttachmentSheetFiles(result)
    }

    override fun onAttachmentSheetPoll(poll: Poll) {
      val recipient = viewModel.recipientSnapshot ?: return
      sendPoll(recipient, poll)
    }
  }

  //endregion

  private class LastScrolledPositionUpdater(
    val adapter: ConversationAdapterV2,
    val layoutManager: LinearLayoutManager,
    val viewModel: ConversationViewModel
  ) : DefaultLifecycleObserver {
    override fun onPause(owner: LifecycleOwner) {
      val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
      val firstVisiblePosition = layoutManager.findFirstCompletelyVisibleItemPosition()
      val lastVisibleMessageTimestamp = if (firstVisiblePosition > 0 && lastVisiblePosition != RecyclerView.NO_POSITION) {
        adapter.getLastVisibleConversationMessage(lastVisiblePosition)?.messageRecord?.dateReceived ?: 0L
      } else {
        0L
      }

      viewModel.setLastScrolled(lastVisibleMessageTimestamp)
    }
  }

  //region Conversation Banner Callbacks

  private inner class ConversationBannerListener : ConversationBannerView.Listener {
    override fun updateAppAction() {
      PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext())
    }

    override fun reRegisterAction() {
      startActivity(RegistrationActivity.newIntentForReRegistration(requireContext()))
    }

    override fun reviewJoinRequestsAction() {
      viewModel.recipientSnapshot?.let { recipient ->
        val intent = ManagePendingAndRequestingMembersActivity.newIntent(requireContext(), recipient.requireGroupId().requireV2())
        startActivity(intent)
      }
    }

    override fun gv1SuggestionsAction(actionId: Int) {
      if (actionId == R.id.reminder_action_gv1_suggestion_add_members) {
        conversationGroupViewModel.groupRecordSnapshot?.let { groupRecord ->
          GroupsV1MigrationSuggestionsDialog.show(childFragmentManager, groupRecord.id.requireV2(), groupRecord.gv1MigrationSuggestions)
        }
      } else if (actionId == R.id.reminder_action_gv1_suggestion_no_thanks) {
        conversationGroupViewModel.onSuggestedMembersBannerDismissed()
      }
    }

    @SuppressLint("InlinedApi")
    override fun changeBubbleSettingAction(disableSetting: Boolean) {
      SignalStore.tooltips.markBubbleOptOutTooltipSeen()

      if (disableSetting) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
      } else {
        binding.conversationBanner.clearBanner()
      }
    }

    override fun onUnverifiedBannerClicked(unverifiedIdentities: List<IdentityRecord>) {
      if (unverifiedIdentities.size == 1) {
        VerifyIdentityActivity.startOrShowExchangeMessagesDialog(requireContext(), unverifiedIdentities[0], false)
      } else {
        val unverifiedNames = unverifiedIdentities
          .map { Recipient.resolved(it.recipientId).getDisplayName(requireContext()) }
          .toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
          .setIcon(R.drawable.symbol_error_triangle_fill_24)
          .setTitle(R.string.ConversationFragment__no_longer_verified)
          .setItems(unverifiedNames) { _, which: Int -> VerifyIdentityActivity.startOrShowExchangeMessagesDialog(requireContext(), unverifiedIdentities[which], false) }
          .show()
      }
    }

    override fun onUnverifiedBannerDismissed(unverifiedIdentities: List<IdentityRecord>) {
      viewModel.resetVerifiedStatusToDefault(unverifiedIdentities)
    }

    override fun onRequestReviewIndividual(recipientId: RecipientId) {
      ReviewCardDialogFragment.createForReviewRequest(recipientId).show(childFragmentManager, null)
    }

    override fun onReviewGroupMembers(groupId: GroupId.V2) {
      ReviewCardDialogFragment.createForReviewMembers(groupId).show(childFragmentManager, null)
    }

    override fun onDismissReview() {
      viewModel.onDismissReview()
    }

    override fun onUnpinMessage(messageId: Long) {
      handleUnpinMessage(messageId)
    }

    override fun onGoToMessage(messageId: Long) {
      viewPinnedMessage(messageId)
    }

    override fun onViewAllMessages() {
      PinnedMessagesBottomSheet.show(
        childFragmentManager,
        threadId = args.threadId,
        conversationRecipientId = viewModel.recipientSnapshot?.id!!,
        canUnpin = conversationGroupViewModel.canEditGroupInfo()
      )
    }
  }

  //endregion

  //region Disabled Input Callbacks

  private inner class DisabledInputListener : DisabledInputView.Listener {
    override fun onUpdateAppClicked() {
      // Tellomi（taishi 审查 b14 包 8 不阻塞 2）：官网版和只读横幅一样打开 App 内的更新页，不去浏览器
      if (BuildConfig.MANAGES_APP_UPDATES && UpdateRequired.isRequired()) {
        startActivity(Intent(requireContext(), ClientDeprecatedActivity::class.java))
      } else {
        PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext())
      }
    }

    override fun onReRegisterClicked() {
      startActivity(RegistrationActivity.newIntentForReRegistration(requireContext()))
    }

    override fun onReLinkDeviceClicked() {
      startActivity(RegistrationActivity.newIntentForReLinkDevice(requireContext()))
    }

    override fun onCancelGroupRequestClicked() {
      conversationGroupViewModel
        .cancelJoinRequest()
        .subscribeBy { result ->
          when (result) {
            is Result.Success -> Log.d(TAG, "Cancel request complete")

            is Result.Failure -> {
              Log.d(TAG, "Cancel join request failed ${result.failure}")
              toast(GroupErrors.getUserDisplayMessage(result.failure))
            }
          }
        }
        .addTo(disposables)
    }

    override fun onShowAdminsBottomSheetDialog() {
      viewModel.recipientSnapshot?.let { recipient ->
        ShowAdminsBottomSheetDialog.show(childFragmentManager, recipient.requireGroupId().requireV2())
      }
    }

    override fun onAcceptMessageRequestClicked() {
      onMessageRequestAccept()
    }

    override fun onDeleteClicked() {
      onDeleteConversation()
    }

    override fun onBlockClicked() {
      onBlock()
    }

    override fun onUnblockClicked() {
      onUnblock()
    }

    override fun onReportSpamClicked() {
      onReportSpam()
    }

    override fun onInviteToSignal(recipient: Recipient) {
      InviteActions.inviteUserToSignal(
        context = requireContext(),
        launchIntent = this@ConversationFragment::startActivity
      )
    }
  }

  //endregion

  //region Compose + Send Callbacks

  private inner class SendButtonListener :
    View.OnClickListener,
    OnEditorActionListener,
    SendButton.ScheduledSendListener {
    override fun onClick(v: View) {
      sendMessage()
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        if (inputPanel.isInEditMode) {
          sendEditButton.performClick()
        } else {
          sendButton.performClick()
        }
        return true
      }
      return false
    }

    override fun onSendScheduled() {
      if (inputPanel.isRecordingInLockedMode) {
        inputPanel.onSaveRecordDraft()
      }

      // Tellomi（#1601）：收藏里叫「设置提醒」。
      val isReminder = viewModel.recipientSnapshot?.isSelf == true
      ScheduleMessageContextMenu.show(sendButton, (conversationContent as ViewGroup), isReminder) { time ->
        if (time == -1L) {
          showSchedule(childFragmentManager, isReminder)
        } else {
          sendMessage(scheduledDate = time)
        }
      }
    }
  }

  private inner class ComposeTextEventsListener :
    View.OnKeyListener,
    View.OnClickListener,
    TextWatcher,
    ComposeText.CursorPositionChangedListener,
    ComposeText.StylingChangedListener {

    private var beforeLength = 0
    private var previousText = ""

    var typingStatusEnabled = true

    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
      if (event.action == KeyEvent.ACTION_DOWN) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
          if (SignalStore.settings.isEnterKeySends || event.isCtrlPressed) {
            sendButton.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            sendButton.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return true
          }
        }
      }
      return false
    }

    override fun onClick(v: View) {
      container.showSoftkey(composeText)
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
      beforeLength = composeText.textTrimmed.length
    }

    override fun afterTextChanged(s: Editable) {
      if (composeText.textTrimmed.isEmpty() || beforeLength == 0) {
        composeText.postDelayed({
          if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            updateToggleButtonState()
          }
        }, 50)
      }

      if (!inputPanel.inEditMessageMode()) {
        stickerViewModel.onInputTextUpdated(s.toString())
      } else {
        stickerViewModel.onInputTextUpdated("")
      }
    }

    override fun onCursorPositionChanged(start: Int, end: Int) {
      linkPreviewViewModel.onTextChanged(composeText.textTrimmed.toString(), start, end)
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
      handleSaveDraftOnTextChange(composeText.textTrimmed)
      handleTypingIndicatorOnTextChange(s.toString())
    }

    private fun handleSaveDraftOnTextChange(text: CharSequence) {
      textDraftSaveDebouncer.publish {
        if (inputPanel.inEditMessageMode()) {
          draftViewModel.setMessageEditDraft(inputPanel.editMessageId!!, text.toString(), MentionAnnotation.getMentionsFromAnnotations(text), getStyling(text))
        } else {
          draftViewModel.setTextDraft(text.toString(), MentionAnnotation.getMentionsFromAnnotations(text), getStyling(text))
        }
      }
    }

    private fun handleTypingIndicatorOnTextChange(text: String) {
      val recipient = viewModel.recipientSnapshot

      if (recipient == null || !typingStatusEnabled || recipient.isBlocked || recipient.isSelf) {
        return
      }

      val typingStatusSender = AppDependencies.typingStatusSender
      if (text.length == 0) {
        typingStatusSender.onTypingStoppedWithNotify(args.threadId)
      } else if (text.length < previousText.length && previousText.contains(text)) {
        typingStatusSender.onTypingStopped(args.threadId)
      } else {
        typingStatusSender.onTypingStarted(args.threadId)
      }

      previousText = text
    }

    override fun onStylingChanged() {
      handleSaveDraftOnTextChange(composeText.textTrimmed)
    }
  }

  //endregion Compose + Send Callbacks

  //region Input Panel Callbacks

  private inner class InputPanelListener : InputPanel.Listener {
    override fun onVoiceNoteDraftPlay(audioUri: Uri, progress: Double) {
      getVoiceNoteMediaController().startSinglePlaybackForDraft(audioUri, args.threadId, progress)
    }

    override fun onVoiceNoteDraftSeekTo(audioUri: Uri, progress: Double) {
      getVoiceNoteMediaController().seekToPosition(audioUri, progress)
    }

    override fun onVoiceNoteDraftPause(audioUri: Uri) {
      getVoiceNoteMediaController().pausePlayback(audioUri)
    }

    override fun onVoiceNoteDraftDelete(audioUri: Uri) {
      getVoiceNoteMediaController().stopPlaybackAndReset(audioUri)
      draftViewModel.deleteVoiceNoteDraft()
    }

    override fun onRecorderStarted() {
      voiceMessageRecordingDelegate.onRecorderStarted()
    }

    override fun onRecorderLocked() {
      updateToggleButtonState()
      voiceMessageRecordingDelegate.onRecorderLocked()
    }

    override fun onRecorderFinished() {
      updateToggleButtonState()
      voiceMessageRecordingDelegate.onRecorderFinished()
    }

    override fun onRecorderCanceled(byUser: Boolean) {
      voiceMessageRecordingDelegate.onRecorderCanceled(byUser)
    }

    override fun onRecorderSaveDraft() {
      voiceMessageRecordingDelegate.onRecordSaveDraft()
      inputPanel.voiceNoteDraft = draftViewModel.voiceNoteDraft
    }

    override fun onRecorderPermissionRequired() {
      Permissions
        .with(this@ConversationFragment)
        .request(Manifest.permission.RECORD_AUDIO)
        .ifNecessary()
        .withRationaleDialog(getString(R.string.ConversationActivity_allow_access_microphone), getString(R.string.ConversationActivity_to_send_voice_messages_allow_signal_access_to_your_microphone), R.drawable.ic_mic_24)
        .withPermanentDenialDialog(
          getString(R.string.ConversationActivity_signal_requires_the_microphone_permission_in_order_to_send_audio_messages),
          null,
          R.string.ConversationActivity_allow_access_microphone,
          R.string.ConversationActivity_signal_to_send_audio_messages,
          this@ConversationFragment.parentFragmentManager
        )
        .onAnyDenied { Toast.makeText(this@ConversationFragment.requireContext(), R.string.ConversationActivity_signal_needs_microphone_access_voice_message, Toast.LENGTH_LONG).show() }
        .execute()
    }

    override fun onRecorderAlreadyInUse() {
      toast(R.string.ConversationFragment_cannot_record_voice_message_during_call)
    }

    override fun onEmojiToggle() {
      container.toggleInput(ChatKeyboards.Media, composeText, showSoftKeyOnHide = true)
    }

    override fun onLinkPreviewCanceled() {
      linkPreviewViewModel.onUserCancel()
    }

    override fun onStickerSuggestionSelected(sticker: StickerRecord) {
      sendSticker(
        stickerRecord = sticker,
        clearCompose = true
      )
    }

    override fun onQuoteChanged(id: Long, author: RecipientId) {
      draftViewModel.setQuoteDraft(id, author)
    }

    override fun onQuoteCleared() {
      draftViewModel.clearQuoteDraft()
    }

    override fun onQuoteClicked(quoteId: Long, authorId: RecipientId) {
      handleMoveToQuotePosition(quoteId = quoteId, authorId = authorId)
    }

    override fun onEnterEditMode() {
      updateToggleButtonState()
      previousPage = keyboardPagerViewModel.page().value
      previousPages = keyboardPagerViewModel.pages().value
      keyboardPagerViewModel.setOnlyPage(KeyboardPage.EMOJI)
      onKeyboardChanged(KeyboardPage.EMOJI)
      stickerViewModel.onInputTextUpdated("")
      updateLinkPreviewState()
    }

    override fun onExitEditMode() {
      updateToggleButtonState()
      draftViewModel.deleteMessageEditDraft()
      if (previousPages != null) {
        keyboardPagerViewModel.setPages(previousPages!!)
        previousPages = null
      }
      if (previousPage != null) {
        keyboardPagerViewModel.switchToPage(previousPage!!)
        onKeyboardChanged(previousPage!!)
        previousPage = null
      }
      updateLinkPreviewState()
    }

    override fun onQuickCameraToggleClicked() {
      val recipientId = viewModel.recipientSnapshot?.id ?: return
      composeText.clearFocus()
      conversationActivityResultContracts.launchCamera(recipientId, inputPanel.quote.isPresent)
    }
  }

  private inner class InputPanelMediaListener {
    fun onMediaSelected(uri: Uri, contentType: String?) {
      if (inputPanel.inEditMessageMode()) {
        Log.i(TAG, "Disregarding media because we are in edit mode")
      } else if (MediaUtil.isGif(contentType) || MediaUtil.isImageType(contentType)) {
        disposables += viewModel.getKeyboardImageDetails(uri)
          .observeOn(AndroidSchedulers.mainThread())
          .subscribeBy(
            onSuccess = {
              sendKeyboardImage(uri, contentType!!, it)
            },
            onComplete = {
              sendKeyboardImage(uri, contentType!!, null)
            },
            onError = {
              Log.w(TAG, "Failed to read details for the keyboard image. Continuing without them.", it)
              sendKeyboardImage(uri, contentType!!, null)
            }
          )
      } else if (MediaUtil.isVideoType(contentType)) {
        setMedia(uri, SlideFactory.MediaType.VIDEO)
      } else {
        setMedia(uri, SlideFactory.MediaType.AUDIO)
      }
    }

    private fun sendKeyboardImage(uri: Uri, contentType: String, keyboardImageDetails: KeyboardUtil.ImageDetails?) {
      if (keyboardImageDetails == null || !keyboardImageDetails.isSticker) {
        setMedia(uri, requireNotNull(SlideFactory.MediaType.from(contentType)))
        return
      }

      val slide: Slide = when {
        MediaUtil.isGif(contentType) -> GifSlide(requireContext(), uri, 0, keyboardImageDetails.width, keyboardImageDetails.height, true, null)
        MediaUtil.isImageType(contentType) -> ImageSlide(requireContext(), uri, contentType, 0, keyboardImageDetails.width, keyboardImageDetails.height, true, null, null)
        else -> null
      } ?: error("Only images are supported!")

      sendMessageWithoutComposeInput(slide = slide)
    }
  }

  //endregion

  //region Attachment + Media Keyboard

  private inner class AttachmentManagerListener : AttachmentManager.AttachmentListener {
    override fun onAttachmentChanged() {
      updateToggleButtonState()
      updateLinkPreviewState()
    }

    override fun onLocationRemoved() {
      draftViewModel.clearLocationDraft()
    }
  }

  /**
   * Tellomi（tellomi/tellomi#1121 F-4、F-7、F-8）：附件 Sheet「文件」页选好的文件——每个一条、按顺序立即发送，说明挂在最后一个
   * （同 Telegram）。超过上限的不发，发完剩下的再提示「文件太大」并写明上限；本机已经没有的也提示一句。
   *
   * 系统选择器挑的文件，Sheet 收到时转成了持久读授权（[PickedFileGrants.take]）：整串发完、出错或者一个都没发成都放掉。
   * 取元数据那一步还挂在 [disposables] 上，离开会话时不放——那时查询可能还在 IO 线程上读，放了它就会抛，而订阅已经断了，
   * 异常只能走 Rx 的全局处理器；留下的这几个持久授权由系统的名额上限兜底（超了按时间淘汰最旧的）。
   */
  private fun sendAttachmentSheetFiles(result: MediaSendFlowActivityContract.AttachmentFilesResult) {
    val context = requireContext().applicationContext
    val maxFileSize = PushMediaConstraints(null).documentMaxSize
    val releasePickedGrants: () -> Unit = { PickedFileGrants.release(context.contentResolver, result.pickedUris) }
    disposables += Single
      .fromCallable { TellomiAttachmentFiles.prepare(context, result, maxFileSize) }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy(
        onError = {
          Log.w(TAG, "Couldn't prepare the attachment sheet files", it)
          releasePickedGrants()
          toast(R.string.ConversationActivity_error_sending_media)
        },
        onSuccess = { prepared ->
          sendSlidesInOrder(prepared.slides, result.caption?.trim().orEmpty(), onTerminate = releasePickedGrants)
          val tooLarge = prepared.tooLarge.firstOrNull()
          when {
            tooLarge != null -> MaterialAlertDialogBuilder(requireContext())
              .setMessage(getString(R.string.TellomiAttachmentFiles__too_large, tooLarge, maxFileSize.bytes.toUnitString()))
              .setPositiveButton(android.R.string.ok, null)
              .show()

            prepared.unavailable > 0 -> toast(R.string.TellomiAttachmentFiles__some_not_on_device, Toast.LENGTH_LONG)
          }
        }
      )
  }

  /**
   * 一个发完（进了本地库）再发下一个，保证对方看到的顺序就是选的顺序；[caption] 只挂在最后一个上，输入框里的草稿不动。
   *
   * 整串一次建好交给 [TellomiSendInOrder]，订阅不进 [disposables]（那个绑在 view 上）：文件这条路不预上传，每个都要在插入时整份
   * 拷进附件库，多选几个大文件就是好几秒，发到一半离开会话、弹窗会话发完第一个就 finish，剩下的也要发完。
   * [onSendComplete] 只在第一个写进库、页面还在时调一次；[onTerminate] 在整串发完、出错或者一个都没发时调（放掉读授权）。
   */
  @SuppressLint("CheckResult")
  private fun sendSlidesInOrder(slides: List<Slide>, caption: String, onTerminate: () -> Unit) {
    val threadRecipient = viewModel.recipientSnapshot
    if (threadRecipient == null) {
      Log.w(TAG, "Unable to send due to invalid thread recipient")
      toast(R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation, Toast.LENGTH_LONG)
      onTerminate()
      return
    }

    if (slides.isEmpty()) {
      onTerminate()
      return
    }

    val parts: List<Completable> = slides.mapIndexed { index, slide ->
      val send = viewModel.sendMessage(
        metricId = null,
        threadRecipient = threadRecipient,
        body = if (index == slides.lastIndex) caption else "",
        slideDeck = SlideDeck().apply { addSlide(slide) },
        scheduledDate = -1L,
        messageToEdit = null,
        quote = null,
        mentions = emptyList(),
        bodyRanges = null,
        contacts = emptyList(),
        linkPreviews = emptyList(),
        preUploadResults = emptyList(),
        isViewOnce = false
      )
      if (index == 0) {
        // 离开会话后 view 已经没了，onSendComplete 里滚动、清草稿都不用做了。
        send.doOnComplete {
          if (isAdded && view != null) {
            onSendComplete()
          }
        }
      } else {
        send
      }
    }

    scrollToPositionDelegate.markListCommittedVersion()

    TellomiSendInOrder.inOrder(parts).subscribeBy(
      onComplete = onTerminate,
      onError = {
        Log.w(TAG, "Error received during send!", it)
        toast(R.string.ConversationActivity_error_sending_media)
        onTerminate()
      }
    )
  }

  /** Tellomi（tellomi/tellomi#1115）：「+」→ 附件 Sheet。先收起键盘和表情面板，Sheet 从底部滑上来、聊天留在后面。 */
  private fun openAttachmentSheet() {
    val recipient = viewModel.recipientSnapshot ?: return
    container.hideAll(composeText)
    conversationActivityResultContracts.launchAttachmentSheet(recipient.id, composeText.textTrimmed, inputPanel.quote.isPresent)
  }

  /**
   * 附件 Sheet 的 dock（tellomi/tellomi#1115）里不在 Sheet 里换页的格子：只剩位置。
   * 相册、文件、投票、联系人（2026-10-16）在 Sheet 里换页，付款不在 dock 里，都走不到这里（两端对齐清单 C-8；上游附件键盘已删）。
   * 位置这一条等安卓的位置页（高德 #1124）进了 Sheet 再删。
   */
  private fun onAttachmentButton(button: AttachmentKeyboardButton, recipient: Recipient) {
    when (button) {
      AttachmentKeyboardButton.LOCATION -> if (BuildConfig.MAPS_AVAILABLE) {
        conversationActivityResultContracts.launchSelectLocation(recipient.chatColors)
      } else {
        // Tellomi（tellomi/tellomi#1235、#1124）：高德接上之前不提供发送位置，dock 里这格已置灰（TellomiAttachmentSheetDock）
        toast(R.string.TellomiLocation__coming_soon, Toast.LENGTH_SHORT)
      }

      AttachmentKeyboardButton.GALLERY,
      AttachmentKeyboardButton.FILE,
      AttachmentKeyboardButton.POLL,
      AttachmentKeyboardButton.CONTACT,
      AttachmentKeyboardButton.PAYMENT -> Log.w(TAG, "Attachment sheet button handled inside the sheet or not in the dock: $button")
    }
  }

  private inner class KeyboardEvents :
    ChatInputController.Listener,
    ChatInputController.KeyboardStateListener {

    override fun onInputShown(key: MediaKeyboardKey) {
      if (key == ChatKeyboards.Media) {
        onShown()
      }
    }

    override fun onInputHidden() {
      setNavBarBackgroundColor(viewModel.wallpaperSnapshot != null || viewModel.recipientSnapshot?.isReleaseNotes == true)
      onHidden()
    }

    override fun onKeyboardShown() {
      // The toggle follows what is on screen: the system keyboard can cover one of ours, not just
      // replace it.
      inputPanel.setMediaKeyboardToggleOffersIme(false)

      if (searchMenuItem?.isActionViewExpanded == true && searchMenuItem?.actionView?.hasFocus() == false) {
        searchMenuItem?.actionView?.requestFocus()
      }
    }

    override fun onKeyboardHidden() {
      inputPanel.setMediaKeyboardToggleOffersIme(container.isInputShowing)

      if (searchMenuItem?.isActionViewExpanded == true && searchMenuItem?.actionView?.hasFocus() == true) {
        searchMenuItem?.actionView?.clearFocus()
      }
    }

    override fun onKeyboardAnimationEnded() {
      if (view == null) {
        return
      }
      if (!container.isKeyboardShowing) {
        closeEmojiSearch()
      }
    }
  }

  //endregion

  //region Event Bus

  @Subscribe(threadMode = ThreadMode.POSTING)
  fun onIdentityRecordUpdate(event: IdentityRecord?) {
    viewModel.updateIdentityRecordsInBackground()
  }

  @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
  fun onStickerPackInstalled(event: StickerPackInstallEvent?) {
    if (event == null) {
      return
    }

    if (!TextSecurePreferences.hasSeenStickerIntroTooltip(requireContext())) {
      return
    }

    EventBus.getDefault().removeStickyEvent(event)

    if (!inputPanel.isStickerMode) {
      conversationTooltips.displayStickerPackInstalledTooltip(inputPanel.mediaKeyboardToggleAnchorView, event)
    }
  }

  @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
  fun onGroupCallPeekEvent(groupCallPeekEvent: GroupCallPeekEvent) {
    groupCallViewModel.onGroupCallPeekEvent(groupCallPeekEvent)
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onRecaptchaRequiredEvent(recaptchaRequiredEvent: RecaptchaRequiredEvent) {
    RecaptchaProofBottomSheetFragment.show(childFragmentManager)
  }

  //endregion

  private inner class SearchEventListener : ConversationSearchBottomBar.EventListener {
    override fun onSearchMoveUpPressed() {
      searchViewModel.onMoveUp()
    }

    override fun onSearchMoveDownPressed() {
      searchViewModel.onMoveDown()
    }

    override fun onDatePickerSelected() {
      disposables += viewModel.getEarliestMessageSentDate().subscribe { earliestDate ->
        val local = LocalDateTime.now()
          .atMidnight()
          .atUTC()
          .toMillis()
        val datePicker =
          MaterialDatePicker.Builder
            .datePicker()
            .setTitleText(getString(R.string.ScheduleMessageTimePickerBottomSheet__select_date_title))
            .setSelection(local)
            .setCalendarConstraints(
              CalendarConstraints.Builder()
                .setValidator(viewModel.jumpToDateValidator)
                .setStart(earliestDate)
                .setEnd(local)
                .build()
            )
            .build()

        datePicker.addOnDismissListener {
          datePicker.clearOnDismissListeners()
          datePicker.clearOnPositiveButtonClickListeners()
        }

        datePicker.addOnPositiveButtonClickListener { selectedDate ->
          if (selectedDate != null) {
            val localMidnightTimestamp = Instant.ofEpochMilli(selectedDate)
              .atZone(ZoneOffset.UTC)
              .toLocalDate()
              .atStartOfDay(ZoneId.systemDefault())
              .toInstant()
              .toEpochMilli()

            disposables += viewModel
              .moveToDate(localMidnightTimestamp)
              .observeOn(AndroidSchedulers.mainThread())
              .subscribeBy { position ->
                moveToPosition(position - 1)
                closeChatSearch()
              }
          }
        }

        datePicker.show(childFragmentManager, "DATE_PICKER")
      }
    }
  }

  private inner class VoiceMessageRecordingSessionCallbacks : VoiceMessageRecordingDelegate.SessionCallback {
    override fun onSessionWillBegin() {
      getVoiceNoteMediaController().pausePlayback()
    }

    override fun sendVoiceNote(draft: VoiceNoteDraft) {
      val audioSlide = AudioSlide(draft.uri, draft.size, MediaUtil.AUDIO_AAC, true)

      sendMessageWithoutComposeInput(
        slide = audioSlide,
        quote = inputPanel.quote.orNull()
      )
    }

    override fun cancelEphemeralVoiceNoteDraft(draft: VoiceNoteDraft) {
      draftViewModel.cancelEphemeralVoiceNoteDraft(draft.asDraft())
    }

    override fun saveEphemeralVoiceNoteDraft(draft: VoiceNoteDraft) {
      draftViewModel.saveEphemeralVoiceNoteDraft(draft.asDraft())
    }
  }

  private inner class VoiceNotePlayerViewListener : VoiceNotePlayerView.Listener {
    override fun onCloseRequested(uri: Uri) {
      getVoiceNoteMediaController().stopPlaybackAndReset(uri)
    }

    override fun onSpeedChangeRequested(uri: Uri, speed: Float) {
      getVoiceNoteMediaController().setPlaybackSpeed(uri, speed)
    }

    override fun onPlay(uri: Uri, messageId: Long, position: Double) {
      getVoiceNoteMediaController().startSinglePlayback(uri, messageId, position)
    }

    override fun onPause(uri: Uri) {
      getVoiceNoteMediaController().pausePlayback(uri)
    }

    override fun onNavigateToMessage(threadId: Long, threadRecipientId: RecipientId, senderId: RecipientId, messageTimestamp: Long, messagePositionInThread: Long) {
      if (threadId != viewModel.threadId) {
        startActivity(
          ConversationIntents.createBuilderSync(requireActivity(), threadRecipientId, threadId)
            .withStartingPosition(messagePositionInThread.toInt())
            .build()
        )
      } else {
        viewModel
          .moveToMessage(messageTimestamp, senderId)
          .subscribeBy {
            moveToPosition(it)
          }
          .addTo(disposables)
      }
    }
  }

  override fun onDoubleTapEditEducationSheetNext(conversationMessage: ConversationMessage) {
    handleEditMessage(conversationMessage)
  }

  /**
   * Tracks the scroll position so that after collapsing/expanding, we can restore it properly
   */
  private data class CollapsibleEventScrollPosition(val position: Int, val top: Int, val height: Int)
}
