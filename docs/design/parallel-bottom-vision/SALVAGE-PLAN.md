# Parallel Bottom Vision Salvage Plan

Status: ported and hardened, machine test pending (`MACHINE-TEST.md`)
Started: 2026-09-27
Source of the work being salvaged: `/mnt/agent1/dev_random/openpnp` (read-only mount, repo owned by
another user; never written to).

## 1. Background

The fork previously had two vision feature lines:

- `multi-bottom-vision` (MBV) - multiple bottom vision cameras, one paired to each nozzle by
  number. Merged content is squashed inside PBV; upstream PR #1916 is still open.
- `parallel-bottom-vision` (PBV) - parallel capture and parallel pipeline processing across those
  cameras. Committed on 2026-04-27 as `6343c999e9` (39 files, +3572/-72), pushed to
  `origin/parallel-bottom-vision`. Tested and working on the physical machine, still experimental.

PBV was developed on the upstream base `c9e2518545` (2026-03-26) and therefore does not contain
anything that landed upstream since then. Our fork is synced to `upstream/test` = `16d29e7b33`
(2026-09-26).

Decisions taken:

1. PBV becomes the active feature line, merged into `test` after porting and hardening.
2. MBV is frozen: branch, tag `backup/mbv-20260926` and PR #1916 are left untouched as history.
3. The uncommitted pre-check work found on disk is backed up now, but its code is NOT ported. It is
   written up as a requirements/design spec for a later clean implementation.
4. Everything is assumed to be "better" in the agent1 source in terms of features, but the code
   went through many back-and-forth iterations by an older LLM, so it is treated as messy until
   proven otherwise. Known risks are listed in section 4 and must not be ignored.

## 2. Port feasibility

- PBV touches 39 files.
- Only 1 of them (`src/main/java/org/openpnp/machine/reference/ContactProbeNozzle.java`) was also
  changed upstream between `c9e2518545` and `16d29e7b33`, so the rebase onto current upstream is
  expected to be nearly conflict-free.
- 22 of the 39 files are the same files touched by our MBV cleanup. PBV already contains MBV
  squashed in, so MBV behaviour is redundant while MBV's quality fixes still need to be re-applied
  on top of PBV.

## 3. Phases

### Phase 1 - Preserve (no writes to the agent1 repo)

1. `git remote add agent1 /mnt/agent1/dev_random/openpnp`, `git fetch agent1 --no-tags`,
   `git tag backup/pbv-20260927 6343c999e9`.
2. Back up the uncommitted pre-check WIP onto branch `parallel-bottom-vision-wip` as an explicit
   "do not merge" commit:
   - tracked diff excluding the personal `openpnp.sh`,
   - untracked `org/openpnp/model/PreAlignmentCheckResult.java`,
   - the five `ReferenceBottomVisionPreCheck*Test.java` classes,
   - fixtures `src/test/resources/config/PreCheckC0402Test/`.
   - Excluded from git: `precheck_img/` (44 MB), real machine config (`machine.xml`, `parts.xml`,
     `packages.xml`, `vision-settings.xml`), run logs, `openpnp.sh`, the `OpenPnP` launcher.
3. Import the 14 design documents from `.opencode/plans/` into
   `docs/design/parallel-bottom-vision/`.

### Phase 2 - Port

4. `git rebase --onto upstream/test c9e2518545 parallel-bottom-vision`.
5. Build and run the full test suite; compare against the 258-test green baseline and classify every
   failure as upstream drift, PBV defect, or hardware-dependent test.

### Phase 3 - Re-apply MBV quality fixes

6. Cherry-pick `f4790a3fcf` over the overlapping files, resolving towards PBV behaviour plus MBV
   type safety: `Length` displacement offsets, `default` SPI methods, `IEEEremainder` rotation wrap,
   configured instead of hardcoded limits, unit-safe `Length.compareTo` distance checks.

### Phase 4 - Hardening (one revertable commit per item)

7. R1 camera-pairing cache data race.
8. R2 global `Core.setNumThreads(1)`.
9. R3 swallowed exceptions in `CvPipeline.resetToDefaults()`.
10. R4 `Mat` native memory lifecycle across threads.
11. R5 executor sizing, queue rejection, thread naming, shutdown.
12. R6 the `parallelProcessed` one-shot flag.
13. R7 displacement fallback correctness.
14. R8 `ReferenceBottomVision` overload sprawl.
15. R9 audit that new SPI methods are `default`.
16. R10-R11 test hygiene and hardware-independent tests.

### Phase 5 - Pre-check as a spec

