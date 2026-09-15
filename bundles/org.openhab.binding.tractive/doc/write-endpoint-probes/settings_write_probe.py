#!/usr/bin/env python3
"""
Probe two more candidate write endpoints from the Android app teardown, to see whether they are
usable by this binding's own x-tractive-client and whether a shared/invited (read_only: true)
account is allowed to call them -- same questions already answered "yes / not gated" for
battery_save_mode and separation/phase/*.

Endpoints (both on graph.tractive.com/4/ -- confirmed from RetroTrackerApi / r8g):

  A. lt_timeout  -- the live-tracking auto-stop duration
     PUT tracker/{trackerId}/lt_timeout      body {"timeout": <int seconds>}   -> Unit (expect 204)
     (RetroTrackerApi.i; body type LiveTrackingDurationRequest, wire key "timeout")
     Read-back: there is no documented GET for it; the app keeps a `custom_lt_timeout` column
     hydrated from tracker sync. This probe scans GET tracker/{id} for any timeout-ish field to use
     as the restore value; if none is found it restores to 300 (the app's LiveTrackingDuration
     default and the value in every capture) and says so loudly.

  B. activity_setting  -- the pet's daily activity goal (health/overview `activity.minutesGoal`)
     GET  activity_setting/{id}   -> ActivitySettingsGraphObject
     PUT  activity_setting/{id}   body ActivitySettingsGraphObject   -> Unit (expect 204)
     (r8g.g / r8g.e; object keys: daily_goal, daily_distance_goal, daily_active_minutes_goal,
      daily_active_minutes_goal_is_default, _version -- _version is optimistic-concurrency, must be
      echoed back). The DB join is `activity_setting.parentid = pet_detail._id`, so {id} is tried as
      the pet_detail _id first (fetched from trackable_object/{petId}.details._id), then the pet id.

RESULTS:
  A. lt_timeout -- accepted for the OWNER account (200), but 403/8006 for a shared/read_only account: a
     real account-ownership gate, unlike everything else probed in this directory. Not shipped as a
     binding feature (would need read-only-account-guard machinery the binding doesn't otherwise have),
     but confirmed viable as an owner-only follow-up.
  B. activity_setting -- 403/8006 even for the OWNER account: a real client-registry gate (first-party
     app only), not an ownership gate. Dead end regardless of account.

Method -- both parts read -> change ONE field -> restore, ending exactly where they started
------------------------------------------------------------------------------------------
  0. Auth. Baseline reads. --start-delay (default 0) if given.
  A. lt_timeout: GET tracker/{id} (record + find a timeout field). PUT timeout=--lt-test-value
     (default 601 -- distinctive, ~10 min, sane). Re-GET tracker/{id} a few times, diff. PUT back
     to the original (or 300).
  B. activity_setting: locate the resource, GET it, cross-check goal against health/overview. PUT
     the same object with daily_active_minutes_goal = goal+1 and ..._is_default = 0. Re-GET
     activity_setting + health/overview to confirm. Re-GET for the fresh _version, PUT back to the
     original goal / is_default / _version. Confirm.

Each PUT retries ONLY on HTTP 429. A finally block emergency-restores anything left changed and
prints a manual curl.

Credentials: TRACTIVE_EMAIL / TRACTIVE_PASSWORD. Tracker + pet IDs: TRACTIVE_TRACKER_ID (8-char) and
TRACTIVE_PET_ID (24-char hex) if set, else prompts. Raw traffic -> settings_write_probe_output.jsonl.

Usage:
    python settings_write_probe.py
    python settings_write_probe.py --only activity
    python settings_write_probe.py --only lt --lt-test-value 655
    python settings_write_probe.py --client-id 53e359a0f1f179e3ef000009
"""

import argparse
import getpass
import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

CLIENT_ID = "625e533dc3c3b41c28a669f0"
GRAPH_BASE = "https://graph.tractive.com/4/"
APS_BASE = "https://aps-api.tractive.com/api/1/"
OUTPUT_FILE = "settings_write_probe_output.jsonl"
LT_DEFAULT_RESTORE = 300


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


