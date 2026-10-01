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
# Step 2: the camera that does not drive the settling says what it did while the other one settled -
# either the frame it kept and how far inside the window that frame was, or the fact that it gave up.
WINDOW_IN = re.compile(r'\[SETTLE-WINDOW\] camera (\S+) frame (\d+) at \+([\d.]+) ms after the rest'
                       r' is inside the window of (\S+) which opened at \+([\d.]+) ms, margin (\d+)'
                       r' ms of (\d+) grabs')
WINDOW_OUT = re.compile(r'\[SETTLE-WINDOW\] camera (\S+) drops frame (\d+) after (\d+) grabs')
# The pre-check is the work of the ringing window, so its line has to come before the line saying which
# frame the window let through, not after it: a verdict that arrives when the head has gone is not the
# safety net stage 1 asked for. The cameras are matched by name, which from the 21:34 jar they are
# logged by on both lines; older logs quote the id on the window line and simply do not pair up.
PRE_CHECK = re.compile(r'\[PRE-CHECK\] nozzle=(\S+) camera=(\S+) .*?verdict=(\S+)')
# The settling of the camera that drives it, which is the clock the other camera aims at. Its frame
# period is not a constant of the code but of the machine, and it is what a second camera grabbing
# next door can stretch: 12.3 ms in log_m with one quick capture, 16.3-16.6 ms in log_n and log_o with
# 4-7 of them. Aiming at the window instead of hammering at it is meant to give that back.
SETTLE_FRAME = re.compile(r'autoSettleAndCapture t=(\d+) auto settle score: ([\d.]+)')
SETTLE_DONE = re.compile(r'autoSettleAndCapture in (\d+) ms')
ALIGN_START = re.compile(r'Using (?:parallel|sequential) alignment')
ALIGN_END = "align step' complete"
FINISHED = re.compile(r'Job finished. (?:It took )?([\d.]+) seconds?|Processed \d+ parts in ([\d.]+)')

# what section 1 and 3 look for, and what a healthy run has of each
PATTERNS = [
    ("parallel alignment used", "Using parallel alignment", "one per aligned batch"),
    ("sequential alignment used", "Using sequential alignment", "0 with 2 cameras and the switch on"),
    ("parallel fell back", "Parallel alignment fell back", "0 from step 2 on; step 1 had one per "
     "placement of the second camera"),
    ("frame refused as not still", "the frame is not a still frame", "0 from step 2 on; step 1 had "
     "every quick-captured frame of the second camera"),
    ("settling window hunt", "[SETTLE-WINDOW]", "one per non-settling camera per batch, from step 2"),
    ("parallel vision tally", "[PARALLEL-VISION]", "one line per job, printed at Finish"),
    ("frame dump written", "[FRAME-DUMP]", "0 unless frameDumpEnabled is on"),
    ("displacement (parallel)", "displacement (parallel)", "displacement runs only"),
    ("displacement (sequential)", "displacement (sequential)", "displacement runs only"),
    ("nozzle not at shot location", "nozzle not moved to the shot location", "displacement runs"),
    ("displacement too large", "exceeds the tolerated", "0"),
    ("post-rotate with displaced image", "uses post-rotate but the pre-captured image", "0 on a pre-rotate machine"),
    ("pipeline parameter reset", "Cannot reset the parameters of pipeline stage", "0 since a4f42fb1dd"),
    ("vision found nothing", "Bottom vision failed to find part", "0"),
]

Step = namedtuple("Step", "name t")


def alignment_batches(lines):
    """The line ranges of the alignment steps, i.e. from the move to the alignment position to the end
    of the align step. Everything the two cameras do with their settling during a placement happens in
    one of these, and the settling of anything else in the log does not belong in the comparison."""
    starts = [i for i, line in enumerate(lines) if ALIGN_START.search(line)]
    ends = [i for i, line in enumerate(lines) if ALIGN_END in line]
    batches = []
    for start in starts:
        after = [i for i in ends if i > start]
        if after:
            batches.append((start, after[0]))
    return batches


