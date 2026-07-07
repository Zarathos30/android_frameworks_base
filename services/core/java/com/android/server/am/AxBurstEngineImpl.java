/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.am;

import static android.os.Process.THREAD_GROUP_DEFAULT;
import static android.os.Process.THREAD_GROUP_FOREGROUND_WINDOW;
import static android.os.Process.THREAD_GROUP_H_BACKGROUND;
import static android.os.Process.THREAD_GROUP_L_BACKGROUND;
import static android.os.Process.THREAD_GROUP_RESTRICTED;
import static android.os.Process.THREAD_GROUP_SYSTEMUI;
import static android.os.Process.THREAD_GROUP_TOP_APP;

import static com.android.server.am.ProcessList.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.ProcessList.SCHED_GROUP_FOREGROUND_WINDOW;
import static com.android.server.am.ProcessList.SCHED_GROUP_RESTRICTED;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP_BOUND;

import android.app.AxFrameRescue;
import android.content.Context;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.hardware.power.SessionTag;
import android.os.Handler;
import android.os.Message;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.axperf.AxFrameRescueInternal;
import com.android.server.am.psc.ProcessRecordInternal;
import com.android.server.wm.AxRefreshRateController;
import com.android.server.wm.SurfaceAnimationThread;

public final class AxBurstEngineImpl extends SystemService {
    private static final String TAG = "AxBurstEngineImpl";

    private static final long UI_ANIMATION_MAX_DURATION_MS = 1600L;
    private static final long UI_ANIMATION_MIN_DURATION_MS = 350L;
    private static final long UI_ANIMATION_RESCHEDULE_SLOP_MS = 96L;
    private static final long UI_PERF_RELEASE_LINGER_MS = 256L;
    private static final long REMOTE_ANIMATION_TIMEOUT_MS = 2000L;
    private static final long APP_LAUNCH_TIMEOUT_MS = 3000L;
    private static final long APP_LAUNCH_MIN_TIMEOUT_MS = 1000L;
    private static final long APP_LAUNCH_PREPARE_MS = 1200L;
    private static final long APP_LAUNCH_SOURCE_MS = 1600L;
    private static final long APP_LAUNCH_FINISH_LINGER_MIN_MS = 512L;
    private static final long APP_LAUNCH_FINISH_LINGER_MAX_MS = 900L;
    private static final long START_ACTIVITY_BINDER_TIMEOUT_MS = 200L;
    private static final long TOP_APP_HANDOFF_DISPLAY_WARM_MS = 1800L;
    private static final long TOP_APP_HANDOFF_RENDER_MS = 640L;
    private static final long TOP_APP_PERF_LIGHT_MS = 256L;
    private static final long TOP_APP_PERF_HEAVY_MS = 512L;
    private static final long TOP_APP_PERF_LAUNCH_MS = 1000L;
    private static final long TOP_APP_PERF_DISPLAY_MS = 128L;
    private static final long TOP_APP_PERF_MAX_MS = 1200L;
    private static final long FALLBACK_LAUNCH_WINDOW_MS = 5000L;
    private static final long FALLBACK_LAUNCH_GAP_MS = 3000L;
    private static final int FALLBACK_LAUNCH_LIGHT_LIMIT = 4;
    private static final int FALLBACK_LAUNCH_MODERATE_LIMIT = 2;
    private static final int THERMAL_LEVEL_LIGHT = 5;
    private static final int THERMAL_LEVEL_MODERATE = 8;
    private static final int UI_ANIMATION_REQUEST_APP = 1;
    private static final int UI_ANIMATION_REQUEST_LAUNCHER = 2;
    private static final int UI_ANIMATION_REQUEST_SYSTEMUI = 3;
    private static final int ANIMATION_REQUEST_REJECTED = 0;
    private static final int ANIMATION_REQUEST_ACCEPTED = 1;
    private static final int ANIMATION_REQUEST_SCHEDULED = 2;

    private final Object mLock = new Object();
    private final Object mAnimationUiPerfRequestLock = new Object();
    private final ServiceThread mThread;
    private final Handler mHandler;
    private final AxBurstScheduler mBurstScheduler = new AxBurstScheduler();
    private final SparseArray<RemoteAnimationState> mRemoteAnimationStates = new SparseArray<>();

    private final TrackedProcess mSystemUi = new TrackedProcess();
    private final TrackedProcess mLauncher = new TrackedProcess();
    private final TrackedProcess mTopApp = new TrackedProcess();
    private final AppLaunchState mAppLaunchState = new AppLaunchState();
    private boolean mAnimationUiPerfQueued;
    private ProcessRecordInternal mAnimationUiPerfRequestApp;
    private int mAnimationUiPerfRequestPid = -1;
    private long mAnimationUiPerfRequestExpiryUptimeMs;
    private int mAnimationUiPerfRequestRank;
    private boolean mAnimationUiPerfActive;
    private int mAnimationUiPerfPid = -1;
    private long mAnimationUiPerfExpiryUptimeMs;
    private int mUiPerfModePid = Integer.MIN_VALUE;
    private int mTopAppPerfPid = -1;
    private int mTopAppPerfSource = -1;
    private int mTopAppPerfSeverity = AxUiSession.SEVERITY_LIGHT;
    private long mTopAppPerfExpiryUptimeMs;
    private long mBackgroundIoDeferUptimeMs;
    private boolean mGameModeActive;
    private int mGameModePid = -1;
    private int mThermalLevel;
    private long mFallbackLaunchWindowStartUptimeMs;
    private long mLastFallbackLaunchUptimeMs;
    private int mFallbackLaunchCount;
    private boolean mFallbackLaunchActive;
    private boolean mFallbackLaunchSuppressed;
    private Object mUiPerfReleaseToken = new Object();
    private Object mAnimationUiPerfToken = new Object();
    private Object mTopAppPerfToken = new Object();

