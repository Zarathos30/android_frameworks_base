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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.FileUtils;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.MemInfoReader;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

public final class AxIoPrefetcher extends SystemService {
    private static final String TAG = "AxIoPrefetcher";
    private static final boolean DEBUG = SystemProperties.getBoolean(
            "persist.sys.ax.ioprefetch.debug", false);

    private static final String PROP_ENABLED = "persist.sys.ax.ioprefetch.enabled";

    private static final long LOW_RAM_LIMIT_KB = 4L * 1024L * 1024L;
    private static final long PREFETCH_COOLDOWN_MS = 30L * 60L * 1000L;
    private static final long MAX_PREFETCH_BYTES = 128L * 1024L * 1024L;
    private static final long START_PREFETCH_DELAY_MS = 1500L;
    private static final long UI_BUSY_RETRY_DELAY_MS = 1000L;
    private static final int MAX_UI_BUSY_RETRIES = 4;
    private static final float MAX_PSI_AVG10 = 0.5f;
    private static final String PROC_PRESSURE_MEMORY = "/proc/pressure/memory";
    private static final String PROC_PRESSURE_IO = "/proc/pressure/io";
    private static final String[] ARTIFACT_SUFFIXES = { ".odex", ".vdex", ".art" };

    private final Handler mHandler = IoThread.getHandler();
    private final ArrayMap<String, Long> mLastPrefetch = new ArrayMap<>();
    private final AtomicLong mPrefetchToken = new AtomicLong();
    private final boolean mLowRamDevice = isLowRamDevice();

    private volatile boolean mReady;

    public AxIoPrefetcher(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxIoPrefetcher.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mReady = true;
        }
    }

    public void onProcessStarted(ProcessRecord app) {
        if (!mReady || !isEnabled() || app == null || app.info == null
                || app.getHostingRecord() == null) {
            return;
        }
        if (!HOSTING_TYPE_NEXT_TOP_ACTIVITY.equals(app.getHostingRecord().getType())) {
            return;
        }
        final ApplicationInfo info = app.info;
        final String packageName = info.packageName;
        if (packageName == null || info.sourceDir == null) {
            return;
        }
        schedulePrefetch(packageName, info.sourceDir);
    }

    private void schedulePrefetch(String packageName, String sourceDir) {
        final long token = mPrefetchToken.incrementAndGet();
        mHandler.postDelayed(() -> prefetchPackage(packageName, sourceDir, 0, token),
                START_PREFETCH_DELAY_MS);
    }

    private void prefetchPackage(String packageName, String sourceDir, int uiBusyRetries,
            long token) {
        if (token != mPrefetchToken.get()) {
            return;
        }
        if (shouldDeferPrefetch()) {
            if (uiBusyRetries < MAX_UI_BUSY_RETRIES) {
                mHandler.postDelayed(() -> prefetchPackage(packageName, sourceDir,
                        uiBusyRetries + 1, token), UI_BUSY_RETRY_DELAY_MS);
            }
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final Long last = mLastPrefetch.get(packageName);
        if (last != null && now - last < PREFETCH_COOLDOWN_MS) {
            return;
        }
        if (hasSystemPressure()) {
            return;
        }
        mLastPrefetch.put(packageName, now);
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "axIoPrefetch");
        try {
            final File source = new File(sourceDir);
            final File oatDir = findOatDir(source.getParentFile());
            if (oatDir == null) {
                return;
            }
            final String baseName = getBaseName(source);
            long remainingBytes = MAX_PREFETCH_BYTES;
            for (String suffix : ARTIFACT_SUFFIXES) {
                remainingBytes = loadFile(new File(oatDir, baseName + suffix), remainingBytes);
                if (remainingBytes <= 0L) {
                    break;
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private static File findOatDir(File codeDir) {
        if (codeDir == null) {
            return null;
        }
        final File arm64 = new File(codeDir, "oat/arm64");
        if (arm64.exists()) {
            return arm64;
        }
        final File arm = new File(codeDir, "oat/arm");
        return arm.exists() ? arm : null;
    }

    private static String getBaseName(File source) {
        final String name = source.getName();
        return name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
    }

    private static long loadFile(File file, long remainingBytes) {
        final long length = file.length();
        if (!file.exists() || length <= 0L || remainingBytes <= 0L) {
            return remainingBytes;
        }
        final long bytesToLoad = Math.min(length, remainingBytes);
        try (RandomAccessFile randomFile = new RandomAccessFile(file, "r");
                FileChannel channel = randomFile.getChannel()) {
            final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L,
                    bytesToLoad);
            if (!buffer.isLoaded()) {
                buffer.load();
                if (DEBUG) {
                    Slog.d(TAG, "prefetched " + file);
                }
                return remainingBytes - bytesToLoad;
            }
        } catch (IOException e) {
            if (DEBUG) {
                Slog.d(TAG, "prefetch failed for " + file, e);
            }
        }
        return remainingBytes;
    }

    private static boolean shouldDeferPrefetch() {
        final AxBurstEngineImpl engine = LocalServices.getService(AxBurstEngineImpl.class);
        return engine != null && engine.shouldDeferBackgroundIo();
    }

    private static boolean hasSystemPressure() {
        return readPsiAvg10(PROC_PRESSURE_MEMORY) >= MAX_PSI_AVG10
                || readPsiAvg10(PROC_PRESSURE_IO) >= MAX_PSI_AVG10;
    }

    private static float readPsiAvg10(String path) {
        try {
            final String pressure = FileUtils.readTextFile(new File(path), 256, null);
            final int index = pressure.indexOf("some avg10=");
            if (index < 0) {
                return 0.0f;
            }
            final int start = index + "some avg10=".length();
            final int end = pressure.indexOf(' ', start);
            return Float.parseFloat(pressure.substring(start,
                    end > start ? end : pressure.length()));
        } catch (Exception e) {
            return 0.0f;
        }
    }

    private boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, !mLowRamDevice);
    }

    private static boolean isLowRamDevice() {
        final MemInfoReader reader = new MemInfoReader();
        reader.readMemInfo();
        return reader.getTotalSizeKb() > 0L && reader.getTotalSizeKb() <= LOW_RAM_LIMIT_KB;
    }
}
