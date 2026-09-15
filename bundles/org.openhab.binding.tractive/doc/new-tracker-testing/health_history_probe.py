#!/usr/bin/env python3
"""
Probe the per-day / historical health endpoints the native Tractive Android app uses, discovered by
decompiling the Android app's base.apk (com.tractive.android.gps). The two RANGE endpoints below
(activity/overview, sleep/overview) are now used by the binding's getHealthHistory(from, to) Thing
Action; the six per-day endpoints and activity/streak-overview are not, and aren't currently planned to
be -- per-day/per-metric historical detail is a straightforward follow-on using the same plumbing if it's
ever wanted. This script still fetches raw JSON for the last N days, useful for inspecting/comparing the
real response shapes against a new tracker model's pet.

What it calls, once per day in the window (aps-api.tractive.com, ?date=YYYY-MM-DD):
    /api/2/pet/{petId}/activity/day-overview
    /api/2/pet/{petId}/sleep/day-overview
    /api/1/pet/{petId}/bark/day-overview
    /api/1/pet/{petId}/resting-heart-rate/day-overview
    /api/1/pet/{petId}/resting-respiratory-rate/day-overview
    /api/1/pet/{petId}/scratch/day-overview

Once (baseline / range, not per day):
    /api/1/pet/{petId}/health/overview                       (no params -- what the binding already polls)
    /api/1/pet/{petId}/activity/overview?dateFrom=&dateTo=    (now used by the binding's getHealthHistory() action)
    /api/1/pet/{petId}/sleep/overview?dateFrom=&dateTo=       (now used by the binding's getHealthHistory() action)
    /api/1/pet/{petId}/activity/streak-overview

With --legacy, also tries the older graph.tractive.com equivalents keyed by
local_day / local_month / local_year ints (still present in the app, likely legacy):
    graph/4/pet/{petId}/activity/day_overview?local_day=&local_month=&local_year=
    graph/4/pet/{petId}/sleep/day_overview?local_day=&local_month=&local_year=

Obfuscation map used to read the decompiled Retrofit interfaces (jadx 1.5.6):
    @h18 = @GET   @r4e = @Path   @qmf = @Query
Base URL for the /api/N/ paths is the remote-config "activity" host
(tractivegps.host_config.activity) = https://aps-api.tractive.com ; the leading-slash
/api/2/ paths override the retrofit base's own /api/1/.

Auth + header pattern is identical to the other scripts in this directory and to the binding itself.
Credentials + pet ID come from TRACTIVE_EMAIL / TRACTIVE_PASSWORD / TRACTIVE_PET_ID if
set, else interactive prompts (hidden password, never lands in shell history). Every
response (status + full body) is appended to health_history_probe_output.jsonl and a
compact line is printed to stdout.

This is a plain sequence of GETs, one every --delay seconds (default 1.0), well under
any measured aps-api rate limit (see doc/rate-limit-probes/README.md -- that host only
tripped on genuine concurrency, never on sequential spacing). A 429 triggers one 6 s
backoff + retry, then the call is recorded as failed and the script moves on.

Usage:
    python health_history_probe.py                 # last 7 days
    python health_history_probe.py --days 14
    python health_history_probe.py --days 7 --legacy
"""

import argparse
import getpass
import json
import os
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta

CLIENT_ID = "625e533dc3c3b41c28a669f0"
GRAPH_BASE_URL = "https://graph.tractive.com/4/"
APS_HOST = "https://aps-api.tractive.com"
OUTPUT_FILE = "health_history_probe_output.jsonl"


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
    if len(preview) > 160:
        preview = preview[:160] + "..."
    log(f"{str(status):>6}  {label:<46}  {preview}")


def call(out, label, url, user_id, token, delay):
    status, body = get(url, user_id, token)
    if status == 429:
        log("  429 -> backing off 6 s, retrying once")
        time.sleep(6)
        status, body = get(url, user_id, token)
    record(out, label, url, status, body)
    time.sleep(delay)


def main():
    ap = argparse.ArgumentParser(
        description="Probe Tractive per-day / historical health endpoints (see module docstring)."
    )
    ap.add_argument("--days", type=int, default=7,
                    help="number of days back from today, inclusive (default 7)")
    ap.add_argument("--delay", type=float, default=1.0,
                    help="seconds between calls (default 1.0)")
    ap.add_argument("--legacy", action="store_true",
                    help="also probe the older graph.tractive.com local_day/month/year endpoints")
    args = ap.parse_args()

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password: ")
    pet_id = os.environ.get("TRACTIVE_PET_ID") or input("Pet ID (trackedPetId, 24-char hex): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    today = date.today()
    days = [today - timedelta(days=n) for n in range(args.days)]
    date_from = days[-1].isoformat()
    date_to = today.isoformat()

    aps_day_endpoints = [
        ("activity/day-overview",
         f"{APS_HOST}/api/2/pet/{pet_id}/activity/day-overview"),
        ("sleep/day-overview",
         f"{APS_HOST}/api/2/pet/{pet_id}/sleep/day-overview"),
        ("bark/day-overview",
         f"{APS_HOST}/api/1/pet/{pet_id}/bark/day-overview"),
        ("resting-heart-rate/day-overview",
         f"{APS_HOST}/api/1/pet/{pet_id}/resting-heart-rate/day-overview"),
        ("resting-respiratory-rate/day-overview",
         f"{APS_HOST}/api/1/pet/{pet_id}/resting-respiratory-rate/day-overview"),
        ("scratch/day-overview",
         f"{APS_HOST}/api/1/pet/{pet_id}/scratch/day-overview"),
    ]

    with open(OUTPUT_FILE, "a", encoding="utf-8") as out:
        record(out, "=== run start ===", "", "-", json.dumps({
            "days": args.days, "date_from": date_from, "date_to": date_to, "legacy": args.legacy,
        }))

        # Baseline / range -- once, not per day.
        call(out, "health/overview",
             f"{APS_HOST}/api/1/pet/{pet_id}/health/overview", user_id, token, args.delay)
        call(out, "activity/overview (range)",
             f"{APS_HOST}/api/1/pet/{pet_id}/activity/overview?dateFrom={date_from}&dateTo={date_to}",
             user_id, token, args.delay)
        call(out, "sleep/overview (range)",
             f"{APS_HOST}/api/1/pet/{pet_id}/sleep/overview?dateFrom={date_from}&dateTo={date_to}",
             user_id, token, args.delay)
        call(out, "activity/streak-overview",
             f"{APS_HOST}/api/1/pet/{pet_id}/activity/streak-overview", user_id, token, args.delay)

        # Per day, newest first.
        for d in days:
            ds = d.isoformat()
            for label, base in aps_day_endpoints:
                call(out, f"{label}?date={ds}", f"{base}?date={ds}", user_id, token, args.delay)
            if args.legacy:
                q = f"local_day={d.day}&local_month={d.month}&local_year={d.year}"
                call(out, f"graph activity/day_overview {ds}",
                     f"{GRAPH_BASE_URL}pet/{pet_id}/activity/day_overview?{q}", user_id, token, args.delay)
                call(out, f"graph sleep/day_overview {ds}",
                     f"{GRAPH_BASE_URL}pet/{pet_id}/sleep/day_overview?{q}", user_id, token, args.delay)

    log(f"Done. Raw responses appended to {OUTPUT_FILE}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
