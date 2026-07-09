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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.power.Boost;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.os.ProcessCpuTracker;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class AxSmartCpuManager extends SystemService {
    private static final String TAG = "AxSmartCpu";

    private static final boolean DEBUG = SystemProperties.getBoolean(
            "persist.sys.ax.smart_cpu.debug", false);
    private static final boolean DEFAULT_ENABLED = true;

    private static final String PROP_ENABLED = "persist.sys.ax.smart_cpu.enabled";
    private static final String PROP_CPU_THRESHOLD = "persist.sys.ax.smart_cpu.threshold";
    private static final String PROP_INPUT_MS = "persist.sys.ax.smart_cpu.input_ms";
    private static final String PROP_POLL_MS = "persist.sys.ax.smart_cpu.poll_ms";
    private static final String PROP_SUPPRESS_MS = "persist.sys.ax.smart_cpu.suppress_ms";
    private static final String PROP_PROCESS_CPU_LIMIT = "persist.sys.ax.smart_cpu.proc_limit";

    private static final int MSG_SETUP_CGROUPS = 1;
    private static final int MSG_RESTORE_INPUT = 2;
    private static final int MSG_CHECK_CPU = 3;
    private static final int MSG_RELEASE_SUPPRESSED = 4;

    private static final int CPU_THRESHOLD_DEFAULT = 80;
    private static final int CPU_THRESHOLD_MIN = 40;
    private static final int CPU_HYSTERESIS = 10;
    private static final int INPUT_DURATION_DEFAULT_MS = 480;
    private static final int INPUT_DURATION_MIN_MS = 120;
    private static final int INPUT_DURATION_MAX_MS = 1000;
    private static final int POLL_DURATION_DEFAULT_MS = 1000;
    private static final int SUPPRESS_DURATION_DEFAULT_MS = 4000;
    private static final int PROCESS_CPU_LIMIT_DEFAULT = 10;
    private static final int MAX_SUPPRESSED_PROCESSES = 3;

    private static final String CPUSET_ROOT = "/dev/cpuset";
    private static final String SYS_CPU_ROOT = "/sys/devices/system/cpu";
    private static final String PROC_STAT = "/proc/stat";

    private static final String GROUP_AX_FOREGROUND = "ax_foreground";
    private static final String GROUP_FOREGROUND = "foreground";
    private static final String GROUP_BACKGROUND = "background";
    private static final String GROUP_SYSTEM_BACKGROUND = "system-background";
    private static final String GROUP_L_BACKGROUND = "l-background";
    private static final String GROUP_H_BACKGROUND = "h-background";
    private static final String GROUP_FOREGROUND_WINDOW = "foreground_window";
    private static final String GROUP_TOP_APP = "top-app";
    private static final String GROUP_SYSTEMUI = "systemui";
    private static final String GROUP_AUDIO_APP = "audio-app";
    private static final String GROUP_DEX2OAT = "dex2oat";
    private static final String GROUP_RESTRICTED = "restricted";
    private static final String GROUP_CAMERA_DAEMON = "camera-daemon";

    private final ActivityManagerService mService;
    private final Handler mHandler;
    private final SparseArray<SuppressedProcess> mSuppressedProcesses = new SparseArray<>();
    private final ArrayMap<String, String> mCpusetValues = new ArrayMap<>();

    private CpuPolicy mPolicy;
    private boolean mCpuLoadHigh;
    private long mLastTotalJiffies;
    private long mLastIdleJiffies;
    private final AtomicLong mInputToken = new AtomicLong();

    public AxSmartCpuManager(Context context, ActivityManagerService service) {
        super(context);
        mService = Objects.requireNonNull(service);
        mHandler = new SmartCpuHandler(IoThread.get().getLooper());
    }

    @Override
    public void onStart() {
        LocalServices.addService(AxSmartCpuManager.class, this);
        if (isEnabled()) {
            mHandler.sendEmptyMessage(MSG_SETUP_CGROUPS);
        }
    }

    public void onPowerBoost(int boost, int durationMs) {
        if (!isEnabled() || boost != Boost.INTERACTION) {
            return;
        }
        final Message msg = mHandler.obtainMessage(MSG_RESTORE_INPUT, nextInputToken());
        final int boundedDuration = boundDuration(durationMs);
        mHandler.removeMessages(MSG_RESTORE_INPUT);
        mHandler.post(this::restrictAxForegroundForInput);
        mHandler.sendMessageDelayed(msg, boundedDuration);
    }

    public void onProcessStarted(ProcessRecord app) {
        if (!isEnabled() || app == null || app.getHostingRecord() == null) {
            return;
        }
        final HostingRecord hostingRecord = app.getHostingRecord();
        if (!HOSTING_TYPE_NEXT_TOP_ACTIVITY.equals(hostingRecord.getType()) || isSystemApp(app)) {
            return;
        }
        final Message msg = mHandler.obtainMessage(MSG_CHECK_CPU, 1, app.uid);
        mHandler.removeMessages(MSG_CHECK_CPU);
        mHandler.sendMessage(msg);
    }

    private void setupCgroups() {
        final CpuPolicy policy = detectPolicy();
        mPolicy = policy;
        writeCpuset(GROUP_AX_FOREGROUND, policy.foreground, true);
        writeCpuset(GROUP_FOREGROUND, policy.foreground, false);
        writeCpuset(GROUP_FOREGROUND_WINDOW, policy.all, false);
        writeCpuset(GROUP_BACKGROUND, policy.small, false);
        writeCpuset(GROUP_SYSTEM_BACKGROUND, policy.small, false);
        writeCpuset(GROUP_L_BACKGROUND, policy.small, true);
        writeCpuset(GROUP_H_BACKGROUND, policy.backgroundHigh, true);
        writeCpuset(GROUP_DEX2OAT, policy.small, true);
        writeCpuset(GROUP_RESTRICTED, policy.small, false);
        writeCpuset(GROUP_TOP_APP, policy.all, false);
        writeCpuset(GROUP_SYSTEMUI, policy.all, true);
        writeCpuset(GROUP_AUDIO_APP, policy.all, true);
        writeCpuset(GROUP_CAMERA_DAEMON, policy.all, false);
        if (DEBUG) {
            Slog.i(TAG, "policy all=" + policy.all + " fg=" + policy.foreground
                    + " small=" + policy.small + " input=" + policy.input);
        }
        readCpuLoad();
    }

    private void restrictAxForegroundForInput() {
        final CpuPolicy policy = ensurePolicy();
        writeCpuset(GROUP_AX_FOREGROUND, policy.input, true);
    }

    private void restoreAxForeground(long token) {
        if (mInputToken.get() != token) {
            return;
        }
        final CpuPolicy policy = ensurePolicy();
        writeCpuset(GROUP_AX_FOREGROUND, policy.foreground, true);
    }

    private void checkCpu(int reason, int startingUid) {
        final int load = readCpuLoad();
        if (load >= 0) {
            boolean high = mCpuLoadHigh;
            final int threshold = cpuThreshold();
            if (load > threshold) {
                high = true;
            } else if (load < threshold - CPU_HYSTERESIS) {
                high = false;
            }
            mCpuLoadHigh = high;
        }
        if (!mCpuLoadHigh) {
            releaseSuppressedProcesses();
            return;
        }
        applyPressurePolicy(startingUid);
        if (reason == 1 && mSuppressedProcesses.size() == 0) {
            final Message retry = mHandler.obtainMessage(MSG_CHECK_CPU, 2, startingUid);
            mHandler.sendMessageDelayed(retry, 500L);
        } else {
            mHandler.sendEmptyMessageDelayed(MSG_CHECK_CPU, pollDurationMs());
        }
    }

    private void applyPressurePolicy(int startingUid) {
        applyPressureCgroups();
        final SparseIntArray cpuByPid = collectCpuByPid();
        if (cpuByPid.size() == 0) {
            return;
        }
        final List<CandidateProcess> candidates = new ArrayList<>();
        synchronized (mService.mPidsSelfLocked) {
            for (int i = 0; i < mService.mPidsSelfLocked.size(); i++) {
                final ProcessRecord app = mService.mPidsSelfLocked.valueAt(i);
                final int pid = app.getPid();
                final int cpu = cpuByPid.get(pid, 0);
                if (isSuppressible(app, startingUid, cpu)) {
                    candidates.add(new CandidateProcess(app, cpu));
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Collections.sort(candidates, (left, right) -> Integer.compare(right.cpu, left.cpu));
        final int count = Math.min(MAX_SUPPRESSED_PROCESSES, candidates.size());
        for (int i = 0; i < count; i++) {
            suppressProcess(candidates.get(i).app);
        }
        mHandler.removeMessages(MSG_RELEASE_SUPPRESSED);
        mHandler.sendEmptyMessageDelayed(MSG_RELEASE_SUPPRESSED, suppressDurationMs());
    }

    private void applyPressureCgroups() {
        final CpuPolicy policy = ensurePolicy();
        writeCpuset(GROUP_BACKGROUND, policy.small, false);
        writeCpuset(GROUP_SYSTEM_BACKGROUND, policy.small, false);
        writeCpuset(GROUP_L_BACKGROUND, policy.small, true);
        writeCpuset(GROUP_H_BACKGROUND, policy.backgroundHigh, true);
        writeCpuset(GROUP_DEX2OAT, policy.small, true);
        writeCpuset(GROUP_RESTRICTED, policy.small, false);
    }

    private SparseIntArray collectCpuByPid() {
        final SparseIntArray result = new SparseIntArray();
        mService.updateCpuStatsNow();
        final List<ProcessCpuTracker.Stats> stats = mService.mAppProfiler.getCpuStats(
                stat -> stat.pid > 0 && stat.uid >= Process.FIRST_APPLICATION_UID
                        && stat.rel_uptime > 0 && stat.rel_utime + stat.rel_stime > 0);
        for (int i = 0; i < stats.size(); i++) {
            final ProcessCpuTracker.Stats stat = stats.get(i);
            final int cpu = (int) Math.min(100L,
                    ((long) stat.rel_utime + stat.rel_stime) * 100L / stat.rel_uptime);
            result.put(stat.pid, cpu);
        }
        return result;
    }

    private boolean isSuppressible(ProcessRecord app, int startingUid, int cpu) {
        if (app == null || app.getPid() <= 0 || app.getPid() == ActivityManagerService.MY_PID
                || app.uid == startingUid || app.uid < Process.FIRST_APPLICATION_UID
                || app.isPersistent() || app.isKilled() || isSystemApp(app) || app.isSystemUi()
                || app.isLauncher3()) {
            return false;
        }
        if (cpu <= processCpuLimit()) {
            return false;
        }
        final int procState = app.getCurProcState();
        return procState >= ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
    }

    private void suppressProcess(ProcessRecord app) {
        final int pid = app.getPid();
        final SuppressedProcess existing = mSuppressedProcesses.get(pid);
        if (existing == null) {
            mSuppressedProcesses.put(pid,
                    new SuppressedProcess(pid, app.uid, app.processName));
        }
        try {
            Process.setProcessGroup(pid, Process.THREAD_GROUP_L_BACKGROUND);
            if (DEBUG) {
                Slog.i(TAG, "suppress " + app.processName + " pid=" + pid);
            }
        } catch (Exception e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed suppressing " + app.processName + " pid=" + pid, e);
            }
        }
    }

    private void releaseSuppressedProcesses() {
        if (mSuppressedProcesses.size() == 0) {
            return;
        }
        final List<SuppressedProcess> pending = new ArrayList<>();
        for (int i = 0; i < mSuppressedProcesses.size(); i++) {
            pending.add(mSuppressedProcesses.valueAt(i));
        }
        mSuppressedProcesses.clear();
        for (int i = 0; i < pending.size(); i++) {
            releaseSuppressedProcess(pending.get(i));
        }
    }

    private void releaseSuppressedProcess(SuppressedProcess suppressed) {
        final int group;
        synchronized (mService.mPidsSelfLocked) {
            final ProcessRecord app = mService.mPidsSelfLocked.get(suppressed.pid);
            if (app == null || app.uid != suppressed.uid) {
                return;
            }
            group = app.getSetProcessGroup();
        }
        try {
            Process.setProcessGroup(suppressed.pid, group);
            if (DEBUG) {
                Slog.i(TAG, "release " + suppressed.name + " pid=" + suppressed.pid);
            }
        } catch (Exception e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed releasing " + suppressed.name + " pid=" + suppressed.pid, e);
            }
        }
    }

    private CpuPolicy ensurePolicy() {
        if (mPolicy == null) {
            mPolicy = detectPolicy();
        }
        return mPolicy;
    }

    private CpuPolicy detectPolicy() {
        final List<CpuInfo> cpus = readCpuTopology();
        if (cpus.isEmpty()) {
            return new CpuPolicy("0", "0", "0", "0", "0");
        }
        Collections.sort(cpus, (left, right) -> {
            final int capacity = Integer.compare(left.capacity, right.capacity);
            return capacity != 0 ? capacity : Integer.compare(left.id, right.id);
        });
        final List<Integer> all = new ArrayList<>();
        final List<Integer> small = new ArrayList<>();
        final List<Integer> input = new ArrayList<>();
        final List<Integer> foreground = new ArrayList<>();
        final int smallCapacity = cpus.get(0).capacity;
        final int maxCapacity = cpus.get(cpus.size() - 1).capacity;
        for (int i = 0; i < cpus.size(); i++) {
            final CpuInfo cpu = cpus.get(i);
            all.add(cpu.id);
            if (cpu.capacity == smallCapacity) {
                small.add(cpu.id);
            }
            if (cpu.capacity < maxCapacity || cpus.size() <= 4) {
                foreground.add(cpu.id);
            }
        }
        for (int i = 0; i < cpus.size() && input.size() < 4; i++) {
            input.add(cpus.get(i).id);
        }
        if (foreground.isEmpty()) {
            foreground.addAll(all);
        }
        if (small.isEmpty()) {
            small.addAll(input);
        }
        Collections.sort(all);
        Collections.sort(small);
        Collections.sort(input);
        Collections.sort(foreground);
        final List<Integer> backgroundHigh = new ArrayList<>(small);
        for (int i = 0; i < cpus.size() && backgroundHigh.size() < Math.min(6, cpus.size()); i++) {
            final int cpu = cpus.get(i).id;
            if (!backgroundHigh.contains(cpu)) {
                backgroundHigh.add(cpu);
            }
        }
        Collections.sort(backgroundHigh);
        return new CpuPolicy(formatCpuList(all), formatCpuList(foreground), formatCpuList(small),
                formatCpuList(backgroundHigh), formatCpuList(input));
    }

    private List<CpuInfo> readCpuTopology() {
        final Set<Integer> online = readOnlineCpus();
        final File root = new File(SYS_CPU_ROOT);
        final File[] files = root.listFiles();
        final List<CpuInfo> cpus = new ArrayList<>();
        if (files == null) {
            return cpus;
        }
        for (int i = 0; i < files.length; i++) {
            final String name = files[i].getName();
            if (!name.startsWith("cpu") || name.length() <= 3 || !isDecimal(name, 3)) {
                continue;
            }
            final int cpu = Integer.parseInt(name.substring(3));
            if (!online.isEmpty() && !online.contains(cpu)) {
                continue;
            }
            cpus.add(new CpuInfo(cpu, readCpuCapacity(cpu)));
        }
        return cpus;
    }

    private int readCpuCapacity(int cpu) {
        final int capacity = readInt(SYS_CPU_ROOT + "/cpu" + cpu + "/cpu_capacity", -1);
        if (capacity > 0) {
            return capacity;
        }
        final int maxFreq = readInt(SYS_CPU_ROOT + "/cpu" + cpu
                + "/cpufreq/cpuinfo_max_freq", -1);
        return maxFreq > 0 ? maxFreq : 1;
    }

    private Set<Integer> readOnlineCpus() {
        final String online = readString(SYS_CPU_ROOT + "/online");
        final Set<Integer> cpus = new HashSet<>();
        if (online == null) {
            return cpus;
        }
        final String[] ranges = online.trim().split(",");
        for (int i = 0; i < ranges.length; i++) {
            final String range = ranges[i].trim();
            if (range.isEmpty()) {
                continue;
            }
            final int dash = range.indexOf('-');
            try {
                if (dash < 0) {
                    cpus.add(Integer.parseInt(range));
                } else {
                    final int start = Integer.parseInt(range.substring(0, dash));
                    final int end = Integer.parseInt(range.substring(dash + 1));
                    for (int cpu = start; cpu <= end; cpu++) {
                        cpus.add(cpu);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return cpus;
    }

    private static String formatCpuList(List<Integer> cpus) {
        if (cpus.isEmpty()) {
            return "0";
        }
        final StringBuilder builder = new StringBuilder();
        int start = cpus.get(0);
        int last = start;
        for (int i = 1; i < cpus.size(); i++) {
            final int cpu = cpus.get(i);
            if (cpu == last + 1) {
                last = cpu;
                continue;
            }
            appendCpuRange(builder, start, last);
            start = cpu;
            last = cpu;
        }
        appendCpuRange(builder, start, last);
        return builder.toString();
    }

    private static void appendCpuRange(StringBuilder builder, int start, int end) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(start);
        if (end != start) {
            builder.append('-').append(end);
        }
    }

    private boolean writeCpuset(String group, String cpus, boolean create) {
        final File dir = new File(CPUSET_ROOT, group);
        boolean created = false;
        if (!dir.exists()) {
            if (!create || !dir.mkdirs()) {
                return false;
            }
            created = true;
        }
        final String path = new File(dir, "cpus").getAbsolutePath();
        if (created) {
            writeString(new File(dir, "mems").getAbsolutePath(), readRootMems());
            mCpusetValues.remove(path);
        }
        if (cpus.equals(mCpusetValues.get(path))) {
            return true;
        }
        if (!writeString(path, cpus)) {
            return false;
        }
        mCpusetValues.put(path, cpus);
        return true;
    }

    private String readRootMems() {
        final String mems = readString(CPUSET_ROOT + "/mems");
        return mems == null || mems.trim().isEmpty() ? "0" : mems.trim();
    }

    private boolean writeString(String path, String value) {
        try {
            FileUtils.stringToFile(path, value);
            return true;
        } catch (IOException | RuntimeException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed writing " + path + "=" + value, e);
            }
            return false;
        }
    }

    private int readCpuLoad() {
        final String stat = readString(PROC_STAT);
        if (stat == null || !stat.startsWith("cpu ")) {
            return -1;
        }
        final String[] parts = stat.split("\\s+");
        if (parts.length < 8) {
            return -1;
        }
        long total = 0L;
        try {
            for (int i = 1; i < Math.min(parts.length, 9); i++) {
                total += Long.parseLong(parts[i]);
            }
            final long idle = Long.parseLong(parts[4]) + Long.parseLong(parts[5]);
            final long deltaTotal = total - mLastTotalJiffies;
            final long deltaIdle = idle - mLastIdleJiffies;
            mLastTotalJiffies = total;
            mLastIdleJiffies = idle;
            if (deltaTotal <= 0L) {
                return -1;
            }
            return (int) Math.max(0L, Math.min(100L,
                    (deltaTotal - deltaIdle) * 100L / deltaTotal));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readString(String path) {
        try {
            final String value = FileUtils.readTextFile(new File(path), 128, null);
            return value == null ? null : value.trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int readInt(String path, int def) {
        final String value = readString(path);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean isDecimal(String value, int offset) {
        for (int i = offset; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, DEFAULT_ENABLED);
    }

    private int cpuThreshold() {
        return Math.max(CPU_THRESHOLD_MIN,
                SystemProperties.getInt(PROP_CPU_THRESHOLD, CPU_THRESHOLD_DEFAULT));
    }

    private int pollDurationMs() {
        return Math.max(250, SystemProperties.getInt(PROP_POLL_MS, POLL_DURATION_DEFAULT_MS));
    }

    private int suppressDurationMs() {
        return Math.max(500,
                SystemProperties.getInt(PROP_SUPPRESS_MS, SUPPRESS_DURATION_DEFAULT_MS));
    }

    private int processCpuLimit() {
        return Math.max(1,
                SystemProperties.getInt(PROP_PROCESS_CPU_LIMIT, PROCESS_CPU_LIMIT_DEFAULT));
    }

    private int boundDuration(int durationMs) {
        final int duration = durationMs > 0 ? durationMs
                : SystemProperties.getInt(PROP_INPUT_MS, INPUT_DURATION_DEFAULT_MS);
        return Math.min(Math.max(duration, INPUT_DURATION_MIN_MS), INPUT_DURATION_MAX_MS);
    }

    private long nextInputToken() {
        return mInputToken.incrementAndGet();
    }

    private static boolean isSystemApp(ProcessRecord app) {
        return app.info != null && (app.info.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private final class SmartCpuHandler extends Handler {
        SmartCpuHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SETUP_CGROUPS:
                    setupCgroups();
                    break;
                case MSG_RESTORE_INPUT:
                    restoreAxForeground((Long) msg.obj);
                    break;
                case MSG_CHECK_CPU:
                    checkCpu(msg.arg1, msg.arg2);
                    break;
                case MSG_RELEASE_SUPPRESSED:
                    releaseSuppressedProcesses();
                    break;
                default:
                    break;
            }
        }
    }

    private static final class CpuInfo {
        final int id;
        final int capacity;

        CpuInfo(int id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }
    }

    private static final class CpuPolicy {
        final String all;
        final String foreground;
        final String small;
        final String backgroundHigh;
        final String input;

        CpuPolicy(String all, String foreground, String small, String backgroundHigh,
                String input) {
            this.all = all;
            this.foreground = foreground;
            this.small = small;
            this.backgroundHigh = backgroundHigh;
            this.input = input;
        }
    }

    private static final class CandidateProcess {
        final ProcessRecord app;
        final int cpu;

        CandidateProcess(ProcessRecord app, int cpu) {
            this.app = app;
            this.cpu = cpu;
        }
    }

    private static final class SuppressedProcess {
        final int pid;
        final int uid;
        final String name;

        SuppressedProcess(int pid, int uid, String name) {
            this.pid = pid;
            this.uid = uid;
            this.name = name;
        }
    }
}
