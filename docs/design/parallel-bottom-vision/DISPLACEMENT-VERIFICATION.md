# Camera displacement - specification

Status: **specification**, rewritten 2026-10-05. It supersedes the 2026-09-27 assessment of the same
name, which is preserved in git history; the differences are listed in §10. The sequence of work is
in `camera-displacement-plan.md`. Question unchanged: can the machine determine and re-verify its own
displacement data, so that checking it does not mean re-doing the camera alignment, the nozzle and the
nozzle tip runout calibration by hand?

Yes - with the bare nozzle tips as the artifact, without parts and without placing anything. The head
is a rigid measurement standard: the nozzles share X/Y and their relative position is fixed by
mechanics, so bottom vision can compare the camera spacing against the nozzle spacing by looking at
the tips. What that measurement can and cannot separate is the whole subject of this document.

## 1. The three offsets of an up-looking camera

An up-looking camera's machine position is built from three stacked offsets, and confusing them is
the root of every problem described here:

| offset | stored in | applied by | in `getLocation(null)` | in `getLocation(nozzle)` |
|--------|-----------|------------|------------------------|--------------------------|
| mechanical head offsets | `<head-offsets>` | `ReferenceCamera.getHeadOffsets()` | yes | yes |
| advanced calibration fit | `<calibrated-offsets>` | `ReferenceCamera.getCalibratedHeadOffsets()` `ReferenceCamera.java:371-373`, fitted at `AdvancedCalibration.java:1459-1473` | yes | yes |
| per tip runout centre | `center-x`/`center-y` of the runout compensation | `ReferenceNozzle.getCameraToolCalibratedOffset()` via `AbstractCamera.java:178-184` | **no** | yes |

```
camera.getLocation(null)    = machine origin frame + head offsets + advanced fit
camera.getLocation(nozzle)  = the above - runout centre of the tip on that nozzle   (paired camera only)
```

Two consequences that matter:

- The **advanced calibration already absorbs camera position.** `getCalibratedHeadOffsets()` adds the
  fitted `<calibrated-offsets>`, and `AdvancedCalibration` computes them as
  `camLocation - headOffsets` where `camLocation` is the position it fitted from the test pattern. On
  the machine in `tests_2026-10-01/machine.xml` these fits are `x = -1.5063` (Upp1) and
  `x = -1.9498` (Upp2) - large, and **0.44 mm apart between the two cameras**. This is why turning a
  camera screw by hand is so awkward, and why any new displacement knob must be defined as stacking
  on top of the fit rather than competing with it.
- The runout **centre is measured raw** - `ReferenceNozzleTipCalibration.java:708-710` deliberately
  passes `null` instead of the nozzle, "as this would defy the very purpose of finding a new one
  here". So `center-x/center-y` is a residual against `getLocation(null)`, i.e. against the
  *advanced-calibrated* camera position, not against the mechanical one.

`LocationOption.SuppressCameraCalibration` (`AbstractHeadMountable.java:325`, `:355`, used by
`ReferenceHead.java:100`) selects the mechanical head offsets alone, and is how the fit's own
contribution can be quantified.

## 2. The two displacements

**Runtime displacement.** From `multi-bottom-vision`, restored in this fork: if a nozzle is already
close to its shot location, do not move it, take the frame without settling, and compensate
`shotLocation - currentLocation` in the analysis (`ReferenceBottomVision.java` of
`multi-bottom-vision` at `:592-608` and `:244-246`; the settle skip is `ImageCapture.java:100` there).
It is measured, therefore exact, and it is guarded by `displacement-enabled`,
`displacement-max-distance`, `-max-Z-diff`, `-max-rotation`. It is a convenience, not a calibration.

**Camera displacement.** A persisted per-camera value for the physical misplacement of a camera
relative to the nozzles. Its job is to make the *one* head move of a parallel batch good for every
camera, i.e. to make the runtime displacement of the non-primary nozzles small and predictable. This
is what `machine.md` describes as the small difference between camera spacing and nozzle spacing,
reference camera zero.

What the parallel path does today is use the runout centre **in place of** the camera displacement,
and therefore use the `displacement-max-*` guardrails as if they were camera tolerances. See §5.

## 3. What the parallel batch needs

