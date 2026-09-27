# Automatic displacement verification - assessment

Status: **assessment only**, nothing here is designed in detail or implemented. Written
2026-09-27 from the question: *can the machine measure its own displacement data, so that checking
it does not mean re-running the camera tilt calibration, the nozzle calibration and the nozzle tip
runout calibration by hand - and ideally while the cameras are still being adjusted?*

Short answer: yes, mostly, and the part of it that pays for itself needs no precision artifact and
no change to the bottom vision code. The measurements are differential: the same thing is measured
two different ways and has to agree, and *how* it disagrees names the cause.

## 1. What "the displacement" is

For every (nozzle, up-looking bottom camera) pair the machine needs to know where the camera's
image is, in machine coordinates, at part height, and how it is rotated. The MBV/PBV shortcut
trusts that: it photographs a part that is not exactly under the camera and computes the nozzle
position from where the part appears in the image.

Error sources, and what the current compensation models:

| # | Source | Compensated today? |
|---|--------|--------------------|
| 1 | camera XY mounting error (where it is vs where the calibration says) | yes, by camera calibration |
| 2 | camera tilt: optical axis not perpendicular to the axis of Z travel | only at the calibrated Z; grows with Z error and with distance from image centre |
| 3 | pixel size (units/pixel) error | only at the calibrated Z; scales with distance from centre |
| 4 | camera roll about its optical axis | yes, by the camera rotation calibration |
| 5 | nozzle tip / part runout, C-axis wobble | yes, by nozzle tip runout calibration |
| 6 | nozzle tilt (tip axis not parallel to Z) | no model at all |
| 7 | part height / balance level error | feeds 2 and 3 |
| 8 | machine squareness, backlash, belt stretch over the distance between shot position and placement position | no |

The compensation itself is a translation plus a rotation between camera location and nozzle
location. That is exactly right for 1 and 4 and a decent approximation for 5, and it is structurally
unable to model 2, 3, 6 - which is why checking it is worth something.

## 2. Method: differential, so no reference artifact

The machine does not need to know absolute truth. It needs to find out whether measuring the same
thing along two different paths gives the same answer. Every experiment below is one nozzle, one
part, and a comparison.

| Exp | What | What a non-zero result means |
|-----|------|------------------------------|
| E1 pair offset | Known part on the nozzle, photographed at its nominal shot location (zero displacement). Residual between measured and expected. | 1, 4 - the pair calibration is off |
| E2 displacement sweep | E1 repeated with the nozzle held displaced by 0, ±0.2, ±0.4, ±0.6 mm in X and Y. | residual constant → the displacement compensation is right; residual linear in displacement → 3 (or a wrong camera-to-nozzle distance); asymmetric/quadratic → 2 |
| E3 Z sweep | E1 at balance level and at ±0.5, ±1 mm. | drift with Z is 2 combined with 3. The slope d(residual)/dZ is essentially tan(tilt angle): it says how much the camera is tilted, and in which direction, i.e. what to shim |
| E4 C sweep | Same part, C at 12 angles over 360°, part centre recorded. Fit a circle. | runout amplitude and phase (5); compare with the stored runout calibration. A component that rotates with the part instead of the tip → 6, needs a part tall enough to see it (a 0603 will not show it, a SOIC will) |
| E5 camera to camera | A fixed target on the bed, photographed by every bottom camera in turn while the head moves. | camera-to-camera registration and 8, with no nozzle involved |
| E6 roll | A target with a known straight edge, seen by each camera. | 4 |

E1+E2+E4 answer the question "is the displacement I am about to trust right now, right". E3 is the
one that helps while a camera is still loose in its mount. All of them are loops over existing
primitives; none of them needs a new kind of motion.

## 3. What already exists and should be reused

Nothing fundamental has to be written; the pieces are:

- `ReferenceBottomVision.findOffsets(part, ..., boolean skipMovement)` measures a part without
  moving the nozzle, and the parallel work added `captureImageOnly` /
  `captureImageOnlyWithSettle`, which return an image on demand. The measurement half exists.
- `VisionUtils.getBottomVisionCamera(nozzle)` gives the pairs; `PreCapturedPlacement` already carries
  the per-shot displacement delta that E2 wants to vary.
