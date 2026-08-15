#!/usr/bin/env python3
"""
Careful, escalating burst probe against a Tractive REST endpoint -- tries to find the
smallest concurrent-burst size that trips Tractive's real HTTP 429 rate limit. Tractive's
API has no public documentation of its rate-limit behavior, so this and its companion
script (rate_limit_window_probe.py) exist to characterize it empirically against a real
account instead of guessing.

Targets GET tracker/{trackerId} by default -- this reproduces the shape of a real
production incident that once happened to this binding: bulk-linking Items to a Thing's
channels made openHAB send a REFRESH command to each newly-linked channel at once, and
the binding's handler (at the time) scheduled a fresh, undebounced poll per command --
producing roughly 9-14 concurrent GET tracker/{id} calls and tripping real HTTP 429s in
production. That incident motivated adding request debouncing (see PollGuard.java in
internal/util), and, later, this whole rate-limit characterization effort to replace
guesswork with real measurements.

Pass --endpoint to target a different REST call instead of the default "tracker":
"device_hw_report" or "device_pos_report" (both graph.tractive.com, same host as
"tracker"), or "health_overview" (pet/{petId}/health/overview on aps-api.tractive.com -- a
different host, requires a pet ID: --pet-id isn't a flag here, it's the same
prompt/TRACTIVE_PET_ID pattern used for email/password/tracker ID below). Running with no
--endpoint flag at all reproduces the exact original tracker/{trackerId} probe, unchanged.

Safety design (rate limiting is confirmed to exist; its exact threshold/window is not
documented by Tractive; it's possible, but not verified, that being too aggressive could
lead to a longer-term account block):
  - Escalates gradually (burst sizes 2, 4, 8, 14 by default -- 14 matches the incident
    described above) rather than jumping straight to a large burst.
  - Stops immediately at the first HTTP 429 seen -- does not proceed to a larger burst.
  - Waits a long, conservative recovery interval between rounds (10 minutes by default)
    so an earlier round's rate-limit window has every chance to fully reset before the
    next, larger round.
  - Only ever GETs (read-only, no risk to tracker/account state).

Strongly recommended: pause the live binding's own polling (e.g. stop the openHAB
instance, or set the Thing offline) while this runs, so its concurrent traffic doesn't
share this account's rate-limit bucket and confound the result -- or worse, combine with
this probe to trip a block that neither alone would have caused. The script pauses once
at startup to remind you.

Usage:
    python rate_limit_burst_probe.py
    python rate_limit_burst_probe.py --endpoint health_overview

You'll be prompted for email, password, and tracker ID at runtime -- the password prompt
is hidden and, because it's read from stdin in response to a prompt rather than typed as
part of a command, it never lands in shell history (unlike e.g. `$env:TRACTIVE_PASSWORD =
"..."` in an interactive PowerShell session, which PSReadLine records in full, secret
included -- that's not actually safer than a CLI argument for this specific risk). If
TRACTIVE_EMAIL/TRACTIVE_PASSWORD/TRACTIVE_TRACKER_ID are already set as environment
variables (e.g. injected by a CI system or secret manager, not typed interactively), those
are used instead and no prompt appears -- useful for unattended runs, but don't set them by
typing the literal value into an interactive shell. --endpoint health_overview also
requires a pet ID, prompted for the same way (TRACTIVE_PET_ID env var, or an interactive
prompt) -- unlike tracker ID, it's only asked for when the chosen endpoint needs it.

Optional flags: --endpoint (default "tracker"), --burst-sizes (default "2,4,8,14"),
--recovery-minutes (default 10).

Do not hardcode credentials into this file, and do not pass them as command-line arguments.

See README.md in this directory for what running this actually found.
"""

import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

CLIENT_ID = "625e533dc3c3b41c28a669f0"
API_BASE_URL = "https://graph.tractive.com/4/"
APS_BASE_URL = "https://aps-api.tractive.com/api/1/"

# Each entry maps an --endpoint name to (requires_pet_id, url-building function).
# "tracker" is the default and reproduces the script's original, only target.
ENDPOINTS = {
    "tracker": (False, lambda tracker_id, pet_id: f"{API_BASE_URL}tracker/{tracker_id}"),
    "device_hw_report": (False, lambda tracker_id, pet_id: f"{API_BASE_URL}device_hw_report/{tracker_id}/"),
    "device_pos_report": (False, lambda tracker_id, pet_id: f"{API_BASE_URL}device_pos_report/{tracker_id}"),
    "health_overview": (True, lambda tracker_id, pet_id: f"{APS_BASE_URL}pet/{pet_id}/health/overview"),
}


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def authenticate(email, password):
    url = API_BASE_URL + "auth/token"
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


