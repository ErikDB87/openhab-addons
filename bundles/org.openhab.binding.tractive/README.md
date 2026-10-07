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
- **Health monitoring** — resting heart rate status, resting respiratory rate status, bark and scratch frequency, hourly activity distribution, and separation-anxiety phase tracking
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

The binding queries the Tractive account and announces each linked tracker of a supported model as an inbox entry of the appropriate Thing type, pre-filled with its `trackerId` and `trackedPetId`.

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

| Parameter         | Type    | Required | Default | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ----------------- | ------- | -------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `trackerId`       | text    | yes      |         | 8-character tracker hardware ID (e.g. `HBDYUFSC`)                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `trackedPetId`    | text    | yes      |         | ID of the pet ("trackable object"), linked to this tracker                                                                                                                                                                                                                                                                                                                                                                                                          |
| `refreshInterval` | integer | no       | 180     | REST polling interval in seconds, backstopping `position`, `hardware`'s battery level, `health`/`dog`, and — when it is linked — `device-info#battery-save-mode` (which also refreshes the Thing properties), alongside the real-time channel; set to 0 to disable and rely solely on real-time channel updates. `tracker-status`, the rest of `device-info`, and `profile` are never on this schedule — see their own sections below. Low values at your own risk¹ |

Both `trackerId` and `trackedPetId` are filled in automatically when the Thing is created via discovery.

**¹** Setting `refreshInterval` to `0` disables the periodic REST poll loop, but on-demand refreshes (the `refreshPosition()`/`refreshHealthOverview()`/`refreshHardware()` actions) still exist.
`refreshInterval` also controls the binding's safety against polling too regularly. Setting `refreshInterval` lower than 60 relaxes that safety, but increases the risk of triggering `HTTP 429` ("Too Many Requests").
It's possible (but not verified) that being too aggressive might lead to longer-term blocks by Tractive's server.

## Channels

### Group `position`

| Channel ID                    | Type          | R/W | Advanced | Description                                                                              |
| ----------------------------- | ------------- | --- | -------- | ---------------------------------------------------------------------------------------- |
| `position#location`           | Location      | R   | No       | Current location (latitude, longitude, altitude)¹                                        |
| `position#last-position-time` | DateTime      | R   | No       | Timestamp of the last position report¹                                                   |
| `position#speed`              | Number:Speed  | R   | No       | Speed at the last position report; `null` when the tracker is stationary¹                |
| `position#sensor-used`        | String        | R   | Yes      | Positioning sensor: `GPS`, `KNOWN_WIFI`, `PHONE`, or `CELL`¹                             |
| `position#position-accuracy`  | Number:Length | R   | No       | Estimated position accuracy radius¹                                                      |
| `position#nearby-user-id`     | String        | R   | Yes      | Internal ID of the Tractive account whose phone is BLE-near the tracker; `NULL` if none¹ |
| `position#position-status`    | String        | R   | Yes      | Fix-quality flags of the last fix (e.g. `STATIONARY_FIX`); `NULL` if none²               |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.
>
> **²** Only delivered by REST, never by the real-time channel, so it updates only at Thing setup, on a `REFRESH`, or when `refreshInterval` is greater than 0. Set to `NULL` when a REST report carries an explicit `null`.

### Group `hardware`

| Channel ID                    | Type   | R/W | Advanced | Description                                                      |
| ----------------------------- | ------ | --- | -------- | ---------------------------------------------------------------- |
| `hardware#battery-level`      | Number | R   | No       | Battery charge level, 0–100 %¹                                   |
| `hardware#temperature-state`  | String | R   | Yes      | A hardware temperature-related state¹ ²                          |
| `hardware#clip-mounted-state` | String | R   | Yes      | Probably whether the tracker is clipped into its collar mount² ³ |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.
>
> **²** Meaning unresolved — always `null` in every real capture taken so far.
>
> **³** Only delivered by REST, never by the real-time channel, so it updates only at Thing setup, on a `REFRESH`, or when `refreshInterval` is greater than 0. Set to `NULL` when a REST report carries an explicit `null`.

### Group `tracker-status`

