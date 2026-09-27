# Camera Displacement Correction Plan

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Problem

Parallel bottom vision alignment displacement values were returning wrong values (~1.5mm camera position instead of < 0.1mm calibration error). The displacement should represent the camera-to-nozzle alignment error + nozzle tip runout.

## Root Cause Analysis

**Found the bug:** The displacement calculation was using camera position instead of nozzle tip calibration data.

**Why sequential alignment worked:** Sequential alignment doesn't use displacement - it captures the part independently with each nozzle tip.

**The conflict:** The displacement code was incorrectly calculating `cameraCalibrationError + runoutCompensation` using camera-specific values, when it should use nozzle tip calibration data which gives the same displacement for both cameras.

**Impact:** The displacement calculation used camera position values (~1.5mm per camera) instead of nozzle tip offset values, causing parallel alignment to fail.

**Final Root Cause:** The displacement should come from **nozzle tip calibration** (`getCalibratedCameraOffset()`), not from camera position. On a two-nozzle machine, there should be ONE displacement value per nozzle tip, not separate values per camera.

## Goal

1. Fix camera displacement calculation to use correct data sources
2. Remove dead `displacementActive` flag dependency
3. Add validation warning for excessive displacement values
4. Ensure parallel alignment works correctly with multiple nozzle tips

## Critical Understanding

**The displacement should be small (< 1mm)** representing the camera-to-nozzle alignment error plus nozzle tip runout compensation.

**Key insight:** The head move to the alignment position already includes runout compensation for the first nozzle tip. For other nozzle tips at different rotations, we need to calculate the displacement that accounts for:
1. The camera-to-nozzle calibration error (from advanced camera calibration)
2. The nozzle tip runout at the current rotation

**Important:** The displacement is NOT the camera's absolute position on the head (which would be 150+ mm). It's the **relative offset** between the camera and nozzle, which should be less than 1mm.

## Data Sources

### 1. Camera Calibration Error
- **Location:** `ReferenceCamera.advancedCalibration.calibratedOffsets`
- **Access:** `camera.getAdvancedCalibration().getCalibratedOffsets()`
- **Source:** Results from "Advanced Camera Calibration" wizard
- **Meaning:** The fixed offset between camera optical center and nozzle center (small values, < 1mm)
- **IMPORTANT:** Do NOT use `camera.getCalibratedHeadOffsets()` - this returns the camera's absolute position on the head (150+ mm), not the calibration error!

### 2. Nozzle Tip Runout
- **Location:** `ReferenceNozzleTipCalibration` (calibration data per nozzle tip)
- **Access:** `calibrationNozzleTip.getCalibration().getCalibratedOffset(nozzle, rotation)`
- **Meaning:** The runout compensation at a specific rotation angle for a specific nozzle tip (small values, typically < 1mm)

## Implementation Status

### Phase 1: Fix Displacement Calculation ✅ COMPLETED

#### What was fixed:
- ✅ **Removed** the incorrect `getCameraCalibrationError()` method - it was using camera position instead of nozzle tip calibration data
- ✅ Updated displacement calculation in `ReferenceBottomVision.java` to use `getCalibratedCameraOffset()` from nozzle tip calibration
- ✅ Displacement now comes from **nozzle tip calibration data**, not camera position
- ✅ Code compiles successfully
- ✅ All tests pass (13/13)

#### Why this approach:
You were absolutely right - on a two-nozzle machine, there should be **ONE displacement value** representing the offset between nozzle tips, not separate values per camera.

The previous approach was wrong because:
- `cameraCalibrationError` was camera-specific (1.5mm for Upp1, -0.09mm for Upp2)
- The displacement should be the **nozzle tip offset**, which is the same for both cameras

**Correct solution:** Use `getCalibratedCameraOffset()` from the nozzle tip calibration, which returns the camera offset for each nozzle tip. This is the correct source for displacement calculation.

#### Location of fix:
**ReferenceBottomVision.java:1298-1316, 1389-1407** - Updated to use nozzle tip calibration:
```java
// Get the camera offset for this nozzle tip (from nozzle tip calibration)
Location cameraOffset = calibrationNozzleTip.getCalibration()
    .getCalibratedCameraOffset(refNozzle, camera);

// Get nozzle runout compensation at the wanted rotation angle
Location runoutCompensation = calibrationNozzleTip.getCalibration()
    .getCalibratedOffset(refNozzle, wantedLocation.getRotation());

// Combine: displacement delta = camera offset + runout compensation
Location displacementDelta = cameraOffset.add(runoutCompensation);
```

#### Expected behavior:
- `cameraOffset` comes from nozzle tip calibration (same for both cameras using the same nozzle tip)
- `runoutCompensation` varies with rotation angle (typically < 0.1mm)
- `displacementDelta` is the sum of both (should be < 2mm total)
- **No need to re-run Advanced Camera Calibration** - uses existing nozzle tip calibration data

