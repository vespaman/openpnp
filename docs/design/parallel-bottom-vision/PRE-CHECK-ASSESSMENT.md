# Pre-check and alignment overlap - assessment

Written 2026-09-28, before any of it is implemented. The state to come back to is the tag
**`pbv-2026-09-28`** (`a768606b6c`): parallel alignment verified on the machine, nothing of this
written into the code.

This updates `PRE-ALIGNMENT-CHECK.md` with real numbers and with what the code of this branch
actually allows. Where the two disagree, this document is the current opinion.

## 1. What the machine run of 2026-09-28 measured

From `tests_2026-09-28/log_a.txt`, per alignment step (4 steps, 2 placements each, 0402 parts):

| step | approach move | capture window (arrival → images in hand) | pipeline (images → offsets) | head standing at the vision position |
|---|---|---|---|---|
| 1 | 262 ms | 37 ms | 49 ms | 92 ms |
| 2 | 224 ms | 39 ms | 48 ms | 94 ms |
| 3 | 226 ms | 35 ms | 55 ms | 98 ms |
| 4 | 263 ms | 60 ms | 48 ms | 115 ms |
| **sum** | **975 ms** | **171 ms** | **200 ms** | **399 ms** |

*Arrival* is the `<< ok` the controller sends for the alignment move; the *capture window* ends when
the pre-capture batch closes, the *pipeline* column ends at the last `Offsets accepted`. This is the
window described as "what we have to play with" (`18:03:12.119` → `18:03:12.158`, 39 ms).

The whole job: 11,018 ms, 8 parts, 1,377 ms per part. Against that:

| phase | union of intervals | share |
|---|---|---|
| pick (`Nozzle.BeforePick` → `AfterPick`) | 1,784 ms | 16.2 % |
| place (`BeforePlace` → `AfterPlace`) | 1,888 ms | 17.1 % |
| head standing at the bottom vision position | 399 ms | **3.6 %** |
| of which the vision pipelines | 200 ms | **1.8 %** |

These two rows are the ceiling of anything in this document. Perfectly hidden vision on this machine
and this job is 2,614 CPH becoming 2,711 CPH. The approach move to the vision position (975 ms,
8.9 %) is only reachable by not stopping there at all, see option 5.

## 2. Before anything else: the pipeline writes PNG files during the job

The run had the global log level at DEBUG (2,088 DEBUG lines in `log_a.txt`, against 16 INFO). That
level is set by the *Global log level* combo in the Log panel, which configures tinylog itself
(`LogPanel.java:204`), and `ImageWriteDebug` decides to write on nothing else:

```java
if (!LogUtils.isDebugEnabled()) {          // ImageWriteDebug.java:41
    return null;
}
Imgcodecs.imwrite(file.getAbsolutePath(), pipeline.getWorkingImage());
```

with `LogUtils.isDebugEnabled()` meaning *level ≤ DEBUG* (`LogUtils.java:8`). Nothing in the pipeline
settings or the machine settings is consulted.

The bottom vision settings in use are `BVS_Stock` (`machine.xml`, `bottom-vision-id="BVS_Stock"`), and
the stock pipeline stored for it in `vision-settings.xml` has **two enabled `ImageWriteDebug`
stages** - `deb0` immediately after the `ImageCapture`, `deb1` at the end - each writing the full
frame.

Same operation, measured on this workstation (OpenCV `imwrite`, PNG, 960×1080):

| written | time |
|---|---|
| PNG 960×1080 | **27.6 ms** |
| PNG 200×200 | 1.1 ms |
| JPEG q95 960×1080 | 2.7 ms |

Two full-frame PNG encodes are thus worth about **55 ms**, i.e. the entire measured pipeline window
of 48-55 ms, and the vision computer is not faster than the workstation used for the measurement. For
scale, the whole pixel-wise part of a bottom vision pipeline at full resolution - copy, 9×9 Gaussian,
BGR→HSV, `inRange`, threshold, `findContours`, `drawContours` - is **2.1 ms** on that same workstation
and **0.2 ms** in a 200×200 crop. The pixel work is not what costs anything; the diagnostics are.

The files go to `<configuration directory>/org.openpnp.vision.pipeline.stages.ImageWriteDebug/` with a
nanosecond name per write (`Configuration.createResourceFile`, `Configuration.java:385`) and are never
cleaned up, so the evidence of this is sitting on the machine: the number of files there should be
about two per vision pass.

