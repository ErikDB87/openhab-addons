#!/usr/bin/env python3
"""
Follow-up to rate_limit_burst_probe.py. That script found that a burst of 14 concurrent
calls to GET tracker/{trackerId} gets a mixed 429/200 result (10/14 succeeded in the one
run so far). This script answers three related but distinct follow-up questions:

  --mode recovery  How long after being rate-limited does the account return to full
                    capacity? Deliberately triggers a 429 (reusing the already-known
                    14-concurrent trigger), then checks at increasing delays afterward
                    with a SINGLE lightweight call (not another burst, to avoid a failed
                    check itself consuming further budget or extending any block) whether
                    the account has recovered.

  --mode spacing    How far apart do 14 calls need to be to avoid triggering 429 at all?
                    Fires 14 calls sequentially, spaced by a candidate interval, and looks
                    for 429s. This characterizes the safe steady rate, as opposed to the
                    concurrent-burst ceiling already measured. By default stops at the
                    first spacing with no 429 (a fast answer to "is there a safe point
                    here"); pass --continue-after-success for a slower, methodologically
                    clean sweep across the whole --spacings list, waiting the full
                    --recovery-pause-seconds between every round (not just failed ones) so
                    each round starts equally rested and results are comparable across the
                    range -- important because a too-short pause between back-to-back
                    rounds can let leftover rate-limit pressure from one round contaminate
                    the next, producing a non-monotonic, confounded result (this happened
                    with a 20s pause in early testing; 600s did not have this problem).

  --mode cross-endpoint  Does the token bucket found on GET tracker/{trackerId} apply
                    per-endpoint, or is it shared across graph.tractive.com's REST
                    endpoints (and possibly aps-api.tractive.com too)? For each other
                    endpoint: (1) fires a 14-concurrent burst against it alone, from a fully
                    rested state, as its own baseline; (2) after resting again, deliberately
                    depletes tracker/{trackerId} via the known 14-concurrent trigger; (3)
                    IMMEDIATELY (no rest) fires the same 14-concurrent burst against the
                    other endpoint again. If depleting tracker/{trackerId} makes the other
                    endpoint noticeably worse than its own baseline, that's evidence of a
                    shared bucket; if it looks the same either way, that's evidence each
                    endpoint has independent capacity. Tests device_hw_report and
                    device_pos_report (same host as tracker/{trackerId}) always; also tests
                    the aps-api.tractive.com health/overview endpoint (different host,
                    suspected separate since it has a different backend fingerprint) if a
                    pet ID is provided.

--mode recovery and --mode spacing both also accept --endpoint (default "tracker", same
choices as rate_limit_burst_probe.py: "tracker", "device_hw_report", "device_pos_report",
"health_overview") to run the same recovery-time / safe-spacing methodology against a
different REST call instead -- e.g. --mode spacing --endpoint health_overview characterizes
aps-api.tractive.com's own limit directly instead of assuming it matches
graph.tractive.com's. "health_overview" requires a pet ID for these two modes (prompted
for the same way as --mode cross-endpoint's existing, separate pet-ID prompt, except
required rather than optional, since here it's the very endpoint being tested rather than
a bonus one). Not passing --endpoint at all reproduces the exact original
tracker/{trackerId} recovery/spacing probes, unchanged; --mode cross-endpoint ignores
--endpoint entirely, since it always tests all endpoints together by design.

These are NOT the same question -- if Tractive's limiter is a simple fixed/sliding
window, the two answers might coincide; if it's a token-bucket or has escalating
penalties for repeat offenders, they could differ substantially. Worth measuring both
rather than assuming.

Safety notes (same caveats as rate_limit_burst_probe.py: rate limiting is confirmed to
exist, its exact behavior is not documented by Tractive, and it's possible, but not
verified, that being too aggressive could lead to a longer-term account block):
  - --mode recovery's repeated checks use a single call each, not a burst, specifically
    to minimize any risk of a check itself compounding the situation.
  - --mode spacing backs off with a long, conservative recovery pause (default 10 min,
    reusable from --mode recovery's own finding once you have it) between attempts that
    still show a 429, before trying a wider (safer) spacing.
  - Both stop early on any unexpected non-{200,429} status, same as the burst probe.

Usage:
    python rate_limit_window_probe.py --mode recovery
    python rate_limit_window_probe.py --mode spacing
    python rate_limit_window_probe.py --mode cross-endpoint
    python rate_limit_window_probe.py --mode recovery --endpoint health_overview

You'll be prompted for email, password, and tracker ID at runtime (hidden password
prompt, never lands in shell history). TRACTIVE_EMAIL/TRACTIVE_PASSWORD/
TRACTIVE_TRACKER_ID environment variables are used instead if already set (e.g. by a
secret manager) -- don't set them by typing the literal value into an interactive shell.
--mode cross-endpoint always prompts for an optional pet ID (needed only to test the
aps-api.tractive.com endpoint, Enter to skip it); --mode recovery/spacing prompt for a pet
ID only when --endpoint health_overview is chosen, and require it in that case (no
skipping -- that's the endpoint under test). TRACTIVE_PET_ID works the same way in both
cases.

Recommended order: run --mode recovery first, note the recovery time it finds, then pass
it to --mode spacing via --recovery-pause-seconds so that experiment's own backoffs are
grounded in a measured value instead of a guess. Repeat per --endpoint if characterizing
more than one REST call -- a recovery time measured against "tracker" is not assumed to
apply to "health_overview" (different host, never jointly measured).

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

# Used by --mode recovery/spacing's --endpoint option. Each entry maps a name to
# (requires_pet_id, url-building function). --mode cross-endpoint has its own separate
# endpoint list (other_endpoints(), below) since its job -- comparing several endpoints
# against a tracker/{trackerId} depletion -- is a different shape of experiment and was
# never meant to be driven by a single --endpoint choice.
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


def tracker_url(tracker_id):
    return f"{API_BASE_URL}tracker/{tracker_id}"


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


def concurrent_burst(url, user_id, access_token, burst_size):
    with ThreadPoolExecutor(max_workers=burst_size) as pool:
        futures = [pool.submit(single_call, url, user_id, access_token) for _ in range(burst_size)]
        return [f.result() for f in as_completed(futures)]


def check_unexpected(counts):
    unexpected = {code: n for code, n in counts.items() if code not in (200, 429)}
    if unexpected:
        log(f"UNEXPECTED status code(s) {unexpected} -- not a 429, so this isn't the rate limit. "
            "Likely a setup problem (bad credentials, or a case-mismatched tracker ID -- IDs are "
            "case-sensitive) rather than something worth continuing past. Stopping.")
        return True
    return False


def mode_recovery(url, endpoint_name, user_id, access_token, delays, round_size):
    log(f"Triggering a rate limit against {endpoint_name} via a {round_size}-concurrent burst "
        f"(pass --round-size to match whatever burst size rate_limit_burst_probe.py found for this "
        f"endpoint -- 14 only reliably triggers a 429 on \"tracker\")...")
    triggered = False
    for attempt in range(1, 4):
        statuses = concurrent_burst(url, user_id, access_token, round_size)
        counts = {}
        for s in statuses:
            counts[s] = counts.get(s, 0) + 1
        log(f"  trigger attempt {attempt}: {counts}")
        if check_unexpected(counts):
            return
        if 429 in counts:
            triggered = True
            trigger_time = time.monotonic()
            break
        log(f"  no 429 this attempt -- burst size {round_size} doesn't reliably trigger every time. "
            "Waiting 60s before retrying...")
        time.sleep(60)

    if not triggered:
        log("Could not trigger a 429 after 3 attempts. Can't measure recovery from a state we "
            "never reached -- try again later, or raise the trigger burst size.")
        return

    log(f"Triggered. Checking recovery with single lightweight calls at increasing delays: {delays}")
    for delay in delays:
        elapsed = time.monotonic() - trigger_time
        remaining = delay - elapsed
        if remaining > 0:
            log(f"  waiting {remaining:.0f}s more (target: {delay}s since trigger)...")
            time.sleep(remaining)
        status = single_call(url, user_id, access_token)
        actual_elapsed = time.monotonic() - trigger_time
        if status == 200:
            log(f"  at {actual_elapsed:.0f}s: HTTP 200 -- recovered.")
            log(f"\nRESULT ({endpoint_name}): recovered somewhere at or before "
                f"{actual_elapsed:.0f}s after the triggering burst (exact recovery point is "
                "between this delay and the previous, still-limited one, if any).")
            return
        elif status == 429:
            log(f"  at {actual_elapsed:.0f}s: still HTTP 429.")
        else:
            log(f"  at {actual_elapsed:.0f}s: UNEXPECTED status {status}. Stopping.")
            return

    log(f"\nRESULT ({endpoint_name}): still HTTP 429 after {delays[-1]}s (the longest delay "
        "tried). Recovery takes longer than that -- widen --delays and try again.")


def mode_spacing(url, endpoint_name, user_id, access_token, spacings, recovery_pause_seconds, round_size,
                  stop_on_success=True):
    results = []
    for round_num, spacing in enumerate(spacings, start=1):
        log(f"Round {round_num}/{len(spacings)}: trying {round_size} calls against {endpoint_name} spaced "
            f"{spacing}s apart (total ~{spacing * (round_size - 1):.1f}s)...")
        counts = {}
        for call_num in range(round_size):
            status = single_call(url, user_id, access_token)
            counts[status] = counts.get(status, 0) + 1
            if call_num < round_size - 1:
                time.sleep(spacing)
        log(f"  results: {counts}")
        if check_unexpected(counts):
            return

        clean = 429 not in counts
        results.append((spacing, clean, counts))
        log(f"  {'no 429' if clean else 'hit 429'} at {spacing}s spacing.")

        if clean and stop_on_success:
            log(f"\nRESULT ({endpoint_name}): no 429 at {spacing}s spacing (~{spacing * 13:.1f}s "
                "to send all 14) -- stopping here. Pass --continue-after-success to keep testing "
                "wider spacings anyway, for a full picture instead of just the first safe point.")
            return

        if round_num < len(spacings):
            log(f"  waiting {recovery_pause_seconds}s before the next spacing, so every round "
                "starts from an equally rested state (not just failed rounds) -- this is what makes "
                "a --continue-after-success run comparable across all spacings tried.")
            time.sleep(recovery_pause_seconds)

    log(f"\nRESULT ({endpoint_name}): reached the end of --spacings. Full picture, spacing -> clean?:")
    for spacing, clean, counts in results:
        log(f"  {spacing}s: {'clean' if clean else 'hit 429'} -- {counts}")


def other_endpoints(tracker_id, pet_id):
    endpoints = [
        ("device_hw_report", f"{API_BASE_URL}device_hw_report/{tracker_id}/"),
        ("device_pos_report", f"{API_BASE_URL}device_pos_report/{tracker_id}"),
    ]
    if pet_id:
        endpoints.append(("health/overview (aps-api host)", f"{APS_BASE_URL}pet/{pet_id}/health/overview"))
    return endpoints


def mode_cross_endpoint(tracker_id, pet_id, user_id, access_token, recovery_pause_seconds):
    deplete_url = tracker_url(tracker_id)
    endpoints = other_endpoints(tracker_id, pet_id)
    if not pet_id:
        log("No pet ID given -- skipping the aps-api.tractive.com health/overview endpoint (different "
            "host, suspected separate; only testing the two graph.tractive.com endpoints).")
    summary = []

    for name, url in endpoints:
        log(f"=== Testing {name} ===")

        log(f"  Baseline: 14-concurrent burst against {name} alone (fully rested)...")
        baseline_statuses = concurrent_burst(url, user_id, access_token, 14)
        baseline_counts = {}
        for s in baseline_statuses:
            baseline_counts[s] = baseline_counts.get(s, 0) + 1
        log(f"    baseline results: {baseline_counts}")
        if check_unexpected(baseline_counts):
            return
        baseline_failures = baseline_counts.get(429, 0)

        log(f"  Resting {recovery_pause_seconds}s before the cross-contamination check...")
        time.sleep(recovery_pause_seconds)

        log(f"  Depleting tracker/{{trackerId}} via the known 14-concurrent trigger...")
        deplete_statuses = concurrent_burst(deplete_url, user_id, access_token, 14)
        deplete_counts = {}
        for s in deplete_statuses:
            deplete_counts[s] = deplete_counts.get(s, 0) + 1
        log(f"    depletion results: {deplete_counts}")
        if check_unexpected(deplete_counts):
            return
        if deplete_counts.get(429, 0) == 0:
            log("    WARNING: depleting tracker/{trackerId} produced zero 429s this time (some "
                "run-to-run variance is expected) -- this round's cross-contamination check below "
                "is unreliable, since we can't be sure depletion actually happened. Treat with "
                "caution.")

        log(f"  IMMEDIATELY firing the same 14-concurrent burst against {name}...")
        cross_statuses = concurrent_burst(url, user_id, access_token, 14)
        cross_counts = {}
        for s in cross_statuses:
            cross_counts[s] = cross_counts.get(s, 0) + 1
        log(f"    cross-contamination results: {cross_counts}")
        if check_unexpected(cross_counts):
            return
        cross_failures = cross_counts.get(429, 0)

        verdict = ("SHARED (worse after depleting tracker/{trackerId})" if cross_failures > baseline_failures
                   else "INDEPENDENT (no worse than its own baseline)")
        log(f"  {name}: baseline failures={baseline_failures}, after-depletion failures={cross_failures} "
            f"-> {verdict}")
        summary.append((name, baseline_failures, cross_failures, verdict))

        if (name, url) != endpoints[-1]:
            log(f"  Resting {recovery_pause_seconds}s before the next endpoint...")
            time.sleep(recovery_pause_seconds)

    log("\nRESULT: cross-endpoint bucket-sharing summary:")
    for name, baseline_failures, cross_failures, verdict in summary:
        log(f"  {name}: baseline={baseline_failures}/14 failed, after depleting tracker/"
            f"{{trackerId}}={cross_failures}/14 failed -> {verdict}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["recovery", "spacing", "cross-endpoint"], required=True)
    parser.add_argument("--endpoint", choices=sorted(ENDPOINTS), default="tracker",
                         help="[recovery/spacing modes only] REST call to target (default: "
                              "tracker, i.e. the original GET tracker/{trackerId} probes, "
                              "unchanged). \"health_overview\" is on a different host "
                              "(aps-api.tractive.com) and needs a pet ID. Ignored by "
                              "--mode cross-endpoint, which always tests all endpoints together.")
    parser.add_argument("--round-size", type=int, default=14,
                         help="[recovery/spacing modes] Concurrent burst size (recovery) or calls per round "
                              "(spacing) (default: 14, unchanged from before this flag existed -- matches "
                              "\"tracker\"'s known trigger size). Raise this for endpoints with a higher "
                              "ceiling -- e.g. health_overview needed 28 to trigger a 429 at all.")
    parser.add_argument("--delays", type=str, default="5,10,20,30,60,120,240",
                         help="[recovery mode] Comma-separated, ascending seconds-after-trigger "
                              "to check (default: 5,10,20,30,60,120,240)")
    parser.add_argument("--spacings", type=str, default="0.1,0.25,0.5,1,2,5",
                         help="[spacing mode] Comma-separated, ascending seconds between calls "
                              "to try (default: 0.1,0.25,0.5,1,2,5)")
    parser.add_argument("--recovery-pause-seconds", type=float, default=600.0,
                         help="[spacing mode] Seconds to wait between every round (default: 600). "
                              "Without --continue-after-success, only used between failed rounds.")
    parser.add_argument("--continue-after-success", action="store_true",
                         help="[spacing mode] Keep testing every spacing in --spacings, even after "
                              "one comes back clean, instead of stopping at the first safe point. "
                              "Also waits --recovery-pause-seconds between EVERY round (not just "
                              "failed ones), so all rounds start from a comparably rested state -- "
                              "use this for a slow, methodologically clean sweep across a spacing "
                              "range, as opposed to a quick answer to 'is there a safe point here.'")
    parser.add_argument("--cross-endpoint-pause-seconds", type=float, default=600.0,
                         help="[cross-endpoint mode] Seconds to rest between every step (baseline, "
                              "depletion, cross-check, and between endpoints) (default: 600)")
    args = parser.parse_args()

    requires_pet_id, build_url = ENDPOINTS[args.endpoint]

    email = os.environ.get("TRACTIVE_EMAIL") or input("Tractive email: ").strip()
    password = os.environ.get("TRACTIVE_PASSWORD") or getpass.getpass("Tractive password (hidden): ")
    tracker_id = os.environ.get("TRACTIVE_TRACKER_ID") or input("Tracker ID (e.g. FCTPOEBK): ").strip()
    pet_id = ""
    if args.mode == "cross-endpoint":
        pet_id = (os.environ.get("TRACTIVE_PET_ID")
                  or input("Pet ID, optional (e.g. 661004cf4076103914d00820) -- Enter to skip the "
                           "aps-api health endpoint: ").strip())
    elif requires_pet_id:
        pet_id = (os.environ.get("TRACTIVE_PET_ID")
                  or input(f"Pet ID (e.g. 661004cf4076103914d00820), required for --endpoint "
                           f"{args.endpoint}: ").strip())
    pet_id_missing = requires_pet_id and args.mode != "cross-endpoint" and not pet_id
    if not email or not password or not tracker_id or pet_id_missing:
        print("Email, password, and tracker ID are all required"
              + (", and pet ID is required for this --endpoint." if pet_id_missing else "."))
        sys.exit(1)

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

    if args.mode == "recovery":
        url = build_url(tracker_id, pet_id)
        log(f"Target endpoint: {args.endpoint} ({url})")
        delays = [int(x) for x in args.delays.split(",")]
        mode_recovery(url, args.endpoint, user_id, access_token, delays, args.round_size)
    elif args.mode == "spacing":
        url = build_url(tracker_id, pet_id)
        log(f"Target endpoint: {args.endpoint} ({url})")
        spacings = [float(x) for x in args.spacings.split(",")]
        mode_spacing(url, args.endpoint, user_id, access_token, spacings, args.recovery_pause_seconds,
                     args.round_size,
                     stop_on_success=not args.continue_after_success)
    else:
        mode_cross_endpoint(tracker_id, pet_id, user_id, access_token, args.cross_endpoint_pause_seconds)


if __name__ == "__main__":
    main()
