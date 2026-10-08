# Runtime displacement: what the bottom vision is supposed to do with the number

Written 2026-10-06, after reading the history of this branch, the original work on `agent1`
(`~/dev_random/openpnp`, its `machine.md`, `displacement-correction-plan.md` and run logs), and the
displacement check runs of the same day. Extended the next day with the alignment move and the settle
(§4), the sequential path as the working reference (§5), and the machine script that answers the open
questions (§7). *Written as a plan, and then executed:* stages 4a to 4e are implemented, each with the
log lines and the offline tests recorded in `MACHINE-TEST.md`, the last run on `pn52` on 2026-10-08 at
12:23; the documents of 4f are updated to say so. What is left of this file as plan rather than record
is §7's Q5, which no code answers.
Companion of `camera-displacement-plan.md` (Stages 1-2,
measure and store); this is that plan's Stage 4, rewritten because the reading changed what Stage 4 is
for.

## 1. Why a plan rather than a patch

Two things have been called displacement since the beginning, and they are not the same thing.

- **The residual of a shot that was not moved to.** Upstream, and first committed here by
  `1341460533`/`9401be8487` (2025-11-11): if the remaining move to the shot location is tiny, do not
  make it, and compensate the difference in the analysis. It is *measured* - `shotLocation -
  currentLocation` - so it is right whatever the machine's calibrations happen to claim, and it is
  self-cancelling: the shot that produced the number is the shot the number is applied to.
- **The prediction of where the other cameras are in a parallel batch.** `machine.md` on `agent1`:
  "The cameras are fixated, with an distance between that is close to the distance between the
  nozzles. The slight delta error is saved to 'displacement' (X & Y) on all cameras but the one that
  is the reference." It is *modelled*, it is a constant per tip, and it is never checked against
  anything.

The first is worth keeping and is in working order: `log_M.txt` shows it doing its job,
`displacement (sequential) (-0.115867` and `(-0.115451` for two shots that differed by 0.4µm from
each other in the same batch.

The second is the one being asked about, and the reading says it has never been right and never been
measured. The term in HEAD is algebraically the term in `6343c999e9` - `git patch-id` ties that
commit to its re-port `1f64cfbb35`, and everything since (`dac7e90083` settings on the SPI,
`f92ab69073` unit safety and enforcing the Z/rotation limits, `74cb7cc892` per-shot instead of
per-camera state, `8cdb64039a` extracting the method) is around it, not in it. What `log_M.txt` shows
it doing is being a constant: `displacementDelta=(0.000000…)` eight times and `(0.201657…)` eight
times, for the whole job. A number that does not vary across a job in which the head stopped in
different places is not a residual.

## 2. What is wrong, itemised

Each item is a fact about the code, with the line it is a fact about.

| # | Fact | Where |
|---|---|---|
| E1 | The analysis never asks where the nozzle was. `nozzleLocation` is initialised to `wantedLocation` and then corrected by the stored term, so the one quantity that is directly known - the head's actual position - is the one quantity not used. | `ReferenceBottomVision.java:319-321`, `:334-341` |
| E2 | The term is the runout calibration's per-camera circle centre, and `wantedLocation` already contains that same centre: `getCameraLocationAtPartHeight()` → `camera.getLocation(nozzle)` → `tool.getCameraToolCalibratedOffset(camera)` → `getCalibratedCameraOffset()`. Adding it again double-counts it. | `:251-256`, `AbstractCamera.java:178-184`, `ReferenceNozzle.java:479-487`, added again at `ReferenceBottomVision.java:1902-1908` |
| E3 | The first nozzle gets zero, which is defensible for a residual and indefensible for the runout at the shot angle, which applies to it like to any other nozzle. | `:1917-1919`, `:2369-2376` |
| E4 | The actual spacing mismatch between cameras and nozzles appears in no term. It is absorbed into the circle centre, which makes it per-tip, per-rotation-model, and erased whenever a camera is touched (`setDisplacement()` → `viewHasChanged()` → `resetAllNozzleTips()`). | `ReferenceCamera.java:414-419`, `:455-460` |
| E5 | The limits mean nothing in the parallel path: `displacement-enabled` and the three `displacement-max-*` gate the sequential skip-move, while the parallel path applies its term regardless and only *warns* when its own term exceeds them. | `:738-742`, `:854-856`, `:1052-1080` |
| E6 | A post-rotate part cannot use a displaced shot at all, and is warned about rather than refused. *Fixed 2026-10-07 by D10: the batch now asks each of its parts how it is aligned and goes sequential before the head moves if any of them is post-rotate, naming the part. The analysis-time warning is kept as an error rather than a warning, because arriving there now means a part slipped past the refusal.* | `:1079-1091`, refusal in `ReferencePnpJobProcessor.alignAllParallel()` |
| E7 | Every pre-captured shot computes its `wantedLocation` with a rotation of **0**, while the head was turned to the placement rotation. The runout part of the term is therefore looked up at 0° whatever the part is placed at, and the rotation the batch leaves behind is never the rotation the term was computed for. *Fixed 2026-10-08 (stage 4d):* `ReferenceBottomVision.shotRotationOf()` is now the one answer to "what rotation is this shot taken at" - the placement rotation transformed by the board and normalised, or `0.` for a post-rotate part, which is what the post-rotate path actually photographs - and `getLocation()`, the head-alignment move, both settle-window paths, the multi-shot pass and the pre-exposure check all ask it. Found again on the way: the head-alignment move used the *untransformed* `placement.getLocation().getRotation()`, so a board with a rotation of its own moved the head to an angle no part was placed at. What the nozzle is actually held at is the `PrerotateAllNozzlesForAlign` step's doing (D12), which turns every nozzle, not only the one being moved for, and now computes the same number as the shot record. | `ReferenceBottomVision.java` `wantedRotationOf()`/`shotRotationOf()`, `ReferencePnpJobProcessor.java` `moveNozzlesToAlignmentPosition()`, `PrerotateAllNozzlesStep`; `ShotRotationTest` |
| E8 | Which nozzle is the reference is decided by the iteration order of a `HashMap`: `allPlacements` is flattened over `placementsByCamera.values()` and `allPlacements.get(0)`'s nozzle picks the settle camera and `firstNozzleTip`. Consistent within a batch, but it can be a different nozzle after a restart, so the constant term of `log_M.txt` sits on whichever camera happened to be second. *Confirmed live 2026-10-07: two runs of an unchanged job moved for different nozzles, and the residual came out the negative.* The machine's owner's position is that this is inherent rather than a defect to remove - with one placement per nozzle there is no order left to arbitrate, the placements themselves are the decision - so the lever is D8's mid-range position, not a deterministic ordering. | `ReferencePnpJobProcessor.java:2483`, `:2495-2511` |
| E9 | The second nozzle reports its Z at capture as the negation of the first's. Both shots want Z +0.4330mm; the nozzle the head was moved for reports +0.4328mm, the other reports -0.4328mm, in all four batches of a run. The magnitude follows the head, the sign does not. *At 20:38 the same thing happened on N1, which was the nozzle not moved for that time* - so it is whichever nozzle the head was not taken for, not a peculiarity of one axis. The machine's owner's recollection is that a negated Z for the second Z axis is natural (an inverted axis reporting in its own sense), which makes this not a fault but a unit mismatch between two nozzles. *Settled 2026-10-08 as the model and not a fault at all:* `pn52` drives both nozzle heights from one motor axis `ZN`, with `Z1 = ReferenceCamCounterClockwiseAxis(ZN)` and `Z2 = ReferenceCamClockwiseAxis(Z1)`, so the second nozzle reporting the negation is that rocker turning the other way, and the two nozzles' reported Z values cannot be compared with each other at all. Either way the consequence for 4c stands and gets sharper: `displacement-max-Z-diff` is 0.1mm, so a limit may not be computed as the difference between the two nozzles' reported Z, and has to stay a per-shot comparison - which since 4c is against the balance level the head is moved to and the shot taken at, the one height at which the two nozzles of such a head agree (D12). | `Displacement of part` lines of `pn52`, 2026-10-07 18:52; the reading is `nozzle.getLocation()` in the capture paths of `ReferenceBottomVision.java` |