**Do this before writing any code:** set *Global log level* to `Info` and run the same job. If the
pipeline window falls from ~50 ms towards the handful of milliseconds the pixel work accounts for,
then §3 has to be re-read - a pre-check may fit inside the settle after all - and the real fix is to
decouple the debug images from the log level, because turning DEBUG off is not an option for someone
debugging a machine that is also being produced on. A per-pipeline or per-machine flag is the shape of
that fix.

Smaller and of the same kind: both up cameras have `settle-diagnostics="true"`, which makes the
settling loop convert every frame into a `BufferedImage` for playback (`AbstractSettlingCamera.java:377`).
The frames are downscaled by 3 first (from the configured `settle-gaussian-blur="15"`), so it is a few
milliseconds rather than tens, but they are milliseconds inside the settle window, spent on something
only the camera wizard ever looks at.

## 3. Why the pre-check does not fit in the settle window

The pipeline takes 48-55 ms, the settle it would have to hide inside takes 35-60 ms. That is the
whole problem - **with the PNG writes of §2 still inside that 48-55 ms.** If they come out and the
pipeline drops to single-digit milliseconds, the arithmetic below no longer holds and the pre-check
does fit. Everything in this section is conditional on that measurement.

It is otherwise the same problem the 2026-04 attempt ran into (50-70 ms measured against a
~30 ms budget, `PRE-ALIGNMENT-CHECK.md` *Why it stopped*): the absolute numbers have improved, the
relation has not. Two things make it worse than a ratio:

- Both nozzles need the analysis, and the two alignment pipelines already run concurrently on
  separate threads. A pre-check per nozzle is two more pipeline runs competing for the same cores
  during the same 39 ms.
- The requirement that the pre-check use **the same pipeline as the alignment** (which is the right
  requirement, users will not maintain two pipelines) means the pre-check costs what the alignment
  costs. It cannot be made cheap by definition, and "cheap" is the only thing that could have made it
  fit.

`PRE-ALIGNMENT-CHECK.md` R1 ("the budget is the settling time the camera would have waited anyway")
is therefore not satisfiable with a shared pipeline, and it should be dropped as a requirement. The
check is still worth doing - but as a check, not as something that pays for itself inside 39 ms.

The other thing that went wrong last time and must not go wrong again: the measured "pre-check time"
included the image capture and the settle. Any new measurement must report capture, settle and
pipeline as three separate numbers, and it must be in the log from the first commit.

## 4. What an image taken before settling can and cannot tell

The cameras are global shutter, so an unseltled frame is sharp. What is wrong with it is the *position
of the head* when it was exposed: the commanded position is known, the actual position is not, because
what settles is the mechanical play in the belts and the nozzle holder, not the stepper.

- Size: measurable. It is a distance inside the image, the head position cancels.
- Rotation: measurable, same reason.
- Presence: measurable.
- **The XY offset from the camera centre: not measurable.** That is the alignment result. It is wrong
  by however far the head had not yet come to rest, in the worst case more than the 0.05 mm the whole
  exercise is about.

So the pre-check can honestly answer everything the user wants verified, and it can never replace the
settled frame for the correction itself. It cannot be shortened into being the alignment.

Nor can the error be measured away: `GcodeDriver.getReportedLocation()`
(`GcodeDriver.java:779`) is an `M114` round trip, which is slow, and reports what the controller
thinks the motors are, not where the tool is.

## 5. What the code already has, and where it resists

Already in place, on this branch:

- The images are already separated from the analysis. `captureImageOnly()` /
  `captureImageOnlyWithSettle()` (`ReferenceBottomVision.java:1366`, `:1471`) produce a
  `PreCapturedPlacement` that carries the pixels and nothing else; the analysis of those pixels is
  `processPreCapturedImage()` (`ReferencePnpJobProcessor.java:2387`), which touches no hardware.
- Those analyses are already `CompletableFuture`s on a dedicated `visionExecutor`
  (`ReferencePnpJobProcessor.java:189-227`).
- The one thing that puts the head to sleep is `CompletableFuture.allOf(...).join()` at
  `ReferencePnpJobProcessor.java:1898` (and `:2239` for the multi-shot pass). Everything after it is
  bookkeeping until the placement target is needed.
- The first thing that truly needs the offsets is `getPlacementLocation()`
  (`ReferencePnpJobProcessor.java:2762`).

What resists:

- After the pre-capture batch the very next thing the machine does is the placement move, and that is
  **one coordinated move** including Z:
  `M204 S10413 G1 X121.66 Y118.02 Z-16.8 A-22.7 B24.3 F52584 ; move to target`. There is currently no
  offset-independent leg to run early; one would have to be issued deliberately (Z only), which the
  motion planner can do but the job processor never asks for.
- `Align` and `Place` are steps of a state machine with `return this` retry semantics
  (`ReferencePnpJobProcessor.java:3275-3322`). `plannedPlacement.alignmentOffsets` is set by `Align`
  and read by `Place`; making it "arrives later" means the retry path, `Job.Placement.Starting`, the
  nozzle-order optimisation and the prerotate-for-place step all have to be re-read to see what they
  do with a placement whose offsets are not yet there. This is R6 territory, the least tested part of
  the branch.
- A late failure (`Offsets too large`, no rectangle found) currently aborts the `Align` step while the
  head is at the vision position, where the retry is cheap. If the head has left, the retry must
  travel back. That is fine as a rare path, but it has to be a designed path, not an accident.
- No per-stage timing exists today. `CvStage.Result` carries `processingTimeNs`
  (`CvPipeline.java:54`) and `CvPipeline.getProcessingTimeNs()` exists, but nothing logs them.

## 6. Options, ordered by what they are worth per unit of risk

**0. Measure. No behaviour change.** Log, per alignment: approach complete, per-camera capture
duration, settle duration, per-stage pipeline duration, offsets accepted, next motion command issued -
one `[ALIGN-TIMING]` line per alignment step. Without it every number in this document comes from one
8 part job and from log timestamps that include each other. It also settles the R12 question (how
long is a pipeline stage, really, and what dominates) and it is the thing `PRE-ALIGNMENT-CHECK.md`
already says to do first. Cost: a day, including a test that the line is produced. Risk: none.

**1. Make the pipeline faster - and the crop that goes with it.** Once the debug writes of §2 are out
of the way, the pixel work itself is a handful of milliseconds, so this is worth doing for headroom
and robustness rather than for cycle time. The geometry, from the advanced calibration of Upp1 in
`machine.xml`:

| | |
|---|---|
| raw captured frame | 960×1080 (`raw-cropped-image-width/height`), principal point 479.5, 539.5 |
| as the pipeline sees it | ~1080×960, the camera's `rotation="±90"` applied first |
| field of view | 15.4 × 17.3 mm (`width-fov`, `height-fov`), `units-per-pixel` 0.0386 × 0.0341 mm |
| allowed part offset | `max-linear-offset` 1.0 mm, `max-angular-offset` 10° |

So a 0402 body is some 30 px long in a frame 1,037 kpx large, and the part cannot legitimately be
more than about 1 mm from the centre: a crop of a few millimetres covers everything the pipeline is
allowed to find. The machine already computes exactly that region - `preparePipeline()` sets
`footprint.maxWidth`/`maxHeight` to `√2 · shot.getMaxMaskRadius()` and `MaskCircle.diameter` to
`2 · shot.getMaxMaskRadius()` (`ReferenceBottomVision.java:572-575`, `:585`) - so the crop does not
need a new number to be guessed per part; it should be derived from the same radius, plus a margin.

Two constraints on how it is done:

- **The crop must stay centred on the image centre.** The millimetre↔pixel conversion that
  `MinAreaRect.center`, `DetectRectlinearSymmetry.center` and the mask stages rely on goes through the
  camera and assumes that the centre of the image is the point the camera is looking at. An off-centre
  crop shifts every reported offset by half the shift, silently. Displaced shots (R14) must therefore
  *enlarge* the centred crop by the displacement, not move it.
- **A mask is not a crop.** `MaskCircle`, `MaskRectangle` and `MaskHsv` zero pixels outside a region but
  keep the frame at full size, which is why the pipeline already masks heavily and still pays for every
  pixel. There is no crop stage in `org.openpnp.vision.pipeline.stages` at all; the only real crop is
  the camera's own `crop-width`/`crop-height`, and that is the wrong place: it is entangled with the
  advanced calibration (`raw-cropped-image-width` exists precisely to remember it) and it crops the
  preview too. A centred crop belongs at the head of the vision pipeline, with the original frame kept
  for the diagnostics, and a fallback to the full frame when nothing is found inside the crop.

