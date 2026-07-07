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

import android.os.FileUtils;
import android.os.SystemClock;
import android.util.SparseArray;

import java.io.IOException;

final class AxBurstScheduler {
    static final int MODE_LAUNCH = 1;
    static final int MODE_ANIMATION = 2;
    static final int MODE_REMOTE = 3;
    static final int MODE_TOP_APP = 4;
    static final int MODE_PERF = 5;

    private static final String SCENE_PATH = "/proc/ax_burst_sched/scene";
    private static final String SVP_TASKS_PATH = "/proc/ax_burst_sched/svp_tasks";
    private static final long RETRY_DELAY_MS = 5000L;
    private static final long RESCHEDULE_SLOP_MS = 96L;

    private final SparseArray<BurstState> mStates = new SparseArray<>();
    private final SparseArray<SvpState> mSvpStates = new SparseArray<>();
    private final StringBuilder mBuilder = new StringBuilder(96);
    private long mSceneRetryUptimeMs;
    private long mSvpRetryUptimeMs;

    boolean set(AxUiSession session) {
        final boolean sticky = isSticky(session);
        if (session == null || session.pid <= 0 || session.mode <= 0
                || (session.durationMs <= 0 && !sticky)) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        final long expiryUptimeMs = sticky ? Long.MAX_VALUE : now + session.durationMs;
        final BurstState state = mStates.get(session.pid);
        if (state != null && state.expiryUptimeMs > now && state.matches(session)) {
            if (sticky || expiryUptimeMs <= state.expiryUptimeMs + RESCHEDULE_SLOP_MS) {
                setSvp(session, expiryUptimeMs);
                return true;
            }
        }
        if (writeScene(commandFor(session))) {
            mStates.put(session.pid, new BurstState(session, expiryUptimeMs));
            setSvp(session, expiryUptimeMs);
            return true;
        }
        return false;
    }

    void clear(int pid) {
        if (pid <= 0) {
            return;
        }
        mStates.remove(pid);
        writeScene(clearCommand(pid));
        clearSvp(pid);
    }

    void clearAll() {
        if (mStates.size() == 0 && mSvpStates.size() == 0) {
            return;
        }
        mStates.clear();
        mSvpStates.clear();
        writeScene(clearCommand(0));
        writeSvp(clearSvpCommand(0));
    }

    void reset() {
        mStates.clear();
        mSvpStates.clear();
        writeScene(clearCommand(0));
        writeSvp(clearSvpCommand(0));
    }

    private boolean writeScene(String command) {
        if (!canWrite(mSceneRetryUptimeMs)) {
            return false;
        }
        try {
            FileUtils.stringToFile(SCENE_PATH, command);
            mSceneRetryUptimeMs = 0L;
            return true;
        } catch (IOException | RuntimeException ignored) {
            mSceneRetryUptimeMs = SystemClock.uptimeMillis() + RETRY_DELAY_MS;
            return false;
        }
    }

    private boolean writeSvp(String command) {
        if (!canWrite(mSvpRetryUptimeMs)) {
            return false;
        }
        try {
            FileUtils.stringToFile(SVP_TASKS_PATH, command);
            mSvpRetryUptimeMs = 0L;
            return true;
        } catch (IOException | RuntimeException ignored) {
            mSvpRetryUptimeMs = SystemClock.uptimeMillis() + RETRY_DELAY_MS;
            return false;
        }
    }

    private static boolean canWrite(long retryUptimeMs) {
        return retryUptimeMs == 0L || SystemClock.uptimeMillis() >= retryUptimeMs;
    }

    private static boolean isSticky(AxUiSession session) {
        return session != null && (session.mode == MODE_TOP_APP
                || (session.source == AxUiSession.SOURCE_GAME && session.durationMs <= 0));
    }

    private void setSvp(AxUiSession session, long expiryUptimeMs) {
        final int level = svpLevelFor(session);
        if (level <= 0) {
            clearSvp(session.pid);
            return;
        }
        final boolean sticky = isSticky(session);
        final SvpState state = mSvpStates.get(session.pid);
        final long now = SystemClock.uptimeMillis();
        if (state != null && state.expiryUptimeMs > now && state.matches(session, level)
                && (sticky || expiryUptimeMs <= state.expiryUptimeMs + RESCHEDULE_SLOP_MS)) {
            return;
        }
        if (writeSvp(svpCommandFor(session, level))) {
            mSvpStates.put(session.pid, new SvpState(session, level, expiryUptimeMs));
        }
    }

