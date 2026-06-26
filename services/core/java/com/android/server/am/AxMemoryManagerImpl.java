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

import android.app.pinner.PinnedFileStat;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.pinner.PinnedFile;
import com.android.server.pinner.PinnerService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class AxMemoryManagerImpl extends SystemService implements IAxMemoryManager {
    private static final String TAG = "AxMemoryManager";
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.nmm.debug", false);

    private static final String EXTRA_FREE_KBYTES_PROPERTY = "sys.sysctl.extra_free_kbytes";
    private static final String CAMERA_BOOST_PROPERTY = "persist.sys.nmm.boost.camera";
    private static final String LOAD_PACKAGE_KEY = "packageName";
    private static final String PINNER_GROUP = "axion";

    private static final int MSG_BOOST_CAMERA_START_WARM = 1;
    private static final int MSG_BOOST_CAMERA_RESET_WARM = 2;
    private static final int MSG_RELEASE_MEMORY_SCREEN_ON = 3;
    private static final int MSG_LOAD_PROCESS_MEMORY = 4;
    private static final int MSG_CAMERA_MEMORY_RELEASE = 5;
    private static final int MSG_BOOST_CAMERA_COLD_RESET = 6;

    private static final int EXTRA_FREE_FACTOR = 6;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int KIB = 1024;

    private static final long MEM_16GB = 16777216L;
    private static final long MEM_12GB = 12582912L;
    private static final long MEM_10GB = 10485760L;
    private static final long MEM_8GB = 8388608L;
    private static final long MEM_6GB = 6291456L;

    private static final List<String> DEFAULT_RELEASE_WHITELIST = List.of(
            "com.google.android.googlequicksearchbox:search",
            "com.google.android.gms",
            "com.android.chrome",
            "com.android.axion.widgets",
            "com.android.edge.bar");

    private static final List<String> CAMERA_PROCESSES = List.of(
            "com.google.android.GoogleCamera",
            "org.lineageos.aperture",
            "com.oplus.camera");

    private static final String[] PINNER_CANDIDATES = {
            "/vendor/lib64/egl/libGLES_mali.so",
            "/vendor/lib64/egl/libEGL_adreno.so",
            "/vendor/lib64/egl/libGLESv2_adreno.so",
            "/vendor/lib64/hw/gralloc.default.so",
            "/vendor/lib64/hw/vulkan.mali.so",
            "/vendor/lib64/hw/mapper.pixel.so",
            "/vendor/lib64/hw/android.hardware.graphics.allocator-aidl-impl.so",
            "/vendor/lib64/libexynosdisplay.so",
            "/vendor/lib/lib_aion_buffer.so",
            "/vendor/lib64/lib_aion_buffer.so",
            "/system/lib64/libhwui.so",
            "/system/lib64/libRenderEngine.so",
            "/system/lib64/libandroid_runtime.so",
            "/system/lib64/libandroid_servers.so",
            "/system/lib64/libandroidfw.so",
            "/system/lib64/libandroid.so",
            "/system/lib64/libjpeg.so",
            "/system/lib64/libutils.so",
            "/system/lib64/libbinder.so",
            "/system/lib64/libbinder_ndk.so",
            "/system/lib64/libgui.so",
            "/system/lib64/libmedia.so",
            "/apex/com.android.art/lib64/libart.so",
            "/apex/com.android.art/lib64/libartbase.so",
            "/apex/com.android.art/javalib/okhttp.jar",
            "/apex/com.android.art/javalib/bouncycastle.jar",
            "/apex/com.android.media/javalib/updatable-media.jar",
            "/system_ext/priv-app/SystemUI/SystemUI.apk",
            "/system/framework/ext.jar",
            "/system/framework/telephony-common.jar",
            "/system/framework/arm64/boot-framework.oat",
            "/system/framework/arm64/boot-framework.vdex",
            "/system/framework/arm64/boot.oat",
            "/system/framework/arm64/boot.vdex",
            "/system/framework/arm64/boot-core-libart.oat",
            "/system/framework/arm64/boot-core-libart.vdex",
            "/system/framework/arm64/boot-ext.vdex",
    };

    private static final Comparator<ProcessInfo> BY_RSS = Comparator.comparingLong(pi -> pi.rss);
    private static final Comparator<ProcessInfo> BY_ADJ = Comparator.comparingInt(pi -> pi.adj);
    private static final Comparator<ProcessInfo> BY_SCORE =
            Comparator.comparingDouble(pi -> pi.score);

    private final ServiceThread mThread;
    private final Handler mHandler;
    private final List<String> mReleaseProcessWhiteList = new ArrayList<>(DEFAULT_RELEASE_WHITELIST);

    private ActivityManagerService mService;
    private volatile boolean mIsBoostingCameraCold;
    private volatile boolean mIsBoostingCameraWarm;
    private volatile boolean mSystemReady;
    private long mBoostCameraDuration = 5000L;
    private int mKillProcessCount = 20;
    private int mKillProcessCountWarmStart = 5;
    private long mLastScreenOnTime;
    private long mReleaseMemoryDuration = 3600000L;
    private float mWeight = 10f;
    private int mReleaseMemoryKillCount = 5;
    private int mReleaseAdj = 900;

    public AxMemoryManagerImpl(Context context) {
        this(context, null);
    }

    public AxMemoryManagerImpl(Context context, ActivityManagerService service) {
        super(context);
        mService = service;
        mThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND, true);
        mThread.start();
        mHandler = new MemoryManagerHandler(mThread.getLooper());
    }

    @Override
    public void onStart() {
        LocalServices.addService(IAxMemoryManager.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            loadReleaseMemoryConfig();
            loadBoostCamera();
            tuneDefaultDisplayExtraFreeKbytes();
            tunePinner();
            mSystemReady = true;
        }
    }

    @Override
    public void tuneExtraFreeKbytes(int displayWidth, int displayHeight) {
        SystemProperties.set(EXTRA_FREE_KBYTES_PROPERTY,
                Integer.toString(calculateExtraFreeKbytes(displayWidth, displayHeight)));
    }

    @Override
    public void boostCamera(boolean isColdStart) {
        if (!mSystemReady) {
            return;
        }
        if (isColdStart) {
            if (!mIsBoostingCameraCold) {
                mIsBoostingCameraCold = true;
                mHandler.sendMessage(mHandler.obtainMessage(MSG_CAMERA_MEMORY_RELEASE));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_COLD_RESET),
                        mBoostCameraDuration);
            }
        }
        if (!mIsBoostingCameraWarm) {
            mIsBoostingCameraWarm = true;
            mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_CAMERA_START_WARM));
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_RESET_WARM),
                    mBoostCameraDuration);
        }
    }

    @Override
    public void releaseMemoryAtScreenOn() {
        if (!mSystemReady) {
            return;
        }
        long current = SystemClock.elapsedRealtime();
        long last = mLastScreenOnTime;
        if (last == 0L || current - last > mReleaseMemoryDuration) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RELEASE_MEMORY_SCREEN_ON));
            mLastScreenOnTime = current;
        } else if (DEBUG) {
            Slog.d(TAG, "Release memory skipped due to cooldown. last:" + last + " now:" + current);
        }
    }

    @Override
    public void loadProcessMemory(String packageName) {
        if (!mSystemReady || packageName == null || packageName.isEmpty()) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString(LOAD_PACKAGE_KEY, packageName);
        Message message = mHandler.obtainMessage(MSG_LOAD_PROCESS_MEMORY);
        message.setData(bundle);
        mHandler.sendMessage(message);
    }

    @Override
    public void releaseMemory(int minAdj, int maxKillCount, boolean includeUiProcesses,
            boolean skipCamera) {
        if (!mSystemReady) {
            return;
        }
        List<String> whitelist = skipCamera ? CAMERA_PROCESSES : List.of();
        mHandler.post(() -> releaseMemoryInternal(minAdj, maxKillCount, includeUiProcesses,
                whitelist));
    }

    @Override
    public boolean isCameraProcess(String processName) {
        return isInProcessList(processName, CAMERA_PROCESSES);
    }

    @Override
    public long getPhysicalMemory() {
        MemInfoReader reader = new MemInfoReader();
        reader.readMemInfo();
        long memTotal = reader.getTotalSizeKb();
        if (memTotal <= 0) {
            return -1L;
        }
        if (memTotal > MEM_12GB) {
            return MEM_16GB;
        }
        if (memTotal > MEM_10GB) {
            return MEM_12GB;
        }
        if (memTotal > MEM_8GB) {
            return MEM_10GB;
        }
        if (memTotal > MEM_6GB) {
            return MEM_8GB;
        }
        return MEM_6GB;
    }

    public static int calculateExtraFreeKbytes(int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return 0;
        }
        long extraFreeKbytes =
                (long) displayWidth * displayHeight * BYTES_PER_PIXEL * EXTRA_FREE_FACTOR / KIB;
        return (int) Math.min(Integer.MAX_VALUE, extraFreeKbytes);
    }

    private void releaseMemoryInternal(int adjThreshold, int killLimit, boolean allowUiKill,
            List<String> whitelist) {
        ActivityManagerService service = mService;
        if (killLimit <= 0 || service == null) {
            return;
        }

        ArrayList<ProcessInfo> candidates = new ArrayList<>();
        synchronized (service.mProcLock) {
            ArrayList<ProcessRecord> lruProcesses = service.mProcessList.getLruProcessesLOSP();
            for (int i = 0, size = lruProcesses.size(); i < size; i++) {
                ProcessRecord process = lruProcesses.get(i);
                if (process == null) {
                    continue;
                }
                int adj = process.getSetAdj();
                if (adj < adjThreshold) {
                    continue;
                }
                if (!allowUiKill && process.hasActivities()) {
                    continue;
                }
                if (isInProcessList(process.processName, whitelist)) {
                    continue;
                }
                int pid = process.getPid();
                if (pid <= 0) {
                    continue;
                }
                candidates.add(new ProcessInfo(pid, adj, process.getLastRss(), process.processName));
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        float adjWeight = allowUiKill ? 1.0f : mWeight / 10.0f;
        float rssWeight = 1.0f - adjWeight;
        if (rssWeight != 0.0f) {
            Collections.sort(candidates, BY_RSS);
            applyGroupOffsets(candidates, rssWeight, true);
        }
        if (adjWeight != 0.0f) {
            Collections.sort(candidates, BY_ADJ);
            applyGroupOffsets(candidates, adjWeight, false);
        }
        Collections.sort(candidates, BY_SCORE);

        int killed = 0;
        for (int i = 0, size = candidates.size(); i < size && killed < killLimit; i++) {
            ProcessInfo processInfo = candidates.get(i);
            Process.killProcess(processInfo.pid);
            killed++;
            if (DEBUG) {
                Slog.d(TAG, "Killed " + processInfo.name + " pid=" + processInfo.pid
                        + " adj=" + processInfo.adj + " rss=" + processInfo.rss
                        + " score=" + processInfo.score);
            }
        }
    }

    private void applyGroupOffsets(List<ProcessInfo> list, float weight, boolean useRss) {
        Object previousValue = null;
        int groupIndex = -1;
        for (int i = 0, size = list.size(); i < size; i++) {
            ProcessInfo processInfo = list.get(i);
            Object currentValue = useRss ? processInfo.rss : processInfo.adj;
            if (!currentValue.equals(previousValue)) {
                groupIndex++;
                previousValue = currentValue;
            }
            processInfo.score += groupIndex * weight;
        }
    }

    private static boolean isInProcessList(String processName, List<String> processList) {
        if (processName == null || processList == null) {
            return false;
        }
        for (int i = 0, size = processList.size(); i < size; i++) {
            String item = processList.get(i);
            if (processName.equals(item) || processName.startsWith(item + ":")) {
                return true;
            }
        }
        return false;
    }

    private void startLoadProcessMemory(String packageName) {
        ActivityManagerService service = mService;
        if (service == null || packageName == null || packageName.isEmpty()) {
            return;
        }
        ProcessRecord processRecord = getProcessRecord(packageName);
        if (processRecord == null) {
            return;
        }
        synchronized (service.mProcLock) {
            service.getCachedAppOptimizer().compactApp(processRecord,
                    CachedAppOptimizer.CompactProfile.POPULATE,
                    CachedAppOptimizer.CompactSource.SHELL, true);
        }
    }

    private ProcessRecord getProcessRecord(String packageName) {
        ActivityManagerService service = mService;
        if (service == null) {
            return null;
        }
        int userId = service.getCurrentUserId();
        int packageUid = service.getPackageManagerInternal().getPackageUid(packageName, 0L,
                userId);
        if (packageUid < 0) {
            return null;
        }
        synchronized (service.mProcLock) {
            return service.getProcessRecordLocked(packageName, packageUid);
        }
    }

    private void loadBoostCamera() {
        long memorySize = getPhysicalMemory();
        if (memorySize == MEM_12GB) {
            mKillProcessCount = 5;
            mKillProcessCountWarmStart = 5;
        } else {
            mKillProcessCount = 15;
            mKillProcessCountWarmStart = 5;
        }
        if (DEBUG) {
            Slog.d(TAG, "KillProcessCount : " + mKillProcessCount);
            Slog.d(TAG, "KillProcessCountWarmStart : " + mKillProcessCountWarmStart);
        }
    }

    private void loadReleaseMemoryConfig() {
        mReleaseMemoryKillCount = getPhysicalMemory() == MEM_12GB ? 10 : 20;
        if (DEBUG) {
            Slog.d(TAG, "KillProcessScreenOnCount : " + mReleaseMemoryKillCount);
        }
    }

    private void tuneDefaultDisplayExtraFreeKbytes() {
        DisplayManagerInternal displayManager =
                LocalServices.getService(DisplayManagerInternal.class);
        if (displayManager == null) {
            return;
        }

        DisplayInfo displayInfo = displayManager.getDisplayInfo(Display.DEFAULT_DISPLAY);
        if (displayInfo == null) {
            return;
        }

        tuneExtraFreeKbytes(displayInfo.logicalWidth, displayInfo.logicalHeight);
    }

    private void tunePinner() {
        PinnerService pinner = LocalServices.getService(PinnerService.class);
        if (pinner == null) {
            return;
        }

        HashSet<String> alreadyPinned = new HashSet<>();
        for (PinnedFileStat stat : pinner.getPinnerStats()) {
            alreadyPinned.add(stat.getFilename());
        }

        int pinned = 0;
        for (String path : PINNER_CANDIDATES) {
            if (alreadyPinned.contains(path) || !new File(path).exists()) {
                continue;
            }
            PinnedFile pinnedFile = pinner.pinFile(path, Integer.MAX_VALUE, null, PINNER_GROUP,
                    false);
            if (pinnedFile != null) {
                pinned++;
                if (DEBUG) {
                    Slog.d(TAG, "Pinned: " + path + " (" + pinnedFile.bytesPinned + " bytes)");
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "Pinner: pinned " + pinned + " additional files");
        }
    }

    private final class MemoryManagerHandler extends Handler {
        MemoryManagerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_BOOST_CAMERA_START_WARM:
                    SystemProperties.set(CAMERA_BOOST_PROPERTY, "1");
                    releaseMemoryInternal(mReleaseAdj, mKillProcessCountWarmStart, true,
                            List.of());
                    break;
                case MSG_BOOST_CAMERA_RESET_WARM:
                    mIsBoostingCameraWarm = false;
                    break;
                case MSG_RELEASE_MEMORY_SCREEN_ON:
                    SystemProperties.set(CAMERA_BOOST_PROPERTY, "1");
                    releaseMemoryInternal(mReleaseAdj, mReleaseMemoryKillCount, false,
                            mReleaseProcessWhiteList);
                    break;
                case MSG_LOAD_PROCESS_MEMORY:
                    startLoadProcessMemory(message.getData().getString(LOAD_PACKAGE_KEY, ""));
                    break;
                case MSG_CAMERA_MEMORY_RELEASE:
                    SystemProperties.set(CAMERA_BOOST_PROPERTY, "1");
                    releaseMemoryInternal(mReleaseAdj, mKillProcessCount, true,
                            List.of());
                    break;
                case MSG_BOOST_CAMERA_COLD_RESET:
                    mIsBoostingCameraCold = false;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class ProcessInfo {
        final int pid;
        final int adj;
        final long rss;
        final String name;
        float score;

        ProcessInfo(int pid, int adj, long rss, String name) {
            this.pid = pid;
            this.adj = adj;
            this.rss = rss;
            this.name = name;
        }
    }
}
