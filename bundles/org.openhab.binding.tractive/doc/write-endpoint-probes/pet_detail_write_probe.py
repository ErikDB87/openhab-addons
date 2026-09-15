#!/usr/bin/env python3
"""
Probe the `pet_detail/{id}` write endpoint from the Android app teardown (obfuscated/r8g.java,
method b: `@cxd("pet_detail/{id}") ... PetDetailGraphObject`; `@cxd` resolves to PUT by the same
reasoning as every other write endpoint probed in this directory). This is the endpoint behind
`height`/`weight`/`name`/etc. on a pet's profile.

Three open questions this answers, none answerable by reading the decompiled source alone:

  1. Is `pet_detail` PUT usable by this binding's own x-tractive-client at all, or client-gated like
     `activity_setting`/`request_status_update` turned out to be (403 code 8006 even for the owner)?
  2. Does the server support PATCH as well as PUT (would remove the whole read-modify-write question
     if so), and if PATCH exists, is IT also full-object, or does it tolerate a near-empty body?
  3. If a field is omitted from a PUT body, does the server leave it unchanged (lenient/PATCH-like)
     or null it out (destructive)? Tested SAFELY: only on a field we already know the value of
     (fetched fresh beforehand) and can restore immediately -- never on `height`/`weight` themselves,
     so the two fields this whole investigation is actually about are never put at risk. `breed_ids`
     is the default target: real, non-null, and low-stakes to have flap briefly.
  4. Is `_version` actually enforced as optimistic concurrency (a stale/wrong value gets REJECTED,
     e.g. 409), or silently accepted regardless (no real protection against a concurrent writer)?
     Tested SAFELY (part E, --only staleversion): the CONTENT sent is always the unchanged current
     state, only `_version` is deliberately wrong -- so even an "accepted" result changes nothing a
     restore would be needed for, unlike parts B/C which touch real field values.

RESULTS SO FAR (owner account):
  1. NOT client-gated -- an earlier "yes, gated" conclusion was itself a probe bug (see the id note
     below); the real answer is an ACCOUNT-ownership gate (200 for the owner, 403 8006 for a shared/
     read_only account, same pet, same request).
  2. PATCH -> 404, not implemented. PUT is the only write verb.
  3. Omission is LENIENT -- an absent key comes back unchanged, not nulled (tested on breed_ids only).
  4. Not yet tested -- this is what part E is for.

The `pet_detail` id is NOT the same as the pet id -- an earlier version of this script assumed they
were equal, based on a REDACTED capture where both collapsed to the same `{objectId}` placeholder by
shape-match, not actual equality. A live, unredacted GET shows `details._id` (pet_detail's own id) as a
genuinely different value from the top-level pet `_id` (petId). This script fetches `details._id` from
a fresh baseline GET and uses THAT for the `pet_detail/{id}` URL, falling back to `pet_id` only if `_id`
is somehow absent from the response.

Write DTO (`PetDetailGraphObject`, decompiled): name, pet_type, breed_ids, gender, birthday, height,
weight, chip_id, neutered, lim, instagram_username, _version -- `_version` is the only non-nullable
field in the Kotlin class (constructor throws if null), the same optimistic-concurrency shape already
CONFIRMED live for the sibling `activity_setting` write. All twelve keys already appear verbatim
under `trackable_object/{petId}`'s own `details` object, so the full write body is a straight subset
copy of a fresh GET -- no field-name translation needed.

Method -- read once, three read-only/near-read-only checks, one deliberately restorable content
change, restore, verify
------------------------------------------------------------------------------------------------
  0. Auth. GET trackable_object/{petId}. Capture the full `details` object as BASELINE (kept
     untouched in memory for the whole run -- this is what every restore step targets).
  A. Baseline PUT: send the full body back with every field UNCHANGED (pure round-trip, zero
     intended content change). If this alone is rejected (403/etc.), STOP -- PATCH/omission would
     just fail the same way, and there's nothing further to learn safely.
  B. PATCH probe: PATCH the same URL with a near-empty body (`{"_version": <fresh>}` only). Re-GET,
     diff every write-DTO field against BASELINE. If anything changed, restore immediately from
     BASELINE via a full PUT.
  C. Omission probe (--omit-field, default breed_ids): PUT the full body with exactly that ONE key
     removed (still sending a fresh `_version`). Re-GET, check whether that field came back
     unchanged, null, or the request was rejected outright. Restore immediately from BASELINE via a
     full PUT regardless of outcome.
  D. Final GET, diff against BASELINE field-by-field, loud manual-restore instructions if anything
     still differs.
  E. Stale-version probe (--only staleversion ONLY -- not part of "all", request it explicitly): PUT
     the full body with every field UNCHANGED but a deliberately wrong `_version` (--stale-version,
     default a generic placeholder value that's virtually guaranteed to differ from any pet's real
     current one). Content never changes either way, so there's nothing to restore -- this only tests
     whether the write is rejected (real optimistic concurrency) or accepted regardless (no real
     protection).

Each write retries ONLY on HTTP 429. A `finally` block re-checks the final state against BASELINE
and emergency-restores (full PUT, fresh `_version`) if anything is still off, then prints a manual
curl as a last resort.

Credentials: TRACTIVE_EMAIL / TRACTIVE_PASSWORD. Pet id: TRACTIVE_PET_ID (24-char hex) if set, else
prompted. Raw traffic -> pet_detail_write_probe_output.jsonl.

Re-run with --client-id 53e359a0f1f179e3ef000009 (the official GPS app's id) if the binding's own
client gets 403, to tell a client-registry gate apart from an account/ownership gate -- same
disambiguation used for activity_setting/request_status_update in settings_write_probe.py and
request_status_update_probe.py. Also worth re-running under both an owner and a shared
(petReadOnly: true) account -- this script does not automate that, just makes it easy to point at
either account via TRACTIVE_EMAIL/PASSWORD.

Usage:
    python pet_detail_write_probe.py
    python pet_detail_write_probe.py --only patch
    python pet_detail_write_probe.py --omit-field neutered
    python pet_detail_write_probe.py --client-id 53e359a0f1f179e3ef000009
    python pet_detail_write_probe.py --only staleversion
    python pet_detail_write_probe.py --only staleversion --stale-version deadbeef
    python pet_detail_write_probe.py --only realchange
    python pet_detail_write_probe.py --only realchange --change-field weight --change-delta 25
    python pet_detail_write_probe.py --only ratelimit
    python pet_detail_write_probe.py --only ratelimit --burst-count 8 --rate-limit-rest-seconds 300
"""

