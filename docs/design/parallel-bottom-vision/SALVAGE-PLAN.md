# Parallel Bottom Vision Salvage Plan

Status: IN PROGRESS
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

## 5. Out of scope

Machine configuration and captured images, `openpnp.sh`, any modification of the agent1 repository,
closing PR #1916, and pushing to `origin`.
