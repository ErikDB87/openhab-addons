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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link TractiveBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveBindingConstants {

    private static final String BINDING_ID = "tractive";

    // Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_DOG6 = new ThingTypeUID(BINDING_ID, "dog-6");

    // Channel IDs — position group (group-id#channel-id)
    public static final String CHANNEL_LOCATION = "position#location";
    public static final String CHANNEL_LAST_POSITION_TIME = "position#last-position-time";
    public static final String CHANNEL_SPEED = "position#speed";
    public static final String CHANNEL_ALTITUDE = "position#altitude";
    public static final String CHANNEL_SENSOR_USED = "position#sensor-used";
    public static final String CHANNEL_POSITION_ACCURACY = "position#position-accuracy";

    // Channel IDs — hardware group
    public static final String CHANNEL_BATTERY_LEVEL = "hardware#battery-level";
    public static final String CHANNEL_CHARGING_STATE = "hardware#charging-state";
    public static final String CHANNEL_BATTERY_STATE = "hardware#battery-state";
    public static final String CHANNEL_TRACKER_STATE = "hardware#tracker-state";

    // Channel IDs — commands group (writable)
    public static final String CHANNEL_BUZZER = "commands#buzzer";
    public static final String CHANNEL_LED = "commands#led";
    public static final String CHANNEL_LIVE_TRACKING = "commands#live-tracking";

    // Channel IDs — health group
    public static final String CHANNEL_ACTIVITY_MINUTES_ACTIVE = "health#activity-minutes-active";
    public static final String CHANNEL_ACTIVITY_MINUTES_GOAL = "health#activity-minutes-goal";
    public static final String CHANNEL_SLEEP_MINUTES_DAY = "health#sleep-minutes-day";
    public static final String CHANNEL_SLEEP_MINUTES_NIGHT = "health#sleep-minutes-night";
    public static final String CHANNEL_SLEEP_MINUTES_CALM = "health#sleep-minutes-calm";
    public static final String CHANNEL_RESTING_HEART_RATE_STATUS = "health#resting-heart-rate-status";
    public static final String CHANNEL_RESTING_RESPIRATORY_RATE_STATUS = "health#resting-respiratory-rate-status";
    public static final String CHANNEL_UNSEEN_HEALTH_ALERTS = "health#unseen-health-alerts";
    public static final String CHANNEL_ACTIVITY_SYNCED_AT = "health#activity-synced-at";
    public static final String CHANNEL_BARK = "health#bark";
    public static final String CHANNEL_SCRATCH = "health#scratch";

    // Tractive API constants
    public static final String API_CLIENT_ID = "625e533dc3c3b41c28a669f0";
    public static final String API_BASE_URL = "https://graph.tractive.com/4/";
    public static final String APS_BASE_URL = "https://aps-api.tractive.com/api/1/";
    public static final String CHANNEL_URL = "https://channel.tractive.com/3/channel";
}
