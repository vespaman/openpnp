# Machine test: parallel bottom vision branch

Written 2026-09-27. To be run on the real machine from branch `parallel-bottom-vision`.
Companion of `SALVAGE-PLAN.md` (what was ported and hardened) and `FORK-NOTES.md`.

## What this test is and is not

It verifies the **port and the hardening**, not the idea. Parallel capture with displacement was
run on hardware in the checkout this came from (`6343c999e9`). What has never run on hardware is
*this* tree: the port onto `upstream/test`, plus the hardening commits, plus a new setting.

What could realistically be broken by the port:

- the hardening commits themselves (`8190ae9c2a`, `dec31fedef`, `68848cfd5d`, `92c971eb18`,
  `a939b4e3df`) - they are meant to be behaviour-neutral, and if one of them is not, this run is
  where it shows;
- the six months of upstream drift between the two bases. Checked: 24 commits, and none of them
  touch the camera, settling, bottom vision, nozzle tip or motion code. The one vision change is a
  gamma lookup table in `DetectRectlinearSymmetry` (upstream `d4e287a8a6`); it only matters if a
  pipeline of a part in the test job actually uses that stage;
- the new `machine.xml` elements (`parallel-alignment-enabled` and the displacement settings)
  surviving a save/restart.

And three things that were never verified anywhere, so they are tested here for the first time:
memory over a long run (R4), abort/retry behaviour (R6), and the post-rotate warning path (R7).

**Placement accuracy is deliberately not measured.** Absolute placement error on this machine is
dominated by other things (mechanics, feeders, nozzle tilt, the calibration you did last); a
difference of a few hundredths of a mm cannot be attributed to a code change, and chasing it costs
a day. What is measured instead is whether the machine still *behaves*: does it place, does it
reject, how long does it take, does it run out of memory.

## What is already automated

Part of this list is a JUnit test now, and should not be repeated by hand unless a machine result
contradicts it. From the repo root, one class at a time (see `SALVAGE-PLAN.md` for why not the whole
suite at once):

```
JAVA_HOME=/opt/android-studio/jbr ~/tools/apache-maven-3.9.6/bin/mvn -B -o \
    -Djvm=/opt/openjdk-jre-bin-17/bin/java test -Dtest=ParallelAlignmentTest
```

| test | covers | of this document |
|---|---|---|
| `ParallelAlignmentTest` | two nozzles really align concurrently, results are matched to the right nozzle, one nozzle alone falls back to sequential, a part with no vision is placed without a camera call, repeated batches on one machine stay consistent | 1, 2 (mechanics), 7 |
| `ParallelAlignmentLightControlTest` | lights on before the shot and off after the batch, in parallel, sequential, mixed and nested batches | 6 |
| `CameraBatchOperationTest`, `CameraBatchNestingTest` | the batch bookkeeping and what happens when a batch opens inside a batch | 1, 6 |
| `BottomVisionSettingsRoundTripTest` | `parallel-alignment-enabled`, the four displacement settings and the `CropCentered` stage surviving save/restart of `machine.xml` and `vision-settings.xml` | 8 |
| `CropCenteredTest` | the crop is centred and its output is in millimetres like the pipeline it sits in | 7 of `PRE-CHECK-ASSESSMENT.md` |
| `ReferenceJobProcessorRetryTests` | feeder, pick and place retries and failover | 5 (retry half) |
| `JobAbortTest` | aborting a job ends the camera batch and the light with it, discards the part that was picked, leaves the batch bookkeeping usable, and the same job started again places what is left | 5 (abort half), 6 (the aborted half, as far as a simulation can see it) |
| `check_run.py` | the log greps and timings of sections 1, 2 and 3 for a pair of runs, in one command | 1, 2, 3 |
| `align_table.py` | one line per analysed shot - camera, rect, angle, accepted offsets, part size, pipeline time - so a shot of one worker thread is never read as a shot of the other | 2 (offsets), 3 |

Three more tests are worth writing and are not written; see `LATER-TESTS.md`: the crop over a size matrix
(the vision setting on this machine is shared by 27 parts and only one of them was ever looked at), the
outside-the-crop failure of `log_b.txt` as a regression test, and leak proxies over a few thousand cycles,
which is what §4 should have been.

What stays on the machine is what a simulated machine cannot see: that the offsets are the size they
should be for these parts and this camera, that the lamps actually come on at the right moment, that
the machine does not knock anything over, and anything involving a real part being lost.

## Before starting

The machine, as far as this test cares: one head, two nozzles (N1, N2) sharing the X and Y axes and
therefore fixed relative to each other, separate Z and rotation; two fixed up-looking cameras, N1↔Up1
and N2↔Up2; pre-rotate required and enabled; part height from contact probing. The distance between
the cameras is close to, but not equal to, the distance between the nozzles - that difference is what
"displacement" is about, and it is the reason both cameras can be used in one pass at all.

1. `git checkout parallel-bottom-vision`, build the jar.
2. Copy the working `config/` directory aside, then copy it into the configuration directory this
   build uses. Nothing else changes between runs.
3. Log panel level: **Debug** (the file is `<config-dir>/log/OpenPnP.log`).
4. Note down and keep for the whole session: camera exposure/gain, lighting, nozzle tips, feeder
   setup, and the job. Same everything.
5. `machine.xml` settings used below:

   | Run | `parallel-alignment-enabled` | `displacement-enabled` |
   |---|---|---|
   | A | `false` | `false` |
   | B | `true` | `false` |
   | C | `true` | `true` |
   | D | `false` | `true` |

   What the runs are, as of this branch (see R14 in `SALVAGE-PLAN.md`):

   - **A** is the classic upstream behaviour: one nozzle walked to its exact shot location at a
     time, settling every time. Compare A with upstream, not with the old `multi-bottom-vision`
     timings.
   - **B** is the parallel path with every nozzle still driven to its exact shot location.
   - **C** is the parallel path where the head parks once and the nozzles other than the settle
     nozzle are photographed where they happen to be, their calibrated camera offset plus runout
     being compensated in the analysis.
   - **D** is the sequential path with the small-move saving of `multi-bottom-vision` restored: a
     nozzle already within `displacement-max-distance` (and the rotation and Z limits) of its shot
     location is not moved at all, the picture is taken without settling and the difference is
     compensated in the analysis. Only for pre-rotate and only for single-shot parts, so a
     composited part still moves. Look for `compensating` in the log; if a run of D shows as many
     moves as A, the limits are too tight or the nozzle really is far from the shot location.

   Run D is the one to watch for accuracy: it is the only one where the machine deliberately
   measures a part from a place that is not the shot location, on its own.


The test job: more than one bottom vision camera in use, more than one nozzle per camera, at least
~100 placements, more than one part type, and at least one part whose package uses vision
compositing (multi-shot). A production job of yours is ideal - do not build a synthetic one.

## 1. Which path ran

For each run, in `OpenPnP.log`:

```
grep -c "Using parallel alignment" OpenPnP.log          # expect: run A and D 0, runs B and C >= 1
grep -c "Using sequential alignment" OpenPnP.log        # run A and D >= 1
grep -c "nozzle not moved to the shot location" OpenPnP.log   # run D >= 1, run A 0
grep -c "displacement (sequential)" OpenPnP.log         # run D >= 1, run A 0
grep -c "displacement (parallel)" OpenPnP.log           # run C >= 1, run B 0
```

If run B or C shows sequential on a multi-camera machine, stop: the setting is not being read, or
the camera pairing does not see more than one camera (`VisionUtils.getBottomVisionCamera`).

## 2. Functional equivalence (the actual pass criterion)

Per run A, B, C record:

| | A | B | C |
|---|---|---|---|
| Job completed without abort | | | |
| Placements attempted / completed | | | |
| Vision rejections: `Offsets too large` | | | |
| Pick failures / vacuum errors / retries | | | |
| Parts that look misplaced afterwards (visual, count) | | | |
| Wall clock time of the job | | | |
| `Parallel alignment failed, falling back` count | 0 | | |

