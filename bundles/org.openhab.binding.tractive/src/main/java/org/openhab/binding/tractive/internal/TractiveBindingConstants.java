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

    /** Binding ID, used to build every {@link ThingTypeUID} and as the OSGi component identifier. */
    public static final String BINDING_ID = "tractive";

    /** Thing type UID for the account bridge, which holds credentials and manages authentication. */
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");

    /** Thing type UID for the Dog 6 tracker. */
    public static final ThingTypeUID THING_TYPE_DOG6 = new ThingTypeUID(BINDING_ID, "dog-6");
    /** Tractive API {@code model_number} value identifying a Dog 6 tracker. */
    public static final String MODEL_DOG6 = "TG6C";
    /** Human-readable display name for the Dog 6 model, used in discovery result labels. */
    public static final String MODEL_NAME_DOG6 = "Dog 6";

    /** Every thing type this binding can create a handler for. */
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_DOG6);
    /** Maps a Tractive API {@code model_number} (e.g. {@link #MODEL_DOG6}) to its display name. */
    public static final Map<String, String> MODEL_NAMES = Map.of(MODEL_DOG6, MODEL_NAME_DOG6);

    /** Channel ID for the tracker's current position. */
    public static final String CHANNEL_LOCATION = "position#location";
    /** Channel ID for the timestamp of the last position report. */
    public static final String CHANNEL_LAST_POSITION_TIME = "position#last-position-time";
    /** Channel ID for the tracker's current speed; {@code null} from the API is mapped to 0, never left unset. */
    public static final String CHANNEL_SPEED = "position#speed";
    /**
     * Channel ID for which sensor produced the last position fix (e.g. {@code GPS}, {@code KNOWN_WIFI}, {@code PHONE}).
     */
    public static final String CHANNEL_SENSOR_USED = "position#sensor-used";
    /** Channel ID for the last position fix's accuracy/uncertainty radius. */
    public static final String CHANNEL_POSITION_ACCURACY = "position#position-accuracy";

    /** Channel ID for battery level percentage. */
    public static final String CHANNEL_BATTERY_LEVEL = "hardware#battery-level";
    /** Channel ID for the tracker's charging state (e.g. {@code NOT_CHARGING}). */
    public static final String CHANNEL_CHARGING_STATE = "hardware#charging-state";
    /** Channel ID for the tracker's coarse battery state (e.g. {@code REGULAR}, {@code FULL}). */
    public static final String CHANNEL_BATTERY_STATE = "hardware#battery-state";
    /** Channel ID for the tracker's operational state (e.g. {@code OPERATIONAL}). */
    public static final String CHANNEL_TRACKER_STATE = "hardware#tracker-state";
    /**
     * Channel ID for the timestamp of the most recent successful contact via any REST poll or real-time channel event;
     * a lightweight stalled-tracker detector.
     */
    public static final String CHANNEL_LAST_CONTACT = "hardware#last-contact";
    /** Channel ID for why the tracker's operational state is what it is (e.g. {@code POWER_SAVING}). */
    public static final String CHANNEL_TRACKER_STATE_REASON = "hardware#tracker-state-reason";
    /** Channel ID for the ID of the geofence zone currently most relevant to the tracker's position. */
    public static final String CHANNEL_ZONE_ID = "hardware#zone-id";
    /**
     * Channel ID for the kind of the currently most relevant geofence zone
     * ({@code POWER_SAVING}/{@code SAFE}/{@code DANGER}).
     */
    public static final String CHANNEL_ZONE_TYPE = "hardware#zone-type";
    /** Channel ID for when the tracker entered its currently most relevant geofence zone. */
    public static final String CHANNEL_ZONE_ENTERED_AT = "hardware#zone-entered-at";
    /** Channel ID for when the tracker was last confirmed still inside its currently most relevant geofence zone. */
    public static final String CHANNEL_ZONE_LAST_SEEN_AT = "hardware#zone-last-seen-at";
    /** Channel ID for the ID of the tracker's configured Power Saving Zone. */
    public static final String CHANNEL_POWER_SAVING_ZONE_ID = "hardware#power-saving-zone-id";
    /** Channel ID for the tracker's hardware model code (e.g. {@code TG6C}). */
    public static final String CHANNEL_MODEL_NUMBER = "hardware#model-number";
    /** Channel ID for the tracker's hardware color/edition variant (e.g. {@code BROWN-LINES}). */
    public static final String CHANNEL_HW_EDITION = "hardware#hw-edition";
    /** Channel ID for the tracker's onboard firmware version string. */
    public static final String CHANNEL_FIRMWARE_VERSION = "hardware#firmware-version";
    /** Channel ID for the configured geofence entry/exit detection sensitivity. */
    public static final String CHANNEL_GEOFENCE_SENSITIVITY = "hardware#geofence-sensitivity";

    /** Channel ID for the buzzer Switch. */
    public static final String CHANNEL_BUZZER = "commands#buzzer";
    /** Channel ID for the LED Switch. */
    public static final String CHANNEL_LED = "commands#led";
    /** Channel ID for the live-tracking Switch. */
    public static final String CHANNEL_LIVE_TRACKING = "commands#live-tracking";
    /** Channel ID for the buzzer's configured auto-stop duration. */
    public static final String CHANNEL_BUZZER_TIMEOUT = "commands#buzzer-timeout";
    /** Channel ID for seconds remaining before the buzzer auto-stops. */
    public static final String CHANNEL_BUZZER_REMAINING = "commands#buzzer-remaining";
    /** Channel ID for the LED's configured auto-stop duration. */
    public static final String CHANNEL_LED_TIMEOUT = "commands#led-timeout";
    /** Channel ID for seconds remaining before the LED auto-stops. */
    public static final String CHANNEL_LED_REMAINING = "commands#led-remaining";
    /** Channel ID for live tracking's configured auto-stop duration. */
    public static final String CHANNEL_LIVE_TRACKING_TIMEOUT = "commands#live-tracking-timeout";
    /** Channel ID for seconds remaining before live tracking auto-stops. */
    public static final String CHANNEL_LIVE_TRACKING_REMAINING = "commands#live-tracking-remaining";

    /** Channel ID for the duration of activity recorded today. */
    public static final String CHANNEL_ACTIVITY_RECORDED = "health#activity-recorded";
    /** Channel ID for the daily activity duration goal. */
    public static final String CHANNEL_ACTIVITY_GOAL = "health#activity-goal";
    /** Channel ID for the duration of daytime sleep. */
    public static final String CHANNEL_SLEEP_DAY = "health#sleep-day";
    /** Channel ID for the duration of nighttime sleep. */
    public static final String CHANNEL_SLEEP_NIGHT = "health#sleep-night";
    /** Channel ID for the duration spent calm. */
    public static final String CHANNEL_SLEEP_CALM = "health#sleep-calm";
    /** Channel ID for the resting heart rate status (e.g. {@code NORMAL}). */
    public static final String CHANNEL_RESTING_HEART_RATE_STATUS = "health#resting-heart-rate-status";
    /** Channel ID for the resting respiratory rate status (e.g. {@code NORMAL}). */
    public static final String CHANNEL_RESTING_RESPIRATORY_RATE_STATUS = "health#resting-respiratory-rate-status";
    /** Channel ID for the count of unseen health alerts. */
    public static final String CHANNEL_UNSEEN_HEALTH_ALERTS = "health#unseen-health-alerts";
    /** Channel ID for when activity/health data was last synced. */
    public static final String CHANNEL_ACTIVITY_SYNCED_AT = "health#activity-synced-at";
    /** Channel ID for scratch status (e.g. {@code NORMAL}, {@code ELEVATED}, {@code NOT_ENOUGH_DATA_TODAY}). */
    public static final String CHANNEL_SCRATCH = "health#scratch";

    /** Channel ID for bark status (e.g. {@code INFREQUENT}, {@code CALCULATING_BASELINE}). */
    public static final String CHANNEL_BARK = "dog#bark";

    /**
     * Channel ID for the pet's breed-catalog ID(s), comma-separated. Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String CHANNEL_BREED_IDS = "profile#breed-ids";
    /** Channel ID for the pet's sex. Only refreshed at Thing setup or via {@code refreshProfile()}. */
    public static final String CHANNEL_GENDER = "profile#gender";
    /** Channel ID for the pet's date of birth. Only refreshed at Thing setup or via {@code refreshProfile()}. */
    public static final String CHANNEL_BIRTHDAY = "profile#birthday";
    /** Channel ID for the pet's height. Only refreshed at Thing setup or via {@code refreshProfile()}. */
    public static final String CHANNEL_HEIGHT = "profile#height";
    /** Channel ID for the pet's weight. Only refreshed at Thing setup or via {@code refreshProfile()}. */
    public static final String CHANNEL_WEIGHT = "profile#weight";
    /**
     * Channel ID for whether the pet is spayed/neutered. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String CHANNEL_NEUTERED = "profile#neutered";
    /**
     * Channel ID for the pet's configured home location. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String CHANNEL_HOME_LOCATION = "profile#home-location";
    /** Channel ID for when the Profile channel group was last fetched. */
    public static final String CHANNEL_PROFILE_LAST_UPDATED = "profile#last-updated";

    /** JSON field name for the account email in the {@code POST auth/token} request body. */
    public static final String FIELD_PLATFORM_EMAIL = "platform_email";
    /** JSON field name for the account password in the {@code POST auth/token} request body. */
    public static final String FIELD_PLATFORM_TOKEN = "platform_token";
    /** JSON field name for the OAuth-style grant type in the {@code POST auth/token} request body. */
    public static final String FIELD_GRANT_TYPE = "grant_type";
    /** Value of {@link #FIELD_GRANT_TYPE} required by the Tractive {@code auth} endpoint. */
    public static final String VALUE_GRANT_TYPE_TRACTIVE = "tractive";

    /** JSON field name for the bearer access token in the {@code auth} response body. */
    public static final String FIELD_ACCESS_TOKEN = "access_token";
    /** JSON field name for the authenticated user's ID in the {@code auth} response body. */
    public static final String FIELD_USER_ID = "user_id";
    /** JSON field name for the access token's expiry, as a Unix epoch, in the {@code auth} response body. */
    public static final String FIELD_EXPIRES_AT = "expires_at";

    /** JSON field name for an object's ID, common to most {@code REST} responses. */
    public static final String FIELD_ID = "_id";
    /** JSON field name for the real-time channel message's type discriminator. */
    public static final String FIELD_MESSAGE = "message";

    /** Real-time channel {@link #FIELD_MESSAGE} value for a keep-alive heartbeat; filtered out before dispatch. */
    public static final String MESSAGE_KEEP_ALIVE = "keep-alive";
    /**
     * Real-time channel {@link #FIELD_MESSAGE} value for the initial connection handshake; filtered out before
     * dispatch.
     */
    public static final String MESSAGE_HANDSHAKE = "handshake";
    /** Real-time channel {@link #FIELD_MESSAGE} value for a combined position/hardware/command-state push. */
    public static final String MESSAGE_TRACKER_STATUS = "tracker_status";
    /** Real-time channel {@link #FIELD_MESSAGE} value for a health/overview push. */
    public static final String MESSAGE_HEALTH_OVERVIEW = "health_overview";
    /**
     * Real-time channel {@link #FIELD_MESSAGE} value for a buzzer/LED/live-tracking command that timed out before the
     * tracker executed it.
     */
    public static final String MESSAGE_START_FAILED = "start_failed";
    /**
     * Real-time channel {@link #FIELD_MESSAGE} value acking a routine tracker report (vitality/activity/bark/VeDBA) —
     * despite the name, not a buzzer/LED/live-tracking command confirmation. Carries no actionable payload beyond which
     * report type was processed.
     */
    public static final String MESSAGE_COMMAND_CONFIRMED = "command_confirmed";
    /**
     * Real-time channel {@link #FIELD_MESSAGE} value notifying that new activity/health data is available for a pet on
     * a given local calendar date. Deliberately unconsumed: this push is immediately followed by a
     * {@link #MESSAGE_HEALTH_OVERVIEW} push carrying the actual updated content, which {@code onChannelEvent()} already
     * applies via its existing case — reacting to this message separately would only trigger a redundant REST call.
     */
    public static final String MESSAGE_ACTIVITY_DATA_UPDATED = "activity_data_updated";

    /** JSON field name for which command timed out, in a {@link #MESSAGE_START_FAILED} push. */
    public static final String FIELD_COMMAND_TYPE = "command_type";
    /** JSON field name for why a command timed out, in a {@link #MESSAGE_START_FAILED} push. */
    public static final String FIELD_CANCELLATION_REASON = "cancellation_reason";
    /** {@link #FIELD_COMMAND_TYPE} value for a failed LED command. */
    public static final String VALUE_COMMAND_TYPE_LED = "MSG_S2D_LED_CONTROL";
    /** {@link #FIELD_COMMAND_TYPE} value for a failed buzzer command. */
    public static final String VALUE_COMMAND_TYPE_BUZZER = "MSG_S2D_BUZZER_CONTROL";
    /** {@link #FIELD_COMMAND_TYPE} value for a failed live-tracking command. */
    public static final String VALUE_COMMAND_TYPE_LIVE_TRACKING = "MSG_S2D_LIVE_TRACKING_MODE";

    /** JSON field name for the tracker ID in a {@link #MESSAGE_TRACKER_STATUS} push. */
    public static final String FIELD_TRACKER_ID = "tracker_id";
    /** JSON field name for the tracker's live operational state in a {@link #MESSAGE_TRACKER_STATUS} push. */
    public static final String FIELD_TRACKER_STATE_LIVE = "tracker_state";
    /** JSON field name for the nested position object in a {@link #MESSAGE_TRACKER_STATUS} push. */
    public static final String FIELD_POSITION = "position";
    /** JSON field name for the nested hardware object in a {@link #MESSAGE_TRACKER_STATUS} push. */
    public static final String FIELD_HARDWARE = "hardware";
    /**
     * JSON field name for position accuracy on the real-time channel; equivalent to {@link #FIELD_POS_UNCERTAINTY} in
     * REST.
     */
    public static final String FIELD_ACCURACY = "accuracy";
    /** JSON field name for the nested payload in a {@link #MESSAGE_HEALTH_OVERVIEW} push. */
    public static final String FIELD_CONTENT = "content";
    /** JSON field name for the pet ID inside a {@link #MESSAGE_HEALTH_OVERVIEW} push's {@link #FIELD_CONTENT}. */
    public static final String FIELD_PET_ID = "petId";
    /** JSON field name for whether a buzzer/LED/live-tracking command is currently active. */
    public static final String FIELD_ACTIVE = "active";
    /**
     * JSON field name for seconds remaining on an active buzzer/LED/live-tracking command; drives the buzzer auto-off
     * prediction.
     */
    public static final String FIELD_REMAINING = "remaining";
    /** JSON field name for a buzzer/LED/live-tracking control object's configured auto-stop duration in seconds. */
    public static final String FIELD_TIMEOUT = "timeout";

    /** JSON field name for the {@code [latitude, longitude]} position array. */
    public static final String FIELD_LATLONG = "latlong";
    /** JSON field name for altitude in meters. */
    public static final String FIELD_ALTITUDE = "altitude";
    /** JSON field name for a report's timestamp, as a Unix epoch. */
    public static final String FIELD_TIME = "time";
    /** JSON field name for speed in m/s; nullable, mapped to 0 when absent. */
    public static final String FIELD_SPEED = "speed";
    /** JSON field name for which sensor produced a REST position fix (e.g. GPS, KNOWN_WIFI, PHONE). */
    public static final String FIELD_SENSOR_USED = "sensor_used";
    /** JSON field name for a REST position fix's accuracy/uncertainty radius in meters. */
    public static final String FIELD_POS_UNCERTAINTY = "pos_uncertainty";

    /** JSON field name for battery level as a 0-100 integer. */
    public static final String FIELD_BATTERY_LEVEL = "battery_level";
    /** JSON field name for the tracker's operational state in REST responses. */
    public static final String FIELD_TRACKER_STATE = "state";
    /**
     * JSON field name for the REST reason behind the tracker's state (e.g. {@link #VALUE_STATE_REASON_POWER_SAVING}).
     */
    public static final String FIELD_STATE_REASON = "state_reason";
    /** JSON field name for the tracker's charging state. */
    public static final String FIELD_CHARGING_STATE = "charging_state";
    /** JSON field name for the tracker's coarse battery state (e.g. REGULAR, FULL). */
    public static final String FIELD_BATTERY_STATE = "battery_state";
    /**
     * JSON field name for the tracker's model number, in {@code tracker/{trackerId}}. Used to resolve its thing type
     * during discovery, and feeds {@link #CHANNEL_MODEL_NUMBER} on the running thing.
     */
    public static final String FIELD_MODEL_NUMBER = "model_number";
    /** JSON field name for the tracker's onboard firmware version string, in {@code tracker/{trackerId}}. */
    public static final String FIELD_FW_VERSION = "fw_version";
    /** JSON field name for the tracker's hardware color/edition variant, in {@code tracker/{trackerId}}. */
    public static final String FIELD_HW_EDITION = "hw_edition";
    /** JSON field name for the configured geofence entry/exit detection sensitivity, in {@code tracker/{trackerId}}. */
    public static final String FIELD_GEOFENCE_SENSITIVITY = "geofence_sensitivity";
    /**
     * JSON field name for the real-time channel's equivalent of {@link #FIELD_STATE_REASON} (same meaning, different
     * key). Gates the buzzer auto-off prediction.
     */
    public static final String FIELD_TRACKER_STATE_REASON = "tracker_state_reason";
    /**
     * Value of {@link #FIELD_STATE_REASON} / {@link #FIELD_TRACKER_STATE_REASON} indicating the tracker is in its
     * power-saving zone (radio may be dormant).
     */
    public static final String VALUE_STATE_REASON_POWER_SAVING = "POWER_SAVING";

    /** JSON field name for the nested prioritized-zone object in a {@link #MESSAGE_TRACKER_STATUS} push. */
    public static final String FIELD_PRIORITIZED_ZONE = "prioritized_zone";
    /** JSON field name for a zone's kind, inside {@link #FIELD_PRIORITIZED_ZONE}. */
    public static final String FIELD_ZONE_TYPE = "type";
    /**
     * JSON field name for when the tracker was last confirmed inside a zone, inside {@link #FIELD_PRIORITIZED_ZONE}.
     */
    public static final String FIELD_ZONE_LAST_SEEN_AT = "last_seen_at";
    /** JSON field name for when the tracker entered a zone, inside {@link #FIELD_PRIORITIZED_ZONE}. */
    public static final String FIELD_ZONE_ENTERED_AT = "entered_at";
    /** JSON field name for the REST-flattened form of the prioritized zone's ID, in {@code tracker/{trackerId}}. */
    public static final String FIELD_PRIORITIZED_ZONE_ID = "prioritized_zone_id";
    /** JSON field name for the REST-flattened form of the prioritized zone's kind, in {@code tracker/{trackerId}}. */
    public static final String FIELD_PRIORITIZED_ZONE_TYPE = "prioritized_zone_type";
    /**
     * JSON field name for the REST-flattened form of {@link #FIELD_ZONE_LAST_SEEN_AT}, in {@code tracker/{trackerId}}.
     */
    public static final String FIELD_PRIORITIZED_ZONE_LAST_SEEN_AT = "prioritized_zone_last_seen_at";
    /**
     * JSON field name for the REST-flattened form of {@link #FIELD_ZONE_ENTERED_AT}, in {@code tracker/{trackerId}}.
     */
    public static final String FIELD_PRIORITIZED_ZONE_ENTERED_AT = "prioritized_zone_entered_at";
    /**
     * JSON field name for the ID of the tracker's configured Power Saving Zone; same key in both REST
     * ({@code tracker/{trackerId}}) and the real-time channel's {@link #MESSAGE_TRACKER_STATUS} push (top-level,
     * not inside {@link #FIELD_PRIORITIZED_ZONE}).
     */
    public static final String FIELD_POWER_SAVING_ZONE_ID = "power_saving_zone_id";

    /** JSON field name for the nested activity object in a health/overview response; may be JSON null. */
    public static final String FIELD_ACTIVITY = "activity";
    /** JSON field name for the nested sleep object in a health/overview response; may be JSON null. */
    public static final String FIELD_SLEEP = "sleep";
    /** JSON field name for the nested resting heart rate object in a health/overview response. */
    public static final String FIELD_RESTING_HEART_RATE = "restingHeartRate";
    /** JSON field name for the nested resting respiratory rate object in a health/overview response. */
    public static final String FIELD_RESTING_RESPIRATORY_RATE = "restingRespiratoryRate";
    /** JSON field name for the nested bark detection object in a health/overview response; may be JSON null. */
    public static final String FIELD_BARK = "bark";
    /** JSON field name for the nested scratch detection object in a health/overview response; may be JSON null. */
    public static final String FIELD_SCRATCH = "scratch";
    /** JSON field name for the nested health alerts object in a health/overview response. */
    public static final String FIELD_HEALTH_ALERTS = "healthAlerts";
    /** JSON field name for the count of unseen health alerts. */
    public static final String FIELD_UNSEEN_COUNT = "unseenCount";
    /** JSON field name for a status enum, shared by several nested health objects (e.g. bark, scratch). */
    public static final String FIELD_STATUS = "status";
    /**
     * JSON field name for when activity/health data was last synced; epoch integer in REST, ISO 8601 string on the
     * real-time channel.
     */
    public static final String FIELD_ACTIVITY_DATA_SYNCED_AT = "activityDataSyncedAt";
    /** JSON field name for minutes of activity recorded today. */
    public static final String FIELD_MINUTES_ACTIVE = "minutesActive";
    /** JSON field name for the daily activity goal in minutes. */
    public static final String FIELD_MINUTES_GOAL = "minutesGoal";
    /** JSON field name for minutes of daytime sleep. */
    public static final String FIELD_MINUTES_DAY_SLEEP = "minutesDaySleep";
    /** JSON field name for minutes of nighttime sleep. */
    public static final String FIELD_MINUTES_NIGHT_SLEEP = "minutesNightSleep";
    /** JSON field name for minutes spent calm. */
    public static final String FIELD_MINUTES_CALM = "minutesCalm";

    /** JSON field name linking a pet ({@code trackable_object}) back to its tracker ID. */
    public static final String FIELD_DEVICE_ID = "device_id";
    /** JSON field name for the nested pet details object in a {@code trackable_object} response. */
    public static final String FIELD_DETAILS = "details";
    /** JSON field name for the pet's name, used in discovery result labels. */
    public static final String FIELD_NAME = "name";
    /** JSON field name for the pet's sex, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_GENDER = "gender";
    /** JSON field name for the pet's date of birth (epoch), inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_BIRTHDAY = "birthday";
    /** JSON field name for the pet's height in meters, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_HEIGHT = "height";
    /** JSON field name for the pet's weight in grams, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_WEIGHT = "weight";
    /** JSON field name for whether the pet is spayed/neutered, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_NEUTERED = "neutered";
    /** JSON field name for the pet's breed-catalog ID(s) array, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_BREED_IDS = "breed_ids";
    /**
     * JSON field name for the pet's configured home location {@code [latitude, longitude]}, in
     * {@code trackable_object/{petId}}.
     */
    public static final String FIELD_HOME_LOCATION = "home_location";

    /**
     * Tracker command name for the buzzer; also the {@link #MESSAGE_TRACKER_STATUS} field name carrying its confirmed
     * device state ({@code buzzer_control.active}).
     */
    public static final String COMMAND_BUZZER_CONTROL = "buzzer_control";
    /**
     * Tracker command name for the LED; also the {@link #MESSAGE_TRACKER_STATUS} field name carrying its confirmed
     * device state ({@code led_control.active}).
     */
    public static final String COMMAND_LED_CONTROL = "led_control";
    /**
     * Tracker command name for live tracking; also the {@link #MESSAGE_TRACKER_STATUS} field name carrying its
     * confirmed device state ({@code live_tracking.active}).
     */
    public static final String COMMAND_LIVE_TRACKING = "live_tracking";
    /** URL path segment for switching a command ({@link #COMMAND_BUZZER_CONTROL} etc.) on. */
    public static final String STATE_ON = "on";
    /** URL path segment for switching a command ({@link #COMMAND_BUZZER_CONTROL} etc.) off. */
    public static final String STATE_OFF = "off";

    /** HTTP header carrying the Tractive API client ID ({@link #API_CLIENT_ID}) on every request. */
    public static final String HEADER_TRACTIVE_CLIENT = "x-tractive-client";
    /** HTTP header carrying the authenticated user ID on every request. */
    public static final String HEADER_TRACTIVE_USER = "x-tractive-user";
    /** HTTP header carrying the bearer access token on every request. */
    public static final String HEADER_AUTHORIZATION = "authorization";
    /** HTTP header name for the request content type. */
    public static final String HEADER_CONTENT_TYPE = "content-type";
    /** Content type value used for every Tractive API request. */
    public static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
    /** Prefix prepended to the access token in the {@link #HEADER_AUTHORIZATION} header. */
    public static final String AUTH_BEARER_PREFIX = "Bearer ";

    /**
     * Client ID sent as {@link #HEADER_TRACTIVE_CLIENT} and in the auth request; validated by Tractive's backend
     * against a real registry — see {@code doc/x-tractive-client-ids/README.md} for provenance and alternatives.
     */
    public static final String API_CLIENT_ID = "625e533dc3c3b41c28a669f0";
    /** Base URL for the main Tractive REST API. */
    public static final String API_BASE_URL = "https://graph.tractive.com/4/";
    /** Base URL for the APS (health/overview) REST API. */
    public static final String APS_BASE_URL = "https://aps-api.tractive.com/api/1/";
    /** URL for the real-time streaming NDJSON channel. */
    public static final String CHANNEL_URL = "https://channel.tractive.com/3/channel";
}
