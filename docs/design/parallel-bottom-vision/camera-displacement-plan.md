# Camera displacement - plan

Status: **Stages 1 and 2 implemented**, and run on the machine on 2026-10-06: `pn52` now carries a
measured `<displacement>` on both up-looking cameras (`verified-at="2026-10-06 17:17"`), with the write
loop in `scripts/DisplacementCheck.bsh` measuring, writing, recalibrating the tips and measuring again.
`DisplacementCheck.java`, `scripts/DisplacementCheck.bsh`, `DisplacementSolveTest.java`; the runs and
what they said are recorded in `MACHINE-TEST.md`. Agreed with the machine owner on 2026-10-05. This is
the implementation plan; the measurement algebra, the audit of the current code and the numbers that
motivated it are in `DISPLACEMENT-VERIFICATION.md`. Where the two disagree, that document is the
specification and this one is only the sequence of work. Stage 4 has since been rewritten as
`runtime-displacement-plan.md`.

## 1. Goal

Get the highest possible placement precision out of parallel bottom vision, where the head makes
**one** move for a whole batch of nozzles and does not move again during alignment. With one move,
at most one camera can be perfectly centred; every other camera sees its part displaced, and that
displacement has to be known exactly, or it becomes a placement error.

Two things follow. The machine needs a **camera displacement** it can store and trust - the small
residual that is left after the cameras have been moved as close to perfect by hand as hands can
get them - and it needs a way to **measure** that residual without a ruler, without parts, and
without placing anything.

Placing is deliberately not part of this. Small deviations cannot be measured through a placement
without risking that some other error (feeder, pick, pocket, Z, contact) is what actually moved, and
then the whole exercise leads somewhere wrong.

## 2. Terms, so that the three offsets stop being confused

An up-looking camera's machine position is built from three stacked offsets:

| offset | stored in | applied by | in `camera.getLocation(null)` | in `camera.getLocation(nozzle)` |
|--------|-----------|------------|-------------------------------|--------------------------------|
| mechanical head offsets | `<head-offsets>` | `ReferenceCamera.getHeadOffsets()` | yes | yes |
| advanced calibration fit | `<calibrated-offsets>` | `ReferenceCamera.getCalibratedHeadOffsets()` (`ReferenceCamera.java:371-373`, fitted at `AdvancedCalibration.java:1459-1473`) | yes | yes |
| per tip runout centre | `center-x`/`center-y` of the runout compensation | `ReferenceNozzle.getCameraToolCalibratedOffset()` via `AbstractCamera.java:180` | no | yes |

and there are two unrelated things called displacement:

- **runtime displacement** (from `multi-bottom-vision`, restored in this fork): the nozzle is
  already close to its shot location, so it is not moved at all; the frame is taken without settling
  and the difference `shotLocation - currentLocation` is compensated in the analysis. Measured, exact
  by construction, guarded by `displacement-max-distance` / `-max-Z-diff` / `-max-rotation`, and the
  settle is skipped (`ImageCapture.java:100` in `multi-bottom-vision`). Not a calibration value.
- **camera displacement** (this plan): a persisted per-camera calibration value for the physical
  misplacement of the camera relative to the nozzles. It is what makes the one head move of a
  parallel batch good for every camera, and it is what keeps the runtime displacement of the other
  nozzles small and honest.

Today the parallel path uses the runout centre in place of the camera displacement
(`ReferenceBottomVision.displacementDelta()`, `ReferenceBottomVision.java:1896-1924`). That is the
substitution this plan removes; the audit and the size of the resulting error are in
`DISPLACEMENT-VERIFICATION.md` §5.

## 3. Decisions

