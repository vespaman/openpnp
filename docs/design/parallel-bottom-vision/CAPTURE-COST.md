# What a frame costs, and what a rewrite would buy

Written 2026-10-01 night, answering three questions put at the end of the `log_y.txt` run: is the
9-14 ms it takes to capture a frame exposure or CPU; is handing work to another thread in Java costly;
and would the camera part be better off in a C++ or Rust service.

Everything below is measured from `tests_2026-10-01/log_x.txt` and `log_y.txt` (one job of 8 parts,
10.8 s each), from `pn52`'s `machine.xml`, or from the code. Where something is not measured it says so.

## How much of a job the cameras own

The settling diagnostics print every frame of every settling (`t=`, the score, and the compute time), so
the whole camera budget can be read out of a job log. Per frame and per settling, for the two kinds:

| | settlings | frames | sum of settlings | first frame | later frames | compute per frame |
|---|---|---|---|---|---|---|
| fiducial, `log_x` | 16 | 57 | 2010 ms | 46.1 ms | 30.9 ms | 4.5 ms |
| fiducial, `log_y` | 16 | 67 | 2472 ms | 54.4 ms | 31.3 ms | 5.4 ms |
| bottom, `log_x` | 8 | 27 | 470 ms | 28.6 ms | 12.7 ms | 2.1 ms |
| bottom, `log_y` | 8 | 22 | 454 ms | 30.8 ms | 14.7 ms | 2.5 ms |

Those rows are over a whole log file, and **each log contains two jobs**, so they must not be read as one
job. Attributed to job boundaries (`Job finished N parts in T sec`, going back from it) the same data is:

| log | the job | settlings | inside a settling loop | verdict to next `G1` | total |
|---|---|---|---|---|---|
| `log_x` | 10.8 s, 8 parts | 12 | 1204 ms (11.1 %) | 184 ms | 1388 ms |
| `log_y` | 11.3 s, 8 parts | 12 | 1543 ms (13.7 %) | 349 ms | 1892 ms |

(The other job in each file is slower and interrupted - 16.9 s in `log_x` with no error, 25.3 s in
`log_y` with an OCR failure and a vision exception - so it is left out of the budget.)

So the pool that camera work owns is **1.4-1.9 s of an ~11 s job, 12-17 % of it**, not "a third". The head
is standing still for the whole of that time: each settling starts after the arrival is confirmed and the
next move is not offered until the measurement is in, which was checked against the `commandQueue offer`
lines for every settling. The async join this branch has been chasing is 38-80 ms of that pool, and the
exposure of a frame is 100 µs to 5 ms.

No idea can save more than that pool, and none can save the vibration decay inside it - the machine has to
come to rest before a placement is worth measuring. What the ideas can touch is the part of the pool that
is waiting for a frame instead of waiting for stillness, and that is what the last section now counts.

## A capture is neither exposure nor compute: it is one frame interval of the device

The devices were read directly on `pn52` with `v4l2-ctl`, and the numbers settle the question:

| camera | `/dev` | running at | exposure | frame interval the device offers |
|---|---|---|---|---|
| Upp1, Upp2 (`ELE01`) | video4, video0 | 960x1080, **120 fps** (`--get-parm`) | `exposure_time_absolute = 1`, manual (unit 100 µs → **0.1 ms**, default is 512) | **8.3 ms** |
| Down (`FHD Camera`) | video2 | 1080p MJPG, **30 fps maximum at every size it makes** | `exposure_time_absolute = 50` → **5 ms** | **33 ms, and it cannot be raised** |

Against that: a bottom settle frame takes 12.7 ms wall (2.1 of it the OpenCV difference), and a fiducial
settle frame 30.9 ms (4.5 of it compute). So the exposure the user measured - 8-9 ms - is not what the
cameras are doing either: the bottom cameras expose for 0.1 ms and the down camera for 5 ms. What the
settle loop pays per frame is **one frame interval of the stream**, plus a few milliseconds of Java and
OpenCV.

