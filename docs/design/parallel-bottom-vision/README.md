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
| 2026-04-25 | `displacement-correction-plan.md` | Correcting the measured offset when the part is photographed at a displaced camera position. |
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
- Camera displacement: **implemented, but in a different place than the design documents describe**.
  The per-camera `displacement` X/Y fields of `multi-bottom-vision` were runtime state and never
  survived a restart; they are gone. What the parallel path uses instead is the camera offset
  already measured by the nozzle tip calibration
  (`ReferenceNozzleTipCalibration.getCalibratedCameraOffset()`, the `center-x`/`center-y` of the
  runout compensation, looked up per nozzle) plus the runout at the wanted rotation angle, carried
   per shot in `PreCapturedPlacement`. The balance level Z of the shot also comes from it.
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
| `DISPLACEMENT-VERIFICATION.md` | assessment of letting the machine measure its own displacement data instead of re-calibrating by hand |
| `TIME-BUDGET.md` | where the time of a job goes, stage by stage, and what the cameras own in it |
| `CAPTURE-COST.md` | what a frame costs, measured on pn52 in 2026-10, and what to do about it ranked by saving per job |
| `GRAB-AHEAD.md` | the frame ring that implements item 2 of `CAPTURE-COST.md`: the settings, the design, the invariants, what it costs, what the tests prove, and what to read in a machine log |
| `MOTION-BUDGET.md` | where the two seconds of a placement cycle go on the machine side, measured on pn52 in 2026-10: the head is busy 99.9 % of the cycle (2-4 ms idle) so neither Java nor the wire is the prize, the 13 forced stillstands and the ~156 ms drag block are, and the dragpin interlock belongs to safety rather than to speed |
| `ONE-CAP-REFUSAL.md` | **open fault**: a part refused as half as wide as it is because one end terminal went dark, which forces a sequential fallback; what it is not (the exchanged-size refusals are the orientation guard working), the minimum-exposure reading that is the leading suspect, the `-DframeDump=onFailure` and `[VISION-EVIDENCE]` instrumentation that costs nothing until it happens, and what to do the next time it does |
