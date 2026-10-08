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
   - **C** is the parallel path where the head is moved once and the nozzles other than the settle
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

## 10. The camera displacement check (`scripts/DisplacementCheck.bsh`)

Everything above tests that the parallel path does what it was told. This tests whether what it was
told is *true*, and it is the only check here that measures the machine rather than the code. No
parts, nothing picked, nothing placed: the bare nozzle tips are the artifact.

Install it once - the Scripting menu reads the *configuration* directory, not the repository, and the
class it calls lives in the jar:

```
mvn -o package -DskipTests                                # from the repo on the share
cp scripts/DisplacementCheck.bsh ~/.openpnp2/scripts/     # on pn52
```

then start OpenPnP (`./openpnp.sh`) so the new jar is the one running, and run *DisplacementCheck*
from the Scripting menu with the machine enabled and homed and the tips clean. For two pairs it
makes four positions: each tip in front of its own camera and each tip in front of the *other* camera.
The crossed positions are the point - they are what separates a camera-side error from a nozzle-side
error (`DISPLACEMENT-VERIFICATION.md` §3) - and they are crossed *moves*, because the head is rigid and
a camera only ever sees the tip that is in front of it. It then repeats the two paired positions as a
batch check: with the head centred on nozzle *k*, what does each camera see, which is precisely what
the analysis has to remove. Default options are three repeats, crossed matrix and batch check on, the
stored runout centres subtracted so the answers come out as residuals; `zOffsetsMm` and `cAngles` are
arrays in the script and are one element by default.

Each shot is one tip in front of one camera at the calibration Z, so the commanded position is the
camera's plain position (`getLocation(null)`: advanced calibration applied, runout centre not) minus
the stored centre of that tip. About a dozen captures in all, a minute or two.

What to look at in the report:

1. **Fit residual** first. It is the rigidity consistency check: a machine whose tips and cameras are
   rigid must satisfy `X(1→1)+X(2→2) = X(1→2)+X(2→1)`, and anything that violates it is a
   misdetection, a knock or a stale Z. If it is not near zero the run is void and no other number
   from it means anything.
2. **Spread over repeats** next, from the three repeats. The check may not claim an error smaller
   than this.
3. **Camera displacement** (`e₁-e₂` on a two-camera machine) against what is stored, and the
   `proposed` column, which is `stored + error`. On the machine of
   `tests_2026-10-01/machine.xml` the two cameras' claimed spacing differs from the nozzles' by
   0.50 mm, which is the whole `displacement-max-distance` allowance, and the value the analysis
   subtracts is wrong by about 0.2 mm for every second nozzle in a batch.
4. **Batch table**: predicted against measured, per nozzle and per choice of primary nozzle. The
   difference between those two columns is the end-to-end check of the model, and the predicted
   offsets themselves are what Stage 4 will feed to the analysis. Expect a few tenths on the
   non-primary nozzle and about zero on the primary.
5. **Per pair residual** against the stored runout centres, and the nozzle-side number `u`: if the
   error sits on the nozzles, moving cameras is the wrong remedy and the head offsets should be
   re-measured.

Note that a camera looking at a tip that does not belong to it uses that tip's own calibration
pipeline, with the mask centred on the commanded position; the tip is within a few tenths of a
millimetre of the axis by construction, well inside the 0.5 mm detection threshold, so a crossed shot
that fails is a real failure and not a mask that was set in the wrong place.

Record the whole run directory the script writes (`<configDir>/displacement/<timestamp>/`, containing
`report.txt`, `results.csv` with one line per shot including the misses, `summary.csv` with one line
per camera and nozzle so two runs can be diffed, `images/` and `settings-snapshot.xml`, the
`machine.xml` as it was when the run started) in a `tests_<date>/` directory as usual. Writing the
displacement into `machine.xml` is a later stage and backs the file up into `<configDir>/backups/`
first, so a comparison with the previous state is always possible.

### 2026-10-05: the runs that measured something, and the three things in the way

Four runs on `pn52` (`~/.openpnp2/displacement/2026-10-05_*`), two pairs, two repeats, three Z levels,
eight frames per shot, `saveFrames` on. They are the first runs in which the crossed shots measured at
all, and they agree with each other far better than any of them passes its own limits:

* The displacement comes out at **Upp1 -0.1861 / Upp2 +0.1861 mm in X** and about ±0.018 mm in Y, at
  all three Z levels, with a fit residual of 0.010 mm and a spread of 0.005 mm at Z = 0. The loop
  closure is 0.021 mm. So the effect is real, it is the same size in both cameras (which is what the
  gauge `Σe = 0` forces) and it does not depend on Z.
* The batch table is where it still fails: on the *non-primary* nozzle the predicted and measured
  offsets differ by 0.04-0.06 mm, about twice the paired-measurement scatter.

Three separate things stand between that and a number worth writing into `machine.xml`.

**The camera hands out the scene it last looked at.** Every diagnostic picture the check saves, and
the frame it judges contrast and sharpness from, came from `captureTransformed()`, which is the
camera's buffer, and the buffer is whatever the camera last grabbed. After a crossed move the head has
moved on for the *other* camera's sake, so the buffer of the camera about to be judged still holds the
previous shot's scene: the lamp and no tip at all. That is the pictures of the diffuser with nothing in
front of it. It also inverted the focus note - the report said Upp2 was the sharp camera while its own
saved tip frames are the blurrier ones by a factor 1.8. Judgements and probes now go through
`settleAndCapture()`; only the `_f` frame dumps, which are taken immediately after a pipeline run that
has just settled for itself, still read the buffer.

**The two cameras do not see the tip equally sharp.** In the frames that do contain a tip, Upp1's is
about 1.8x sharper than Upp2's at the same commanded Z (Laplacian 11 against 6.2), which at this
magnification means one of them is not focused on the tip plane at all. A defocused tip does not fail
to be detected, it is detected on a different feature: the specular ring spreads into the penumbra and
the centre of what is fitted moves. The `maxFocusRatio` gate is there to stop a run drawing conclusions
across that, but the fix is optical, and until it is made the closure and the batch errors cannot be
attributed to the displacement.

**A pixel is not the same in the two cameras.** The `pixelScaleProbeMm` pictures are the empirical
answer to what a millimetre is on each camera, and `analyze_probe.py` in this directory measures them;
the commanded move is machine millimetres and owes nothing to a camera's own calibration. On the first
probe after this is written Upp2 gives ~34-37 px/mm (0.0274-0.0294 mm/px, and the run is marked
*usable*), while Upp1 gave 0.2-4.4 px/mm, which is not a pixel size but the stale-buffer frame above.
The advanced calibrations of the two cameras already disagree about pixel size by about 6% at the tip
plane, and 6% of the ~0.4 mm that a crossed tip sits off axis is 0.024 mm - the size of the residual
that is left. Rerun the probe once the captures are fresh and the two numbers can be compared for real.

### Which circle of the tip is being measured

An up-looking camera sees several circles in one bare tip, and the picture of N1 in Upp1 at Z = 0 shows
them laid out: a dark core about 8 px across with the values behind it at 1-9 against a bright ring at
80-135, that ring 20-24 px across, and a penumbra that fades out over 80-90 px. `DetectCircularSymmetry`
is told a diameter and locks onto whatever matches it, and the diameter it is told is
`nozzleTip.diameter`, which `ReferenceNozzleTipCalibration` sets from the tip's configured
`calibration-tip-diameter` (0.386 mm on this machine), converted to pixels and widened by the stage's
inner/outer margins. So the *size* the user has to input already exists in the nozzle tip calibration
UI; what does not exist is a second, smaller circle to measure.

The vacuum hole is the better artifact: its edge is the tip wall, not where the lamp happens to shine,
and it is the highest contrast feature in the frame (values 1-9 against 80-135). For the displacement
check that can be tried without any UI at all - `Options.holeDiameterMm` passes a diameter straight into
the pipeline for the shots of that run, and the report says which circle was measured under
`Detection:`. It is only worth setting once both cameras are focused: a hole of eight pixels is the
first thing a defocused lens erases, and indeed in Upp2's softer frame the dark core is no longer a
round hole but a smear.

### 2026-10-06: the run is clean, and what it then finds is not a displacement

`2026-10-06_07.23.02` and `2026-10-06_07.29.30`, the second after `Upp2` was given the same camera
settings as `Upp1`. The fresh-frame capture and the equal settings removed every complaint about the
measurement itself: contrast 18.3-19.5 everywhere with no camera varying by more than a factor two,
8 distinct frames per shot, frame-to-frame scatter at the quantization step, focus ratio 0.80 (the user
reports no visible focus difference either), and tilt down to **0.0014 mm per mm of Z** on both cameras
- the 0.024/0.047 of the previous evening was bad frames, not tilted cameras. The displacement is
-0.1590/+0.1590 mm in X, ∓0.0265 in Y, the same at all three Z, residual 0.012 mm, closure 0.024 mm.

The saved pictures say the same thing independently of the pipeline: correlating the first frame of
repeat 0 against repeat 1 for the same shot leaves only **0.03-0.68 px** of shift (0.001-0.018 mm), so
the head, the tips and the cameras do repeat to a few microns. The one cell the report still complains
about, `batch@N1/N2→Upp2` at 0.023-0.026 mm spread, is a real 1 px of movement between the two visits.

What is left is not noise. Take the error of each batch cell relative to the cell of the nozzle that
batch was centred on and the head positioning error and the claimed camera position cancel:

| run | N2 relative to N1 | per nozzle |
|-----|-------------------|--------------|
| 2026-10-05_23.42 | -0.046, +0.007 mm | 0.020-0.026 mm |
| 2026-10-06_07.23 | -0.065, +0.039 mm | 0.036-0.040 mm |
| 2026-10-06_07.29 | -0.077, +0.044 mm | 0.035-0.041 mm |

Two batch centres give the same number from two independent measurements and they agree to 0.015 mm,
and it is steady - on 2026-10-06 it came out at -0.0346 to -0.0358 mm in X and +0.0188 to +0.0192 mm
in Y over the three Z, i.e. to a micron at every height of the run, while the noise of one measurement
of this kind is about 0.01 mm. So it is a fixed property of the machine. **What it is a property of was
first written down as the nozzle spacing and that was too quick**; the number is real, its attribution
was not, and the run of 2026-10-06_08.26 with the same head said 0.035 mm against a 0.025 mm limit that
had no business being there.

