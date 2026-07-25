#!/usr/bin/env python3
"""
Build and incrementally update a browsable API-payload reference tree
(doc/<tracker-type>/...) from openHAB Tractive binding log files: REST
"GET <url> -> {json}" lines, real-time "Channel line: {json}" lines, and
several discovery-service/command-response trace-line formats that predate
that convention (tracker list, trackable objects list/detail, commands).

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

Two additional outputs are opt-in via CLI flags:
- --raw-archive appends every extracted sample verbatim and unredacted, as JSONL rows
  of {"timestamp", "log-line-type", "payload"}: "timestamp" is parsed from the original
  binding-log line (e.g. "2026-08-09 11:29:17.225"; null if a line didn't match the
  expected prefix), "log-line-type" is which log-line format it came from (e.g.
  "GET <url>", "Channel line"). Same sensitivity as --review-log: must live outside
  --out, must never be committed or shared. Rotated backups are moved into a "rotated/"
  subdirectory next to the live file (see rotate_if_too_large()'s rotated_dir parameter).
- --new-keys-log is overwritten (not appended) on every run with only the
  JSON key paths first discovered in *this* run, empty if none. A separate
  watcher script can alert on it without needing its own dedup logic --
  sending that alert is deliberately not this script's job.

Usage:
    python doc_scaffold.py --tracker-type dog-6 \
        --review-log /path/outside/doc/review.md \
        [--raw-archive /path/outside/doc/raw.jsonl] \
        [--new-keys-log /path/outside/doc/new-keys.md] \
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

# Real enum/status values that happen to be exactly 8 uppercase letters, so they collide with
# TRACKER_ID_RE by coincidence. Confirmed via HEURISTIC_ID_SHAPE rows in review-log.md,
# cross-referenced against the real (unredacted) values in raw-archive.jsonl. Add newly
# discovered collisions here as they turn up.
KNOWN_NON_ID_VALUES = {"CHARGING", "ELEVATED"}

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

# Every binding-log line is prefixed with this timestamp, e.g.:
#   2026-08-09 11:29:17.225 [TRACE] [internal.handler.TractiveDog6Handler - 891       ] - ...
# Captured so each --raw-archive row can be traced back to when it happened, not just
# which log file/line it came from.
LOG_TIMESTAMP_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")

# Real log format, confirmed from a live binding log sample:
#   GET https://graph.tractive.com/4/tracker/FCTPOEBK → {"hw_id":"FCTPOEBK",...}
# The separator is the Unicode arrow U+2192, not ASCII "->".
REST_LINE_RE = re.compile(r"\bGET\s+(\S+)\s*\u2192\s*(\{.*|\[.*)$")

# Confirmed correct as-is against the real log, e.g.:
#   Channel line: {"channel_id":"channel-...","message":"handshake"}
CHANNEL_LINE_RE = re.compile(r"Channel line:\s*(\{.*)$")

# Discovery-service and command-response trace lines predate the
# "GET <url> -> <json>" convention and use their own bespoke formats --
# without these, trackable_objects/trackable_object/tracker-list/command
# payloads are silently invisible to this script.
TRACKER_LIST_RE = re.compile(r"Tracker list response:\s*(\[.*)$")
TRACKABLE_OBJECTS_LIST_RE = re.compile(r"Trackable objects list:\s*(\[.*)$")
TRACKABLE_OBJECT_RE = re.compile(r"Trackable object (\S+) response:\s*(\{.*)$")
COMMAND_RESPONSE_RE = re.compile(r"Command (\S+)/(\S+)\s*\u2192\s*(\{.*)$")

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
# Log rotation
# ---------------------------------------------------------------------------

# --review-log/--raw-archive are both append-only, and this script runs hourly
# forever via a systemd timer with nothing else ever cleaning them up -- without
# a cap they grow without bound. 100 MiB per generation x 3 kept backups is an
# arbitrary but generous ceiling (~400 MiB total); adjust to taste.
ROTATE_MAX_BYTES = 100 * 1024 * 1024
ROTATE_BACKUP_COUNT = 3


def rotate_if_too_large(path, max_bytes=ROTATE_MAX_BYTES, backup_count=ROTATE_BACKUP_COUNT, rotated_dir=None):
    """logrotate-style numbered rotation: path -> path.1 -> path.2 ... path.N, oldest dropped.

    Called before opening `path` in append mode, so a run that would push it past
    max_bytes starts a fresh file instead of appending onto an ever-growing one.

    If `rotated_dir` is given, numbered backups are moved into that directory
    (created if it doesn't exist yet) instead of sitting next to the live file.
    """
    path = Path(path)
    if not path.exists() or path.stat().st_size < max_bytes:
        return

    backup_root = Path(rotated_dir) if rotated_dir is not None else path.parent
    backup_root.mkdir(parents=True, exist_ok=True)

    oldest = backup_root / f"{path.name}.{backup_count}"
    if oldest.exists():
        oldest.unlink()

    for i in range(backup_count - 1, 0, -1):
        src = backup_root / f"{path.name}.{i}"
        if src.exists():
            src.rename(backup_root / f"{path.name}.{i + 1}")

    path.rename(backup_root / f"{path.name}.1")


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------

def extract_samples(log_paths):
    for log_path in log_paths:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            for line_num, line in enumerate(f, 1):
                source = f"{log_path}:{line_num}"
                ts_match = LOG_TIMESTAMP_RE.match(line)
                timestamp = ts_match.group(1) if ts_match else None

                m = REST_LINE_RE.search(line)
                if m:
                    url, json_text = m.group(1), m.group(2)
                    payload = try_parse_json(json_text, source)
                    if payload is None:
                        continue
                    yield normalize_endpoint(url), payload, source, f"GET {url}", timestamp
                    continue

                m = CHANNEL_LINE_RE.search(line)
                if m:
                    payload = try_parse_json(m.group(1), source)
                    if payload is None:
                        continue
                    message = payload.get("message") if isinstance(payload, dict) else None
                    if message in ("keep-alive", "handshake"):
                        continue
                    group_key = "channel/_unclassified" if message is None else f"channel/{message}"
                    yield group_key, payload, source, "Channel line", timestamp
                    continue

                m = TRACKER_LIST_RE.search(line)
                if m:
                    payload = try_parse_json(m.group(1), source)
                    if payload is None:
                        continue
                    yield ("graph.tractive.com/4/user/{objectId}/trackers", payload, source,
                           "Tracker list response", timestamp)
                    continue

                m = TRACKABLE_OBJECTS_LIST_RE.search(line)
                if m:
                    payload = try_parse_json(m.group(1), source)
                    if payload is None:
                        continue
                    yield ("graph.tractive.com/4/user/{objectId}/trackable_objects", payload, source,
                           "Trackable objects list", timestamp)
                    continue

                m = TRACKABLE_OBJECT_RE.search(line)
                if m:
                    pet_id, json_text = m.group(1), m.group(2)
                    payload = try_parse_json(json_text, source)
                    if payload is None:
                        continue
                    yield ("graph.tractive.com/4/trackable_object/{objectId}", payload, source,
                           f"Trackable object {pet_id} response", timestamp)
                    continue

                m = COMMAND_RESPONSE_RE.search(line)
                if m:
                    command_name, state, json_text = m.group(1), m.group(2), m.group(3)
                    payload = try_parse_json(json_text, source)
                    if payload is None:
                        continue
                    group_key = f"graph.tractive.com/4/tracker/{{trackerId}}/command/{command_name}"
                    yield group_key, payload, source, f"Command {command_name}/{state}", timestamp


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
        rotate_if_too_large(path)
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

        if TRACKER_ID_RE.match(obj) and obj not in KNOWN_NON_ID_VALUES:
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
    previously_known_keys = set(stats.keys())

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

    return [key for key in key_order if key not in previously_known_keys]


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

        def is_top_level(key):
            stripped = key[3:] if key.startswith("[].") else key
            return "." not in stripped and "[]" not in stripped

        top_level_keys = [
            key for key, _ in STATSKEY_RE.findall(text)
            if is_top_level(key)
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
# Control-object timeout correlation
# ---------------------------------------------------------------------------

CONTROL_FIELDS = ("led_control", "buzzer_control", "live_tracking")

CONTROL_STATS_RE = re.compile(r"<!--\s*control-stats:\s*(.*?)\s*-->", re.S)


def load_previous_control_stats(path):
    """Loads the JSON stats blob embedded in a previous run's control-timeout-correlation.md
    so this run's samples merge into it instead of overwriting it -- mirrors what
    load_previous_overview() does for overview.md.
    """
    if not path.exists():
        return {}
    match = CONTROL_STATS_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        return {}
    return json.loads(match.group(1))


def extract_control_samples(group_key, payload):
    """Yields (control_field, control_object) pairs from a single already-redacted payload.

    Two distinct origins carry the same control-object shape: a channel/tracker_status push
    nests it under led_control/buzzer_control/live_tracking, while a command-response group's
    payload *is* the control object directly.
    """
    if group_key == "channel/tracker_status" and isinstance(payload, dict):
        for field in CONTROL_FIELDS:
            value = payload.get(field)
            if isinstance(value, dict):
                yield field, value
        return

    for field in CONTROL_FIELDS:
        if group_key == f"graph.tractive.com/4/tracker/{{trackerId}}/command/{field}" and isinstance(payload, dict):
            yield field, payload


def merge_control_timeout_stats(stats, control_field, control_object, source, origin):
    active = control_object.get("active")
    active_key = json.dumps(active)
    entry = stats.setdefault(control_field, {}).setdefault(active_key, {"timeouts": {}, "examples": []})

    timeout_key = json.dumps(control_object.get("timeout"))
    timeout_entry = entry["timeouts"].setdefault(timeout_key, {"count": 0, "pending_true": 0, "pending_false": 0})
    timeout_entry["count"] += 1
    pending = control_object.get("pending")
    if pending is True:
        timeout_entry["pending_true"] += 1
    elif pending is False:
        timeout_entry["pending_false"] += 1

    if len(entry["examples"]) < 5:
        entry["examples"].append({"origin": origin, "source": source, "control_object": control_object})


def write_control_timeout_correlation(out_root, control_samples):
    """Writes control-timeout-correlation.md: for each of led_control/buzzer_control/
    live_tracking, how `timeout` varies with `active`, across every occurrence seen -- whether
    nested in a tracker_status push or standalone in a command response.

    Answers the open question of whether `timeout` is a fixed per-channel constant or varies with
    context (e.g. LED seen at both 300 and 3600) without having to eyeball a raw log by hand.
    """
    out_path = out_root / "control-timeout-correlation.md"
    stats = load_previous_control_stats(out_path)
    for group_key, payload, source in control_samples:
        origin = "tracker_status push" if group_key == "channel/tracker_status" else "command response"
        for control_field, control_object in extract_control_samples(group_key, payload):
            merge_control_timeout_stats(stats, control_field, control_object, source, origin)

    lines = [
        "# Control-object timeout correlation", "",
        "Auto-generated by `doc_scaffold.py` -- do not hand-edit, changes will be overwritten.", "",
        "For each control field, how `timeout` varies with `active`, across every occurrence seen "
        "in a `tracker_status` push *or* a command response (both carry the same object shape).", "",
        f"<!-- control-stats: {json.dumps(stats)} -->", "",
    ]
    for control_field in CONTROL_FIELDS:
        lines.append(f"## `{control_field}`")
        lines.append("")
        bucket = stats.get(control_field, {})
        if not bucket:
            lines.append("_None captured yet._")
            lines.append("")
            continue
        for active_key in sorted(bucket.keys()):
            entry = bucket[active_key]
            lines.append(f"- `active={active_key}`:")
            for timeout_key, timeout_entry in sorted(entry["timeouts"].items()):
                lines.append(f"  - `timeout={timeout_key}` — {timeout_entry['count']} sample(s) "
                             f"(pending=true: {timeout_entry['pending_true']}, "
                             f"pending=false: {timeout_entry['pending_false']})")
            lines.append("  - examples:")
            for ex in entry["examples"]:
                lines.append(f"    - {ex['origin']} ({ex['source']}): `{json.dumps(ex['control_object'])}`")
        lines.append("")

    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tracker-type", required=True, help="e.g. dog-6")
    parser.add_argument("--review-log", required=True, help="Path to append redaction review-log rows to")
    parser.add_argument("--raw-archive", help="Path to append unredacted {timestamp, log-line-type, payload} "
                                               "JSONL rows to (must live outside --out, same sensitivity as "
                                               "--review-log)")
    parser.add_argument("--new-keys-log", help="Path to overwrite with keys first discovered in this run "
                                                "(empty if none); a separate watcher can alert on it")
    parser.add_argument("log_files", nargs="+")
    args = parser.parse_args()

    review_log = RedactionLog()
    groups = {}
    raw_rows = []
    control_samples = []

    for group_key, payload, source, log_label, timestamp in extract_samples(args.log_files):
        groups.setdefault(group_key, []).append((payload, source))
        raw_rows.append({
            "timestamp": timestamp,
            "log-line-type": log_label,
            "payload": payload,
        })

    if args.raw_archive:
        raw_archive_path = Path(args.raw_archive)
        rotate_if_too_large(raw_archive_path, rotated_dir=raw_archive_path.parent / "rotated")
        with open(raw_archive_path, "a", encoding="utf-8") as f:
            for row in raw_rows:
                f.write(json.dumps(row) + "\n")

    out_root = SCRIPT_DIR / args.tracker_type
    new_keys = []

    for group_key, payload_sources in groups.items():
        group_dir = out_root / group_key
        redacted_payloads = []
        for payload, source in payload_sources:
            redacted = redact(payload, source, review_log)
            redacted_payloads.append(redacted)
            control_samples.append((group_key, redacted, source))

        write_shapes(group_dir, redacted_payloads)
        new_keys.extend((group_key, key) for key in write_overview(group_dir, redacted_payloads))

    write_control_timeout_correlation(out_root, control_samples)
    build_index(out_root, args.tracker_type)

    review_log.write(args.review_log)
    id_redaction_count = sum(1 for row in review_log.rows if row[0] == "ID_SHAPE_MATCH")

    if args.new_keys_log:
        with open(args.new_keys_log, "w", encoding="utf-8") as f:
            if new_keys:
                f.write("| Group | Key |\n|---|---|\n")
                for group_key, key in new_keys:
                    f.write(f"| `{group_key}` | `{key}` |\n")

    print(f"Processed {sum(len(v) for v in groups.values())} samples across {len(groups)} groups.")
    print(f"Redaction review log: {args.review_log} ({len(review_log.rows)} rows, "
          f"{id_redaction_count} ID-shape matches)")
    if new_keys:
        print(f"New keys discovered this run: {len(new_keys)} (see {args.new_keys_log})")
    print(f"Index written to {out_root / 'index.md'}")


if __name__ == "__main__":
    main()