    private void clearSvp(int pid) {
        if (pid <= 0) {
            return;
        }
        if (mSvpStates.get(pid) == null) {
            return;
        }
        mSvpStates.remove(pid);
        writeSvp(clearSvpCommand(pid));
    }

    private static int svpLevelFor(AxUiSession session) {
        if (session.mode == MODE_TOP_APP) {
            return 2;
        }
        if (session.durationMs <= 0) {
            return 0;
        }
        if (session.severity >= AxUiSession.SEVERITY_HEAVY
                || session.role == AxUiSession.ROLE_SYSTEM_UI
                || session.role == AxUiSession.ROLE_LAUNCHER) {
            return 3;
        }
        return 2;
    }

    private String commandFor(AxUiSession session) {
        final StringBuilder builder = mBuilder;
        builder.setLength(0);
        builder.append(session.pid)
                .append(' ')
                .append(session.uid)
                .append(' ')
                .append(session.mode)
                .append(' ')
                .append(session.source)
                .append(' ')
                .append(session.severity)
                .append(' ')
                .append(Math.min(session.durationMs, Integer.MAX_VALUE));
        builder.append(' ').append(session.tidCount);
        appendTid(builder, session.tid1);
        appendTid(builder, session.tid2);
        appendTid(builder, session.tid3);
        appendTid(builder, session.tid4);
        builder.append(' ').append(session.role);
        return builder.toString();
    }

    private String svpCommandFor(AxUiSession session, int level) {
        final StringBuilder builder = mBuilder;
        builder.setLength(0);
        builder.append(session.pid)
                .append(' ')
                .append(level)
                .append(' ')
                .append(Math.min(session.durationMs, Integer.MAX_VALUE));
        builder.append(' ').append(validTidCount(session));
        appendTid(builder, session.tid1);
        appendTid(builder, session.tid2);
        appendTid(builder, session.tid3);
        appendTid(builder, session.tid4);
        return builder.toString();
    }

    private static int validTidCount(AxUiSession session) {
        int count = 0;
        if (session.tid1 > 0) {
            count++;
        }
        if (session.tid2 > 0) {
            count++;
        }
        if (session.tid3 > 0) {
            count++;
        }
        if (session.tid4 > 0) {
            count++;
        }
        return count;
    }

    private static void appendTid(StringBuilder builder, int tid) {
        if (tid > 0) {
            builder.append(' ').append(tid);
        }
    }

    private String clearCommand(int pid) {
        final StringBuilder builder = mBuilder;
        builder.setLength(0);
        return builder.append(pid).append(" 0 0 0 0 0 0").toString();
    }

    private String clearSvpCommand(int pid) {
        final StringBuilder builder = mBuilder;
        builder.setLength(0);
        return builder.append(pid).append(" 0 0 0").toString();
    }

    private static final class BurstState {
        final int uid;
        final int mode;
        final int source;
        final int severity;
        final long expiryUptimeMs;
        final int tidCount;
        final int tid1;
        final int tid2;
        final int tid3;
        final int tid4;
        final int role;

        BurstState(AxUiSession session, long expiryUptimeMs) {
            uid = session.uid;
            mode = session.mode;
            source = session.source;
            severity = session.severity;
            tidCount = session.tidCount;
            tid1 = session.tid1;
            tid2 = session.tid2;
            tid3 = session.tid3;
            tid4 = session.tid4;
            role = session.role;
            this.expiryUptimeMs = expiryUptimeMs;
        }

        boolean matches(AxUiSession session) {
            return session.uid == uid && session.mode == mode && session.source == source
                    && session.severity == severity && session.tidCount == tidCount
                    && session.tid1 == tid1 && session.tid2 == tid2 && session.tid3 == tid3
                    && session.tid4 == tid4 && session.role == role;
        }
    }

    private static final class SvpState {
        final int level;
        final long expiryUptimeMs;
        final int tid1;
        final int tid2;
        final int tid3;
        final int tid4;

        SvpState(AxUiSession session, int level, long expiryUptimeMs) {
            this.level = level;
            this.expiryUptimeMs = expiryUptimeMs;
            tid1 = session.tid1;
            tid2 = session.tid2;
            tid3 = session.tid3;
            tid4 = session.tid4;
        }

        boolean matches(AxUiSession session, int level) {
            return this.level == level && session.tid1 == tid1 && session.tid2 == tid2
                    && session.tid3 == tid3 && session.tid4 == tid4;
        }
    }
}