| # | Decision |
|---|----------|
| D1 | The reference artifact is the **bare nozzle tip**, using the existing runout measurement pipeline. Parts are an optional later cross-check, never the primary measurement. |
| D2 | **Nothing is placed** at any stage. No feeder, no pick, no pocket, no board. |
| D3 | The crossed shots are the measurement: N1 seen by Cam2 and N2 seen by Cam1 is what separates a camera-side error from a nozzle-side error. |
| D4 | Moves are commanded **raw**: `camera.getLocation(null)`, i.e. the advanced calibration stays applied (it anchors the pixel↔machine mapping and it is what production uses), the runout centre is not applied (it is one of the things being measured). This is exactly how the runout calibration itself measures (`ReferenceNozzleTipCalibration.java:708-710`). |
| D5 | The stored quantity is called **camera displacement**. It is a `<displacement>` element on the up-looking camera, applied *after* the advanced calibration term in `getCalibratedHeadOffsets()`, so it stacks on top of the physical mount. |
| D6 | `<displacement x y>` is the **absolute** offset from head offsets + advanced fit, not an increment. The check proposes `new = current + measured residual`. |
| D7 | The batch head position is the **mid-range centre** of the batch's per-nozzle ideal shot locations (per axis minimax, so the worst-off camera is as centred as a single move can get it), not "exact for the first nozzle". Must generalize to 3 and 4 nozzle/camera machines. |
| D8 | The write-back **is** automated, but it first copies `machine.xml` into the existing backup convention (`Configuration.createBackedUpFile()`, `Configuration.java:640-653` → `<configDir>/backups/<timestamp>/machine.xml`), and every run also writes a settings snapshot next to its results, so there is always something to compare with. |
| D9 | Tolerances are **adjustable settings**, kept next to the existing `displacement-*` ones, and the four existing ones finally get a UI. |
| D10 | Invocation is a **script first**, then Issues & Solutions. Deliberately *not* in the nozzle tip calibration wizard: this is not a nozzle tip property. A camera adjustment helper is a later stage. |
| D11 | The runtime change in the parallel job (Stage 4) lands only after Stage 1 has measured the machine, so the correction is written against numbers rather than against algebra. |

## 4. Stages

### Stage 0 - specification

Rewrite `DISPLACEMENT-VERIFICATION.md` from an assessment into the current specification: the offset
stack of §2, the measurement algebra and its rank-3 gauge, the audit of
`ReferenceBottomVision.displacementDelta()` with the numbers from `tests_2026-10-01/machine.xml`, the
pass criteria, and what is out of scope. Mark `displacement-correction-plan.md` superseded and update
the index and status lines in `README.md`.

### Stage 1 - measure, report, write nothing - **done**

`org.openpnp.machine.reference.vision.DisplacementCheck`, GUI-free (no `MainFrame`, no dialog), plus
`scripts/DisplacementCheck.bsh` so it appears in the Scripting menu without any UI work
(`ScriptFileWatcher.java:76-133`). Entry point:

```java
DisplacementCheck.Options options = new DisplacementCheck.Options();
options.repeats = 3;
DisplacementCheck.Result result = DisplacementCheck.run(options);
System.out.println(result.report);
```

- Move set. One move centres **one tip in front of one camera**, and the head then takes whatever
  all the other tips happen to be looking at; a crossed shot is therefore a crossed *move*, not an
  extra camera on a paired move. For **N** pairs that is `N²` moves (N paired + N²-N crossed), one
  shot each, taken by the camera the move was made for. `crossedMatrix = false` restricts it to the
  N paired moves, which still gives the per-pair residuals but not the decomposition - the solver
  says so rather than inventing an answer (`DisplacementSolveTest.pairedShotsAloneCannotBeSolved`).
- Each shot reuses the runout measurement, so no second vision model is built beside it:
  `ReferenceNozzleTipCalibration.getPreparedPipeline(camera, nozzle, measureLocation)` is already
  public and already masks around the expected tip position, so a crossed shot needs only the camera
  as a parameter. `findCircle()` gained a public camera-taking entry point, `measureTip(nozzle,
  camera, measureLocation)`; the existing behaviour is unchanged, and the background calibration
  bookkeeping is deliberately left alone for a foreign camera.
