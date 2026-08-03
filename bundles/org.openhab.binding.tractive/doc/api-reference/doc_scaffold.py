#!/usr/bin/env python3
"""
Build and incrementally update a browsable API-payload reference tree
(doc/<tracker-type>/...) from openHAB Tractive binding log files: REST
"GET <url> -> {json}" lines and real-time "Channel line: {json}" lines.

For each endpoint / channel message type, writes one file per distinct
*recursive* JSON shape (shape-1.json, shape-2.json, ...) plus a tree-shaped
overview.md summarizing every key ever seen (type, value range/enumeration,
"not always present" remark, and a hand-editable Explanation line).

Coordinates (`latlong`/`home_location`, or anything else that looks like a
lat/lon pair) and ID-shaped strings (tracker-ID and Mongo-ObjectId shaped)
are anonymized before anything is written to disk. Every "in doubt"
redaction (heuristic coordinate match, ID-shape match) is appended as a row
to a required, separate review-log table -- this log contains the REAL
values, so it must never live under --out.

Usage:
    python doc_scaffold.py --tracker-type dog-6 \
        --review-log /path/outside/doc/review.md \
        binding1.log binding2.log ...

Output is always written to <the directory this script lives in>/<tracker-type>/...

Designed to be run once, or repeatedly (e.g. daily), over newly captured logs:
shape files only ever gain new entries, and overview.md's counters are
merged with its own previous run via hidden HTML-comment state, so
value ranges/enumerations/presence accumulate across runs even though
each run only sees that run's new log lines.
"""

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Placeholder coordinates used to anonymize any real GPS position before it is
# written into the shareable doc/ tree. Chosen arbitrarily; the only property
# that matters is that they are not a real captured location.
ANON_LAT = 51.15156499765719
ANON_LON = 4.476487652479988

TRACKER_ID_RE = re.compile(r"^[A-Z0-9]{8}$")
OBJECT_ID_RE = re.compile(r"^[0-9a-f]{24}$")

COORD_FIELD_NAMES = {"latlong", "home_location"}

# Field names known to hold an ID-shaped value (tracker ID or Mongo ObjectId).
# A silent/expected redaction fires only when BOTH the value's shape matches
# AND the field name is one of these -- matching on value shape alone let
# coincidental all-caps 8-letter enum values (e.g. "CHARGING") get silently
# swapped out even though they were never actually an identifier.
ID_FIELD_NAMES = {
    "_id", "hw_id", "tracker_id", "device_id", "trackerId", "trackedPetId",
    "petId", "power_saving_zone_id", "prioritized_zone_id", "report_id",
}
ID_FIELD_SUFFIX_RE = re.compile(r"(_id|Id)$")


def is_id_like_field(field_name):
    return field_name in ID_FIELD_NAMES or bool(ID_FIELD_SUFFIX_RE.search(field_name))

SCRIPT_DIR = Path(__file__).resolve().parent

# Cap on how many distinct values an enum-like (string/bool) key can
# accumulate in overview.md before we give up listing them individually and
# collapse the entry into a "many" bucket (count + a few examples) instead.
# Keeps overview.md readable for high-cardinality fields (e.g. per-report IDs
# that only look enum-like at small sample sizes) without hiding genuinely
# small, meaningful enumerations like status strings.
ENUM_CAP = 10

# Real log format, confirmed from a live binding log sample:
#   GET https://graph.tractive.com/4/tracker/FCTPOEBK → {"hw_id":"FCTPOEBK",...}
# The separator is the Unicode arrow U+2192, not ASCII "->".
REST_LINE_RE = re.compile(r"\bGET\s+(\S+)\s*\u2192\s*(\{.*|\[.*)$")

# Confirmed correct as-is against the real log, e.g.:
#   Channel line: {"channel_id":"channel-...","message":"handshake"}
CHANNEL_LINE_RE = re.compile(r"Channel line:\s*(\{.*)$")


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

def decimal_places(value):
    text = repr(value)
    if "." not in text:
        return 0
    return len(text.split(".", 1)[1])


def anonymized_coord_pair(lat, lon):
    lat_places = decimal_places(lat)
    lon_places = decimal_places(lon)
    return round(ANON_LAT, lat_places), round(ANON_LON, lon_places)


def looks_like_coordinate(value):
    if not isinstance(value, list) or len(value) != 2:
        return False
    lat, lon = value
    if not all(isinstance(v, (int, float)) for v in (lat, lon)):
        return False
    return -90 <= lat <= 90 and -180 <= lon <= 180


