/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.asObservable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.ui.compose.split.ListDetailEvents
import org.signal.core.ui.compose.split.ListDetailNavigator
import org.signal.core.ui.compose.split.PaneAnchor
import org.signal.core.ui.compose.split.exitDetail
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.calls.log.CallLogRow
import org.thoughtcrime.securesms.components.settings.app.notifications.profiles.NotificationProfilesRepository
import org.thoughtcrime.securesms.components.snackbars.SnackbarStateConsumerRegistry
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.TellomiFeed
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.megaphone.Megaphone
import org.thoughtcrime.securesms.megaphone.Megaphones
import org.thoughtcrime.securesms.notifications.profiles.NotificationProfile
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.Stories
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

class MainNavigationViewModel(
  savedStateHandle: SavedStateHandle,
  initialListLocation: MainListRoute = MainListRoute.Chats
) : EventDrivenViewModel<MainNavigationEvents>(TAG, shouldLogEvents = false), MainNavigationEventSink {

  companion object {
    private val TAG = Log.tag(MainNavigationViewModel::class)
    private const val NAV_PREFETCH_TIMEOUT_MS = 250L
    private const val CHATS_BACK_STACK_KEY = "chats_back_stack_v2"
    private const val CALLS_BACK_STACK_KEY = "calls_back_stack_v2"
    private const val STORIES_BACK_STACK_KEY = "stories_back_stack_v2"
    private const val FEED_BACK_STACK_KEY = "tellomi_feed_back_stack"
    /**
     * Tellomi：会话压在哪个栈上，由来源决定、不看当前 Tab：只有好友页里点人打开的压在「好友」上，
     * 别的（通知、分享、深链接……）一律压在「聊天」上。
     */
    fun chatsDetailRoot(fromFriendsTab: Boolean): MainListRoute {
      return if (fromFriendsTab) MainListRoute.Friends else MainListRoute.Chats
    }

    /** Tellomi：压到 [target] 之前要不要先切 Tab。站在「好友」上、却要压到「聊天」时先切到聊天，免得会话压在看不见的栈上。 */
    fun shouldSwitchToChatsFirst(currentRoot: MainListRoute, target: MainListRoute): Boolean {
      return target == MainListRoute.Chats && currentRoot == MainListRoute.Friends
    }

    private const val FRIENDS_BACK_STACK_KEY = "tellomi_friends_back_stack"
    private const val ME_BACK_STACK_KEY = "tellomi_me_back_stack"
  }

  class Factory(
    private val initialListLocation: MainListRoute = MainListRoute.Chats
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
      val savedStateHandle = extras.createSavedStateHandle()
      @Suppress("UNCHECKED_CAST")
      return MainNavigationViewModel(savedStateHandle, initialListLocation) as T
    }
  }

  private val megaphoneRepository = AppDependencies.megaphoneRepository

  private var captureChatListSnapshot: (suspend () -> Unit)? = null

  /**
   * The stacks behind the main window, one per tab. The archive gets no stack of its own: it is a list
   * pushed onto the chats stack, so that opening it keeps whatever chat was open beside it.
   */
  val navigator = ListDetailNavigator<MainListRoute, MainDetailRoute>(
    savedStateHandle = savedStateHandle,
    scope = viewModelScope,
    stackKeys = mapOf(
      MainListRoute.Feed to FEED_BACK_STACK_KEY,
      MainListRoute.Chats to CHATS_BACK_STACK_KEY,
      MainListRoute.Calls to CALLS_BACK_STACK_KEY,
      MainListRoute.Stories to STORIES_BACK_STACK_KEY,
      MainListRoute.Friends to FRIENDS_BACK_STACK_KEY,
      MainListRoute.Me to ME_BACK_STACK_KEY
    ),
    initialRoot = initialListLocation.tab
  )

  /** The currently selected tab. */
  val currentTab: StateFlow<MainListRoute> = navigator.currentRoot

  /** How a split-pane window currently divides the list and detail panes. */
  val paneAnchor: StateFlow<PaneAnchor> = navigator.paneAnchor

  /** Whether one pane currently occupies the whole window. Only meaningful in split-pane layouts. */
  val isFullScreenPane: StateFlow<Boolean> = navigator.isFullScreenPane

  // Tellomi：「好友」栈上开着的会话也算当前会话（通知据此不重复提醒），按当前 Tab 取那一栈
  val observableActiveRecipientId: Observable<Optional<out RecipientId>> =
    combine(
      navigator.snapshotsOf(MainListRoute.Chats) { activeRecipientId },
      navigator.snapshotsOf(MainListRoute.Friends) { activeRecipientId },
      navigator.currentRoot
    ) { chats, friends, root -> if (root == MainListRoute.Friends) friends else chats }
      .combine(isFullScreenPane) { id, expanded -> if (expanded) Optional.ofNullable(null) else Optional.ofNullable(id) }
      .asObservable()

  val observableActiveCallId: Observable<Optional<out CallLogRow.Id>> =
    navigator.snapshotsOf(MainListRoute.Calls) { activeCallId }
      .combine(isFullScreenPane) { id, expanded -> if (expanded) Optional.ofNullable(null) else Optional.ofNullable(id) }
      .asObservable()

  private val internalMegaphone = MutableStateFlow(Megaphone.NONE)
  val megaphone: StateFlow<Megaphone> = internalMegaphone

  private val internalNavigationEvents = MutableSharedFlow<NavigationEvent>()
  val navigationEvents: Flow<NavigationEvent> = internalNavigationEvents

  private val notificationProfilesRepository: NotificationProfilesRepository = NotificationProfilesRepository()

  private val internalMainNavigationBarState = MutableStateFlow(MainNavigationBarState(currentListLocation = initialListLocation))
  val mainNavigationBarState: StateFlow<MainNavigationBarState> = combine(internalMainNavigationBarState, navigator.displayedList) { state, listLocation ->
    state.copy(currentListLocation = listLocation)
  }.stateIn(viewModelScope, SharingStarted.Eagerly, MainNavigationBarState(currentListLocation = initialListLocation))

  /**
   * The detail content displayed above the current list, or null when the list is showing on its own.
   */
  val detailLocation: StateFlow<MainDetailRoute?> = navigator.detail

  /**
   * Whether the current tab is displaying detail content.
   */
  val hasDetailContent: StateFlow<Boolean> = navigator.hasDetail

  /**
   * This is Rx because these are still accessed from Java.
   */
  private val internalTabClickEvents: MutableSharedFlow<MainListRoute> = MutableSharedFlow()
  val tabClickEventsObservable: Observable<MainListRoute> = internalTabClickEvents.asObservable()

  val snackbarRegistry = SnackbarStateConsumerRegistry()

  init {
    performStoreUpdate(MainNavigationRepository.getNumberOfUnreadMessages()) { unreadChats, state ->
      state.copy(chatsCount = unreadChats.toInt())
    }

    performStoreUpdate(MainNavigationRepository.getNumberOfUnseenCalls()) { unseenCalls, state ->
      state.copy(callsCount = unseenCalls.toInt())
    }

    performStoreUpdate(MainNavigationRepository.getNumberOfUnseenStories()) { unseenStories, state ->
      state.copy(storiesCount = unseenStories.toInt())
    }

    performStoreUpdate(MainNavigationRepository.getHasFailedOutgoingStories()) { hasFailedStories, state ->
      state.copy(storyFailure = hasFailedStories)
    }
  }

  override suspend fun processEvent(event: MainNavigationEvents) {
    when (event) {
      is MainNavigationEvents.GoToTab -> goToTab(event.tab)
      is MainNavigationEvents.GoToList -> goToList(event.route)
      is MainNavigationEvents.GoToDetail -> goToDetail(event.route)
      is MainNavigationEvents.GoToConversationFromFriends -> goToConversation(event.route, fromFriendsTab = true)
      MainNavigationEvents.ExitDetail -> navigator.processEvent(ListDetailEvents.ExitDetail)
      MainNavigationEvents.GoToCameraFirstStoryCapture -> internalNavigationEvents.emit(NavigationEvent.STORY_CAMERA_FIRST)
      MainNavigationEvents.RefreshNavigationBar -> refreshNavigationBarState()
      MainNavigationEvents.RequestNextMegaphone -> requestNextMegaphone()
      is MainNavigationEvents.MegaphoneVisible -> megaphoneRepository.markVisible(event.megaphone.event)
      is MainNavigationEvents.MegaphoneSnoozed -> onMegaphoneSnoozed(event.event)
      is MainNavigationEvents.MegaphoneCompleted -> onMegaphoneCompleted(event.event)
      is MainNavigationEvents.ListDetailEvent -> navigator.processEvent(event.event)
    }
  }

  /** Set from the MainActivity composition, and cleared when it is disposed. */
  fun setChatListSnapshotCaptureProvider(capture: (suspend () -> Unit)?) {
    captureChatListSnapshot = capture
  }

  /**
   * Clicking the tab already displayed reveals its list and tells that tab's screen about the click —
   * scrolling to the top, and the like — rather than navigating anywhere.
   */
  private suspend fun goToTab(tab: MainListRoute) {
    if (navigator.displayedList.value == tab) {
      navigator.processEvent(ListDetailEvents.RevealList)
      internalTabClickEvents.emit(tab)
    } else {
      goToList(tab)
    }
  }

  /** Switching tabs only changes which stack is displayed; each tab comes back to whatever it had open. */
  private fun goToList(route: MainListRoute) {
    navigator.processEvent(
      ListDetailEvents.GoToList(
        listRoute = route,
        root = route.tab,
        push = route == MainListRoute.Archive
      )
    )
  }

  private suspend fun goToDetail(route: MainDetailRoute) {
    when (route) {
      is MainDetailRoute.Chats -> pushChatsDetailLocation(route)
      is MainDetailRoute.Conversation -> goToConversation(route)
      is MainDetailRoute.Calls, is MainDetailRoute.CallLinkDetails -> pushCallsDetailLocation(route)
      is MainDetailRoute.Stories -> navigator.processEvent(ListDetailEvents.Push(route, MainListRoute.Stories))
    }
  }

  private suspend fun MainDetailRoute.Conversation.withPreloadedWallpaper(): MainDetailRoute.Conversation {
    val args = conversationArgs
    val liveRecipient = Recipient.live(args.recipientId)
    val recipientSnapshot = liveRecipient.get()
    val wallpaper = recipientSnapshot.wallpaper

    val updatedArgs = if (recipientSnapshot.isResolving || (wallpaper?.isPhoto == true && !wallpaper.isPrefetched)) {
      withTimeoutOrNull(NAV_PREFETCH_TIMEOUT_MS.milliseconds) {
        withContext(Dispatchers.Default) {
          val freshWallpaper = liveRecipient.resolve().wallpaper
          if (freshWallpaper?.prefetch(AppDependencies.application, NAV_PREFETCH_TIMEOUT_MS) == false) {
            Log.w(TAG, "[goToConversation] Failed to prefetch wallpaper.")
          }
          args.copy(hasWallpaper = freshWallpaper != null)
        }
      } ?: run {
        Log.w(TAG, "[goToConversation] Timed out resolving recipient/wallpaper. Navigating without prefetch.")
        args
      }
    } else {
      args.copy(hasWallpaper = wallpaper != null)
    }

    return copy(conversationArgs = updatedArgs)
  }

  private suspend fun goToConversation(location: MainDetailRoute.Conversation, fromFriendsTab: Boolean = false) {
    val captureSnapshot = captureChatListSnapshot

    if (captureSnapshot == null) {
      // share intent or process restore - there's no chat-list snapshot to capture and no need to preload a wallpaper
      pushChatsDetailLocation(location, fromFriendsTab)
    } else {
      captureSnapshot()
      pushChatsDetailLocation(location.withPreloadedWallpaper(), fromFriendsTab)
    }
  }

  private fun pushChatsDetailLocation(location: MainDetailRoute, fromFriendsTab: Boolean = false) {
    val root = chatsDetailRoot(fromFriendsTab)
    if (shouldSwitchToChatsFirst(navigator.currentRoot.value, root)) {
      goToList(MainListRoute.Chats)
    }

    // Tellomi：同一个会话不在两个栈上各开一份——另一个栈顶上开着同一个人，先把它收掉
    val otherRoot = if (root == MainListRoute.Friends) MainListRoute.Chats else MainListRoute.Friends
    val otherBackStack = navigator[otherRoot]
    if (location is MainDetailRoute.Chats && otherBackStack.activeRecipientId == location.controllerKey) {
      otherBackStack.exitDetail()
    }

    val chatsBackStack = navigator[root]
    if (location is MainDetailRoute.Chats && chatsBackStack.activeRecipientId != location.controllerKey) {
      chatsBackStack.exitDetail()
    }

    navigator.processEvent(ListDetailEvents.Push(location, root))
  }

  private fun pushCallsDetailLocation(location: MainDetailRoute) {
    val callsBackStack = navigator[MainListRoute.Calls]
    if (location is MainDetailRoute.Calls && callsBackStack.activeCallId != location.controllerKey) {
      callsBackStack.exitDetail()
    }

    navigator.processEvent(ListDetailEvents.Push(location, MainListRoute.Calls))
  }

  private fun requestNextMegaphone() {
    megaphoneRepository.getNextMegaphone { next ->
      internalMegaphone.update { next ?: Megaphone.NONE }
    }
  }

  private fun onMegaphoneSnoozed(event: Megaphones.Event) {
    megaphoneRepository.markInteractedWith(event)
    internalMegaphone.update { Megaphone.NONE }
  }

  private fun onMegaphoneCompleted(event: Megaphones.Event) {
    internalMegaphone.update { Megaphone.NONE }
    megaphoneRepository.markFinished(event)
  }

  private fun refreshNavigationBarState() {
    internalMainNavigationBarState.update {
      it.copy(
        compact = SignalStore.settings.useCompactNavigationBar,
        destinations = MainNavigationBarState.tabsToShow(
          areStoriesEnabled = Stories.isFeatureEnabled(),
          isFeedEnabled = TellomiFeed.isTabEnabled(),
          showsFriendsAndMe = TellomiFeed.isFriendsAndMeTabsEnabled()
        )
      )
    }
  }

  fun getNotificationProfiles(): Flow<List<NotificationProfile>> {
    return notificationProfilesRepository.getProfiles().asFlow()
  }

  private fun <T : Any> performStoreUpdate(flow: Flow<T>, fn: (T, MainNavigationBarState) -> MainNavigationBarState) {
    viewModelScope.launch {
      flow.collectLatest { item ->
        internalMainNavigationBarState.update { state -> fn(item, state) }
      }
    }
  }

  enum class NavigationEvent {
    STORY_CAMERA_FIRST
  }
}
