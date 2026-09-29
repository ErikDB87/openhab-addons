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
    /** Human-readable model-family name for the Dog 6 tracker, used in discovery result labels. */
    public static final String MODEL_NAME_DOG6 = "Dog 6";

    /** Every thing type this binding can create a handler for. */
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_DOG6);
    /**
     * Maps every recognised Tractive API {@code model_number} to its thing type. {@code TG6A}/{@code TG6C}/{@code TG6D}
     * are hardware/colour variants of the same Dog 6 generation. {@code TG6C} has been verified against a real device,
     * the others are extracted from the Tractive Android app. To support a new tracker type, move its model
     * number(s) here from {@link #KNOWN_UNSUPPORTED_MODEL_NUMBERS} and add a matching entry to
     * {@link #THING_TYPE_MODEL_NAMES}.
     */
    public static final Map<String, ThingTypeUID> MODEL_THING_TYPES = Map.of("TG6A", THING_TYPE_DOG6, "TG6C",
            THING_TYPE_DOG6, "TG6D", THING_TYPE_DOG6);
    /** Maps each supported thing type to its human-readable model-family name (for discovery result labels). */
    public static final Map<ThingTypeUID, String> THING_TYPE_MODEL_NAMES = Map.of(THING_TYPE_DOG6, MODEL_NAME_DOG6);
    /**
     * Tractive {@code model_number} values this binding recognises from the Tractive Android app's own model
     * catalogue (2026-09-05) but does not yet implement. Lets discovery log a known-but-unsupported tracker with
     * a "not supported yet" note rather than the "unrecognised model number" wording reserved for a genuinely
     * new one. Kept disjoint from {@link #MODEL_THING_TYPES}.
     */
    public static final Set<String> KNOWN_UNSUPPORTED_MODEL_NUMBERS = Set.of("TG6XLC", "TG7A", "TG5", "TG4XL", "TG4410",
            "TG4422", "TRGPS2", "TRCAT1", "TRNJA4", "TRATR1", "TRAXL1", "TRDOG1");

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
    /**
     * Channel ID for a nearby-device/user ID accompanying a position fix, the real Tractive account whose phone is
     * BLE-near the tracker at that moment.
     */
    public static final String CHANNEL_NEARBY_USER_ID = "position#nearby-user-id";

    /** Channel ID for battery level percentage; kept live by the real-time channel, backstopped by REST polling. */
    public static final String CHANNEL_BATTERY_LEVEL = "hardware#battery-level";
    /**
     * Channel ID for a hardware temperature-related state; always {@code null} in every capture taken so far, so
     * its meaning remains unresolved.
     */
    public static final String CHANNEL_TEMPERATURE_STATE = "hardware#temperature-state";
    /**
     * Channel ID for the tracker's charging state (e.g. {@code NOT_CHARGING}). Lives in the {@code tracker-status}
     * group, not {@code hardware} -- see {@link #CHANNEL_TRACKER_STATE} for why.
     */
    public static final String CHANNEL_CHARGING_STATE = "tracker-status#charging-state";
    /**
     * Channel ID for the tracker's coarse battery state (e.g. {@code REGULAR}, {@code FULL}). See
     * {@link #CHANNEL_TRACKER_STATE}.
     */
    public static final String CHANNEL_BATTERY_STATE = "tracker-status#battery-state";
    /**
     * Channel ID for the tracker's operational state (e.g. {@code OPERATIONAL}). Lives in its own {@code
     * tracker-status} group, not {@code hardware}: unlike {@link #CHANNEL_BATTERY_LEVEL}, this data (and everything
     * else in that group) has no per-field timestamp to arbitrate freshness against the real-time channel, so REST
     * only ever seeds it once at Thing setup -- the real-time channel is authoritative for it from then on,
     * regardless of {@code refreshInterval}.
     */
    public static final String CHANNEL_TRACKER_STATE = "tracker-status#tracker-state";
    /**
     * Channel ID for the timestamp of the most recent successful contact via any REST poll (including the
     * {@code device-info}/{@code profile} one-shot fetches) or real-time channel event; a lightweight
     * stalled-tracker detector. Unlike its {@code tracker-status} group-mates, this one is fed by every
     * source in the binding, not just the real-time channel after an initial seed.
     */
    public static final String CHANNEL_LAST_CONTACT = "tracker-status#last-contact";
    /**
     * Channel ID for why the tracker's operational state is what it is (e.g. {@code POWER_SAVING}). See
     * {@link #CHANNEL_TRACKER_STATE}.
     */
    public static final String CHANNEL_TRACKER_STATE_REASON = "tracker-status#tracker-state-reason";
    /**
     * Channel ID for the ID of the geofence zone currently most relevant to the tracker's position. See
     * {@link #CHANNEL_TRACKER_STATE}.
     */
    public static final String CHANNEL_ZONE_ID = "tracker-status#zone-id";
    /**
     * Channel ID for the kind of the currently most relevant geofence zone
     * ({@code POWER_SAVING}/{@code SAFE}/{@code DANGER}). See {@link #CHANNEL_TRACKER_STATE}.
     */
    public static final String CHANNEL_ZONE_TYPE = "tracker-status#zone-type";
    /**
     * Channel ID for when the tracker entered its currently most relevant geofence zone. See
     * {@link #CHANNEL_TRACKER_STATE}.
     */
    public static final String CHANNEL_ZONE_ENTERED_AT = "tracker-status#zone-entered-at";
    /**
     * Channel ID for when the tracker was last confirmed still inside its currently most relevant geofence zone.
     * Derived from the position channel's own timestamp rather than read directly -- see the note on {@code
     * applyPrioritizedZone()} in {@link org.openhab.binding.tractive.internal.handler.TractiveTrackerHandler} for
     * why.
     */
    public static final String CHANNEL_ZONE_LAST_SEEN_AT = "tracker-status#zone-last-seen-at";
    /** Channel ID for the ID of the tracker's configured Power Saving Zone. See {@link #CHANNEL_TRACKER_STATE}. */
    public static final String CHANNEL_POWER_SAVING_ZONE_ID = "tracker-status#power-saving-zone-id";
    /**
     * Thing property name for the tracker's hardware color/edition variant (e.g. {@code BROWN-LINES}). This field,
     * plus {@code model_number}, {@code fw_version} and {@link #PROPERTY_GEOFENCE_SENSITIVITY}, are Thing
     * properties rather than channels ({@code model_number}/{@code fw_version} use openHAB core's own
     * {@code modelId}/{@code firmwareVersion} names): they describe the hardware and change at most across an OTA
     * update, so they carry no rule or charting value.
     */
    public static final String PROPERTY_HARDWARE_EDITION = "hardwareEdition";
    /** Thing property name for the configured geofence entry/exit detection sensitivity (e.g. {@code HIGH}). */
    public static final String PROPERTY_GEOFENCE_SENSITIVITY = "geofenceSensitivity";
    /**
     * Thing property name for the tracker's onboard hardware/firmware capability flags (e.g. {@code LED},
     * {@code BUZZER}, {@code ACTIVITY_TRACKING}), comma-joined. Static per model/firmware, not per-tracker state.
     */
    public static final String PROPERTY_CAPABILITIES = "capabilities";
    /**
     * Thing property name for which geofence shapes this tracker+app combination supports drawing (e.g.
     * {@code CIRCLE}, {@code RECTANGLE}, {@code POLYGON}), comma-joined.
     */
    public static final String PROPERTY_SUPPORTED_GEOFENCE_TYPES = "supportedGeofenceTypes";
    /** Thing property name for whether the tracker offers a self-diagnostic test feature via the app. */
    public static final String PROPERTY_SELF_TEST_AVAILABLE = "selfTestAvailable";
    /**
     * Thing property name for whether the authenticated account only has shared/invited-level access to this
     * tracker, as opposed to owner-level access -- distinct from {@code profile#read-only}, which reflects the
     * same kind of account-level permission but on the pet ({@code trackable_object/{petId}}), a different object.
     */
    public static final String PROPERTY_TRACKER_READ_ONLY = "trackerReadOnly";
    /** Thing property name for when {@link #PROPERTY_TRACKER_READ_ONLY} was set to its current value. */
    public static final String PROPERTY_TRACKER_READ_ONLY_SINCE = "trackerReadOnlySince";
    /**
     * Channel ID for the status of the Tractive subscription covering this tracker (e.g. {@code ACTIVE}). From
     * {@code user/widget-data}, seeded once at startup and refreshable via the hardware refresh action — never
     * on the recurring schedule. See {@link #CHANNEL_BATTERY_SAVE_MODE}.
     */
    public static final String CHANNEL_SUBSCRIPTION_STATUS = "device-info#subscription-status";
    /**
     * Channel ID for the date the subscription covering this tracker is paid through. See
     * {@link #CHANNEL_SUBSCRIPTION_STATUS}.
     */
    public static final String CHANNEL_SUBSCRIPTION_VALID_TO = "device-info#subscription-valid-to";
    /**
     * Channel ID for the tracker's aggressive battery-save-mode toggle. Read/write: it is fetched with the rest of
     * the {@code device-info} group and can also be commanded, in which case the write's response (a full
     * {@code tracker/{trackerId}} object) updates it to the confirmed value.
     */
    public static final String CHANNEL_BATTERY_SAVE_MODE = "device-info#battery-save-mode";

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
    /** Channel ID for whether the cloud has accepted the buzzer command but the tracker hasn't yet executed it. */
    public static final String CHANNEL_BUZZER_PENDING = "commands#buzzer-pending";
    /**
     * Channel ID for whether the tracker was mid-reconnect while the buzzer command was outstanding; always
     * {@code false} in every capture taken so far.
     */
    public static final String CHANNEL_BUZZER_RECONNECTING = "commands#buzzer-reconnecting";
    /** Channel ID for when the current buzzer activation started; undefined while inactive. */
    public static final String CHANNEL_BUZZER_STARTED_AT = "commands#buzzer-started-at";
    /** Channel ID for whether the cloud has accepted the LED command but the tracker hasn't yet executed it. */
    public static final String CHANNEL_LED_PENDING = "commands#led-pending";
    /**
     * Channel ID for whether the tracker was mid-reconnect while the LED command was outstanding; always
     * {@code false} in every capture taken so far.
     */
    public static final String CHANNEL_LED_RECONNECTING = "commands#led-reconnecting";
    /** Channel ID for when the current LED activation started; undefined while inactive. */
    public static final String CHANNEL_LED_STARTED_AT = "commands#led-started-at";
    /**
     * Channel ID for whether the cloud has accepted the live-tracking command but the tracker hasn't yet executed
     * it.
     */
    public static final String CHANNEL_LIVE_TRACKING_PENDING = "commands#live-tracking-pending";
    /**
     * Channel ID for whether the tracker was mid-reconnect while the live-tracking command was outstanding; always
     * {@code false} in every capture taken so far.
     */
    public static final String CHANNEL_LIVE_TRACKING_RECONNECTING = "commands#live-tracking-reconnecting";
    /** Channel ID for when the current live-tracking activation started; undefined while inactive. */
    public static final String CHANNEL_LIVE_TRACKING_STARTED_AT = "commands#live-tracking-started-at";

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
    /** Channel ID for scratch status (e.g. {@code INFREQUENT}). */
    public static final String CHANNEL_SCRATCH = "health#scratch";
    /**
     * Channel ID for Tractive's 24-element per-hour activity breakdown of the current day: a comma-separated list of 24
     * integers.
     */
    public static final String CHANNEL_ACTIVITY_HOURLY_DISTRIBUTION = "health#activity-hourly-distribution";
    /**
     * Channel ID for which day {@link #CHANNEL_RESTING_HEART_RATE_STATUS} covers: {@code 0} = today, {@code -1} =
     * yesterday's value, still shown because today's is not yet available.
     */
    public static final String CHANNEL_RESTING_HEART_RATE_DAY_OFFSET = "health#resting-heart-rate-day-offset";
    /**
     * Channel ID for which day {@link #CHANNEL_RESTING_RESPIRATORY_RATE_STATUS} covers: {@code 0} = today,
     * {@code -1} = yesterday's value, still shown because today's is not yet available.
     */
    public static final String CHANNEL_RESTING_RESPIRATORY_RATE_DAY_OFFSET = "health#resting-respiratory-rate-day-offset";
    /**
     * Channel ID for which day {@link #CHANNEL_SCRATCH} covers: {@code 0} = today, {@code -1} = yesterday's value,
     * still shown because today's is not yet available.
     */
    public static final String CHANNEL_SCRATCH_DAY_OFFSET = "health#scratch-day-offset";
    /**
     * Channel ID for the report types bundled with the health overview, comma-separated (e.g.
     * {@code HEALTH_WEEKLY_REPORT}).
     */
    public static final String CHANNEL_ASSOCIATED_REPORT_TYPES = "health#associated-report-types";
    /** Channel ID for whether a separation-anxiety monitoring phase is currently ongoing for this pet. */
    public static final String CHANNEL_SEPARATION_PHASE_ONGOING = "health#separation-phase-ongoing";
    /** Channel ID for when the current separation-anxiety monitoring phase started; undefined while none is ongoing. */
    public static final String CHANNEL_SEPARATION_PHASE_STARTED_AT = "health#separation-phase-started-at";

    /** Channel ID for bark status (e.g. {@code NORMAL}, {@code ELEVATED}). */
    public static final String CHANNEL_BARK = "dog#bark";
    /**
     * Channel ID for which day {@link #CHANNEL_BARK} covers: {@code 0} = today, {@code -1} = yesterday's value,
     * still shown because today's is not yet available.
     */
    public static final String CHANNEL_BARK_DAY_OFFSET = "dog#bark-day-offset";

    /**
     * Thing property name for the pet's breed-catalog ID(s), comma-separated; not human-readable breed names. Only
     * refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_BREED_IDS = "breedIds";
    /** Thing property name for the pet's sex. Only refreshed at Thing setup or via {@code refreshProfile()}. */
    public static final String PROPERTY_GENDER = "gender";
    /**
     * Thing property name for the pet's date of birth. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_BIRTHDAY = "birthday";
    /**
     * Channel ID for the pet's height. Only refreshed at Thing setup or via {@code refreshProfile()}. Writable for
     * the pet's owner account, rejected for a shared/{@code read_only} one -- see {@link #PROPERTY_PET_READ_ONLY}.
     */
    public static final String CHANNEL_HEIGHT = "profile#height";
    /**
     * Channel ID for the pet's weight. Only refreshed at Thing setup or via {@code refreshProfile()}. Writable for
     * the pet's owner account, rejected for a shared/{@code read_only} one -- see {@link #PROPERTY_PET_READ_ONLY}.
     */
    public static final String CHANNEL_WEIGHT = "profile#weight";
    /**
     * Thing property name for whether the pet is spayed/neutered. Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String PROPERTY_NEUTERED = "neutered";
    /**
     * Channel ID for the pet's configured home location. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String CHANNEL_HOME_LOCATION = "profile#home-location";
    /** Channel ID for when the Profile channel group was last fetched. */
    public static final String CHANNEL_PROFILE_LAST_UPDATED = "profile#last-updated";
    /**
     * Thing property name for whether the owner opted this pet out of Tractive's activity-leaderboard/community
     * feature. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_LEADERBOARD_OPT_OUT = "leaderboardOptOut";
    /**
     * Thing property name for whether the authenticated account has owner-level (as opposed to shared/invited
     * -account) permission on this pet -- distinct from {@link #PROPERTY_TRACKER_READ_ONLY}, the same kind of
     * permission on the tracker, a different object. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_PET_READ_ONLY = "petReadOnly";
    /**
     * Thing property name for when this pet profile was first created in Tractive. Only refreshed at Thing setup
     * or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_PROFILE_CREATED_AT = "profileCreatedAt";
    /**
     * Thing property name for the pet's species (e.g. {@code DOG}). Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String PROPERTY_PET_TYPE = "petType";
    /**
     * Thing property name for the pet's microchip ID, if entered in the app. Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String PROPERTY_CHIP_ID = "chipId";
    /**
     * Thing property name for the pet's Leg Index Measurement (LIM), one of two inputs to the Feline Body Mass
     * Index (FBMI) -- always {@code null} for a {@code DOG} {@link #PROPERTY_PET_TYPE}, since FBMI is a
     * cat-specific formula. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_LIM = "lim";
    /**
     * Thing property name for the pet's ribcage circumference, the other FBMI input alongside {@link #PROPERTY_LIM}.
     * Same dog/cat caveat applies. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_RIBCAGE = "ribcage";
    /**
     * Thing property name for the pet's linked Instagram handle, if set. Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String PROPERTY_INSTAGRAM_USERNAME = "instagramUsername";
    /**
     * Thing property name for whether {@code profile#weight} is a real user-entered value or a breed-average
     * default. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_WEIGHT_IS_DEFAULT = "weightIsDefault";
    /**
     * Thing property name for whether {@code profile#height} is a real user-entered value or a breed-average
     * default. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_HEIGHT_IS_DEFAULT = "heightIsDefault";
    /**
     * Thing property name for the ID referencing the pet's profile photo in Tractive's media storage. Only
     * refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_PROFILE_PICTURE_ID = "profilePictureId";
    /**
     * Thing property name for a comma-separated list of IDs referencing the pet's gallery photos. Only refreshed
     * at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_GALLERY_PICTURE_IDS = "galleryPictureIds";
    /**
     * Thing property name for whether the owner consented to sharing this pet's walk data, a Tractive
     * community/walk-sharing feature. Only refreshed at Thing setup or via {@code refreshProfile()}.
     */
    public static final String PROPERTY_WALK_SHARING_CONSENT = "walkSharingConsent";
    /**
     * Thing property name for the pet's body length ({@link #FIELD_LENGTH}). Only refreshed at Thing setup or via
     * {@code refreshProfile()}.
     */
    public static final String PROPERTY_LENGTH = "length";
    /**
     * Thing property name for the pet's owner-set personality tags ({@link #FIELD_PERSONALITY}), comma-separated.
     */
    public static final String PROPERTY_PERSONALITY = "personality";
    /**
     * Thing property name for a presumed pet-status flag ({@link #FIELD_LOST_OR_DEAD}).
     */
    public static final String PROPERTY_LOST_OR_DEAD = "lostOrDead";
    /**
     * Thing property name for whether {@link #PROPERTY_BIRTHDAY} is a real value or a default
     * ({@link #FIELD_BIRTHDAY_IS_DEFAULT}).
     */
    public static final String PROPERTY_BIRTHDAY_IS_DEFAULT = "birthdayIsDefault";
    /**
     * Thing property name for whether {@link #PROPERTY_BREED_IDS} is a real value or a default
     * ({@link #FIELD_BREED_IS_DEFAULT}).
     */
    public static final String PROPERTY_BREED_IS_DEFAULT = "breedIsDefault";
    /**
     * Thing property name for a configured daily activity goal ({@link #FIELD_DAILY_GOAL}), units unconfirmed.
     */
    public static final String PROPERTY_ACTIVITY_DAILY_GOAL = "activityDailyGoal";
    /**
     * Thing property name for a configured daily distance goal ({@link #FIELD_DAILY_DISTANCE_GOAL}).
     */
    public static final String PROPERTY_ACTIVITY_DAILY_DISTANCE_GOAL = "activityDailyDistanceGoal";
    /**
     * Thing property name for a presumed activity-category threshold override
     * ({@link #FIELD_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE}).
     */
    public static final String PROPERTY_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE = "activityCategoryThresholdsOverride";
    /**
     * Thing property name for whether the daily active-minutes goal is a real value or a default
     * ({@link #FIELD_DAILY_ACTIVE_MINUTES_GOAL_IS_DEFAULT}, confirmed {@code Integer}-typed, not boolean).
     */
    public static final String PROPERTY_ACTIVITY_DAILY_MINUTES_GOAL_IS_DEFAULT = "activityDailyMinutesGoalIsDefault";

    /** JSON field name for the account email in the {@code POST auth/token} request body. */
    public static final String FIELD_PLATFORM_EMAIL = "platform_email";
    /** JSON field name for the account password in the {@code POST auth/token} request body. */
    public static final String FIELD_PLATFORM_TOKEN = "platform_token";
    /** JSON field name for the OAuth-style grant type in the {@code POST auth/token} request body. */
    public static final String FIELD_GRANT_TYPE = "grant_type";
    /** Value of {@link #FIELD_GRANT_TYPE} required by the Tractive {@code auth} endpoint. */
    public static final String VALUE_GRANT_TYPE_TRACTIVE = "tractive";
    /** {@code subscription.status} value meaning the subscription is paid and active. */
    public static final String VALUE_SUBSCRIPTION_ACTIVE = "ACTIVE";

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
    /**
     * JSON field name for whether the cloud has accepted a buzzer/LED/live-tracking command but the tracker hasn't
     * yet executed it.
     */
    public static final String FIELD_PENDING = "pending";
    /**
     * JSON field name for whether the tracker was mid-reconnect while a buzzer/LED/live-tracking command was
     * outstanding.
     */
    public static final String FIELD_RECONNECTING = "reconnecting";
    /**
     * JSON field name for when a buzzer/LED/live-tracking control object's current activation started, as a Unix
     * epoch; absent/null while inactive.
     */
    public static final String FIELD_STARTED_AT = "started_at";

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
    /**
     * JSON field name for a nearby-device/user ID accompanying a position fix, in both REST and the real-time
     * channel.
     */
    public static final String FIELD_NEARBY_USER_ID = "nearby_user_id";

    /** JSON field name for battery level as a 0-100 integer. */
    public static final String FIELD_BATTERY_LEVEL = "battery_level";
    /**
     * JSON field name for a hardware temperature-related state, in both REST's {@code device_hw_report} and the
     * real-time channel's nested {@code hardware} object.
     */
    public static final String FIELD_TEMPERATURE_STATE = "temperature_state";
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
     * during discovery, and surfaced as the {@code modelId} Thing property on the running thing.
     */
    public static final String FIELD_MODEL_NUMBER = "model_number";
    /** JSON field name for the tracker's onboard firmware version string, in {@code tracker/{trackerId}}. */
    public static final String FIELD_FW_VERSION = "fw_version";
    /** JSON field name for the tracker's hardware color/edition variant, in {@code tracker/{trackerId}}. */
    public static final String FIELD_HW_EDITION = "hw_edition";
    /** JSON field name for the configured geofence entry/exit detection sensitivity, in {@code tracker/{trackerId}}. */
    public static final String FIELD_GEOFENCE_SENSITIVITY = "geofence_sensitivity";
    /**
     * JSON field name for the tracker's onboard hardware/firmware capability flags array, in
     * {@code tracker/{trackerId}}.
     */
    public static final String FIELD_CAPABILITIES = "capabilities";
    /**
     * JSON field name for which geofence shapes this tracker+app combination supports drawing, in
     * {@code tracker/{trackerId}}.
     */
    public static final String FIELD_SUPPORTED_GEOFENCE_TYPES = "supported_geofence_types";
    /**
     * JSON field name for whether the tracker offers a self-diagnostic test feature, in {@code tracker/{trackerId}}.
     */
    public static final String FIELD_SELF_TEST_AVAILABLE = "self_test_available";
    /**
     * JSON field name for the tracker's own Bluetooth MAC address, in {@code tracker/{trackerId}}; always
     * {@code null} in every capture so far.
     */
    public static final String FIELD_BLUETOOTH_MAC = "bluetooth_mac";
    /**
     * JSON field name for when {@link #FIELD_READ_ONLY} (as read from {@code tracker/{trackerId}}) was set to its
     * current value.
     */
    public static final String FIELD_READ_ONLY_SINCE = "read_only_since";
    /**
     * JSON field name for the tracker's battery-save-mode flag, in {@code tracker/{trackerId}} (both directions:
     * it is read from that response and sent as the sole key of the {@code POST tracker/{trackerId}/battery_save_mode}
     * request body). The same literal is also the URL path segment of that write endpoint.
     */
    public static final String FIELD_BATTERY_SAVE_MODE = "battery_save_mode";
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
     * JSON field name for the epoch-seconds paid-through date inside a {@code user/widget-data} {@code subscription}
     * object.
     */
    public static final String FIELD_VALID_TO = "valid_to";
    /** JSON field name for the per-tracker bundle array in a {@code user/widget-data} response. */
    public static final String FIELD_TRACKERS = "trackers";
    /** JSON field name for the nested tracker object in a {@code user/widget-data} {@code trackers[]} element. */
    public static final String FIELD_TRACKER = "tracker";
    /** JSON field name for the nested subscription object in a {@code user/widget-data} {@code trackers[]} element. */
    public static final String FIELD_SUBSCRIPTION = "subscription";
    /**
     * JSON field name for the tracker ID inside a {@code user/widget-data} {@code tracker} object — plain
     * {@code id}, distinct from {@link #FIELD_ID} ({@code _id}) and {@link #FIELD_TRACKER_ID} ({@code tracker_id}).
     */
    public static final String FIELD_WIDGET_TRACKER_ID = "id";
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
    /**
     * JSON field name for Tractive's 24-element per-hour activity breakdown of the current day, as a string, inside the
     * nested {@link #FIELD_ACTIVITY} object.
     */
    public static final String FIELD_HOURLY_DISTRIBUTION = "hourlyDistribution";
    /**
     * JSON field name for "day offset" of a health sub-record; shared by the nested bark, scratch, resting-heart-rate
     * and resting-respiratory-rate objects. Integer: {@code 0} means the sibling {@code status} is for today,
     * {@code -1} means it is yesterday's, returned while today's has not been computed yet.
     */
    public static final String FIELD_DAY_OFFSET = "dayOffset";
    /** JSON field name for "associated data", part of a health/overview response. */
    public static final String FIELD_ASSOCIATED_DATA = "associatedData";
    /** JSON field name for "type", inside an {@link #FIELD_ASSOCIATED_DATA} array element. */
    public static final String FIELD_REPORT_TYPE = "type";
    /**
     * JSON field name for the nested separation-phase-status object in a health/overview response; may be JSON null.
     */
    public static final String FIELD_SEPARATION_PHASE_STATUS = "separationPhaseStatus";
    /** JSON field name for whether a separation phase is ongoing, inside {@link #FIELD_SEPARATION_PHASE_STATUS}. */
    public static final String FIELD_IS_PHASE_ONGOING = "isPhaseOngoing";
    /** JSON field name for when the current separation phase started, inside {@link #FIELD_SEPARATION_PHASE_STATUS}. */
    public static final String FIELD_PHASE_STARTED_AT = "phaseStartedAt";

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
    /**
     * JSON field name for a {@code pet_detail} resource's optimistic-concurrency version. Required, and must be
     * fresh, on every {@code PUT pet_detail/{id}} write -- a stale value is rejected with HTTP 400, code 7002.
     */
    public static final String FIELD_VERSION = "_version";
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
     * JSON field name for whether this pet is opted out of Tractive's activity-leaderboard feature, in
     * {@code trackable_object/{petId}}.
     */
    public static final String FIELD_LEADERBOARD_OPT_OUT = "leaderboard_opt_out";
    /**
     * JSON field name for account-level ownership/permission -- on the pet, in {@code trackable_object/{petId}}
     * (top-level and, identically, {@link #FIELD_DETAILS}-nested -- only the top-level copy is read, since the two
     * were confirmed to always agree); the same literal key, same meaning, also appears on the tracker itself in
     * {@code tracker/{trackerId}}, surfaced there as {@link #PROPERTY_TRACKER_READ_ONLY}.
     */
    public static final String FIELD_READ_ONLY = "read_only";
    /** JSON field name for when this pet profile was created, in {@code trackable_object/{petId}}. */
    public static final String FIELD_CREATED_AT = "created_at";
    /** JSON field name for the pet's species, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_PET_TYPE = "pet_type";
    /** JSON field name for the pet's microchip ID, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_CHIP_ID = "chip_id";
    /**
     * JSON field name for the pet's Leg Index Measurement, inside {@link #FIELD_DETAILS} -- see
     * {@link #PROPERTY_LIM} for what this measures.
     */
    public static final String FIELD_LIM = "lim";
    /** JSON field name for the pet's ribcage circumference, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_RIBCAGE = "ribcage";
    /** JSON field name for the pet's linked Instagram handle, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_INSTAGRAM_USERNAME = "instagram_username";
    /**
     * The eleven writable field names of the {@code PetDetailGraphObject} write DTO behind
     * {@code PUT pet_detail/{id}}, a strict subset of what {@link #FIELD_DETAILS} exposes for reading. Used to
     * build a full-object write body from a freshly-fetched {@code details} object, copying every field verbatim
     * except the one actually being changed. Declared here, after all eleven fields it references, because a
     * static field initializer in Java cannot forward-reference another field of the same class declared later
     * in the file.
     */
    public static final String[] PET_DETAIL_WRITE_FIELDS = { FIELD_NAME, FIELD_PET_TYPE, FIELD_BREED_IDS, FIELD_GENDER,
            FIELD_BIRTHDAY, FIELD_HEIGHT, FIELD_WEIGHT, FIELD_CHIP_ID, FIELD_NEUTERED, FIELD_LIM,
            FIELD_INSTAGRAM_USERNAME };
    /**
     * JSON field name for whether {@link #FIELD_WEIGHT} is a real value or a breed-average default, inside
     * {@link #FIELD_DETAILS}.
     */
    public static final String FIELD_WEIGHT_IS_DEFAULT = "weight_is_default";
    /**
     * JSON field name for whether {@link #FIELD_HEIGHT} is a real value or a breed-average default, inside
     * {@link #FIELD_DETAILS}.
     */
    public static final String FIELD_HEIGHT_IS_DEFAULT = "height_is_default";
    /** JSON field name for the ID of the pet's profile photo asset, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_PROFILE_PICTURE_ID = "profile_picture_id";
    /** JSON field name for the array of IDs of the pet's gallery photo assets, inside {@link #FIELD_DETAILS}. */
    public static final String FIELD_GALLERY_PICTURE_IDS = "gallery_picture_ids";
    /**
     * JSON field name for whether the owner consented to sharing this pet's walk data, in
     * {@code trackable_object/{petId}}.
     */
    public static final String FIELD_IS_WALK_SHARING_CONSENT_PROVIDED = "is_walk_sharing_consent_provided";
    /**
     * JSON field name for the pet's body length, inside {@link #FIELD_DETAILS} -- presumably alongside
     * {@link #FIELD_HEIGHT}/{@link #FIELD_WEIGHT}, unconfirmed by name alone. Entirely absent from a
     * shared/{@code read_only} account's response, not merely {@code null} -- see
     * {@link #PROPERTY_LENGTH}.
     */
    public static final String FIELD_LENGTH = "length";
    /**
     * JSON field name for a comma-separated list of owner-set personality tags, inside {@link #FIELD_DETAILS}. Same
     * account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_PERSONALITY = "personality";
    /**
     * JSON field name for a presumed pet-status flag (e.g. reported lost or deceased), inside {@link #FIELD_DETAILS}.
     * Same account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_LOST_OR_DEAD = "lost_or_dead";
    /**
     * JSON field name for whether {@link #FIELD_BIRTHDAY} is a real value or a default, inside
     * {@link #FIELD_DETAILS} -- mirrors {@link #FIELD_WEIGHT_IS_DEFAULT}/{@link #FIELD_HEIGHT_IS_DEFAULT}. Same
     * account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_BIRTHDAY_IS_DEFAULT = "birthday_is_default";
    /**
     * JSON field name for whether {@link #FIELD_BREED_IDS} is a real value or a default, inside
     * {@link #FIELD_DETAILS}. Same account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_BREED_IS_DEFAULT = "breed_is_default";
    /**
     * JSON field name for a nested object mirroring the separately client-gated {@code GET activity_setting/{id}}
     * endpoint's own record, inside {@link #FIELD_DETAILS} -- reachable here ungated. Same account-dependent
     * absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_ACTIVITY_SETTINGS = "activity_settings";
    /**
     * JSON field name for a configured daily activity goal, inside {@link #FIELD_ACTIVITY_SETTINGS} -- units
     * unconfirmed (possibly points, not minutes; distinct from the sibling {@code daily_active_minutes_goal}, which
     * duplicates the already-channeled {@code health/overview.activity.minutesGoal} and is deliberately not read
     * here).
     *
     * Same account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_DAILY_GOAL = "daily_goal";
    /**
     * JSON field name for a configured daily distance goal, inside {@link #FIELD_ACTIVITY_SETTINGS}. Same
     * account-dependent absence and active-clear behavior as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_DAILY_DISTANCE_GOAL = "daily_distance_goal";
    /**
     * JSON field name for a presumed per-pet override of activity-intensity category thresholds, inside
     * {@link #FIELD_ACTIVITY_SETTINGS} -- real shape unconfirmed (always {@code null} so far). The official
     * Tractive Android app's own Moshi DTOs for this object ({@code ActivitySettingsGraphObject}/
     * {@code ActivitySettingsCreationObject}) don't declare this field at all -- Moshi
     * silently ignores unknown JSON keys, so the app has no code path to ever read or write it, making it likely
     * dead/vestigial backend data with no known way to populate it. A same-named but distinct concept exists at the
     * Bluetooth protocol layer ({@code com.tractive.tap}'s {@code ACTIVITY_CATEGORY_THRESHOLDS}, decoded as a plain
     * {@code List<Integer>}) -- if this REST field is ever populated as a cloud-side mirror of that on-device
     * config, a flat integer array is a more likely shape than a nested object, though this is not confirmed either
     * way.
     *
     * Same account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_ACTIVITY_CATEGORY_THRESHOLDS_OVERRIDE = "activity_category_thresholds_override";
    /**
     * JSON field name for whether {@code daily_active_minutes_goal} is a real value or a default, inside
     * {@link #FIELD_ACTIVITY_SETTINGS}. Same account-dependent absence as {@link #FIELD_LENGTH}.
     */
    public static final String FIELD_DAILY_ACTIVE_MINUTES_GOAL_IS_DEFAULT = "daily_active_minutes_goal_is_default";

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
    /**
     * URL path segment to start a separation-anxiety monitoring phase ({@code PUT pet/{petId}/separation/phase/start}).
     */
    public static final String SEPARATION_PHASE_START = "start";
    /**
     * URL path segment to stop a separation-anxiety monitoring phase ({@code PUT pet/{petId}/separation/phase/stop}).
     */
    public static final String SEPARATION_PHASE_STOP = "stop";

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
