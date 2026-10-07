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

    /**
     * Called by the bridge with fresh subscription state for a tracker whose ID is in {@link #getTargetIds()}.
     * Pushed from the bridge's periodic {@code user/widget-data} check so the tracker never fetches that
     * account-level bundle itself.
     *
     * @param subscription the {@code subscription} object for this tracker ({@code status}, {@code valid_to})
     */
    void onSubscriptionUpdate(JsonObject subscription);

    /**
     * Called by the bridge each time the real-time channel reconnects after having been connected before, so the
     * listener can catch up on anything pushed while the stream was down. Not called for the very first connect.
     */
    void onChannelReconnected();
}
