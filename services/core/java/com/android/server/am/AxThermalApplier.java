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

import android.os.FileUtils;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.kernel.AxKernelManagerService;
import com.android.server.thermal.AxAdvancedThermalMitigationConfig;

import java.io.IOException;

public final class AxThermalApplier {
    private static final String TAG = "AxThermalApplier";
    private static final String ATCM_STATE_PATH = "/proc/ax_atcm/state";
    private static final long RETRY_DELAY_MS = 10_000L;

    private final Object mLock = new Object();
    private final AxThermalBoostPolicy mPolicy = new AxThermalBoostPolicy();
    private final StringBuilder mBuffer = new StringBuilder(128);
    private long mRetryUptimeMs;
    private boolean mUnavailableLogged;

    public void updateThermalState(int level, int cpuCap, int gpuCap, int boostCap) {
        mPolicy.updateThermalState(cpuCap);
        final AxBurstEngineImpl engine = LocalServices.getService(AxBurstEngineImpl.class);
        if (engine != null) {
            engine.setThermalLevel(level);
        }
        synchronized (mLock) {
            writeStateLocked(level, cpuCap, gpuCap, boostCap);
        }
    }

    private void writeStateLocked(int level, int cpuCap, int gpuCap, int boostCap) {
        final long now = SystemClock.uptimeMillis();
        if (now < mRetryUptimeMs) {
            return;
        }

        mBuffer.setLength(0);
        mBuffer.append(Math.max(level, 0));
        mBuffer.append(' ').append(cpuCap);
        mBuffer.append(' ').append(gpuCap);
        mBuffer.append(' ').append(boostCap);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_LITTLE);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_BIG);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_PRIME);
        mBuffer.append('\n');

        try {
            FileUtils.stringToFile(ATCM_STATE_PATH, mBuffer.toString());
            mRetryUptimeMs = 0L;
            mUnavailableLogged = false;
        } catch (IOException | RuntimeException e) {
            mRetryUptimeMs = now + RETRY_DELAY_MS;
            if (!mUnavailableLogged) {
                Slog.w(TAG, "atcm state unavailable: " + e.getMessage());
                mUnavailableLogged = true;
            }
        }
    }

    private void appendClusterFloor(StringBuilder out, int level, int cpuCap, int cluster) {
        long floor = 0L;
        long ceiling = 0L;
        if (level > 0 && cpuCap >= 0) {
            final AxAdvancedThermalMitigationConfig.CpuClusterPath path = findClusterPath(cluster);
            floor = path != null && path.min > 0 ? path.min : 0L;
            ceiling = path != null && path.max > 0 ? path.max : 0L;
            if (path != null) {
                final long userMin = userFrequency(path.minPath);
                final long userMax = userFrequency(path.maxPath);
                if (userMin > 0L) {
                    floor = Math.max(floor, userMin);
                }
                if (userMax > 0L) {
                    ceiling = ceiling > 0L ? Math.min(ceiling, userMax) : userMax;
                }
            }
            final long thermalMin = mPolicy.getThermalCpuMinKhz(cluster);
            final long thermalMax = mPolicy.getThermalCpuMaxKhz(cluster);
            if (thermalMin > 0L) {
                floor = Math.max(floor, thermalMin);
            }
            if (thermalMax > 0L) {
                ceiling = ceiling > 0L ? Math.min(ceiling, thermalMax) : thermalMax;
            }
            if (floor > 0L && ceiling > 0L && floor > ceiling) {
                floor = ceiling;
            }
        }
        out.append(' ').append(toInt(floor));
    }

    private static long userFrequency(String path) {
        if (path == null || path.isEmpty()) {
            return 0L;
        }
        return SystemProperties.getLong(AxKernelManagerService.propKeyForPath(path), 0L);
    }

    private static AxAdvancedThermalMitigationConfig.CpuClusterPath findClusterPath(int cluster) {
        for (AxAdvancedThermalMitigationConfig.CpuClusterPath path
                : AxPerfConfig.getAtmc().getCpuClusterPaths()) {
            if (path.cluster == cluster) {
                return path;
            }
        }
        return null;
    }

    private static int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
