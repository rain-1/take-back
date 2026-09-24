#!/usr/bin/env python3
"""Filter Takeback's plain event stream for one group and append ideas as JSONL.

Run `tb watch -plain` as the input process, then pipe its output here. Each
matching message is appended once per process run. This keeps intake separate
from implementation: messages become a reviewable local queue, never commands.
"""

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path

LINE = re.compile(r"^\[#(?P<group>.+?)\] (?P<sender>[^:]+): (?P<body>.*)$")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", help="group name, with or without leading #")
    parser.add_argument("--output", type=Path, default=Path(".takeback-ideas.jsonl"),
                        help="append-only JSON Lines queue (default: %(default)s)")
    args = parser.parse_args()
    wanted = args.group.removeprefix("#").strip().casefold()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for line in sys.stdin:
        match = LINE.match(line.rstrip("\r\n"))
        if not match or match["group"].casefold() != wanted:
            continue
        body = match["body"].strip()
        if not body:
            continue
        record = {
            "received_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "group": match["group"],
            "sender": match["sender"],
            "body": body,
        }
        with args.output.open("a", encoding="utf-8") as queue:
            queue.write(json.dumps(record, ensure_ascii=False) + "\n")
            queue.flush()
        print(f"queued idea from {match['sender']}", file=sys.stderr, flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        raise SystemExit(0)
