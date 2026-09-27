# Parallel Nozzle Tip Calibration (NTC) with Camera Displacement

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Overview

Implement parallel NTC calibration for multiple nozzles that share the same nozzle tip type, while simultaneously calculating and updating camera displacement values for accurate parallel bottom vision.

## Understanding Key Concepts

### Camera Displacement vs Camera Head Offsets

**Camera Head Offsets** (lines 823-861 in ReferenceNozzleTipCalibration.java):
- The camera's position relative to the head in machine coordinates
- Updated during NTC when `calibrateCamera=true` for certain runout compensation algorithms
- Each camera has independent headOffsets
- Used to transform camera coordinates to head coordinates

**Camera Displacement** (xShotDisplacement, yShotDisplacement in AbstractCamera.java):
- The geometric offset between where the nozzle is when the head moves to a camera's position
- Critical for parallel bottom vision to work correctly
- Currently set manually in machine.xml
- Used during parallel alignment to adjust nozzle location (lines 267-284 in ReferenceBottomVision.java)

### The Problem

When multiple nozzles share the same head but have different bottom vision cameras:
1. Each camera-nozzle pair has a unique geometric relationship
2. During parallel bottom vision, the head moves to multiple camera positions simultaneously
3. The displacement values tell the system how to adjust for these different positions
4. Currently, these values must be manually configured - they're not automatically calculated

### The Opportunity

During NTC, we already measure the nozzle tip position relative to each camera. This measurement can be used to calculate the camera displacement:

```
displacement = -measured_offset
```

Where `measured_offset` is the offset detected by the camera when the nozzle is moved to the calibration location.

## Architecture

```
loadNozzleTip()
  └─> findOtherNozzlesNeedingCalibration()
       └─> If count > 1 AND count == cameraCount (1:1 mapping):
            └─> calibrateParallel()
                 ├─> Group nozzles by camera
                 ├─> Verify 1:1 mapping (nozzles == cameras)
                 ├─> ExecutorService with threadCount = cameraCount
                 ├─> For each camera group:
                 │    └─> calibrateSingle() WITH CameraBatchOperation
                 │         └─> Calculate displacement from measured offset
                 │         └─> Update camera.setDisplacements()
                 └─> Aggregate results, throw if any failures
```

## Implementation Details

### 1. Extract `calibrateSingle()` Method

**File**: `ReferenceNozzleTipCalibration.java`

Extract the calibration logic from `calibrateInBatch()` into a new method:

```java
private void calibrateSingle(ReferenceNozzle nozzle, boolean homing, boolean calibrateCamera, 
                             boolean updateDisplacement) throws Exception
```

**Key changes**:
- Remove CameraBatchOperation wrapper (caller will provide)
- Add `updateDisplacement` parameter to control displacement calculation
- After measuring nozzle tip positions, calculate displacement:
  ```java
  if (updateDisplacement && !calibrateCamera) {
      // Calculate displacement from the average measured offset
      Location averageOffset = calculateAverageOffset(nozzleTipMeasuredLocations);
      double displacementX = -averageOffset.getX();
      double displacementY = -averageOffset.getY();
      camera.setDisplacements(displacementX, displacementY);
      camera.setDisplacementActive(true);
      Logger.info("Set camera {} displacement to ({}, {})", 
                  camera.getName(), displacementX, displacementY);
  }
  ```

### 2. Implement `calibrateParallel()` Method

**File**: `ReferenceNozzleTipCalibration.java`

```java
public void calibrateParallel(List<ReferenceNozzle> nozzles, boolean homing, boolean calibrateCamera) 
    throws Exception
```

**Algorithm**:
1. Group nozzles by their assigned camera using `VisionUtils.getBottomVisionCamera()`
2. Verify 1:1 mapping: `nozzles.size() == cameraGroups.size()`
3. Create ExecutorService with thread count = number of camera groups
4. For each camera group:
   - Submit task to call `calibrateSingle()` for each nozzle in the group
   - Each task wraps its work in a CameraBatchOperation
5. Wait for all futures to complete
6. Aggregate results:
   - If any nozzle failed, throw exception with list of failed nozzles
   - If all succeeded, return

**Thread Safety**: Each camera group has its own CameraBatchOperation, preventing cross-camera interference.

### 3. Implement `findOtherNozzlesNeedingCalibration()` Helper

**File**: `ReferenceNozzle.java`

```java
private List<ReferenceNozzle> findOtherNozzlesNeedingCalibration(NozzleTip nozzleTip)
```

**Algorithm**:
1. Iterate through all nozzles in the machine
2. Filter by:
   - Nozzle is compatible with the given nozzle tip
   - Nozzle is not already calibrated for this nozzle tip
   - Nozzle is not the calling nozzle (include it separately)
3. Return list including the calling nozzle

### 4. Modify `loadNozzleTip()` Method

**File**: `ReferenceNozzle.java`

Around line 735-740, replace the single calibration call:

```java
if (withCalibration
        && this.nozzleTip.getCalibration().isRecalibrateOnNozzleTipChangeNeeded(this)
        && !this.nozzleTip.getCalibration().isCalibrated(this)) {
    
    List<ReferenceNozzle> nozzlesToCalibrate = findOtherNozzlesNeedingCalibration(nozzleTip);
    
    if (nozzlesToCalibrate.size() > 1) {
        // Check for 1:1 nozzle-to-camera mapping
        Set<Camera> cameras = nozzlesToCalibrate.stream()
            .map(n -> { try { return VisionUtils.getBottomVisionCamera(n); } catch (Exception e) { return null; } })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        
        if (nozzlesToCalibrate.size() == cameras.size()) {
            // 1:1 mapping - use parallel calibration
            Logger.debug("{}.loadNozzleTip() calibrating {} nozzles in parallel", 
                        getName(), nozzlesToCalibrate.size());
            this.nozzleTip.getCalibration()
                .calibrateParallel(nozzlesToCalibrate, false, false);
        } else {
            // Not 1:1 mapping - fall back to sequential
            Logger.debug("{}.loadNozzleTip() nozzles share cameras, calibrating sequentially", 
                        getName());
            this.nozzleTip.getCalibration().calibrate(this);
        }
    } else {
        // Single nozzle - use existing calibration
        this.nozzleTip.getCalibration().calibrate(this);
    }
}
```

### 5. Add `calculateAverageOffset()` Helper

**File**: `ReferenceNozzleTipCalibration.java`

```java
private Location calculateAverageOffset(List<Location> measuredLocations) {
    double sumX = 0, sumY = 0;
    for (Location loc : measuredLocations) {
        sumX += loc.getX();
        sumY += loc.getY();
    }
    return new Location(LengthUnit.Millimeters, 
                       sumX / measuredLocations.size(),
                       sumY / measuredLocations.size(),
                       0, 0);
}
```

## Camera Displacement Calculation

### Why This Works

During NTC:
1. We move the nozzle to `measureBaseLocation` (calculated from `camera.getLocation(nozzle)`)
2. The camera measures where the nozzle tip actually appears
3. If the nozzle tip is offset from camera center by (dx, dy), this means:
   - The head moved to position P
   - The nozzle is actually at position P + (dx, dy) relative to camera center
   - The displacement should be -(dx, dy) to compensate

### When to Calculate

- **During NTC** (`calibrateCamera=false`): Calculate and update displacement
- **During Camera Calibration** (`calibrateCamera=true`): Skip displacement (user is explicitly calibrating camera position)

### Algorithm

```java
// After collecting all measured locations during NTC
if (updateDisplacement && !calibrateCamera && !nozzleTipMeasuredLocations.isEmpty()) {
    Location averageOffset = calculateAverageOffset(nozzleTipMeasuredLocations);
    
    // Displacement is the negative of the measured offset
    double displacementX = -averageOffset.getX();
    double displacementY = -averageOffset.getY();
    
    camera.setDisplacements(displacementX, displacementY);
    camera.setDisplacementActive(true);
    
    Logger.info("Camera {} displacement updated: ({}, {}) mm", 
                camera.getName(), displacementX, displacementY);
}
```

## Test Strategy

### Test 1: Single Nozzle Calibration
- Verify existing behavior unchanged
- Displacement should NOT be updated (single nozzle case)

### Test 2: Multiple Nozzles with 1:1 Camera Mapping
- Create 2 nozzles, 2 cameras
- Verify parallel execution
- Verify displacement is calculated and saved for each camera
- Verify both nozzles are calibrated

### Test 3: Multiple Nozzles with Shared Camera
- Create 2 nozzles, 1 camera
- Verify sequential execution (not parallel)
- Verify displacement is NOT updated (shared camera case)

### Test 4: Error Handling
- Simulate calibration failure for one nozzle
- Verify exception is thrown with failed nozzle names
- Verify no partial state changes

### Test 5: CameraBatchOperation Nesting
- Verify CameraBatchOperation works correctly in parallel threads
- Verify no light leakage between camera groups

### Test 6: Displacement Accuracy
- Verify calculated displacement matches expected values
- Verify displacement is used correctly in subsequent bottom vision

## Files to Modify

1. **`ReferenceNozzleTipCalibration.java`**
   - Add `calibrateSingle()` method
   - Add `calibrateParallel()` method
   - Add `calculateAverageOffset()` helper
   - Modify displacement calculation logic

2. **`ReferenceNozzle.java`**
   - Add `findOtherNozzlesNeedingCalibration()` helper
   - Modify `loadNozzleTip()` to use parallel calibration

## Benefits

1. **Faster Calibration**: Multiple nozzles calibrated simultaneously
2. **Automatic Displacement**: No manual configuration needed
3. **Better Accuracy**: Displacement calculated from actual measurements
4. **Backward Compatible**: Existing single-nozzle workflow unchanged
5. **Safe**: CameraBatchOperation prevents LED conflicts

## Limitations

1. **1:1 Mapping Required**: Parallel only when nozzles == cameras
2. **Shared Cameras**: Nozzles sharing a camera calibrate sequentially
3. **Displacement Active**: Only updates displacement during NTC, not camera calibration

## Migration Path

1. Existing configurations continue to work unchanged
2. New installations benefit from automatic displacement calculation
3. Manual displacement values can still be overridden if needed
4. No configuration changes required

## Future Enhancements

1. Support parallel calibration for non-1:1 mappings (more complex threading)
2. Automatic displacement recalculation on nozzle tip change
3. Displacement validation during job execution
4. UI indicator for displacement quality/accuracy
