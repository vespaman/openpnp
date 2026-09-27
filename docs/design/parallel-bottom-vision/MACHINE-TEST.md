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

   What the runs are, as of this branch (see R14 in `SALVAGE-PLAN.md`): A is the classic upstream
   behaviour, one nozzle walked to its shot location at a time. B is the parallel path with every
   nozzle still driven to its exact shot location. C is the parallel path where the head parks once
   and the nozzles other than the settle nozzle are photographed where they happen to be, their
   calibrated camera offset plus runout being compensated in the analysis. `displacement-enabled` in
   run A does almost nothing here - in this branch the sequential path only takes the balance level Z
   from it, it does not yet have the `multi-bottom-vision` trick of skipping a move that is small
   enough. So compare run A with upstream, not with the old `multi-bottom-vision` timings.


The test job: more than one bottom vision camera in use, more than one nozzle per camera, at least
~100 placements, more than one part type, and at least one part whose package uses vision
compositing (multi-shot). A production job of yours is ideal - do not build a synthetic one.

## 1. Which path ran

For each run, in `OpenPnP.log`:

```
grep -c "Using parallel alignment" OpenPnP.log     # expect: run A 0, runs B and C >= 1
grep -c "Using sequential alignment" OpenPnP.log   # run A >= 1
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
absolute: for a few placements that appear in all three runs, look at the `Offsets accepted {...}`
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
