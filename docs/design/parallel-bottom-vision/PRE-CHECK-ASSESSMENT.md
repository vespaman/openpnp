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

## 2. Ruled out: the debug images the log level makes the pipeline write

Worth writing down because it is a trap, even though it does not explain this run.

`ImageWriteDebug` decides to write a PNG on nothing but the global log level:

```java
if (!LogUtils.isDebugEnabled()) {          // ImageWriteDebug.java:41
    return null;
}
Imgcodecs.imwrite(file.getAbsolutePath(), pipeline.getWorkingImage());
```

with `LogUtils.isDebugEnabled()` meaning *level ≤ DEBUG* (`LogUtils.java:8`), and that level is the
*Global log level* combo in the Log panel, which reconfigures tinylog itself (`LogPanel.java:204`). The
run had Debug (2,088 DEBUG lines against 16 INFO). The stock pipeline in `vision-settings.xml` has two
`ImageWriteDebug` stages enabled, `deb0` right after the capture and `deb1` at the end, and a PNG encode
of a 960x1080 frame measures 27.6 ms on this workstation. Two of them would have been the entire
measured pipeline window of 48-55 ms.

Checked on the machine: `<configuration directory>/org.openpnp.vision.pipeline.stages.ImageWriteDebug/`
(where `Configuration.createResourceFile`, `Configuration.java:385`, puts them, one file per write,
never cleaned up) contains only old files. The pipeline the package in the test job actually uses has
its debug stages disabled, so this run paid nothing for them. The per-package pipelines live in
`packages.xml`, which was not part of the copied configuration, so the pipeline the job ran cannot be
inspected from here.

What does survive:

- Any part that inherits the stock pipelines gets a full-frame PNG encode per enabled stage per vision
  pass for as long as the log level is Debug, and nothing says so in the log. If a future timing
  investigation finds unexplained milliseconds, the timestamps in that directory are the first thing to
  look at, and the fix is a flag of the pipeline or machine rather than the log level.
- `settle-diagnostics="true"` is set on both up cameras in `machine.xml`, which makes the settling loop
  convert every frame into a `BufferedImage` for playback (`AbstractSettlingCamera.java:377`), inside
  the settle window. The frames are downscaled by 3 first (from `settle-gaussian-blur="15"`), so this is
  milliseconds rather than tens, spent on something only the camera wizard ever looks at.
- **Benchmarks from another machine do not transfer and must not be used to size anything.** The
  pixel-wise part of the pipeline (copy, 9x9 Gaussian, BGR-HSV, `inRange`, threshold, `findContours`,
  `drawContours`) measures 2.1 ms at full resolution here and 0.2 ms in a 200x200 crop, which tells us
  nothing about the vision computer, the OpenCV build OpenPnP bundles, the Java/JNI and `Mat` allocation
  overhead per stage, or the two pipelines running concurrently. **Superseded**: §3 is that measurement,
  on the vision computer, on the bundled OpenCV, on the pipeline that actually ran.

## 3. What the pipeline is made of, measured stage by stage

The OpenPnP PC (`pn52`, Ryzen 7 5800H) has the fork's own `target/classes` and the same OpenCV 4.5.5, so
the pipeline can be timed there against a captured frame without touching hardware. It is the pipeline of
`R0402` (`BVS1750dee5e50a6b58`), 19 stages, read out of `vision-settings.xml` **on the machine** - the
stock pipelines in `tests_2026-09-28/` are not what the job ran - with its `ImageCapture` replaced by an
`ImageRead` of a real 1080x926 bottom vision frame, its camera replaced by a stub returning the calibrated
`units-per-pixel`, and every stage's `processingTimeNs` printed. Median of 40 runs. The other columns are
the same pipeline against copies of the frame cropped to a centred square, which is what §7 option 1
proposes.