`OpenPnpCaptureCamera.internalCapture()` (line 101) shows why it has to be a whole interval:

```java
while (!stream.hasNewFrame()) {
    Thread.yield();
    ...
}
BufferedImage img = stream.capture();
```

Capture is on demand. The stream produces a frame every 8.3 ms (bottom) or 33 ms (down), and a demand
arrives at a random point inside that interval, so a capture costs on average about half an interval of
waiting and at most a whole one, and a settling of four frames costs four intervals because each frame is
asked for separately. Nothing in Java can shorten that; the frame does not exist yet.

Two consequences worth saying plainly:

- **The down camera is a 30 fps webcam and there is no format that goes faster.** Every fiducial look is
  ~3.5 frames × 33 ms ≈ 115 ms, 16-21 looks a job, ~2 s of the job. The only levers are fewer frames per
  look, fewer looks, or a faster camera.
- **The bottom cameras are already at their maximum, 120 fps**, so their 12.7 ms is 8.3 ms of interval plus
  2.5 of compute plus the wait for the next boundary. The interval can only be removed by not asking for
  frames on demand - see the ring buffer below.

The same spin loop is also how two cameras on separate USB3 roots manage to slow each other down: a
waiting capture is a *runnable* thread burning a core, and while two cameras settle there are two of them,
plus the vision executor. `log_y` saw exactly this: four times the grabs cost nothing on the bus and did
show one batch's `capture=` go from 9 ms to 21 ms.

The `fps="3.0"` and `fps="6.0"` attributes in `machine.xml` are the preview broadcast rate, not the stream
rate; the stream runs at what the negotiated format gives, which the table above measured.

One thing falls out of this that is free: since the bottom cameras expose for 0.1 ms inside an 8.3 ms frame
interval, **exposure time is not on the critical path there**. It could be raised several-fold, to
something more like 1-4 ms, without costing a millisecond of job time - and if any of the 30-110 µm
fiducial scatter is light and signal-to-noise rather than mechanics, that is measurement quality bought
for nothing. It has to be tried on the machine and looked at, not assumed.

## What Java costs here, itemised

- **Thread handoff: not measurable.** The deferred alignment and the async capture go through
  `ReferencePnpJobProcessor.getVisionExecutor()`, a pooled `ExecutorService`; a submit and a `Future.get`
  on a warm pool is tens of microseconds. The join that has been the obsession is 7-11 ms, which is one
  frame interval (above), not a scheduler.
- **Per-frame image plumbing: 2-5 ms a frame, and ~3 MB of garbage.** `ReferenceCamera.capture()` runs
  `transformImage()` on every frame, and with advanced calibration on (both `Upp` cameras have it
  enabled) that is `OpenCvUtils.toMat()`, white balance, `advancedUndistort()` and
  `OpenCvUtils.toBufferedImage()` - three conversions and a remap of a 960x1080 image, every frame,
  including all ~110 settling frames of a job. The settling loop then blurs and diffs them. That is the
  `compute time:` column, and it is JNI calls plus allocation, not arithmetic that a Rust rewrite would do
  differently: `GaussianBlur`, `absdiff` and `norm` are already native.
- **GC.** A job moves ~350 MB of `BufferedImage` through the heap and `openpnp.sh` passes no GC options
  at all on a 15 GB box, so it is default G1. This is a suspicion rather than a measurement: the way to
  settle it is `-Xlog:gc*` on one job and look for pauses at the moments the log shows a `capture=` spike.
- **Diagnostics are on.** `settle-diagnostics="true"` on all three cameras makes `autoSettleAndCapture()`
  convert every settle frame to a `BufferedImage` and keep it in a `TreeMap` for the graph, plus the graph
  bookkeeping. That is per-frame work and per-frame garbage paid for a picture that is looked at once in a
  while. It should be off outside a diagnostics run.

