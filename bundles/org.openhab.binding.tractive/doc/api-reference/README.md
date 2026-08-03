# api-reference

Auto-generated, anonymized reference tree of every distinct JSON shape the Tractive API has been observed sending — one subtree per tracker type, built from real openHAB binding logs by `doc_scaffold.py`.

## Why this exists

The Tractive API is undocumented.
This directory is the binding's living record of what the API actually returns: REST endpoint responses and real-time channel messages, captured from real traffic rather than reverse-engineered from documentation.

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
python doc_scaffold.py --tracker-type dog-6 --review-log /path/outside/doc/review.md log1.txt log2.txt ...
```

Safe to run once, or repeatedly (e.g. daily), over newly captured logs.
`shape-N.json` files only ever gain new entries, and `overview.md`'s per-key statistics (value ranges, enumerations, presence) accumulate across runs via hidden HTML-comment state embedded in the file — including any hand-written `Explanation:` text you add, which survives regeneration.

`--review-log` is **required** and must point **outside** the binding directory.
It records every "in doubt" redaction (coordinate- or ID-shaped values that got anonymized based on a heuristic guess, not a known field name) together with the **real, un-anonymized value** — so it must never be committed or shared alongside this tree.
Review it carefully for false positives.

## Anonymization

- Coordinate pairs (`latlong`, `home_location`, or anything else that _looks_ like a lat/lon pair) are replaced with a fixed placeholder location, rounded to match the original value's decimal precision.
- Tracker-ID-shaped (8-char uppercase alphanumeric) and Mongo-ObjectId-shaped (24-char hex) strings are replaced with `{trackerId}` / `{objectId}`.
- Known ID-bearing field names (`hw_id`, `tracker_id`, `device_id`, `_id`, `*_id`, `*Id`, etc.) are redacted silently; anything else that merely _happens_ to match the shape (e.g. a coincidentally 8-letter enum value) is still redacted, but logged loudly to the review log as a `HEURISTIC_ID_SHAPE` / `HEURISTIC_COORDINATE` row for manual review.

See `doc_scaffold.py`'s module docstring for the full design rationale.
