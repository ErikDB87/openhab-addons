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
package org.openhab.binding.tractive.internal;

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link TractiveDog6Handler} handles a single Tractive Dog 6 tracker thing.
 * It registers with the account bridge for real-time channel events and polls
 * four REST endpoints on a configurable interval.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveDog6Handler extends BaseThingHandler implements TractiveEventListener {

    private final Logger logger = LoggerFactory.getLogger(TractiveDog6Handler.class);
    private final Gson gson = new Gson();
    private final TractiveTaskTracker taskTracker = new TractiveTaskTracker();

    private String trackerId = "";
    private String trackableId = "";

    public TractiveDog6Handler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        TractiveDog6Configuration config = getConfigAs(TractiveDog6Configuration.class);
        trackerId = config.trackerId;
        trackableId = config.trackableId;

        if (trackerId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Tracker ID must be configured");
            return;
        }
        if (trackableId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Pet/trackable ID must be configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED, "No bridge configured");
            return;
        }
        bridge.registerListener(this);

        scheduler.execute(() -> {
            pollAll();
            updateStatus(ThingStatus.ONLINE);
            if (config.refreshInterval > 0) {
                taskTracker.track(scheduler.scheduleWithFixedDelay(this::pollTrackerDetails, config.refreshInterval,
                        config.refreshInterval, TimeUnit.SECONDS));
            }
        });
    }

    @Override
    public void dispose() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            bridge.unregisterListener(this);
        }
        taskTracker.cancelAll();
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            updateStatus(ThingStatus.UNKNOWN);
            scheduler.execute(this::pollAll);
            updateStatus(ThingStatus.ONLINE);
        }
    }

    // ---- TractiveEventListener ----

    @Override
    public Set<String> getTargetIds() {
        return Set.of(trackerId, trackableId);
    }

    @Override
    public void onChannelEvent(String messageType, JsonObject event) {
        String type = event.has("_type") ? event.get("_type").getAsString() : "";
        switch (type) {
            case "device_pos_report":
                applyPositionReport(event);
                break;
            case "device_hw_report":
                applyHwReport(event);
                break;
            default:
                if (messageType.startsWith("trackable_object.health_overview")) {
                    applyHealthOverview(event);
                } else {
                    logger.debug("Ignoring channel event type={} message={}", type, messageType);
                }
                break;
        }
        updateStatus(ThingStatus.ONLINE);
    }

    // ---- handleCommand ----

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            scheduler.execute(this::pollAll);
            return;
        }

        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null) {
            return;
        }
        HttpClient httpClient = bridge.getHttpClient();

        switch (channelUID.getId()) {
            case CHANNEL_BUZZER:
                sendCommand(httpClient, bridge, "buzzer_control", command == OnOffType.ON ? "on" : "off");
                break;
            case CHANNEL_LED:
                sendCommand(httpClient, bridge, "led_control", command == OnOffType.ON ? "on" : "off");
                break;
            case CHANNEL_LIVE_TRACKING:
                sendCommand(httpClient, bridge, "live_tracking", command == OnOffType.ON ? "on" : "off");
                break;
            default:
                logger.debug("Ignoring command for read-only channel {}", channelUID.getId());
                break;
        }
    }

    private void sendCommand(HttpClient httpClient, TractiveAccountHandler bridge, String commandName, String state) {
        String url = API_BASE_URL + "tracker/" + trackerId + "/command/" + commandName + "/" + state;
        try {
            ContentResponse response = TractiveRetryUtil.sendWithRetry(
                    () -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)), logger);
            if (response.getStatus() != HttpStatus.OK_200) {
                logger.debug("Command {}/{} returned HTTP {}", commandName, state, response.getStatus());
            }
        } catch (Exception e) {
            logger.debug("Failed to send command {}/{}: {}", commandName, state, e.getMessage());
        }
    }

    // ---- Polling ----

    /** Public so TractiveDog6Actions can trigger individual polls on demand. */
    public void refreshPosition() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollPositionReport(bridge);
        }
    }

    public void refreshHealthOverview() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollHealthOverview(bridge);
        }
    }

    private void pollAll() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null || bridge.getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        pollTrackerDetails(bridge);
        pollHwReport(bridge);
        pollPositionReport(bridge);
        pollHealthOverview(bridge);
    }

    private void pollTrackerDetails() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null && bridge.getThing().getStatus() == ThingStatus.ONLINE) {
            pollTrackerDetails(bridge);
        }
    }

    private void pollTrackerDetails(TractiveAccountHandler bridge) {
        JsonObject json = getJson(bridge, API_BASE_URL + "tracker/" + trackerId);
        if (json == null) {
            return;
        }
        updateStringChannel(CHANNEL_TRACKER_STATE, json, "state");
        updateStringChannel(CHANNEL_CHARGING_STATE, json, "charging_state");
        updateStringChannel(CHANNEL_BATTERY_STATE, json, "battery_state");
    }

    private void pollHwReport(TractiveAccountHandler bridge) {
        JsonObject json = getJson(bridge, API_BASE_URL + "device_hw_report/" + trackerId + "/");
        if (json != null) {
            applyHwReport(json);
        }
    }

    private void pollPositionReport(TractiveAccountHandler bridge) {
        JsonObject json = getJson(bridge, API_BASE_URL + "device_pos_report/" + trackerId);
        if (json != null) {
            applyPositionReport(json);
        }
    }

    private void pollHealthOverview(TractiveAccountHandler bridge) {
        JsonObject json = getJson(bridge, APS_BASE_URL + "pet/" + trackableId + "/health/overview");
        if (json != null) {
            applyHealthOverview(json);
        }
    }

    private @Nullable JsonObject getJson(TractiveAccountHandler bridge, String url) {
        try {
            HttpClient httpClient = bridge.getHttpClient();
            ContentResponse response = TractiveRetryUtil.sendWithRetry(
                    () -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)), logger);
            if (response.getStatus() != HttpStatus.OK_200) {
                logger.debug("GET {} returned HTTP {}", url, response.getStatus());
                return null;
            }
            return gson.fromJson(response.getContentAsString(), JsonObject.class);
        } catch (Exception e) {
            logger.debug("GET {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    // ---- State application ----

    private void applyPositionReport(JsonObject json) {
        if (json.has("latlong")) {
            JsonArray ll = json.get("latlong").getAsJsonArray();
            if (ll.size() == 2) {
                double lat = ll.get(0).getAsDouble();
                double lon = ll.get(1).getAsDouble();
                updateState(CHANNEL_LOCATION, new PointType(lat + "," + lon));
            }
        }
        updateEpochChannel(CHANNEL_LAST_POSITION_TIME, json, "time");

        if (json.has("speed") && !json.get("speed").isJsonNull()) {
            double speed = json.get("speed").getAsDouble();
            updateState(CHANNEL_SPEED, new QuantityType<>(speed, Units.METRE_PER_SECOND));
        }
        if (json.has("altitude") && !json.get("altitude").isJsonNull()) {
            updateState(CHANNEL_ALTITUDE, new QuantityType<>(json.get("altitude").getAsDouble(), SIUnits.METRE));
        }
        updateStringChannel(CHANNEL_SENSOR_USED, json, "sensor_used");
        if (json.has("pos_uncertainty") && !json.get("pos_uncertainty").isJsonNull()) {
            updateState(CHANNEL_POSITION_ACCURACY,
                    new QuantityType<>(json.get("pos_uncertainty").getAsDouble(), SIUnits.METRE));
        }
    }

    private void applyHwReport(JsonObject json) {
        if (json.has("battery_level") && !json.get("battery_level").isJsonNull()) {
            updateState(CHANNEL_BATTERY_LEVEL, new DecimalType(json.get("battery_level").getAsInt()));
        }
    }

    private void applyHealthOverview(JsonObject json) {
        if (json.has("activity")) {
            JsonObject activity = json.get("activity").getAsJsonObject();
            updateIntMinutesChannel(CHANNEL_ACTIVITY_MINUTES_ACTIVE, activity, "minutesActive");
            updateIntMinutesChannel(CHANNEL_ACTIVITY_MINUTES_GOAL, activity, "minutesGoal");
        }
        if (json.has("sleep")) {
            JsonObject sleep = json.get("sleep").getAsJsonObject();
            updateIntMinutesChannel(CHANNEL_SLEEP_MINUTES_DAY, sleep, "minutesDaySleep");
            updateIntMinutesChannel(CHANNEL_SLEEP_MINUTES_NIGHT, sleep, "minutesNightSleep");
            updateIntMinutesChannel(CHANNEL_SLEEP_MINUTES_CALM, sleep, "minutesCalm");
        }
        if (json.has("restingHeartRate")) {
            JsonObject rhr = json.get("restingHeartRate").getAsJsonObject();
            updateStringChannel(CHANNEL_RESTING_HEART_RATE_STATUS, rhr, "status");
        }
        if (json.has("restingRespiratoryRate")) {
            JsonObject rrr = json.get("restingRespiratoryRate").getAsJsonObject();
            updateStringChannel(CHANNEL_RESTING_RESPIRATORY_RATE_STATUS, rrr, "status");
        }
        if (json.has("healthAlerts")) {
            JsonObject alerts = json.get("healthAlerts").getAsJsonObject();
            if (alerts.has("unseenCount")) {
                updateState(CHANNEL_UNSEEN_HEALTH_ALERTS, new DecimalType(alerts.get("unseenCount").getAsInt()));
            }
        }
        updateEpochChannel(CHANNEL_ACTIVITY_SYNCED_AT, json, "activityDataSyncedAt");
    }

    // ---- Channel update helpers ----

    private void updateStringChannel(String channelId, JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el != null && !el.isJsonNull()) {
            updateState(channelId, new StringType(el.getAsString()));
        }
    }

    private void updateEpochChannel(String channelId, JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el != null && !el.isJsonNull()) {
            ZonedDateTime zdt = Instant.ofEpochSecond(el.getAsLong()).atZone(ZoneId.systemDefault());
            updateState(channelId, new DateTimeType(zdt));
        }
    }

    private void updateIntMinutesChannel(String channelId, JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el != null && !el.isJsonNull()) {
            updateState(channelId, new QuantityType<>(el.getAsInt(), Units.MINUTE));
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(TractiveDog6Actions.class);
    }

    // ---- Helpers ----

    private @Nullable TractiveAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof TractiveAccountHandler handler) {
            return handler;
        }
        return null;
    }
}