## Would a C++ or Rust service help?

The pixel work is already native: capture is the `openpnp-capture` C library, and the vision pipelines are
OpenCV through JNI. What a service of one's own would remove is the Java-side copying and the GC pressure
of frames that are thrown away, and it could add one thing Java cannot easily get: the driver's own
per-frame timestamps, which say when an exposure actually started rather than when Java got round to
asking.

Ranked by what it is worth on this machine, per job of 10.8 s:

| idea | ms/job | what it is |
|---|---|---|
| a ring-buffer grabber instead of capture-on-demand | ~700-800 | the ~8.3 ms interval per bottom frame (22-27 frames), the cold first frame everywhere, and the async join - all three are the same demand-driven wait |
| declaring the head still one frame sooner (the threshold) | ~500 | modelled from `log_y` below; the down camera cannot go faster than 30 fps, so its 2 s is frames x 33 ms |
| cheaper settle frames (raw, downscaled, diagnostics off) | ~300-500 | the 2-5 ms of conversions and copies per frame, and the garbage |
| no spinning wait in `internalCapture()` | ~50-150, mostly variance | stops stealing the CPU from the camera that is trying to work |
| the async join gone on its own | ~40-80 | what this branch has been about |
| rewriting the vision in Rust | ~0 | `GaussianBlur`, `absdiff`, `norm` and the capture are already native |

So: a rewrite of the vision buys nothing, because the vision is not the cost. But a *grabber* service is a
genuinely different thing from what is there now, and it is the only shape that can take the join below one
frame interval:

> one thread per camera, running for the whole job, taking every frame the device produces into a ring of
> the last N frames, stamped with the driver's own exposure timestamps; the settling and the vision then
> ask it for "the newest frame whose exposure began at or after T" and it answers from memory.

That removes the cold-first-frame penalty (a fresh frame is always in hand), removes the spin, makes the
still-window claim exact instead of inferred from `tCapture`, and turns the settling's detection latency
into the vibration decay instead of four frame intervals. What it cannot remove is the physics: a frame
that is to be inside a still window still cannot be exposed before the window opens, which is the wall
`log_y` hit. The light would have to stay on for the whole job, and the frames of a moving head would sit
in memory, which is a change to the machine's behaviour and not only to its code.

## The other thing the table says

12-16 fiducial settlings a job, ~3.5 frames each, 30 ms apart. Each fiducial is looked for **twice**:
`ReferenceFiducialLocator.getFiducialLocation()` runs `getMaxVisionPasses()` home-in passes and breaks out
early only when the last offset is under `getMaxLinearOffset()`. In `log_y`'s clean job the first pass came
in 0.78 mm off the nominal on the first fiducial, which is more than the 0.2 mm tolerance, so a second pass
always runs: four fiducials, eight looks, 2.95 s from the first `Looking for` to the last `located at` -
26 % of the job, of which 1.32 s is settling. That is the largest single camera block in the job and it is
a property of the fiducial strategy, not of the parallel vision work.

What the settling is protecting has also been mis-stated here and in `TIME-BUDGET.md`, so to be exact: the
fiducial is measured twice per job, back to back, and the two measurements of the same fiducial differ by

| log, job | the four fiducials, first pass vs second pass |
|---|---|
| `log_x` job 1 | 0.014, 0.015, 0.062, 0.010 mm |
| `log_x` job 2 | 0.017, 0.019, 0.051, 0.014 mm |
| `log_y` job 1 | 0.005, 0.019, 0.065, 0.020 mm |
| `log_y` job 2 | 0.024, 0.033, 0.017, 0.049 mm |

That is a real repeatability figure - the board cannot move inside one job - and it says 5-65 µm against a
200 µm tolerance. The 30-110 µm "spread" published in `TIME-BUDGET.md` compared the same fiducial *across
jobs*, and the board is not in the same place across jobs (it is placed by hand, and the first home-in pass
of every job lands ~0.78 mm off for exactly that reason), so that number was board placement mixed with
measurement error and should not be used.

