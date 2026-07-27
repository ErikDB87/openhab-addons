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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The {@link TractiveAccountHandler} implements the Tractive account bridge.
 * It handles authentication, the real-time NDJSON channel, and dispatches events
 * to registered {@link TractiveEventListener} instances (the tracker thing handlers).
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveAccountHandler extends BaseBridgeHandler {

    private static final long TOKEN_REFRESH_INTERVAL_MINUTES = 10;
    private static final long TOKEN_REFRESH_BEFORE_EXPIRY_SECONDS = 3600;
    private static final long CHANNEL_RECONNECT_INITIAL_DELAY_S = 15;
    private static final long CHANNEL_RECONNECT_MAX_DELAY_S = 300;

    private final Logger logger = LoggerFactory.getLogger(TractiveAccountHandler.class);
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final TractiveTaskTracker taskTracker = new TractiveTaskTracker();
    private final CopyOnWriteArrayList<TractiveEventListener> eventListeners = new CopyOnWriteArrayList<>();

    private @Nullable String accessToken;
    private @Nullable String userId;
    private long expiresAt;
    private long channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

    public TractiveAccountHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::initializeBridge);
    }

    private void initializeBridge() {
        TractiveAccountConfiguration config = getConfigAs(TractiveAccountConfiguration.class);
        if (config.email.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Email and password must be configured");
            return;
        }
        try {
            authenticate(config.email, config.password);
            updateStatus(ThingStatus.ONLINE);
            channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

            taskTracker.track(scheduler.scheduleWithFixedDelay(
                    () -> checkAndRefreshToken(config.email, config.password),
                    TOKEN_REFRESH_INTERVAL_MINUTES, TOKEN_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES));

            startChannelLoop();
        } catch (Exception e) {
            logger.debug("Bridge initialization failed: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Authentication failed: " + e.getMessage());
        }
    }

    private void authenticate(String email, String password) throws Exception {
        String bodyJson = gson.toJson(Map.of(
                "platform_email", email,
                "platform_token", password,
                "grant_type", "tractive"));

        ContentResponse response = httpClient.newRequest(API_BASE_URL + "auth/token")
                .method(HttpMethod.POST)
                .header("x-tractive-client", API_CLIENT_ID)
                .header("content-type", "application/json;charset=UTF-8")
                .content(new StringContentProvider(bodyJson, StandardCharsets.UTF_8))
                .send();

        if (response.getStatus() != HttpStatus.OK_200) {
            throw new IOException(
                    "Authentication failed with HTTP " + response.getStatus() + ": " + response.getContentAsString());
        }

        JsonObject body = gson.fromJson(response.getContentAsString(), JsonObject.class);
        if (body == null || !body.has("access_token") || !body.has("user_id") || !body.has("expires_at")) {
            throw new IOException("Unexpected authentication response: " + response.getContentAsString());
        }
        accessToken = body.get("access_token").getAsString();
        userId = body.get("user_id").getAsString();
        expiresAt = body.get("expires_at").getAsLong();
        logger.debug("Authenticated as user_id={}, token expires at epoch={}", userId, expiresAt);
    }

    private void checkAndRefreshToken(String email, String password) {
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        if (expiresAt - nowEpochSeconds < TOKEN_REFRESH_BEFORE_EXPIRY_SECONDS) {
            logger.debug("Token expiry within {} s, refreshing", TOKEN_REFRESH_BEFORE_EXPIRY_SECONDS);
            try {
                authenticate(email, password);
            } catch (Exception e) {
                logger.debug("Token refresh failed: {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Token refresh failed: " + e.getMessage());
            }
        }
    }

    private void startChannelLoop() {
        TractiveChannelListener channelListener = new TractiveChannelListener(gson, this::dispatchChannelEvent);
        taskTracker.track(scheduler.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                String token = accessToken;
                String uid = userId;
                if (token == null || uid == null) {
                    break;
                }
                try {
                    channelListener.run(httpClient, token, uid);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.debug("Channel disconnected: {}", e.getMessage());
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    logger.debug("Channel reconnecting in {}s", channelReconnectDelaySeconds);
                    TimeUnit.SECONDS.sleep(channelReconnectDelaySeconds);
                    channelReconnectDelaySeconds = Math.min(
                            channelReconnectDelaySeconds * 2, CHANNEL_RECONNECT_MAX_DELAY_S);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }));
    }

    private void dispatchChannelEvent(JsonObject event) {
        // Reset reconnect back-off: we're actively receiving data
        channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

        String messageType = event.has("message") ? event.get("message").getAsString() : "";
        String targetId = resolveTargetId(event, messageType);
        if (targetId.isEmpty()) {
            return;
        }
        for (TractiveEventListener listener : eventListeners) {
            if (listener.getTargetIds().contains(targetId)) {
                listener.onChannelEvent(messageType, event);
            }
        }
    }

    /** Extracts the target ID from the event — prefers the "_id" field, falls back to parsing "message[ID]". */
    private String resolveTargetId(JsonObject event, String messageType) {
        if (event.has("_id")) {
            return event.get("_id").getAsString();
        }
        int start = messageType.indexOf('[');
        int end = messageType.indexOf(']');
        if (start >= 0 && end > start) {
            return messageType.substring(start + 1, end);
        }
        return "";
    }

    // ---- Public API for child handlers ----

    public void registerListener(TractiveEventListener listener) {
        eventListeners.addIfAbsent(listener);
    }

    public void unregisterListener(TractiveEventListener listener) {
        eventListeners.remove(listener);
    }

    public @Nullable String getAccessToken() {
        return accessToken;
    }

    public @Nullable String getUserId() {
        return userId;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Decorates a {@link Request} with the three required Tractive auth headers.
     * Returns the same request for chaining.
     */
    public Request addAuthHeaders(Request request) {
        String token = accessToken;
        String uid = userId;
        if (token != null && uid != null) {
            request.header("x-tractive-client", API_CLIENT_ID)
                    .header("x-tractive-user", uid)
                    .header("authorization", "Bearer " + token)
                    .header("content-type", "application/json;charset=UTF-8");
        }
        return request;
    }

    // ---- BaseThingHandler overrides ----

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The account bridge has no channels of its own.
    }

    @Override
    public void dispose() {
        taskTracker.cancelAll();
        accessToken = null;
        userId = null;
        super.dispose();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(TractiveDiscoveryService.class);
    }
}
