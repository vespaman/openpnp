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

While run C is going, sample the process memory:

```
while :; do echo "$(date +%s) $(ps -o rss= -p $(pgrep -f openpnp | head -1))"; sleep 5; \
done > /tmp/pbv-rss.log
```

Pass: RSS rises while the job runs and then flattens. A line that climbs steadily across hundreds
of placements, run after run, is the native `Mat` leak this is guarding against; keep the log and
the RSS file.

## 5. Abort, resume, retry (R6)

With the setting on (run C):

1. Start the job, abort it in the middle of the alignment phase, then resume / restart it. It must
   align again properly - no placement skipped, no image of the previous run used.
2. Repeat three times, at different moments (during capture, during a move, while a part is being
   placed).
3. Then: put a nozzle to a feeder it will not pick from, or lift the part off the nozzle by hand
   after the pick, and start the job. The machine must stop on the missing part - this is the
   part-still-on-nozzle check the parallel path had lost and `92c971eb18` put back. If it calmly
   tries to place air, that is a failure of this branch and a pass of the old behaviour.

## 6. Lights

After a finished job and after an aborted job, all camera lights must be off
(`CameraBatchOperation`). An aborted job that leaves a light on is a bug worth reporting.

## 7. Single camera

Disable all but one bottom vision camera (or run a job whose nozzles all use one camera). The
behaviour must be that of run A exactly, including for a single placement in the batch. This is the
case that must not change for anyone else either.

## 8. Settings round trip

Set `parallel-alignment-enabled` to `false`, save the machine (`Machine` → `Save`), restart, and
check the value is still `false` and that `machine.xml` contains

```xml
<parallel-alignment-enabled>false</parallel-alignment-enabled>
```

Same for the displacement settings. Watch the log at startup for XML/unknown element complaints.

## Recording

Put the logs of each run in a directory per run (`~/pbv-test/A/`, `A2`, `B`, `C`, ...) together with
the `machine.xml` used, and fill the table in section 2. If something fails, the useful things to
keep are: the log, the settings, and *which* of the checks above failed - do not clean up before
copying the log out.

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
