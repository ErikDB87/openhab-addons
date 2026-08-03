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
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Unit tests for {@link TractiveTrackerHandler#fetchPositions}.
 *
 * Uses an anonymous {@link TractiveDog6Handler} subclass to override
 * {@code getAccountHandler()} so the HTTP path can be exercised without an
 * OSGi container or a real bridge.
 *
 * The test coordinates (51.15156499765719, 4.476487652479988) are the location of
 * the house used to depict the home of Samson the TV dog.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTrackerHandlerFetchPositionsTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");
    private static final String SAMSON_TRACKER_ID = "HBDYUFSC";
    private static final double SAMSON_LAT = 51.15156499765719;
    private static final double SAMSON_LON = 4.476487652479988;

    private static final ZonedDateTime FROM = ZonedDateTime.of(2025, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime TO = ZonedDateTime.of(2025, 7, 8, 0, 0, 0, 0, ZoneOffset.UTC);

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
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);

        TractiveAccountHandler br = bridge;
        handler = new TractiveDog6Handler(thing) {
            {
                trackerId = SAMSON_TRACKER_ID;
            }

            @Override
            protected @Nullable TractiveAccountHandler getAccountHandler() {
                return br;
            }
        };
        handler.setCallback(callback);
    }

    @Test
    void fetchPositionsHttp200ReturnsBodyString() throws Exception {
        String json = "[{\"time\":1784832952,\"latlong\":[" + SAMSON_LAT + "," + SAMSON_LON
                + "],\"speed\":0.0,\"sensor_used\":\"GPS\"}]";
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(json);

        assertEquals(json, handler.fetchPositions(FROM, TO));
    }

    @Test
    void fetchPositionsHttpErrorReturnsNull() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        assertNull(handler.fetchPositions(FROM, TO));
    }

    @Test
    void fetchPositionsNoBridgeReturnsNullWithoutHttpCall() throws Exception {
        TractiveDog6Handler noBridgeHandler = new TractiveDog6Handler(thing) {
            {
                trackerId = SAMSON_TRACKER_ID;
            }

            @Override
            protected @Nullable TractiveAccountHandler getAccountHandler() {
                return null;
            }
        };
        noBridgeHandler.setCallback(callback);

        assertNull(noBridgeHandler.fetchPositions(FROM, TO));
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchPositionsSendThrowsExceptionReturnsNull() throws Exception {
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        assertNull(handler.fetchPositions(FROM, TO));
    }

    @Test
    void fetchPositionsUrlContainsTrackerIdAndTimeRange() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("[]");

        handler.fetchPositions(FROM, TO);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).newRequest(urlCaptor.capture());
        String url = urlCaptor.getValue();
        assertAll(() -> assertTrue(url.contains("tracker/" + SAMSON_TRACKER_ID + "/positions")),
                () -> assertTrue(url.contains("time_from=" + FROM.toEpochSecond())),
                () -> assertTrue(url.contains("time_to=" + TO.toEpochSecond())),
                () -> assertTrue(url.contains("format=json")));
    }

    @Test
    void fetchPositionsHttp200BumpsLastContact() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("[]");

        handler.fetchPositions(FROM, TO);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)),
                any(DateTimeType.class));
    }
}
