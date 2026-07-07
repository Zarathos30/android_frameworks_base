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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** @hide */
public final class AxBurstEngine {
    public static final int BINDER_UX_DISABLED = 0;
    public static final int BINDER_UX_ENQUEUE = 1;
    private static final long ANIMATION_DEFAULT_DURATION_MS = 1600L;
    private static final long ANIMATION_RESCHEDULE_SLOP_MS = 96L;

    private static final AtomicInteger sActiveBinderUxScopes = new AtomicInteger();
    private static final AtomicInteger sActiveAnimations = new AtomicInteger();
    private static final AtomicLong sAnimationRequestExpiryUptimeMs = new AtomicLong();
    private static final ThreadLocal<int[]> sBinderUxFlag =
            ThreadLocal.withInitial(() -> new int[1]);

    public interface RemoteExceptionRunnable {
        void run() throws RemoteException;
    }

    public interface RemoteExceptionIntSupplier {
        int getAsInt() throws RemoteException;
    }

    private static int getBinderUxFlag() {
        return sBinderUxFlag.get()[0];
    }

    public static int setBinderThreadUxFlag(int flag) {
        if (flag != BINDER_UX_DISABLED && flag != BINDER_UX_ENQUEUE) {
            return getBinderUxFlag();
        }
        final int[] state = sBinderUxFlag.get();
        final int oldFlag = state[0];
        if (flag == BINDER_UX_DISABLED) {
            if (oldFlag != BINDER_UX_DISABLED) {
                state[0] = BINDER_UX_DISABLED;
                sActiveBinderUxScopes.decrementAndGet();
            }
            return oldFlag;
        }
        if (oldFlag == BINDER_UX_DISABLED) {
            sActiveBinderUxScopes.incrementAndGet();
        }
        state[0] = flag;
        return oldFlag;
    }

    public static void withBinderUxFlag(int flag, Runnable call) {
        int previousFlag = setBinderThreadUxFlag(flag);
        try {
            call.run();
        } finally {
            setBinderThreadUxFlag(previousFlag);
        }
    }

    public static void withBinderUxFlagForRemote(int flag, RemoteExceptionRunnable call)
            throws RemoteException {
        int previousFlag = setBinderThreadUxFlag(flag);
        try {
            call.run();
        } finally {
            setBinderThreadUxFlag(previousFlag);
        }
    }

    public static int withBinderUxFlagForRemote(int flag, RemoteExceptionIntSupplier call)
            throws RemoteException {
        int previousFlag = setBinderThreadUxFlag(flag);
        try {
            return call.getAsInt();
        } finally {
            setBinderThreadUxFlag(previousFlag);
        }
    }

    public static boolean isBinderUxEnabled() {
        return sActiveBinderUxScopes.get() != 0 && getBinderUxFlag() != BINDER_UX_DISABLED;
    }

    public static void onAnimationStart() {
        onAnimationStart(ANIMATION_DEFAULT_DURATION_MS);
    }

    public static void onAnimationStart(long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        sActiveAnimations.incrementAndGet();
        requestAnimationStart(durationMs);
    }

    public static void prepareForAnim() {
        prepareForAnim(ANIMATION_DEFAULT_DURATION_MS);
    }

    public static void prepareForAnim(long durationMs) {
        if (durationMs <= 0 || !updateAnimationRequest(durationMs)) {
            return;
        }
        try {
            final IActivityManager service = ActivityManager.getService();
            if (service != null) {
                service.onUiAnimationPrepared(durationMs);
            }
        } catch (RemoteException ignored) {
        }
    }

    private static void requestAnimationStart(long durationMs) {
        if (!updateAnimationRequest(durationMs)) {
            return;
        }
        try {
            final IActivityManager service = ActivityManager.getService();
            if (service != null) {
                service.onUiAnimationStarted(durationMs);
            }
        } catch (RemoteException ignored) {
        }
    }

    private static boolean updateAnimationRequest(long durationMs) {
        if (durationMs <= 0) {
            return false;
        }
        final long expiryUptimeMs = SystemClock.uptimeMillis()
                + Math.min(durationMs, ANIMATION_DEFAULT_DURATION_MS);
        long currentExpiryUptimeMs;
        do {
            currentExpiryUptimeMs = sAnimationRequestExpiryUptimeMs.get();
            if (expiryUptimeMs <= currentExpiryUptimeMs + ANIMATION_RESCHEDULE_SLOP_MS) {
                return false;
            }
        } while (!sAnimationRequestExpiryUptimeMs.compareAndSet(
                currentExpiryUptimeMs, expiryUptimeMs));
        return true;
    }

    public static void onAnimationEnd() {
        if (decrementActiveAnimations() != 0) {
            return;
        }
        sAnimationRequestExpiryUptimeMs.set(0);
        try {
            final IActivityManager service = ActivityManager.getService();
            if (service != null) {
                service.onUiAnimationFinished();
            }
        } catch (RemoteException ignored) {
        }
    }

    private static int decrementActiveAnimations() {
        int current;
        do {
            current = sActiveAnimations.get();
            if (current == 0) {
                return -1;
            }
        } while (!sActiveAnimations.compareAndSet(current, current - 1));
        return current - 1;
    }

    private AxBurstEngine() {}
}