Machine frame, 2D, as the machine claims it. `H_i` claimed nozzle head offsets, `C_j` claimed camera
position (head offsets **plus** the advanced fit, i.e. `getLocation(null)`), `t_j` the true tip
position of nozzle *j* relative to the X/Y axis origin, and `u_i = t_i - H_i`, `e_j = (true camera
axis) - C_j` the two errors. Assuming the runout model is fitted and the C angle is known, the runout
itself cancels and only these constants remain.

Command nozzle *i* to the claimed position `L`: the true tip ends up at `L + u_i`. Camera *j* reports
the offset of what it sees from its own optical axis, converted to machine units by its own
calibration, so a shot of tip *i* in camera *j* is

```
X(i→j) = u_i - e_j
```

Two moves, each "move N_k to Cam_k", give the two diagonal values. The two off-diagonal ones need
*moves* of their own - "move N1 to Cam2" and "move N2 to Cam1" - because the head is rigid and the
cameras are 23 mm apart, so a camera only ever sees the tip that has been brought in front of it. Four
moves for two pairs, `N²` for `N`. Then:

| quantity | expression | what it is | tip-independent |
|----------|------------|------------|-----------------|
| `X(1→1)`, `X(2→2)` | paired shots | what runout calibration stores as `center-x/y` - verifies it | no |
| `X(2→1) - X(1→1)` = `X(1→2) - X(2→2)` | crossed shots | `u₂ - u₁`: nozzle spacing vs claimed head offsets | yes |
| `X(1→2) - X(1→1)` = `X(2→1) - X(2→2)` | crossed shots | `e₁ - e₂`: **the camera displacement** | yes |
| `(X(1→1)+X(2→2)) - (X(1→2)+X(2→1))` | consistency | must be ≈ 0 | - |

The four equations have rank 3: shifting every camera by `v` and every nozzle by `-v` gives identical
measurements, so no absolute gauge exists and none is needed. The consistency residual is not a
formality - if it is large, a shot is bad (misdetection, a knock, a stale Z) and nothing else from the
run should be trusted.

For the production chain this is the payoff. Head positioned for nozzle 1 so that tip 1 is truly on
Cam1's axis: tip 2 then appears under Cam2 at

```
Δ₂ = (m₂₂ - m₁₁) + (H₂ - H₁) - (C₂ - C₁)
```

where `m_ii = X(i→i)`. That is the displacement the analysis of the second shot must remove, and it
is computable from data the machine already holds. General `N`: with the batch positioned at `P`,
shot *i* must be corrected by `Δ_i = (t_i - C_i) - (t_ref - C_ref)` evaluated at `P`, which the least
squares solve of §4 delivers per camera together with its uncertainty.

Because the head does not move during the batch, `Δ_i` is constant for the whole batch - one
measurement per mechanical state, not one per part.

## 4. The measurement

**Artifact: the bare nozzle tip**, measured with the existing runout pipeline
(`ReferenceNozzleTipCalibration.getPreparedPipeline(camera, nozzle, measureLocation)`, already public,
already masking around the expected tip position, `:1699-1708`). No parts: a part adds pick force, a
pocket and a contact-probed Z to a measurement that is supposed to say something about cameras. A
part is kept as an optional cross-check only.

**Moves: raw.** `camera.getLocation(null)` - advanced calibration applied (it anchors the
pixel↔machine mapping and it is what production uses), runout centre not applied (it is being
measured). This is the same convention the runout calibration itself uses (§1).

**Z: the calibration Z offset, not the camera's.** The tip has to be in the focal plane of the
camera, and the machine Z of that plane is the nozzle tip calibration's `calibrationZOffset` - the
same value the runout calibration itself uses. It is *not* `camera.getLocation().getZ()`: the Z of a
bottom camera is the gantry Z plus a few hundredths of head offset, i.e. wherever the head happens to
be standing, and using it makes the focus of a shot depend on the previous move. Whether the two
cameras really have the same focal plane is not something a fixed Z can express; it is a tilt, and it
is the Z sweep of §4 that shows it.

