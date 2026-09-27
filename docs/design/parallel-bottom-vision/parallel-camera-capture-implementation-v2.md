# Parallel Camera Capture - Revised Implementation (v2)

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Key Corrections from v1

1. **Thread Pool**: Use `CompletableFuture.supplyAsync()` without executor (defaults to ForkJoinPool.commonPool())
   - Camera capture is I/O bound, not CPU bound
   - Leaves visionExecutor free for OpenCV math operations

2. **API Purity**: Use existing `captureImageOnly()` method, no new `skipLightControl` parameter
   - `CameraBatchOperation` already suppresses light actuation natively
   - Cleaner integration with existing OpenPnP patterns

3. **Multi-Shot Fallback**: Two-pass system for `needsMultipleShots(part)`
   - Pass 1 (Parallel): Async captures for single-shot parts
   - Pass 2 (Sequential): Main thread handles multi-shot parts after settle

---

## Change 1: ReferencePnpJobProcessor.java

### Import Addition (Line 35)

Add after existing imports:
```java
import java.util.concurrent.CompletableFuture;
```

### Modified preCaptureAllImages() Method

**Location**: Lines 2044-2130 (replace entire section)

```java
ImageBuffer imageBuffer = new ImageBuffer();
// Track async capture futures for secondary cameras (single-shot parts only)
List<CompletableFuture<org.openpnp.model.PreCapturedPlacement>> asyncFutures = 
    new ArrayList<>();

try {
    // ===================================================================
    // PASS 1 (PARALLEL): Trigger async captures for single-shot parts
    // This starts BEFORE the settle loop, allowing parallel capture
    // during the 10-12ms settle time.
    // ===================================================================
    
    // First, identify all secondary cameras and their single-shot placements
    for (Camera captureCamera : cameras) {
        // Skip the settle camera - it handles its own captures
        if (captureCamera.equals(settleCamera)) {
            continue;
        }
        
        List<PlannedPlacement> captureCameraPlacements = placementsByCamera.get(captureCamera);
        if (captureCameraPlacements == null || captureCameraPlacements.isEmpty()) {
            continue;
        }
        
        // Submit async captures for single-shot parts only
        for (PlannedPlacement placement : captureCameraPlacements) {
            Nozzle nozzle = placement.nozzle;
            Part part = placement.jobPlacement.getPlacement().getPart();
            
            PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
            if (!(partAlignment instanceof ReferenceBottomVision)) {
                continue;
            }
            
            ReferenceBottomVision bottomVision = (ReferenceBottomVision) partAlignment;
            BottomVisionSettings bottomVisionSettings = 
                bottomVision.getInheritedVisionSettings(part);
            
            if (!bottomVisionSettings.isEnabled()) {
                continue;
            }
            
            // Skip multi-shot parts - they'll be handled in Pass 2
            if (needsMultipleShots(part)) {
                Logger.trace("Skipping async capture for multi-shot part {}, will handle sequentially", 
                    part.getId());
                continue;
            }
            
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(
                part, captureCamera, nozzle, 0., false);
            boolean isFirstNozzleTip = nozzle.equals(firstNozzleTip);
            
            // Capture references for closure
            final Camera cam = captureCamera;
            final Nozzle noz = nozzle;
            final Part prt = part;
            final boolean firstTip = isFirstNozzleTip;
            final Location loc = wantedLocation;
            final ReferenceBottomVision bv = bottomVision;
            
            // Submit async capture using default ForkJoinPool.commonPool()
            // This is I/O bound work, leaving visionExecutor free for OpenCV math
            CompletableFuture<org.openpnp.model.PreCapturedPlacement> future = 
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Logger.trace("Async capture started for camera {}, part {}", 
                            cam.getName(), prt.getId());
                        org.openpnp.model.PreCapturedPlacement shot = 
                            bv.captureImageOnly(prt, cam, noz, loc, firstTip);
                        Logger.trace("Async capture completed for camera {}, part {}", 
                            cam.getName(), prt.getId());
                        return shot;
                    }
                    catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
            
            asyncFutures.add(future);
        }
    }

    // ===================================================================
    // PASS 1 CONTINUED: Settle on the first camera (blocks 10-12ms)
    // During this time, secondary camera async captures run in parallel
    // ===================================================================
    
    if (settleCameraPlacements != null && !settleCameraPlacements.isEmpty()) {
        for (PlannedPlacement placement : settleCameraPlacements) {
            Camera camera = settleCamera;
            Nozzle nozzle = placement.nozzle;
            Part part = placement.jobPlacement.getPlacement().getPart();
            
            try {
                PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
                if (!(partAlignment instanceof ReferenceBottomVision)) {
                    continue;
                }
                
                ReferenceBottomVision bottomVision = (ReferenceBottomVision) partAlignment;
                BottomVisionSettings bottomVisionSettings = bottomVision.getInheritedVisionSettings(part);
                
                if (bottomVisionSettings.isEnabled()) {
                    // Check single-shot vs multi-shot
                    if (!needsMultipleShots(part)) {
                        Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(
                            part, camera, nozzle, 0., false);
                        boolean isFirstTip = nozzle.equals(firstNozzleTip);
                        
                        org.openpnp.model.PreCapturedPlacement shot = 
                            bottomVision.captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, isFirstTip);
                        imageBuffer.addShot(nozzle, shot);
                    }
                    else {
                        // Multi-shot part on settle camera - mark for Pass 2
                        Logger.trace("Settle camera has multi-shot part {}, will handle in Pass 2", part.getId());
                    }
                }
            }
            catch (Exception e) {
                throw new JobProcessorException(nozzle, e);
            }
        }
    }
    
    // ===================================================================
    // PASS 1 END: Wait for all async captures to complete
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
                Logger.trace("Async capture {} added to buffer (camera: {}, nozzle: {})", 
                    i+1, shot.getCamera().getId(), shot.getNozzle().getId());
            }
            catch (Exception e) {
                Logger.error("Async camera capture failed: {}", e.getMessage());
                throw new JobProcessorException("Async camera capture failed", e);
            }
        }
        Logger.trace("All async captures complete, {} shots added", asyncFutures.size());
    }
    
    // ===================================================================
    // PASS 2 (SEQUENTIAL): Handle multi-shot parts
    // After settle and async captures complete, process multi-shot parts
    // that require head movement between shots.
    // ===================================================================
    
    // Iterate through ALL cameras and placements
    for (Camera captureCamera : cameras) {
        List<PlannedPlacement> captureCameraPlacements = placementsByCamera.get(captureCamera);
        if (captureCameraPlacements == null || captureCameraPlacements.isEmpty()) {
            continue;
        }
        
        for (PlannedPlacement placement : captureCameraPlacements) {
            Nozzle nozzle = placement.nozzle;
            Part part = placement.jobPlacement.getPlacement().getPart();
            
            PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
            if (!(partAlignment instanceof ReferenceBottomVision)) {
                continue;
            }
            
            ReferenceBottomVision bottomVision = (ReferenceBottomVision) partAlignment;
            BottomVisionSettings bottomVisionSettings = 
                bottomVision.getInheritedVisionSettings(part);
            
            if (!bottomVisionSettings.isEnabled()) {
                continue;
            }
            
            // Only process multi-shot parts here
            if (!needsMultipleShots(part)) {
                // Already handled in Pass 1 (async) or settle camera (sequential)
                continue;
            }
            
            // Multi-shot part: needs sequential processing with head movement
            Logger.trace("Processing multi-shot part {} sequentially on camera {}", 
                part.getId(), captureCamera.getName());
            
            try {
                Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(
                    part, captureCamera, nozzle, 0., false);
                boolean isFirstNozzleTip = nozzle.equals(firstNozzleTip);
                
                // For multi-shot, we need to use the regular capture method
                // which handles the movement between shots
                // This falls back to the original sequential behavior for these parts
                org.openpnp.model.PreCapturedPlacement shot = 
                    bottomVision.captureImageOnlyWithSettle(part, captureCamera, nozzle, 
                        wantedLocation, isFirstNozzleTip);
                imageBuffer.addShot(nozzle, shot);
            }
            catch (Exception e) {
                throw new JobProcessorException(placement.nozzle, e);
            }
        }
    }
    
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

## Key Design Decisions in v2

### 1. Thread Pool Selection

```java
// I/O bound work - use default ForkJoinPool.commonPool()
CompletableFuture.supplyAsync(() -> {
    // Camera capture happens here
});
```

**Why not visionExecutor?**
- Camera capture is I/O bound (USB transfer), not CPU bound
- visionExecutor is sized for CPU work (CPU cores / 2)
- ForkJoinPool.commonPool() has parallelism = CPU cores
- Leaves visionExecutor free for OpenCV image processing

### 2. API Purity

```java
// Use existing method - no new parameters
org.openpnp.model.PreCapturedPlacement shot = 
    bv.captureImageOnly(prt, cam, noz, loc, firstTip);
