# Parallel Bottom Vision Implementation Plan - Corrected

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Executive Summary

This plan implements true parallel bottom vision processing for machines with multiple cameras, leveraging the mechanical offset where Nozzle 1 over Camera 1 means Nozzle 2 is automatically over Camera 2.

**Key architectural changes:**
1. **Separate vision executor** - Not using `machine.submit()` to avoid command queue conflicts
2. **Per-nozzle result containers** - Each nozzle has its own thread-safe VisionCompositing
3. **Barrier synchronization** - Using `CountDownLatch` for proper completion signaling
4. **Hardware-aware parallelism** - Move once, capture both cameras, process in parallel

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    PnpJobProcessor Align Step                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  PlannedPlacements: [N1→P1, N2→P2, N1→P3, N2→P4]                │
│                                                                  │
│  ┌─────────────────┐  ┌─────────────────┐                       │
│  │  Group by Nozzle│  │  Group by Camera│                       │
│  └────────┬────────┘  └────────┬────────┘                       │
│           │                    │                                 │
│           ▼                    ▼                                 │
│  ┌─────────────────┐  ┌─────────────────┐                       │
│  │ Nozzle 1 Tasks  │  │ Nozzle 2 Tasks  │  ← Submit to Executor │
│  │ (Camera 1)      │  │ (Camera 2)      │                       │
│  └────────┬────────┘  └────────┬────────┘                       │
│           │                    │                                 │
│           └──────────┬─────────┘                                 │
│                      ▼                                           │
│           ┌─────────────────────┐                                │
│           │  Move to Position   │  ← Single move for both        │
│           │  (both nozzles over │                                 │
│           │   their cameras)    │                                 │
│           └──────────┬──────────┘                                │
│                      │                                           │
│           ┌──────────┴──────────┐                                │
│           ▼                     ▼                                │
│  ┌─────────────────┐   ┌─────────────────┐                       │
│  │ Capture Camera 1│   │ Capture Camera 2│  ← Simultaneous       │
│  └────────┬────────┘   └────────┬────────┘                       │
│           │                     │                                 │
│           ▼                     ▼                                 │
│  ┌─────────────────┐   ┌─────────────────┐                       │
│  │ Process Thread 1│   │ Process Thread 2│  ← Parallel OpenCV    │
│  └────────┬────────┘   └────────┬────────┘                       │
│           │                     │                                 │
│           └──────────┬──────────┘                                 │
│                      ▼                                            │
│           ┌─────────────────────┐                                 │
│           │  CountDownLatch     │  ← Wait for both                │
│           └──────────┬──────────┘                                 │
│                      │                                            │
│                      ▼                                            │
│           ┌─────────────────────┐                                 │
│           │  Merge Results      │  ← Thread-safe                  │
│           └─────────────────────┘                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Thread-Safe Vision Compositing

**File:** `src/main/java/org/openpnp/model/VisionCompositing.java`

### Problem Analysis

Current `VisionCompositing.Composite` class has these issues:
1. `cornerMap` uses `HashMap` - not thread-safe
2. `interpret()` iterates while others may write
3. No completion signaling

### Solution: Add Thread-Safe Wrapper Class

