#!/usr/bin/env python3
"""Check a parallel bottom vision run against MACHINE-TEST.md.

    ./check_run.py tests_2026-09-28/log_a.txt tests_2026-09-28/log_c.txt

Prints, per log, the counts and numbers that sections 1, 2 and 3 of
docs/design/parallel-bottom-vision/MACHINE-TEST.md ask for, plus the [ALIGN-TIMING] breakdown, and
then the same numbers side by side, so that two runs can be compared without a spreadsheet. It only
reads logs: nothing here decides a pass, but everything a pass is decided on is on the screen.
"""

import re
import sys
import datetime
from collections import namedtuple

TS = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) ')
OFFSET = re.compile(r'Offsets accepted \(([-\d.]+), ([-\d.]+), ([-\d.]+), ([-\d.]+) mm\)')
TOO_LARGE = re.compile(r'Offsets too large \(([-\d.]+), ([-\d.]+), ([-\d.]+), ([-\d.]+) mm\) :'
                       r' center offset ([-\d.]+) > ([-\d.]+)')
TIMING = re.compile(r'\[ALIGN-TIMING\] part (\S+) camera (\S+) prepare ([\d.]+) ms process ([\d.]+) ms'
                    r'(?: stages \[([^\]]*)\])?')
# The pipeline of this machine captures inside itself, as the stage named "0". A sequential shot pays its
# camera settle there, so process() without that stage is the number to compare across runs.
CAPTURE_STAGE = "0"
FINISHED = re.compile(r'Job finished. (?:It took )?([\d.]+) seconds?|Processed \d+ parts in ([\d.]+)')

# what section 1 and 3 look for, and what a healthy run has of each
PATTERNS = [
    ("parallel alignment used", "Using parallel alignment", "one per aligned batch"),
    ("sequential alignment used", "Using sequential alignment", "0 with 2 cameras and the switch on"),
    ("parallel fell back", "Parallel alignment failed", "0"),
    ("displacement (parallel)", "displacement (parallel)", "displacement runs only"),
    ("displacement (sequential)", "displacement (sequential)", "displacement runs only"),
    ("nozzle not at shot location", "nozzle not moved to the shot location", "displacement runs"),
    ("displacement too large", "exceeds the tolerated", "0"),
    ("post-rotate with displaced image", "uses post-rotate but the pre-captured image", "0 on a pre-rotate machine"),
    ("pipeline parameter reset", "Cannot reset the parameters of pipeline stage", "0 since a4f42fb1dd"),
    ("vision found nothing", "Bottom vision failed to find part", "0"),
]

Step = namedtuple("Step", "name t")


def timestamps(lines):
    out = []
    for line in lines:
        m = TS.match(line)
        out.append(datetime.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None)
    return out


def analyse(path):
    with open(path, errors="replace") as f:
        lines = f.read().split("\n")
    ts = timestamps(lines)
    text = "\n".join(lines)
    report = {"path": path, "counts": {}, "offsets": [], "too_large": [], "timing": [],
              "align_steps": [], "capture": [], "tail": [], "cycles": [], "job": None}

    for _, needle, _ in PATTERNS:
        report["counts"][needle] = text.count(needle)

    for line in lines:
        m = OFFSET.search(line)
        if m:
            x, y = float(m.group(1)), float(m.group(2))
            report["offsets"].append((abs(x), abs(y), abs(float(m.group(4)))))
        m = TOO_LARGE.search(line)
        if m:
            report["too_large"].append(float(m.group(5)))
        m = TIMING.search(line)
        if m:
            stages = {}
            if m.group(5):
                for part in m.group(5).split(", "):
                    if "=" in part:
                        name, _, value = part.rpartition("=")
                        try:
                            stages[name] = float(value)
                        except ValueError:
                            pass
            report["timing"].append((m.group(2), float(m.group(3)), float(m.group(4)),
                                     stages.get(CAPTURE_STAGE, 0.0)))

    for line in lines:
        if "Job finished" in line or "parts in" in line:
            m = re.search(r"in ([\d.]+) sec", line)
            if m:
                report["job"] = float(m.group(1))

    # alignment steps: from the move to the alignment position to the last PartAlignment.After,
    # with the capture window and the pipeline tail in between
    anchors = [i for i, l in enumerate(lines)
               if l and "Moving head to alignment position" in l]
    for i in anchors:
        moved = ts[i]
        j = i
        while j < len(lines) and not (lines[j] and "Camera batch operation ended" in lines[j]):
            j += 1
        batch_end = ts[j] if j < len(lines) and ts[j] else None
        k = j
        last_after = None
        while k < len(lines) and ts[k] and (ts[k] - moved).total_seconds() < 1.0:
            if lines[k] and "Vision.PartAlignment.After" in lines[k]:
                last_after = ts[k]
            k += 1
        if last_after is None:
            continue
        report["align_steps"].append((last_after - moved).total_seconds() * 1000)
        if batch_end:
            report["capture"].append((batch_end - moved).total_seconds() * 1000)
            report["tail"].append((last_after - batch_end).total_seconds() * 1000)

    if len(anchors) > 1:
        for a, b in zip(anchors, anchors[1:]):
            report["cycles"].append((ts[b] - ts[a]).total_seconds())

    return report


