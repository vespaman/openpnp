# Automatic displacement verification - assessment

Status: **assessment**, nothing implemented. Written 2026-09-27, rewritten the same day after the
machine concept in `machine.md` was taken into account. Question: can the machine determine and
re-verify its own displacement data, so that checking it does not mean re-doing the camera
alignment (tilt), the nozzle and the nozzle tip runout calibration by hand - and ideally while the
camera is still being moved?

Short answer: yes. The head is the artifact. One head, nozzles at fixed positions, sharing X/Y;
their relative position is known from the earlier setup. That is a rigid, machine-mounted
measurement standard, and bottom vision can see two of its nozzles at the same time. Everything
below follows from that.

## 1. Where the displacement actually lives in the code

The shot location for a nozzle under an up-looking camera is built up like this:

```
camera.getLocation(nozzle)                      AbstractCamera:178
  = camera's own machine location
    - nozzle.getCameraToolCalibratedOffset(camera)
        = runout compensation camera offset     ReferenceNozzleTipCalibration:922
          (only for the camera paired to this nozzle, zero otherwise)
  + part height ...                             ReferenceBottomVision.getCameraLocationAtPartHeight()
```

and then, during analysis, the parallel path subtracts the per-shot
`PreCapturedPlacement.intendedDisplacementDelta` (`ReferenceBottomVision.java:271-278`) - the
distance the head happened to be off the ideal shot location, runout included.

Two consequences:

- There is **no stored per-camera displacement X/Y** in this branch. What `machine.md` describes
  (the small difference between camera spacing and nozzle spacing, kept per camera, reference camera
  zero) is spread over the camera's machine-coordinate calibration, the runout compensation offset,
  and the per-shot runtime delta. `multi-bottom-vision` had a runtime `Camera.setDisplacements(x, y)`;
  the parallel implementation replaced it with the per-shot value. So "write the measured displacement
  back" has no field to write to today - see §5.
- Everything pixel-to-machine goes through the **advanced camera calibration**
  (`reference/camera/calibration/AdvancedCalibration`), which keeps a virtual camera matrix, a
  rectification matrix, a physical camera position in the machine frame
  (`vectorFromMachToPhyCamInMachRefFrame`), the **optical axis direction in the machine frame**
  (`unitVectorPhyCamZInMachRefFrame` - the tilt, already measured and stored), and a principal point
  offset. That is the correction the user has in mind, and it is why turning a camera screw by hand
  is so awkward: it changes the physical camera position, while the calibration still applies the
  tilt/rectification of the previous position, and the apparent XY of anything not at the calibrated
  Z shifts as a result.

## 2. The reference is the head

Two 0402s (or any part that picks and outlines reliably), one on each nozzle, head at a position
where both are inside their cameras' fields of view, both cameras shooting - which is exactly what
the parallel capture already does. Both part centres measured, each converted to machine coordinates
by its own camera calibration:

- the vector between them must equal the known inter-nozzle vector;
- the **residual per camera is that camera's displacement error**, directly.

Nothing else is needed for the basic check, and it is a strong measurement:

- Both shots are of the same rigid head, at the same instant. Axis repeatability, backlash,
  squareness and belt stretch do not enter at all - the inter-nozzle distance is fixed by mechanics,
  not by the axes.
- Both nozzles are at the same Z (balance level), so a Z error affects both the same way and largely
  cancels in the vector.
- A 0402 is fine as a target: the vector is between two part centres, so the part's own size error
  mostly cancels, and its outline is what bottom vision is already good at. The bare nozzle tip
  works too but gives no rotation, so a part is better.

One measurement gives the displacement. Repeating it while moving the head gives the decomposition
that makes the result actionable:

| Vary | Keep | A residual that... | is caused by | what to do |
|------|------|--------------------|--------------|------------|
| nothing (one position) | - | is constant | camera mounting / displacement | move the camera, or store the offset |
| head X/Y over the field, e.g. 5x5 steps of 1 mm | Z | changes with position | pixel size or perspective error in the advanced calibration | re-run advanced calibration, do not bend the mechanical alignment to fix it |
| head Z, ±0.5 / ±1 mm | X/Y | changes with Z | camera tilt: the optical axis direction (`unitVectorPhyCamZInMachRefFrame`) is wrong | shim/tilt the camera; the slope d(residual)/dZ is essentially tan(tilt), so it also says by how much and in which direction |
| C angle, 12 steps | X/Y, Z | periodic with C | runout (already calibrated) - compare with the stored runout compensation; a component that follows the part rather than the tip is nozzle tilt | re-run runout calibration / check the tip |
| which part is on which nozzle (swap) | - | flips sign | the parts, not the machine | use one pair and average both ways |

The mean over positions is the displacement; the spread is the calibration error. That separation is
the useful output, because they are fixed by completely different actions, and today they are
conflated in one manual alignment.

The same data also gives a **scale check in machine units**: the inter-nozzle spacing is a known
length, so the measured distance between the two part centres is a direct measurement of the
effective units-per-pixel at that Z and in that part of the field. That is what a printed
checkerboard is normally bought for - here it is bolted to the head.