- The commanded position of a shot is `camera.getLocation(null) - c`, with `c` the stored runout
  centre of the tip being measured (`applyStoredAxisOffsets`, on by default), plus the calibration Z
  offset and the requested `cAngles`/`zOffsetsMm`. Subtracting `c` is what makes the answer a
  *residual* of the current model rather than an absolute offset, and it is also what makes the
  measurement independent of the tip's rotation angle, because the motion itself already cancels the
  runout amplitude. `applyStoredAxisOffsets = false` gives the raw absolute offsets instead, which
  then need a full C cycle to average the runout out.
- Every nozzle is rotated in place to the measurement angle before the matrix. The runout is only
  compensated by the move of the nozzle that is actually moved, so a nozzle left at some other angle
  would contribute an uncompensated runout phase to the batch table.
- The batch table (`batchCheck`, on by default) is the production situation and the reason for the
  whole exercise: centre tip *k* in camera *k* the way the job processor does, then look at what each
  camera sees. That is `N` extra passes over the cameras with no extra moves, and it reports directly
  what the runtime analysis has to remove for every nozzle in the batch. It is printed as predicted
  (from the solved model) against measured, and the difference between those two is the end-to-end
  check.
- The shots are solved by **least squares** in the model `X + c = u - e` (§3 of the specification),
  gauge `sum(e) = 0`, per axis, for any `N >= 2` and with dropped shots tolerated. Reported per
  camera: the displacement error, its sigma, what is stored, and `stored + error` as the proposal;
  per nozzle: the head offset error and the residual against the stored runout centre; and the fit
  residual, which is the rigidity consistency gate on everything else. The arithmetic is separate
  from the machine (`leastSquares`, `predict`, `predictE`) and covered by
  `src/test/java/DisplacementSolveTest.java`.
- Repeats give the spread, and the check never claims an error smaller than the spread it just
  measured.
- Optional passes, each off by default: `zOffsetsMm` (a real tilt walks the displacement with Z),
  `cAngles` (with the axis offsets off, this is the runout cycle; with them on it checks the runout
  compensation), and `suppressAdvancedCalibration`, which removes `<calibrated-offsets>` from the
  camera positions and so reports how much of the displacement the advanced calibration is currently
  carrying - the seed of the future adjustment helper.
- Output, per run, into `<configDir>/displacement/yyyy-MM-dd_HH.mm.ss/`: `report.txt`, `results.csv`
  (every shot, including the misses), `summary.csv` (one line per camera and nozzle per condition,
  so runs can be diffed), `images/`, and `settings-snapshot.xml`, which is the `machine.xml` as it
  was when the run started. The snapshot is what makes a later run comparable.
- Nothing in this stage writes to the configuration, and no code path in it can.

### Stage 2 - store it

- `<displacement units="Millimeters" x="..." y="..." verified-at="..." spread="..."/>` on the
  up-looking camera, applied in `ReferenceCamera.getCalibratedHeadOffsets()` after the advanced
  calibration term, and still applied when `SuppressCameraCalibration` is requested (it is an explicit
  measured value, not a fitted one).
- Wizard field on the camera, and an Issues & Solutions entry on the camera/head subject with
  *Measure* and *Write into machine.xml* actions (`ActionProperty`,
  `NozzleTipSolutions.java:206-230`, `UiUtils.submitUiMachineTask(...)`).
- Before the write: the `machine.xml` backup of D8. After the write: the run's numbers recorded in
  `verified-at`/`spread`.
- Staleness: an Issue whenever the advanced calibration of a camera has re-run since that camera's
  `verified-at`, because the fit absorbs camera position and would silently double-count.

### Stage 3 - tolerances and their UI

The three limits are already applied by Stage 1, as `DisplacementCheck.Options` fields
(`maxResidualMm` 0.05, `maxSpreadMm` 0.02, `maxTiltMmPerMm` 0.02 mm/mm) checked after every run, with
the report printing `VALID`/`NOT VALID` and a reason per limit. What is missing is the place to set
them per machine and per operator rather than per script.

New machine settings next to the existing ones: `displacement-verify-max-residual-mm` (0.05),
`displacement-verify-max-spread-mm` (0.02), `displacement-verify-max-tilt-mm-per-mm` (0.02). The
existing `displacement-enabled` / `-max-distance` / `-max-Z-diff` / `-max-rotation` keep their
original "shoot from where you are" meaning and get fields in the same panel.

