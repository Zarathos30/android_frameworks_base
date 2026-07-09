/*
 * Copyright 2025-2026 AxionOS
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

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.BatteryManager;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.art.ReasonMapping;
import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.DexoptParams;
import com.android.server.art.model.DexoptResult;
import com.android.server.pm.DexOptHelper;
import com.android.server.pm.PackageManagerLocal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AxDexOptimizer extends SystemService {
    private static final String TAG = "AxDexOptimizer";
    private static final boolean DEBUG = SystemProperties.getBoolean(
            "persist.sys.ax.dexopt.debug", false);

    private static final String PROP_ENABLED = "persist.sys.ax.screenoff_dexopt.enabled";
    private static final String PROP_DELAY_MS = "persist.sys.ax.screenoff_dexopt.delay_ms";
    private static final String PROP_MAX_PACKAGES = "persist.sys.ax.screenoff_dexopt.max";

    private static final int DEFAULT_MAX_PACKAGES = 20;
    private static final int MAX_FAILURES = 2;
    private static final long DEFAULT_DELAY_MS = 10L * 60L * 1000L;
    private static final int KILL_RETRY_DELAY_MS = 1000;
    private static final String COMPILER_FILTER = "speed-profile";
    private static final String ARTD_COMMAND = "/apex/com.android.art/bin/artd";
    private static final String INSTALLD_COMMAND = "/system/bin/installd";
    private static final String DEX2OAT32_COMMAND = "/apex/com.android.art/bin/dex2oat32";
    private static final String DEX2OAT64_COMMAND = "/apex/com.android.art/bin/dex2oat64";

    private final ActivityManagerService mService;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final AlarmManager mAlarmManager;
    private final ArraySet<String> mSuccessPackages = new ArraySet<>();
    private final ArrayMap<String, Integer> mFailedCounts = new ArrayMap<>();
    private final ArrayList<String> mPendingPackages = new ArrayList<>();
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBroadcast(intent);
        }
    };
    private final AlarmManager.OnAlarmListener mAlarmListener = this::runDexoptPass;

    private CancellationSignal mCancellation;
    private boolean mRegistered;
    private boolean mScreenOff;
    private boolean mCharging;
    private boolean mBatteryLow;
    private boolean mRunning;

    public AxDexOptimizer(Context context, ActivityManagerService service) {
        super(context);
        mService = Objects.requireNonNull(service);
        mAlarmManager = context.getSystemService(AlarmManager.class);
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxDexOptimizer.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START && isEnabled()) {
            registerReceivers();
        }
    }

    private void registerReceivers() {
        if (mRegistered || mAlarmManager == null) {
            return;
        }
        final Intent battery = getContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateBatteryState(battery);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        getContext().registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
        mRegistered = true;
    }

    private void handleBroadcast(Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            mScreenOff = false;
            cancelAlarm();
            cancelRunningDexopt();
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            mScreenOff = true;
            updatePendingPackages();
            maybeScheduleDexopt();
        } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            updateBatteryState(intent);
            maybeScheduleDexopt();
        } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            mBatteryLow = true;
            cancelAlarm();
            cancelRunningDexopt();
        } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
            mBatteryLow = false;
            maybeScheduleDexopt();
        }
    }

    private void updateBatteryState(Intent intent) {
        if (intent == null) {
            return;
        }
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        mBatteryLow = intent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, mBatteryLow);
    }

    private void maybeScheduleDexopt() {
        if (!canRunDexopt()) {
            cancelAlarm();
            return;
        }
        if (mPendingPackages.isEmpty()) {
            updatePendingPackages();
        }
        if (mPendingPackages.isEmpty()) {
            return;
        }
        cancelAlarm();
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + dexoptDelayMs(), "ax-screenoff-dexopt",
                mAlarmListener, mHandler);
    }

    private boolean canRunDexopt() {
        return isEnabled() && mScreenOff && mCharging && !mBatteryLow && !mRunning;
    }

    private void updatePendingPackages() {
        mPendingPackages.clear();
        final ArraySet<String> seen = new ArraySet<>();
        final int maxPackages = maxPackages();
        synchronized (mService.mProcLock) {
            final ArrayList<ProcessRecord> lru = mService.mProcessList.getLruProcessesLOSP();
            for (int i = lru.size() - 1; i >= 0 && mPendingPackages.size() < maxPackages; i--) {
                final ProcessRecord app = lru.get(i);
                if (!isDexoptCandidate(app)) {
                    continue;
                }
                final String packageName = app.info.packageName;
                if (seen.add(packageName) && needsDexopt(packageName)) {
                    mPendingPackages.add(packageName);
                }
            }
        }
    }

    private boolean isDexoptCandidate(ProcessRecord app) {
        return app != null && app.info != null && app.info.packageName != null
                && (app.info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
    }

    private boolean needsDexopt(String packageName) {
        final Integer failed = mFailedCounts.get(packageName);
        return !mSuccessPackages.contains(packageName) && (failed == null || failed < MAX_FAILURES);
    }

    private void runDexoptPass() {
        if (!canRunDexopt() || mPendingPackages.isEmpty()) {
            return;
        }
        if (isDex2oatRunning()) {
            maybeScheduleDexopt();
            return;
        }
        mRunning = true;
        mCancellation = new CancellationSignal();
        try {
            final ArrayList<String> packages = new ArrayList<>(mPendingPackages);
            for (int i = 0; i < packages.size() && canContinueDexopt(); i++) {
                dexoptPackage(packages.get(i), mCancellation);
            }
            updatePendingPackages();
        } finally {
            mRunning = false;
            mCancellation = null;
        }
        if (!mPendingPackages.isEmpty()) {
            maybeScheduleDexopt();
        }
    }

    private boolean canContinueDexopt() {
        return isEnabled() && mScreenOff && mCharging && !mBatteryLow
                && mCancellation != null && !mCancellation.isCanceled();
    }

    private void dexoptPackage(String packageName, CancellationSignal cancellation) {
        final boolean success = performDexopt(packageName, cancellation);
        if (cancellation.isCanceled()) {
            return;
        }
        if (success) {
            mSuccessPackages.add(packageName);
            mFailedCounts.remove(packageName);
        } else {
            final Integer failed = mFailedCounts.get(packageName);
            mFailedCounts.put(packageName, failed == null ? 1 : failed + 1);
        }
    }

    private boolean performDexopt(String packageName, CancellationSignal cancellation) {
        if (!DexOptHelper.artManagerLocalIsInitialized()) {
            return false;
        }
        final PackageManagerLocal packageManager = LocalManagerRegistry.getManager(
                PackageManagerLocal.class);
        if (packageManager == null) {
            return false;
        }
        try (PackageManagerLocal.FilteredSnapshot snapshot =
                     packageManager.withFilteredSnapshot()) {
            final int flags = ArtFlags.FLAG_FOR_PRIMARY_DEX
                    | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES;
            final DexoptParams params = new DexoptParams.Builder(ReasonMapping.REASON_BG_DEXOPT)
                    .setCompilerFilter(COMPILER_FILTER)
                    .setFlags(flags, ArtFlags.FLAG_FOR_PRIMARY_DEX
                            | ArtFlags.FLAG_FOR_SECONDARY_DEX
                            | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES)
                    .build();
            final DexoptResult result = DexOptHelper.getArtManagerLocal().dexoptPackage(snapshot,
                    packageName, params, cancellation);
            final int status = result.getFinalStatus();
            return status == DexoptResult.DEXOPT_PERFORMED
                    || status == DexoptResult.DEXOPT_SKIPPED;
        } catch (RuntimeException e) {
            if (DEBUG) {
                Slog.d(TAG, "dexopt failed for " + packageName, e);
            }
            return false;
        }
    }

    private void cancelRunningDexopt() {
        final CancellationSignal cancellation = mCancellation;
        if (cancellation == null && !mRunning) {
            return;
        }
        if (cancellation != null) {
            cancellation.cancel();
        }
        killDex2oatChildren();
        mHandler.postDelayed(this::killDex2oatChildren, KILL_RETRY_DELAY_MS);
    }

    private void cancelAlarm() {
        if (mAlarmManager != null) {
            mAlarmManager.cancel(mAlarmListener);
        }
    }

    private boolean isDex2oatRunning() {
        return findDex2oatPid() > 0;
    }

    private void killDex2oatChildren() {
        final int dex2oatPid = findDex2oatPid();
        if (dex2oatPid <= 0) {
            return;
        }
        Process.killProcess(dex2oatPid);
    }

    private int findDex2oatPid() {
        final String parentCommand = SystemProperties.getBoolean("dalvik.vm.useartservice", false)
                ? ARTD_COMMAND : INSTALLD_COMMAND;
        final String[] commands = { parentCommand, DEX2OAT32_COMMAND, DEX2OAT64_COMMAND };
        final int[] pids = Process.getPidsForCommands(commands);
        if (pids == null || pids.length == 0) {
            return -1;
        }
        int parentPid = -1;
        for (int pid : pids) {
            final String cmdline = readCmdline(pid);
            if (parentCommand.equals(cmdline)) {
                parentPid = pid;
                break;
            }
        }
        if (parentPid <= 0) {
            return -1;
        }
        for (int pid : pids) {
            if (pid != parentPid && Process.getParentPid(pid) == parentPid
                    && readCmdline(pid).contains("dex2oat")) {
                return pid;
            }
        }
        return -1;
    }

    private static String readCmdline(int pid) {
        try {
            final String cmdline = FileUtils.readTextFile(new File("/proc/" + pid + "/cmdline"),
                    256, null);
            final int nul = cmdline.indexOf('\0');
            return nul >= 0 ? cmdline.substring(0, nul) : cmdline.trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    private long dexoptDelayMs() {
        return Math.max(60_000L, SystemProperties.getLong(PROP_DELAY_MS, DEFAULT_DELAY_MS));
    }

    private int maxPackages() {
        return Math.max(1, SystemProperties.getInt(PROP_MAX_PACKAGES, DEFAULT_MAX_PACKAGES));
    }

}