17. Write `docs/design/parallel-bottom-vision/PRE-ALIGNMENT-CHECK.md` from the WIP plus the
    `pre-check-plan.md` and `diagnose-precheck-timing.md` notes, including the measured blocker.
    Disabled by default and experimental when it is eventually implemented.

### Phase 6 - Wrap-up

18. Update `FORK-NOTES.md` with the new branch topology.
19. Final full test-suite run. No push to `origin` until GitHub authentication is available.

## 4. Risk register (verified in `6343c999e9`)

| # | Risk | Evidence |
|---|------|----------|
| R1 | Data race: the nozzle to camera pairing cache is a plain `HashMap` read and written from parallel vision threads, and is never invalidated on configuration reload or rename | `Configuration.java:132,755` and `VisionUtils.java:169-183` (`cache.put` inside `getBottomVisionCamera`) |
| R2 | JVM-global OpenCV change inside a constructor: `Core.setNumThreads(1)` on every `CvPipeline`, throttling all vision for all users | `CvPipeline` constructor |
| R3 | `resetToDefaults()` swallows exceptions, hiding genuine pipeline errors | `CvPipeline.resetToDefaults()` |
| R4 | OpenCV `Mat` native memory handed across threads (`CapturedShot`) with no try/finally release on exception or job-abort paths; leak or use-after-release | `CapturedShot`, `clearPreCapturedShots`, `processPreCapturedImage` |
| R5 | Fixed pool `max(2, cpus/2)` with `LinkedBlockingQueue(100)` gives `RejectedExecutionException` mid-job; non-atomic counter in the thread factory; `shutdownVisionExecutor()` is never called | `ReferencePnpJobProcessor.getVisionExecutor()` |
| R6 | `Align.parallelProcessed` one-shot boolean inside `run()` is fragile with retries, `return this` re-entry and aborted jobs | `ReferencePnpJobProcessor` `Align` step |
| R7 | Displacement changed from camera state (MBV `isDisplacementActive`) to a per-shot `intendedDisplacementDelta`; the author's own comments warn the fallback is invalid when `skipMovement` is false. Must confirm a failed alignment re-captures instead of reusing a stale shot | `ReferenceBottomVision`, `PreCapturedPlacement` |
| R8 | API sprawl in one class: 3 x `findOffsets`, 2 x `preparePipeline`, 2 x `getCameraLocationAtPartHeight`, `captureImageOnly` and `captureImageOnlyWithSettle` each duplicated, static `getThreadLocalPipeline` | `ReferenceBottomVision.java:109-186,506-513,652` |
| R9 | New SPI methods must be `default` or they break third-party plugins | `PartAlignment.captureImageOnly`, `AdvancedCalibration` skipMovement overload, `Camera.set/getCapturedImage`, `CameraBatchOperation.getCurrentBatchName` |
| R10 | `Logger.trace` added to the per-capture light path; overhead must be measured | `AbstractCamera` |
| R11 | Test hygiene: 149 added `System.out` lines, hardcoded `/home/micke/dev_random/openpnp/precheck_img/precheck` paths, fixtures at the repository root instead of `src/test/resources` | new `Parallel*Test`, `PreCheck*Test` |
| R12 | The pre-check WIP is mid-debug and over budget: 50-70 ms measured against a ~30 ms settle window | `.opencode/plans/diagnose-precheck-timing.md` |
| R13 | No way back: the parallel path has no setting of its own, it is taken whenever a batch has more than one bottom vision camera and more than one placement. Found while writing the outcome, it is not in the register above. | `ReferencePnpJobProcessor.Align.stepImpl()` and `performParallelAlignment()` |
| R14 | What displacement actually is was unclear enough to be reported as missing. The MBV sequential trick (nozzle already within `displacement-max-distance` of the shot location, so do not move it, store the difference on the camera, let `ImageCapture` skip settling, compensate in `findOffsets`) is gone from the parallel rework: the non-pre-captured path always moves to the exact shot location. Displacement in this branch is only the per-shot delta of the pre-captured path plus the balance-level Z. | MBV `ReferenceBottomVision` (`camera.setDisplacements(...)`, `isDisplacementActive()`) and `ImageCapture` (`SettleOption.Skip`) against this branch, where `ImageCapture` has no displacement logic and `PipelineShot.apply()` says "Displacement fallback is NOT allowed" |
| R15 | Every bottom vision clone resets the pipeline it clones. `clone()` goes through `toXmlString()`, which calls `resetToDefaults()`; the parallel path clones the pipeline of the vision settings once per pass per nozzle, so the pipeline shared with the GUI and the other threads is reset on every one of them, and the reset fails because that pipeline has no camera property for a size parameter to convert with. Found in the first machine run, where it filled the log with 16 messages in a job of 8 parts. | `CvPipeline.clone()`/`toXmlString()`, `ReferenceBottomVision.getThreadLocalPipeline()`; `tests_2026-09-28/log_a.txt`, `pDetail` is `SquareMillimetersToPixels` in 28 of the machine's vision settings |
| R16 | A nested camera batch renames the batch around it, so the log of the outer close names the inner one and a camera is said to register with a batch that has already closed. The batch name exists only as a diagnostic of who forgot to close a batch, which the parallel pre-capture (nested inside the align step) breaks. | `ReferenceCameraBatchOperation.startBatchOperation()`/`endBatchOperation()`; `log_a.txt`: `Batch END level 1 'align step' - expected: parallel-align-precapture` |