Pass:

- B and C complete the job with the **same or fewer** vision rejections and pick failures as A. A
  handful of extra rejections is a failure, not noise: the alignment is what decides those.
- Cycle time of B and C is not worse than A. Faster is the point of the feature; if it is the same
  or slower, note it and keep the log - that is a finding, not a pass.
- `Parallel alignment failed, falling back to sequential alignment for all` never appears in a
  normal run. If it appears, the interesting part of the log is the 50 lines before it.

The one numeric comparison worth doing, because it is easy and it is self-consistent rather than
absolute: for a few placements that appear in all the runs, look at the `Offsets accepted {...}`
lines. They should be of the same order (say within 0.05 mm / 0.5° of each other). If they agree,
the paths agree. If one of them is out by a millimetre, something in the displacement or camera
pairing is wrong. Do not push it finer than that - you cannot measure it finer anyway.

## 3. Warnings that must not appear

```
grep -n "exceeds the tolerated" OpenPnP.log
grep -n "uses post-rotate but the pre-captured image" OpenPnP.log
```

- `Bottom vision displacement of ... exceeds the tolerated ...` (`ReferenceBottomVision.java:756`)
  means the shot was taken from further off than `displacement-max-distance` allows. Cause is
  normally the camera calibration or nozzle tip runout, not this code, but it must be zero in a
  healthy run.
- `Bottom vision of nozzle ... uses post-rotate but the pre-captured image ... is displaced by ...`
  (`:770`) is the known unfixed gap: in post-rotate the displacement is not compensated. This machine
  is a **pre-rotate** machine (required by the setup, and enabled), so this must never appear. If it
  does, a part or a pipeline has slipped to post-rotate - that is a configuration error to fix, not a
  warning to live with. Note it in the results either way.
- `Cannot reset the parameters of pipeline stage pDetail : Cannot invoke "org.openpnp.spi.Camera
  .getUnitsPerPixel()" because "camera" is null` appeared sixteen times in the first real run, twice
  per pipeline clone, in every pipeline that has a size parameter (`pDetail` in most of them). It was
  the parallel path resetting the pipeline of the vision settings on every clone - see R15 - and it is
  fixed in `a4f42fb1dd`; a DEBUG line rather than a fault, but it must be gone in the next run.

## 4. Long run and memory (R4)

Not run. A long run was dropped on 2026-09-29: the branch changes no allocation that is not per
placement, and the RSS sampling of an eleven second job says nothing. If a real job ever grows
thousands of placements, sample the resident set size of the JVM every few minutes over an hour and
look for a slope; a flat curve is the pass.


While run C is going, sample the process memory:

```
while :; do echo "$(date +%s) $(ps -o rss= -p $(pgrep -f openpnp | head -1))"; sleep 5; \
done > /tmp/pbv-rss.log
```

Pass: RSS rises while the job runs and then flattens. A line that climbs steadily across hundreds
of placements, run after run, is the native `Mat` leak this is guarding against; keep the log and
the RSS file.

## 5. Abort, resume, retry (R6)

`JobAbortTest` covers the abort of a job between two steps: the camera batch and the light with it are
given back, the picked part is discarded, the batch bookkeeping still works afterwards, and the same job
run again places the rest. It cannot abort *during* a step, which is the interesting moment, because the
aligner only owns a batch inside one step of the job processor; nor can a simulated machine notice a part
that is not there (`TestActuator` answers the vacuum sense with a constant). So the following is still
machine work:

1. Start the job, abort it in the middle of the alignment phase, then resume / restart it. It must
   align again properly - no placement skipped, no image of the previous run used.

   Neither control lands inside a capture, and that is not a defect to chase. `Pause` is checked between
   steps of the job processor, and if a motion is under way the loop keeps stepping until that motion is
   complete (`JobPanel.jobRun()` and its `isSteppingToNextMotion()` test); `Abort` calls
   `ReferencePnpJobProcessor.abort()`, which is `synchronized` on the job processor, so it waits for the
   current step to return before it ends the camera batch and cleans up. The alignment phase is about
   150 ms, so aiming at the shutter is not a thing a hand can do. What is reachable, and what is worth
   reaching for: drop the feed rate to about 10 % so the moves are long, and abort while the head is on
   its way to or from the vision position.
2. Repeat three times, at different moments (during capture, during a move, while a part is being
   placed).
3. Then: put a nozzle to a feeder it will not pick from and start the job. The machine must stop on the
   missing part - this is the part-still-on-nozzle check the parallel path had lost and `92c971eb18` put
   back. If it calmly tries to place air, that is a failure of this branch and a pass of the old behaviour.

   Note what the machine is configured to do before concluding anything from this: on `pn52` on
   2026-09-29 every nozzle tip has `part-on-check-after-pick` true except `502`, but
   `part-on-check-align` and `part-on-check-before-place` are **false on all of them**, with
   `method-part-on` `Absolute` and the sense actuators `AVACS1`/`AVACS2` wired to the nozzles. So a part
   lost *after* the pick is not detected, on any tip, by configuration and not by this branch: lifting the
   part off the nozzle will end with the machine placing air and nothing logged wrong. If the lost in
   transit case is the one to verify, tick *Check Part On* at *Align* (or *Before Place*) on the tip the
   test job's `C0402` uses, restart, and only then lift the part - and say in the run notes that the
   setting was changed for the test.

## 6. Lights

After a finished job and after an aborted job, all camera lights must be off
(`CameraBatchOperation`). An aborted job that leaves a light on is a bug worth reporting.

On this machine `Upp1` and `Upp2` share one light actuator, `UPLED`, so the two up cameras can never be
lit or switched independently and every light off is a deferred one until the batch covering both ends.
The batch also spans the move after the alignment, so holding the light on for a second or two is normal;
holding it on after a job that has stopped is not. See the `log_d.txt` entry below for how long it can be
while the machine is merely idle between two steps at low speed.

## 7. Single camera

Disable all but one bottom vision camera (or run a job whose nozzles all use one camera). The
behaviour must be that of run A exactly, including for a single placement in the batch. This is the
case that must not change for anyone else either.

## 8. Settings round trip

Automated by `BottomVisionSettingsRoundTripTest`. Keep the manual half only if a machine refuses to
come back with the switch on, which is a machine configuration problem and not a code one.

Set `parallel-alignment-enabled` to `false`, save the machine (`Machine` → `Save`), restart, and
check the value is still `false` and that `machine.xml` contains

```xml
<parallel-alignment-enabled>false</parallel-alignment-enabled>
```

Same for the displacement settings. Watch the log at startup for XML/unknown element complaints.

## 9. What stresses the crop, and what does not

The crop is a stage in the *bottom vision* pipeline: it cuts a window around where the part is expected on
the **nozzle**, in front of the up-looking camera. The board location, fiducials and the placement
coordinates have nothing to do with it - bottom vision runs before any of that and reports an offset
relative to the pick. Moving the board tests the placement, not the crop.

What the crop has to tolerate is the part sitting off-centre on the nozzle, and the size of that is already
measured: the eight accepted offsets of `log_c.txt` top out at 0.26 mm, and at the ~37 px/mm of this camera
(`1080x926` over about 29 x 25 mm) the 200 px window leaves about 2 mm of margin around an 0402. So the
realistic case is proven by the successful run and the margin over it is roughly eight to one.

If the margin is to be stressed deliberately, stress it where it lives:

1. Pick the part (Feeders or Parts panel, *Pick*), and repeat it ten times. The scatter of the reported
   offsets is the quantity the crop is sized against; it should stay in the tenths of a millimetre.
2. Put a part on the nozzle by hand, visibly off-centre, and run the alignment anyway - the part must still
   be found, and the reported offset must be about the displacement you made, not nonsense. That is the
   `log_b.txt` failure mode in slow motion, and it is a hand test because nothing on the machine produces
   it on purpose.
