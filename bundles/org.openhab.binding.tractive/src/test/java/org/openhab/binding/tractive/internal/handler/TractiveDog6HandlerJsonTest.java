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

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.tractive.internal.TractiveBindingConstants;
import org.openhab.binding.tractive.internal.TractiveStateDescriptionProvider;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Unit tests for the JSON-to-state parsing methods in {@link TractiveDog6Handler}
 * and the shared helpers in {@link TractiveTrackerHandler}.
 *
 * No bridge or HTTP — only JSON input and channel-update verification. {@code BaseThingHandler.scheduler}
 * is reflection-mocked in {@link #setUp()} for the buzzer/LED/live-tracking auto-off prediction tests.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveDog6HandlerJsonTest {

    private static final double SAMSON_WEIGHT = 21000.0;
    private static final double SAMSON_HEIGHT = 0.56;
    private static final long SAMSON_DATE_OF_BIRTH = 630669600L;
    private static final double SAMSON_LAT = 51.15156499765719;
    private static final double SAMSON_LON = 4.476487652479988;

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");

    @Mock
    private @NonNullByDefault({}) Thing thing;

    @Mock
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    @Mock
    private @NonNullByDefault({}) TractiveStateDescriptionProvider stateDescriptionProvider;
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @Mock
    private @NonNullByDefault({}) ScheduledExecutorService mockScheduler;
    private @NonNullByDefault({}) List<ScheduledFuture<?>> scheduledFutures;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(callback.isChannelLinked(any())).thenReturn(true);
        handler = new TractiveDog6Handler(thing, stateDescriptionProvider);
        handler.setCallback(callback);

        scheduledFutures = new ArrayList<>();
        lenient().when(mockScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            scheduledFutures.add(future);
            return future;
        });
        Field schedulerField = BaseThingHandler.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(handler, mockScheduler);
    }

    /** Runs {@code action}, then returns a map of channel-id → last State seen on that channel. */
    private Map<String, State> captureUpdates(Runnable action) {
        ArgumentCaptor<ChannelUID> channelCaptor = ArgumentCaptor.forClass(ChannelUID.class);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        action.run();
        verify(callback, atLeastOnce()).stateUpdated(channelCaptor.capture(), stateCaptor.capture());
        Map<String, State> result = new HashMap<>();
        List<ChannelUID> channels = channelCaptor.getAllValues();
        List<State> states = stateCaptor.getAllValues();
        for (int i = 0; i < channels.size(); i++) {
            result.put(channels.get(i).getId(), states.get(i));
        }
        return result;
    }

    /**
     * Builds a tracker_status-shaped event with a control object under {@code field} and (optionally)
     * tracker_state_reason.
     */
    private JsonObject controlEvent(String field, boolean active, @Nullable Double remaining,
            @Nullable String trackerStateReason) {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, active);
        if (remaining != null) {
            control.addProperty(TractiveBindingConstants.FIELD_REMAINING, remaining);
        }
        JsonObject event = new JsonObject();
        event.add(field, control);
        if (trackerStateReason != null) {
            event.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_REASON, trackerStateReason);
        }
        return event;
    }

    /** Builds a tracker_status-shaped event with a buzzer_control object and (optionally) tracker_state_reason. */
    private JsonObject buzzerControlEvent(boolean active, @Nullable Double remaining,
            @Nullable String trackerStateReason) {
        return controlEvent(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, active, remaining, trackerStateReason);
    }

    /** Builds a tracker_status-shaped event with a led_control object and (optionally) tracker_state_reason. */
    private JsonObject ledControlEvent(boolean active, @Nullable Double remaining,
            @Nullable String trackerStateReason) {
        return controlEvent(TractiveBindingConstants.COMMAND_LED_CONTROL, active, remaining, trackerStateReason);
    }

    /** Builds a tracker_status-shaped event with a live_tracking object and (optionally) tracker_state_reason. */
    private JsonObject liveTrackingControlEvent(boolean active, @Nullable Double remaining,
            @Nullable String trackerStateReason) {
        return controlEvent(TractiveBindingConstants.COMMAND_LIVE_TRACKING, active, remaining, trackerStateReason);
    }

    @Test
    void updateControlDiagnosticsUpdatesPendingReconnectingAndStartedAt() {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, true);
        control.addProperty(TractiveBindingConstants.FIELD_PENDING, true);
        control.addProperty(TractiveBindingConstants.FIELD_RECONNECTING, false);
        control.addProperty(TractiveBindingConstants.FIELD_STARTED_AT, 1784832952L);
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, control);

        Map<String, State> updates = captureUpdates(() -> handler.updateControlDiagnostics(
                TractiveBindingConstants.CHANNEL_BUZZER_PENDING, TractiveBindingConstants.CHANNEL_BUZZER_RECONNECTING,
                TractiveBindingConstants.CHANNEL_BUZZER_STARTED_AT, event,
                TractiveBindingConstants.COMMAND_BUZZER_CONTROL));

        assertEquals(OnOffType.ON, updates.get("commands#buzzer-pending"));
        assertEquals(OnOffType.OFF, updates.get("commands#buzzer-reconnecting"));
        assertInstanceOf(DateTimeType.class, updates.get("commands#buzzer-started-at"));
    }

    @Test
    void updateControlDiagnosticsMissingFieldsNoException() {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, false);
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, control);

        assertDoesNotThrow(() -> handler.updateControlDiagnostics(TractiveBindingConstants.CHANNEL_BUZZER_PENDING,
                TractiveBindingConstants.CHANNEL_BUZZER_RECONNECTING,
                TractiveBindingConstants.CHANNEL_BUZZER_STARTED_AT, event,
                TractiveBindingConstants.COMMAND_BUZZER_CONTROL));
    }

    @Test
    void updateControlDiagnosticsMissingStartedAtSetsStartedAtChannelToNull() {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, false);
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, control);

        Map<String, State> updates = captureUpdates(() -> handler.updateControlDiagnostics(
                TractiveBindingConstants.CHANNEL_BUZZER_PENDING, TractiveBindingConstants.CHANNEL_BUZZER_RECONNECTING,
                TractiveBindingConstants.CHANNEL_BUZZER_STARTED_AT, event,
                TractiveBindingConstants.COMMAND_BUZZER_CONTROL));

        assertEquals(UnDefType.NULL, updates.get("commands#buzzer-started-at"));
    }

    @Test
    void updateControlDiagnosticsExplicitNullStartedAtSetsStartedAtChannelToNull() {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, false);
        control.add(TractiveBindingConstants.FIELD_STARTED_AT, JsonNull.INSTANCE);
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, control);

        Map<String, State> updates = captureUpdates(() -> handler.updateControlDiagnostics(
                TractiveBindingConstants.CHANNEL_BUZZER_PENDING, TractiveBindingConstants.CHANNEL_BUZZER_RECONNECTING,
                TractiveBindingConstants.CHANNEL_BUZZER_STARTED_AT, event,
                TractiveBindingConstants.COMMAND_BUZZER_CONTROL));

        assertEquals(UnDefType.NULL, updates.get("commands#buzzer-started-at"));
    }

    @Test
    void updateControlDiagnosticsAbsentControlObjectLeavesStartedAtUntouched() {
        JsonObject event = new JsonObject();

        handler.updateControlDiagnostics(TractiveBindingConstants.CHANNEL_BUZZER_PENDING,
                TractiveBindingConstants.CHANNEL_BUZZER_RECONNECTING,
                TractiveBindingConstants.CHANNEL_BUZZER_STARTED_AT, event,
                TractiveBindingConstants.COMMAND_BUZZER_CONTROL);

        verify(callback, never()).stateUpdated(any(ChannelUID.class), any(State.class));
    }

    @Test
    void applyPositionReportUpdatesAllPositionChannels() {
        JsonObject json = new JsonObject();
        JsonArray latlong = new JsonArray();
        latlong.add(SAMSON_LAT);
        latlong.add(SAMSON_LON);
        json.add(TractiveBindingConstants.FIELD_LATLONG, latlong);
        json.addProperty(TractiveBindingConstants.FIELD_ALTITUDE, 9);
        json.addProperty(TractiveBindingConstants.FIELD_TIME, 1784832952L);
        json.addProperty(TractiveBindingConstants.FIELD_SPEED, 1.5);
        json.addProperty(TractiveBindingConstants.FIELD_SENSOR_USED, "GPS");
        json.addProperty(TractiveBindingConstants.FIELD_POS_UNCERTAINTY, 8);
        json.addProperty(TractiveBindingConstants.FIELD_NEARBY_USER_ID, "65eeb9406e8687af338e3952");
        JsonArray posStatus = new JsonArray();
        posStatus.add("STATIONARY_FIX");
        json.add(TractiveBindingConstants.FIELD_POS_STATUS, posStatus);

        Map<String, State> updates = captureUpdates(() -> handler.applyPositionReport(json));

        PointType location = assertInstanceOf(PointType.class, updates.get("position#location"));
        assertEquals(SAMSON_LAT, location.getLatitude().doubleValue(), 1e-6);
        assertEquals(SAMSON_LON, location.getLongitude().doubleValue(), 1e-6);
        assertEquals(9.0, location.getAltitude().doubleValue(), 1e-6);
        assertInstanceOf(DateTimeType.class, updates.get("position#last-position-time"));
        assertInstanceOf(QuantityType.class, updates.get("position#speed"));
        assertEquals(new StringType("GPS"), updates.get("position#sensor-used"));
        assertInstanceOf(QuantityType.class, updates.get("position#position-accuracy"));
        assertEquals(new StringType("65eeb9406e8687af338e3952"), updates.get("position#nearby-user-id"));
        assertEquals(new StringType("STATIONARY_FIX"), updates.get("position#position-status"));
    }

    @Test
    void applyPositionReportExplicitNullPosStatusSetsChannelToNull() {
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_POS_STATUS, JsonNull.INSTANCE);

        Map<String, State> updates = captureUpdates(() -> handler.applyPositionReport(json));

        assertEquals(UnDefType.NULL, updates.get(TractiveBindingConstants.CHANNEL_POSITION_STATUS));
    }

    @Test
    void applyPositionReportAbsentPosStatusLeavesChannelUntouched() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_SENSOR_USED, "GPS");

        Map<String, State> updates = captureUpdates(() -> handler.applyPositionReport(json));

        assertFalse(updates.containsKey(TractiveBindingConstants.CHANNEL_POSITION_STATUS));
    }

    @Test
    void applyPositionReportPosStatusFollowedByNullEndsAtNull() {
        JsonArray flags = new JsonArray();
        flags.add("STATIONARY_FIX");
        JsonObject first = new JsonObject();
        first.addProperty(TractiveBindingConstants.FIELD_TIME, 1784832952L);
        first.add(TractiveBindingConstants.FIELD_POS_STATUS, flags);
        JsonObject second = new JsonObject();
        second.addProperty(TractiveBindingConstants.FIELD_TIME, 1784832960L);
        second.add(TractiveBindingConstants.FIELD_POS_STATUS, JsonNull.INSTANCE);

        Map<String, State> updates = captureUpdates(() -> {
            handler.applyPositionReport(first);
            handler.applyPositionReport(second);
        });

        assertEquals(UnDefType.NULL, updates.get(TractiveBindingConstants.CHANNEL_POSITION_STATUS));
    }

    @Test
    void applyPositionReportExplicitNullNearbyUserIdSetsChannelToNull() {
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_NEARBY_USER_ID, JsonNull.INSTANCE);

        Map<String, State> updates = captureUpdates(() -> handler.applyPositionReport(json));

        assertEquals(UnDefType.NULL, updates.get(TractiveBindingConstants.CHANNEL_NEARBY_USER_ID));
    }

    @Test
    void applyPositionReportNullSpeedAndNullAltitudeNoExceptionAndSpeedZero() {
        JsonObject json = new JsonObject();
        JsonArray latlong = new JsonArray();
        latlong.add(SAMSON_LAT);
        latlong.add(SAMSON_LON);
        json.add(TractiveBindingConstants.FIELD_LATLONG, latlong);
        json.add(TractiveBindingConstants.FIELD_ALTITUDE, JsonNull.INSTANCE);
        json.addProperty(TractiveBindingConstants.FIELD_TIME, 1784832952L);
        json.add(TractiveBindingConstants.FIELD_SPEED, JsonNull.INSTANCE);
        json.addProperty(TractiveBindingConstants.FIELD_SENSOR_USED, "KNOWN_WIFI");
        json.addProperty(TractiveBindingConstants.FIELD_POS_UNCERTAINTY, 30);

        assertDoesNotThrow(() -> handler.applyPositionReport(json));

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION)),
                any(PointType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SPEED)),
                eq(new QuantityType<>(0, Units.METRE_PER_SECOND)));
    }

    @Test
    void applyHwReportUpdatesBatteryLevel() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_BATTERY_LEVEL, 65);

        handler.applyHwReport(json);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_LEVEL)),
                eq(new DecimalType(65)));
    }

    @Test
    void applyHwReportUpdatesTemperatureState() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_TEMPERATURE_STATE, "SOME_VALUE");

        handler.applyHwReport(json);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_TEMPERATURE_STATE)),
                eq(new StringType("SOME_VALUE")));
    }

    @Test
    void applyHwReportUpdatesClipMountedState() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_CLIP_MOUNTED_STATE, "SOME_VALUE");

        handler.applyHwReport(json);

        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_CLIP_MOUNTED_STATE)),
                eq(new StringType("SOME_VALUE")));
    }

    @Test
    void applyHwReportExplicitNullClipMountedStateSetsChannelToNull() {
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_CLIP_MOUNTED_STATE, JsonNull.INSTANCE);

        handler.applyHwReport(json);

        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_CLIP_MOUNTED_STATE)), eq(UnDefType.NULL));
    }

    @Test
    void applyHealthOverviewNullActivityBarkScratchNoExceptionNoUpdates() {
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_ACTIVITY, JsonNull.INSTANCE);
        json.add(TractiveBindingConstants.FIELD_BARK, JsonNull.INSTANCE);
        json.add(TractiveBindingConstants.FIELD_SCRATCH, JsonNull.INSTANCE);

        assertDoesNotThrow(() -> handler.applyHealthOverview(json));
        verify(callback, never()).stateUpdated(any(ChannelUID.class), any(State.class));
    }

    @Test
    void applyHealthOverviewNewFieldsUpdatesAllChannels() {
        JsonObject activity = new JsonObject();
        JsonArray hourly = new JsonArray();
        for (int h = 0; h < 24; h++) {
            hourly.add(h == 7 ? 5 : 0);
        }
        activity.add(TractiveBindingConstants.FIELD_HOURLY_DISTRIBUTION, hourly);

        JsonObject bark = new JsonObject();
        bark.addProperty(TractiveBindingConstants.FIELD_STATUS, "NORMAL");
        bark.addProperty(TractiveBindingConstants.FIELD_DAY_OFFSET, -1);
        JsonObject scratch = new JsonObject();
        scratch.addProperty(TractiveBindingConstants.FIELD_STATUS, "INFREQUENT");
        scratch.addProperty(TractiveBindingConstants.FIELD_DAY_OFFSET, 0);
        JsonObject rhr = new JsonObject();
        rhr.addProperty(TractiveBindingConstants.FIELD_STATUS, "NORMAL");
        rhr.addProperty(TractiveBindingConstants.FIELD_DAY_OFFSET, -1);
        JsonObject rrr = new JsonObject();
        rrr.addProperty(TractiveBindingConstants.FIELD_STATUS, "NORMAL");
        rrr.addProperty(TractiveBindingConstants.FIELD_DAY_OFFSET, -1);

        JsonArray associated = new JsonArray();
        JsonObject report = new JsonObject();
        report.addProperty(TractiveBindingConstants.FIELD_REPORT_TYPE, "HEALTH_WEEKLY_REPORT");
        associated.add(report);

        JsonObject phase = new JsonObject();
        phase.addProperty(TractiveBindingConstants.FIELD_IS_PHASE_ONGOING, true);
        phase.addProperty(TractiveBindingConstants.FIELD_PHASE_STARTED_AT, 1784832952L);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_ACTIVITY, activity);
        json.add(TractiveBindingConstants.FIELD_BARK, bark);
        json.add(TractiveBindingConstants.FIELD_SCRATCH, scratch);
        json.add(TractiveBindingConstants.FIELD_RESTING_HEART_RATE, rhr);
        json.add(TractiveBindingConstants.FIELD_RESTING_RESPIRATORY_RATE, rrr);
        json.add(TractiveBindingConstants.FIELD_ASSOCIATED_DATA, associated);
        json.add(TractiveBindingConstants.FIELD_SEPARATION_PHASE_STATUS, phase);

        Map<String, State> updates = captureUpdates(() -> handler.applyHealthOverview(json));

        assertEquals(new StringType("0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"),
                updates.get(TractiveBindingConstants.CHANNEL_ACTIVITY_HOURLY_DISTRIBUTION));
        assertEquals(new DecimalType(-1), updates.get(TractiveBindingConstants.CHANNEL_BARK_DAY_OFFSET));
        assertEquals(new DecimalType(0), updates.get(TractiveBindingConstants.CHANNEL_SCRATCH_DAY_OFFSET));
        assertEquals(new DecimalType(-1), updates.get(TractiveBindingConstants.CHANNEL_RESTING_HEART_RATE_DAY_OFFSET));
        assertEquals(new DecimalType(-1),
                updates.get(TractiveBindingConstants.CHANNEL_RESTING_RESPIRATORY_RATE_DAY_OFFSET));
        assertEquals(new StringType("HEALTH_WEEKLY_REPORT"),
                updates.get(TractiveBindingConstants.CHANNEL_ASSOCIATED_REPORT_TYPES));
        assertEquals(OnOffType.ON, updates.get(TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_ONGOING));
        assertEquals(Instant.ofEpochSecond(1784832952L),
                ((DateTimeType) Objects
                        .requireNonNull(updates.get(TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_STARTED_AT)))
                        .getInstant());
    }

    @Test
    void applyHealthOverviewAssociatedDataJoinsMultipleReportTypes() {
        JsonArray associated = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty(TractiveBindingConstants.FIELD_REPORT_TYPE, "HEALTH_WEEKLY_REPORT");
        JsonObject b = new JsonObject();
        b.addProperty(TractiveBindingConstants.FIELD_REPORT_TYPE, "HEALTH_MONTHLY_REPORT");
        associated.add(a);
        associated.add(b);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_ASSOCIATED_DATA, associated);

        Map<String, State> updates = captureUpdates(() -> handler.applyHealthOverview(json));

        assertEquals(new StringType("HEALTH_WEEKLY_REPORT,HEALTH_MONTHLY_REPORT"),
                updates.get(TractiveBindingConstants.CHANNEL_ASSOCIATED_REPORT_TYPES));
    }

    @Test
    void applyHealthOverviewSeparationPhaseAbsentOrNullLeavesChannelsUntouched() {
        handler.applyHealthOverview(new JsonObject());

        JsonObject withNull = new JsonObject();
        withNull.add(TractiveBindingConstants.FIELD_SEPARATION_PHASE_STATUS, JsonNull.INSTANCE);
        handler.applyHealthOverview(withNull);

        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_ONGOING)), any());
        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_STARTED_AT)), any());
    }

    @Test
    void applyHealthOverviewSeparationPhaseWithoutIsPhaseOngoingLeavesChannelsUntouched() {
        JsonObject phase = new JsonObject();
        phase.addProperty(TractiveBindingConstants.FIELD_PHASE_STARTED_AT, 1784832952L);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_SEPARATION_PHASE_STATUS, phase);

        handler.applyHealthOverview(json);

        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_ONGOING)), any());
        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_STARTED_AT)), any());
    }

    @Test
    void applyHealthOverviewSeparationPhaseEndedClearsStartedAtToUndef() {
        // The real end-of-phase payload: isPhaseOngoing present and false, phaseStartedAt explicitly null.
        JsonObject phase = new JsonObject();
        phase.addProperty(TractiveBindingConstants.FIELD_IS_PHASE_ONGOING, false);
        phase.add(TractiveBindingConstants.FIELD_PHASE_STARTED_AT, JsonNull.INSTANCE);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_SEPARATION_PHASE_STATUS, phase);

        Map<String, State> updates = captureUpdates(() -> handler.applyHealthOverview(json));

        assertEquals(OnOffType.OFF, updates.get(TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_ONGOING));
        assertEquals(UnDefType.UNDEF, updates.get(TractiveBindingConstants.CHANNEL_SEPARATION_PHASE_STARTED_AT));
    }

    @Test
    void applyProfileNewFieldsSetsAllProperties() {
        JsonObject details = new JsonObject();
        details.addProperty(TractiveBindingConstants.FIELD_PET_TYPE, "DOG");
        details.addProperty(TractiveBindingConstants.FIELD_CHIP_ID, "985121012345678");
        details.addProperty(TractiveBindingConstants.FIELD_LIM, 12.5);
        details.addProperty(TractiveBindingConstants.FIELD_RIBCAGE, 34.0);
        details.addProperty(TractiveBindingConstants.FIELD_INSTAGRAM_USERNAME, "samson_the_dog");
        details.addProperty(TractiveBindingConstants.FIELD_WEIGHT_IS_DEFAULT, false);
        details.addProperty(TractiveBindingConstants.FIELD_HEIGHT_IS_DEFAULT, true);
        details.addProperty(TractiveBindingConstants.FIELD_PROFILE_PICTURE_ID, "abc123");
        JsonArray galleryIds = new JsonArray();
        galleryIds.add("pic1");
        galleryIds.add("pic2");
        details.add(TractiveBindingConstants.FIELD_GALLERY_PICTURE_IDS, galleryIds);

        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_LEADERBOARD_OPT_OUT, false);
        json.addProperty(TractiveBindingConstants.FIELD_READ_ONLY, true);
        json.addProperty(TractiveBindingConstants.FIELD_CREATED_AT, 1712325806L);
        json.addProperty(TractiveBindingConstants.FIELD_IS_WALK_SHARING_CONSENT_PROVIDED, false);
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        handler.applyProfile(json);

        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LEADERBOARD_OPT_OUT, "false");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PET_READ_ONLY, "true");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PROFILE_CREATED_AT,
                Instant.ofEpochSecond(1712325806L).toString());
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_WALK_SHARING_CONSENT, "false");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PET_TYPE, "DOG");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_CHIP_ID, "985121012345678");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LIM, "12.5");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_RIBCAGE, "34.0");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_INSTAGRAM_USERNAME, "samson_the_dog");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_WEIGHT_IS_DEFAULT, "false");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_HEIGHT_IS_DEFAULT, "true");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PROFILE_PICTURE_ID, "abc123");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_GALLERY_PICTURE_IDS, "pic1,pic2");
    }

    @Test
    void applyProfileWalkSharingConsentAbsentClearsProperty() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_LEADERBOARD_OPT_OUT, false);

        handler.applyProfile(json);

        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_WALK_SHARING_CONSENT, null);
    }

    @Test
    void applyProfileOwnerOnlyFieldsSetsProperties() {
        JsonObject activitySettings = new JsonObject();
        activitySettings.addProperty(TractiveBindingConstants.FIELD_DAILY_GOAL, 1000);
        activitySettings.addProperty(TractiveBindingConstants.FIELD_DAILY_DISTANCE_GOAL, 0);
        activitySettings.addProperty(TractiveBindingConstants.FIELD_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE, "custom");
        activitySettings.addProperty(TractiveBindingConstants.FIELD_DAILY_ACTIVE_MINUTES_GOAL_IS_DEFAULT, 1);

        JsonObject details = new JsonObject();
        details.addProperty(TractiveBindingConstants.FIELD_LENGTH, 45.0);
        JsonArray personality = new JsonArray();
        personality.add("playful");
        personality.add("curious");
        details.add(TractiveBindingConstants.FIELD_PERSONALITY, personality);
        details.addProperty(TractiveBindingConstants.FIELD_LOST_OR_DEAD, false);
        details.addProperty(TractiveBindingConstants.FIELD_BIRTHDAY_IS_DEFAULT, true);
        details.addProperty(TractiveBindingConstants.FIELD_BREED_IS_DEFAULT, false);
        details.add(TractiveBindingConstants.FIELD_ACTIVITY_SETTINGS, activitySettings);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        handler.applyProfile(json);

        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LENGTH, "45.0");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PERSONALITY, "playful,curious");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LOST_OR_DEAD, "false");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BIRTHDAY_IS_DEFAULT, "true");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BREED_IS_DEFAULT, "false");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_GOAL, "1000");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_DISTANCE_GOAL, "0");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE, "custom");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_MINUTES_GOAL_IS_DEFAULT, "1");
    }

    @Test
    void applyProfileOwnerOnlyFieldsAbsentClearsProperties() {
        JsonObject details = new JsonObject();
        details.addProperty(TractiveBindingConstants.FIELD_GENDER, "M");

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        handler.applyProfile(json);

        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LENGTH, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PERSONALITY, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_LOST_OR_DEAD, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BIRTHDAY_IS_DEFAULT, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BREED_IS_DEFAULT, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_GOAL, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_DISTANCE_GOAL, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE, null);
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_DAILY_MINUTES_GOAL_IS_DEFAULT, null);
    }

    @Test
    void applyProfileActivityCategoryThresholdsOverrideNonPrimitiveDoesNotSetProperty() {
        JsonArray thresholds = new JsonArray();
        thresholds.add(10);
        thresholds.add(30);
        thresholds.add(60);
        JsonObject activitySettings = new JsonObject();
        activitySettings.add(TractiveBindingConstants.FIELD_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE, thresholds);
        JsonObject details = new JsonObject();
        details.add(TractiveBindingConstants.FIELD_ACTIVITY_SETTINGS, activitySettings);
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        assertDoesNotThrow(() -> handler.applyProfile(json));
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE, null);
    }

    @Test
    void applyProfilePreExistingFieldsUpdatesChannelsAndProperties() {
        JsonObject details = new JsonObject();
        details.addProperty(TractiveBindingConstants.FIELD_GENDER, "M");
        details.addProperty(TractiveBindingConstants.FIELD_BIRTHDAY, SAMSON_DATE_OF_BIRTH);
        details.addProperty(TractiveBindingConstants.FIELD_HEIGHT, SAMSON_HEIGHT);
        details.addProperty(TractiveBindingConstants.FIELD_WEIGHT, SAMSON_WEIGHT);
        details.addProperty(TractiveBindingConstants.FIELD_NEUTERED, true);
        JsonArray breedIds = new JsonArray();
        breedIds.add("925");
        details.add(TractiveBindingConstants.FIELD_BREED_IDS, breedIds);

        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);
        JsonArray homeLocation = new JsonArray();
        homeLocation.add(51.2038919);
        homeLocation.add(4.7111879);
        json.add(TractiveBindingConstants.FIELD_HOME_LOCATION, homeLocation);

        Map<String, State> updates = captureUpdates(() -> handler.applyProfile(json));

        assertEquals(SAMSON_HEIGHT,
                ((QuantityType<?>) Objects.requireNonNull(updates.get(TractiveBindingConstants.CHANNEL_HEIGHT)))
                        .doubleValue());
        assertEquals(SAMSON_WEIGHT,
                ((QuantityType<?>) Objects.requireNonNull(updates.get(TractiveBindingConstants.CHANNEL_WEIGHT)))
                        .doubleValue());
        assertEquals(new PointType(new DecimalType(51.2038919), new DecimalType(4.7111879)),
                updates.get(TractiveBindingConstants.CHANNEL_HOME_LOCATION));
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_GENDER, "M");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BIRTHDAY,
                Instant.ofEpochSecond(SAMSON_DATE_OF_BIRTH).toString());
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_NEUTERED, "true");
        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_BREED_IDS, "925");
    }

    @Test
    void applyProfileDetailsReadOnlyIsNotSurfacedSeparately() {
        JsonObject details = new JsonObject();
        details.addProperty(TractiveBindingConstants.FIELD_READ_ONLY, true);
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_READ_ONLY, false);
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        handler.applyProfile(json);

        verify(thing).setProperty(TractiveBindingConstants.PROPERTY_PET_READ_ONLY, "false");
        verify(thing, never()).setProperty(eq(TractiveBindingConstants.PROPERTY_PET_READ_ONLY), eq("true"));
    }

    @Test
    void applyProfileNewFieldsNullValuesNoExceptionNoPropertyUpdates() {
        JsonObject details = new JsonObject();
        details.add(TractiveBindingConstants.FIELD_LIM, JsonNull.INSTANCE);
        details.add(TractiveBindingConstants.FIELD_RIBCAGE, JsonNull.INSTANCE);
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_READ_ONLY, JsonNull.INSTANCE);
        json.add(TractiveBindingConstants.FIELD_DETAILS, details);

        assertDoesNotThrow(() -> handler.applyProfile(json));
        verify(thing, never()).setProperty(eq(TractiveBindingConstants.PROPERTY_LIM), anyString());
        verify(thing, never()).setProperty(eq(TractiveBindingConstants.PROPERTY_RIBCAGE), anyString());
        verify(thing, never()).setProperty(eq(TractiveBindingConstants.PROPERTY_PET_READ_ONLY), anyString());
    }

    @Test
    void onSubscriptionUpdateUpdatesBothChannels() {
        JsonObject subscription = new JsonObject();
        subscription.addProperty(TractiveBindingConstants.FIELD_STATUS, "ACTIVE");
        subscription.addProperty(TractiveBindingConstants.FIELD_VALID_TO, 1838505599L);

        Map<String, State> updates = captureUpdates(() -> handler.onSubscriptionUpdate(subscription));

        assertEquals(new StringType("ACTIVE"), updates.get(TractiveBindingConstants.CHANNEL_SUBSCRIPTION_STATUS));
        assertEquals(Instant.ofEpochSecond(1838505599L),
                ((DateTimeType) Objects
                        .requireNonNull(updates.get(TractiveBindingConstants.CHANNEL_SUBSCRIPTION_VALID_TO)))
                        .getInstant());
    }

    @Test
    void trackerStatusWithStateButNoReasonClearsTrackerStateReason() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_LIVE, "OPERATIONAL");

        Map<String, State> updates = captureUpdates(
                () -> handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event));

        assertEquals(UnDefType.NULL, updates.get(TractiveBindingConstants.CHANNEL_TRACKER_STATE_REASON));
    }

    @Test
    void trackerStatusWithStateAndReasonSetsTrackerStateReason() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_LIVE, "OPERATIONAL");
        event.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_REASON, "POWER_SAVING");

        Map<String, State> updates = captureUpdates(
                () -> handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event));

        assertEquals(new StringType("POWER_SAVING"),
                updates.get(TractiveBindingConstants.CHANNEL_TRACKER_STATE_REASON));
    }

    @Test
    void partialTrackerStatusWithoutStateLeavesTrackerStateReasonUntouched() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_CHARGING_STATE, "NOT_CHARGING");

        Map<String, State> updates = captureUpdates(
                () -> handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event));

        assertFalse(updates.containsKey(TractiveBindingConstants.CHANNEL_TRACKER_STATE_REASON));
    }

    @Test
    void updateTimestampChannelWithEpochLongProducesCorrectInstant() {
        long epoch = 1784832952L;
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_ACTIVITY_DATA_SYNCED_AT, epoch);

        handler.updateTimestampChannel(TractiveBindingConstants.CHANNEL_ACTIVITY_SYNCED_AT, json,
                TractiveBindingConstants.FIELD_ACTIVITY_DATA_SYNCED_AT);

        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_SYNCED_AT)),
                stateCaptor.capture());
        assertEquals(Instant.ofEpochSecond(epoch), ((DateTimeType) stateCaptor.getValue()).getInstant());
    }

    @Test
    void updateTimestampChannelWithIso8601StringProducesSameInstant() {
        long epoch = 1784832952L;
        String iso = Instant.ofEpochSecond(epoch).toString();
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_ACTIVITY_DATA_SYNCED_AT, iso);

        handler.updateTimestampChannel(TractiveBindingConstants.CHANNEL_ACTIVITY_SYNCED_AT, json,
                TractiveBindingConstants.FIELD_ACTIVITY_DATA_SYNCED_AT);

        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_SYNCED_AT)),
                stateCaptor.capture());
        assertEquals(Instant.ofEpochSecond(epoch), ((DateTimeType) stateCaptor.getValue()).getInstant());
    }

    @Test
    void onChannelEventHealthOverviewUnwrapsContentAndUpdatesActivityChannels() {
        JsonObject activity = new JsonObject();
        activity.addProperty(TractiveBindingConstants.FIELD_MINUTES_ACTIVE, 196);
        activity.addProperty(TractiveBindingConstants.FIELD_MINUTES_GOAL, 155);
        JsonObject content = new JsonObject();
        content.add(TractiveBindingConstants.FIELD_ACTIVITY, activity);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_CONTENT, content);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_HEALTH_OVERVIEW, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_RECORDED)),
                any(QuantityType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_GOAL)),
                any(QuantityType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)),
                any(DateTimeType.class));
    }

    @Test
    void onChannelEventTrackerStatusUnwrapsPositionAndHardware() {
        JsonObject position = new JsonObject();
        JsonArray latlong = new JsonArray();
        latlong.add(51.27526);
        latlong.add(5.304217);
        position.add(TractiveBindingConstants.FIELD_LATLONG, latlong);

        JsonObject hardware = new JsonObject();
        hardware.addProperty(TractiveBindingConstants.FIELD_BATTERY_LEVEL, 48);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_POSITION, position);
        event.add(TractiveBindingConstants.FIELD_HARDWARE, hardware);
        event.addProperty(TractiveBindingConstants.FIELD_CHARGING_STATE, "NOT_CHARGING");
        event.addProperty(TractiveBindingConstants.FIELD_BATTERY_STATE, "REGULAR");

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION)),
                any(PointType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_LEVEL)),
                any(DecimalType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_CHARGING_STATE)),
                any(StringType.class));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)),
                any(DateTimeType.class));
    }

    @Test
    void applyPowerSavingZoneIdUsesTopLevelValueWhenPresent() {
        JsonObject hardware = new JsonObject();
        hardware.addProperty(TractiveBindingConstants.FIELD_POWER_SAVING_ZONE_ID, "zone-from-hardware");
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_POWER_SAVING_ZONE_ID, "zone-from-top-level");
        event.add(TractiveBindingConstants.FIELD_HARDWARE, hardware);

        handler.applyPowerSavingZoneId(event);

        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_POWER_SAVING_ZONE_ID)),
                eq(new StringType("zone-from-top-level")));
    }

    @Test
    void applyPowerSavingZoneIdFallsBackToHardwareWhenTopLevelMissing() {
        JsonObject hardware = new JsonObject();
        hardware.addProperty(TractiveBindingConstants.FIELD_POWER_SAVING_ZONE_ID, "zone-from-hardware");
        JsonObject position = new JsonObject();
        position.addProperty(TractiveBindingConstants.FIELD_POWER_SAVING_ZONE_ID, "zone-from-position");
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_HARDWARE, hardware);
        event.add(TractiveBindingConstants.FIELD_POSITION, position);

        handler.applyPowerSavingZoneId(event);

        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_POWER_SAVING_ZONE_ID)),
                eq(new StringType("zone-from-hardware")));
    }

    @Test
    void applyPowerSavingZoneIdFallsBackToPositionWhenTopLevelAndHardwareMissing() {
        JsonObject position = new JsonObject();
        position.addProperty(TractiveBindingConstants.FIELD_POWER_SAVING_ZONE_ID, "zone-from-position");
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_POSITION, position);

        handler.applyPowerSavingZoneId(event);

        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_POWER_SAVING_ZONE_ID)),
                eq(new StringType("zone-from-position")));
    }

    @Test
    void applyPowerSavingZoneIdAbsentEverywhereLeavesChannelUntouched() {
        JsonObject event = new JsonObject();

        handler.applyPowerSavingZoneId(event);

        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_POWER_SAVING_ZONE_ID)), any(State.class));
    }

    @Test
    void onChannelEventTrackerStatusLedControlActiveTrueTurnsLedChannelOn() {
        JsonObject ledControl = new JsonObject();
        ledControl.addProperty(TractiveBindingConstants.FIELD_ACTIVE, true);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_LED_CONTROL, ledControl);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                eq(OnOffType.ON));
    }

    @Test
    void onChannelEventTrackerStatusBuzzerControlActiveFalseTurnsBuzzerChannelOff() {
        JsonObject buzzerControl = new JsonObject();
        buzzerControl.addProperty(TractiveBindingConstants.FIELD_ACTIVE, false);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, buzzerControl);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
    }

    @Test
    void onChannelEventTrackerStatusLiveTrackingActiveTrueTurnsLiveTrackingChannelOn() {
        JsonObject liveTracking = new JsonObject();
        liveTracking.addProperty(TractiveBindingConstants.FIELD_ACTIVE, true);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_LIVE_TRACKING, liveTracking);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)),
                eq(OnOffType.ON));
    }

    @Test
    void onChannelEventTrackerStatusWithoutControlFieldsLeavesCommandChannelsUntouched() {
        JsonObject position = new JsonObject();
        JsonArray latlong = new JsonArray();
        latlong.add(51.27526);
        latlong.add(5.304217);
        position.add(TractiveBindingConstants.FIELD_LATLONG, latlong);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_POSITION, position);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                any());
        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                any());
        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)), any());
    }

    @Test
    void onChannelEventTrackerStatusControlObjectWithoutActiveFieldLeavesChannelUntouched() {
        JsonObject ledControl = new JsonObject();
        ledControl.addProperty("pending", true);

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_LED_CONTROL, ledControl);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                any());
    }

    @Test
    void onChannelEventUnknownMessageTypeStillBumpsLastContact() {
        JsonObject event = new JsonObject();

        handler.onChannelEvent("some_future_message_type", event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)),
                any(DateTimeType.class));
    }

    @Test
    void lastContactCoalescedWithinThrottleWindow() {
        handler.onChannelEvent("some_future_message_type", new JsonObject());
        handler.onChannelEvent("some_future_message_type", new JsonObject());

        verify(callback, times(1)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)), any(DateTimeType.class));
    }

    @Test
    void lastContactBumpsAgainAfterThrottleWindow() throws Exception {
        handler.onChannelEvent("some_future_message_type", new JsonObject());

        Field lastContactField = TractiveTrackerHandler.class.getDeclaredField("lastContactUpdateMs");
        lastContactField.setAccessible(true);
        lastContactField.setLong(handler, System.currentTimeMillis() - 6_000L);

        handler.onChannelEvent("some_future_message_type", new JsonObject());

        verify(callback, times(2)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LAST_CONTACT)), any(DateTimeType.class));
    }

    @Test
    void onChannelEventStartFailedLedCommandTypeTurnsLedChannelOff() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE, TractiveBindingConstants.VALUE_COMMAND_TYPE_LED);
        event.addProperty(TractiveBindingConstants.FIELD_CANCELLATION_REASON, "LED_START_FAILED_TIMEOUT");

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_START_FAILED, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                eq(OnOffType.OFF));
    }

    @Test
    void onChannelEventStartFailedBuzzerCommandTypeTurnsBuzzerChannelOff() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE,
                TractiveBindingConstants.VALUE_COMMAND_TYPE_BUZZER);
        event.addProperty(TractiveBindingConstants.FIELD_CANCELLATION_REASON, "BUZZER_START_FAILED_TIMEOUT");

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_START_FAILED, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
    }

    @Test
    void onChannelEventStartFailedLiveTrackingCommandTypeTurnsLiveTrackingChannelOff() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE,
                TractiveBindingConstants.VALUE_COMMAND_TYPE_LIVE_TRACKING);
        event.addProperty(TractiveBindingConstants.FIELD_CANCELLATION_REASON, "LT_START_FAILED_TIMEOUT");

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_START_FAILED, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)),
                eq(OnOffType.OFF));
    }

    @Test
    void onChannelEventStartFailedUnknownCommandTypeUpdatesNoCommandChannel() {
        JsonObject event = new JsonObject();
        event.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE, "MSG_S2D_SOME_FUTURE_CONTROL");
        event.addProperty(TractiveBindingConstants.FIELD_CANCELLATION_REASON, "SOME_FUTURE_TIMEOUT");

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_START_FAILED, event);

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                any());
        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                any());
        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)), any());
    }

    @Test
    void onChannelEventStartFailedMissingCommandTypeDoesNotThrowOrUpdateCommandChannels() {
        JsonObject event = new JsonObject();

        assertDoesNotThrow(() -> handler.onChannelEvent(TractiveBindingConstants.MESSAGE_START_FAILED, event));

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                any());
        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                any());
        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)), any());
    }

    @Test
    void onChannelEventTrackerStatusBuzzerActiveSchedulesAutoOffAfterRemainingSeconds() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 42.5, null));

        verify(mockScheduler).schedule(any(Runnable.class), eq(42500L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void predictedAutoOffFiresButSkipsUpdateWhenNotDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 0.05, null));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
    }

    @Test
    void predictedAutoOffFiresAndPushesOffWhenDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                buzzerControlEvent(true, 0.05, TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER_REMAINING)),
                eq(new QuantityType<>(0, Units.SECOND)));
    }

    @Test
    void partialTrackerStatusWithoutStateKeepsDormancyFlagSoPredictedAutoOffStillFires() {
        JsonObject dormantPush = new JsonObject();
        dormantPush.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_REASON,
                TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING);
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, dormantPush);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 0.05, null));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
    }

    @Test
    void trackerStatusWithStateButNoReasonClearsDormancyFlagSoPredictedAutoOffIsSkipped() {
        JsonObject dormantPush = new JsonObject();
        dormantPush.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_REASON,
                TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING);
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, dormantPush);

        JsonObject awakePush = buzzerControlEvent(true, 0.05, null);
        awakePush.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_LIVE, "OPERATIONAL");
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, awakePush);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                eq(OnOffType.OFF));
    }

    @Test
    void autoOffFiresAndUpdatesOnlyStillLinkedChannelWhenSwitchBecomesUnlinkedBeforeFiring() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                buzzerControlEvent(true, 0.05, TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));

        when(callback.isChannelLinked(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER))))
                .thenReturn(false);
        clearInvocations(callback);
        task.getValue().run();

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER)),
                any());
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER_REMAINING)),
                eq(new QuantityType<>(0, Units.SECOND)));
    }

    @Test
    void autoOffFiresButUpdatesNothingWhenBothChannelsBecomeUnlinkedBeforeFiring() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                buzzerControlEvent(true, 0.05, TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));

        when(callback.isChannelLinked(any())).thenReturn(false);
        clearInvocations(callback);
        task.getValue().run();

        verify(callback, never()).stateUpdated(any(), any());
    }

    @Test
    void freshConfirmationCancelsAndReschedulesPreviousAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 250.0, null));

        assertEquals(2, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void realConfirmedOffCancelsPendingAutoOffWithoutRescheduling() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(false, null, null));

        assertEquals(1, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void buzzerStartFailedCancelsPendingAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 300.0, null));

        JsonObject startFailed = new JsonObject();
        startFailed.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE,
                TractiveBindingConstants.VALUE_COMMAND_TYPE_BUZZER);
        handler.applyStartFailed(startFailed);

        verify(scheduledFutures.get(0)).cancel(false);
    }

    @Test
    void buzzerActiveWithoutRemainingFieldDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, null, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void buzzerActiveWithZeroRemainingDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 0.0, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void onChannelEventTrackerStatusLedActiveSchedulesAutoOffAfterRemainingSeconds() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 42.5, null));

        verify(mockScheduler).schedule(any(Runnable.class), eq(42500L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void ledPredictedAutoOffFiresButSkipsUpdateWhenNotDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 0.05, null));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback, never()).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                eq(OnOffType.OFF));
    }

    @Test
    void ledPredictedAutoOffFiresAndPushesOffWhenDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                ledControlEvent(true, 0.05, TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED)),
                eq(OnOffType.OFF));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED_REMAINING)),
                eq(new QuantityType<>(0, Units.SECOND)));
    }

    @Test
    void ledFreshConfirmationCancelsAndReschedulesPreviousAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 250.0, null));

        assertEquals(2, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void ledRealConfirmedOffCancelsPendingAutoOffWithoutRescheduling() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(false, null, null));

        assertEquals(1, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void ledStartFailedCancelsPendingAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 300.0, null));

        JsonObject startFailed = new JsonObject();
        startFailed.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE,
                TractiveBindingConstants.VALUE_COMMAND_TYPE_LED);
        handler.applyStartFailed(startFailed);

        verify(scheduledFutures.get(0)).cancel(false);
    }

    @Test
    void ledActiveWithoutRemainingFieldDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, null, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void ledActiveWithZeroRemainingDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 0.0, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void buzzerActiveWithZeroRemainingReportsSwitchOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 0.0, null));

        ChannelUID buzzer = new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER);
        verify(callback).stateUpdated(eq(buzzer), eq(OnOffType.OFF));
        verify(callback, never()).stateUpdated(eq(buzzer), eq(OnOffType.ON));
    }

    @Test
    void ledActiveWithZeroRemainingReportsSwitchOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, ledControlEvent(true, 0.0, null));

        ChannelUID led = new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LED);
        verify(callback).stateUpdated(eq(led), eq(OnOffType.OFF));
        verify(callback, never()).stateUpdated(eq(led), eq(OnOffType.ON));
    }

    @Test
    void liveTrackingActiveWithZeroRemainingStaysOn() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 0.0, null));

        ChannelUID liveTracking = new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING);
        verify(callback).stateUpdated(eq(liveTracking), eq(OnOffType.ON));
        verify(callback, never()).stateUpdated(eq(liveTracking), eq(OnOffType.OFF));
    }

    @Test
    void buzzerActiveWithZeroRemainingButQueuedCommandStaysOn() {
        JsonObject event = buzzerControlEvent(true, 0.0, null);
        event.getAsJsonObject(TractiveBindingConstants.COMMAND_BUZZER_CONTROL)
                .addProperty(TractiveBindingConstants.FIELD_PENDING, true);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        ChannelUID buzzer = new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER);
        verify(callback).stateUpdated(eq(buzzer), eq(OnOffType.ON));
        verify(callback, never()).stateUpdated(eq(buzzer), eq(OnOffType.OFF));
    }

    @Test
    void buzzerActiveWithTimeRemainingStaysOn() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, buzzerControlEvent(true, 5.0, null));

        ChannelUID buzzer = new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BUZZER);
        verify(callback).stateUpdated(eq(buzzer), eq(OnOffType.ON));
        verify(callback, never()).stateUpdated(eq(buzzer), eq(OnOffType.OFF));
    }

    @Test
    void onChannelEventTrackerStatusLiveTrackingActiveSchedulesAutoOffAfterRemainingSeconds() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 42.5, null));

        verify(mockScheduler).schedule(any(Runnable.class), eq(42500L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void liveTrackingPredictedAutoOffFiresButSkipsUpdateWhenNotDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 0.05, null));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)), eq(OnOffType.OFF));
    }

    @Test
    void liveTrackingPredictedAutoOffFiresAndPushesOffWhenDormant() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 0.05, TractiveBindingConstants.VALUE_STATE_REASON_POWER_SAVING));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        task.getValue().run();

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING)),
                eq(OnOffType.OFF));
        verify(callback).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LIVE_TRACKING_REMAINING)),
                eq(new QuantityType<>(0, Units.SECOND)));
    }

    @Test
    void liveTrackingFreshConfirmationCancelsAndReschedulesPreviousAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 250.0, null));

        assertEquals(2, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void liveTrackingRealConfirmedOffCancelsPendingAutoOffWithoutRescheduling() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 300.0, null));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(false, null, null));

        assertEquals(1, scheduledFutures.size());
        verify(scheduledFutures.get(0)).cancel(false);
        verify(mockScheduler, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void liveTrackingStartFailedCancelsPendingAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 300.0, null));

        JsonObject startFailed = new JsonObject();
        startFailed.addProperty(TractiveBindingConstants.FIELD_COMMAND_TYPE,
                TractiveBindingConstants.VALUE_COMMAND_TYPE_LIVE_TRACKING);
        handler.applyStartFailed(startFailed);

        verify(scheduledFutures.get(0)).cancel(false);
    }

    @Test
    void liveTrackingActiveWithoutRemainingFieldDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, null, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void liveTrackingActiveWithZeroRemainingDoesNotScheduleAutoOff() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS,
                liveTrackingControlEvent(true, 0.0, null));

        verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    private JsonObject positionReport(double lat, double lon, long time) {
        JsonObject json = new JsonObject();
        JsonArray latlong = new JsonArray();
        latlong.add(lat);
        latlong.add(lon);
        json.add(TractiveBindingConstants.FIELD_LATLONG, latlong);
        json.addProperty(TractiveBindingConstants.FIELD_TIME, time);
        return json;
    }

    @Test
    void applyPositionReportOlderTimeIsIgnored() {
        handler.applyPositionReport(positionReport(SAMSON_LAT, SAMSON_LON, 1784832952L));
        handler.applyPositionReport(positionReport(SAMSON_LAT + 1, SAMSON_LON + 1, 1784832951L));

        verify(callback, times(1)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION)), any(PointType.class));
    }

    @Test
    void applyPositionReportEqualTimeIsStillApplied() {
        JsonObject json = positionReport(SAMSON_LAT, SAMSON_LON, 1784832952L);

        handler.applyPositionReport(json);
        handler.applyPositionReport(json);

        verify(callback, times(2)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION)), any(PointType.class));
    }

    @Test
    void applyPositionReportNewerTimeAfterOlderIsApplied() {
        handler.applyPositionReport(positionReport(SAMSON_LAT, SAMSON_LON, 1784832951L));
        handler.applyPositionReport(positionReport(SAMSON_LAT + 1, SAMSON_LON + 1, 1784832952L));

        verify(callback, times(2)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION)), any(PointType.class));
    }

    private JsonObject hwReport(int batteryLevel, long time) {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_BATTERY_LEVEL, batteryLevel);
        json.addProperty(TractiveBindingConstants.FIELD_TIME, time);
        return json;
    }

    @Test
    void applyHwReportOlderTimeIsIgnored() {
        handler.applyHwReport(hwReport(80, 1784832952L));
        handler.applyHwReport(hwReport(20, 1784832951L));

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_LEVEL)),
                eq(new DecimalType(80)));
        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_LEVEL)), eq(new DecimalType(20)));
    }

    private JsonObject healthOverviewEvent(int minutesActive, long syncedAt) {
        JsonObject activity = new JsonObject();
        activity.addProperty(TractiveBindingConstants.FIELD_MINUTES_ACTIVE, minutesActive);
        JsonObject content = new JsonObject();
        content.add(TractiveBindingConstants.FIELD_ACTIVITY, activity);
        content.addProperty(TractiveBindingConstants.FIELD_ACTIVITY_DATA_SYNCED_AT, syncedAt);
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_CONTENT, content);
        return event;
    }

    @Test
    void onChannelEventHealthOverviewOlderSyncIsIgnored() {
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_HEALTH_OVERVIEW, healthOverviewEvent(200, 1784832952L));
        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_HEALTH_OVERVIEW, healthOverviewEvent(50, 1784832951L));

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_RECORDED)),
                eq(new QuantityType<>(200, Units.MINUTE)));
        verify(callback, never()).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_RECORDED)),
                eq(new QuantityType<>(50, Units.MINUTE)));
    }

    @Test
    void onChannelEventDerivesZoneLastSeenAtFromPositionTimeWhenZoneCoOccurs() {
        JsonObject zone = new JsonObject();
        zone.addProperty(TractiveBindingConstants.FIELD_ID, "zone-1");
        zone.addProperty(TractiveBindingConstants.FIELD_ZONE_TYPE, "POWER_SAVING");

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_POSITION, positionReport(SAMSON_LAT, SAMSON_LON, 1784832952L));
        event.add(TractiveBindingConstants.FIELD_PRIORITIZED_ZONE, zone);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ZONE_LAST_SEEN_AT)),
                eq(new DateTimeType(Instant.ofEpochSecond(1784832952L).atZone(ZoneId.systemDefault()))));
    }

    @Test
    void onChannelEventDoesNotDeriveZoneLastSeenAtWithoutZone() {
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_POSITION, positionReport(SAMSON_LAT, SAMSON_LON, 1784832952L));

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ZONE_LAST_SEEN_AT)), any());
    }

    @Test
    void onChannelEventDoesNotDeriveZoneLastSeenAtWithoutPosition() {
        JsonObject zone = new JsonObject();
        zone.addProperty(TractiveBindingConstants.FIELD_ID, "zone-1");

        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.FIELD_PRIORITIZED_ZONE, zone);

        handler.onChannelEvent(TractiveBindingConstants.MESSAGE_TRACKER_STATUS, event);

        verify(callback, never())
                .stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ZONE_LAST_SEEN_AT)), any());
    }

    @Test
    void applyProfileSetsReadOnlyOverrideForHeightAndWeightWhenAccountIsSharedReadOnly() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_READ_ONLY, true);

        handler.applyProfile(json);

        verify(stateDescriptionProvider)
                .setReadOnly(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_HEIGHT)), eq(true));
        verify(stateDescriptionProvider)
                .setReadOnly(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_WEIGHT)), eq(true));
    }

    @Test
    void applyProfileClearsReadOnlyOverrideForHeightAndWeightWhenAccountIsOwner() {
        JsonObject json = new JsonObject();
        json.addProperty(TractiveBindingConstants.FIELD_READ_ONLY, false);

        handler.applyProfile(json);

        verify(stateDescriptionProvider)
                .setReadOnly(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_HEIGHT)), eq(false));
        verify(stateDescriptionProvider)
                .setReadOnly(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_WEIGHT)), eq(false));
    }
}
