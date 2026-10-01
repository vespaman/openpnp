#!/usr/bin/env python3
"""
What the frames themselves say, as against what the log says was measured on them.

    check_frames.py <dump directory> <log of the same session>

The dump is the one FrameDump writes when OpenPnP is started with -DframeDump=<directory>: a PNG per
frame an alignment kept *and* per frame the pre-check judged, with the whole provenance of each in its
name and nothing in a side file that can get separated from it. An alignment therefore leaves two frames
where the hunt took more than one grab: the first frame, which is the ringing and what the pre-check
answered for, and the last, which is what was measured. The role column says which is which. Two things are read out of it here, and they are of very different kinds.

The first is the provenance, checked against the log: the age of the frame after the head came to rest,
the instant its still window opened, how many grabs the hunt took, and that the frames on disk are
exactly the frames the log speaks of - not one more, not one fewer, not the same image written twice.
Those numbers are the claim the whole parallel path rests on, and until a frame is on disk with them
attached, nobody outside the machine can look at them at all. These checks fail the run.

The second is the picture, and it is deliberately not a measurement of the part. A naive reader - Otsu
inside the lit circle - finds the bore of the nozzle and the shape of the illumination, not a 0402, which
is precisely why the machine's own colour pipeline is what measures parts and why a replay of a frame has
to run that pipeline and not a substitute. What the pixel columns are good for is the negative result:
they show a frame is not blank, where the light is, and how much one frame of a camera differs from the
next. The measurement of the part on these pixels is the replay's job, and it needs the machine's config
alongside the frames (see the end of MACHINE-TEST.md).
"""

import cv2
import glob
import hashlib
import numpy as np
import os
import re
import sys

# Mirrors org.openpnp.vision.FrameDump.fileName(). The letters on the numbers are not decoration: a name
# whose fields have been shuffled would otherwise read as a frame that is perfectly well described and
# completely wrong, which is worse than one that is refused.
NAME = re.compile(
    r"^bv__(?P<origin>[A-Za-z]+)"
    r"__(?P<camera>[^_]+)"
    r"__(?P<nozzle>[^_]+)"
    r"__(?P<part>[^_]+)"
    r"__f(?P<frame>[0-9]+|na)"
    r"__a(?P<arrival>[+-~][0-9.]+|na)"
    r"__c(?P<captured>[+-~][0-9.]+|na)"
    r"__w(?P<window>[+-~][0-9.]+|na)"
    r"__r(?P<rotation>[+-~][0-9.]+|na)\.png$"
)

PRE_CHECK = re.compile(
    r"\[(PRE-CHECK-LATE|PRE-CHECK)\] nozzle=(\S+) camera=(\S+) part=(\S+) frame=(\S+)"
    r".*?size=([0-9.]+)x([0-9.]+).*?rot=(-?[0-9.]+|\?) offset=([0-9.]+|\?)"
    r".*?verdict=(\w+)"
)

# How far apart the two readings of the same thing may be before it is worth a look. The naive pixel
# reading is not a measurement, so this is only a shout if a frame is blank, or is another frame.
BLANK_LEVELS = 1.0
SAME_IMAGE_LEVELS = 0.05


def value(text):
    return None if text == "na" else float(text.replace("~", "-"))


def read_dump(directory):
    frames, rejected = [], []
    for path in sorted(glob.glob(os.path.join(directory, "*.png"))):
        name = os.path.basename(path)
        match = NAME.match(name)
        if not match:
            # A name that does not read is not a frame with unknown provenance, it is a frame whose
            # provenance would have to be guessed at.
            rejected.append(name)
            continue
        frame = match.groupdict()
        frame["path"] = path
        frame["file"] = name
        for key in ("frame", "arrival", "captured", "window", "rotation"):
            frame[key] = value(frame[key])
        frames.append(frame)
    return frames, rejected


