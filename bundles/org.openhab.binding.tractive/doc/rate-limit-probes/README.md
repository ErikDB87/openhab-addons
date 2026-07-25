# Rate-limit probes

Tractive's API has no public documentation of its HTTP 429 rate-limiting behavior — no
published request quota, no `Retry-After` header, nothing. The two scripts in this
directory characterize it empirically, against a real account, instead of guessing. This
file records what running them actually found, and how those findings map onto the
binding's own rate-limit handling.

## Why this exists

A real production incident once hit this binding: bulk-linking Items to a Thing's
channels made openHAB send a `REFRESH` command to each newly-linked channel at once, and
the handler at the time scheduled a fresh, undebounced poll per command — producing
roughly 9–14 concurrent `GET tracker/{id}` calls and tripping real HTTP 429s. That
motivated `PollGuard` (`internal/util/PollGuard.java`), a per-endpoint request debounce.

Later, `SharedRateLimitBucket` (`internal/util/SharedRateLimitBucket.java`) was added on
top of that: a token-bucket model of Tractive's actual rate limit, shared by every REST
call this binding makes to `graph.tractive.com`. Its two tuning constants
(`GRAPH_API_BUCKET_CAPACITY` and `GRAPH_API_BUCKET_REFILL_PER_SECOND`, both in
`TractiveAccountHandler.java`) are set from the measurements below, not guessed.

## The scripts

Both are read-only (GET requests only), prompt for credentials interactively (never via
command-line arguments, so nothing lands in shell history), and pause once at startup to
remind you to take the live binding offline first — its own traffic would otherwise share
the same account-level rate-limit budget and confound the result.

- **`rate_limit_burst_probe.py`** — fires escalating batches of genuinely concurrent
  requests (`ThreadPoolExecutor`) against `GET tracker/{trackerId}`, looking for the
  smallest burst size that produces an HTTP 429. Stops at the first failure rather than
  pushing further.
- **`rate_limit_window_probe.py`** — three modes answering three follow-up questions:
  - `--mode recovery`: after deliberately tripping a 429, how long until the account is
    back to full capacity?
  - `--mode spacing`: how far apart do sequential calls need to be to avoid triggering a
    429 at all — i.e. what's the safe _steady_ rate, as opposed to the burst ceiling?
  - `--mode cross-endpoint`: is the rate limit shared across `graph.tractive.com`'s
    different REST endpoints, or does each have its own independent budget?

Both `rate_limit_burst_probe.py` and `--mode recovery`/`--mode spacing` in
`rate_limit_window_probe.py` accept `--endpoint` to target a different REST call instead
of the default `tracker` — including `health_overview`, which is on the separate
`aps-api.tractive.com` host rather than `graph.tractive.com`. `--mode cross-endpoint`
ignores `--endpoint`; it already tests every endpoint together by design.

Run `python <script> --help` for the full set of options; each script's own module
docstring explains its design and safety reasoning in more detail than this file does.

## What was found

All measurements below are from one testing session against one real account. They're
a model fit to a handful of black-box samples, not documented Tractive behavior — treat
them as the best evidence currently available, not a guarantee.

**At a glance:**

| Measurement                    | `tracker` (graph.tractive.com)                            | `health_overview` (aps-api.tractive.com)                                                                                     |
| ------------------------------ | --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Concurrent-burst ceiling       | 8 clean, 14 → 4/14 failed (bracket: 8–14)                 | 20 clean, 28 → 2/28 failed (bracket: 20–28)                                                                                  |
| Recovery time                  | `(5s, 6s]`, after a 4/14 (~29%) depletion                 | ≤5s, after a shallower 2/28 (~7%) depletion — not directly comparable                                                        |
| Fully clean sequential spacing | 2.0s (14 calls, ~26s total)                               | 0.1s (28 calls, ~2.7s total)                                                                                                 |
| Apparent mechanism             | Token bucket — rate-sensitive even when sequential        | Possibly concurrency-limited — clean at every sequential rate tested, only fails when genuinely simultaneous (not confirmed) |
| Binding's modeled constants    | `SharedRateLimitBucket`: capacity 8, refill 0.15 tokens/s | None — no bucket exists for this host                                                                                        |

