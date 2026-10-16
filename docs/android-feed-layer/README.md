# Android feed (动态) client layer — alignment with the iOS drafts

Branch `bot/android-feed-layer` (base `tellomi` @ 0967a0c). **New files + pure Kotlin only; no existing file is edited**, nothing is wired in.
Verification is local only (kotlinc + JUnit, see below). Not built with Gradle, no Android SDK, no device.

iOS reference: tellomi fork branch `bot/ios-feed-drafts` (`docs/drafts/ios-feed/`, specs I-0 … I-16) and
`IOS-ANDROID-CROSSCHECK.md` (A-1 … A-18). The iOS drafts were *ported from* Android, so Android already has almost every
pure-logic piece; this branch adds the pieces iOS has that Android lacks (found by reading each iOS spec's "differences" section
and comparing the Android sources).

## What is added

| File (main) | Test | What |
|---|---|---|
| `feed/FeedDetailEvents.kt` | `feed/FeedDetailEventsTest.kt` (13) | `open` / `dwell` / `quick_exit` / `share` events (iOS I-13). Android only had `impression`. `FeedEventSurface` (recommend, following, hot, search, profile), `FeedDetailEvents` builders, `FeedShareOutcome`, `FeedDetailEventTracker`. |
| `feed/net/FeedCapabilities.kt` | `feed/net/FeedCapabilitiesTest.kt` (15) | `GET /v1/capabilities` → `{"version":1,"features":[…]}` parsing + `FeedCapabilityChecker` (cache, single flight, timeout). Gate for the friend entry. New on both platforms. |
| `feed/net/FeedAuthBackoff.kt` | `feed/net/FeedAuthBackoffTest.kt` (10) | `feed/auth` "not deployed" classification + 10 min backoff wrapper (iOS I-0, A-3). |
| `docs/android-feed-layer/run-tests.sh` | | the local harness (below) |