- `ReferenceNozzleTipCalibration` already does an E4-like sweep against a bottom camera and fits
  runout; E4 is largely that machinery pointed at a report instead of at a calibration write.
- `org.openpnp.machine.reference.camera.calibration.AdvancedCalibration` already runs a test-pattern
  calibration with `Calib3d.calibrateCamera`, i.e. tilt/perspective and intrinsics are already
  modelled for cameras that are calibrated that way. E2/E3 should read what that produced and report
  residuals, not invent a second bit of maths next to it.
- The Issues & Solutions panel (`Solutions.Subject`, `Solutions.Issue`, and `ActionProperty` for a
  button) is the existing place for "this is off, click to check/fix", including showing a check as
  stale since the last run.
- The scripting panel sees the whole SPI, so the first version needs no compiled code at all.

## 4. Shape of the module, additive by construction

**v0 - a script, zero core changes.** Loop nozzles × cameras, run E1, E4 and a short E2, print a
table, write a CSV next to the configuration. One file, no new UI, no calibration touched. Wrong in
details until it has been run once, but it settles the design questions with real numbers.

**v1 - a report-only module.** New class, e.g.
`org.openpnp.machine.reference.vision.DisplacementCheck`, plus one action reachable from Issues &
Solutions. Runs as a machine action, so it is cancelable and refused while a job runs. Per pair it
reports: dx, dy, dz, dθ, runout amplitude/phase, residual-vs-displacement slope, d(residual)/dZ, and
a verdict against `displacement-max-distance`, `-z-diff`, `-rotation`. CSV and log. It writes no
calibration value. This is the "verify it every now and then" deliverable.

**v2 - adjust assist.** For one camera at a time, static target, no head movement, looped at camera
frame rate: measured centre vs expected centre, in mm, with direction, plus the tilt figure from the
Z slope. That is the "help me while I move the camera" tool. It is a small non-modal window; it can
start life as the ordinary camera view plus a script printing to the console, which is nearly free
and already usable.

**v3, only after v1 has proved itself.** Offer to write the measured values back, and treat a drift
since the last check as an issue in the panel. Until then, a human decides.

Effort, honestly: v0 can be written here in one sitting but is worthless until it runs once on the
machine. v1 is about a day of coding plus a couple of hours at the machine to make the numbers
sane. v2 is a further day with iteration. The maths is not the cost; the cost is settling what the
target is and what to do about vibration, lighting and part-to-part variation.

## 5. What has to be decided first

1. **The target.** What can sit repeatably on the bed, or be picked reliably? In increasing
   usefulness: the bare nozzle tip (centre only, no rotation); a part with a trustworthy package
   size; a printed checkerboard/ChArUco (needed for a real tilt/intrinsic answer); a machined pin or
   gauge block if one exists. E1/E2/E4 work with the first two. E3 wants a part of some height or a
   board. E5/E6 want something fixed.
2. **Machine facts.** How many nozzles and bottom cameras and which pairs; how repeatable the axes
   are over a 50 mm move; whether it is homed and left homed. Every measurement below is limited by
   that number, so it has to be known, not assumed.
3. **Report only, or eventually write back** (v3).
4. **Pre-rotate or post-rotate** on the parts in use. Post-rotate is the case where the current code
   does not compensate the displacement at all, and a check is the only way to know how much that
   costs in practice.
5. Whether a check should be runnable unattended (overnight self-test) or is something you start by
   hand.

## 6. Limits of the method, stated plainly

- Differential checks find inconsistency, not truth. A calibration that is wrong in a
  self-consistent way (pixel size and camera distance both wrong in the same direction) can pass
  every check above and still place wrong. Only a real artifact, or measuring a placed board, finds
  that.
- Everything is bounded by machine repeatability and by settling after each move. Each measurement
  therefore has to be taken N times, reported as median **and spread**, and the check must refuse to
  claim an error smaller than the spread it measures.
- Part-to-part size variation inside one package pollutes E1/E2 unless the *same physical part* is
  used throughout.
- Auto-exposure or a lighting change moves the outline. Run it with the production lighting and
  auto-exposure off.
- Tilt cannot be separated from pixel size error from a single height. Two heights minimum (E3), or
  a checkerboard.
- Nozzle tilt needs a part tall enough for the tilt to rise above the noise.