## 5. Out of scope

Machine configuration and captured images, `openpnp.sh`, any modification of the agent1 repository,
closing PR #1916, and pushing to `origin`.

## 6. Outcome (2026-09-27)

Branch `parallel-bottom-vision`, based on `upstream/test` `16d29e7b33`:

| Commit | Content |
|--------|---------|
| `1f64cfbb35` | the salvaged work, ported as one commit (parallel capture + displacement, MBV squashed in) |
| `cf456ad267` | design history documents imported into `docs/design/parallel-bottom-vision/` |
| `dac7e90083` | MBV quality fix: displacement settings on `Machine` become `default` methods |
| `f92ab69073` | MBV quality fix: unit-safe displacement handling of pre-captured shots |
| `8190ae9c2a` | hardening R1 |
| `dec31fedef` | hardening R2, R3 |
| `68848cfd5d` | hardening R4 (part), R5, R2 scope |
| `92c971eb18` | hardening R6 (part) |
| `a66d0dad5d`, `8f9fb00a98` | this plan and `PRE-ALIGNMENT-CHECK.md` |
| `a939b4e3df` | R13: the `parallel-alignment-enabled` setting |
| `74cb7cc892` | R14: the sequential small-move saving of MBV, per shot instead of per camera |
| `a4f42fb1dd` | R15: cloning for a vision thread no longer resets the shared pipeline |
| `0f00b3f06f` | R16: nested camera batches keep their own names |

The documentation commits in between are left out of the table, `git log --oneline` has them all. The
test suite is 285 tests now: `ShotDisplacementTest`, `PipelineCloneResetTest` and
`CameraBatchNestingTest` were added along the way.

Disposition of the risk register. The full test suite passed before and after every hardening commit;
none of the items below marked *fixed* changes what the machine does, they only remove the ways it
could go wrong.