import argparse
import copy
import getpass
import json
import os
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

CLIENT_ID = "625e533dc3c3b41c28a669f0"
GRAPH_BASE = "https://graph.tractive.com/4/"
OUTPUT_FILE = "pet_detail_write_probe_output.jsonl"

# Wire keys of the PetDetailGraphObject write DTO (decompiled) -- all already present verbatim as
# keys in trackable_object/{petId}'s own `details` object, so no name translation is needed.
WRITE_KEYS = ["name", "pet_type", "breed_ids", "gender", "birthday", "height", "weight", "chip_id",
              "neutered", "lim", "instagram_username", "_version"]
# Fields safe to pick for the omission test: real content, but not the two fields this whole
# investigation is actually about (height/weight are deliberately excluded from this list).
OMITTABLE_FIELDS = ["name", "pet_type", "breed_ids", "gender", "birthday", "chip_id", "neutered",
                     "lim", "instagram_username"]
# The two fields the omission test above deliberately never touches -- tested separately, and only
# here, via probe_real_change(), with a small round-trip delta instead of an omission.
CHANGEABLE_FIELDS = ["height", "weight"]
# Small, deliberately-inconspicuous default deltas per field -- height in metres (1 cm), weight in
# grams (10 g) -- distinctive enough to prove a real change happened, small enough that the pet's
# profile is never left meaningfully wrong even if a restore step were somehow skipped.
DEFAULT_CHANGE_DELTA = {"height": 0.01, "weight": 10}


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def authenticate(email, password):
    body = json.dumps({
        "platform_email": email, "platform_token": password, "grant_type": "tractive",
    }).encode("utf-8")
    req = urllib.request.Request(GRAPH_BASE + "auth/token", data=body, method="POST", headers={
        "x-tractive-client": CLIENT_ID, "content-type": "application/json;charset=UTF-8",
    })
    with urllib.request.urlopen(req, timeout=15) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return payload["user_id"], payload["access_token"]


def _headers(user_id, access_token):
    return {
        "x-tractive-client": CLIENT_ID,
        "x-tractive-user": user_id,
        "authorization": f"Bearer {access_token}",
        "content-type": "application/json;charset=UTF-8",
    }