The reasoning that was wrong: the crossed matrix reproduces itself to 0.012 mm but a batch cell only to
0.06 mm, and with two nozzles and two cameras there is nothing in that gap that separates a nozzle that
is not where its head offsets say from a tip that is detected slightly wrong when it is not in the
middle of the image. Both are fixed, both repeat to a micron, both land on every non-primary placement,
and a stored displacement removes neither. The batch is therefore a *symptom* and not an instrument:
it says a batch is 0.06 mm wrong, which is worth knowing, and it cannot say which knob to turn.

What the report prints instead, since the head's own numbers are what a real batch runs on:

- The tip's distance off the middle of the image per batch cell, in millimetres and pixels.
- `model pk` and `model rms` per nozzle: the error the stored runout model already had on the day it
  was fitted. This machine's stored models carry peak errors of 0.019-0.065 mm, so a `pair res` of that
  size is a calibration that is only as good as it ever was rather than a stale one.
- The batch table keeps predicted against measured, with the tip's distance off the middle of the image
  in millimetres and pixels, because a detection that is pulled away from the centre would show up as
  exactly the fixed 0.05 mm this table shows.

To be settled by runs rather than by argument: `applyStoredAxisOffsets` false against true, and
`suppressAdvancedCalibration` false against true, say whether the stored runout offsets or the camera
calibration fit are what the batch error follows; `cAngles {0, 180}` measures the runout circle live and
compares it with the stored model directly. Until one of those has been run, the batch error has no
owner and nothing should be written to `machine.xml` because of it.

The displacement itself is still only trustworthy to about ±0.03 mm, and this run shows why: it came out
at -0.1861 before the `Upp2` camera settings were changed and at -0.1590 after. A per-camera bias of
that size is absorbed by that camera's own `e` and so is invisible in the batch table; it means the
detector follows the specular ring, which moves with the exposure, rather than a wall of the tip. That
is the case for `holeDiameterMm`, and the probe pictures from this run are good enough to try it with:
**Upp1 37.1-38.5 px/mm (0.0266 mm/px), Upp2 38.4-40.3 px/mm (0.0255 mm/px)**, against the 0.0267 and
0.0263 the advanced calibrations claim - so the two cameras disagree about pixel size by 4%, not the 6%
the calibrations had implied, and the pixel scale is good to better than 0.01 mm over the 0.2 mm that a
batch reaches.

### 2026-10-06: what the runtime compensates, and how good its own numbers are

The mechanism of not moving and compensating instead exists twice, and the two copies do not do the
same thing:

- **Sequential** (`ReferenceBottomVision.preparePipeline()`, around the `shotLocation` construction):
  the nozzle's actual location is compared with the planned shot location and the difference is tested
  against `displacement-max-distance`, `displacement-max-Z-diff` and `displacement-max-rotation`. If it
  is small enough the nozzle is not moved and `shotLocation - currentLocation` is handed to the analysis
  through `pipeline.setShotDisplacement()`. The compensation is the *planned travel that was not
  travelled*, measured at the moment of the shot, and it costs nothing.
- **Parallel** (`displacementDelta()`, and the same arithmetic again in `captureImageOnlyWithSettle()`):
  nothing is compared and nothing is measured. For every nozzle but the first it returns
  `getCalibratedCameraOffset(nozzle, camera) + getCalibratedOffset(nozzle, wantedRotation)`, for the
  first it returns zero, and that is the whole compensation.

Both terms of the parallel version come from the nozzle tip **runout calibration**, and both are
silently zero when `isCalibrated(nozzle)` is false: a head with an uncalibrated tip runs parallel
vision with no compensation at all and no line in the log saying so. In the model stored on this
machine (`ModelBasedRunoutCameraOffsetCompensation`) `getCameraOffset()` is the centre of the fitted
circle and `getOffset(angle)` is the wobble alone, so the two added together are the complete offset
and nothing is counted twice.

What those stored models are worth is written next to them in `machine.xml`: peak errors of
**0.0196, 0.0383, 0.0413, 0.0389, 0.0647 and 0.0412 mm**, rms 0.010-0.041 mm. That is the error the
offset had on the day it was calibrated, before any tip wore or was refitted, and it is the same size
as the 0.05-0.06 mm the batch table has been reporting. Whether the calibration has gone *stale* since
is a separate question and `cAngles {0, 180}` answers it directly, because the crossed matrix is immune
to the stored axis offset (it cancels between the command and the fit) and so measures the runout that
is there today rather than the one on file.

The camera's stored `<displacement>` is folded into `getCalibratedHeadOffsets()` and is therefore in
every command and every prediction of the check; it is 0,0,0 on this machine, so a parallel batch
currently compensates the runout of the tip and nothing at all about the cameras being displaced from
where they claim to be. That is what Stage 2 would store, and the batch table is the number it would
improve.

The report of the check now says all this next to the measurement instead of arguing about it:
`model pk` and `model rms` per nozzle (how good the runout offset was when it was made) and the tip's
distance off the middle of the image in millimetres and pixels. The `spacing` table, its
`maxSpacingMm` limit and `centreSpacings()` are gone; see above for why.

A second overreach was retracted the same day, and it is worth recording because it is the same mistake
in a new costume. A `belief` column was added per batch cell, holding what
`ReferenceBottomVision.displacementDelta()` removes for that nozzle (runout calibration camera offset
plus runout at this rotation), and its difference from the measurement was printed as "what a parallel
placement of that nozzle is wrong by". It is not. `displacementDelta()` compensates a *part* that is
imaged while the head sits at another nozzle's shot location; the batch of this check images a *bare
tip* and centres it, which is a different frame, and the primary cell's "error" was the off-axis
distance by construction rather than a measurement of anything. It read 0.27-0.45 mm, which is the size
of the runout camera offsets and of nothing else in the model. The column, the `maxBeliefErrorMm` limit
and the problem text are removed; the batch table's own predicted/measured/err is the honest number.

One deployment fact worth writing down because it decides which build is running: `~/openpnp/OpenPnP`
sets `app_home=$(pwd)`, so OpenPnP runs the jar of whatever directory it was started from. Started from
`/mnt/public/stuff/OpenPnP/openpnp/target` it runs the build just made here and nothing has to be
copied to `pn52`; started from `~/openpnp` it runs the jar standing there. (Starting this session the
jar in `~/openpnp` was copied over with today's build by mistake; it had been standing there since
2025-03-07 and corresponds to `22b7defd24` if it is wanted back.)

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

One setting turns this on, and it has no GUI control yet. In `~/.openpnp2/machine.xml`, next to
`parallel-alignment-enabled`:

```xml
<early-alignment-departure>true</early-alignment-departure>
```

Not an attribute. The field is `@Element`, like `parallel-alignment-enabled`, and `@Element`s of this
machine are child elements, so putting it on the `<machine>` tag as
`early-alignment-departure="true"` makes the configuration refuse to load at all:

```
Attribute 'early-alignment-departure' does not have a match in class
org.openpnp.machine.reference.ReferenceMachine at line 2
```

which is what `log_u.txt` is. (That was my doing, on the machine, at 18:24; the attribute is off the
file again and the load was checked afterwards with a headless `Configuration.load()` of a copy of the
whole configuration directory, which reported `early-alignment-departure = true`.)

OpenPnP writes the element itself, as `false`, the first time the new jar saves the machine, so on
`pn52` the only edit needed is `false` to `true` in the line already there under
`<parallel-alignment-enabled>true</parallel-alignment-enabled>`.

Restart, and check it survived: `Machine` → `Save` and the element must still say `true`. Everything
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

### The armed run, and what it bought

`log_v.txt`, 2026-10-01 18:56 and 18:57, two jobs of four placements, setting on. The machinery works.
It buys nothing that can be seen.

| | unarmed `log_t.txt` | armed `log_v.txt` |
|---|---|---|
| job, the comparable runs | 10.58 s and 8.91 s | 10.88 s (the first job was 15.72 s, warming up) |
| batches released with an alignment owed | never | 8 of 8 |
| wait at the placement | nothing to wait | 10, 10, 10, 12, 12, 14, 23, 25 ms |
| nozzles that travelled before their numbers | - | one per batch, never two |

The wait column is the whole of what was hidden: 10 to 25 ms against a cycle of about 1.9 s. It is
hidden while the head is on its way, and the head is on its way for hundreds of ms, so hiding it changes
nothing that a stopwatch on the machine can resolve - the unarmed runs of `log_t.txt` differ from each
other by 1.7 s.

The reason is visible at the front of the batch, not the back. At 18:56:44 `Upp2` shot a frame at
44.544, `Upp1`'s window opened at +51 ms, so that frame was 7 ms too early, was refused, and `Upp2` shot
again at 44.572. The head was released at 44.575 and both alignments were in hand at 44.600. Everything
expensive happened *before* the release, and stage 2 begins after it.

Two numbers in these logs need reading carefully:

* `set off for ... , {} ms to queue` is the time taken to hand the move to the motion planner, and the
  asynchronous driver has usually not finished the move when that returns. It is not driving time and
  must not be compared with anything; the old wording (`travelled ... 3 ms`) invited precisely that
  mistake, which is how this run came to look as though the head moved after the numbers arrived.
* `are complete {} ms later` is the honest one: release to last future, measured on the machine.

So the next work is not behind the release but before it: the settle hunt and the re-shot frame. If
`AbstractSettlingCamera` kept its last frame or two with their exposure times, a camera whose frame
landed 7 ms early could be handed a frame it has already taken once the neighbour's window is known,
instead of being made to take another one and to sit through it.

### What the next armed run has to show

