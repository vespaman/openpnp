# Parallel bottom vision — requirements and invariants

Status: **draft for review** (Step 0). No behaviour described here is implemented yet.
This file is the contract. Before any further change to the parallel vision code, the
audit table at the bottom is re-checked and updated, and each change names the
requirement it serves. If a change cannot name one, it does not go in.

Written after `tests_2026-09-28/log_j.txt` showed the parallel path using a frame taken
12-27 ms after the head stopped — i.e. while the machine was still ringing — which is
the exact thing this design is supposed to prevent.

## 1. Purpose

Save machine time by reducing the time the head has to stand still over the bottom
cameras, without ever measuring a part on a frame that is not still.

The time saving comes from two separable ideas:

1. **Stage 1 — fill the ringing window with useful work.** The head arrives stopped but
   ringing. While the settle camera settles, the pre-check inspects the first frames.
   The pre-check never delays departure and its verdict is never used for placement.
2. **Stage 2 — leave before the alignment is finished.** Once every needed frame exists
   and every pre-check is OK, the head departs for the placement and the alignment is
   computed during the trip. If it fails, abort and go back the old way.

Both ideas rest on one rule, and the rule is the reason the current code is wrong:

> A frame may be used for alignment only if it is *demonstrably* taken while the head is
> still. "The head had already stopped" is not a demonstration.

## 2. Requirements

Each requirement states the rule, how the code enforces it, and what proves it.
"Proved by" items marked *(missing)* do not exist yet.

### R1 — Stillness is a property of the frame, not of the caller
Every `Mat` handed to the alignment carries a provenance stamp: camera, capture time
(relative to the head stop), the settling it belongs to, and whether the capture time
falls inside that settling's still window. The alignment entry point rejects an
unstamped or out-of-window frame instead of trusting its caller.

*Enforced by:* the stamp type on the captured-shot object; a check in the alignment path.
*Proved by:* a simulated-machine test that offers an unstamped frame and expects refusal;
a log field on every alignment line. *(missing)*

### R2 — The settle camera's last settle frame is reused
The frame that ended the settling loop is the alignment frame. No further capture, no
extra wait after settling completes.

*Enforced by:* the settling callback keeps the last agreeing frame and returns it.
*Proved by:* `frame=N/N` and `capture=<settle end>` on the alignment line. Partially
present today (`AbstractSettlingCamera` records the frames and hands the last one out).

### R3 — A non-settling camera contributes a frame from inside the still window
Cameras that are not driving the settling free-run during the settling. The frame used
is the **last one captured after the second-to-last agreeing settle frame** of the settle
camera. A frame from before that window is discarded, not used.

*Enforced by:* the settle camera exposes the timestamps of its agreeing frames and the epoch of
the settling they belong to; the non-settle camera keeps grabbing until a window exists and
keeps the first frame captured at or after its start; nothing outside is eligible (R1).
*Proved by:* per-camera log field showing the frame's age relative to the window start, and the
`[SETTLE-WINDOW]` line with the margin. Implemented in step 2, off-machine proof in §10, machine
proof outstanding.

### R4 — Arrival cost is what we are minimising, and it is measured
The quantity to reduce is the wall time between "head stopped over the cameras" and
"head may leave". It is logged per step, per camera, as one line. Every proposed saving
must be shown against this line, not argued.

*Enforced by:* the align-timing line. *Proved by:* `log_f`..`log_j`. Present.

### R5 — The pre-check judges presence and size, never position
The pre-check answers "is there a part on this tip, and is it the right part". It must
not fail on the part being off-centre, because during the ringing window the position is
not trustworthy and the offset is going to be corrected by the alignment anyway.

When the two cameras do not settle the same way, the frame the pre-check looks at and the frame the
alignment gets are deliberately different frames. The camera that waits hands over its very first
frame for checking and a later, in-window frame for measuring, because the first frame is the only
one that exists while there is still time to look at it: stage 1 above is what the ringing window is
for. A check that waited for the alignment's own frame would run after the settling, on the executor
the alignment is about to want, and would deliver its verdict to a head that has already left.

*Enforced by:* the position verdict must not be able to make the overall verdict bad.
*Proved by:* a log line per nozzle whose verdict is independent of its offset field.
*(violated today — see audit; and the size verdict itself is angle-dependent, which is a
second bug, see R6.)*

### R6 — The pre-check runs the same pipeline configuration as the alignment
Same pipeline, same parameters, same footprint, same expected angle, same vision
compositing. A verdict produced by a differently-configured pipeline is not evidence
about the alignment, so it is worthless at best and misleading at worst.

