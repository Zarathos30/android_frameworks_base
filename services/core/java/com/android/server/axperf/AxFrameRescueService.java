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

package com.android.server.axperf;

import android.app.AxFrameRescue;
import android.content.Context;
import android.hardware.power.SessionTag;
import android.os.PerformanceHintManager;
import android.os.SystemClock;
import android.os.Trace;

import com.android.server.LocalServices;
import com.android.server.SystemService;

/** @hide */
public final class AxFrameRescueService extends SystemService {
    private static final int MIN_DURATION_MS = 64;
    private static final int MAX_DURATION_MS = 1600;
    private static final int LIGHT_DURATION_MS = AxFrameRescue.DEFAULT_LIGHT_DURATION_MS;
    private static final int HEAVY_DURATION_MS = AxFrameRescue.DEFAULT_HEAVY_DURATION_MS;
    private static final int RESCHEDULE_SLOP_MS = 96;
    private static final long TRACE_TAG = Trace.TRACE_TAG_ACTIVITY_MANAGER;

    private final Object mLock = new Object();
    private final FrameBoostWriter mWriter = new FrameBoostWriter();
    private final AxFrameRescueInternal mLocalService = new LocalService();

    private int mActivePid = -1;
    private int mActiveUid = -1;
    private int mActiveSource = -1;
    private int mActiveLevel = AxFrameRescue.LEVEL_NONE;
    private long mActiveExpiryUptimeMs;

