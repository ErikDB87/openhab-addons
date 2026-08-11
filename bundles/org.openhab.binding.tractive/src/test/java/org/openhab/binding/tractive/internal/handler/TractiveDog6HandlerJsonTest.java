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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Unit tests for the JSON-to-state parsing methods in {@link TractiveDog6Handler}
 * and the shared helpers in {@link TractiveTrackerHandler}.
 *
 * No bridge or HTTP — only JSON input and channel-update verification. {@code BaseThingHandler.scheduler}
 * is reflection-mocked in {@link #setUp()} for the buzzer auto-off prediction tests.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveDog6HandlerJsonTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");

    private static final double SAMSON_LAT = 51.15156499765719;
    private static final double SAMSON_LON = 4.476487652479988;

    @Mock
    private @NonNullByDefault({}) Thing thing;
    @Mock
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @Mock
    private @NonNullByDefault({}) ScheduledExecutorService mockScheduler;
    private @NonNullByDefault({}) List<ScheduledFuture<?>> scheduledFutures;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(callback.isChannelLinked(any())).thenReturn(true);
        handler = new TractiveDog6Handler(thing);
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

    /** Builds a tracker_status-shaped event with a buzzer_control object and (optionally) tracker_state_reason. */
    private JsonObject buzzerControlEvent(boolean active, @Nullable Double remaining,
            @Nullable String trackerStateReason) {
        JsonObject control = new JsonObject();
        control.addProperty(TractiveBindingConstants.FIELD_ACTIVE, active);
        if (remaining != null) {
            control.addProperty(TractiveBindingConstants.FIELD_REMAINING, remaining);
        }
        JsonObject event = new JsonObject();
        event.add(TractiveBindingConstants.COMMAND_BUZZER_CONTROL, control);
        if (trackerStateReason != null) {
            event.addProperty(TractiveBindingConstants.FIELD_TRACKER_STATE_REASON, trackerStateReason);
        }
        return event;
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

        Map<String, State> updates = captureUpdates(() -> handler.applyPositionReport(json));

        PointType location = assertInstanceOf(PointType.class, updates.get("position#location"));
        assertEquals(SAMSON_LAT, location.getLatitude().doubleValue(), 1e-6);
        assertEquals(SAMSON_LON, location.getLongitude().doubleValue(), 1e-6);
        assertEquals(9.0, location.getAltitude().doubleValue(), 1e-6);
        assertInstanceOf(DateTimeType.class, updates.get("position#last-position-time"));
        assertInstanceOf(QuantityType.class, updates.get("position#speed"));
        assertEquals(new StringType("GPS"), updates.get("position#sensor-used"));
        assertInstanceOf(QuantityType.class, updates.get("position#position-accuracy"));
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
    void applyHealthOverviewNullActivityBarkScratchNoExceptionNoUpdates() {
        JsonObject json = new JsonObject();
        json.add(TractiveBindingConstants.FIELD_ACTIVITY, JsonNull.INSTANCE);
        json.add(TractiveBindingConstants.FIELD_BARK, JsonNull.INSTANCE);
        json.add(TractiveBindingConstants.FIELD_SCRATCH, JsonNull.INSTANCE);

        assertDoesNotThrow(() -> handler.applyHealthOverview(json));
        verify(callback, never()).stateUpdated(any(ChannelUID.class), any(State.class));
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
}