**Frames.** One detection on one frame is not a measurement: the noise of a blurry tip silhouette is
of the same order as the displacement being measured. Each shot is therefore detected on
`frames-per-shot` frames and averaged, and the scatter of those frames is reported separately as the
noise floor of the vision - it says what the optics can do, independent of the mechanics. Before any
of it, the frame itself is checked for contrast (standard deviation of the luma): a frame in which the
light has not come up is not a measurement, and a circle detector will happily fit a circle to noise.
Such a frame is retried and then dropped, and a dropped shot is recorded as a missed shot rather than
silently averaged in. On the first machine run exactly this happened - one frame of 18 came back
almost black, mean luma 0.7 against 55-84 for the others, and it was the largest single contributor to
the reported spread.

**Light.** The lamps take a few milliseconds to strike, and the vision pipeline by itself actuates the
light before each capture and switches it off again after it (`ImageCapture`,
`actuateLightBeforeCapture`/`actuateLightAfterCapture`). Averaging eight detections therefore means
eight strikes, each exposing while the lamp is still coming up - a flicker visible to the eye, and
frame contrast that is a function of when the exposure happened rather than of the machine. A shot is
so to speak pre-lit: the light is actuated once, held for `light-warmup-ms`, and kept on over the whole
shot through the machine's own `CameraBatchOperation`, which defers the light-off to the end of the
batch. Once the actuator is at the on value, the pipeline's own actuation is a no-op and does not
re-strike the lamp. The contrast of the frames of a camera within a run is compared with itself: more
than a factor of two between the dimmest and the brightest frame of one camera is a PROBLEM, because
thresholds and circle fitting are not brightness invariant and a run like that measures the lighting.

**New frames.** Averaging eight detections is worth nothing if the camera handed out the same frame
eight times, which is what a camera grabbing ahead of being asked can do. Each detection therefore
records the instant the camera says that frame was taken (`getRawFrameCapturedAtMs()`,
`frames.csv`), the shot reports how many distinct instants its frames had, and a shot with eight
values from one frame is a PROBLEM rather than a measurement with a reassuringly small scatter. The
second machine run showed exactly this, together with a fit residual of 0.0000 mm and a closure of
0.0000 mm on a condition whose crossed shots had all failed - see below.

**Redundancy.** With `n` pairs, one repeat offers `n²` observations for `2n-1` unknowns, so the two
pair machine has exactly one degree of freedom per axis and nothing at all if a crossed shot is
missing. A residual of zero from an exactly determined system is arithmetic, not evidence, so the
report prints *shots per repeat* against *unknowns* and refuses a run that has no redundancy. The
closure check likewise refuses to score it: it leaves the closure empty and says why instead of
reporting a beautiful 0.0000 mm.

**Solve.** The system `X(i→j) = u_i - e_j` is solved by least squares with the gauge fixed by the
claimed values (`Σe = 0` over the cameras, so reported `e_j` are displacements relative to the mean of
the cameras, which is exactly the unobservable direction). Least squares rather than pairwise
differences because it handles any `N ≥ 2`, missing or failed shots, and repeated runs, and because it
yields the spread of every derived quantity - which is the floor on what the run may claim.

**Passes.**

| vary | keep | a residual that... | is caused by | what to do |
|------|------|--------------------|--------------|------------|
| nothing | - | is constant, `e₁-e₂` ≠ 0 | camera mounting | move the camera (stage 5 helper), or store the displacement (§6) |
| head X/Y across the field | Z | changes with position | pixel size / perspective error in the advanced calibration | re-run the advanced calibration; do not bend the mechanical alignment to fix it |
| head Z, ±0.5 / ±1 mm | X/Y | changes with Z | camera tilt: `unitVectorPhyCamZInMachRefFrame` wrong | shim/tilt; `d(offset)/dZ ≈ tan(tilt)` says by how much and where |
| C angle | X/Y, Z | periodic with C | runout, already calibrated | compare with the stored compensation; something following the tip rather than the axis is a bent/dirty tip |
| `SuppressCameraCalibration` on the move | everything | changes by the fit | the advanced calibration's own position fit | re-run it, or correct the mechanical mounting it had to absorb |
| repeat runs | everything | spread > tolerance | settling, lighting, detection noise | nothing is written until the spread is inside tolerance |

