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

package android.app;

import android.os.RemoteException;
import android.os.SystemClock;

/** @hide */
public final class AxFrameRescue {
    public static final int SOURCE_HWUI_EXPENSIVE_FRAME = 2;
    public static final int SOURCE_HWUI_GPU_LOAD = 3;
    public static final int SOURCE_HWUI_ADPF_WORK = 4;
    public static final int SOURCE_HWUI_ADPF_CPU_HINT = 5;
    public static final int SOURCE_HWUI_ADPF_GPU_HINT = 6;
    public static final int SOURCE_ANIMATION_RENDER = 7;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_LIGHT = 1;
    public static final int LEVEL_HEAVY = 2;

    public static final int DEFAULT_LIGHT_DURATION_MS = 350;
    public static final int DEFAULT_HEAVY_DURATION_MS = 700;

    private static final int RESCHEDULE_SLOP_MS = 96;
    private static final Object sLock = new Object();

    private static int sLevel;
    private static long sExpiryUptimeMs;

    public static void notifyExpensiveFrame() {
        requestFrameRescue(SOURCE_HWUI_EXPENSIVE_FRAME, LEVEL_LIGHT, 0L, 0L,
                DEFAULT_LIGHT_DURATION_MS);
    }

    public static void notifyGpuLoadUp() {
        requestFrameRescue(SOURCE_HWUI_GPU_LOAD, LEVEL_HEAVY, 0L, 0L,
                DEFAULT_HEAVY_DURATION_MS);
    }

    private static void requestFrameRescue(int source, int level, long actualDurationNs,
            long targetDurationNs, int durationMs) {
        if (!isValidSource(source) || !isValidLevel(level) || durationMs <= 0) {
            return;
        }
        if (!updateFrameRequest(level, durationMs)) {
            return;
        }
        sendFrameRescue(source, level, actualDurationNs, targetDurationNs, durationMs);
    }

    private static boolean updateFrameRequest(int level, int durationMs) {
        final long now = SystemClock.uptimeMillis();
        final long expiryUptimeMs = now + durationMs;
        synchronized (sLock) {
            if (now > sExpiryUptimeMs) {
                sLevel = LEVEL_NONE;
            }
            if (level < sLevel) {
                return false;
            }
            if (level == sLevel && expiryUptimeMs <= sExpiryUptimeMs + RESCHEDULE_SLOP_MS) {
                return false;
            }
            sLevel = level;
            sExpiryUptimeMs = expiryUptimeMs;
            return true;
        }
    }

    private static void sendFrameRescue(int source, int level, long actualDurationNs,
            long targetDurationNs, int durationMs) {
        try {
            final IActivityManager service = ActivityManager.getService();
            if (service != null) {
                service.onFrameRescue(source, level, actualDurationNs, targetDurationNs,
                        durationMs);
            }
        } catch (RemoteException ignored) {
        }
    }

    private static boolean isValidSource(int source) {
        switch (source) {
            case SOURCE_HWUI_EXPENSIVE_FRAME:
            case SOURCE_HWUI_GPU_LOAD:
            case SOURCE_HWUI_ADPF_WORK:
            case SOURCE_HWUI_ADPF_CPU_HINT:
            case SOURCE_HWUI_ADPF_GPU_HINT:
            case SOURCE_ANIMATION_RENDER:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidLevel(int level) {
        return level == LEVEL_LIGHT || level == LEVEL_HEAVY;
    }

    private AxFrameRescue() {}
}
