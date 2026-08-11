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

| Parameter         | Type    | Required | Default | Description                                                                            |
| ----------------- | ------- | -------- | ------- | -------------------------------------------------------------------------------------- |
| `trackerId`       | text    | yes      |         | 8-character tracker hardware ID (e.g. `HBDYUFSC`)                                      |
| `trackedPetId`    | text    | yes      |         | ID of the pet ("trackable object"), linked to this tracker                             |
| `refreshInterval` | integer | no       | 0       | REST polling interval in seconds; set to 0 to rely solely on real-time channel updates |

Both `trackerId` and `trackedPetId` are filled in automatically when the Thing is created via discovery.

## Channels

### Group `position`

| Channel ID                    | Type          | R/W | Description                                                              |
| ----------------------------- | ------------- | --- | ------------------------------------------------------------------------ |
| `position#location`           | Location      | R   | Current location (latitude, longitude, altitude)                         |
| `position#last-position-time` | DateTime      | R   | Timestamp of the last position report                                    |
| `position#speed`              | Number:Speed  | R   | Speed at the last position report; `null` when the tracker is stationary |
| `position#sensor-used`        | String        | R   | Positioning sensor: `GPS`, `KNOWN_WIFI`, or `PHONE`                      |
| `position#position-accuracy`  | Number:Length | R   | Estimated position accuracy radius                                       |

### Group `hardware`

| Channel ID                      | Type     | R/W | Description                                                                                                    |
| ------------------------------- | -------- | --- | -------------------------------------------------------------------------------------------------------------- |
| `hardware#battery-level`        | Number   | R   | Battery charge level, 0–100 %                                                                                  |
| `hardware#charging-state`       | String   | R   | Charging state reported by the tracker                                                                         |
| `hardware#battery-state`        | String   | R   | Battery health state                                                                                           |
| `hardware#tracker-state`        | String   | R   | Operational state of the tracker hardware                                                                      |
| `hardware#zone-type`            | String   | R   | Kind of the geofence zone currently most relevant to the tracker's position (`POWER_SAVING`, `SAFE`, `DANGER`) |
| `hardware#tracker-state-reason` | String   | R   | Why `tracker-state` is what it is (e.g. `POWER_SAVING`, `STALE_POSITION`)                                      |
| `hardware#zone-id`              | String   | R   | Internal ID of the geofence zone currently most relevant to the tracker's position                             |
| `hardware#zone-entered-at`      | DateTime | R   | Time the tracker entered its currently most relevant geofence zone                                             |
| `hardware#zone-last-seen-at`    | DateTime | R   | Time the tracker was last confirmed still inside its currently most relevant geofence zone                     |
| `hardware#power-saving-zone-id` | String   | R   | Internal ID of the tracker's configured Power Saving Zone                                                      |
| `hardware#model-number`         | String   | R   | Tractive hardware model code (e.g. `TG6C`)                                                                     |
| `hardware#hw-edition`           | String   | R   | Hardware color/edition variant of the physical unit (e.g. `BROWN-LINES`)                                       |
| `hardware#firmware-version`     | String   | R   | Onboard firmware version string; changes across OTA firmware updates                                           |
| `hardware#geofence-sensitivity` | String   | R   | Configured geofence entry/exit detection sensitivity (e.g. `HIGH`)                                             |
| `hardware#last-contact`         | DateTime | R   | Timestamp of the most recent successful contact with the tracker (be it via REST poll or real-time event)      |

### Group `commands`

| Channel ID                         | Type        | R/W | Description                                                                      |
| ---------------------------------- | ----------- | --- | -------------------------------------------------------------------------------- |
| `commands#buzzer`                  | Switch      | R/W | Activate (`ON`) or deactivate (`OFF`) the audible buzzer                         |
| `commands#buzzer-timeout`          | Number:Time | R   | Configured maximum duration the buzzer is allowed to run before auto-stopping    |
| `commands#buzzer-remaining`        | Number:Time | R   | Seconds left before the buzzer auto-stops                                        |
| `commands#led`                     | Switch      | R/W | Activate (`ON`) or deactivate (`OFF`) the LED light                              |
| `commands#led-timeout`             | Number:Time | R   | Configured maximum duration the LED is allowed to run before auto-stopping       |
| `commands#led-remaining`           | Number:Time | R   | Seconds left before the LED auto-stops                                           |
| `commands#live-tracking`           | Switch      | R/W | Enable or disable high-frequency live tracking                                   |
| `commands#live-tracking-timeout`   | Number:Time | R   | Configured maximum duration live tracking is allowed to run before auto-stopping |
| `commands#live-tracking-remaining` | Number:Time | R   | Seconds left before live tracking auto-stops                                     |

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

