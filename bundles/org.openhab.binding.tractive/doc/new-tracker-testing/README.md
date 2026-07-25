# New Tracker Testing Checklist

**Purpose:** every assumption baked into this binding (endpoint shapes, real-time message dialect, command timing, dormancy behavior) was reverse-engineered from a single Tractive Dog 6 unit. Before adding support for a new tracker model — or trusting this binding with one — work through this checklist and record your results in a dated section of this file (or a new `<model>-findings.md` in this directory). Don't assume anything below "just works" the same way on a different model until you've confirmed it yourself.

## A. Setup

- **1.** Confirm the new tracker appears in `GET user/{userId}/trackers` and that `GET tracker/{trackerId}` returns a `model_number` not already known to the binding — if it _does_ match an existing model, this is really the same hardware family, not a new one.
- **2.** Confirm discovery resolves the correct `model_number` → thing-type mapping and that the tracker gets discovered and can be added as a Thing without errors.

## B. REST endpoint shapes

- **3.** Diff `GET tracker/{trackerId}`'s fields against the Dog 6 shape this binding already handles — same field names? Same `capabilities` vocabulary? Any fields present here that are missing or extra?
- **4.** Same diff for `device_hw_report`, `device_pos_report`, `tracker/{id}/positions`, `trackable_object/{petId}`, and `pet/{petId}/health/overview`.
- **5.** Confirm `state`/`state_reason` and `charging_state`/`battery_state` use the same enum values (`OPERATIONAL`, `POWER_SAVING`, `NOT_CHARGING`, `REGULAR`, etc.) — note any new values seen.
- **6.** Check whether `capabilities` includes `BUZZER`/`LED`/`LT` at all — some models may lack one of these three features entirely.

## C. Real-time channel dialect

- **7.** Confirm the channel delivers `tracker_status` and `health_overview` messages with the same top-level shape (nested `position`/`hardware`/`prioritized_zone` objects, flat `led_control`/`buzzer_control`/`live_tracking` objects) — this has so far only been confirmed against the Dog 6.
- **8.** Watch for any message types beyond the seven already catalogued for the Dog 6: `handshake`, `keep-alive`, `tracker_status`, `health_overview`, `activity_data_updated`, `command_confirmed`, `start_failed`.
- **9.** If the dialect differs at all, `TractiveTrackerHandler.onChannelEvent()` (currently one shared concrete method covering every tracker model) will need to become model-aware — flag this early rather than after building out full support.

## D. Commands — normal operation

- **10.** Test buzzer ON/OFF, LED ON/OFF, and live-tracking ON/OFF while the tracker is awake (not in a Power Saving Zone). Confirm the `active` field in the real-time push matches what physically happened.
- **11.** Record the LED's `timeout` value when `active:true` — the Dog 6 always shows `3600`; confirm whether this holds for the new model or whether it's Dog-6-specific.
- **12.** Record the buzzer's and live-tracking's `timeout` values the same way (Dog 6: `300`).

## E. Commands — dormancy / Power Saving Zone

- **13.** Deliberately let the tracker sit in its Power Saving Zone until commands start failing, and note how long that takes. On the Dog 6 this onset window varies session to session and isn't tightly bounded yet — more data points from any model are valuable.
- **14.** Send an ON command while dormant and confirm whether a `start_failed` push arrives (Dog 6: yes, after ~30s for buzzer/LED, ~60s for live tracking).
- **15.** Send an OFF command while dormant and confirm whether it queues silently with no `start_failed` at all (observed, but not yet fully proven, on the Dog 6).
- **16.** Compare command behavior sent from the Tractive app vs. from openHAB during the same dormancy window — on the Dog 6, the app has succeeded where openHAB's single GET request failed, in the same zone state; worth checking whether that's Dog-6-specific or general.

## F. Physical button

