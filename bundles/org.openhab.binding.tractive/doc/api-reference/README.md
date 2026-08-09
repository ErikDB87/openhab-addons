# api-reference

Auto-generated, anonymized reference tree of every distinct JSON shape the Tractive API has been observed sending — one subtree per tracker type, built from real openHAB binding logs by `doc_scaffold.py`.

## Why this exists

The Tractive API is undocumented.
This directory is the binding's living record of what the API actually returns: REST endpoint responses and real-time channel messages, captured from real traffic rather than reverse-engineered from documentation.

## Captured log-line formats

`doc_scaffold.py` recognizes six binding-log trace formats:

- `GET <url> → {json}` — any REST poll/response
- `Channel line: {json}` — real-time channel push (`keep-alive`/`handshake` filtered out)
- `Tracker list response: [json]`
- `Trackable objects list: [json]`
- `Trackable object <id> response: {json}`
- `Command <name>/<state> → {json}`

`Auth response: {json}` is deliberately **not** captured — it carries a live `access_token`/`user_id`.

## Layout

```text
api-reference/
├── doc_scaffold.py
├── README.md              (this file)
└── <tracker-type>/                        e.g. dog-6/
    ├── index.md                           auto-generated overview of every endpoint/message — start here
    ├── <normalized-endpoint>/             e.g. graph.tractive.com/4/tracker/{trackerId}/
    │   ├── shape-1.json, shape-2.json...  one file per distinct JSON shape seen
    │   └── overview.md                    every key ever seen: type, value range/enum, explanation
    └── channel/<message-type>/            e.g. channel/tracker_status/
        ├── shape-1.json, ...
        └── overview.md
```

`{trackerId}` / `{objectId}` path segments are anonymized the same way field values are — see below.

`index.md` is rebuilt from scratch on every run — it scans the whole `<tracker-type>/` tree (not just the current run's log lines), so it always reflects every endpoint/message discovered across all past runs, not just the latest batch.

## Regenerating / updating

For example:

```bash
python doc_scaffold.py --tracker-type dog-6 --review-log /path/outside/doc/review.md \
    --raw-archive /path/outside/doc/raw.jsonl --new-keys-log /path/outside/doc/new-keys.md \
    log1.txt log2.txt ...
```

Safe to run once, or repeatedly (e.g. daily), over newly captured logs.
`shape-N.json` files only ever gain new entries, and `overview.md`'s per-key statistics (value ranges, enumerations, presence) accumulate across runs via hidden HTML-comment state embedded in the file — including any hand-written `Explanation:` text you add, which survives regeneration.

`--review-log` is **required** and must point **outside** the binding directory.
It records every "in doubt" redaction (coordinate- or ID-shaped values that got anonymized based on a heuristic guess, not a known field name) together with the **real, un-anonymized value** — so it must never be committed or shared alongside this tree.
Review it carefully for false positives.

It is append-only and size-rotated (see "Log rotation" below) rather than growing without bound.

Two more flags are optional:

- `--raw-archive <path>` appends every extracted sample **unredacted**, one JSON object per line, tagged with which log-line format it came from (`tractive-binding-log-source`). Same rule as `--review-log`: point it **outside** the binding directory, never commit or share it. Also append-only and size-rotated (see "Log rotation" below).
- `--new-keys-log <path>` is **overwritten** each run with only the JSON key paths first seen in that run (empty file if none) — meant to be polled by a separate alerting script, which is intentionally not this script's job.

## Log rotation

`--review-log` and `--raw-archive` are the only two outputs that are truly append-only across runs (everything else — `overview.md`, `shape-N.json`, `index.md`, `control-timeout-correlation.md` — is either rewritten each run or naturally capped by the finite set of distinct shapes/keys actually seen). Since this script runs hourly forever via a systemd timer with nothing else cleaning them up, both are size-rotated logrotate-style: once a file reaches `ROTATE_MAX_BYTES` (15 MiB by default), it's renamed to `<path>.1`, any existing `.1`/`.2` are shifted to `.2`/`.3`, and a fresh file is started — keeping up to `ROTATE_BACKUP_COUNT` (3 by default) old generations before the oldest is deleted. Adjust the constants at the top of `doc_scaffold.py` if your deployment needs a different cap.

## Anonymization

- Coordinate pairs (`latlong`, `home_location`, or anything else that _looks_ like a lat/lon pair) are replaced with a fixed placeholder location, rounded to match the original value's decimal precision.
- Tracker-ID-shaped (8-char uppercase alphanumeric) and Mongo-ObjectId-shaped (24-char hex) strings are replaced with `{trackerId}` / `{objectId}`.
- Known ID-bearing field names (`hw_id`, `tracker_id`, `device_id`, `_id`, `*_id`, `*Id`, etc.) are redacted silently; anything else that merely _happens_ to match the shape (e.g. a coincidentally 8-letter enum value) is still redacted, but logged loudly to the review log as a `HEURISTIC_ID_SHAPE` / `HEURISTIC_COORDINATE` row for manual review.

See `doc_scaffold.py`'s module docstring for the full design rationale.
