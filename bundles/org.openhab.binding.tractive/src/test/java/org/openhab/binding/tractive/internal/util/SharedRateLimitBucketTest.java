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
 * Unit tests for {@link SharedRateLimitBucket}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
class SharedRateLimitBucketTest {

    @Test
    void tryConsumeSucceedsUpToCapacityThenFails() {
        SharedRateLimitBucket bucket = new SharedRateLimitBucket(3, 0.0);
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }

    @Test
    void tryConsumeSucceedsAgainAfterRefillElapses() throws InterruptedException {
        SharedRateLimitBucket bucket = new SharedRateLimitBucket(1, 100.0);
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
        Thread.sleep(30);
        assertTrue(bucket.tryConsume());
    }

    @Test
    void refillNeverExceedsCapacity() throws InterruptedException {
        SharedRateLimitBucket bucket = new SharedRateLimitBucket(2, 1000.0);
        Thread.sleep(50);
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }
}
