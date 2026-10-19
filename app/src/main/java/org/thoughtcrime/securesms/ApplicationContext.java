/*
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.bumptech.glide.Glide;
import com.google.android.gms.security.ProviderInstaller;

import net.zetetic.database.Logger;

import org.conscrypt.ConscryptSignal;
import org.greenrobot.eventbus.EventBus;
import org.signal.aesgcmprovider.AesGcmProvider;
import org.signal.core.util.AppForegroundObserver;
import org.signal.core.util.DiskUtil;
import org.signal.core.util.MemoryTracker;
import org.signal.core.util.PartAuthorityUris;
import org.signal.core.util.Util;
import org.signal.core.util.concurrent.AnrDetector;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.crypto.AttachmentSecretProvider;
import org.signal.core.util.logging.AndroidLogger;
import org.signal.core.util.logging.Log;
import org.signal.core.util.logging.Scrubber;
import org.signal.core.util.tracing.Tracer;
import org.signal.glide.SignalGlideCodecs;
import org.signal.libsignal.net.ChatServiceException;
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider;
import org.signal.registration.RegistrationDependencies;
import org.signal.ringrtc.CallManager;
import org.thoughtcrime.securesms.apkupdate.ApkUpdateRefreshListener;
import org.thoughtcrime.securesms.avatar.AvatarPickerStorage;
import org.thoughtcrime.securesms.backup.v2.BackupRepository;
import org.thoughtcrime.securesms.clockskew.ClockSkewDetector;
import org.thoughtcrime.securesms.contacts.index.ContactIndexRepository;
import org.thoughtcrime.securesms.preferences.EditProxyActivity;
import org.thoughtcrime.securesms.conversation.drafts.DraftBlobs;
import org.thoughtcrime.securesms.crypto.AppAttachmentSecretStore;
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider;
import org.thoughtcrime.securesms.database.LogDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider;
import org.signal.emoji.EmojiSource;
import org.signal.emoji.JumboEmoji;
import org.thoughtcrime.securesms.gcm.FcmFetchManager;
import org.thoughtcrime.securesms.glide.SignalGlideComponents;
import org.thoughtcrime.securesms.jobmanager.impl.SealedSenderConstraint;
import org.thoughtcrime.securesms.jobs.AccountConsistencyWorkerJob;
import org.thoughtcrime.securesms.jobs.BackupRefreshJob;
import org.thoughtcrime.securesms.jobs.BackupSubscriptionCheckJob;
import org.thoughtcrime.securesms.jobs.BuildExpirationConfirmationJob;
import org.thoughtcrime.securesms.jobs.CallingAssetsDownloadJob;
import org.thoughtcrime.securesms.jobs.CheckKeyTransparencyJob;
import org.thoughtcrime.securesms.jobs.CheckServiceReachabilityJob;
import org.thoughtcrime.securesms.jobs.DownloadLatestEmojiDataJob;
import org.thoughtcrime.securesms.jobs.EmojiSearchIndexDownloadJob;
import org.thoughtcrime.securesms.jobs.FcmRefreshJob;
import org.thoughtcrime.securesms.jobs.FontDownloaderJob;
import org.thoughtcrime.securesms.jobs.GroupRingCleanupJob;
import org.thoughtcrime.securesms.jobs.GroupV2UpdateSelfProfileKeyJob;
import org.thoughtcrime.securesms.jobs.InAppPaymentAuthCheckJob;
import org.thoughtcrime.securesms.jobs.InAppPaymentKeepAliveJob;
import org.thoughtcrime.securesms.jobs.LinkedDeviceInactiveCheckJob;
import org.thoughtcrime.securesms.jobs.MessageSendLogCleanupJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob;
import org.thoughtcrime.securesms.jobs.ProfileUploadJob;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.jobs.RefreshSvrCredentialsJob;
import org.thoughtcrime.securesms.jobs.RestoreOptimizedMediaJob;
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob;
import org.thoughtcrime.securesms.jobs.RetrieveRemoteAnnouncementsJob;
import org.thoughtcrime.securesms.jobs.StoryOnboardingDownloadJob;
import org.thoughtcrime.securesms.keyvalue.KeepMessagesDuration;
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore;
import org.thoughtcrime.securesms.keyvalue.SettingsValues;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.logging.CustomSignalProtocolLogger;
import org.thoughtcrime.securesms.logging.PersistentLogger;
import org.thoughtcrime.securesms.logsubmit.SubmitDebugLogActivity;
import org.thoughtcrime.securesms.messageprocessingalarm.RoutineMessageFetchReceiver;
import org.thoughtcrime.securesms.messages.IncomingMessageObserver;
import org.thoughtcrime.securesms.migrations.ApplicationMigrations;
import org.thoughtcrime.securesms.mms.SignalGlideModule;
import org.thoughtcrime.securesms.net.TellomiCrossBorderNetworkGate;
import org.thoughtcrime.securesms.region.TellomiRegionAutoSwitchRunner;
import org.thoughtcrime.securesms.ratelimit.RateLimitUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.registration.util.RegistrationUtil;
import org.thoughtcrime.securesms.registration.v2.AppContactSupportController;
import org.thoughtcrime.securesms.registration.v2.AppRegistrationNetworkController;
import org.thoughtcrime.securesms.registration.v2.AppRegistrationStorageController;
import org.thoughtcrime.securesms.restriction.TellomiRestrictions;
import org.thoughtcrime.securesms.ringrtc.RingRtcLogger;
import org.thoughtcrime.securesms.service.AnalyzeDatabaseAlarmListener;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.service.LocalBackupListener;
import org.thoughtcrime.securesms.service.MessageBackupListener;
import org.thoughtcrime.securesms.service.RotateSenderCertificateListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.service.webrtc.ActiveCallManager;
import org.thoughtcrime.securesms.service.webrtc.AndroidTelecomUtil;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.updaterequired.UpdateManifests;
import org.thoughtcrime.securesms.util.AppStartup;
import org.thoughtcrime.securesms.util.BatterySnapshotTracker;
import org.signal.core.util.DeviceProperties;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.Environment;
import org.signal.core.util.PlayServicesUtil;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.SignalUncaughtExceptionHandler;
import org.thoughtcrime.securesms.util.SqlCipherLogTarget;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.VersionTracker;
import org.thoughtcrime.securesms.util.dynamiclanguage.DynamicLanguageContextWrapper;
import org.whispersystems.signalservice.api.websocket.SignalWebSocket;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import kotlin.Unit;
import rxdogtag2.RxDogTag;

/**
 * Will be called once when the TextSecure process is created.
 * <p>
 * We're using this as an insertion point to patch up the Android PRNG disaster,
 * to initialize the job manager, and to check for GCM registration freshness.
 *
 * @author Moxie Marlinspike
 */