**Closure, which is not the fit residual.** The residual of the least squares over *all* repeats is a
per-shot scatter estimate - legitimate, but it cannot see the one thing that matters most, because
averaging over repeats averages away exactly the violations one wants to catch. So the closure is
evaluated on **each repeat separately**: within one repeat the head is one rigid body, the shots have
to satisfy `X(1→1)+X(2→2) = X(1→2)+X(2→1)`, and any residual is a violation of geometry, not
measurement uncertainty - a tip picked up out of focus, a move still settling, a frame taken too early.
For two pairs the reported closure is half the loop value. The first machine run measured 0.066 mm on
its worst repeat where the fit residual was 0.028 mm; the fit residual was not lying, it was simply
looking at the wrong thing.

Mean over repeats is the value, spread is what may not be claimed. The mean over *field positions* is
the displacement; the slope is the calibration error - they are fixed by completely different actions
and today they are conflated in one manual alignment.

## 5. Audit of the current code

`ReferenceBottomVision.displacementDelta()` (`ReferenceBottomVision.java:1896-1924`) returns, for the
non-first nozzle, `getCalibratedCameraOffset()` (the runout centre) plus `getCalibratedOffset()` (the
runout at the wanted rotation), and zero for the first nozzle. Against §3:

- the runout centre of the *second* nozzle appears with the wrong sign relative to the first, and the
  first nozzle's own centre is missing entirely. The correct combination is `m₂₂ - m₁₁`, the code
  supplies `m₂₂`;
- `(H₂-H₁) - (C₂-C₁)`, the actual spacing mismatch, is **not in the expression at all**;
- the runout term is added although the motion already compensated it, so the up-to-0.11 mm
  amplitude of this machine's fitted runout is applied twice in the analysis.

The numbers from `tests_2026-10-01/machine.xml`, which is the machine the feature was developed on:

```
N1 head-offsets (34.0606, -18.2714)      N2 (57.1517, -18.2974)
                                              claimed nozzle spacing  T = (23.0911, -0.0261)
Upp1 head-offsets (158.102, 27.65)  + calibrated-offsets (-1.5063,  0.1914)
Upp2 head-offsets (181.153, 27.564) + calibrated-offsets (-1.9498,  0.1178)
                                              claimed camera spacing  S = (22.6075, -0.1596)
                                              T - S                   = ( 0.4836,  0.1336)  = 0.502 mm
N1 + tip 502  runout centre  m₁₁ = ( 0.2812, 0.1821)
N2 + tip 502b runout centre  m₂₂ = (-0.0592, 0.0970)
                                              required Δ₂ = (m₂₂-m₁₁) + (T-S) = (0.1432, 0.0485) = 0.151 mm
                                              code applies m₂₂ + runout(θ)   ≈ (-0.0592, 0.0970)
                                              error                            ≈ (0.2024, -0.0485) = 0.208 mm
```

So the second nozzle of every parallel batch is placed about **0.2 mm** away from where it should be -
computed from the machine's own stored values, not yet measured - and the 0.50 mm spacing mismatch
means `displacement-max-distance = 0.5` is already consumed by an uncalibrated misplacement: the
guardrail has no margin left and cannot protect anything. Both numbers are far above what a 0402
placement tolerates, and far below anything the current code complains about. Stage 1 exists to turn
this arithmetic into a measurement.

The same file also shows why `center-x/center-y` cannot be the carrier of a camera property. On N1,
whose rotation axis does not move between tips, the centre is `0.2812` (tip 502), `0.2044` (503),
`0.2549` (504b) - 0.077 mm of tip-dependence in X among the entries with a genuine fitted runout, and
two further entries (`503b`, `505b`) are degenerate fits with `radius = 0`, `rms-error = 0` whose
centre means nothing at all. A camera property must be tip-independent; this is not.

## 6. Where the value lives

```xml
<camera ... name="Upp1" looking="Up" ...>
  <head-offsets units="Millimeters" x="158.102" y="27.65" .../>
  <advanced-calibration ...><calibrated-offsets .../></advanced-calibration>
  <displacement units="Millimeters" x="..." y="..." verified-at="2026-10-05T..." spread="0.012"/>
</camera>
```

- Applied in `ReferenceCamera.getCalibratedHeadOffsets()`, **after** the advanced calibration term, so
  it stacks on the physical mount and everything downstream (`getLocation`, the shot location, the
  crop, the preview) follows. Kept when `SuppressCameraCalibration` is requested: it is a measured
  value the user chose, not a fit.
