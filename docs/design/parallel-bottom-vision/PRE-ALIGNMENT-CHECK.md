# Pre-alignment part presence and size check

Status: **not implemented**. Written 2026-09-27 from a mid-development snapshot; this is a
requirement and design note for a future implementation, not a description of the current code.

## Where the snapshot comes from

The idea was worked on 2026-04-29 to 2026-05-03 in the development checkout that also produced
parallel bottom vision. It was never committed. The work as it stood on 2026-09-27 is preserved
verbatim on branch `parallel-bottom-vision-wip` (commit `b0fb281577`, do not merge), the design
documents that surrounded it are `pre-check-plan.md`, `diagnose-precheck-timing.md` and
`fix-precheck-timing.md` in this directory.

The snapshot was abandoned in the middle of a timing problem, see *Why it stopped* below. Nothing
in it was ever reviewed.

## The idea

A bottom vision camera that has just been moved under a nozzle has to wait for the image to settle
before the real alignment analysis can start. That waiting time is dead time. The pre-check uses it:
the very first frames of the settling sequence are analysed with a deliberately cheap pipeline, and
the machine knows *before* the alignment stage starts whether

- a part is actually held by the nozzle, and
- the thing that is held has roughly the size the part expects.

Detecting a missing or wrong part at that moment is much cheaper than after the full alignment: no
pipelines run, no lighting changes, no extra movements.

## Requirements

R1. The check must overlap the camera settling. It may not add measurable time to the cycle; the
    budget for the analysis of one frame is the settling time the camera would have waited anyway,
    about 30ms per camera in the configuration it was developed on.

R2. The check runs on frames the camera produces anyway. It hooks into the settling loop
    (`AbstractSettlingCamera`) through a callback on the first frames; it must not trigger an extra
    `camera.capture()` of its own.

R3. The analysis is cheap and self-contained: one small OpenCV pipeline (threshold, contours,
    `minAreaRect`), then a comparison of the found rectangle with the part's expected size within a
    tolerance, plus a confidence value. It must not reuse or disturb the alignment pipeline.

R4. Pipelines for the check are cached per nozzle and must be invalidated when the pipeline or its
    parameters change. The snapshot used a fingerprint for that.

R5. Results are collected per nozzle during the parallel capture stage and made available to the
    alignment stage, together with the duration the check took.

R6. On any error the check fails *open*: a broken check must never stop a job that would otherwise
    have run. The snapshot did this by substituting a passing result and logging a warning.

R7. Until the check has proven itself it only reports. The snapshot deliberately logged
    `NO PART` / `SIZE MISMATCH` and proceeded with the normal alignment. Turning a failed check into
    a real error, a retry, or a feeder re-pick is a separate decision, to be taken with the
    pick-and-place error handling (`NozzleTipPickError` handling, retry counts) in view, and it
    needs a switch so it can be turned off.

R8. Debug images of what the check saw must stay behind an explicit setting
    (`preCheckDebugImageSavingEnabled`, off by default) and must never write into a machine
    configuration directory.

## Why it stopped

The check measured 50-70ms per frame against the ~30ms budget, i.e. it was slower than the settling
it was supposed to hide, and the pipeline was executed on the same threads as everything else. The
two debugging documents describe the analysis; neither was finished. The likely causes named there
were the cost of creating/looking up the pipeline per call, colour conversion and thresholding on a
full-size image, and the results being waited on in a way that serialised the cameras again.

Consequence for a re-implementation: measure first, on the real machine, with the real camera
resolutions, before adding any of the machinery.

## What to do differently

- Do not resurrect the snapshot commit. Re-implement from this document.
- Resize the frame down before thresholding; the check needs a few pixels across the part, not the
  full resolution.
- Reuse the pipeline objects created for the parallel alignment infrastructure instead of a second
  cache with its own invalidation.
- Keep R6 (fail open) and R7 (report only) for the first version, and add a per-machine setting for
  the action on a failed check only after it has been measured on real hardware.
- Write the timing into the log from the start (`[TIMING]` in the snapshot was a good idea) and
  compare it against the actual settling time, not against an assumed one.

## Shape of the preserved API

The snapshot introduced these, kept here as a starting point rather than a contract:

- `org.openpnp.model.PreAlignmentCheckResult` — `partPresent`, `sizeOk`, `confidence`,
  `checkDurationMs`, `nozzle`, plus an asynchronous view of the result.
- `ReferenceBottomVision.quickCheckPartPresence(Mat, Part, Camera, ...)`, with helpers
  `checkSizeWithTolerance(...)` and `calculateDetectionConfidence(...)`.
- `BottomVisionSettings.preCheckDebugImageSavingEnabled` and the per-nozzle pipeline cache.
- A callback parameter on the settling methods of `AbstractSettlingCamera`.
- In `ReferencePnpJobProcessor`, a `Map<Nozzle, PreAlignmentCheckResult>` filled during
  `performParallelAlignment()` and read at the start of the alignment of each placement.
