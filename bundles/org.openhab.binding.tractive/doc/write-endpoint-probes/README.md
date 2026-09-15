# Write-endpoint probes

Tractive's public REST surface (as used by this binding) is entirely read-only and command GETs — the
official Android app also *writes* several things (battery-save mode, separation-phase tracking, live
tracking's auto-stop duration, the pet's activity goal, the pet's profile fields) via endpoints that
aren't documented anywhere and aren't used by this binding. The five scripts here were built to answer
one recurring question about each of those endpoints, safely: **is this write endpoint something the
binding's own client could actually use, or is it gated to the official app / to the pet's owner?**

Every endpoint here was first identified by decompiling the official Android app's `base.apk`
(`apk-teardown/` in the binding's working tree) — the Retrofit interface method signatures and DTO
field names cited in each script's docstring come from that decompile, not from any public
documentation, since none exists.

## Why "client gate" vs. "account gate" matters, and why it's easy to conflate

A rejected write almost always comes back as HTTP `403` with the same body shape:
`{"code":8006,"category":"SECURITY","message":"Access to this resource has been denied.","detail":null}`.
That single response has been observed to mean at least three different things, depending on the
endpoint and circumstance:

- **A client-registry gate** — the endpoint only works for a specific, whitelisted `x-tractive-client`
  value (the official app's), and this binding's own client id is rejected outright, regardless of
  which account is authenticated.
- **An account-ownership gate** — the endpoint works fine for *some* accounts and not others, keyed on
  whether the authenticated account actually owns the pet/tracker (as opposed to having shared/invited
  access to it).
- **A wrong or inaccessible resource id** — the request never had a chance either way, because the id
  in the URL doesn't resolve to anything the account can see (this bit one of these probes directly —
  see `pet_detail_write_probe.py` below).

These three causes are not distinguishable from the response alone. Every script here disambiguates by
varying exactly **one** variable at a time and comparing:

- `--client-id 53e359a0f1f179e3ef000009` retries the same request as the official Tractive GPS app's
  own client id (extracted from the same decompile), to tell "my client is rejected" apart from "the
  account is rejected." (See `doc/x-tractive-client-ids/README.md` for where that id — and this
  binding's own `625e533dc3c3b41c28a669f0` — come from.) **This binding never ships or uses the app's
  client id for anything but this one-off disambiguation test.**
- Re-running the same script under a different Tractive account (one that owns the pet/tracker vs. one
  with only shared/invited access) tells an ownership gate apart from a client gate.
- Re-fetching the resource id fresh, immediately before use, rather than assuming an id from one
  endpoint is interchangeable with an id from a related one, rules out the third cause.

## Safety design common to all five scripts

Every script here performs at least one real write against a real account. All five share the same
shape to keep that safe:

- **Read a baseline first**, and keep it in memory for the whole run — every restore step targets that
  captured baseline, not a hardcoded assumption.
- **Any write is followed by a restore**, in a `finally` block, so a crash, `Ctrl+C`, or an unexpected
  rejection mid-run still attempts to put things back. If even the restore fails, the script prints a
  ready-to-run manual `curl` command with a placeholder for a fresh Bearer token.
- **Retries fire ONLY on HTTP 429** (rate-limited), never on any other status — a real rejection is
  reported, not silently retried into something that looks like success.
- **Two fields are structurally off-limits for content changes**: `pet_detail_write_probe.py`'s
  omission test (part C) is restricted to a list of fields that specifically excludes `height`/`weight`
  — the two fields the wider investigation was actually about — so even a scripting mistake in that
  part can't put real profile data at risk. A separate, narrower part (F, `--only realchange`) is the
  only place either of those two is ever actually changed, and it always restores immediately.
- **Credentials never touch disk or shell history**: `TRACTIVE_EMAIL`/`TRACTIVE_PASSWORD`/tracker-or-pet-ID
  env vars, or an interactive prompt with a hidden password. Raw HTTP traffic (status + full body,
  never credentials or the bearer token) is appended to a `*_output.jsonl` file next to the script.

## The scripts

- **`battery_save_mode_probe.py`** — `POST tracker/{trackerId}/battery_save_mode`
  (`graph.tractive.com/4/`). Flips the tracker's aggressive battery-save toggle, watches for the change
  to become visible on `GET tracker/{id}` and for any knock-on effect on `state`/`state_reason`/report
  cadence, then flips it back.
- **`separation_phase_probe.py`** — `PUT pet/{petId}/separation/phase/{start,stop}`
  (host auto-resolved between `graph.tractive.com/4/` and `aps-api.tractive.com/api/1/`, since the
  decompiled Retrofit wiring didn't make the base URL obvious). Starts a separation-anxiety tracking
  phase, watches `separationPhaseStatus.isPhaseOngoing` flip in `health/overview`, then stops it again.
- **`settings_write_probe.py`** — two unrelated endpoints in one script, both `graph.tractive.com/4/`:
  `PUT tracker/{trackerId}/lt_timeout` (the live-tracking auto-stop duration) and
  `PUT activity_setting/{id}` (the pet's daily activity-minutes goal). Each is a read → change one field
  → restore round trip.
- **`request_status_update_probe.py`** — `POST tracker/{trackerId}/request_status_update`
  (`graph.tractive.com/4/`, empty body). No content is ever written by this one (there's nothing to
  restore) — it fires the request and watches whether `device_pos_report`/`device_hw_report` produce a
  fresher check-in afterward, particularly while the tracker is dormant in its Power Saving Zone.
- **`pet_detail_write_probe.py`** — `PUT pet_detail/{id}` (`graph.tractive.com/4/`), the endpoint behind
  a pet's `height`/`weight`/`name`/`breed_ids`/`gender`/`birthday`/`chip_id`/`neutered`/`lim`/
  `instagram_username` profile fields. By far the most involved of the five (multiple `--only` modes):
  a content-free baseline round-trip, a PATCH-with-near-empty-body probe, a field-omission-leniency
  probe (restricted to fields other than `height`/`weight`, see Safety design above), a deliberately
  wrong-`_version` probe (does the server actually enforce optimistic concurrency?), a genuine
  restore-immediately value-change probe, and a sequential-burst rate-limit/bucket-sharing probe
  mirroring `doc/rate-limit-probes/rate_limit_window_probe.py`'s `--mode cross-endpoint` methodology,
  adapted for a `_version`-gated write (a concurrent burst doesn't work here — only the first of several
  simultaneous writers could ever win, so it fires the burst sequentially instead, each write preceded
  by its own fresh `_version` read).

Run any script with `--help` for its full flag set; each one's own module docstring explains its method
and safety reasoning in more depth than this file does.

## What was found

| Endpoint | Client-gated? | Account-gated? | Result |
| --- | --- | --- | --- |
| `battery_save_mode` | No | No | **Shipped** — `device-info#battery-save-mode` writable Switch, on the recurring poll schedule |
| `separation/phase/{start,stop}` | No | No | **Shipped** — `health#separation-phase-ongoing` writable Switch |
| `pet_detail` (`height`/`weight`/etc.) | No | **Yes — owner only** (`200` for the owner, `403 8006` for a shared/read-only account, same pet, same request) | **Shipped** — `profile#height`/`weight` writable Channels, with a `DynamicStateDescriptionProvider` reflecting the account's own read-only status in MainUI |
| `lt_timeout` | No | **Yes — owner only** | Not shipped (would need read-only-account-guard machinery the binding doesn't otherwise have); confirmed viable as an owner-only follow-up |
| `activity_setting` | **Yes** — `403 8006` even for the pet's owner | N/A | Dead end, first-party-app-only |
| `request_status_update` | **Yes** — `403 8006` for this binding's client, `429 4006` (rate-limited, but *allowed*) for the official app's client, even across repeated spaced-out attempts | N/A | Dead end — and even on the one run that got a clean accept, a 5-minute watch of `device_pos_report`/`device_hw_report` showed zero movement: it doesn't wake a deep-dormant tracker even when it works |

Two write-mechanics findings that generalize across endpoints, both from `pet_detail_write_probe.py`
but worth knowing before touching any of the others:

- **`PATCH` is not implemented anywhere probed here** — every endpoint that was tried returns `404` for
  `PATCH`; `PUT` (always full-object) is the only write verb Tractive's API offers.
- **Omitting a key from a `PUT` body is lenient, not destructive** — an absent field comes back
  unchanged on the next `GET`, it is not nulled out. Confirmed only on `pet_detail`'s `breed_ids`
  field, not independently re-verified on any other endpoint's fields.
- **`_version` is real, server-enforced optimistic concurrency, not decorative** — sending a
  deliberately stale value gets a clear `400 {"code":7002,"category":"GRAPH","message":"Provided
  version <sent> was not equal to local version."}` rejection, confirmed on `pet_detail`. A future write
  feature can treat that code as "re-fetch and retry," not a generic bad request.
- **Every endpoint probed on `graph.tractive.com` shares one account-wide rate-limit bucket** with the
  binding's own `tracker`/`device_hw_report`/`device_pos_report` polling — confirmed directly for
  `pet_detail`/`trackable_object` via the sequential-burst methodology in
  `pet_detail_write_probe.py --only ratelimit`, consistent with the cross-endpoint findings in
  `doc/rate-limit-probes/README.md`. A real write feature built on any of these endpoints needs to be
  wired into the same shared-bucket accounting as everything else, not treated as an independent budget.

## What's still unknown

- Whether `lt_timeout`'s write actually persists correctly long-term, or has any subtler interaction
  with the live-tracking command flow — only a short watch-and-restore was run.
- Whether other, still-undiscovered write endpoints exist. These five were found by decompiling one
  version of one Android app build; a newer build could easily expose more.
- Whether the account-ownership gate ever changes state mid-session (e.g. a share being revoked while
  a write is in flight) — not tested, and not expected to matter for a binding that authenticates once
  and holds a long-lived token.
- Whether `activity_setting`'s and `request_status_update`'s client-registry gates could ever open up
  (e.g. via a future client-registration process) — treated here as a hard, permanent dead end based on
  current behavior only.