def put_json(out, label, url, user_id, token, payload, post_attempts, post_retry_interval):
    data = json.dumps(payload).encode("utf-8") if payload is not None else b""
    status, body = None, ""
    for attempt in range(1, post_attempts + 1):
        status, body = request("PUT", url, user_id, token, data=data)
        record(out, f"{label} (attempt {attempt})", url, status, body)
        if status != 429:
            break
        if attempt < post_attempts:
            log(f"  429 -- waiting {post_retry_interval:g}s before attempt {attempt + 1}")
            time.sleep(post_retry_interval)
    return status, body


def ok(status):
    return isinstance(status, int) and 200 <= status < 300


def verdict(status):
    if ok(status):
        return "VERDICT: ACCEPTED for this x-tractive-client ID"
    if status == 403:
        return ("VERDICT: 403 -- client-registry or account gate. Re-run with "
                "--client-id 53e359a0f1f179e3ef000009 to tell those apart.")
    if status == 404:
        return "VERDICT: 404 -- wrong id or path."
    if status == 409:
        return "VERDICT: 409 -- version conflict; re-read and retry."
    if status == 401:
        return "VERDICT: 401 -- auth problem, not an endpoint verdict."
    return f"VERDICT: unexpected status {status} -- see {OUTPUT_FILE}."


def find_timeout_field(obj):
    """First (key, int-value) in a tracker/{id} body whose key mentions 'timeout', or (None, None)."""
    if not isinstance(obj, dict):
        return None, None
    for k, v in obj.items():
        if "timeout" in k.lower() and isinstance(v, (int, float)):
            return k, int(v)
    return None, None


def probe_lt_timeout(out, tracker_id, user_id, token, test_value, post_attempts, post_retry_interval):
    log("========== A: lt_timeout ==========")
    tr_url = GRAPH_BASE + "tracker/" + tracker_id
    lt_url = tr_url + "/lt_timeout"

    base = get_json(out, "lt baseline GET tracker", tr_url, user_id, token)
    key, original = find_timeout_field(base)
    if key:
        log(f"Found timeout field on tracker/{{id}}: {key} = {original} -- will restore to that")
    else:
        original = LT_DEFAULT_RESTORE
        log(f"No timeout field on tracker/{{id}} -- will restore to the default {original} (verify manually!)")

    flip_status = None
    restored_ok = False
    try:
        log(f"--- lt: PUT timeout={test_value} ---")
        flip_status, _ = put_json(out, f"lt PUT timeout={test_value}", lt_url, user_id, token,
                                  {"timeout": test_value}, post_attempts, post_retry_interval)
        log(verdict(flip_status))
        if not ok(flip_status):
            return {"part": "lt_timeout", "flip_status": flip_status, "accepted": False}

        for i in (1, 2, 3):
            time.sleep(15)
            after = get_json(out, f"lt watch GET tracker (#{i})", tr_url, user_id, token)
            k2, v2 = find_timeout_field(after)
            if k2:
                log(f"  {k2} = {v2}")
        log(f"--- lt: RESTORE timeout={original} ---")
        rs, _ = put_json(out, f"lt PUT restore timeout={original}", lt_url, user_id, token,
                         {"timeout": original}, post_attempts, post_retry_interval)
        restored_ok = ok(rs)
    finally:
        if ok(flip_status) and not restored_ok:
            for _ in range(3):
                rs, _ = put_json(out, f"lt EMERGENCY restore timeout={original}", lt_url, user_id, token,
                                 {"timeout": original}, 1, 0)
                if ok(rs):
                    restored_ok = True
                    break
                time.sleep(10)
        final = get_json(out, "lt final GET tracker", tr_url, user_id, token)
        kf, vf = find_timeout_field(final)
        summary = {"part": "lt_timeout", "flip_status": flip_status, "accepted": ok(flip_status),
                   "timeout_field_key": kf, "timeout_field_value_final": vf,
                   "restore_target": original, "restored_ok": restored_ok}
        record(out, "=== lt_timeout summary ===", "", "-", json.dumps(summary))
        if not restored_ok and ok(flip_status):
            log(f"!!! lt_timeout may be left at {test_value}. Restore manually:")
            log(f'!!!   curl -s -X PUT "{lt_url}" -H "x-tractive-client: {CLIENT_ID}" '
                f'-H "x-tractive-user: {user_id}" -H "authorization: Bearer <token>" '
                f'-H "content-type: application/json;charset=UTF-8" --data \'{{"timeout": {original}}}\'')
        return summary