3. Beyond the crop edge it must fail with no result, not with a confident wrong number - which is a test to
   write, not a test to run by hand; see `LATER-TESTS.md`.

## Recording

Put the logs of each run in a directory per run (`~/pbv-test/A/`, `A2`, `B`, `C`, ...) together with
the `machine.xml` used, and fill the table in section 2. If something fails, the useful things to
keep are: the log, the settings, and *which* of the checks above failed - do not clean up before
copying the log out.

Copy the **whole configuration directory**, not only `machine.xml` and `vision-settings.xml`. The
bottom vision pipeline a part actually runs is the per-package one, and those live in `packages.xml`;
without it, the pipeline that produced the timings cannot be read by anyone who was not standing at
the machine, which is exactly what happened to the analysis of the first run.

## Results

### 2026-09-28, first run on the machine (`tests_2026-09-28/log_a.txt`)

The file is called `log_a.txt` but it is **run C** by the definition above: the `machine.xml` copied
with it has `parallel-alignment-enabled` `true` and `displacement-enabled` `true`, and the log says
`Using parallel alignment` four times and `Using sequential alignment` never. The log and the two
settings files are committed in `tests_2026-09-28/` so the numbers below can be rechecked; keep later
runs in a directory of the same shape (`tests_<date>/` with the log and the settings), which is also
why `.gitignore` has an exception for `tests_*/machine.xml` - `machine.xml` is otherwise ignored
everywhere in this repository.

Small job: 8 placements, all `C0402-22p/50V`, two nozzles, two up-looking cameras (`Upp1`, `Upp2`),
two placements per alignment step.

| Check | Result |
|---|---|
| Job completed without abort | yes, `Job finished 8 parts in 11.0 sec. This is 2,613.7 CPH` |
| `Using parallel alignment` | 4 (one per alignment step, 2 placements + 2 cameras each) |
| `Parallel alignment failed, falling back` | 0 |
| `Offsets too large` | 0 |
| `Offsets accepted` | 8, one per placement |
| Pick failures, vacuum errors, retries | 0 |
| `displacement (parallel)` | 8 |
| `displacement (sequential)`, `nozzle not moved to the shot location` | 0, as expected: with two nozzles the pre-captured path always moves onto the shot location, R14 only applies when a nozzle is already there |
| `exceeds the tolerated` | 0 |
| `uses post-rotate but the pre-captured image` | 0 |
| `ERROR` / `WARN` lines in the whole log | 0 |
| `Cannot reset the parameters of pipeline stage pDetail` | 16 - see R15, fixed in `a4f42fb1dd` |

Where the time of one alignment step goes (first step, `18:03:09.925` to `18:03:10.274`):

| Phase | Time |
|---|---|
| nozzle order optimisation | 3 ms |
| head move to the alignment position | 263 ms |
| capture, including the settling of `Upp2` | 36 ms |
| the two pipelines processed in parallel | 48 ms |
| **alignment step total** | **349 ms** |

The alignment is not the part of the cycle this feature can speed up much on this machine: three
quarters of it is the one move that both nozzles share, and it is shared precisely because the
alignment is parallel. The two pipelines did run at the same time - their `MaskHsv` stage lines
interleave (`10.247`, `10.248`, `10.252`, `10.254`) and both finish within 2 ms of each other - which
is the point of the branch.

Two things in the log that look wrong and are not:

- `Batch END level 1 'align step' - expected: parallel-align-precapture` is the diagnostic of
  `CameraBatchOperation` lying about nested batches, see R16, fixed in `0f00b3f06f`.
- `Camera 'Upp1' registration attempted but no batch in progress` followed by `turning OFF light (no
  batch active)`: the pre-capture batch is closed when the images are taken, but the pipelines are
  processed after it, and the end of the processing calls `actuateLightAfterCapture()` on a camera
  whose batch is already gone. The light is off either way. Harmless, and the same happens upstream.
- `UPLED.actuate(true)` a millisecond after `Job finished`. The ring light does not go off with the
  job, it goes off when the machine is disabled (`M811 ; Turn off UPLED`). This is the preview: the
  cameras have `suspend-preview-in-tasks` and `user-action-light-on` on, so when the machine falls
  idle the preview resumes and `AbstractBroadcastingCamera.captureCameraView()` turns the light back
  on for it. Upstream, and nothing to do with the batch bookkeeping - but it means check 6 (lights
  off after a job) has to be judged with the preview closed, or with the machine disabled.

Not yet done: runs A, B and D, the abort and missing-part checks, the long run with the RSS sampling,
the single-camera case and the settings round trip.

### 2026-09-28, the pipeline is cropped to the part (`vision-settings.xml` on the machine)

Because §3 of `PRE-CHECK-ASSESSMENT.md` measured the analysis at 23 ms on a megapixel frame for a 30 px
part, the new `CropCentered` stage was installed on the machine ahead of the next run. Two things had to
change, and both are reversible; OpenPnP was closed for it (`pgrep -af openpnp-gui` empty) because the GUI
rewrites the settings it has open.

1. **The build.** The machine starts OpenPnP with `./openpnp.sh`, which runs
   `<repo>/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`, i.e. the build directory of this very repository
   (the share is mounted there as `/mnt/public/stuff/OpenPnP/openpnp`). `mvn package` was run here at
   23:04, so the jar now contains `org/openpnp/vision/pipeline/stages/CropCentered.class`. That ties the
   settings file to this build: a jar built from a branch without the stage will not know the stage this
   pipeline now names, so restore the settings file too, not only the jar, if this experiment is undone.
2. **The pipeline.** In `~/.openpnp2/vision-settings.xml`, settings `BVS1750dee5e50a6b58` (`R0402`), one
   stage was inserted directly under the capture and nothing else in the file was touched:

   ```xml
   <cv-stage class="org.openpnp.vision.pipeline.stages.CropCentered" name="crop"
             enabled="true" width="0" height="0" margin="0" minimum="200"/>
   ```

   19 stages became 20. `width`/`height` of 0 means "take it from the part", which is
   `footprint.maxWidth`/`maxHeight` as `preparePipeline()` sets them from the mask radius; `minimum="200"`
   is the floor, about 7.7 mm on this camera, deliberately far above what a 0402 needs because the same
   `R0402` setting is shared by 27 parts here, `C0402` and `C0603` both.

Backup and undo, on the machine:

    cp ~/.openpnp2/vision-settings.xml.bak-before-cropcentered-2026-09-28 ~/.openpnp2/vision-settings.xml

The backup is the file as it ran the job of the previous entry, byte for byte (verified with `diff` before
installing). Deleting the `crop` stage in the vision pipeline editor does the same thing through the GUI.

What the run has to show, in order of importance:

- **The part is found, every time.** `Offsets accepted` 8, no `Parallel alignment failed`, no retries. A
  crop that is too small does not fail loudly: the masks black out the part, no contour survives, and the
  alignment falls back with a much larger offset or an error.
- **The offsets did not move.** Compare the accepted offsets of the same placements against
  `tests_2026-09-28/log_a.txt`; they should agree to well under half a pixel, which is what the crop
  measurement of §3 predicted. A systematic half pixel would mean the crop is not centred.
- **The cost.** `CropCentered` and the stages after it are debug logged per stage, and the
  `[ALIGN-TIMING]` line gives `prepare` and `process` per shot - it needs debug level logging for
  `org.openpnp.machine.reference.vision.ReferenceBottomVision`, or the line simply is not there. The
  expectation is a `process` of about 20 ms, of which the `ImageCapture` stage keeps its ~2 ms and the
  stages after the crop keep theirs. `log_a.txt` predates the `[ALIGN-TIMING]` line and has none of its own,
  so what can be compared across the two runs is the pipeline window, the tail of the alignment step, not a
  per shot `process`.
- Note the CPU governor: it is on `performance` now, it was on `powersave` for `log_a.txt`. Say so in the
  run notes, or the two runs are not comparable.

