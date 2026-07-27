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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
    private @Nullable TractiveAccountHandler bridgeHandler;

    public TractiveDiscoveryService() {
        super(Set.of(THING_TYPE_DOG6), SCAN_TIMEOUT_SECONDS, false);
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof TractiveAccountHandler accountHandler) {
            this.bridgeHandler = accountHandler;
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
        scheduler.submit(() -> runScan(bridge, userId));
    }

    private void runScan(TractiveAccountHandler bridge, String userId) {
        HttpClient httpClient = bridge.getHttpClient();

        // Map device_id → trackable (pet) ID from trackable objects
        Map<String, String> deviceToTrackable = fetchDeviceToTrackableMap(bridge, httpClient, userId);

        // List trackers and announce each as a discovery result
        JsonArray trackers = fetchTrackerList(bridge, httpClient, userId);
        if (trackers == null) {
            return;
        }
        for (JsonElement el : trackers) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject tracker = el.getAsJsonObject();
            if (!tracker.has("_id")) {
                continue;
            }
            String tId = tracker.get("_id").getAsString();
            String trackableId = deviceToTrackable.getOrDefault(tId, "");

            ThingUID bridgeUID = bridge.getThing().getUID();
            ThingUID thingUID = new ThingUID(THING_TYPE_DOG6, bridgeUID, tId.toLowerCase());

            Map<String, Object> properties = new HashMap<>();
            properties.put("trackerId", tId);
            properties.put("trackableId", trackableId);

            thingDiscovered(DiscoveryResultBuilder.create(thingUID)
                    .withBridge(bridgeUID)
                    .withProperties(properties)
                    .withRepresentationProperty("trackerId")
                    .withLabel("Tractive Dog 6 (" + tId + ")")
                    .build());
        }
    }

    private @Nullable JsonArray fetchTrackerList(TractiveAccountHandler bridge, HttpClient httpClient, String userId) {
        String url = API_BASE_URL + "user/" + userId + "/trackers";
        try {
            ContentResponse response = TractiveRetryUtil.sendWithRetry(
                    () -> bridge.addAuthHeaders(httpClient.newRequest(url).method(HttpMethod.GET)), logger);
            if (response.getStatus() != HttpStatus.OK_200) {
                logger.debug("Tracker list returned HTTP {}", response.getStatus());
                return null;
            }
            return gson.fromJson(response.getContentAsString(), JsonArray.class);
        } catch (Exception e) {
            logger.debug("Failed to fetch tracker list: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> fetchDeviceToTrackableMap(TractiveAccountHandler bridge, HttpClient httpClient,
            String userId) {
        Map<String, String> result = new HashMap<>();
        String listUrl = API_BASE_URL + "user/" + userId + "/trackable_objects";
        try {
            ContentResponse listResponse = TractiveRetryUtil.sendWithRetry(
                    () -> bridge.addAuthHeaders(httpClient.newRequest(listUrl).method(HttpMethod.GET)), logger);
            if (listResponse.getStatus() != HttpStatus.OK_200) {
                return result;
            }
            JsonArray objects = gson.fromJson(listResponse.getContentAsString(), JsonArray.class);
            if (objects == null) {
                return result;
            }
            for (JsonElement el : objects) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("_id")) {
                    continue;
                }
                String petId = obj.get("_id").getAsString();
                String petUrl = API_BASE_URL + "trackable_object/" + petId;
                try {
                    ContentResponse petResponse = TractiveRetryUtil.sendWithRetry(
                            () -> bridge.addAuthHeaders(httpClient.newRequest(petUrl).method(HttpMethod.GET)),
                            logger);
                    if (petResponse.getStatus() == HttpStatus.OK_200) {
                        JsonObject petObj = gson.fromJson(petResponse.getContentAsString(), JsonObject.class);
                        if (petObj != null && petObj.has("device_id")) {
                            result.put(petObj.get("device_id").getAsString(), petId);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to fetch trackable object {}: {}", petId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch trackable objects list: {}", e.getMessage());
        }
        return result;
    }
}