def settling(lines, batches):
    """One entry per settling that ended during an alignment step: how long it took, how many frames it needed and
    how far apart those frames were, i.e. how much of a settling was the mechanics coming to rest and
    how much was the camera being slow to hand the next one over.

    The frame gap is the number to watch when a second camera starts grabbing while this one settles:
    the frames needed go down and the gap goes up, so the total can stay the same while the shape of it
    changes. A settling that was a configured wait says nothing about frames and is left out.
    """
    runs = []
    frames = []
    for index, line in enumerate(lines):
        inside = any(start <= index <= end for start, end in batches)
        m = SETTLE_FRAME.search(line)
        if m:
            t = int(m.group(1))
            if frames and t < frames[-1]:
                frames = []  # a new settling began without the previous one ever finishing
            frames.append(t)
            continue
        m = SETTLE_DONE.search(line)
        if m and frames:
            gaps = [b-a for a, b in zip(frames, frames[1:])]
            if inside:
                runs.append((int(m.group(1)), len(frames)+1, sum(gaps)/len(gaps) if gaps else 0.0))
            frames = []
    return runs


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
              "align_steps": [], "capture": [], "tail": [], "cycles": [], "job": None,
              "window": [], "window_dropped": [], "window_at": [], "precheck": [],
              "precheck_pairs": 0, "precheck_late": 0, "settling": []}

    batches = alignment_batches(lines)
    report["settling"] = settling(lines, batches)

    for _, needle, _ in PATTERNS:
        report["counts"][needle] = text.count(needle)

    for idx, line in enumerate(lines):
        m = WINDOW_IN.search(line)
        if m:
            report["window"].append((m.group(1), int(m.group(2)), float(m.group(3)),
                                     int(m.group(6)), int(m.group(7))))
            report["window_at"].append((m.group(1), idx))
        m = WINDOW_OUT.search(line)
        if m:
            report["window_dropped"].append((m.group(1), int(m.group(2)), int(m.group(3))))
            report["window_at"].append((m.group(1), idx))
        m = PRE_CHECK.search(line)
        if m:
            report["precheck"].append((m.group(2), idx, m.group(3)))
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

    # The verdict of a camera belongs before the line saying which of its frames the window let
    # through: it is the work that was meant to fill the ringing. Cameras are matched by name and, just
    # as importantly, within one alignment step: a pre-check of the next placement is not the verdict of
    # this one, and without the batch this counts one late pre-check per batch for a design that works.
    for camera, idx in report["window_at"]:
        inside = [b for b in batches if b[0] <= idx <= b[1]]
        if not inside:
            continue
        first, last = inside[0]
        mine = [at for cam, at, _ in report["precheck"] if cam == camera and first <= at <= last]
        if mine:
            report["precheck_pairs"] += 1
            if min(mine) > idx:
                report["precheck_late"] += 1

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
    if r["window"]:
        print("  %-33s %s" % ("frames kept inside the window", " ".join(
            "%s f%d@%.0fms +%dms in %d grabs" % w for w in r["window"])))
        print("  %-33s %d ms" % ("window margin mean",
                                 sum(w[3] for w in r["window"])/len(r["window"])))
    if r["settling"]:
        print("  %-33s %s ms" % ("settling", " ".join("%.0f" % b[0] for b in r["settling"])))
        print("  %-33s %s" % ("settling frames", " ".join("%d" % b[1] for b in r["settling"])))
        print("  %-33s %.1f ms mean, %.1f ms worst" % ("settling frame gap",
              sum(b[2] for b in r["settling"])/len(r["settling"]), max(b[2] for b in r["settling"])))
    if r["precheck_pairs"]:
        print("  %-33s %d/%d    the verdict of a camera should be on its way before that camera's"
              " window line" % ("pre-checks before the window",
                                r["precheck_pairs"]-r["precheck_late"], r["precheck_pairs"]))
    if r["window_dropped"]:
        print("  !! %-31s %3d    %s" % ("frames with no window", len(r["window_dropped"]),
                                        " ".join("%s f%d of %d grabs" % d
                                                 for d in r["window_dropped"])))
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


def mean(values):
    values = list(values)
    return sum(values)/len(values) if values else 0


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
        ("frames kept inside a window", len(a["window"]), len(b["window"]), "%d"),
        ("frames with no window", len(a["window_dropped"]), len(b["window_dropped"]), "%d"),
        ("pre-checks after the window (want 0)", a["precheck_late"], b["precheck_late"], "%d"),
        ("mean grabs per window frame",
         sum(w[4] for w in a["window"]) / len(a["window"]) if a["window"] else 0,
         sum(w[4] for w in b["window"]) / len(b["window"]) if b["window"] else 0, "%.1f"),
        ("settling mean (ms)", mean(x[0] for x in a["settling"]), mean(x[0] for x in b["settling"]),
         "%.1f"),
        ("settling frames mean", mean(x[1] for x in a["settling"]), mean(x[1] for x in b["settling"]),
         "%.1f"),
        ("settling frame gap mean (ms)", mean(x[2] for x in a["settling"]),
         mean(x[2] for x in b["settling"]), "%.1f"),
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
