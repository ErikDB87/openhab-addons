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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Tracks {@link ScheduledFuture} instances so they can all be cancelled together on disposal.
 * Completed futures are pruned opportunistically on each {@link #track} call.
 * Thread-safe; safe to call from any thread.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveTaskTracker {

    private final List<ScheduledFuture<?>> tracked = new CopyOnWriteArrayList<>();

    /**
     * Adds a future to the tracked set, pruning any already-completed entries first.
     * Returns the future unchanged for fluent use at the call site.
     */
    public <T extends ScheduledFuture<?>> T track(T future) {
        tracked.removeIf(ScheduledFuture::isDone);
        tracked.add(future);
        return future;
    }

    /**
     * Cancels all tracked futures with interrupt and clears the list.
     */
    public void cancelAll() {
        for (ScheduledFuture<?> job : tracked) {
            job.cancel(true);
        }
        tracked.clear();
    }

    /** Returns the number of currently tracked futures. For testing only. */
    int trackedCount() {
        return tracked.size();
    }
}
