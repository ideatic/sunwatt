#!/usr/bin/env python3
"""Check App Store metadata lengths and completeness.

Usage: python3 -I AppStore/check_metadata.py AppStore/metadata
"""
import pathlib
import sys

LOCALES = ["en-US", "es-ES", "pt-PT", "fr-FR", "de-DE", "it", "nl-NL", "zh-Hans", "zh-Hant", "pl"]
LIMITS = {
    "name": 30,
    "subtitle": 30,
    "promotional_text": 170,
    "description": 4000,
    "keywords": 100,
    "release_notes": 4000,
    "privacy_url": 2048,
    "support_url": 2048,
    "marketing_url": 2048,
}
GLOBAL = ["copyright.txt", "primary_category.txt", "secondary_category.txt"]


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: check_metadata.py <metadata_dir>")
    root = pathlib.Path(sys.argv[1])
    errs = []
    for g in GLOBAL:
        p = root / g
        if not p.is_file() or not p.read_text(encoding="utf-8").strip():
            errs.append(f"missing or empty {g}")
    for loc in LOCALES:
        for f, lim in LIMITS.items():
            p = root / loc / f"{f}.txt"
            if not p.is_file():
                errs.append(f"{loc}: missing {f}.txt")
                continue
            t = p.read_text(encoding="utf-8").rstrip("\n")
            n = len(t)
            if n == 0:
                errs.append(f"{loc}/{f}: empty")
            if n > lim:
                errs.append(f"{loc}/{f}: {n} > {lim}")
            if f == "keywords":
                if ", " in t or t != t.strip():
                    errs.append(f"{loc}/keywords: spaces around commas")
                if "sunwatt" in t.lower():
                    errs.append(f"{loc}/keywords: contains the app name")
            if f == "name" and not t.startswith("SunWatt"):
                errs.append(f"{loc}/name: does not start with SunWatt")
            print(f"{loc:6} {f:17} {n:5}/{lim}")
    if root.is_dir():
        extra = sorted(d.name for d in root.iterdir() if d.is_dir() and d.name not in LOCALES + ["review_information"])
        if extra:
            errs.append(f"unexpected locale folders: {extra}")
    if errs:
        print("\nFAIL")
        for e in errs:
            print(" -", e)
        sys.exit(1)
    print("\nOK: all files present, all limits respected")


main()