Two changes went in after `log_v.txt` (REQUIREMENTS §11, "the two things that made the first armed run
achieve nothing"), and neither can be proved off the machine, because neither is about whether the job is
correct - both are about when the driver is handed a command. Arm as above, run one job, and look for
these two lines in this order:

```
[EARLY-DEPARTURE] nozzle N. set off for (...) while its alignment was still being computed
commandQueue offer >> ... G1 X... Y...          <- must come BEFORE the next line
[EARLY-DEPARTURE] N alignments ... are complete K ms later
```

If the `G1` comes after the `complete` line, the head has again been asked instead of sent, and the
planner has swallowed the move; that is the failure mode of `log_v.txt` and it must not be read as
"working". The second thing is the margin on the settle line:

```
[SETTLE-WINDOW] camera Upp2 frame F at +A ms after the rest is inside the window of Upp1
                which opened at +W ms, margin M ms of F grabs
```

`M` was 25-35 ms in `log_v.txt`. It should now be about one capture (8-15 ms) and `F` should not have
grown. R4 is the requirement: what is being minimised is the time between the head stopping over the
cameras and the head being allowed to leave.

### log_w.txt: asking earlier is not sending

`log_w.txt` ran with the departure moved before the pre-rotation. The head still did not move:

```
57.972 [EARLY-DEPARTURE] nozzle N1 set off for (173.109160, 106.759975, ...)
57.990 [EARLY-DEPARTURE] 2 alignments ... are complete 23 ms later
57.991 commandQueue offer >> M204 S10694 G1 X139.09 Y125.00 ...
```

So the diagnosis of `log_v.txt` was half right. The pre-rotation was not the culprit: whatever the order
in the job, the advanced motion planner keeps a plan in its queue until something flushes it, and the
thing that flushes it is the next synchronised move - here the corrected approach with its `M400`. That
move is precisely the one waiting for the alignment, so the head starts one ms after the numbers. The
job thread cannot fix this by ordering; it has to empty the queue, and `CompletionType.CommandJog` is
the completion type that does exactly that (`AbstractMotionPlanner.waitForCompletion()` calls
`executeMotionPlan()` first and only waits the drivers when `isEnforcingStillstand()`, which `CommandJog`
is not). The advanced planner uses the same trick itself for jogs.

The margin expectation above was also too optimistic: measured 23-30 ms against 25-35 ms before, still
2 grabs. The aiming bought a little; the rest is the exposure itself, because the frame is stamped when
`capture()` returns, so a frame that starts the instant the window opens is still reported a capture's
length inside it. Expect 20-30 ms, not 8-15 ms; what would be wrong is `F` growing.

The run also delivered the abort on its own: a `C0402` measured too small inside a deferred pipeline, the
batch fell back with its WARNING and count, and the head went back over the cameras and lit the light
again. From the next run onwards the head will already be travelling when that happens, and the return
move simply queues behind the departure - the driver executes in order, so nothing has to be revoked.

Unrelated thing this run made visible: on the fallback path the realignment opens `Batch START level 1
'visionutils' - cameras: 0`, and the light then comes on only when the nozzle arrives rather than when
the head starts back. Not caused by anything in this work, but it is a light-off/on batch that registers
no cameras and is worth a look on its own.

### log_x.txt: the head is now sent before the numbers are in

One job with the `CommandJog` flush, 8 batches, every one of them leaving the cameras before its offsets
existed:

| batch | release -> first `G1` | release -> offsets complete |
|---|---|---|
| 1 | 7 ms | 22 ms |
| 2 | 5 ms | 22 ms |
| 3 | 14 ms | 20 ms |
| 4 | 5 ms | 14 ms |
| 5-8 | 5, 5, 5, 6 ms | 10, 11, 10, 10 ms |

`attempts=4 fell back=0 frames refused=0` on both jobs, so the deferred path carried everything. This is
the first run in which R9 is true in the driver log rather than only in the code: the machine was told to
go, and the arithmetic caught up afterwards.

What it is worth has to be said carefully, because the run time does not show it. The job took 10.8 s;
the unarmed job took 11.1 s and 9.4 s in `log_t.txt`, and the same binary armed-but-not-overlapping took
11.4 s in `log_v.txt`. The spread between runs of the same job is a couple of seconds, and what the
overlap hides is one pipeline per camera, about 14 ms each, so the expected gain is a few tens of
milliseconds per batch - around one percent. The job clock is the wrong instrument for that; the table
above is the measurement, and it says the overlap is 10-22 ms per batch and the head is 5-14 ms on its way
before the offsets land.

The rest of the time over the cameras is not the alignment. One batch, from the light coming on to the
head being released (278 ms):

```
   0  M810 on, G1 to the cameras, M400, M114
 224  position report back          <- 219 ms of travelling in and confirming
 225  first frames, settle starts
 265  autoSettleAndCapture in 38 ms
 266  pre-check done on Upp1, Waiting for 1 async camera captures
 277  the second settle-window frame of Upp2 arrives, 11 ms of that wait
 278  head released
```

So: 219 ms is the approach, which no part of this work touches, and 55 ms is the camera work, of which
the settling is 38 ms and the join on the other camera's capture is 11 ms. The 10-22 ms now hidden behind
the departure is most of what is left to hide in those 55 ms.

The join itself is worth naming: eight waits in the run, 38 ms in total, four of them nil and four of
7-11 ms. It is the job thread waiting at `PASS 1 END` for the secondary camera's settle-window grab, which
is a frame behind the settle camera by construction. Handing that future to the deferred worker instead
would release the head a frame earlier, but the light is switched off when the pre-capture batch ends, and
the batch is nested per thread - so the frame still being exposed would be exposed in the dark. That is a
question about the light batch outliving the job thread, not about the vision.

### The join is one exposure, tried to buy it by grabbing, and could not (`log_y.txt`)

*(Superseded in one respect: `CAPTURE-COST.md` measured the cameras directly and the exposure is 0.1 ms on
the bottom cameras and 5 ms on the down one - what the join waits for is a frame interval of the stream,
not the exposure. The conclusion "grabbing earlier cannot buy it back" stands, and so does the reason: the
frame after the window opens does not exist yet.)*


The 7-11 ms join looked like a scheduling loss: `grabUntilTheStillWindow()` slept until the settling had
claimed an opening and only then exposed, so the frame it accepted was exposed after the verdict rather
than while the verdict was still on its way. The fix tried was to expose whenever there was nothing to go
on and choose the frame afterwards, judging each frame by the instant it was *asked for* rather than the
instant it came back. Reverted as `e5ccfa1d95`. This is what the machine said:

| batch | `log_x`: window, frame in, grabs, join | `log_y`: window, frame in, grabs, join |
|---|---|---|
| 1 | +61, +86, 2, 10 ms | +57, +83, 5, 9 ms |
| 2 | +61, +85, 2, 9 ms | +38, +66, 4, 9 ms |
| 3 | +58, +85, 2, 10 ms | +29, +55, 4, 10 ms |
| 4 | +38, +64, 2, 9 ms | +63, +86, 5, 10 ms |
| 5 | +30, +55, 2, 11 ms | +29, +58, 4, fell back |
| 6 | +53, +74, 2, 7 ms | +29, +58, 4, 12 ms |
| 7 | +24, +53, 2, 11 ms | +27, +64, 4, 8 ms |
| 8 | +63, +87, 2, 10 ms | +55, +83, 4, 10 ms |

The join is 8-12 ms where it was 7-11 ms. The frame is handed over 23-37 ms after the window opens where
`log_x` was 21-29 ms. The grabs went from 2 to 4-5, and one batch fell back to sequential alignment
(`attempts=4 fell back=1`) which had never happened in `log_x`. The change bought nothing and cost two and
a half times the frames.

Why it cannot work, which is the useful part: an exposure that is to be inside a window cannot begin before
the window does. The window opens a frame or two before the settling says so, and the exposure is 9-14 ms
(`capture=` on the `[PRE-CHECK]` lines), so the earliest frame that can honestly be called still is handed
over one exposure after the opening - 21-29 ms in `log_x`, which is what the physical floor is, not slack.
The head waits 7-11 ms for it because the settle camera's own frame and pre-check got there first. Asking
for frames earlier does not make the exposure earlier; a frame in flight over the ringing is a frame that
must be thrown away, and my stricter "asked inside the window" rule threw away even the frame that
`log_x` had been willing to keep.

So the answer to "is this a Java problem" is no, and it is not a USB problem either - the two cameras are
on separate USB3 roots, and the settling did not slow down measurably under four times the grabs (27-63 ms
in `log_x`, 29-62 ms in `log_y`); what the extra frames cost is CPU, one batch's capture going 9 ms to 21
ms. The join is the exposure time of a camera that is being asked to photograph a head that has only just
stopped. The only way to remove it is to accept a frame whose exposure straddles the start of the window,
part of it taken while the head is still ringing, which is exactly the thing the frame provenance was
built to refuse. If the join is ever removed it will be by not needing the second camera's frame before
the head leaves - i.e. by deferring the whole grab, with the light, into the deferred worker
(`TIME-BUDGET.md`) - and not by taking frames earlier than the physics allows.

## 2026-10-06 (later): a recalibration, a detector with no subpixel, and storing the number

Two runs, `2026-10-06_11.13.22` and `2026-10-06_11.15.55`, with a nozzle tip calibration of both tips
in between, which is the A/B that the batch table has been waiting for.

| at Z = 0 | before | after recalibrating |
|---|---|---|
| stored axis offset N1 | 0.2812, 0.1821 | 0.2593, 0.2348 |
| stored axis offset N2 | -0.0592, 0.0970 | -0.0343, 0.1524 |
| pair res N1 | 0.0318 | 0.0078 |
| pair res N2 | 0.0653 | 0.0618 |
| batch err, primary cells | 0.0108 / 0.0055 | 0.0114 / 0.0202 |
| batch err, non-primary cells | 0.0552 / 0.0702 | 0.0302 / 0.0597 |

N1 was stale and is now as good as its own model (0.0078 mm against a `model pk` of 0.0158 mm). N2 was
not: a fresh calibration left it 0.062 mm off, against a model that claims 0.019 mm, so for N2 the
disagreement between how the runout calibration measures the tip and how anything else measures it is
structural and not age. The recalibration also moved the tips themselves: `u` shifted by 0.035 mm and
the stored centres by up to 0.05 mm, which is the answer to whether a bare tip may be trusted to sit
where it sat before - it may not, to within a few hundredths.

What did not move is the reason for the whole exercise: the camera displacement difference is
0.358 mm in X and about 0.04 mm in Y in every one of the four runs of the day, recalibrated or not, and
is now the best determined number on the machine.

The frames of these runs also exposed the floor under all of it. Every value in `frames.csv` is a
multiple of 0.0134 mm, which is half a pixel at 0.0267 mm/px, and whole cells have a standard deviation
of exactly 0.00000 over 48 frames spanning three Z heights and two repeats. The circle detector reports
its centre on a half-pixel grid; there is no subpixel in this path at all. So the "frame noise" of
0.009-0.015 mm is binning, a tip held still reads identical to four decimals between runs while a tip that
was moved onto the axis wanders 0.03-0.08 mm between them, and a check whose tolerances are 0.01-0.02 mm
is currently reading a ruler graduated in 0.0134 mm. Nothing measured today is wrong because of it - the
displacement is 27 graduations - but a subpixel stage (a contour centroid or a circle refit around the
Hough answer) has to go in before anything at the 0.01 mm level is claimed.

## Why the displacement has to be stored, and where it is stored now

The question worth asking before writing anything: the sequential path needs no stored displacement, it
knows how far it moved and compensates that, so why can the parallel path not do the same? Because the
movement delta is available exactly once per batch. It belongs to the nozzle whose shot location the head
was taken to, which is why `displacementDelta()` returns nothing for the first nozzle tip. For every
other camera there is no movement at all between the pose the head is in and the pose the tip is imaged
from, so what has to be removed is not a travel but a geometry: the difference between the spacing of
the cameras and the spacing of the parts. A camera can only ever see its own part against its own axis,
so no two-camera, two-part batch can see that difference from the inside; it is invisible by
construction, and it has to come from a calibration.

It is already stored today, in the worst possible place: the centre of a tip's runout circle is measured
in a specific camera at a specific moment and therefore absorbs that camera's whole mounting error,
which is why the calibrated camera offsets on this machine are 0.2-0.3 mm when the runout of a clean tip
is a few hundredths. Every tip remount, every head offset edit and every advanced recalibration moves it
again. Storing the displacement on the camera, where Stage 2 puts it, is what lets the runout
calibration go back to measuring runout.

Stage 2 is in, opt-in and nothing more: `DisplacementCheck.Options.writeDisplacement` for an unattended
run, and `DisplacementCheck.writeDisplacement(result, options)` for a caller that asks first, which the
script does in a dialog when the run finishes. It writes `stored + measured error`, averaged over the
conditions, into `<displacement>` with `verified-at` and `spread`, through
`Configuration.save()`, which takes the backup of `machine.xml` with it. The difference between the
cameras is all that is observable, so the write keeps the mean of the stored values and moves them apart
by exactly the measured amount. What it invalidates is said in the note it leaves in the report: after
the write, the nozzle tips have to be calibrated again, and the check run again - if the story told here
is right, their paired centres should come down from 0.2-0.3 mm towards the runout of the tips.

Saying yes to that dialog runs the rest of the loop by itself, because stopping halfway would leave the
machine in the one state that is worse than either end (cameras moved, tips still fitted to where they
were). It calls `nozzleTip.getCalibration().calibrate(nozzle)` for every loaded tip whose calibration is
enabled - the same call the Nozzle Tip Solutions action makes - saves, and then measures the whole thing
a second time. The two run directories are therefore the machine before and after one write, and the
second one is the verdict: its displacement should be near zero and the paired centres should have come
down. `DisplacementCheck.describeRunout(result)` prints the per-nozzle line that is compared across the
two (stored centre, pair residual, model peak-to-peak), and the second dialog offers to write whatever
is left over. Calibration is skipped, and said so, when nothing is loaded or a tip's calibration is off;
a failure on one tip does not stop the others, and a tip resets before it refits, so a failed one is left
uncalibrated, which the second run shows as a centre at zero and a pair residual of the whole runout.

For the sharpness question the report now prints, whenever more than one Z was measured, the frame
sharpness of each camera against the Z it was taken at. Two cameras of unequal sharpness do not measure
the same feature of the same tip, but unequal has two causes and they want opposite answers: a camera
whose sharpest Z is not the Z the machine shoots at is focused for another height, which is what the
calibration Z of that camera is for, while a camera whose curve is flat over the sweep is at the limit of
its lens and arguing with it is a wasted afternoon. The suspicion worth testing this way is that Upp2 is
the softer one (1.8x against Upp1 in the frames that hold a tip) and that N2, which is the nozzle that
looks at Upp2, is also the nozzle whose pair residual stayed at 0.062 mm through a fresh calibration -
though against the blur story stands the fact that this residual did not walk with the three Z heights of
the sweep, which blur does.

The sweep answered that. Run `2026-10-06_16.32.27`:

```
             Z-0.3000   Z+0.0000   Z+0.3000
  Upp1           3.40       3.38       3.35   sharpest at Z-0.3000
  Upp2           2.73       2.73       2.69   sharpest at Z-0.3000
```

Both curves are flat to within a percent over +-0.3 mm, so neither camera is focused for another height:
Upp2 is simply 0.8 as sharp as Upp1 at every height, which is the lens and the sensor and not something
to correct by moving the Z. That also means the sharpness difference cannot be what makes the N2 pair
residual walk, because there is nothing for it to walk with. The remaining explanation is the one the
report has been pointing at: the two calibrations are fitting a slightly different circle on the two
tips, which is what `holeDiameterMm` is for.

That run was not valid, for one reason only, and it is worth recording because it is the reason the write
is now guarded:

```
PROBLEM: Camera Upp2: displacement changes by 0.0220mm per mm of Z, over the 0.0200mm/mm limit.
```

Everything else was good (residual 0.011-0.014 mm, closure 0.024-0.026 mm, spread 0.019 mm), but a camera
that is tilted does not have a displacement so much as a direction, and storing one number for it moves
the cameras in a way that is right only at the Z it was measured at. `writeDisplacement()` now refuses a
run that calls itself invalid unless `forceWriteDisplacement` is set, and the dialog turns into a warning
with No as the default in that case - the first time this ran, the operator said yes to an invalid run,
and the script then died on a method that does not exist (`machine.getHead()`, where `ReferenceMachine`
has `getHeads()`), which left the displacement written and the nozzle tips not recalibrated: the one
state that is worse than either end. The call is fixed, the write and everything after it are separately
caught, and a failure after the write now prints what the operator has to do about it rather than a stack
trace.

One more thing the crash brought out, because it bears on what storing a displacement is actually worth.
The advanced camera calibration does model a tilted camera: it fits a full pose with
`Calib3d.calibrateCamera()`, and what it leaves behind as `calibratedOffsets` is where that fitted axis
crosses the camera's default Z (`AdvancedCalibration.applyCalibrationToMachine()`, i.e.
`-getCameraOffsetAtZ(defaultZ)`). At that plane the tilt is therefore already compensated. What it stores
is a constant, though - `getCameraOffsetAtZ()` is evaluated once when the calibration is applied - so
away from that plane the axis has moved and the constant has not, and that leftover slope is exactly what
the tilt sweep of this check measures. It measures it with the advanced calibration in force as well
(`suppressAdvancedCalibration` off by default), so it is not the part the fit already took.

