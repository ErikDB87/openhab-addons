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

import java.util.Map;
import java.util.Set;

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

    public static final String BINDING_ID = "tractive";

    // Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");

    // Model Dog 6
    public static final ThingTypeUID THING_TYPE_DOG6 = new ThingTypeUID(BINDING_ID, "dog-6");
    public static final String MODEL_DOG6 = "TG6C";
    public static final String MODEL_NAME_DOG6 = "Dog 6";

    // Set and Map of all implemented models
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_DOG6);
    public static final Map<String, String> MODEL_NAMES = Map.of(MODEL_DOG6, MODEL_NAME_DOG6);

    // Channel IDs — position group (group-id#channel-id)
    public static final String CHANNEL_LOCATION = "position#location";
    public static final String CHANNEL_LAST_POSITION_TIME = "position#last-position-time";
    public static final String CHANNEL_SPEED = "position#speed";
    public static final String CHANNEL_SENSOR_USED = "position#sensor-used";
    public static final String CHANNEL_POSITION_ACCURACY = "position#position-accuracy";

    // Channel IDs — hardware group
    public static final String CHANNEL_BATTERY_LEVEL = "hardware#battery-level";
    public static final String CHANNEL_CHARGING_STATE = "hardware#charging-state";
    public static final String CHANNEL_BATTERY_STATE = "hardware#battery-state"; // EDB: I doubt this is a useful
                                                                                 // channel; it only reports "REGULAR"
                                                                                 // or "FULL". Better to go with the
                                                                                 // actual number.
    public static final String CHANNEL_TRACKER_STATE = "hardware#tracker-state"; // EDB: I'm not sure this is a useful
                                                                                 // channel; I've only ever seen it be
                                                                                 // "OPERATIONAL".
    public static final String CHANNEL_LAST_CONTACT = "hardware#last-contact";

    // Channel IDs — commands group (writable)
    public static final String CHANNEL_BUZZER = "commands#buzzer";
    public static final String CHANNEL_LED = "commands#led";
    public static final String CHANNEL_LIVE_TRACKING = "commands#live-tracking";

    // Channel IDs — health group
    public static final String CHANNEL_ACTIVITY_RECORDED = "health#activity-recorded";
    public static final String CHANNEL_ACTIVITY_GOAL = "health#activity-goal";
    public static final String CHANNEL_SLEEP_DAY = "health#sleep-day";
    public static final String CHANNEL_SLEEP_NIGHT = "health#sleep-night";
    public static final String CHANNEL_SLEEP_CALM = "health#sleep-calm";
    public static final String CHANNEL_RESTING_HEART_RATE_STATUS = "health#resting-heart-rate-status";
    public static final String CHANNEL_RESTING_RESPIRATORY_RATE_STATUS = "health#resting-respiratory-rate-status";
    public static final String CHANNEL_UNSEEN_HEALTH_ALERTS = "health#unseen-health-alerts";
    public static final String CHANNEL_ACTIVITY_SYNCED_AT = "health#activity-synced-at";
    public static final String CHANNEL_SCRATCH = "health#scratch";

    // Channel IDs — dog group
    public static final String CHANNEL_BARK = "dog#bark";

    // Tractive API JSON field names — auth request body (POST auth/token)
    public static final String FIELD_PLATFORM_EMAIL = "platform_email";
    public static final String FIELD_PLATFORM_TOKEN = "platform_token";
    public static final String FIELD_GRANT_TYPE = "grant_type";
    public static final String VALUE_GRANT_TYPE_TRACTIVE = "tractive";

    // Tractive API JSON field names — auth response body
    public static final String FIELD_ACCESS_TOKEN = "access_token";
    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_EXPIRES_AT = "expires_at";

    // Tractive API JSON field names — common envelope (REST responses & channel events)
    public static final String FIELD_ID = "_id";
    public static final String FIELD_MESSAGE = "message";

    // Real-time channel message/type literal values
    public static final String MESSAGE_KEEP_ALIVE = "keep-alive";
    public static final String MESSAGE_HANDSHAKE = "handshake";
    public static final String MESSAGE_TRACKER_STATUS = "tracker_status";
    public static final String MESSAGE_HEALTH_OVERVIEW = "health_overview";

    // Real-time channel ("tracker_status" / "health_overview") field names
    public static final String FIELD_TRACKER_ID = "tracker_id";
    public static final String FIELD_TRACKER_STATE_LIVE = "tracker_state";
    public static final String FIELD_POSITION = "position";
    public static final String FIELD_HARDWARE = "hardware";
    public static final String FIELD_ACCURACY = "accuracy";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_PET_ID = "petId";

    // device_pos_report fields
    public static final String FIELD_LATLONG = "latlong";
    public static final String FIELD_ALTITUDE = "altitude";
    public static final String FIELD_TIME = "time";
    public static final String FIELD_SPEED = "speed";
    public static final String FIELD_SENSOR_USED = "sensor_used";
    public static final String FIELD_POS_UNCERTAINTY = "pos_uncertainty";

    // device_hw_report / tracker fields
    public static final String FIELD_BATTERY_LEVEL = "battery_level";
    public static final String FIELD_TRACKER_STATE = "state";
    public static final String FIELD_CHARGING_STATE = "charging_state";
    public static final String FIELD_BATTERY_STATE = "battery_state";
    public static final String FIELD_MODEL_NUMBER = "model_number";

    // health/overview fields
    public static final String FIELD_ACTIVITY = "activity";
    public static final String FIELD_SLEEP = "sleep";
    public static final String FIELD_RESTING_HEART_RATE = "restingHeartRate";
    public static final String FIELD_RESTING_RESPIRATORY_RATE = "restingRespiratoryRate";
    public static final String FIELD_BARK = "bark";
    public static final String FIELD_SCRATCH = "scratch";
    public static final String FIELD_HEALTH_ALERTS = "healthAlerts";
    public static final String FIELD_UNSEEN_COUNT = "unseenCount";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_ACTIVITY_DATA_SYNCED_AT = "activityDataSyncedAt";
    public static final String FIELD_MINUTES_ACTIVE = "minutesActive";
    public static final String FIELD_MINUTES_GOAL = "minutesGoal";
    public static final String FIELD_MINUTES_DAY_SLEEP = "minutesDaySleep";
    public static final String FIELD_MINUTES_NIGHT_SLEEP = "minutesNightSleep";
    public static final String FIELD_MINUTES_CALM = "minutesCalm";

    // trackable_object (discovery) fields
    public static final String FIELD_DEVICE_ID = "device_id";
    public static final String FIELD_DETAILS = "details";
    public static final String FIELD_NAME = "name";

    // Tracker command names and on/off states
    public static final String COMMAND_BUZZER_CONTROL = "buzzer_control";
    public static final String COMMAND_LED_CONTROL = "led_control";
    public static final String COMMAND_LIVE_TRACKING = "live_tracking";
    public static final String STATE_ON = "on";
    public static final String STATE_OFF = "off";

    // HTTP headers sent on every Tractive API request
    public static final String HEADER_TRACTIVE_CLIENT = "x-tractive-client";
    public static final String HEADER_TRACTIVE_USER = "x-tractive-user";
    public static final String HEADER_AUTHORIZATION = "authorization";
    public static final String HEADER_CONTENT_TYPE = "content-type";
    public static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";

    // Tractive API constants
    public static final String API_CLIENT_ID = "625e533dc3c3b41c28a669f0";
    public static final String API_BASE_URL = "https://graph.tractive.com/4/";
    public static final String APS_BASE_URL = "https://aps-api.tractive.com/api/1/";
    public static final String CHANNEL_URL = "https://channel.tractive.com/3/channel";
}
