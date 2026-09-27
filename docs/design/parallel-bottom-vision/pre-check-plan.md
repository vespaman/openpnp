# Pre-Alignment Check Implementation Plan

## Overview

Add a quick "part-on + size check" that runs in parallel with camera settling for ALL cameras (settle + non-settle), using the first available image from each camera. Results are logged when complete, with the intent to use the results in a future optimization phase.

## Problem Statement

Currently in parallel bottom vision:
- Settle camera captures multiple frames (10-46ms), discarding all but the final frame
- Non-settle cameras capture single frames asynchronously during settle
- Full alignment pipeline runs AFTER settle completes
- No early detection of "no part on nozzle" or "wrong part size"

**Goal**: Detect bad picks early by running a minimal check during settle, using the first available image from each camera.

## Requirements

1. **Part Presence Check**: TRUE/FALSE - is there a component on the nozzle tip?
2. **Size Check**: Compare detected component size against configured part dimensions (with 20% lenient tolerance)
3. **Timing**: Complete within ~30ms window (parallel with settle)
4. **Scope**: Only for parallel capture (single-shot parts)
5. **Configuration**: Auto-enable when parallel capture detected, but structure for future config option
6. **Output**: Logging of individual results + summary

## Timing Analysis

| Camera Type | Image Available | Pre-Check Window | Target Duration |
|-------------|-----------------|------------------|-----------------|
| **Settle Camera** | ~0ms (first frame) | 30ms | 10-20ms |
| **Non-Settle Cameras** | ~5-8ms (async capture) | 22-25ms | 10-20ms |

**Total time**: Max(settle_time, capture_time + precheck_time) ≈ 30-50ms

## Architecture

### Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    preCaptureAllImages()                        │
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │ Settle Camera   │    │ Other Cameras   │                    │
│  │                 │    │                 │                    │
│  │ [First Frame]───┼───▶│ [Capture]       │                    │
│  │      │          │    │      │          │                    │
│  │      ▼          │    │      ▼          │                    │
│  │ [Pre-Check]     │    │ [Pre-Check]     │                    │
│  │      │          │    │      │          │                    │
│  │      └────┬─────┘    │      └────┬─────┘                    │
│  │           │          │           │                          │
│  └───────────┼──────────┴───────────┼──────────┐               │
│              │                      │          │               │
│              ▼                      ▼          │               │
│         [Collect Results]──────────────────────┘               │
│                         │                                      │
│                         ▼                                      │
│              [Log Summary + Individual]                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  processPreCapturedImage()                      │
│                                                                 │
│  [Check Pre-Check Result]                                       │
│         │                                                       │
│         ├─ NO PART  ──▶ Skip alignment, log warning            │
│         ├─ SIZE BAD ──▶ Skip alignment, log warning            │
│         └─ OK       ──▶ Proceed with full alignment            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| `AbstractSettlingCamera` | Provide first-frame callback during settle |
| `ReferenceBottomVision` | Run quick detection + size check pipeline |
| `ReferencePnpJobProcessor` | Coordinate pre-check threads, collect results |
| `PreAlignmentCheckResult` | Data structure for check results |

## Implementation Steps

### Step 1: First-Frame Callback in AbstractSettlingCamera

**File**: `AbstractSettlingCamera.java`

Add a new method that optionally captures the first frame during settle:

```java
/**
 * Settle and capture, with optional first-frame callback.
 * @param settleOption Settle method
 * @param firstFrameCallback Optional callback invoked with first captured frame
 */
public BufferedImage settleAndCapture(SettleOption settleOption, 
                                      Consumer<BufferedImage> firstFrameCallback) throws Exception {
    // Inside the autoSettleAndCapture() while loop, after first capture:
    if (firstFrameCallback != null && lastSettleMat == null) {
        firstFrameCallback.accept(image);  // Capture first frame before processing
    }
    // ... rest of settling logic unchanged ...
}

// Keep existing method for backward compatibility:
public BufferedImage settleAndCapture(SettleOption settleOption) throws Exception {
    return settleAndCapture(settleOption, null);
}
```

