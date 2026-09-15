#!/usr/bin/env python3
"""
Probe POST tracker/{trackerId}/battery_save_mode -- the "toggle battery-save mode" write endpoint
found in the Android app teardown (apk-teardown/, Retrofit interface obfuscated/f9j.java):

    @POST("tracker/{id}/battery_save_mode")            # @bxd = @POST  (eg9.java:574-575)
    Object d(@Path("id") String id,                    # @r4e = @Path
             @Body IsBatterySaveModeOn body,           # @r62 = @Body  (eg9.java:389-395)
             Continuation<BatterySaveModeResponse>)

  request  body : {"battery_save_mode": <bool>}        (com.tractive.android.network.api.IsBatterySaveModeOn)
  response body : {"_id": "<trackerId>", "battery_save_mode": <bool|null>}   (BatterySaveModeResponse)

The binding already READS `battery_save_mode` off GET tracker/{id} but has no way to set it. Open
questions this probe answers, toward making it a real Switch channel / Action:
  1. Is the write allowed for the binding's own x-tractive-client ID (625e...), or is it gated to the
     official app the way tracker/{id}/request_status_update turned out to be (403 / code 8006)?
  2. Does the new value take effect server-side immediately (visible on the next GET tracker/{id})?
  3. Does toggling battery-save mode visibly change anything else -- `state`, `state_reason`, or the
     tracker's report cadence (device_pos_report / device_hw_report `.time`)?

RESULTS: yes, allowed for this binding's own client (question 1); the new value is visible on the very
next GET (question 2); and it doesn't visibly move `state`/`state_reason`/report cadence beyond normal
noise (question 3). Now shipped as the writable `device-info#battery-save-mode` Switch channel, kept on
the binding's recurring poll schedule since it's mutable (unlike the rest of that channel group).

Method -- two writes, always ending on the ORIGINAL value so nothing is left changed
-----------------------------------------------------------------------------------
  0. --start-delay (default 600 s = 10 min): sleep FIRST, so this can be launched right before
     leaving for a walk and only act once the dog is actually out of its Power Saving Zone.
     Auth + a pre-flight baseline read happen BEFORE the sleep, so a bad password / wrong tracker
     ID fails fast and nothing is scheduled against a tracker we can't even read.
  1. Baseline: GET tracker/{id}, device_pos_report/{id}, device_hw_report/{id}/. Record
     `battery_save_mode` (= B, the restore target), `state`, `state_reason`, and each report `.time`.
     ABORT before writing anything if B is not a readable boolean.
  2. FLIP    : POST battery_save_mode = (not B). Retry ONLY on HTTP 429, up to --post-attempts times.
     Then poll all three GETs every --interval s for --observe-seconds, logging when
     `battery_save_mode` / `state` / `state_reason` / the report `.time`s change. This window also
     serves as the cooldown before the restore write.
  3. RESTORE : POST battery_save_mode = B. Same retry, same observe window.
  4. Final GET tracker/{id}; confirm `battery_save_mode` is back to B. Print a verdict.

If the baseline is already TRUE (unexpected -- every capture to date shows false), phase 1 DISABLES
and phase 2 RE-ENABLES instead. Either way the end state equals the baseline.

Safety
------
Any path that leaves FLIP applied but RESTORE unconfirmed (non-2xx restore, crash, Ctrl+C) triggers a
best-effort emergency restore in a finally block, then prints an explicit manual-fix curl if even
that fails. Worst realistic case: mode left flipped + a printed one-liner to undo it.

Run with the pet's OWNER account. A shared account also has to clear the separate
`read_only` gate, which would confound "is the client allowed" with "is this account allowed" -- a
later run with the shared account is how you test that gate specifically.

Rate limits: the GETs hit graph.tractive.com's shared bucket; --interval defaults to 30 s (3 GETs
per cycle, well within the measured safe rate -- see doc/rate-limit-probes/README.md). A GET 429
backs off 6 s and retries once. POSTs retry ONLY on 429, bounded by --post-attempts / --post-retry-
interval.

Credentials + tracker ID: TRACTIVE_EMAIL / TRACTIVE_PASSWORD / TRACTIVE_TRACKER_ID env vars if set,
else interactive prompts (hidden password). Every request (status + full body) is appended to
battery_save_mode_probe_output.jsonl; a compact line goes to stdout.

Default runtime ~= start-delay + 2 x observe-seconds + overhead  (10 + 15 + 15 min ~= 40 min).

Usage:
    python battery_save_mode_probe.py
    python battery_save_mode_probe.py --start-delay 1200 --observe-seconds 1200 --interval 30
    python battery_save_mode_probe.py --start-delay 0             # run immediately
    python battery_save_mode_probe.py --client-id 53e359a0f1f179e3ef000009   # retry as the official app
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
GRAPH_BASE_URL = "https://graph.tractive.com/4/"
OUTPUT_FILE = "battery_save_mode_probe_output.jsonl"


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def authenticate(email, password):
    url = GRAPH_BASE_URL + "auth/token"
    body = json.dumps({
        "platform_email": email,
        "platform_token": password,
        "grant_type": "tractive",
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "x-tractive-client": CLIENT_ID,
        "content-type": "application/json;charset=UTF-8",
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
    """Return (status, body_text). status is an int HTTP code, or a string 'ERR:<reason>'."""
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
        "label": label,
        "url": url,
        "status": status,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "body": body,
    }) + "\n")
    out.flush()
    preview = " ".join(body.split())
    if len(preview) > 240:
        preview = preview[:240] + "..."
    log(f"{str(status):>6}  {label:<36}  {preview}")


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


def report_time(obj):
    """The `time` epoch of a device_pos_report / device_hw_report body, or None."""
    if isinstance(obj, dict) and isinstance(obj.get("time"), (int, float)):
        return int(obj["time"])
    return None


def fmt_epoch(epoch):
    if epoch is None:
        return "n/a"
    return f"{epoch} ({datetime.fromtimestamp(epoch, timezone.utc).strftime('%H:%M:%SZ')})"


def tracker_snapshot(obj):
    """Interesting fields off a GET tracker/{id} body."""
    if not isinstance(obj, dict):
        return {}
    return {k: obj.get(k) for k in (
        "battery_save_mode", "state", "state_reason", "battery_state",
        "charging_state", "prioritized_zone_type",
    )}


def snapshot(out, label, urls, user_id, token):
    """Three GETs -> {snap, pos_time, hw_time, tracker_ok}."""
    tracker_url, pos_url, hw_url = urls
    tr = get_json(out, f"{label} tracker", tracker_url, user_id, token)
    pos = get_json(out, f"{label} device_pos_report", pos_url, user_id, token)
    hw = get_json(out, f"{label} device_hw_report", hw_url, user_id, token)
    return {
        "snap": tracker_snapshot(tr),
        "pos_time": report_time(pos),
        "hw_time": report_time(hw),
        "tracker_ok": isinstance(tr, dict),
    }


def post_bsm(out, url, user_id, token, target, phase, post_attempts, post_retry_interval):
    """POST battery_save_mode = target (bool). Retry ONLY on 429. Return (status, echoed_value)."""
    payload = json.dumps({"battery_save_mode": bool(target)}).encode("utf-8")
    status, body = None, ""
    for attempt in range(1, post_attempts + 1):
        status, body = request("POST", url, user_id, token, data=payload)
        record(out, f"{phase}: POST battery_save_mode={target} (attempt {attempt})", url, status, body)
        if status != 429:
            break
        if attempt < post_attempts:
            log(f"  429 -- waiting {post_retry_interval:g}s before attempt {attempt + 1}")
            time.sleep(post_retry_interval)
    echoed = None
    if isinstance(status, int) and 200 <= status < 300:
        try:
            echoed = json.loads(body).get("battery_save_mode")
        except (ValueError, AttributeError):
            pass
    return status, echoed


def observe(out, phase, urls, user_id, token, expected_bsm, base, observe_seconds, interval):
    """Poll the three GETs for observe_seconds, logging changes vs. the pre-write snapshot `base`."""
    tracker_url, pos_url, hw_url = urls
    start = time.time()
    deadline = start + observe_seconds
    bsm_visible_after = None
    state_changes = []
    pos_seen, hw_seen = base["pos_time"], base["hw_time"]
    pos_adv_after = hw_adv_after = None
    last_state = (base["snap"].get("state"), base["snap"].get("state_reason"))
    log(f"----- {phase}: observing {observe_seconds}s (expect tracker.battery_save_mode -> {expected_bsm}) -----")
    while time.time() < deadline:
        time.sleep(interval)
        elapsed = round(time.time() - start)
        tr = get_json(out, f"{phase} watch tracker (+{elapsed}s)", tracker_url, user_id, token)
        pos = get_json(out, f"{phase} watch device_pos_report (+{elapsed}s)", pos_url, user_id, token)
        hw = get_json(out, f"{phase} watch device_hw_report (+{elapsed}s)", hw_url, user_id, token)
        snap = tracker_snapshot(tr)
        if bsm_visible_after is None and snap.get("battery_save_mode") == expected_bsm:
            bsm_visible_after = elapsed
            log(f"  * tracker.battery_save_mode == {expected_bsm} on GET at +{elapsed}s")
        cur_state = (snap.get("state"), snap.get("state_reason"))
        if cur_state != last_state:
            state_changes.append({"elapsed": elapsed, "from": last_state, "to": cur_state})
            log(f"  * state/state_reason {last_state} -> {cur_state} at +{elapsed}s")
            last_state = cur_state
        pt, ht = report_time(pos), report_time(hw)
        if pt is not None and (pos_seen is None or pt > pos_seen):
            pos_seen = pt
            if pos_adv_after is None:
                pos_adv_after = elapsed
                log(f"  * device_pos_report.time advanced to {fmt_epoch(pt)} at +{elapsed}s")
        if ht is not None and (hw_seen is None or ht > hw_seen):
            hw_seen = ht
            if hw_adv_after is None:
                hw_adv_after = elapsed
                log(f"  * device_hw_report.time advanced to {fmt_epoch(ht)} at +{elapsed}s")
    result = {
        "phase": phase,
        "expected_bsm": expected_bsm,
        "bsm_visible_on_get_after_seconds": bsm_visible_after,
        "state_changes": state_changes,
        "pos_time_pre_write": base["pos_time"], "pos_time_final": pos_seen,
        "pos_advanced_after_seconds": pos_adv_after,
        "hw_time_pre_write": base["hw_time"], "hw_time_final": hw_seen,
        "hw_advanced_after_seconds": hw_adv_after,
    }
    record(out, f"=== {phase} observe summary ===", "", "-", json.dumps(result))
    return result


def _verdict_line(status):
    if status == 403:
        return ("VERDICT: 403 -- likely a client-registry gate (same shape as request_status_update's "
                "8006). Re-run with --client-id 53e359a0f1f179e3ef000009 to confirm it's the client, "
                "not the account; if so, the binding can't use this endpoint as-is.")
    if status == 401:
        return "VERDICT: 401 -- an auth/token problem, not an endpoint verdict."
    if isinstance(status, int) and 200 <= status < 300:
        return "VERDICT: write ACCEPTED for this x-tractive-client ID."
    return f"VERDICT: unexpected status {status} -- inspect the body in {OUTPUT_FILE}."


def _emergency_restore(out, bsm_url, user_id, token, original_bsm):
    log(f"EMERGENCY RESTORE: forcing battery_save_mode back to {original_bsm}")
    for attempt in range(1, 6):
        status, body = request("POST", bsm_url, user_id, token,
                               data=json.dumps({"battery_save_mode": bool(original_bsm)}).encode("utf-8"))
        record(out, f"EMERGENCY RESTORE POST (attempt {attempt})", bsm_url, status, body)
        if isinstance(status, int) and 200 <= status < 300:
            log(f"  emergency restore accepted (HTTP {status})")
            return
        time.sleep(15)
    log("  emergency restore did NOT succeed after 5 attempts -- see the manual curl in the summary")


def main():
    global CLIENT_ID
    ap = argparse.ArgumentParser(description="Probe POST tracker/{id}/battery_save_mode (see module docstring).")
    ap.add_argument("--start-delay", type=int, default=600,
                    help="seconds to wait before the first write (default 600 = 10 min; 0 = immediate)")
    ap.add_argument("--observe-seconds", type=int, default=900,
                    help="seconds to poll after each write (default 900); the phase-1 window is also the "
                         "cooldown before the restore write (min 60)")
    ap.add_argument("--interval", type=float, default=30.0, help="seconds between watch polls (default 30, min 5)")
    ap.add_argument("--post-attempts", type=int, default=3,
                    help="max POSTs per write; retries ONLY on 429 (default 3, max 6)")
    ap.add_argument("--post-retry-interval", type=float, default=60.0,
                    help="seconds between 429-retry POSTs (default 60, min 30)")
    ap.add_argument("--client-id", default=CLIENT_ID,
                    help="x-tractive-client header (default: the binding's 625e...; pass the official app's "
                         "53e359a0f1f179e3ef000009 to tell a client-registry 403/8006 apart from a hard deny)")
    args = ap.parse_args()

    observe_seconds = max(60, args.observe_seconds)
    interval = max(5.0, args.interval)
    post_attempts = max(1, min(args.post_attempts, 6))
    post_retry_interval = max(30.0, args.post_retry_interval)

    CLIENT_ID = args.client_id
    if CLIENT_ID != ap.get_default("client_id"):
        log(f"Using non-default x-tractive-client: {CLIENT_ID}")

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (8-char, e.g. ABCD1234): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    tracker_url = f"{GRAPH_BASE_URL}tracker/{tracker_id}"
    bsm_url = f"{tracker_url}/battery_save_mode"
    pos_url = f"{GRAPH_BASE_URL}device_pos_report/{tracker_id}"
    hw_url = f"{GRAPH_BASE_URL}device_hw_report/{tracker_id}/"
    urls = (tracker_url, pos_url, hw_url)

    out = open(OUTPUT_FILE, "a", encoding="utf-8")
    record(out, "=== run start ===", "", "-", json.dumps({
        "user_id": user_id, "tracker_id": tracker_id, "client_id": CLIENT_ID,
        "start_delay": args.start_delay, "observe_seconds": observe_seconds, "interval": interval,
        "post_attempts": post_attempts, "post_retry_interval": post_retry_interval,
    }))

    # --- pre-flight baseline, BEFORE the delay, so a bad tracker id / account fails fast ---
    log("Pre-flight baseline read...")
    base0 = snapshot(out, "preflight baseline", urls, user_id, token)
    if not base0["tracker_ok"] or not isinstance(base0["snap"].get("battery_save_mode"), bool):
        record(out, "=== ABORT ===", "", "-", json.dumps({
            "reason": "GET tracker/{id} did not return a boolean battery_save_mode",
            "snapshot": base0["snap"],
        }))
        log("ABORT: GET tracker/{id} did not return a boolean battery_save_mode -- nothing written.")
        out.close()
        return

    original_bsm = base0["snap"]["battery_save_mode"]
    flip_target = not original_bsm
    log(f"Baseline battery_save_mode = {original_bsm}  "
        f"(state={base0['snap'].get('state')}, state_reason={base0['snap'].get('state_reason')}, "
        f"pos.time={fmt_epoch(base0['pos_time'])}, hw.time={fmt_epoch(base0['hw_time'])})")
    if original_bsm:
        log("NOTE: baseline is already TRUE -- phase 1 DISABLES, phase 2 RE-ENABLES. End state == baseline.")

    if args.start_delay > 0:
        log(f"Sleeping {args.start_delay}s before the first write -- leave for the walk now. "
            f"(Ctrl+C now aborts cleanly; nothing has been changed yet.)")
        slept = 0
        while slept < args.start_delay:
            chunk = min(120, args.start_delay - slept)
            time.sleep(chunk)
            slept += chunk
            if slept < args.start_delay:
                log(f"  ...{args.start_delay - slept}s to go")

    flip_status = None
    restore_status = None
    restore_confirmed = False
    try:
        # --- PHASE 1: flip away from baseline ---
        log(f"===== PHASE 1 (FLIP): battery_save_mode -> {flip_target} =====")
        b1 = snapshot(out, "phase1 pre-write", urls, user_id, token)
        flip_status, flip_echo = post_bsm(out, bsm_url, user_id, token, flip_target,
                                          "PHASE1", post_attempts, post_retry_interval)
        if not (isinstance(flip_status, int) and 200 <= flip_status < 300):
            record(out, "=== PHASE 1 not accepted ===", "", "-",
                   json.dumps({"post_status": flip_status, "target": flip_target}))
            log(f"PHASE 1 POST not accepted (status {flip_status}). Nothing changed; no restore needed.")
            log(_verdict_line(flip_status))
            return
        log(f"PHASE 1 accepted (HTTP {flip_status}); response echoed battery_save_mode={flip_echo}")
        log(_verdict_line(flip_status))
        observe(out, "PHASE1", urls, user_id, token, flip_target, b1, observe_seconds, interval)

        # --- PHASE 2: restore to baseline ---
        log(f"===== PHASE 2 (RESTORE): battery_save_mode -> {original_bsm} =====")
        b2 = snapshot(out, "phase2 pre-write", urls, user_id, token)
        restore_status, restore_echo = post_bsm(out, bsm_url, user_id, token, original_bsm,
                                                "PHASE2", post_attempts, post_retry_interval)
        restore_confirmed = isinstance(restore_status, int) and 200 <= restore_status < 300
        if restore_confirmed:
            log(f"PHASE 2 accepted (HTTP {restore_status}); response echoed battery_save_mode={restore_echo}")
            observe(out, "PHASE2", urls, user_id, token, original_bsm, b2, observe_seconds, interval)
        else:
            log(f"PHASE 2 POST not accepted (status {restore_status}) -- emergency restore will run.")
    except KeyboardInterrupt:
        log("Interrupted -- attempting emergency restore if phase 1 had applied.")
    finally:
        flip_applied = isinstance(flip_status, int) and 200 <= flip_status < 300
        if flip_applied and not restore_confirmed:
            _emergency_restore(out, bsm_url, user_id, token, original_bsm)
        final = snapshot(out, "final confirmation", urls, user_id, token)
        final_bsm = final["snap"].get("battery_save_mode")
        record(out, "=== run summary ===", "", "-", json.dumps({
            "original_battery_save_mode": original_bsm,
            "flip_target": flip_target,
            "flip_post_status": flip_status,
            "restore_post_status": restore_status,
            "final_battery_save_mode_on_get": final_bsm,
            "restored_ok": final_bsm == original_bsm,
        }))
        if final_bsm == original_bsm:
            log(f"DONE. battery_save_mode is back to its original value ({original_bsm}). Raw log: {OUTPUT_FILE}")
        else:
            log("!!! WARNING: could not confirm battery_save_mode was restored. "
                f"GET tracker/{tracker_id} shows battery_save_mode={final_bsm}, original was {original_bsm}.")
            log("!!! Restore it manually, e.g. (fill in a fresh Bearer token):")
            log(f'!!!   curl -s -X POST "{bsm_url}" \\')
            log(f'!!!     -H "x-tractive-client: {CLIENT_ID}" -H "x-tractive-user: {user_id}" \\')
            log('!!!     -H "authorization: Bearer <token>" -H "content-type: application/json;charset=UTF-8" \\')
            log(f"!!!     --data '{{\"battery_save_mode\": {str(bool(original_bsm)).lower()}}}'")
        out.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