The run happened the same evening, and the three bullets come out one good, one good, one broken
(`tests_2026-09-28/log_b.txt`):

- **The cost was as predicted.** `process 20.1 ms` against the 48-55 ms *window* of `log_a.txt`, with the
  capture and the recall unchanged, i.e. the analysis did most of what the off-machine measurement said it
  would.
- **The part was found**, and found where it should be: `MinAreaRect` in the 200 px crop at
  `(97.5, 101.5)`, which is `-0.096, -0.051 mm` off the center of that crop.
- **The offsets moved by 15 mm.** The same find was reported to the job as `-11.640, +9.544 mm`, so
  `center offset 15.05 > 1.0` rejected it, the parallel attempt fell back to sequential, and the retries
  then presented the part somewhere the crop was not looking and the job ended with no result.
- **The sequential fallback is far more expensive than the parallel shot, and this log measures it.** The
  twelve retries of `log_b.txt` report a `process` of 185-232 ms for a pipeline that costs 20.1 ms in the
  parallel shots, and the per stage lines put all of it in one stage, `0`, which is `ImageCapture`:
  `0=216.2` of `process 218.6 ms`. The parallel path hands the pipeline a frame it has already taken
  (~2 ms of capture); the sequential path calls the camera itself and sits out the settle inside the
  pipeline, and `Upp1`/`Upp2` are configured with `settle-time-ms="250"`. So a fallback shot costs about ten
  times a parallel one, which is worth knowing both when reading a bad run and when weighing how much the
  parallel path is worth. It also means the `process` of a fallback run must have its capture stage
  subtracted before it is compared with anything: `check_run.py` reports the two apart.

The settings were therefore reverted with the command above and the machine is back on the 19 stage
pipeline; `grep -c CropCentered ~/.openpnp2/vision-settings.xml` is 0 again. The jar was left as built,
which is harmless while no pipeline names the stage.

The cause was not the crop but the code that reads a crop, and §7 option 1 of
`PRE-CHECK-ASSESSMENT.md` has the arithmetic: the pixels of the crop were converted to millimetres against
the center of the whole camera frame. Fixed in this branch by carrying the analysed image size with the
detected rect and by converting `Location` pipeline properties against the working image, with a
`CropCenteredTest` that runs the real `VisionUtils` conversion both ways.

Checked off the machine before re-running on it, because the offsets are the thing that broke: the same
captured frame through the live `R0402` pipeline, with and without the crop stage, and the millimetres
printed both ways (§7 option 1 of `PRE-CHECK-ASSESSMENT.md`). The crop and the full frame agree to the
micron after the fix, and reading the crop against the frame is off by the 15 mm class. On the OpenPnP PC:

    javac -cp "classes:lib/*" -d out Benchmark.java Crop.java
    for p in p_full.xml p_cropstage.xml; do
      java -cp "out:classes:lib/*" org.openpnp.vision.pipeline.Benchmark $p 5 \
           0.03859462904927454 0.03412551850076619 0.1 2.83
    done

### 2026-09-29, the crop again, with the fix (`tests_2026-09-28/log_c.txt`)

The jar was rebuilt from this branch at 08:59, which is the jar the machine runs, and the `crop` stage was
put back into `R0402` the same way as before - OpenPnP closed first, `vision-settings.xml` backed up to
`~/.openpnp2/vision-settings.xml.bak-before-cropcentered-run2` so that this run is reversible too. The
offline millimetre check of the previous entry was run first, and passed.

The job then ran clean, and this time all three questions came out the right way:

- **The part was found, every time.** `Offsets accepted` 8 of 8, no `Parallel alignment failed`, no retry,
  no no-result. The only WARN-shaped lines in the file are the two known benign ones: the push-pull feeder
  reporting a 0.206 mm vision offset against a previous 0, and `GcodeDriver$ReaderThread` "Read error while
  disconnecting (expected)".
- **The crop is in the path, and it is centred.** The rects come back at `(100.5, 92.0)`, `(100.5, 96.5)`,
  `(95.6, 102.1)` and `(104.5, 94.5)` - within 5 px of the middle of the 200 px crop - on a part measured at
  37x18 and 37x19, i.e. the 37x19 and 36x19 of `log_a.txt`. That is the half pixel test of the bullet above,
  passed by a margin of ten, and it is also the proof of the frame size: a crop centred on the analysed image
  puts the part in its middle, and the same part is found at `{537, 465}` in the uncropped run, so the
  analysed frame is about 1080x926.
- **The offsets are the size they were.** The eight accepted offsets of `log_c.txt` top out at 0.26 mm
  linear and 4.3° rotation; those of `log_a.txt` top out at 0.25 mm and 4.5°. They cannot be compared
  placement by placement, because they are measured against the pick and the pick scatter of this feeder is
  of the same order as the difference, but 15 mm is not on offer any more and neither is anything else of
  that class.
- **The cost.** `process` per shot is 8.1-13.2 ms, the first two shots of the first alignment, 19.0 and
  22.7 ms, still carrying the pipeline warm-up, and 110 ms for the eight shots of the job together.
  `log_a.txt` has no `[ALIGN-TIMING]` line to set against that, so the comparison is the window below:
  49-57 ms become 19-35 ms.

What that buys is smaller than it sounds, and it is worth writing down before someone quotes the 4x at the
job: an alignment step runs from `Moving head to alignment position` to the last `Vision.PartAlignment.After`
at 311-357 ms here against 314-373 ms in `log_a.txt`, because the step is mostly the ~260 ms move to the
alignment position and a 36-55 ms capture window, and the pipeline is only the tail of it. That tail, measured
from `Camera batch operation ended` to the last `After`, is where the crop lands: 49-57 ms in `log_a.txt`,
19-35 ms here. The steady state is correspondingly a shade quicker - alignment to alignment 1.96/1.90/1.83 s
against 1.97/1.92/1.87 s, and first alignment to job end 7.04 s against 7.15 s.

The headline of the run is the other way round and it is not vision: the job is reported as 11.6 s against
11.0 s. `log_c.txt` opens with a push-pull feeder calibration that `log_a.txt` did not pay -
`09:22:38.850 calibrating sprocket holes pass 0` to `09:22:39.802 new vision offset (0.204, 0.028) vs.
previous (0.000...)`, about a second of feeder vision, and the tail of the job and every steady state cycle
of it are as fast or faster than the baseline. The feed count says why: 947 in `log_a.txt`, 957 in this one,
i.e. the feeder had been calibrated in between and had to do it again. Job totals on this machine move by
half a second on their own; only the per shot numbers of `[ALIGN-TIMING]` should be quoted between runs.
Governor: `performance` for this run, `powersave` for `log_a.txt`, so the two are *not* comparable on
absolute milliseconds - which is another reason to read the per stage lines and not the wall clock.

State at the time of writing: the stage is still installed in `R0402`, the jar is the one built from this
branch, and the run C checks of the previous entries are still outstanding. Undo is

    cp ~/.openpnp2/vision-settings.xml.bak-before-cropcentered-run2 ~/.openpnp2/vision-settings.xml

If the run is good, this stops being an experiment in one package and becomes a default: the other bottom
vision settings on the machine, the stock settings in `src/main/resources`, and the pipeline templates the
setup wizard writes all want the stage, and it is worth proposing upstream on its own. The floor is the
open design question: `minimum` is in pixels today, so a 200 px floor means a different number of
millimetres on every camera, and it is what held this pipeline at 200 px instead of the 105 px the part
actually needs.

### 2026-09-29, a turned part, and a part that was not there at all (`tests_2026-09-28/log_d.txt`)

Two runs of the test job at 10 % speed, 44.4 s and 133.6 s, with the parts interfered with by hand.
Read them one shot at a time, not one job at a time - `align_table.py` in this directory prints exactly
that, one line per analysed shot, because the two worker threads of a parallel batch write their lines
back to back and a grep for `Offsets accepted` pairs the wrong rect with the wrong camera.