## 3. Reuse, do not re-implement

- `ReferenceBottomVision.findOffsets(part, ..., skipMovement)` measures a part without moving;
  `captureImageOnly` / `captureImageOnlyWithSettle` hand back an image on demand.
- The parallel pre-capture already photographs all nozzles at once and knows the per-shot
  displacement delta - the verification measurement is close to a call of that same code with a
  known part forced on each nozzle.
- `ReferenceNozzleTipCalibration` already sweeps C against a bottom camera and fits runout, and
  already produces `getCalibratedCameraOffset(nozzle, camera)`, which is the number to compare with.
- `AdvancedCalibration` already knows the camera position and tilt and already has the test pattern
  machinery; the check should read what it stored and report residuals against it, not build a second
  model beside it.
- Issues & Solutions (`Solutions.Subject`, `Issue`, `ActionProperty` for a button) is the existing
  home for "this drifted, run the check", including keeping the result stale until it is re-run.
- The scripting panel sees the whole SPI, so the first version needs no compiled code.

## 4. Shape of the module

**v0 - script, zero core changes.** Force/assume a part on each nozzle, one head position, shoot both
cameras, print the per-camera residual and the inter-nozzle vector error; then loop over a small
X/Y grid and over three Z heights and print the decomposed table; CSV into the config directory. This
is a day of writing at most, and it will already be better than the current manual check.

**v1 - report-only module.** `org.openpnp.machine.reference.vision.DisplacementCheck` plus one entry
in Issues & Solutions. Runs as a machine action so it is cancelable and refused during a job. Per
camera: displacement dx, dy (and dθ if the parts have a defined orientation), residual vs position
slope, d(residual)/dZ, runout amplitude and phase, all compared with the stored calibration and with
`displacement-max-distance` / `-max-Z-diff` / `-max-rotation`. Log + CSV. It writes nothing.

**v2 - assist while adjusting.** One camera selected, static target, no head motion, looped at frame
rate: residual in mm with direction, plus the tilt figure. This is the "I am holding the camera in my
hand, tell me when it is right" tool. The live part is cheap; the honest part (telling tilt from
offset in real time) needs the Z variation, i.e. two measurements, so the assist should show both and
let the operator take them.

**v3 - only after v1/v2 have proved themselves.** Write back, and drift alarms.

## 5. The write-back problem

Because there is no per-camera displacement field (§1), "store what you measured" means choosing one:

1. put the residual into the camera calibration (correct place for a mounting error, but it fights
   the advanced calibration workflow: re-running that calibration would overwrite it);
2. put it into the mechanical alignment (v2 helps here; no code at all);
3. add a per-camera displacement offset applied in `getCameraLocationAtPartHeight()`, i.e. resurrect
   the `multi-bottom-vision` idea as a *calibration* value rather than the runtime value it was.

Option 3 is the one that matches `machine.md`, and it is a small, well-contained change, but it adds a
knob that overlaps with the camera calibration, which is exactly the kind of overlap that makes the
current manual procedure hard to reason about. It should not be added before the measurements from v1
show how often the residual is a stable offset (needs a knob) versus a wobble in the calibration
(needs a calibration, not a knob).

## 6. What is still needed

1. Which part to use as the target, and does it outline cleanly on both nozzles at balance level
   (a 0402 was suggested; something with a defined edge/angle is better if rotation is to be checked).
2. The inter-nozzle vector as currently trusted, and how it was obtained - the check is only absolute
   if that vector is; without it the check is still valid as a constancy/drift test.
3. Camera Z heights available at part height for the tilt measurement, and whether the balance level
   can be moved ±1 mm reliably (parts are contact-probed here, so Z should be trustworthy - confirm).
4. Report-only first (recommended) or straight to a knob.
5. Whether the check should also run unattended as a self-test.

## 7. Limits

- Residuals are computed **after** the advanced calibration corrections, in machine coordinates, so
  the check reports what the machine actually does, not what the cameras are physically doing. The
  position/Z slopes are the bridge back to the physical side; without them a residual cannot be
  attributed to a mounting error.
- The absolute result is only as good as the trusted inter-nozzle vector (§6.2). The *stability* and
  *consistency* results do not depend on it at all, and those are what a periodic check is for.
- Everything is bounded by settling after each move and by outline repeatability. Each number is
  measured N times and reported with its spread; the check must not claim an error smaller than the
  spread it just measured.
- Parts vary within a package: swap the two parts and average, or the part error shows up in the
  residual.
- Lighting changes move outlines. Run with production lighting and auto-exposure off.

## 8. Pre-rotate

Pre-rotate is required on this machine and is enabled. That matters here for two reasons: it is the
only path that applies the displacement delta to the nozzle location (`ReferenceBottomVision.java:271`
and the comment at `:765-773`), so on this machine the compensation is actually in effect; and the
uncompensated post-rotate case should therefore never occur - if
`Bottom vision of nozzle ... uses post-rotate but the pre-captured image ...` appears in a log, a part
or pipeline has slipped to post-rotate and should be treated as a configuration error, not as a
warning to ignore.
