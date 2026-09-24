#!/usr/bin/env python3
"""Forward queued Takeback messages to an existing Codex thread."""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path


def write_offset(path: Path, offset: int) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(str(offset) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--offset", type=Path, required=True)
    parser.add_argument("--codex", type=Path, required=True)
    args = parser.parse_args()
    thread = os.environ.get("TAKEBACK_CODEX_THREAD", "")
    if not thread:
        parser.error("TAKEBACK_CODEX_THREAD is required")
    args.offset.parent.mkdir(parents=True, exist_ok=True)
    if not args.offset.exists():
        # Start with new messages. Existing history remains in the JSONL queue.
        write_offset(args.offset, args.queue.stat().st_size if args.queue.exists() else 0)
    offset = int(args.offset.read_text().strip())

    while True:
        if not args.queue.exists():
            time.sleep(2)
            continue
        with args.queue.open("rb") as queue:
            queue.seek(offset)
            while line := queue.readline():
                if not line.endswith(b"\n"):
                    break
                next_offset = queue.tell()
                try:
                    item = json.loads(line)
                    message = (
                        f"Takeback message in #{item['group']} from {item['sender']}:\n\n"
                        f"{item['body']}"
                    )
                except (ValueError, KeyError, TypeError) as exc:
                    print(f"Skipping invalid queue record at {offset}: {exc}", flush=True)
                    offset = next_offset
                    write_offset(args.offset, offset)
                    continue
                result = subprocess.run(
                    [str(args.codex), "queue", "--thread", thread,
                     "--message", message], capture_output=True, text=True,
                    check=False,
                )
                if result.returncode:
                    print(f"Codex queue failed: {result.stderr.strip()}", flush=True)
                    break  # retry the same message after a short delay
                print(f"Relayed message from {item['sender']}", flush=True)
                offset = next_offset
                write_offset(args.offset, offset)
        time.sleep(2)


if __name__ == "__main__":
    main()
