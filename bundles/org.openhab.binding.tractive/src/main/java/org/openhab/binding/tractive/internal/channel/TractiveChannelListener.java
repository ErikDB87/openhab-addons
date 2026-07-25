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
package org.openhab.binding.tractive.internal.channel;

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Manages the Tractive real-time NDJSON channel connection.
 * Call {@link #run} from a background thread; it blocks until the connection drops.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveChannelListener {

    private static final long KEEP_ALIVE_TIMEOUT_MS = 60_000;
    private static final long CONNECT_TIMEOUT_S = 10;

    private final Logger logger = LoggerFactory.getLogger(TractiveChannelListener.class);
    private final Consumer<JsonObject> eventConsumer;
    private final AtomicLong lastKeepAliveMs = new AtomicLong(System.currentTimeMillis());

    /**
     * Creates a channel listener that parses NDJSON lines and forwards data events to the given consumer.
     */
    public TractiveChannelListener(Consumer<JsonObject> eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    /**
     * Connects to the Tractive real-time channel and reads events until the connection
     * drops. Throws on any error so the caller can reconnect.
     */
    public void run(HttpClient httpClient, String accessToken, String userId) throws IOException, InterruptedException {
        lastKeepAliveMs.set(System.currentTimeMillis());

        InputStreamResponseListener streamListener = new InputStreamResponseListener();
        httpClient.newRequest(CHANNEL_URL).method(HttpMethod.POST).header(HEADER_TRACTIVE_CLIENT, API_CLIENT_ID)
                .header(HEADER_TRACTIVE_USER, userId).header(HEADER_AUTHORIZATION, AUTH_BEARER_PREFIX + accessToken)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).timeout(0, TimeUnit.SECONDS)
                .idleTimeout(KEEP_ALIVE_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS).send(streamListener);

        Response response;
        try {
            response = streamListener.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            InterruptedIOException ioe = new InterruptedIOException("Channel connect timed out");
            ioe.initCause(e);
            throw ioe;
        } catch (ExecutionException e) {
            throw new IOException("Channel connect failed: " + e.getMessage(), e);
        }
        if (response.getStatus() == HttpStatus.UNAUTHORIZED_401 || response.getStatus() == HttpStatus.FORBIDDEN_403) {
            throw new TractiveChannelAuthException("Channel connect failed with HTTP " + response.getStatus());
        }
        if (response.getStatus() != HttpStatus.OK_200) {
            throw new IOException("Channel connect failed with HTTP " + response.getStatus());
        }
        logger.trace("Channel connected");

        InputStream is = streamListener.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line.trim());
                if (System.currentTimeMillis() - lastKeepAliveMs.get() > KEEP_ALIVE_TIMEOUT_MS) {
                    throw new TractiveKeepAliveTimeoutException("Keep-alive timeout; reconnecting");
                }
            }
        }
    }

    private void processLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        logger.trace("Channel line: {}", line);
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!json.has(FIELD_MESSAGE)) {
                return;
            }
            String message = json.get(FIELD_MESSAGE).getAsString();
            if (MESSAGE_KEEP_ALIVE.equals(message)) {
                lastKeepAliveMs.set(System.currentTimeMillis());
                return;
            }
            if (MESSAGE_HANDSHAKE.equals(message)) {
                return;
            }
            eventConsumer.accept(json);
        } catch (JsonSyntaxException e) {
            logger.debug("Ignoring unparseable channel line: {}", e.getMessage());
        }
    }
}