    public AxFrameRescueService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxFrameRescueInternal.class, mLocalService);
    }

    private void clearActivePid(int pid) {
        final FrameRescueSignal signal;
        synchronized (mLock) {
            if (pid <= 0 || pid != mActivePid) {
                return;
            }
            signal = new FrameRescueSignal(mActivePid, mActiveUid, mActiveSource,
                    AxFrameRescue.LEVEL_NONE, 0L, 0L, 0);
            mActivePid = -1;
            mActiveUid = -1;
            mActiveSource = -1;
            mActiveLevel = AxFrameRescue.LEVEL_NONE;
            mActiveExpiryUptimeMs = 0L;
        }
        traceSignal("clear", signal);
        mWriter.write(signal);
    }

    private void onFrameRescue(int pid, int uid, int source, int level, long actualDurationNs,
            long targetDurationNs, int durationMs) {
        if (!isValidRequest(pid, uid, source, level, durationMs)) {
            return;
        }
        final int boundedDurationMs = Math.min(Math.max(durationMs, MIN_DURATION_MS),
                MAX_DURATION_MS);
        final FrameRescueSignal signal;
        synchronized (mLock) {
            if (!updateActiveRequestLocked(pid, uid, source, level, boundedDurationMs)) {
                return;
            }
            signal = new FrameRescueSignal(pid, uid, source, level, actualDurationNs,
                    targetDurationNs, boundedDurationMs);
        }
        traceSignal("apply", signal);
        mWriter.write(signal);
    }

    private void onAdpfWorkDuration(int pid, int uid, int tag, long actualDurationNs,
            long targetDurationNs) {
        if (!isHwuiTag(tag) || actualDurationNs <= 0 || targetDurationNs <= 0) {
            return;
        }
        final int level = frameLevel(actualDurationNs, targetDurationNs);
        if (level == AxFrameRescue.LEVEL_NONE) {
            return;
        }
        final int durationMs = level == AxFrameRescue.LEVEL_HEAVY
                ? HEAVY_DURATION_MS : LIGHT_DURATION_MS;
        onFrameRescue(pid, uid, AxFrameRescue.SOURCE_HWUI_ADPF_WORK, level, actualDurationNs,
                targetDurationNs, durationMs);
    }

    private void onAdpfHint(int pid, int uid, int tag, int hint, long targetDurationNs) {
        if (!isHwuiTag(tag)) {
            return;
        }
        if (hint == PerformanceHintManager.Session.GPU_LOAD_UP) {
            onFrameRescue(pid, uid, AxFrameRescue.SOURCE_HWUI_ADPF_GPU_HINT,
                    AxFrameRescue.LEVEL_HEAVY, 0L, targetDurationNs, HEAVY_DURATION_MS);
        } else if (hint == PerformanceHintManager.Session.CPU_LOAD_UP) {
            onFrameRescue(pid, uid, AxFrameRescue.SOURCE_HWUI_ADPF_CPU_HINT,
                    AxFrameRescue.LEVEL_LIGHT, 0L, targetDurationNs, LIGHT_DURATION_MS);
        }
    }

    private boolean updateActiveRequestLocked(int pid, int uid, int source, int level,
            int durationMs) {
        final long now = SystemClock.uptimeMillis();
        if (now > mActiveExpiryUptimeMs) {
            mActiveLevel = AxFrameRescue.LEVEL_NONE;
        }
        final long expiryUptimeMs = now + durationMs;
        if (pid == mActivePid && uid == mActiveUid && level < mActiveLevel) {
            return false;
        }
        if (pid == mActivePid && uid == mActiveUid && level <= mActiveLevel
                && expiryUptimeMs <= mActiveExpiryUptimeMs + RESCHEDULE_SLOP_MS) {
            return false;
        }
        mActivePid = pid;
        mActiveUid = uid;
        mActiveSource = source;
        mActiveLevel = level;
        mActiveExpiryUptimeMs = expiryUptimeMs;
        return true;
    }

    private static boolean isValidRequest(int pid, int uid, int source, int level,
            int durationMs) {
        return pid > 0 && uid >= 0 && isValidSource(source)
                && (level == AxFrameRescue.LEVEL_LIGHT || level == AxFrameRescue.LEVEL_HEAVY)
                && durationMs > 0;
    }

    private static boolean isValidSource(int source) {
        switch (source) {
            case AxFrameRescue.SOURCE_HWUI_EXPENSIVE_FRAME:
            case AxFrameRescue.SOURCE_HWUI_GPU_LOAD:
            case AxFrameRescue.SOURCE_HWUI_ADPF_WORK:
            case AxFrameRescue.SOURCE_HWUI_ADPF_CPU_HINT:
            case AxFrameRescue.SOURCE_HWUI_ADPF_GPU_HINT:
            case AxFrameRescue.SOURCE_ANIMATION_RENDER:
                return true;
            default:
                return false;
        }
    }

    private static int frameLevel(long actualDurationNs, long targetDurationNs) {
        if (actualDurationNs >= targetDurationNs) {
            return AxFrameRescue.LEVEL_HEAVY;
        }
        final long lightDurationNs = targetDurationNs - targetDurationNs / 4;
        return actualDurationNs >= lightDurationNs
                ? AxFrameRescue.LEVEL_LIGHT : AxFrameRescue.LEVEL_NONE;
    }

    private static boolean isHwuiTag(int tag) {
        return tag == SessionTag.HWUI;
    }

    private static void traceSignal(String action, FrameRescueSignal signal) {
        if (!Trace.isTagEnabled(TRACE_TAG)) {
            return;
        }
        Trace.instant(TRACE_TAG, "AxFrameRescue." + action
                + " pid=" + signal.pid
                + " source=" + signal.source
                + " level=" + signal.level
                + " durationMs=" + signal.durationMs);
        Trace.traceCounter(TRACE_TAG, "AxFrameRescue.pid", signal.pid);
        Trace.traceCounter(TRACE_TAG, "AxFrameRescue.source", signal.source);
        Trace.traceCounter(TRACE_TAG, "AxFrameRescue.level", signal.level);
        Trace.traceCounter(TRACE_TAG, "AxFrameRescue.actualMs",
                nanosToMillis(signal.actualDurationNs));
        Trace.traceCounter(TRACE_TAG, "AxFrameRescue.targetMs",
                nanosToMillis(signal.targetDurationNs));
    }

    private static int nanosToMillis(long value) {
        return value > 0 ? (int) (value / 1_000_000L) : 0;
    }

    private final class LocalService extends AxFrameRescueInternal {
        @Override
        public void clear(int pid) {
            clearActivePid(pid);
        }

        @Override
        public void onFrameRescue(int pid, int uid, int source, int level,
                long actualDurationNs, long targetDurationNs, int durationMs) {
            AxFrameRescueService.this.onFrameRescue(pid, uid, source, level,
                    actualDurationNs, targetDurationNs, durationMs);
        }

        @Override
        public void onAdpfWorkDuration(int pid, int uid, int tag,
                long actualDurationNs, long targetDurationNs) {
            AxFrameRescueService.this.onAdpfWorkDuration(pid, uid, tag,
                    actualDurationNs, targetDurationNs);
        }

        @Override
        public void onAdpfHint(int pid, int uid, int tag, int hint,
                long targetDurationNs) {
            AxFrameRescueService.this.onAdpfHint(pid, uid, tag, hint, targetDurationNs);
        }
    }
}
