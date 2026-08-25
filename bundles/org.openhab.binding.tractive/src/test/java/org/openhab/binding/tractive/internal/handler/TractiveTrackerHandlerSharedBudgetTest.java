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
package org.openhab.binding.tractive.internal.handler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
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
import org.openhab.binding.tractive.internal.TractiveBindingConstants;
import org.openhab.binding.tractive.internal.util.PollGuard;
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.binding.tractive.internal.util.TractiveRetryUtil;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Unit tests for {@link TractiveTrackerHandler#tryConsumeSharedBudget}, exercised via
 * {@link TractiveTrackerHandler#pollTrackerDetails}, one representative caller among the four
 * {@code graph.tractive.com}-polling methods that share it.
 *
 * Uses a real {@link SharedRateLimitBucket} rather than a mock -- it has no dependencies of its own and is
 * already covered by its own unit tests, so a capacity-1, zero-refill instance gives fully deterministic
 * "one call succeeds, the next is denied" behavior with no timing dependency.
 *
 * {@code trackerDetailsGuard}'s own {@code minIntervalMs} (10 s by default, since these tests deliberately
 * never call {@code initialize()} -- same as {@link TractiveTrackerHandlerFetchPositionsTest}) would otherwise
 * intercept a second call as {@code COOLDOWN} before it ever reaches the shared-budget check, so it's
 * reflectively zeroed in {@link #setUp()} -- the same private-field-injection pattern already used by
 * {@code TractiveDog6HandlerJsonTest} for its mocked scheduler.
 *
 * Since {@code initialize()} is never called here either, {@code recurringPollEnabled} also defaults to
 * {@code false} -- which, since 2026-08-18, makes {@code tryConsumeSharedBudget} bypass the shared-budget gate
 * entirely (the "no recurring cycle to retry a skipped call on" treatment {@code sendCommand}/{@code
 * fetchPositions} already used). These tests are specifically about that gate, so {@code recurringPollEnabled}
 * is reflectively forced to {@code true} in {@link #setUp()} the same way, to restore the behavior under test.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTrackerHandlerSharedBudgetTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");
    private static final String SAMSON_TRACKER_ID = "HBDYUFSC";
    private static final ChannelUID MODEL_NUMBER_CHANNEL_UID = new ChannelUID(THING_UID,
            TractiveBindingConstants.CHANNEL_MODEL_NUMBER);

    @Mock
    private @NonNullByDefault({}) Thing thing;
    @Mock
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    @Mock
    private @NonNullByDefault({}) TractiveAccountHandler bridge;
    @Mock
    private @NonNullByDefault({}) HttpClient httpClient;
    @Mock
    private @NonNullByDefault({}) Request request;
    @Mock
    private @NonNullByDefault({}) ContentResponse response;
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(callback.isChannelLinked(any())).thenReturn(true);
        lenient().when(bridge.getHttpClient()).thenReturn(httpClient);
        lenient().when(bridge.getGraphApiRateLimitBucket()).thenReturn(new SharedRateLimitBucket(1, 0.0));
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);
        lenient().when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        lenient().when(response.getContentAsString()).thenReturn("{\"model_number\":\"TG6C\"}");

        handler = new TractiveDog6Handler(thing) {
            {
                trackerId = SAMSON_TRACKER_ID;
            }
        };
        handler.setCallback(callback);

        Field guardField = TractiveTrackerHandler.class.getDeclaredField("trackerDetailsGuard");
        guardField.setAccessible(true);
        PollGuard<?> guard = (PollGuard<?>) Objects.requireNonNull(guardField.get(handler));
        guard.setMinIntervalMs(0);

        Field recurringPollEnabledField = TractiveTrackerHandler.class.getDeclaredField("recurringPollEnabled");
        recurringPollEnabledField.setAccessible(true);
        recurringPollEnabledField.set(handler, true);
    }

    @Test
    void secondPollWithinSameCycleIsSkippedWhenSharedBudgetIsEmpty() {
        handler.pollTrackerDetails(bridge);
        handler.pollTrackerDetails(bridge);

        verify(httpClient, times(1)).newRequest(anyString());
    }

    @Test
    void budgetDeniedPollReappliesTheCachedResponse() {
        handler.pollTrackerDetails(bridge);
        handler.pollTrackerDetails(bridge);

        verify(callback, times(2)).stateUpdated(eq(MODEL_NUMBER_CHANNEL_UID), eq(new StringType("TG6C")));
    }

    @Test
    void budgetDeniedPollWithNoCachedResponseMakesNoCallAndUpdatesNothing() {
        SharedRateLimitBucket emptyBucket = new SharedRateLimitBucket(1, 0.0);
        assertTrue(emptyBucket.tryConsume());
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(emptyBucket);

        handler.pollTrackerDetails(bridge);

        verifyNoInteractions(httpClient);
        verify(callback, never()).stateUpdated(eq(MODEL_NUMBER_CHANNEL_UID), any());
    }

    /**
     * Configures {@code handler} for the "recurringPollEnabled == false, bucket believed empty, HTTP 429" scenario
     * shared by the four {@code budgetDenied...WithRecurringDisabled...} tests below: forces
     * {@code recurringPollEnabled} to {@code false} (the real-world default), reflectively injects a scheduler that
     * runs {@link TractiveRetryUtil}'s retry tasks synchronously (avoiding a real 6 s wait per attempt), and stubs
     * the HTTP response to 429.
     *
     * @return a mocked {@link SharedRateLimitBucket} whose {@code tryConsume()} always reports empty
     */
    private SharedRateLimitBucket setUpRecurringDisabledWithEmptyBucketAnd429Response() throws Exception {
        Field recurringPollEnabledField = TractiveTrackerHandler.class.getDeclaredField("recurringPollEnabled");
        recurringPollEnabledField.setAccessible(true);
        recurringPollEnabledField.set(handler, false);

        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        lenient().when(mockScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return mock(ScheduledFuture.class);
        });
        Field schedulerField = BaseThingHandler.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(handler, mockScheduler);

        SharedRateLimitBucket mockBucket = mock(SharedRateLimitBucket.class);
        when(mockBucket.tryConsume()).thenReturn(false);
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(mockBucket);
        when(response.getStatus()).thenReturn(HttpStatus.TOO_MANY_REQUESTS_429);
        return mockBucket;
    }

    /**
     * See {@link #setUpRecurringDisabledWithEmptyBucketAnd429Response} -- this is the {@code pollTrackerDetails}
     * instance, one of four call sites sharing {@code tryConsumeSharedBudget}'s bypass-gate behavior.
     */
    @Test
    void budgetDeniedWithRecurringDisabledProceedsAnywayButNeverDepletesTheBucket() throws Exception {
        SharedRateLimitBucket mockBucket = setUpRecurringDisabledWithEmptyBucketAnd429Response();

        handler.pollTrackerDetails(bridge);

        verify(httpClient, atLeastOnce()).newRequest(anyString());
        verify(mockBucket, never()).deplete(anyLong());
    }

    /**
     * See {@link #setUpRecurringDisabledWithEmptyBucketAnd429Response} -- this is the {@code pollHwReport} instance.
     */
    @Test
    void hwReportBudgetDeniedWithRecurringDisabledProceedsAnywayButNeverDepletesTheBucket() throws Exception {
        SharedRateLimitBucket mockBucket = setUpRecurringDisabledWithEmptyBucketAnd429Response();

        handler.pollHwReport(bridge);

        verify(httpClient, atLeastOnce()).newRequest(anyString());
        verify(mockBucket, never()).deplete(anyLong());
    }

    /**
     * See {@link #setUpRecurringDisabledWithEmptyBucketAnd429Response} -- this is the {@code pollPositionReport}
     * instance. Unlike the other three call sites, {@code pollPositionReport} gates on the cached
     * {@code positionGroupLinked} field rather than a live {@code isLinked()} check, so it needs reflectively
     * forcing to {@code true} first -- it defaults to {@code false} since these tests never call {@code initialize()}.
     */
    @Test
    void positionReportBudgetDeniedWithRecurringDisabledProceedsAnywayButNeverDepletesTheBucket() throws Exception {
        Field positionGroupLinkedField = TractiveTrackerHandler.class.getDeclaredField("positionGroupLinked");
        positionGroupLinkedField.setAccessible(true);
        positionGroupLinkedField.set(handler, true);

        SharedRateLimitBucket mockBucket = setUpRecurringDisabledWithEmptyBucketAnd429Response();

        handler.pollPositionReport(bridge);

        verify(httpClient, atLeastOnce()).newRequest(anyString());
        verify(mockBucket, never()).deplete(anyLong());
    }

    /** See {@link #setUpRecurringDisabledWithEmptyBucketAnd429Response} -- this is the {@code pollProfile} instance. */
    @Test
    void profileBudgetDeniedWithRecurringDisabledProceedsAnywayButNeverDepletesTheBucket() throws Exception {
        SharedRateLimitBucket mockBucket = setUpRecurringDisabledWithEmptyBucketAnd429Response();

        handler.pollProfile(bridge);

        verify(httpClient, atLeastOnce()).newRequest(anyString());
        verify(mockBucket, never()).deplete(anyLong());
    }
}