| Channel ID                               | Type        | R/W | Description                                                          |
| ---------------------------------------- | ----------- | --- | -------------------------------------------------------------------- |
| `health#activity-recorded`               | Number:Time | R   | Total time of activity recorded today                                |
| `health#activity-goal`                   | Number:Time | R   | Daily activity time goal                                             |
| `health#sleep-day`                       | Number:Time | R   | Total time of daytime sleep today                                    |
| `health#sleep-night`                     | Number:Time | R   | Total time of night-time sleep today                                 |
| `health#sleep-calm`                      | Number:Time | R   | Total time of calm (resting but not sleep) today                     |
| `health#resting-heart-rate-status`       | String      | R   | Resting heart rate status, e.g. `NORMAL`                             |
| `health#resting-respiratory-rate-status` | String      | R   | Resting respiratory rate status, e.g. `NORMAL`                       |
| `health#unseen-health-alerts`            | Number      | R   | Number of unseen health alerts in the Tractive app                   |
| `health#activity-synced-at`              | DateTime    | R   | Timestamp of the last health data sync from the tracker              |
| `health#scratch`                         | String      | R   | Scratch frequency status, e.g. `INFREQUENT`, `NOT_ENOUGH_DATA_TODAY` |

### Group `dog`

| Channel ID | Type   | R/W | Description                                                      |
| ---------- | ------ | --- | ---------------------------------------------------------------- |
| `dog#bark` | String | R   | Bark frequency status, e.g. `INFREQUENT`, `CALCULATING_BASELINE` |

### Group `profile`

| Channel ID              | Type          | R/W | Description                                  |
| ----------------------- | ------------- | --- | -------------------------------------------- |
| `profile#breed-ids`     | String        | R   | Comma-separated Tractive breed-catalog ID(s) |
| `profile#gender`        | String        | R   | The pet's sex (`M`/`F`)                      |
| `profile#birthday`      | DateTime      | R   | The pet's date of birth                      |
| `profile#height`        | Number:Length | R   | The pet's height                             |
| `profile#weight`        | Number:Mass   | R   | The pet's weight                             |
| `profile#neutered`      | Switch        | R   | Whether the pet is spayed/neutered           |
| `profile#home-location` | Location      | R   | The pet's configured home location           |
| `profile#last-updated`  | DateTime      | R   | When this group was last fetched             |

> **Note:** This group holds mostly-static pet-profile data from the Tractive app (breed, birthday, weight, etc.).
> It is **not** part of the timed polling schedule (`refreshInterval`) — it's populated once when the Thing is created, and after that only updates when something explicitly asks for it: the `refreshProfile()` action, or a `REFRESH` command sent to any channel on this Thing (e.g. clicking refresh in the UI).
> If you edit this data in the Tractive app, values here stay stale until one of those happens.

## Thing Actions

The `dog-6` Thing exposes five actions for use in rules:

| Action                                               | Description                                                                                                                             |
| ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `refreshPosition()`                                  | Triggers an immediate refresh of the "Position" channel group, outside the regular polling schedule                                     |
| `refreshHealthOverview()`                            | Triggers an immediate refresh of the "Health" and "Dog" channel groups, outside the regular polling schedule                            |
| `refreshHardware()`                                  | Triggers an immediate refresh of the "Hardware" channel group, outside the regular polling schedule                                     |
| `refreshProfile()`                                   | Triggers an immediate refresh of the "Profile" channel group — unlike the others, this group is otherwise never refreshed automatically |
| `getPositions(ZonedDateTime from, ZonedDateTime to)` | Fetches historical tracker positions within a time window                                                                               |

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
Location      samson_Location         "Location"                          ["GeoLocation", "Measurement"] { channel="tractive:dog-6:gert:samson:position#location" }
DateTime      samson_LastPositionTime "Last Position Update"              ["Point", "Timestamp"]         { channel="tractive:dog-6:gert:samson:position#last-position-time" }
Number:Speed  samson_Speed            "Speed"                             ["Measurement", "Speed"]       { channel="tractive:dog-6:gert:samson:position#speed", unit="km/h", stateDescription=""[pattern="%.1f %unit%"] }
String        samson_SensorUsed       "Sensor"                            ["Point"]                      { channel="tractive:dog-6:gert:samson:position#sensor-used" }
Number:Length samson_Accuracy         "Accuracy"                          ["Point"]                      { channel="tractive:dog-6:gert:samson:position#position-accuracy" }

Number        samson_BatteryLevel     "Battery"                 <Battery> ["Energy", "Measurement"]      { channel="tractive:dog-6:gert:samson:hardware#battery-level" }
String        samson_ChargingState    "Charging State"                    ["Point"]                      { channel="tractive:dog-6:gert:samson:hardware#charging-state" }
String        samson_BatteryState     "Battery State"                     ["Point"]                      { channel="tractive:dog-6:gert:samson:hardware#battery-state" }
String        samson_TrackerState     "Tracker State"                     ["Point"]                      { channel="tractive:dog-6:gert:samson:hardware#tracker-state" }
DateTime      samson_LastContact      "Last Contact"                      ["Point", "Timestamp"]         { channel="tractive:dog-6:gert:samson:hardware#last-contact" }

Switch        samson_Buzzer           "Buzzer"                            ["Control"]                    { channel="tractive:dog-6:gert:samson:commands#buzzer" }
Switch        samson_LED              "LED"                     <light>   ["Control", "Light"]           { channel="tractive:dog-6:gert:samson:commands#led" }
Switch        samson_LiveTracking     "Live Tracking"                     ["Control"]                    { channel="tractive:dog-6:gert:samson:commands#live-tracking" }

Number:Time   samson_ActiveTime       "Active"                            ["Duration", "Measurement"]    { channel="tractive:dog-6:gert:samson:health#activity-recorded", unit="s", stateDescription=""[pattern="%d %unit%"] }
Number:Time   samson_ActivityGoal     "Goal"                              ["Duration", "Measurement"]    { channel="tractive:dog-6:gert:samson:health#activity-goal", unit="s", stateDescription=""[pattern="%d %unit%"] }
Number:Time   samson_SleepDay         "Day Sleep"                         ["Duration", "Measurement"]    { channel="tractive:dog-6:gert:samson:health#sleep-day", unit="s", stateDescription=""[pattern="%d %unit%"] }
Number:Time   samson_SleepNight       "Night Sleep"                       ["Duration", "Measurement"]    { channel="tractive:dog-6:gert:samson:health#sleep-night", unit="s", stateDescription=""[pattern="%d %unit%"] }
Number:Time   samson_Calm             "Calm"                              ["Duration", "Measurement"]    { channel="tractive:dog-6:gert:samson:health#sleep-calm", unit="s", stateDescription=""[pattern="%d %unit%"] }
String        samson_HeartRate        "Heart Rate Status"                 ["Point"]                      { channel="tractive:dog-6:gert:samson:health#resting-heart-rate-status" }
String        samson_RespRate         "Respiratory Rate Status"           ["Point"]                      { channel="tractive:dog-6:gert:samson:health#resting-respiratory-rate-status" }
Number        samson_HealthAlerts     "Health Alerts"                     ["Point"]                      { channel="tractive:dog-6:gert:samson:health#unseen-health-alerts" }
DateTime      samson_ActivitySyncedAt "Activity Synced At"                ["Point", "Timestamp"]         { channel="tractive:dog-6:gert:samson:health#activity-synced-at" }
String        samson_Bark             "Bark Status"                       ["Point"]                      { channel="tractive:dog-6:gert:samson:dog#bark" }
String        samson_Scratch          "Scratch Status"                    ["Point"]                      { channel="tractive:dog-6:gert:samson:health#scratch" }
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