def resolve_activity_setting(out, pet_id, user_id, token):
    """Return (id_used, current_object) or (None, None). Tries pet_detail._id then pet id."""
    to = get_json(out, "act resolve: GET trackable_object", GRAPH_BASE + "trackable_object/" + pet_id,
                  user_id, token)
    candidates = []
    if isinstance(to, dict) and isinstance(to.get("details"), dict) and to["details"].get("_id"):
        candidates.append(("pet_detail._id", to["details"]["_id"]))
    candidates.append(("pet id", pet_id))
    for label, cand in candidates:
        obj = get_json(out, f"act GET activity_setting/{{{label}}}", GRAPH_BASE + "activity_setting/" + cand,
                       user_id, token)
        if isinstance(obj, dict):
            log(f"activity_setting resolved via {label} = {cand}")
            return cand, obj
    return None, None


def probe_activity_setting(out, pet_id, user_id, token, post_attempts, post_retry_interval):
    log("========== B: activity_setting ==========")
    as_id, obj0 = resolve_activity_setting(out, pet_id, user_id, token)
    if not as_id or not isinstance(obj0, dict):
        record(out, "=== activity_setting summary ===", "", "-",
               json.dumps({"part": "activity_setting", "accepted": None, "note": "resource not located"}))
        log("Could not locate the activity_setting resource -- no writes attempted.")
        return {"part": "activity_setting", "accepted": None}

    as_url = GRAPH_BASE + "activity_setting/" + as_id
    goal0 = obj0.get("daily_active_minutes_goal")
    is_default0 = obj0.get("daily_active_minutes_goal_is_default")
    version0 = obj0.get("_version")
    log(f"Baseline: daily_active_minutes_goal={goal0}, is_default={is_default0}, _version={version0}")
    ho = get_json(out, "act cross-check GET health/overview",
                  APS_BASE + "pet/" + pet_id + "/health/overview", user_id, token)
    if isinstance(ho, dict) and isinstance(ho.get("activity"), dict):
        log(f"  health/overview activity.minutesGoal = {ho['activity'].get('minutesGoal')} (should match {goal0})")
    if not isinstance(goal0, int):
        record(out, "=== activity_setting summary ===", "", "-",
               json.dumps({"part": "activity_setting", "accepted": None, "note": "no int goal to flip"}))
        log("daily_active_minutes_goal is not an int -- no writes attempted.")
        return {"part": "activity_setting", "accepted": None}

    target = goal0 + 1

    def body(goal, is_default, version):
        return {
            "daily_goal": obj0.get("daily_goal"),
            "daily_distance_goal": obj0.get("daily_distance_goal"),
            "daily_active_minutes_goal": goal,
            "daily_active_minutes_goal_is_default": is_default,
            "_version": version,
        }

    flip_status = None
    restored_ok = False
    try:
        log(f"--- act: PUT daily_active_minutes_goal={target} ---")
        flip_status, _ = put_json(out, f"act PUT goal={target}", as_url, user_id, token,
                                  body(target, 0, version0), post_attempts, post_retry_interval)
        log(verdict(flip_status))
        if not ok(flip_status):
            return {"part": "activity_setting", "flip_status": flip_status, "accepted": False}

        time.sleep(10)
        after = get_json(out, "act watch GET activity_setting", as_url, user_id, token)
        ho2 = get_json(out, "act watch GET health/overview",
                       APS_BASE + "pet/" + pet_id + "/health/overview", user_id, token)
        if isinstance(after, dict):
            log(f"  activity_setting now goal={after.get('daily_active_minutes_goal')}")
        if isinstance(ho2, dict) and isinstance(ho2.get("activity"), dict):
            log(f"  health/overview now minutesGoal={ho2['activity'].get('minutesGoal')}")

        fresh = get_json(out, "act pre-restore GET (fresh _version)", as_url, user_id, token)
        version1 = fresh.get("_version") if isinstance(fresh, dict) else version0
        log(f"--- act: RESTORE goal={goal0}, is_default={is_default0}, _version={version1} ---")
        rs, _ = put_json(out, f"act PUT restore goal={goal0}", as_url, user_id, token,
                         body(goal0, is_default0, version1), post_attempts, post_retry_interval)
        restored_ok = ok(rs)
    finally:
        if ok(flip_status) and not restored_ok:
            for _ in range(3):
                fresh = get_json(out, "act EMERGENCY pre-restore GET", as_url, user_id, token)
                v = fresh.get("_version") if isinstance(fresh, dict) else None
                rs, _ = put_json(out, "act EMERGENCY restore", as_url, user_id, token,
                                 body(goal0, is_default0, v), 1, 0)
                if ok(rs):
                    restored_ok = True
                    break
                time.sleep(10)
        final = get_json(out, "act final GET activity_setting", as_url, user_id, token)
        final_goal = final.get("daily_active_minutes_goal") if isinstance(final, dict) else None
        summary = {"part": "activity_setting", "id_used": as_id, "flip_status": flip_status,
                   "accepted": ok(flip_status), "baseline_goal": goal0, "final_goal": final_goal,
                   "restored_ok": final_goal == goal0}
        record(out, "=== activity_setting summary ===", "", "-", json.dumps(summary))
        if final_goal != goal0:
            log(f"!!! activity goal may be left at {target}. Restore it in the Tractive app "
                f"(Pet > Activity goal), or PUT {as_url} with daily_active_minutes_goal={goal0}.")
        return summary