def request(method, url, user_id, access_token, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers=_headers(user_id, access_token))
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except urllib.error.URLError as e:
        return f"ERR:{e.reason}", ""


def record(out, label, url, status, body):
    out.write(json.dumps({
        "label": label, "url": url, "status": status,
        "timestamp": datetime.now(timezone.utc).isoformat(), "body": body,
    }) + "\n")
    out.flush()
    preview = " ".join(body.split())
    if len(preview) > 260:
        preview = preview[:260] + "..."
    log(f"{str(status):>6}  {label:<40}  {preview}")


def get_json(out, label, url, user_id, token, delay=1.0):
    status, body = request("GET", url, user_id, token)
    if status == 429:
        log("  429 -> backing off 6 s, retrying once")
        time.sleep(6)
        status, body = request("GET", url, user_id, token)
    record(out, label, url, status, body)
    time.sleep(delay)
    if status == 200:
        try:
            return json.loads(body)
        except ValueError:
            return None
    return None


def burst_get(out, label, url, user_id, token):
    """Like get_json, but NEVER auto-retries on 429 and never sleeps -- get_json's own unconditional
    "429 -> wait 6s, retry once" is the right default for a one-off lookup, but inside a burst it
    silently pads every rate-limited iteration with a forced 6s wait, defeating the entire point of
    measuring the RAW back-to-back failure rate. Returns (status, parsed_json_or_None)."""
    status, body = request("GET", url, user_id, token)
    record(out, label, url, status, body)
    if status == 200:
        try:
            return status, json.loads(body)
        except ValueError:
            return status, None
    return status, None


def write_json(method, out, label, url, user_id, token, payload, post_attempts, post_retry_interval):
    data = json.dumps(payload).encode("utf-8") if payload is not None else b""
    status, body = None, ""
    for attempt in range(1, post_attempts + 1):
        status, body = request(method, url, user_id, token, data=data)
        record(out, f"{label} (attempt {attempt})", url, status, body)
        if status != 429:
            break
        if attempt < post_attempts:
            log(f"  429 -- waiting {post_retry_interval:g}s before attempt {attempt + 1}")
            time.sleep(post_retry_interval)
    return status, body


def ok(status):
    return isinstance(status, int) and 200 <= status < 300


def verdict(status, method="PUT"):
    if ok(status):
        return f"VERDICT: {method} ACCEPTED for this x-tractive-client ID"
    if status == 403:
        return (f"VERDICT: {method} 403 -- client-registry or account gate. Re-run with "
                "--client-id 53e359a0f1f179e3ef000009 to tell those apart.")
    if status == 404:
        return f"VERDICT: {method} 404 -- wrong id/path, or PATCH not implemented for this resource."
    if status == 405:
        return f"VERDICT: {method} 405 -- method not allowed (PATCH not implemented here)."
    if status == 400:
        return f"VERDICT: {method} 400 -- rejected as malformed (e.g. a required field was missing)."
    if status == 409:
        return f"VERDICT: {method} 409 -- version conflict; re-read and retry."
    if status == 401:
        return f"VERDICT: {method} 401 -- auth problem, not an endpoint verdict."
    return f"VERDICT: {method} unexpected status {status} -- see {OUTPUT_FILE}."


def full_body(details, overrides=None, omit=None):
    """Build a PetDetailGraphObject-shaped body from a trackable_object/{petId}.details snapshot."""
    body = {k: details.get(k) for k in WRITE_KEYS}
    if overrides:
        body.update(overrides)
    if omit:
        body.pop(omit, None)
    return body


def diff_write_fields(baseline, current, keys=WRITE_KEYS):
    if not isinstance(current, dict):
        return {k: (baseline.get(k), "MISSING/no successful GET") for k in keys}
    changed = {}
    for k in keys:
        if k == "_version":
            continue  # expected to change on every successful write
        if baseline.get(k) != current.get(k):
            changed[k] = (baseline.get(k), current.get(k))
    return changed


def probe_baseline_put(out, url, baseline, user_id, token, post_attempts, post_retry_interval):
    log("========== A: baseline PUT (full body, zero intended change) ==========")
    status, _ = write_json("PUT", out, "A PUT baseline (no-op content)", url, user_id, token,
                            full_body(baseline), post_attempts, post_retry_interval)
    log(verdict(status, "PUT"))
    return status


