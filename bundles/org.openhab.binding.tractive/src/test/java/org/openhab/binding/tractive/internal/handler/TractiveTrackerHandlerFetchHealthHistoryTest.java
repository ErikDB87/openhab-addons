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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeoutException;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.tractive.internal.TractiveBindingConstants;
import org.openhab.binding.tractive.internal.TractiveStateDescriptionProvider;
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link TractiveTrackerHandler#fetchHealthHistory}.
 *
 * Uses an anonymous {@link TractiveDog6Handler} subclass to override
 * {@code getAccountHandler()} so the HTTP path can be exercised without an
 * OSGi container or a real bridge. Mirrors {@link TractiveTrackerHandlerFetchPositionsTest}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTrackerHandlerFetchHealthHistoryTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");
    private static final String SAMSON_TRACKER_ID = "HBDYUFSC";
    private static final String SAMSON_PET_ID = "9c7a4e2d1f6b8035c2a9d0ef";

    private static final ZonedDateTime FROM = ZonedDateTime.of(2025, 7, 1, 6, 30, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime TO = ZonedDateTime.of(2025, 7, 8, 21, 15, 0, 0, ZoneOffset.UTC);
    private static final String ACTIVITY_BODY = "{\"overviews\":[{\"date\":\"2025-07-01\",\"progress\":134}]}";
    private static final String SLEEP_BODY = "{\"overviews\":[{\"date\":\"2025-07-01\",\"daySleep\":305,\"nightSleep\":37}]}";

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
    @Mock
    private @NonNullByDefault({}) Request sleepRequest;
    @Mock
    private @NonNullByDefault({}) ContentResponse sleepResponse;
    private final TractiveStateDescriptionProvider stateDescriptionProvider = new TractiveStateDescriptionProvider();
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(callback.isChannelLinked(any())).thenReturn(true);
        lenient().when(bridge.getHttpClient()).thenReturn(httpClient);
        lenient().when(bridge.getGraphApiRateLimitBucket()).thenReturn(new SharedRateLimitBucket(100, 0.0));
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);

        TractiveAccountHandler br = bridge;
        handler = new TractiveDog6Handler(thing, stateDescriptionProvider) {
            {
                trackerId = SAMSON_TRACKER_ID;
                trackedPetId = SAMSON_PET_ID;
            }

            @Override
            protected @Nullable TractiveAccountHandler getAccountHandler() {
                return br;
            }
        };
        handler.setCallback(callback);
    }

    /** Routes the sleep-overview call to its own request/response mocks so the two calls can differ. */
    private void splitSleepRequest() throws Exception {
        when(httpClient.newRequest(contains("sleep/overview"))).thenReturn(sleepRequest);
        when(sleepRequest.method(HttpMethod.GET)).thenReturn(sleepRequest);
        when(bridge.addAuthHeaders(sleepRequest)).thenReturn(sleepRequest);
        when(sleepRequest.send()).thenReturn(sleepResponse);
    }

    @Test
    void bothHttp200ReturnsCombinedJson() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(ACTIVITY_BODY);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(sleepResponse.getContentAsString()).thenReturn(SLEEP_BODY);

        String result = handler.fetchHealthHistory(FROM, TO);

        assertNotNull(result);
        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
        assertAll(() -> assertTrue(obj.get("activity").isJsonObject()),
                () -> assertTrue(obj.get("sleep").isJsonObject()),
                () -> assertEquals(134,
                        obj.getAsJsonObject("activity").getAsJsonArray("overviews").get(0).getAsJsonObject()
                                .get("progress").getAsInt()),
                () -> assertEquals(305, obj.getAsJsonObject("sleep").getAsJsonArray("overviews").get(0)
                        .getAsJsonObject().get("daySleep").getAsInt()));
    }

    @Test
    void oneHttp200OneErrorReturnsPartialWithJsonNull() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(ACTIVITY_BODY);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        String result = handler.fetchHealthHistory(FROM, TO);

        assertNotNull(result);
        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
        assertAll(() -> assertTrue(obj.get("activity").isJsonObject()),
                () -> assertTrue(obj.get("sleep").isJsonNull()));
    }

    @Test
    void bothHttpErrorReturnsNull() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        assertNull(handler.fetchHealthHistory(FROM, TO));
    }

    @Test
    void noBridgeReturnsNullWithoutHttpCall() throws Exception {
        TractiveDog6Handler noBridgeHandler = new TractiveDog6Handler(thing, stateDescriptionProvider) {
            {
                trackerId = SAMSON_TRACKER_ID;
                trackedPetId = SAMSON_PET_ID;
            }

            @Override
            protected @Nullable TractiveAccountHandler getAccountHandler() {
                return null;
            }
        };
        noBridgeHandler.setCallback(callback);

        assertNull(noBridgeHandler.fetchHealthHistory(FROM, TO));
        verifyNoInteractions(httpClient);
    }

    @Test
    void sendThrowsExceptionReturnsNull() throws Exception {
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        assertNull(handler.fetchHealthHistory(FROM, TO));
    }

    @Test
    void urlsContainPetIdEndpointsAndDateRange() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(ACTIVITY_BODY);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(sleepResponse.getContentAsString()).thenReturn(SLEEP_BODY);

        handler.fetchHealthHistory(FROM, TO);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(2)).newRequest(urlCaptor.capture());
        List<String> urls = urlCaptor.getAllValues();
        assertAll(
                () -> assertTrue(
                        urls.stream().anyMatch(u -> u.contains("pet/" + SAMSON_PET_ID + "/activity/overview"))),
                () -> assertTrue(urls.stream().anyMatch(u -> u.contains("pet/" + SAMSON_PET_ID + "/sleep/overview"))),
                () -> assertTrue(urls.stream().allMatch(u -> u.contains("dateFrom=2025-07-01"))),
                () -> assertTrue(urls.stream().allMatch(u -> u.contains("dateTo=2025-07-08"))));
    }

    @Test
    void successBumpsLastContact() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(ACTIVITY_BODY);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(sleepResponse.getContentAsString()).thenReturn(SLEEP_BODY);

        handler.fetchHealthHistory(FROM, TO);

        verify(callback, atLeastOnce()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)), any(DateTimeType.class));
    }

    @Test
    void totalFailureDoesNotBumpLastContact() throws Exception {
        splitSleepRequest();
        when(response.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);
        when(sleepResponse.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        handler.fetchHealthHistory(FROM, TO);

        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)), any(DateTimeType.class));
    }
}