And the largest single block over the cameras is not vision at all: 219 ms of the 278 ms a batch spends at
the cameras is the travel in and its confirmation, and there are two nozzles. Hiding a nozzle's whole
camera phase behind the other nozzle's pick-and-place is worth hundreds of milliseconds a job, where the
join is worth tens.

## What to do, ranked by saving per job

The frame rates are measured now, so the list is short and each item says how to check it from the lines
the log already prints (`autoSettleAndCapture t=... score: ... compute time:`).

1. **Raising the settle threshold: measured, and it does not pay.** What the score means came out of the
   code: with `settle-method="Motion"` (all three cameras) `computeDifference()` does a template match of
   one frame inside the next and returns the shift **in pixels**, with a sub-pixel term from the match value.
   So the threshold is already a distance, not an abstract score:

   | camera | threshold | frame scaling | what it accepts | debounce |
   |---|---|---|---|---|
   | `Down Locking` (fiducial) | 1.0 | 0.01754 mm/px x 3 (blur 15 rescales by 3) | 53 µm of motion between two frames | 2 |
   | `Upp1` | 0.48 | 0.0386/0.0341 mm/px x 3 | 53 µm | 1 |
   | `Upp2` | 0.40 | 0.0345 mm/px x 3 | 41 µm | 1 |

   Replaying the score series of the clean job of each log through the settling's own rule reproduces the
   recorded end of every settling in both logs (8 of 8, 4 of 4, 9 of 9, 3 of 3), so the replay can be
   trusted to price a different threshold. It says the saving is small and the price is not:

   | camera | threshold | between frames | saved per job | worst distance of a frame after the earlier verdict to the kept frame |
   |---|---|---|---|---|
   | fiducial | 1.5 | 79 µm | 36 ms (`log_x`) / 228 ms (`log_y`) | 0.019 mm / 0.125 mm |
   | fiducial | 2.0 | 105 µm | 74 ms / 325 ms | 0.019 mm / 0.201 mm |
   | bottom | 0.6-1.2 | 66-131 µm | 0-24 ms | up to 0.054 mm |

   The reason it is so cheap is that at the configured thresholds the settling already stops at the first
   frame that can stop it: the vibration decays inside two or three frames and the rest is the debounce
   waiting for proof, which a threshold cannot buy back. Accepting 0.2 mm of motion between two frames, in a
   machine whose whole placement tolerance is 0.2 mm, to get a third of a second is not a trade. **This item
   is closed, and two earlier versions of this document were wrong about it** - the "537 ms at 0.45" figure
   summed the two jobs contained in `log_y.txt`, and the "359-667 ms at 0.45" figure that replaced it
   ignored the debounce and used thresholds that are not in `machine.xml` (there is no 0.45 threshold
   anywhere on this machine).

   The same arithmetic does answer the question the still-window rule was built for, without a machine run:
   summing the scores (which are distances) from the first agreeing pair to the kept frame bounds how far a
   frame taken at that earlier instant could be from the frame the settling kept. Across both clean jobs that
   bound is 0.012-0.021 mm in the median case and 0.221 mm in the worst settling. So a frame chosen inside
   the still window, which is what the second bottom camera does, is about 20 µm from the frame the settling
   would have kept, and the rule is worth keeping as it stands.

