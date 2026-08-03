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
- **Tracker state** — operational state as reported by the tracker hardware
- **Activity** — active time today and the configured daily goal
- **Sleep** — daytime sleep, night sleep, and calm time
- **Health monitoring** — resting heart rate status, resting respiratory rate status, bark frequency, and scratch frequency
- **Health alerts** — count of unseen alerts in the Tractive app
- **Historical positions** — fetch a time-windowed GPS track via a rule action
- **Buzzer** — remotely activate an audible tone
- **LED** — remotely activate the LED light on the tracker
- **Live tracking** — enable high-frequency location updates on demand
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

| Channel ID                | Type     | R/W | Description                                                                                               |
| ------------------------- | -------- | --- | --------------------------------------------------------------------------------------------------------- |
| `hardware#battery-level`  | Number   | R   | Battery charge level, 0–100 %                                                                             |
| `hardware#charging-state` | String   | R   | Charging state reported by the tracker                                                                    |
| `hardware#battery-state`  | String   | R   | Battery health state                                                                                      |
| `hardware#tracker-state`  | String   | R   | Operational state of the tracker hardware                                                                 |
| `hardware#last-contact`   | DateTime | R   | Timestamp of the most recent successful contact with the tracker (be it via REST poll or real-time event) |

### Group `commands`

| Channel ID               | Type   | R/W | Description                                              |
| ------------------------ | ------ | --- | -------------------------------------------------------- |
| `commands#buzzer`        | Switch | W   | Activate (`ON`) or deactivate (`OFF`) the audible buzzer |
| `commands#led`           | Switch | W   | Activate (`ON`) or deactivate (`OFF`) the LED light      |
| `commands#live-tracking` | Switch | W   | Enable or disable high-frequency live tracking           |

> **Note 1:** Tractive commands are queued in the cloud.
> There may be a delay between sending a command and the tracker responding physically.
>
> **Note 2:** The displayed Item state reflects the last command sent, not a confirmed readback from the tracker — the binding currently can't verify whether a command actually completed on the physical device.

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

## Thing Actions

The `dog-6` Thing exposes four actions for use in rules:

| Action                                               | Description                                                                                                  |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `refreshPosition()`                                  | Triggers an immediate refresh of the "Position" channel group, outside the regular polling schedule          |
| `refreshHealthOverview()`                            | Triggers an immediate refresh of the "Health" and "Dog" channel groups, outside the regular polling schedule |
| `refreshHardware()`                                  | Triggers an immediate refresh of the "Hardware" channel group, outside the regular polling schedule          |
| `getPositions(ZonedDateTime from, ZonedDateTime to)` | Fetches historical tracker positions within a time window                                                    |

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
Switch        samson_LED              "LED"                               ["Control", "Light"]           { channel="tractive:dog-6:gert:samson:commands#led" }
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