```java
// New inner class for thread-safe compositing
public class ThreadSafeComposite {
    private final Composite delegate;
    private final ConcurrentHashMap<Corner, Point> cornerMap;
    private final AtomicInteger pendingShots;
    private final CountDownLatch completionLatch;
    private volatile boolean interpretationComplete = false;
    private volatile RotatedRect finalResult = null;
    
    public ThreadSafeComposite(Composite delegate, int expectedShotCount) {
        this.delegate = delegate;
        this.cornerMap = new ConcurrentHashMap<>();
        this.pendingShots = new AtomicInteger(expectedShotCount);
        this.completionLatch = new CountDownLatch(1);
    }
    
    /**
     * Thread-safe accumulation of shot detection results.
     * Multiple threads can call this concurrently.
     */
    public void accumulateShotDetection(Shot shot, RotatedRect rect) {
        if (!delegate.getCompositingSolution().isAdvanced()) {
            // Simple case - just store the rect (single shot)
            synchronized(this) {
                delegate.setDetectedRotatedRect(rect);
            }
        } else {
            // Advanced compositing - thread-safe corner accumulation
            Point[] points = convertToPoints(shot, rect);
            for (Corner corner : shot.getCorners()) {
                int idx = (corner.getXSign() < 0 ? 0 : 1)
                        + (corner.getYSign() > 0 ? 0 : 2);
                cornerMap.put(corner, points[idx]);
            }
        }
        
        // Check if this was the last shot
        if (pendingShots.decrementAndGet() == 0) {
            completionLatch.countDown();
        }
    }
    
    /**
     * Thread-safe interpretation - waits for all shots to complete.
     */
    public void interpret() throws Exception {
        if (!delegate.getCompositingSolution().isAdvanced()) {
            return;
        }
        
        // Wait for all shots to be accumulated
        completionLatch.await();
        
        // Now safe to interpret - all data is present
        synchronized(this) {
            if (interpretationComplete) {
                return; // Already interpreted
            }
            
            // Perform interpretation using cornerMap
            interpretInternal();
            interpretationComplete = true;
        }
    }
    
    private void interpretInternal() throws Exception {
        // Copy the interpretation logic from Composite.interpret()
        // but use cornerMap instead of the original cornerMap
        Point centerSum = new Point(0, 0);
        int centerWeights = 0;
        double angleSum = 0;
        int angleWeights = 0;
        
        // Safe to iterate now - completionLatch ensures all writes done
        for (Entry<Corner, Point> entry : cornerMap.entrySet()) {
            Corner corner = entry.getKey();
            Point point1 = entry.getValue();
            
            // Diagonal
            if (corner.diagonalBuddy != null) {
                Point point2 = cornerMap.get(corner.diagonalBuddy);
                if (point2 != null) {
                    centerSum = centerSum.add(point1).add(point2);
                    centerWeights += 2;
                    // ... rest of interpretation logic
                }
            }
            // ... other cases
        }
        
        // Store final result
        finalResult = delegate.getDetectedRotatedRect();
    }
    
    public RotatedRect getFinalResult() {
        return finalResult;
    }
    
    // Delegate other methods to the original Composite
    public Composite getDelegate() {
        return delegate;
    }
}
```

### Add Helper Methods to Composite Class

```java
// In Composite class, add package-private setters for ThreadSafeComposite
void setDetectedRotatedRect(RotatedRect rect) {
    this.detectedRotatedRect = rect;
}

RotatedRect getDetectedRotatedRect() {
    return detectedRotatedRect;
}
```

---

## Phase 2: Parallel Vision Executor

**File:** `src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

### Add Vision-Specific Executor

```java
public class ReferencePnpJobProcessor extends AbstractPnpJobProcessor {
    
    // Separate executor for vision processing - NOT using machine.submit()
    private static volatile ExecutorService visionExecutor;
    private static final Object executorLock = new Object();
    
    private static ExecutorService getVisionExecutor() {
        if (visionExecutor == null || visionExecutor.isShutdown()) {
            synchronized (executorLock) {
                if (visionExecutor == null || visionExecutor.isShutdown()) {
                    // Size based on available processors, minimum 2
                    int poolSize = Math.max(2, 
                        Runtime.getRuntime().availableProcessors() / 2);
                    visionExecutor = new ThreadPoolExecutor(
                        poolSize, poolSize,
                        1, TimeUnit.MINUTES,
                        new LinkedBlockingQueue<>(100),
                        new ThreadFactoryBuilder()
                            .setNameFormat("vision-processor-%d")
                            .setDaemon(true)
                            .build());
                }
            }
        }
        return visionExecutor;
    }
    
