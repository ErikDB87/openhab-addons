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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.JsonObject;

/**
 * Callback interface for receiving real-time events from the Tractive channel.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public interface TractiveEventListener {

    /**
     * Returns the set of tracker and/or pet IDs whose events this listener wants to receive.
     */
    Set<String> getTargetIds();

    /**
     * Called on a channel event whose target ID matches one in {@link #getTargetIds()}.
     *
     * @param messageType the raw "message" field from the NDJSON event
     * @param event the full event JSON object
     */
    void onChannelEvent(String messageType, JsonObject event);
}