def probe_patch(out, url, baseline, user_id, token, post_attempts, post_retry_interval):
    log("========== B: PATCH probe (near-empty body: _version only) ==========")
    fresh = get_json(out, "B pre-PATCH GET (fresh _version)", url, user_id, token)
    version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
    status, _ = write_json("PATCH", out, "B PATCH {_version only}", url, user_id, token,
                            {"_version": version}, post_attempts, post_retry_interval)
    log(verdict(status, "PATCH"))
    result = {"patch_status": status, "patch_accepted": ok(status), "wiped_fields": {}}
    if ok(status):
        after = get_json(out, "B post-PATCH GET", url, user_id, token)
        changed = diff_write_fields(baseline, after)
        if changed:
            log(f"  !!! PATCH with only _version changed {len(changed)} field(s): {changed}")
            result["wiped_fields"] = changed
            restore_from_baseline(out, url, baseline, user_id, token, post_attempts, post_retry_interval,
                                  reason="post-PATCH")
        else:
            log("  PATCH with only _version left every other field unchanged -- true partial update.")
    return result


def probe_omission(out, url, baseline, omit_field, user_id, token, post_attempts, post_retry_interval):
    log(f"========== C: PUT-omission probe (field omitted: {omit_field}) ==========")
    fresh = get_json(out, "C pre-omit GET (fresh _version)", url, user_id, token)
    version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
    body = full_body(baseline, overrides={"_version": version}, omit=omit_field)
    log(f"  Sending body with '{omit_field}' key absent entirely (not null -- omitted).")
    status, _ = write_json("PUT", out, f"C PUT omit={omit_field}", url, user_id, token, body,
                           post_attempts, post_retry_interval)
    log(verdict(status, "PUT"))
    result = {"omit_field": omit_field, "omit_status": status, "omit_accepted": ok(status)}
    if ok(status):
        after = get_json(out, "C post-omit GET", url, user_id, token)
        current_value = after.get(omit_field) if isinstance(after, dict) else "MISSING/no successful GET"
        original_value = baseline.get(omit_field)
        if current_value == original_value:
            log(f"  '{omit_field}' unchanged ({current_value!r}) -- omission is LENIENT (leave-unchanged).")
            result["omit_semantics"] = "leave_unchanged"
        elif current_value is None:
            log(f"  '{omit_field}' is now null (was {original_value!r}) -- omission is DESTRUCTIVE.")
            result["omit_semantics"] = "nulled"
        else:
            log(f"  '{omit_field}' changed to an unexpected value: {original_value!r} -> {current_value!r}")
            result["omit_semantics"] = "unexpected"
        result["value_before"] = original_value
        result["value_after"] = current_value
    else:
        log("  Request itself was rejected -- the server may require every field present (safest outcome).")
        result["omit_semantics"] = "rejected"
    log(f"  Restoring '{omit_field}' regardless of outcome...")
    restore_from_baseline(out, url, baseline, user_id, token, post_attempts, post_retry_interval,
                          reason="post-omission")
    return result


def probe_stale_version(out, url, baseline, stale_version, user_id, token, post_attempts, post_retry_interval):
    """PUT the CURRENT content but a deliberately WRONG `_version`, to see whether the server actually
    enforces optimistic concurrency (rejects it, e.g. 409) or silently accepts it regardless (no real
    protection against a concurrent writer). Content is unchanged either way, so nothing needs restoring
    even if the write is accepted -- this only tests the version check itself, never real data."""
    log(f"========== E: stale-_version probe (sending _version={stale_version!r}, deliberately wrong) ==========")
    fresh = get_json(out, "E pre-check GET (the REAL current _version, for comparison only)", url, user_id, token)
    real_version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
    log(f"  Real current _version is {real_version!r}; about to PUT with {stale_version!r} instead"
        + (" (these already differ, good)" if real_version != stale_version else
           " -- WARNING: these are the same value, this run will not actually test anything"))
    body = full_body(baseline, overrides={"_version": stale_version})
    status, resp_body = write_json("PUT", out, f"E PUT with stale _version={stale_version}", url, user_id, token,
                                   body, post_attempts, post_retry_interval)
    log(verdict(status, "PUT"))
    result = {"stale_version_sent": stale_version, "real_version_at_time": real_version,
              "stale_put_status": status, "stale_put_accepted": ok(status)}
    if ok(status):
        log("  !!! ACCEPTED despite a wrong _version -- the server does NOT enforce optimistic concurrency "
            "here, or not the way expected. No real protection against a concurrent writer.")
        try:
            returned_version = json.loads(resp_body).get("_version")
        except (ValueError, AttributeError):
            returned_version = None
        result["returned_version"] = returned_version
        log(f"  Response _version: {returned_version!r}")
    else:
        log(f"  Rejected (status {status}) -- consistent with real optimistic-concurrency enforcement.")
    after = get_json(out, "E post-check GET (content should be unchanged either way)", url, user_id, token)
    changed = diff_write_fields(baseline, after)
    if changed:
        log(f"  !!! Unexpected: content differs from baseline even though only _version was wrong: {changed}")
        result["unexpected_content_change"] = changed
    else:
        log("  Content matches baseline -- as expected, this test never touched real field values.")
    return result