**Lines**: ~15-20
**Risk**: Low (new method, existing code unchanged)

---

### Step 2: Quick Check Method in ReferenceBottomVision

**File**: `ReferenceBottomVision.java`

Add a method that runs a minimal pipeline for part detection + size check:

```java
/**
 * Quick pre-alignment check: detect part presence and verify size.
 * Uses the first captured frame, runs detection pipeline only (no alignment calculation).
 * 
 * @param image Pre-captured image
 * @param part Part being checked
 * @param camera Camera used
 * @return PreAlignmentCheckResult with present/size_ok/confidence
 */
public PreAlignmentCheckResult quickCheckPartPresence(Mat image, Part part, Camera camera) 
        throws Exception {
    
    BottomVisionSettings settings = getInheritedVisionSettings(part);
    Location expectedSize = settings.getPartCheckSize(part, false);
    
    // Clone pipeline for this check
    CvPipeline pipeline = getThreadLocalPipeline(settings);
    try {
        // Prepare pipeline for detection only (skip movement)
        preparePipeline(pipeline, settings.getPipelineParameterAssignments(),
                camera, part.getPackage(), nozzle, nozzleTip,
                wantedLocation, wantedLocation, settings, true);
        
        // Process pipeline (runs detection stages)
        pipeline.process();
        
        // Get result (RotatedRect from MinAreaRect)
        Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
        RotatedRect rect = (RotatedRect) result.model;
        
        // Check presence (rect exists and has reasonable size)
        boolean present = rect != null && rect.size.width > 0 && rect.size.height > 0;
        
        // Check size if expected size configured
        boolean sizeOk = true;
        if (present && expectedSize != null) {
            // Use lenient tolerance (configured + 20%)
            double lenientTolerance = settings.getCheckSizeTolerancePercent() * 1.2;
            sizeOk = checkSizeWithTolerance(rect, expectedSize, lenientTolerance, camera);
        }
        
        // Calculate confidence based on detection quality
        double confidence = calculateDetectionConfidence(rect, image);
        
        return new PreAlignmentCheckResult(present, sizeOk, confidence);
        
    } finally {
        pipeline.close();
    }
}
```

**Lines**: ~50-60
**Risk**: Low (new method)

---

### Step 3: PreAlignmentCheckResult Data Structure

**File**: `PreAlignmentCheckResult.java` (new)

```java
package org.openpnp.model;

import java.util.concurrent.CompletableFuture;

/**
 * Result of a pre-alignment check.
 */
public class PreAlignmentCheckResult {
    private final boolean partPresent;
    private final boolean sizeOk;
    private final double confidence;
    private final CompletableFuture<PreAlignmentCheckResult> future;
    private final long checkDurationMs;
    
    // Synchronous result
    public PreAlignmentCheckResult(boolean partPresent, boolean sizeOk, double confidence) {
        this.partPresent = partPresent;
        this.sizeOk = sizeOk;
        this.confidence = confidence;
        this.future = CompletableFuture.completedFuture(this);
        this.checkDurationMs = 0;
    }
    
    // Async result with duration
    public PreAlignmentCheckResult(PreAlignmentCheckResult syncResult, long checkDurationMs) {
        this.partPresent = syncResult.partPresent;
        this.sizeOk = syncResult.sizeOk;
        this.confidence = syncResult.confidence;
        this.future = CompletableFuture.completedFuture(syncResult);
        this.checkDurationMs = checkDurationMs;
    }
    
    public boolean isPartPresent() { return partPresent; }
    public boolean isSizeOk() { return sizeOk; }
    public double getConfidence() { return confidence; }
    public long getCheckDurationMs() { return checkDurationMs; }
    
    public boolean isComplete() { return future.isDone(); }
    
    public PreAlignmentCheckResult getNow() throws Exception {
        return future.get();
    }
}
```

