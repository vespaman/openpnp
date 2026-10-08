#!/usr/bin/env python3
"""Measure the true pixel size of every camera from a displacement check run.

The run's `pixelScaleProbeMm` option parks the tip in front of its own camera and then commands
the head by a known number of machine millimetres, once in each direction on each axis, saving a
picture every time. A commanded move is millimetres of the machine, so comparing the pictures
says how many pixels a millimetre is on that camera, without asking the camera's own calibration.
Two cameras whose pixel sizes disagree put a systematic error into every crossed shot, because the
offset the tip is measured at is converted to millimetres with the camera's own scaling.

Usage: analyze_probe.py <run directory>

Needs OpenCV. The pictures must have been taken with `saveImages` on.
"""

import glob
import os
import re
import sys

import cv2
import numpy as np

NAME = re.compile(r"probe-(.+?)-(.+?)-(z[+-][0-9.]+)-([xyp][0mpm])"
                  r"-x([+-][0-9.]+)-y([+-][0-9.]+)\.png$")


def shift(a, b, half=200):
    """Sub pixel shift of b relative to a, over a window centred on the optical centre."""
    h, w = a.shape
    cx, cy = w // 2, h // 2
    a = a[cy - half:cy + half, cx - half:cx + half].astype(np.float32)
    b = b[cy - half:cy + half, cx - half:cx + half].astype(np.float32)
    window = cv2.createHanningWindow((a.shape[1], a.shape[0]), cv2.CV_32F)
    (sx, sy), response = cv2.phaseCorrelate(a, b, window)
    return sx, sy, response


def main(directory):
    shots = {}
    # A run directory keeps its pictures in images/, but a directory of pictures copied out of one
    # is just as useful to look at.
    pictures = glob.glob(os.path.join(directory, "images", "probe-*.png"))
    if not pictures:
        pictures = glob.glob(os.path.join(directory, "probe-*.png"))
    for path in sorted(pictures):
        match = NAME.search(os.path.basename(path))
        if not match:
            print("unparsed probe picture: %s" % path)
            continue
        nozzle, camera, z, tag, x, y = match.groups()
        shots.setdefault((camera, z), {}).setdefault("frames", {})[tag] = (
                path, float(x), float(y))

    print("%-8s %-8s %-9s %-9s %-9s %-9s" % ("camera", "z", "px X", "px Y", "mm/px", "usable"))
    for (camera, z), probe in sorted(shots.items()):
        frames = probe["frames"]
        if "x0" not in frames:
            continue
        base, bx, by = frames["x0"]
        base_image = cv2.imread(base, cv2.IMREAD_GRAYSCALE)
        h, w = base_image.shape
        moves = []
        for tag in ("xp", "xm", "yp", "ym"):
            if tag not in frames:
                continue
            path, x, y = frames[tag]
            # The commanded distance comes out of the file name, so a probe taken with another
            # pixelScaleProbeMm is still read correctly.
            distance = max(abs(x - bx), abs(y - by))
            if distance <= 0:
                continue
            sx, sy, response = shift(base_image, cv2.imread(path, cv2.IMREAD_GRAYSCALE))
            moves.append((tag, distance, float(np.hypot(sx, sy)) / distance, response))
        if not moves:
            continue
        # A rotated camera shows a machine X move along its own Y, so the axis of the shift says
        # nothing useful; its length does.
        px = [m[2] for m in moves]
        mean = float(np.mean(px))
        print("%-8s %-8s %-9.3f %-9.3f %-9.6f %s" % (
            camera, z, np.mean([m[2] for m in moves if m[0].startswith("x")]),
            np.mean([m[2] for m in moves if m[0].startswith("y")]),
            1.0 / mean if mean else 0.0,
            "yes" if max(px) - min(px) < 0.1 * mean else
            "no, the frames disagree by %.0f%%" % (100 * (max(px) - min(px)) / mean)))
        for tag, distance, per_mm, response in moves:
            print("         %s over %.3fmm: %.3f px/mm (correlation %.2f)" % (
                tag, distance, per_mm, response))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
