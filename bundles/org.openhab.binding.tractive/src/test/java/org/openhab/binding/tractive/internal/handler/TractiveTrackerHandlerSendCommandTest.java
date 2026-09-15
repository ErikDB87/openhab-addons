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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
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
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
    private static final String SAMSON_PET_ID = "9c7a4e2d1f6b8035c2a9d0ef";

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
    private @NonNullByDefault({}) ContentResponse putResponse;
    private final TractiveStateDescriptionProvider stateDescriptionProvider = new TractiveStateDescriptionProvider();
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(callback.isChannelLinked(any())).thenReturn(true);
        lenient().when(bridge.getGraphApiRateLimitBucket()).thenReturn(new SharedRateLimitBucket(100, 0.0));
        lenient().when(bridge.getHttpClient()).thenReturn(httpClient);
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(any(HttpMethod.class))).thenReturn(request);
        lenient().when(request.content(any())).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);

        handler = new TractiveDog6Handler(thing, stateDescriptionProvider) {
            {
                trackerId = SAMSON_TRACKER_ID;
                trackedPetId = SAMSON_PET_ID;
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

    @Test
    void setBatterySaveModePostsJsonBodyToBatterySaveModeUrl() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"battery_save_mode\":true}");

        handler.setBatterySaveMode(httpClient, bridge, true);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).newRequest(urlCaptor.capture());
        assertTrue(urlCaptor.getValue().endsWith("tracker/" + SAMSON_TRACKER_ID + "/battery_save_mode"));
        verify(request).method(HttpMethod.POST);
        verify(request).content(any());
    }

    @Test
    void setBatterySaveModeAppliesConfirmedValueFromResponse() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"battery_save_mode\":true}");

        handler.setBatterySaveMode(httpClient, bridge, true);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_SAVE_MODE)),
                eq(OnOffType.ON));
    }

    @Test
    void setBatterySaveModeNon2xxDoesNotUpdateChannel() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.FORBIDDEN_403);
        when(response.getContentAsString()).thenReturn("{\"code\":8006}");

        handler.setBatterySaveMode(httpClient, bridge, true);

        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_SAVE_MODE)), any());
    }

    @Test
    void setSeparationPhaseStartPutsEmptyBodyToApsStartPath() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.NO_CONTENT_204);

        handler.setSeparationPhase(httpClient, bridge, true);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).newRequest(urlCaptor.capture());
        assertTrue(urlCaptor.getValue().contains("aps-api.tractive.com"));
        assertTrue(urlCaptor.getValue().endsWith("/pet/" + SAMSON_PET_ID + "/separation/phase/start"));
        verify(request).method(HttpMethod.PUT);
        verify(request, never()).content(any());
    }

    @Test
    void setSeparationPhaseStopUsesStopPath() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.NO_CONTENT_204);

        handler.setSeparationPhase(httpClient, bridge, false);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).newRequest(urlCaptor.capture());
        assertTrue(urlCaptor.getValue().endsWith("/separation/phase/stop"));
    }

    private static final String PROFILE_JSON = "{\"device_id\":\"" + SAMSON_TRACKER_ID + "\",\"home_location\":"
            + "[51.1,4.1],\"details\":{\"_id\":\"detail123\",\"_type\":\"pet_detail\",\"_version\":\"v-old\","
            + "\"name\":\"Samson\",\"pet_type\":\"DOG\",\"breed_ids\":[\"925\"],\"gender\":\"M\","
            + "\"birthday\":630669600,\"height\":0.56,\"weight\":21000,\"chip_id\":null,\"neutered\":true,"
            + "\"lim\":null,\"instagram_username\":null}}";

    /** Decodes a Jetty {@link ContentProvider}'s buffered bytes back to a String, to inspect a captured PUT body. */
    private static String contentAsString(ContentProvider provider) {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer buffer : provider) {
            sb.append(StandardCharsets.UTF_8.decode(buffer.duplicate()));
        }
        return sb.toString();
    }

    @Test
    void setHeightSendsFullBodyWithOverriddenFieldAndFreshVersion() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(PROFILE_JSON);
        when(putResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(request.send()).thenReturn(response, putResponse);

        handler.setHeight(httpClient, bridge, new QuantityType<>(0.60, SIUnits.METRE));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient, times(2)).newRequest(urlCaptor.capture());
        assertTrue(urlCaptor.getAllValues().get(0).endsWith("trackable_object/" + SAMSON_PET_ID));
        assertTrue(urlCaptor.getAllValues().get(1).endsWith("pet_detail/detail123"));
        verify(request).method(HttpMethod.PUT);

        ArgumentCaptor<ContentProvider> bodyCaptor = ArgumentCaptor.forClass(ContentProvider.class);
        verify(request).content(bodyCaptor.capture());
        JsonObject body = JsonParser.parseString(contentAsString(bodyCaptor.getValue())).getAsJsonObject();
        assertAll(() -> assertEquals(0.60, body.get("height").getAsDouble()),
                () -> assertEquals("v-old", body.get("_version").getAsString()),
                () -> assertEquals("Samson", body.get("name").getAsString()),
                () -> assertEquals("DOG", body.get("pet_type").getAsString()),
                () -> assertEquals("M", body.get("gender").getAsString()),
                () -> assertEquals(21000, body.get("weight").getAsInt()),
                () -> assertTrue(body.get("neutered").getAsBoolean()), () -> assertFalse(body.has("_id")));
    }

    @Test
    void setHeightAppliesCommandedValueOnSuccess() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(PROFILE_JSON);
        when(putResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(request.send()).thenReturn(response, putResponse);
        QuantityType<?> newHeight = new QuantityType<>(0.60, SIUnits.METRE);

        handler.setHeight(httpClient, bridge, newHeight);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_HEIGHT)),
                eq(newHeight));
    }

    @Test
    void setHeightSkipsHttpCallWhenAccountIsReadOnly() throws Exception {
        when(thing.getProperties()).thenReturn(Map.of(TractiveBindingConstants.PROPERTY_PET_READ_ONLY, "true"));

        handler.setHeight(httpClient, bridge, new QuantityType<>(0.60, SIUnits.METRE));

        verify(httpClient, never()).newRequest(anyString());
        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_HEIGHT)),
                any());
    }

    @Test
    void setHeightGetFailureDoesNotAttemptPut() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.NOT_FOUND_404);

        handler.setHeight(httpClient, bridge, new QuantityType<>(0.60, SIUnits.METRE));

        verify(httpClient, times(1)).newRequest(anyString());
        verify(request, never()).method(HttpMethod.PUT);
    }

    @Test
    void setHeightNon2xxPutDoesNotUpdateChannel() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(PROFILE_JSON);
        when(putResponse.getStatus()).thenReturn(HttpStatus.BAD_REQUEST_400);
        when(putResponse.getContentAsString()).thenReturn(
                "{\"code\":7002,\"category\":\"GRAPH\",\"message\":\"Provided version v-old was not equal to local version.\"}");
        when(request.send()).thenReturn(response, putResponse);

        handler.setHeight(httpClient, bridge, new QuantityType<>(0.60, SIUnits.METRE));

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_HEIGHT)),
                any());
    }

    @Test
    void setWeightAppliesCommandedValueOnSuccess() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn(PROFILE_JSON);
        when(putResponse.getStatus()).thenReturn(HttpStatus.OK_200);
        when(request.send()).thenReturn(response, putResponse);
        QuantityType<?> newWeight = new QuantityType<>(21500, SIUnits.GRAM);

        handler.setWeight(httpClient, bridge, newWeight);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_WEIGHT)),
                eq(newWeight));
    }
}
