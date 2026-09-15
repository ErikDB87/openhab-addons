#!/usr/bin/env python3
"""
Probe POST tracker/{trackerId}/request_status_update -- the "ask the cloud to make the tracker report
in now" endpoint found in the Android app teardown (RetroTrackerApi, @bxd = @POST, no body). This was
originally written to answer an open question: does it actually do anything, and in particular does it
wake a tracker sitting dormant deep in its Power Saving Zone -- the state where buzzer / LED /
live-tracking commands just queue forever with no effect. That question is now resolved: the endpoint
is a triple dead end. It returns 403 (code 8006, a client-registry gate) for this binding's own
x-tractive-client, and 429 (code 4006, rate-limited on top of being allowed) for the official GPS
app's client id even across repeated, spaced-out attempts. And on the one run that got a clean accept,
a 5-minute watch of device_pos_report/device_hw_report afterward showed zero movement -- it does not
wake a deep-dormant tracker either way. This script is kept for the method (baseline -> fire -> watch
for a fresh check-in) and as a ready-made way to re-verify any of that on a new tracker model, not
because the endpoint itself is expected to be useful.

Method
------
  1. Baseline: GET device_pos_report/{id}, device_hw_report/{id}/, tracker/{id}. Record each report's
     `time` (last check-in, epoch seconds) plus the tracker's state / state_reason
     (state_reason == POWER_SAVING is the dormant hint).
  2. Fire: POST tracker/{id}/request_status_update (empty body), retrying ONLY on HTTP 429 (the endpoint
     is rate-limited on top of the client gate) up to --post-attempts times, --post-retry-interval apart.
     Any non-429 status stops the loop. Step 3 runs only if a POST actually returns 200.
  3. Watch: re-GET the two report endpoints every --interval seconds for --watch-seconds, and report
     whether / when each report's `time` advances past its baseline -- i.e. whether the tracker
     actually produced a fresh check-in in response to the POST.
  4. --repeat runs the fire+watch cycle more than once (fresh baseline each time).

For a clean answer, run this TWICE and compare:
  * tracker known-awake  (just been moving outdoors)         -> expect a fresh report either way
  * tracker known-dormant (indoors in its PSZ for hours)     -> the interesting case

Auth + headers are identical to the binding and the other probes. Credentials + tracker ID come from
TRACTIVE_EMAIL / TRACTIVE_PASSWORD / TRACTIVE_TRACKER_ID if set, else interactive prompts (hidden
password). Every request (status + full body) is appended to request_status_update_probe_output.jsonl
and a compact line is printed to stdout.

Not breaking anything: request_status_update returns 403/8006 for the binding's client ID and 429/4006
for the official app's -- so probing it usefully means sending the app's client ID (--client-id)
repeatedly over an extended window, which is the part to keep conservative. The retry is bounded:
--post-attempts is capped at 8, --post-retry-interval is floored at 120 s, retries fire ONLY on 429,
and any other status stops the loop. Keep --repeat at 1 when using retries (they compose -- worst case
is post-attempts x repeat POSTs). The watch GETs hit graph.tractive.com's shared bucket; --interval
defaults to 20 s (3 GETs per cycle, well within the measured safe rate -- see
doc/rate-limit-probes/README.md; 0 GET-side 429s across three real runs). A GET 429 backs off 6 s and
retries once.

Usage:
    python request_status_update_probe.py
    python request_status_update_probe.py --client-id 53e359a0f1f179e3ef000009 --post-attempts 6 --post-retry-interval 600
    python request_status_update_probe.py --watch-seconds 600 --interval 30
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
OUTPUT_FILE = "request_status_update_probe_output.jsonl"


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
    if len(preview) > 220:
        preview = preview[:220] + "..."
    log(f"{str(status):>6}  {label:<26}  {preview}")


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


def run_cycle(out, cycle, user_id, token, pos_url, hw_url, tracker_url, watch_seconds, interval,
              post_attempts, post_retry_interval):
    log(f"===== cycle {cycle}: baseline =====")
    pos0 = get_json(out, "baseline device_pos_report", pos_url, user_id, token)
    hw0 = get_json(out, "baseline device_hw_report", hw_url, user_id, token)
    tr0 = get_json(out, "baseline tracker", tracker_url, user_id, token)

    pos_base = report_time(pos0)
    hw_base = report_time(hw0)
    state = tr0.get("state") if isinstance(tr0, dict) else None
    state_reason = tr0.get("state_reason") if isinstance(tr0, dict) else None
    log(f"  pos_report.time = {fmt_epoch(pos_base)} | hw_report.time = {fmt_epoch(hw_base)}")
    log(f"  tracker.state = {state} | state_reason = {state_reason}"
        + ("  <-- DORMANT" if state_reason == "POWER_SAVING" else ""))

    rsu_url = tracker_url + "/request_status_update"
    log(f"----- cycle {cycle}: POST request_status_update "
        f"(<= {post_attempts} attempt(s), {post_retry_interval:g}s apart, retry on 429 only) -----")
    fired_at = time.time()
    status, body = None, ""
    for attempt in range(1, post_attempts + 1):
        status, body = request("POST", rsu_url, user_id, token, data=b"")
        record(out, f"POST request_status_update (attempt {attempt})", rsu_url, status, body)
        fired_at = time.time()
        if status != 429:
            break
        if attempt < post_attempts:
            log(f"  429 -- waiting {post_retry_interval:g}s before attempt {attempt + 1}")
            time.sleep(post_retry_interval)

    if not (isinstance(status, int) and 200 <= status < 300):
        record(out, f"=== cycle {cycle} summary ===", "", "-", json.dumps({
            "cycle": cycle, "dormant_at_baseline": state_reason == "POWER_SAVING",
            "tracker_state": state, "tracker_state_reason": state_reason,
            "post_status": status, "post_succeeded": False,
        }))
        log(f"===== cycle {cycle} verdict: request_status_update not accepted (last status {status}) — no watch =====")
        return

    # A report only counts as a wake-response if its own timestamp is after the POST
    # (minus a little slack for clock skew / a fix already in flight) — the tracker also
    # checks in on its own slow PSZ cadence, so "newer than baseline" alone is too loose.
    post_epoch = int(fired_at) - 120
    log(f"----- cycle {cycle}: POST accepted (HTTP {status}) — watching {watch_seconds}s for a fresh check-in "
        f"(report.time must exceed {fmt_epoch(post_epoch)}) -----")
    pos_seen, hw_seen = pos_base, hw_base
    pos_advanced_after = hw_advanced_after = None
    deadline = fired_at + watch_seconds
    while time.time() < deadline:
        time.sleep(interval)
        elapsed = round(time.time() - fired_at)
        pos = get_json(out, f"watch device_pos_report (+{elapsed}s)", pos_url, user_id, token)
        hw = get_json(out, f"watch device_hw_report (+{elapsed}s)", hw_url, user_id, token)
        pt, ht = report_time(pos), report_time(hw)
        if pt is not None and (pos_seen is None or pt > pos_seen):
            pos_seen = pt
            if pos_advanced_after is None and pt > post_epoch:
                pos_advanced_after = elapsed
                log(f"  * device_pos_report advanced to {fmt_epoch(pt)} at +{elapsed}s")
        if ht is not None and (hw_seen is None or ht > hw_seen):
            hw_seen = ht
            if hw_advanced_after is None and ht > post_epoch:
                hw_advanced_after = elapsed
                log(f"  * device_hw_report advanced to {fmt_epoch(ht)} at +{elapsed}s")
        if pos_advanced_after is not None and hw_advanced_after is not None:
            log("  both reports advanced -- stopping watch early")
            break

    summary = {
        "cycle": cycle,
        "dormant_at_baseline": state_reason == "POWER_SAVING",
        "tracker_state": state,
        "tracker_state_reason": state_reason,
        "post_status": status,
        "post_succeeded": True,
        "pos_report_time_baseline": pos_base,
        "pos_report_time_final": pos_seen,
        "pos_advanced_after_seconds": pos_advanced_after,
        "hw_report_time_baseline": hw_base,
        "hw_report_time_final": hw_seen,
        "hw_advanced_after_seconds": hw_advanced_after,
    }
    record(out, f"=== cycle {cycle} summary ===", "", "-", json.dumps(summary))
    log(f"===== cycle {cycle} verdict: "
        + ("pos+hw advanced" if pos_advanced_after and hw_advanced_after
           else "pos advanced only" if pos_advanced_after
           else "hw advanced only" if hw_advanced_after
           else "NO fresh check-in within the watch window")
        + f" (dormant at baseline: {summary['dormant_at_baseline']}) =====")


def main():
    global CLIENT_ID
    ap = argparse.ArgumentParser(description="Probe POST tracker/{id}/request_status_update (see module docstring).")
    ap.add_argument("--watch-seconds", type=int, default=300, help="how long to watch for a fresh report per cycle (default 300)")
    ap.add_argument("--interval", type=float, default=20.0, help="seconds between watch polls (default 20)")
    ap.add_argument("--repeat", type=int, default=1, help="number of fire+watch cycles (default 1, max 5)")
    ap.add_argument("--client-id", default=CLIENT_ID,
                    help="x-tractive-client header value (default: the binding's; pass the official app's to "
                         "test whether an 8006 is a client-registry gate). Probing only.")
    ap.add_argument("--post-attempts", type=int, default=1,
                    help="max request_status_update POSTs per cycle; retries ONLY on 429 (default 1, max 8)")
    ap.add_argument("--post-retry-interval", type=float, default=300.0,
                    help="seconds between 429-retry POST attempts (default 300, min 120)")
    args = ap.parse_args()
    repeat = max(1, min(args.repeat, 5))
    post_attempts = max(1, min(args.post_attempts, 8))
    post_retry_interval = max(120.0, args.post_retry_interval)

    CLIENT_ID = args.client_id
    if CLIENT_ID != ap.get_default("client_id"):
        log(f"Using non-default x-tractive-client: {CLIENT_ID}")

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (8-char, e.g. ABCD1234): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    pos_url = f"{GRAPH_BASE_URL}device_pos_report/{tracker_id}"
    hw_url = f"{GRAPH_BASE_URL}device_hw_report/{tracker_id}/"
    tracker_url = f"{GRAPH_BASE_URL}tracker/{tracker_id}"

    with open(OUTPUT_FILE, "a", encoding="utf-8") as out:
        record(out, "=== run start ===", "", "-", json.dumps({
            "user_id": user_id, "tracker_id": tracker_id, "repeat": repeat,
            "watch_seconds": args.watch_seconds, "interval": args.interval, "client_id": CLIENT_ID,
            "post_attempts": post_attempts, "post_retry_interval": post_retry_interval,
        }))
        for cycle in range(1, repeat + 1):
            run_cycle(out, cycle, user_id, token, pos_url, hw_url, tracker_url,
                      args.watch_seconds, args.interval, post_attempts, post_retry_interval)

    log(f"Done. Raw requests appended to {OUTPUT_FILE}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