- **A turned part is corrected, not rejected, and that is the feature.** Run 1 accepted
  `-44.911°` on N1 at 13:52:33: the shot shows the part at `36x17 * 45.0` px, dead centred in the crop at
  {104.5, 104.0}, with the size still `ok` at 0.96x0.47 mm. Run 2, after the part was turned by hand,
  accepted `11.034°`, `13.143°` and `17.252°`. Nothing in the accept path looks at the rotation at all:
  `Offsets accepted` guards the centre offset, the `size ok` line guards width and length, and the
  rotation is handed to the placement as a correction so the part lands square on the pad. There is no
  rotation tolerance to tick and no number of degrees that makes bottom vision complain. **Decided:** that
  stays as it is - a turned part being corrected is the feature, not a fault, and no max rotation setting
  is wanted.
- **The run did fail once, but not because of the rotation.** At 13:53:07 N2 had nothing in front of
  Upp2: four captures in a row with `findCountours=0.0` and `contours=0.0`, then
  `ReferenceBottomVision (C0402-22p/50V): No result found`, machine idle, job error dialog at
  13:53:07.672. The light was on and the camera was fine (the same camera detected the part again at
  13:53:28 after the re-pick), so there was genuinely no part on the tip. That is the correct behaviour,
  and it is also the one case the parallel path must not paper over, which it does not: the sequential
  fallback re-aligns, hits the same empty camera, and rethrows.
- **The fallback used to hide its reason.** The parallel attempt failed first, and all that was logged
  was `Parallel alignment failed, falling back to sequential alignment for all` - the exception was
  dropped. The cause was only recoverable later, from the rethrow of the sequential pass. The catch in
  `alignAllParallel()` now logs the cause, so a log says *which* of the two failed and why.
- **What the fallback costs.** The three sequential re-alignments of that step took 198.5, 945.0 and
  210.0 ms of `process` against 3-15 ms for the pre-captured shots of the same job, all of it in stage
  `0` (`ImageCapture`): the sequential path captures inside the pipeline and pays the camera settle
  there. See the `log_b.txt` entry above.
- **Lights: one actuator for both up cameras, and the batch bookkeeping is clean on this machine.**
  `Upp1` and `Upp2` share `UPLED` (`light-actuator-id="ACT1579799618401"` in `machine.xml`), so they can
  never be lit independently and the light off is always deferred until the batch that covers both ends.
  Every `Batch START` in both runs has its `Batch END`, including the run that errored, and nothing is
  left open at either job end. What the batch does do is hold the light on: the `align step` batch spans
  `StartCameraBatchOperation` to `EndCameraBatchOperation`, i.e. the alignment *and* the slow move that
  follows it, so at 10 % speed the up-light stays on for 11-12 s after the shots are taken, and after the
  error at 13:53:07 it stayed on for 20.6 s, until the retried step unwound the batch. That is not a
  leak - aborting closes it, which `JobAbortTest` covers - but it is what "the light is still on while I
  am looking at the error dialog" is.

### 2026-09-30, step 2 is in the jar: what `log_n.txt` has to show (`target/openpnp-gui-...jar`, 20:17)

The second camera no longer takes its one frame and calls it good; it grabs frames while the other
camera settles and keeps the first one inside the window that settling opened (§10 of
`REQUIREMENTS.md`). Nothing about the settings has to change for this run: `frameDumpEnabled` stays off,
the job is the same 8 placements.

    ./openpnp.sh          # on pn52, from the repo, as usual
    tee tests_2026-09-28/log_n.txt        # two or three jobs, then copy the log

    ./check_run.py tests_2026-09-28/log_m.txt tests_2026-09-28/log_n.txt

What has to be true, in the order one would notice it:

- `frames kept inside the window` is about one per aligned batch, each entry of the form
  `Upp2 f4@38ms +17ms in 4 grabs`, and `window margin mean` is a positive number of a few tens of
  milliseconds. The frame index is how many frames were thrown away to get it, which is the cost of
  the honesty and should be small.
- `frames with no window` is `0`, `frame refused as not still` is `0` and `parallel fell back` is `0`.
  Step 1 had four of each per job; a run that still has them is not a failed run, it is a run that says
  the settling of that batch genuinely did not agree - the `[SETTLE-WINDOW]` line says which camera and
  after how many grabs.
- `alignment step (move->after)` stays where `log_m` left it (355-456 ms) and the pipeline tail stays at
  81-112 ms. The kept frame is the one that was being exposed while the settling reached its verdict, so
  this step should cost nothing new; if it costs 10-20 ms per batch, that is one frame too many being
  thrown away after the window opened, and the `[SETTLE-WINDOW]` margins will show it.
- The head should still be over the cameras for about 330 ms per batch. If it is noticeably longer, the
  deadline (settling timeout + 100 ms) is being reached rather than the window, which is a bug and not a
  property of the mechanics.


### 2026-09-30, the pre-check back inside the ringing: what `log_o.txt` has to show (`target/openpnp-gui-...jar`, 21:49)

`log_n` measured the second camera honestly and showed the pre-check of that camera running after the
settling, which is stage 1 upside down (§10 of `REQUIREMENTS.md`). The check now starts on the first
frame that comes out of the camera and the alignment still takes the frame from inside the window, so
the two frames of that camera are different frames on purpose. Same job, same settings.

    ./openpnp.sh          # on pn52, from the repo, as usual
    tee tests_2026-09-28/log_o.txt        # two or three jobs, then copy the log

    ./check_run.py tests_2026-09-28/log_n.txt tests_2026-09-28/log_o.txt

What has to be true:

- `pre-checks before the window` is `N/N`. For the camera that does not drive the settling, the
  `[PRE-CHECK]` line must appear *before* its `[SETTLE-WINDOW]` line, because the check went in with the
  first grab and the window line is only written when the hunt is over. Both lines carry the camera's
  name now rather than its id, which is what lets the analyzer pair them at all.
- In that `[PRE-CHECK]` line, `frame=1/%d` where the denominator is the number of grabs of the matching
  `[SETTLE-WINDOW]` line. That is the whole change in one field: the first frame was checked, that many
  were grabbed. The settling camera's own line keeps its old meaning.
  (Corrected on the same evening, see the `log_p` section below: the check can report before the hunt is
  over, and a denominator taken from the previous placement's settling was worse than no number, so the
  line says `frame=1/?` while the hunt is still running and the `[SETTLE-WINDOW]` line is what says how
  many grabs it took.)
- `verdict` is still `Ok` and the size fields still look like the part. The frame the check saw is one
  the head was still ringing on, so a `SizeOut` here is worth reading twice: presence and size are the
  two things that survive the ringing, and if one of them does not, the check is looking at the wrong
  thing.
- Everything else stays where `log_n` left it: `frames kept inside a window` one per batch, `frames with
  no window` 0, `parallel fell back` 0, `alignment step` in the 330-350 ms band. The check costs the hunt
  nothing: it runs on the vision executor while the grabbing thread does what it was doing.
- One thing to expect and not mistake for a regression: the `[PRE-CHECK]` line of the second camera is
  earlier now, so `queue=` may show a few milliseconds it did not show before, because the check and the
  settling want a CPU at the same moment. R7 is the one to watch: if the settling of that batch gets
  slower in step with it, the check is stealing from the wrong pocket.

### 2026-09-30, the hunt aims at the window instead of hammering at it: what `log_p.txt` has to show (`target/openpnp-gui-...jar`, 23:21)

`log_n` and `log_o` were honest about one more thing than anyone wanted: the settling of the camera that
drives it had become slower, from 34-40 ms to 66-70 ms, and the reason is in the frame gap, which the
analyzer now prints. It went from 11.8 ms between settling frames (`log_m`, where the other camera took
one quick frame) to 17-18 ms (`log_n`, `log_o`, where it took 4-7 of them). Two cameras on a bus, a CPU
and a light: frames taken while the head is still visibly moving are paid for twice, once by the camera
that throws them away and once by the settling that gets its frames later.