**Concurrent-burst ceiling** (`rate_limit_burst_probe.py`, `GET tracker/{trackerId}`):
2, 4, and 8 genuinely concurrent calls all returned clean `200`s. 14 concurrent calls got
a mixed result — 4 of the 14 came back `429`, the other 10 succeeded. So the safe
concurrent-call ceiling for this endpoint is somewhere between 8 and 14, and the limiter
isn't strictly all-or-nothing at the threshold (some calls in the same burst succeeded,
some didn't).

**Recovery time** (`--mode recovery`, immediately after re-triggering the same
14-concurrent-burst 429 above): a single lightweight call 5 seconds after the triggering
burst still got `429`; the same check at 10 seconds got a clean `200`. A follow-up run
with a narrower delay sweep (6/7/8/9 seconds) recovered already at the first check (6s),
tightening the window to somewhere in `(5s, 6s]`. Both trigger attempts reproduced the
same 4×429/10×200 split from the burst test, which is reassuring repeatability.

**Trigger spacing** (`--mode spacing`, full 600-second rest between every round to avoid
contaminating one round's result with leftover pressure from the last — an earlier attempt
with only a 20-second pause produced a confusing, non-monotonic result for exactly this
reason): a clean, monotonic result — 0.25s spacing between 14 sequential calls → 4/14
failed; 0.5s → 3/14; 1.0s → 2/14; 2.0s → 0/14 (fully clean). Failures decrease smoothly as
spacing widens, as expected once rounds are properly isolated from each other.

**A token-bucket model fits all of the above.** Modeling the limiter as a bucket with some
capacity that refills continuously, `successes = min(14, capacity + floor(duration ×
refill_rate))`, where `duration` is how long it took to send all 14 calls (0 for the
concurrent burst; `spacing × 13` for a spacing round, since 14 calls have 13 gaps between
them). Plugging in `capacity ≈ 10` (from the burst result) and a refill rate in the
0.15–0.23 tokens/second range reproduces every spacing-sweep point:

| Spacing               | Duration for 14 calls | Observed successes | `10 + duration × refill_rate`          |
| --------------------- | --------------------- | ------------------ | -------------------------------------- |
| 0s (concurrent burst) | ~0s                   | 10/14              | 10 (this point _defines_ capacity)     |
| 0.25s                 | 3.25s                 | 10/14              | 10 + 3.25 × 0.15–0.23 ≈ 10             |
| 0.5s                  | 6.5s                  | 11/14              | 10 + 6.5 × 0.15–0.23 ≈ 11              |
| 1.0s                  | 13s                   | 12/14              | 10 + 13 × 0.15–0.23 ≈ 12               |
| 2.0s                  | 26s                   | 14/14 (capped)     | 10 + 26 × 0.15–0.23 ≈ 14, capped at 14 |

Two unrelated experiments (burst/recovery vs. sequential spacing) converging on the same
refill-rate range is stronger evidence than either alone.

The binding's `SharedRateLimitBucket` constants were set conservatively relative to this
model: **capacity 8**, not the ~10 the model infers (8 is the largest burst size _directly
observed_ to succeed 100% clean; 10 is only inferred from a 14-call burst that already had
failures in it — a less certain number to build a real limit on). **Refill rate 0.15
tokens/second**, the slower/more conservative end of the measured 0.15–0.23 range.

**Cross-endpoint bucket sharing** (`--mode cross-endpoint`): `device_hw_report` and
`device_pos_report` (both `graph.tractive.com`, like `tracker/{trackerId}`) each showed a
healthy 4/14-failure baseline alone — matching the ~10-capacity pattern independently —
then went to **14/14, total failure**, immediately after `tracker/{trackerId}` was
deliberately depleted, despite never having been touched themselves before that moment.
That confirms the three endpoints **share one combined rate-limit bucket**, not
independent ones per endpoint — which is exactly what `SharedRateLimitBucket` models: one
shared instance per account, not one per REST call site. The `aps-api.tractive.com` health
endpoint, tested the same way, was completely unaffected (0/14 failures both before and
after `graph.tractive.com` was drained) — consistent with rate limiting being per-host,
which is also why `SharedRateLimitBucket` deliberately excludes that host.

