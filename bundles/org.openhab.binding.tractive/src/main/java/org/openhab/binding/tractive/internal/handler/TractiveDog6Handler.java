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
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link TractiveDog6Handler} handles a single Tractive Dog 6 tracker thing.
 * It registers with the account bridge for real-time channel events, polls three REST endpoints (position, hardware
 * battery level, health/dog) on a configurable interval as a backstop, and fetches Tracker Status, Device Info, and
 * Profile once at Thing setup, refreshable afterward only via their own actions.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveDog6Handler extends TractiveTrackerHandler {

    private static final String[] HEALTH_OVERVIEW_CHANNEL_IDS = { CHANNEL_ACTIVITY_RECORDED, CHANNEL_ACTIVITY_GOAL,
            CHANNEL_SLEEP_DAY, CHANNEL_SLEEP_NIGHT, CHANNEL_SLEEP_CALM, CHANNEL_RESTING_HEART_RATE_STATUS,
            CHANNEL_RESTING_RESPIRATORY_RATE_STATUS, CHANNEL_UNSEEN_HEALTH_ALERTS, CHANNEL_ACTIVITY_SYNCED_AT,
            CHANNEL_SCRATCH, CHANNEL_BARK, CHANNEL_ACTIVITY_HOURLY_DISTRIBUTION, CHANNEL_RESTING_HEART_RATE_DAY_OFFSET,
            CHANNEL_RESTING_RESPIRATORY_RATE_DAY_OFFSET, CHANNEL_SCRATCH_DAY_OFFSET, CHANNEL_ASSOCIATED_REPORT_TYPES,
            CHANNEL_SEPARATION_PHASE_ONGOING, CHANNEL_SEPARATION_PHASE_STARTED_AT, CHANNEL_BARK_DAY_OFFSET };

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
            taskTracker.track(scheduler.schedule(this::refreshProfile, 0, TimeUnit.SECONDS));
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
            updateJoinedArrayChannel(CHANNEL_ACTIVITY_HOURLY_DISTRIBUTION, activity, FIELD_HOURLY_DISTRIBUTION);
        }
        if (json.has(FIELD_SLEEP) && json.get(FIELD_SLEEP).isJsonObject()) {
            JsonObject sleep = json.get(FIELD_SLEEP).getAsJsonObject();
            updateIntMinutesChannel(CHANNEL_SLEEP_DAY, sleep, FIELD_MINUTES_DAY_SLEEP);
            updateIntMinutesChannel(CHANNEL_SLEEP_NIGHT, sleep, FIELD_MINUTES_NIGHT_SLEEP);
            updateIntMinutesChannel(CHANNEL_SLEEP_CALM, sleep, FIELD_MINUTES_CALM);
        }
        if (json.has(FIELD_RESTING_HEART_RATE) && json.get(FIELD_RESTING_HEART_RATE).isJsonObject()) {
            JsonObject rhr = json.get(FIELD_RESTING_HEART_RATE).getAsJsonObject();
            updateStringChannel(CHANNEL_RESTING_HEART_RATE_STATUS, rhr, FIELD_STATUS);
            updateIntChannel(CHANNEL_RESTING_HEART_RATE_DAY_OFFSET, rhr, FIELD_DAY_OFFSET);
        }
        if (json.has(FIELD_RESTING_RESPIRATORY_RATE) && json.get(FIELD_RESTING_RESPIRATORY_RATE).isJsonObject()) {
            JsonObject rrr = json.get(FIELD_RESTING_RESPIRATORY_RATE).getAsJsonObject();
            updateStringChannel(CHANNEL_RESTING_RESPIRATORY_RATE_STATUS, rrr, FIELD_STATUS);
            updateIntChannel(CHANNEL_RESTING_RESPIRATORY_RATE_DAY_OFFSET, rrr, FIELD_DAY_OFFSET);
        }
        if (json.has(FIELD_BARK) && json.get(FIELD_BARK).isJsonObject()) {
            JsonObject bark = json.get(FIELD_BARK).getAsJsonObject();
            updateStringChannel(CHANNEL_BARK, bark, FIELD_STATUS);
            updateIntChannel(CHANNEL_BARK_DAY_OFFSET, bark, FIELD_DAY_OFFSET);
        }
        if (json.has(FIELD_SCRATCH) && json.get(FIELD_SCRATCH).isJsonObject()) {
            JsonObject scratch = json.get(FIELD_SCRATCH).getAsJsonObject();
            updateStringChannel(CHANNEL_SCRATCH, scratch, FIELD_STATUS);
            updateIntChannel(CHANNEL_SCRATCH_DAY_OFFSET, scratch, FIELD_DAY_OFFSET);
        }
        if (json.has(FIELD_HEALTH_ALERTS) && json.get(FIELD_HEALTH_ALERTS).isJsonObject()) {
            JsonObject alerts = json.get(FIELD_HEALTH_ALERTS).getAsJsonObject();
            if (isLinked(CHANNEL_UNSEEN_HEALTH_ALERTS) && alerts.has(FIELD_UNSEEN_COUNT)) {
                updateState(CHANNEL_UNSEEN_HEALTH_ALERTS, new DecimalType(alerts.get(FIELD_UNSEEN_COUNT).getAsInt()));
            }
        }
        if (json.has(FIELD_SEPARATION_PHASE_STATUS) && json.get(FIELD_SEPARATION_PHASE_STATUS).isJsonObject()) {
            boolean separationOngoingChannelLinked = isLinked(CHANNEL_SEPARATION_PHASE_ONGOING);
            boolean separationStartTimeChannelLinked = isLinked(CHANNEL_SEPARATION_PHASE_STARTED_AT);
            if (separationOngoingChannelLinked || separationStartTimeChannelLinked) {
                JsonObject phase = json.get(FIELD_SEPARATION_PHASE_STATUS).getAsJsonObject();
                if (phase.has(FIELD_IS_PHASE_ONGOING) && !phase.get(FIELD_IS_PHASE_ONGOING).isJsonNull()) {
                    if (separationOngoingChannelLinked) {
                        updateState(CHANNEL_SEPARATION_PHASE_ONGOING,
                                OnOffType.from(phase.get(FIELD_IS_PHASE_ONGOING).getAsBoolean()));
                    }
                    if (separationStartTimeChannelLinked) {
                        updateTimestampChannel(CHANNEL_SEPARATION_PHASE_STARTED_AT, phase, FIELD_PHASE_STARTED_AT);
                    }
                }
            }
        }
        updateAssociatedReportTypes(json);
        updateTimestampChannel(CHANNEL_ACTIVITY_SYNCED_AT, json, FIELD_ACTIVITY_DATA_SYNCED_AT);
    }

    /**
     * Joins the {@code type} of every element of the {@code associatedData} array into one comma-separated string
     * and pushes it to {@link #CHANNEL_ASSOCIATED_REPORT_TYPES}.
     */
    private void updateAssociatedReportTypes(JsonObject json) {
        if (!isLinked(CHANNEL_ASSOCIATED_REPORT_TYPES) || !json.has(FIELD_ASSOCIATED_DATA)
                || !json.get(FIELD_ASSOCIATED_DATA).isJsonArray()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : json.get(FIELD_ASSOCIATED_DATA).getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonElement type = el.getAsJsonObject().get(FIELD_REPORT_TYPE);
            if (type == null || type.isJsonNull()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(type.getAsString());
        }
        updateState(CHANNEL_ASSOCIATED_REPORT_TYPES, new StringType(sb.toString()));
    }

    @Override
    protected String[] getHealthOverviewChannelIds() {
        return HEALTH_OVERVIEW_CHANNEL_IDS;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(TractiveDog6Actions.class);
    }
}