So the camera that hunts no longer grabs at full rate. It takes one frame immediately - that is the
pre-check's, and the ringing is where the pre-check works - and then it waits for the settling to have a
run of alike frames under way before it captures again, and once it has a frame it holds that frame while
the settling finishes saying whether it covers it. Same job, same settings.

    ./openpnp.sh          # on pn52, from the repo, as usual
    tee tests_2026-09-28/log_p.txt        # two or three jobs, then copy the log

    ./check_run.py tests_2026-09-28/log_m.txt tests_2026-09-28/log_o.txt tests_2026-09-28/log_p.txt

What has to be true, and this is the run where the numbers are the point rather than a side effect:

- `grabs` in the `frames kept inside the window` line drops from 4-7 to 2-3. The margin may be smaller
  than the 22-46 ms of `log_o` - the frame is taken as the window opens rather than after a while of
  hammering - and it must still be positive.
- `settling frame gap` comes back towards the 11.8-12.3 ms of `log_m`. This is the number the change was
  about. It is a property of the machine, not of the code, so it will not be identical between sessions;
  what must not happen is it sitting at 17 ms while the other camera is down to 2 grabs, because that
  would mean the frames were never the reason.
- `settling` therefore comes back towards 35-50 ms with the same `settling frames` count. Fewer frames
  and a shorter total are not the same improvement: the frames the settling needs are the mechanics, and
  the gap is the camera being slow to hand the next one over.
- `alignment step` stays in the 330-350 ms band or better. It was 348 ms in `log_o` and 405 ms in
  `log_m`; the whole design has never been about the settling line, it is about this one, so a settling
  that gets back to 40 ms while the alignment step gets worse is a bad trade and should be read as such.
- `frames kept inside a window` still one per batch and `frames with no window` still 0: waiting for the
  window must not turn into missing it. `parallel fell back` 0.
- The `[PRE-CHECK]` line of the second camera now says `frame=1/?` while the hunt is going on (see the
  correction above) and `pre-checks before the window` stays `N/N`.
- One way this can fail that is worth naming: if the settling's run of alike frames only ever forms in
  its very last frame, the waiting camera starts its capture at the same moment the settling finishes, and
  the batch ends up as slow as the sequential one. `margin` near 0 ms together with an `alignment step`
  back near 400 ms is that case; it would mean the alike run is too late to aim at, and the aim would
  have to move to something earlier (the first frame under the threshold rather than the second).

What `log_p.txt` showed, on the same evening:

| | `log_m` | `log_o` | `log_p` |
|---|---|---|---|
| grabs per frame kept | 1 | 5.5 | **2.0** |
| settling frame gap | 11.8 ms | 18.3 ms | **12.3 ms** (14.0 worst) |
| settling | 34.5 ms | 65.6 ms | **46.9 ms** |
| settling frames | 5.1 | 4.2 | 3.4 |
| alignment step | 405 ms | 348 ms | **331 ms** |
| frames kept inside a window | 8 | 8 | 8 |
| frames with no window | 0 | 0 | 0 |

The gap is back where the single quick capture of `log_m` left it, so the frames taken next door really
were the whole of the settling's slowdown. The settling is not quite at `log_m`'s 34.5 ms because it needs
3-4 frames where `log_m` needed 5 of 11.8 ms each - the mechanics, not the camera. The margin of the kept
frame shrank to 23-29 ms as expected, and it is still positive in all eight batches. The alignment step is
the best it has ever been on this machine.

Three things in the log are worth naming, of which one is not ours:

- **`SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` and the two lines after it**, at the
  first camera capture of a session. Not new and not ours: `lib/slf4j-api-1.7.2.jar` is on the classpath
  because `webcam-capture` pulls it in, and there is no SLF4J binding next to it, so the first time any
  class asks SLF4J for a logger it prints those three lines to stderr - which the `SystemLogger` then
  dutifully files as ERROR - and falls back to a logger that discards everything. Nothing OpenPnP logs is
  affected, because OpenPnP logs through `java.util.logging`; what is affected is any library that would
  have logged through SLF4J, which now says nothing at all. The fix is to put a matching binding
  (`slf4j-jdk14` 1.7.2, which hands SLF4J to the same `java.util.logging`) next to it, and that needs a
  download: this machine's local repository only has `slf4j-jdk14` 1.5.6, which is too old to bind to a
  1.7.2 API. It is in `log_l` and in every session since, and in none of `log_c`..`log_j`. The dependency
  has been in the pom unchanged across all of those, so what changed is the assembling of `target/lib`
  rather than the code, and nothing in this design is involved either way.


- **one `verdict=SizeOut` on a turned part.** `[PRE-CHECK] ... size=0.45x0.97 expected=1.00x0.50 ...
  width too small: nominal 1.000, limit 0.620, measured 0.448`, twice in `log_n`/`log_o` and once in
  `log_p`, always for the part that is deliberately placed a quarter turn off. The rectangle the pipeline
  returns has no notion of which of its sides is the part's width: `17x37 at 0°` and `37x17 at 90°` are
  the same rectangle and OpenCV hands out either. The alignment's own size check compares width with
  width too - it is upstream, and it is a coin toss on a turned part - and in this batch the alignment
  happened to get `37x17` for the same part 26 ms later while the check got `17x37`. The check now asks
  both ways and says so (`verdict=Ok ... turned: ...`); the alignment is left alone, because changing what
  the alignment accepts is not this design's call to make. Expect `verdict=Ok` sixteen times a job and the
  word `turned` twice.
- **`check=-1.0 size=-1.00x-1.00 rot=0.0 offset=-1.00` on the one line that goes out before the check
  reports.** Those are not measurements, they are the absence of one, and they read as measurements of
  nothing. A field that was not filled in is now left out (`check=- size=- rot=? offset=?`), the line is
  logged at debug rather than warning - it is not a finding - and it says the check is still on the vision
  executor. The `[PRE-CHECK-LATE]` line that follows it 2 ms later in `log_p` is the verdict arriving as
  designed; one per job is normal. The analyzer's `pre-checks before the window` now pairs the two lines
  within one alignment step rather than anywhere later in the log - the pre-check of the next placement is
  not the verdict of this one - and it says 8/8 for `log_p`.

The two fixes are tested (`PreCheckReportTest`: a part drawn a quarter turn off must be `Ok` and must say
`turned`; a rectangle measured the other way round must fit, and one that is neither expected side must
still be refused), and with the second reading removed both tests fail with exactly the machine's message,
`width too small: nominal 2.000, limit 1.800, measured 1.250`.

## The run that produces the frames themselves (`-DframeDump`)

Everything so far has been read out of the log, which says what the machine concluded but not what it
looked at. The next step (departing before the analysis is finished) is only defensible if the analysis
can be shown, away from the machine, to reach the same conclusion twice on the same pixels - so the
frames have to be kept, and a PNG of a bottom camera says nothing by itself. The pipeline's own debug
stage writes `bv_source_<nsec>.png`, which cannot be replayed as evidence: no part, no camera, no claim
about the motion, and pairs that can only be matched by guessing from adjacent nanoseconds.

`FrameDump`, built for this in step 1, writes the frame and puts the whole claim in the name:

```
bv__Settled__C2__N1__R0805-1K__f4__a+1000.0__c+1082.0__w+1061.0__r+0.0.png
```

settled by agreement, camera `C2`, nozzle `N1`, part `R0805-1K`, the fourth frame the camera was asked
for, the head came to rest at 1000 ms, the frame was integrated until 1082 ms, the still window opened at
1061 ms, the placement wanted rotation 0. `FrameDump.parse()` reads a name back and refuses anything it
did not write, so a frame cannot quietly arrive with a shuffled history.

Start the run with the dump on:

```
./openpnp.sh "-DframeDump=$HOME/bv-corpus"
```

