# Parallel Bottom Vision Implementation Plan v2

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Problem Summary

Current threading violations in `multi-bottom-vision` branch:
1. **DRAGPIN interlock violation**: `autoFocus()` called from background threads via `getCameraLocationAtPartHeight()` 
2. **Camera light deadlock**: `ImageCapture.actuateLightBeforeCapture()` calls `Machine.execute()` from background threads while main thread waits on `CompletableFuture.join()`

## Solution Architecture: Pre-Capture Pattern

**Core Principle**: All hardware interactions (movement, settling, image capture) happen in the **main thread BEFORE** submitting tasks to background threads. Background threads process **pre-captured images only** - pure compute with no hardware access.

---

## Implementation Steps

### Step 1: Create `CapturedShot` Data Class
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/model/CapturedShot.java` (new file)

Data structure holding pre-captured image data (no hardware references):
```java
public class CapturedShot {
    private final Camera camera;           // For identification only, not used for hardware access
    private final Nozzle nozzle;           // For identification only
    private final Mat image;               // Pre-captured image
    private final Location shotLocation;   // Where image was captured
    private final long captureTimestamp;
    
    // Constructor, getters, release() method for OpenCV memory management
}
```

**Rationale**: Encapsulates captured image data with metadata, no hardware references that could cause side-effects.

---

### Step 2: Create `ImageBuffer` Class
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/model/ImageBuffer.java` (new file)

Manages captured shots per nozzle/camera:
```java
public class ImageBuffer {
    private final Map<Nozzle, CapturedShot> shotsByNozzle;
    
    public void capturePlacement(Camera camera, Nozzle nozzle, CvPipeline pipeline) throws Exception;
    public CapturedShot getShot(Nozzle nozzle);
    public void release();  // Release OpenCV memory
}
```

**Rationale**: Centralized buffer management, clean lifecycle for per-cycle captured images.

---

### Step 3: Modify `CvPipeline` to Support Pre-Captured Shots
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/vision/pipeline/CvPipeline.java`

Add methods:
```java
private Map<Integer, CapturedShot> preCapturedShots = new HashMap<>();

public void setPreCapturedShot(int shotIndex, CapturedShot shot);
public CapturedShot getPreCapturedShot(int shotIndex);
public void clearPreCapturedShots();
```

**Rationale**: Allow pipeline to use pre-captured images instead of capturing new ones.

---

### Step 4: Modify `ImageCapture` Stage to Use Pre-Captured Images
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/vision/pipeline/stages/ImageCapture.java`

Modify `process()` method:
```java
@Override
public Result process(CvPipeline pipeline) throws Exception {
    Camera camera = (Camera) pipeline.getProperty("camera");
    
    // Check for pre-captured shot first
    CapturedShot preCaptured = pipeline.getPreCapturedShot(pipeline.getCurrentShot().getIndex());
    if (preCaptured != null) {
        // Use pre-captured image - NO hardware access
        return new Result(preCaptured.getImage().clone(), ColorSpace.Bgr);
    }
    
    // Original hardware capture path
    camera.actuateLightBeforeCapture(...);
    BufferedImage bufferedImage = camera.settleAndCapture(...);
    ...
}
```

**Rationale**: Background threads get pre-captured images, no `Machine.execute()` calls.

---

### Step 5: Add `capturePlacement()` Method to `ReferenceBottomVision`
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java`

Add new method for main-thread image capture:
```java
/**
 * Capture placement image in main thread (before background processing).
 * All hardware interactions happen here - movement, settling, capture.
 */