The size of E4 was already computed from the machine's own stored values in
`DISPLACEMENT-VERIFICATION.md` §5: claimed nozzle spacing `T = (23.0911, -0.0261)`, claimed camera
spacing `S = (22.6075, -0.1596)`, `T - S = 0.502mm`. That is the whole of `displacement-max-distance`
consumed by a static error before any part is placed, which is why the limit reads as noise on this
machine. It is also what the check measured and stored on 2026-10-06: Upp1 `-0.1850, -0.0237` and
Upp2 `0.1850, 0.0237` (`verified-at="2026-10-06 17:17"`, `spread 0.0248`), which removes 0.370mm of
that 0.502 and leaves about 0.13mm - with 0.011mm of the write itself left over, at the detector's
half-pixel step of 0.0134mm.

So the new work is not a rival to `agent1`'s solution, it is the same error in a different place.
`displacement-correction-plan.md` on `agent1` (2026-04-25) reached the opposite storage decision after
a 180mm disaster - "The displacement should come from nozzle tip calibration (`getCalibratedCameraOffset()`),
not from camera position … ONE displacement value per nozzle tip, not separate values per camera" -
and the consequence is visible in its own `machine.xml`, where tip 503 carries circle centres of
`(0.20435, 0.14572)` on N1 and `(-0.18781, 0.07070)` on N2: runout of a machine that has none of that.
Either fit can hold the error; whichever is refreshed last owns it. The difference is that a
`<displacement>` on the camera is measured, dated and legible, while the centre is fitted as a
by-product, is per-tip, and is silently emptied by `viewHasChanged()`.

## 3. The contract

One sentence: **the displacement of a shot is the difference between where that shot wanted to be and
where the nozzle actually was, in machine coordinates, recorded at the instant the frame was taken.**
It is never a calibration term, because a calibration term is a claim about the machine and a
displacement is a fact about a frame.

- *Wanted*: `getCameraLocationAtPartHeight(part, camera, nozzle, angle)` (`:251-256`). This already
  carries the head offsets, the advanced calibration's `calibratedOffsets`, the stored
  `<displacement>` (`ReferenceCamera.java:397-404`) and the runout circle centre. Nothing else needs
  to be added to it. The `angle` has to be the angle the shot is really taken at, which today it is
  not (E7): `wanted - actual` is only meaningful if `wanted` is the shot that happened.
- *Actual*: `nozzle.getLocation()`, which is derived from the mapped axes
  (`AbstractHeadMountable.java:401-406`), so a nozzle that was not the one commanded still reports the
  truth on a coupled head. It has to be **recorded into the shot at capture time**, because by the
  time the analysis runs the head has moved on.
- *Delta*: `wanted - actual`. The nozzle the head was moved for gets 0 because it is 0, not because a
  boolean said so. The others get the geometry that actually produced the batch, including the
  spacing mismatch, with its sign. Three and four nozzles need no new thought.
- *Limits*: the three `displacement-max-*` values are then honest - they describe how far a shot may
  stand from where it wanted to be, which is exactly what the operator is being asked to tolerate.

What follows mechanically: `PreCapturedPlacement` carries the actual location instead of an
`intendedDisplacementDelta`; `displacementDelta()` is deleted; `isFirstNozzleTip` is deleted from the
26 places it appears; the duplicated block in `captureImageOnlyWithSettle` (`:2347-2376`) goes with
it; and the runout-at-angle question is separated out rather than smuggled along in the same term
(Q1 below).

## 4. The alignment move and the settle, as they actually happen

Worth writing down because the contract has to be fitted into this and not into an idealised loop, and
because the settle is what makes a frame worth applying a displacement to at all. All of it is
`ReferencePnpJobProcessor.java` and `ReferenceBottomVision.java`, checked line by line.

1. **Whether the batch happens at all.** More than one placement in the cycle and those placements
   spread over more than one bottom camera, otherwise every part is aligned the ordinary way
   (`:2190-2214`). Any placement that ends up without a pre-captured shot - a composited part, mostly -
   sends the *whole* batch back to the sequential path (`:2233-2247`).