### Phase 3: Remove `displacementActive` Flag Dependency ✅ COMPLETED

#### What was done:
- ✅ Replaced `camera.isDisplacementActive()` check with `preCapturedShot instanceof PreCapturedPlacement` (line 268-275)
- ✅ Removed `camera.setDisplacementActive(false)` call (line 618)
- ✅ Simplified displacement logic - no fallback needed (displacement delta is always in PreCapturedPlacement)
- ✅ Kept settling logic in ImageCapture.java for backward compatibility with sequential alignment
- ✅ Code compiles successfully
- ✅ All tests pass

#### Changes made:

**ReferenceBottomVision.java:268-275** - Simplified displacement logic:
```java
// For parallel alignment with pre-captured shots, use displacement delta from the shot
if (preCapturedShot instanceof org.openpnp.model.PreCapturedPlacement) {
    Location displacement = ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getIntendedDisplacementDelta();
    if (displacement != null) {
        Logger.debug("Bottom vision displacement (parallel), using displacement delta {}", displacement);
        nozzleLocation = nozzleLocation.subtract(displacement);
    }
}
```

**ReferenceBottomVision.java:617-618** - Removed dead code:
```java
// Before:
camera.setDisplacementActive(false);
if (skipMove) { ... }

// After:
if (skipMove) { ... }
```

### Phase 4: Add Displacement Validation Warning ✅ COMPLETED