> **Tip — timestamping a physical action precisely:** a button press (or any other physical interaction with the tracker) doesn't appear in the binding's logs by itself, which makes it hard to line up against what the logs show right afterward. Create a plain marker Item with no channel binding and an `expire` binding, and flip it right when you perform the action:
>
> ```java
> Switch TrackerActionMarker { expire="0h0m1s,OFF" }
> ```
>
> It reverts to `OFF` automatically one second later, leaving a precise, log-correlatable `ItemCommandEvent`/`ItemStateChangedEvent` pair to compare against whatever the tracker does (or doesn't) report afterward. Use this for every test in this section, and for test 30 in section J below.

- **17.** With the buzzer OFF and nothing pending, press the tracker's physical button (using the marker Item above) and confirm whether _anything_ shows up in the logs around that timestamp — channel push, REST poll change, anything at all. On the Dog 6, multiple sessions show nothing.
- **18.** With the buzzer actively ON, press the button (with the marker) and immediately watch the logs for the next few minutes — does the Item eventually flip to OFF with no corresponding push (consistent with the button silencing the buzzer locally without reporting it), or does a push arrive around the marker timestamp?

## G. Health / activity data

- **19.** Confirm `bark`/`scratch`/`restingHeartRate`/`restingRespiratoryRate` use the same status vocabulary (`NORMAL`, `ELEVATED`, `INFREQUENT`, `NOT_SYNCED_YET`, `CALCULATING_BASELINE`, etc.), and if possible, confirm whether `NOT_SYNCED_YET` correlates with the collar being physically off the pet.
- **20.** Confirm `rest` is still always `null`, or capture a sample where it isn't (its shape is otherwise fully unknown).

## H. Rate limiting & errors

- **21.** If practical, deliberately trigger HTTP 429 (e.g. a very short poll interval for a few minutes) and confirm the response body/backoff behavior matches what this binding expects.
- **22.** Watch for error codes `4001`/`4002` (seen by other unofficial Tractive API clients but never by this binding) — if either appears, capture the exact response body.

## I. Wrap-up

- **23.** Record battery drain over a multi-day period at your normal `refreshInterval` — polling load hasn't been characterized on any model but the Dog 6.
- **24.** Record your findings — timestamps, exact JSON samples, and which of the above items passed/failed/diverged — in a dated section of this document or a separate file in this directory. Cite real captures rather than assumptions, and note how many independent test sessions each finding is based on before treating it as settled.

## J. Buzzer/LED/button — deeper follow-up tests

Items 13–18 establish _whether_ dormancy and the physical button affect commands at all; the tests below dig into the specifics with more tightly controlled, single-variable repetitions. Worth running on any model, not just as a Dog-6 curiosity — command-queuing and button behavior are exactly the kind of thing that's easy to get a false read on from a single mixed-purpose test session.

- **25. Button press, tracker awake, nothing active.** With the buzzer/LED off and the tracker confirmed _not_ in a Power Saving Zone, press the button once (with the marker Item from section F). Does anything show up in the logs at all around that timestamp? This isolates whether the button reports to the cloud in any way, independent of dormancy.
- **26. Button press, tracker awake, buzzer actively ON.** Turn the buzzer on, confirm it's active, then press the button (with the marker). Does the Item flip to OFF via a real push, or does nothing get logged around the press?
- **27. Button press, tracker dormant, buzzer actively ON.** Same as #26, but only once the tracker has been solidly dormant for several minutes (`last_seen_at` no longer advancing). Press the button (with the marker) and watch closely for the next few minutes. Does the Item eventually read OFF with a real corresponding push, or with nothing logged around the press timestamp at all? Repeat a few times if practical — a single session isn't enough to settle this either way.
- **28. Baseline: command resolution timing while dormant, with no physical action at all.** Turn the buzzer on while dormant, then don't touch the tracker or send any further command — just note how long the Item takes to resolve back to OFF on its own via the binding's normal OFF-handling. This is the baseline that any button-assisted resolution (test 27) needs to be compared against: if button presses don't resolve any faster than this baseline, that's evidence the button (if it does anything at all) isn't actually shortening resolution, and an "it turned off right after I pressed the button" observation may just be coincidental timing.
- **29. Repeat ON commands while dormant.** Send several ON commands a few minutes apart while the tracker is dormant. Are failures consistently near-instant (the optimistic state reverts within a fraction of a second — too fast for any real device response) or does the failure sometimes instead take the full timeout path (a `start_failed`-style message after tens of seconds)? These are two different failure modes for the same command under the same conditions, and it's worth knowing whether both occur or just one.
- **30. App vs. openHAB, same dormancy window.** While dormant, send an ON command through openHAB first; if it fails, retry immediately via the vendor's own app (and reverse the order on a separate occasion). Checks whether an openHAB attempt has any wake-up effect on a following app-issued command, or whether the two are fully independent — and more generally, whether the vendor app's command delivery is simply more persistent/reliable than the binding's single HTTP request.