The consequence for the experiment is that `calibratedOffsets` and `<displacement>` are added together in
`ReferenceCamera.getCalibratedHeadOffsets()`, which makes them interchangeable: an advanced calibration
run after the write takes the same amount straight back out again. So the advanced calibration must be
left alone between writing and measuring again, or the second run says nothing. That is the same
mechanism as the runout centres, and it is the finding worth stating plainly: where a camera is cannot be
measured on its own, only where it is relative to the nozzles and at what angle, so the number settles
into whichever of the fits is refreshed last. Storing it as a displacement buys durability and legibility
- a value that is not silently re-fitted by the next tip calibration, that carries its own spread and its
own date - rather than information the machine did not already have somewhere.




## What the write does to the rest of the machine, and what "not valid" was never a verdict on

The loop has now been run end to end on the machine (`2026-10-06_17.16.05`): measure, write, calibrate
both tips, save, measure again. It converges. The displacement left over afterwards is 0.0107mm
(stored -0.1850,-0.0237, proposed -0.1743,-0.0219, spread 0.0113mm), which is where the half-pixel
grid of the detector lives, so the first write took the displacement and there is nothing left to chase
with the detector as it is. The paired centres moved as predicted once the tips were fitted again
against the cameras in their new position: N1 from 0.1528mm residual to 0.0134mm with the model's own
peak-to-peak at 0.0272mm, N2 from 0.1862mm to 0.0383mm against 0.0173mm. N1 is now inside the error
its calibration had on the day it was fitted; N2 is a little outside it. That is the whole point of the
exercise, and it is measurable.

Two things about the write are worth writing down, because both were learned the hard way.

The first is that storing a displacement does not only move a camera: it wipes the runout calibration
of every nozzle tip on the machine, and it does so at the moment the value is set.
`ReferenceCamera.setDisplacement()` calls `viewHasChanged()`, which for an up-looking camera calls
`ReferenceNozzleTipCalibration.resetAllNozzleTips()` - all tips, not the two being measured here, and
not only the two nozzles. This is deliberate: a runout circle is fitted against one camera, so its
centre is made of where that camera claimed to be, and moving the camera makes the circle a lie. The
history of the afternoon is the consequence. The backup directory tells it: `machine.xml` up to
`2026-10-06_16.34.27` has the runout models of every tip in it, and from the next save onwards they
are gone (`<runout-compensation-lookup class="java.util.HashMap"/>`). The write happened at 16:34:27,
the script then died on `machine.getHead()`, and the shutdown at 16:36 saved the machine in the
in-between state - displacement moved, runout wiped. So the two runs that followed opened with "Nozzle
tip 502 is not calibrated on N1" and paired centres of 0.18mm, which was not the check being shy and
not the settings being wrong: the check was the only thing on the machine that still knew the tips had
just been emptied. Recovery is not to restore the old `machine.xml`, because those models were fitted
against the cameras where they used to be; it is to calibrate the tips that get used. The loop does the
loaded ones by itself, the script now says so out loud before it starts, and the report note names the
call chain so that nobody has to rediscover it from a backup diff.

The second is that a run saying NOT VALID was being read as a sentence about the machine, and it is
only ever a sentence about the measurement. The run that ends the loop said NOT VALID for one reason:
Upp2's axis tilts by 0.0221mm per mm of Z, just over the 0.02 limit. Nothing about that makes the
displacement number wrong - the residuals, the closure, the spread and the contrast were all fine - it
makes the number a value at a height. What bounds it is not this check's threshold but the machine's
own `displacement-max-Z-diff`, because outside that window the runtime does not apply the displacement
at all. The check now keeps the two apart: `Result.problems` are reasons not to believe the number
(residual, closure, spread, contrast, focus, an unsolvable system), `Result.caveats` are reasons the
number is smaller than it looks, and the tilt caveat quotes the cost as tilt times the machine's Z
window (0.0221 x 0.1mm, the `ReferenceMachine.displacementMaxZDiff` default, which this machine has
never overridden = 2um) rather than pretending a limit was exceeded. The report prints CAVEAT
lines and closes with "VALID - 1 caveat above: they say what the number is worth, not that it is
wrong". Tilt alone can no longer block a write, which matters because the operator facing an invalid
run is being asked to judge the measurement, not the camera mount.

Two interface failures from the same run are fixed with it, both of them mine. A second run from the
same script wrote over the first run's directory, because `run()` stores the directory it chose back
into the options it was handed, so the comparison the loop exists for had one file on disk instead of
two; `Options.directoryFromRun` now marks a directory chosen by a run, and the next run picks a fresh
one. And the loop ended without a word when the second run came back not valid, which from the
operator's side is indistinguishable from a crash - it looked like "it just disappeared". The loop now
always ends with the before-and-after in a dialog, offering the leftover write when the run is valid
and saying what is the case when it is not, because the one run out of three that is not valid is the
run that most needs somebody told.

