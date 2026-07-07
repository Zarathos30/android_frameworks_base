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

final class AxUiSession {
    static final int SOURCE_LAUNCH = 1;
    static final int SOURCE_ANIMATION = 2;
    static final int SOURCE_REMOTE = 3;
    static final int SOURCE_START_ACTIVITY_BINDER = 5;
    static final int SOURCE_TOP_APP = 6;
    static final int SOURCE_ADPF_CPU = 7;
    static final int SOURCE_ADPF_GPU = 8;
    static final int SOURCE_POWER_INTERACTION = 9;
    static final int SOURCE_POWER_DISPLAY = 10;
    static final int SOURCE_POWER_LAUNCH = 11;
    static final int SOURCE_POWER_RENDER = 12;
    static final int SOURCE_GAME_LOADING = 13;
    static final int SOURCE_GAME = 14;
    static final int SOURCE_TOP_APP_HANDOFF = 15;
    static final int SEVERITY_LIGHT = 1;
    static final int SEVERITY_HEAVY = 2;
    static final int ROLE_APP = 0;
    static final int ROLE_SYSTEM_SERVER = 1;
    static final int ROLE_SYSTEM_UI = 2;
    static final int ROLE_LAUNCHER = 3;
    static final int ROLE_TOP_APP = 4;

    final int pid;
    final int uid;
    final int mode;
    final int source;
    final int severity;
    final long durationMs;
    final int tidCount;
    final int tid1;
    final int tid2;
    final int tid3;
    final int tid4;
    final int role;

    private AxUiSession(int pid, int uid, int mode, int source, int severity, long durationMs,
            int tidCount, int tid1, int tid2, int tid3, int tid4, int role) {
        this.pid = pid;
        this.uid = uid;
        this.mode = mode;
        this.source = source;
        this.severity = severity;
        this.durationMs = durationMs;
        this.tidCount = tidCount;
        this.tid1 = tid1;
        this.tid2 = tid2;
        this.tid3 = tid3;
        this.tid4 = tid4;
        this.role = role;
    }

    static AxUiSession createWithRole(int pid, int uid, int mode, int source, int severity,
            long durationMs, int role) {
        return new AxUiSession(pid, uid, mode, source, severity, durationMs, 0,
                -1, -1, -1, -1, role);
    }

    static AxUiSession createForTidWithRole(int pid, int uid, int mode, int source, int severity,
            long durationMs, int tid, int role) {
        if (tid <= 0) {
            return createWithRole(pid, uid, mode, source, severity, durationMs, role);
        }
        return new AxUiSession(pid, uid, mode, source, severity, durationMs, 1,
                tid, -1, -1, -1, role);
    }

    static AxUiSession createForTidsWithRole(int pid, int uid, int mode, int source, int severity,
            long durationMs, int tid1, int tid2, int tid3, int tid4, int role) {
        int count = 0;
        if (tid1 > 0) {
            count++;
        }
        if (tid2 > 0) {
            count++;
        }
        if (tid3 > 0) {
            count++;
        }
        if (tid4 > 0) {
            count++;
        }
        return new AxUiSession(pid, uid, mode, source, severity, durationMs, count,
                tid1, tid2, tid3, tid4, role);
    }
}
