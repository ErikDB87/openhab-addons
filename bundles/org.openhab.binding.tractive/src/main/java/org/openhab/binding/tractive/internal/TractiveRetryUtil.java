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
package org.openhab.binding.tractive.internal;

import java.io.IOException;
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
     * Sends a request, retrying with exponential backoff when the server returns 429.
     * A fresh {@link Request} is created on each attempt because Jetty requests cannot be resent.
     *
     * @param requestFactory supplies a fresh request for each attempt
     * @param logger used for debug output
     * @return the first non-429 response
     * @throws IOException if all retries are exhausted with 429 responses
     */
    public static ContentResponse sendWithRetry(Supplier<Request> requestFactory, Logger logger) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            ContentResponse response = requestFactory.get().send();
            if (response.getStatus() != 429) {
                return response;
            }
            if (attempt < MAX_RETRIES) {
                long delayMs = BASE_DELAY_MS * (1L << (attempt - 1));
                logger.debug("Rate limited (attempt {}), retrying after {}ms", attempt, delayMs);
                Thread.sleep(delayMs);
            }
        }
        throw new IOException("Tractive API rate limit exceeded after " + MAX_RETRIES + " attempts");
    }
}