*Enforced by:* the pre-check calls the same setup routine with the same arguments the
alignment would use (including the wanted angle from the placement/board).
*Proved by:* for the same frame, the pre-check's measured size and angle must match the
alignment's within a stated tolerance. A replay test over saved frames asserts it.
*(violated today: the pre-check set up with rotation 0 while the alignment uses the
placement rotation, which transposes the expected width/height and produced 3 false
SizeOuts in `log_j`.)*

### R7 — The pre-check may never lengthen the settling
The settling loop needs its CPU. A pre-check that competes with it makes the thing we
are trying to shorten longer. One pre-check job per camera per settling, never retried,
never waited for, running on the vision executor; if it is not finished when the head
leaves, it is reported late or dropped, and the job proceeds.

*Enforced by:* fire-and-forget submission, single submission guard, separate executor.
*Proved by:* the per-frame compute time and the settle duration must not regress against
the step-2 baseline (see gates). Partially present.

### R8 — Fallback to sequential is rare, counted, and loud
Falling back is correct behaviour, but a fallback that nobody counts becomes a silent
way of being wrong. The count is per job, logged at WARNING, and printed in the job
summary. A run with a non-zero count is a run that failed its purpose.

*Enforced by:* counter + WARNING + summary line. *Proved by:* the summary line. *(missing)*

### R9 — Stage 2 is opt-in, with a written abort path
Departing before the alignment is finished is only allowed behind a setting that
defaults off. The abort path must be: cancel the in-flight alignment, return to the
cameras, do the ordinary sequential alignment. It must be exercised in a test before it
is ever armed on the machine, and it must be observable in the log.

*Enforced by:* the setting, plus the abort path in code. *(missing)*

## 3. Numbers this design has to live inside

Measured on `pn52`, `testjob_8-placements_rm10_v40017-1.job.xml`, `log_j.txt` (warm):

| quantity | value |
|---|---|
| head stopped → settle camera's still frame | 37-85 ms |
| head stopped → second camera's frame today (wrong) | 12-27 ms |
| one frame grab, either camera | 8-17 ms |
| frames available inside one settling window | 2-6 |
| alignment pipeline maths, per part, warm | 3-10 ms |
| pipeline setup (XML clone + parameter assignment) | 2-3 ms warm, 16-23 ms cold |
| head leaves cameras → placement finished | ~290 ms |
| parallel fallbacks in 12 alignments | 3 |

Consequences:
- Stage 2 has ~10x the time it needs; timing is not the obstacle, the abort path is.
- The second camera *can* produce a frame inside the still window; R3 is feasible with
  the existing 3 fps free-run plus on-demand grabs.
- The pre-check fits the window only if the setup overhead stops being paid repeatedly.
  That overhead is bookkeeping, not vision.

## 4. Open question: is the vision mask too tight?

`log_j` failing case, one part: the parallel pass measured `9x18 px` and rejected the
part as too small (`0.238 mm` against a nominal `1.00 x 0.50 mm`); the sequential
fallback measured the same `9x18 px` remnant on its first settled pass, then **moved the
nozzle**, re-shot, and got `36x19 px` = a whole part, size ok.

Machine values (from `~/.openpnp2` on `pn52`, package `C0402`, bottom vision `R0402`):

| quantity | value |
|---|---|
| camera frame | 960 x 1080 px, upp ≈ 0.0344 mm/px → ≈ 16.9 x 15.7 mm |
| `CropCentered` | width/height 0 → `footprint.maxWidth`; floor `minimum=200 px` → **200 px ≈ 6.9 mm** |
| `footprint.maxWidth` | `sqrt(2) * maxMaskRadius` |
| `MaskCircle.diameter` | `2 * maxMaskRadius` |
| `maxMaskRadius` | `footprint outer diagonal / 2 + maxPickTolerance` = `0.559 + 0.55` = **1.109 mm = 32 px** |
| part on a good pick | 36 x 19 px = 1.24 x 0.65 mm |
| one pad of the footprint | 0.25 x 0.50 mm = 7 x 15 px |
| part centre in the failing frame | 17 px from the crop centre = 0.58 mm |

`log_s.txt` (2026-10-01, `bv-corpus-s` in git) hit it again on `N2`, and this time the
frames came out with it: the ringing grab and the settled grab of the same batch both
measure `0.237 x 0.501 mm` when replayed here through the machine's own pipeline, while
the short side is exactly nominal and only the long side reads a quarter. A part slid
sideways on the tip, with the rest of it behind the shoulder of the tip or outside the
mask, looks exactly like that; a part blurred by ringing does not, because ringing makes
an outline bigger and does not reproduce to the pixel 26 ms later.

