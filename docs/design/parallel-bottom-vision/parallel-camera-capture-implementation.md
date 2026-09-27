# Parallel Camera Capture - Implementation Guide

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

This document provides the exact code changes needed to implement parallel camera capture during the settle test.

## Summary of Changes

### Files to Modify
1. `src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java`
2. `src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

---

## Change 1: ReferenceBottomVision.java

### Location
Add new method after line 1349 (after the existing `captureImageOnly()` method), before line 1367.

### Code to Add

```java
/**
 * Capture image ONLY (no pipeline processing) with optional light control skip.
 * This method is designed for async/parallel capture where light control is
 * deferred to a batch operation. Use skipLightControl=true when capturing
 * asynchronously, then let CameraBatchOperation handle turning off all lights
 * at once after all captures complete.
 * 
 * @param part The part being aligned
 * @param camera The camera to use for capture
 * @param nozzle The nozzle performing the capture
 * @param wantedLocation The location where capture should occur
 * @param isFirstNozzleTip True if this is the first nozzle tip (displacement = 0)
 * @param skipLightControl If true, skip actuateLightAfterCapture() - caller handles it
 * @return PreCapturedPlacement containing the captured image and displacement delta
 * @throws Exception If capture fails
 */
public org.openpnp.model.PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
        Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip, 
        boolean skipLightControl) throws Exception {
    BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);
    
    if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
        throw new Exception("Bottom vision not enabled for part "+part.getId());
    }
    
    // Clone pipeline for this capture
    CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
    try {
        // Prepare the pipeline with camera and other settings
        // skipMovement=true to prevent any hardware movement during parallel capture
        preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(), 
                camera, part.getPackage(), nozzle, nozzle.getNozzleTip(), 
                wantedLocation, wantedLocation, bottomVisionSettings, true);
        
        // Get the first pipeline shot (typically only one for simple capture)
        List<PipelineShot> shots = pipeline.getPipelineShots();
        if (shots.isEmpty()) {
            throw new Exception("No pipeline shots configured for part "+part.getId());
        }
        
        PipelineShot pipelineShot = shots.get(0);
        
        // Apply the shot - with skipMovement=true, this sets properties but doesn't move hardware
        pipelineShot.apply();
        
        // Calculate the displacement delta: nozzle tip camera offset + nozzle runout compensation
        ReferenceNozzle refNozzle = (ReferenceNozzle) nozzle;
        ReferenceNozzleTip calibrationNozzleTip = refNozzle.getCalibrationNozzleTip();
        
        // Get the camera offset for this nozzle tip
        Location cameraOffset = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
        if (calibrationNozzleTip != null && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
            cameraOffset = calibrationNozzleTip.getCalibration().getCalibratedCameraOffset(refNozzle, camera);
        }
        
        // Get nozzle runout compensation at the wanted rotation angle
        Location runoutCompensation = new Location(cameraOffset.getUnits(), 0, 0, 0, 0);
        if (calibrationNozzleTip != null && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
            runoutCompensation = calibrationNozzleTip.getCalibration().getCalibratedOffset(refNozzle, wantedLocation.getRotation());
        }
        
        // Combine: displacement delta = camera offset + runout compensation
        Location displacementDelta;
        if (isFirstNozzleTip) {
            displacementDelta = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
        }
        else {
            displacementDelta = cameraOffset.add(runoutCompensation);
        }
        Logger.debug("captureImageOnly (async): isFirstNozzleTip={}, cameraOffset={}, runoutCompensation={}, displacementDelta={}", 
            isFirstNozzleTip, cameraOffset, runoutCompensation, displacementDelta);
        
        // Capture the image (no settling needed - head is already stationary)
        BufferedImage capturedBufferedImage = camera.capture();
        Mat capturedImage = OpenCvUtils.toMat(capturedBufferedImage);
        
        // Only turn off light if not skipping - batch operation handles it
        if (!skipLightControl) {
            try {
                camera.actuateLightAfterCapture();
            }
            catch (Exception e) {
                Logger.warn("Failed to turn off camera light after pre-capture: {}", e.getMessage());
            }
        }
        
        // Create a PreCapturedPlacement with the captured image and displacement delta
        return new org.openpnp.model.PreCapturedPlacement(
                camera, nozzle, capturedImage, wantedLocation, displacementDelta);
    }
    finally {
        // Always close the pipeline to release resources
        pipeline.close();
    }
}
```

---

## Change 2: ReferencePnpJobProcessor.java

### Location
Modify the `preCaptureAllImages()` method, specifically the section from line 2044-2130.

### Imports to Add
At the top of the file (around line 30-35), add:
```java
import java.util.concurrent.CompletableFuture;
```
**Note:** `CompletionException` is already imported at line 30, so only add `CompletableFuture`.

### Code Replacement

Replace the entire section from line 2044 (`ImageBuffer imageBuffer = new ImageBuffer();`) through line 2130 (closing brace of the camera capture loops) with:

```java
ImageBuffer imageBuffer = new ImageBuffer();
// Track async capture futures for secondary cameras
List<CompletableFuture<org.openpnp.model.PreCapturedPlacement>> asyncFutures = 
    new ArrayList<>();