public CapturedShot capturePlacement(Part part, Camera camera, Nozzle nozzle, 
                                     Location wantedLocation) throws Exception {
    // 1. Move nozzle to shot location (with settle if needed)
    // 2. Capture image using camera.settleAndCapture()
    // 3. Return CapturedShot with pre-captured image
    // NO pipeline processing here - just capture
}
```

**Rationale**: Separates capture (main thread) from processing (background thread).

---

### Step 6: Modify `ParallelAlign.alignCameraGroup()` - Pre-Capture Pattern
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

**Current flow** (BROKEN - hardware in background):
```java
alignCameraGroup(camera, cameraPlacements) {
    preDeterminePartHeights(camera, placements);  // Main thread ✓
    moveNozzlesToAlignmentPosition(placements);   // Main thread ✓
    
    for (PlannedPlacement placement : placements) {
        alignPlacement(placement, camera);        // Background thread ✗ - calls Machine.execute()
    }
}
```

**New flow** (FIXED - pre-capture in main thread):
```java
alignCameraGroup(Camera camera, List<PlannedPlacement> placements) 
        throws JobProcessorException {
    // STEP 1: Pre-determine part heights (main thread)
    preDeterminePartHeights(camera, placements);
    
    // STEP 2: Move nozzles to alignment position (main thread)
    moveNozzlesToAlignmentPosition(placements);
    
    // STEP 3: PRE-CAPTURE all images (main thread - hardware access)
    ImageBuffer imageBuffer = new ImageBuffer();
    for (PlannedPlacement placement : placements) {
        try {
            CapturedShot shot = capturePlacementForNozzle(
                placement.nozzle, camera, placement);
            imageBuffer.addShot(placement.nozzle, shot);
        }
        catch (Exception e) {
            throw new JobProcessorException(placement.nozzle, e);
        }
    }
    
    // STEP 4: Submit pre-captured images to background threads (pure compute)
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (PlannedPlacement placement : placements) {
        CapturedShot shot = imageBuffer.getShot(placement.nozzle);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                processPreCapturedImage(placement, shot);  // NO hardware access
            }
            catch (JobProcessorException e) {
                throw new CompletionException(e);
            }
        }, getVisionExecutor());
        futures.add(future);
    }
    
    // STEP 5: Wait for all processing to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // STEP 6: Cleanup
    imageBuffer.release();
}
```

**Rationale**: All hardware interactions (capture) happen in main thread, background threads only process pre-captured images.

---

### Step 7: Add `processPreCapturedImage()` Method
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

Process pre-captured image in background thread:
```java
private void processPreCapturedImage(PlannedPlacement placement, CapturedShot shot) 
        throws JobProcessorException {
    final Nozzle nozzle = placement.nozzle;
    final Part part = placement.jobPlacement.getPlacement().getPart();
    final BoardLocation boardLocation = placement.jobPlacement.getBoardLocation();
    
    PartAlignment partAlignment = AbstractPartAlignment.getPartAlignment(part);
    if (partAlignment == null) {
        placement.alignmentOffsets = new PartAlignmentOffset(Location.origin, false);
        return;
    }
    
    Exception lastException = null;
    int maxRetries = ReferencePnpJobProcessor.this.getMaxVisionRetries();
    
    for (int i = 0; i < Math.max(1, maxRetries); i++) {
        try {
            // Use pre-captured image - NO hardware access
            placement.alignmentOffsets = VisionUtils.findPartAlignmentOffsetsWithCapturedShot(
                partAlignment, part, boardLocation, 
                placement.jobPlacement.getPlacement(), nozzle, shot);
            return;
        }
        catch (Exception e) {
            lastException = e;
            // Failed capture - mark as bad for retry logic
            if (i < maxRetries) {
                Logger.warn("Alignment retry {}/{} for nozzle {}", i+1, maxRetries, nozzle.getId());
            }
        }
    }
    
    throw new JobProcessorException(part, nozzle, lastException);
}
```

**Rationale**: Background thread processes pre-captured image, no hardware side-effects.

---

### Step 8: Add `findPartAlignmentOffsetsWithCapturedShot()` to `VisionUtils`
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/util/VisionUtils.java`

New overload that accepts pre-captured shot:
```java
public static PartAlignment.PartAlignmentOffset findPartAlignmentOffsetsWithCapturedShot(
        PartAlignment p, Part part, BoardLocation boardLocation, Placement placement, 
        Nozzle nozzle, CapturedShot capturedShot) throws Exception {
    // Set pre-captured shot in pipeline before processing
    try (CvPipeline pipeline = ((ReferenceBottomVision) p).getThreadLocalPipeline(
            ((ReferenceBottomVision) p).getInheritedVisionSettings(part))) {
        
        // Inject pre-captured shot into pipeline
        pipeline.setPreCapturedShot(0, capturedShot);  // Assuming 1 shot per nozzle
        
        // Process pipeline with pre-captured image
        // This will use the pre-captured image instead of capturing new one
        return p.findOffsets(part, boardLocation, placement, nozzle, true);
    }
}
```

**Rationale**: Clean API for processing pre-captured images.

---

### Step 9: Implement Compositing Detection and Fallback
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