Secondary benefit: each shot of a multi-shot alignment currently analyses the whole frame, so the crop
pays per shot there.

**2. Start the settling analysis before the move has finished.** Nothing looks at a frame until the
controller confirms the move (`waitForCompletion(CompletionType.WaitForStillstand)`,
`AbstractSettlingCamera.java:657`, and the settle loop then evaluates frames from scratch). The head
is decelerating over the last few millimetres while the camera is already producing frames; the settle
loop could be running against them during the tail of the move, and if the score is already settled at
the moment of the `ok`, the wait is over before it began. This attacks the 171 ms capture window,
which nothing else in this document touches. Cost: moderate, confined to `AbstractSettlingCamera`.
Risk: a settle that reports "settled" while the head is still moving is exactly the wrong answer, so
this needs the real machine and the settle threshold in view.

**3. Leave the vision position before the offsets are known.** Hand the futures to the placements
instead of joining at `:1898`, issue the offset-independent Z retraction, and join at
`getPlacementLocation()` instead. The pipelines then run while the head lifts. Win: up to the 200 ms
of pipeline time, 1.8 %. Cost: the largest change in this list, because of the step state machine and
the late-failure path, not because of the vision. Risk: R6 (retry/re-entry), which is the part of this
branch that has never been exercised on a machine. Do it only after the run D / abort / retry checks of
`MACHINE-TEST.md` have been done, so that there is a baseline to compare a failure against.

**4. The pre-check, riding on option 3, as verification only.** With the head already leaving, the
first unseltled frame gives presence, size and rotation about 60 ms after arrival instead of 90 ms,
and it does not have to beat the settle window any more because the head is not waiting for it. Its
value is the early out: a missing or wrong part is known before the placement, so the machine re-picks
instead of placing air, which on this machine is worth a whole 1.4 s cycle, far more than the 50 ms.
Keep R6 (fail open) and R7 (report only) from `PRE-ALIGNMENT-CHECK.md` for the first version. Cost:
small once option 3 exists, and none of the machinery of the `parallel-bottom-vision-wip` snapshot is
needed (`PRE-ALIGNMENT-CHECK.md` says the same: do not resurrect it).

**5. Capture while moving: ruled out, but for the position and not the blur.** Blur is exposure ×
speed, and the up cameras are set to `Exposure="1"`, which if it is the usual 100 µs unit is 0.1 ms and
25 µm of smear at 250 mm/s - tolerable. What rules it out is that the position of the head at the
instant of exposure cannot be known: `GcodeDriver.getReportedLocation()` (`GcodeDriver.java:779`) is an
`M114` round trip that returns what the controller thinks the motors are, not where the tool is, and the
residual that matters is in the belts and the nozzle holder. The `exposure_time_absolute=50` that
`openpnp.sh` forces is on `/dev/video2`, which is the **down** looking camera, so it does not constrain
bottom vision at all - but it does mean the exposure the up cameras actually use should be confirmed
with `v4l2-ctl -d <up camera> -c exposure_time_absolute` before anyone reasons about smear budgets.

## 7. Recommended sequence

1. **The experiment in §2, today, with no code**: set the global log level to `Info` and re-run the
   same 8 part job. Compare the pipeline window (48-55 ms) and the total standing-at-vision time
   (399 ms). Count the files in
   `<configuration directory>/org.openpnp.vision.pipeline.stages.ImageWriteDebug/` before and after.
   This is free, reversible, and it decides what the rest of the document is about.
2. Option 0, the `[ALIGN-TIMING]` line, so that the next run is measured per stage rather than inferred
   from log timestamps that contain each other.
3. Whatever §2 leaves standing: the debug-image flag decoupled from the log level, `settle-diagnostics`
   off during jobs, then option 1 (crop) for headroom.
4. `MACHINE-TEST.md` runs A, B, D plus the abort/retry checks (R6) - the baseline option 3 needs.
5. Option 3, behind a setting, default off, with the late failure path designed explicitly.
6. Option 4 on top, report only.

Open questions for the machine owner:

- With the log level at `Info`, how much of the 48-55 ms pipeline window is left? Everything above
  hinges on that one number.
- What exposure do the up cameras really run at, and what is the light level headroom to shorten it?
  The `Exposure="1"` property is meaningless without its unit and its real v4l2 value.

Answered since this was first written: the captured size is 960×1080 (§6 option 1), and `/dev/video2`
is the down looking camera.