So the question of this section is still open, and it is now answerable from the corpus:
overlay the mask circle of `R0402` on
`bv__Settled__CAM1830e742fb154786__N2__C0402-4n7-50V__f3__a+240515342.0__c+240515380.0__w+240515369.0__r+0.0.png`
and see whether the part's own outline runs outside it. Parked until Stage 2 lands,
because it changes what the size check is allowed to say, not when the head may leave.

Two candidate mechanisms, and they need different fixes:

1. **The mask clips the part.** The mask radius is the part's half-diagonal *plus* the
   pick tolerance, so a pick sitting exactly at the tolerance limit has its far edge at
   the mask boundary. Any part a little larger than the footprint, or any offset beyond
   the tolerance, gets shaved — and a shaved part measures small, which is the symptom.
   A pixel simulation shows the clipping is marginal (a corner or an end, ~1 px) for this
   geometry, so clipping alone does **not** obviously explain a `9x18` remnant.
2. **The threshold splits the part into pads.** A `9x18 px` blob is almost exactly one
   pad (7 x 15 px) plus fuzz. If the HSV/threshold stages separate the two shiny contacts
   from the body, the contour that wins can be one pad, and the size check then reports a
   fraction of the part. This also explains why the *same* settled frame measured the same
   remnant and only a **different view** (after the nozzle move) fixed it.

Which one it is decides whether the fix is "make the mask/crop generous" (R-related, cheap)
or "the pipeline for this part is fragile" (user tuning, not code). It must be measured,
not argued — see the replay rig in §5. Note that the same crop and mask are used by the
final sequential alignment, so a too-tight mask is not a parallel-path-only bug.

## 5. Off-machine testing (the replay rig)

Goal: settle the questions in §4, and later assert R1/R3/R6, without starting the machine.

Existing building block: the pipeline already contains an
`org.openpnp.vision.pipeline.stages.ImageWriteDebug` stage (`enabled="false"` in
`vision-settings.xml`). When enabled it writes `bv_source_*.png` (post-crop, pre-mask)
and `bv_result_*.png` after each stage's result. There are 9703 such images from
2026-05-02 under `~/.openpnp2/org.openpnp.vision.pipeline.stages.ImageWriteDebug/`.

What exists now, after the run of 2026-10-01 (`MACHINE-TEST.md` has the run itself):

1. **Provenance in the filename** — `org.openpnp.vision.FrameDump`, switched on with
   `-DframeDump=<dir>`. The name carries origin, camera, nozzle, part, frame index and the three
   instants (arrival, capture, window open) that R1 and R3 are stated in. `check_frames.py` holds the
   names against the log's `[SETTLE-WINDOW]` lines.
2. **Replay test** — `FrameReplayTest` (`mvn -Dtest=FrameReplayTest test`, no machine, no camera). It
   loads the configuration of the run, prepares the part's pipeline through the production
   `preparePipeline`, feeds the kept frame in through `CapturedShot`, and asserts the replayed rectangle
   against the `Bottom vision part ... result rect` line of the log. All eight frames of the corpus
   reproduce the machine's rectangle: centre and angle equal to the last digit printed, sides within the
   decimals OpenCV drops when it prints them.
3. **A corpus that is in git** — 860 kB x 8 was cheap and the claim is worth being checkable. A bigger
   or private corpus is replayed with `-Dreplay.dir=<run>`; a run whose directory is absent is skipped,
   so CI does not depend on the machine having been run.

The rig cannot yet replay the **pre-check**, and not for a lack of frames: `[PRE-CHECK]` measures the
first grab of a hunt while the dump keeps the last, so the corpus has the alignment's frames and not the
pre-check's. R6 ("the pre-check runs the same pipeline configuration as the alignment") therefore still
rests on the log's agreement of `clone`/`prep` timings and on the code, not on a re-judged frame. The
next corpus run should dump every grab of a hunt, which also turns the drift between the first frame and
the kept one into a measured number rather than an observation.

## 6. Steps and gates

| step | what | gate before next step |
|---|---|---|
| 0 | this file + audit | user review |
| 1 | frame provenance stamp; alignment refuses unstamped frames; fallback counted at WARNING + job summary | run shows the true cost of the (wrong) shortcut; no hidden wrongness left |
| 2 | R3 free-run + in-window selection; settle frame reuse | **0 fallbacks in 24 alignments**, 100% of alignment frames stamped still, warm job ≤ 10.6 s |
| 3 | pre-check: same setup as alignment (R6), position never bad (R5), setup cost reduced | verdict for every nozzle before departure; settle duration and per-frame compute no worse than step 2; no false SizeOut on parts the alignment measures fine |
| 4 | stage 2, setting off by default, abort path | simulated-machine test of the abort path passes; armed on the machine only after that |

## 7. Audit of the current code against these requirements

