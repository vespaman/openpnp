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

*Enforced by:* the settle camera exposes the timestamps of its agreeing frames; the
non-settle camera keeps its frame ring with timestamps; selection picks the newest frame
inside the window; nothing outside is eligible (R1).
*Proved by:* per-camera log field showing the frame's age relative to the window start.
*(missing)*

### R4 — Arrival cost is what we are minimising, and it is measured
The quantity to reduce is the wall time between "head stopped over the cameras" and
"head may leave". It is logged per step, per camera, as one line. Every proposed saving
must be shown against this line, not argued.

*Enforced by:* the align-timing line. *Proved by:* `log_f`..`log_j`. Present.

### R5 — The pre-check judges presence and size, never position
The pre-check answers "is there a part on this tip, and is it the right part". It must
not fail on the part being off-centre, because during the ringing window the position is
not trustworthy and the offset is going to be corrected by the alignment anyway.

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

Plan:
1. **Dump, on one machine run, with provenance in the filename**: nozzle, camera, head
   stopped timestamp, frame index, settle-window state. Without provenance a dumped frame
   cannot be used to test R1/R3.
2. **Replay test** (`mvn test`, no machine): load the machine's pipeline XML + the real
   package footprint + tolerance, run the pipeline over the dumped frames twice — once with
   the alignment's configuration and once with the pre-check's — and print/assert a table:
   frame, rect size, angle, size verdict, position verdict.
   - Catches R6 violations (angle mismatch) mechanically. This test would have caught the
     3 false SizeOuts in `log_j`.
   - Answers §4: the dumped `bv_source` image shows the crop size actually used, and the
     per-stage results show whether the part is clipped by the mask or split by the
     threshold.
3. Keep dumped frames **out of git** (they are ~860 kB each). The test takes a directory
   (`-Dreplay.dir=...`) and is skipped when it is not set, so CI stays green.

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
