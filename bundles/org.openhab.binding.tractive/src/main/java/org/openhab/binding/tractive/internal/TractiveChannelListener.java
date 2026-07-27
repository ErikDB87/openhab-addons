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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Manages the Tractive real-time NDJSON channel connection.
 * Call {@link #run} from a background thread; it blocks until the connection drops
 * or {@link #stop} is called.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveChannelListener {

    private static final long KEEP_ALIVE_TIMEOUT_MS = 60_000;
    private static final long CONNECT_TIMEOUT_S = 10;

    private final Logger logger = LoggerFactory.getLogger(TractiveChannelListener.class);
    private final Gson gson;
    private final Consumer<JsonObject> eventConsumer;
    private final AtomicLong lastKeepAliveMs = new AtomicLong(System.currentTimeMillis());
    private volatile boolean stopped;

    public TractiveChannelListener(Gson gson, Consumer<JsonObject> eventConsumer) {
        this.gson = gson;
        this.eventConsumer = eventConsumer;
    }

    /** Signals the read loop to exit cleanly after the next line. */
    public void stop() {
        stopped = true;
    }

    /**
     * Connects to the Tractive real-time channel and reads events until the connection
     * drops or {@link #stop()} is called. Throws on any error so the caller can reconnect.
     */
    public void run(HttpClient httpClient, String accessToken, String userId) throws Exception {
        stopped = false;
        lastKeepAliveMs.set(System.currentTimeMillis());

        InputStreamResponseListener streamListener = new InputStreamResponseListener();
        httpClient.newRequest(CHANNEL_URL)
                .method(HttpMethod.POST)
                .header("x-tractive-client", API_CLIENT_ID)
                .header("x-tractive-user", userId)
                .header("authorization", "Bearer " + accessToken)
                .header("content-type", "application/json;charset=UTF-8")
                .timeout(0, TimeUnit.SECONDS)
                .idleTimeout(KEEP_ALIVE_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
                .send(streamListener);

        Response response = streamListener.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS);
        if (response.getStatus() != 200) {
            throw new IOException("Channel connect failed with HTTP " + response.getStatus());
        }

        InputStream is = streamListener.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                processLine(line.trim());
                if (System.currentTimeMillis() - lastKeepAliveMs.get() > KEEP_ALIVE_TIMEOUT_MS) {
                    throw new IOException("Keep-alive timeout; reconnecting");
                }
            }
        }
    }

    private void processLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        try {
            JsonObject json = gson.fromJson(line, JsonObject.class);
            if (json == null || !json.has("message")) {
                return;
            }
            String message = json.get("message").getAsString();
            if ("keep-alive".equals(message)) {
                lastKeepAliveMs.set(System.currentTimeMillis());
                return;
            }
            if ("handshake".equals(message)) {
                return;
            }
            eventConsumer.accept(json);
        } catch (JsonSyntaxException e) {
            logger.debug("Ignoring unparseable channel line: {}", e.getMessage());
        }
    }
}