Branch `parallel-bottom-vision`, commit `564165ae02`. Violations are things the code does
today; unknowns are things we cannot currently tell from the evidence.

| req | state | where | note |
|---|---|---|---|
| R1 | **violated** | `ReferenceBottomVision.captureImageOnly()`, called from `ReferencePnpJobProcessor.preCaptureAllImages()` | shoots immediately after the stop, comment asserts "head is already stationary"; nothing stamps or checks stillness. Introduced by `1f64cfbb35` (the agent1 parallel-capture foundation, rebased in 2026-09-27) |
| R2 | mostly there | `AbstractSettlingCamera` | last agreeing frame is handed out; `captureImageOnlyWithSettle()` reuses it |
| R3 | **violated** | same as R1 | the second camera's frame is taken *before* the window, and no window timestamps exist to select by |
| R4 | present | align-timing + `[PRE-CHECK]` lines | `clone`, `queue`, `pipeline`, `prep`, `pipe`, `judge`, `size`, `rot`, `offset`, `verdict` |
| R5 | **violated** | `ReferenceBottomVision.runPreCheck()` | the position term contributes to a bad verdict |
| R6 | **violated** | same | pre-check prepared with rotation 0, alignment with the placement rotation → 3 false SizeOuts in `log_j` |
| R7 | present, unproven | `getVisionExecutor()`, single submission, no waiting | needs the step-2 baseline comparison |
| R8 | **violated** | `ParallelAlign` | fallback logged at INFO once per event, no counter, no summary |
| R9 | not started | — | alignment still happens before departure |
| ? | unknown | §4 | mask/crop vs threshold-split — needs the replay rig |

## 8. After step 1

The frame now carries where it came from, and the alignment declines a frame that cannot show the head
stood still while it was integrated. `FrameProvenance` is the claim,
`ReferenceBottomVision.requireStillFrame()` is the place it is checked, and `FrameNotStillException`
is what the alignment says when the claim is missing.

| req | state | where | note |
|---|---|---|---|
| R1 | enforced | `FrameProvenance`, `ReferenceBottomVision.requireStillFrame()` | `captureImageOnly()` stamps `QuickCapture`, and the alignment refuses it. The settling hands back `Settled` (by agreement, or by the configured wait) and `SettleTimedOut` when it gave up. `Unknown` remains refused, so no path can reach the alignment without a statement |
| R2 | unchanged, better instrumented | `AbstractSettlingCamera` | also records `SettleResult`, the timestamp of the frame handed back, and the start of the still window |
| R3 | not implemented | — | the second camera's frame is still taken *before* the window; step 1 refuses it instead of measuring on it |
| R8 | enforced | `ReferencePnpJobProcessor.parallelVisionFellBack()` | counted `attempts` / `fell back` / `frames refused`, printed at `Finish`, each event at `WARNING` |
| dump | implemented | `FrameDump`, `BottomVisionSettings.frameDumpEnabled`, `frameDumpDirectory` | off by default; the whole provenance is in the file name and parses back with `FrameDump.parse()`. No GUI property — the two attributes are edited in `vision-settings.xml`, as `preCheckAction` already is |

Expected effect on the machine: **more** fallbacks than the 3 in 12 of `log_j`, because the second
camera's frame has never been proven still and is now refused every time. That is the point of the
step — the number is the price of the shortcut taken in `1f64cfbb35`, and step 2 pays it back with an
in-window frame. A run showing a *low* number would mean the guard is not firing, which is the thing to
investigate.

Tests: `FrameProvenanceTest`, 8 tests — the quick-captured frame is refused, the frame that ended a
settling is accepted, a settling that ran out of time proves nothing, a streamed frame is still only
inside its window, the camera forgets the previous settling, and a dumped name round-trips through the
filesystem unchanged. Full suite 312 tests, 0 failures.

One thing worth knowing about the simulated camera: it is perfectly still, so a settling that measures
never agrees with it — identical frames differ by nothing at all, which is indistinguishable from the
camera handing back the same frame twice — and it ends by timeout. So the test that expects acceptance
uses a fixed-time settling, and the one that expects refusal uses the measuring settling. On the real
machine the sensor noise makes the agreement happen; the `log_j` settle times of 37–85 ms against a
500 ms timeout are the evidence for that.

## 9. Measured after step 1 (`log_l.txt`, `log_m.txt`)

`pn52`, the same 8-placement job, build `a863918` (step 1) against `log_j.txt` (before step 1).

