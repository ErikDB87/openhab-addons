#!/usr/bin/env python3
"""
Probe the separation-anxiety "phase" start/stop write endpoints found in the Android app teardown
(apk-teardown/, Retrofit interface obfuscated/jzg.java):

    @cxd("pet/{petId}/separation/phase/start")  Object c(@r4e("petId") String petId, ...);   # PUT, empty body
    @cxd("pet/{petId}/separation/phase/stop")   Object a(@r4e("petId") String petId, ...);   # PUT, empty body
    @h18("pet/{petId}/separation/phases?states=ONGOING")  Object b(...);                      # GET -> List<{startedAt}>

The confirmed-state signal for a phase is `content.separationPhaseStatus.isPhaseOngoing` /
`.phaseStartedAt` inside the `pet/{petId}/health/overview` response (and the matching real-time
`health_overview` push). This binding currently exposes those two as read-only channels; the plan is
to promote `health#separation-phase-ongoing` to a writable Switch backed by these PUT calls.

Open questions this probe answers:
  1. WHICH HOST. The paths are clear but `jzg`'s Retrofit base URL is buried in obfuscated Hilt
     wiring. Strong guess: graph.tractive.com/4/ (every other pet/.../tracker/... write lives there).
     Fallback candidate: aps-api.tractive.com/api/1/ (where health/overview itself lives). The probe
     resolves this by first trying GET .../separation/phases on each host (a harmless read) and only
     PUTting against the host that answered.
  2. CLIENT GATE. Does the binding's own x-tractive-client (625e...) work, or is it gated to the
     official app the way tracker/{id}/request_status_update turned out to be (403 / code 8006)?
  3. READ-ONLY GATE. Run under a shared/invited account (read_only: true). The app already let that
     account start a phase, so this should NOT 403 -- confirming it via the direct API closes the loop.
  4. LATENCY. How long after the PUT does isPhaseOngoing flip in health/overview.

RESULTS: graph.tractive.com/4/ was NOT the right host -- aps-api.tractive.com/api/1/ is (question 1);
the binding's own client works, ungated, for both an owner and a shared/read_only account (questions 2
and 3). Now shipped as the writable `health#separation-phase-ongoing` Switch channel.

Method -- always ends with the phase STOPPED, so nothing is left changed
----------------------------------------------------------------------
  0. --start-delay (default 0): sleep first if given. A separation phase does NOT need the dog out of
     its Power Saving Zone (unlike the battery_save_mode probe), so 0 is the sensible default. Auth
     and the baseline read happen BEFORE any sleep.
  1. Resolve the host: GET {host}pet/{petId}/separation/phases?states=ONGOING on each candidate; the
     first HTTP 200 wins. If none answers 200, fall back to trying both hosts for the PUT, warning.
  2. Baseline: GET pet/{petId}/health/overview (always aps-api). Record isPhaseOngoing (= B) and
     phaseStartedAt. ABORT if B is already true -- do not disturb a real ongoing phase.
  3. START: PUT {host}pet/{petId}/separation/phase/start (empty body). Retry ONLY on HTTP 429.
     Then poll health/overview (+ the phases GET) every --interval s for --observe-seconds, logging
     when isPhaseOngoing flips true and phaseStartedAt appears.
  4. STOP: PUT {host}pet/{petId}/separation/phase/stop (empty body), same retry + observe window.
  5. Final GET health/overview; confirm isPhaseOngoing is false again. Print a verdict.

Safety
------
Any path that leaves a phase started but not confirmed-stopped (non-2xx stop, crash, Ctrl+C, or the
watch seeing isPhaseOngoing true) triggers a best-effort emergency stop in a finally block, then
prints an explicit manual-fix curl if that also fails.

Credentials + pet ID: TRACTIVE_EMAIL / TRACTIVE_PASSWORD / TRACTIVE_PET_ID env vars if set, else
interactive prompts (hidden password). The PET ID here is the trackable-object / pet ID
(24-char lowercase hex), NOT the 8-char tracker ID. Every request (status + full body) is appended
to separation_phase_probe_output.jsonl; a compact line goes to stdout.

Usage:
    python separation_phase_probe.py
    python separation_phase_probe.py --observe-seconds 600 --interval 20
    python separation_phase_probe.py --client-id 53e359a0f1f179e3ef000009   # retry as the official app
    python separation_phase_probe.py --host aps-api/1                        # force a host, skip resolution
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
CANDIDATE_HOSTS = [("graph/4", GRAPH_BASE), ("aps-api/1", APS_BASE)]
OUTPUT_FILE = "separation_phase_probe_output.jsonl"


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def authenticate(email, password):
    url = GRAPH_BASE + "auth/token"
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
    log(f"{str(status):>6}  {label:<38}  {preview}")


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


def health_overview_url(pet_id):
    return f"{APS_BASE}pet/{pet_id}/health/overview"


def phases_url(base, pet_id):
    return f"{base}pet/{pet_id}/separation/phases?states=ONGOING"


def phase_action_url(base, pet_id, action):
    return f"{base}pet/{pet_id}/separation/phase/{action}"


def separation_status(overview):
    """(isPhaseOngoing, phaseStartedAt) off a health/overview body, or (None, None)."""
    if not isinstance(overview, dict):
        return None, None
    sps = overview.get("separationPhaseStatus")
    if not isinstance(sps, dict):
        return None, None
    return sps.get("isPhaseOngoing"), sps.get("phaseStartedAt")


def resolve_host(out, pet_id, user_id, token, forced):
    """Return (label, base_url) for the host that answers the phases GET, or (None, None)."""
    if forced:
        for label, base in CANDIDATE_HOSTS:
            if label == forced:
                log(f"Host forced to {label} ({base}) -- skipping resolution")
                return label, base
        raise SystemExit(f"--host must be one of {[h[0] for h in CANDIDATE_HOSTS]}")
    for label, base in CANDIDATE_HOSTS:
        status, body = request("GET", phases_url(base, pet_id), user_id, token)
        record(out, f"resolve host: GET phases on {label}", phases_url(base, pet_id), status, body)
        time.sleep(1.0)
        if status == 200:
            log(f"Host resolved: {label} ({base}) answered the phases GET with HTTP 200")
            return label, base
    log("No candidate host answered the phases GET with 200 -- PUTs will try each host in turn")
    return None, None


def put_phase(out, pet_id, user_id, token, action, host, post_attempts, post_retry_interval):
    """PUT separation/phase/{action}. If host is None, try each candidate. Return (status, working_base)."""
    bases = [host[1]] if host else [b for _, b in CANDIDATE_HOSTS]
    labels = [host[0]] if host else [lbl for lbl, _ in CANDIDATE_HOSTS]
    for label, base in zip(labels, bases):
        url = phase_action_url(base, pet_id, action)
        status, body = None, ""
        for attempt in range(1, post_attempts + 1):
            status, body = request("PUT", url, user_id, token, data=b"")
            record(out, f"PUT separation/phase/{action} on {label} (attempt {attempt})", url, status, body)
            if status != 429:
                break
            if attempt < post_attempts:
                log(f"  429 -- waiting {post_retry_interval:g}s before attempt {attempt + 1}")
                time.sleep(post_retry_interval)
        if isinstance(status, int) and 200 <= status < 300:
            return status, base
        if host:  # a specific host was given/resolved and it refused -- don't shotgun the other
            return status, None
    return status, None


def observe(out, phase, pet_id, host_base, user_id, token, expected_ongoing, observe_seconds, interval):
    ho_url = health_overview_url(pet_id)
    ph_url = phases_url(host_base, pet_id) if host_base else None
    start = time.time()
    deadline = start + observe_seconds
    flipped_after = None
    started_at_seen = None
    phases_count_seen = None
    log(f"----- {phase}: observing {observe_seconds}s (expect isPhaseOngoing -> {expected_ongoing}) -----")
    while time.time() < deadline:
        time.sleep(interval)
        elapsed = round(time.time() - start)
        ho = get_json(out, f"{phase} watch health/overview (+{elapsed}s)", ho_url, user_id, token)
        if ph_url:
            ph = get_json(out, f"{phase} watch phases (+{elapsed}s)", ph_url, user_id, token)
            if isinstance(ph, list) and phases_count_seen != len(ph):
                phases_count_seen = len(ph)
                log(f"  * separation/phases now has {len(ph)} entr{'y' if len(ph) == 1 else 'ies'} at +{elapsed}s")
        ongoing, started_at = separation_status(ho)
        if started_at is not None and started_at_seen != started_at:
            started_at_seen = started_at
            log(f"  * phaseStartedAt = {started_at} at +{elapsed}s")
        if flipped_after is None and ongoing == expected_ongoing:
            flipped_after = elapsed
            log(f"  * isPhaseOngoing == {expected_ongoing} at +{elapsed}s")
    result = {
        "phase": phase,
        "expected_ongoing": expected_ongoing,
        "flipped_after_seconds": flipped_after,
        "phase_started_at_final": started_at_seen,
        "phases_count_final": phases_count_seen,
    }
    record(out, f"=== {phase} observe summary ===", "", "-", json.dumps(result))
    return result


def _verdict_line(status, working_base):
    if isinstance(status, int) and 200 <= status < 300:
        return f"VERDICT: start ACCEPTED for this client ID on {working_base}"
    if status == 403:
        return ("VERDICT: 403 -- likely a client-registry gate (like request_status_update's 8006). "
                "Re-run with --client-id 53e359a0f1f179e3ef000009 to tell that apart from an account deny.")
    if status == 404:
        return "VERDICT: 404 -- wrong host or path. Try --host aps-api/1 (or check the path)."
    if status == 401:
        return "VERDICT: 401 -- auth/token problem, not an endpoint verdict."
    return f"VERDICT: unexpected status {status} -- inspect the body in {OUTPUT_FILE}."


def _emergency_stop(out, pet_id, user_id, token, host_base):
    log("EMERGENCY STOP: forcing separation/phase/stop")
    bases = [host_base] if host_base else [b for _, b in CANDIDATE_HOSTS]
    for attempt in range(1, 6):
        for base in bases:
            status, body = request("PUT", phase_action_url(base, pet_id, "stop"), user_id, token, data=b"")
            record(out, f"EMERGENCY STOP PUT on {base} (attempt {attempt})", phase_action_url(base, pet_id, "stop"),
                   status, body)
            if isinstance(status, int) and 200 <= status < 300:
                log(f"  emergency stop accepted (HTTP {status}) on {base}")
                return
        time.sleep(15)
    log("  emergency stop did NOT succeed after 5 rounds -- see the manual curl in the summary")


def main():
    global CLIENT_ID
    ap = argparse.ArgumentParser(description="Probe PUT pet/{id}/separation/phase/{start,stop} (see module docstring).")
    ap.add_argument("--start-delay", type=int, default=0,
                    help="seconds to wait before the first write (default 0; a phase needs no walk)")
    ap.add_argument("--observe-seconds", type=int, default=420,
                    help="seconds to poll health/overview after each write (default 420, min 60)")
    ap.add_argument("--interval", type=float, default=20.0, help="seconds between watch polls (default 20, min 5)")
    ap.add_argument("--post-attempts", type=int, default=3,
                    help="max PUTs per write; retries ONLY on 429 (default 3, max 6)")
    ap.add_argument("--post-retry-interval", type=float, default=60.0,
                    help="seconds between 429-retry PUTs (default 60, min 30)")
    ap.add_argument("--client-id", default=CLIENT_ID,
                    help="x-tractive-client header (default: the binding's 625e...; pass the official app's "
                         "53e359a0f1f179e3ef000009 to tell a client-registry 403 apart from an account deny)")
    ap.add_argument("--host", default=None, choices=[h[0] for h in CANDIDATE_HOSTS],
                    help="force the host and skip auto-resolution (graph/4 or aps-api/1)")
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
    pet_id = os.environ.get("TRACTIVE_PET_ID") or input("Pet ID (24-char hex, NOT the tracker ID): ").strip()

    log("Authenticating...")
    user_id, token = authenticate(email, password)
    log(f"OK: user_id={user_id}")

    out = open(OUTPUT_FILE, "a", encoding="utf-8")
    record(out, "=== run start ===", "", "-", json.dumps({
        "user_id": user_id, "pet_id": pet_id, "client_id": CLIENT_ID, "forced_host": args.host,
        "start_delay": args.start_delay, "observe_seconds": observe_seconds, "interval": interval,
        "post_attempts": post_attempts, "post_retry_interval": post_retry_interval,
    }))

    host_label, host_base = resolve_host(out, pet_id, user_id, token, args.host)
    host = (host_label, host_base) if host_base else None

    log("Baseline health/overview read...")
    baseline = get_json(out, "preflight baseline health/overview", health_overview_url(pet_id), user_id, token)
    ongoing0, started0 = separation_status(baseline)
    if not isinstance(baseline, dict):
        record(out, "=== ABORT ===", "", "-", json.dumps({"reason": "health/overview not readable"}))
        log("ABORT: could not read health/overview -- nothing written.")
        out.close()
        return
    log(f"Baseline separationPhaseStatus: isPhaseOngoing={ongoing0}, phaseStartedAt={started0}")
    if ongoing0 is True:
        record(out, "=== ABORT ===", "", "-", json.dumps({
            "reason": "a separation phase is already ongoing at baseline", "phaseStartedAt": started0,
        }))
        log("ABORT: a phase is already ongoing. Stop it in the Tractive app first, then re-run.")
        out.close()
        return

    if args.start_delay > 0:
        log(f"Sleeping {args.start_delay}s before the first write. (Ctrl+C aborts cleanly; nothing changed yet.)")
        slept = 0
        while slept < args.start_delay:
            chunk = min(120, args.start_delay - slept)
            time.sleep(chunk)
            slept += chunk
            if slept < args.start_delay:
                log(f"  ...{args.start_delay - slept}s to go")

    start_status = None
    stop_status = None
    stop_confirmed = False
    working_base = host_base
    try:
        log("===== START: PUT separation/phase/start =====")
        start_status, working_base = put_phase(out, pet_id, user_id, token, "start", host, post_attempts,
                                               post_retry_interval)
        log(_verdict_line(start_status, working_base))
        if not (isinstance(start_status, int) and 200 <= start_status < 300):
            record(out, "=== START not accepted ===", "", "-", json.dumps({"status": start_status}))
            log("START not accepted. Watching health/overview 60 s to be sure nothing started anyway...")
            observe(out, "START-CHECK", pet_id, working_base or host_base, user_id, token, True, 60, interval)
            # if the check saw it go ongoing, fall through to the finally stop; otherwise nothing to undo
            recheck = get_json(out, "post-check health/overview", health_overview_url(pet_id), user_id, token)
            if separation_status(recheck)[0] is True:
                log("A phase IS ongoing despite the non-2xx start -- emergency stop will run.")
                start_status = 200  # force the finally-stop path
            else:
                return
        else:
            observe(out, "START", pet_id, working_base, user_id, token, True, observe_seconds, interval)

        log(f"===== STOP: PUT separation/phase/stop on {working_base} =====")
        stop_status, _ = put_phase(out, pet_id, user_id, token, "stop", (None, working_base) if working_base else None,
                                   post_attempts, post_retry_interval)
        stop_confirmed = isinstance(stop_status, int) and 200 <= stop_status < 300
        if stop_confirmed:
            observe(out, "STOP", pet_id, working_base, user_id, token, False, observe_seconds, interval)
        else:
            log(f"STOP PUT not accepted (status {stop_status}) -- emergency stop will run.")
    except KeyboardInterrupt:
        log("Interrupted -- attempting emergency stop if a phase was started.")
    finally:
        started_ok = isinstance(start_status, int) and 200 <= start_status < 300
        if started_ok and not stop_confirmed:
            _emergency_stop(out, pet_id, user_id, token, working_base)
        final = get_json(out, "final confirmation health/overview", health_overview_url(pet_id), user_id, token)
        final_ongoing, final_started = separation_status(final)
        record(out, "=== run summary ===", "", "-", json.dumps({
            "resolved_host": host_label, "working_base": working_base,
            "start_put_status": start_status, "stop_put_status": stop_status,
            "baseline_is_phase_ongoing": ongoing0,
            "final_is_phase_ongoing": final_ongoing, "final_phase_started_at": final_started,
            "restored_ok": final_ongoing is not True,
        }))
        if final_ongoing is not True:
            log(f"DONE. No phase ongoing (back to baseline). Raw log: {OUTPUT_FILE}")
        else:
            base = working_base or GRAPH_BASE
            log("!!! WARNING: a separation phase is still ongoing. Stop it manually, e.g.:")
            log(f'!!!   curl -s -X PUT "{base}pet/{pet_id}/separation/phase/stop" \\')
            log(f'!!!     -H "x-tractive-client: {CLIENT_ID}" -H "x-tractive-user: {user_id}" \\')
            log('!!!     -H "authorization: Bearer <token>" -H "content-type: application/json;charset=UTF-8"')
        out.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
