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
package org.openhab.binding.tractive.internal.discovery;

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.tractive.internal.handler.TractiveAccountHandler;
import org.openhab.binding.tractive.internal.util.TractiveRetryUtil;
import org.openhab.binding.tractive.internal.util.TractiveTaskTracker;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Discovers Tractive tracker things by querying the account's tracker list and
 * correlating trackers with pet (trackable object) IDs.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    private static final int SCAN_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(TractiveDiscoveryService.class);
    private final Gson gson = new Gson();
    private final TractiveTaskTracker taskTracker = new TractiveTaskTracker();
    private @Nullable TractiveAccountHandler bridgeHandler;
    private boolean automaticScanDone = false;

    private record PetInfo(String petId, String petName) {
    }

    /**
     * Creates the discovery service with a fixed scan timeout.
     */
    public TractiveDiscoveryService() {
        super(SUPPORTED_THING_TYPES, SCAN_TIMEOUT_SECONDS, false);
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof TractiveAccountHandler accountHandler) {
            this.bridgeHandler = accountHandler;
            accountHandler.registerDiscoveryService(this);
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    @Override
    public void activate() {
        super.activate(null);
    }

    @Override
    public void deactivate() {
        super.deactivate();
    }

    @Override
    protected void stopScan() {
        taskTracker.cancelAll();
        super.stopScan();
    }

    /**
     * Sends an authenticated GET request and returns the response, or {@code null} if the request
     * could not be completed (interrupted, or failed after retries).
     */
    private @Nullable ContentResponse fetchGet(TractiveAccountHandler bridge, HttpClient httpClient, String url,
            String logContext) {
        try {
            return TractiveRetryUtil
                    .sendWithRetry(() -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)),
                            scheduler, logger)
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            logger.warn("{} failed: {}", logContext, e.getMessage());
            return null;
        }
    }

    /**
     * Runs one discovery scan automatically the first time this is called after the bridge
     * comes online. Subsequent calls are no-ops. Manual scans via {@link #startScan()} are
     * a separate path and are never affected by this guard.
     */
    public void runAutomaticScanOnce() {
        if (automaticScanDone) {
            return;
        }
        automaticScanDone = true;
        TractiveAccountHandler bridge = bridgeHandler;
        if (bridge == null) {
            return;
        }
        String userId = bridge.getUserId();
        if (userId == null) {
            return;
        }
        scheduleRunScan(bridge, userId);
    }

    @Override
    protected void startScan() {
        TractiveAccountHandler bridge = bridgeHandler;
        if (bridge == null) {
            return;
        }
        String userId = bridge.getUserId();
        if (userId == null) {
            logger.debug("Discovery skipped: bridge not yet authenticated");
            return;
        }
        scheduleRunScan(bridge, userId);
    }

    private void scheduleRunScan(TractiveAccountHandler bridge, String userId) {
        taskTracker.track(scheduler.schedule(() -> runScan(bridge, userId), 0, TimeUnit.SECONDS));
    }

    private void runScan(TractiveAccountHandler bridge, String userId) {
        HttpClient httpClient = bridge.getHttpClient();

        Map<String, PetInfo> trackerToPet = fetchDeviceToTrackableMap(bridge, httpClient, userId);

        JsonArray trackers = fetchTrackerList(bridge, httpClient, userId);
        if (trackers == null) {
            return;
        }
        for (JsonElement el : trackers) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject tracker = el.getAsJsonObject();
            if (!tracker.has(FIELD_ID)) {
                continue;
            }
            String trackerId = tracker.get(FIELD_ID).getAsString();
            PetInfo petInfo = trackerToPet.get(trackerId);
            String petId = petInfo != null ? petInfo.petId() : "";
            String petName = petInfo != null ? petInfo.petName() : "";
            logger.trace("Relevant JSON for discovery: {}", el);
            logger.trace("Discovered tracker: trackerId={}, petId={}", trackerId, petId);
            ThingUID bridgeUID = bridge.getThing().getUID();
            String modelNumber = fetchModelNumber(bridge, httpClient, trackerId);
            ThingTypeUID thingType = resolveThingType(modelNumber);
            if (thingType == null) {
                logger.warn(
                        "Discovered tracker {} has unrecognised model number '{}' — skipping. Please report this model to the binding maintainer.",
                        trackerId, modelNumber);
                continue;
            }
            ThingUID thingUID = new ThingUID(thingType, bridgeUID, trackerId.toLowerCase(Locale.ROOT));

            Map<String, Object> properties = new HashMap<>();
            properties.put("trackerId", trackerId);
            properties.put("trackedPetId", petId);

            String modelName = MODEL_NAMES.getOrDefault(modelNumber, modelNumber);
            String label = petName.isBlank() ? "Tractive " + modelName + " (" + trackerId + ")"
                    : "Tractive " + modelName + " (" + petName + ")";
            thingDiscovered(DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withProperties(properties)
                    .withRepresentationProperty("trackerId").withLabel(label).build());
        }
    }

    private String fetchModelNumber(TractiveAccountHandler bridge, HttpClient httpClient, String trackerId) {
        String url = API_BASE_URL + "tracker/" + trackerId;
        ContentResponse response = fetchGet(bridge, httpClient, url, "Fetch tracker details for " + trackerId);
        if (response == null) {
            return "";
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            logger.warn("Tracker details for {} returned HTTP {}", trackerId, response.getStatus());
            return "";
        }
        JsonElement parsed = JsonParser.parseString(response.getContentAsString());
        if (!parsed.isJsonObject()) {
            return "";
        }
        JsonObject json = parsed.getAsJsonObject();
        if (json.has(FIELD_MODEL_NUMBER)) {
            return json.get(FIELD_MODEL_NUMBER).getAsString();
        }
        return "";
    }

    private @Nullable ThingTypeUID resolveThingType(String modelNumber) {
        switch (modelNumber) {
            case MODEL_DOG6:
                return THING_TYPE_DOG6;
            default:
                return null;
        }
    }

    private @Nullable JsonArray fetchTrackerList(TractiveAccountHandler bridge, HttpClient httpClient, String userId) {
        String url = API_BASE_URL + "user/" + userId + "/trackers";
        ContentResponse response = fetchGet(bridge, httpClient, url, "Fetch tracker list");
        if (response == null) {
            return null;
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            logger.warn("Tracker list returned HTTP {}", response.getStatus());
            return null;
        }
        logger.trace("Tracker list response: {}", response.getContentAsString());
        return gson.fromJson(response.getContentAsString(), JsonArray.class);
    }

    private Map<String, PetInfo> fetchDeviceToTrackableMap(TractiveAccountHandler bridge, HttpClient httpClient,
            String userId) {
        Map<String, PetInfo> result = new HashMap<>();
        String listUrl = API_BASE_URL + "user/" + userId + "/trackable_objects";
        ContentResponse listResponse = fetchGet(bridge, httpClient, listUrl, "Fetch trackable objects list");
        if (listResponse == null) {
            return result;
        }
        if (listResponse.getStatus() != HttpStatus.OK_200) {
            logger.warn("Trackable objects list returned HTTP {}", listResponse.getStatus());
            return result;
        }
        logger.trace("Trackable objects list: {}", listResponse.getContentAsString());
        JsonElement parsed = JsonParser.parseString(listResponse.getContentAsString());
        if (!parsed.isJsonArray()) {
            return result;
        }
        JsonArray objects = parsed.getAsJsonArray();
        for (JsonElement el : objects) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has(FIELD_ID)) {
                continue;
            }
            String petId = obj.get(FIELD_ID).getAsString();
            String petUrl = API_BASE_URL + "trackable_object/" + petId;
            ContentResponse petResponse = fetchGet(bridge, httpClient, petUrl, "Fetch trackable object " + petId);
            if (Thread.currentThread().isInterrupted()) {
                return result;
            }
            if (petResponse != null && petResponse.getStatus() == HttpStatus.OK_200) {
                logger.trace("Trackable object {} response: {}", petId, petResponse.getContentAsString());
                JsonElement petParsed = JsonParser.parseString(petResponse.getContentAsString());
                if (petParsed.isJsonObject()) {
                    JsonObject petObj = petParsed.getAsJsonObject();
                    if (petObj.has(FIELD_DEVICE_ID)) {
                        String trackerId = petObj.get(FIELD_DEVICE_ID).getAsString();
                        result.put(trackerId, new PetInfo(petId, extractPetName(petObj)));
                    }
                }
            }
        }
        return result;
    }

    private String extractPetName(JsonObject petObj) {
        if (petObj.has(FIELD_DETAILS) && petObj.get(FIELD_DETAILS).isJsonObject()) {
            JsonObject details = petObj.get(FIELD_DETAILS).getAsJsonObject();
            if (details.has(FIELD_NAME) && !details.get(FIELD_NAME).isJsonNull()) {
                return details.get(FIELD_NAME).getAsString();
            }
        }
        return "";
    }
}