| # | Disposition |
|---|-------------|
| R1 | **fixed** `8190ae9c2a`: the cache is gone, the camera is looked up on every call. The lookup is a loop over a handful of cameras; there is nothing worth caching in it. |
| R2 | **fixed** `dec31fedef` + `68848cfd5d`: no longer touched by the pipeline constructor; `alignAllParallel()` saves the value, sets one thread for its own duration and restores it in a `finally`. |
| R3 | **fixed** `dec31fedef`: skipped stage resets are logged, and the `clone()` shortcut that avoided the reset is gone. |
| R4 | **partly fixed** `68848cfd5d`: a pre-capture that throws now releases the images it had already taken, and the released flag of a shot is visible to the other threads. Not done: an audit of who owns the `Mat` in `processPreCapturedImage()` and `clearPreCapturedShots()`, and whether overwriting a buffered shot releases the old image. Needs a native-memory measurement on the machine, not a code reading. |
| R5 | **fixed** `68848cfd5d`: unbounded queue (at most one task per nozzle exists anyway), atomic thread numbering, pool allowed to shrink to zero, `shutdownVisionExecutor()` called from `ReferenceMachine.close()`. |
| R6 | **partly fixed** `92c971eb18`: the align step again verifies the part is still on the nozzle afterwards, which is what the parallel path had silently dropped, and the flag is documented. Not done: the flag is still a one-shot on a step object; what happens to it on a job retry or an aborted-then-resumed job is untested. |
| R7 | **partly fixed** `f92ab69073`: displacement limits are enforced in real units, a displaced shot is only used in the single-pass case, and using one after a rotation now logs a warning instead of pretending. Not done: the post-rotate path still does not compensate the displacement, and the correctness of the per-shot delta needs a real machine with displacement enabled. |
| R8 | **not done, on purpose**: `ReferenceBottomVision` remains a 4000 line class with three `findOffsets` and two of most other things. Untangling it is a behaviour-preserving refactor of the code that decides where a part is, and it should be done with the machine available to verify it, not as drive-by cleanup. |
| R9 | **fixed** `dac7e90083` for the `Machine` methods; the ported `PartAlignment.findOffsets(..., boolean skipMovement)` was already a `default` method. The other methods named in the register (`Camera.set/getCapturedImage`, `CameraBatchOperation.getCurrentBatchName`, an `AdvancedCalibration` overload) turned out not to exist in the tree; the register was over-inclusive. |
| R10 | **not measured**: the trace logging in the light path is still there. Whether it costs anything is a measurement on the machine, and it is cheap to remove if it does. |
| R11 | **mostly not a defect**: `System.out.println` in tests is the convention of this repository (30 test files use it), so the new tests were left alone. The hardcoded `/home/micke/...` paths and the repository-root fixtures belong to the pre-check snapshot on `parallel-bottom-vision-wip`, not to this branch, and that branch is not meant to be merged. |
| R12 | documented in `PRE-ALIGNMENT-CHECK.md`, not implemented. |
| R13 | **fixed** after the first test run of this outcome: `parallel-alignment-enabled` on `ReferenceMachine`, exposed as a `default` method on the `Machine` SPI, gating `Align.stepImpl()`. Defaults to `true`, i.e. the behaviour of the checkout this came from; set it to `false` to get the classic one-nozzle-at-a-time alignment without rebuilding. No GUI, like the displacement settings. |
| R14 | **fixed** `74cb7cc892`, per-shot rather than per-camera: `CvPipeline.setShotDisplacement(shotIndex, ...)` records that the picture is taken without moving, `ImageCapture` skips the settling for that shot, and `findOffsetsPreRotate()` subtracts what is left. Narrower than MBV on purpose, because MBV kept the value on the `Camera` and never cleared it on the branch that did move, so a stale displacement could be applied to a later pass or to the other nozzle: it is only done for a single-shot part (a composited part is assembled from its shots in machine coordinates), only in pre-rotate (the post-rotate path returns offsets relative to the camera centre and has no nozzle location to correct), and the value is cleared on every `resetReusedPipeline()`. `ShotDisplacementTest` covers the bookkeeping; whether the compensation is right is run D of `MACHINE-TEST.md`. |
| R15 | **fixed** `a4f42fb1dd`: `CvPipeline.cloneWithoutReset()`, used by `getThreadLocalPipeline()`. `clone()` still resets, because that is what keeps runtime parameter values out of a pipeline that is saved or opened in the editor. Nothing is lost for the vision threads: `preparePipeline()` gives the clone its parameter values again from the settings and the parameter stage applies them to its target stage on every `process()`. The catch in `resetToDefaults()` from `dec31fedef` stays - without it the failed reset would leave `clone()` as a `CloneNotSupportedException`, which is also why the parallel author had made `clone()` skip the reset. Note that the feeders that
clone a pipeline (`ReferencePushPullFeeder`, `BlindsFeeder`, `AbstractPandaplacerVisionFeeder`) have
the same exposure upstream and were left alone: they clone their own pipeline, not one shared with a
GUI, and they are not what this branch runs. |
| R16 | **fixed** `0f00b3f06f`: the open batch names are kept on a stack and the outer name is restored when the inner batch closes. Logging only, but it is the logging that tells us who leaked a batch, which is check 6 of `MACHINE-TEST.md`. |

Still open, in the order they should be taken:

1. `MACHINE-TEST.md`: the same job run four times, with `parallel-alignment-enabled` off, on with
   `displacement-enabled` off, on with `displacement-enabled` on, and off with
   `displacement-enabled` on, plus the abort, missing-part, single-camera and long-run checks.
   Started: the parallel run with displacement on exists (`tests_2026-09-28/log_a.txt`, recorded
   under *Results*), and it is clean - no fallback, no rejection, no warning, and the two pipelines
   provably processed at the same time. It also produced R15. Runs A, B and D and the abort,
   missing-part, single-camera and long-run checks are still to be done.
   Placement accuracy is deliberately not measured - on this machine it is dominated by mechanics and
   by the last calibration, so a change of this kind cannot be seen in it; behaviour, rejections,
   cycle time and memory can.
2. R7 post-rotate compensation, once (1) says where the remaining error is.
3. R4 ownership audit.
4. R8 class untangling, with the machine available.

The branch has been run on the machine once, on a build made in the author's checkout rather than
from this repository; which commit that build was is not recorded, so treat the first run as evidence
about the design, not about a commit here. Re-run it (run C) from a build of this branch when the
remaining runs are made.