| stage | 1080x926 | 525 px | 300 px | 105 px |
|---|--:|--:|--:|--:|
| `ImageRead`, standing in for the capture | 11.5 | 3.4 | 1.2 | 0.3 |
| `MaskHsv` x2 | 11.3 | 1.8 | 0.7 | 0.1 |
| `ConvertColor` x3 | 3.5 | 1.5 | 0.8 | 0.2 |
| `MaskCircle` x2 | 2.5 | 0.3 | 0.1 | 0.0 |
| `MinAreaRect` | 2.0 | 0.3 | 0.2 | 0.1 |
| `ImageRecall` | 1.4 | 0.4 | 0.1 | 0.0 |
| `BlurGaussian` | 0.9 | 0.6 | 0.1 | 0.1 |
| `FindContours` + `FilterContours` + `DrawContours` | 1.2 | 0.2 | 0.1 | 0.1 |
| parameter stages, `DrawRotatedRects` | 0.2 | 0.1 | 0.0 | 0.1 |
| **sum of stages** | **34.4** | **8.7** | **3.5** | **1.0** |
| **analysis alone**, i.e. without the `ImageRead` | **22.9** | **5.2** | **2.3** | **0.7** |
| whole `process()`, incl. inter-stage bookkeeping | 45.0 | 11.5 | 4.5 | 1.2 |

The 525 px column is a coincidence of this particular pipeline: its `MaskCircle` is already 525 px in the
XML, so that crop is the mask made real. 105 px is 4 mm, which is `maxMaskRadius` for a 0402 plus the 1.0
mm `max-linear-offset` with room to spare. A 200 px run was measured and dropped: its median came out
*above* the 300 px one, which says the medians below about 5 ms carry noise of that order. The ratio
between the first and the last column is not noise.

Three things follow:

- **The pixel-wise stages are the cost, and they are all of it.** Two `MaskHsv`, three colour conversions,
  two `MaskCircle` and the blur are 20 of the 22.9 ms. Contours, which everyone assumes are the expensive
  part, are 1.2 ms. A megapixel analysed to locate a 30 px body is the story §7 option 1 predicted, now
  with numbers: **a 4 mm crop makes the analysis 0.7 ms instead of 22.9 ms**, a factor of thirty.
- **A centred crop does not move the answer.** The same part is found at these centres, and its distance
  from the centre of each image is the same to within a pixel:

  | frame | detection centre | px from image centre |
  |---|---|---|
  | 1080x926 | 537.5, 464.5 | -2.5, +1.5 |
  | 525 px | 260.5, 264.5 | -2.0, +2.0 |
  | 300 px | 147.5, 151.5 | -2.5, +1.5 |
  | 105 px | 50.5, 54.5 | -2.0, +2.0 |

- **The pre-check relation flips.** §4 concluded that an analysis of 48-55 ms cannot hide inside a settle
  of 35-60 ms. An analysis of 0.7 ms hides in it four times over, and so do two of them, one per nozzle.
  The pre-check could never pay for itself on the full frame; on a 4 mm crop it can.

How it was measured, so that it can be done again: `bench/make_pipeline.py` pulls the pipeline of a part
out of the machine's own `parts.xml`, `packages.xml` and `vision-settings.xml` and turns its capture into
an image read, `bench/Crop.java` cuts the centred copies of a frame, and `bench/Benchmark.java` runs the
result with the fork's own classes and a camera stub and prints the stages. On the OpenPnP PC, with
`classes` and `lib` copied out of `target/`:

    javac -cp "classes:lib/*" -d out Benchmark.java Crop.java
    java -cp "out:classes:lib/*" org.openpnp.vision.pipeline.Benchmark p_full.xml 40

Putting the crop stage of §7 option 1 into that same pipeline under the capture, and letting it take its
size from the `footprint.maxWidth` property the way it does on the machine, is the row that matters,
because it is the configuration that has now been installed on the machine:

| | full frame | with `crop` under the capture |
|---|--:|--:|
| sum of stages | 34.5 | 14.0 |
| analysis alone | 23.1 | 2.6 |
| whole `process()` | 44.9 | 20.1 |
| detection | `39x21` at 537.5, 464.5 | `39x21` at 97.5, 101.5 |
| px from image centre | -2.5, +1.5 | -2.0, +2.0 |