def describe(path):
    """
    Where the light is and how much of the frame is anything: the lit circle, and the darkest thing
    inside it. Not the part - see the note above - but a frame whose circle is missing or whose inside is
    uniform is a frame of nothing, and that is worth knowing before anything is concluded from it.
    """
    gray = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if gray is None:
        return None
    with open(path, "rb") as handle:
        digest = hashlib.md5(handle.read()).hexdigest()[:12]
    _, bright = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(bright, 8)
    if count < 2:
        return None
    widest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    disc = np.where(labels == widest, 255, 0).astype(np.uint8)
    found = cv2.findContours(disc, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    (center_x, center_y), radius = cv2.minEnclosingCircle(max(found[0], key=cv2.contourArea))
    inside = np.zeros_like(gray)
    cv2.circle(inside, (int(center_x), int(center_y)), int(0.85*radius), 255, -1)
    masked = cv2.bitwise_and(gray, inside)
    _, dark = cv2.threshold(masked, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    dark = cv2.bitwise_and(dark, inside)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(dark, 8)
    darkest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA])) if count > 1 else -1
    return {
        "digest": digest,
        "gray": gray,
        "radius": radius,
        "light_center": (center_x, center_y),
        "dark_area": stats[darkest, cv2.CC_STAT_AREA] if darkest > 0 else 0,
        "levels": float(np.std(gray[inside > 0])),
    }


def read_log(path):
    pre_checks, windows = [], []
    with open(path, "r", errors="replace") as handle:
        for line in handle:
            match = PRE_CHECK.search(line)
            if match and match.group(10) != "Pending":
                pre_checks.append({
                    "late": match.group(1) == "PRE-CHECK-LATE",
                    "nozzle": match.group(2),
                    "camera": match.group(3),
                    "part": match.group(4),
                    "frame": match.group(5),
                    "width": float(match.group(6)),
                    "height": float(match.group(7)),
                    "rotation": match.group(8),
                    "offset": match.group(9),
                })
    return pre_checks


def batches(log):
    """
    The log carries no batch number on these lines, so a batch is what there is: the pre-checks of one
    alignment step are one per nozzle, and the next time a nozzle speaks the previous step is over.
    """
    groups, current, seen = [], {}, set()
    for entry in log:
        if entry["nozzle"] in seen:
            groups.append(current)
            current, seen = {}, set()
        current[entry["nozzle"]] = entry
        seen.add(entry["nozzle"])
    if current:
        groups.append(current)
    return groups


def grabs(log_entry):
    """
    How many grabs the hunt took and which of them the pre-check judged, as the pre-check line says.
    Either can be unknown: a camera grabbing inside somebody else's window answers before the hunt is
    over, and the count only fills in once it is.
    """
    if not log_entry:
        return None, None
    first, _, last = log_entry["frame"].partition("/")

    def number(text):
        try:
            return int(text)
        except (TypeError, ValueError):
            return None

    return number(last), number(first)