## The batch residual probe, and what the script interpreter will not do

The three questions the runtime plan had to leave open (§7 of `runtime-displacement-plan.md`) are now a
script: `scripts/BatchResidualCheck.bsh`, deployed to `pn52:~/.openpnp2/scripts/` (md5
`18c37d69cb6868735abbcc3499f4137f`). It writes nothing but its own run log, moves only after lifting to
safe Z, and lifts the tips when it finishes. It has been run three times, once with nothing calibrated and
twice with both tips calibrated, and each of those states is worth recording because the machine spends its
life in both.

Three parts, all of them measuring bare tips with `ReferenceNozzleTipCalibration.measureTip()`. One
nozzle is dragged to two places and every other nozzle's `getLocation()` is compared with where the head
geometry puts it, which is Q2 and everything else rests on it. Then each nozzle is commanded to
`camera.getLocation(nozzle)` at its calibration Z - a location that already contains the runout circle
centre, so a tip obeying its model should stand still - and turned through 0/90/180/270, and the printed
"the tip walked *x*mm over the cycle" is Q1 in one number. Then the head is moved once for each
candidate primary, because the planner may pick either, and every nozzle prints where it is, where its
own shot wanted it, the gap, the term the code applies, what its camera sees, and the leftover. Two
details of that part are traps rather than bugs: the expected position handed the detector is
`actual + centre`, because that is where the tip stands when the model holds; and a tip more than the
offset threshold (0.5mm, `ReferenceNozzleTipCalibration.offsetThresholdLength`) from that expectation is
refused by the vision and reported as "nothing measured", which is itself an answer. The Z is always
`getCalibrationZOffset()` and never the Z of the camera location, which is the gantry Z plus a couple of
hundredths of head offset.

### Q2 has an answer, and it is not the worrying one

Two probe locations, both nozzles, `getLocation()` against `target + headOffsets₂ - headOffsets₁`:

```
    moved N1 to 160.4134 24.8207
        N1 reports 160.4134 24.8207, head geometry says 160.4134 24.8207, error 0.0000
        N2 reports 183.5045 24.7946, head geometry says 183.5045 24.7946, error 0.0000
    moved N1 to 167.4134 29.8207
        N1 reports 167.4134 29.8207, head geometry says 167.4134 29.8207, error 0.0000
        N2 reports 190.5045 29.7946, head geometry says 190.5045 29.7946, error 0.0000
```

Nothing at all - until the tips were calibrated, after which the same part reads:

```
    moved N1 to 160.1367 24.6133
        N1 reports 160.1367 24.6133, head geometry says 160.1367 24.6133, error 0.0000
        N2 reports 183.2500 24.5586, head geometry says 183.2279 24.5873, error 0.0363
    moved N1 to 167.1367 29.6133
        N1 reports 167.1367 29.6133, head geometry says 167.1367 29.6133, error 0.0000
        N2 reports 190.2500 29.5586, head geometry says 190.2279 29.5873, error 0.0363
```

Same 0.0363mm at both places, which is the shape of a bookkeeping distance rather than a flexing head, and
so it is: `getLocation()` is not the axis, it is the tool. `ReferenceNozzle.toHeadLocation():519-527`
subtracts `getCalibratedOffset(this, location.getRotation())` on the way from the commanded tool position to
the axis, and `toHeadMountableLocation():533-543` adds it back on the way from the axis to the report, which
is what makes a tip land where it was commanded while turning. Two nozzles bolted to one rigid head
therefore report each other apart by the difference of their runout offsets at whatever rotations they
happen to stand at, and because the angle between the two circles does not change with the rotation, that
error is a constant 0.0363mm here.

The head is rigid, so Q2 still holds, but the answer is subtler than the first run's zeros and matters to
the batch: **the positions the batch reasons about are runout-compensated tool positions, so a few
hundredths of "gap" between two nozzles is not geometry to chase.** The script now prints the error with
the two offsets taken back off, which is the axis-only figure, and it comes out at a thousandth.

### The state the machine was in when the probe first ran

`machine.xml` says displacement is on, with 0.5mm distance, 0.1mm Z and 0.01° rotation; Upp1 carries
`-0.1823, -0.0208` and Upp2 the negation, verified `2026-10-06 18:22` with a spread of 0.0195mm. And all
nine `runout-compensation-lookup` maps in the file are empty: **no tip on the machine is
runout-calibrated**, N1 tip 502 and N2 tip 502b included. The 18:22 write wiped the models that the
17:17 recalibration had fitted, because `ReferenceCamera.setDisplacement()` calls `viewHasChanged()` and
that resets every nozzle tip (§4 of `runtime-displacement-plan.md`). The probe is still worth running in
that state - with no centre to subtract, `camera.getLocation(nozzle)` puts the tip wherever it happens to
stand and the gap column shows the raw offset, which is the number the runtime has to live with - but
every model column in the output says zero, and the script now says so out loud rather than letting the
zeros be read as measurements.

### BeanShell 2.0b5, the interpreter OpenPnP ships (`pom.xml:134`)

What it accepts and refuses, all of it paid for in aborted runs:

- `String.format("%.4f", x)` does not resolve. It is a varargs method and 2.0b5 has no varargs support,
  so it fails at runtime with "Static method format(...) not found". `java.text.DecimalFormat` is the
  way to a fixed number of decimals.
- **No varargs call resolves at all**, and that includes every `moveTo`: `Movable.java:19,21` declares
  both forms with trailing `MotionOption...`, so `nozzle.moveTo(at)` fails with "Method
  moveTo(org.openpnp.model.Location) not found in class
  'org.openpnp.machine.reference.ContactProbeNozzle'" even though Java code calls exactly that. Pass the
  array yourself: `nozzle.moveTo(at, new Motion.MotionOption[0])` matches the erased signature, lands on
  `AbstractHeadMountable.java:280`, and so moves at the machine speed like any Java caller. The script
  wraps this in `goTo(n, at)`.
- **The interpreter has dynamic scope, and it ate a whole run.** A function that assigns to a name the
  caller already has writes into the *caller's* variable; only formal parameters are bound locally. So a
  helper that loops over `i` resets the caller's `i`, and every loop over the nozzles ended after the
  first nozzle. The run printed `=== Done`, looked entirely healthy, and reported one of the two nozzles
  for both Q1 and Q3. Confirmed in isolation:
  `f(k) { for (i = 0; i < 3; i = i + 1) { } } out = ""; for (i = 0; i < 4; i = i + 1) { f(1); out = out + i + ","; } out`
  answers `3,`, not `0,1,2,3,`. Every name a function in the script assigns now carries a leading
  underscore, which is the only guarantee that it does not belong to the caller.
- **Inside a method an array cannot be sized from a variable.** `new double[n]` dies with "Array index: 0
  does not evaluate to an integer" when `n` is a global, a parameter holding a global, or a local copy of
  one, whatever value it holds. A literal size, a static field, an array element and a method call as the
  size all work. So `measureTipOffsets()` sums what it collects and reports the RMS scatter out of running
  sums, and the rotation cycle keeps running minimums and maximums, and no array in the script is sized
  by anything but a literal.
- **Assigning `null` removes the variable.** `c = null` then `c == null` reads back true, but passing `c`
  anywhere dies with "Undefined argument: c" somewhere far from the reason, which is how
  `centre = calibrated ? ... : null` turned into an error eleven lines later. "There is no value" is a NaN
  location, `noValue()`, tested with `hasValue()`; `mag()` and `xy()` answer NaN and a dash for it.
  Reading Java `null` (a `measureTip()` that found nothing) is fine, and only comparing it is allowed.
- `Length` is not `Location`: the machine settings are `Length`s, so `machine.getDisplacementMaxDistance()`
  has `getValue()` and no `getX()`. `mmv()` is for those, `mm()` and `xy()` for positions.
- A class body needs typed fields. `nozzle;` is not a field declaration and assigning to it fails, and
  `public nozzle;` crashes the parser outright with an escaped `Parser$LookaheadSuccess`. `Object nozzle;`
  works and stays dynamic at call sites.
- No array literal: `x = [1, 2]` is a parse error.
- A `Map` is not reachable by field syntax: `m.nozzle = v` is `ReflectError: No such field`, no matter
  that later BeanShell versions allow it.
- Nested enum constants are fine, so `MotionPlanner.CompletionType.WaitForStillstand` needs no dance, and
  `Location.derive(null, null, z, null)` accepts `null` arguments. Both proven on the machine.
- `try { ... } catch (Exception e)` does not catch everything: a method that does not resolve escapes the
  handler and aborts the script, which is why the experiments below had to run one line per process.

### Checking a script without the machine, which is the whole point

Parsing the file with `bsh.Interpreter` and only the bsh jar is **not** enough, and the four aborted runs
are the price of believing it. The Scripting panel evaluates through `javax.script`, and
`BshScriptEngine` resolves things differently: the array-sized-from-a-variable rule above does not fire
under `bsh.Interpreter` at all, and classes are missing from the namespace unless the openpnp jar is on
the classpath. Two checks, both cheap:

```
# the file parses end to end; "undefined variable or class name: machine" means it did
java -Duser.home=/tmp/bsh \
     -cp ~/.m2/repository/org/beanshell/bsh/2.0b5/bsh-2.0b5.jar:\
target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar \
     bsh.Interpreter scripts/BatchResidualCheck.bsh
```

and the deeper one, in the repository next to the scripts it checks: `scripts/ScriptHarness.java` evals
the script's definitions through the same `BshScriptEngine` with fake nozzles, cameras and calibrations,
and calls `measureTipOffsets()` with `framesPerMeasure` exactly as the machine does. It checks the
averaging, the scatter, a partly failed measure, the nothing-measured path, that measuring a station does
not end the caller's loop over the stations, that `goTo` hands the move its option array, the empty
`MotionOption` array itself, and each of the array sizing and dynamic scope rules above. It exits non-zero
when a check fails, so it is worth running before a script is deployed:

```
java -Duser.home=/tmp/bsh \
     -cp ~/.m2/repository/org/beanshell/bsh/2.0b5/bsh-2.0b5.jar:\
target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar \
     scripts/ScriptHarness.java scripts/BatchResidualCheck.bsh
```

`-Duser.home` is not decoration: the probe opens its run log under `user.home` as soon as it evaluates, so
without it a check run writes a log into the home of whoever ran the check.

The general rule for scripts here, learned the expensive way: keep the arithmetic in plain statements and
one pass, give every function a leading-underscore name for everything it assigns, size no array from a
variable inside a function, never assign `null`, and never call a varargs method without handing it its
array.