    // Shutdown hook or machine cleanup
    public static void shutdownVisionExecutor() {
        synchronized (executorLock) {
            if (visionExecutor != null) {
                visionExecutor.shutdownNow();
                visionExecutor = null;
            }
        }
    }
```

---

## Phase 3: Parallel Alignment Implementation

**File:** `src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

### Replace Sequential Align with Parallel

```java
/**
 * Parallel alignment step - align parts on multiple nozzles simultaneously.
 * Leverages mechanical offset: when Nozzle 1 is over Camera 1, 
 * Nozzle 2 is automatically over Camera 2.
 */
protected class ParallelAlign extends PlannedPlacementStep {
    
    public ParallelAlign(List<PlannedPlacement> plannedPlacements) {
        super(plannedPlacements);
    }
    
    @Override
    public Step stepImpl(PlannedPlacement plannedPlacement) throws JobProcessorException {
        if (plannedPlacement != null) {
            // Sequential processing for single placement (fallback)
            align(plannedPlacement);
            return this;
        }
        
        return new OptimizeNozzlesForPlace(plannedPlacements);
    }
    
    /**
     * Entry point for parallel alignment of all placements.
     * Called from the main Align step before iterating placements.
     */
    public void alignAllParallel() throws JobProcessorException {
        if (plannedPlacements.size() <= 1) {
            // No parallelism possible
            for (PlannedPlacement p : plannedPlacements) {
                align(p);
            }
            return;
        }
        
        // Group by camera (which groups by nozzle due to mechanical offset)
        Map<Camera, List<PlannedPlacement>> placementsByCamera = new HashMap<>();
        for (PlannedPlacement p : plannedPlacements) {
            try {
                Camera camera = VisionUtils.getBottomVisionCamera(p.nozzle);
                placementsByCamera.computeIfAbsent(camera, k -> new ArrayList<>())
                    .add(p);
            } catch (Exception e) {
                throw new JobProcessorException(
                    p.jobPlacement.getPlacement(), p.nozzle, e);
            }
        }
        
        // If only one camera, use sequential
        if (placementsByCamera.size() <= 1) {
            for (PlannedPlacement p : plannedPlacements) {
                align(p);
            }
            return;
        }
        
        // Submit each camera group as a parallel task
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (Map.Entry<Camera, List<PlannedPlacement>> entry : placementsByCamera.entrySet()) {
            Camera camera = entry.getKey();
            List<PlannedPlacement> cameraPlacements = entry.getValue();
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    alignCameraGroup(camera, cameraPlacements);
                } catch (JobProcessorException e) {
                    throw new CompletionException(e);
                }
            }, getVisionExecutor());
            
            futures.add(future);
        }
        
        // Wait for all camera groups to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .join();
        } catch (CompletionException e) {
            // Unwrap and throw the actual exception
            if (e.getCause() instanceof JobProcessorException) {
                throw (JobProcessorException) e.getCause();
            }
            throw new JobProcessorException(null, null, e);
        }
    }
    
    /**
     * Align all placements for a single camera group.
     * This runs in a separate thread.
     */
    private void alignCameraGroup(Camera camera, List<PlannedPlacement> placements) 
            throws JobProcessorException {
        
        // Move to position where all nozzles in this group are over their cameras
        // (This is a no-op if already positioned correctly)
        moveNozzlesToAlignmentPosition(placements);
        
        // Process each placement in the group
        // Note: Within a camera group, placements are processed sequentially
        // because they share the same camera pipeline
        for (PlannedPlacement placement : placements) {
            alignPlacement(placement, camera);
        }
    }
    
    /**
     * Align a single placement with thread-safe result accumulation.
     */
    private void alignPlacement(PlannedPlacement placement, Camera camera) 
            throws JobProcessorException {
        
        final Nozzle nozzle = placement.nozzle;
        final JobPlacement jobPlacement = placement.jobPlacement;
        final Placement actuation = jobPlacement.getPlacement();
        final Part part = actuation.getPart();
        final BoardLocation boardLocation = jobPlacement.getBoardLocation();
        
        PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
        if (partAlignment == null) {
            placement.alignmentOffsets = new PartAlignment.PartAlignmentOffset(
                new Location(LengthUnit.Millimeters), false);
            return;
        }
        
        Exception lastException = null;
        for (int i = 0; i < Math.max(1, getMaxVisionRetries()); i++) {
            try {
                // Create thread-safe composite for this alignment
                Camera cam = VisionUtils.getBottomVisionCamera(nozzle);
                BottomVisionSettings settings = getVisionSettings(part);
                
                // Get pipeline and count shots
                try (CvPipeline pipeline = settings.getPipeline()) {
                    int shotCount = getShotCount(pipeline, settings, part, nozzle, cam);
                    ThreadSafeComposite threadSafeComposite = 
                        createThreadSafeComposite(part, nozzle, cam, settings, shotCount);
                    
                    // Perform alignment with thread-safe compositing
                    placement.alignmentOffsets = performParallelAlignment(
                        partAlignment, part, boardLocation, actuation, 
                        nozzle, cam, settings, threadSafeComposite);
                    
                    Logger.debug("Align {} with {}, offsets {}", part, nozzle, 
                        placement.alignmentOffsets);
                    return;
                    
                } catch (Exception e) {
                    lastException = e;
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        
        throw new JobProcessorException(part, nozzle, lastException);
    }
    
    private ThreadSafeComposite createThreadSafeComposite(
            Part part, Nozzle nozzle, Camera camera,
            BottomVisionSettings settings, int shotCount) throws Exception {
        
        // Create the base composite (this sets up the shots)
        Location location = getAlignmentLocation(part, camera, nozzle, settings);
        Composite baseComposite = settings.getVisionCompositing()
            .new Composite(part.getPackage(), settings, nozzle, nozzle.getNozzleTip(),
                          camera, location);
        
        return new ThreadSafeComposite(baseComposite, shotCount);
    }
    
    private int getShotCount(CvPipeline pipeline, BottomVisionSettings settings,
                            Part part, Nozzle nozzle, Camera camera) throws Exception {
        // Count the shots that will be processed
        // This is based on the compositing solution
        Location location = getAlignmentLocation(part, camera, nozzle, settings);
        Composite temp = settings.getVisionCompositing()
            .new Composite(part.getPackage(), settings, nozzle, nozzle.getNozzleTip(),
                          camera, location);
        return temp.getCompositeShots().size();
    }
}
```

---

## Phase 4: Modified Bottom Vision Pipeline

**File:** `src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java`

### Add Thread-Safe Alignment Method

```java
/**
 * Perform alignment with thread-safe result accumulation.
 * This method is designed to be called from parallel threads.
 */
public PartAlignmentOffset findOffsetsParallel(
        Part part, BoardLocation boardLocation, Placement placement, 
        Nozzle nozzle, Camera camera, BottomVisionSettings settings,
        ThreadSafeComposite threadSafeComposite) throws Exception {
    
    boolean preRotate = (settings.getPreRotateUsage() == PreRotateUsage.Default && preRotate)
            || (settings.getPreRotateUsage() == PreRotateUsage.AlwaysOn);
    
    if (preRotate) {
        return findOffsetsPreRotateParallel(part, boardLocation, placement, nozzle, 
                                           camera, settings, threadSafeComposite);
    } else {
        return findOffsetsPostRotateParallel(part, boardLocation, placement, nozzle,
                                            camera, settings, threadSafeComposite);
    }
}

private PartAlignmentOffset findOffsetsPreRotateParallel(
        Part part, BoardLocation boardLocation, Placement placement,
        Nozzle nozzle, Camera camera, BottomVisionSettings settings,
        ThreadSafeComposite threadSafeComposite) throws Exception {
    
    double wantedAngle = placement.getLocation().getRotation();
    if (boardLocation != null) {
        wantedAngle = Utils2D.calculateBoardPlacementLocation(boardLocation, 
            placement.getLocation()).getRotation();
    }
    wantedAngle = Utils2D.angleNorm(wantedAngle, 180.);
    
    Location wantedLocation = getCameraLocationAtPartHeight(part, camera, nozzle, wantedAngle);
    Location nozzleLocation = wantedLocation;
    final Location center = new Location(maxLinearOffset.getUnits());
    
    try (CvPipeline pipeline = settings.getPipeline()) {
        Location offsets = new Location(nozzleLocation.getUnits());
        RotatedRect rect;
        
        for (int pass = 0;; pass++) {
            // Process pipeline with thread-safe accumulation
            rect = processPipelineParallel(pipeline, camera, part, nozzle,
                wantedLocation, nozzleLocation, settings, threadSafeComposite);
            
            // Wait for all shots to be processed and interpreted
            threadSafeComposite.interpret();
            
            if (camera.isDisplacementActive()) {
                Location displacement = nozzleLocation.derive(
                    camera.getXDisplacement(), camera.getYDisplacement(), null, null);
                nozzleLocation = nozzleLocation.subtract(displacement);
            }
            
            offsets = VisionUtils.getPixelCenterOffsets(camera, rect.center.x, rect.center.y);
            double angleOffset = VisionUtils.getPixelAngle(camera, rect.angle) - wantedAngle;
            
            if (settings.getMaxRotation() == MaxRotation.Adjust) {
                angleOffset = Utils2D.angleNorm(angleOffset);
            } else {
                angleOffset = Utils2D.angleNorm(angleOffset, 180);
            }
            
            offsets = offsets.rotateXy(-angleOffset)
                    .derive(null, null, null, angleOffset);
            nozzleLocation = nozzleLocation.subtractWithRotation(offsets);
            
            if (++pass >= maxVisionPasses) {
                break;
            }
            
            Point corners[] = new Point[4];
            rect.points(corners);
            Location corner = VisionUtils.getPixelCenterOffsets(camera, corners[0].x, corners[0].y)
                    .convertToUnits(maxLinearOffset.getUnits());
            Location cornerWithAngularOffset = corner.rotateXy(angleOffset);
            
            if (center.getLinearDistanceTo(offsets) > getMaxLinearOffset().getValue()
                || corner.getLinearDistanceTo(cornerWithAngularOffset) > getMaxLinearOffset().getValue()
                || Math.abs(angleOffset) > getMaxAngularOffset()) {
                // Try again with corrected position
                continue;
            }
            
            break;
        }
        
        offsets = wantedLocation.subtractWithRotation(nozzleLocation);
        offsets = offsets.subtract(settings.getVisionOffset().rotateXy(wantedAngle));
        
        offsetsCheck(part, nozzle, offsets);
        partSizeCheck(part, settings, rect, camera);
        
        return new PartAlignmentOffset(offsets, true);
    }
}
```

### Modified Pipeline Processing

```java
private RotatedRect processPipelineParallel(
        CvPipeline pipeline, Camera camera, Part part, Nozzle nozzle,
        Location wantedLocation, Location adjustedNozzleLocation,
        BottomVisionSettings settings, ThreadSafeComposite threadSafeComposite) 
        throws Exception {
    
    preparePipeline(pipeline, settings.getPipelineParameterAssignments(), 
                   camera, part.getPackage(), nozzle, nozzle.getNozzleTip(),
                   wantedLocation, adjustedNozzleLocation, settings);
    
    // Process each shot - accumulate results in thread-safe composite
    for (PipelineShot pipelineShot : pipeline.getPipelineShots()) {
        pipelineShot.apply();
        pipeline.process();
        
        Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
        if (result == null) {
            result = pipeline.getResult("result");
        }
        
        if (result == null) {
            throw new Exception(String.format(
                "ReferenceBottomVision (%s): Pipeline error. No result.", part.getId()));
        }
        
        if (!(result.model instanceof RotatedRect)) {
            throw new Exception(String.format(
                "ReferenceBottomVision (%s): Expected RotatedRect.", part.getId()));
        }
        
        // Thread-safe accumulation - no blocking here
        pipelineShot.processResult(result);
        
        // Accumulate in thread-safe composite
        // Get the shot from the pipeline shot's context
        // (This requires passing shot reference through the pipeline)
        // For now, we accumulate after all shots are done
    }
    
    // Get final result from composite after interpretation
    RotatedRect result = threadSafeComposite.getFinalResult();
    if (result == null) {
        // Fallback to current pipeline shot result
        result = (RotatedRect) pipeline.getCurrentPipelineShot()
            .processCompositeResult().getModel();
    }
    
    return result;
}
```

---

## Phase 5: Integration with Job Processor

**File:** `src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

### Modify Align Step to Use Parallel Processing

```java
/**
 * Alignment step - now supports parallel processing
 */
protected class Align extends PlannedPlacementStep {
    private ParallelAlign parallelAlign;
    
