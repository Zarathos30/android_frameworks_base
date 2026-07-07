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
import android.os.FileUtils;
import android.os.SystemClock;

import java.io.IOException;

final class FrameBoostWriter {
    private static final String BOOST_PATH = "/proc/ax_frame_boost/boost";
    private static final long RETRY_DELAY_MS = 5000L;

    private final StringBuilder mBuilder = new StringBuilder(96);
    private long mRetryUptimeMs;

    synchronized void write(FrameRescueSignal signal) {
        if (signal == null || !canWrite()) {
            return;
        }
        try {
            FileUtils.stringToFile(BOOST_PATH, commandFor(signal));
            mRetryUptimeMs = 0L;
        } catch (IOException | RuntimeException ignored) {
            mRetryUptimeMs = SystemClock.uptimeMillis() + RETRY_DELAY_MS;
        }
    }

    private boolean canWrite() {
        return mRetryUptimeMs == 0L || SystemClock.uptimeMillis() >= mRetryUptimeMs;
    }

    private String commandFor(FrameRescueSignal signal) {
        final int level = kernelLevel(signal.level);
        final int durationMs = level == 0 ? 0 : signal.durationMs;
        final StringBuilder builder = mBuilder;
        builder.setLength(0);
        builder.append(signal.pid)
                .append(' ')
                .append(signal.uid)
                .append(' ')
                .append(level)
                .append(' ')
                .append(durationMs)
                .append(" 0 ")
                .append(signal.source)
                .append(' ')
                .append(nonNegative(signal.actualDurationNs))
                .append(' ')
                .append(nonNegative(signal.targetDurationNs));
        return builder.toString();
    }

    private static int kernelLevel(int level) {
        if (level == AxFrameRescue.LEVEL_HEAVY) {
            return AxFrameRescue.LEVEL_HEAVY;
        }
        if (level == AxFrameRescue.LEVEL_LIGHT) {
            return AxFrameRescue.LEVEL_LIGHT;
        }
        return AxFrameRescue.LEVEL_NONE;
    }

    private static long nonNegative(long value) {
        return value > 0L ? value : 0L;
    }
}