| quantity | before (`log_j`) | `log_l` | `log_m` |
|---|---|---|---|
| first job of the session | 16.3 s | 17.9 s | 16.1 s |
| later jobs | 10.4 / 10.9 s | — | 11.1 s |
| attempts / fell back / frames refused | 12 / 3 / not counted | 4 / 4 / 4 | 4 / 4 / 4 per job |
| alignment step per batch | 274-373 ms when it worked | 581-650 ms | 355-456 ms |
| pipeline tail per batch | 8-20 ms when it worked, 218-403 ms when it fell back | 262-308 ms | 81-112 ms |

The refused frames are now dated, which is what the run was for. Their age after the head is
demonstrably at rest, eight refusals over two jobs: **12, 12, 13, 13, 13, 14, 19, 20 ms**.

The settling they were refused against: agreement after **3 to 5 frames**, i.e. **25-53 ms** after
the rest, at one frame per **8-17 ms**. The still window opens roughly **16-45 ms** after the rest,
so the refused frame was some 0 to 30 ms too early. The head stands over the cameras for about
330 ms, so there are several frames inside the window to choose from; nothing about the timing argues
against R3.

Three things this settled:

- **The guard is cheap.** A batch that falls back costs about 80-140 ms more than one that does not,
  and the warm job is 11.1 s against 10.4-10.9 s before. The spread between sessions (16.1, 16.3 and
  17.9 s for a first job) is larger than the cost of this change, so the first job of a session is not
  a measurement.
- **The camera that settles is not a fixed one.** `log_l` refused Upp2/N2, `log_m` refused Upp1/N1:
  `settleCamera` is taken from the first placement of the batch, so the camera doing the
  capture-without-settling alternates. R3 has to hold for both, and there is no "the slow camera" to
  optimise once.
- **The two `SizeOut` verdicts are not about size.** Measured `0.47 x 0.97` and `0.48 x 0.99` against a
  nominal `1.00 x 0.50`, at `rot=-0.0`, is the right part with its axes transposed: the symmetry
  detection took the other pair of axes. Add it to §4 as a second candidate, checkable on the replay
  rig, and it says something R5 should say explicitly: the size has to be judged on the axes of the
  part's footprint, not on whatever order the rectangle comes back in.

The arrival instant itself needed a fix to be measurable at all: `preCaptureAllImages()` never passed
it, so every frame in `log_l` reads `quickcapture f1` with no age. It is now recorded in
`moveNozzlesToAlignmentPosition()` after `WaitForStillstand` and passed to all three pre-captures,
which is what produced the ages above.

## 10. Step 2: a frame from inside somebody else's still window

`ReferenceBottomVision.captureImageFromSettlingWindow()` is the non-settling camera's capture now.
It grabs frames while the main thread waits for the settling and keeps the first one whose capture
instant is at or after the start of the settling camera's window. The check is made after each grab,
so the frame that gets kept is the one that was being exposed while the settling came to its
conclusion, not a frame after it: on the machine that should mean no additional arrival cost at all.

Four things had to be decided, and they are in the code as comments as well:

- **Whose window.** `AbstractSettlingCamera` counts the settlings it is asked for
  (`getSettleEpoch()`), and the grabber is handed that number before the batch submits anything. The
  window recorded by an earlier settling was a real stillness about a head that has since moved away,
  so none of the recorded numbers are read until the epoch has moved. Without this, a job whose
  second camera is occasionally slower than the settling would quote the previous placement's
  stillness, which is exactly the kind of wrong that no log would show.
- **A waiting that measured nothing.** `SettleResult.FixedTime` has no window, yet its own frame is
  accepted on the claim of the configured wait. The other camera's frame at that instant has the
  same claim or it has none, so the end of the wait is the window for it too. This is a decision
  about what the existing rule means, not a new permission.
- **When to stop.** The deadline is the settling's own timeout plus 100 ms counted from the rest, and
  a settling that reports `TimedOut` is noticed between grabs and ends the hunt at once: no window is
  coming, so waiting is only a slower way of refusing. There is also a grab-count ceiling, which is
  not a timing decision but a guard for a clock that cannot be trusted to have reached the deadline.
- **Nothing is thrown away silently.** A frame with no window goes over with `UNKNOWN` in it, the
  alignment refuses it (R1), the fallback is counted (R8), and the `[SETTLE-WINDOW]` line says how
  many grabs it took and what the margin was.

Proved off machine, with generated frames, in `SettleWindowCaptureTest`. The simulated camera is the
interesting difficulty: it renders identical pixels, and a difference of exactly zero is *not*
agreement but the absence of a measurement, so such a camera can only ever time out. The test camera
therefore supplies motion that stops — the first frames are shifted bodily, after them they wobble by
one grey level — and the settling agrees for the reason it agrees on the machine:

```
[TEST] frame inside the window of the other camera: streamed f1 +77.0ms window+17.0ms
[TEST] configured wait shared with the other camera: streamed f1 +51.0ms window+8.0ms
[TEST] refused after a timed out settling in 8 ms: streamed f1 +209.0ms
[TEST] stale window refused after 152 ms: streamed f81 +193.0ms
[TEST] grabbed into the window on another thread: streamed f69 +167.0ms window+5.0ms after 15 frames of settling
```

The last of those is the shape of the machine: one thread settling, another thread grabbing, neither
knowing what the other has done, and the kept frame inside the window that the settling recorded.

### The pre-check belongs to the ringing, not to the stillness

`log_n` showed the in-window frame working, and showed the mistake next to it: the camera that hunts
for a window was starting its pre-check on the frame it keeps, which is the frame that ends the hunt,
so the `[PRE-CHECK]` line came *after* the settling and after the `[SETTLE-WINDOW]` line. That is the
opposite of stage 1. It also takes the vision executor at the moment the alignment wants it, and its
verdict arrives too late to be the safety net it is supposed to be, since a net is only a net if it is
under the head while the head is still there.

`FrameGrab` now takes a handover callback: a strategy that produces more than one frame passes the
earliest one to it as soon as it has it. The first grab of the hunt is therefore also the frame the
pre-check is running on, roughly the whole ringing window before the alignment frame exists, and the
`frame=1/n` field of the `[PRE-CHECK]` line has become the fact it always looked like: the first frame
checked, out of that many grabbed. The `[SETTLE-WINDOW]` line quotes camera names rather than ids now,
for the same reason: the two lines have to be seen to belong to the same camera.

What asserts it is not a stopwatch. Three threads contending for a CPU make any deadline in a test a
race, and the first version of this test failed for that reason and proved nothing either way. Instead
the test subclasses the vision and records how many frames the camera had served when `startPreCheck`
was called, and the camera counts its own captures; the number is left behind by the code and cannot
be late:

```
[TEST] pre-check started on grab 1 of a hunt of 89: ... verdict=Ok ... nominal=2.0x1.25 ... frames=89
```

One grab for the check, eighty-nine for the alignment, and the verdict was `Ok` before the head could
have gone. Full suite with this: 318 tests, no failures.

`log_o` has since shown that: for the camera that does not drive the settling the `[PRE-CHECK]` line is
now the earlier of the two, `PRE-CHECK-LATE` is 0, and everything else stayed where `log_n` left it.
One field of it was a lie and has been taken back: `frame=1/0`. The check can report while the hunt is
still running, and filling the denominator from `camera.getRecordedFrameCount()` quoted that camera's
*previous* settling - the camera that hunts does not settle at all in this batch. The number belongs to
whoever captured, so the check no longer invents one and the line says `frame=1/?`, with the grab count
in the `[SETTLE-WINDOW]` line of the same batch, which knows it.

### The hunt aims at the window; it does not hammer at it

`log_o` also showed something that had nothing to do with the pre-check and everything to do with the
settling, and it had been there since `log_n`: the settling of the camera that drives it took 66-70 ms
where `log_m` had measured 34-40 ms. Reading the frame gap rather than the total says what happened.

| log | what the other camera did meanwhile | settling | settling frames | gap between them |
|---|---|---|---|---|
| `log_c`..`log_j` | captured afterwards, one at a time | 38-48 ms | 2.2-2.5 | 11-13 ms |
| `log_m` | one quick frame | 34.5 ms | 5.1 | 11.8 ms |
| `log_n`, `log_o` | 4-7 frames during the settling | 65.6-66.9 ms | 3.2-3.4 | 16.3-18.3 ms |

The frames the settling needs are the mechanics and they did not change; the *gap* did, by a third. Two
cameras share a USB bus, a CPU and a light, and `OpenPnpCaptureCamera.internalCapture()` waits for a
fresh frame by spinning on `hasNewFrame()` with `Thread.yield()`, so a second camera taking frames at
full rate is not free: on this machine it costs the settling about 1 ms of its frame period per frame
taken next door. A settling of four frames then costs 66 ms instead of 48, which is the whole of the
regression. It arrived with step 2's frame hunting, not with the pre-check: `log_n` and `log_o` have the
same gap and the same totals.

The hunting camera does not need those frames. It needs one frame at the moment the window opens, and it
was taking five to get it, throwing four away and paying the settling for all five. So it aims:

- the first frame is still taken at once, for the pre-check, because the ringing is where that works;
- then it waits, without capturing, until the settling has a run of alike frames under way. That is a new
  live signal on the settling camera, `getCandidateStillWindowStartAtMs()`: the moment from which the
  frames so far look alike, reset when the next one breaks the run. It is a promise and not a verdict, so
  the frame taken on the strength of it is still checked against the recorded window afterwards, exactly
  as before;
