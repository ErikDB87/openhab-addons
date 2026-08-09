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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.tractive.internal.channel.TractiveChannelListener;
import org.openhab.binding.tractive.internal.channel.TractiveEventListener;
import org.openhab.binding.tractive.internal.channel.TractiveKeepAliveTimeoutException;
import org.openhab.binding.tractive.internal.config.TractiveAccountConfiguration;
import org.openhab.binding.tractive.internal.discovery.TractiveDiscoveryService;
import org.openhab.binding.tractive.internal.util.TractiveTaskTracker;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    private volatile @Nullable String accessToken;
    private @Nullable String userId;
    private volatile @Nullable TractiveAccountConfiguration config;
    private long expiresAt;
    private long channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

    private volatile @Nullable TractiveDiscoveryService discoveryService;

    private final Object authLock = new Object();

    /**
     * Creates a new account bridge handler using the shared openHAB HTTP client.
     */
    public TractiveAccountHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        taskTracker.track(scheduler.schedule(this::initializeBridge, 0, TimeUnit.SECONDS));
    }

    private void initializeBridge() {
        TractiveAccountConfiguration localConfig = getConfigAs(TractiveAccountConfiguration.class);
        if (localConfig.email.isBlank() || localConfig.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Email and password must be configured");
            return;
        }
        config = localConfig;
        try {
            authenticate(localConfig.email, localConfig.password, accessToken);
            updateStatus(ThingStatus.ONLINE);
            channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

            TractiveDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.runAutomaticScanOnce();
            }

            taskTracker.track(scheduler.scheduleWithFixedDelay(this::checkAndRefreshToken,
                    TOKEN_REFRESH_INTERVAL_MINUTES, TOKEN_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES));

            startChannelLoop();
        } catch (InterruptedIOException e) {
            // Thread already re-interrupted inside authenticate(); exit without touching status.
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Authentication failed: " + e.getMessage());
        }
    }

    private void authenticate(String email, String password, @Nullable String knownToken) throws IOException {
        synchronized (authLock) {
            if (!Objects.equals(accessToken, knownToken)) {
                return;
            }
            String bodyJson = gson.toJson(Map.of(FIELD_PLATFORM_EMAIL, email, FIELD_PLATFORM_TOKEN, password,
                    FIELD_GRANT_TYPE, VALUE_GRANT_TYPE_TRACTIVE));
            ContentResponse response;
            try {
                response = httpClient.newRequest(API_BASE_URL + "auth/token").method(HttpMethod.POST)
                        .header(HEADER_TRACTIVE_CLIENT, API_CLIENT_ID).header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                        .content(new StringContentProvider(bodyJson, StandardCharsets.UTF_8)).send();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException ioe = new InterruptedIOException("Authentication interrupted");
                ioe.initCause(e);
                throw ioe;
            } catch (TimeoutException e) {
                InterruptedIOException ioe = new InterruptedIOException("Authentication request timed out");
                ioe.initCause(e);
                throw ioe;
            } catch (ExecutionException e) {
                throw new IOException("Authentication request failed: " + e.getMessage(), e);
            }
            if (response.getStatus() != HttpStatus.OK_200) {
                throw new IOException("Authentication failed with HTTP " + response.getStatus() + ": "
                        + response.getContentAsString());
            }
            logger.trace("Auth response: {}", response.getContentAsString());
            JsonElement parsed = JsonParser.parseString(response.getContentAsString());
            if (!parsed.isJsonObject()) {
                throw new IOException("Unexpected authentication response: " + response.getContentAsString());
            }
            JsonObject body = parsed.getAsJsonObject();
            if (!body.has(FIELD_ACCESS_TOKEN) || !body.has(FIELD_USER_ID) || !body.has(FIELD_EXPIRES_AT)) {
                throw new IOException("Unexpected authentication response: " + response.getContentAsString());
            }
            accessToken = body.get(FIELD_ACCESS_TOKEN).getAsString();
            userId = body.get(FIELD_USER_ID).getAsString();
            expiresAt = body.get(FIELD_EXPIRES_AT).getAsLong();
            logger.debug("Authenticated as user_id={}, token expires at epoch={}", userId, expiresAt);
        }
    }

    private void checkAndRefreshToken() {
        TractiveAccountConfiguration currentConfig = config;
        if (currentConfig == null) {
            return;
        }
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        logger.trace("Token expiry check: {}s remaining", expiresAt - nowEpochSeconds);
        if (expiresAt - nowEpochSeconds < TOKEN_REFRESH_BEFORE_EXPIRY_SECONDS) {
            logger.debug("Token expiry within {} s, refreshing", TOKEN_REFRESH_BEFORE_EXPIRY_SECONDS);
            try {
                authenticate(currentConfig.email, currentConfig.password, accessToken);
            } catch (InterruptedIOException e) {
                // Thread already re-interrupted inside authenticate(); exit without touching status.
            } catch (IOException | RuntimeException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Token refresh failed: " + e.getMessage());
            }
        }
    }

    private void startChannelLoop() {
        TractiveChannelListener channelListener = new TractiveChannelListener(this::dispatchChannelEvent);
        scheduleChannelConnect(channelListener, 0);
    }

    private void scheduleChannelConnect(TractiveChannelListener channelListener, long delaySeconds) {
        taskTracker.track(scheduler.schedule(() -> {
            String token = accessToken;
            String uid = userId;
            if (token == null || uid == null) {
                return;
            }
            try {
                channelListener.run(httpClient, token, uid);
                // Stream ended cleanly — reconnect with reset backoff
                channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;
                scheduleChannelConnect(channelListener, CHANNEL_RECONNECT_INITIAL_DELAY_S);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (TractiveKeepAliveTimeoutException e) {
                logger.debug("Channel keep-alive stale, reconnecting: {}", e.getMessage());
                scheduleChannelConnect(channelListener, CHANNEL_RECONNECT_INITIAL_DELAY_S);
            } catch (InterruptedIOException e) {
                logger.debug("Channel connection timed out: {}", e.getMessage());
                long nextDelay = channelReconnectDelaySeconds;
                channelReconnectDelaySeconds = Math.min(channelReconnectDelaySeconds * 2,
                        CHANNEL_RECONNECT_MAX_DELAY_S);
                scheduleChannelConnect(channelListener, nextDelay);
            } catch (IOException e) {
                logger.debug("Channel disconnected: {}", e.getMessage());
                long nextDelay = channelReconnectDelaySeconds;
                channelReconnectDelaySeconds = Math.min(channelReconnectDelaySeconds * 2,
                        CHANNEL_RECONNECT_MAX_DELAY_S);
                scheduleChannelConnect(channelListener, nextDelay);
            } catch (RuntimeException e) {
                logger.debug("Unexpected error in channel listener: {}", e.getMessage());
                long nextDelay = channelReconnectDelaySeconds;
                channelReconnectDelaySeconds = Math.min(channelReconnectDelaySeconds * 2,
                        CHANNEL_RECONNECT_MAX_DELAY_S);
                scheduleChannelConnect(channelListener, nextDelay);
            }
        }, delaySeconds, TimeUnit.SECONDS));
    }

    private void dispatchChannelEvent(JsonObject event) {
        // Reset reconnect back-off: we're actively receiving data
        channelReconnectDelaySeconds = CHANNEL_RECONNECT_INITIAL_DELAY_S;

        String messageType = event.has(FIELD_MESSAGE) ? event.get(FIELD_MESSAGE).getAsString() : "";
        String targetId = resolveTargetId(event, messageType);
        if (targetId.isEmpty()) {
            return;
        }
        logger.trace("Dispatching channel event: messageType={}, targetId={}", messageType, targetId);
        for (TractiveEventListener listener : eventListeners) {
            if (listener.getTargetIds().contains(targetId)) {
                listener.onChannelEvent(messageType, event);
            }
        }
    }

    /**
     * Extracts the target ID from the event. Checks, in order: the "_id" field (REST-shaped
     * envelopes), "tracker_id" ({@code tracker_status} messages), "device_id" ({@code start_failed}/
     * {@code command_confirmed} messages), nested "content.petId" ({@code health_overview} messages),
     * then falls back to parsing "message[ID]".
     */
    private String resolveTargetId(JsonObject event, String messageType) {
        if (event.has(FIELD_ID)) {
            return event.get(FIELD_ID).getAsString();
        }
        if (event.has(FIELD_TRACKER_ID)) {
            return event.get(FIELD_TRACKER_ID).getAsString();
        }
        if (event.has(FIELD_DEVICE_ID)) {
            return event.get(FIELD_DEVICE_ID).getAsString();
        }
        if (event.has(FIELD_CONTENT) && event.get(FIELD_CONTENT).isJsonObject()) {
            JsonObject content = event.get(FIELD_CONTENT).getAsJsonObject();
            if (content.has(FIELD_PET_ID)) {
                return content.get(FIELD_PET_ID).getAsString();
            }
        }
        int start = messageType.indexOf('[');
        int end = messageType.indexOf(']');
        if (start >= 0 && end > start) {
            return messageType.substring(start + 1, end);
        }
        return "";
    }

    /**
     * Registers a listener to receive real-time channel events for its tracker and pet IDs.
     * Duplicate registrations are silently ignored.
     */
    public void registerListener(TractiveEventListener listener) {
        eventListeners.addIfAbsent(listener);
    }

    /**
     * Removes a previously registered channel event listener.
     */
    public void unregisterListener(TractiveEventListener listener) {
        eventListeners.remove(listener);
    }

    /**
     * Returns the current Bearer access token, or {@code null} if not yet authenticated.
     */
    public @Nullable String getAccessToken() {
        return accessToken;
    }

    /**
     * Re-authenticates using the stored credentials, replacing the current access token.
     * Called by tracker handlers when a request returns HTTP 401.
     * If another thread has already refreshed the token since {@code knownToken} was captured, the call is a no-op.
     *
     * @param knownToken the token the caller held when the 401 was received
     */
    public void refreshToken(@Nullable String knownToken) {
        TractiveAccountConfiguration currentConfig = config;
        if (currentConfig == null) {
            return;
        }
        try {
            authenticate(currentConfig.email, currentConfig.password, knownToken);
        } catch (InterruptedIOException e) {
            // Thread already re-interrupted inside authenticate(); exit without touching status.
        } catch (IOException | RuntimeException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Token refresh failed: " + e.getMessage());
        }
    }

    /**
     * Returns the authenticated Tractive user ID, or {@code null} if not yet authenticated.
     */
    public @Nullable String getUserId() {
        return userId;
    }

    /**
     * Returns the shared openHAB HTTP client.
     */
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
            request.header(HEADER_TRACTIVE_CLIENT, API_CLIENT_ID).header(HEADER_TRACTIVE_USER, uid)
                    .header(HEADER_AUTHORIZATION, AUTH_BEARER_PREFIX + token)
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        }
        return request;
    }

    /**
     * Registers the discovery service so the bridge can trigger a one-shot automatic scan
     * once it comes online.
     */
    public void registerDiscoveryService(TractiveDiscoveryService service) {
        this.discoveryService = service;
    }

    /**
     * No-op: the account bridge has no channels of its own.
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void dispose() {
        taskTracker.cancelAll();
        accessToken = null;
        userId = null;
        config = null;
        super.dispose();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(TractiveDiscoveryService.class);
    }
}
