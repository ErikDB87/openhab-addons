# Tractive Binding

This binding integrates [Tractive](https://tractive.com) GPS pet trackers into openHAB.
It reports the tracker's location, battery, and health data in real time and lets you activate the buzzer, LED, and live tracking from openHAB rules or the UI.

> **Important:** This binding uses Tractive's unofficial, undocumented HTTP API.
Tractive has not published a public API and makes no guarantee of stability or availability.
The binding may stop working without warning if Tractive changes its backend.

## Supported Things

- `account` (Bridge) — one per Tractive account; handles authentication and the real-time event stream.
- `dog-6` — a Tractive Dog 6 GPS tracker linked to a dog.

Not all Tractive tracker models are currently supported.
If you own a model that is not listed here, please contact the binding maintainer so support can be added.

## Supported Tracker Types

### Tractive Dog 6 (`dog-6`)

The Dog 6 is a GPS tracker designed for dogs.
It supports GPS and Wi-Fi positioning, real-time location updates, remote commands, and Tractive's health monitoring features.

Capabilities exposed by this binding:

- **Location** — latitude, longitude, altitude, positioning sensor type (GPS, Wi-Fi, phone-assisted), and estimated accuracy
- **Speed** — movement speed
- **Battery** — charge level (0–100 %), charging state, and battery health state
- **Tracker state** — operational state as reported by the tracker hardware, and why it's in that state (e.g. inside its configured Power Saving Zone)
- **Geofence zone** — which zone (and what kind) the tracker currently considers itself in, when it entered, and when it was last confirmed still there
- **Hardware info** — model number, hardware edition, onboard firmware version, and configured geofence sensitivity
- **Activity** — active time today and the configured daily goal
- **Sleep** — daytime sleep, night sleep, and calm time
- **Health monitoring** — resting heart rate status, resting respiratory rate status, bark frequency, and scratch frequency
- **Health alerts** — count of unseen alerts in the Tractive app
- **Historical positions** — fetch a time-windowed GPS track via a rule action
- **Buzzer** — remotely activate an audible tone
- **LED** — remotely activate the LED light on the tracker
- **Live tracking** — enable high-frequency location updates on demand
- **Command timing** — for the buzzer, LED, and live tracking: configured auto-stop duration and seconds remaining before it stops
- **Pet profile** — breed, sex, birthday, height, weight, neutered status, and home location, as entered in the Tractive app (fetched once at Thing setup, refreshable on demand — see the `profile` channel group below)
- **Last contact** — timestamp of the most recent successful contact with the tracker, be it via REST poll or real-time event

## Discovery

The `account` bridge needs to be created manually.
Once it is configured and online, all trackers that are linked to that account are automatically discovered.
**To add another tracker later, you need to manually scan to get them discovered.**

The binding queries the Tractive account and announces each linked tracker as an inbox entry of the appropriate Thing type, pre-filled with its `trackerId` and `trackedPetId`.

## Bridge Configuration

### `account` Bridge

| Parameter  | Type | Required | Description                            |
| ---------- | ---- | -------- | -------------------------------------- |
| `email`    | text | yes      | E-mail address of the Tractive account |
| `password` | text | yes      | Password of the Tractive account       |

The bridge authenticates on startup and refreshes the access token automatically.
It also maintains a persistent real-time event stream that pushes position and health updates to all linked Things.

## Thing Configuration

### `dog-6` Thing

| Parameter         | Type    | Required | Default | Description                                                                                                                                                                                                                                                                                                                                         |
| ----------------- | ------- | -------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `trackerId`       | text    | yes      |         | 8-character tracker hardware ID (e.g. `HBDYUFSC`)                                                                                                                                                                                                                                                                                                   |
| `trackedPetId`    | text    | yes      |         | ID of the pet ("trackable object"), linked to this tracker                                                                                                                                                                                                                                                                                          |
| `refreshInterval` | integer | no       | 0       | REST polling interval in seconds, backstopping `position`, `hardware`'s battery level, and `health`/`dog` alongside the real-time channel; set to 0 to disable and rely solely on real-time channel updates. `tracker-status`, `device-info`, and `profile` are never on this schedule — see their own sections below. Low values at your own risk¹ |

Both `trackerId` and `trackedPetId` are filled in automatically when the Thing is created via discovery.

**¹** Setting `refreshInterval` to `0` disables the periodic REST poll loop, but on-demand refreshes (the `refreshPosition()`/`refreshHealthOverview()`/`refreshHardware()` actions) still exist.
`refreshInterval` also controls the binding's safety against polling too regularly. Setting `refreshInterval` lower than 60 relaxes that safety, but increases the risk of triggering `HTTP 429` ("Too Many Requests").
It's possible (but not verified) that being too aggressive might lead to longer-term blocks by Tractive's server.

## Channels

### Group `position`

| Channel ID                    | Type          | R/W | Advanced | Description                                                               |
| ----------------------------- | ------------- | --- | -------- | ------------------------------------------------------------------------- |
| `position#location`           | Location      | R   | No       | Current location (latitude, longitude, altitude)¹                         |
| `position#last-position-time` | DateTime      | R   | No       | Timestamp of the last position report¹                                    |
| `position#speed`              | Number:Speed  | R   | No       | Speed at the last position report; `null` when the tracker is stationary¹ |
| `position#sensor-used`        | String        | R   | Yes      | Positioning sensor: `GPS`, `KNOWN_WIFI`, or `PHONE`¹                      |
| `position#position-accuracy`  | Number:Length | R   | No       | Estimated position accuracy radius¹                                       |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `hardware`

| Channel ID               | Type   | R/W | Advanced | Description                    |
| ------------------------ | ------ | --- | -------- | ------------------------------ |
| `hardware#battery-level` | Number | R   | No       | Battery charge level, 0–100 %¹ |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `tracker-status`

| Channel ID                            | Type     | R/W | Advanced | Description                                                                                                    |
| ------------------------------------- | -------- | --- | -------- | -------------------------------------------------------------------------------------------------------------- |
| `tracker-status#charging-state`       | String   | R   | No       | Charging state reported by the tracker                                                                         |
| `tracker-status#battery-state`        | String   | R   | Yes      | Battery health state                                                                                           |
| `tracker-status#tracker-state`        | String   | R   | Yes      | Operational state of the tracker hardware                                                                      |
| `tracker-status#tracker-state-reason` | String   | R   | Yes      | Why `tracker-state` is what it is (e.g. `POWER_SAVING`, `STALE_POSITION`)                                      |
| `tracker-status#zone-type`            | String   | R   | No       | Kind of the geofence zone currently most relevant to the tracker's position (`POWER_SAVING`, `SAFE`, `DANGER`) |
| `tracker-status#zone-id`              | String   | R   | Yes      | Internal ID of the geofence zone currently most relevant to the tracker's position                             |
| `tracker-status#zone-entered-at`      | DateTime | R   | Yes      | Time the tracker entered its currently most relevant geofence zone                                             |
| `tracker-status#zone-last-seen-at`    | DateTime | R   | Yes      | Time the tracker was last confirmed still inside its currently most relevant geofence zone                     |
| `tracker-status#power-saving-zone-id` | String   | R   | Yes      | Internal ID of the tracker's configured Power Saving Zone                                                      |
| `tracker-status#last-contact`         | DateTime | R   | No       | Timestamp of the most recent successful contact with the tracker (be it via REST poll or real-time event)      |

> **Note:** Every channel in this group except `last-contact` is populated once by REST, at Thing setup, and never touched by REST again — the real-time channel is authoritative for them from that point on, regardless of `refreshInterval`. `last-contact` is the exception: it's bumped by every successful REST call or real-time event, from any channel group.

### Group `device-info`

| Channel ID                         | Type   | R/W | Advanced | Description                                                              |
| ---------------------------------- | ------ | --- | -------- | ------------------------------------------------------------------------ |
| `device-info#model-number`         | String | R   | Yes      | Tractive hardware model code (e.g. `TG6C`)                               |
| `device-info#hw-edition`           | String | R   | Yes      | Hardware color/edition variant of the physical unit (e.g. `BROWN-LINES`) |
| `device-info#firmware-version`     | String | R   | Yes      | Onboard firmware version string; changes across OTA firmware updates     |
| `device-info#geofence-sensitivity` | String | R   | Yes      | Configured geofence entry/exit detection sensitivity (e.g. `HIGH`)       |

> **Note:** This data has no real-time channel equivalent and doesn't change during normal operation, so it's fetched once at Thing setup and never on the recurring poll schedule — refresh it on demand via the `refreshHardware()` action or a `REFRESH` command.

### Group `commands`

| Channel ID                         | Type        | R/W | Advanced | Description                                                                      |
| ---------------------------------- | ----------- | --- | -------- | -------------------------------------------------------------------------------- |
| `commands#buzzer`                  | Switch      | R/W | No       | Activate (`ON`) or deactivate (`OFF`) the audible buzzer                         |
| `commands#buzzer-timeout`          | Number:Time | R   | Yes      | Configured maximum duration the buzzer is allowed to run before auto-stopping    |
| `commands#buzzer-remaining`        | Number:Time | R   | Yes      | Seconds left before the buzzer auto-stops                                        |
| `commands#led`                     | Switch      | R/W | No       | Activate (`ON`) or deactivate (`OFF`) the LED light                              |
| `commands#led-timeout`             | Number:Time | R   | Yes      | Configured maximum duration the LED is allowed to run before auto-stopping       |
| `commands#led-remaining`           | Number:Time | R   | Yes      | Seconds left before the LED auto-stops                                           |
| `commands#live-tracking`           | Switch      | R/W | No       | Enable or disable high-frequency live tracking                                   |
| `commands#live-tracking-timeout`   | Number:Time | R   | Yes      | Configured maximum duration live tracking is allowed to run before auto-stopping |
| `commands#live-tracking-remaining` | Number:Time | R   | Yes      | Seconds left before live tracking auto-stops                                     |

> **Note 1:** Tractive commands are queued in the cloud.
> There may be a delay between sending a command and the tracker responding physically.
>
> **Note 2:** The Item state reflects the last _confirmed_ device state, pushed asynchronously over the real-time channel — not the immediate response to the command itself, which is not reliable (see Note 1).
> If Tractive reports that a command timed out before the tracker ever executed it, the binding forces the Item back to `OFF`; this is confirmed correct for a failed _activation_ (turning the feature on), but a failed _deactivation_ would also force the Item to `OFF`, which could be wrong if the device is actually still on — the failure message doesn't say which direction failed, and that case is unverified.
> If no resolution (success or failure) ever arrives at all, the Item is left showing openHAB's initial optimistic guess indefinitely.
> This note still needs work!
>
> **Note 3:** The `-timeout` and `-remaining` channels are only updated by the real-time channel push, not by REST polling — the command endpoints' own synchronous HTTP response is not reliable (see Note 2), so there is nothing safe to poll for these two fields.
>
> **Note 4:** The tracker's physical button may be able to silence an active buzzer locally — but this is not yet confirmed. Real-world testing so far shows the buzzer eventually reading `OFF` after a button press, but with **no corresponding real-time push, no REST poll showing fresh data, and no `start_failed` message** anywhere around the press. The leading theory is that the button stops the buzzer without the tracker ever reporting that fact back to the cloud, meaning the Item may keep showing `ON` for a while after the buzzer has actually already stopped — but a coincidental, unrelated resolution around the same time hasn't been ruled out either. **This needs dedicated, controlled testing** (press the button with no other command in flight, and confirm whether the logs show literally nothing) before this behavior can be documented with confidence.

### Group `health`

| Channel ID                               | Type        | R/W | Advanced | Description                                              |
| ---------------------------------------- | ----------- | --- | -------- | -------------------------------------------------------- |
| `health#activity-recorded`               | Number:Time | R   | No       | Total time of activity recorded today¹                   |
| `health#activity-goal`                   | Number:Time | R   | No       | Daily activity time goal¹                                |
| `health#sleep-day`                       | Number:Time | R   | No       | Total time of daytime sleep today¹                       |
| `health#sleep-night`                     | Number:Time | R   | No       | Total time of night-time sleep today¹                    |
| `health#sleep-calm`                      | Number:Time | R   | No       | Total time of calm (resting but not sleep) today¹        |
| `health#resting-heart-rate-status`       | String      | R   | No       | Resting heart rate status, e.g. `NORMAL`¹                |
| `health#resting-respiratory-rate-status` | String      | R   | No       | Resting respiratory rate status, e.g. `NORMAL`¹          |
| `health#unseen-health-alerts`            | Number      | R   | Yes      | Number of unseen health alerts in the Tractive app¹      |
| `health#activity-synced-at`              | DateTime    | R   | Yes      | Timestamp of the last health data sync from the tracker¹ |
| `health#scratch`                         | String      | R   | No       | Scratch frequency status, e.g. `INFREQUENT`¹             |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `dog`

| Channel ID | Type   | R/W | Advanced | Description                                       |
| ---------- | ------ | --- | -------- | ------------------------------------------------- |
| `dog#bark` | String | R   | No       | Bark frequency status, e.g. `NORMAL`, `ELEVATED`¹ |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `profile`

| Channel ID              | Type          | R/W | Advanced | Description                                  |
| ----------------------- | ------------- | --- | -------- | -------------------------------------------- |
| `profile#breed-ids`     | String        | R   | Yes      | Comma-separated Tractive breed-catalog ID(s) |
| `profile#gender`        | String        | R   | Yes      | The pet's sex (`M`/`F`)                      |
| `profile#birthday`      | DateTime      | R   | Yes      | The pet's date of birth                      |
| `profile#height`        | Number:Length | R   | Yes      | The pet's height                             |
| `profile#weight`        | Number:Mass   | R   | Yes      | The pet's weight                             |
| `profile#neutered`      | Switch        | R   | Yes      | Whether the pet is spayed/neutered           |
| `profile#home-location` | Location      | R   | Yes      | The pet's configured home location           |
| `profile#last-updated`  | DateTime      | R   | Yes      | When this group was last fetched             |

> **Note:** This group holds mostly-static pet-profile data from the Tractive app (breed, birthday, weight, etc.).
> It is **not** part of the timed polling schedule (`refreshInterval`) — it's populated once when the Thing is created, and after that only updates when something explicitly asks for it: the `refreshProfile()` action, or a `REFRESH` command sent to any channel on this Thing (e.g. clicking refresh in the UI).
> If you edit this data in the Tractive app, values here stay stale until one of those happens.

## Thing Actions

The `dog-6` Thing exposes five actions for use in rules:

| Action                                               | Description                                                                                                                                                                                                                                                                                                                     |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `refreshPosition()`                                  | Triggers an immediate refresh of the "Position" channel group, outside the regular polling schedule                                                                                                                                                                                                                             |
| `refreshHealthOverview()`                            | Triggers an immediate refresh of the "Health" and "Dog" channel groups, outside the regular polling schedule                                                                                                                                                                                                                    |
| `refreshHardware()`                                  | Triggers an immediate refresh of the "Device Info" channel group and "Hardware"'s battery level, outside the regular polling schedule. Also re-polls "Tracker Status", but that group only actually updates as a result if this happens to be the very first successful call ever — see the `tracker-status` group's note above |
| `refreshProfile()`                                   | Triggers an immediate refresh of the "Profile" channel group — unlike the others, this group is otherwise never refreshed automatically                                                                                                                                                                                         |
| `getPositions(ZonedDateTime from, ZonedDateTime to)` | Fetches historical tracker positions within a time window                                                                                                                                                                                                                                                                       |

> **Note:** The binding has a safety against polling too frequently.
> If the same action is called within 60 seconds of the previous (or less, if you set `refreshInterval` to less than 60), the cached data from the previous call are rused.

### `getPositions`

Returns a `Map<String, Object>` with a single key `"positions"` whose value is a JSON array string.
Each element corresponds to one position fix:

```json
[{"time": 1784832952, "latlong": [51.20, 4.71], "alt": 9, "speed": 0.0, "course": 0, "pos_uncertainty": 10, "sensor_used": "GPS"}, ...]
```

| Field             | Type             | Description                                                                                                        |
| ----------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------ |
| `time`            | integer          | Unix epoch of the position fix (seconds)                                                                           |
| `latlong`         | array            | `[latitude, longitude]` in decimal degrees                                                                         |
| `alt`             | integer          | Altitude in metres                                                                                                 |
| `speed`           | number or `null` | Speed in m/s; `null` when the tracker is stationary                                                                |
| `course`          | number           | Probably course/heading value as returned by the API (unit not documented by Tractive), but seems to always be `0` |
| `pos_uncertainty` | number           | Estimated position uncertainty in metres                                                                           |
| `sensor_used`     | string           | `GPS`, `KNOWN_WIFI`, or `PHONE`                                                                                    |

The map is empty if the bridge is unavailable or the request fails.

## Full Example

### `tractive.things`

```java
Bridge tractive:account:gert "Tractive Account" [email="gert@hetdorp.vl", password="marleneke"] {
    Thing dog-6 samson "Samson (Tractive)" [trackerId="HBDYUFSC", trackedPetId="9c7a4e2d1f6b8035c2a9d0ef", refreshInterval=0]
}
```

### `tractive.items`

```java
Location      samson_Location              "Samson Location"                          ["GeoLocation", "Measurement"] { channel="tractive:dog-6:gert:samson:position#location" }
DateTime      samson_LastPositionTime      "Samson Last Position Update"              ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:position#last-position-time" }
Number:Speed  samson_Speed                 "Samson Speed"                             ["Measurement", "Speed"] { channel="tractive:dog-6:gert:samson:position#speed", unit="km/h" }
String        samson_SensorUsed            "Samson Sensor"                            ["Point"] { channel="tractive:dog-6:gert:samson:position#sensor-used" }
Number:Length samson_Accuracy              "Samson Accuracy"                          ["Point"] { channel="tractive:dog-6:gert:samson:position#position-accuracy" }

Number        samson_BatteryLevel          "Samson Battery"                 <Battery> ["Energy", "Measurement"] { channel="tractive:dog-6:gert:samson:hardware#battery-level" }
String        samson_ChargingState         "Samson Charging State"                    ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#charging-state" }
String        samson_BatteryState          "Samson Battery State"                     ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#battery-state" }
String        samson_TrackerState          "Samson Tracker State"                     ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#tracker-state" }
String        samson_TrackerStateReason    "Samson State Reason"                      ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#tracker-state-reason" }
String        samson_ZoneType              "Samson Zone Type"                         ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#zone-type" }
String        samson_ZoneId                "Samson Zone ID"                           ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#zone-id" }
DateTime      samson_ZoneEnteredAt         "Samson Zone Entered At"                   ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:tracker-status#zone-entered-at" }
DateTime      samson_ZoneLastSeenAt        "Samson Zone Last Seen At"                 ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:tracker-status#zone-last-seen-at" }
String        samson_PowerSavingZoneId     "Samson Power Saving Zone ID"              ["Point"] { channel="tractive:dog-6:gert:samson:tracker-status#power-saving-zone-id" }
String        samson_ModelNumber           "Samson Model Number"                      ["Point"] { channel="tractive:dog-6:gert:samson:device-info#model-number" }
String        samson_HwEdition             "Samson Hardware Edition"                  ["Point"] { channel="tractive:dog-6:gert:samson:device-info#hw-edition" }
String        samson_FirmwareVersion       "Samson Firmware Version"                  ["Point"] { channel="tractive:dog-6:gert:samson:device-info#firmware-version" }
String        samson_GeofenceSensitivity   "Samson Geofence Sensitivity"              ["Point"] { channel="tractive:dog-6:gert:samson:device-info#geofence-sensitivity" }
DateTime      samson_LastContact           "Samson Last Contact"                      ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:tracker-status#last-contact" }

Switch        samson_Buzzer                "Samson Buzzer"                            ["Control"] { channel="tractive:dog-6:gert:samson:commands#buzzer" }
Number:Time   samson_BuzzerTimeout         "Samson Buzzer Timeout"                    ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#buzzer-timeout" }
Number:Time   samson_BuzzerRemaining       "Samson Buzzer Remaining"                  ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#buzzer-remaining" }
Switch        samson_LED                   "Samson LED"                     <light>   ["Control", "Light"] { channel="tractive:dog-6:gert:samson:commands#led" }
Number:Time   samson_LedTimeout            "Samson LED Timeout"                       ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#led-timeout" }
Number:Time   samson_LedRemaining          "Samson LED Remaining"                     ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#led-remaining" }
Switch        samson_LiveTracking          "Samson Live Tracking"                     ["Control"] { channel="tractive:dog-6:gert:samson:commands#live-tracking" }
Number:Time   samson_LiveTrackingTimeout   "Samson Live Tracking Timeout"             ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#live-tracking-timeout" }
Number:Time   samson_LiveTrackingRemaining "Samson Live Tracking Remaining"           ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:commands#live-tracking-remaining" }

Number:Time   samson_ActiveTime            "Samson Active"                            ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:health#activity-recorded", unit="s" }
Number:Time   samson_ActivityGoal          "Samson Goal"                              ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:health#activity-goal", unit="s" }
Number:Time   samson_SleepDay              "Samson Day Sleep"                         ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:health#sleep-day", unit="s" }
Number:Time   samson_SleepNight            "Samson Night Sleep"                       ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:health#sleep-night", unit="s" }
Number:Time   samson_Calm                  "Samson Calm"                              ["Duration", "Measurement"] { channel="tractive:dog-6:gert:samson:health#sleep-calm", unit="s" }
String        samson_HeartRate             "Samson Heart Rate Status"                 ["Point"] { channel="tractive:dog-6:gert:samson:health#resting-heart-rate-status" }
String        samson_RespRate              "Samson Respiratory Rate Status"           ["Point"] { channel="tractive:dog-6:gert:samson:health#resting-respiratory-rate-status" }
Number        samson_HealthAlerts          "Samson Health Alerts"                     ["Point"] { channel="tractive:dog-6:gert:samson:health#unseen-health-alerts" }
DateTime      samson_ActivitySyncedAt      "Samson Activity Synced At"                ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:health#activity-synced-at" }
String        samson_Scratch               "Samson Scratch Status"                    ["Point"] { channel="tractive:dog-6:gert:samson:health#scratch" }

String        samson_Bark                  "Samson Bark Status"                       ["Point"] { channel="tractive:dog-6:gert:samson:dog#bark" }

String        samson_BreedIds              "Samson Breed IDs"                         ["Point"] { channel="tractive:dog-6:gert:samson:profile#breed-ids" }
String        samson_Gender                "Samson Gender"                            ["Point"] { channel="tractive:dog-6:gert:samson:profile#gender" }
DateTime      samson_Birthday              "Samson Birthday"                          ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:profile#birthday" }
Number:Length samson_Height                "Samson Height"                            ["Point"] { channel="tractive:dog-6:gert:samson:profile#height" }
Number:Mass   samson_Weight                "Samson Weight"                            ["Point"] { channel="tractive:dog-6:gert:samson:profile#weight", unit="kg", stateDescription=""[pattern="%.1f %unit%"] }
Switch        samson_Neutered              "Samson Neutered"                          ["Status"] { channel="tractive:dog-6:gert:samson:profile#neutered" }
Location      samson_HomeLocation          "Samson Home Location"                     ["GeoLocation", "Measurement"] { channel="tractive:dog-6:gert:samson:profile#home-location" }
DateTime      samson_ProfileLastUpdated    "Samson Profile Last Updated"              ["Point", "Timestamp"] { channel="tractive:dog-6:gert:samson:profile#last-updated" }
```

### `tractive.rules`

```java
import org.openhab.binding.tractive.internal.action.TractiveDog6Actions

rule "Log Samson's daily position history"
when
    Time cron "0 0 0 * * ?"
then
    val actions = getActions("tractive", "tractive:dog-6:gert:samson") as TractiveDog6Actions
    val to = now
    val from = to.minusDays(1)
    val result = actions.getPositions(from, to)
    val json = result.get("positions")
    logInfo("Tractive", "Samson's positions for the last 24 hours: " + json)
end
```