**Lines**: ~50
**Risk**: Low (new file)

---

### Step 4: Integrate into preCaptureAllImages

**File**: `ReferencePnpJobProcessor.java`

This is the core integration - spawn pre-check threads for BOTH settle and non-settle cameras.

#### 4.1 Add Tracking Variables

```java
// Add at the start of preCaptureAllImages():
List<CompletableFuture<PreAlignmentCheckResult>> preCheckFutures = new ArrayList<>();
Map<Nozzle, PreAlignmentCheckResult> preCheckResults = new ConcurrentHashMap<>();

// Check if parallel capture is active (pre-check only enabled for parallel)
boolean isParallelCaptureActive = cameras.size() > 1 || 
    (settleCameraPlacements != null && settleCameraPlacements.size() > 1);
```

#### 4.2 Non-Settle Cameras: Chain Pre-Check to Capture

```java
for (Camera captureCamera : cameras) {
    if (captureCamera.equals(settleCamera)) continue;
    
    for (PlannedPlacement placement : captureCameraPlacements) {
        // ... existing async capture code ...
        
        final Camera cam = captureCamera;
        final Nozzle noz = nozzle;
        final Part prt = part;
        final ReferenceBottomVision bv = bottomVision;
        
        CompletableFuture<PreCapturedPlacement> captureFuture = 
            CompletableFuture.supplyAsync(() -> {
                return bv.captureImageOnly(prt, cam, noz, loc, firstTip);
            });
        
        // Chain pre-check to capture completion (if parallel active)
        if (isParallelCaptureActive) {
            CompletableFuture<PreAlignmentCheckResult> preCheckFuture = 
                captureFuture.thenApplyAsync(shot -> {
                    long start = System.currentTimeMillis();
                    try {
                        PreAlignmentCheckResult result = bv.quickCheckPartPresence(
                            shot.getImage(), prt, cam);
                        long duration = System.currentTimeMillis() - start;
                        result = new PreAlignmentCheckResult(result, duration);
                        Logger.debug("Pre-check {} camera={}, part={}, duration={}ms",
                            result.isPartPresent() && result.isSizeOk() ? "PASS" : "FAIL",
                            cam.getName(), prt.getId(), duration);
                        return result;
                    } catch (Exception e) {
                        Logger.warn("Pre-check failed: {}", e.getMessage());
                        return new PreAlignmentCheckResult(true, true, 0.0); // Fail open
                    }
                }, getVisionExecutor());
            
            preCheckFutures.add(preCheckFuture);
        }
    }
}
```

#### 4.3 Settle Camera: Pre-Check During Settle

```java
if (settleCameraPlacements != null && !settleCameraPlacements.isEmpty()) {
    PlannedPlacement firstPlacement = settleCameraPlacements.get(0);
    Camera camera = settleCamera;
    Nozzle nozzle = firstPlacement.nozzle;
    Part part = firstPlacement.jobPlacement.getPlacement().getPart();
    
    if (!needsMultipleShots(part)) {
        // Capture first frame for pre-check (only if parallel active)
        final BufferedImage[] firstFrameHolder = new BufferedImage[1];
        
        PreCapturedPlacement shot;
        if (isParallelCaptureActive) {
            shot = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                .captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, 
                    isFirstNozzleTip, firstFrame -> {
                        firstFrameHolder[0] = firstFrame;
                    });
        } else {
            shot = ((ReferenceBottomVision)AbstractPartAlignment.getPartAlignment(part))
                .captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, 
                    isFirstNozzleTip);
        }
        
        imageBuffer.addShot(nozzle, shot);
        
        // Spawn pre-check thread immediately (while settle continues)
        if (isParallelCaptureActive && firstFrameHolder[0] != null) {
            CompletableFuture<PreAlignmentCheckResult> preCheckFuture = 
                CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        Mat firstFrameMat = OpenCvUtils.toMat(firstFrameHolder[0]);
                        PreAlignmentCheckResult result = bv.quickCheckPartPresence(
                            firstFrameMat, part, camera);
                        firstFrameHolder[0].release();
                        long duration = System.currentTimeMillis() - start;
                        result = new PreAlignmentCheckResult(result, duration);
                        Logger.debug("Pre-check {} camera={}, part={}, duration={}ms",
                            result.isPartPresent() && result.isSizeOk() ? "PASS" : "FAIL",
                            camera.getName(), part.getId(), duration);
                        return result;
                    } catch (Exception e) {
                        Logger.warn("Pre-check failed: {}", e.getMessage());
                        return new PreAlignmentCheckResult(true, true, 0.0);
                    }
                }, getVisionExecutor());
            
            preCheckFutures.add(preCheckFuture);
        }
    }
}
```

