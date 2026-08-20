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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.tractive.internal.TractiveBindingConstants;
import org.openhab.binding.tractive.internal.util.PollGuard;
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Unit tests for the {@code isLinked()}/group-linked auto-skip gating added to {@link
 * TractiveTrackerHandler#pollTrackerDetails}/{@link TractiveTrackerHandler#pollHwReport}/{@link
 * TractiveTrackerHandler#pollPositionReport}/{@link TractiveTrackerHandler#pollHealthOverview}, the
 * {@code trackerDetailsGuard}-backed one-time seed of the Tracker Status group, the {@link
 * TractiveTrackerHandler#channelLinked}/{@link TractiveTrackerHandler#channelUnlinked} caching that backs the
 * Position/Health group skips, and the {@code bridge.getAccessToken() != null} startup-race guard on {@link
 * TractiveTrackerHandler#initialize}/the {@code ONLINE}-vs-everything-else branching in {@link
 * TractiveTrackerHandler#bridgeStatusChanged}.
 *
 * The {@code initialize()}/{@code bridgeStatusChanged()} tests are the only ones in this file that need
 * {@code BaseThingHandler.scheduler} reflection-mocked (same pattern as {@link TractiveDog6HandlerJsonTest}) and
 * {@code thing.getConfiguration()}/{@code thing.getBridgeUID()}/{@code callback.getBridge(...)} stubbed so {@code
 * getAccountHandler()} resolves to {@link #bridge}; every other test in this file still calls the {@code pollXxx()}
 * methods directly and never touches either. For all tests, {@code positionGroupLinked}/{@code healthOverviewLinked}
 * start at their Java default ({@code false}) and are set up per test via {@link
 * TractiveTrackerHandler#channelLinked}/{@code channelUnlinked} or direct reflection, whichever more directly
 * isolates the behavior under test. {@code trackerDetailsGuard}'s {@code minIntervalMs} is reflectively zeroed so
 * consecutive calls within one test aren't blocked by its cooldown.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveTrackerHandlerPollGatingTest {

    private static final ThingUID THING_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_DOG6, "Samson");
    private static final String SAMSON_TRACKER_ID = "HBDYUFSC";

    @Mock
    private @NonNullByDefault({}) Thing thing;
    @Mock
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    @Mock
    private @NonNullByDefault({}) TractiveAccountHandler bridge;
    @Mock
    private @NonNullByDefault({}) Bridge bridgeThing;
    @Mock
    private @NonNullByDefault({}) HttpClient httpClient;
    @Mock
    private @NonNullByDefault({}) Request request;
    @Mock
    private @NonNullByDefault({}) ContentResponse response;
    @Mock
    private @NonNullByDefault({}) ScheduledExecutorService mockScheduler;
    private @NonNullByDefault({}) TractiveDog6Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(thing.getUID()).thenReturn(THING_UID);
        lenient().when(bridge.getHttpClient()).thenReturn(httpClient);
        lenient().when(bridge.getGraphApiRateLimitBucket()).thenReturn(new SharedRateLimitBucket(100, 0.0));
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.GET)).thenReturn(request);
        lenient().when(bridge.addAuthHeaders(request)).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);
        lenient().when(bridgeThing.getHandler()).thenReturn(bridge);
        lenient().when(thing.getBridgeUID())
                .thenReturn(new ThingUID(TractiveBindingConstants.THING_TYPE_ACCOUNT, "gert"));
        lenient().when(callback.getBridge(any())).thenReturn(bridgeThing);
        lenient().when(bridge.getThing()).thenReturn(bridgeThing);
        lenient().when(bridgeThing.getStatus()).thenReturn(ThingStatus.ONLINE);
        @NonNullByDefault({})
        Map<String, Object> configProperties = new HashMap<>();
        configProperties.put("trackerId", SAMSON_TRACKER_ID);
        configProperties.put("trackedPetId", "somePetId");
        lenient().when(thing.getConfiguration()).thenReturn(new Configuration(configProperties));
        lenient().when(mockScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        handler = new TractiveDog6Handler(thing) {
            {
                trackerId = SAMSON_TRACKER_ID;
            }
        };
        handler.setCallback(callback);

        Field schedulerField = BaseThingHandler.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(handler, mockScheduler);

        Field guardField = TractiveTrackerHandler.class.getDeclaredField("trackerDetailsGuard");
        guardField.setAccessible(true);
        PollGuard<?> guard = (PollGuard<?>) Objects.requireNonNull(guardField.get(handler));
        guard.setMinIntervalMs(0);
    }

    @Test
    void pollTrackerDetailsSkipsHttpCallWhenNothingLinked() {
        when(callback.isChannelLinked(any())).thenReturn(false);

        handler.pollTrackerDetails(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void pollTrackerDetailsMakesHttpCallWhenModelNumberIsLinked() {
        lenient()
                .when(callback
                        .isChannelLinked(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_MODEL_NUMBER))))
                .thenReturn(true);
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"model_number\":\"TG6C\"}");

        handler.pollTrackerDetails(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void trackerStatusFieldsAreSeededOnFirstSuccessfulPoll() {
        when(callback.isChannelLinked(any())).thenReturn(true);
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{\"state\":\"OPERATIONAL\",\"model_number\":\"TG6C\"}");

        handler.pollTrackerDetails(bridge);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_TRACKER_STATE)),
                eq(new StringType("OPERATIONAL")));
    }

    @Test
    void trackerStatusFieldsAreNotRewrittenOnSecondPollButDeviceInfoIs() {
        when(callback.isChannelLinked(any())).thenReturn(true);
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        // Each successful GET reads getContentAsString() twice -- once for executeGet()'s trace log, once for
        // getJson()'s actual parse -- so each poll's JSON needs to be queued twice, not once.
        when(response.getContentAsString()).thenReturn("{\"state\":\"OPERATIONAL\",\"model_number\":\"TG6C\"}")
                .thenReturn("{\"state\":\"OPERATIONAL\",\"model_number\":\"TG6C\"}")
                .thenReturn("{\"state\":\"POWER_SAVING\",\"model_number\":\"TG6D\"}")
                .thenReturn("{\"state\":\"POWER_SAVING\",\"model_number\":\"TG6D\"}");

        handler.pollTrackerDetails(bridge);
        handler.pollTrackerDetails(bridge);

        verify(callback, times(1)).stateUpdated(
                eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_TRACKER_STATE)),
                eq(new StringType("OPERATIONAL")));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_MODEL_NUMBER)),
                eq(new StringType("TG6C")));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_MODEL_NUMBER)),
                eq(new StringType("TG6D")));
    }

    @Test
    void pollHwReportSkipsHttpCallWhenBatteryLevelNotLinked() {
        when(callback.isChannelLinked(any())).thenReturn(false);

        handler.pollHwReport(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void pollHwReportMakesHttpCallWhenBatteryLevelIsLinked() {
        lenient()
                .when(callback
                        .isChannelLinked(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BATTERY_LEVEL))))
                .thenReturn(true);
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.pollHwReport(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void pollPositionReportSkipsHttpCallByDefaultBeforeAnyChannelIsLinked() {
        handler.pollPositionReport(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void channelLinkedEnablesPositionPolling() {
        handler.channelLinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION));
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.pollPositionReport(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void channelUnlinkedRecheckKeepsPositionPollingEnabledWhenSiblingStillLinked() throws Exception {
        Field field = TractiveTrackerHandler.class.getDeclaredField("positionGroupLinked");
        field.setAccessible(true);
        field.set(handler, true);
        lenient().when(callback.isChannelLinked(eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_SPEED))))
                .thenReturn(true);

        handler.channelUnlinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION));

        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");
        handler.pollPositionReport(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void channelUnlinkedDisablesPositionPollingWhenNoChannelRemainsLinked() throws Exception {
        Field field = TractiveTrackerHandler.class.getDeclaredField("positionGroupLinked");
        field.setAccessible(true);
        field.set(handler, true);

        handler.channelUnlinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_LOCATION));
        handler.pollPositionReport(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void pollHealthOverviewSkipsHttpCallByDefaultBeforeAnyChannelIsLinked() {
        handler.pollHealthOverview(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void channelLinkedEnablesHealthPolling() {
        handler.channelLinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BARK));
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.pollHealthOverview(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void channelUnlinkedRecheckKeepsHealthPollingEnabledWhenSiblingStillLinked() throws Exception {
        Field field = TractiveTrackerHandler.class.getDeclaredField("healthOverviewLinked");
        field.setAccessible(true);
        field.set(handler, true);
        lenient()
                .when(callback.isChannelLinked(
                        eq(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_ACTIVITY_RECORDED))))
                .thenReturn(true);

        handler.channelUnlinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BARK));

        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");
        handler.pollHealthOverview(bridge);

        verify(httpClient).newRequest(anyString());
    }

    @Test
    void channelUnlinkedDisablesHealthPollingWhenNoChannelRemainsLinked() throws Exception {
        Field field = TractiveTrackerHandler.class.getDeclaredField("healthOverviewLinked");
        field.setAccessible(true);
        field.set(handler, true);

        handler.channelUnlinked(new ChannelUID(THING_UID, TractiveBindingConstants.CHANNEL_BARK));
        handler.pollHealthOverview(bridge);

        verifyNoInteractions(httpClient);
    }

    @Test
    void initializeSkipsPollAndStaysNotOnlineWhenBridgeHasNoAccessToken() throws Exception {
        // bridge.getAccessToken() is left unstubbed -- Mockito's default for an unstubbed String-returning
        // method is null, exactly the "bridge hasn't authenticated yet" case the guard checks for.
        handler.initialize();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();

        verifyNoInteractions(httpClient);
        verify(callback, never()).statusUpdated(eq(thing), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    @Test
    void initializePollsAndGoesOnlineWhenBridgeHasAccessToken() throws Exception {
        when(bridge.getAccessToken()).thenReturn("some-token");
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.initialize();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();

        // Only pollProfile() is unconditional (no isLinked() gate) -- with nothing linked in this test, it's
        // the one call that proves the guarded block actually ran.
        verify(httpClient).newRequest(anyString());
        verify(callback).statusUpdated(eq(thing), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    @Test
    void bridgeStatusChangedOfflineSetsThingOfflineWithBridgeOfflineDetail() {
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));

        verify(callback).statusUpdated(eq(thing), argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                && info.getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE));
    }

    @Test
    void bridgeStatusChangedUnknownAlsoSetsThingOfflineWithBridgeOfflineDetail() {
        // Before the if(ONLINE){...}else{...} restructure, UNKNOWN fell through both branches silently.
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.UNKNOWN, ThingStatusDetail.NOT_YET_READY, null));

        verify(callback).statusUpdated(eq(thing), argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                && info.getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE));
    }

    @Test
    void bridgeStatusChangedOnlinePollsAndSetsThingOnline() throws Exception {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));

        verify(callback).statusUpdated(eq(thing), argThat(info -> info.getStatus() == ThingStatus.UNKNOWN
                && info.getStatusDetail() == ThingStatusDetail.NOT_YET_READY));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();

        verify(httpClient).newRequest(anyString());
        verify(callback).statusUpdated(eq(thing), argThat(
                info -> info.getStatus() == ThingStatus.ONLINE && info.getStatusDetail() == ThingStatusDetail.NONE));
    }
}
