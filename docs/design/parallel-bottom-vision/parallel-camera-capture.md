# Parallel Camera Capture During Settle Test

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Problem Statement

Currently, during parallel alignment:
1. Primary camera (settle camera) performs auto-settle loop (10-12ms)
2. Secondary cameras capture **after** settle completes
3. This adds 10-12ms latency that could be eliminated

Previous attempts to trigger secondary captures during the settle loop caused ~1 second main thread lockup, likely due to machine resource contention (motion planner synchronization).

## Solution Overview

Trigger secondary camera captures **asynchronously** at the **start** of the settle test using `CompletableFuture`, then synchronize after settle completes. This achieves true parallelism without blocking the main thread.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PARALLEL CAPTURE TIMELINE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Main Thread                                                                │
│  │                                                                          │
│  ├─ moveNozzlesToAlignmentPosition()                                        │
│  │    ├─ moveToSafeZ()                                                      │
│  │    ├─ MovableUtils.moveToLocationAtSafeZ()                              │
│  │    ├─ camera.actuateLightBeforeCapture()  (M810 queued)                 │
│  │    └─ waitForCompletion(Stillstand)       (M400 executed, head stops)   │
│  │                                                                          │
│  ├─ preCaptureAllImages()                                                   │
│  │    │                                                                      │
│  │    ├─ [NEW] START: Trigger secondary captures async                      │
│  │    │    └─ CompletableFuture.supplyAsync(() -> captureSecondary())      │
│  │    │         (submitted to visionExecutor, returns immediately)          │
│  │    │                                                                      │
│  │    ├─ captureImageOnlyWithSettle()  (blocks 10-12ms)                     │
│  │    │    └─ autoSettleAndCapture()                                        │
│  │    │         └─ while(!stable) { capture(); computeDiff(); }            │
│  │    │                                                                      │
│  │    ├─ [NEW] SYNC: Wait for secondary captures                           │
│  │    │    └─ future.join()  (very fast, capture already complete)         │
│  │    │                                                                      │
│  │    └─ Return ImageBuffer with ALL captures ready                         │
│  │                                                                          │
│  └─ CompletableFuture.runAsync(...) for processing                          │
│       (visionExecutor processes all pre-captured images)                    │
│                                                                             │
│  Background Threads (visionExecutor)                                        │
│  │                                                                          │
│  └─ Secondary Capture Task 1                                                │
│       └─ camera.capture() → Mat → PreCapturedPlacement  (runs parallel)     │
│                                                                             │
│  └─ Secondary Capture Task 2                                                │
│       └─ camera.capture() → Mat → PreCapturedPlacement  (runs parallel)     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Why This Prevents Lockup

1. **Machine Resources Released**: `waitForCompletion(Stillstand)` at line 1927 ensures all motion commands are complete before captures begin
2. **Thread Isolation**: Secondary captures run in `visionExecutor` pool, not main thread
3. **No Shared State**: Each capture creates independent `Mat` objects
4. **Light Control Deferred**: Using `skipLightControl=true` for async captures, light turned off after all complete via `CameraBatchOperation`
5. **Non-blocking**: Main thread never waits during settle loop

## Implementation Details

### Modified Files

1. **ReferenceBottomVision.java** - Add async-compatible capture method
2. **ReferencePnpJobProcessor.java** - Modify `preCaptureAllImages()` 

### Code Changes

#### 1. ReferenceBottomVision.java

Add new method signature that supports async capture with deferred light control:

```java
/**
 * Capture image ONLY (no pipeline processing) with optional light control skip.
 * This method is designed for async/parallel capture where light control is
 * deferred to a batch operation.
 * 
 * @param part The part being aligned
 * @param camera The camera to use for capture
 * @param nozzle The nozzle performing the capture
 * @param wantedLocation The location where capture should occur
 * @param isFirstNozzleTip True if this is the first nozzle tip
 * @param skipLightControl If true, skip actuateLightAfterCapture() - caller handles it
 * @return PreCapturedPlacement containing the captured image and displacement delta
 * @throws Exception If capture fails
 */
public org.openpnp.model.PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
        Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip, 
        boolean skipLightControl) throws Exception
```

Implementation:
- Same as existing `captureImageOnly()` 
- Add `skipLightControl` parameter to conditionally skip `camera.actuateLightAfterCapture()`
- Pipeline and Mat handling unchanged