#### 4.4 Wait for Pre-Checks and Log Results

```java
// After PASS 1 completes (async captures done)
if (isParallelCaptureActive && !preCheckFutures.isEmpty()) {
    Logger.trace("Waiting for {} pre-alignment checks to complete", preCheckFutures.size());
    
    long preCheckStart = System.currentTimeMillis();
    CompletableFuture.allOf(preCheckFutures.toArray(new CompletableFuture[0])).join();
    long preCheckTotalTime = System.currentTimeMillis() - preCheckStart;
    
    // Collect results
    for (CompletableFuture<PreAlignmentCheckResult> future : preCheckFutures) {
        try {
            PreAlignmentCheckResult result = future.get();
            preCheckResults.put(result.getNozzle(), result);
        } catch (Exception e) {
            Logger.warn("Pre-check result retrieval failed: {}", e.getMessage());
        }
    }
    
    // Log summary
    int passCount = (int) preCheckResults.values().stream()
        .filter(r -> r.isPartPresent() && r.isSizeOk()).count();
    int failCount = preCheckResults.size() - passCount;
    
    Logger.info("Pre-alignment check: {}/{} passed, {}/{} failed, total time={}ms",
        passCount, preCheckResults.size(), failCount, preCheckResults.size(), 
        preCheckTotalTime);
    
    // Log individual results
    preCheckResults.forEach((nozzle, result) -> {
        String status = result.isPartPresent() && result.isSizeOk() ? "PASS" : "FAIL";
        String details = result.isPartPresent() ? 
            (result.isSizeOk() ? "size ok" : "size mismatch") : 
            "no part detected";
        Logger.info("  {} nozzle={}, status={}, duration={}ms", 
            status, nozzle.getName(), details, result.getCheckDurationMs());
    });
}
```

**Lines**: ~150-200
**Risk**: Medium (core logic modification)

---

### Step 5: Use Pre-Check Results in Alignment

**File**: `ReferencePnpJobProcessor.java`

Modify `processPreCapturedImage()` to check pre-alignment results:

```java
private void processPreCapturedImage(PlannedPlacement placement, CapturedShot shot) 
        throws JobProcessorException {
    
    // Check pre-alignment result if available (parallel capture mode)
    PreAlignmentCheckResult preCheck = preCheckResults.get(placement.nozzle);
    if (preCheck != null) {
        if (!preCheck.isPartPresent()) {
            Logger.warn("Pre-check: NO PART on nozzle {}, skipping alignment", 
                placement.nozzle.getName());
            placement.alignmentOffsets = new PartAlignmentOffset(
                new Location(LengthUnit.Millimeters), false);
            return;
        }
        if (!preCheck.isSizeOk()) {
            Logger.warn("Pre-check: SIZE MISMATCH on nozzle {}, skipping alignment",
                placement.nozzle.getName());
            placement.alignmentOffsets = new PartAlignmentOffset(
                new Location(LengthUnit.Millimeters), false);
            return;
        }
    }
    
    // ... existing full alignment code ...
}
```

**Lines**: ~15-20
**Risk**: Low (adds early exit path)

---

## Files Modified