### Stage 4 - use it in the job - **done elsewhere, 2026-10-08**

Superseded on 2026-10-06 by `runtime-displacement-plan.md`, which replaces the `isFirstNozzleTip`
term with the residual of the shot after reading the original `agent1` solution and `log_M.txt`.
That plan has now been carried out, stages 4a to 4e, and run on `pn52` the same evening; what its
first bullet asked for no longer exists:

- `isFirstNozzleTip` is deleted rather than replaced with a per-nozzle value. There is no per-nozzle
  displacement value to thread, because the displacement is not carried on the shot at all - it is
  derived from the shot's own `wanted` and the nozzle position read at the exposure
  (`PreCapturedPlacement.getShotDisplacement()`), which is what makes a third and fourth nozzle
  expressible without saying anything about which one the head was moved for.
- The batch head position stayed where it was (D8 there): the residual is exact whichever nozzle the
  head is positioned for, so the mid-range idea is only about how much of the tolerance is spent.
- The limits became real before any of that was trusted: `checkDisplacementLimits()` refuses a batch
  before its first exposure (D5), and post-rotate parts are refused outright (D10).

The bullets below stand as the reason it was tempting in October.

- Replace the `isFirstNozzleTip` boolean (threaded through `ReferenceBottomVision.java:1657`, `:1677`,
  `:1731`, `:1783`, `:1897`, `:2302`, `:2369`) with a per-nozzle displacement value, so a third and
  fourth nozzle are expressible.
- Batch head position = the mid-range centre of D7, computed per batch from the stored camera
  displacements; per-nozzle runtime displacement = `ideal shot - actual`, exact, still guarded by
  `displacement-max-*`.
- Runout compensation goes back to being only runout: after a correct camera displacement the paired
  centres should sit near zero, and that is itself a check.

### Stage 5 - later, not planned here

A camera adjustment helper (live residual while a camera is in the hand, tilt separated from offset,
and not fighting the advanced calibration), and the optional part-based cross-check.

## 5. Files

New: `docs/design/parallel-bottom-vision/camera-displacement-plan.md`,
`docs/design/parallel-bottom-vision/DISPLACEMENT-VERIFICATION.md` (rewritten),
`src/main/java/org/openpnp/machine/reference/vision/DisplacementCheck.java`,
`src/test/java/DisplacementSolveTest.java`, `scripts/DisplacementCheck.bsh`.
Changed: `ReferenceCamera.java` (the `<displacement>` element and its use in
`getCalibratedHeadOffsets()`), `ReferenceNozzleTipCalibration.java` (`measureTip()`,
`getCalibratedAxisOffset()`).

Touched: `ReferenceCamera.java` (displacement element), `ReferenceNozzleTipCalibration.java` (public
camera-taking tip measurement), `ReferenceCameraWizard` / machine settings wizard (fields),
`Machine.java` + `ReferenceMachine.java` (tolerances, Issues), `MACHINE-TEST.md` (run E),
`README.md`, `displacement-correction-plan.md` (superseded pointer). Stage 4 adds
`ReferenceBottomVision.java`, `ReferencePnpJobProcessor.java`, `PreCapturedPlacement.java`.

## 6. Tests

Unit tests on a simulated machine, no hardware:

- inject known `e_i` and `u_i`, run the solver, assert they are recovered;
- pin the vector sign conventions through each camera's `rotation` and `flip-x`/`flip-y` (`Upp1` is
  `-90/flip-x`, `Upp2` is `+90/flip-y` - a sign error here mirrors the displacement instead of
  measuring it, so this test is not optional);
- `N = 2, 3, 4`, including a run with shots deliberately dropped;
- the mid-range batch centring;
- regression: `ParallelAlignmentTest`, `ShotDisplacementTest`.

On the machine: run E of `MACHINE-TEST.md` - the two-move measurement, its consistency residual, and
a repeat run to see the spread.
