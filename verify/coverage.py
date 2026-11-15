"""Line coverage per package from a JaCoCo CSV report, against the floors in a file.

    python3 verify/coverage.py REPORT.csv FLOORS.txt

Prints one line per package and a last line with the packages that have a floor. Exit 1 when a
package is under its floor, or when a package with a floor is not in the report. Exit 2 when the
floors file has a line that cannot be read, a package twice, or no floor.
"""
import csv
import sys
from collections import defaultdict


def main():
    report, floors_file = sys.argv[1], sys.argv[2]
    floors = {}
    with open(floors_file, encoding="utf-8") as f:
        for number, text in enumerate(f, 1):
            line = text.split("#", 1)[0].split()
            if not line:
                continue
            try:
                if len(line) != 2 or line[0] in floors:
                    raise ValueError
                floors[line[0]] = float(line[1])
            except ValueError:
                print("%s line %d is not '<package> <percent>', once per package: %s" % (floors_file, number, text.strip()))
                return 2
    if not floors:
        print("%s names no floor" % floors_file)
        return 2
    lines = defaultdict(lambda: [0, 0])
    with open(report, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            lines[row["PACKAGE"]][0] += int(row["LINE_COVERED"])
            lines[row["PACKAGE"]][1] += int(row["LINE_MISSED"])
    under, kept = [], []
    for package in sorted(set(lines) | set(floors)):
        if package.endswith(".selftest"):
            continue
        covered, missed = lines.get(package, (0, 0))
        total = covered + missed
        share = 100.0 * covered / total if total else 0.0
        floor = floors.get(package)
        state = ""
        if floor is not None:
            if total == 0 or share < floor:
                state = "  UNDER its floor of %.0f%%" % floor
                under.append("%s %.1f%% (floor %.0f%%)" % (package, share, floor))
            else:
                state = "  (floor %.0f%%)" % floor
                kept.append("%s %.0f%%" % (package.rsplit(".", 1)[-1], share))
        print("%-20s %5.1f%% of %5d lines%s" % (package, share, total, state))
    if under:
        print("UNDER: " + "; ".join(under))
        return 1
    print("line coverage: " + ", ".join(kept))
    return 0


if __name__ == "__main__":
    sys.exit(main())