- **Absolute**, i.e. the total offset from head offsets + fit, not an increment; the check proposes
  `new = current + residual`.
- The runout calibration is reset when an up-looking camera changes (`ReferenceCamera.viewHasChanged()`
  → `resetAllNozzleTips()`), so the order is fixed: camera displacement first, then runout
  calibration. The criterion that the displacement is right is then directly observable: **the paired
  centres `m_ii` collapse towards zero**, leaving only each tip's own concentricity.
- Staleness: an Issue while the advanced calibration of a camera has changed since that camera's
  `verified-at`, because the fit absorbs camera position (§1) and would otherwise silently
  double-count.
- Runtime for the non-primary nozzles stays the measured runtime displacement of §2, computed from the
  stored displacements as `ideal shot - actual` and guarded by `displacement-max-*`, which return to
  meaning "how far from the shot location we are willing to photograph".

## 7. Pass criteria

Adjustable machine settings, defaults below; a run is valid only if the consistency residual and the
repeat spread pass, and only then may the displacement be written.

| setting | default | basis |
|---------|---------|-------|
| `displacement-verify-max-residual-mm` | 0.05 | the fitted runout `rms-error` on this machine spans 0.010-0.041 mm; below this a "residual" is noise |
| `displacement-verify-max-closure-mm` | 0.05 | per repeat, this is geometry and not scatter; see §4 |
| `displacement-verify-max-spread-mm` | 0.02 | repeat spread of the same shot, and the frame-to-frame scatter of one shot |
| `displacement-verify-frames-per-shot` | 8 | average the vision noise instead of reporting it |
| `displacement-verify-min-image-stddev` | 5 | on luma 0..255; a frame below this is a light that is not up |
| `displacement-verify-light-warmup-ms` | 50 | the lamps on this machine take a few ms to strike; paid once per shot, not per frame |
| - | - | frame contrast of one camera within a run: factor 2 at most, otherwise the run measures the lighting |
| `displacement-verify-max-focus-ratio` | 2.5 | sharpness of the softest camera relative to the sharpest one, see below |
| `displacement-verify-pixel-scale-probe-mm` | 1.0 | frames at known machine offsets, so the true pixel size of each camera can be measured afterwards |
| `displacement-verify-hole-diameter-mm` | 0 | 0 measures the circle the nozzle tip calibration is configured for; a bore diameter makes the detector fit the vacuum hole instead of the glare, see `MACHINE-TEST.md` |
| - | - | distinct frame instants per shot = frames detected, otherwise the average is of one frame |
| `displacement-verify-max-tilt-mm-per-mm` | 0.02 | 1.1° of unmodelled tilt over a ±1 mm Z sweep |
| `displacement-max-distance` (existing) | 0.5 | unchanged meaning; after §6 it should hold margin, not be consumed |

The check must never report an error smaller than the spread it just measured.

Three things the 2026-10-05/06 runs showed, each of which a closure limit alone only reports and
cannot explain:

- **Equally sharp.** The tip as seen by an up-looking camera is a specular ring around the bore with
  a soft penumbra around that, not a hard silhouette; `DetectCircularSymmetry` is driven by
  `nozzleTip.diameter`, so what it fits is a ring somewhere in that structure, and where exactly that
  ring is depends on focus and on the lighting of that one camera. On this machine the tip of `Upp2`
  measures about 2.3× softer than the same tip in `Upp1` at the same commanded Z, so the two cameras
  are not looking at the same feature and their difference is not a displacement. Hence the sharpness
  (RMS Laplacian of the centre window) of every shot is recorded and the softest/sharpest ratio is a
  pass criterion. That ratio has to be taken from frames the camera actually saw: the first runs judged
  it from whatever was last in the camera's buffer, which after a crossed move is the previous shot's
  empty field, and reported the sharper camera as the softer one.
- **Equal millimetres.** A crossed shot subtracts a distance measured in one camera's pixels-as-scaled
  by its own advanced calibration from a distance measured in another camera's. A few percent
  disagreement in pixel size between the two calibrations is therefore a few percent of the off-centre
  distance (0.4 mm here) of straight systematic error, which is of the same size as the displacement
  sought. The commanded move of the probe is machine millimetres, so the pictures decide the question;
  the advanced calibrations of these two cameras imply a pixel size ratio of ~0.94, while the probe
  pictures of 2026-10-06 measure 0.0266 against 0.0255 mm/px, i.e. 4%.
