# Tests to write later, off the machine

Written 2026-09-29, from the questions that came up while closing out `MACHINE-TEST.md`: can the long run
be a test, and can the crop be tested over component sizes. Both, mostly, and neither needs the machine.
Nothing here is written yet. Each item says what it would assert and where it belongs, so it can be picked
up without re-deriving it.

## 1. Crop over a size matrix - `CropCenteredTest`, extended

`CropCenteredTest` already proves the crop is centred, clamped, respects `minimum`/`margin`, never exceeds
the frame, and keeps its output in millimetres. What is missing is the part library: the crop in
`vision-settings.xml` is one setting (`R0402`) shared by 27 parts, and the machine trial only ever looked
at `C0402-22p/50V`. So:

- Drive the stage over a size matrix - 01005, 0201, 0402, 0603, 0805, 1206, plus one deliberately odd
  shape (a wide connector, a polarised electrolytic, anything with an aspect ratio far from 1) - and assert
  for each: the crop is centred on the footprint, never larger than the frame, never smaller than
  `minimum`, and the crop grows with the footprint rather than with the pixel size of the last capture.
- Same synthetic part drawn at a known millimetre offset from the centre, once through the pipeline with
  `CropCentered` in it and once without, and the measured offsets must agree to well under a pixel for
  every size in the matrix. This is the property the whole crop rests on and it is currently only argued,
  not asserted, for sizes other than the one that was measured.
- Repeat with the part rotated (0, 15, 45°): the crop is a rectangle that does not rotate, so what must
  survive is the *centre*, not the extent.
- Assert the thing that actually broke on the machine: the crop must not consult
  `ReferenceCamera.getWidth()`/`getHeight()`. Those are a cache of the last transformed capture and are
  `640x480` when the camera has never been opened, which is exactly how the first crop on the machine read
  a 200 px window out of a frame it thought was 640 px wide. A test that closes the camera, or reports a
  stale size, and still gets the same crop, is a test that cannot regress that way again. See
  `PRE-CHECK-ASSESSMENT.md`.

## 2. Outside the crop must fail loudly - new test, same file

The failure of `tests_2026-09-28/log_b.txt` was not a crash: `MinAreaRect` found something inside the crop,
the pipeline reported a confident part, and the mm conversion turned it into `(-11.640, +9.544 mm)`, which
`center offset 15.05 > 1.0` rejected. A crop that is too small or misplaced fails like this for every part
that shares the vision setting. Encode it:

- Draw the part well outside the crop and assert the result is *no result* (or a result the tolerance
  rejects), never a confident offset.
- Draw the part straddling the crop edge and assert whatever comes out is either nothing or rejected. A
  truncated contour that reports a plausible centre is the dangerous case, and it is the one that cost the
  machine trial a night.
- Assert the same for a part that is present but tiny in the crop (below `minimum`): the machine should not
  be able to mistake the crop's own edge for a part.

## 3. Leak proxies over many cycles - new test, replaces the long run

`MACHINE-TEST.md` §4 asked for a long run with RSS sampling; it was dropped because an eleven second job
says nothing and because RSS is a blunt instrument: the JVM rarely hands pages back to the OS, and the
simulated machine never runs the v4l2 decode path where a `Mat` would actually leak. What can be asserted,
and is a better test than a slope of RSS, is that the plumbing the parallel aligner adds does not retain
anything:

- Run a few thousand alignment cycles on the simulated machine (a couple of nozzles, two cameras, the
  switch on, the sequential fallback forced for some of them).
- After every cycle assert the transient state is empty: the camera batch depth is 0, the pre-captured shot
  map holds nothing, no `CompletableFuture` is left uncompleted, no camera listener was added and never
  removed, and the thread count is back to where it started. These are exact assertions, not thresholds,
  and they are the failure modes R4 was actually about.
- Then, as a soft backstop rather than a test in its own right: retained old generation after
  `System.gc()`, measured with `MemoryPoolMXBean`, must grow by less than a generous bound per hundred
  cycles. Loose enough that CI variance cannot fail it, tight enough that a retained list of images per
  cycle cannot pass it. Mark it as such in the name or a comment, so nobody reads a green run as "no leaks
  in the camera driver", which it does not mean.

## Considered and not wanted

A max rotation for bottom vision, so that a part turned far enough on the nozzle stops the job instead
of being corrected, was on the table after `log_d.txt` (a part measured at 45° and placed square). It is
not wanted: correcting a turned part is what bottom vision is for, and a tolerance would only turn a
working correction into a stop. Recorded so the next person does not write it.