| File | Lines Added | Lines Modified | Risk |
|------|-------------|----------------|------|
| `AbstractSettlingCamera.java` | 15-20 | 0 | Low |
| `ReferenceBottomVision.java` | 50-60 | 0 | Low |
| `PreAlignmentCheckResult.java` | 50 | 0 | Low |
| `ReferencePnpJobProcessor.java` | 150-200 | 10-15 | Medium |

**Total**: ~265-330 lines added, ~10-15 modified

---

## Design Decisions

### 1. Lenient Size Tolerance (20% Fixed)
- **Decision**: Use configured tolerance × 1.2
- **Rationale**: Pre-check is preliminary; full alignment will do precise check
- **Future**: Make configurable if needed

### 2. Fail Open
- **Decision**: If pre-check throws exception, assume part is present
- **Rationale**: Better to waste alignment time than miss a good pick
- **Logging**: Warn on failures for debugging

### 3. Auto-Enable on Parallel Capture
- **Decision**: Enable when `cameras.size() > 1` or multiple placements
- **Rationale**: Only useful in parallel mode
- **Future**: Restructure with config option pattern for easy override

### 4. Multi-Shot Parts Excluded
- **Decision**: Skip pre-check for multi-shot parts
- **Rationale**: Already handled sequentially; pre-check would duplicate work

### 5. Logging Strategy
- **Decision**: Individual results at DEBUG, summary at INFO
- **Rationale**: Minimal noise in normal operation, detailed info available

---

## Future Considerations

### Phase 2: Act on Pre-Check Results
The current implementation logs results but doesn't significantly change behavior (fails open). Future phases could:
- Skip full alignment for failed pre-checks (saves time)
- Retry pick for failed pre-checks
- Adjust alignment parameters based on pre-check confidence
- Use pre-check data for pick quality metrics

### Configuration Option
When ready to add config:
```java
@Attribute(required = false)
protected boolean enablePreAlignmentCheck = true;  // Default: enabled for parallel
```

### Performance Metrics
Track and report:
- Pre-check success/fail rates
- Average pre-check duration per camera/part type
- Time saved by skipping full alignment

---

## Testing Strategy

### Unit Tests
1. `PreAlignmentCheckResult` - basic functionality
2. `quickCheckPartPresence()` - detection accuracy, size check
3. Edge cases: no part, wrong part, rotated part

### Integration Tests
1. Parallel alignment with pre-check enabled
2. Verify timing (< 30ms total)
3. Verify logging output
4. Verify fail-open behavior

### Performance Tests
1. Measure pre-check duration per camera
2. Measure total parallel alignment time (with/without pre-check)
3. Compare against baseline (no pre-check)

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Pre-check too slow | Log duration, tune algorithm if > 20ms |
| False negatives (miss good picks) | Fail open, lenient tolerance |
| False positives (bad picks pass) | Full alignment still runs |
| Memory leaks | Release first frame Mat in finally block |
| Threading issues | Use CompletableFuture, visionExecutor |

---

## Implementation Checklist

- [ ] Step 1: Add first-frame callback to `AbstractSettlingCamera`
- [ ] Step 2: Add `quickCheckPartPresence()` to `ReferenceBottomVision`
- [ ] Step 3: Create `PreAlignmentCheckResult` class
- [ ] Step 4: Integrate pre-check into `preCaptureAllImages()`
  - [ ] Add tracking variables
  - [ ] Non-settle camera pre-check (chain to capture)
  - [ ] Settle camera pre-check (during settle)
  - [ ] Wait for pre-checks and log results
- [ ] Step 5: Modify `processPreCapturedImage()` to use pre-check results
- [ ] Add unit tests
- [ ] Add integration tests
- [ ] Performance testing
- [ ] Documentation

---

## Notes

- Multi-shot parts are already excluded from parallel capture (handled in PASS 2)
- Pre-check only runs when parallel capture is active
- All pre-check failures are logged for debugging
- Future phases can make pre-check results more actionable