    public Align(List<PlannedPlacement> plannedPlacements) {
        super(plannedPlacements);
        this.parallelAlign = new ParallelAlign(plannedPlacements);
    }
    
    @Override
    public Step stepImpl(PlannedPlacement plannedPlacement) 
            throws JobProcessorException {
        
        if (plannedPlacement == null) {
            // All placements processed
            return new OptimizeNozzlesForPlace(plannedPlacements);
        }
        
        // Use parallel alignment for the entire group
        // This is called once at the start of the Align step
        if (plannedPlacement == plannedPlacements.get(0)) {
            parallelAlign.alignAllParallel();
            return this; // Will return null to signal completion
        }
        
        return this;
    }
    
    // Keep the original align method for fallback/single-placement cases
    private void align(PlannedPlacement plannedPlacement, PartAlignment partAlignment) 
            throws JobProcessorException {
        // Original sequential implementation
    }
}
```

---

## Configuration Changes

**File:** Configuration XML schema

```xml
<machine>
    <vision>
        <parallelAlignment enabled="true">
            <!-- Use separate executor for vision processing -->
            <useSeparateExecutor>true</useSeparateExecutor>
            
            <!-- Thread pool size (0 = auto-calculate) -->
            <maxThreads>0</maxThreads>
            
            <!-- Minimum placements to trigger parallel processing -->
            <minPlacementsForParallel>2</minPlacementsForParallel>
            