| Channel ID                            | Type     | R/W | Advanced | Description                                                                                                    |
| ------------------------------------- | -------- | --- | -------- | -------------------------------------------------------------------------------------------------------------- |
| `tracker-status#charging-state`       | String   | R   | No       | Charging state: `NOT_CHARGING`, `CHARGING`, or `FULL`                                                          |
| `tracker-status#battery-state`        | String   | R   | No       | Battery health state: `REGULAR`, `LOW`, `CRITICAL`, or `FULL`                                                  |
| `tracker-status#tracker-state`        | String   | R   | Yes      | Operational state of the tracker hardware                                                                      |
| `tracker-status#tracker-state-reason` | String   | R   | Yes      | Why `tracker-state` is what it is (e.g. `POWER_SAVING`, `STALE_POSITION`)                                      |
| `tracker-status#zone-type`            | String   | R   | No       | Kind of the geofence zone currently most relevant to the tracker's position (`POWER_SAVING`, `SAFE`, `DANGER`) |
| `tracker-status#zone-id`              | String   | R   | Yes      | Internal ID of the geofence zone currently most relevant to the tracker's position                             |
| `tracker-status#zone-entered-at`      | DateTime | R   | Yes      | Time the tracker entered its currently most relevant geofence zone                                             |
| `tracker-status#zone-last-seen-at`    | DateTime | R   | Yes      | Time the tracker was last confirmed still inside its currently most relevant geofence zone                     |
| `tracker-status#power-saving-zone-id` | String   | R   | Yes      | Internal ID of the tracker's configured Power Saving Zone                                                      |
| `tracker-status#last-contact`         | DateTime | R   | No       | Timestamp of the most recent successful exchange with the Tractive cloud (any REST poll or real-time event)    |

> **Note:** Every channel in this group except `last-contact` is populated once by REST, at Thing setup, and never touched by REST again — the real-time channel is authoritative for them from that point on, regardless of `refreshInterval`. `last-contact` is the exception: it's bumped by every successful REST call or real-time event, from any channel group. It therefore tracks the binding's connection to the Tractive cloud, **not** the tracker's own last check-in — with `refreshInterval > 0` it advances on every poll cycle even while the tracker is dormant. To detect a tracker that has stopped reporting, watch `position#last-position-time` or `health#activity-synced-at` instead.

### Group `device-info`

| Channel ID                          | Type     | R/W | Advanced | Description                                                                                                                |
| ----------------------------------- | -------- | --- | -------- | -------------------------------------------------------------------------------------------------------------------------- |
| `device-info#battery-save-mode`     | Switch   | RW  | No       | Aggressive battery-save mode toggle. Cloud-side account setting — takes effect within ~30 s regardless of tracker dormancy |
| `device-info#subscription-status`   | String   | R   | Yes      | Status of the Tractive subscription covering this tracker (e.g. `ACTIVE`)                                                  |
| `device-info#subscription-valid-to` | DateTime | R   | Yes      | Date the subscription covering this tracker is paid through                                                                |

The tracker's model code, hardware edition, firmware version, and geofence sensitivity are exposed as Thing [properties](#properties), not channels.