### Event semantics (decisions of 2026-10-16, same as iOS)
- `open`: when a post/article detail opens, from any surface; no `value`.
- `dwell`: on leaving the detail (back, tab switch, app to background) **and** the visit lasted ≥ 30 s; `value` = seconds, clamped to 0…86400 (engine rejects the whole batch otherwise). Never for shorter visits (engine stores them with weight 0).
- `quick_exit`: leaving within 3 s of opening (3.000 s inclusive); `value` = actual seconds; exclusive with `dwell`; on by default (`reportsQuickExit = false` switches it off, then nothing is sent for a short visit). The engine does not enforce the 3 s, so the client does.
- 3–30 s: nothing beyond the `open`.
- `share`: only after the user *completed* a share (link copied / system sheet reports completed), and only for `shareable` cards. Reposting has its own endpoint (+5 interest as well) — do **not** also send `share` for it.
- One detail at a time: opening another closes the previous one "now" first. Backgrounding = leaving (call `closed`), foreground does not restart the timer and does not resend `open`. A clock that went backwards counts as 0 s.
- `surface` is the tab for list opens, `search` / `profile` when the detail was opened from search / a profile page.
- Events go into the existing `FeedEventBuffer` (flush at 50, every 30 s, on hide; `drain()` slices ≤ 50 — already on `tellomi` since #331).
- Not covered: the engine silently drops events older than 24 h or > 5 min in the future (`interactions.go:645`), so a device clock ≥ 5 min ahead loses every event on both platforms without an error. No client-side fix here (would need a server time source).

### Capabilities (contract update from the engine side)
Engine `GET /v1/capabilities` (content-engine `bot/engine-capabilities` d0a5a01), no auth, `Cache-Control: max-age=60`.
Feature names: `feed, events, posting, audience_tokens, comments, reactions, polls, friend_assertions, follow, blocks, profiles, search, topics, notifications, reports, voice_comments, media_upload, media_url` (constants in `FeedCapabilities`). There is **no** `friend_sync` / `friend_report`; **the friend entry is gated on `friend_assertions`**.
- Only a complete readable `200` counts. `404`, any other status, no body, oversized body (> 64 K chars), not JSON, not an object, `version` missing / not the integer `1` (a string `"1"`, `1.5`, `2`, `0` are all rejected) or `features` not an array → **all disabled**. Timeout (5 s) or any exception from the fetcher → all disabled. Non-string elements of `features` are ignored; names are compared exactly (case-sensitive); unknown names are kept and harmless; an empty list is a valid answer that enables nothing.
- Cache: ok and failure results are both kept 60 s (`OK_TTL_MILLIS`, `FAIL_TTL_MILLIS`, adjustable) to match `max-age=60` without asking on every foreground; a clock that went backwards counts as stale; concurrent callers share one request; a cancelled caller is not recorded as a failed check; `clear()` for logout / region switch.
- **Limit:** the endpoint does not cover the Signal-Server routes (`/v1/tellomi/feed/auth`, `friend-assertions`, `audience-lists`). `friend_assertions` says the *engine's* `/v1/feed/friends` exists; the 404 ambiguity of `POST …/friend-assertions` on Signal-Server (404 = peer has no account vs. route not deployed) is only reduced, not removed, until Signal-Server has its own signal or a distinguishable code. `FeedFriendSync` should additionally stay off while the `feed/auth` entry check has not passed (the token source will fail with `feed_auth_not_deployed`).
- No HTTP fetcher is included: whoever wires it chooses the client/host (engine base URL from `FeedSources`, no auth header needed) and returns `FeedCapabilitiesResponse(status, body)`; throw on I/O errors.

### feed/auth not deployed (A-3)
`FeedAuthFailure.classify`: `auth_unavailable` with status 404 / 405 / 501 → `feed_auth_not_deployed` (distinct from the engine's 404 `feed_hidden`); 401 / 403 / 429 / 5xx / I/O errors stay ordinary. `NotDeployedBackoffTokenSource(inner)` fails locally for 10 min after that (also for the `forceRefresh` retry after an engine 401); success or `resetBackoff()` ends it; backwards clock does not lock out; concurrent callers queue and see the backoff. `FeedEntryCheck` already keeps its remembered value for failures other than `feed_hidden`, so it needs no change.

## How to wire it (NOT done — each is an edit to an existing file, left to whoever owns it)
1. Detail screen (`detail/FeedPostDetailViewModel.kt` / `FeedViewModel`): keep one `FeedDetailEventTracker`; on open `tracker.opened(id, surface, System.currentTimeMillis()).forEach(events::add)` (same `FeedEventBuffer` the impressions use); on back / tab switch / `ON_STOP` `tracker.closed(now).forEach(events::add)`. Share: `FeedShareOutcome.COMPLETED/CANCELLED.event(id, surface, card.shareable, Instant.now())?.let(events::add)` in `FeedShare`.
2. Token source: `FeedSources` wraps `CachingFeedTokenSource.fromSignalServer(api)` in `NotDeployedBackoffTokenSource(...)`; call `resetBackoff()` and the inner `clear()` on logout / region switch (nothing calls `clear()` on logout today, iOS cross-check A-2).
3. Friend sync: `FeedFriendSync(enabled = { capabilityChecker.isEnabled(FeedCapabilities.FRIEND_ASSERTIONS) && …existing… })`, with `capabilityChecker.refreshIfStale()` before `syncIfStale()` on foreground; hide the add-friend entry the same way.

## Compared with iOS and found equal / not worth porting (no change)
Report flow (reasons table, evidence rules, caps 500/40/9), blocked keywords, feed settings, notification models, search / topic models, compose rules (poll, reply options, quote, article autosave), minor-mode limits (40 min / 22:00–06:00), post links `https://tell.cc/m#<id>` (Android has `TellomiLinks.postLink/parsePostId` + `FeedPostLinkViewModel`, the iOS I-16 is a port of it), friend sync, image upload, chrome state: the iOS drafts are ports of the Android code, rules identical (the iOS specs list only deliberate platform differences: Character vs UTF-16 counting, `UserDefaults` backup behaviour, token single-flight). Those Android classes pull in `R.string` / Compose / `SignalStore`, so they cannot be run in the Linux harness anyway.

## Android issues found, NOT fixed here (existing files; small follow-ups)
- **Notification settings saves can race**: `FeedNotificationSettingsViewModel.save` launches one coroutine per toggle and each PUTs the whole settings object, so two quick toggles can reach the engine out of order and the older one wins (iOS found this and serialises saves, I-7). Fix: a `Mutex` around `saveNotificationSettings` (`private val saveLock = Mutex()` … `saveLock.withLock { withContext(ioDispatcher) { source.saveNotificationSettings(after) } }`). The rollback logic ("only if still this value") is already right.
- **Emoji can be split by length caps**: `FeedShare` (60-char snippet) and the `take(n)` caps on report text / search query / appeal count UTF-16 units. Cutting between a surrogate pair leaves a broken character. iOS counts `Character`s. The engine's own cap unit should be checked before changing.
- `CachingFeedTokenSource.clear()` has no caller on logout (A-2); `FeedConfig` KDoc still says the engine has no `/config` and lacks `sensitive_more_available` / `rejection_reasons` (A-15).

## Verification (local only)
Harness: `docs/android-feed-layer/run-tests.sh <tree>` — kotlinc 2.2.20, JDK 21.0.12, JUnit 4.13.2, assertk 0.28.1, kotlinx-serialization-json-jvm 1.4.1 (only `parseToJsonElement` and the `Json*` element types, no compiler plugin), kotlinx-coroutines (kotlinc/lib). It compiles the **real** `FeedEvents.kt`, `FeedDetailEvents.kt`, `FeedCapabilities.kt`, `FeedAuthBackoff.kt` with the real tests; `FeedEventPayload`, `FeedApiException`, `FeedTokenSource`, `FeedApi.MAX_EVENTS` are cut out of the real `FeedModels.kt` / `FeedApi.kt` (annotations removed) and `FeedTab` is a stub (its `apiName` strings are grep-checked against the real file). The script fails on any compile error.

Results (each commit checked out in a clean worktree and run): `cef7096` **18 tests OK**; `b6aac44` **33 tests OK**; `a1f2ccd` **43 tests OK** (5 existing `FeedEventsTest` + 13 + 15 + 10). 25/25 consecutive runs of the 43 clean (concurrency / timeout tests included).
Mutations that the tests catch: `<=`→`<` (quick exit boundary), `>=`→`>` (dwell boundary), shareable guard removed, previous-close removed, clamp removed, version check removed, status check removed, element filter changed, backwards-clock rule removed (capabilities and backoff), cancellation handling removed, 405 dropped from the not-deployed set, success not ending the backoff. (Two mutants first survived — cancellation recorded as failure, success not clearing backoff — and got their own tests.)

## NOT verified
Gradle build / compile against the real app module, Android SDK / lint / ktlint, any device. JSON wire encoding of the new events (they use the existing `FeedEventPayload` serializer; `FeedApiTest` was not run). The real `/v1/capabilities` response (the parser is written against the documented shape and the engine's test fixture). Nothing is wired into any screen.

## Skipped (and why)
Attachment panel / sheet, media viewer, camera, conversation list, contact page into sheet, scheduled messages full page, MediaSendV3 caption input and Claude-owned docs: untouched. `.github/workflows`: untouched (no CI). No HTTP capabilities fetcher (host/auth decision belongs to the wiring). I-17 (strings) and share-to-story: iOS-side items, not applicable here.
