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

final class FrameRescueSignal {
    final int pid;
    final int uid;
    final int source;
    final int level;
    final long actualDurationNs;
    final long targetDurationNs;
    final int durationMs;

    FrameRescueSignal(int pid, int uid, int source, int level, long actualDurationNs,
            long targetDurationNs, int durationMs) {
        this.pid = pid;
        this.uid = uid;
        this.source = source;
        this.level = level;
        this.actualDurationNs = actualDurationNs;
        this.targetDurationNs = targetDurationNs;
        this.durationMs = durationMs;
    }
}