The property turns it on for every Bottom Vision setting at once, which is the point: this machine has
five of them, and a corpus taken with three ticked boxes is not a corpus but a mystery. `-DframeDumpMax`
caps it (4000 frames by default) so a session left running cannot fill the disk. The PNG encoding and
write happen on a thread of its own, and a queue that fills up drops frames and says how many, because a
machine that cannot write frames as fast as it takes them should lose frames rather than hold the head
over the camera. The copy of the pixels into memory stays on the capturing thread, since the frame stops
existing when that thread returns - so **the timings of a corpus run are not the timings to compare
against `log_p`**. One ordinary warm job is enough: about two frames per placement.

Say it worked by looking for `[FRAME-DUMP] frames go to /home/.../bv-corpus because -DframeDump is set`
at the first capture and one line per frame after it. Those lines were not in any earlier log for a
reason of its own: `FrameDump` logged through SLF4J, which on this machine has no binding and discards
everything (the three warning lines at the start of `log_l` onwards). It now logs the way the rest of
OpenPnP does, so the dump announces itself.

Copy away, together, when the job is done:

```
cp -r ~/bv-corpus tests_2026-10-01/framedump
cp ~/.openpnp2/machine.xml ~/.openpnp2/packages.xml ~/.openpnp2/parts.xml \
   ~/.openpnp2/boards.xml ~/.openpnp2/vision-settings.xml tests_2026-10-01/framedump/
cp <the log of that session> tests_2026-10-01/log_q.txt
```

The config files belong with the frames because replaying a frame means running *that part's* pipeline
over it, and the pipeline lives in the settings of the moment. A frame without the settings that made it
is a picture, not an experiment.

### The corpus run happened (`log_q.txt`, `bv-corpus/`)

`./openpnp.sh "-DframeDump=$HOME/bv-corpus"`, one job of four aligned batches, eight frames, two per
batch. The dump announced itself in the log as designed, and `check_frames.py` reads the eight names and
holds them against the log: the frame kept for each camera of each batch is there, its age after the rest
and the margin inside the window are the numbers the `[SETTLE-WINDOW]` line gives (batch 1: `Upp2` frame 2
at +86 ms, window opened at +58 ms, and the frame on disk says `a+230061721 c+230061807 w+230061779`),
and the frame number in the name is the grab count the `[PRE-CHECK]` line reports for that camera. No
frame is another frame twice. The job was a cold one (16.8 s) and its timings mean nothing, which was the
deal struck above.

### The replay happened: the frame and the config are the whole measurement

The config came over with the frames (`machine.xml`, `packages.xml`, `parts.xml`, `boards.xml`,
`vision-settings.xml`; the live set, and `pn52:~/.openpnp2` is where they will be for the next run), and
the eight frames then went through the pipeline of the run itself on a machine with no head, no lights and
no camera:

```
mvn -B -Dtest=FrameReplayTest -DfailIfNoTests=false test
```

The test loads that config, finds the part, prepares its pipeline the way the head does
(`preparePipeline` with the part's package and the camera brought down to the part's height), hands one
kept frame to the pipeline through `CapturedShot` so that `ImageCapture` never reaches for a camera, and
reads the rectangle out of the `results` stage. The assertion is the rectangle the log recorded for that
measurement:

```
Bottom vision part C0402-4n7/50V result rect { {97.96086120605469, 97.51771545410156} 36x18 * 2.2025983333587646 }
```

All eight frames reproduce it. The centres and the angles agree to the last digit OpenCV prints, which is
1e-15 of a pixel and not a distance anyone arrives at by being nearly right. Two things keep the
comparison from being literally bit-for-bit, and neither is a difference in the measurement: OpenCV prints
a rectangle's sides through `toString()` with the decimals dropped, and it has no opinion about which side
it calls the width, so one frame came back `18x37 * 0` against the log's `37x18 * 90` - the same four
corners, named the other way round.

What that buys is the precondition for the rest of the design. The frame plus the settings that were in
the machine at the time contain the entire measurement: nothing in the analysis needs the head to still be
over the glass. A saved frame can be re-judged away from the machine and the judgement is the machine's
own. Stage 2 - leaving before the alignment is finished - rests on that, and so does any future tuning of
a pipeline against frames kept from a production run.

One thing the corpus turned out not to be, discovered while making the test: `[PRE-CHECK]` measures the
*first* frame of a hunt (`frame=1/N`), while the frame the hunt keeps, and therefore the frame the dump
saves, is the last one it took (`f3`, `f4`, `f5`). The eight frames are evidence about the alignment's
measurement, not the pre-check's. The gap between the two readings of the same part is the motion the hunt
exists to take out - batch 1 on `Upp1` is 0.98x0.51 mm at -2.4 deg, 0.17 mm off centre on frame 1 and
0.974x0.488 mm at 2.2 deg on the kept frame. The test prints both columns and compares neither against
the other.

To do again, and it is the shape of the next corpus run rather than a fault in this one:

- **Dump the frame the pre-check measured.** A run whose dump is switched on early enough catches every
  grab, not the kept one, if the dump moves into the pre-check as well as the alignment; then the drift
  above becomes a measured quantity instead of an anecdote, and it is the quantity that decides whether
  an early departure can be allowed to answer for a still frame.
- **More than one part, and a warm job.** Eight frames of one 0402 on a cold machine say a frame replays;
  they do not say a pipeline replays across packages, which is what a changed threshold would have to
  survive.

## The run that has to happen next: the ringing frame too (`log_r.txt`)

The corpus above settles one question and opens another. It proves that a frame plus the settings of the
run contain the whole measurement, and it does that on the frame the hunt *kept*. It says nothing about
the frame the pre-check judged, because a hunt keeps its last frame and throws the rest away, and only the
kept one was written down. That difference is the quantity Stage 2 is made of: a head may leave the glass
before the alignment is finished only if the frame it left behind, taken while the head was still ringing,
says what the frame taken afterwards would have said.

So `ReferenceBottomVision` now dumps the frame the pre-check was given as well, from the same place it
hands it to the check (`earliestFrameHandedTo`), and the corpus of the next run has two frames per
alignment where it had one: `f1` from the ringing, `fN` from the settled. `check_frames.py` labels the
role of each row and complains about a frame no line of the log accounts for; run against the old corpus
it says so in a note instead, because that corpus genuinely lacks the frames.

The cost was measured before making the trade (`FrameDumpCostTest`, which prints and does not assert):

    measuring a 1080x926 frame of 2 MB of pixels
    the capture thread pays 0.61 ms per frame for the copy; the writer thread pays 28.09 ms for the PNG,
    827 kB on disk

which is the whole argument for why a dump does not force the stillness it is trying to observe: the
capturing thread pays half a millisecond and the encoding is another thread's business at minimum
priority. It also bounds what dumping *every* grab would have cost, and that is why it is not done: a
hunt is three to five grabs, so a warm 24-placement job would be a hundred and fifty frames, five seconds
of stolen CPU and a hundred and twenty megabytes, in exchange for four points on a curve nobody has asked
for yet. Two frames per alignment, before and after, is the curve that matters.

To run it, on `pn52`, with the jar built from this commit (the share is the repo the machine starts from,
so `openpnp.sh` needs nothing but a restart):

```
pgrep -af openpnp-gui                                    # close OpenPnP first if it is open
cd /mnt/public/stuff/OpenPnP/openpnp
rm -rf ~/bv-corpus-r                                     # a fresh directory; two runs in one is not a corpus
./openpnp.sh "-DframeDump=$HOME/bv-corpus-r"
```

Then the same job as before, and if the machine is being warmed anyway, two or three passes of it, since
the first pass of a cold machine measures the cold machine. Nothing else to do: the dump is off unless the
property is on the command line.

Bring it back together, frames, config and log, into a new dated directory:

```
mkdir -p tests_2026-10-01                     # one directory per day, corpora named after the log
cp -r ~/bv-corpus-r tests_2026-10-01/bv-corpus-r
cp ~/.openpnp2/machine.xml ~/.openpnp2/packages.xml ~/.openpnp2/parts.xml \
   ~/.openpnp2/boards.xml ~/.openpnp2/vision-settings.xml tests_2026-10-01/
cp <the log of that session> tests_2026-10-01/log_r.txt
```

