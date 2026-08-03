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
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.slf4j.Logger;

/**
 * Utility for sending HTTP requests with exponential backoff on HTTP 429 responses.
 * The Tractive API sends no Retry-After header, so we compute the delay ourselves.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveRetryUtil {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 2_000;

    private TractiveRetryUtil() {
    }

    /**
     * Sends a request, retrying with exponential backoff when the server returns HTTP 429.
     * A fresh {@link Request} is created on each attempt because Jetty requests cannot be resent.
     * The retry delay is scheduled via the provided executor so no thread is held during the wait.
     * Callers must use {@link java.util.concurrent.CompletableFuture#get() get()} (not {@code join()})
     * so that thread interruption from {@code dispose()} propagates correctly.
     *
     * @param requestFactory supplies a fresh request for each attempt
     * @param scheduler used to schedule retry delays without holding a thread
     * @param logger used for debug output
     * @return a future that completes with the first non-429 response, or exceptionally after all retries
     */
    public static CompletableFuture<ContentResponse> sendWithRetry(Supplier<Request> requestFactory,
            ScheduledExecutorService scheduler, Logger logger) {
        return attempt(requestFactory, scheduler, logger, 1);
    }

    private static CompletableFuture<ContentResponse> attempt(Supplier<Request> requestFactory,
            ScheduledExecutorService scheduler, Logger logger, int attemptNum) {
        try {
            ContentResponse response = requestFactory.get().send();
            logger.trace("Attempt {}/{} → HTTP {}", attemptNum, MAX_RETRIES, response.getStatus());
            if (response.getStatus() != 429 || attemptNum >= MAX_RETRIES) {
                if (response.getStatus() == 429) {
                    return CompletableFuture.failedFuture(
                            new IOException("HTTP 429 rate limit exceeded after " + MAX_RETRIES + " attempts"));
                }
                return CompletableFuture.completedFuture(response);
            }
            long delayMs = BASE_DELAY_MS * (1L << (attemptNum - 1));
            logger.debug("Rate limited (attempt {}), retrying after {}ms", attemptNum, delayMs);
            CompletableFuture<ContentResponse> result = new CompletableFuture<>();
            scheduler.schedule((Runnable) () -> attempt(requestFactory, scheduler, logger, attemptNum + 1)
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            result.completeExceptionally(ex);
                        } else {
                            result.complete(r);
                        }
                    }), delayMs, TimeUnit.MILLISECONDS);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
