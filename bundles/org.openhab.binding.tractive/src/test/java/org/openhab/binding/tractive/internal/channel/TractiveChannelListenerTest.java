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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Unit tests for {@link TractiveChannelListener}'s line handling, exercised through the private
 * {@code processLine()} via reflection.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
class TractiveChannelListenerTest {

    private final List<JsonObject> events = new ArrayList<>();
    private final AtomicInteger connectedCalls = new AtomicInteger();
    private final TractiveChannelListener listener = new TractiveChannelListener(events::add,
            connectedCalls::incrementAndGet);

    private void feed(String line) throws Exception {
        feed(listener, line);
    }

    private void feed(TractiveChannelListener target, String line) throws Exception {
        Method method = TractiveChannelListener.class.getDeclaredMethod("processLine", String.class);
        method.setAccessible(true);
        method.invoke(target, line);
    }

    @Test
    void handshakeCallsOnConnectedOnceAndIsNotForwardedAsAnEvent() throws Exception {
        feed("{\"message\":\"handshake\",\"keep_alive_ttl\":5}");

        assertEquals(1, connectedCalls.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void eachHandshakeCallsOnConnectedAgain() throws Exception {
        feed("{\"message\":\"handshake\"}");
        feed("{\"message\":\"handshake\"}");

        assertEquals(2, connectedCalls.get());
    }

    @Test
    void keepAliveTriggersNeitherTheCallbackNorTheConsumer() throws Exception {
        feed("{\"message\":\"keep-alive\"}");

        assertEquals(0, connectedCalls.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void dataEventIsForwardedWithoutCallingOnConnected() throws Exception {
        feed("{\"message\":\"tracker_status\",\"tracker_id\":\"ABCD1234\"}");

        assertEquals(0, connectedCalls.get());
        assertEquals(1, events.size());
        assertEquals("tracker_status", events.get(0).get("message").getAsString());
    }

    @Test
    void unparseableLineIsIgnored() throws Exception {
        feed("not json");

        assertEquals(0, connectedCalls.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void throwingOnConnectedCallbackDoesNotPropagate() {
        TractiveChannelListener failing = new TractiveChannelListener(events::add, () -> {
            throw new IllegalStateException("boom");
        });

        assertDoesNotThrow(() -> feed(failing, "{\"message\":\"handshake\"}"));
        assertTrue(events.isEmpty());
    }

    @Test
    void throwingEventConsumerDoesNotPropagateAndLaterLinesAreStillHandled() throws Exception {
        AtomicInteger consumerCalls = new AtomicInteger();
        TractiveChannelListener failing = new TractiveChannelListener(json -> {
            if (consumerCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            events.add(json);
        }, connectedCalls::incrementAndGet);

        assertDoesNotThrow(() -> feed(failing, "{\"message\":\"tracker_status\",\"tracker_id\":\"ABCD1234\"}"));
        feed(failing, "{\"message\":\"tracker_status\",\"tracker_id\":\"ABCD1234\"}");

        assertEquals(2, consumerCalls.get());
        assertEquals(1, events.size());
    }

    @Test
    void nonPrimitiveMessageFieldDoesNotPropagate() {
        assertDoesNotThrow(() -> feed("{\"message\":{}}"));

        assertEquals(0, connectedCalls.get());
        assertTrue(events.isEmpty());
    }
}
