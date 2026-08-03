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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.tractive.internal.action.TractiveDog6Actions;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

import com.google.gson.JsonObject;

/**
 * The {@link TractiveDog6Handler} handles a single Tractive Dog 6 tracker thing.
 * It registers with the account bridge for real-time channel events and polls
 * four REST endpoints on a configurable interval.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveDog6Handler extends TractiveTrackerHandler {

    /**
     * Creates a new Dog 6 tracker handler for the given thing.
     */
    public TractiveDog6Handler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            taskTracker.track(scheduler.schedule(this::pollAll, 0, TimeUnit.SECONDS));
            return;
        }

        TractiveAccountHandler bridge = getAccountHandler();
        if (bridge == null) {
            return;
        }
        HttpClient httpClient = bridge.getHttpClient();
        String state = command == OnOffType.ON ? STATE_ON : STATE_OFF;

        switch (channelUID.getId()) {
            case CHANNEL_BUZZER:
                taskTracker.track(scheduler.schedule(
                        () -> sendCommand(httpClient, bridge, COMMAND_BUZZER_CONTROL, state), 0, TimeUnit.SECONDS));
                break;
            case CHANNEL_LED:
                taskTracker.track(scheduler.schedule(() -> sendCommand(httpClient, bridge, COMMAND_LED_CONTROL, state),
                        0, TimeUnit.SECONDS));
                break;
            case CHANNEL_LIVE_TRACKING:
                taskTracker.track(scheduler.schedule(
                        () -> sendCommand(httpClient, bridge, COMMAND_LIVE_TRACKING, state), 0, TimeUnit.SECONDS));
                break;
            default:
                logger.debug("Ignoring command for read-only channel {}", channelUID.getId());
                break;
        }
    }

    @Override
    protected void applyHealthOverview(JsonObject json) {
        logger.trace("Applying health overview: {}", json);
        if (json.has(FIELD_ACTIVITY) && json.get(FIELD_ACTIVITY).isJsonObject()) {
            JsonObject activity = json.get(FIELD_ACTIVITY).getAsJsonObject();
            updateIntMinutesChannel(CHANNEL_ACTIVITY_RECORDED, activity, FIELD_MINUTES_ACTIVE);
            updateIntMinutesChannel(CHANNEL_ACTIVITY_GOAL, activity, FIELD_MINUTES_GOAL);
        }
        if (json.has(FIELD_SLEEP) && json.get(FIELD_SLEEP).isJsonObject()) {
            JsonObject sleep = json.get(FIELD_SLEEP).getAsJsonObject();
            updateIntMinutesChannel(CHANNEL_SLEEP_DAY, sleep, FIELD_MINUTES_DAY_SLEEP);
            updateIntMinutesChannel(CHANNEL_SLEEP_NIGHT, sleep, FIELD_MINUTES_NIGHT_SLEEP);
            updateIntMinutesChannel(CHANNEL_SLEEP_CALM, sleep, FIELD_MINUTES_CALM);
        }
        if (json.has(FIELD_RESTING_HEART_RATE) && json.get(FIELD_RESTING_HEART_RATE).isJsonObject()) {
            updateStringChannel(CHANNEL_RESTING_HEART_RATE_STATUS, json.get(FIELD_RESTING_HEART_RATE).getAsJsonObject(),
                    FIELD_STATUS);
        }
        if (json.has(FIELD_RESTING_RESPIRATORY_RATE) && json.get(FIELD_RESTING_RESPIRATORY_RATE).isJsonObject()) {
            updateStringChannel(CHANNEL_RESTING_RESPIRATORY_RATE_STATUS,
                    json.get(FIELD_RESTING_RESPIRATORY_RATE).getAsJsonObject(), FIELD_STATUS);
        }
        if (json.has(FIELD_BARK) && json.get(FIELD_BARK).isJsonObject()) {
            updateStringChannel(CHANNEL_BARK, json.get(FIELD_BARK).getAsJsonObject(), FIELD_STATUS);
        }
        if (json.has(FIELD_SCRATCH) && json.get(FIELD_SCRATCH).isJsonObject()) {
            updateStringChannel(CHANNEL_SCRATCH, json.get(FIELD_SCRATCH).getAsJsonObject(), FIELD_STATUS);
        }
        if (json.has(FIELD_HEALTH_ALERTS) && json.get(FIELD_HEALTH_ALERTS).isJsonObject()) {
            JsonObject alerts = json.get(FIELD_HEALTH_ALERTS).getAsJsonObject();
            if (alerts.has(FIELD_UNSEEN_COUNT) && isLinked(CHANNEL_UNSEEN_HEALTH_ALERTS)) {
                updateState(CHANNEL_UNSEEN_HEALTH_ALERTS, new DecimalType(alerts.get(FIELD_UNSEEN_COUNT).getAsInt()));
            }
        }
        updateTimestampChannel(CHANNEL_ACTIVITY_SYNCED_AT, json, FIELD_ACTIVITY_DATA_SYNCED_AT);
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(TractiveDog6Actions.class);
    }
}
