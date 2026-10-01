# Where the seconds of a job actually go

Measured from `log_x.txt`, the first job where the early departure works. One job of 8 parts, 10.8 s
(21:47:46.874 to the `Job finished` line at 21:47:57.619). Every number below is a sum of timestamps in
that window; nothing is estimated.

| what | ms | % of the job |
|---|---|---|
| settling of the down-looking camera (8 settles, 100-155 ms each) | 990 | 9 % |
| settling of the bottom-vision cameras (4 settles, 38-74 ms) | 214 | 2 % |
| the join on the other bottom camera's capture (8 waits, 4 of them nil) | 38 | 0.35 % |
| offset computation that no longer sits between the cameras and leaving (8 batches x 10-22 ms) | 119 | 1.1 % |

The remaining ~85 % is the head moving and the Z plunges of pick and place. Those are mechanics: the
commands are in the controller's queue and the motors are doing their job. The two things on the list that
are not mechanics are the two settling lines. Counting it the other way round agrees: the driver had
nothing to send for 1489 ms of the job, and 1204 ms of that is the settling above - the queue-empty time
is almost entirely cameras, not Java.

## The down-looking camera is the biggest single consumer, and it is not buying much

Each fiducial capture runs `autoSettleAndCapture`, which grabs frames about every 25-30 ms and compares
each with the one before it. It stops at the first frame whose difference score falls below the camera's
`settleThreshold` (`AbstractSettlingCamera.autoSettleAndCapture()`, line 501: above the threshold resets
the debounce, below it counts). This is the whole sequence of one job:

```
155 ms  t60:1.47 t89:0.42 t118:0.42 t155:0.36
100 ms  t36:0.61 t63:0.40 t99:0.20
130 ms  t56:2.10 t85:0.50 t107:0.25 t130:0.24
128 ms  t30:2.11 t65:0.49 t95:0.24 t128:0.17
118 ms  t53:0.71 t80:0.57 t118:0.40
139 ms  t46:1.55 t69:0.41 t102:0.23 t139:0.22
111 ms  t44:0.56 t78:0.39 t110:0.28
109 ms  t50:0.60 t83:0.41 t109:0.23
```

The score is the residual vibration. It falls fast and then flattens: 2.1, 0.5, 0.25, 0.24 - the last two
frames of a settle are nearly the same, which is what the settling is asking for. So the cost is the
decay of the vibration after a hard stop, at ~25-30 ms a frame, and the threshold decides how many frames
of a flattening tail you insist on. Raising the threshold buys back one frame (~28 ms) per fiducial,
8 fiducials, ~230 ms per job. That is real but it is a gamble on accuracy, and the accuracy figure below
says the accuracy is not what the settling is protecting.

**The fiducial measurement scatters by 30-110 µm and the tolerance is 200 µm.** Grouping every
`FIDUCIAL-FIDUCIAL located at (x, y, ...)` of the four logs of this session by fiducial:

| log | spread of the located coordinate, per fiducial |
|---|---|
| `log_t.txt` (11 locates each) | x 0.042-0.066 mm, y 0.056-0.075 mm |
| `log_v.txt` (4 each) | x 0.009-0.056 mm, y 0.014-0.056 mm |
| `log_w.txt` (4 each) | x 0.033-0.071 mm, y 0.061-0.117 mm |
| `log_x.txt` (4 each) | x 0.005-0.061 mm, y 0.009-0.056 mm |

The board does not move between jobs, so that spread is measurement error, and the whole board transform
is built from these numbers. The locator itself is happy with it: `less than max. linear offset 0.062mm <
0.200mm, locator satisfied`. Which means: 990 ms a job is being spent on a settling that ends when the
vibration score happens to dip, while the result is off by up to a tenth of a millimetre anyway. The
settling is not the thing protecting the fiducial accuracy on this machine, and one should not tune its
threshold before knowing what the 50-110 µm actually is - vibration during the exposure, the fiducial
pipeline (blur, mask, threshold, `settleContrastEnhance`), the lighting, or the axis backlash after a
direction reversal. That is a separate piece of work from the parallel vision, and it is 25 times the size
of the async join.

## The async join, which was the question

`Waiting for 1 async camera captures to complete` is the job thread joining the secondary bottom camera's
settle-window grab at `PASS 1 END` (`ReferencePnpJobProcessor`, line 2563). In `log_x.txt`: eight waits,
38 ms in total, four of them nil and four of 7-11 ms. It is a frame behind by construction - the second
camera's still window is defined relative to the first camera's settling, so the frame that satisfies it
cannot exist before the first camera has already produced its own.

Releasing the head before that join is not a matter of moving the `join()` into the deferred worker: the
light is switched off when the pre-capture camera batch ends, the batch ends on the job thread right after
the join, and the nested batch bookkeeping (`ReferenceCameraBatchOperation`) is per-thread. A grab that is
still exposing when the batch ends exposes in the dark, and a frame taken in the dark is not a frame that
should ever be aligned on.

If it is done, the safe shape is to stop making the light-off depend on the batch end at that point:

1. the settle camera's frame is what the job thread waits for, as now;
2. the secondary camera's future goes into the `DeferredAlignment` record together with the shots it
   already has;
3. the light-off is deferred to something that cannot race the grab - either the deferred worker issuing it
   once the future completes, or the next `M810` for that camera, or a short timer started at the release.
   The LED being on for an extra 10 ms of travel costs nothing; a frame exposed after the LED is off costs
   a placement;
4. the deferred worker joins the future before it aligns, and a future that fails or returns a frame from
   outside the window fails the deferred path exactly as a refused frame does today, which is the abort
   path that is now proven on hardware.

Expected gain: 38 ms in 10.8 s, 0.35 %, and it needs the light sequencing to change. It is not the lowest
hanging fruit; the fiducial settling is, and it is 25 times bigger. It is also the last item in the
parallel-vision pipeline that is still standing between the head and leaving the cameras, so it may be
worth doing for that reason rather than for the milliseconds.

## What the head does with the rest of the time over the cameras

One batch in `log_x.txt`, light on to head released, 278 ms:

```
   0  M810 on, G1 to the cameras, M400, M114
 224  position report back          <- the approach, 219 ms
 225  first frames, settling starts
 265  autoSettleAndCapture in 38 ms
 266  pre-check done, Waiting for 1 async camera captures
 277  the secondary settle-window frame arrives
 278  head released
```

The approach is the reason the head is over the cameras at all, and the confirmation is the controller
saying it has arrived; neither is on our list. Of the 55 ms of camera work, the settling is 38 ms, the
join is 11 ms, and the rest is the pre-checks and the pipeline that now run after the head has gone.
