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

import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
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
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Unit tests for {@link TractiveTrackerHandler#sendCommand}. Previously entirely untested -- this class also
 * covers its {@link SharedRateLimitBucket} interaction, the same call shape that NPE'd in
 * {@link TractiveTrackerHandlerFetchPositionsTest} when the bridge mock didn't stub
 * {@code getGraphApiRateLimitBucket()}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTrackerHandlerSendCommandTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");
    private static final String SAMSON_TRACKER_ID = "HBDYUFSC";

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
        lenient().when(bridge.getGraphApiRateLimitBucket()).thenReturn(new SharedRateLimitBucket(100, 0.0));
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);

        handler = new TractiveDog6Handler(thing) {
            {
                trackerId = SAMSON_TRACKER_ID;
            }
        };
        handler.setCallback(callback);
    }

    @Test
    void sendCommandHttp200BumpsLastContact() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"active\":false}");

        handler.sendCommand(httpClient, bridge, TractiveBindingConstants.COMMAND_BUZZER_CONTROL,
                TractiveBindingConstants.STATE_ON);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)),
                any(DateTimeType.class));
    }

    @Test
    void sendCommandUrlContainsTrackerIdCommandNameAndState() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"active\":false}");

        handler.sendCommand(httpClient, bridge, TractiveBindingConstants.COMMAND_BUZZER_CONTROL,
                TractiveBindingConstants.STATE_ON);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).newRequest(urlCaptor.capture());
        assertTrue(urlCaptor.getValue().endsWith("tracker/" + SAMSON_TRACKER_ID + "/command/"
                + TractiveBindingConstants.COMMAND_BUZZER_CONTROL + "/" + TractiveBindingConstants.STATE_ON));
    }

    @Test
    void sendCommandHttpErrorDoesNotBumpLastContact() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        handler.sendCommand(httpClient, bridge, TractiveBindingConstants.COMMAND_LED_CONTROL,
                TractiveBindingConstants.STATE_OFF);

        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)), any());
    }

    @Test
    void sendCommandSendThrowsExceptionDoesNotThrow() throws Exception {
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        assertDoesNotThrow(() -> handler.sendCommand(httpClient, bridge, TractiveBindingConstants.COMMAND_LIVE_TRACKING,
                TractiveBindingConstants.STATE_ON));
    }

    @Test
    void sendCommandProceedsEvenWhenSharedBudgetIsEmpty() throws Exception {
        SharedRateLimitBucket emptyBucket = new SharedRateLimitBucket(1, 0.0);
        assertTrue(emptyBucket.tryConsume());
        when(bridge.getGraphApiRateLimitBucket()).thenReturn(emptyBucket);
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"active\":false}");

        handler.sendCommand(httpClient, bridge, TractiveBindingConstants.COMMAND_BUZZER_CONTROL,
                TractiveBindingConstants.STATE_ON);

        verify(httpClient).newRequest(anyString());
    }
}
