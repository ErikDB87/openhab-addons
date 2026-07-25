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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TractiveTaskTracker}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTaskTrackerTest {

    private final TractiveTaskTracker tracker = new TractiveTaskTracker();

    @Test
    void trackReturnsSameFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        assertSame(future, tracker.track(future));
    }

    @Test
    void trackStoresFuture() {
        tracker.track(mock(ScheduledFuture.class));
        assertEquals(1, tracker.trackedCount());
    }

    @Test
    void trackPrunesCompletedFuturesOnNextCall() {
        ScheduledFuture<?> done = mock(ScheduledFuture.class);
        when(done.isDone()).thenReturn(true);
        tracker.track(done);
        tracker.track(mock(ScheduledFuture.class));
        assertEquals(1, tracker.trackedCount());
    }

    @Test
    void cancelAllCancelsEachTrackedFuture() {
        ScheduledFuture<?> f1 = mock(ScheduledFuture.class);
        ScheduledFuture<?> f2 = mock(ScheduledFuture.class);
        tracker.track(f1);
        tracker.track(f2);
        tracker.cancelAll();
        verify(f1).cancel(true);
        verify(f2).cancel(true);
    }

    @Test
    void cancelAllEmptiesTrackedList() {
        tracker.track(mock(ScheduledFuture.class));
        tracker.cancelAll();
        assertEquals(0, tracker.trackedCount());
    }
}
