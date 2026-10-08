# Parallel Bottom Vision - design documents

These documents are the design history of the parallel bottom vision work, imported verbatim from
the `.opencode/plans/` directory of the development checkout on 2026-09-27. They were written by an
LLM assistant during the April-May 2026 development iterations, in several back-and-forth rounds,
so they overlap and some of them describe designs that were later abandoned.

They are kept as engineering background, not as specification. Where a document disagrees with the
code, the code wins. See `SALVAGE-PLAN.md` for the risk register of the resulting implementation.
Each of them carries a one line notice under its title saying the same thing.

The imported files keep the modification date they had in the development checkout, so `ls -lt`
sorts them below the documents written in this fork.

## Chronology

| Date | Document | Subject |
|------|----------|---------|
| 2026-04-12 | `parallel_bottom_vision_plan.md` | Original design: align all nozzles at once instead of one by one. |
| 2026-04-13 | `parallel_bottom_vision_plan_v2.md` | Revised design, supersedes most of v1. |
| 2026-04-13 | `parallel_bottom_vision_test_plan.md` | Test strategy for the parallel path. |
| 2026-04-19 | `parallel-ntc-with-displacement.md` | Nozzle tip calibration while the camera displacement feature is active. |
| 2026-04-25 | `displacement-correction-plan.md` | Correcting the measured offset when the part is photographed at a displaced camera position. **Superseded twice over: 2026-10-05 by `DISPLACEMENT-VERIFICATION.md`, 2026-10-08 by `runtime-displacement-plan.md`, whose first stage deleted the term it corrects.** |
| 2026-04-26 | `parallel-camera-capture.md` | Capture-side design. |
| 2026-04-26 | `parallel-camera-capture-implementation.md` | Implementation notes. |
| 2026-04-26 | `parallel-camera-capture-implementation-v2.md` | Revised implementation notes. |
| 2026-04-26 | `parallel-camera-capture-timing.md` | Timing analysis of the capture stage. |
| 2026-04-26 | `parallel-camera-capture-summary.md` | Result summary. |
| 2026-04-26 | `parallel-camera-capture-summary-v2.md` | Revised result summary. |
| 2026-04-29 | `pre-check-plan.md` | Pre-alignment part presence and size check inside the settling window. |
| 2026-05-03 | `diagnose-precheck-timing.md` | Debugging plan for the pre-check running 50-70ms instead of 30ms. |
| 2026-05-03 | `fix-precheck-timing.md` | Proposed fix for that timing problem. |

## Status of each idea

- Parallel capture and parallel pipeline processing: **implemented**, committed as
  `Implement parallel image capture and bottom vision improvements`, experimental. It ran on real
  hardware in the checkout it came from, not from this repository; see `MACHINE-TEST.md`.
- Camera displacement: **implemented on both sides as of 2026-10-08, in a different shape than the
  design documents above describe.** The per-camera `displacement` X/Y fields of
  `multi-bottom-vision` were runtime state and never survived a restart; they are gone, and what
  replaced them is a measured, persisted `<displacement>` on the camera
  (`camera-displacement-plan.md` stages 1 to 3, written on `pn52` on 2026-10-06). What the parallel
  path used in the meantime - the tip calibration's camera offset plus the runout at the shot
  rotation, selected by an `isFirstNozzleTip` flag and carried per shot in `PreCapturedPlacement` -
  is **deleted**. It was computed, from the machine's own stored calibration values, to misplace the
  second nozzle of every parallel batch by about 0.2 mm (that is the camera spacing against nozzle
  spacing mismatch, which the expression never contained), and measured on a running job to sit
  0.27-0.32 mm away from what the frames showed. A pre-captured shot is now analysed with its own
  residual: where the shot wanted to be minus where the machine said its nozzle was the instant the
  frame was exposed, which is the same quantity the sequential path records for a move it chose not
  to make, arrived at the same way and subtracted in one place. `runtime-displacement-plan.md` is the
  specification and decision record of that rewrite; stages 4a to 4e are implemented and each was run
  on `pn52`, the last of them on 2026-10-08 at 12:23.
  The sequential trick of `multi-bottom-vision` - a nozzle already close to its shot location is not
  moved at all, the picture is taken without settling and the difference is compensated in the
  analysis - was dropped by the parallel rework and restored in this fork, but kept per shot on the
  pipeline instead of mutable state on the `Camera`, and only for pre-rotate and single-shot parts.
  See R14 in `SALVAGE-PLAN.md` and run D in `MACHINE-TEST.md`.
- Pre-alignment check: **not merged**. A mid-debug snapshot is preserved on branch
  `parallel-bottom-vision-wip`; see `PRE-ALIGNMENT-CHECK.md` for the write-up.

## Written in this fork, not imported

| Document | Content |
|----------|---------|
| `SALVAGE-PLAN.md` | how the work was rescued from the development checkout, the risk register, and what the hardening did about it |
| `MACHINE-TEST.md` | what to run on the real machine before this branch is trusted, and what deliberately is not measured |
| `PRE-ALIGNMENT-CHECK.md` | the unimplemented pre-alignment check, as a requirement note |
| `PRE-CHECK-ASSESSMENT.md` | whether the pre-check and an earlier departure from the vision position are worth doing, measured on the machine on 2026-09-28, including what has already been ruled out; supersedes the timing requirements of `PRE-ALIGNMENT-CHECK.md` |
| `DISPLACEMENT-VERIFICATION.md` | **specification**: the three offsets of an up-looking camera, the two things called displacement, the tip-only measurement that separates a camera displacement from a nozzle error, the audit of `displacementDelta()` with the machine's own numbers (that method is deleted as of 2026-10-08; the audit is why), pass criteria |
| `runtime-displacement-plan.md` | **specification and decision record**: what the bottom vision does with the displacement number at runtime - the residual of the shot as the only term (D1), the limits enforced before the first exposure of a batch (D5), post-rotate parts refused by the parallel path (D10), the rotation a shot is taken at (E7), a head with independently driven nozzles refused rather than photographed out of focus (D13). Stages 4a to 4e are implemented and each run on pn52 on 2026-10-07/08; the one question none of them answers is Q5, whether the parts land where the numbers say they land, which needs a gauge |
| `camera-displacement-plan.md` | the sequence of work that follows from it: measure with a script, store `<displacement>` on the camera, then change the job - stages 1 to 3 done here, stage 4 handed to `runtime-displacement-plan.md` |
| `TIME-BUDGET.md` | where the time of a job goes, stage by stage, and what the cameras own in it |
| `CAPTURE-COST.md` | what a frame costs, measured on pn52 in 2026-10, and what to do about it ranked by saving per job |
| `GRAB-AHEAD.md` | the frame ring that implements item 2 of `CAPTURE-COST.md`: the settings, the design, the invariants, what it costs, what the tests prove, and what to read in a machine log |
| `MOTION-BUDGET.md` | where the two seconds of a placement cycle go on the machine side, measured on pn52 in 2026-10: the head is busy 99.9 % of the cycle (2-4 ms idle) so neither Java nor the wire is the prize, the 13 forced stillstands and the ~156 ms drag block are, and the dragpin interlock belongs to safety rather than to speed |
| `ONE-CAP-REFUSAL.md` | **open fault**: a part refused as half as wide as it is because one end terminal went dark, which forces a sequential fallback; what it is not (the exchanged-size refusals are the orientation guard working), the minimum-exposure reading that is the leading suspect, the `-DframeDump=onFailure` and `[VISION-EVIDENCE]` instrumentation that costs nothing until it happens, and what to do the next time it does |