def normalize_endpoint(url):
    url = url.split("://", 1)[-1]
    url, _, query = url.partition("?")
    url = url.rstrip("/")
    segments = url.split("/")

    normalized = []
    for seg in segments:
        if TRACKER_ID_RE.match(seg):
            normalized.append("{trackerId}")
        elif OBJECT_ID_RE.match(seg):
            normalized.append("{objectId}")
        else:
            normalized.append(seg)

    key = "/".join(normalized)

    if "positions" in normalized and query:
        params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
        fmt = params.get("format")
        if fmt:
            key = f"{key}/format={fmt}"

    return key


def try_parse_json(text, source):
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        print(f"WARN: could not parse JSON from {source}: {text[:120]!r}", file=sys.stderr)
        return None


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------

def extract_samples(log_paths):
    for log_path in log_paths:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            for line_num, line in enumerate(f, 1):
                source = f"{log_path}:{line_num}"

                m = REST_LINE_RE.search(line)
                if m:
                    url, json_text = m.group(1), m.group(2)
                    payload = try_parse_json(json_text, source)
                    if payload is None:
                        continue
                    yield normalize_endpoint(url), payload, source
                    continue

                m = CHANNEL_LINE_RE.search(line)
                if m:
                    payload = try_parse_json(m.group(1), source)
                    if payload is None:
                        continue
                    message = payload.get("message") if isinstance(payload, dict) else None
                    if message in ("keep-alive", "handshake"):
                        continue
                    if message is None:
                        yield "channel/_unclassified", payload, source
                    else:
                        yield f"channel/{message}", payload, source


# ---------------------------------------------------------------------------
# Redaction / anonymization
# ---------------------------------------------------------------------------

class RedactionLog:
    def __init__(self):
        self.rows = []

    def record(self, event_type, field_path, source, original, replacement):
        self.rows.append((event_type, field_path, source, original, replacement))

    def write(self, path):
        path = Path(path)
        is_new = not path.exists()
        with open(path, "a", encoding="utf-8") as f:
            if is_new:
                f.write("| Type | Field | Source | Original value | Replacement |\n")
                f.write("|---|---|---|---|---|\n")
            for event_type, field_path, source, original, replacement in self.rows:
                f.write(f"| {event_type} | {field_path} | {source} | {original} | {replacement} |\n")


def redact(obj, source, review_log, path=""):
    if isinstance(obj, dict):
        result = {}
        for key, value in obj.items():
            sub_path = f"{path}.{key}" if path else key
            if key in COORD_FIELD_NAMES and looks_like_coordinate(value):
                lat, lon = anonymized_coord_pair(value[0], value[1])
                result[key] = [lat, lon]
            else:
                result[key] = redact(value, source, review_log, sub_path)
        return result

    if isinstance(obj, list):
        return [redact(v, source, review_log, f"{path}[]") for v in obj]

    if isinstance(obj, str):
        field_name = path.rsplit(".", 1)[-1] if path else ""
        id_like_field = is_id_like_field(field_name)

        if TRACKER_ID_RE.match(obj):
            if not id_like_field:
                print(f"WARN: heuristic ID-shape match at {path} ({source})", file=sys.stderr)
                review_log.record("HEURISTIC_ID_SHAPE", path, source, obj, "{trackerId}")
            else:
                review_log.record("ID_SHAPE_MATCH", path, source, obj, "{trackerId}")
            return "{trackerId}"

        if OBJECT_ID_RE.match(obj):
            if not id_like_field:
                print(f"WARN: heuristic ID-shape match at {path} ({source})", file=sys.stderr)
                review_log.record("HEURISTIC_ID_SHAPE", path, source, obj, "{objectId}")
            else:
                review_log.record("ID_SHAPE_MATCH", path, source, obj, "{objectId}")
            return "{objectId}"

        return obj

    if looks_like_coordinate(obj):
        lat, lon = anonymized_coord_pair(obj[0], obj[1])
        print(f"WARN: heuristic coordinate match at {path} ({source})", file=sys.stderr)
        review_log.record("HEURISTIC_COORDINATE", path, source, obj, [lat, lon])
        return [lat, lon]

    return obj


# ---------------------------------------------------------------------------
# Shape deduplication
# ---------------------------------------------------------------------------

def shape_signature(obj, path=""):
    if isinstance(obj, dict):
        keys = set()
        for key, value in obj.items():
            sub_path = f"{path}.{key}" if path else key
            keys.add(sub_path)
            keys |= shape_signature(value, sub_path)
        return keys
    if isinstance(obj, list):
        if not obj:
            return set()
        return shape_signature(obj[0], f"{path}[]")
    return set()