def probe_real_change(out, url, baseline, field, delta, user_id, token, post_attempts, post_retry_interval):
    """PUT a genuinely CHANGED value for `field` (height or weight -- the two fields every other part
    of this script deliberately avoids touching), full body (every other field sent back verbatim, not
    omitted -- keeps this test isolated to "does a real content change round-trip correctly", not
    conflated with the separate omission-leniency question). Confirms the change actually took via a
    follow-up GET, then restores the original value. Always restores, success or failure."""
    log(f"========== F: real value-change probe (field={field}, delta={delta:+}) ==========")
    original_value = baseline.get(field)
    if not isinstance(original_value, (int, float)):
        log(f"  '{field}' isn't numeric in the baseline ({original_value!r}) -- aborting, nothing written.")
        return {"field": field, "aborted": "not numeric", "original_value": original_value}
    new_value = round(original_value + delta, 6)
    log(f"  '{field}': {original_value} -> {new_value} (temporary -- will be restored before this script exits)")

    fresh = get_json(out, "F pre-change GET (fresh _version)", url, user_id, token)
    version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
    body = full_body(baseline, overrides={field: new_value, "_version": version})
    status, _ = write_json("PUT", out, f"F PUT {field}={new_value}", url, user_id, token, body,
                           post_attempts, post_retry_interval)
    log(verdict(status, "PUT"))
    result = {"field": field, "original_value": original_value, "new_value": new_value,
              "change_status": status, "change_accepted": ok(status)}
    if ok(status):
        after = get_json(out, "F post-change GET (confirm it really took)", url, user_id, token)
        confirmed_value = after.get(field) if isinstance(after, dict) else None
        result["confirmed_value"] = confirmed_value
        # Tractive echoes weight as an int (grams) but height can come back as a float with binary
        # rounding noise (e.g. 0.49 -> 0.49000000000000005) -- compare with a small tolerance.
        if confirmed_value == new_value or (isinstance(confirmed_value, (int, float))
                                             and abs(confirmed_value - new_value) < 1e-6):
            log(f"  Confirmed: '{field}' really did change to {confirmed_value} and reads back correctly.")
        else:
            log(f"  !!! '{field}' reads back as {confirmed_value!r}, not the expected {new_value!r}.")
    else:
        log(f"  Change was rejected (status {status}) -- '{field}' should still be the original value.")
    log(f"  Restoring '{field}' to {original_value}...")
    restore_from_baseline(out, url, baseline, user_id, token, post_attempts, post_retry_interval,
                          reason="post-real-change")
    return result