2. **Who is the reference.** `preCaptureAllImages()` flattens the placements and takes the settle camera
   from the first one's nozzle, and that nozzle becomes `firstNozzleTip` (`:2483`, `:2495-2511`). Note
   E8: this is `HashMap` iteration order.
3. **Grab ahead, before the move.** Every camera *except* the settle camera is told to keep a ring of
   frames ready while the head is still travelling (`beginGrabAhead(...)`, `:2556-2563`), because a frame
   asked for after the arrival is one exposure and one driver round-trip late.
4. **One move, for one nozzle.** `moveNozzlesToAlignmentPosition(settleCameraPlacements)` (`:2384-2444`)
   computes the wanted location of `settleCameraPlacements.get(0)` at part height and at the *placement
   rotation* (`:2408-2419`), lifts to safe Z, moves, actuates the light **before** the wait so that the
   M810 is queued ahead of the M400 (`:2421-2431`), waits for stillstand, and only then stamps
   `arrivedAtMs` from `NanosecondTime` (`:2434-2439`). That stamp is the instant the planner says nothing
   moves, not the instant the last command was sent.
5. **The content bound.** The ring-reading cameras are then told that a frame must be from no earlier
   than that arrival (`setFrameNotBefore(...)`, `:2578`). This works precisely because the newest ring
   frame lags the stop by a little rather than preceding it.
6. **The settle is the proof.** The settle camera runs its own settling, and the epoch of that settling is
   read *before* anything is submitted so that a window recorded by an earlier settling cannot be
   mistaken for this one (`:2596-2604`). The other cameras then hunt for a frame demonstrably inside the
   still window that settling opens - `grabUntilTheStillWindow()` (`ReferenceBottomVision.java:1998`),
   with `getSettleEpoch()` and `getCandidateStillWindowStartAtMs()` as the two things it is allowed to
   believe. The batch ends by clearing the bound and ending the grab-ahead (`:2852-2853`).
7. **What a capture does.** `preparePipeline(..., skipMovement = true)`, so no capture moves anything
   (`:2328-2342`); the legacy term is computed there and then (`:2347-2376`, and a second copy at
   `:1811` in the grab-ahead path); a pre-check, if the part wants one, is analysed on the very first
   frame of the settling, while the mechanics are still settling (`:2384-2400`).

Two consequences for the contract, and one for the plan:

- **The actual location has to be read inside the window and kept in the shot.** The head is where it is
  for the duration of the still window and for no longer; by the time the pipeline is processed the head
  has moved on, which is exactly why `log_M.txt` cannot be reproduced from a log line afterwards.
- **One arrival per batch is real, not an approximation.** The batch shares `arrivedAtMs` and the settle
  camera's window, so recording one `actual` per nozzle is not a simplification of a moving head, it is
  the description of a head that was standing still. That is also why D8 can leave the choice of the
  batch position alone: with an honest delta it is a tolerance question, not a correctness one.
- **Nothing here is performance tuning to be tidied up.** The light-before-wait ordering, the epoch read
  before submission, the content bound and the still-window hunt are all correctness machinery with a
  failure each behind them, which is what D7 means when it says untouched.

## 5. What the sequential path already does, in full

It is the only working implementation of the contract, so it is worth reading as the specification
rather than as a neighbour. Same file, `ReferenceBottomVision.java`.

- **Is it allowed at all** (`:738-742`, `displacementPossible`): not a skipped movement, a nozzle
  present, **pre-rotate**, exactly one shot in the composite, and `machine.isDisplacementEnabled()`. The
  comment above it gives both reasons: a composited part is assembled from its shots in machine
  coordinates so shots taken from other places do not add up, and the post-rotate path never sees a
  nozzle location to apply a difference to.
- **Where the shot wants to be** (`:819-821`):
  `shotLocationTemp = composite.getShotLocation(shot).addWithRotation(adjustedNozzleLocation
  .subtractWithRotation(wantedLocation))`. The shot's place in the composite, moved by however much the
  nozzle location has been adjusted since the composite was designed, rotated through the same
  difference - so a second pass aims at where the first pass says the part is, not where the machine
  drawing says it is.
- **At what Z** (`:822-827`): when displacement is enabled the shot Z becomes
  `machine.getBalanceLevel()`, an absolute machine Z, deliberately via the `Length` variant.
- **To move or not to move** (`:838-880`):
  - further from the shot location than `camera.getRoamingRadius() + nozzleTip.getMaxPickTolerance()`
    → go there at safe Z, and there is no displacement at all;
  - otherwise, if allowed, `distance < displacement-max-distance` **and** `zDifference <
    displacement-max-Z-diff` **and** `|IEEEremainder(currentRotation - shotRotation, 360)| <
    displacement-max-rotation` → do not move, and remember
    `displacement = shotLocation - currentLocation` in X/Y;
  - otherwise move exactly to the shot location.
  So Z and rotation are never compensated, only gated, and a difference that is too big is simply moved
  instead of being trusted. Nothing warns, because nothing has to.
- **Recorded per shot** (`:878`): `pipeline.setShotDisplacement(pipeline.getCurrentShotIndex(),
  displacement)`, which is what `74cb7cc892` was about - one shot's residual belongs to that shot and to
  no other.
- **Spent once, in one place** (`:344-351`): `nozzleLocation` starts out as `wantedLocation`
  (`:319-321`) and the residual is subtracted from it, once, before the offsets are computed.

`log_M.txt` is the evidence that this works: two shots in one batch produced `-0.115867` and
`-0.115451`, four tenths of a micrometre apart. That is what a measured number does; §1 shows the
parallel term producing a constant instead.

The parallel path is the same idea with one difference that decides everything: the sequential path is
standing where it measured, in the same thread, at the same instant, and it may still decide to move.
The parallel path has already left, on another thread, and cannot move at all - hence the recording the
contract asks for, and hence D5 and D10 replacing the `nozzle.moveTo()` escape with a refusal.

## 6. Decisions

