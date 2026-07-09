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

import static com.android.server.am.HostingRecord.HOSTING_TYPE_NEXT_TOP_ACTIVITY;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

public final class AxSmartMemoryManager extends SystemService {
    private static final String TAG = "AxSmartMemory";
    private static final boolean DEBUG = SystemProperties.getBoolean(
            "persist.sys.ax.smart_mem.debug", false);

    private static final String PROP_ENABLED = "persist.sys.ax.smart_mem.enabled";
    private static final String PROP_AVAILABLE_LOW = "persist.sys.ax.smart_mem.available_low";
    private static final String PROP_PSI_HIGH = "persist.sys.ax.smart_mem.psi_high";
    private static final String PROP_RSS_LIMIT = "persist.sys.ax.smart_mem.rss_limit";
    private static final String PROP_COMPACT_LIMIT = "persist.sys.ax.smart_mem.compact_limit";
    private static final String PROP_KILL_LIMIT = "persist.sys.ax.smart_mem.kill_limit";

    private static final int MSG_CHECK_MEMORY = 1;
    private static final int CHECK_REASON_PERIODIC = 0;
    private static final int CHECK_REASON_LAUNCH = 1;
    private static final int CHECK_REASON_RETRY = 2;

    private static final int DEFAULT_AVAILABLE_LOW_MB = 1200;
    private static final int DEFAULT_PSI_HIGH = 2;
    private static final int DEFAULT_RSS_LIMIT_MB = 300;
    private static final int DEFAULT_COMPACT_LIMIT = 3;
    private static final int DEFAULT_KILL_LIMIT = 1;
    private static final long HIGH_PRESSURE_POLL_MS = 1000L;
    private static final long LOW_PRESSURE_POLL_MS = 2000L;
    private static final long LAUNCH_RETRY_MS = 500L;
    private static final long COMPACT_COOLDOWN_MS = 30L * 1000L;
    private static final String PROC_MEMINFO = "/proc/meminfo";
    private static final String PROC_PRESSURE_MEMORY = "/proc/pressure/memory";

    private final ActivityManagerService mService;
    private final Handler mHandler;
    private final ArrayMap<Integer, Long> mLastCompactUptime = new ArrayMap<>();

    private volatile boolean mReady;

