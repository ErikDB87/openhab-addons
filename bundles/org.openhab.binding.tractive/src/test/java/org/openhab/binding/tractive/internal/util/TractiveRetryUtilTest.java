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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

/**
 * Unit tests for {@link TractiveRetryUtil}.
 *
 * The scheduler mock runs retry tasks synchronously so tests complete without real delays.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveRetryUtilTest {

    @Mock
    private @NonNullByDefault({}) ScheduledExecutorService scheduler;
    @Mock
    private @NonNullByDefault({}) Logger logger;

    @BeforeEach
    void setUp() {
        lenient().when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return mock(ScheduledFuture.class);
        });
    }

    @Test
    void firstAttemptSucceedsFutureCompletesWithResponse() throws Exception {
        ContentResponse response = mock(ContentResponse.class);
        when(response.getStatus()).thenReturn(200);
        Request request = mock(Request.class);
        when(request.send()).thenReturn(response);

        CompletableFuture<ContentResponse> future = TractiveRetryUtil.sendWithRetry(() -> request, scheduler, logger);

        assertSame(response, future.get());
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void firstAttemptReturns429SecondAttemptSucceedsFutureCompletesWithSecondResponse() throws Exception {
        ContentResponse r429 = mock(ContentResponse.class);
        when(r429.getStatus()).thenReturn(429);
        ContentResponse r200 = mock(ContentResponse.class);
        when(r200.getStatus()).thenReturn(200);

        Request req1 = mock(Request.class);
        when(req1.send()).thenReturn(r429);
        Request req2 = mock(Request.class);
        when(req2.send()).thenReturn(r200);
        Queue<Request> requests = new LinkedList<>(List.of(req1, req2));

        CompletableFuture<ContentResponse> future = TractiveRetryUtil
                .sendWithRetry(() -> Objects.requireNonNull(requests.poll()), scheduler, logger);

        assertSame(r200, future.get());
    }

    @Test
    void threeConsecutive429sFutureCompletesExceptionallyWithIOException() throws Exception {
        ContentResponse r429 = mock(ContentResponse.class);
        when(r429.getStatus()).thenReturn(429);
        Request request = mock(Request.class);
        when(request.send()).thenReturn(r429);

        CompletableFuture<ContentResponse> future = TractiveRetryUtil.sendWithRetry(() -> request, scheduler, logger);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }
}