            <!-- Enable simultaneous camera capture (hardware dependent) -->
            <simultaneousCapture>false</simultaneousCapture>
        </parallelAlignment>
    </vision>
</machine>
```

---

## Testing Strategy

### Test 1: Thread Safety Verification

```java
@Test
public void testThreadSafeComposite() throws Exception {
    ThreadSafeComposite composite = new ThreadSafeComposite(baseComposite, 4);
    
    // Simulate concurrent shot accumulation
    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < 4; i++) {
        final int shotNum = i;
        futures.add(executor.submit(() -> {
            composite.accumulateShotDetection(shots.get(shotNum), rects.get(shotNum));
        }));
    }
    
    // Wait for all shots
    for (Future<?> f : futures) {
        f.get();
    }
    
    // Interpret should wait for all shots
    composite.interpret();
    
    // Verify result
    assertNotNull(composite.getFinalResult());
}
```

### Test 2: Parallel Alignment Performance

```java
@Test
public void testParallelAlignmentSpeedup() throws Exception {
    // Measure sequential time
    long sequentialStart = System.currentTimeMillis();
    alignSequential(placements);
    long sequentialTime = System.currentTimeMillis() - sequentialStart;
    
    // Measure parallel time
    long parallelStart = System.currentTimeMillis();
    alignParallel(placements);
    long parallelTime = System.currentTimeMillis() - parallelStart;
    
    // Verify speedup (at least 1.5x for 2 cameras)
    assertTrue("Parallel should be faster", 
        parallelTime < sequentialTime * 0.7);
}
```

---

## Rollback Plan

If issues are detected:

1. **Configuration flag**: Set `<parallelAlignment enabled="false">` to revert to sequential
2. **Fallback code**: Keep original `align()` method intact
3. **Monitoring**: Log timing and thread counts for debugging

---

## Summary of Key Changes

| Component | Change | Reason |
|-----------|--------|--------|
| `VisionCompositing.Composite` | Add `ThreadSafeComposite` wrapper | Thread-safe accumulation |
| `ReferencePnpJobProcessor` | Add `visionExecutor` | Separate from machine queue |
| `ReferencePnpJobProcessor.Align` | Add `ParallelAlign` class | Parallel group processing |
| `ReferenceBottomVision` | Add `findOffsetsParallel()` | Thread-safe alignment |
| Configuration | Add parallel settings | Runtime control |

---

*Plan created: 2026-04-12*
*Addresses issues from qwen122b_objections.md*