> **Note:** `battery-save-mode` is polled on the `refreshInterval` schedule whenever it is linked; the `tracker/{trackerId}` call it needs also refreshes the Thing [properties](#properties) as a side effect. It is otherwise fetched once at Thing setup and on demand via the `refreshHardware()` action or a `REFRESH` command. Commanding `battery-save-mode` updates its channel from the write's response. With `refreshInterval` set to `0` there is no periodic poll, so a change made in the Tractive app is then only picked up on the next `refreshHardware()` / `REFRESH` / Thing restart.
>
> **Subscription:** A non-`ACTIVE` subscription silently disables commands, live tracking, and real-time updates. The bridge fetches this once at startup and once a day, logs a WARN for any tracker whose subscription isn't active (so you get the warning even without linking the channels), and pushes each tracker's status to its Thing — the two channels update from that, not from `refreshHardware()`.

### Group `commands`

| Channel ID                            | Type        | R/W | Advanced | Description                                                                                     |
| ------------------------------------- | ----------- | --- | -------- | ----------------------------------------------------------------------------------------------- |
| `commands#buzzer`                     | Switch      | R/W | No       | Activate (`ON`) or deactivate (`OFF`) the audible buzzer                                        |
| `commands#buzzer-timeout`             | Number:Time | R   | Yes      | Configured maximum duration the buzzer is allowed to run before auto-stopping                   |
| `commands#buzzer-remaining`           | Number:Time | R   | Yes      | Seconds left before the buzzer auto-stops                                                       |
| `commands#buzzer-pending`             | Switch      | R   | Yes      | Whether the cloud has accepted the buzzer command but the tracker hasn't yet executed it        |
| `commands#buzzer-reconnecting`        | Switch      | R   | Yes      | Whether the tracker was mid-reconnect while the buzzer command was outstanding³                 |
| `commands#buzzer-started-at`          | DateTime    | R   | Yes      | When the current buzzer activation started; undefined while inactive                            |
| `commands#led`                        | Switch      | R/W | No       | Activate (`ON`) or deactivate (`OFF`) the LED light                                             |
| `commands#led-timeout`                | Number:Time | R   | Yes      | Configured maximum duration the LED is allowed to run before auto-stopping                      |
| `commands#led-remaining`              | Number:Time | R   | Yes      | Seconds left before the LED auto-stops                                                          |
| `commands#led-pending`                | Switch      | R   | Yes      | Whether the cloud has accepted the LED command but the tracker hasn't yet executed it           |
| `commands#led-reconnecting`           | Switch      | R   | Yes      | Whether the tracker was mid-reconnect while the LED command was outstanding³                    |
| `commands#led-started-at`             | DateTime    | R   | Yes      | When the current LED activation started; undefined while inactive                               |
| `commands#live-tracking`              | Switch      | R/W | No       | Enable or disable high-frequency live tracking                                                  |
| `commands#live-tracking-timeout`      | Number:Time | R   | Yes      | Configured maximum duration live tracking is allowed to run before auto-stopping                |
| `commands#live-tracking-remaining`    | Number:Time | R   | Yes      | Seconds left before live tracking auto-stops                                                    |
| `commands#live-tracking-pending`      | Switch      | R   | Yes      | Whether the cloud has accepted the live-tracking command but the tracker hasn't yet executed it |
| `commands#live-tracking-reconnecting` | Switch      | R   | Yes      | Whether the tracker was mid-reconnect while the live-tracking command was outstanding³          |
| `commands#live-tracking-started-at`   | DateTime    | R   | Yes      | When the current live-tracking activation started; undefined while inactive                     |

> **Note 1:** Tractive commands are queued in the cloud.
> There may be a delay between sending a command and the tracker responding physically.
>
> **Note 2:** The Item state reflects the last _confirmed_ device state, pushed asynchronously over the real-time channel — not the immediate response to the command itself, which is not reliable (see Note 1).
> If Tractive later reports that a command timed out before the tracker ever executed it, the binding forces the Item to `OFF`; this is confirmed correct for a failed _activation_.
> A failed _deactivation_ would, in principle, also force the Item to `OFF` incorrectly if the device is still on, since the failure message doesn't indicate direction — but deactivation commands don't appear to produce this message at all; they queue with no cloud confirmation, relying instead on the local auto-off prediction (see below, covering all three command channels) to correct the Item once its own hardware timer would have expired regardless.
>
> **Note 3:** The `-timeout`, `-remaining`, `-pending`, `-reconnecting`, and `-started-at` channels are only updated by the real-time channel push, not by REST polling — the command endpoints' own synchronous HTTP response is not reliable (see Note 2), so there is nothing safe to poll for these fields.
> The local auto-off prediction (see Note 2) also zeroes `-remaining` when it fires, alongside forcing the Switch to `OFF` — that `0` is the same local guess as the Switch state, not a fresh cloud confirmation.
>
> **³** Has been `OFF` in every real capture taken so far, so its meaning is a fairly confident guess from the field name, not a confirmed fact.
>
> **Note 4:** The tracker's physical button silences an active buzzer and reports that fact back to the cloud.
>
> **Note 5:** Buzzer/LED activation from the official Tractive app can succeed even when the same command sent through this binding times out on the same tracker at the same time.
> The app has a separate command path over Bluetooth (BLE) directly to the tracker when your phone is in range (the tracker's `capabilities` list includes `BUZZER_BLE`/`LED_BLE`).
> This binding runs independently of any phone and has no Bluetooth radio, so it has no access to this path — commands sent through openHAB are always subject to the cloud queuing/timeout behavior in Note 1/Note 2, with no BLE shortcut available.

### Group `health`

| Channel ID                                   | Type        | R/W | Advanced | Description                                                                                                                                                            |
| -------------------------------------------- | ----------- | --- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `health#activity-recorded`                   | Number:Time | R   | No       | Total time of activity recorded today¹                                                                                                                                 |
| `health#activity-goal`                       | Number:Time | R   | No       | Daily activity time goal¹                                                                                                                                              |
| `health#sleep-day`                           | Number:Time | R   | No       | Total time of daytime sleep today¹                                                                                                                                     |
| `health#sleep-night`                         | Number:Time | R   | No       | Total time of night-time sleep today¹                                                                                                                                  |
| `health#sleep-calm`                          | Number:Time | R   | No       | Total time of calm (resting but not sleep) today¹                                                                                                                      |
| `health#resting-heart-rate-status`           | String      | R   | No       | Resting heart rate status, e.g. `NORMAL`¹                                                                                                                              |
| `health#resting-respiratory-rate-status`     | String      | R   | No       | Resting respiratory rate status, e.g. `NORMAL`¹                                                                                                                        |
| `health#unseen-health-alerts`                | Number      | R   | Yes      | Number of unseen health alerts in the Tractive app¹                                                                                                                    |
| `health#activity-synced-at`                  | DateTime    | R   | Yes      | Timestamp of the last health data sync from the tracker¹                                                                                                               |
| `health#scratch`                             | String      | R   | No       | Scratch frequency status, e.g. `INFREQUENT`¹                                                                                                                           |
| `health#activity-hourly-distribution`        | String      | R   | Yes      | Comma-separated 24-integer per-hour activity breakdown for today. The array backfills and revises earlier hours, so treat it as a snapshot, not an append-only series¹ |
| `health#resting-heart-rate-day-offset`       | Number      | R   | Yes      | Which day `health#resting-heart-rate-status` covers: `0` = today, `-1` = yesterday's value, still shown until today's is available¹                                    |
| `health#resting-respiratory-rate-day-offset` | Number      | R   | Yes      | Which day `health#resting-respiratory-rate-status` covers: `0` = today, `-1` = yesterday's value, still shown until today's is available¹                              |
| `health#scratch-day-offset`                  | Number      | R   | Yes      | Which day `health#scratch` covers: `0` = today, `-1` = yesterday's value, still shown until today's is available¹                                                      |
| `health#associated-report-types`             | String      | R   | Yes      | Comma-separated types of the reports bundled with the health overview, e.g. `HEALTH_WEEKLY_REPORT`¹                                                                    |
| `health#separation-phase-ongoing`            | Switch      | RW  | No       | Whether a separation-anxiety monitoring phase is currently ongoing for this pet. Send `ON` to start a phase, `OFF` to stop the current one¹                            |
| `health#separation-phase-started-at`         | DateTime    | R   | Yes      | When the current separation-anxiety monitoring phase started; undefined while none is ongoing¹                                                                         |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `dog`

| Channel ID            | Type   | R/W | Advanced | Description                                                                                                 |
| --------------------- | ------ | --- | -------- | ----------------------------------------------------------------------------------------------------------- |
| `dog#bark`            | String | R   | No       | Bark frequency status, e.g. `NORMAL`, `ELEVATED`¹                                                           |
| `dog#bark-day-offset` | Number | R   | Yes      | Which day `dog#bark` covers: `0` = today, `-1` = yesterday's value, still shown until today's is available¹ |

> **¹** Also updated by the periodic REST poll (`refreshInterval`), as a backstop — but the real-time channel normally already keeps this current, so REST polling isn't needed for it to stay accurate. When both sources have reported, whichever is actually newer wins.

### Group `profile`

| Channel ID              | Type          | R/W | Advanced | Description                        |
| ----------------------- | ------------- | --- | -------- | ---------------------------------- |
| `profile#height`        | Number:Length | RW  | Yes      | The pet's height                   |
| `profile#weight`        | Number:Mass   | RW  | Yes      | The pet's weight                   |
| `profile#home-location` | Location      | R   | Yes      | The pet's configured home location |
| `profile#last-updated`  | DateTime      | R   | Yes      | When this group was last fetched   |

The rest of this group's data — breed, sex, birthday, neutered status, account access level, microchip ID, and more — is exposed as Thing [properties](#properties), not channels.

> **Note:** This group holds mostly-static pet-profile data from the Tractive app (breed, birthday, weight, etc.).
> Neither the channels above nor the properties are part of the timed polling schedule (`refreshInterval`) — they're populated once when the Thing is created, and after that only update when something explicitly asks for it: the `refreshProfile()` action, or a `REFRESH` command sent to any channel on this Thing (e.g. clicking refresh in the UI).
> If you edit this data in the Tractive app, values here stay stale until one of those happens.
>
> **`height`/`weight` are writable, but only for the pet's owner account.** Tractive's API itself enforces this —
> sending a command from a shared/invited account is rejected server-side and logged as a WARN; nothing is sent
> at all if the binding already knows the account is read-only (the same status the `petReadOnly` property
> reflects). A successful write updates the channel immediately; it does not update `profile#last-updated` or
> any other field in this group — those still only refresh via `refreshProfile()`/`REFRESH`/Thing restart.

## Properties

The `dog-6` Thing exposes these read-only properties, shown on the Thing's page in the UI. Discovery also sets `trackerId` and `trackedPetId`.

| Property                 | Description                                                                                                                                                                         |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `modelId`                | Tractive hardware model code (e.g. `TG6C`)                                                                                                                                          |
| `hardwareEdition`        | Hardware color/edition variant of the physical unit (e.g. `BROWN-LINES`)                                                                                                            |
| `firmwareVersion`        | Onboard firmware version string; changes across OTA firmware updates                                                                                                                |
| `geofenceSensitivity`    | Configured geofence entry/exit detection sensitivity (e.g. `HIGH`)                                                                                                                  |
| `capabilities`           | Comma-separated onboard hardware/firmware capability flags (e.g. `LED,BUZZER,ACTIVITY_TRACKING`)                                                                                    |
| `supportedGeofenceTypes` | Comma-separated geofence shapes this tracker+app combination supports (e.g. `CIRCLE,RECTANGLE,POLYGON`)                                                                             |
| `selfTestAvailable`      | Whether the tracker offers a self-diagnostic test feature via the app                                                                                                               |
| `macAddress`             | The tracker's Bluetooth MAC address, if ever reported (`null` in every capture so far)                                                                                              |
| `trackerReadOnly`        | Whether the authenticated account only has shared/invited-level access to this tracker (distinct from `petReadOnly` below, the same kind of permission on the pet, not the tracker) |
| `trackerReadOnlySince`   | When `trackerReadOnly` was set to its current value                                                                                                                                 |

These come from `tracker/{trackerId}`: seeded at Thing setup, and refreshed on the same poll that keeps `device-info#battery-save-mode` current (so they also track `refreshInterval` while that channel is linked) as well as on the `refreshHardware()` action.

The `dog-6` Thing also exposes these pet-profile properties, from `trackable_object/{petId}` — populated once at Thing setup and refreshed only via the `refreshProfile()` action or a `REFRESH` command, same as `profile#height`/`weight`/`home-location`/`last-updated`:

| Property             | Description                                                                                                                                                                         |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `breedIds`           | Comma-separated Tractive breed-catalog ID(s); not human-readable breed names                                                                                                        |
| `gender`             | The pet's sex (`M`/`F`)                                                                                                                                                             |
| `birthday`           | The pet's date of birth                                                                                                                                                             |
| `neutered`           | Whether the pet is spayed/neutered                                                                                                                                                  |
| `leaderboardOptOut`  | Whether the owner opted this pet out of Tractive's leaderboard/community feature                                                                                                    |
| `petReadOnly`        | Whether the authenticated account only has shared/invited-level access to this pet (distinct from `trackerReadOnly` above, the same kind of permission on the tracker, not the pet) |
| `profileCreatedAt`   | When this pet profile was first created in Tractive                                                                                                                                 |
| `petType`            | The pet's species (e.g. `DOG`)                                                                                                                                                      |
| `chipId`             | The pet's microchip ID, if entered in the app                                                                                                                                       |
| `lim`                | Leg Index Measurement, a Feline Body Mass Index input — only populated for cats                                                                                                     |
| `ribcage`            | Ribcage circumference, the other FBMI input — only populated for cats                                                                                                               |
| `instagramUsername`  | The pet's linked Instagram handle, if set                                                                                                                                           |
| `weightIsDefault`    | Whether `profile#weight` is a real value or a breed-average default                                                                                                                 |
| `heightIsDefault`    | Whether `profile#height` is a real value or a breed-average default                                                                                                                 |
| `profilePictureId`   | ID of the pet's profile photo asset                                                                                                                                                 |
| `galleryPictureIds`  | Comma-separated IDs of the pet's gallery photo assets                                                                                                                               |
| `walkSharingConsent` | Whether the owner consented to sharing this pet's walk data¹                                                                                                                        |

> **¹** Only present in Tractive's API response for a shared/invited account — absent (not merely `null`) when polling under the pet's own owner account.

## Thing Actions

The `dog-6` Thing exposes six actions for use in rules:

| Action                                                   | Description                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `refreshPosition()`                                      | Triggers an immediate refresh of the "Position" channel group, outside the regular polling schedule                                                                                                                                                                                                                                                              |
| `refreshHealthOverview()`                                | Triggers an immediate refresh of the "Health" and "Dog" channel groups, outside the regular polling schedule                                                                                                                                                                                                                                                     |
| `refreshHardware()`                                      | Re-polls `tracker/{trackerId}`, refreshing `device-info#battery-save-mode` and the Thing properties outside the regular schedule. Mainly for `refreshInterval = 0` setups, or to pick up a battery-save-mode change made in the Tractive app without waiting. "Tracker Status" only updates on the very first successful call ever — see that group's note above |
| `refreshProfile()`                                       | Triggers an immediate refresh of the "Profile" channel group — unlike the others, this group is otherwise never refreshed automatically                                                                                                                                                                                                                          |
| `getPositions(ZonedDateTime from, ZonedDateTime to)`     | Fetches historical tracker positions within a time window                                                                                                                                                                                                                                                                                                        |
| `getHealthHistory(ZonedDateTime from, ZonedDateTime to)` | Fetches per-day activity and sleep history within a date window                                                                                                                                                                                                                                                                                                  |

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
| `sensor_used`     | string           | `GPS`, `KNOWN_WIFI`, `PHONE`, or `CELL`                                                                            |

The map is empty if the bridge is unavailable or the request fails.

### `getHealthHistory`

Returns a `Map<String, Object>` with a single key `"history"` whose value is a JSON object string with two members, `"activity"` and `"sleep"`. Each is the raw body of the corresponding Tractive per-day rollup for the requested window — one entry per calendar day — or `null` if that request failed:

```json
{
  "activity": {"overviews": [{"date": "2026-08-30", "progress": 134, "streakBefore": true, "streakAfter": true}]},
  "sleep":    {"overviews": [{"date": "2026-08-30", "daySleep": 305, "nightSleep": 37, "calm": 84}]}
}
```

| Field                                               | Type    | Description                                                  |
| --------------------------------------------------- | ------- | ------------------------------------------------------------ |
| `activity.overviews[].date`                         | string  | Local calendar date (`YYYY-MM-DD`)                           |
| `activity.overviews[].progress`                     | integer | Percentage of that day's activity goal reached               |
| `activity.overviews[].streakBefore` / `streakAfter` | boolean | Whether the goal streak was intact on either side of the day |
| `sleep.overviews[].daySleep`                        | integer | Minutes of daytime sleep                                     |
| `sleep.overviews[].nightSleep`                      | integer | Minutes of night-time sleep                                  |
| `sleep.overviews[].calm`                            | integer | Minutes classified as calm                                   |

The window is taken from the local date of the two `ZonedDateTime` arguments; the time-of-day is ignored. The map is empty if the bridge is unavailable or both requests fail.

> **Note:** Unlike the `refresh…` actions, `getHealthHistory` is not rate-limit debounced. Call it on a schedule (for example once a day), not from a rule that fires on every state change.

## Full Example

### `tractive.things`

```java
Bridge tractive:account:gert "Tractive Account" [email="gert@hetdorp.vl", password="marleneke"] {
    Thing dog-6 samson "Samson (Tractive)" [trackerId="HBDYUFSC", trackedPetId="9c7a4e2d1f6b8035c2a9d0ef"]
}
```

### `tractive.items`

```java
Location      samson_Location              "Samson Location"                         ["GeoLocation", "Measurement"]  { channel="tractive:dog-6:gert:samson:position#location" }
DateTime      samson_LastPositionTime      "Samson Last Position Update"             ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:position#last-position-time" }
Number:Speed  samson_Speed                 "Samson Speed"                            ["Measurement", "Speed"]        { channel="tractive:dog-6:gert:samson:position#speed", unit="km/h" }
String        samson_SensorUsed            "Samson Sensor"                           ["Point"]                       { channel="tractive:dog-6:gert:samson:position#sensor-used" }
Number:Length samson_Accuracy              "Samson Accuracy"                         ["Point"]                       { channel="tractive:dog-6:gert:samson:position#position-accuracy" }
String        samson_NearbyUserId          "Samson Nearby User ID"                   ["Point"]                       { channel="tractive:dog-6:gert:samson:position#nearby-user-id" }
String        samson_PositionStatus        "Samson Position Status"                  ["Point"]                       { channel="tractive:dog-6:gert:samson:position#position-status" }

Number        samson_BatteryLevel          "Samson Battery"                <Battery> ["Energy", "Measurement"]       { channel="tractive:dog-6:gert:samson:hardware#battery-level" }
String        samson_TemperatureState      "Samson Temperature State"                ["Point"]                       { channel="tractive:dog-6:gert:samson:hardware#temperature-state" }
String        samson_ClipMountedState      "Samson Clip Mounted State"               ["Point"]                       { channel="tractive:dog-6:gert:samson:hardware#clip-mounted-state" }
String        samson_ChargingState         "Samson Charging State"                   ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#charging-state" }
String        samson_BatteryState          "Samson Battery State"                    ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#battery-state" }
String        samson_TrackerState          "Samson Tracker State"                    ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#tracker-state" }
String        samson_TrackerStateReason    "Samson State Reason"                     ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#tracker-state-reason" }
String        samson_ZoneType              "Samson Zone Type"                        ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#zone-type" }
String        samson_ZoneId                "Samson Zone ID"                          ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#zone-id" }
DateTime      samson_ZoneEnteredAt         "Samson Zone Entered At"                  ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:tracker-status#zone-entered-at" }
DateTime      samson_ZoneLastSeenAt        "Samson Zone Last Seen At"                ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:tracker-status#zone-last-seen-at" }
String        samson_PowerSavingZoneId     "Samson Power Saving Zone ID"             ["Point"]                       { channel="tractive:dog-6:gert:samson:tracker-status#power-saving-zone-id" }
String        samson_SubscriptionStatus    "Samson Subscription Status"              ["Point"]                       { channel="tractive:dog-6:gert:samson:device-info#subscription-status" }
DateTime      samson_SubscriptionValidTo   "Samson Subscription Valid To"            ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:device-info#subscription-valid-to" }
Switch        samson_BatterySaveMode       "Samson Battery Save Mode"                ["Switch"]                      { channel="tractive:dog-6:gert:samson:device-info#battery-save-mode" }
DateTime      samson_LastContact           "Samson Last Contact"                     ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:tracker-status#last-contact" }

Switch        samson_Buzzer                "Samson Buzzer"                           ["Control"]                     { channel="tractive:dog-6:gert:samson:commands#buzzer" }
Number:Time   samson_BuzzerTimeout         "Samson Buzzer Timeout"                   ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#buzzer-timeout" }
Number:Time   samson_BuzzerRemaining       "Samson Buzzer Remaining"                 ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#buzzer-remaining" }
Switch        samson_BuzzerPending         "Samson Buzzer Pending"                   ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#buzzer-pending" }
Switch        samson_BuzzerReconnecting    "Samson Buzzer Reconnecting"              ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#buzzer-reconnecting" }
DateTime      samson_BuzzerStartedAt       "Samson Buzzer Started At"                ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:commands#buzzer-started-at" }
Switch        samson_LED                   "Samson LED"                    <light>   ["Control", "Light"]            { channel="tractive:dog-6:gert:samson:commands#led" }
Number:Time   samson_LedTimeout            "Samson LED Timeout"                      ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#led-timeout" }
Number:Time   samson_LedRemaining          "Samson LED Remaining"                    ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#led-remaining" }
Switch        samson_LedPending            "Samson LED Pending"                      ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#led-pending" }
Switch        samson_LedReconnecting       "Samson LED Reconnecting"                 ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#led-reconnecting" }
DateTime      samson_LedStartedAt          "Samson LED Started At"                   ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:commands#led-started-at" }
Switch        samson_LiveTracking          "Samson Live Tracking"                    ["Control"]                     { channel="tractive:dog-6:gert:samson:commands#live-tracking" }
Number:Time   samson_LiveTrackingTimeout   "Samson Live Tracking Timeout"            ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#live-tracking-timeout" }
Number:Time   samson_LiveTrackingRemaining "Samson Live Tracking Remaining"          ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:commands#live-tracking-remaining" }
Switch        samson_LiveTrackingPending   "Samson Live Tracking Pending"            ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#live-tracking-pending" }
Switch        samson_LiveTrackingReconnecting "Samson Live Tracking Reconnecting"    ["Point"]                       { channel="tractive:dog-6:gert:samson:commands#live-tracking-reconnecting" }
DateTime      samson_LiveTrackingStartedAt "Samson Live Tracking Started At"         ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:commands#live-tracking-started-at" }

Number:Time   samson_ActiveTime            "Samson Active"                           ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:health#activity-recorded", unit="s" }
Number:Time   samson_ActivityGoal          "Samson Goal"                             ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:health#activity-goal", unit="s" }
Number:Time   samson_SleepDay              "Samson Day Sleep"                        ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:health#sleep-day", unit="s" }
Number:Time   samson_SleepNight            "Samson Night Sleep"                      ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:health#sleep-night", unit="s" }
Number:Time   samson_Calm                  "Samson Calm"                             ["Duration", "Measurement"]     { channel="tractive:dog-6:gert:samson:health#sleep-calm", unit="s" }
String        samson_HeartRate             "Samson Heart Rate Status"                ["Point"]                       { channel="tractive:dog-6:gert:samson:health#resting-heart-rate-status" }
String        samson_RespRate              "Samson Respiratory Rate Status"          ["Point"]                       { channel="tractive:dog-6:gert:samson:health#resting-respiratory-rate-status" }
Number        samson_HealthAlerts          "Samson Health Alerts"                    ["Point"]                       { channel="tractive:dog-6:gert:samson:health#unseen-health-alerts" }
DateTime      samson_ActivitySyncedAt      "Samson Activity Synced At"               ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:health#activity-synced-at" }
String        samson_Scratch               "Samson Scratch Status"                   ["Point"]                       { channel="tractive:dog-6:gert:samson:health#scratch" }
String        samson_HourlyActivity        "Samson Hourly Activity Distribution"     ["Point"]                       { channel="tractive:dog-6:gert:samson:health#activity-hourly-distribution" }
Number        samson_HeartRateDayOffset    "Samson Heart Rate Day Offset"            ["Point"]                       { channel="tractive:dog-6:gert:samson:health#resting-heart-rate-day-offset" }
Number        samson_RespRateDayOffset     "Samson Respiratory Rate Day Offset"      ["Point"]                       { channel="tractive:dog-6:gert:samson:health#resting-respiratory-rate-day-offset" }
Number        samson_ScratchDayOffset      "Samson Scratch Day Offset"               ["Point"]                       { channel="tractive:dog-6:gert:samson:health#scratch-day-offset" }
String        samson_AssociatedReports     "Samson Associated Report Types"          ["Point"]                       { channel="tractive:dog-6:gert:samson:health#associated-report-types" }
Switch        samson_SeparationOngoing     "Samson Separation Phase Ongoing"         ["Switch"]                      { channel="tractive:dog-6:gert:samson:health#separation-phase-ongoing" }
DateTime      samson_SeparationStartedAt   "Samson Separation Phase Started At"      ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:health#separation-phase-started-at" }

String        samson_Bark                  "Samson Bark Status"                      ["Point"]                       { channel="tractive:dog-6:gert:samson:dog#bark" }
Number        samson_BarkDayOffset         "Samson Bark Day Offset"                  ["Point"]                       { channel="tractive:dog-6:gert:samson:dog#bark-day-offset" }

Number:Length samson_Height                "Samson Height"                           ["Point"]                       { channel="tractive:dog-6:gert:samson:profile#height" }
Number:Mass   samson_Weight                "Samson Weight"                           ["Point"]                       { channel="tractive:dog-6:gert:samson:profile#weight", unit="kg", stateDescription=""[pattern="%.1f %unit%"] }
Location      samson_HomeLocation          "Samson Home Location"                    ["GeoLocation", "Measurement"]  { channel="tractive:dog-6:gert:samson:profile#home-location" }
DateTime      samson_ProfileLastUpdated    "Samson Profile Last Updated"             ["Point", "Timestamp"]          { channel="tractive:dog-6:gert:samson:profile#last-updated" }
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