2. **Stop asking for frames on demand.** One background consumer per camera, taking every frame the stream
   produces into a short ring with the driver's own timestamps, so the settling and the pre-capture ask for
   "the newest frame at or after T" and get it out of memory. This is the item that removes the cold first
   frame, the interval-per-frame on the bottom cameras, and the async join, because all three are the same
   thing: capture-on-demand waiting for a frame that has not been exposed yet. It is also the honest shape
   of the "small service in a faster language" idea - and it does not need one: it can be prototyped inside
   `OpenPnpCaptureCamera` behind a setting, with the ring holding downscaled copies so the memory is not
   120 frames of 960x1080. The light would stay on for the whole job, which is a decision about the machine
   rather than about the code.

   How it would be wired, because the shape matters more than the code:

   - One grabber thread per camera, not one per measurement. It runs while the camera's stream is open,
     which with the light-on-for-the-job decision means from the first `M810`/`M822` of the job to the last.
     Starting it "on head arrival" is also possible but then the first frame after arrival is cold again,
     which is one of the things being paid for.
   - The grabber's loop is what `internalCapture()` does today - `hasNewFrame()`, `capture()` - except that
     nobody is waiting, so it neither spins nor blocks the caller, and the frames it takes are not thrown
     away. It stamps each frame with the driver's frame time, and the frame it hands on is the one the
     stream actually produced, not one it provoked.
   - A fixed preallocated ring, ~8 frames, buffers reused, never grown during a job. At 960x1080 a raw
     frame is ~3 MB so 8 is ~24 MB per camera; if that is too much the ring holds the downscaled grey
     image used for settling and only pulls the full frame for the one measurement that is kept.
   - A request becomes `frameAtOrAfter(t, deadline)`: return the newest buffered frame whose exposure time
     is at or after `t` if one exists, otherwise wait at most one frame interval for the next one. That is
     the only new API; `AbstractSettlingCamera` and the pre-capture call it instead of `capture()`.
   - The settling stops driving the camera and becomes an observer: each time the grabber buffers a frame
     the settling scores it against the previous one, so the frame interval and the 2-5 ms of compute
     overlap instead of adding up, and the verdict is a statement about frames that are already in memory.
   - That last point is what the second bottom camera needs: when the first camera's still window is
     declared, the frames proving it were exposed two frames ago, and the other camera's ring contains
     frames exposed after that instant already. Today it provokes a fresh exposure and waits ~10 ms for it,
     which is the join. With rings on both cameras the join is a buffer index.

    The risk is a new class of bug rather than a saving: the ring must refuse frames exposed before the
    claimed window start exactly as the current code refuses them, and the grabber must not become a second
    owner of the stream that fights the preview. Both are testable offline against a dumped corpus.
3. **Make each frame of a settling cheaper**: settle on `captureRaw()` downscaled rather than the fully
   transformed frame (white balance and the undistort remap do not belong in a motion test), and turn
   `settle-diagnostics` off unless a diagnostics run is wanted - today it converts and keeps a copy of
   every settle frame for a graph that is looked at occasionally. Verify: the `compute time:` column,
   4.5-5.5 ms on the fiducials should come down towards 1 ms. This overlaps item 2: the grabber thread
   already moves that compute off the critical path, so it must not be counted again on top of item 2.
4. **Stop the spin** in `internalCapture()`: wait on the stream, do not `Thread.yield()` at it. With item 2
   in place nobody is waiting on the stream any more, so this item disappears into item 2. Verify: the
   spread of the `capture=` column, and whether the two cameras still slow each other down.
5. **Decide the stillness question with data** (this is item 5 only because it needs a machine run): dump
   every grab of a hunt with `-DframeDump`, replay each through the part's own pipeline and measure how far
   the frames taken during the ringing are from the kept one in millimetres. If they are well inside the
   placement tolerance, the still-window rule can be relaxed by a stated, defended amount and the join goes
   away honestly; if they are not, no scheduling idea will ever help and we stop looking for one.
6. **The two-nozzle overlap**, which is not a camera matter at all: 219 ms of the 278 ms a batch spends at
   the cameras is travelling and its confirmation, and there are two nozzles that are mostly waiting for
   each other. A second of a job is still available there, and it is the only item on this list that shortens
   the part of the job in which the machine is moving.

## The same budget, counted once

