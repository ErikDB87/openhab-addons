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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PollGuard}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
class PollGuardTest {

    @Test
    void tryAcquireSucceedsOnFirstCall() {
        PollGuard<String> guard = new PollGuard<>(1000);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
    }

    @Test
    void tryAcquireReportsInProgressWhileAlreadyInProgress() {
        PollGuard<String> guard = new PollGuard<>(1000);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
        assertEquals(PollGuard.AcquireResult.IN_PROGRESS, guard.tryAcquire());
    }

    @Test
    void tryAcquireReportsCooldownWithinCooldownAfterRelease() {
        PollGuard<String> guard = new PollGuard<>(1000);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
        guard.release();
        assertEquals(PollGuard.AcquireResult.COOLDOWN, guard.tryAcquire());
    }

    @Test
    void tryAcquireSucceedsAgainAfterCooldownElapses() throws InterruptedException {
        PollGuard<String> guard = new PollGuard<>(20);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
        guard.release();
        Thread.sleep(30);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
    }

    @Test
    void tryAcquireSucceedsImmediatelyAfterReleaseWhenCooldownIsZero() {
        PollGuard<String> guard = new PollGuard<>(0);
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
        guard.release();
        assertEquals(PollGuard.AcquireResult.ACQUIRED, guard.tryAcquire());
    }

    @Test
    void getCachedReturnsNullBeforeAnyValueIsSet() {
        PollGuard<String> guard = new PollGuard<>(1000);
        assertNull(guard.getCached());
    }

    @Test
    void getCachedReturnsLastSetValue() {
        PollGuard<String> guard = new PollGuard<>(0);
        guard.setCached("first");
        assertEquals("first", guard.getCached());
        guard.setCached("second");
        assertEquals("second", guard.getCached());
    }

    @Test
    void getCacheAgeMsReflectsTimeSinceLastRelease() throws InterruptedException {
        PollGuard<String> guard = new PollGuard<>(1000);
        guard.tryAcquire();
        guard.release();
        Thread.sleep(30);
        assertTrue(guard.getCacheAgeMs() >= 30);
    }
}
