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
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.server.DisplayThread;

import java.util.concurrent.atomic.AtomicInteger;

public class AxRefreshRateController {

    public interface RefreshRateUpdateCallback {
        void onRefreshRateChanged(float min, float peak, int displayId);
    }

    private static final String TAG = "AxRefreshRateController";

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";

    private static final int BOOST_TOUCH = 1;
    private static final int BOOST_FOCUS = 1 << 2;
    private static final int BOOST_FLING = 1 << 3;
    private static final int BOOST_ANIMATION = 1 << 4;

    private static final long DISPLAY_CHANGE_REQUERY_DELAY_MS = 500;
    private static final float PEAK_REFRESH_RATE_OFFSET = 1.0f;
    private static final float KEYGUARD_MIN_REFRESH_RATE_HZ = 60f;

    private static final AxRefreshRateController sInstance = new AxRefreshRateController();

    private static final boolean sSupportsVrr = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);
    private static final boolean sNeedsHfrFlickerFix = SystemProperties.getBoolean(
            "persist.sys.vrr_needs_hfr_flicker_fix", false);

    private Context mContext;
    private Handler mHandler;
    private WindowManagerService mWmService;

    private volatile RefreshRateUpdateCallback mRateCallback;
    private volatile boolean mInitialized;

    private volatile float mMaxSupportedHz = 60f;
    private volatile float mDefaultMinHz = 60f;
    private volatile float mLowestKeyguardHz = 60f;

    private float mCustomListMin;
    private float mCustomListMax;
    private float mCustomListLowestKeyguard = Float.MAX_VALUE;
    private boolean mHasCustomList;

    private volatile boolean mVrrEnabled;
    private volatile int mFixedRefreshRate = 60;
    private volatile boolean mLockscreenLimitEnabled;
    private volatile boolean mKeyguardDone = true;
    private volatile boolean mAppOverrideActive;
    private volatile float mPerAppOverrideRate;
    private volatile String mFocusedPackage = "";

    private final AtomicInteger mActiveBoosts = new AtomicInteger();
    private long mIdleTimeoutMs;
    private long mFocusBoostTimeoutMs;
    private volatile long mLastActivityTime;

    private boolean mCurrentVoteActive;
    private float mCurrentVoteMin;
    private float mCurrentVoteMax;

    @GuardedBy("mUserAppRefreshRates")
    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    private volatile float mLastSyncedPeak = -1f;
    private volatile float mLastSyncedMin = -1f;
    private volatile String mLastSource = "";

    private final Runnable mSyncRunnable = this::syncDisplaySettings;
    private final Runnable mForceSyncRunnable = () -> {
        invalidateLastSync();
        syncDisplaySettings();
    };
    private final Runnable mDisplayChangeRequeryRunnable = () -> {
        queryAndApplyDisplayModes();
        invalidateLastSync();
        syncDisplaySettings();
    };
    private final Runnable mFlingBoostTimeoutRunnable = () -> {
        if (clearBoost(BOOST_FLING)) {
            syncDisplaySettings();
        }
    };
    private final Runnable mAnimationBoostTimeoutRunnable = () -> {
        if (clearBoost(BOOST_ANIMATION)) {
            syncDisplaySettings();
        }
    };

    private AxRefreshRateController() {}

    public static AxRefreshRateController getInstance() {
        return sInstance;
    }

    public void init(Context context, WindowManagerService wms) {
        if (mInitialized) {
            return;
        }
        mContext = context;
        mWmService = wms;
        mHandler = DisplayThread.getHandler();
        mIdleTimeoutMs = SystemProperties.getLong("persist.sys.ax.idle_timeout_ms", 5000);
        mFocusBoostTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.focus_boost_timeout_ms", 5000);

        parseCustomRefreshRateList();
        queryAndApplyDisplayModes();
        loadRefreshRateSetting();
        loadPerAppRefreshRates();

        mInitialized = true;
        new SettingsObserver();
        forceResync();
        mHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
    }

    public void setRefreshRateUpdateCallback(RefreshRateUpdateCallback callback) {
        mRateCallback = callback;
        forceResync();
    }

    public void onPointerEvent() {
        if (!mInitialized || !mVrrEnabled || (mKeyguardDone && mAppOverrideActive)) {
            return;
        }
        if (mLockscreenLimitEnabled && !mKeyguardDone) {
            return;
        }
        if (setBoost(BOOST_TOUCH)) {
            mHandler.post(mSyncRunnable);
        }
    }

    public void setFlingBoost(long durationMillis) {
        if (!mInitialized || !mVrrEnabled) {
            return;
        }
        mHandler.post(() -> setFlingBoostInternal(durationMillis));
    }

    public void setAnimationBoost(long durationMillis) {
        if (!mInitialized || !mVrrEnabled) {
            return;
        }
        mHandler.post(() -> setTimedBoost(BOOST_ANIMATION, durationMillis,
                mAnimationBoostTimeoutRunnable));
    }

    public void clearAnimationBoost() {
        if (!mInitialized || !mVrrEnabled) {
            return;
        }
        mHandler.post(() -> {
            mHandler.removeCallbacks(mAnimationBoostTimeoutRunnable);
            if (clearBoost(BOOST_ANIMATION)) {
                syncDisplaySettings();
            }
        });
    }

    public void setKeyguardDone(boolean done) {
        if (mKeyguardDone == done) {
            return;
        }
        mKeyguardDone = done;
        if (!mInitialized) {
            return;
        }
        if (!done) {
            clearAllBoosts();
        } else if (mVrrEnabled && !mAppOverrideActive) {
            setBoost(BOOST_FOCUS);
        }
        mHandler.post(mSyncRunnable);
        requestTraversal();
    }

    public void updateFocusedApp(ActivityRecord activityRecord) {
        if (!mInitialized || activityRecord == null) {
            return;
        }
        final String packageName = activityRecord.packageName == null
                ? "" : activityRecord.packageName;
        mHandler.post(() -> handleFocusedPackage(packageName));
    }

    public void onDisplayChanged() {
        if (!mInitialized) {
            return;
        }
        mHandler.post(() -> {
            queryAndApplyDisplayModes();
            invalidateLastSync();
            mHandler.removeCallbacks(mDisplayChangeRequeryRunnable);
            syncDisplaySettings();
            mHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
        });
    }

    public void forceResync() {
        if (!mInitialized) {
            return;
        }
        mHandler.post(mForceSyncRunnable);
    }

    public void updateVoteResult() {
        if (!mInitialized) {
            return;
        }
        clearCurrentVote();

        if (!mKeyguardDone) {
            if (!mLockscreenLimitEnabled && mVrrEnabled && hasEffectiveBoost()) {
                setCurrentVote(mMaxSupportedHz, mMaxSupportedHz);
            } else if (sNeedsHfrFlickerFix || mLockscreenLimitEnabled) {
                final float keyguardHz = resolveKeyguardRefreshRate();
                setCurrentVote(keyguardHz, keyguardHz);
            }
        } else if (mAppOverrideActive && mPerAppOverrideRate > 0f) {
            setCurrentVote(mPerAppOverrideRate, mPerAppOverrideRate);
        } else if (mVrrEnabled && hasEffectiveBoost()) {
            setCurrentVote(mMaxSupportedHz, mMaxSupportedHz);
        } else if (!mVrrEnabled) {
            final float rate = resolveLockedRefreshRate();
            setCurrentVote(rate, rate);
        }
    }

    public boolean hasActiveVote() {
        return mCurrentVoteActive;
    }

    public boolean shouldSuppressAppRefreshRateRequests() {
        return mInitialized;
    }

    public float getMaxPreferredRate() {
        return mCurrentVoteMax;
    }

    public float getMinPreferredRate() {
        return mCurrentVoteMin;
    }

    private void setFlingBoostInternal(long durationMillis) {
        setTimedBoost(BOOST_FLING, durationMillis, mFlingBoostTimeoutRunnable);
    }

    private void setTimedBoost(int flag, long durationMillis, Runnable timeoutRunnable) {
        mHandler.removeCallbacks(timeoutRunnable);
        if ((mKeyguardDone && mAppOverrideActive)
                || (mLockscreenLimitEnabled && !mKeyguardDone) || durationMillis <= 0) {
            if (clearBoost(flag)) {
                syncDisplaySettings();
            }
            return;
        }

        if (setBoost(flag)) {
            syncDisplaySettings();
        }
        mHandler.postDelayed(timeoutRunnable, durationMillis);
    }

    private void handleFocusedPackage(String packageName) {
        mFocusedPackage = packageName;
        updateFocusedAppOverride();
        if (mVrrEnabled && !mAppOverrideActive && mKeyguardDone) {
            setBoost(BOOST_FOCUS);
        }
        syncDisplaySettings();
        requestTraversal();
    }

    private long getVrrTimeout() {
        return hasBoost(BOOST_FOCUS) ? mFocusBoostTimeoutMs : mIdleTimeoutMs;
    }

    private boolean setBoost(int flag) {
        mLastActivityTime = SystemClock.uptimeMillis();
        int activeBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & flag) != 0) {
                return false;
            }
        } while (!mActiveBoosts.compareAndSet(activeBoosts, activeBoosts | flag));
        return true;
    }

    private boolean clearBoost(int flag) {
        int activeBoosts;
        int newBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & flag) == 0) {
                return false;
            }
            newBoosts = activeBoosts & ~flag;
        } while (!mActiveBoosts.compareAndSet(activeBoosts, newBoosts));
        return true;
    }

    private void clearAllBoosts() {
        mHandler.removeCallbacks(mFlingBoostTimeoutRunnable);
        mHandler.removeCallbacks(mAnimationBoostTimeoutRunnable);
        mActiveBoosts.set(0);
    }

    private boolean hasBoost(int flag) {
        return (mActiveBoosts.get() & flag) != 0;
    }

    private boolean hasBoost() {
        return mActiveBoosts.get() != 0;
    }

    private boolean hasEffectiveBoost() {
        if (!hasBoost()) {
            return false;
        }
        if (hasBoost(BOOST_FLING)) {
            return true;
        }
        final long elapsed = SystemClock.uptimeMillis() - mLastActivityTime;
        if (elapsed < getVrrTimeout()) {
            return true;
        }
        mActiveBoosts.set(0);
        return false;
    }

    private float resolveUserRefreshRate(Float rate) {
        if (rate == null || rate <= 0f) {
            return 0f;
        }
        return Math.max(mDefaultMinHz, Math.min(rate, mMaxSupportedHz));
    }

    private void scheduleSyncDisplaySettings(long delayMillis) {
        mHandler.removeCallbacks(mSyncRunnable);
        mHandler.postDelayed(mSyncRunnable, delayMillis);
    }

    private void invalidateLastSync() {
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        mLastSource = "";
    }

    private void setCurrentVote(float min, float max) {
        mCurrentVoteActive = true;
        mCurrentVoteMin = min;
        mCurrentVoteMax = max;
    }

    private void clearCurrentVote() {
        mCurrentVoteActive = false;
        mCurrentVoteMin = 0f;
        mCurrentVoteMax = 0f;
    }

    private void parseCustomRefreshRateList() {
        final String customList = SystemProperties.get(
                "persist.sys.display_refresh_rates_list", "");
        if (customList.isEmpty()) {
            return;
        }
        try {
            float parsedMin = Float.MAX_VALUE;
            float parsedMax = 0f;
            float parsedLowestKeyguard = Float.MAX_VALUE;
            for (String rateString : customList.split(",")) {
                final float rate = Float.parseFloat(rateString.trim());
                if (rate <= 0f) {
                    continue;
                }
                if (rate < parsedMin) {
                    parsedMin = rate;
                }
                if (rate > parsedMax) {
                    parsedMax = rate;
                }
                if (rate >= KEYGUARD_MIN_REFRESH_RATE_HZ && rate < parsedLowestKeyguard) {
                    parsedLowestKeyguard = rate;
                }
            }
            if (parsedMax > 0f && parsedMin != Float.MAX_VALUE) {
                mCustomListMin = parsedMin;
                mCustomListMax = parsedMax;
                mCustomListLowestKeyguard = parsedLowestKeyguard;
                mHasCustomList = true;
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse persist.sys.display_refresh_rates_list: "
                    + customList, e);
        }
    }

    private void queryAndApplyDisplayModes() {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        if (displayManager == null) {
            return;
        }
        final Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return;
        }

        float newMax = 60f;
        float newMin = 60f;
        float newLowestKeyguard = Float.MAX_VALUE;

        if (mHasCustomList) {
            newMax = mCustomListMax;
            newMin = mCustomListMin;
            newLowestKeyguard = mCustomListLowestKeyguard;
        } else {
            newMin = Float.MAX_VALUE;
            for (Display.Mode mode : display.getSupportedModes()) {
                final float hz = mode.getRefreshRate();
                if (hz > newMax) {
                    newMax = hz;
                }
                if (hz < newMin) {
                    newMin = hz;
                }
                if (hz >= KEYGUARD_MIN_REFRESH_RATE_HZ && hz < newLowestKeyguard) {
                    newLowestKeyguard = hz;
                }
            }
            if (newMin == Float.MAX_VALUE) {
                newMin = 60f;
            }
        }

        if (newMax <= 0f || newMin <= 0f || newMax < newMin) {
            return;
        }

        mDefaultMinHz = newMin;
        mMaxSupportedHz = newMax;
        mLowestKeyguardHz = newLowestKeyguard != Float.MAX_VALUE ? newLowestKeyguard : newMax;
    }

    private void syncDisplaySettings() {
        final boolean appOverrideActive = mAppOverrideActive && mPerAppOverrideRate > 0f;
        final boolean boosted = mVrrEnabled && !(mKeyguardDone && appOverrideActive)
                && hasEffectiveBoost();
        long nextVrrIdleCheckDelay = -1;

        if (boosted && !hasBoost(BOOST_FLING)) {
            final long vrrTimeout = getVrrTimeout();
            final long elapsed = SystemClock.uptimeMillis() - mLastActivityTime;
            nextVrrIdleCheckDelay = Math.max(1, vrrTimeout - elapsed);
        }

        final float maxHz = mMaxSupportedHz;
        final float minHz = mDefaultMinHz;
        final float peak;
        final float min;
        final String source;
        if (!mKeyguardDone) {
            if (!mLockscreenLimitEnabled && boosted) {
                peak = maxHz + PEAK_REFRESH_RATE_OFFSET;
                min = maxHz;
                source = "KG_INTERACTIVE";
            } else if (sNeedsHfrFlickerFix || mLockscreenLimitEnabled) {
                final float keyguardHz = resolveKeyguardRefreshRate();
                peak = keyguardHz + PEAK_REFRESH_RATE_OFFSET;
                min = keyguardHz;
                source = mLockscreenLimitEnabled ? "KG_LIMIT" : "KG_IDLE";
            } else {
                peak = minHz + PEAK_REFRESH_RATE_OFFSET;
                min = 0f;
                source = "VRR_IDLE";
            }
        } else if (appOverrideActive) {
            peak = mPerAppOverrideRate + PEAK_REFRESH_RATE_OFFSET;
            min = mPerAppOverrideRate;
            source = "APP";
        } else if (!mVrrEnabled) {
            final float rate = resolveLockedRefreshRate();
            peak = rate + PEAK_REFRESH_RATE_OFFSET;
            min = rate;
            source = "FIXED";
        } else if (boosted) {
            peak = maxHz + PEAK_REFRESH_RATE_OFFSET;
            min = maxHz;
            source = "VRR_INTERACTIVE";
        } else {
            peak = minHz + PEAK_REFRESH_RATE_OFFSET;
            min = 0f;
            source = "VRR_IDLE";
        }

        final RefreshRateUpdateCallback callback = mRateCallback;
        if (callback != null && (peak != mLastSyncedPeak || min != mLastSyncedMin
                || !source.equals(mLastSource))) {
            mLastSyncedPeak = peak;
            mLastSyncedMin = min;
            mLastSource = source;
            callback.onRefreshRateChanged(min, peak, Display.DEFAULT_DISPLAY);
        }
        if (nextVrrIdleCheckDelay > 0) {
            scheduleSyncDisplaySettings(nextVrrIdleCheckDelay);
        }
    }

    private void loadRefreshRateSetting() {
        final int value = Settings.Global.getInt(mContext.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, sSupportsVrr ? 0 : Math.round(mMaxSupportedHz));
        mVrrEnabled = value == 0 && sSupportsVrr;
        mFixedRefreshRate = mVrrEnabled || value <= 0 ? Math.round(mMaxSupportedHz) : value;
        mLockscreenLimitEnabled = Settings.System.getInt(mContext.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0) != 0;
    }

    private void loadPerAppRefreshRates() {
        final String config = Settings.System.getString(mContext.getContentResolver(),
                PER_APP_REFRESH_RATE);
        final ArrayMap<String, Float> parsed = new ArrayMap<>();
        if (config != null && !config.isEmpty()) {
            for (String app : config.split(",")) {
                final String[] parts = app.split(":");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    final float rate = Float.parseFloat(parts[1]);
                    if (rate > 0f) {
                        parsed.put(parts[0], rate);
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse refresh rate for app: " + app, e);
                }
            }
        }
        synchronized (mUserAppRefreshRates) {
            mUserAppRefreshRates.clear();
            mUserAppRefreshRates.putAll(parsed);
        }
    }

    private void refreshFocusedAppOverride() {
        updateFocusedAppOverride();
        if (mVrrEnabled && !mAppOverrideActive && mKeyguardDone) {
            setBoost(BOOST_FOCUS);
        }
        syncDisplaySettings();
        requestTraversal();
    }

    private void updateFocusedAppOverride() {
        final String packageName = mFocusedPackage;
        if (packageName.isEmpty()) {
            mAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
            return;
        }
        final Float userRate;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(packageName);
        }
        final float resolvedUserRate = resolveUserRefreshRate(userRate);
        mAppOverrideActive = resolvedUserRate > 0f;
        mPerAppOverrideRate = resolvedUserRate;
    }

    private float resolveLockedRefreshRate() {
        final float rate = mFixedRefreshRate > 0 ? mFixedRefreshRate : mMaxSupportedHz;
        return Math.max(mDefaultMinHz, Math.min(rate, mMaxSupportedHz));
    }

    private float resolveKeyguardRefreshRate() {
        return Math.max(mDefaultMinHz, Math.min(mLowestKeyguardHz, mMaxSupportedHz));
    }

    private void requestTraversal() {
        synchronized (mWmService.mGlobalLock) {
            mWmService.requestTraversal();
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mRefreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri mLockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri mPerAppRefreshRateUri = Settings.System.getUriFor(PER_APP_REFRESH_RATE);

        SettingsObserver() {
            super(mHandler);
            mContext.getContentResolver().registerContentObserver(
                    mRefreshRateModeUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mLockscreenLimitUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mPerAppRefreshRateUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mPerAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
                refreshFocusedAppOverride();
                return;
            }
            final boolean wasVrrEnabled = mVrrEnabled;
            loadRefreshRateSetting();
            if (mVrrEnabled && !wasVrrEnabled && !mAppOverrideActive && mKeyguardDone) {
                setBoost(BOOST_FOCUS);
            }
            syncDisplaySettings();
            requestTraversal();
        }
    }
}