- having taken that frame it *holds* it while the settling finishes, instead of grabbing another one to
  find out. The verdict is one frame away and the frame is already in hand.

Off machine this was invisible in exactly the way a rendered image guarantees: a `SimulatedUpCamera`
hands out a frame as fast as the CPU can draw one, so "grab ten frames while somebody settles" measures
as free, and the test that was supposed to protect the settling had nothing to say. The camera is now
modelled as a device where the test needs it - one frame at a time on a shared bus, each frame occupying
the bus for a fixed transfer time - and the budget is asserted directly as well:

```
[TEST] 2 grabs into the window, 1 of them before there was anything to aim at, after 15 frames of settling
[TEST] settling 130 ms alone, 124 ms against 2 grabs of another camera (5 ms per frame on the bus)
```

With the waiting removed again, to see whether they bite, the same two tests say `took 81 frames while
the settling had no window and no prospect of one` and `the settling took 274 ms with the hunt and 128 ms
without it, so 27 grabs cost it 146 ms on a bus of 5 ms per frame`. Full suite with this: 320 tests, no
failures.

R7 says the pre-check may not lengthen the settling. That was always the narrower version of the rule;
what `log_n` measured is that *nothing* on the other camera may lengthen it, and least of all the frames
this design grabs on purpose.

The machine had the last word and it agreed: in `log_p` the hunt takes 2 grabs instead of 4-7, the frame
gap of the settling is 12.3 ms where `log_o` measured 18.3 and `log_m` 11.8, the settling is 46.9 ms where
`log_o` measured 65.6, and the alignment step is 331 ms, the best this machine has done. The frames the
settling needs are what is left of it: 3-4 frames of 12 ms, which is the mechanics coming to rest and not
the camera being asked twice. What was paid for before is paid for once now.

### Which side of a rectangle is the part's width

`log_p` had one `[PRE-CHECK] SizeOut` left in it, on the part the test job deliberately places a quarter
turn off: `size=0.45x0.97 expected=1.00x0.50`, `width too small: nominal 1.000, limit 0.620, measured
0.448`. The part was the right part. A rectangle fitted to a contour has no notion of which of its sides
is the part's width - `17x37 at 0°` and `37x17 at 90°` are the same rectangle, and OpenCV hands out either
one, depending on where the contour starts - and the size check compares width with width. The alignment
proper does exactly the same, which makes its verdict on a turned part a coin toss as well; in this batch
the alignment got `37x17` for the very same part 26 ms later and said nothing.

The check is the one that has to be careful, because it is the one that is not allowed to cry wolf: a
warning that a legitimately turned part provokes is a warning that gets read as noise the next time it
matters. So `checkPartSizeEitherWay` asks both ways and keeps what the second way says, because *sides
exchanged* is a fact about the part - it is lying a quarter turn from its footprint - and it is the one
thing a frame may honestly say about the rotation of a part this design refuses to judge:

```
[TEST] size check read both ways: 148x93 fits, 93x148 fits turned, 93x66 refused (Part R0805-1K width too small: nominal 2.000, limit 1.800, measured 1.250)
```

What is deliberately *not* done here is changing what the alignment itself accepts. The coin toss upstream
is a real one and it will occasionally throw a job error on a turned part; making that a warning instead
is a change of behaviour on the gate, and this design's rule about the gate is that it does not move it.
Full suite with this: 322 tests, no failures.

## 11. Stage 2: letting the head go before the numbers are in

R9 asked for a setting that defaults off and an abort path that is exercised in a test before it is
ever armed on the machine. Both now exist.

`ReferenceMachine.earlyAlignmentDeparture` is the setting, off, spoken to by
`Machine.isEarlyAlignmentDepartureEnabled()`. With it off nothing whatever changes: `ParallelAlign`
waits over the cameras exactly as it always did. With it on, STEP 4 hands the futures, the pictures
they are reading and the reckoning of what was refused to a `DeferredAlignment`, and the alignment
step ends.

The head then does the part of the placement that does not need the numbers.
`getPlacementLocation(plannedPlacement, false)` is the placement as the job describes it, with the
alignment offsets left out; the nozzle goes there at safe Z through the same
`MovableUtils.moveToLocationAtSafeZ()` the placement itself uses. A part that came off the feeder
crooked is out by a fraction of a millimetre and a board is tens of centimetres away, so that is the
journey, and what the wait that follows covers is the approach to it. The descent is deliberately not
split: the corrected location is still reached, and the part still put down, in the one move the
placement has always made.