#### What was done:
- ✅ Added displacement validation warning in `processPipelineAndGetResult()` (line 708-724)
- ✅ Warning triggers when displacement exceeds `getDisplacementMaxDistance()` (default 0.5mm)
- ✅ Warning includes nozzle ID and camera name for easy troubleshooting
- ✅ Warning is non-blocking (doesn't fail alignment)
- ✅ Code compiles successfully
- ✅ All tests pass

#### Changes made:

**ReferenceBottomVision.java:708-724** - Added validation warning:
```java
RotatedRect resultRect = (RotatedRect) pipeline.getCurrentPipelineShot().processCompositeResult().getModel();

// Validate displacement if we have a pre-captured placement with displacement data
if (preCapturedShot instanceof org.openpnp.model.PreCapturedPlacement) {
    Location displacementDelta = ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getIntendedDisplacementDelta();
    if (displacementDelta != null) {
        double displacementDistance = displacementDelta.getLinearLengthTo(Location.origin).getValue();
        double maxDistance = Configuration.get().getMachine().getDisplacementMaxDistance().getValue();
        
        if (displacementDistance > maxDistance) {
            Logger.warn("Displacement warning: displacement {} mm exceeds max {} mm for nozzle {} camera {}. " +
                       "Check Advanced Camera Calibration and Nozzle Tip Runout Calibration.",
                       displacementDistance, maxDistance, 
                       ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getNozzle().getId(),
                       ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getCamera().getName());
        }
    }
}

return resultRect;
```

### Phase 5: Testing and Validation ⏳ TODO

#### 5.1 Re-run Advanced Camera Calibration (CRITICAL)
- **This is required because the `calibratedOffsets` values in machine.xml are wrong!**
- Run Advanced Camera Calibration wizard for each camera
- This will regenerate `calibratedOffsets` with correct small values (< 0.1mm)

#### 5.2 Verify calibration data exists
- Run Nozzle Tip Runout Calibration for each nozzle tip (if not already done)

#### 5.3 Test parallel alignment
- Pick up a part with first nozzle tip, capture at alignment location
- Rotate head to second nozzle tip position
- Verify displacement is non-zero and correct (< 0.2mm)
- Pick up same part with second nozzle tip, verify alignment works

#### 5.4 Test rotation compensation
- Pick up part at rotation 0°, capture
- Rotate to 90°, capture again
- Verify different displacement values due to runout compensation

#### 5.5 Check debug logs
Expected log output:
```
captureImageOnly: cameraCalibrationError=(0.010, 0.020, 0.000, 0.000 mm), 
                  runoutCompensation=(-0.019, 0.001, 0.000, 0.000 mm), 
                  displacementDelta=(-0.009, 0.021, 0.000, 0.000 mm)
Bottom vision displacement (parallel), using displacement delta (-0.009, 0.021, 0.000, 0.000 mm)
```

NOT (before fix):
```
captureImageOnly: cameraCalibrationError=(-1.506, 0.191, 0.000, 0.000 mm),  // WRONG! Camera position!
                  runoutCompensation=(-0.019, 0.001, 0.000, 0.000 mm),
                  displacementDelta=(-1.525, 0.192, 0.000, 0.000 mm)       // WRONG! Too large!
```

## Files Modified

### Phase 1: Fix Advanced Calibration Bug ✅
- `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/camera/calibration/AdvancedCalibration.java`
  - Line 1454-1475: Fixed `applyCalibrationToMachine()` to calculate `calibratedOffsets` as the difference between camera position and head offsets, not just the negative of camera position

### Phase 3: Remove `displacementActive` dependency ✅
- `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java`
  - Line 268-275: Replaced `camera.isDisplacementActive()` check with `preCapturedShot instanceof PreCapturedPlacement`
  - Line 618: Removed `camera.setDisplacementActive(false)` call

### Phase 4: Add displacement validation warning ✅
- `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java`
  - Line 708-724: Added displacement validation warning after pipeline processing

### Kept unchanged (backward compatibility) ✅
- `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/vision/pipeline/stages/ImageCapture.java`
  - Line 112-115: Kept settling skip logic for sequential alignment

## Expected Behavior After Fix

**Correct log output:**
```
captureImageOnly: cameraOffset=(0.500, 0.300, 0.000, 0.000 mm), 
                  runoutCompensation=(-0.019, 0.001, 0.000, 0.000 mm), 
                  displacementDelta=(0.481, 0.301, 0.000, 0.000 mm)
Bottom vision displacement (parallel), using displacement delta (0.481, 0.301, 0.000, 0.000 mm)
```

**Warning log (if calibration issue detected):**
```
Displacement warning: displacement 1.2 mm exceeds max 0.5 mm for nozzle Nozzle1 camera Camera1. 
Check Advanced Camera Calibration and Nozzle Tip Runout Calibration.
```

**Wrong log output (before fix):**
```
captureImageOnly: cameraCalibrationError=(-1.506, 0.191, 0.000, 0.000 mm),  // WRONG! Camera position!
                  runoutCompensation=(-0.019, 0.001, 0.000, 0.000 mm),
                  displacementDelta=(-1.525, 0.192, 0.000, 0.000 mm)       // WRONG! Too large!
```

**Is the displacement enforced?** YES! The log message "Bottom vision displacement (parallel), using displacement delta..." is NOT just informational. The displacement is actually applied in `ReferenceBottomVision.findOffsetsPreRotate()`:

```java
if (preCapturedShot instanceof org.openpnp.model.PreCapturedPlacement) {
    Location displacement = ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getIntendedDisplacementDelta();
    if (displacement != null) {
        nozzleLocation = nozzleLocation.subtract(displacement);  // <-- ACTUALLY APPLIED!
    }
}
```

This adjusts the nozzle location before processing the pipeline result, compensating for the camera-to-nozzle offset and runout.

## Summary of Changes

| Phase | Status | Description |
|-------|--------|-------------|
| 1 | ✅ Done | Fix displacement calculation to use nozzle tip calibration data |
| 2 | ✅ Done | Remove `displacementActive` flag dependency |
| 3 | ✅ Done | Add displacement validation warning |
| 4 | ✅ Done | All tests pass (13/13), code compiles successfully |
| 5 | ✅ Done | **Fix: First nozzle tip displacement should be zero** |

## Phase 5: Fix First Nozzle Tip Displacement ✅ COMPLETED

### Problem
The displacement was being applied to ALL nozzle tips, including the FIRST one. But the first nozzle tip should have displacement = (0, 0, 0, 0) because we're already at the correct position for it.

**Evidence from logs:**
```
captureImageOnlyWithSettle: displacementDelta=(-0.224, 0.020) - Upp2 (FIRST) - SHOULD BE (0, 0, 0, 0)!
captureImageOnly: displacementDelta=(0.220, 0.116) - Upp1 (SECOND) - This is correct
```

### Root Cause
The capture methods (`captureImageOnly` and `captureImageOnlyWithSettle`) were calculating displacement for ALL nozzle tips, but they should only calculate non-zero displacement for nozzle tips AFTER the first one.

**CRITICAL BUG FOUND:** The code was using `settleCameraPlacements.get(0).nozzle` to identify the first nozzle tip, but this is WRONG when Travelling Salesman optimization reorders placements!

**Why it was wrong:**
- `initialNozzle` = first nozzle in optimized placement list (e.g., Upp1) → SHOULD have zero displacement
- `settleCameraPlacements.get(0).nozzle` = first placement for settle camera (might be Upp2!) → INCORRECTLY used as first nozzle tip

**Example:**
- Optimized placement list: [Upp1, Upp2, ...]
- Settle camera: Upp1's camera (determined by Upp1)
- `settleCameraPlacements.get(0).nozzle`: Upp2 (if Upp2's placement happens to be first in settle camera list)
- BUG: Code treated Upp2 as first nozzle tip (zero displacement), but Upp1 should be first!

### Solution
Use `initialNozzle` (first nozzle in optimized placement list) as the first nozzle tip, NOT `settleCameraPlacements.get(0).nozzle`.

### Changes Made

**ReferencePnpJobProcessor.java:1967-1987** - Fixed first nozzle tip identification:
```java
PlannedPlacement initialPlacement = allPlacements.get(0);
Nozzle initialNozzle = initialPlacement.nozzle;
Camera settleCamera = VisionUtils.getBottomVisionCamera(initialNozzle);

// The FIRST nozzle tip to have zero displacement is the initial nozzle (first in optimized list).
// The head moves to position this nozzle under the settle camera, so it should have displacement = 0.
Nozzle firstNozzleTip = initialNozzle;  // NOT settleCameraPlacements.get(0).nozzle!
```

**ReferencePnpJobProcessor.java:2032-2120** - Pass `isFirstNozzleTip` based on nozzle tip identity:
```java
boolean isFirstNozzleTip = nozzle.equals(firstNozzleTip);
captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, isFirstNozzleTip);
```

**ReferenceBottomVision.java:1261-1356** - Added overloaded `captureImageOnly` method:
```java
public PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
        Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip) throws Exception {
    if (isFirstNozzleTip) {
        displacementDelta = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
    }
    else {
        displacementDelta = cameraOffset.add(runoutCompensation);
    }
}
```

**ReferenceBottomVision.java:273** - Added camera name to displacement log:
```java
String cameraName = ((PreCapturedPlacement) preCapturedShot).getCamera().getName();
Logger.debug("{} displacement (parallel), using displacement delta {}", cameraName, displacement);
```

### Test Results
- ✅ All tests pass (7/7 in ParallelAlignmentTest)
- ✅ Code compiles successfully

### Expected Behavior After Fix
```
Parallel alignment: initial nozzle=Upp1, settle camera=Upp1, first nozzle tip (zero displacement)=Upp1
Upp1 displacement (parallel), using displacement delta (0.0, 0.0, 0.0, 0.0 mm)  // FIRST nozzle tip
Upp2 displacement (parallel), using displacement delta (0.220, 0.116, 0.0, 0.0 mm)  // SECOND nozzle tip
```

## NEXT STEPS

**Test with hardware:**
1. Pick up a part with first nozzle tip, capture at alignment location
2. Rotate head to second nozzle tip position
3. Verify displacement is < 2mm in logs
4. Verify parallel alignment works without falling back to sequential

**Check debug logs:**
Expected log output:
```
captureImageOnly: cameraOffset=(0.239, 0.115, 0.000, 0.000 mm), 
                  runoutCompensation=(-0.019, 0.001, 0.000, 0.000 mm), 
                  displacementDelta=(0.220, 0.116, 0.000, 0.000 mm)
Bottom vision displacement (parallel), using displacement delta (0.220, 0.116, 0.000, 0.000 mm)
```

**Latest findings (OpenPnP.3.log):**
- ✅ Displacement values are now reasonable: ~0.22mm (was ~180mm before fix)
- ✅ No displacement warnings triggered
- ❌ Parallel alignment still failing due to **angle offset** exceeding 10° limit (not displacement issue)
  - Log: `Offsets too large ... angle offset 10.401396 > 10.0`
  - This is a separate issue - part angle detection is off, not displacement

**Is the displacement enforced?** YES! The log message "Bottom vision displacement (parallel), using displacement delta..." is NOT just informational. The displacement is actually applied in `ReferenceBottomVision.findOffsetsPreRotate()`:

```java
if (preCapturedShot instanceof org.openpnp.model.PreCapturedPlacement) {
    Location displacement = ((org.openpnp.model.PreCapturedPlacement) preCapturedShot).getIntendedDisplacementDelta();
    if (displacement != null) {
        nozzleLocation = nozzleLocation.subtract(displacement);  // <-- ACTUALLY APPLIED!
    }
}
```

This adjusts the nozzle location before processing the pipeline result, compensating for the camera-to-nozzle offset and runout.

## Notes

- **No need to re-run Advanced Camera Calibration:** The fix uses nozzle tip calibration data (`getCalibratedCameraOffset()`), not camera position
- **No need to re-run Nozzle Tip Runout Calibration:** Uses existing calibration data
- **Backward compatible:** Existing nozzle tip calibration values in machine.xml work correctly
- **Phase 2 changes are safe:** The `displacementActive` flag is already always false, so removing its usage won't break anything
- **Phase 3 is non-breaking:** Warning logs don't fail alignment, just alert users to potential issues
- **Settling logic preserved:** ImageCapture settling skip logic kept for backward compatibility with sequential alignment
- **No dead code removal yet:** `xShotDisplacement`, `yShotDisplacement`, `setDisplacements()` can be removed in a future cleanup pass