### What the run before it said, with nothing calibrated

`batch-residual-2026-10-07_15.58.04.txt`. Only N1 of the two nozzles was measured, in either head position, because
of the dynamic scope bug above, and with no model loaded there was nothing for the motion to apply, so this
run answers neither Q1 nor Q3 in the sense the plan asked for. What it did show, and it is worth keeping:

- tip 502 uncalibrated, measured at 0/90/180/270 while its axis was commanded to stand still, went round a
  circle of mean radius 0.088mm, points `(-0.0134, 0.2137) (0.0801, 0.1336) (0.1469, 0.2404)
  (0.0490, 0.3205)`, with a scatter inside each position of 0.0000 to 0.0134mm - the half pixel of the
  detector. The reading is far finer than the thing it reads, which is what makes the calibrated run's
  residuals meaningful at all.
- a nozzle the head had not been moved for stood 0.1505mm from where its own shot wanted it, with `0.0000`
  applied to it, because with nothing calibrated the parallel path has nothing to apply. A head with no
  runout model is a state the runtime has to survive, and that is what it costs here.

### What the calibrated runs said

Two runs with N1 and N2 runout-calibrated, `batch-residual-2026-10-07_16.25.00.txt` and
`batch-residual-2026-10-07_16.53.34.txt`. Quote the second: it prints every detection and measures every
nozzle twice with nothing moved in between, so the readings the first one had to throw away are usable, and
where both are readable they agree.

```
    N1 / Upp1,  tip 502,  calibrated true, centre 0.0408 0.2053
    N2 / Upp2,  tip 502b, calibrated true, centre 0.1354 0.1440
```

0.2093mm and 0.1980mm of tip: `log_M.txt`'s constant `0.201657` is that, to within the difference between
two tips. The constant that has followed this investigation along was never a machine behaviour, it is
roughly what one of these tips sticks out.

One thing to have in mind for every number below: **every detection is a multiple of about 0.0133mm**, one
pixel of these cameras, because the tip detector has no subpixel refinement. 0.04mm is three pixels, and
0.0133mm is the floor under all of it.

**Q1: the motion does apply the runout at the rotation it is commanded to.** It is in the code as just
above, and the machine agrees. Each nozzle was commanded to the location that puts its tip on its own
camera's axis and then turned through four right angles; a tip nobody corrects for runout goes a whole
circle round while its nozzle turns, and a tip whose runout is corrected stands still:

| | circle radius | width it would have, uncorrected | mean off the commanded point | widest over the cycle | the model was fitted to |
|---|---|---|---|---|---|
| N1 | 0.0865 | 0.2447 | 0.0331 | 0.0678 | peak 0.0234, rms 0.0155 |
| N2 | 0.1023 | 0.2893 | 0.0373 | 0.0372 | peak 0.0228, rms 0.0127 |

A quarter to an eighth of the width the runout would have had it uncorrected, about the peak error the
calibration carried on the day it was made, and three to five pixels. So: applied, imperfectly, and what is
left belongs to the calibration rather than to a missing term. What it settles for the runtime is that
**the analysis must not add the runout again**, neither for the nozzle the head was moved for nor for the
one it was not - and `ReferenceBottomVision.java:2356-2362` adds exactly that, for both.

That verdict is not the one the 16:25 run printed, and the reason is worth keeping: the script had a
threshold of 0.02mm, which is a round number rather than the thing being ruled out, and it therefore called
a compensated tip uncompensated. The rule is now "less than half the width an uncorrected circle would span
here", printed next to that width, the pixel size and the model's own fit error, so the reader can disagree
with the threshold rather than with the measurement.

**Q3: the batch closes to about 0.04mm without the term, and the term is 0.18mm to 0.21mm.**

| moved for | nozzle | gap the batch has to close | what its own camera sees | left over | term the code applies |
|---|---|---|---|---|---|
| N1 | N1, the one moved for | 0.0000 | 0.0045 | **0.0045** | 0 |
| N1 | N2, not moved | 0.2129 | 0.1944 | **0.0414** | 0.1792 |
| N2 | N1, not moved | 0.2129 | 0.1912 | **0.0326** | 0.2071 |
| N2 | N2, the one moved for | 0.0000 | 0.0186 | **0.0186** | 0 |

Every row was read twice with nothing moved between the reads, and the two reads came back identical to
within 0.0126mm, which is a pixel. The camera of a nozzle that was never moved sees almost exactly the gap -
0.1944 of 0.2129, and 0.1912 of 0.2129 from the other head position - so its own bottom vision already knows about
the position the batch left it in and wants 3 pixels, not the `centre + runout` of 0.18mm to 0.21mm that
the legacy term charges it. Two head positions, two nozzles, same leftover from either side, which is what makes it
a property of the batch rather than of one odd camera. Whether the sign lands the resulting error at +0.18mm
or somewhere subtler is what stage 4a's side-by-side logging is for; the size is no longer an assumption.

**The first look after a move, which did not reproduce.** The 16:25 run had, as the first thing measured in
each head position:

```
    parked for N1, first look:  N1  0.2181  0.0356   (3 detections, scatter 0.3312)
    parked for N2, first look:  N1 -0.2983  0.1158   (3 detections, scatter 0.3501)
```

(Quoted as the probe printed it. Its own wording says "parked for", meaning the head position the batch was
taken to; the word is wrong there too - nothing is parked, the head is simply held where one placement's
shot wanted it - and the prose above and below says "moved for" instead.)

a third of a millimetre of disagreement between three consecutive frames of a head standing still, on the
same tip that had scattered 0.0000 in Q1 a minute earlier, while the second nozzle measured in each head position
scattered nothing. Twenty-eight minutes later, with the same calibration saved and the machine otherwise
untouched, the same two looks come back at 0.0126 and 0.0000 and the same tip reads `0.0045` where it had
read `0.2210`.

So it is not a settle finding, and it would have been wrong to publish it as one. It is a vision finding:
the detector occasionally locks onto something that is not the tip, three frames in a row can agree on the
wrong thing, and the mean of that is a number about two things that are both not it. Nothing about the run
says what it locked onto. Two guards come out of it and both are now in the script: every detection printed
on its own, so an aggregate cannot hide a bimodal set, and two looks with nothing moved between them, so a
reading that cannot be repeated is visible as one. A third is worth having in the runtime, and it is not in
this plan's scope: `measureTip()` goes to `findCircle()`, which calls `pipeline.process()` on **one frame
with no settling at all** (`ReferenceNozzleTipCalibration.java:1015-1021`), and the batch capture goes
through the `AbstractSettlingCamera` settling that keeps shooting until the frames agree. A calibration
reading that takes one frame and accepts it is how a 0.22mm fiction gets into a model.

Two things to keep in mind about the state these runs were made in. A runout calibration lives only in
memory until the configuration is saved - the 16:25 run read centres that `machine.xml` did not have yet -
and `setDisplacement()` resets every nozzle tip calibration on the machine, so the order of operations is
displacement first, runout second, always (`ReferenceCamera.setDisplacement()` -> `viewHasChanged()`). The
saved file now carries both, and the 16:53 run read the same centres back.

## Stage 4a: the two answers, side by side, on a real job

Build of 18:43 with stage 4a's recording in it, `pn52` restarted on it by hand, then the ordinary
`C0402-4n7/50V` job: eight parts in 17.1 s (1686 CPH), four parallel batches, two nozzles each. The
logging is one line per shot, so the whole exchange is in the log, and nothing else changed:

```bash
ssh pn52 'grep "Displacement of part" ~/.openpnp2/log/OpenPnP.log'
```

N1 was the nozzle the head was moved for in every one of the four batches, and N2 was the one left
wherever the head left it:

| batch | rotation N2 was photographed at | what the position says the shot was off by | width | the term that was applied to it | the analysis was off by |
|---|---|---|---|---|---|
| 1 | -180.106° | (-0.2141, -0.1331) | 0.252 | (0.0380, 0.1751) | 0.398 |
| 2 | -89.953° | (-0.1922, 0.0047) | 0.192 | (0.0380, 0.1751) | 0.286 |
| 3 | -0.106° | (-0.1929, 0.0076) | 0.193 | (0.0380, 0.1751) | 0.283 |
| 4 | -180.106° | (-0.2141, -0.1331) | 0.252 | (0.0380, 0.1751) | 0.398 |

N1's own residual over the same four batches: 0.0000, 0.0032, 0.0000, 0.0000, against the zero it is
handed. Everything in millimetres.

**The term is a constant; the residual is a circle.** The applied term is identical in all four batches,
because it is the circle centre plus the runout looked up at the shot's wanted rotation, and the parallel
path's wanted rotation is 0° whatever the head was actually turned to (E7). The measured residual moved
0.14mm in Y as N2's C went from -180° through -90° to 0° - that is N2's runout circle, radius 0.102mm,
turning on a job rather than on a probe. So E7 and E3 are confirmed, and they say something the code
reading alone did not: the predicted term cannot be put right by correcting its formula, because the angle
it would need is not in the shot description anywhere, whereas the position the machine was in already
contains the answer.

**Two instruments, the same number.** The probe measured N2's tip 0.1944mm off its camera axis by looking
at it (`batch-residual-2026-10-07_16.53.34.txt`); the machine's own bookkeeping says the shot was taken
0.192mm off, twice, at two different rotations. An image and a position agreeing to a couple of microns is
the evidence that `wanted - actual` is the quantity the frame shows, which is what the switch of 4b rests
on. It also puts `log_M.txt`'s constant 0.201657 where it belongs: the same 0.19mm to 0.25mm of tip
geometry, not a behaviour of the machine.

**How far wrong the applied term is: 0.28mm to 0.40mm.** That is the distance between the two vectors,
which is precisely what the analysis misplaces the nozzle by, since it subtracts one of them from the same
`wantedLocation`. It is larger than the 0.2mm that started this investigation and it is not even in the
same direction - the term pushes mostly +Y where the truth is mostly -X. What it is not is a measured
placement error: the end-to-end gauge of Q5 still does not exist, so the honest statement is that the term
the analysis uses and the position the machine was in disagree by this much, on every shot of the nozzle
that was not moved for, on a job that ran at full speed and finished.

**Something new, and it is not displacement.** N2 reported its Z at capture as the negation of N1's in all
four batches: both shots wanted Z +0.4330mm, N1 was there at +0.4328mm, N2 reported -0.4328mm. The
magnitude tracks N1 exactly and the sign does not, which is not physics, since both tips hang off the same
head. It matters for stage 4c, because `displacement-max-Z-diff` is 0.1mm and this makes the Z term of the
limits 0.87mm for one of the two nozzles every cycle: a limit enforced in the parallel path would refuse
every second shot for a reason that has nothing to do with displacement. It is not caused by stage 4a - the
reading is a plain `nozzle.getLocation()` - but it was invisible until there was a line printing the wanted
and the actual next to each other. Filed as E9.