**`health_overview` (aps-api.tractive.com), characterized separately** — the
cross-endpoint result above only proved this host is _independent_ of
`graph.tractive.com`'s bucket, not what its own limit actually is (see the at-a-glance
table above for the summary), so the same three experiments were repeated against it
directly via `--endpoint health_overview`.

_Concurrent-burst ceiling:_ 2, 4, 8, 14, and 20 genuinely concurrent calls all returned
clean `200`s. 28 concurrent calls got a mixed result — 2 of the 28 came back `429`.

_Recovery time:_ a single lightweight call 5 seconds after the triggering burst already
got a clean `200` — recovered at or before the first delay checked.

_Trigger spacing:_ completely clean at every spacing tested — 0.1s, 0.25s, 0.5s, 1.0s,
2.0s, and 5.0s all returned 28/28 successes, including the tightest spacing (28 calls in
~2.7s, roughly 10 calls/second sustained). `tracker`, by contrast, only went fully clean at
2.0s spacing — a difference in shape, not just degree.

_Leading interpretation, not confirmed:_ this endpoint may be limited by
concurrent/simultaneous in-flight requests rather than sustained throughput, unlike
`tracker`'s token-bucket behavior — 28 truly-parallel requests trip it, but 28 requests
spaced even 0.1s apart (never more than one in flight from a single-threaded caller) never
do, regardless of overall rate. An equally consistent alternative: a token bucket with a
much higher capacity and/or refill rate than `graph.tractive.com`'s, simply not pushed hard
enough by these spacings to reveal its ceiling. Distinguishing the two would need a much
higher sustained _sequential_ throughput test (a far larger round size at very tight
spacing, no concurrency involved); not attempted here.

**Why the burst-ceiling brackets (8–14 for `tracker`, 20–28 for `health_overview`) weren't
narrowed further:** both scripts escalate through a fixed, coarse ladder of burst sizes
rather than binary-searching for the exact ceiling. The property that actually matters —
"this number was directly observed to succeed 100% clean, so it's ≤ the true capacity" —
holds no matter which sizes happened to be tested; only the _tightness_ of the resulting
bound depends on the ladder's granularity. Since these numbers are only ever used as
conservative safety floors, not performance targets, that imprecision costs some unused
headroom, not correctness — and closing the gap further would mean more escalating rounds,
each with its own full rested wait, deliberately triggering more 429s for a number that
wouldn't change the resulting configuration either way.

## What's still unknown

- The exact bucket capacity and refill rate — the numbers above are a model fit to one
  session's measurements, not a documented contract. They could be wrong, or could drift
  if Tractive changes its backend.
- Whether the rate limit is truly per-account (shared across multiple trackers under one
  login) or per-tracker — all testing here used a single tracker ID throughout, so this
  was never actually distinguished.
- Whether sustained pressure over many hours or days behaves differently (e.g. escalating
  penalties for repeat offenders) — everything measured here is on the scale of minutes.
- Whether being aggressive enough risks a longer-term account-level block, separate from
  the per-request 429 behavior characterized here. Not tested, deliberately — the
  escalating, stop-at-first-failure design of both scripts exists specifically to avoid
  finding out the hard way.
- Whether `health_overview`'s limiter is genuinely concurrency-based or just a
  higher-capacity/faster-refill token bucket that these spacings weren't tight/fast enough
  to expose — see "Leading interpretation" above.
- The exact `health_overview` burst ceiling within its 20–28 bracket, and its true refill
  rate (only bounded loosely by the ≤5s recovery figure) — deliberately not narrowed
  further, same reasoning as `tracker`'s bracket.