def probe_rate_limit(out, pd_url, tracker_id, baseline, burst_count, rest_seconds, user_id, token):
    """Two-part rate-limit characterization for PUT pet_detail/{id}, mirroring
    rate_limit_window_probe.py's proven --mode cross-endpoint methodology but adapted for a WRITE
    endpoint: a concurrent burst doesn't work for a _version-gated PUT (only the first of N
    concurrent writers could ever win; the rest would collide on a now-stale _version and return
    400/7002, not 429, corrupting the signal) -- so this fires a SEQUENTIAL burst instead, each PUT
    preceded by its own fresh GET for _version so a real 429 can never be masked by a false-positive
    version conflict. G1 measures pet_detail's own rested baseline; G2 deliberately depletes the
    already-known tracker/{trackerId} bucket via the proven 14-concurrent trigger; G3 immediately
    repeats G1's burst. If G3 is markedly worse than G1, that's evidence of a SHARED bucket; if not,
    evidence pet_detail has its own, independent one -- same shared/independent framework already
    used for device_hw_report/device_pos_report/health_overview, just adapted for a write endpoint.
    Every burst PUT sends the baseline's own unchanged content (never height/weight touched, never
    any real value at risk), and a restore-from-baseline runs at the end regardless of outcome."""
    log(f"========== G: pet_detail write rate-limit / bucket-sharing probe (burst={burst_count}) ==========")
    log("  NOTE: pause the live binding's own polling before running this (stop openHAB, or take the "
        "Thing offline) so its traffic doesn't share this account's rate-limit bucket during the test.")

    def sequential_put_burst(phase_label):
        statuses = []
        for i in range(1, burst_count + 1):
            get_status, fresh = burst_get(out, f"{phase_label} pre-PUT GET #{i} (fresh _version, no "
                                          "auto-retry)", pd_url, user_id, token)
            if get_status != 200:
                # The pre-PUT GET itself was rate-limited (or failed) -- don't fall back to a stale
                # _version (that would risk a 400/7002 version-conflict masquerading as "not rate
                # limited"). Count this whole iteration's status as the GET's own, skip the PUT.
                # This also isn't purely artificial: a real write feature needs this same GET first,
                # so a throttled read genuinely does block the write in practice too.
                log(f"  burst call #{i}: pre-PUT GET itself failed ({get_status}) -- counting this "
                    "iteration as that status, skipping the PUT (no fresh _version to write with).")
                statuses.append(get_status)
                continue
            version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
            body = full_body(baseline, overrides={"_version": version})
            status, _ = write_json("PUT", out, f"{phase_label} burst PUT #{i}", pd_url, user_id, token,
                                   body, post_attempts=1, post_retry_interval=0)
            statuses.append(status)
            if status not in (200, 429):
                log(f"  !!! burst call #{i} got unexpected status {status} (not 200/429) -- noting, continuing")
        return Counter(statuses)

    log(f"  --- G1: baseline sequential burst of {burst_count} PUTs, fully rested ---")
    baseline_counts = sequential_put_burst("G1")
    log(f"  baseline results: {dict(baseline_counts)}")
    baseline_429 = baseline_counts.get(429, 0)

    log(f"  Resting {rest_seconds}s before the cross-contamination check...")
    time.sleep(rest_seconds)

    log("  --- G2: depleting tracker/{trackerId}'s known bucket via the proven 14-concurrent GET trigger ---")
    tracker_probe_url = GRAPH_BASE + "tracker/" + tracker_id

    def one_get():
        status, _ = request("GET", tracker_probe_url, user_id, token)
        return status

    with ThreadPoolExecutor(max_workers=14) as pool:
        futures = [pool.submit(one_get) for _ in range(14)]
        deplete_statuses = [f.result() for f in as_completed(futures)]
    deplete_counts = Counter(deplete_statuses)
    log(f"    depletion results: {dict(deplete_counts)}")
    record(out, "G2: tracker depletion burst summary", tracker_probe_url, "-", json.dumps(dict(deplete_counts)))
    if deplete_counts.get(429, 0) == 0:
        log("    WARNING: depleting tracker/{trackerId} produced zero 429s this time -- the "
            "cross-contamination check below is unreliable, since depletion may not have actually "
            "happened. Treat with caution (same caveat rate_limit_window_probe.py's own cross-endpoint "
            "mode already carries).")

    log(f"  --- G3: IMMEDIATELY firing the same {burst_count}-PUT sequential burst against pet_detail ---")
    cross_counts = sequential_put_burst("G3")
    log(f"  after-depletion results: {dict(cross_counts)}")
    cross_429 = cross_counts.get(429, 0)

    verdict = ("SHARED (worse after depleting tracker/{trackerId})" if cross_429 > baseline_429
               else "INDEPENDENT (no worse than its own rested baseline)")
    log(f"  VERDICT: baseline 429s={baseline_429}/{burst_count}, after-depletion 429s={cross_429}/{burst_count} "
        f"-> {verdict}")
    result = {"burst_count": burst_count, "baseline_counts": dict(baseline_counts),
              "deplete_counts": dict(deplete_counts), "cross_counts": dict(cross_counts),
              "baseline_429": baseline_429, "cross_429": cross_429, "verdict": verdict}
    record(out, "=== G: rate-limit probe summary ===", "", "-", json.dumps(result))

    log("  Restoring from baseline (content was never actually changed by any burst call, but "
        "settling _version back to a clean state)...")
    restore_from_baseline(out, pd_url, baseline, user_id, token, 3, 60, reason="post-ratelimit-burst")
    return result


