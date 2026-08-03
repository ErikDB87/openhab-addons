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

/**
 * Debounces repeated poll triggers: at most one poll may be in progress at a time, and a
 * newly-completed poll suppresses further triggers for a configurable minimum interval. Any
 * other pending trigger within that window is redundant, since the poll it would have requested
 * has already just run.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class PollGuard {

    private final long minIntervalMs;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private volatile long lastCompletedMs = 0;

    /**
     * Creates a poll guard that suppresses triggers within {@code minIntervalMs} of the last
     * completed poll, in addition to preventing concurrent overlap.
     */
    public PollGuard(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Attempts to acquire the guard for a new poll. Returns {@code false} if a poll is already
     * in progress, or if the last one completed less than {@code minIntervalMs} ago — callers
     * should skip the poll in that case. On {@code true}, the caller must call {@link #release()}
     * when the poll completes, ideally in a {@code finally} block.
     */
    public boolean tryAcquire() {
        if (System.currentTimeMillis() - lastCompletedMs < minIntervalMs) {
            return false;
        }
        return inProgress.compareAndSet(false, true);
    }

    /**
     * Releases the guard and records the completion time, so a subsequent {@link #tryAcquire()}
     * within {@code minIntervalMs} will be suppressed.
     */
    public void release() {
        lastCompletedMs = System.currentTimeMillis();
        inProgress.set(false);
    }
}