#### 2. ReferencePnpJobProcessor.java

Modify `preCaptureAllImages()` method (around line 2044-2130):

```java
ImageBuffer imageBuffer = new ImageBuffer();
// [NEW] Track async capture futures
List<CompletableFuture<org.openpnp.model.PreCapturedPlacement>> asyncFutures = 
    new ArrayList<>();

try {
    // [NEW] Step 1: Trigger secondary camera captures ASYNCHRONOUSLY
    // This starts BEFORE the settle loop, allowing parallel capture
    List<Camera> secondaryCameras = new ArrayList<>();
    for (Camera c : cameras) {
        if (!c.equals(settleCamera)) {
            secondaryCameras.add(c);
        }
    }
    
    // Submit all secondary captures to vision executor
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
                        
                        // Submit async capture - returns immediately
                        CompletableFuture<org.openpnp.model.PreCapturedPlacement> future = 
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    Logger.trace("Async capture started for camera {}", cam.getName());
                                    org.openpnp.model.PreCapturedPlacement shot = 
                                        bottomVision.captureImageOnly(prt, cam, noz, loc, firstTip, true);
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
    
    // Step 2: Settle on the first camera (blocks 10-12ms)
    // During this time, secondary captures run in parallel on background threads
    if (settleCameraPlacements != null && !settleCameraPlacements.isEmpty()) {
        // ... existing settle camera capture code ...
        // captureImageOnlyWithSettle(...)
    }
    
    // Step 3: Wait for all async captures to complete
    // This is very fast since captures already ran during settle
    if (!asyncFutures.isEmpty()) {
        CompletableFuture.allOf(asyncFutures.toArray(new CompletableFuture[0])).join();
        
        // Add completed captures to image buffer
        for (CompletableFuture<org.openpnp.model.PreCapturedPlacement> future : asyncFutures) {
            try {
                org.openpnp.model.PreCapturedPlacement shot = future.get();
                imageBuffer.addShot(shot.getNozzle(), shot);
            }
            catch (Exception e) {
                throw new JobProcessorException(
                    "Async camera capture failed", e);
            }
        }
    }
    
    // Step 4: Capture remaining placements on settle camera (sequential, no settle)
    // ... existing code ...
    
}
finally {
    // End CameraBatchOperation - turns off all camera lights
    if (batchStarted && cbo != null) {
        cbo.endBatchOperation("parallel-align-precapture");
    }
}
```

### Key Design Decisions

1. **When to trigger async captures**: After `waitForCompletion(Stillstand)` ensures machine is idle, but BEFORE settle loop starts
2. **Thread pool**: Use existing `visionExecutor` - already sized appropriately (CPU cores / 2)
3. **Light control**: Use `skipLightControl=true` for async captures, rely on `CameraBatchOperation` to turn off all lights at end
4. **Error handling**: Wrap exceptions in `CompletionException`, propagate to main thread via `future.get()`
5. **Memory management**: Each async capture creates its own `Mat`, added to `ImageBuffer` which tracks and releases all

## Testing Considerations

1. **Verify no lockup**: Monitor main thread during settle with async captures enabled
2. **Measure latency**: Should see 10-12ms reduction in total alignment time
3. **Check thread pool**: Ensure visionExecutor has enough threads for concurrent captures
4. **Test with 2, 3, 4 cameras**: Verify scaling behavior
5. **Monitor OpenCV memory**: Ensure no leaks from async Mat objects

## Potential Issues & Mitigations

| Issue | Cause | Mitigation |
|-------|-------|------------|
| USB bandwidth contention | Multiple cameras on same bus | Cameras are on separate USB3 buses (confirmed) |
| OpenCV thread starvation | Too many concurrent captures | visionExecutor pool size = CPU cores / 2, should be sufficient |
| Light timing issues | Async capture light off before settle done | Use `skipLightControl=true`, rely on batch operation |
| Exception in async task | Capture fails silently | Wrap in CompletionException, check via future.get() |
| Memory leak | Mat not released | ImageBuffer tracks all shots, release() called in finally block |

## Future Enhancements

1. **Configurable parallelism**: Add setting to control max concurrent captures
2. **Priority queuing**: Settle camera could have priority over secondary captures
3. **Batched async**: For 4+ cameras, consider batching captures in groups
4. **Hardware trigger**: If cameras support hardware triggering, trigger all simultaneously