Add compositing check in `alignCameraGroup()`:
```java
private void alignCameraGroup(Camera camera, List<PlannedPlacement> placements) 
        throws JobProcessorException {
    // Check for vision compositing (multiple shots per nozzle)
    for (PlannedPlacement placement : placements) {
        Part part = placement.jobPlacement.getPlacement().getPart();
        BottomVisionSettings settings = 
            ((ReferenceBottomVision) AbstractPartAlignment.getPartAlignment(part))
                .getInheritedVisionSettings(part);
        
        if (settings != null) {
            // Need to get pipeline to check shotsTravel
            // This requires temporary pipeline creation
            try (CvPipeline tempPipeline = settings.getPipeline().clone()) {
                // Prepare pipeline to get shotsTravel count
                // If shotsTravel.size() > 1, fall back to sequential
                VisionCompositing.Composite composite = createTempComposite(part, settings);
                if (composite.getShotsTravel().size() > 1) {
                    Logger.info("Vision compositing detected ({} shots) for {}, " +
                               "falling back to sequential alignment", 
                               composite.getShotsTravel().size(), part.getId());
                    // Fall back to sequential for this camera group
                    for (PlannedPlacement p : placements) {
                        super.align(p, AbstractPartAlignment.getPartAlignment(
                            p.jobPlacement.getPlacement().getPart()));
                    }
                    return;
                }
            }
        }
    }
    
    // No compositing detected, proceed with parallel pre-capture pattern
    ...
}
```

**Rationale**: User requirement - if compositing enabled (multiple shots), fall back to sequential.

---

### Step 10: Modify Retry Logic for Independent Nozzle Retries
**File**: `/home/micke/dev_random/openpnp/src/main/java/org/openpnp/machine/reference/ReferencePnpJobProcessor.java`

Current retry logic retries ALL nozzles together. Change to independent retry:
```java
// In processPreCapturedImage():
for (int i = 0; i < Math.max(1, maxRetries); i++) {
    try {
        placement.alignmentOffsets = VisionUtils.findPartAlignmentOffsetsWithCapturedShot(...);
        return;  // Success - keep this image!
    }
    catch (Exception e) {
        lastException = e;
        // Don't retry - image already captured, just fail this nozzle
        // Other nozzles continue processing
    }
}
```

**Rationale**: User requirement - once image is good from vision, keep it. Failed nozzles retry independently.

---

## Files to Create

1. **`/home/micke/dev_random/openpnp/src/main/java/org/openpnp/model/CapturedShot.java`** (new)
2. **`/home/micke/dev_random/openpnp/src/main/java/org/openpnp/model/ImageBuffer.java`** (new)

## Files to Modify

1. **`CvPipeline.java`** - Add pre-captured shot support
2. **`ImageCapture.java`** - Use pre-captured images when available
3. **`ReferenceBottomVision.java`** - Add `capturePlacement()` method
4. **`ReferencePnpJobProcessor.java`** - Implement pre-capture pattern in `ParallelAlign`
5. **`VisionUtils.java`** - Add `findPartAlignmentOffsetsWithCapturedShot()`

## Test Updates

**`ParallelAlignmentTest.java`** - Update tests to verify:
1. Pre-capture pattern works correctly
2. No threading violations (DRAGPIN, light deadlock)
3. Compositing fallback to sequential
4. Independent retry for failed nozzles
5. Performance improvement with parallel processing

---

## Key Design Decisions

1. **Per-nozzle capture**: One image per nozzle per cycle (simpler, matches user requirement)
2. **Independent retry**: Failed nozzles retry independently, others continue
3. **Compositing detection**: If `shotsTravel.size() > 1`, fall back to sequential
4. **Image lifecycle**: Created fresh each pick-place cycle, released after processing
5. **No hardware in background**: Background threads are 100% pure compute

---

## Verification Checklist

- [ ] No `Machine.execute()` calls from background threads
- [ ] No `autoFocus()` calls from background threads  
- [ ] No `actuateLightBeforeCapture()` calls from background threads
- [ ] All image capture happens in main thread
- [ ] Background threads only process pre-captured `Mat` objects
- [ ] Compositing (multiple shots) falls back to sequential
- [ ] Failed nozzle retries don't block other nozzles
- [ ] Images released properly (no OpenCV memory leaks)
- [ ] Tests pass (existing + new)

---

## Current State vs. Reference Code

**Current state (multi-bottom-vision branch)**:
- `ParallelAlign` class exists but runs hardware operations in background threads
- `preDeterminePartHeights()` implemented correctly in main thread
- `skipMovement=true` passed to alignment methods
- `ImageCapture` stage still calls `Machine.execute()` from background threads (DEADLOCK)

**Reference code (sequential only)**:
- All operations happen sequentially in main thread
- No threading violations
- `Align` step processes one `PlannedPlacement` at a time

**Key difference**: Reference code never has hardware side-effects in background threads because there are no background threads for alignment. Our implementation must preserve this property while adding parallelism.