public class ApplicationContext extends Application implements AppForegroundObserver.Listener {

  private static final String TAG = Log.tag(ApplicationContext.class);

  public static ApplicationContext getInstance(Context context) {
    return (ApplicationContext) context.getApplicationContext();
  }

  @Override
  public void onCreate() {
    PartAuthorityUris.init(BuildConfig.APPLICATION_ID);

    Tracer.getInstance().start("Application#onCreate()");
    AppStartup.getInstance().onApplicationCreate();
    SignalLocalMetrics.ColdStart.start();

    long startTime = System.currentTimeMillis();

    super.onCreate();

    AppStartup.getInstance().addBlocking("sqlcipher-init", () -> {
                SqlCipherLibraryLoader.load();
                SignalDatabase.init(this,
                                    DatabaseSecretProvider.getOrCreateDatabaseSecret(this),
                                    AttachmentSecretProvider.getInstance(this, AppAttachmentSecretStore.INSTANCE).getOrCreateAttachmentSecret());
                Logger.setTarget(SqlCipherLogTarget.INSTANCE);
              })
              .addBlocking("signal-store", () -> SignalStore.init(this))
              .addBlocking("logging", () -> {
                initializeLogging();
                Log.i(TAG, "onCreate()");
              })
              .addBlocking("security-provider", this::initializeSecurityProvider)
              .addBlocking("app-dependencies", this::initializeAppDependencies)
              .addBlocking("tellomi-cross-border-gate", () -> TellomiCrossBorderNetworkGate.install(this))
              .addBlocking("anr-detector", this::startAnrDetector)
              .addBlocking("crash-handling", this::initializeCrashHandling)
              .addBlocking("rx-init", this::initializeRx)
              .addBlocking("event-bus", () -> EventBus.builder().logNoSubscriberMessages(false).installDefaultEventBus())
              .addBlocking("scrubber", () -> Scrubber.setIdentifierHmacKeyProvider(() -> SignalStore.svr().getMasterKey().deriveLoggingKey()))
              .addBlocking("first-launch", this::initializeFirstEverAppLaunch)
              .addBlocking("app-migrations", this::initializeApplicationMigrations)
              .addBlocking("lifecycle-observer", () -> AppForegroundObserver.addListener(this))
              .addBlocking("message-retriever", this::initializeMessageRetrieval)
              .addBlocking("dynamic-theme", () -> DynamicTheme.setDefaultDayNightMode(this))
              .addBlocking("proxy-init", () -> {
                if (SignalStore.proxy().isProxyEnabled()) {
                  Log.w(TAG, "Proxy detected. Enabling Conscrypt.setUseEngineSocketByDefault()");
                  ConscryptSignal.setUseEngineSocketByDefault(true);
                }
              })
              .addBlocking("blob-provider", this::initializeBlobProvider)
              .addBlocking("remote-config", RemoteConfig::init)
              .addBlocking("ring-rtc", this::initializeRingRtc)
              .addBlocking("glide", () -> SignalGlideModule.setRegisterGlideComponents(new SignalGlideComponents()))
              .addBlocking("tracer", this::initializeTracer)
              .addNonBlocking(() -> RegistrationUtil.maybeMarkRegistrationComplete())
              .addNonBlocking(() -> Glide.get(this))
              .addNonBlocking(this::cleanAvatarStorage)
              .addNonBlocking(this::initializeRevealableMessageManager)
              .addNonBlocking(this::initializePendingRetryReceiptManager)
              .addNonBlocking(this::initializeScheduledMessageManager)
              .addNonBlocking(this::initializeFcmCheck)
              .addNonBlocking(PreKeysSyncJob::enqueueIfNeeded)
              .addNonBlocking(this::initializePeriodicTasks)
              .addNonBlocking(this::initializeCircumvention)
              .addNonBlocking(this::initializeCleanup)
              .addNonBlocking(this::initializeGlideCodecs)
              .addNonBlocking(SealedSenderConstraint::checkAndSetValidity)
              .addNonBlocking(StorageSyncHelper::scheduleRoutineSync)
              .addNonBlocking(this::beginJobLoop)
              .addNonBlocking(EmojiSource::refresh)
              .addNonBlocking(() -> AppDependencies.getGiphyMp4Cache().onAppStart(this))
              .addNonBlocking(AppDependencies::getBillingApi)
              .addNonBlocking(this::ensureProfileUploaded)
              .addNonBlocking(() -> AppDependencies.getExpireStoriesManager().scheduleIfNecessary())
              .addNonBlocking(BackupRepository::maybeFixAnyDanglingUploadProgress)
              .addPostRender(() -> AppDependencies.getDeletedCallEventManager().scheduleIfNecessary())
              .addPostRender(() -> RateLimitUtil.retryAllRateLimitedMessages(this))
              .addPostRender(this::initializeExpiringMessageManager)
              .addPostRender(this::initializeTrimThreadsByDateManager)
              .addPostRender(RefreshSvrCredentialsJob::enqueueIfNecessary)
              .addPostRender(() -> DownloadLatestEmojiDataJob.scheduleIfNecessary(this))
              .addPostRender(EmojiSearchIndexDownloadJob::scheduleIfNecessary)
              .addPostRender(MessageSendLogCleanupJob::enqueue)
              .addPostRender(() -> ContactIndexRepository.deleteAbandonedIndex(this))
              .addPostRender(() -> JumboEmoji.updateCurrentVersion(this))
              .addPostRender(RetrieveRemoteAnnouncementsJob::enqueue)
              .addPostRender(AndroidTelecomUtil::registerPhoneAccount)
              .addPostRender(() -> AppDependencies.getJobManager().add(new FontDownloaderJob()))
              .addPostRender(() -> AppDependencies.getJobManager().add(new CallingAssetsDownloadJob()))
              .addPostRender(CheckServiceReachabilityJob::enqueueIfNecessary)
              .addPostRender(GroupV2UpdateSelfProfileKeyJob::enqueueForGroupsIfNecessary)
              .addPostRender(StoryOnboardingDownloadJob.Companion::enqueueIfNeeded)
              .addPostRender(() -> AppDependencies.getExoPlayerPool().getPoolStats().getMaxUnreserved())
              .addPostRender(() -> AppDependencies.getRecipientCache().warmUp())
              .addPostRender(AccountConsistencyWorkerJob::enqueueIfNecessary)
              .addPostRender(GroupRingCleanupJob::enqueue)
              .addPostRender(LinkedDeviceInactiveCheckJob::enqueueIfNecessary)
              .addPostRender(() -> ActiveCallManager.clearNotifications(this))
              .addPostRender(RestoreOptimizedMediaJob::enqueueIfNecessary)
              .addPostRender(() -> AppDependencies.getPinnedMessageManager().scheduleIfNecessary())
              .addPostRender(() -> AppDependencies.getUnreadReminderManager().scheduleIfNecessary())
              .execute();

    Log.d(TAG, "onCreate() took " + (System.currentTimeMillis() - startTime) + " ms");
    SignalLocalMetrics.ColdStart.onApplicationCreateFinished();
    Tracer.getInstance().end("Application#onCreate()");
  }

