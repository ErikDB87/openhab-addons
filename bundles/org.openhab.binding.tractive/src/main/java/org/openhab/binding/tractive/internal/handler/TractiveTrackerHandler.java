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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.tractive.internal.channel.TractiveEventListener;
import org.openhab.binding.tractive.internal.config.TractiveTrackerConfiguration;
import org.openhab.binding.tractive.internal.util.PollGuard;
import org.openhab.binding.tractive.internal.util.SharedRateLimitBucket;
import org.openhab.binding.tractive.internal.util.TractiveRetryUtil;
import org.openhab.binding.tractive.internal.util.TractiveTaskTracker;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
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

    private static final long DEFAULT_MIN_POLL_INTERVAL_MS = 10_000;

    private final PollGuard<JsonObject> trackerDetailsGuard = new PollGuard<>(DEFAULT_MIN_POLL_INTERVAL_MS);
    private final PollGuard<JsonObject> hwReportGuard = new PollGuard<>(DEFAULT_MIN_POLL_INTERVAL_MS);
    private final PollGuard<JsonObject> positionReportGuard = new PollGuard<>(DEFAULT_MIN_POLL_INTERVAL_MS);
    /**
     * Shares {@code guardIntervalMs} with the three {@code graph.tractive.com}-backed guards above in
     * {@link #initialize()}, but, unlike them, is never plugged into {@link #tryConsumeSharedBudget}:
     * {@link #pollHealthOverview(TractiveAccountHandler)} hits {@code aps-api.tractive.com}, a separate host with its
     * own separate rate-limit budget that {@link SharedRateLimitBucket} deliberately doesn't model. Measurements in
     * {@code doc/rate-limit-probes/README.md} suggest that host may be limited by concurrent in-flight requests rather
     * than sustained rate, unlike {@code graph.tractive.com}'s token-bucket behavior -- clean at every sequential
     * spacing tested (including roughly 10 calls/second sustained), but failing once requests became simultaneous.
     * Since this guard's own {@code IN_PROGRESS} check already prevents more than one in-flight request to this
     * endpoint at a time, sharing the poll interval with the other three guards is believed low-risk even without a
     * bucket of its own -- but that's a read of limited evidence, not a confirmed mechanism, and this host has no
     * {@link SharedRateLimitBucket} equivalent to fall back on if the read turns out wrong.
     */
    private final PollGuard<JsonObject> healthOverviewGuard = new PollGuard<>(DEFAULT_MIN_POLL_INTERVAL_MS);
    /**
     * Deliberately not re-tuned to {@code config.refreshInterval} in {@link #initialize()}, unlike its four siblings
     * above. {@link #pollProfile(TractiveAccountHandler)} is off the periodic poll schedule entirely: profile data
     * barely changes, so it's only fetched once at startup, via the {@code refreshProfile()} action, or on a
     * {@code REFRESH} command. Stays pinned to {@link #DEFAULT_MIN_POLL_INTERVAL_MS} unconditionally.
     */
    private final PollGuard<JsonObject> profileGuard = new PollGuard<>(DEFAULT_MIN_POLL_INTERVAL_MS);

    private volatile boolean powerSaving = false;
    private final Map<String, ScheduledFuture<?>> autoOffTasks = new ConcurrentHashMap<>();

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
                updatePowerSavingFlag(event, FIELD_TRACKER_STATE_REASON);
                updateStringChannel(CHANNEL_TRACKER_STATE_REASON, event, FIELD_TRACKER_STATE_REASON);
                applyControlState(CHANNEL_LED, event, COMMAND_LED_CONTROL);
                applyControlState(CHANNEL_BUZZER, event, COMMAND_BUZZER_CONTROL);
                updateControlTiming(CHANNEL_LED_TIMEOUT, CHANNEL_LED_REMAINING, event, COMMAND_LED_CONTROL);
                updateControlTiming(CHANNEL_BUZZER_TIMEOUT, CHANNEL_BUZZER_REMAINING, event, COMMAND_BUZZER_CONTROL);
                scheduleOrCancelBuzzerAutoOff(event);
                applyControlState(CHANNEL_LIVE_TRACKING, event, COMMAND_LIVE_TRACKING);
                updateControlTiming(CHANNEL_LIVE_TRACKING_TIMEOUT, CHANNEL_LIVE_TRACKING_REMAINING, event,
                        COMMAND_LIVE_TRACKING);
                updateStringChannel(CHANNEL_TRACKER_STATE, event, FIELD_TRACKER_STATE_LIVE);
                updateStringChannel(CHANNEL_CHARGING_STATE, event, FIELD_CHARGING_STATE);
                updateStringChannel(CHANNEL_BATTERY_STATE, event, FIELD_BATTERY_STATE);
                applyPowerSavingZoneId(event);
                applyPrioritizedZone(event);
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
            case MESSAGE_START_FAILED:
                applyStartFailed(event);
                break;
            case MESSAGE_COMMAND_CONFIRMED:
            case MESSAGE_ACTIVITY_DATA_UPDATED:
                logger.trace("Ignoring known no-op channel event message={}", messageType);
                break;
            default:
                logger.debug("Ignoring unrecognized channel event message={}", messageType);
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
        trackerId = config.trackerId.trim().toUpperCase(Locale.ROOT);
        trackedPetId = config.trackedPetId.trim();

        long guardIntervalMs = config.refreshInterval > 0 ? config.refreshInterval * 1000L
                : DEFAULT_MIN_POLL_INTERVAL_MS;
        trackerDetailsGuard.setMinIntervalMs(guardIntervalMs);
        hwReportGuard.setMinIntervalMs(guardIntervalMs);
        positionReportGuard.setMinIntervalMs(guardIntervalMs);
        healthOverviewGuard.setMinIntervalMs(guardIntervalMs);

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
                pollProfile(bridge);
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
            TractiveAccountHandler bridge = getAccountHandler();
            taskTracker.track(scheduler.schedule(() -> {
                try {
                    pollAll();
                    if (bridge != null && profileGuard.getCached() == null) {
                        pollProfile(bridge);
                    }
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
     * Consumes a token from the shared {@code graph.tractive.com} rate-limit bucket if one is available, purely to keep
     * its internal accounting honest about traffic -- deliberately never gated on the result, since commands are
     * best-effort and shouldn't be silently dropped by the binding's bookkeeping.
     */
    protected void sendCommand(HttpClient httpClient, TractiveAccountHandler bridge, String commandName, String state) {
        bridge.getGraphApiRateLimitBucket().tryConsume();
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
     * Consumes a token from the shared {@code graph.tractive.com} rate-limit bucket if one is available, purely to keep
     * its internal accounting honest about traffic -- deliberately never gated on the result.
     *
     * @return the raw JSON array string, or {@code null} on any HTTP or parse error
     */
    public @Nullable String fetchPositions(ZonedDateTime from, ZonedDateTime to) {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null) {
            return null;
        }
        bridge.getGraphApiRateLimitBucket().tryConsume();
        String url = API_BASE_URL + "tracker/" + trackerId + "/positions?time_from=" + from.toEpochSecond()
                + "&time_to=" + to.toEpochSecond() + "&format=json";
        ContentResponse response = sendGetWithReauth(bridge, bridge.getHttpClient(), url, "fetchPositions");
        if (response == null) {
            logger.warn("fetchPositions({}, {}) could not be completed (see DEBUG log for the cause)", from, to);
            return null;
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            logger.warn("fetchPositions({}, {}) returned HTTP {}", from, to, response.getStatus());
            return null;
        }
        if (isLinked(CHANNEL_LAST_CONTACT)) {
            updateState(CHANNEL_LAST_CONTACT, new DateTimeType());
        }
        return response.getContentAsString();
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
     * Consumes one token from the bridge's shared {@code graph.tractive.com} rate-limit bucket (see
     * {@link SharedRateLimitBucket}). If none is available, releases {@code guard} (so a later attempt isn't blocked by
     * an in-progress/cooldown state for a call that never happened) and re-applies its cached response, if any -- the
     * same treatment as a {@link PollGuard.AcquireResult#COOLDOWN} skip, just triggered by the shared account-level
     * budget instead of this endpoint's own interval.
     *
     * @return {@code true} if a token was consumed and the caller should proceed with the actual HTTP call
     */
    private boolean tryConsumeSharedBudget(TractiveAccountHandler bridge, PollGuard<JsonObject> guard,
            Consumer<JsonObject> applyCached, String logContext) {
        if (bridge.getGraphApiRateLimitBucket().tryConsume()) {
            return true;
        }
        guard.release();
        JsonObject cached = guard.getCached();
        if (cached != null) {
            logger.debug("{} skipped (shared rate-limit bucket empty): re-applying cached response", logContext);
            applyCached.accept(cached);
        } else {
            logger.debug("{} skipped (shared rate-limit bucket empty), no cached response to re-apply", logContext);
        }
        return false;
    }

    /**
     * Polls tracker details and updates the hardware state channels. On {@link PollGuard.AcquireResult#COOLDOWN},
     * re-applies the cached response instead of doing nothing; on {@link PollGuard.AcquireResult#IN_PROGRESS}, a fresh
     * response is already in flight, so the cache is left untouched.
     */
    protected void pollTrackerDetails(TractiveAccountHandler bridge) {
        PollGuard.AcquireResult result = trackerDetailsGuard.tryAcquire();
        if (result == PollGuard.AcquireResult.COOLDOWN) {
            JsonObject cached = trackerDetailsGuard.getCached();
            if (cached != null) {
                logger.debug("Tracker details poll skipped (cooldown, cached response is {} s old): re-applying",
                        trackerDetailsGuard.getCacheAgeMs() / 1000);
                applyTrackerDetails(cached);
            }
            return;
        }
        if (result == PollGuard.AcquireResult.IN_PROGRESS) {
            logger.trace("Skipping tracker details poll: already in progress");
            return;
        }
        if (!tryConsumeSharedBudget(bridge, trackerDetailsGuard, this::applyTrackerDetails, "Tracker details poll")) {
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "tracker/" + trackerId);
            if (json == null) {
                return;
            }
            trackerDetailsGuard.setCached(json);
            applyTrackerDetails(json);
        } finally {
            trackerDetailsGuard.release();
        }
    }

    /**
     * Applies a {@code tracker/{trackerId}} payload to the Hardware channel group.
     */
    private void applyTrackerDetails(JsonObject json) {
        updateStringChannel(CHANNEL_TRACKER_STATE, json, FIELD_TRACKER_STATE);
        updateStringChannel(CHANNEL_CHARGING_STATE, json, FIELD_CHARGING_STATE);
        updateStringChannel(CHANNEL_BATTERY_STATE, json, FIELD_BATTERY_STATE);
        updateStringChannel(CHANNEL_TRACKER_STATE_REASON, json, FIELD_STATE_REASON);
        updateStringChannel(CHANNEL_MODEL_NUMBER, json, FIELD_MODEL_NUMBER);
        updateStringChannel(CHANNEL_HW_EDITION, json, FIELD_HW_EDITION);
        updateStringChannel(CHANNEL_FIRMWARE_VERSION, json, FIELD_FW_VERSION);
        updateStringChannel(CHANNEL_GEOFENCE_SENSITIVITY, json, FIELD_GEOFENCE_SENSITIVITY);
        updateStringChannel(CHANNEL_ZONE_ID, json, FIELD_PRIORITIZED_ZONE_ID);
        updateStringChannel(CHANNEL_ZONE_TYPE, json, FIELD_PRIORITIZED_ZONE_TYPE);
        updateEpochChannel(CHANNEL_ZONE_LAST_SEEN_AT, json, FIELD_PRIORITIZED_ZONE_LAST_SEEN_AT);
        updateEpochChannel(CHANNEL_ZONE_ENTERED_AT, json, FIELD_PRIORITIZED_ZONE_ENTERED_AT);
        updateStringChannel(CHANNEL_POWER_SAVING_ZONE_ID, json, FIELD_POWER_SAVING_ZONE_ID);
        updatePowerSavingFlag(json, FIELD_STATE_REASON);
    }

    /**
     * Polls the hardware report and updates the battery level channel. On {@link PollGuard.AcquireResult#COOLDOWN},
     * re-applies the cached response instead of doing nothing; on {@link PollGuard.AcquireResult#IN_PROGRESS}, a fresh
     * response is already in flight, so the cache is left untouched.
     */
    protected void pollHwReport(TractiveAccountHandler bridge) {
        PollGuard.AcquireResult result = hwReportGuard.tryAcquire();
        if (result == PollGuard.AcquireResult.COOLDOWN) {
            JsonObject cached = hwReportGuard.getCached();
            if (cached != null) {
                logger.debug("Hw report poll skipped (cooldown, cached response is {} s old): re-applying",
                        hwReportGuard.getCacheAgeMs() / 1000);
                applyHwReport(cached);
            }
            return;
        }
        if (result == PollGuard.AcquireResult.IN_PROGRESS) {
            logger.trace("Skipping hw report poll: already in progress");
            return;
        }
        if (!tryConsumeSharedBudget(bridge, hwReportGuard, this::applyHwReport, "Hw report poll")) {
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "device_hw_report/" + trackerId + "/");
            if (json != null) {
                hwReportGuard.setCached(json);
                applyHwReport(json);
            }
        } finally {
            hwReportGuard.release();
        }
    }

    /**
     * Polls the position report and updates the position channel group. On {@link PollGuard.AcquireResult#COOLDOWN},
     * re-applies the cached response instead of doing nothing; on {@link PollGuard.AcquireResult#IN_PROGRESS}, a fresh
     * response is already in flight, so the cache is left untouched.
     */
    protected void pollPositionReport(TractiveAccountHandler bridge) {
        PollGuard.AcquireResult result = positionReportGuard.tryAcquire();
        if (result == PollGuard.AcquireResult.COOLDOWN) {
            JsonObject cached = positionReportGuard.getCached();
            if (cached != null) {
                logger.debug("Position report poll skipped (cooldown, cached response is {} s old): re-applying",
                        positionReportGuard.getCacheAgeMs() / 1000);
                applyPositionReport(cached);
            }
            return;
        }
        if (result == PollGuard.AcquireResult.IN_PROGRESS) {
            logger.trace("Skipping position report poll: already in progress");
            return;
        }
        if (!tryConsumeSharedBudget(bridge, positionReportGuard, this::applyPositionReport, "Position report poll")) {
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "device_pos_report/" + trackerId);
            if (json != null) {
                positionReportGuard.setCached(json);
                applyPositionReport(json);
            }
        } finally {
            positionReportGuard.release();
        }
    }

    /**
     * Polls the health overview from the APS API and delegates to {@link #applyHealthOverview}. On
     * {@link PollGuard.AcquireResult#COOLDOWN}, re-applies the cached response instead of doing nothing; on
     * {@link PollGuard.AcquireResult#IN_PROGRESS}, a fresh response is already in flight, so the cache is left
     * untouched.
     */
    protected void pollHealthOverview(TractiveAccountHandler bridge) {
        PollGuard.AcquireResult result = healthOverviewGuard.tryAcquire();
        if (result == PollGuard.AcquireResult.COOLDOWN) {
            JsonObject cached = healthOverviewGuard.getCached();
            if (cached != null) {
                logger.debug("Health overview poll skipped (cooldown, cached response is {} s old): re-applying",
                        healthOverviewGuard.getCacheAgeMs() / 1000);
                applyHealthOverview(cached);
            }
            return;
        }
        if (result == PollGuard.AcquireResult.IN_PROGRESS) {
            logger.trace("Skipping health overview poll: already in progress");
            return;
        }
        try {
            JsonObject json = getJson(bridge, APS_BASE_URL + "pet/" + trackedPetId + "/health/overview");
            if (json != null) {
                healthOverviewGuard.setCached(json);
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
        if (isLinked(CHANNEL_POSITION_ACCURACY) && json.has(uncertaintyField)
                && !json.get(uncertaintyField).isJsonNull()) {
            updateState(CHANNEL_POSITION_ACCURACY,
                    new QuantityType<>(json.get(uncertaintyField).getAsDouble(), SIUnits.METRE));
        }
    }

    /**
     * Applies a {@code device_hw_report} payload to the hardware channel group.
     */
    protected void applyHwReport(JsonObject json) {
        logger.trace("Applying hw report: {}", json);
        if (isLinked(CHANNEL_BATTERY_LEVEL) && json.has(FIELD_BATTERY_LEVEL)
                && !json.get(FIELD_BATTERY_LEVEL).isJsonNull()) {
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
     * Updates a Switch channel from a nested tracker_status control object's "active" field
     * (led_control/buzzer_control/live_tracking) — the only confirmed source of real device
     * state for the three command switches; the synchronous command response is not reliable.
     */
    protected void applyControlState(String channelId, JsonObject event, String field) {
        if (!isLinked(channelId) || !event.has(field) || !event.get(field).isJsonObject()) {
            return;
        }
        JsonElement active = event.get(field).getAsJsonObject().get(FIELD_ACTIVE);
        if (active != null && !active.isJsonNull()) {
            updateState(channelId, OnOffType.from(active.getAsBoolean()));
        }
    }

    /**
     * Updates the zone channel group from a nested {@code prioritized_zone} object on the real-time channel. REST
     * carries the same data flattened as {@code prioritized_zone_*} top-level fields, handled directly in
     * {@link #pollTrackerDetails(TractiveAccountHandler)}.
     */
    protected void applyPrioritizedZone(JsonObject event) {
        if (!event.has(FIELD_PRIORITIZED_ZONE) || !event.get(FIELD_PRIORITIZED_ZONE).isJsonObject()) {
            return;
        }
        JsonObject zone = event.get(FIELD_PRIORITIZED_ZONE).getAsJsonObject();
        updateStringChannel(CHANNEL_ZONE_ID, zone, FIELD_ID);
        updateStringChannel(CHANNEL_ZONE_TYPE, zone, FIELD_ZONE_TYPE);
        updateEpochChannel(CHANNEL_ZONE_LAST_SEEN_AT, zone, FIELD_ZONE_LAST_SEEN_AT);
        updateEpochChannel(CHANNEL_ZONE_ENTERED_AT, zone, FIELD_ZONE_ENTERED_AT);
    }

    /**
     * Updates the Power Saving Zone ID channel from a {@code tracker_status} push. The field appears in up to three
     * places in the same push (top-level, nested under {@code hardware}, nested under {@code position}) and is not
     * always present at the top level -- a raw-capture check across 976 real {@code tracker_status} samples found it
     * present only under {@code hardware} (no top-level copy at all) in ~29% of samples, and wherever more than one
     * copy is present in the same push, they always agree, so falling back to the nested copies is safe.
     */
    protected void applyPowerSavingZoneId(JsonObject event) {
        if (!isLinked(CHANNEL_POWER_SAVING_ZONE_ID)) {
            return;
        }
        JsonElement el = event.get(FIELD_POWER_SAVING_ZONE_ID);
        if ((el == null || el.isJsonNull()) && event.has(FIELD_HARDWARE) && event.get(FIELD_HARDWARE).isJsonObject()) {
            el = event.get(FIELD_HARDWARE).getAsJsonObject().get(FIELD_POWER_SAVING_ZONE_ID);
        }
        if ((el == null || el.isJsonNull()) && event.has(FIELD_POSITION) && event.get(FIELD_POSITION).isJsonObject()) {
            el = event.get(FIELD_POSITION).getAsJsonObject().get(FIELD_POWER_SAVING_ZONE_ID);
        }
        if (el != null && !el.isJsonNull()) {
            updateState(CHANNEL_POWER_SAVING_ZONE_ID, new StringType(el.getAsString()));
        }
    }

    /**
     * Updates a command's timeout/remaining channels from its nested control object
     * ({@code led_control}/{@code buzzer_control}/{@code live_tracking}) in a {@code tracker_status} push.
     * Real-time-channel-only: the synchronous command HTTP response is never parsed (see {@link #sendCommand}'s caller
     * and the class-level notes on why).
     */
    protected void updateControlTiming(String timeoutChannelId, String remainingChannelId, JsonObject event,
            String field) {
        if (!event.has(field) || !event.get(field).isJsonObject()) {
            return;
        }
        JsonObject control = event.get(field).getAsJsonObject();
        if (isLinked(timeoutChannelId)) {
            JsonElement timeout = control.get(FIELD_TIMEOUT);
            if (timeout != null && !timeout.isJsonNull()) {
                updateState(timeoutChannelId, new QuantityType<>(timeout.getAsDouble(), Units.SECOND));
            }
        }
        if (isLinked(remainingChannelId)) {
            JsonElement remaining = control.get(FIELD_REMAINING);
            if (remaining != null && !remaining.isJsonNull()) {
                updateState(remainingChannelId, new QuantityType<>(remaining.getAsDouble(), Units.SECOND));
            }
        }
    }

    /**
     * Reacts to an explicit command-timeout push from Tractive's cloud by forcing the relevant
     * command switch back to OFF — confirmed only for the "an ON attempt timed out"; the OFF direction doesn't report
     * such a time-out.
     */
    protected void applyStartFailed(JsonObject event) {
        if (!event.has(FIELD_COMMAND_TYPE)) {
            return;
        }
        String channelId = switch (event.get(FIELD_COMMAND_TYPE).getAsString()) {
            case VALUE_COMMAND_TYPE_LED -> CHANNEL_LED;
            case VALUE_COMMAND_TYPE_BUZZER -> CHANNEL_BUZZER;
            case VALUE_COMMAND_TYPE_LIVE_TRACKING -> CHANNEL_LIVE_TRACKING;
            default -> null;
        };
        if (channelId == null) {
            return;
        }
        if (CHANNEL_BUZZER.equals(channelId)) {
            cancelBuzzerAutoOffTask();
        }
        String reason = event.has(FIELD_CANCELLATION_REASON) ? event.get(FIELD_CANCELLATION_REASON).getAsString()
                : "unknown";
        logger.info("Command on {} failed to take effect: {}", channelId, reason);
        if (isLinked(channelId)) {
            updateState(channelId, OnOffType.OFF);
        }
    }

    /**
     * Applies a {@code trackable_object/{petId}} payload to the Profile channel group. Unlike every other
     * {@code applyXxx} method in this class, the data behind this one is never fetched on the recurring timed poll
     * schedule -- see README "Group profile" for the full list of what does trigger a fetch.
     */
    protected void applyProfile(JsonObject json) {
        logger.trace("Applying profile: {}", json);
        if (json.has(FIELD_DETAILS) && json.get(FIELD_DETAILS).isJsonObject()) {
            JsonObject details = json.get(FIELD_DETAILS).getAsJsonObject();
            updateStringChannel(CHANNEL_GENDER, details, FIELD_GENDER);
            updateEpochChannel(CHANNEL_BIRTHDAY, details, FIELD_BIRTHDAY);
            if (isLinked(CHANNEL_HEIGHT) && details.has(FIELD_HEIGHT) && !details.get(FIELD_HEIGHT).isJsonNull()) {
                updateState(CHANNEL_HEIGHT, new QuantityType<>(details.get(FIELD_HEIGHT).getAsDouble(), SIUnits.METRE));
            }
            if (isLinked(CHANNEL_WEIGHT) && details.has(FIELD_WEIGHT) && !details.get(FIELD_WEIGHT).isJsonNull()) {
                updateState(CHANNEL_WEIGHT, new QuantityType<>(details.get(FIELD_WEIGHT).getAsDouble(), SIUnits.GRAM));
            }
            if (isLinked(CHANNEL_NEUTERED) && details.has(FIELD_NEUTERED)
                    && !details.get(FIELD_NEUTERED).isJsonNull()) {
                updateState(CHANNEL_NEUTERED, OnOffType.from(details.get(FIELD_NEUTERED).getAsBoolean()));
            }
            if (isLinked(CHANNEL_BREED_IDS) && details.has(FIELD_BREED_IDS)
                    && details.get(FIELD_BREED_IDS).isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement el : details.get(FIELD_BREED_IDS).getAsJsonArray()) {
                    if (sb.length() > 0) {
                        sb.append(",");
                    }
                    sb.append(el.getAsString());
                }
                updateState(CHANNEL_BREED_IDS, new StringType(sb.toString()));
            }
        }
        if (isLinked(CHANNEL_HOME_LOCATION) && json.has(FIELD_HOME_LOCATION)
                && json.get(FIELD_HOME_LOCATION).isJsonArray()) {
            JsonArray hl = json.get(FIELD_HOME_LOCATION).getAsJsonArray();
            if (hl.size() == 2) {
                updateState(CHANNEL_HOME_LOCATION, new PointType(new DecimalType(hl.get(0).getAsDouble()),
                        new DecimalType(hl.get(1).getAsDouble())));
            }
        }
    }

    /**
     * Fetches and applies the pet profile; resolves the bridge internally. On {@link PollGuard.AcquireResult#COOLDOWN},
     * re-applies the cached payload but leaves {@code CHANNEL_PROFILE_LAST_UPDATED} untouched, since no fetch actually
     * happened -- only a real fetch below advances that channel. On {@link PollGuard.AcquireResult#IN_PROGRESS}, a
     * fresh response is already in flight, so the cache is left untouched.
     */
    protected void pollProfile(TractiveAccountHandler bridge) {
        PollGuard.AcquireResult result = profileGuard.tryAcquire();
        if (result == PollGuard.AcquireResult.COOLDOWN) {
            JsonObject cached = profileGuard.getCached();
            if (cached != null) {
                logger.debug("Profile poll skipped (cooldown, cached response is {} s old): re-applying",
                        profileGuard.getCacheAgeMs() / 1000);
                applyProfile(cached);
            }
            return;
        }
        if (result == PollGuard.AcquireResult.IN_PROGRESS) {
            logger.trace("Skipping profile poll: already in progress");
            return;
        }
        if (!tryConsumeSharedBudget(bridge, profileGuard, this::applyProfile, "Profile poll")) {
            return;
        }
        try {
            JsonObject json = getJson(bridge, API_BASE_URL + "trackable_object/" + trackedPetId);
            if (json != null) {
                profileGuard.setCached(json);
                applyProfile(json);
                if (isLinked(CHANNEL_PROFILE_LAST_UPDATED)) {
                    updateState(CHANNEL_PROFILE_LAST_UPDATED, new DateTimeType());
                }
            }
        } finally {
            profileGuard.release();
        }
    }

    /**
     * Triggers an immediate pet-profile refresh; resolves the bridge internally and delegates to {@link #pollProfile}.
     * Reachable two ways: directly, via the tracker model's {@code ThingActions} implementation (e.g.
     * {@link org.openhab.binding.tractive.internal.action.TractiveDog6Actions}), and indirectly, since
     * {@code TractiveDog6Handler.handleCommand()} also calls this for any {@code REFRESH} command -- the only one of
     * the four {@code refreshXxx} methods reachable that second way, since the Profile group sits outside
     * {@link #pollAll()}.
     */
    public void refreshProfile() {
        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge != null) {
            pollProfile(bridge);
        }
    }

    /**
     * Updates the internally-tracked dormancy flag from a state_reason/tracker_state_reason field --
     * REST calls it {@code state_reason}, the real-time channel calls it {@code tracker_state_reason};
     * same meaning, different key. Backs the auto-off prediction's dormancy gate (see
     * {@link #scheduleOrCancelAutoOff}) -- absence or null means "no special reason", i.e. awake,
     * matching every confirmed capture so far.
     */
    private void updatePowerSavingFlag(JsonObject json, String field) {
        JsonElement el = json.get(field);
        powerSaving = el != null && !el.isJsonNull() && VALUE_STATE_REASON_POWER_SAVING.equals(el.getAsString());
    }

    /**
     * Schedules (or cancels/reschedules) a locally-predicted "OFF" state for a command switch
     * channel, timed off the tracker's own {@code remaining} countdown from a confirmed
     * {@code tracker_status} push. Shared mechanics for any channel whose control object carries a device-driven
     * auto-off timer -- confirmed so far only for the buzzer minute hardware timeout, consistent across every capture);
     * LED's {@code timeout} field has shown both {@code 300} and {@code 3600} with no settled explanation yet, so it is
     * deliberately not wired up here until that's resolved. Each validated channel gets its own thin wrapper below
     * rather than being called directly with an arbitrary channel/field pair, so adding a channel here is always a
     * deliberate, visible decision, not an incidental one. While the tracker is awake, the real {@code tracker_status}
     * confirmation arrives promptly on its own, so the prediction only actually pushes state if the tracker is still
     * believed dormant at the moment the countdown elapses, since the tracker can go dormant partway through an
     * already-running countdown. Every fresh confirmation cancels and (if still active) reschedules the prediction, so
     * a renewal extends it exactly like it extends the real device timer.
     */
    private void scheduleOrCancelAutoOff(String channelId, JsonObject event, String field) {
        if (!isLinked(channelId) || !event.has(field) || !event.get(field).isJsonObject()) {
            return;
        }
        cancelAutoOffTask(channelId);

        JsonObject control = event.get(field).getAsJsonObject();
        JsonElement active = control.get(FIELD_ACTIVE);
        JsonElement remaining = control.get(FIELD_REMAINING);
        if (active == null || active.isJsonNull() || !active.getAsBoolean() || remaining == null
                || remaining.isJsonNull()) {
            return;
        }
        long delayMillis = Math.round(remaining.getAsDouble() * 1000);
        if (delayMillis <= 0) {
            return;
        }
        autoOffTasks.put(channelId, taskTracker.track(scheduler.schedule(() -> {
            if (isLinked(channelId) && powerSaving) {
                logger.info("Predicting {} auto-off after {}s with no confirmation (tracker believed dormant)",
                        channelId, remaining.getAsDouble());
                updateState(channelId, OnOffType.OFF);
            }
        }, delayMillis, TimeUnit.MILLISECONDS)));
    }

    /**
     * Cancels any pending predicted auto-off for the given channel, e.g. because a fresh
     * confirmation superseded it or a {@code start_failed} forced the channel OFF directly.
     */
    private void cancelAutoOffTask(String channelId) {
        ScheduledFuture<?> previous = autoOffTasks.remove(channelId);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * Buzzer-specific entry point into {@link #scheduleOrCancelAutoOff} -- see that method for the
     * shared mechanics and why only the buzzer is wired up so far.
     */
    protected void scheduleOrCancelBuzzerAutoOff(JsonObject event) {
        scheduleOrCancelAutoOff(CHANNEL_BUZZER, event, COMMAND_BUZZER_CONTROL);
    }

    /**
     * Buzzer-specific entry point into {@link #cancelAutoOffTask}.
     */
    private void cancelBuzzerAutoOffTask() {
        cancelAutoOffTask(CHANNEL_BUZZER);
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
