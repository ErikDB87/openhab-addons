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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Models Tractive's account-level rate-limit bucket for {@code graph.tractive.com}, to be shared across every endpoint
 * on that host, not independent per endpoint. One instance lives on {@code TractiveAccountHandler} and is shared by
 * every tracker handler under that bridge, since the constraint is probably per-account, not per-tracker. Deliberately
 * does not cover {@code aps-api.tractive.com}, which has its own separate, unaffected budget.
 *
 * A simple token bucket: {@code capacity} tokens available at once, refilling continuously at {@code refillPerSecond}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class SharedRateLimitBucket {

    private final double capacity;
    private final double refillPerMillis;
    private final Object lock = new Object();

    private double availableTokens;
    private long lastRefillMillis;

    /**
     * Creates a bucket starting at full capacity.
     *
     * Both parameters should be the result of measuring the target service's behavior (burst tolerance, recovery time).
     *
     * @param capacity number of tokens available at once, with none of it used
     * @param refillPerSecond tokens regained per second while below capacity
     */
    public SharedRateLimitBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerMillis = refillPerSecond / 1000.0;
        this.availableTokens = capacity;
        this.lastRefillMillis = System.currentTimeMillis();
    }

    /**
     * Attempts to consume one token. Concurrent callers are serialized through a single lock, which is fine at the call
     * rate this bucket sees (at most a handful of requests per poll cycle).
     *
     * @return {@code true} if a token was available and has now been consumed, {@code false} if the bucket is empty.
     */
    public boolean tryConsume() {
        synchronized (lock) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsedMillis = now - lastRefillMillis;
        if (elapsedMillis > 0) {
            availableTokens = Math.min(capacity, availableTokens + elapsedMillis * refillPerMillis);
            lastRefillMillis = now;
        }
    }
}