def restore_from_baseline(out, url, baseline, user_id, token, post_attempts, post_retry_interval,
                           reason="restore"):
    fresh = get_json(out, f"{reason}: pre-restore GET (fresh _version)", url, user_id, token)
    version = fresh.get("_version") if isinstance(fresh, dict) else baseline.get("_version")
    status, _ = write_json("PUT", out, f"{reason}: RESTORE from baseline", url, user_id, token,
                           full_body(baseline, overrides={"_version": version}),
                           post_attempts, post_retry_interval)
    return ok(status)


def main():
    global CLIENT_ID
    ap = argparse.ArgumentParser(description="Probe the pet_detail write endpoint (see docstring).")
    ap.add_argument("--only",
                    choices=["baseline", "patch", "omit", "staleversion", "realchange", "ratelimit", "all"],
                    default="all", help="'staleversion'/'realchange'/'ratelimit' are deliberate and NOT "
                                        "included in 'all' -- request them explicitly")
    ap.add_argument("--burst-count", type=int, default=14,
                    help="[ratelimit] sequential PUTs per phase (default: 14, matching this project's "
                         "existing concurrent-burst convention for GET endpoints)")
    ap.add_argument("--rate-limit-rest-seconds", type=float, default=600.0,
                    help="[ratelimit] seconds to rest between the baseline burst and the depletion/"
                         "cross-check (default: 600, matching rate_limit_window_probe.py's own default)")
    ap.add_argument("--omit-field", choices=OMITTABLE_FIELDS, default="breed_ids",
                    help="which field to omit for part C (default: breed_ids -- never height/weight)")
    ap.add_argument("--stale-version", default="00000000",
                    help="a deliberately wrong _version to send for part E (default: a generic "
                         "placeholder that's virtually guaranteed to differ from any pet's real "
                         "current value; override if you want to test against a specific known-old "
                         "value instead)")
    ap.add_argument("--change-field", choices=CHANGEABLE_FIELDS, default="height",
                    help="which field part F actually changes-then-restores (default: height)")
    ap.add_argument("--change-delta", type=float, default=None,
                    help="how much to (temporarily) change --change-field by (default: 0.01 for height "
                         "in metres, 10 for weight in grams -- small, restored before this script exits)")
    ap.add_argument("--start-delay", type=int, default=0, help="seconds to wait before the first write (default 0)")
    ap.add_argument("--post-attempts", type=int, default=3, help="max writes per step; retry ONLY on 429 (default 3)")
    ap.add_argument("--post-retry-interval", type=float, default=60.0, help="seconds between 429-retry writes (min 30)")
    ap.add_argument("--client-id", default=CLIENT_ID,
                    help="x-tractive-client header (default: the binding's; pass 53e359a0f1f179e3ef000009 for the app)")
    args = ap.parse_args()
    post_attempts = max(1, min(args.post_attempts, 6))
    post_retry_interval = max(30.0, args.post_retry_interval)

    CLIENT_ID = args.client_id
    if CLIENT_ID != ap.get_default("client_id"):
        log(f"Using non-default x-tractive-client: {CLIENT_ID}")

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    pet_id = os.environ.get("TRACTIVE_PET_ID") or input("Pet ID (24-char hex): ").strip()
    tracker_id = ""
    if args.only == "ratelimit":
        tracker_id = (os.environ.get("TRACTIVE_TRACKER_ID")
                      or input("Tracker ID (8-char, e.g. ABCD1234) -- needed to deplete its bucket "
                               "as the cross-contamination trigger: ").strip())

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    out = open(OUTPUT_FILE, "a", encoding="utf-8")
    record(out, "=== run start ===", "", "-", json.dumps({
        "user_id": user_id, "pet_id": pet_id, "tracker_id": tracker_id, "client_id": CLIENT_ID,
        "only": args.only, "omit_field": args.omit_field, "stale_version": args.stale_version,
        "change_field": args.change_field, "change_delta": args.change_delta,
        "burst_count": args.burst_count, "rate_limit_rest_seconds": args.rate_limit_rest_seconds,
    }))

    if args.start_delay > 0:
        log(f"Sleeping {args.start_delay}s (Ctrl+C aborts cleanly; nothing written yet).")
        time.sleep(args.start_delay)

    to_url = GRAPH_BASE + "trackable_object/" + pet_id

    to0 = get_json(out, "0: GET trackable_object (baseline)", to_url, user_id, token)
    if not isinstance(to0, dict) or not isinstance(to0.get("details"), dict):
        log("Could not read trackable_object/{petId}.details -- aborting, nothing written.")
        record(out, "=== run summary ===", "", "-", json.dumps({"aborted": "no baseline details"}))
        out.close()
        return
    baseline = copy.deepcopy(to0["details"])
    log(f"Baseline captured: { {k: baseline.get(k) for k in WRITE_KEYS} }")

    # pet_detail has its OWN _id, distinct from petId (see docstring). Always take it from a live
    # GET; never assume it equals pet_id.
    pd_id = baseline.get("_id") or pet_id
    if pd_id != pet_id:
        log(f"pet_detail's own _id ({pd_id}) differs from petId ({pet_id}) -- using pet_detail's _id.")
    else:
        log("pet_detail's own _id matches petId -- using it directly (unexpected, but harmless).")
    pd_url = GRAPH_BASE + "pet_detail/" + pd_id
    record(out, "=== resolved pet_detail id ===", "", "-", json.dumps({"pet_id": pet_id, "pet_detail_id": pd_id}))

    results = {}
    try:
        baseline_status = probe_baseline_put(out, pd_url, baseline, user_id, token,
                                             post_attempts, post_retry_interval)
        results["baseline_put_status"] = baseline_status
        results["baseline_put_accepted"] = ok(baseline_status)
        if not ok(baseline_status):
            log("Baseline PUT was rejected -- stopping here. PATCH/omission would only fail the "
                "same way, and there is nothing further to learn safely.")
            return

        if args.only in ("patch", "all"):
            results["patch"] = probe_patch(out, pd_url, baseline, user_id, token,
                                           post_attempts, post_retry_interval)
        if args.only in ("omit", "all"):
            results["omission"] = probe_omission(out, pd_url, baseline, args.omit_field, user_id, token,
                                                  post_attempts, post_retry_interval)
        if args.only == "staleversion":
            results["stale_version"] = probe_stale_version(out, pd_url, baseline, args.stale_version, user_id,
                                                            token, post_attempts, post_retry_interval)
        if args.only == "realchange":
            delta = args.change_delta if args.change_delta is not None else DEFAULT_CHANGE_DELTA[args.change_field]
            results["real_change"] = probe_real_change(out, pd_url, baseline, args.change_field, delta, user_id,
                                                        token, post_attempts, post_retry_interval)
        if args.only == "ratelimit":
            results["rate_limit"] = probe_rate_limit(out, pd_url, tracker_id, baseline, args.burst_count,
                                                      args.rate_limit_rest_seconds, user_id, token)
    except KeyboardInterrupt:
        log("Interrupted -- restoring from baseline before exiting.")
    finally:
        final = get_json(out, "D: final GET (post-run verification)", to_url, user_id, token)
        final_details = final.get("details") if isinstance(final, dict) else None
        residual = diff_write_fields(baseline, final_details)
        if residual:
            log(f"!!! {len(residual)} field(s) still differ from baseline: {residual}")
            log("!!! Attempting emergency restore...")
            restored = False
            for _ in range(3):
                if restore_from_baseline(out, pd_url, baseline, user_id, token, 1, 0, reason="EMERGENCY"):
                    restored = True
                    break
                time.sleep(10)
            final = get_json(out, "D: post-emergency-restore GET", to_url, user_id, token)
            final_details = final.get("details") if isinstance(final, dict) else None
            residual = diff_write_fields(baseline, final_details)
            if residual:
                fresh_version = final_details.get("_version") if isinstance(final_details, dict) else "?"
                log(f"!!! STILL not fully restored: {residual}")
                log(f'!!!   curl -s -X PUT "{pd_url}" -H "x-tractive-client: {CLIENT_ID}" '
                    f'-H "x-tractive-user: {user_id}" -H "authorization: Bearer <token>" '
                    f'-H "content-type: application/json;charset=UTF-8" '
                    f"--data '{json.dumps(full_body(baseline, overrides={'_version': fresh_version}))}'")
            results["emergency_restore_attempted"] = True
            results["fully_restored"] = not residual
        else:
            log("Final state matches baseline for every write-DTO field -- clean.")
            results["fully_restored"] = True
        record(out, "=== run summary ===", "", "-", json.dumps(results))
        log(f"Done. Raw traffic in {OUTPUT_FILE}")
        out.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