def main():
    global CLIENT_ID
    ap = argparse.ArgumentParser(description="Probe lt_timeout and activity_setting writes (see docstring).")
    ap.add_argument("--only", choices=["lt", "activity", "both"], default="both")
    ap.add_argument("--lt-test-value", type=int, default=601, help="temporary lt_timeout in seconds (default 601)")
    ap.add_argument("--start-delay", type=int, default=0, help="seconds to wait before the first write (default 0)")
    ap.add_argument("--post-attempts", type=int, default=3, help="max PUTs per write; retry ONLY on 429 (default 3)")
    ap.add_argument("--post-retry-interval", type=float, default=60.0, help="seconds between 429-retry PUTs (min 30)")
    ap.add_argument("--client-id", default=CLIENT_ID,
                    help="x-tractive-client header (default: the binding's; pass 53e359a0f1f179e3ef000009 for the app)")
    args = ap.parse_args()
    post_attempts = max(1, min(args.post_attempts, 6))
    post_retry_interval = max(30.0, args.post_retry_interval)
    lt_test_value = max(60, min(args.lt_test_value, 3600))

    CLIENT_ID = args.client_id
    if CLIENT_ID != ap.get_default("client_id"):
        log(f"Using non-default x-tractive-client: {CLIENT_ID}")

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (8-char): ").strip()
    pet_id = os.environ.get("TRACTIVE_PET_ID") or input("Pet ID (24-char hex): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    out = open(OUTPUT_FILE, "a", encoding="utf-8")
    record(out, "=== run start ===", "", "-", json.dumps({
        "user_id": user_id, "tracker_id": tracker_id, "pet_id": pet_id, "client_id": CLIENT_ID,
        "only": args.only, "lt_test_value": lt_test_value,
    }))

    if args.start_delay > 0:
        log(f"Sleeping {args.start_delay}s (Ctrl+C aborts cleanly; nothing changed yet).")
        time.sleep(args.start_delay)

    results = []
    try:
        if args.only in ("lt", "both"):
            results.append(probe_lt_timeout(out, tracker_id, user_id, token, lt_test_value,
                                            post_attempts, post_retry_interval))
        if args.only in ("activity", "both"):
            results.append(probe_activity_setting(out, pet_id, user_id, token, post_attempts, post_retry_interval))
    except KeyboardInterrupt:
        log("Interrupted -- any in-flight part ran its own finally-block restore.")
    finally:
        record(out, "=== run summary ===", "", "-", json.dumps({"results": results}))
        log(f"Done. Raw traffic in {OUTPUT_FILE}")
        for r in results:
            log(f"  {r.get('part')}: accepted={r.get('accepted')}  restored_ok={r.get('restored_ok')}")
        out.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
