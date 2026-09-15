#!/usr/bin/env python3
"""
Probe a handful of graph.tractive.com endpoints the native Android app uses, to see what they return --
discovered by decompiling the Android app's base.apk. Read-only GETs.

Two questions this was originally written to answer -- both now resolved, but the probe itself is still
useful groundwork for the same "what does this endpoint return" style of investigation against a new
tracker model, especially if its widget-data/tracker entries turn out to look different:

  * user/widget-data  -- the app's home-screen-widget bundle (no params). Resolved: it bundles a rough
    position + battery + status + per-tracker subscription state for every tracker in one call. It was
    considered as a lighter replacement for pollAll()'s three GETs per tracker per cycle and rejected for
    that (it drops sensor_used/speed/altitude/pos_uncertainty and everything device-info/tracker-status/
    zone-related, so it's not a drop-in) -- but it IS the endpoint the binding actually polls for its
    subscription-status/subscription-valid-to channels, since it's the only place that carries per-tracker
    subscription state at all.

  * subscription / service state -- resolved, see above: lives in widget-data's per-tracker
    `subscription: {status, valid_to}` object, not in user/me or tracker/{trackerId}. This probe still
    dumps both of those alongside widget-data, useful for cross-checking where a field actually lives if
    a new model's widget-data entry doesn't match what's documented here.

Also dumps user/orion_attributes (seen in the app's endpoint list, purpose still unknown).

Auth + header pattern is identical to the other scripts in this directory and to the binding itself.
Credentials + tracker ID come from TRACTIVE_EMAIL / TRACTIVE_PASSWORD / TRACTIVE_TRACKER_ID if set, else
interactive prompts (hidden password). Every response (status + full body) is appended to
app_endpoint_probe_output.jsonl and a compact line is printed to stdout. One GET every --delay seconds
(default 1.0). A 429 backs off 6 s and retries once, then the call is recorded as failed.

Usage:
    python app_endpoint_probe.py
"""

import argparse
import getpass
import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime

CLIENT_ID = "625e533dc3c3b41c28a669f0"
GRAPH_BASE_URL = "https://graph.tractive.com/4/"
OUTPUT_FILE = "app_endpoint_probe_output.jsonl"


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


def get(url, user_id, access_token):
    """Return (status, body_text). status is an int HTTP code, or a string 'ERR:<reason>'."""
    req = urllib.request.Request(url, method="GET", headers={
        "x-tractive-client": CLIENT_ID,
        "x-tractive-user": user_id,
        "authorization": f"Bearer {access_token}",
        "content-type": "application/json;charset=UTF-8",
    })
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
        "timestamp": datetime.now().isoformat(),
        "body": body,
    }) + "\n")
    out.flush()
    preview = " ".join(body.split())
    if len(preview) > 200:
        preview = preview[:200] + "..."
    log(f"{str(status):>6}  {label:<26}  {preview}")


def call(out, label, url, user_id, token, delay):
    status, body = get(url, user_id, token)
    if status == 429:
        log("  429 -> backing off 6 s, retrying once")
        time.sleep(6)
        status, body = get(url, user_id, token)
    record(out, label, url, status, body)
    time.sleep(delay)


def main():
    ap = argparse.ArgumentParser(description="Probe app-only graph.tractive.com endpoints (see module docstring).")
    ap.add_argument("--delay", type=float, default=1.0, help="seconds between calls (default 1.0)")
    args = ap.parse_args()

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (8-char, e.g. ABCD1234): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    endpoints = [
        ("user/me", f"{GRAPH_BASE_URL}user/me"),
        ("user/widget-data", f"{GRAPH_BASE_URL}user/widget-data"),
        ("user/{id}/trackers", f"{GRAPH_BASE_URL}user/{user_id}/trackers"),
        ("tracker/{trackerId}", f"{GRAPH_BASE_URL}tracker/{tracker_id}"),
        ("user/orion_attributes", f"{GRAPH_BASE_URL}user/orion_attributes"),
    ]

    with open(OUTPUT_FILE, "a", encoding="utf-8") as out:
        record(out, "=== run start ===", "", "-", json.dumps({"user_id": user_id, "tracker_id": tracker_id}))
        for label, url in endpoints:
            call(out, label, url, user_id, token, args.delay)

    log(f"Done. Raw responses appended to {OUTPUT_FILE}")
    log("Grep the output for: subscription, service_paused, dunning, plan_type, insurance, valid_to")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