def single_call(url, user_id, access_token):
    req = urllib.request.Request(url, method="GET", headers={
        "x-tractive-client": CLIENT_ID,
        "x-tractive-user": user_id,
        "authorization": f"Bearer {access_token}",
        "content-type": "application/json;charset=UTF-8",
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def run_burst(url, user_id, access_token, burst_size):
    statuses = []
    with ThreadPoolExecutor(max_workers=burst_size) as pool:
        futures = [pool.submit(single_call, url, user_id, access_token) for _ in range(burst_size)]
        for f in as_completed(futures):
            statuses.append(f.result())
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--endpoint", choices=sorted(ENDPOINTS), default="tracker",
                         help="REST call to target (default: tracker, i.e. the original "
                              "GET tracker/{trackerId} probe, unchanged). \"health_overview\" is "
                              "on a different host (aps-api.tractive.com) and needs a pet ID.")
    parser.add_argument("--burst-sizes", type=str, default="2,4,8,14",
                         help="Comma-separated, ascending burst sizes to try (default: 2,4,8,14)")
    parser.add_argument("--recovery-minutes", type=float, default=10.0,
                         help="Minutes to wait between rounds (default: 10)")
    args = parser.parse_args()
    burst_sizes = [int(x) for x in args.burst_sizes.split(",")]
    if burst_sizes != sorted(burst_sizes):
        print("--burst-sizes must be ascending (each round should be a bigger step than the last).")
        sys.exit(1)

    requires_pet_id, build_url = ENDPOINTS[args.endpoint]

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password (hidden): ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (e.g. FCTPOEBK): ").strip()
    pet_id = ""
    if requires_pet_id:
        pet_id = (os.environ.get("TRACTIVE_PET_ID")
                  or input(f"Pet ID (e.g. 661004cf4076103914d00820), required for --endpoint "
                           f"{args.endpoint}: ").strip())
    if not email or not password or not tracker_id or (requires_pet_id and not pet_id):
        print("Email, password, and tracker ID are all required"
              + (", and pet ID is required for this --endpoint." if requires_pet_id else "."))
        sys.exit(1)

    url = build_url(tracker_id, pet_id)

    print("Reminder: pause the live binding's own polling before continuing (stop the openHAB")
    print("instance, or take the Thing offline), so its traffic doesn't share this account's")
    print("rate-limit bucket during the test.")
    input("Press Enter once you've done that (or to proceed anyway)...")

    log("Authenticating...")
    try:
        user_id, access_token = authenticate(email, password)
    except urllib.error.HTTPError as e:
        log(f"Auth failed: HTTP {e.code} {e.reason}")
        print(e.read().decode("utf-8", errors="replace"))
        sys.exit(1)
    log(f"Authenticated as user_id={user_id}")
    log(f"Target endpoint: {args.endpoint} ({url})")

    for round_num, burst_size in enumerate(burst_sizes, start=1):
        log(f"Round {round_num}/{len(burst_sizes)}: firing {burst_size} concurrent calls...")
        statuses = run_burst(url, user_id, access_token, burst_size)
        counts = {}
        for s in statuses:
            counts[s] = counts.get(s, 0) + 1
        log(f"  results: {counts}")

        unexpected = {code: n for code, n in counts.items() if code not in (200, 429)}
        if unexpected:
            log(f"UNEXPECTED status code(s) {unexpected} -- not a 429, so this isn't the rate limit. "
                "Likely a setup problem (wrong email/password/tracker ID/pet ID -- tracker IDs are "
                "case-sensitive, e.g. 'FCTPOEBK' not 'fctpoebk') rather than something worth waiting "
                "out. Stopping instead of burning a 10-minute cooldown on a broken run.")
            return

        if 429 in counts:
            log(f"RESULT: hit HTTP 429 at burst size {burst_size} ({counts[429]}/{burst_size} calls "
                f"limited) against {args.endpoint}. Stopping here -- a safe concurrent-call ceiling "
                f"is somewhere between {burst_sizes[round_num - 2] if round_num > 1 else 0} and "
                f"{burst_size}.")
            return

        if round_num < len(burst_sizes):
            log(f"no 429 at burst size {burst_size}. Waiting {args.recovery_minutes} minutes "
                "before the next, larger round...")
            time.sleep(args.recovery_minutes * 60)

    log(f"RESULT: no HTTP 429 seen against {args.endpoint} at any burst size up to "
        f"{burst_sizes[-1]} (the largest tried). That doesn't rule out a lower limit over a "
        "longer sustained period -- this only characterizes short concurrent bursts, which is "
        "the specific failure mode request debouncing (see PollGuard.java) protects against.")


if __name__ == "__main__":
    main()
