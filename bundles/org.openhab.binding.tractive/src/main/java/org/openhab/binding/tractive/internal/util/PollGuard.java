/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.tractive.internal.util;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Debounces repeated poll triggers: at most one poll may be in progress at a time, and a newly-completed poll
 * suppresses further triggers for a configurable minimum interval. Any other pending trigger within that window is
 * redundant, since the poll it would have requested has already just run.
 *
 * Also caches the payload of the last successful poll ({@link #getCached()}/{@link #setCached(Object)}). A caller
 * skipped with {@link AcquireResult#COOLDOWN} can re-apply that cached payload instead of doing nothing. A caller
 * skipped with {@link AcquireResult#IN_PROGRESS} should not -- a fresh answer is already in flight and will arrive on
 * its own shortly.
 *
 * @param <T> the type of payload cached alongside the guard state
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class PollGuard<T> {

    /**
     * Outcome of a {@link #tryAcquire()} call.
     */
    public enum AcquireResult {
        /** The guard was acquired; the caller must call {@link #release()} when done. */
        ACQUIRED,
        /** A poll is already in progress; its own completion will supply fresh data shortly. */
        IN_PROGRESS,
        /** The last poll completed less than {@code minIntervalMs} ago. */
        COOLDOWN
    }

    private volatile long minIntervalMs;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private volatile long lastCompletedMs = 0;
    private volatile @Nullable T cached;

    /**
     * Creates a poll guard that suppresses triggers within {@code minIntervalMs} of the last completed poll, in
     * addition to preventing concurrent overlap. The interval can be changed later via {@link #setMinIntervalMs(long)}.
     */
    public PollGuard(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Changes the minimum interval enforced by {@link #tryAcquire()}. Takes effect on the next call -- it does not
     * retroactively re-evaluate a cooldown that's already in progress.
     */
    public void setMinIntervalMs(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Attempts to acquire the guard for a new poll. On {@link AcquireResult#ACQUIRED}, the caller must call
     * {@link #release()} when the poll completes, ideally in a {@code finally} block. On
     * {@link AcquireResult#IN_PROGRESS} or {@link AcquireResult#COOLDOWN}, the caller should skip the poll -- see
     * {@link AcquireResult} for how callers should treat the two differently.
     */
    public AcquireResult tryAcquire() {
        if (inProgress.get()) {
            return AcquireResult.IN_PROGRESS;
        }
        if (System.currentTimeMillis() - lastCompletedMs < minIntervalMs) {
            return AcquireResult.COOLDOWN;
        }
        return inProgress.compareAndSet(false, true) ? AcquireResult.ACQUIRED : AcquireResult.IN_PROGRESS;
    }

    /**
     * Releases the guard and records the completion time, so a subsequent {@link #tryAcquire()} within
     * {@code minIntervalMs} will be suppressed.
     */
    public void release() {
        lastCompletedMs = System.currentTimeMillis();
        inProgress.set(false);
    }

    /**
     * Returns the payload from the last successful poll, or {@code null} if none has completed yet.
     */
    public @Nullable T getCached() {
        return cached;
    }

    /**
     * Records the payload of a just-completed successful poll, for {@link #getCached()} to return the
     * next time {@link #tryAcquire()} skips a caller.
     */
    public void setCached(T value) {
        this.cached = value;
    }

    /**
     * Returns how long ago, in milliseconds, the cached payload (if any) was fetched -- i.e. the age of whatever
     * {@link #getCached()} currently returns. Meaningless if {@link #getCached()} is {@code null}.
     */
    public long getCacheAgeMs() {
        return System.currentTimeMillis() - lastCompletedMs;
    }
}