## Stage 4b: the switch, and what a run of it has to show

Written 2026-10-07, code done, run pending. The two answers that Stage 4a printed side by side are now
one setting apart, so a machine can be driven on either while it decides which one to believe.

The Java property is `displacementLegacyTerm`, and OpenPnP's serializer hyphenates property names
(`Configuration.createSerializer()` wraps a `HyphenStyle`), so in `machine.xml` it is
`displacement-legacy-term`, a sibling of `displacement-enabled`. Since the 20:38 run below it ships
`false`, i.e. the measured residual, so a machine gets the new behaviour without writing anything; the
element exists to go back:

```xml
      <displacement-legacy-term>true</displacement-legacy-term>
```

which is exactly the behaviour every job up to and including the 18:52 run above had. The default was
flipped on 2026-10-07 on the strength of the identity below - the analysis is demonstrably handed the
position the head was really in - and in the absence of any way to measure the consequence on a real part.

There is no GUI control for it, and OpenPnP rewrites `machine.xml` whenever it saves, so the edit is made
with OpenPnP shut down and is then permanent until edited back. That is deliberate for a setting whose
whole purpose is to be switched off permanently: to go back, write `true`. The
round trip was checked rather than assumed, because the Java property and the XML element do not spell
themselves the same: a machine saved with the property false came out as `<displacement-legacy-term>false
</displacement-legacy-term>` and a second process reading that file answered false, while the same machine
with the element absent answered true.

*Written to `pn52:~/.openpnp2/machine.xml` at 20:35 on 2026-10-07*, on line 4849 directly below
`<displacement-enabled>true</displacement-enabled>`, with OpenPnP not running and the previous file kept as
`machine.xml.pre-4b-20261007`. The next job started there runs on the measured residual.

What the log says differs, and the difference is the thing to look for:

- the capture line names its own term. `Displacement of part X on camera Upp2 nozzle N2: measured term
  (-0.214, -0.133, ...), the other answer is (0.038, 0.175, ...), the two are 0.398 apart` with the switch
  off, and the same line reading `legacy term` with it on. The 4a line and this one are the same
  information; the applied choice is now in the sentence rather than something the reader has to infer.
- the analysis line is one line for both paths instead of two branches: `Bottom vision part X displacement
  ... compensated on Upp2 (parallel), nozzle location becomes ...`. The nozzle location it prints is the
  one the part will be placed relative to, and with the switch off it should be the position the head was
  really in when the frame was exposed - Stage 4a measured that as `wanted - residual`, so the two numbers
  can be checked against each other by hand on one part.

Expected on this machine, from the four batches above: the term applied to N2 stops being the constant
`(0.0380, 0.1751)` and becomes the `(-0.19, -0.13)` to `(-0.19, +0.01)` that moved with its C rotation,
while N1 keeps `0` because its residual is `0`. The `0.283-0.398mm` of disagreement Stage 4a measured
should therefore stop being a disagreement about the term, which is all this stage can prove from a log.

What it cannot prove, and what the run must not be credited with: **that the parts land better**. The term
being the honest residual says the analysis is no longer told a fiction; whether a `C0402` then sits inside
its courtyard is Q5, and Q5 still has no measurement method that this document will accept. So the run is
to be done the way §9 has it - the same job twice, switch off then on, diffing the logged terms - and the
placement verdict waits for the gauge.

Two things carry over unchanged and one is worth noticing:

- the limits (`0.5mm`, `0.1mm`, `0.01°`) are not touched by 4b. `displacementCheck()` reads whatever term
  the shot carries, so with the switch off it now judges the real residual, which on this machine is
  0.19-0.25mm and comfortably inside `0.5`. Silence from that check no longer means "the prediction was
  small"; it means the frame was taken from near where it wanted to be.
- E9 is still there: the residual is XY-only, so the negated Z of the second nozzle is invisible to it and
  the Z limit stays blind. That is 4c's problem, and 4c cannot pick its Z limit until it is understood.
- the `0.` rotation of E7 stops mattering for the term, because the measured residual never consults the
  rotation at all. It still matters for `wanted`, which is what the residual is measured against, so E7
  stays open until 4d makes the shot carry the rotation it was taken at.

## Stage 4d, first half: the batch refuses post-rotate, and needs no machine to say so

D10 was settled as a decision rather than a measurement, so this half of 4d is recorded here only for what
it changes on a machine, which is nothing at all on `pn52` and everything on one that ships the OpenPnP way.

The parallel batch now asks every planned part how it is aligned before it captures anything, through
`ReferenceBottomVision.usesPreRotate()` - the same predicate that the pre- and post-rotate branches inside
`findOffsets*` were changed to ask, so the batch and the alignment can no longer disagree about a part. Any
part that is post-rotate sends the whole batch sequential. That is true whether it is post-rotate because
the machine default says so (`pre-rotate="false"`, which is how OpenPnP ships) or because one package or
part said `Pre-Rotate Usage = AlwaysOff` on a machine that is otherwise pre-rotate; one part placed by the
head's displacement is one part placed wrong, however well the rest of the batch was aligned. A refusal is
neither an attempt nor a fallback - the tallies stay where they were - because a route not taken is not a
failure, and the log line names the part and says what to do about it:

```
[PARALLEL-VISION] not attempted: part R0603-1K of placement B is aligned post-rotate, which cannot
compensate being displaced from the shot the head moved for. Align everything sequentially; set
Pre-Rotate on the bottom vision (or Pre-Rotate Usage = AlwaysOn for the part) to let this job go parallel.
```

The warning in `displacementCheck()` that this replaces is kept, but as an error: the only way to reach it
now is for a part to have slipped past the refusal, which is a bug rather than a configuration.

Proved offline, three ways, in `PostRotateParallelRefusalTest`: a pre-rotate job across two bottom cameras
must attempt the parallel alignment (the control, without which the other two would prove nothing); the same
job with the machine default off must not attempt it and must still place both parts; and one `AlwaysOff`
part must refuse the batch of an otherwise pre-rotate machine. `EarlyDepartureAbortTest`, whose test machine
is `pre-rotate="false"`, had to be told to turn pre-rotate on, because its premise is a deferred alignment
over the cameras and there now is none to abort; it also asserts the attempt count from then on, so that it
cannot quietly stop testing the parallel path again.

`pn52` is `pre-rotate="true"` at machine level, with no `preRotateUsage` in its `packages.xml`, so the 20:38
run above stands and the refusal never fires there. What it would look like if it did: `[PARALLEL-VISION]
attempts=0 fell back=0 frames refused=0` at the end of a job that used to report attempts, the head visiting
the cameras between every pick, and the part named in the line above.

*Checked on the machine, 2026-10-08 09:32*, the same `testjob_8-placements_rm10_v40017-1` on the jar built
for the refusal: `Job finished 8 parts in 17.1 sec. This is 1,682.6 CPH`,
`[PARALLEL-VISION] attempts=4 fell back=0 frames refused=0`, eight capture lines all saying `using the
measured term`, and no `not attempted` line anywhere in the session - which is the whole of what a
pre-rotate machine can be asked to prove about a refusal. Not one `WARN` or `ERROR` in the session either.
Against the two runs before it the speed is unchanged (17.1s, 17.3s, 17.1s).

The run also showed E7 and E9 unbidden, in the same lines. The head was turned to the placement rotation -
`nozzle actually at (179.250035, 27.558578, 0.433000, -180.000000 mm)` - while the shot it was captured for
claims `wanted (179.250035, 27.558578, 0.433000, 0.000000 mm)`, and the residual on the nozzle the head was
not taken for moved from `(0.214, 0.133)` at `-0.137°` to `(0.193, -0.008)` at `-180.137°` while the legacy
term sat at `(-0.046, 0.202)` both times. E9 too: this time it was N1 reporting Z `-0.433` against the shot's
`+0.433`, having been the nozzle not moved for, exactly as it did to N2 the day before.

## Stage 4c: the limits stop being a caption

Until now the parallel path printed the displacement of a shot after the pipeline had already used it, and
nothing could be done with the number. The sequential path has always done the opposite: the same three
numbers from `machine.xml` decide, *before* the picture is taken, between skipping the move onto the shot and
making it. A batch never makes that move - the head stands at one shot location and the other nozzles are
where the rigid head leaves them - so it needed the decision itself rather than a translation of the
sequential one, in the one moment where it can still be acted on: the head has arrived under the cameras and
nothing has been exposed.

Two changes, both of them about the shot rather than about the arithmetic:

- The shot is taken at the machine's **balance level**, by `ReferenceBottomVision.withBalanceLevelZ()`, for
  the batch as it already was for the sequential pipeline shot: the head's alignment Z and every single-shot
  shot location of a batch go through it. This is what the original code did (`docs/original_bottomvision/`),
  and it is what makes the Z limit computable at all on this machine - see E9 below.
- `ReferencePnpJobProcessor.checkDisplacementLimits()` walks the planned placements once, between the head
  arriving and PASS 1 submitting its captures, and throws `DisplacementExceededException` for the first
  nozzle standing further from its own shot than `displacement-max-distance` or `displacement-max-Z-diff`
  allow. Parts that need several shots are left out: those are shot later, one pass at a time, with the
  nozzle moved for the part on each of them, so the limits belong to that move as they do on the sequential
  path. `alignAllParallel()` counts one fallback and aligns every part of that batch sequentially. The check
  is inside `preCaptureAllImages()`'s `try`, so the ring and the camera batch operation are still released
  when it fires, and nothing has been exposed when it does: the cost of a refusal is the move the batch was
  going to make anyway.

Rotation is not compared, which is worth saying because the original code compared it here too. Nothing turns
a nozzle during a batch; the head arrives holding each nozzle at the rotation its placement is to be placed at
and holds it there through the exposure. The rotation difference that `displacement-max-rotation` is about is
between a part's *measured* angle and the angle it was commanded to, which is only knowable after a pipeline,
and comparing the shot's recorded rotation instead would compare a rotation with the `0.` of E7. See D12.

Offline, `DisplacementLimitRefusalTest`, on the two-bottom-camera job machine with displacement switched on:

- a machine within its limits must still go parallel, so that the check cannot pass by refusing everything;
- a second camera bumped 1mm off its head offsets must send the batch sequential, naming the nozzle, the
  distance, the camera and the Z, with the parts still placed by the sequential path;