The stage derived 78 px from a 2.83 mm footprint and then took the `minimum="200"` floor, so it cropped to
200 px rather than to the 105 px of the table above; that is the conservative setting, and it is still a
factor of nine. The 1.3 ms of `ImageRecall` is still there, because this pipeline recalls the full frame
anyway in order to draw on it: a crop makes the analysis cheap, it does not make the frame disappear.

One trap worth writing down, because it silently produced a wrong measurement here first: the benchmark
calls `resetReusedPipeline()` between runs the way the real code does, and that reset puts the stage
parameters back to their XML values - on the machine `preparePipeline()` then applies them again before
every shot. Properties applied once before the loop are therefore only in force for the first run, and the
median describes the uncropped pipeline. They are applied per run in `bench/Benchmark.java` now.

Two caveats. The job measured 48-55 ms for the window and `process()` here is 45 ms, of which 11.5 ms is
a PNG decode standing in for the captured image - so the real capture path, `preparePipeline()` and
`displayResult()` together account for the remaining 10-20 ms, and that is precisely what the
`[ALIGN-TIMING]` line of option 0 now measures separately. And this ran with the CPU governor on
`performance`, which the machine was not on during the logged job; how much of the gap between 45 and
55 ms that is, only the next run can say.

## 4. Why the pre-check does not fit in the settle window

The pipeline takes 48-55 ms, the settle it would have to hide inside takes 35-60 ms. That is the
whole problem, and it is the same problem the 2026-04 attempt ran into (50-70 ms measured against a
~30 ms budget, `PRE-ALIGNMENT-CHECK.md` *Why it stopped*): the absolute numbers have improved, the
relation has not. It holds for the pipeline as it is configured today, and §3 is what shows it: the
22.9 ms of analysis is spread over a megapixel by nine pixel-wise stages, none of which can be dropped
on its own - they are cheap only together, and only once the frame is small. Two things make it worse
than a ratio either way:

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

## 5. What an image taken before settling can and cannot tell

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

## 6. What the code already has, and where it resists

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

## 7. Options, ordered by what they are worth per unit of risk

**0. Measure. No behaviour change.** *Partly done.* `processPipelineAndGetResult()` now writes one
`[ALIGN-TIMING]` line per shot, debug gated like the rest of the diagnostics
(`ReferenceBottomVision.logAlignTimings`): `preparePipeline()`, `process()`, and every stage's
`processingTimeNs` in pipeline order. What it does not yet separate is the capture, the settle and the
`displayResult()` conversion, so the three numbers `PRE-ALIGNMENT-CHECK.md` asks for are still not all
there. The off-machine measurement of §3 has meanwhile answered the R12 question that this option was
supposed to answer.

**1. Make the pipeline faster - and the crop that goes with it.** This is now the lead option: §3
measured it, and a centred 4 mm crop takes the analysis from 22.9 ms to 0.7 ms while reporting the same
part within a pixel. The reason it was always going to be worth it is visible without measuring - the
frames are a megapixel and the part is 30 px, so every pixel-wise stage works over some thirty times the
area the answer can possibly come from. The geometry, from the advanced calibration of Upp1 in
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

*The stage exists now*: `CropCentered` (`org.openpnp.vision.pipeline.stages.CropCentered`). Its width and
height come from `footprint.maxWidth`/`footprint.maxHeight` unless they are set explicitly, which is the
number `preparePipeline()` already computes, so no new per-part number is introduced; it then adds a
`margin` and refuses to go below a `minimum` (200 px by default, already fifteen times less area than the
full frame of this camera and most of the measured win); it passes the frame through unchanged when it
cannot work out a size, because a badly configured stage must not fail a job; and it cannot be made
off-centre, because it has no offset parameter to give it. To try it on the machine, add it to `R0402`
directly under the capture and read the `[ALIGN-TIMING]` line of the next run. The image from before the
crop is not lost: it is still the result of the capture stage, so `ImageRecall` of that stage brings the
full frame back, either for `ImageWriteDebug` or for a second pass at full size when the crop found
nothing.

