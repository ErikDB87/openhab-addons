#!/usr/bin/env python3
"""
Extracts JSON payloads from Tractive openHAB binding log lines — both the
real-time channel's "Channel line: {...}" traces and the REST "GET <url> ->
{...}" traces — into a JSONL file (one JSON object per line) for later
analysis.

Usage:
    python3 extract_tractive_json.py <log_file> [output_file]

Also prints a summary of distinct message "shapes" seen (grouped by the
"message" field plus the set of top-level keys), so new or rare variants
stand out without scrolling through the repetitive keep-alive entries.
"""

import argparse
import json
from collections import Counter


def extract_json_from_line(line):
    if "Channel line: " not in line and " GET " not in line:
        return None

    brace_index = line.find("{")
    if brace_index == -1:
        return None

    candidate = line[brace_index:].strip()
    try:
        json.loads(candidate)
    except json.JSONDecodeError:
        return None

    return candidate


def summarize(payloads):
    shapes = Counter()
    for payload in payloads:
        obj = json.loads(payload)
        if not isinstance(obj, dict):
            shapes[("<non-object JSON>", ())] += 1
            continue
        message = obj.get("message", "<no message field>")
        shapes[(message, tuple(sorted(obj.keys())))] += 1
    return shapes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log_file", help="Path to the log file to scan (e.g. tractivebinding.log)")
    parser.add_argument("output_file", nargs="?", default="extracted.jsonl",
                         help="Output JSONL file (default: extracted.jsonl)")
    args = parser.parse_args()

    try:
        with open(args.log_file, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except FileNotFoundError:
        print(f"File not found: {args.log_file}")
        raise SystemExit(1)

    payloads = []
    for line in lines:
        payload = extract_json_from_line(line)
        if payload is not None:
            payloads.append(payload)

    with open(args.output_file, "w", encoding="utf-8") as f:
        for payload in payloads:
            f.write(payload + "\n")

    print(f"Extracted {len(payloads)} JSON payloads to {args.output_file}")

    print("\nDistinct shapes seen (message, sorted top-level keys) -> count:")
    for (message, keys), count in summarize(payloads).most_common():
        print(f"  [{count:>5}] message={message!r} keys={list(keys)}")


if __name__ == "__main__":
    main()