def role_of(frame, counted, checked, group):
    """
    Which frame of an alignment this is: the one the pre-check judged, the one the hunt kept and the
    alignment was measured on, or a frame no line of the log speaks of. Where the hunt took a single
    frame the first two are the same frame, and the count stays unknown.
    """
    if frame["frame"] is None:
        return "?"
    if counted is not None:
        if frame["frame"] == counted:
            return "kept"
        if checked is not None and frame["frame"] == checked:
            return "pre-check"
        return "unclaimed"
    highest = max((other["frame"] or 0) for other in group if other["nozzle"] == frame["nozzle"])
    if frame["frame"] == highest:
        return "kept"
    if checked is not None and frame["frame"] == checked:
        return "pre-check"
    return "unclaimed"


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip())
        return 2
    frames, rejected = read_dump(sys.argv[1])
    if not frames:
        print("no frames readable as FrameDump names in %s" % sys.argv[1])
        return 1
    groups = batches(read_log(sys.argv[2]))

    print("frames on disk      %d%s" % (len(frames), "" if not rejected else
          "  (%d file(s) refused as not ours: %s)" % (len(rejected), ", ".join(rejected))))
    print("batches in the log  %d" % len(groups))
    print()
    print("batch  nozzle role       camera                origin    grabs    age    window  margin   "
          "light r    dark px   md5        log size mm    rot    offset")

    by_arrival = {}
    for frame in frames:
        by_arrival.setdefault(frame["arrival"], []).append(frame)
    ordered = sorted(by_arrival.items())

    failures = []
    notes = []
    digests = {}
    previous = {}
    kept_of = set()
    judged_of = set()
    for index, (arrival, group) in enumerate(ordered):
        log_group = groups[index] if index < len(groups) else {}
        for frame in sorted(group, key=lambda f: (f["nozzle"], f["frame"] or 0)):
            seen = describe(frame["path"])
            log_entry = log_group.get(frame["nozzle"])
            age = frame["captured"] - frame["arrival"]
            margin = frame["captured"] - frame["window"]
            if seen is None:
                failures.append("%s: cannot be read as an image" % frame["file"][:70])
                continue
            counted, checked = grabs(log_entry)
            role = role_of(frame, counted, checked, group)
            # The size, rotation and offset in the log are the pre-check's reading of the frame it was
            # given, so they belong on that frame's row and on no other. A dump older than the pre-check's
            # frame having been written down has no such row, and says so below rather than putting the
            # numbers somewhere they were not measured.
            judged = checked is not None and frame["frame"] == checked
            quotes = log_entry if judged else None
            if judged:
                judged_of.add((index + 1, frame["nozzle"]))
            print("%5d  %-6s %-9s %-22s %-9s %5s  %5.0f  %6.0f  %6.0f   %6.0f  %7.0f  %s   "
                  "%5.2fx%-5.2f %6s %7s"
                  % (index + 1, frame["nozzle"], role, frame["camera"][:22], frame["origin"],
                     "f%d" % frame["frame"] if frame["frame"] is not None else "-",
                     age, frame["window"] - frame["arrival"], margin,
                     seen["radius"], seen["dark_area"], seen["digest"],
                     quotes["width"] if quotes else float("nan"),
                     quotes["height"] if quotes else float("nan"),
                     quotes["rotation"] if quotes else "-",
                     quotes["offset"] if quotes else "-"))

            if role == "kept":
                kept_of.add((index + 1, frame["nozzle"]))
                # Only the kept frame promises to be inside the still window. The pre-check's frame is
                # taken before it opens, which is the whole reason the pre-check may not judge position.
                if frame["origin"] != "QuickCapture" and margin < 0:
                    failures.append("%s: the frame the hunt kept is %.0f ms outside the window it claims "
                                    "to be inside" % (frame["file"][:70], -margin))
            if role == "unclaimed":
                failures.append("%s: a frame numbered %d, which neither the hunt's grab count nor the "
                                "pre-check accounts for" % (frame["file"][:70], frame["frame"]))
            if seen["levels"] < BLANK_LEVELS:
                failures.append("%s: the frame is uniform, nothing was captured"
                                % frame["file"][:70])
            digests.setdefault(seen["digest"], []).append(frame["file"])

            # Two frames of one camera have to be two frames. A dump that wrote the same image twice
            # would look like a settling that held perfectly still, and since the ringing frame and the
            # kept frame of one alignment are now both here, this is the drift showing or not showing.
            earlier = previous.get(frame["camera"])
            if earlier is not None:
                difference = float(np.mean(cv2.absdiff(earlier["gray"], seen["gray"])))
                if difference < SAME_IMAGE_LEVELS:
                    failures.append("%s and the frame before it from the same camera are the same image"
                                    % frame["file"][:70])
            previous[frame["camera"]] = seen

    print()
    for index, (arrival, group) in enumerate(ordered):
        for nozzle in sorted({frame["nozzle"] for frame in group}):
            if (index + 1, nozzle) not in judged_of:
                notes.append("batch %d %s: the frame the pre-check judged is not in this dump, so the "
                             "size and rotation columns are empty for it (run 2026-10-01 is like this; "
                             "a dump written since FrameDump was told to keep that frame is not)"
                             % (index + 1, nozzle))
    for note in notes:
        print("note: " + note)
    if notes:
        print()
    for index, (arrival, group) in enumerate(ordered):
        for nozzle in sorted({frame["nozzle"] for frame in group}):
            if (index + 1, nozzle) not in kept_of:
                failures.append("batch %d %s: nothing on disk is the frame that hunt kept"
                                % (index + 1, nozzle))
    for digest, files in digests.items():
        if len(files) > 1:
            failures.append("the same image was written %d times: %s" % (len(files), files[0][:70]))
    if len(groups) != len(ordered):
        failures.append("%d batches in the log and %d in the dump; the frames and the log are not the "
                        "same run" % (len(groups), len(ordered)))

    print("the pixel columns are not a measurement of the part: Otsu inside the lit circle finds the "
          "bore of\nnozzle and the shape of the light, which is why the replay has to run the machine's "
          "own pipeline\nover these frames, and why that needs machine.xml and packages.xml next to them.")
    print()
    if failures:
        print("what does not add up:")
        for failure in failures:
            print("  - " + failure)
        return 1
    print("every frame on disk is one the log accounts for, every hunt's kept frame is there and inside "
          "its window, and no frame is another frame twice.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