**The first machine run of the crop failed, and not because the crop was wrong** (`tests_2026-09-28/log_b.txt`,
2026-09-28 23:20). The crop did exactly what it was measured to do: `process 20.1 ms` instead of the
48-55 ms of `log_a.txt`, and `MinAreaRect` reported the part at `(97.5, 98.5)` inside the 200 px crop,
which for a `Upp1` of 0.038595 x 0.034126 mm is `-0.077, +0.034 mm` - a good find of a part sitting
essentially where the nozzle expected it. What the machine then steered to was `-11.775, +10.038 mm`, the
center offset check failed at `15.06 > 1.0 mm`, the parallel attempt fell back to sequential, and the
retries presented the part somewhere else than where the crop was looking, so the job ended with no result.

Those two numbers are the bug in one line: the pixels of the crop were read against the center of the
**frame**. `ReferenceBottomVision` converted the rect centre with
`VisionUtils.getPixelCenterOffsets(camera, rect.center.x, rect.center.y)`, and the two-argument form takes
the center from `camera.getWidth()/getHeight()`. The frame that camera produces after calibration is
805x785, and indeed `(97.5 - 805/2) * 0.038595 = -11.771` and `(98.5 - 785/2) * 0.034126 = +10.034`, to
three decimals of what was logged. Nothing about the detection was off by 11 mm; nothing about the crop was
involved either - the same call was simply wrong for any image smaller than the frame, which is why a crop
was the thing that finally walked into it.

The rule the fix is built on: **a geometry belongs to the image it was computed in**, and that image is
neither necessarily the camera frame nor recoverable from the camera. Three places now respect it:

- `VisionUtils.getPixelCenterOffsets(camera, imageWidth, imageHeight, x, y)` and
  `VisionUtils.getLocationPixels(camera, tool, imageWidth, imageHeight, location)` take the frame to
  convert against as an argument; the old two-argument forms delegate to them with the camera size, so
  every existing caller behaves exactly as before.
- `ReferenceBottomVision.processPipelineAndGetResult()` returns the rect together with the size of the
  image the results stage looked at (`PipelineGeometry`), and both the centre offsets and the corner
  offsets of the maximum-offset check go through it. The composite of a multi-shot alignment is unchanged:
  all shots share one frame.
- `CvStage.getConvertedPipelineProperty()` converts a `Location` pipeline property - `MinAreaRect.center`,
  `DetectRectlinearSymmetry.center` and the like, which the machine sets in millimetres - against the
  current `CvPipeline.getWorkingImage()` instead of the nominal camera size, which is the same mistake
  coming the other way: with a crop in the pipeline it would have told a stage to look 11 mm outside it.

The benchmark of §3 could not have caught this, and that is worth saying plainly: it compared the detected
rect between the full frame and the crop, inside the pipeline, where everything is self-consistent at any
origin. The failure needed the millimetre conversion, i.e. the consumer, to appear. `CropCenteredTest` now
runs that conversion for both cases and asserts the millimetres are the same, and asserts that the
frame-referenced variant of the same pixels is more than 100 px away, so the shape of the old bug is pinned
as well as the fix. `bench/Benchmark.java` prints the same two numbers for whatever pipeline it is given, so
the check is made on the real one and not only on a synthetic mark:

| | full frame | with `crop` |
|---|--:|--:|
| analysed image | 1080x926 px | 200x200 px |
| detection | `{537.5, 464.5} 39x21` | `{97.5, 101.5} 39x21` |
| offsets, analysed image | -0.096, -0.051 mm | **-0.096, -0.051 mm** |
| offsets, camera frame | -0.096, -0.051 mm | -17.078, +12.336 mm |

The bolded cell is the whole point: after the fix the crop reports the same millimetres the full frame
does, on the same frame of the same part, and the last row is what the machine did on the first trial. The
magnitudes differ from the log because this frame is 1080x926 and the machine's is 805x785; the sign
pattern and the order of magnitude are the same failure.

