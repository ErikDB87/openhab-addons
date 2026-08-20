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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;

/**
 * Utility for sending HTTP requests, retrying after a fixed delay when the server returns HTTP 429.
 * The Tractive API sends no Retry-After header, so the measured delay is used instead.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveRetryUtil {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 6_000;

    private TractiveRetryUtil() {
    }

    /**
     * Sends a request, retrying after a fixed delay when the server returns HTTP 429.
     * A fresh {@link Request} is created on each attempt because Jetty requests cannot be resent.
     * The retry delay is scheduled via the provided executor so no thread is held during the wait.
     * Callers must use {@link java.util.concurrent.CompletableFuture#get() get()} (not {@code join()})
     * so that thread interruption from {@code dispose()} propagates correctly.
     *
     * @param requestFactory supplies a fresh request for each attempt
     * @param scheduler used to schedule retry delays without holding a thread
     * @param logger used for debug output
     * @param sharedBucket the local {@link SharedRateLimitBucket} tracking this call's host, or {@code null} if the
     *            host has none. On HTTP 429, {@link SharedRateLimitBucket#deplete(long)} is called on it --
     *            immediately if giving up, or protected until the next scheduled retry if one is coming, so that
     *            retry isn't outraced by an unrelated caller for whatever refills in the meantime. Either way, the
     *            server's answer is more authoritative than this bucket's own local estimate: the real
     *            account-level limit may also be drawn on by other consumers this local model has no visibility
     *            into (e.g. the Tractive app).
     * @return a future that completes with the first non-429 response, or exceptionally after all retries
     */
    public static CompletableFuture<ContentResponse> sendWithRetry(Supplier<Request> requestFactory,
            ScheduledExecutorService scheduler, Logger logger, @Nullable SharedRateLimitBucket sharedBucket) {
        return attempt(requestFactory, scheduler, logger, 1, sharedBucket);
    }

    private static CompletableFuture<ContentResponse> attempt(Supplier<Request> requestFactory,
            ScheduledExecutorService scheduler, Logger logger, int attemptNum,
            @Nullable SharedRateLimitBucket sharedBucket) {
        try {
            if (attemptNum > 1 && sharedBucket != null) {
                sharedBucket.tryConsume();
            }
            ContentResponse response = requestFactory.get().send();
            int status = response.getStatus();
            boolean isRateLimited = status == HttpStatus.TOO_MANY_REQUESTS_429;
            logger.trace("Attempt {}/{} → HTTP {}{}", attemptNum, MAX_RETRIES, status,
                    isRateLimited ? "" : " (not a 429, no further attempts will be made)");
            if (!isRateLimited) {
                return CompletableFuture.completedFuture(response);
            }
            if (sharedBucket != null) {
                logger.warn(
                        "Got HTTP 429 even though the shared rate-limit bucket believed a token was available. Either another consumer (e.g. the Tractive app) is drawing from the same account budget, or Tractive's real limits have changed since this binding's constants were last measured");
            }
            if (attemptNum >= MAX_RETRIES) {
                if (sharedBucket != null) {
                    sharedBucket.deplete(0);
                }
                logger.debug("Still rate limited after {} attempts, giving up", MAX_RETRIES);
                return CompletableFuture.failedFuture(
                        new IOException("HTTP 429 rate limit exceeded after " + MAX_RETRIES + " attempts"));
            }
            if (sharedBucket != null) {
                sharedBucket.deplete(RETRY_DELAY_MS);
            }
            logger.debug("Rate limited (attempt {}), retrying after {} ms", attemptNum, RETRY_DELAY_MS);
            CompletableFuture<ContentResponse> result = new CompletableFuture<>();
            scheduler.schedule((Runnable) () -> attempt(requestFactory, scheduler, logger, attemptNum + 1, sharedBucket)
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            result.completeExceptionally(ex);
                        } else {
                            result.complete(r);
                        }
                    }), RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
