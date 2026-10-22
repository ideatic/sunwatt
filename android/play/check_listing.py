#!/usr/bin/env python3
"""Checks the Google Play listing texts: every locale has the three files, within Google's limits.

Usage: python3 -I android/play/check_listing.py android/play/listings
"""
import pathlib
import sys

LOCALES = ["en-US", "es-ES", "pt-PT", "fr-FR", "de-DE", "it-IT", "nl-NL", "zh-CN", "zh-TW", "pl-PL"]
LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit("usage: check_listing.py <listings_dir>")
    root = pathlib.Path(sys.argv[1])
    problems = []
    for locale in LOCALES:
        for name, limit in LIMITS.items():
            path = root / locale / f"{name}.txt"
            if not path.is_file():
                problems.append(f"{locale}: missing {name}.txt")
                continue
            text = path.read_text(encoding="utf-8").rstrip("\n")
            if not text.strip():
                problems.append(f"{locale}: empty {name}.txt")
            elif len(text) > limit:
                problems.append(f"{locale}: {name}.txt has {len(text)} characters (limit {limit})")
    for problem in problems:
        print(f"FAILED  {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"LISTING OK: {len(LOCALES)} locales")
    return 0


if __name__ == "__main__":
    sys.exit(main())