def ms(values):
    return " ".join("%.0f" % v for v in values) if values else "-"


def show(r):
    print("=" * 78)
    print(r["path"])
    print("=" * 78)
    for label, needle, expectation in PATTERNS:
        n = r["counts"][needle]
        if n or "0" not in expectation:
            print("  %-33s %3d    %s" % (label, n, expectation))
    if r["offsets"]:
        print("  %-33s %3d    accepted" % ("offsets accepted", len(r["offsets"])))
        print("  %-33s %.3f mm / %.2f deg"
              % ("largest offset of the run", max(max(a, b) for a, b, _ in r["offsets"]),
                 max(c for _, _, c in r["offsets"])))
    if r["too_large"]:
        print("  !! %-31s %3d    worst center offset %.2f mm over %.2f mm"
              % ("offsets rejected", len(r["too_large"]), max(r["too_large"]), r["too_large"][0]))
    if not r["timing"]:
        print("  %-33s ---    this log predates the [ALIGN-TIMING] line, so only the"
              " windows below are measurable here" % "process per shot")
    if r["timing"]:
        processes = [p for _, _, p, _ in r["timing"]]
        print("  %-33s %.1f-%.1f ms, sum %.0f ms"
              % ("process per shot", min(processes), max(processes), sum(processes)))
        print("  %-33s %.1f-%.1f ms, sum %.0f ms"
              % ("of which the capture stage",
                 min(c for _, _, _, c in r["timing"]), max(c for _, _, _, c in r["timing"]),
                 sum(c for _, _, _, c in r["timing"])))
        print("  %-33s %.0f ms" % ("analysis without the capture", sum(processes) - sum(
            c for _, _, _, c in r["timing"])))
        print("  %-33s %.1f-%.1f ms" % ("prepare per shot",
                                        min(p for _, p, _, _ in r["timing"]),
                                        max(p for _, p, _, _ in r["timing"])))
        for camera, prepare, process, capture in r["timing"]:
            print("       %-22s prepare %5.1f  process %6.1f  capture %5.1f"
                  % (camera, prepare, process, capture))
    print("  %-33s %s ms" % ("alignment step (move->after)", ms(r["align_steps"])))
    print("  %-33s %s ms" % ("  of which move+capture", ms(r["capture"])))
    print("  %-33s %s ms" % ("  of which pipeline tail", ms(r["tail"])))
    print("  %-33s %s s" % ("alignment to alignment", " ".join("%.2f" % c for c in r["cycles"]) or "-"))
    if r["job"]:
        print("  %-33s %.1f s" % ("job", r["job"]))


def compare(a, b):
    print("=" * 78)
    print("%s  ->  %s" % (a["path"], b["path"]))
    print("=" * 78)
    rows = [
        ("offsets accepted", len(a["offsets"]), len(b["offsets"]), "%d"),
        ("largest offset (mm)", max((max(x, y) for x, y, _ in a["offsets"]), default=0),
         max((max(x, y) for x, y, _ in b["offsets"]), default=0), "%.3f", "%+.3f"),
        ("alignment phase sum, move+capture+pipeline (ms)",
         sum(a["capture"]) + sum(a["tail"]), sum(b["capture"]) + sum(b["tail"]), "%.0f"),
        ("alignment step mean (ms)",
         sum(a["align_steps"]) / len(a["align_steps"]) if a["align_steps"] else 0,
         sum(b["align_steps"]) / len(b["align_steps"]) if b["align_steps"] else 0, "%.0f"),
        ("pipeline tail mean (ms)",
         sum(a["tail"]) / len(a["tail"]) if a["tail"] else 0,
         sum(b["tail"]) / len(b["tail"]) if b["tail"] else 0, "%.0f"),
        ("cycle mean (s)", sum(a["cycles"]) / len(a["cycles"]) if a["cycles"] else 0,
         sum(b["cycles"]) / len(b["cycles"]) if b["cycles"] else 0, "%.2f"),
        ("job (s)", a["job"] or 0, b["job"] or 0, "%.1f"),
    ]
    if a["timing"] and b["timing"]:
        rows.append(("process sum of the shots (ms)", sum(p for _, _, p, _ in a["timing"]),
                     sum(p for _, _, p, _ in b["timing"]), "%.0f"))
        rows.append(("  of which capture (ms)", sum(c for _, _, _, c in a["timing"]),
                     sum(c for _, _, _, c in b["timing"]), "%.0f"))
    for row in rows:
        label, x, y, fmt = row[:4]
        dfmt = row[4] if len(row) > 4 else "%+.0f"
        print("  %-28s %s -> %s   (%s)" % (label, fmt % x, fmt % y, dfmt % (y - x)))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    reports = [analyse(p) for p in argv[1:]]
    for r in reports:
        show(r)
    if len(reports) == 2:
        compare(reports[0], reports[1])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