| | |
|---|---|
| D1 | Displacement means the residual of a shot. The predicted per-camera term is retired, not corrected, because there is nothing to correct it *with* - the quantity it tried to predict is measurable directly. |
| D2 | The runout calibration goes back to being only runout. Acceptance is not "the centres get small" - a tip mounted off-centre legitimately has a centre - but "storing a displacement, and then recalibrating a tip, does not move the measured displacement". |
| D3 | The camera `<displacement>` stays, and stays out of the shot: it belongs in `getCalibratedHeadOffsets()` where it is now, so that every commanded position knows it. |
| D4 | No advanced camera calibration and no tip calibration between writing a displacement and a run that claims to verify it. Both absorb it; `setDisplacement()` itself wipes the tip calibrations of the whole machine. |
| D5 | Exceeding the limits on a pre-captured shot stops being a warning: the shot is refused and the batch goes sequential, which is what `skipMovement=false` is for on the sequential path. *Implemented 2026-10-08* as `ReferencePnpJobProcessor.checkDisplacementLimits()`, called before PASS 1 of `preCaptureAllImages()` and answered in `alignAllParallel()`; see stage 4c. |
| D6 | One setting (`displacement-legacy-term` in `machine.xml`; the serializer hyphenates property names, `Configuration.java:1556`, default now `false`) keeps the old term alive for a machine that wants it back, and is deleted in Stage 4d. Implemented 2026-10-07 defaulting to `true`, run on `pn52` both ways the same evening, and flipped to `false` on the strength of that run: the identity `wanted - term = where the head actually was` is now demonstrable in a job log, which is as far as this can be verified without a way to measure a placed part. *Deleted 2026-10-08 (stage 4e)*, together with `displacementDelta()`, the hand-written copy of it in the settle path, the `isFirstNozzleTip` argument that only it used, and `PreCapturedPlacement.intendedDisplacementDelta`, which the measured residual had made a duplicate of a duplicate. One consequence is worth knowing before deleting a persisted setting: Simple XML is strict, `Persister`/`AnnotationStrategy` and no tolerance for strangers, so a `<displacement-legacy-term>` left in a `machine.xml` makes the machine fail to load with *"Element 'displacement-legacy-term' does not have a match in class ... ReferenceMachine"* rather than being ignored. Any machine that ran the branch has to have the line removed by hand - `pn52`'s is line 4849. |
| D7 | The performance machinery is out of scope and untouched: pre-check, grab-ahead ring, early departure, settle window. Where they pass the field along, only the name changes. |
| D8 | The batch head position stays "move for the first placement" (`ReferencePnpJobProcessor.java:2392-2419`). The mid-range compromise is a *later* optimisation, because with an honest delta it changes only how much of the tolerance is spent, not whether the result is right. |
| D9 | The word "displacement" is never written again without a qualifier: `cameraDisplacement` for the stored machine property, `shotDisplacement` for the residual. The XML names stay as they are - they are persisted, and `agent1`'s config uses them. |
| D10 | Post-rotate and parallel do not mix, so the parallel path refuses and the batch goes sequential rather than warning and continuing (E6). This is a decision, not an open question: the post-rotate path returns offsets relative to the camera centre and never has a nozzle location to apply a difference to (`:1079-1091`), so there is nothing for the term to be right about. *Implemented 2026-10-07.* Note what that means for a machine that ships the OpenPnP way, with `pre-rotate="false"`: the parallel alignment is then refused outright and the machine works its way through the cameras one part at a time. That is the correct outcome - the alternative is a part placed by the head's displacement - but it is invisible from the floor apart from speed, so the refusal says what to do about it (turn Pre-Rotate on) rather than merely that it happened. `pn52` is `pre-rotate="true"` with no per-part override, so it is unaffected: the 2026-10-08 09:32 run on the
refusal build reported `attempts=4 fell back=0 frames refused=0`, no refusal line, and 17.1s/1682.6 CPH, the
same as the two runs before it. |
| D11 | The settle design of §4 is not part of this change. What changes is what is written down at `:2347-2376` and `:1811`; where the settling, the epoch and the content bound pass a field along, only the name changes. |
| D12 | The pre-exposure check compares XY and Z and does not compare rotation (D5's limits, stage 4c). Nothing turns a nozzle *during* a batch: the turning is a step of its own, `PrerotateAllNozzlesForAlign`, which runs before the head goes to the cameras and - because it locates every nozzle with `AlignLocator`, not only the one being moved for - holds all of them at the rotation their placements are to be placed at through the exposure. A rotation difference at the exposure moment could therefore only report a machine that had failed to execute a move it had already been told to make, which is not what `displacement-max-rotation` is for - on the sequential path it is the difference between the part's measured angle and the angle it was commanded to, which no batch measurement can stand in for. Since E7 was fixed the recorded shot `wanted` carries the same rotation the pre-rotation step commands, so the comparison would at least be between two real numbers now; it is still not made, for the reason above. The original code compared the placement rotation against a `wanted` of 0 and got a number out of the difference between a rotation and nothing. |
| D13 | A head whose nozzles have Z axes of their own cannot put the second nozzle at the height the shots are taken at, so it now fails the Z limit and works its way through the cameras one part at a time (stage 4c). Before, it photographed that part from wherever its Z axis happened to be - out of focus and at the wrong magnification - and placed from it without a word, which is the class of error this whole plan is about. Driving every independently movable nozzle to the balance level before the batch is the follow-up that would give such a head its parallel alignment back; on `pn52` it is not needed, because both nozzle heights come off one motor and the balance level is exactly where they agree. |

## 7. Questions to answer on the machine, not in the code

The questions are now a script rather than a description of one: `scripts/BatchResidualCheck.bsh`,
deployed to `pn52:~/.openpnp2/scripts/` (md5 `e565205a338e602edebe2409c8d1f430`, 2026-10-07). It writes
nothing - no `machine.xml`, no calibration, no settings - moves only at safe Z, lifts the tips when it is
done, and needs the machine enabled and homed. A run is a couple of minutes and can be repeated, and
`scripts/ScriptHarness.java` checks it offline, through the same scripting engine, before it is deployed.
What each part prints, and what it settles:

- **Q1 - the runout at the shot angle.** Each nozzle is commanded to `camera.getLocation(nozzle)` at its
  calibration Z - a location that already contains the runout circle centre, so a tip that obeys its
  model should stand still - and then turned through 0/90/180/270 with the tip measured by
  `measureTip()` at every angle. The answer is the one line "the tip walked *x* mm over the cycle": near
  zero means the motion applies the runout at the angle and the analysis must not add it again; a walk of
  twice the circle's amplitude means it does not, and then the analysis owes it to *every* nozzle, the
  one the head was moved for included.
  *Answered 2026-10-07, both tips calibrated: the motion does apply it.* `ReferenceNozzle
  .toHeadLocation():519-527` subtracts the runout at the rotation being commanded to and
  `toHeadMountableLocation():533-543` adds it back into the report, and the machine agrees: turned through
  four right angles, N1's tip stood a mean of 0.0331mm off where it was commanded and N2's 0.0373mm,
  against runout circles that would have spread them 0.2447mm and 0.2893mm uncorrected, and against models
  that were themselves fitted to a peak error of 0.023mm and 0.022mm. Every detection is a multiple of the
  0.0133mm pixel, so this is as fine as the machine can say it. The analysis must therefore **not** add the
  runout again, neither for the nozzle the head moved for nor for the one it did not - and
  `ReferenceBottomVision.java:2356-2362` is adding exactly that. Numbers in `MACHINE-TEST.md`.
- **Q2 - coupled siblings.** One nozzle is dragged to two places and every other nozzle's
  `getLocation()` is compared with where the head geometry puts it
  (`target + headOffsets₂ - headOffsets₁`). A few thousandths is rounding between the axis coordinates
  and the offsets; hundredths means the whole batch rests on a position that is invented.
  *Answered 2026-10-07, with a wrinkle that matters.* Uncalibrated, 0.0000mm at both probe locations. With
  both tips calibrated, a constant 0.0363mm for the nozzle that was not moved, which is not the head
  flexing but the report being a **tool** position rather than an axis: two rigid nozzles report each other
  apart by the difference of their runout offsets at the rotations they stand at, and that difference is
  constant because the two circles keep their relative phase. The head is rigid and the batch's assumption
  holds, but a few hundredths of "gap" between nozzles is bookkeeping rather than something to close.
- **Q3 - the batch.** The head is moved once for each candidate primary, because the planner may pick
  either, and for every nozzle the run prints: where it is, where its own shot would have wanted it, the
  gap between the two, the term the code applies today (centre plus runout at the rotation it is
  actually at), what its camera really sees, and the leftover `gap + seen`, which is what the model
  cannot explain. The rotation each nozzle is off by, against `displacement-max-rotation`, is on the same
  line. Two caveats read into it: the sign is asserted, not measured, so a leftover of about twice the
  gap means the sign is the other way round rather than that the machine is badly off; and a tip further
  than the offset threshold (0.5mm) from where it was expected is refused by the vision and reported as
  "nothing measured", which is itself the answer to a question the code never asks.
  *Answered 2026-10-07, and the term is the wrong one.* For the nozzle the head had not been moved for,
  with the head moved for the other one: gap 0.2129mm, its own camera seeing 0.1929mm of it, **0.0414mm
  left over**, and the term the code applies to it 0.1792mm. The primary's own leftover is 0.0186mm. A
  non-primary nozzle's bottom vision already knows almost all of the position the batch left it in, so
  the legacy `centre + runout` term charges it for an offset that has been measured and is already in the
  picture, which is the 0.2mm that started this. Both head positions and both nozzles, 0.0414mm and 0.0326mm, read
  twice each with identical results, against primaries at 0.0045mm and 0.0186mm.
- **Q4 - post-rotate.** Answered by D10 rather than by the machine: there is nothing to measure, because
  the post-rotate path has no nozzle location for a difference to be applied to.
- **Q5 - what the residual costs in microns.** Still open and still not answered by this script, which
  measures bare tips. Parts on the tips change what is measured, and the only end-to-end proof is
  placing something and measuring it, which is a project of its own (down-looking camera, gauge, and a
  way of saying "this placement is 0.2mm off" that is better than the thing it is testing).

## 8. Stages

- **4a0 - run the probe.** `BatchResidualCheck.bsh` on `pn52`, output into `MACHINE-TEST.md`, before any
  of the below is written. It costs a couple of minutes and writes nothing; 4a's logging is designed
  around whatever Q1 says, so running it afterwards means designing the logging twice.
  *Run on 2026-10-07 with both tips calibrated: Q1, Q2 and Q3 all answered, and one thing found that was
  not asked for.* One run had the first tip looked at in each head position disagree with itself by 0.33mm across its
  three frames, and the same tip twenty-eight minutes later, with nothing moved on the machine, read 0.0126
  and 0.0000. It did not reproduce, so it is not a settle finding; it is the detector occasionally locking
  onto something that is not the tip, three frames in a row agreeing on the wrong thing, and `measureTip()`
  accepting a single unsettled frame (`ReferenceNozzleTipCalibration.java:1015-1021`). The probe now prints
  every detection separately and measures twice with nothing moved between, so an unrepeatable reading shows
  up as one. Numbers and reasoning in `MACHINE-TEST.md`.
- **4a - record, do not decide.** Store `actualAtCapture` in the shot and log, per shot, both the
  legacy predicted term and the residual that the recorded location implies, side by side. Nothing
  uses the new number yet. Acceptance: the logged residual explains `log_M.txt`'s constant `0.201657`
  from the geometry, and says what the difference is per nozzle. Until this has run on a job, the rest
  of the plan is arithmetic on assumptions.
  *Code in place 2026-10-07, never yet run on a job.* A `PreCapturedPlacement` now carries the position
  the machine reported for its nozzle at the moment of exposure (`setActualAtCapture()`, read the instant
  the frame is in hand, and stated next to the frame's provenance because a grab-ahead frame is exactly
  the one where the two disagree by design), and `getShotDisplacement()` turns wanted-minus-actual into
  the XY residual in millimetres, the same quantity the sequential path records against the pipeline
  shot. All three capture paths record it, every capture writes one line putting the legacy term and the
  measured residual side by side with the distance between them, and the line the analysis writes about
  the term it is using now says what was measured too. Nothing subtracts the new number: the applied term
  is untouched, so a job run with this is a measurement rather than a change. `PrecapturedShotDisplacementTest`
  pins the sign, the units and the XY-only-ness, and the existing 25 tests are unchanged. To satisfy the
  acceptance: rebuild, restart `pn52` (it runs the jar off NFS and is running now), run the job that made
  `log_M.txt`, and grep `Displacement of part`.
  *Run on a job 2026-10-07 18:52*: eight `C0402-4n7/50V` parts, four parallel batches, 1686 CPH. The two
  numbers disagree by 0.283mm to 0.398mm on every shot of the nozzle the head was not moved for, and agree
  to 0.003mm on the one it was moved for. The applied term is the same constant in all four batches while
  the measured residual moves 0.14mm in Y as that nozzle's C turns, which is E7 happening on a live job;
  and the residual's magnitude matches what the probe measured of the same tip in the image to a couple of
  microns, which is what 4b's switch rests on. Two things came of it that were not in the plan: the
  predicted term cannot be put right by correcting its formula, because the rotation it would need is
  nowhere in the shot, and E9, a negated Z on the second nozzle, which is a 0.87mm answer against a 0.1mm
  limit and therefore squarely in 4c's way. Table and reasoning in `MACHINE-TEST.md`, "Stage 4a: the two
  answers, side by side, on a real job".
- **4b - switch the term** behind D6, delete `isFirstNozzleTip` from the analysis path, and let the
  sequential and parallel paths share the one subtraction at `:344-351` instead of competing at
  `:334-341`.
  *Code and machine run done 2026-10-07.* One method, `ReferenceBottomVision.displacementTerm()`,
  is now the only place the parallel path decides what a shot is displaced by: it computes the
  prediction (the old `displacementDelta()`, kept whole), computes the residual of the shot
  (`PreCapturedPlacement.shotDisplacement()`, the same wanted-minus-actual the sequential path records),
  picks one by `isDisplacementLegacyTerm()`, and writes a line naming the term it used, the other answer
  and the distance between them. The three capture sites now call it instead of each carrying its own
  arithmetic - which also deleted the block at `:2347-2376` early, since it was the same prediction
  written out by hand a second time. The term is decided *after* the frame at all three sites, because
  half of it is not knowable before, and the analysis has one subtraction for both paths rather than two
  branches that could disagree. `isFirstNozzleTip` survives only as an input of the prediction, which is
  what 4d removes together with the prediction. Offline: `ParallelBatchResidualTest` is enabled and green,
  both nozzles handed `(-0.415, 0.156)` and `(0, 0)` with the setting off, and the second test pins the
  prediction `(0.275, 0.024)` while it is on; 363 tests, 0 failures, 0 skipped. What is *not* offline:
  whether the parts land right, which needs the same job run both ways and, past that, the gauge of Q5.
  *Run on `pn52` at 20:38*, the setting written into `machine.xml` explicitly - the default was flipped to
  `false` afterwards, on the strength of this run*: eight parts, four batches, 1661.6 CPH
  against the 1686 CPH the same job made on the old term an hour earlier, and for the nozzle the head was
  not moved for the analysis was handed `wanted - residual`, which comes out as the position the head was
  actually in when the frame was exposed. The 18:52 run above is the same job on the old term, so the
  two-way diff §9 asks for exists, and it is 0.27-0.32mm on every second nozzle's shot. The run also
  produced E8 for real - the batch moved for the *other* nozzle than last time, and the mismatch came out
  the negative of Stage 4a's to a micron - and showed that E9's negated Z travels with whichever nozzle was
  not moved for rather than with the one called N2. Numbers in `MACHINE-TEST.md`, "Run: 2026-10-07 20:38".
- **4c - make the limits real** (D5). `pn52`'s `machine.xml` turns out to carry them already -
  `displacement-enabled true`, `0.5`, `0.1`, `0.01`, the same as `agent1`'s - which corrects what an earlier
  draft of this plan said. So the stage is not writing settings but finding out what they let through: the
  Q3 numbers say what the limits would have refused, which is the only way to pick them without guessing,
  and a 0.15mm gap inside a 0.5mm limit is admitted by a machine with no way of knowing the gap is not a
  camera displacement.
  *Implemented 2026-10-08.* Two things had to happen for the numbers to be actionable, because a batch
  moves no nozzle onto its own shot and therefore has no moment at which a limit could be skipped:
  - the shot is taken at the machine's balance level. `ReferenceBottomVision.withBalanceLevelZ()` is now
    the one place that says so, used by the sequential pipeline shot (where the same thing was written out
    inline) and by the batch: the head's alignment Z and every single-shot shot location of a batch. The
    multi-shot pass is left at its own height, because there the nozzle is moved for the part anyway. The
    balance level is a `Length` and is applied as one; read as a bare double it would be taken in the units
    of the shot location, which on an inch machine is 10.7mm on a 0.1mm tolerance.
  - `ReferencePnpJobProcessor.checkDisplacementLimits()` asks each planned nozzle, after the head has
    arrived and *before* PASS 1 exposes anything, how far it stands from its own shot, and throws
    `DisplacementExceededException`; `alignAllParallel()` answers by counting one fallback and aligning
    every part of the batch the ordinary way. Nothing is exposed, so the whole cost is the move that was
    already made. It runs inside the `try` of `preCaptureAllImages()` so the ring and the camera batch
    operation are still released.
  XY is the linear distance, Z the absolute difference, as in the original code
  (`docs/original_bottomvision/ReferenceBottomVision.java`), where the same two numbers decide between
  skipping the move and making it. Rotation is deliberately *not* compared (D12). `agent1` is no guide
  here: it declares `getDisplacementMaxZDiff()` and never reads it, and checks XY only, after the pipeline,
  as a `Logger.warn` - the caption this stage replaces.
  Offline, `DisplacementLimitRefusalTest`: a machine within its limits still goes parallel (so the check is
  a limit and not an off switch); a second camera bumped 1mm sends the batch sequential with the distance
  named; a head whose second nozzle cannot be brought to the height of the shots is refused rather than
  photographed out of focus; and `withBalanceLevelZ()` keeps the balance level's own units and touches
  nothing else. 370 tests, 0 failures, 0 skipped. What is *not* offline is the focus question: the batch
  now shoots at the balance level where it used to shoot at each camera's focal height, which the
  simulation cannot feel and `pn52` can.
  *Run on `pn52` at 11:12, 2026-10-08*: eight parts, four batches, `attempts=4 fell back=0 frames refused=0`,
  no `WARN` or `ERROR`, and every shot at Z `0.000000` on both cameras - so the balance level is a height this
  machine photographs at, and E9's `-0.433` is gone from the log because both nozzles are now at the one
  height instead of one at the other's negation. The XY residual the analysis is handed is the same
  `(0.214076, 0.132842)` it was before the height moved, to within 30nm, which is the evidence that the two
  changes are as orthogonal as the plan assumed. Numbers and the comparison with the two earlier runs in
  `MACHINE-TEST.md`, "Stage 4c".
- **4d - refuse what cannot work** (D10): post-rotate in a parallel batch goes sequential instead of
  warning, and E7's rotation of `0.` becomes the rotation the shot is taken at, so that the recorded
  `wanted` describes the frame it is applied to.
  The refusal is done (2026-10-07): `alignAllParallel()` consults each planned part's own inherited
  vision settings through the one predicate `ReferenceBottomVision.usesPreRotate()` - which is now what
  the two pre/post branches in `findOffsets*` ask too, so the batch and the alignment cannot disagree
  about how a part is aligned - and a part that is post-rotate, whether by the machine default or by
  its own `Pre-Rotate Usage = AlwaysOff`, takes the whole batch sequential. It is not counted as a
  parallel attempt or a fallback, because it is a route not taken, and it is tested as one
  (`PostRotateParallelRefusalTest`: pre-rotate control must go parallel, machine-default post-rotate
  must not, one `AlwaysOff` part must refuse the batch of an otherwise pre-rotate machine).
  E7 followed the same day, and it was not only a caption. `ReferenceBottomVision.wantedRotationOf()`
  computes the rotation a placement is shot at - the placement rotation transformed by its board,
  normalised to the -180..180 the analysis compares in - and `shotRotationOf()` makes it a decision about
  the frame: that rotation for a pre-rotate part, `0.` for a post-rotate one, because a post-rotate
  analysis genuinely photographs the part at whatever angle the nozzle happens to hold it. Every site now
  asks: the head's alignment move, `getLocation()`, the async single-shot captures, the settle-window
  captures, the multi-shot pass and the pre-exposure check. Two things fell out of doing it properly. The
  head's alignment move had used the *untransformed* `placement...getRotation()`, so on a board with a
  rotation of its own the head carried the whole batch to an angle no part is placed at - a wrong `wanted`
  in the one place a `wanted` still commands hardware. And the reason the batch could ever claim a
  rotation of 0 without the picture disagreeing is the pre-rotation step (D12): the nozzle is turned for
  its alignment whether or not it is the one being moved, which is what makes the recorded rotation and
  the photographed one the same thing.
  Offline, `ShotRotationTest`: a 30° placement on a board rotated 90° is announced as 120° and is the very
  location `getLocation()` hands the head; a post-rotate part is announced as 0°; the normalisation is the
  analysis's, not a modulo nobody agrees on.
- **4e - delete the old term**, the D6 setting, and the duplicated block at `:2347-2376`.
  *Implemented 2026-10-08.* Out went `displacementDelta()` and its hand-written twin in the settle path, the
  `displacementTerm()` that chose between the two answers, `isFirstNozzleTip` through
  `captureImageOnly()`/`capturePreparedFrame()`/`captureImageFromSettlingWindow()`/
  `captureImageOnlyWithSettle()` and the `firstNozzleTip` that fed it in the job processor,
  `PreCapturedPlacement.intendedDisplacementDelta` with its constructor argument, and
  `displacement-legacy-term` from `Machine` and `ReferenceMachine`. What the analysis is handed is now
  `getShotDisplacement()`, one quantity, from both paths, subtracted in one place.
  Deleting the prediction also deleted its escape route: a shot whose nozzle position was never read used
  to fall back on the prediction and be analysed anyway. It cannot fall back to nothing without being
  analysed as if the nozzle had been exactly on its location, which is a wrong answer of the whole camera
  offset, so `requireDisplacement()` now refuses the shot and the batch goes sequential like any other
  refused frame.
  The machine has to be told: see D6, a leftover `<displacement-legacy-term>` is a load failure.
  Offline, 372 tests, 0 failures, 0 skipped; `ParallelBatchResidualTest` is one test rather than two, since
  the second existed to pin a number that no longer exists, and its claim - the prediction is 0.70mm off on
  the nozzle the head was not moved for - is written into the surviving test's javadoc instead.
  *Run on `pn52` at 12:23, 2026-10-08*, after that line was deleted from `machine.xml`: eight parts, four
  batches, `attempts=4 fell back=0 frames refused=0`, no `WARN`, no `ERROR`, 17.3s. The capture line now
  carries one answer and the shot's own rotation, and the identity `wanted - measured = nozzle actually at`
  prints true to the last digit on both nozzles; the rotation on a nozzle the head was not moved for matches
  the angle the pre-rotation step holds it at (D12), including the `-0.121783°` board correction that the
  head's alignment move used to ignore. The residual is where the three earlier runs put it, to 140nm.
  Numbers and the four separate claims to be drawn from them in `MACHINE-TEST.md`, "Run 2026-10-08 12:23".
- **4f - documents** - **done 2026-10-08.** `MACHINE-TEST.md` carries the runs of 4a0 through 4e, each
  with its numbers and what they do and do not prove; `README.md` says where the displacement work
  stands now rather than where it stood when it was audited wrong; `displacement-correction-plan.md`
  is superseded with a pointer here and the reason (the term it set out to correct is deleted, not
  corrected); and Stage 4 of `camera-displacement-plan.md` says what happened to its own three bullets
  rather than pretending to still be a plan. Nothing here is a plan any more except Q5.

## 9. Tests

Unit, on the simulated machine, no hardware: build a two-nozzle two-camera head, inject a known
camera error and known circle centres, and assert that the term the analysis uses equals the injected
residual **for both nozzles**. Today's term fails that on both counts: it is 0 for the first when the
truth is `-e₁`, and `C₂` for the second when the truth is `C₂ - C₁ + (H₂ - H₁) - (S₂ - S₁)`. That test
is the whole argument of this document in fifteen lines of code, and it should be written before 4b,
against 4a's logging, so it fails first for the right reason. *Written, run once failing, then
`@Disabled`: `ParallelBatchResidualTest`. Enabled again by 4b, and green.* Two nozzles and two up-looking
simulated cameras whose spacing is deliberately 0.335mm away from the mirror of the nozzle spacing, circle
centres and radii set, the head moved once for N1 exactly as `moveNozzlesToAlignmentPosition()` does it.
It failed where the plan said it would, on the nozzle that was not moved for: truth `(-0.415, 0.156)`,
term handed to the analysis `(0.275, 0.024)`, off by 0.7025mm against a two-pixel tolerance. It was
disabled rather than left red because nothing may subtract the honest residual until 4b, and its
`@Disabled` text said so with the numbers in. It now has two tests: one with `displacementLegacyTerm`
false, where the term handed over equals the residual for both nozzles to within two pixels, and one with
the shipped default, where the term is the prediction and is wrong by more than ten pixels - so the
fallback is a measured quantity up to the stage that deletes it, rather than a rumour.

That run also corrected two things this section had asserted, and both matter more than the test:

- **a camera that is not where the model says it is is not a shot displacement, and no displacement can
  compensate it.** `SimulatedUpCamera.setErrorOffsets()` turns out to move the *drawn part*, not the
  camera, so it simulates a part sitting off-centre on the tip; injected on the nozzle the head was moved
  for, it changed neither `getActualAtCapture()` nor `getShotDisplacement()` by one bit, and the primary's
  truth stayed `(0, 0)` rather than becoming `-e₁`. That is not a limitation of the test: an error in where
  the camera is believed to be is exactly what bottom vision exists to measure out of the image, and it is
  the reason `wanted` is computed through the camera's own calibrated head offsets in the first place (D3).
  `-e₁` belongs to the camera's displacement, is settled there, and must not be asked of the batch.
- **the sign.** `C₂ - C₁ + (H₂ - H₁) - (S₂ - S₁)` above is the negation of §3's `wanted - actual`, which is
  what §3 and the contract mean and what the test asserts. Q3 warned that this sign had been argued rather
  than measured; here it is measured, in both directions, on a machine and in a test, and the contract's
  version is the one that comes out.

What exists alongside it is the smaller thing the injected test rests on:
`PrecapturedShotDisplacementTest` pins the sign convention of the measured residual (subtract what the shot
reports from where the shot wanted to be and one lands on where the nozzle was), without which the injected
test would only prove that two wrongs agree. `ShotDisplacementTest` (from`74cb7cc892`),
`ParallelAlignmentTest`, `ParallelAlignmentLightControlTest`, `ReferenceBottomVisionOffsetTest` and
`NozzleTipCalibrationMultiCameraTest` stay green throughout; `GrabAheadCaptureTest` and
`SettleWindowCaptureTest` guard D7. The two refusals have their own tests, because a route not taken is
otherwise invisible: `PostRotateParallelRefusalTest` (D10, stage 4d) and `DisplacementLimitRefusalTest`
(D5, stage 4c), each with a control that must still go parallel, so that neither can pass by nothing ever
being attempted.

Machine, in order: 4a0 (the probe, no job needed); then 4a on the job that produced `log_M.txt`; then
4b/4c on the same job with the legacy setting on and off, diffing the logged terms; then the displacement
check again, which should still say about 0.01mm left over, *including after a tip recalibration* - that
last one is the proof that D2 happened.

## 10. Not changed

The pre-check and its fail-open policy, the grab-ahead ring and early departure, the settle window,
the pipelines, the compositing, the `<displacement>` storage and its write path, the check itself, and
`displacement-enabled`'s meaning for the sequential path.

## 11. Risks

- **The parallel path has never been shown to place a part correctly.** It completes jobs, which is not
  the same thing and is not evidence: nothing has measured what it delivers. A 0.2mm error is a failure
  on 0402 and on anything with pitch, however tidy the job log looks. So the acceptance of this whole
  change is a gauge measurement (Q5), not a finished job, and no stage of the plan should be called
  validated without one.
- Changing the term moves every parallel placement at once, possibly by ~0.2mm, in a direction nobody
  has measured yet. Hence 4a0 and 4a before anything, the fallback setting of D6, and one gauge before
  production.
- Two of the numbers here (the 0.502mm spacing mismatch and the 0.2mm audit) are computed from stored
  values and one uncommitted analysis, not measured end to end. 4a measures them.
- E8 makes the reference nozzle depend on `HashMap` iteration order, so two runs of the same job can put
  the entire static error on opposite cameras. Any A/B comparison of two logs has to check which camera
  settled before it concludes anything from the difference.
- E7 means a term measured at C = 0 does not transfer to a placement at an angle: whatever the probe and
  4a find is valid for the rotations they were run at, which is why the probe turns the nozzles at all.
- The probe measures bare tips. Parts on the tips move the very quantities it measures, so its numbers
  describe the machine and not the pick; the two are only tied together by Q5.
- Q1 can turn out to mean the first nozzle is currently *over*-corrected rather than under-corrected,
  which changes the sign of the improvement but not the shape of the plan.
- Each validation run costs a tip recalibration (a few minutes) because of D4.
- **Stage 4c moved the batch's shots to the balance level**, which is a focus change as well as a
  displacement change: the cameras used to be shot at their own focal heights and are now shot at one
  height for the whole batch, which is what the original code did and what a rocker head can be at twice
  at once. If the balance level on `pn52` had not also been the height at which both cameras see sharply,
  the symptom would not have been a refusal but a pipeline that fails to find a part. *The 2026-10-08 11:12
  run answers this for `pn52`, in the weak sense that every pipeline found every part at the balance height
  on both cameras.* It is not answered for any other machine, and the strong sense - that the parts land
  where the numbers say - is still Q5.
- A machine whose nozzles have their own Z axes now goes sequential on every batch (D13) instead of
  photographing the second part from wherever its Z axis happened to be. That is the correct outcome and
  it will look like a regression to whoever notices that the machine got slower: the refusal names the
  nozzle, the height it is at and the height it was asked for, so it can be told apart from a machine that
  has developed a real focus problem.