    public AxBurstEngineImpl(Context context) {
        super(context);
        mThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_FOREGROUND, true);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxBurstEngineImpl.class, this);
        mHandler.post(mBurstScheduler::reset);
        mHandler.post(this::clearUiPerfMode);
    }

    public boolean isUiPerfActive() {
        synchronized (mLock) {
            return mUiPerfModePid > 0;
        }
    }

    public boolean shouldDeferBackgroundIo() {
        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            return mUiPerfModePid > 0 || isAppLaunchActiveLocked()
                    || isTopAppPerfActiveLocked(now) || mBackgroundIoDeferUptimeMs > now;
        }
    }

    public void onProcessEnded(ProcessRecordInternal app) {
        if (app == null || app.getPid() <= 0) {
            return;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AxBurstEngineImpl::applyProcessEnded, this, app.getPid()));
    }

    public void onProcessStarted(ProcessRecordInternal app) {
        if (app == null || app.getPid() <= 0 || (!app.isSystemUi() && !app.isLauncher3())) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::applyProcessStarted, this, app);
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    public void onProcessGroupChanged(ProcessRecordInternal app, int group) {
        if (app == null || app.getPid() <= 0) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::applyProcessGroupChanged, this, app, group);
        message.setAsynchronous(true);
        if (app.isSystemUi()) {
            mHandler.sendMessageAtFrontOfQueue(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void startAnimationUiPerf(ProcessRecordInternal app, int pid, long durationMs) {
        if (pid <= 0 || durationMs <= 0) {
            return;
        }
        final long boundedDurationMs = Math.min(Math.max(durationMs,
                UI_ANIMATION_MIN_DURATION_MS), UI_ANIMATION_MAX_DURATION_MS);
        final long expiryUptimeMs = SystemClock.uptimeMillis() + boundedDurationMs;
        final int request = requestAnimationUiPerf(app, pid, expiryUptimeMs);
        if (request == ANIMATION_REQUEST_REJECTED) {
            return;
        }
        boostDisplayWarmup(boundedDurationMs);
        boostAnimationRenderPath(pid, app != null ? app.uid : Process.SYSTEM_UID,
                boundedDurationMs);
        if (request != ANIMATION_REQUEST_SCHEDULED) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::applyAnimationUiPerf, this);
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    public void onUiAnimationPrepared(ProcessRecordInternal app, int pid, long durationMs) {
        startAnimationUiPerf(app, pid, durationMs);
    }

    public void onUiAnimationStarted(ProcessRecordInternal app, int pid, long durationMs) {
        startAnimationUiPerf(app, pid, durationMs);
    }

    public void onUiAnimationFinished(int pid) {
        if (pid <= 0) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::clearAnimationUiPerfForPid, this, pid);
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    public void onAppLaunchPrepared() {
        final Message message = Message.obtain(mHandler, this::applyAppLaunchPrepared);
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    public void onAppLaunchStarted(int pid, long durationMs) {
        if (pid <= 0) {
            return;
        }
        final long boundedDurationMs = boundedAppLaunchDurationMs(durationMs);
        final Message message = Message.obtain(mHandler,
                () -> applyAppLaunchStarted(pid, boundedDurationMs));
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    public void onAppLaunchSource(int pid, int uid) {
        if (pid <= 0 || uid < 0) {
            return;
        }
        final Message message = Message.obtain(mHandler, () -> applyAppLaunchSource(pid, uid));
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    public void onStartActivityBinder(int tid) {
        if (tid <= 0) {
            return;
        }
        final Message message = Message.obtain(mHandler, () -> applyStartActivityBinder(tid));
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    public boolean onPowerBoost(int boost, int durationMs) {
        final TopAppPerfRequest request = powerBoostRequest(boost, durationMs);
        if (request == null) {
            return false;
        }
        return applyTopAppPerf(request);
    }

    public boolean onPowerMode(int mode, boolean enabled) {
        if (mode == Mode.GAME) {
            return applyGameMode(enabled);
        }
        final TopAppPerfRequest request = powerModeRequest(mode, enabled);
        if (request == null) {
            return false;
        }
        final boolean handled = applyTopAppPerf(request);
        if (mode == Mode.LAUNCH && !enabled) {
            return shouldSuppressFallbackLaunchEnd(handled);
        }
        if (handled || mode != Mode.LAUNCH) {
            return handled;
        }
        return shouldSuppressFallbackLaunchStart();
    }

    public void setThermalLevel(int level) {
        synchronized (mLock) {
            mThermalLevel = Math.max(level, 0);
            if (mThermalLevel < THERMAL_LEVEL_LIGHT) {
                mFallbackLaunchWindowStartUptimeMs = 0L;
                mLastFallbackLaunchUptimeMs = 0L;
                mFallbackLaunchCount = 0;
            }
        }
    }

    public void onAdpfWork(int pid, int uid, int tag, boolean graphicsPipeline,
            long actualDurationNs, long targetDurationNs) {
        if (pid <= 0 || uid < 0 || actualDurationNs <= 0 || targetDurationNs <= 0) {
            return;
        }
        final int source = graphicsPipeline || tag == SessionTag.GAME
                ? AxUiSession.SOURCE_ADPF_GPU : AxUiSession.SOURCE_ADPF_CPU;
        final int severity = actualDurationNs >= targetDurationNs
                ? AxUiSession.SEVERITY_HEAVY : AxUiSession.SEVERITY_LIGHT;
        final long durationMs = severity == AxUiSession.SEVERITY_HEAVY
                ? TOP_APP_PERF_HEAVY_MS : TOP_APP_PERF_LIGHT_MS;
        final TopAppPerfRequest request = new TopAppPerfRequest(pid, uid, source,
                severity, durationMs);
        final Message message = Message.obtain(mHandler, () -> applyTopAppPerf(request));
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    public void onAdpfHint(int pid, int uid, int tag, boolean graphicsPipeline, int hint) {
        if (pid <= 0 || uid < 0) {
            return;
        }
        final TopAppPerfRequest request = adpfHintRequest(pid, uid, tag, graphicsPipeline, hint);
        if (request == null) {
            return;
        }
        final Message message = Message.obtain(mHandler, () -> applyTopAppPerf(request));
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    public void onAppLaunchFinished(int pid) {
        if (pid <= 0) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::applyAppLaunchFinished, this, pid);
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    public void setRunningRemoteAnimation(int pid, int processGroup, boolean running) {
        if (pid <= 0) {
            return;
        }
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::applySetRunningRemoteAnimation, this, pid, processGroup,
                running);
        message.setAsynchronous(true);
        if (running) {
            mHandler.sendMessageAtFrontOfQueue(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void applyProcessEnded(int pid) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            final boolean hadRemoteAnimation = mRemoteAnimationStates.get(pid) != null;
            final boolean hadLauncher = mLauncher.hasPid(pid);
            final boolean hadAppLaunch = mAppLaunchState.hasPid(pid);
            mBurstScheduler.clear(pid);
            mRemoteAnimationStates.remove(pid);
            if (hadAppLaunch) {
                clearAppLaunchStateLocked();
            }
            if (pid == mAnimationUiPerfPid) {
                clearAnimationUiPerfLocked(false);
            }
            if (mSystemUi.hasPid(pid)) {
                clearSystemUiStateLocked();
            }
            if (mLauncher.hasPid(pid)) {
                clearLauncherStateLocked();
            }
            if (mTopApp.hasPid(pid)) {
                clearTopAppStateLocked();
            }
            if (hadAppLaunch || hadLauncher || hadRemoteAnimation || mUiPerfModePid == pid) {
                updateUiPerfModeLocked();
            }
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
        clearFrameRescue(pid);
    }

    private static void clearFrameRescue(int pid) {
        final AxFrameRescueInternal frameRescue =
                LocalServices.getService(AxFrameRescueInternal.class);
        if (frameRescue != null) {
            frameRescue.clear(pid);
        }
    }

    private void applyProcessStarted(ProcessRecordInternal app) {
        synchronized (mLock) {
            trackSpecialProcessLocked(app);
        }
    }

    private void applyProcessGroupChanged(ProcessRecordInternal app, int group) {
        final boolean warmDisplay;
        final boolean clearDisplayWarmup;
        final TopAppPerfRequest handoffPerfRequest;
        synchronized (mLock) {
            if (app == null || app.getPid() <= 0) {
                return;
            }
            trackSpecialProcessLocked(app);
            final int pid = app.getPid();
            final int renderThreadTid = app.getRenderThreadTid();
            boolean updateTopApp = false;
            int clearedAppLaunchPid = -1;
            if (app.isLauncher3()) {
                mLauncher.group = group;
                mLauncher.uid = app.uid;
                mLauncher.renderThreadTid = renderThreadTid;
            }
            if (isTopAppGroup(group)) {
                if (mAppLaunchState.hasProcess() && !mAppLaunchState.hasPid(pid)) {
                    clearedAppLaunchPid = mAppLaunchState.pid;
                    mBurstScheduler.clear(clearedAppLaunchPid);
                    clearAppLaunchStateLocked();
                }
                if (mTopApp.hasProcess() && !mTopApp.hasPid(pid)) {
                    final int oldPid = mTopApp.pid;
                    if (mAnimationUiPerfPid == oldPid) {
                        clearAnimationUiPerfLocked(false);
                    }
                    if (mGameModePid == oldPid) {
                        mGameModePid = -1;
                    }
                    if (oldPid != clearedAppLaunchPid) {
                        mBurstScheduler.clear(oldPid);
                    }
                }
                if (!mTopApp.hasPid(pid) || mTopApp.uid != app.uid || mTopApp.group != group
                        || mTopApp.renderThreadTid != renderThreadTid) {
                    updateTopApp = true;
                }
                mTopApp.set(pid, app.uid, group, renderThreadTid);
            } else if (mTopApp.hasPid(pid)) {
                clearTopAppStateLocked();
            }
            final RemoteAnimationState state = mRemoteAnimationStates.get(pid);
            if (state != null) {
                state.processGroup = group;
            }
            if (updateTopApp || clearedAppLaunchPid > 0 || pid == uiPerfModeTargetPidLocked()
                    || state != null || app.isLauncher3()) {
                updateUiPerfModeLocked();
            }
            warmDisplay = updateTopApp;
            handoffPerfRequest = warmDisplay
                    ? new TopAppPerfRequest(pid, app.uid, AxUiSession.SOURCE_TOP_APP_HANDOFF,
                            AxUiSession.SEVERITY_LIGHT, TOP_APP_HANDOFF_RENDER_MS)
                    : null;
            clearDisplayWarmup = !warmDisplay && shouldClearDisplayWarmupLocked();
        }
        if (warmDisplay) {
            applyTopAppPerf(handoffPerfRequest);
            boostDisplayWarmup(TOP_APP_HANDOFF_DISPLAY_WARM_MS);
        } else if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void applyAppLaunchPrepared() {
        synchronized (mLock) {
            deferBackgroundIoLocked(APP_LAUNCH_PREPARE_MS);
        }
        boostDisplayWarmup(APP_LAUNCH_PREPARE_MS);
        boostSystemServerRenderPath(AxBurstScheduler.MODE_LAUNCH, AxUiSession.SOURCE_LAUNCH,
                AxUiSession.SEVERITY_HEAVY, APP_LAUNCH_PREPARE_MS);
    }

    private void applyAppLaunchStarted(int pid, long durationMs) {
        final Object token = new Object();
        synchronized (mLock) {
            if (mAppLaunchState.hasProcess() && !mAppLaunchState.hasPid(pid)) {
                mBurstScheduler.clear(mAppLaunchState.pid);
                clearAppLaunchStateLocked();
            }
            mAppLaunchState.set(pid, SystemClock.uptimeMillis() + durationMs, token);
            deferBackgroundIoLocked(durationMs);
            updateUiPerfModeLocked();
        }
        boostDisplayWarmup(durationMs);
        boostSystemServerRenderPath(AxBurstScheduler.MODE_LAUNCH, AxUiSession.SOURCE_LAUNCH,
                AxUiSession.SEVERITY_HEAVY, durationMs);
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::clearAppLaunchIfCurrent, this, token);
        message.setAsynchronous(true);
        mHandler.sendMessageDelayed(message, durationMs);
    }

    private void applyAppLaunchSource(int pid, int uid) {
        synchronized (mLock) {
            if (!mLauncher.hasPid(pid)) {
                mLauncher.setPid(pid, uid, -1);
            }
            mBurstScheduler.set(AxUiSession.createForTidWithRole(pid, uid,
                    AxBurstScheduler.MODE_LAUNCH, AxUiSession.SOURCE_LAUNCH,
                    AxUiSession.SEVERITY_HEAVY, APP_LAUNCH_SOURCE_MS,
                    renderThreadTidForPidLocked(pid),
                    AxUiSession.ROLE_LAUNCHER));
            deferBackgroundIoLocked(APP_LAUNCH_SOURCE_MS);
        }
        boostDisplayWarmup(APP_LAUNCH_SOURCE_MS);
    }

    private void applyStartActivityBinder(int tid) {
        synchronized (mLock) {
            mBurstScheduler.set(AxUiSession.createForTidWithRole(Process.myPid(),
                    Process.SYSTEM_UID, AxBurstScheduler.MODE_LAUNCH,
                    AxUiSession.SOURCE_START_ACTIVITY_BINDER, AxUiSession.SEVERITY_LIGHT,
                    START_ACTIVITY_BINDER_TIMEOUT_MS, tid, AxUiSession.ROLE_SYSTEM_SERVER));
        }
    }

    private boolean applyTopAppPerf(TopAppPerfRequest request) {
        if (request.restore) {
            final boolean restored;
            final boolean clearDisplayWarmup;
            synchronized (mLock) {
                restored = request.source <= 0 || request.source == mTopAppPerfSource;
                if (restored) {
                    final boolean warmedDisplay =
                            shouldWarmDisplayForTopAppPerf(mTopAppPerfSource);
                    clearTopAppPerfStateLocked();
                    updateUiPerfModeLocked();
                    clearDisplayWarmup = warmedDisplay && shouldClearDisplayWarmupLocked();
                } else {
                    clearDisplayWarmup = false;
                }
            }
            if (clearDisplayWarmup) {
                clearDisplayWarmup();
            }
            return restored;
        }
        final Object token = new Object();
        final int pid;
        final long expiryUptimeMs;
        final long durationMs;
        synchronized (mLock) {
            final int targetPid = request.pid > 0 ? request.pid : mTopApp.pid;
            final int targetUid = request.uid >= 0 ? request.uid : mTopApp.uid;
            if (!mTopApp.hasProcess() || !mTopApp.hasPid(targetPid)
                    || !isTopAppGroup(mTopApp.group)) {
                return false;
            }
            if (uiPerfModeTargetPidLocked() == targetPid) {
                return true;
            }
            durationMs = boundedTopAppPerfDurationMs(request.durationMs);
            final long now = SystemClock.uptimeMillis();
            expiryUptimeMs = now + durationMs;
            if (mTopAppPerfPid == targetPid && mTopAppPerfExpiryUptimeMs > now
                    && mTopAppPerfSource == request.source
                    && mTopAppPerfSeverity >= request.severity
                    && expiryUptimeMs <= mTopAppPerfExpiryUptimeMs
                            + UI_ANIMATION_RESCHEDULE_SLOP_MS) {
                return true;
            }
            if (!mBurstScheduler.set(AxUiSession.createForTidWithRole(targetPid, targetUid,
                    AxBurstScheduler.MODE_PERF, request.source, request.severity, durationMs,
                    renderThreadTidForPidLocked(targetPid), AxUiSession.ROLE_TOP_APP))) {
                return false;
            }
            mTopAppPerfToken = token;
            mTopAppPerfPid = targetPid;
            mTopAppPerfSource = request.source;
            mTopAppPerfSeverity = request.severity;
            mTopAppPerfExpiryUptimeMs = expiryUptimeMs;
            pid = targetPid;
        }
        if (shouldWarmDisplayForTopAppPerf(request.source)) {
            boostDisplayWarmup(durationMs);
        }
        final Message message = Message.obtain(mHandler,
                () -> restoreTopAppPerfIfCurrent(token, pid, expiryUptimeMs));
        message.setAsynchronous(true);
        mHandler.sendMessageDelayed(message, durationMs);
        return true;
    }

    private boolean shouldSuppressFallbackLaunchEnd(boolean handled) {
        synchronized (mLock) {
            if (mFallbackLaunchActive) {
                mFallbackLaunchActive = false;
                mFallbackLaunchSuppressed = false;
                return false;
            }
            if (mFallbackLaunchSuppressed) {
                mFallbackLaunchSuppressed = false;
                return true;
            }
            return handled;
        }
    }

    private boolean shouldSuppressFallbackLaunchStart() {
        synchronized (mLock) {
            if (mFallbackLaunchActive) {
                return false;
            }
            if (mFallbackLaunchSuppressed) {
                return true;
            }
            if (mThermalLevel < THERMAL_LEVEL_LIGHT) {
                mFallbackLaunchActive = true;
                return false;
            }

            final long now = SystemClock.uptimeMillis();
            if (mFallbackLaunchWindowStartUptimeMs == 0L
                    || now - mFallbackLaunchWindowStartUptimeMs >= FALLBACK_LAUNCH_WINDOW_MS) {
                mFallbackLaunchWindowStartUptimeMs = now;
                mFallbackLaunchCount = 0;
            }
            mFallbackLaunchCount++;
            final int limit = mThermalLevel >= THERMAL_LEVEL_MODERATE
                    ? FALLBACK_LAUNCH_MODERATE_LIMIT : FALLBACK_LAUNCH_LIGHT_LIMIT;
            if (mFallbackLaunchCount > limit
                    && now - mLastFallbackLaunchUptimeMs < FALLBACK_LAUNCH_GAP_MS) {
                mFallbackLaunchSuppressed = true;
                return true;
            }
            mLastFallbackLaunchUptimeMs = now;
            mFallbackLaunchActive = true;
            return false;
        }
    }

    private boolean applyGameMode(boolean enabled) {
        synchronized (mLock) {
            if (!enabled) {
                if (!mGameModeActive && mGameModePid <= 0) {
                    return false;
                }
                final int oldPid = mGameModePid;
                mGameModeActive = false;
                mGameModePid = -1;
                if (oldPid > 0) {
                    mBurstScheduler.clear(oldPid);
                }
                updateUiPerfModeLocked();
                return true;
            }
            if (!mTopApp.hasProcess() || !isTopAppGroup(mTopApp.group)) {
                return false;
            }
            if (!mBurstScheduler.set(gameSessionLocked())) {
                return false;
            }
            mGameModeActive = true;
            mGameModePid = mTopApp.pid;
            return true;
        }
    }

    private void restoreTopAppPerfIfCurrent(Object token, int pid, long expiryUptimeMs) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            if (token != mTopAppPerfToken || pid != mTopAppPerfPid
                    || expiryUptimeMs != mTopAppPerfExpiryUptimeMs
                    || SystemClock.uptimeMillis() < expiryUptimeMs) {
                return;
            }
            final boolean warmedDisplay = shouldWarmDisplayForTopAppPerf(mTopAppPerfSource);
            clearTopAppPerfStateLocked();
            updateUiPerfModeLocked();
            clearDisplayWarmup = warmedDisplay && shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void applyAppLaunchFinished(int pid) {
        final Object token = new Object();
        final long durationMs;
        synchronized (mLock) {
            if (!mAppLaunchState.hasPid(pid)) {
                return;
            }
            durationMs = appLaunchFinishLingerMsLocked();
            mBurstScheduler.clear(pid);
            mAppLaunchState.set(pid, SystemClock.uptimeMillis() + durationMs, token);
            deferBackgroundIoLocked(durationMs);
            updateUiPerfModeLocked();
        }
        boostDisplayWarmup(durationMs);
        boostSystemServerRenderPath(AxBurstScheduler.MODE_LAUNCH, AxUiSession.SOURCE_LAUNCH,
                AxUiSession.SEVERITY_LIGHT, durationMs);
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::clearAppLaunchIfCurrent, this, token);
        message.setAsynchronous(true);
        mHandler.sendMessageDelayed(message, durationMs);
    }

    private void applySetRunningRemoteAnimation(int pid, int processGroup, boolean running) {
        if (running) {
            applyStartRemoteAnimation(pid, processGroup);
        } else {
            applyFinishRemoteAnimation(pid, processGroup);
        }
    }

    private void applyStartRemoteAnimation(int pid, int processGroup) {
        synchronized (mLock) {
            RemoteAnimationState state = mRemoteAnimationStates.get(pid);
            if (state == null) {
                state = new RemoteAnimationState();
                mRemoteAnimationStates.put(pid, state);
            }
            state.count++;
            state.processGroup = processGroup;
            updateUiPerfModeLocked();
            scheduleRemoteAnimationTimeoutLocked(pid, state);
        }
        boostDisplayWarmup(REMOTE_ANIMATION_TIMEOUT_MS);
        boostSystemServerRenderPath(AxBurstScheduler.MODE_REMOTE, AxUiSession.SOURCE_REMOTE,
                AxUiSession.SEVERITY_HEAVY, REMOTE_ANIMATION_TIMEOUT_MS);
        boostAnimationRenderPath(pid, Process.SYSTEM_UID, REMOTE_ANIMATION_TIMEOUT_MS);
    }

    private void applyFinishRemoteAnimation(int pid, int processGroup) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            final RemoteAnimationState state = mRemoteAnimationStates.get(pid);
            if (state == null) {
                return;
            }
            state.count--;
            state.processGroup = processGroup;
            if (state.count > 0) {
                scheduleRemoteAnimationTimeoutLocked(pid, state);
                updateUiPerfModeLocked();
                return;
            }
            clearRemoteAnimationLocked(pid);
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void scheduleRemoteAnimationTimeoutLocked(int pid, RemoteAnimationState state) {
        final int generation = ++state.generation;
        mHandler.postDelayed(() -> clearRemoteAnimationIfCurrent(pid, generation),
                REMOTE_ANIMATION_TIMEOUT_MS);
    }

    private void clearRemoteAnimationIfCurrent(int pid, int generation) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            final RemoteAnimationState state = mRemoteAnimationStates.get(pid);
            if (state == null || state.generation != generation) {
                return;
            }
            clearRemoteAnimationLocked(pid);
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void clearRemoteAnimationLocked(int pid) {
        mRemoteAnimationStates.remove(pid);
        if (pid == mAnimationUiPerfPid) {
            clearAnimationUiPerfLocked(false);
        }
        updateUiPerfModeLocked();
    }

    private void setSystemUiAppLocked(ProcessRecordInternal app) {
        if (app == null || !app.isSystemUi() || app.getPid() <= 0) {
            return;
        }
        if (mSystemUi.hasPid(app.getPid())) {
            mSystemUi.uid = app.uid;
            mSystemUi.renderThreadTid = app.getRenderThreadTid();
            return;
        }
        mSystemUi.setPid(app.getPid(), app.uid, app.getRenderThreadTid());
        updateUiPerfModeLocked();
    }

    private void setLauncherAppLocked(ProcessRecordInternal app) {
        if (app == null || !app.isLauncher3() || app.getPid() <= 0) {
            return;
        }
        if (mLauncher.hasPid(app.getPid())) {
            mLauncher.uid = app.uid;
            mLauncher.renderThreadTid = app.getRenderThreadTid();
            return;
        }
        mLauncher.setPid(app.getPid(), app.uid, app.getRenderThreadTid());
    }

    private void trackSpecialProcessLocked(ProcessRecordInternal app) {
        if (app == null || app.getPid() <= 0) {
            return;
        }
        if (app.isSystemUi()) {
            setSystemUiAppLocked(app);
        }
        if (app.isLauncher3()) {
            setLauncherAppLocked(app);
        }
    }

    private void clearSystemUiStateLocked() {
        final int oldPid = mSystemUi.pid;
        final boolean clearAnimation = oldPid > 0 && mAnimationUiPerfPid == oldPid;
        mSystemUi.clear();
        if (clearAnimation) {
            clearAnimationUiPerfLocked(false);
        }
        clearUiPerfModeLocked();
        mBurstScheduler.clear(oldPid);
    }

    private void clearLauncherStateLocked() {
        final int oldPid = mLauncher.pid;
        mLauncher.clear();
        mBurstScheduler.clear(oldPid);
    }

    private void clearTopAppStateLocked() {
        final int oldPid = mTopApp.pid;
        final boolean clearAnimation = oldPid > 0 && mAnimationUiPerfPid == oldPid;
        mTopApp.clear();
        if (oldPid > 0 && mTopAppPerfPid == oldPid) {
            clearTopAppPerfStateLocked();
        }
        if (oldPid > 0 && mGameModePid == oldPid) {
            mGameModePid = -1;
        }
        if (clearAnimation) {
            clearAnimationUiPerfLocked(false);
        }
        mBurstScheduler.clear(oldPid);
        updateUiPerfModeLocked();
    }

    private void clearUiPerfMode() {
        synchronized (mLock) {
            clearUiPerfModeLocked();
        }
    }

    private void updateUiPerfModeLocked() {
        final int targetPid = uiPerfModeTargetPidLocked();
        if (targetPid > 0) {
            clearTopAppPerfStateLocked();
            mUiPerfReleaseToken = new Object();
            setUiPerfModePidLocked(targetPid);
            updateBurstSchedulingLocked(targetPid);
            return;
        }
        updateBurstSchedulingLocked(-1);
        scheduleUiPerfModeReleaseLocked();
    }

    private boolean isAnimationUiPerfModeActiveLocked() {
        return mAnimationUiPerfActive && animationUiPerfTargetPidLocked(mAnimationUiPerfPid) > 0;
    }

    private boolean isAppLaunchActiveLocked() {
        return mAppLaunchState.isActive(SystemClock.uptimeMillis());
    }

    private boolean hasRemoteAnimationsLocked() {
        return mRemoteAnimationStates.size() != 0;
    }

    private int uiPerfModeTargetPidLocked() {
        final int animationPid = isAnimationUiPerfModeActiveLocked() ? mAnimationUiPerfPid : -1;
        if (shouldAnimationPreemptLaunchLocked(animationPid)) {
            return animationPid;
        }
        if (isAppLaunchActiveLocked()) {
            return mAppLaunchState.pid;
        }
        if (animationPid > 0) {
            return animationPid;
        }
        return remoteAnimationPerfTargetPidLocked();
    }

    private boolean shouldAnimationPreemptLaunchLocked(int pid) {
        return pid > 0 && isAppLaunchActiveLocked()
                && (pid == mSystemUi.pid || mLauncher.hasPid(pid));
    }

    private int animationUiPerfTargetPidLocked(int pid) {
        if (pid <= 0) {
            return -1;
        }
        if (pid == mSystemUi.pid) {
            return pid;
        }
        if (mLauncher.hasPid(pid)) {
            return pid;
        }
        if (mTopApp.hasPid(pid) && isTopAppGroup(mTopApp.group)) {
            return pid;
        }
        if (mRemoteAnimationStates.get(pid) != null) {
            return pid;
        }
        return -1;
    }

    private int remoteAnimationPerfTargetPidLocked() {
        if (!hasRemoteAnimationsLocked()) {
            return -1;
        }
        if (mLauncher.hasProcess() && currentGroupForPid(mLauncher.pid) != THREAD_GROUP_DEFAULT) {
            return mLauncher.pid;
        }
        for (int i = 0; i < mRemoteAnimationStates.size(); i++) {
            final int pid = mRemoteAnimationStates.keyAt(i);
            if (pid > 0) {
                return pid;
            }
        }
        return -1;
    }

    private void updateBurstSchedulingLocked(int pid) {
        if (pid <= 0) {
            updateTopAppSchedulingLocked();
            return;
        }
        mBurstScheduler.set(burstSessionLocked(pid, Process.SYSTEM_UID,
                AxUiSession.SEVERITY_HEAVY));
    }

    private void updateTopAppSchedulingLocked() {
        if (mTopApp.hasProcess() && isTopAppGroup(mTopApp.group)) {
            if (isTopAppPerfActiveLocked(SystemClock.uptimeMillis())) {
                return;
            }
            if (mGameModeActive && mBurstScheduler.set(gameSessionLocked())) {
                mGameModePid = mTopApp.pid;
                return;
            }
            mBurstScheduler.set(topAppSessionLocked());
            return;
        }
        mGameModePid = -1;
        mBurstScheduler.clearAll();
    }

    private AxUiSession topAppSessionLocked() {
        final int uid = mTopApp.uid >= 0 ? mTopApp.uid : Process.SYSTEM_UID;
        return AxUiSession.createForTidWithRole(mTopApp.pid, uid, AxBurstScheduler.MODE_TOP_APP,
                AxUiSession.SOURCE_TOP_APP, AxUiSession.SEVERITY_LIGHT, 0L,
                mTopApp.renderThreadTid, AxUiSession.ROLE_TOP_APP);
    }

    private AxUiSession gameSessionLocked() {
        final int uid = mTopApp.uid >= 0 ? mTopApp.uid : Process.SYSTEM_UID;
        return AxUiSession.createForTidWithRole(mTopApp.pid, uid, AxBurstScheduler.MODE_PERF,
                AxUiSession.SOURCE_GAME, AxUiSession.SEVERITY_LIGHT, 0L,
                mTopApp.renderThreadTid, AxUiSession.ROLE_TOP_APP);
    }

    private boolean isTopAppPerfActiveLocked(long now) {
        return mTopAppPerfPid > 0 && mTopApp.hasPid(mTopAppPerfPid)
                && mTopAppPerfExpiryUptimeMs > now;
    }

    private void clearTopAppPerfStateLocked() {
        mTopAppPerfToken = new Object();
        mTopAppPerfPid = -1;
        mTopAppPerfSource = -1;
        mTopAppPerfSeverity = AxUiSession.SEVERITY_LIGHT;
        mTopAppPerfExpiryUptimeMs = 0L;
    }

    private AxUiSession burstSessionLocked(int pid, int uid, int severity) {
        final int mode = burstModeLocked(pid);
        return AxUiSession.createForTidWithRole(pid, uid, mode, burstSourceForMode(mode),
                severity, burstDurationMsLocked(pid), renderThreadTidForPidLocked(pid),
                roleForPidLocked(pid));
    }

    private int roleForPidLocked(int pid) {
        if (pid == Process.myPid()) {
            return AxUiSession.ROLE_SYSTEM_SERVER;
        }
        if (pid == mSystemUi.pid) {
            return AxUiSession.ROLE_SYSTEM_UI;
        }
        if (mLauncher.hasPid(pid)) {
            return AxUiSession.ROLE_LAUNCHER;
        }
        if (mTopApp.hasPid(pid)) {
            return AxUiSession.ROLE_TOP_APP;
        }
        return AxUiSession.ROLE_APP;
    }

    private int renderThreadTidForPidLocked(int pid) {
        if (pid == mSystemUi.pid) {
            return mSystemUi.renderThreadTid;
        }
        if (mLauncher.hasPid(pid)) {
            return mLauncher.renderThreadTid;
        }
        if (mTopApp.hasPid(pid)) {
            return mTopApp.renderThreadTid;
        }
        return -1;
    }

    private static int burstSourceForMode(int mode) {
        switch (mode) {
            case AxBurstScheduler.MODE_LAUNCH:
                return AxUiSession.SOURCE_LAUNCH;
            case AxBurstScheduler.MODE_ANIMATION:
                return AxUiSession.SOURCE_ANIMATION;
            case AxBurstScheduler.MODE_REMOTE:
                return AxUiSession.SOURCE_REMOTE;
            case AxBurstScheduler.MODE_TOP_APP:
                return AxUiSession.SOURCE_TOP_APP;
            default:
                return AxUiSession.SOURCE_ANIMATION;
        }
    }

    private int burstModeLocked(int pid) {
        if (isAppLaunchActiveLocked() && mAppLaunchState.hasPid(pid)) {
            return AxBurstScheduler.MODE_LAUNCH;
        }
        if (isAnimationUiPerfModeActiveLocked() && mAnimationUiPerfPid == pid) {
            return AxBurstScheduler.MODE_ANIMATION;
        }
        return AxBurstScheduler.MODE_REMOTE;
    }

    private long burstDurationMsLocked(int pid) {
        final long now = SystemClock.uptimeMillis();
        if (isAppLaunchActiveLocked() && mAppLaunchState.hasPid(pid)) {
            return Math.max(1L, mAppLaunchState.expiryUptimeMs - now);
        }
        if (isAnimationUiPerfModeActiveLocked() && mAnimationUiPerfPid == pid) {
            return Math.max(1L, mAnimationUiPerfExpiryUptimeMs - now);
        }
        return REMOTE_ANIMATION_TIMEOUT_MS;
    }

    private static long boundedAppLaunchDurationMs(long durationMs) {
        return Math.min(Math.max(durationMs, APP_LAUNCH_MIN_TIMEOUT_MS), APP_LAUNCH_TIMEOUT_MS);
    }

    private static long boundedTopAppPerfDurationMs(long durationMs) {
        return Math.min(Math.max(durationMs, 1L), TOP_APP_PERF_MAX_MS);
    }

    private long appLaunchFinishLingerMsLocked() {
        final long remainingMs = mAppLaunchState.expiryUptimeMs - SystemClock.uptimeMillis();
        return Math.min(Math.max(remainingMs / 2, APP_LAUNCH_FINISH_LINGER_MIN_MS),
                APP_LAUNCH_FINISH_LINGER_MAX_MS);
    }

    private void deferBackgroundIoLocked(long durationMs) {
        if (durationMs <= 0L) {
            return;
        }
        mBackgroundIoDeferUptimeMs = Math.max(mBackgroundIoDeferUptimeMs,
                SystemClock.uptimeMillis() + durationMs);
    }

    private static void boostDisplayWarmup(long durationMs) {
        if (durationMs > 0) {
            AxRefreshRateController.getInstance().setAnimationBoost(durationMs);
        }
    }

    private static void clearDisplayWarmup() {
        AxRefreshRateController.getInstance().clearAnimationBoost();
    }

    private boolean shouldClearDisplayWarmupLocked() {
        return !isAnimationUiPerfModeActiveLocked()
                && !isAppLaunchActiveLocked()
                && !hasRemoteAnimationsLocked()
                && !isDisplayWarmTopAppPerfActiveLocked(SystemClock.uptimeMillis());
    }

    private boolean isDisplayWarmTopAppPerfActiveLocked(long now) {
        return isTopAppPerfActiveLocked(now) && shouldWarmDisplayForTopAppPerf(mTopAppPerfSource);
    }

    private static boolean shouldWarmDisplayForTopAppPerf(int source) {
        switch (source) {
            case AxUiSession.SOURCE_ADPF_GPU:
            case AxUiSession.SOURCE_POWER_INTERACTION:
            case AxUiSession.SOURCE_POWER_DISPLAY:
            case AxUiSession.SOURCE_POWER_LAUNCH:
            case AxUiSession.SOURCE_POWER_RENDER:
            case AxUiSession.SOURCE_GAME_LOADING:
                return true;
            default:
                return false;
        }
    }

    private static TopAppPerfRequest powerBoostRequest(int boost, int durationMs) {
        if (durationMs < 0) {
            switch (boost) {
                case Boost.INTERACTION:
                    return TopAppPerfRequest.restore(AxUiSession.SOURCE_POWER_INTERACTION);
                case Boost.DISPLAY_UPDATE_IMMINENT:
                    return TopAppPerfRequest.restore(AxUiSession.SOURCE_POWER_DISPLAY);
                default:
                    return null;
            }
        }
        final long boundedDurationMs = durationMs > 0 ? durationMs : TOP_APP_PERF_LIGHT_MS;
        switch (boost) {
            case Boost.INTERACTION:
                return TopAppPerfRequest.current(AxUiSession.SOURCE_POWER_INTERACTION,
                        AxUiSession.SEVERITY_LIGHT, boundedDurationMs);
            case Boost.DISPLAY_UPDATE_IMMINENT:
                return TopAppPerfRequest.current(AxUiSession.SOURCE_POWER_DISPLAY,
                        AxUiSession.SEVERITY_LIGHT, TOP_APP_PERF_DISPLAY_MS);
            default:
                return null;
        }
    }

    private static TopAppPerfRequest powerModeRequest(int mode, boolean enabled) {
        if (!enabled) {
            switch (mode) {
                case Mode.LAUNCH:
                    return TopAppPerfRequest.restore(AxUiSession.SOURCE_POWER_LAUNCH);
                case Mode.EXPENSIVE_RENDERING:
                    return TopAppPerfRequest.restore(AxUiSession.SOURCE_POWER_RENDER);
                case Mode.GAME_LOADING:
                    return TopAppPerfRequest.restore(AxUiSession.SOURCE_GAME_LOADING);
                default:
                    return null;
            }
        }
        switch (mode) {
            case Mode.LAUNCH:
                return TopAppPerfRequest.current(AxUiSession.SOURCE_POWER_LAUNCH,
                        AxUiSession.SEVERITY_HEAVY, TOP_APP_PERF_LAUNCH_MS);
            case Mode.EXPENSIVE_RENDERING:
                return TopAppPerfRequest.current(AxUiSession.SOURCE_POWER_RENDER,
                        AxUiSession.SEVERITY_HEAVY, TOP_APP_PERF_HEAVY_MS);
            case Mode.GAME_LOADING:
                return TopAppPerfRequest.current(AxUiSession.SOURCE_GAME_LOADING,
                        AxUiSession.SEVERITY_HEAVY, TOP_APP_PERF_LAUNCH_MS);
            default:
                return null;
        }
    }

    private static TopAppPerfRequest adpfHintRequest(int pid, int uid, int tag,
            boolean graphicsPipeline, int hint) {
        final boolean graphics = graphicsPipeline || tag == SessionTag.GAME;
        switch (hint) {
            case PerformanceHintManager.Session.GPU_LOAD_UP:
                return new TopAppPerfRequest(pid, uid, AxUiSession.SOURCE_ADPF_GPU,
                        AxUiSession.SEVERITY_HEAVY, TOP_APP_PERF_HEAVY_MS);
            case PerformanceHintManager.Session.CPU_LOAD_UP:
                return new TopAppPerfRequest(pid, uid, graphics
                        ? AxUiSession.SOURCE_ADPF_GPU : AxUiSession.SOURCE_ADPF_CPU,
                        graphics ? AxUiSession.SEVERITY_HEAVY : AxUiSession.SEVERITY_LIGHT,
                        graphics ? TOP_APP_PERF_HEAVY_MS : TOP_APP_PERF_LIGHT_MS);
            case PerformanceHintManager.Session.CPU_LOAD_RESET:
            case PerformanceHintManager.Session.CPU_LOAD_RESUME:
                return new TopAppPerfRequest(pid, uid, AxUiSession.SOURCE_ADPF_CPU,
                        AxUiSession.SEVERITY_LIGHT, TOP_APP_PERF_LIGHT_MS);
            default:
                return null;
        }
    }

    private static int animationUiPerfRequestRank(ProcessRecordInternal app) {
        if (app == null) {
            return UI_ANIMATION_REQUEST_APP;
        }
        if (app.isSystemUi()) {
            return UI_ANIMATION_REQUEST_SYSTEMUI;
        }
        if (app.isLauncher3()) {
            return UI_ANIMATION_REQUEST_LAUNCHER;
        }
        return UI_ANIMATION_REQUEST_APP;
    }

    private int requestAnimationUiPerf(ProcessRecordInternal app, int pid,
            long expiryUptimeMs) {
        final int rank = animationUiPerfRequestRank(app);
        synchronized (mAnimationUiPerfRequestLock) {
            if (mAnimationUiPerfRequestPid > 0
                    && mAnimationUiPerfRequestExpiryUptimeMs > SystemClock.uptimeMillis()
                    && rank < mAnimationUiPerfRequestRank) {
                return ANIMATION_REQUEST_REJECTED;
            }
            if (rank == mAnimationUiPerfRequestRank) {
                if (pid == mAnimationUiPerfRequestPid
                        && expiryUptimeMs <= mAnimationUiPerfRequestExpiryUptimeMs
                                + UI_ANIMATION_RESCHEDULE_SLOP_MS) {
                    return ANIMATION_REQUEST_REJECTED;
                }
                if (pid != mAnimationUiPerfRequestPid && mAnimationUiPerfQueued
                        && expiryUptimeMs <= mAnimationUiPerfRequestExpiryUptimeMs) {
                    return ANIMATION_REQUEST_REJECTED;
                }
            }
            mAnimationUiPerfRequestApp = app;
            mAnimationUiPerfRequestPid = pid;
            mAnimationUiPerfRequestExpiryUptimeMs = expiryUptimeMs;
            mAnimationUiPerfRequestRank = rank;
            if (mAnimationUiPerfQueued) {
                return ANIMATION_REQUEST_ACCEPTED;
            }
            mAnimationUiPerfQueued = true;
            return ANIMATION_REQUEST_SCHEDULED;
        }
    }

    private void applyAnimationUiPerf() {
        final ProcessRecordInternal app;
        final int pid;
        final long expiryUptimeMs;
        synchronized (mAnimationUiPerfRequestLock) {
            mAnimationUiPerfQueued = false;
            app = mAnimationUiPerfRequestApp;
            pid = mAnimationUiPerfRequestPid;
            expiryUptimeMs = mAnimationUiPerfRequestExpiryUptimeMs;
        }
        final Object token = new Object();
        long durationMs = 0;
        int boostPid = -1;
        boolean clearDisplayWarmup = false;
        synchronized (mLock) {
            trackSpecialProcessLocked(app);
            final int targetPid = animationUiPerfTargetPidLocked(pid);
            final long now = SystemClock.uptimeMillis();
            if (targetPid <= 0) {
                clearAnimationUiPerfRequest(pid, expiryUptimeMs);
                clearDisplayWarmup = shouldClearDisplayWarmupLocked();
            } else if (expiryUptimeMs <= now) {
                clearAnimationUiPerfRequest(pid, expiryUptimeMs);
                clearDisplayWarmup = shouldClearDisplayWarmupLocked();
            } else if (mAnimationUiPerfActive
                    && targetPid == mAnimationUiPerfPid
                    && expiryUptimeMs <= mAnimationUiPerfExpiryUptimeMs
                            + UI_ANIMATION_RESCHEDULE_SLOP_MS) {
                return;
            } else {
                mAnimationUiPerfToken = token;
                mAnimationUiPerfActive = true;
                mAnimationUiPerfPid = targetPid;
                mAnimationUiPerfExpiryUptimeMs = expiryUptimeMs;
                durationMs = expiryUptimeMs - now;
                updateUiPerfModeLocked();
                boostPid = targetPid;
            }
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
        if (boostPid <= 0 || durationMs <= 0) {
            return;
        }
        boostSystemServerRenderPath(AxBurstScheduler.MODE_ANIMATION, AxUiSession.SOURCE_ANIMATION,
                AxUiSession.SEVERITY_HEAVY, durationMs);
        boostAnimationRenderPath(boostPid, app != null ? app.uid : Process.SYSTEM_UID, durationMs);
        final Message message = PooledLambda.obtainMessage(
                AxBurstEngineImpl::clearAnimationUiPerfIfCurrent, this, token);
        message.setAsynchronous(true);
        mHandler.sendMessageDelayed(message, durationMs);
    }

    private void boostSystemServerRenderPath(int mode, int source, int severity, long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        mBurstScheduler.set(AxUiSession.createForTidsWithRole(Process.myPid(),
                Process.SYSTEM_UID, mode, source, severity, durationMs,
                AnimationThread.get().getThreadId(), SurfaceAnimationThread.get().getThreadId(),
                DisplayThread.get().getThreadId(), mThread.getThreadId(),
                AxUiSession.ROLE_SYSTEM_SERVER));
    }

    private void clearAnimationUiPerfIfCurrent(Object token) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            if (token != mAnimationUiPerfToken) {
                return;
            }
            if (hasPendingAnimationUiPerfExtension(
                    mAnimationUiPerfPid, mAnimationUiPerfExpiryUptimeMs)) {
                return;
            }
            clearAnimationUiPerfForRequestLocked(
                    mAnimationUiPerfPid, mAnimationUiPerfExpiryUptimeMs);
            updateUiPerfModeLocked();
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void clearAnimationUiPerfForPid(int pid) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            clearAnimationUiPerfForPidLocked(pid);
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void clearAnimationUiPerfForPidLocked(int pid) {
        if (pid <= 0 || mAnimationUiPerfPid != pid) {
            return;
        }
        clearAnimationUiPerfLocked();
        updateUiPerfModeLocked();
    }

    private static void boostAnimationRenderPath(int pid, int uid, long durationMs) {
        if (pid <= 0 || durationMs <= 0) {
            return;
        }
        final AxFrameRescueInternal frameRescue =
                LocalServices.getService(AxFrameRescueInternal.class);
        if (frameRescue == null) {
            return;
        }
        frameRescue.onFrameRescue(pid, uid, AxFrameRescue.SOURCE_ANIMATION_RENDER,
                AxFrameRescue.LEVEL_HEAVY, 0L, 0L,
                (int) Math.min(durationMs, UI_ANIMATION_MAX_DURATION_MS));
    }

    private boolean hasPendingAnimationUiPerfExtension(int pid, long expiryUptimeMs) {
        synchronized (mAnimationUiPerfRequestLock) {
            return pid == mAnimationUiPerfRequestPid
                    && mAnimationUiPerfRequestExpiryUptimeMs > expiryUptimeMs
                            + UI_ANIMATION_RESCHEDULE_SLOP_MS;
        }
    }

    private void clearAnimationUiPerfLocked() {
        clearAnimationUiPerfLocked(true);
    }

    private void clearAnimationUiPerfLocked(boolean clearRequest) {
        if (clearRequest) {
            synchronized (mAnimationUiPerfRequestLock) {
                mAnimationUiPerfQueued = false;
                mAnimationUiPerfRequestApp = null;
                mAnimationUiPerfRequestPid = -1;
                mAnimationUiPerfRequestExpiryUptimeMs = 0;
                mAnimationUiPerfRequestRank = 0;
            }
        }
        clearAnimationUiPerfStateLocked();
    }

    private void clearAnimationUiPerfForRequestLocked(int pid, long expiryUptimeMs) {
        clearAnimationUiPerfRequest(pid, expiryUptimeMs);
        clearAnimationUiPerfStateLocked();
    }

    private void clearAnimationUiPerfRequest(int pid, long expiryUptimeMs) {
        synchronized (mAnimationUiPerfRequestLock) {
            if (pid == mAnimationUiPerfRequestPid
                    && mAnimationUiPerfRequestExpiryUptimeMs <= expiryUptimeMs
                            + UI_ANIMATION_RESCHEDULE_SLOP_MS) {
                mAnimationUiPerfRequestApp = null;
                mAnimationUiPerfRequestPid = -1;
                mAnimationUiPerfRequestExpiryUptimeMs = 0;
                mAnimationUiPerfRequestRank = 0;
            }
        }
    }

    private void clearAnimationUiPerfStateLocked() {
        mAnimationUiPerfToken = new Object();
        mAnimationUiPerfActive = false;
        mAnimationUiPerfPid = -1;
        mAnimationUiPerfExpiryUptimeMs = 0;
    }

    private void clearAppLaunchIfCurrent(Object token) {
        final boolean clearDisplayWarmup;
        synchronized (mLock) {
            if (token != mAppLaunchState.token) {
                return;
            }
            clearAppLaunchStateLocked();
            updateUiPerfModeLocked();
            clearDisplayWarmup = shouldClearDisplayWarmupLocked();
        }
        if (clearDisplayWarmup) {
            clearDisplayWarmup();
        }
    }

    private void clearAppLaunchStateLocked() {
        mAppLaunchState.clear();
    }

    private void scheduleUiPerfModeReleaseLocked() {
        if (mUiPerfModePid <= 0) {
            clearUiPerfModeLocked();
            return;
        }
        final Object token = new Object();
        mUiPerfReleaseToken = token;
        mHandler.postDelayed(() -> clearUiPerfModeIfCurrent(token),
                UI_PERF_RELEASE_LINGER_MS);
    }

    private void clearUiPerfModeIfCurrent(Object token) {
        synchronized (mLock) {
            if (token != mUiPerfReleaseToken || uiPerfModeTargetPidLocked() > 0) {
                return;
            }
            clearUiPerfModeLocked();
        }
    }

    private void clearUiPerfModeLocked() {
        mUiPerfReleaseToken = new Object();
        setUiPerfModePidLocked(-1);
        updateTopAppSchedulingLocked();
    }

    private void setUiPerfModePidLocked(int pid) {
        final int targetPid = pid > 0 ? pid : -1;
        if (mUiPerfModePid == targetPid) {
            return;
        }
        mUiPerfModePid = targetPid;
    }

    private static boolean isTopAppGroup(int group) {
        return group == THREAD_GROUP_TOP_APP;
    }

    private int currentGroupForPid(int pid) {
        if (mTopApp.hasPid(pid)) {
            return mTopApp.group;
        }
        if (pid == mSystemUi.pid) {
            return THREAD_GROUP_SYSTEMUI;
        }
        if (mLauncher.hasPid(pid) && mLauncher.group != THREAD_GROUP_DEFAULT) {
            return mLauncher.group;
        }
        final RemoteAnimationState state = mRemoteAnimationStates.get(pid);
        return state != null ? state.processGroup : THREAD_GROUP_DEFAULT;
    }

    static int mapProcessGroup(ProcessRecordInternal app, int curSchedGroup) {
        if (app.isSystemUi()) {
            return THREAD_GROUP_SYSTEMUI;
        }
        int res;
        switch (curSchedGroup) {
            case SCHED_GROUP_BACKGROUND:
                final boolean lowPrioBg = app.getCurAdj() >= ProcessList.CACHED_APP_MIN_ADJ;
                res = lowPrioBg ? THREAD_GROUP_L_BACKGROUND : THREAD_GROUP_H_BACKGROUND;
                break;
            case SCHED_GROUP_TOP_APP:
            case SCHED_GROUP_TOP_APP_BOUND:
                res = THREAD_GROUP_TOP_APP;
                break;
            case SCHED_GROUP_RESTRICTED:
                res = THREAD_GROUP_RESTRICTED;
                break;
            case SCHED_GROUP_FOREGROUND_WINDOW:
                res = THREAD_GROUP_FOREGROUND_WINDOW;
                break;
            default:
                res = THREAD_GROUP_DEFAULT;
                break;
        }
        return res;
    }

    private static final class TopAppPerfRequest {
        final int pid;
        final int uid;
        final int source;
        final int severity;
        final long durationMs;
        final boolean restore;

        TopAppPerfRequest(int pid, int uid, int source, int severity, long durationMs) {
            this(pid, uid, source, severity, durationMs, false);
        }

        static TopAppPerfRequest current(int source, int severity, long durationMs) {
            return new TopAppPerfRequest(-1, -1, source, severity, durationMs);
        }

        static TopAppPerfRequest restore(int source) {
            return new TopAppPerfRequest(-1, -1, source, AxUiSession.SEVERITY_LIGHT, 0L, true);
        }

        private TopAppPerfRequest(int pid, int uid, int source, int severity, long durationMs,
                boolean restore) {
            this.pid = pid;
            this.uid = uid;
            this.source = source;
            this.severity = severity;
            this.durationMs = durationMs;
            this.restore = restore;
        }
    }

    private static final class TrackedProcess {
        int pid = -1;
        int uid = -1;
        int group = THREAD_GROUP_DEFAULT;
        int renderThreadTid = -1;

        boolean hasPid(int pid) {
            return this.pid > 0 && this.pid == pid;
        }

        boolean hasProcess() {
            return pid > 0;
        }

        void setPid(int pid, int uid, int renderThreadTid) {
            this.pid = pid;
            this.uid = uid;
            this.renderThreadTid = renderThreadTid;
            group = THREAD_GROUP_DEFAULT;
        }

        void set(int pid, int uid, int group, int renderThreadTid) {
            this.pid = pid;
            this.uid = uid;
            this.group = group;
            this.renderThreadTid = renderThreadTid;
        }

        void clear() {
            pid = -1;
            uid = -1;
            group = THREAD_GROUP_DEFAULT;
            renderThreadTid = -1;
        }
    }

    private static final class RemoteAnimationState {
        int count;
        int generation;
        int processGroup = THREAD_GROUP_DEFAULT;
    }

    private static final class AppLaunchState {
        int pid = -1;
        long expiryUptimeMs;
        Object token = new Object();

        boolean hasPid(int pid) {
            return this.pid > 0 && this.pid == pid;
        }

        boolean hasProcess() {
            return pid > 0;
        }

        boolean isActive(long uptimeMs) {
            return pid > 0 && uptimeMs <= expiryUptimeMs;
        }

        void set(int pid, long expiryUptimeMs, Object token) {
            this.pid = pid;
            this.expiryUptimeMs = expiryUptimeMs;
            this.token = token;
        }

        void clear() {
            pid = -1;
            expiryUptimeMs = 0;
            token = new Object();
        }
    }
}
