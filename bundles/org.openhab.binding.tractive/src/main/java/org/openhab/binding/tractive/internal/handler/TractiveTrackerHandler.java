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

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.tractive.internal.channel.TractiveEventListener;
import org.openhab.binding.tractive.internal.config.TractiveTrackerConfiguration;
import org.openhab.binding.tractive.internal.util.PollGuard;
import org.openhab.binding.tractive.internal.util.TractiveRetryUtil;
import org.openhab.binding.tractive.internal.util.TractiveTaskTracker;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Abstract base class for all Tractive tracker thing handlers. Provides shared
 * authentication, REST polling, channel event handling, and state-update
 * helpers. Concrete subclasses implement the model-specific {@link #handleCommand}
 * and {@link #applyHealthOverview} behaviour.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public abstract class TractiveTrackerHandler extends BaseThingHandler implements TractiveEventListener {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final Gson gson = new Gson();
    protected final TractiveTaskTracker taskTracker = new TractiveTaskTracker();
    protected String trackerId = "";
    protected String trackedPetId = "";

    private static final long MIN_POLL_INTERVAL_MS = 5_000;

    private final PollGuard trackerDetailsGuard = new PollGuard(MIN_POLL_INTERVAL_MS);
    private final PollGuard hwReportGuard = new PollGuard(MIN_POLL_INTERVAL_MS);
    private final PollGuard positionReportGuard = new PollGuard(MIN_POLL_INTERVAL_MS);
    private final PollGuard healthOverviewGuard = new PollGuard(MIN_POLL_INTERVAL_MS);

    /**
     * Creates a new tracker handler for the given thing.
     */
    protected TractiveTrackerHandler(Thing thing) {
        super(thing);
    }

    @Override
    public Set<String> getTargetIds() {
        return Set.of(trackerId, trackedPetId);
    }

    @Override
    public void onChannelEvent(String messageType, JsonObject event) {
        logger.trace("Channel event: messageType={}", messageType);
        switch (messageType) {
            case MESSAGE_TRACKER_STATUS:
                updateStringChannel(CHANNEL_TRACKER_STATE, event, FIELD_TRACKER_STATE_LIVE);
                updateStringChannel(CHANNEL_CHARGING_STATE, event, FIELD_CHARGING_STATE);
                updateStringChannel(CHANNEL_BATTERY_STATE, event, FIELD_BATTERY_STATE);
                if (event.has(FIELD_POSITION) && event.get(FIELD_POSITION).isJsonObject()) {
                    applyPositionReport(event.get(FIELD_POSITION).getAsJsonObject());
                }
                if (event.has(FIELD_HARDWARE) && event.get(FIELD_HARDWARE).isJsonObject()) {
                    applyHwReport(event.get(FIELD_HARDWARE).getAsJsonObject());
                }
                break;
            case MESSAGE_HEALTH_OVERVIEW:
                if (event.has(FIELD_CONTENT) && event.get(FIELD_CONTENT).isJsonObject()) {
                    applyHealthOverview(event.get(FIELD_CONTENT).getAsJsonObject());
                }
                break;
            default:
                logger.debug("Ignoring channel event message={}", messageType);
                break;
        }
        if (isLinked(CHANNEL_LAST_CONTACT)) {
            updateState(CHANNEL_LAST_CONTACT, new DateTimeType());
        }
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void initialize() {
        TractiveTrackerConfiguration config = getConfigAs(TractiveTrackerConfiguration.class);
        trackerId = config.trackerId;
        trackedPetId = config.trackedPetId;

        if (trackerId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Tracker ID must be configured");
            return;
        }
        if (trackedPetId.isBlank()) {
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

        taskTracker.track(scheduler.schedule(() -> {
            try {
                pollAll();
                updateStatus(ThingStatus.ONLINE);
                if (config.refreshInterval > 0) {
                    taskTracker.track(scheduler.scheduleWithFixedDelay(this::pollAll, config.refreshInterval,
                            config.refreshInterval, TimeUnit.SECONDS));
                }
            } catch (RuntimeException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Unexpected error during initialization: " + e.getMessage());
            }
        }, 0, TimeUnit.SECONDS));
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            updateStatus(ThingStatus.UNKNOWN);
            taskTracker.track(scheduler.schedule(() -> {
                try {
                    pollAll();
                    updateStatus(ThingStatus.ONLINE);
                } catch (RuntimeException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Unexpected error during refresh: " + e.getMessage());
                }
            }, 0, TimeUnit.SECONDS));
        }
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

    /**
     * Returns the parent bridge cast to {@link TractiveAccountHandler}, or {@code null} if the
     * bridge is absent or of an unexpected type.
     */
    protected @Nullable TractiveAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof TractiveAccountHandler handler) {
            return handler;
        }
        return null;
    }

    /**
     * Performs an authenticated GET and returns the parsed JSON body, or {@code null} on any
     * HTTP or parse error. Retries automatically on HTTP 429.
     */
    protected @Nullable JsonObject getJson(TractiveAccountHandler bridge, String url) {
        ContentResponse response = executeGet(bridge, url);
        if (response == null) {
            return null;
        }
        return gson.fromJson(response.getContentAsString(), JsonObject.class);
    }

    /**
     * Sends a tracker command. Tractive commands use GET, not POST.
     * Failures are logged at DEBUG and silently swallowed; commands are best-effort.
     */
    protected void sendCommand(HttpClient httpClient, TractiveAccountHandler bridge, String commandName, String state) {
        String url = API_BASE_URL + "tracker/" + trackerId + "/command/" + commandName + "/" + state;
        ContentResponse response = sendGetWithReauth(bridge, httpClient, url, "Command " + commandName + "/" + state);
        if (response == null) {
            return;
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            logger.debug("Command {}/{} returned HTTP {}", commandName, state, response.getStatus());
        } else {
            logger.trace("Command {}/{} → {}", commandName, state, response.getContentAsString());
            if (isLinked(CHANNEL_LAST_CONTACT)) {
                updateState(CHANNEL_LAST_CONTACT, new DateTimeType());
            }
        }
    }

    /**
     * Triggers an immediate health overview refresh outside the polling schedule.
     * Intended to be called from the tracker model's {@code ThingActions} implementation (e.g.
     * {@link org.openhab.binding.tractive.internal.action.TractiveDog6Actions} for the Dog 6).
     */
    public void refreshHealthOverview() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollHealthOverview(bridge);
        }
    }

    /**
     * Triggers an immediate tracker details and hardware report refresh outside the polling schedule.
     * Intended to be called from the tracker model's {@code ThingActions} implementation (e.g.
     * {@link org.openhab.binding.tractive.internal.action.TractiveDog6Actions} for the Dog 6).
     */
    public void refreshHardware() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollTrackerDetails(bridge);
            pollHwReport(bridge);
        }
    }

    /**
     * Fetches historical positions for this tracker between two instants.
     *
     * @return the raw JSON array string, or {@code null} on any HTTP or parse error
     */
    public @Nullable String fetchPositions(ZonedDateTime from, ZonedDateTime to) {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null) {
            return null;
        }
        String url = API_BASE_URL + "tracker/" + trackerId + "/positions?time_from=" + from.toEpochSecond()
                + "&time_to=" + to.toEpochSecond() + "&format=json";
        ContentResponse response = executeGet(bridge, url);
        return response != null ? response.getContentAsString() : null;
    }

    /**
     * Polls all four REST endpoints and updates the corresponding channels.
     */
    protected void pollAll() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null || bridge.getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        pollTrackerDetails(bridge);
        pollHwReport(bridge);
        pollPositionReport(bridge);
        pollHealthOverview(bridge);
    }

    /**
     * Polls tracker details; resolves the bridge internally.
     */
    protected void pollTrackerDetails() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null && bridge.getThing().getStatus() == ThingStatus.ONLINE) {
            pollTrackerDetails(bridge);
        }
    }

    /**
     * Polls tracker details and updates the hardware state channels.
     */
    protected void pollTrackerDetails(TractiveAccountHandler bridge) {
        if (!trackerDetailsGuard.tryAcquire()) {
            logger.trace("Skipping tracker details poll: too soon or already in progress");
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "tracker/" + trackerId);
            if (json == null) {
                return;
            }
            updateStringChannel(CHANNEL_TRACKER_STATE, json, FIELD_TRACKER_STATE);
            updateStringChannel(CHANNEL_CHARGING_STATE, json, FIELD_CHARGING_STATE);
            updateStringChannel(CHANNEL_BATTERY_STATE, json, FIELD_BATTERY_STATE);
        } finally {
            trackerDetailsGuard.release();
        }
    }

    /**
     * Polls the hardware report and updates the battery level channel.
     */
    protected void pollHwReport(TractiveAccountHandler bridge) {
        if (!hwReportGuard.tryAcquire()) {
            logger.trace("Skipping hw report poll: too soon or already in progress");
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "device_hw_report/" + trackerId + "/");
            if (json != null) {
                applyHwReport(json);
            }
        } finally {
            hwReportGuard.release();
        }
    }

    /**
     * Polls the position report and updates the position channel group.
     */
    protected void pollPositionReport(TractiveAccountHandler bridge) {
        if (!positionReportGuard.tryAcquire()) {
            logger.trace("Skipping position report poll: too soon or already in progress");
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "device_pos_report/" + trackerId);
            if (json != null) {
                applyPositionReport(json);
            }
        } finally {
            positionReportGuard.release();
        }
    }

    /**
     * Polls the health overview from the APS API and delegates to {@link #applyHealthOverview}.
     */
    protected void pollHealthOverview(TractiveAccountHandler bridge) {
        if (!healthOverviewGuard.tryAcquire()) {
            logger.trace("Skipping health overview poll: too soon or already in progress");
            return;
        }
        try {
            JsonObject json = getJson(bridge, APS_BASE_URL + "pet/" + trackedPetId + "/health/overview");
            if (json != null) {
                applyHealthOverview(json);
            }
        } finally {
            healthOverviewGuard.release();
        }
    }

    /**
     * Applies a health overview payload to the thing's channels. Called from both the REST poll
     * path and the real-time channel path (channel events unwrap the {@code data} key before calling).
     *
     * The data points in this overview may differ from tracker type to tracker type.
     *
     * @param json the health overview payload, already unwrapped
     */
    protected abstract void applyHealthOverview(JsonObject json);

    /**
     * Applies a {@code device_pos_report} payload to the position channel group.
     */
    protected void applyPositionReport(JsonObject json) {
        logger.trace("Applying position report: {}", json);
        if (json.has(FIELD_LATLONG)) {
            JsonArray ll = json.get(FIELD_LATLONG).getAsJsonArray();
            if (ll.size() == 2) {
                DecimalType lat = new DecimalType(ll.get(0).getAsDouble());
                DecimalType lon = new DecimalType(ll.get(1).getAsDouble());
                PointType point;
                if (json.has(FIELD_ALTITUDE) && !json.get(FIELD_ALTITUDE).isJsonNull()) {
                    point = new PointType(lat, lon, new DecimalType(json.get(FIELD_ALTITUDE).getAsDouble()));
                } else {
                    point = new PointType(lat, lon);
                }
                if (isLinked(CHANNEL_LOCATION)) {
                    updateState(CHANNEL_LOCATION, point);
                }
                updateEpochChannel(CHANNEL_LAST_POSITION_TIME, json, FIELD_TIME);
            }
        }
        if (json.has(FIELD_SPEED)) {
            JsonElement speedEl = json.get(FIELD_SPEED);
            if (speedEl.isJsonNull()) {
                logger.trace("speed `null`, treating as 0 m/s");
                if (isLinked(CHANNEL_SPEED)) {
                    updateState(CHANNEL_SPEED, new QuantityType<>(0, Units.METRE_PER_SECOND));
                }
            } else {
                if (isLinked(CHANNEL_SPEED)) {
                    updateState(CHANNEL_SPEED, new QuantityType<>(speedEl.getAsDouble(), Units.METRE_PER_SECOND));
                }
            }
        }
        updateStringChannel(CHANNEL_SENSOR_USED, json, FIELD_SENSOR_USED);
        String uncertaintyField = json.has(FIELD_POS_UNCERTAINTY) ? FIELD_POS_UNCERTAINTY : FIELD_ACCURACY;
        if (json.has(uncertaintyField) && !json.get(uncertaintyField).isJsonNull()
                && isLinked(CHANNEL_POSITION_ACCURACY)) {
            updateState(CHANNEL_POSITION_ACCURACY,
                    new QuantityType<>(json.get(uncertaintyField).getAsDouble(), SIUnits.METRE));
        }
    }

    /**
     * Applies a {@code device_hw_report} payload to the hardware channel group.
     */
    protected void applyHwReport(JsonObject json) {
        logger.trace("Applying hw report: {}", json);
        if (json.has(FIELD_BATTERY_LEVEL) && !json.get(FIELD_BATTERY_LEVEL).isJsonNull()
                && isLinked(CHANNEL_BATTERY_LEVEL)) {
            updateState(CHANNEL_BATTERY_LEVEL, new DecimalType(json.get(FIELD_BATTERY_LEVEL).getAsInt()));
        }
    }

    /**
     * Triggers an immediate position report refresh outside the polling schedule.
     * Intended to be called from the tracker model's {@code ThingActions} implementation (e.g.
     * {@link org.openhab.binding.tractive.internal.action.TractiveDog6Actions} for the Dog 6).
     */
    public void refreshPosition() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollPositionReport(bridge);
        }
    }

    /**
     * Updates a String channel from a JSON field; no-ops if the field is absent or JSON null.
     */
    protected void updateStringChannel(String channelId, JsonObject json, String field) {
        if (isLinked(channelId)) {
            JsonElement el = json.get(field);
            if (el != null && !el.isJsonNull()) {
                updateState(channelId, new StringType(el.getAsString()));
            }
        }
    }

    /**
     * Updates a DateTime channel from a Unix epoch integer JSON field.
     */
    protected void updateEpochChannel(String channelId, JsonObject json, String field) {
        if (isLinked(channelId)) {
            JsonElement el = json.get(field);
            if (el != null && !el.isJsonNull()) {
                ZonedDateTime zdt = Instant.ofEpochSecond(el.getAsLong()).atZone(ZoneId.systemDefault());
                updateState(channelId, new DateTimeType(zdt));
            }
        }
    }

    /**
     * Updates a {@link Units#MINUTE} quantity channel from an integer JSON field.
     */
    protected void updateIntMinutesChannel(String channelId, JsonObject json, String field) {
        if (isLinked(channelId)) {
            JsonElement el = json.get(field);
            if (el != null && !el.isJsonNull()) {
                updateState(channelId, new QuantityType<>(el.getAsInt(), Units.MINUTE));
            }
        }
    }

    /**
     * Updates a DateTime channel from a field that may be either a Unix epoch (long) or an
     * ISO 8601 string. The REST health endpoint returns epoch; the channel event returns ISO 8601.
     */
    protected void updateTimestampChannel(String channelId, JsonObject json, String field) {
        if (isLinked(channelId)) {
            JsonElement el = json.get(field);
            if (el != null && !el.isJsonNull()) {
                ZonedDateTime zdt;
                try {
                    zdt = Instant.parse(el.getAsString()).atZone(ZoneId.systemDefault());
                } catch (DateTimeParseException e) {
                    zdt = Instant.ofEpochSecond(el.getAsLong()).atZone(ZoneId.systemDefault());
                }
                updateState(channelId, new DateTimeType(zdt));
            }
        }
    }

    /**
     * Sends an authenticated GET request, retrying once with a fresh token if the first attempt
     * returns HTTP 401. Returns {@code null} if the request could not be completed at all
     * (interrupted, or failed after retries) — callers still need to check the response status.
     */
    private @Nullable ContentResponse sendGetWithReauth(TractiveAccountHandler bridge, HttpClient httpClient,
            String url, String logContext) {
        String tokenSnapshot = bridge.getAccessToken();
        try {
            ContentResponse response = TractiveRetryUtil
                    .sendWithRetry(() -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)),
                            scheduler, logger)
                    .get();
            if (response.getStatus() == HttpStatus.UNAUTHORIZED_401) {
                logger.debug("{} returned 401, triggering re-auth", logContext);
                bridge.refreshToken(tokenSnapshot);
                response = TractiveRetryUtil
                        .sendWithRetry(() -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)),
                                scheduler, logger)
                        .get();
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            logger.debug("{} failed: {}", logContext, e.getMessage());
            return null;
        }
    }

    private @Nullable ContentResponse executeGet(TractiveAccountHandler bridge, String url) {
        ContentResponse response = sendGetWithReauth(bridge, bridge.getHttpClient(), url, "GET " + url);
        if (response == null) {
            return null;
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            logger.debug("GET {} returned HTTP {}", url, response.getStatus());
            return null;
        }
        logger.trace("GET {} → {}", url, response.getContentAsString());
        if (isLinked(CHANNEL_LAST_CONTACT)) {
            updateState(CHANNEL_LAST_CONTACT, new DateTimeType());
        }
        return response;
    }
}