- **The sum of the two spacings is not observable, and a batch cannot split it either.** Every shot of
  the crossed matrix puts one tip on one camera axis, so in each of its equations the spacing of the
  nozzles and the spacing of the cameras enter as a single sum: whatever the two are wrong by, the fit
  absorbs it and reports a residual of 0.012 mm happily. A batch is centred on one nozzle and measures
  the others, so it does see the sum, and the batch table of 2026-10-06 measures it at 0.05-0.08 mm -
  larger than the residual, the closure and the spread of the whole run put together. That number says
  a batch is wrong by that much; with two nozzles and two cameras it cannot say by what. A nozzle that
  is not where its head offsets say, and a tip that is detected slightly off when it sits away from the
  middle of the image, are both fixed, both repeat to a micron over Z, both land on every non-primary
  placement and neither is removed by a stored displacement. Splitting them needs a third camera or a
  run that changes one candidate and not the other (`cAngles`, `applyStoredAxisOffsets`,
  `suppressAdvancedCalibration`), not a second reading of the same four shots. The report therefore
  prints the off-axis distance of the tip in that cell as well, and no correction. See `MACHINE-TEST.md`
  for the `belief` column that was added on 2026-10-06 and taken out the same day: comparing
  `displacementDelta()`, which is about a part, with a centred bare tip, is not a measurement.

## 8. Limits

- No absolute gauge (§3). The result is relative to the machine's own claims and to the mean of the
  cameras; only `e₁-e₂` (and its `N` camera generalization) is observable. Absolute accuracy would
  need an external artifact.
- Everything is computed after the advanced calibration, in machine coordinates: the check reports
  what the machine *does*. The Z and field slopes are the only bridge back to what the cameras are
  *physically doing*, which is why they are part of the spec and not an extra.
- A crossed shot puts tip *i* in camera *j*, whose background/lighting pipeline was tuned for its own
  nozzle. Misdetection is expected to be more likely there; the run reports per-shot detection success
  and tolerates dropped shots (least squares), and a paired-only mode exists - which measures the
  per-pair residuals but not the decomposition.
- Sign conventions are the biggest single risk: `Upp1` is `rotation=-90, flip-x`, `Upp2` is
  `rotation=+90, flip-y`. A mirror here does not throw an exception, it reports a displacement with
  the wrong sign and the mechanical correction then makes matters worse. Pinned by unit test.
- Settling, lighting and outline repeatability bound everything; auto-exposure off, production light.
- The absolute result is only as good as the claimed head offsets. Constancy and consistency - what a
  periodic check is actually for - do not depend on it at all.

## 9. Pre-rotate

Pre-rotate is required on this machine and enabled. It is the only path that applies the displacement
delta to the nozzle location (`ReferenceBottomVision.java:334-341`), so on this machine the
compensation is genuinely in effect. The uncompensated post-rotate case should therefore never occur:
if `Bottom vision of nozzle ... uses post-rotate but the pre-captured image ...` appears in a log, a
part or pipeline has slipped to post-rotate and is a configuration error, not a warning to ignore.

## 10. What changed relative to the 2026-09-27 assessment

- Reference artifact: two parts on two nozzles → **bare tips**, parts optional. Placing was never in
  the old document either, and is now explicitly excluded (D2).
- §1 was written knowing the runout centre is the number to compare with; it is now known to be a
  *per pair* residual that cannot carry a camera property, and the advanced calibration's absorption of
  camera position is quantified (0.44 mm between the two cameras) rather than described.
- The write-back question of §5 there ("three options, choose later") is answered: option 3, a
  per-camera displacement stacking on the advanced calibration, with the staleness rule that option 1
  was missing.
- The decomposition of §2 there rested on one head position and a known inter-nozzle vector; it is now
  the four-shot algebra of §3, which needs no known vector and produces its own consistency check.
- v0 "script, zero core changes" survives as the entry point, but the core change of §6 is no longer
  deferred behind a knob-overlap argument: the overlap is measured, and it is the reason a knob is
  needed.