def write_shapes(group_dir, redacted_payloads):
    group_dir.mkdir(parents=True, exist_ok=True)

    existing = {}
    for shape_file in group_dir.glob("shape-*.json"):
        with open(shape_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        existing[frozenset(shape_signature(data))] = shape_file

    next_index = len(existing) + 1
    for payload in redacted_payloads:
        sig = frozenset(shape_signature(payload))
        if sig in existing:
            continue
        shape_path = group_dir / f"shape-{next_index}.json"
        with open(shape_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, sort_keys=True)
        existing[sig] = shape_path
        next_index += 1


# ---------------------------------------------------------------------------
# overview.md generation
# ---------------------------------------------------------------------------

STATSKEY_RE = re.compile(r"<!--\s*statskey:\s*(\S+)\s*(.*?)-->")
EXPLAIN_RE = re.compile(r"<!--\s*explain-anchor:\s*(\S+)\s*-->\n(.*?)\n<!-- end-explain -->", re.S)
TOTAL_RE = re.compile(r"<!--\s*total_samples:\s*(\d+)\s*-->")


def load_previous_overview(path):
    if not path.exists():
        return 0, {}, {}

    text = path.read_text(encoding="utf-8")

    total_match = TOTAL_RE.search(text)
    total = int(total_match.group(1)) if total_match else 0

    stats = {}
    for key, raw in STATSKEY_RE.findall(text):
        stats[key] = json.loads(raw) if raw.strip() else {}

    explanations = {key: body.strip() for key, body in EXPLAIN_RE.findall(text)}

    return total, stats, explanations


def merge_key_stats(old, obj_type, value):
    # A null sample must never erase or relabel stats already established from a
    # real (non-null) sample, and it must never itself become the reported kind
    # once real data exists -- previously a single null seen after (or before)
    # real values could permanently flip "kind" to "null" while numeric/enum
    # data kept silently accumulating underneath, unreported.
    if value is None:
        return old if old is not None else {"kind": "null"}

    if isinstance(value, (dict, list)):
        kind = "container"
    elif looks_like_coordinate(value):
        kind = "coordinate"
    elif isinstance(value, bool):
        kind = "enum"
    elif isinstance(value, (int, float)):
        kind = "numeric"
    else:
        kind = "enum"

    # Start fresh only if nothing real has been recorded yet. A prior stat that
    # already carries real data (counts/min, or a real non-null kind) is kept
    # and just gets its "kind" label corrected -- this self-heals overview.md
    # files written before this fix, where "kind" may say "null" despite
    # "counts"/"min" already holding real accumulated values.
    has_real_data = old is not None and (
        "counts" in old or "min" in old or old.get("kind") not in (None, "null")
    )
    stat = old if has_real_data else {"kind": kind}
    stat["kind"] = kind

    if kind == "numeric":
        stat.setdefault("min", value)
        stat.setdefault("max", value)
        stat.setdefault("sum", 0.0)
        stat.setdefault("count", 0)
        stat["min"] = min(stat["min"], value)
        stat["max"] = max(stat["max"], value)
        stat["sum"] += value
        stat["count"] += 1
    elif kind == "enum":
        if stat.get("kind") == "many":
            stat["examples"] = (stat.get("examples") or [])[:3]
        else:
            counts = stat.setdefault("counts", {})
            key = json.dumps(value)
            counts[key] = counts.get(key, 0) + 1
            if len(counts) > ENUM_CAP:
                examples = list(counts.keys())[:3]
                stat = {"kind": "many", "examples": [json.loads(e) for e in examples]}

    return stat


def walk_and_merge(obj, path, stats, key_order):
    if isinstance(obj, dict):
        for key, value in obj.items():
            sub_path = f"{path}.{key}" if path else key
            if sub_path not in stats:
                key_order.append(sub_path)
            stats[sub_path] = merge_key_stats(stats.get(sub_path), type(value), value)
            walk_and_merge(value, sub_path, stats, key_order)
    elif isinstance(obj, list) and obj:
        walk_and_merge(obj[0], f"{path}[]", stats, key_order)


def format_value_summary(stat):
    kind = stat.get("kind")
    if kind == "numeric" and stat.get("count"):
        mean = stat["sum"] / stat["count"]
        return f"numeric, min={stat['min']}, max={stat['max']}, mean={mean:.2f}"
    if kind == "enum":
        values = [json.loads(k) for k in stat.get("counts", {})]
        return f"enum, values seen: {values}"
    if kind == "many":
        return f"many distinct values, e.g. {stat.get('examples')}"
    if kind == "coordinate":
        return "coordinate pair (anonymized in shape samples)"
    if kind == "null":
        return "always null so far"
    if kind == "container":
        return "object/array (see nested keys)"
    return kind or "unknown"


def write_overview(group_dir, redacted_payloads):
    overview_path = group_dir / "overview.md"
    prev_total, stats, explanations = load_previous_overview(overview_path)

    key_order = list(stats.keys())
    total = prev_total
    for payload in redacted_payloads:
        walk_and_merge(payload, "", stats, key_order)
        total += 1

    lines = [f"# {group_dir.name}", "", f"<!-- total_samples: {total} -->", ""]
    for key in key_order:
        stat = stats[key]
        depth = key.count(".") + key.count("[]")
        indent = "  " * depth
        summary = format_value_summary(stat)
        lines.append(f"{indent}- `{key}` — {summary}")
        lines.append(f"<!-- statskey: {key} {json.dumps(stat)} -->")
        anchor = key.replace(".", "_").replace("[]", "_arr")
        lines.append(f"<!-- explain-anchor: {anchor} -->")
        lines.append(explanations.get(key, "_Explanation: TODO_"))
        lines.append("<!-- end-explain -->")

    overview_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# index.md generation
# ---------------------------------------------------------------------------

def build_index(out_root, tracker_type):
    rows = []
    for overview_path in sorted(out_root.rglob("overview.md")):
        group_dir = overview_path.parent
        group_key = group_dir.relative_to(out_root).as_posix()
        text = overview_path.read_text(encoding="utf-8")

        total_match = TOTAL_RE.search(text)
        total = int(total_match.group(1)) if total_match else 0

        shape_count = len(list(group_dir.glob("shape-*.json")))

        top_level_keys = [
            key for key, _ in STATSKEY_RE.findall(text)
            if "." not in key and "[]" not in key
        ]

        rows.append((group_key, total, shape_count, top_level_keys))

    channel_rows = [r for r in rows if r[0].startswith("channel/")]
    endpoint_rows = [r for r in rows if not r[0].startswith("channel/")]

    def format_table(table_rows, key_label):
        lines = [
            f"| {key_label} | Samples | Shapes | Top-level fields | Details |",
            "|---|---|---|---|---|",
        ]
        for group_key, total, shape_count, top_level_keys in table_rows:
            fields = ", ".join(top_level_keys) if top_level_keys else "_(none)_"
            lines.append(f"| `{group_key}` | {total} | {shape_count} | {fields} | [overview.md]({group_key}/overview.md) |")
        return "\n".join(lines)

    lines = [
        f"# {tracker_type} — API reference index",
        "",
        "Auto-generated by `doc_scaffold.py` on every run -- do not hand-edit, changes will be overwritten.",
        "",
        "## REST endpoints",
        "",
        format_table(endpoint_rows, "Endpoint") if endpoint_rows else "_None captured yet._",
        "",
        "## Real-time channel messages",
        "",
        format_table(channel_rows, "Message") if channel_rows else "_None captured yet._",
        "",
    ]

    (out_root / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tracker-type", required=True, help="e.g. dog-6")
    parser.add_argument("--review-log", required=True, help="Path to append redaction review-log rows to")
    parser.add_argument("log_files", nargs="+")
    args = parser.parse_args()

    review_log = RedactionLog()
    groups = {}

    for group_key, payload, source in extract_samples(args.log_files):
        groups.setdefault(group_key, []).append((payload, source))

    out_root = SCRIPT_DIR / args.tracker_type
    id_redaction_count = 0

    for group_key, payload_sources in groups.items():
        group_dir = out_root / group_key
        redacted_payloads = []
        for payload, source in payload_sources:
            redacted = redact(payload, source, review_log)
            redacted_payloads.append(redacted)

        write_shapes(group_dir, redacted_payloads)
        write_overview(group_dir, redacted_payloads)

    build_index(out_root, args.tracker_type)

    review_log.write(args.review_log)
    id_redaction_count = sum(1 for row in review_log.rows if row[0] == "ID_SHAPE_MATCH")

    print(f"Processed {sum(len(v) for v in groups.values())} samples across {len(groups)} groups.")
    print(f"Redaction review log: {args.review_log} ({len(review_log.rows)} rows, "
          f"{id_redaction_count} ID-shape matches)")
    print(f"Index written to {out_root / 'index.md'}")


if __name__ == "__main__":
    main()
