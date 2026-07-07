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

/** @hide */
public abstract class AxFrameRescueInternal {
    public abstract void clear(int pid);

    public abstract void onFrameRescue(int pid, int uid, int source, int level,
            long actualDurationNs, long targetDurationNs, int durationMs);

    public abstract void onAdpfWorkDuration(int pid, int uid, int tag,
            long actualDurationNs, long targetDurationNs);

    public abstract void onAdpfHint(int pid, int uid, int tag, int hint,
            long targetDurationNs);
}
