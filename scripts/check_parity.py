#!/usr/bin/env python3
"""Checks FEATURES.md, the feature list shared by the Apple and the Android app.

Usage: python3 -I scripts/check_parity.py   (from any folder; exit code 1 on a problem)

1. Every row has a valid ID, a status for each app (done, planned, n/a) and a reason for n/a.
2. Every provider, price region, inverter kind, widget option, app link, App Intent and widget
   found in the Apple code has a row, so a new one cannot land without its Android status.
3. Android cannot be `done` while android/ has no app code.
4. The Kotlin enums of the stored values have the same raw values as the Swift enums (FEATURES.md, "Stored values").
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FEATURES = ROOT / "FEATURES.md"
KIT = ROOT / "apple/Packages/SunWattKit/Sources/SunWattKit"
CORE = ROOT / "android/core/src/main/kotlin/com/ideatic/sunwatt/core"
STATUSES = {"done", "planned", "n/a"}
ID_PATTERN = re.compile(r"^[a-z][A-Za-z0-9-]*$")

# Enums whose cases are features: (file, enum name, ID prefix).
ENUMS = [
    (KIT / "Models/SolarAccount.swift", "SolarProvider", "solar-"),
    (KIT / "Models/PriceRegion.swift", "PriceRegion", "price-"),
    (KIT / "Services/InverterFinder.swift", "LocalInverterKind", "inverter-"),
    (KIT / "Models/Settings.swift", "WidgetContent", "widget-content-"),
    (KIT / "Models/Settings.swift", "PriceWindow", "price-window-"),
    (KIT / "Models/SunWattLink.swift", "SunWattLink", "link-"),
]

# The same enums in the Android core: Swift enum name -> Kotlin file (DECISIONS D34).
KOTLIN_ENUMS = {
    "SolarProvider": CORE / "models/SolarAccount.kt",
    "PriceRegion": CORE / "models/PriceRegion.kt",
    "LocalInverterKind": CORE / "models/LocalInverter.kt",
    "WidgetContent": CORE / "models/Settings.kt",
    "PriceWindow": CORE / "models/Settings.kt",
    "SunWattLink": CORE / "models/SunWattLink.kt",
}


def enum_cases(path: Path, name: str) -> list[str]:
    """Raw values of the top-level cases of a Swift enum (`case a`, `case a = "b"`, `case a, b`)."""
    lines = path.read_text(encoding="utf-8").splitlines()
    start = next((i for i, line in enumerate(lines) if re.match(rf"\s*(public\s+)?enum {name}\b", line)), None)
    if start is None:
        raise SystemExit(f"FAILED  enum {name} not found in {path.relative_to(ROOT)} (update scripts/check_parity.py)")
    cases = []
    for line in lines[start + 1:]:
        if line.startswith("}"):
            break
        match = re.match(r"^    case ([^:/]+?)\s*(//.*)?$", line)
        if not match:
            continue
        for part in match.group(1).split(","):
            case = re.match(r'\s*(\w+)\s*(?:=\s*"([^"]*)")?\s*$', part)
            if case:
                cases.append(case.group(2) or case.group(1))
    if not cases:
        raise SystemExit(f"FAILED  enum {name} has no cases the script can read (update scripts/check_parity.py)")
    return cases


def kotlin_enum_values(path: Path, name: str) -> list[str]:
    """Raw values of a Kotlin stored enum, written one entry per line: `    NAME("rawValue"),` (CONVENTIONS.md)."""
    lines = path.read_text(encoding="utf-8").splitlines()
    start = next((i for i, line in enumerate(lines) if re.match(rf"enum class {name}\(val rawValue: String\)", line)), None)
    if start is None:
        raise SystemExit(f"FAILED  enum class {name} not found in {path.relative_to(ROOT)} (update scripts/check_parity.py)")
    values = []
    for line in lines[start + 1:]:
        if line.startswith("}"):
            break
        match = re.match(r'^    [A-Z][A-Z0-9_]*\("([^"]*)"\)\s*[,;]', line)
        if match:
            values.append(match.group(1))
        if line.rstrip().endswith(";") and match:
            break
    if not values:
        raise SystemExit(f"FAILED  enum class {name} has no entries the script can read (update scripts/check_parity.py)")
    return values


def stored_value_problems() -> list[str]:
    """Kotlin raw values that differ from the Swift ones. Nothing to compare while android/core does not exist."""
    if not CORE.exists():
        return []
    problems = []
    for path, name, _ in ENUMS:
        swift = enum_cases(path, name)
        if name not in KOTLIN_ENUMS:
            raise SystemExit(f"FAILED  no Kotlin file for enum {name} in KOTLIN_ENUMS (update scripts/check_parity.py)")
        kotlin = kotlin_enum_values(KOTLIN_ENUMS[name], name)
        if sorted(swift) != sorted(kotlin):
            only_swift = sorted(set(swift) - set(kotlin))
            only_kotlin = sorted(set(kotlin) - set(swift))
            problems.append(f"{name}: stored values differ; only in Swift {only_swift}, only in Kotlin {only_kotlin}")
    return problems


def code_ids() -> dict[str, str]:
    """Feature IDs the Apple code requires, with where each one comes from."""
    ids = {}
    for path, name, prefix in ENUMS:
        for case in enum_cases(path, name):
            ids[prefix + case] = f"{name}.{case}"
    for swift in sorted((ROOT / "apple").rglob("*.swift")):
        if "/Tests/" in str(swift) or "/.build/" in str(swift) or "/build/" in str(swift):
            continue
        text = swift.read_text(encoding="utf-8")
        for match in re.finditer(r"^\s*(?:public\s+)?struct (\w+): AppIntent\b", text, re.M):
            ids["intent-" + match.group(1)] = f"App Intent {match.group(1)}"
        for match in re.finditer(r"^\s*(?:public\s+)?struct (\w+): (?:Widget|ControlWidget)\b", text, re.M):
            ids["surface-" + match.group(1)] = f"widget {match.group(1)}"
    return ids


def feature_rows() -> list[tuple[int, list[str]]]:
    rows = []
    for number, line in enumerate(FEATURES.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("|") or line.startswith("| ID ") or line.startswith("| ---"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        rows.append((number, cells))
    return rows


def android_has_code() -> bool:
    android = ROOT / "android"
    return any(android.rglob("*.kt")) or any(android.rglob("*.java"))


def main() -> int:
    problems = []
    seen = {}
    rows = feature_rows()
    android_code = android_has_code()
    for number, cells in rows:
        where = f"FEATURES.md:{number}"
        if len(cells) != 5:
            problems.append(f"{where}: needs 5 columns (ID, Feature, Apple, Android, Notes), has {len(cells)}")
            continue
        row_id, feature, apple, android, notes = cells
        if not ID_PATTERN.match(row_id):
            problems.append(f"{where}: bad ID '{row_id}'")
        if row_id in seen:
            problems.append(f"{where}: ID '{row_id}' also on line {seen[row_id]}")
        seen[row_id] = number
        if not feature:
            problems.append(f"{where}: '{row_id}' has no feature text")
        for app, status in (("Apple", apple), ("Android", android)):
            if status not in STATUSES:
                problems.append(f"{where}: '{row_id}' {app} status '{status}' is not one of {sorted(STATUSES)}")
            if status == "n/a" and not notes:
                problems.append(f"{where}: '{row_id}' is n/a for {app} without a reason in Notes")
        if apple != "done" and android != "done":
            problems.append(f"{where}: '{row_id}' is done in no app; remove the row or build it")
        if android == "done" and not android_code:
            problems.append(f"{where}: '{row_id}' is done for Android, but android/ has no app code")

    for row_id, source in sorted(code_ids().items()):
        if row_id not in seen:
            problems.append(f"FEATURES.md: no row '{row_id}' for {source} (add it with its Android status)")

    problems += stored_value_problems()

    if problems:
        for problem in problems:
            print(f"FAILED  {problem}", file=sys.stderr)
        print(f"PARITY FAILED ({len(problems)} problems). Rules: FEATURES.md", file=sys.stderr)
        return 1
    planned = sum(1 for _, cells in rows if "planned" in cells[2:4])
    print(f"PARITY OK: {len(rows)} features, {planned} with work planned in one app")
    return 0


if __name__ == "__main__":
    sys.exit(main())