```

**Why no skipLightControl?**
- `CameraBatchOperation.startBatchOperation()` suppresses light actuation
- `CameraBatchOperation.endBatchOperation()` turns off all lights at once
- Existing `captureImageOnly()` already calls `camera.actuateLightAfterCapture()`
- The batch operation wraps these calls and defers the actual light-off

### 3. Two-Pass System

**Pass 1 (Parallel)**:
- Async captures for single-shot parts on secondary cameras
- Settles and captures on settle camera (single-shot only)
- Runs during settle loop (10-12ms)

**Pass 2 (Sequential)**:
- Multi-shot parts that require head movement
- After async futures join
- Falls back to sequential behavior for these edge cases

**Why two passes?**
- Multi-shot parts need head movement between shots
- Can't be parallelized (requires sequential motion)
- Separate pass keeps parallel path clean and fast

---

## Edge Cases Handled

| Case | Handling |
|------|----------|
| Multi-shot part on secondary camera | Pass 2 sequential |
| Multi-shot part on settle camera | Pass 2 sequential |
| Async capture fails | Exception propagated via future.get() |
| All parts are multi-shot | Falls back to all sequential |
| All parts are single-shot | Fully parallel (best case) |
| Mixed single/multi-shot | Hybrid: parallel + sequential |

---

## Expected Performance

| Scenario | v1 (visionExecutor) | v2 (commonPool) |
|----------|---------------------|-----------------|
| 8-core CPU, 2 cameras | ~15ms | ~14ms |
| 8-core CPU, 4 cameras | ~15ms | ~13ms |
| Heavy OpenCV processing | visionExecutor starved | visionExecutor free |

**Key insight**: On 8-core CPU with 4 cameras, commonPool provides better parallelism for I/O while leaving visionExecutor (4 threads) for OpenCV.