`ReferencePnpJobProcessor.awaitDeferredAlignment()` is the wait, and it is also the abort. It joins
the futures, it checks the parts are still on the nozzles, and if any of it fails it calls
`Align.realignSequentially()` on the whole batch, which walks the head back to the cameras and shoots
again the ordinary way. A job that was aborted and never reached a placement is not left holding
pictures: `Cleanup` releases them.

### The abort path, seen in a test

`EarlyDepartureAbortTest` runs a real job on a machine: the `JobProcessorTest` machine with its
feeders, a second bottom camera so that the two nozzles are not sharing one lens (they would be, and
then the parallel alignment declines to try and the test would prove nothing), and two parts on one
board.

The failure is injected by a subclass of `ReferenceBottomVision` that throws once, at the seam the
deferred pipelines use. That it has to be a subclass and not a wrapper is worth writing down:
`VisionUtils.findPartAlignmentOffsetsWithCapturedShot()` tests
`p instanceof ReferenceBottomVision` rather than asking the interface whether it can take a picture
taken beforehand. A wrapper would therefore not be handed the picture at all, would quietly align the
old way, and the test would pass while measuring nothing. The same is true of any future second
alignment implementation: it cannot take part in the parallel alignment until that dispatch is put on
the interface.

The machine has no way to have a part alignment added to it - the GUI edits XML, and the machine that
loads with none is given a `ReferenceBottomVision` for itself - so the test stands that one aside for
its own, keeping its name.

What the test asserts, in order:

* the machine really was armed;
* a deferred alignment was asked for a pre-captured picture, and refused one;
* that same placement then went *over the camera* - the ordinary alignment, which moves the head back
  and shoots for itself;
* both parts were aligned, put down, and the head came to rest with nothing on it.

Full suite with this: 327 tests, no failures.

### What is not yet claimed

* **The saving.** Measured, and it is not there. `log_v.txt` (MACHINE-TEST) ran the machine with the
  setting on: every batch was released with its alignments owed, and the wait at the placement was
  10-25 ms, which is all this hides, against a cycle of about 1.9 s and a run-to-run spread of 1.7 s.
  The head is held at the cameras by the settle hunt and by re-shooting a frame that landed outside the
  other camera's window, both of which happen before the release and are untouched here.
* **The second nozzle.** One nozzle per batch travels before its numbers are in, and it can only be
  one: the head has a single XY and the two placements are not as far apart as the two nozzles are.
  Which of the two goes first is decided by the routing step, not by the alignment.

### The two things that made the first armed run achieve nothing

Both are in `log_v.txt` and both were fixed the same evening; both are of the kind that is invisible in
the job and visible only in the log.

**The head was not sent, it was only asked.** The travel was issued in the placement step, which runs
after `PrerotateAllNozzlesForPlace`. That step has already queued the placement location as a
*subordinate* motion (`head.moveTo(..., MotionOption.SubordinateRotation)`), and a subordinate motion
puts its location into what the motion planner considers the planned destination without the head moving
an inch. The travel that followed asked for that same XY, so the planner saw no change and handed the
driver nothing:

```
01.227 PrerotateAllNozzlesStep: All nozzle pre-rotation for place requested
01.227 N1.moveTo((155.727478, 99.901314, ...))        <- subordinate, planner now "at" the placement
01.229 N1.moveTo((155.727478, 99.901314, ...))        <- the early departure; nothing emitted
01.235 [EARLY-DEPARTURE] 2 alignments ... complete 10 ms later
01.236 N1.moveTo((155.754131, 99.861207, ...))        <- corrected, differs: G1 at last
01.236 commandQueue offer >> M204 S10501 G1 X121.81 Y118.08 ...
```

The head left the cameras at 01.236, one millisecond after the numbers arrived, which is the whole of
what stage 2 was supposed to hide and is why the measurement showed nothing. The departure is therefore
issued in `PrerotateAllNozzlesForPlace.step()`, before `prerotateAllNozzles(placeLocator)`, and the
routing that decides which of the two nozzles goes first has by then been done. R4 is the requirement
this breaks: the head stood still while the machine was being routed.

**The frame was aimed at the verdict, not at the window.** The camera that does not drive the settling
took its second frame as soon as a window looked likely, and the still window it then had to satisfy
opened 27 ms later than the exposure. The start of the window is known before the settling has finished
saying it - the candidate, `getCandidateStillWindowStartAtMs()` - and a frame that is exposed before the
opening is refused whatever the verdict says, so waiting for the verdict to be told so is standing still
for nothing. `awaitWindowOpening()` now waits for the instant, and the too-early frame is rejected on the
candidate instead of on the announcement.
* **Rotation.** A part that the alignment turns is turned by the offsets, so the travel without them
  arrives square while the part is not. The final move corrects it, exactly as it always has, but the
  head has travelled to a place the part is not quite in, and that is the whole risk of this step.