The numbers above are per ~11 s job, and several of them are the same milliseconds seen from different
angles. Stated so they can be added:

| of the 1.4-1.9 s the cameras own | `log_x` job | `log_y` job | what it is |
|---|---|---|---|
| inside a settling loop | 1204 ms | 1543 ms | vibration decay plus frame intervals plus compute |
| verdict to next move | 184 ms | 349 ms | the measurement pipeline, head already free |
| - one frame interval per settling that a ring would remove | ~210 ms | ~210 ms | the cold first frame: 51 vs 31 ms fiducial, 28 vs 15 ms bottom |
| - compute that a ring thread would overlap | ~190 ms | ~220 ms | `compute time:` x frames |
| - the async join | 38-80 ms | 38-80 ms | one extra exposure on the second bottom camera |
| - whole frame intervals a higher threshold would skip | 36-74 ms | 228-325 ms | measured in item 1, and it costs up to 0.2 mm |

Item 2 is therefore about 450-500 ms a job (4 % of it) and is the only camera item that does not trade
accuracy away. Item 1 adds at most 250 ms and is rejected. Nothing here is over 1.9 s, because nothing can
save time the machine is not spending: the sum of the settling loops is the ceiling, and the earlier "over
1000 ms" figure in this document was two jobs of one log file read as one job, plus item 2 and item 3
counted twice.

A Rust or C++ rewrite of the vision is not on this list, and the reason is in the numbers above: the
arithmetic is already native, the Java around it costs 2-5 ms a frame and some garbage, and the rest of the
time is a sensor integrating light and a machine coming to rest. The one part of it that a native component
would do genuinely better is per-frame exposure timestamps and a zero-copy ring - which is item 2, and
which is a grabber, not a rewrite.

## Reuse on the down-looking camera

The fiducial camera is a different problem with the same parts, and it is the bigger one: 2.95 s of an
11.3 s job, 26 % of it. It is not vibration-bound - it is bound by the number of home-in passes (two, see
above) and by a stream that cannot exceed 30 fps, so every frame of every settling costs 33 ms.

- Item 2 applies unchanged, and per frame interval it gains more there than at the bottom cameras, because
  a fiducial frame interval is 33 ms against 8.3 ms. The compute is also twice as large there (5.4 ms).
- Item 1 is closed here too, and it is worth saying why it looks tempting and is not: 8 of the 12 settlings
  in the fiducial phase are the second home-in pass, whose scores are low from the first frame, so the
  settling there is nearly all debounce. That is 2 extra frames at 26-31 ms in eight places, which item 1's
  measured 36-228 ms is - and it buys that by declaring the head still while the score says it moved
  0.1-0.2 mm between two frames.
- Two things are specific to the fiducial camera and are not settling matters at all: the second home-in
  pass itself (`settle-threshold="1.0"` and `settle-debounce="2"` are not what decides it -
  `max-linear-offset` in the fiducial vision settings does, and the first pass lands ~0.78 mm off because
  the board is placed by hand, so a second pass is normally unavoidable), and the exposure. Exposure there
  is 5 ms inside a 33 ms frame interval, so 10-20 ms of exposure is available without costing a millisecond
  of job time, and more light is the direct way to reduce the 5-65 µm repeatability if that repeatability
  is shot noise rather than vibration.
- One thing ruled out, because the configuration looks alarming: `machine.xml` carries `fps="6.0"` on the
  down camera and `fps="3.0"` on both up cameras. That attribute is `AbstractBroadcastingCamera.fps`, which
  only paces the **preview** loop (`captureNotifier.wait(1000./fps)`, line 355) - it is not a frame-rate
  request to the device. The device rates measured with `v4l2-ctl` (30 and 120 fps) and the frame intervals
  in the log (26-31 and 12 ms) agree with each other and not with those numbers, which is the expected
  result. `suspend-preview-in-tasks="true"` on all three cameras also means the preview is not competing for
  frames during a job.