- a head whose second nozzle cannot be brought to the height of the shots must be refused rather than
  photographed out of focus - the simulated head has independent Z axes per nozzle, so this is what it is;
- and `withBalanceLevelZ()` must apply the balance level as a `Length`, in its own units, leaving X and Y
  alone. The plain double this replaces would have been read in the units of the shot location, which on an
  inch machine is 10.7mm on a 0.1mm tolerance.

```
WARNING: Parallel alignment fell back to sequential for all nozzles (1 of 1 in this job) : nozzle N1 stands
1.000mm from its own shot location on Bottom 1 (Z 0.000mm), while the tolerated displacement is 0.500mm
(Z 10.000mm)
```

370 tests, 0 failures, 0 skipped.

E9, which is what made the Z limit look uncomputable, is a modelling fact rather than a fault, and this is
where it stops mattering: `pn52` drives both nozzle heights from one motor axis `ZN`, with
`Z1 = ReferenceCamCounterClockwiseAxis(ZN)` and `Z2 = ReferenceCamClockwiseAxis(Z1)`, so the second nozzle's
negated Z is that rocker turning the other way. The two nozzles' reported Z values cannot be compared with
each other; comparing each against the balance level compares each with the height it is really at, and at
the balance level both are about 0.

**What was owed, and what it can say.** The same `testjob_8-placements_rm10_v40017-1`, on the jar carrying
4c. Not speed but three things:

- The capture lines had to show the shot Z at the balance level, `0.000000`, where the runs above show
  `0.432824`. That is the visible sign the change landed, and E9's `-0.433` had to disappear with it,
  because both nozzles are now near 0 instead of one near each other's negation.
- `attempts=4 fell back=0` as before. Fallbacks with a `stands ... from its own shot location ... (Z ...)`
  line would have meant that the balance level on this machine is not the height the two cameras agree at,
  and the number in that line would have been what `balance-level` should be set to - the refusal would then
  have been doing its job, and the setting the thing to change, not the code.
- The parts still had to be found and placed. The batch is now photographed at one height for both cameras
  instead of each camera's focal height, so a pipeline that suddenly could not find a part would be a focus
  problem in `balance-level` and not a reason to loosen a limit.

*Run 2026-10-08 11:12*, on `2.7_2026-10-08_09-07-04.e0e20dc`: all three.

```
Job finished 8 parts in 17.4 sec. This is 1,651.8 CPH
[PARALLEL-VISION] attempts=4 fell back=0 frames refused=0

Displacement of part C0402-4n7/50V on camera Upp1 nozzle N1: using the measured term
(0.192821, -0.007956, 0.000000, 0.000000 mm), the other answer is (-0.045611, 0.201968, ...) ...
(shot wanted (156.372565, 27.615344, 0.000000, 0.000000 mm),
 nozzle actually at (156.179744, 27.623300, 0.000000, -180.105116 mm))
```

Eight capture lines, all `using the measured term`, all at shot Z `0.000000` on both cameras, and not one
`WARN` or `ERROR` in the session. E9 is gone from the log because there is nothing left for it to be about:
the nozzle the head was not taken for now reports `0.000000` where it reported `-0.433` against the shot's
`+0.433`, and the two are the same height rather than each other's negation.

What that did *not* change is the part of the line that matters, which is the point of comparing it with the
two runs before it. The residual on the nozzle the head was not moved for is `(0.214076, 0.132842)` here and
`(0.214071, 0.132816)` at 2026-10-07 20:38 - the same physical mismatch of camera spacing against nozzle
spacing, to within 30nm. Shooting the batch at the balance height moved the height the pictures are taken at
and left the measured XY difference where it was, which is what "the two are orthogonal" has to mean before
either can be trusted. Speed, across four runs of this job now: 17.1, 17.3, 17.1, 17.4 seconds.

The focus question is answered in the weakest sense it can be answered in: every pipeline found every part
at the balance height on both cameras, so `balance-level = 0` is a height this machine photographs at. The
strong sense - that the parts landed where the numbers say they did - is still Q5, still needs a gauge, and
no run of this job substitutes for it. E7 is visible in the same lines, unchanged: `wanted ... 0.000000`
while the head stood at `-180.105116`, which is the last thing 4d owes.

## Stage 4d second half and 4e: the rotation belongs to the shot, and the old term is gone

Two changes, one jar, neither of them visible in speed.

**E7.** A shot's `wanted` used to carry a rotation of `0.` while the head held the nozzle at the placement
rotation, so the recorded location described a frame nobody had taken. `ReferenceBottomVision.shotRotationOf()`
is now the one answer - the placement rotation transformed by its board and normalised, `0.` for a post-rotate
part - and the head's alignment move, `getLocation()`, the settle-window captures, the multi-shot pass and the
pre-exposure check all use it. On the way it was found that the head's own alignment move used the
*untransformed* placement rotation, so a rotated board took the batch to an angle no part is placed at; that
one commanded hardware, which is the difference between a wrong caption and a wrong move.

**4e.** `displacement-legacy-term`, the predicted `displacementDelta()`, its hand-written twin in the settle
path, the `isFirstNozzleTip` argument that only the prediction read, and `intendedDisplacementDelta` on the
shot are all deleted. One quantity, `getShotDisplacement()`, wanted minus where the nozzle was read at the
exposure, from both the sequential and the parallel path, subtracted in one place. A shot whose nozzle
position was never read used to fall back on the prediction; there is nothing to fall back on now, so it is
refused and the batch goes sequential.

Offline: 372 tests, 0 failures, 0 skipped, `ShotRotationTest` the new one and
`ParallelBatchResidualTest` down to its single contract test.

**Before the jar is started, not after.** `pn52`'s `machine.xml` line 4849 carries
`<displacement-legacy-term>false</displacement-legacy-term>`. Simple XML does not ignore what it does not
know, so OpenPnP will refuse to load the machine and say *"Element 'displacement-legacy-term' does not have a
match in class org.openpnp.machine.reference.ReferenceMachine at line 4849"*. The line has to be deleted by
hand, with OpenPnP stopped, and the file backed up first.

**What the run has to show**, on the same `testjob_8-placements_rm10_v40017-1`:

- The capture line has a new shape and one answer in it: `Displacement of part ... on camera ... nozzle ...:
  measured (x, y, 0, 0) (shot wanted (...), nozzle actually at (...))`. Any line still saying *using the
  measured term* or *the other answer* is an old jar.
- The rotation in `shot wanted` is no longer `0.000000`: it matches the rotation in `nozzle actually at` on
  the same line, on both nozzles, to within the angle hold. On the run above the head stood at
  `-180.105116` while `wanted` said `0.000000`; that pair cannot appear again.
- The agreement in the previous bullet is the machine's, not the code's: the rotation the shot records is the
  rotation `PrerotateAllNozzlesForAlign` commands, and the pre-exposure check deliberately does not compare
  rotation (D12). So a `wanted` and an `actually at` that disagree by more than a fraction of a degree mean
  the pre-rotation step is not holding, which nothing else in the log would report.
- `attempts=4 fell back=0 frames refused=0`, no `WARN`, no `ERROR`, and the residual on the nozzle not moved
  for still near `(0.214076, 0.132842)` - the physics of the spacing mismatch is not what either of these two
  changes touches, and if it moved, one of them is wrong about more than it claims.
- Speed in the 17.1-17.4s band. The prediction cost nothing to compute, so 4e buys nothing measurable; the
  rotation costs one transform per shot location.

### Run 2026-10-08 12:23: the rotation is in the line, and there is only one answer

`2.7_2026-10-08_10-13-04.e0e20dc`, `testjob_8-placements_rm10_v40017-1`, config as above (the orphan
`<displacement-legacy-term>` deleted from `machine.xml`, backup `machine.xml.bak-4e-*`).

```
Job finished 8 parts in 17.3 sec. This is 1,669.4 CPH
[PARALLEL-VISION] attempts=4 fell back=0 frames refused=0
```

Eight capture lines, all in the new one-answer shape, zero `WARN`, zero `ERROR` in the session. The batch
that moved for N2, with N1 left standing:

```
Displacement of part C0402-4n7/50V on camera Upp2 nozzle N2: measured (0.000000, 0.000000, ...)
    (shot wanted (179.250035, 27.558578, 0.000000,  179.878217 mm),
                nozzle actually at (179.250035, 27.558578, 0.000000, -180.121783 mm))
Displacement of part C0402-4n7/50V on camera Upp1 nozzle N1: measured (0.214212, 0.132985, ...)
    (shot wanted (156.372565, 27.615344, 0.000000,   -0.121783 mm),
                nozzle actually at (156.158353, 27.482359, 0.000000,   -0.121783 mm))
```

Four things come out of those eight lines, and they are four different claims.

**E7 is fixed on hardware, not in a unit test.** `wanted` no longer says `0.000000`. It says the rotation
the nozzle is held at: N1's `wanted` `-0.121783` against an actual `-0.121783`, N2's `wanted` `179.878217`
against an actual `-180.121783`, which is the same angle written 360° apart because the shot normalises to
-180..180 and the axis reports past the end. A batch taken the other way round in the same job shows a `90`
placement recorded as `-90.121783`. So the recorded location describes the frame, and the pre-rotation step
(D12) is seen doing its work on a nozzle the head was not moved for.

**The rotation carries the board, which is the part that used to command hardware.** All three placements
of the batch differ from their `wanted` rotation by exactly `-0.121783°`, the fiducial-derived board
correction. The head-alignment move used to be given the untransformed placement rotation; it now gets the
same number the shot records, so a rotated board can no longer move the head to an angle no part is placed
at.

**The identity holds exactly.** `wanted - measured = actually at`, to the last printed digit on both axes of
both nozzles: 156.372565 - 0.214212 = 156.158353, 27.615344 - 0.132985 = 27.482359. Nothing predicted is
left in the term, because nothing predicted is left at all.

**The physics did not move.** The residual on the nozzle not moved for is `(0.214212, 0.132985)` against
`(0.214076, 0.132842)` on the 4c run and `(0.214071, 0.132816)` on the 4b one - the same camera-spacing
against nozzle-spacing mismatch, now to within 140nm rather than 30nm, which is the machine being a machine.
The other batch says `(0.192800, -0.007766)` where 4c said `(0.192821, -0.007956)`. Speed 17.3s, inside the
17.1-17.4s band of the four runs before it, as expected of a change that deletes arithmetic.

What is still owed is unchanged and unaffected by anything above: the parts landing where these numbers say
they land (Q5) needs a gauge.