try {
    // ===================================================================
    // STEP 1: Trigger secondary camera captures ASYNCHRONOUSLY
    // This starts BEFORE the settle loop, allowing parallel capture
    // during the 10-12ms settle time.
    // ===================================================================
    List<Camera> secondaryCameras = new ArrayList<>();
    for (Camera c : cameras) {
        if (!c.equals(settleCamera)) {
            secondaryCameras.add(c);
        }
    }
    
    // Submit all secondary captures to vision executor
    // These run in parallel while main thread performs settle
    for (Camera captureCamera : secondaryCameras) {
        List<PlannedPlacement> captureCameraPlacements = placementsByCamera.get(captureCamera);
        if (captureCameraPlacements != null && !captureCameraPlacements.isEmpty()) {
            for (PlannedPlacement placement : captureCameraPlacements) {
                Nozzle nozzle = placement.nozzle;
                Part part = placement.jobPlacement.getPlacement().getPart();
                
                PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
                if (partAlignment instanceof ReferenceBottomVision) {
                    ReferenceBottomVision bottomVision = (ReferenceBottomVision) partAlignment;
                    BottomVisionSettings bottomVisionSettings = 
                        bottomVision.getInheritedVisionSettings(part);
                    
                    if (bottomVisionSettings.isEnabled() && !needsMultipleShots(part)) {
                        Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(
                            part, captureCamera, nozzle, 0., false);
                        boolean isFirstNozzleTip3 = nozzle.equals(firstNozzleTip);
                        
                        // Capture camera reference for closure
                        final Camera cam = captureCamera;
                        final Nozzle noz = nozzle;
                        final Part prt = part;
                        final boolean firstTip = isFirstNozzleTip3;
                        final Location loc = wantedLocation;
                        final ReferenceBottomVision bv = bottomVision;
                        
                        // Submit async capture - returns immediately
                        CompletableFuture<org.openpnp.model.PreCapturedPlacement> future = 
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    Logger.trace("Async capture started for camera {}", cam.getName());
                                    org.openpnp.model.PreCapturedPlacement shot = 
                                        bv.captureImageOnly(prt, cam, noz, loc, firstTip, true);
                                    Logger.trace("Async capture completed for camera {}", cam.getName());
                                    return shot;
                                }
                                catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            }, getVisionExecutor());
                        
                        asyncFutures.add(future);
                    }
                }
            }
        }
    }
    
    // ===================================================================
    // STEP 2: Settle on the first camera (blocks 10-12ms)
    // During this time, secondary captures run in parallel on background threads
    // ===================================================================
    if (settleCameraPlacements != null && !settleCameraPlacements.isEmpty()) {
        PlannedPlacement firstPlacement = settleCameraPlacements.get(0);
        Camera camera = settleCamera;
        Nozzle nozzle = firstPlacement.nozzle;
        Part part = firstPlacement.jobPlacement.getPlacement().getPart();
        
        try {
            BottomVisionSettings bottomVisionSettings = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                .getInheritedVisionSettings(part);
            if (bottomVisionSettings.isEnabled() && !needsMultipleShots(part)) {
                Location wantedLocation = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                    .getCameraLocationAtPartHeight(part, camera, nozzle, 0., false);
                
                // Auto-settle and capture on first camera
                boolean isFirstNozzleTip = nozzle.equals(firstNozzleTip);
                org.openpnp.model.PreCapturedPlacement shot = 
                    ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                    .captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, isFirstNozzleTip);
                imageBuffer.addShot(nozzle, shot);
                
                // Capture remaining placements on the settle camera
                for (int i = 1; i < settleCameraPlacements.size(); i++) {
                    PlannedPlacement placement = settleCameraPlacements.get(i);
                    nozzle = placement.nozzle;
                    part = placement.jobPlacement.getPlacement().getPart();
                    
                    bottomVisionSettings = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                        .getInheritedVisionSettings(part);
                    if (bottomVisionSettings.isEnabled() && !needsMultipleShots(part)) {
                        wantedLocation = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                            .getCameraLocationAtPartHeight(part, camera, nozzle, 0., false);
                        
                        boolean isFirstNozzleTip2 = nozzle.equals(firstNozzleTip);
                        org.openpnp.model.PreCapturedPlacement shot2 = 
                            ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                            .captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, isFirstNozzleTip2);
                        imageBuffer.addShot(nozzle, shot2);
                    }
                }
            }
        }
        catch (Exception e) {
            throw new JobProcessorException(firstPlacement.nozzle, e);
        }
    }
    
    // ===================================================================
    // STEP 3: Wait for all async captures to complete
    // This is very fast since captures already ran during settle
    // ===================================================================
    if (!asyncFutures.isEmpty()) {
        Logger.trace("Waiting for {} async camera captures to complete", asyncFutures.size());
        CompletableFuture.allOf(asyncFutures.toArray(new CompletableFuture[0])).join();
        
        // Add completed captures to image buffer
        for (int i = 0; i < asyncFutures.size(); i++) {
            CompletableFuture<org.openpnp.model.PreCapturedPlacement> future = asyncFutures.get(i);
            try {
                org.openpnp.model.PreCapturedPlacement shot = future.get();
                imageBuffer.addShot(shot.getNozzle(), shot);
                Logger.trace("Async capture {} added to buffer", i+1);
            }
            catch (Exception e) {
                Logger.error("Async camera capture failed: {}", e.getMessage());
                throw new JobProcessorException("Async camera capture failed", e);
            }
        }
        Logger.trace("All async captures complete, {} shots added", asyncFutures.size());
    }
    
    // ===================================================================
    // STEP 4: Capture remaining placements on settle camera (sequential)
    // These don't need settle since scene is already stable
    // ===================================================================
    // Note: This code is now mostly redundant since async captures handle
    // secondary cameras, but kept for settle camera remaining placements
    // and as a fallback.
    
}
finally {
    // End CameraBatchOperation - turns off all camera lights
    if (batchStarted && cbo != null) {
        try {
            cbo.endBatchOperation("parallel-align-precapture");
            Logger.debug("Camera batch operation ended for parallel alignment pre-capture");
        }
        catch (Exception e) {
            Logger.warn("Failed to end camera batch operation: {}", e.getMessage());
        }
    }
}
```

---

## Testing Checklist

After implementing these changes:

- [ ] Compile successfully with no errors
- [ ] Run with 2 cameras (Upp1, Upp2) - verify no lockup
- [ ] Measure alignment time - should see ~10-12ms improvement
- [ ] Run with 3-4 cameras - verify scaling
- [ ] Check logs for "Async capture started/completed" messages
- [ ] Verify all nozzles align correctly (no regression)
- [ ] Monitor memory - no OpenCV Mat leaks
- [ ] Test error handling - what happens if one async capture fails?

---

## Rollback Plan

If issues occur:

1. **Quick rollback**: Revert both file changes
2. **Partial rollback**: Comment out STEP 1 (async triggers) - system falls back to sequential
3. **Debug mode**: Add `Logger.trace()` calls to track async task lifecycle

---

## Expected Performance Gains

| Scenario | Before | After | Improvement |
|----------|--------|-------|-------------|
| 2 cameras, 10ms settle | ~25ms | ~15ms | ~10ms (40%) |
| 2 cameras, 12ms settle | ~27ms | ~15ms | ~12ms (44%) |
| 4 cameras, 10ms settle | ~35ms | ~15ms | ~20ms (57%) |

Note: Actual gains depend on camera hardware, USB bandwidth, and OpenCV processing time.
