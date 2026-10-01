# What a frame costs, and what a rewrite would buy

Written 2026-10-01 night, answering three questions put at the end of the `log_y.txt` run: is the
9-14 ms it takes to capture a frame exposure or CPU; is handing work to another thread in Java costly;
and would the camera part be better off in a C++ or Rust service.

Everything below is measured from `tests_2026-10-01/log_x.txt` and `log_y.txt` (one job of 8 parts,
10.8 s each), from `pn52`'s `machine.xml`, or from the code. Where something is not measured it says so.

## A third of the job is cameras, and the part we have been fighting is 0.35 % of it

The settling diagnostics print every frame of every settling (`t=`, the score, and the compute time), so
the whole camera budget can be read out of a job log:

| | settlings | frames | sum of settlings | first frame | later frames | compute per frame |
|---|---|---|---|---|---|---|
| fiducial, `log_x` | 16 | 57 | 2010 ms | 46.1 ms | 30.9 ms | 4.5 ms |
| fiducial, `log_y` | 16 | 67 | 2472 ms | 54.4 ms | 31.3 ms | 5.4 ms |
| bottom, `log_x` | 8 | 27 | 470 ms | 28.6 ms | 12.7 ms | 2.1 ms |
| bottom, `log_y` | 8 | 22 | 454 ms | 30.8 ms | 14.7 ms | 2.5 ms |

Add the settlings whose owner the log does not name (4 in `log_x`, 6 in `log_y`, all of them fiducial in
character) and the job spends 3.1-3.7 s of its 10.8 s inside a settling loop. Against that, the async
join that this branch has been chasing is 38-80 ms, and the exposure of a frame is 100 µs to 5 ms.

So the honest framing of every idea, including a rewrite, is this table.

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
| declaring the head still one frame sooner (the threshold) | ~500 | modelled from `log_y` below; the down camera cannot go faster than 30 fps, so its 2 s is frames x 33 ms |
| a ring-buffer grabber instead of capture-on-demand | ~700-800 | the ~8.3 ms interval per bottom frame (22-27 frames), the cold first frame everywhere, and the async join - all three are the same demand-driven wait |
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

16-21 fiducial settlings a job, ~3.5 frames each, 30 ms apart. Each fiducial is looked for once per board
transform; the job has four fiducials and does both passes. Before any of this is engineered further, the
question worth asking the machine is whether all of those looks are needed, and whether a fiducial needs a
settling at all if the exposure is short enough to freeze the vibration: the fiducial measurement scatters
30-110 µm with the settling in place (`TIME-BUDGET.md`), so the settling is not what is holding it.

And the largest single block over the cameras is not vision at all: 219 ms of the 278 ms a batch spends at
the cameras is the travel in and its confirmation, and there are two nozzles. Hiding a nozzle's whole
camera phase behind the other nozzle's pick-and-place is worth hundreds of milliseconds a job, where the
join is worth tens.

## What to do, in the order that pays

The frame rates are measured now, so the list is short and each item says how to check it from the lines
the log already prints (`autoSettleAndCapture t=... score: ... compute time:`).

1. **Fewer frames per fiducial settling.** The down camera cannot be made faster than 30 fps, so its
   ~115 ms per look is frames × 33 ms. `settle-debounce` is already 1 and the threshold is 0.48 down / 0.40 up.
   Replaying the score series of `log_y.txt` through the settling's own rule - same frames, same debounce,
   only the threshold moved - says how much of the 3.7 s of settling a higher threshold would take away:

   | threshold | settling in the job | saved | settlings ending sooner |
   |---|---|---|---|
   | 0.30 | 3523 ms | 189 ms | 6 of 30 |
   | 0.40 | 3273 ms | 439 ms | 10 of 30 |
   | 0.45 | 3175 ms | 537 ms | 13 of 30 |
   | 0.55 | 3081 ms | 631 ms | 16 of 30 |

   That is a real half second, and it is also a direct trade of residual vibration against time, which is
   why it must not be set by feel: the corpus replay (`FrameReplayTest`, `bv-corpus/`) can re-measure saved
   frames through the machine's own pipeline - it already reproduces the recorded rectangle to the last
   digit - so the question "what does a score of 0.45 actually cost in millimetres" is answerable offline.
   The score is a difference of a blurred 960x1080 frame, not a distance, so the first job is to correlate
   one against the other.
2. **Stop asking for frames on demand.** One background consumer per camera, taking every frame the stream
   produces into a short ring with the driver's own timestamps, so the settling and the pre-capture ask for
   "the newest frame at or after T" and get it out of memory. This is the item that removes the cold first
   frame, the interval-per-frame on the bottom cameras, and the async join, because all three are the same
   thing: capture-on-demand waiting for a frame that has not been exposed yet. It is also the honest shape
   of the "small service in a faster language" idea - and it does not need one: it can be prototyped inside
   `OpenPnpCaptureCamera` behind a setting, with the ring holding downscaled copies so the memory is not
   120 frames of 960x1080. The light would stay on for the whole job, which is a decision about the machine
   rather than about the code.
3. **Make each settle frame cheaper**: settle on `captureRaw()` downscaled rather than the fully
   transformed frame (white balance and the undistort remap do not belong in a motion test), and turn
   `settle-diagnostics` off unless a diagnostics run is wanted - today it converts and keeps a copy of
   every settle frame for a graph that is looked at occasionally. Verify: the `compute time:` column,
   4.5-5.5 ms on the fiducials should come down towards 1 ms.
4. **Stop the spin** in `internalCapture()`: wait on the stream, do not `Thread.yield()` at it. Verify: the
   spread of the `capture=` column, and whether the two cameras still slow each other down.
5. **Settle the stillness question with data** (this is item 5 only because it needs a machine run): dump
   every grab of a hunt with `-DframeDump`, replay each through the part's own pipeline and measure how far
   the frames taken during the ringing are from the kept one in millimetres. If they are well inside the
   placement tolerance, the still-window rule can be relaxed by a stated, defended amount and the join goes
   away honestly; if they are not, no scheduling idea will ever help and we stop looking for one.
6. **The two-nozzle overlap**, which is not a camera matter at all: 219 ms of the 278 ms a batch spends at
   the cameras is travelling and its confirmation, and there are two nozzles that are mostly waiting for
   each other. That is where a second of a job still lives.

A Rust or C++ rewrite of the vision is not on this list, and the reason is in the numbers above: the
arithmetic is already native, the Java around it costs 2-5 ms a frame and some garbage, and the rest of the
time is a sensor integrating light and a machine coming to rest. The one part of it that a native component
would do genuinely better is per-frame exposure timestamps and a zero-copy ring - which is item 2, and
which is a grabber, not a rewrite.
