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
        PollGuard guard = new PollGuard(1000);
        assertTrue(guard.tryAcquire());
    }

    @Test
    void tryAcquireFailsWhileAlreadyInProgress() {
        PollGuard guard = new PollGuard(1000);
        assertTrue(guard.tryAcquire());
        assertFalse(guard.tryAcquire());
    }

    @Test
    void tryAcquireFailsWithinCooldownAfterRelease() {
        PollGuard guard = new PollGuard(1000);
        assertTrue(guard.tryAcquire());
        guard.release();
        assertFalse(guard.tryAcquire());
    }

    @Test
    void tryAcquireSucceedsAgainAfterCooldownElapses() throws InterruptedException {
        PollGuard guard = new PollGuard(20);
        assertTrue(guard.tryAcquire());
        guard.release();
        Thread.sleep(30);
        assertTrue(guard.tryAcquire());
    }

    @Test
    void tryAcquireSucceedsImmediatelyAfterReleaseWhenCooldownIsZero() {
        PollGuard guard = new PollGuard(0);
        assertTrue(guard.tryAcquire());
        guard.release();
        assertTrue(guard.tryAcquire());
    }
}