  @Override
  public void onForeground() {
    long startTime = System.currentTimeMillis();
    Log.i(TAG, "App is now visible. Battery: " + DeviceProperties.getBatteryLevel(this) + "% (charging: " + DeviceProperties.isCharging(this) + ")");

    BatterySnapshotTracker.emit(this, "foreground");

    AppDependencies.getFrameRateTracker().start();
    AppDependencies.getMegaphoneRepository().onAppForegrounded();
    AppDependencies.getDeadlockDetector().start();
    InAppPaymentKeepAliveJob.enqueueAndTrackTimeIfNecessary();
    FcmFetchManager.onForeground(this);
    startAnrDetector();

    SignalExecutors.BOUNDED.execute(() -> {
      BackupRefreshJob.enqueueIfNecessary();
      InAppPaymentAuthCheckJob.enqueueIfNeeded();
      RemoteConfig.refreshIfNecessary();
      TellomiRegionAutoSwitchRunner.evaluateAsync("foreground"); // Tellomi（REGION_PROFILE 第六节之二）：键不存在且在 global 时不碰网络
      RetrieveProfileJob.enqueueRoutineFetchIfNecessary();
      executePendingContactSync();
      KeyCachingService.onAppForegrounded(this);
      AppDependencies.getShakeToReport().enable();
      checkBuildExpiration();
      UpdateManifests.onForeground();
      TellomiRestrictions.onForeground(); // Tellomi（ADR-0078 §3.4）：至多每 6 小时问一次有没有人工处置
      checkFreeDiskSpace();
      MemoryTracker.start();
      BackupSubscriptionCheckJob.enqueueIfAble();
      CheckKeyTransparencyJob.enqueueIfNecessary(true, false);
      AppDependencies.getAuthWebSocket().registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE);
      AppDependencies.getUnauthWebSocket().registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE);

      long lastForegroundTime = SignalStore.misc().getLastForegroundTime();
      long currentTime        = System.currentTimeMillis();
      long timeDiff           = currentTime - lastForegroundTime;

      if (timeDiff < 0) {
        Log.w(TAG, "Time travel! The system clock has moved backwards. (currentTime: " + currentTime + " ms, lastForegroundTime: " + lastForegroundTime + " ms, diff: " + timeDiff + " ms)", true);
      }

      SignalStore.misc().setLastForegroundTime(currentTime);
    });

    Log.d(TAG, "onStart() took " + (System.currentTimeMillis() - startTime) + " ms");
  }

  @Override
  public void onBackground() {
    Log.i(TAG, "App is no longer visible.");
    BatterySnapshotTracker.emit(this, "background");
    KeyCachingService.onAppBackgrounded(this);
    AppDependencies.getMessageNotifier().clearVisibleThread();
    AppDependencies.getFrameRateTracker().stop();
    AppDependencies.getShakeToReport().disable();
    AppDependencies.getDeadlockDetector().stop();
    AppDependencies.getAuthWebSocket().removeKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE);
    AppDependencies.getUnauthWebSocket().removeKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE);
    MemoryTracker.stop();
    AnrDetector.stop();
  }

  public void checkBuildExpiration() {
    if (Util.getTimeUntilBuildExpiry(SignalStore.misc().getEstimatedServerTime()) <= 0 && !SignalStore.misc().isClientDeprecated()) {
      Log.w(TAG, "Build potentially expired! Enqueing job to check.", true);
      AppDependencies.getJobManager().add(new BuildExpirationConfirmationJob());
    }
  }

  public void checkFreeDiskSpace() {
    long availableBytes = DiskUtil.getAvailableSpace(getApplicationContext()).getBytes();
    SignalStore.backup().setSpaceAvailableOnDiskBytes(availableBytes);
  }

  /**
   * Note: this is purposefully "started" twice -- once during application create, and once during foreground.
   * This is so we can capture ANR's that happen on boot before the foreground event.
   */
  private void startAnrDetector() {
    AnrDetector.start(TimeUnit.SECONDS.toMillis(5), () -> RemoteConfig.internalUser() && SignalStore.internal().getAnrDetectionCrashes(), (dumps) -> {
      LogDatabase.getInstance(this).anrs().save(System.currentTimeMillis(), dumps);
      return Unit.INSTANCE;
    });
  }

  private void initializeSecurityProvider() {
    int aesPosition = Security.insertProviderAt(new AesGcmProvider(), 1);
    Log.i(TAG, "Installed AesGcmProvider: " + aesPosition);

    if (aesPosition < 0) {
      Log.e(TAG, "Failed to install AesGcmProvider()");
      throw new ProviderInitializationException();
    }

    int conscryptPosition = Security.insertProviderAt(ConscryptSignal.newProvider(), 2);
    Log.i(TAG, "Installed Conscrypt provider: " + conscryptPosition);

    if (conscryptPosition < 0) {
      Log.w(TAG, "Did not install Conscrypt provider. May already be present.");
    }
  }

  @VisibleForTesting
  protected void initializeLogging() {
    Log.initialize(RemoteConfig::internalUser, AndroidLogger.INSTANCE, PersistentLogger.getInstance(this));

    SignalProtocolLoggerProvider.setProvider(new CustomSignalProtocolLogger());
    SignalProtocolLoggerProvider.initializeLogging(BuildConfig.LIBSIGNAL_LOG_LEVEL);

    SignalExecutors.UNBOUNDED.execute(() -> {
      Log.blockUntilAllWritesFinished();
      LogDatabase.getInstance(this).logs().trimToSize();
      LogDatabase.getInstance(this).crashes().trimToSize();
    });
  }

  private void initializeCrashHandling() {
    final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(new SignalUncaughtExceptionHandler(originalHandler));
  }

  private void initializeRx() {
    RxDogTag.install();
    RxJavaPlugins.setInitIoSchedulerHandler(schedulerSupplier -> Schedulers.from(SignalExecutors.UNBOUNDED, true, false));
    RxJavaPlugins.setInitComputationSchedulerHandler(schedulerSupplier -> Schedulers.from(SignalExecutors.BOUNDED, true, false));
    RxJavaPlugins.setErrorHandler(e -> {
      boolean wasWrapped = false;
      while ((e instanceof UndeliverableException || e instanceof AssertionError || e instanceof OnErrorNotImplementedException) && e.getCause() != null) {
        wasWrapped = true;
        e = e.getCause();
      }

      if (wasWrapped && (e instanceof SocketException || e instanceof InterruptedException || e instanceof InterruptedIOException || e instanceof ChatServiceException)) {
        return;
      }

      Log.e(TAG, "RxJava error handler invoked", e);

      Thread.UncaughtExceptionHandler uncaughtExceptionHandler = Thread.currentThread().getUncaughtExceptionHandler();
      if (uncaughtExceptionHandler == null) {
        uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
      }

      uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
    });
  }

  private void initializeApplicationMigrations() {
    ApplicationMigrations.onApplicationCreate(this, AppDependencies.getJobManager());
  }

  public void initializeMessageRetrieval() {
    SignalExecutors.UNBOUNDED.execute(AppDependencies::startNetwork);
  }

  @VisibleForTesting
  void initializeAppDependencies() {
    if (!AppDependencies.isInitialized()) {
      Log.i(TAG, "Initializing AppDependencies.");
      AppDependencies.init(this, new ApplicationDependencyProvider(this));
    }
    AppForegroundObserver.begin();
    ClockSkewDetector.beginObserving(this);

    if (Environment.USE_NEW_REGISTRATION) {
      initializeRegistrationDependencies();
    }
  }

  private void initializeRegistrationDependencies() {
    RegistrationDependencies.provide(
      new RegistrationDependencies(
        new AppRegistrationNetworkController(this, AppDependencies.getRegistrationApiV2()),
        new AppRegistrationStorageController(this),
        Environment.IS_LINK_AND_SYNC_AVAILABLE,
        Environment.PHONENUMBERLESS_REGISTRATION,
        Environment.supportsGooglePlayBilling(),
        null,
        context -> {
          context.startActivity(new Intent(context, SubmitDebugLogActivity.class));
          return Unit.INSTANCE;
        },
        context -> {
          context.startActivity(EditProxyActivity.intent(context));
          return Unit.INSTANCE;
        },
        new AppContactSupportController()
      )
    );
  }

  private void initializeFirstEverAppLaunch() {
    if (TextSecurePreferences.getFirstInstallVersion(this) == -1) {
      if (!SignalDatabase.databaseFileExists(this) || VersionTracker.getDaysSinceFirstInstalled(this) < 365) {
        Log.i(TAG, "First ever app launch!");
        AppInitialization.onFirstEverAppLaunch(this);
      }

      Log.i(TAG, "Setting first install version to " + BuildConfig.CANONICAL_VERSION_CODE);
      TextSecurePreferences.setFirstInstallVersion(this, BuildConfig.CANONICAL_VERSION_CODE);
    } else if (!SignalStore.settings().getPassphraseDisabled() && VersionTracker.getDaysSinceFirstInstalled(this) < 90) {
      Log.i(TAG, "Detected a new install that doesn't have passphrases disabled -- assuming bad initialization.");
      AppInitialization.onRepairFirstEverAppLaunch(this);
    } else if (!SignalStore.settings().getPassphraseDisabled() && VersionTracker.getDaysSinceFirstInstalled(this) < 912) {
      Log.i(TAG, "Detected a not-recent install that doesn't have passphrases disabled -- disabling now.");
      SignalStore.settings().setPassphraseDisabled(true);
    }
  }

  private void initializeFcmCheck() {
    if (!SignalStore.account().isRegistered()) {
      return;
    }

    PlayServicesUtil.PlayServicesStatus playServicesStatus = PlayServicesUtil.getPlayServicesStatus(this);

    if (playServicesStatus == PlayServicesUtil.PlayServicesStatus.SUCCESS && !SignalStore.account().isFcmEnabled()) {
      // Tellomi：上游在这里「看见装了 Play Services 就认为 FCM 可用」——直接把 fcmEnabled 打开
      // 并**停掉常驻 websocket 的前台服务**。这个假设在大陆不成立：手机装着 GMS，但网络到不了
      // Google，FCM 永远收不到。症状是 App 退到后台约 50 秒 websocket 断开，之后完全收不到消息，
      // 直到用户手动打开 App（2026-09-22 在模拟器上实测：`Foreground: false, FCM: true` →
      // `WebSocket State: DISCONNECTED` → `Waiting for websocket state change....`）。
      //
      // 上游确实有自愈，但要 FCM **连续失败超过 3 天**才会自动切回 websocket 模式
      // （FcmRefreshJob.onFailure），三天收不到消息对我们不可接受。
      //
      // 改法：**装了 GMS 不等于 FCM 能用**——先不动 fcmEnabled，只去真的取一次 token；
      // 取到了由 FcmRefreshJob 自己把 fcmEnabled 打开并停掉前台服务（那段逻辑它本来就有）。
      // 取不到就维持 websocket 模式。这样「FCM 真能用」的机器行为完全不变。
      Log.w(TAG, "Play Services are available but FCM is not enabled yet. Trying to get a token before trusting FCM.");
      AppDependencies.getJobManager().startChain(new FcmRefreshJob())
                                      .then(new RefreshAttributesJob())
                                      .enqueue();
    } else if (playServicesStatus == PlayServicesUtil.PlayServicesStatus.SUCCESS &&
               SignalStore.account().isFcmEnabled() &&
               FcmRefreshJob.neverHadAnFcmToken() &&
               SignalStore.settings().getForceWebsocketMode() == SettingsValues.ForceWebsocketMode.ENABLED_AUTOMATICALLY) {
      // Tellomi（#923）：这台设备已经被判定「FCM 用不了」（forceWebsocketMode 自动打开了），
      // 但 fcmEnabled 还是 true —— 于是**服务端以为它走推送**（账号属性 fetchesMessages=false），
      // 每来一条消息都往一个根本不存在的 token 发一次 FCM。本机能收到消息纯粹是强制 websocket 兜住了。
      //
      // 实测（2026-09-22，emulator-5554，装着 GMS 但拿不到 token）：同一行日志里
      //   Force websocket: true 而 FCM: true。
      //
      // FcmRefreshJob 里那段修复只在「从 DISABLED 切到 ENABLED_AUTOMATICALLY」的那一刻跑，
      // 已经处在这个状态的设备（和升级上来的用户）再也不会进那个分支，所以要在启动时收敛一次。
      // 恢复路径不受影响：fcmEnabled 变 false 之后，下次启动会走上面那条
      // 「Play Services available but FCM not enabled → 真取一次 token」，取到了
      // FcmRefreshJob 的成功分支会把 fcmEnabled 打开并把 forceWebsocketMode revert 回 DISABLED。
      Log.w(TAG, "Forced websocket mode was auto-enabled and we never had an FCM token, but fcmEnabled is still true. Telling the server we fetch messages.");
      SignalStore.account().setFcmEnabled(false);
      SignalStore.account().setFcmToken(null);
      AppDependencies.getJobManager().add(new RefreshAttributesJob());
    } else if (playServicesStatus == PlayServicesUtil.PlayServicesStatus.MISSING && SignalStore.account().isFcmEnabled()) {
      if (FcmRefreshJob.neverHadAnFcmToken()) {
        // Tellomi（#952）：全新安装时 fcmEnabled 会被写成 true，**与有没有 GMS 无关**——
        // AccountValues.migrateFromSharedPrefsV1（KV 首次迁移，全新安装也跑）里是
        //   putBoolean(KEY_FCM_ENABLED, !getBooleanPreference(context, "pref_gcm_disabled", false))
        // 全新安装取不到 pref_gcm_disabled → 默认 false → !false = true。
        // 属性声明上那个 booleanValue(KEY_FCM_ENABLED, false) 的默认值**永远用不上**。
        //
        // 上游靠下面这个 FcmRefreshJob 重试耗尽后自己纠正，实测约 51 秒。这 51 秒里
        // fcmEnabled=true，IncomingMessageObserver 认为不需要常驻连接，用户一切后台就收不到消息
        // （2026-09-22 实测：06:35 发出，直到 06:41 切回前台才出现）。
        //
        // 没装 GMS **而且从来没拿到过 token** 的机器，等多久都不会拿到，没有任何理由等：
        // 立刻改对，并补一个 RefreshAttributesJob 让服务端知道 fetchesMessages=true。
        // 「以前拿到过、现在 GMS 没了」不走这条——那种情况按上游的样子再试一次。
        Log.w(TAG, "No Play Services and we never had an FCM token, but fcmEnabled was true. Fixing it now instead of waiting for the job to exhaust its retries.");
        SignalStore.account().setFcmEnabled(false);
        SignalStore.account().setFcmToken(null);
        AppDependencies.getJobManager().add(new RefreshAttributesJob());
        AppDependencies.resetNetwork();
        AppDependencies.startNetwork();
      } else {
        Log.w(TAG, "Play Services are no longer available. Attempting to get an FCM token anyway.");
        AppDependencies.getJobManager().add(new FcmRefreshJob());
      }
    } else if (playServicesStatus == PlayServicesUtil.PlayServicesStatus.MISSING && (System.currentTimeMillis() - SignalStore.misc().getLastMissingPlayServicesFcmVerificationTime()) > TimeUnit.DAYS.toMillis(3)) {
      Log.i(TAG, "Play Services are unavailable, but it's been long enough that we should check and see if we can get an FCM token anyway.");
      AppDependencies.getJobManager().add(new FcmRefreshJob());
    } else if (SignalStore.account().isFcmEnabled()) {
      long lastSetTime = SignalStore.account().getFcmTokenLastSetTime();
      long nextSetTime = lastSetTime + TimeUnit.HOURS.toMillis(6);
      long now         = System.currentTimeMillis();

      if (SignalStore.account().getFcmToken() == null || nextSetTime <= now || lastSetTime > now) {
        Log.i(TAG, "Time for routine FCM token refresh.");
        AppDependencies.getJobManager().add(new FcmRefreshJob());
      }
    } else {
      Log.d(TAG, "Play Services status: " + playServicesStatus + ", fcmEnabled: false. Skipping FCM check.");
    }
  }

  private void initializeExpiringMessageManager() {
    AppDependencies.getExpiringMessageManager().checkSchedule();
  }

  private void initializeRevealableMessageManager() {
    AppDependencies.getViewOnceMessageManager().scheduleIfNecessary();
  }

  private void initializePendingRetryReceiptManager() {
    AppDependencies.getPendingRetryReceiptManager().scheduleIfNecessary();
  }

  private void initializeScheduledMessageManager() {
    AppDependencies.getScheduledMessageManager().scheduleIfNecessary();
  }

  private void initializeTrimThreadsByDateManager() {
    KeepMessagesDuration keepMessagesDuration = SignalStore.settings().getKeepMessagesDuration();
    if (keepMessagesDuration != KeepMessagesDuration.FOREVER) {
      AppDependencies.getTrimThreadsByDateManager().scheduleIfNecessary();
    }
  }

  private void initializeTracer() {
    if (RemoteConfig.internalUser()) {
      Tracer.getInstance().setMaxBufferSize(35_000);
    }
  }

  private void initializePeriodicTasks() {
    RotateSignedPreKeyListener.schedule(this);
    DirectoryRefreshListener.schedule(this);
    LocalBackupListener.schedule(this);
    MessageBackupListener.schedule(this);
    RotateSenderCertificateListener.schedule(this);
    RoutineMessageFetchReceiver.startOrUpdateAlarm(this);
    AnalyzeDatabaseAlarmListener.schedule(this);

    if (BuildConfig.MANAGES_APP_UPDATES) {
      ApkUpdateRefreshListener.schedule(this);
    }
  }

  private void initializeRingRtc() {
    try {
      Map<String, String> fieldTrials = new HashMap<>();
      if (RemoteConfig.callingFieldTrialAnyAddressPortsKillSwitch()) {
        fieldTrials.put("RingRTC-AnyAddressPortsKillSwitch", "Enabled");
      }
      CallManager.initialize(this, new RingRtcLogger(), fieldTrials);
    } catch (UnsatisfiedLinkError e) {
      throw new AssertionError("Unable to load ringrtc library", e);
    }
  }

  @WorkerThread
  private void initializeCircumvention() {
    if (AppDependencies.getSignalServiceNetworkAccess().isCensored()) {
      try {
        ProviderInstaller.installIfNeeded(ApplicationContext.this);
      } catch (Throwable t) {
        Log.w(TAG, t);
      }
    }
  }

  private void ensureProfileUploaded() {
    if (SignalStore.account().isRegistered() && !SignalStore.registration().hasUploadedProfile() && !Recipient.self().getProfileName().isEmpty() && SignalStore.account().isPrimaryDevice()) {
      Log.w(TAG, "User has a profile, but has not uploaded one. Uploading now.");
      AppDependencies.getJobManager().add(new ProfileUploadJob());
    }
  }

  private void executePendingContactSync() {
    if (TextSecurePreferences.needsFullContactSync(this)) {
      AppDependencies.getJobManager().add(new MultiDeviceContactUpdateJob(true));
    }
  }

  @VisibleForTesting
  protected void beginJobLoop() {
    AppDependencies.getJobManager().beginJobLoop();
  }

  @WorkerThread
  private void initializeBlobProvider() {
    AppDependencies.getBlobs().initialize(this, DraftBlobs.INSTANCE::deleteOrphanedDraftFiles);
  }

  @WorkerThread
  private void cleanAvatarStorage() {
    AvatarPickerStorage.cleanOrphans(this);
  }

  @WorkerThread
  private void initializeCleanup() {
    int deleted = SignalDatabase.attachments().deleteAbandonedPreuploadedAttachments();
    Log.i(TAG, "Deleted " + deleted + " abandoned attachments.");
  }

  private void initializeGlideCodecs() {
    SignalGlideCodecs.setLogProvider(new org.signal.glide.Log.Provider() {
      @Override
      public void v(@NonNull String tag, @NonNull String message) {
        Log.v(tag, message);
      }

      @Override
      public void d(@NonNull String tag, @NonNull String message) {
        Log.d(tag, message);
      }

      @Override
      public void i(@NonNull String tag, @NonNull String message) {
        Log.i(tag, message);
      }

      @Override
      public void w(@NonNull String tag, @NonNull String message) {
        Log.w(tag, message);
      }

      @Override
      public void e(@NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
        Log.e(tag, message, throwable);
      }
    });
  }

  @Override
  protected void attachBaseContext(Context base) {
    PlainTextKeyValueStore.init(base);
    DynamicLanguageContextWrapper.updateContext(base);
    super.attachBaseContext(base);
  }

  private static class ProviderInitializationException extends RuntimeException {
  }
}