Two things this also settled. The processed bottom vision frame is 805x785 and not the 1080x926 PNG the
benchmark used, so the **absolute** stage times of §3 are high by about 1.35x in area terms; the ratios, and
therefore the case for the crop, stand, and the machine's own 48-55 ms full frame against 20.1 ms cropped
agrees with them. And `ImageRecall` of the capture stage remains available as the full frame fallback, but
only *after* the geometry stages: recalling the frame in front of them puts them back in an image whose
center they are not.

One effect the first machine comparison will have to live with: the crop side length is rounded to a whole
pixel, and a crop of odd side and a crop of even side put the image centre half a pixel apart, i.e. 19 µm
on this camera. That is visible in the table of §3 (-2.5, +1.5 px against -2.0, +2.0 px) and it is well
inside the 0.05 mm the offsets are checked against, but it means two runs with different crop sizes are
not comparable to better than half a pixel.

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

## 8. Recommended sequence

1. **Enable `CropCentered` on the machine and measure it.** *Installed on the machine 2026-09-28 and run
   once; it failed for the reason in §7 option 1 and the configuration was reverted to the backup. The
   coordinate-frame fix is in this branch, so the trial is re-runnable: rebuild the jar, put the stage back,
   run the same job. See the second results entry of `MACHINE-TEST.md` for what was changed there and how to
   undo it.* The stage of option 1 is written and tested; what is left is the part
   that can only be done there. It is in `R0402` under the capture; run the same 8 part job, and compare
   the `[ALIGN-TIMING]` lines. Measured off the machine, 22.9 ms became 2.6 ms at the conservative 200 px
   floor and 0.7 ms at 105 px (§3), which is worth more than every scheduling trick in this document put
   together, and it is a stage rather than a change to the job state machine, so one package can carry the
   risk before all of them do. Two things to look for specifically: that the offsets are the same as they
   were without the crop to within half a pixel, and that a part deliberately presented near the 1 mm
   offset limit is still found - the full frame fallback has to be exercised deliberately, not discovered.
   The first run makes the first of those two the whole point of the second one: log the offsets, do not
   just look at whether the job passed.

   **If the run is good, the crop is not a experiment to be left in one package, it is a default.** `R0402`
   is shared by 27 parts on this machine and the job only aligned one of them, so the run proves the
   setting and not the fleet. Then: the rest of the bottom vision settings on the machine, the stock
   settings that ship in `src/main/resources`, and the pipeline templates the setup wizard writes, all want
   the same stage under the capture; and it is worth an upstream proposal on its own, independently of
   everything else in this document, because it speeds up vanilla OpenPnP with no change to its job state
   machine.
2. **The rest of option 0**, i.e. separate capture, settle and display in the `[ALIGN-TIMING]` line, so
   the next run shows what the remaining 10-20 ms of the window are. Run the same job with the governor on
   `performance` and say so in the log, otherwise the numbers cannot be compared with
   `tests_2026-09-28/log_a.txt`.
3. **Copy the whole configuration next run.** `packages.xml` and `parts.xml` were not in
   `tests_2026-09-28/`, so the pipeline the job actually ran could only be read off the machine itself; see
   the *Recording* section of `MACHINE-TEST.md`.
4. `MACHINE-TEST.md` runs A, B, D plus the abort/retry checks (R6) - the baseline option 3 needs.
5. Option 3, behind a setting, default off, with the late failure path designed explicitly.
6. Option 4 on top, report only. It is the crop of step 1 that makes it affordable: see the last bullet of
   §3.

Open questions for the machine owner:

- What exposure do the up cameras really run at, and how much light headroom is there to shorten it?
  `Exposure="1"` is meaningless without its unit and its actual v4l2 value.
- Does the crop survive a part presented at the far end of the allowed 1 mm offset, and a bent lead
  hanging outside the mask? The full frame fallback has to be exercised deliberately, not discovered.

Answered since this was first written: the captured frame is 960x1080 (§7 option 1); `/dev/video2` is
the down looking camera; the debug image writes of §2 are **not** what made this run slow; the pipeline
that ran is `R0402` from the machine's own `vision-settings.xml`; and its stages are now timed, §3.