    public AxSmartMemoryManager(Context context, ActivityManagerService service) {
        super(context);
        mService = Objects.requireNonNull(service);
        mHandler = new SmartMemoryHandler(BackgroundThread.get().getLooper());
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxSmartMemoryManager.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START && isEnabled()) {
            mReady = true;
            scheduleMemoryCheck(LOW_PRESSURE_POLL_MS);
        }
    }

    public void onProcessStarted(ProcessRecord app) {
        if (!mReady || !isEnabled() || app == null || app.info == null
                || app.getHostingRecord() == null) {
            return;
        }
        if (!HOSTING_TYPE_NEXT_TOP_ACTIVITY.equals(app.getHostingRecord().getType())
                || isSystemApp(app)) {
            return;
        }
        mHandler.removeMessages(MSG_CHECK_MEMORY);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_CHECK_MEMORY, CHECK_REASON_LAUNCH,
                app.uid));
    }

    private void checkMemory(int reason, int startingUid) {
        if (!mReady || !isEnabled()) {
            return;
        }
        final int availableMb = readMemAvailableMb();
        final float memPsi = readMemPsiAvg10();
        final boolean lowMemory = availableMb > 0 && availableMb < availableLowMb();
        final boolean highPsi = memPsi > psiHigh();
        final boolean pressure = lowMemory || highPsi;
        if (pressure) {
            final AxBurstEngineImpl burstEngine =
                    LocalServices.getService(AxBurstEngineImpl.class);
            if (burstEngine != null && burstEngine.shouldDeferBackgroundIo()) {
                scheduleMemoryCheck(HIGH_PRESSURE_POLL_MS);
                return;
            }
        }
        boolean reclaimedUi = false;
        if (pressure) {
            reclaimedUi = reclaimMemory(startingUid, lowMemory, highPsi);
        }
        if (reason == CHECK_REASON_LAUNCH && pressure && !reclaimedUi) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CHECK_MEMORY, CHECK_REASON_RETRY,
                    startingUid), LAUNCH_RETRY_MS);
        } else {
            scheduleMemoryCheck(pressure ? HIGH_PRESSURE_POLL_MS : LOW_PRESSURE_POLL_MS);
        }
    }

    private boolean reclaimMemory(int startingUid, boolean lowMemory, boolean highPsi) {
        final ArrayList<MemoryCandidate> candidates = collectCandidates(startingUid);
        if (candidates.isEmpty()) {
            return false;
        }
        Collections.sort(candidates, (left, right) -> Integer.compare(right.score, left.score));
        int compacted = 0;
        boolean touchedUi = false;
        final int compactLimit = compactLimit();
        for (int i = 0; i < candidates.size() && compacted < compactLimit; i++) {
            final MemoryCandidate candidate = candidates.get(i);
            if (shouldCompact(candidate)) {
                compactProcess(candidate, CachedAppOptimizer.CompactProfile.FULL);
                compacted++;
                touchedUi |= candidate.hasUi;
            }
        }
        if (lowMemory || highPsi) {
            killCachedProcesses(candidates);
        }
        if (DEBUG && (compacted > 0 || lowMemory || highPsi)) {
            Slog.d(TAG, "reclaim compacted=" + compacted + " low=" + lowMemory
                    + " psi=" + highPsi);
        }
        return touchedUi;
    }

    private ArrayList<MemoryCandidate> collectCandidates(int startingUid) {
        final ArrayList<MemoryCandidate> candidates = new ArrayList<>();
        synchronized (mService.mProcLock) {
            final ArrayList<ProcessRecord> lru = mService.mProcessList.getLruProcessesLOSP();
            for (int i = 0; i < lru.size(); i++) {
                final ProcessRecord app = lru.get(i);
                if (!isReclaimCandidate(app, startingUid)) {
                    continue;
                }
                final int pid = app.getPid();
                final int rssMb = (int) Math.max(0L, app.getLastRss() / 1024L);
                final int procState = app.getCurProcState();
                final boolean cached = app.getSetAdj() >= CACHED_APP_MIN_ADJ
                        || procState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
                final boolean hasUi = app.hasActivities();
                final int score = scoreCandidate(i, rssMb, cached, hasUi);
                candidates.add(new MemoryCandidate(app, pid, app.uid, app.processName, rssMb,
                        score, cached, hasUi));
            }
        }
        return candidates;
    }

    private boolean isReclaimCandidate(ProcessRecord app, int startingUid) {
        return app != null && app.info != null && app.getPid() > 0
                && app.getPid() != ActivityManagerService.MY_PID
                && app.uid >= Process.FIRST_APPLICATION_UID && app.uid != startingUid
                && !app.isPersistent() && !app.isKilled() && !app.isSystemUi() && !app.isLauncher3()
                && !isSystemApp(app)
                && app.getCurProcState() >= ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
    }

    private int scoreCandidate(int lruIndex, int rssMb, boolean cached, boolean hasUi) {
        int score = lruIndex * 16 + Math.max(0, rssMb - rssLimitMb());
        if (cached) {
            score += 256;
        }
        if (!hasUi) {
            score += 128;
        }
        return score;
    }

    private boolean shouldCompact(MemoryCandidate candidate) {
        if (candidate.rssMb < Math.max(64, rssLimitMb() / 2)) {
            return false;
        }
        final Long last = mLastCompactUptime.get(candidate.uid);
        return last == null || SystemClock.uptimeMillis() - last > COMPACT_COOLDOWN_MS;
    }

    private void compactProcess(MemoryCandidate candidate,
            CachedAppOptimizer.CompactProfile profile) {
        synchronized (mService.mProcLock) {
            final ProcessRecord app = candidate.app;
            if (app == null || app.uid != candidate.uid || app.getPid() != candidate.pid
                    || app.isKilled()) {
                return;
            }
            if (mService.getCachedAppOptimizer().compactApp(app, profile,
                    CachedAppOptimizer.CompactSource.APP, false)) {
                mLastCompactUptime.put(candidate.uid, SystemClock.uptimeMillis());
            }
        }
    }

    private void killCachedProcesses(ArrayList<MemoryCandidate> candidates) {
        int killed = 0;
        final int killLimit = killLimit();
        for (int i = 0; i < candidates.size() && killed < killLimit; i++) {
            final MemoryCandidate candidate = candidates.get(i);
            if (!candidate.cached || candidate.hasUi || candidate.rssMb < rssLimitMb()) {
                continue;
            }
            if (killCachedProcess(candidate)) {
                killed++;
            }
        }
    }

    private boolean killCachedProcess(MemoryCandidate candidate) {
        synchronized (mService.mProcLock) {
            final ProcessRecord app = candidate.app;
            if (app == null || app.uid != candidate.uid || app.getPid() != candidate.pid
                    || app.isKilled() || app.getSetAdj() < CACHED_APP_MIN_ADJ
                    || app.hasActivities()) {
                return false;
            }
        }
        Process.killProcess(candidate.pid);
        if (DEBUG) {
            Slog.d(TAG, "killed " + candidate.name + " pid=" + candidate.pid
                    + " rss=" + candidate.rssMb);
        }
        return true;
    }

    private static float readMemPsiAvg10() {
        final String pressure = readString(PROC_PRESSURE_MEMORY, 256);
        if (pressure == null) {
            return 0.0f;
        }
        final int index = pressure.indexOf("some avg10=");
        if (index < 0) {
            return 0.0f;
        }
        final int start = index + "some avg10=".length();
        final int end = pressure.indexOf(' ', start);
        try {
            return Float.parseFloat(pressure.substring(start,
                    end > start ? end : pressure.length()));
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    private static int readMemAvailableMb() {
        final String meminfo = readString(PROC_MEMINFO, 512);
        if (meminfo == null) {
            return -1;
        }
        final int index = meminfo.indexOf("MemAvailable:");
        if (index < 0) {
            return -1;
        }
        final int start = index + "MemAvailable:".length();
        final int end = meminfo.indexOf("kB", start);
        if (end < 0) {
            return -1;
        }
        try {
            return (int) (Long.parseLong(meminfo.substring(start, end).trim()) / 1024L);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readString(String path, int max) {
        try {
            return FileUtils.readTextFile(new File(path), max, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void scheduleMemoryCheck(long delayMs) {
        mHandler.removeMessages(MSG_CHECK_MEMORY);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CHECK_MEMORY,
                CHECK_REASON_PERIODIC, 0), delayMs);
    }

    private boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    private int availableLowMb() {
        return Math.max(256, SystemProperties.getInt(PROP_AVAILABLE_LOW,
                DEFAULT_AVAILABLE_LOW_MB));
    }

    private int psiHigh() {
        return Math.max(1, SystemProperties.getInt(PROP_PSI_HIGH, DEFAULT_PSI_HIGH));
    }

    private int rssLimitMb() {
        return Math.max(64, SystemProperties.getInt(PROP_RSS_LIMIT, DEFAULT_RSS_LIMIT_MB));
    }

    private int compactLimit() {
        return Math.max(0, SystemProperties.getInt(PROP_COMPACT_LIMIT, DEFAULT_COMPACT_LIMIT));
    }

    private int killLimit() {
        return Math.max(0, SystemProperties.getInt(PROP_KILL_LIMIT, DEFAULT_KILL_LIMIT));
    }

    private static boolean isSystemApp(ProcessRecord app) {
        return (app.info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private final class SmartMemoryHandler extends Handler {
        SmartMemoryHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_CHECK_MEMORY) {
                checkMemory(msg.arg1, msg.arg2);
            }
        }
    }

    private static final class MemoryCandidate {
        final ProcessRecord app;
        final int pid;
        final int uid;
        final String name;
        final int rssMb;
        final int score;
        final boolean cached;
        final boolean hasUi;

        MemoryCandidate(ProcessRecord app, int pid, int uid, String name, int rssMb, int score,
                boolean cached, boolean hasUi) {
            this.app = app;
            this.pid = pid;
            this.uid = uid;
            this.name = name;
            this.rssMb = rssMb;
            this.score = score;
            this.cached = cached;
            this.hasUi = hasUi;
        }
    }
}
