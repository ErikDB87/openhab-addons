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
package org.openhab.binding.tractive.internal.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.tractive.internal.handler.TractiveAccountHandler;
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.core.config.discovery.AbstractDiscoveryService;

/**
 * Unit tests for {@link TractiveDiscoveryService#fetchGet}, the shared HTTP helper behind every discovery-scan
 * call ({@code fetchModelNumber}/{@code fetchTrackerList}/{@code fetchDeviceToTrackableMap}). Exercised via
 * reflection since it's {@code private} and identical across all three callers for what these tests cover --
 * going through the full {@code runScan()} pipeline (thing type resolution, {@code DiscoveryResultBuilder}, etc.)
 * would only add unrelated setup for the same assertions.
 *
 * The scheduler mock runs {@link org.openhab.binding.tractive.internal.util.TractiveRetryUtil} retry tasks
 * synchronously so tests complete without a real 6 s wait per attempt -- same pattern as
 * {@code TractiveTrackerHandlerSharedBudgetTest}/{@code TractiveDog6HandlerJsonTest}, reflectively injected into
 * {@link AbstractDiscoveryService#scheduler} here instead of {@code BaseThingHandler.scheduler}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveDiscoveryServiceTest {

    @Mock
    private @NonNullByDefault({}) TractiveAccountHandler bridge;
    @Mock
    private @NonNullByDefault({}) HttpClient httpClient;
    @Mock
    private @NonNullByDefault({}) Request request;
    @Mock
    private @NonNullByDefault({}) ContentResponse response;
    private @NonNullByDefault({}) TractiveDiscoveryService service;
    private @NonNullByDefault({}) Method fetchGetMethod;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);
        lenient().when(response.getStatus()).thenReturn(HttpStatus.OK_200);

        service = new TractiveDiscoveryService();

        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        lenient().when(mockScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return mock(ScheduledFuture.class);
        });
        Field schedulerField = AbstractDiscoveryService.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(service, mockScheduler);

        fetchGetMethod = TractiveDiscoveryService.class.getDeclaredMethod("fetchGet", TractiveAccountHandler.class,
                HttpClient.class, String.class, String.class);
        fetchGetMethod.setAccessible(true);
    }

    private @Nullable ContentResponse invokeFetchGet() throws Exception {
        return (ContentResponse) fetchGetMethod.invoke(service, bridge, httpClient,
                "https://graph.tractive.com/4/tracker/ABCD1234", "test");
    }

    /**
     * Before the discovery-accounting fix, a first attempt that succeeded outright never called
     * {@code tryConsume()} at all -- only retries did, inside {@link
     * org.openhab.binding.tractive.internal.util.TractiveRetryUtil}. This is the regression test for that gap: a
     * capacity-1 bucket should have nothing left after one successful {@code fetchGet()} call.
     */
    @Test
    void successfulFirstAttemptConsumesOneTokenFromTheSharedBucket() throws Exception {
        SharedRateLimitBucket realBucket = new SharedRateLimitBucket(1, 0.0);
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(realBucket);

        ContentResponse result = invokeFetchGet();

        assertNotNull(result);
        assertFalse(realBucket.tryConsume());
    }

    /**
     * When the bucket already believed itself empty, {@code fetchGet()} must forward {@code null} downstream, not
     * the real bucket -- otherwise a 429 that was already expected would still call {@link
     * SharedRateLimitBucket#deplete}, and {@code TractiveRetryUtil} would log a "believed available" WARN that
     * wasn't true. A mocked bucket that {@code verify(never()).deplete()} would otherwise definitely have seen is
     * the only way to observe this from outside the method's own local variable.
     */
    @Test
    void a429OnAnEmptyBucketNeverCallsDeplete() throws Exception {
        SharedRateLimitBucket mockBucket = mock(SharedRateLimitBucket.class);
        when(mockBucket.tryConsume()).thenReturn(false);
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(mockBucket);
        when(response.getStatus()).thenReturn(HttpStatus.TOO_MANY_REQUESTS_429);

        invokeFetchGet();

        verify(mockBucket, never()).deplete(anyLong());
    }

    /**
     * The other half of the ternary: when a token genuinely was available, a resulting 429 must still correct the
     * real bucket via {@code deplete()} -- the "never gate" design only means the request isn't blocked, not that
     * a real surprise goes uncorrected.
     */
    @Test
    void a429OnAnAvailableTokenDepletesTheSharedBucket() throws Exception {
        SharedRateLimitBucket realBucket = new SharedRateLimitBucket(5, 0.0);
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(realBucket);
        when(response.getStatus()).thenReturn(HttpStatus.TOO_MANY_REQUESTS_429);

        invokeFetchGet();

        assertFalse(realBucket.tryConsume());
    }
}