and say whether it is a corpus:

```
python3 docs/design/parallel-bottom-vision/check_frames.py tests_2026-10-01/bv-corpus-r \
        tests_2026-10-01/log_r.txt
```

What will be read out of it, in this order:

- the drift between `f1` and `fN` of one alignment, measured rather than inferred - `FrameReplayTest` can
  now be pointed at the ringing frame with `-Dreplay.dir=tests_2026-10-02` and will say what the pipeline
  made of it, which is the number that decides whether an early departure can answer for a still frame;
- whether the drift has a shape at all (does it fall to nothing by grab three, or does it wander);
- whether the timings of the run still look like the previous one, i.e. whether the dump changed anything.

### `log_r.txt` happened: the replay holds at eight batches, the ringing frame did not survive

`bv-corpus-r` is that run: 8 alignments, 16 kept frames, `log_r.txt`. Replayed against the log of the run
itself, every one of the 16 reproduces the rectangle the machine measured - the same result as the first
corpus, twice as long and with the machine warm:

```
mvn -Dtest=FrameReplayTest test     # replays every corpus in git, one per log
```

The ringing frames, which were the point of the run, did not survive, in two ways that the frames
themselves make plain:

- the hunt handed its first frame to the pre-check with `FrameProvenance.unknown()` - true enough of a
  frame nobody has proved anything about, but it means the name of the file had no arrival, no capture
  instant and no frame number, so all eight ringing frames of the run were written to **one filename over
  each other** and one file is all that is left (`bv__Unknown__...__fna__ana__cna__wna__r+0.0.png`).
- the settling camera's first frame - the one `Upp1`'s pre-check actually judged - is handed to the check
  inside the settling loop and was never handed to the dumper at all.

Both are fixed: `FrameProvenance.Origin.Ringing` (dated, belonging to a batch, never still, which is the
reason to keep it), `AbstractSettlingCamera.getRecordedFirstCaptureAtMs()`, and a dump from the settling's
first-frame consumer. So: **run it again** with the jar built at 15:01, same command, and the corpus
should now have two frames per alignment, the older one named `bv__Ringing__...__f1__...`.

What the log gives up even without the frames, comparing what the pre-check saw on grab 1 with what the
alignment accepted on the frame it kept (the accepted offsets are `Offsets accepted (dx, dy, -, dr)` in
the part's own frame, so the sizes are the pre-check's and the position is a magnitude):

```
batch noz   ringing frame          settled frame         offset moved
            size mm      rot    off     rot      Δ off
    1 N1    0.99x0.51   -0.0   0.100    -1.98    -0.033 mm
    1 N2    0.92x0.47   -0.0   0.220     0.14    -0.030 mm
    2 N1    1.00x0.52   -2.3   0.120    -2.25    -0.065 mm
    2 N2    0.47x0.97   -0.0   0.180     0.14    +0.034 mm
    3 N1    1.00x0.52  -13.5   0.250   -13.36    -0.042 mm
    3 N2    0.92x0.47   -0.0   0.160     0.14    -0.006 mm
    5 N1    0.96x0.48   -0.0   0.100    -2.10    -0.014 mm
    5 N2    0.92x0.47   -0.0   0.180     0.10    -0.012 mm
    6 N1    1.00x0.52   -6.8   0.160    -6.74    -0.035 mm
    6 N2    0.42x0.95   -0.0   0.210     0.10    -0.005 mm
    7 N1    0.99x0.52   -5.0   0.160    -6.74    -0.020 mm
    7 N2    0.97x0.47   -0.0   0.160     0.10    -0.030 mm
```

Two things read out of that, and they point in different directions:

- **The angle survives the ringing.** Where the pick came out of the feeder rotated - batch 3 by 13.5 deg,
  batch 6 by 6.8 - the first frame already says the same thing to within 0.1 deg. The head's residual
  motion does not turn the part.
- **The size and the centre do not.** The centre is read high by 0.005 to 0.065 mm on the ringing frame,
  always high, which is the ringing and nothing else. And the size scatters in both directions by up to 6%
  - `Upp2` saw batch 6's part as 0.42x0.95 mm where its own kept frame says 0.448x0.975, and batch 3's as
  0.92x0.47 against 0.948x0.501. An 0402 is 1.0x0.5 mm, so 6% of a side is most of the slack a size check
  has. That is the mechanism the false `SizeOut`s in `log_j` needed and did not have an explanation for:
  the pre-check judges the one frame on which the mechanics are still moving, and a silhouette measured
  while it moves is not the silhouette measured after.

So the honest summary for Stage 2 is narrower than it was yesterday: an early departure may rely on the
angle seen on the ringing frame, must not rely on its size, and cannot rely on its position at all - and
in any case the still window that has to be waited for opens only 27 to 58 ms after the head stops being
commanded to move.

## The armed run: the head leaves before the numbers are in (`log_t.txt`)

Build: the jar of commit `2467a0cebd` plus the javadoc tidy that followed it, `target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`.

One setting turns this on, and it has no GUI control yet. In `~/.openpnp2/machine.xml`, on the
`<machine class="org.openpnp.machine.reference.ReferenceMachine" ...>` element:

```xml
early-alignment-departure="true"
```

Restart, and check it survived: `Machine` → `Save` and the attribute must still be there. Everything
else about the job stays exactly as in `log_r.txt` / `log_s.txt` — the same 8 placements, the same
speed, the same frame dumps — because the only comparison worth making is this attribute against its
absence.

### What the log has to say

Both lines are `DEBUG`, so `logging.properties` needs `org.openpnp` at `DEBUG` as in the previous runs.

| line | meaning |
|---|---|
| `[EARLY-DEPARTURE] head released with 2 alignments still being computed` | the alignment step ended over the cameras without waiting |
| `[EARLY-DEPARTURE] nozzle N1 travelled to ... while its alignment was still being computed, NN ms` | the journey to the board, taken without the offsets |
| `[EARLY-DEPARTURE] 2 alignments were still being computed when the head left the cameras and are complete NN ms later` | the wait, at the placement; `NN` is what the journey bought |
| `Parallel alignment fell back to sequential for all nozzles (N of M in this job) : ...` | the abort path, entered from the placement rather than from over the cameras |

The number worth having is the last `NN ms` on the third line. If the head's journey is longer than the
vision's work, that number is `0` and the whole vision time was hidden. If it is several hundred
milliseconds, the vision is the slower of the two and the journey did not cover it — which is not a
failure, it is the size of the remaining gap.

Compare against the same job's total time in `log_r.txt`, and say which jar and which setting produced
each number.

### The abort, on real hardware

The abort path cannot be provoked on demand from the operator's side, and it has been tested where it
can be — `EarlyDepartureAbortTest` refuses one pre-captured picture during a real job on the simulated
machine and shows the refused placement going back over the camera, being aligned the ordinary way, and
both parts going down. Two things can still be watched for on the real machine, and neither needs
anything invented:

1. **Let it happen on its own.** The `C0402` that measured a quarter of itself (`log_s.txt`,
   requirements §4) fails its size check *inside* a deferred pipeline. If it does so again with the
   setting on, the abort path runs for real: the head will be seen going back to the cameras. Say so in
   the run notes, with the timestamps — that is the hardware version of the harness test, and it costs
   nothing to record.
2. **Stop it mid-flight.** Press `Stop` while the head is between the cameras and the board. The job
   ends with an alignment still owed, and the `Cleanup` step has to release the pictures nobody will
   ever read. The camera batch must end and the lights go out, the same as any other abort (§5). This
   is the real-hardware half of the machinery that the harness cannot see, because it is the abort of
   the *job*, not of the alignment.

Neither of these is required to pass. They are the two chances the physical machine gets to say
something the simulation cannot.
