# Parallel Camera Capture - Revised Plan Summary (v2)

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Executive Summary

Implement parallel image capture during settle test using ForkJoinPool.commonPool() for I/O bound camera captures, preserving visionExecutor for OpenCV processing. Two-pass system handles multi-shot parts sequentially.

## Key Corrections from v1

| Issue | v1 | v2 |
|-------|-----|-----|
| Thread pool | `getVisionExecutor()` | `ForkJoinPool.commonPool()` (default) |
| Light control | New `skipLightControl` parameter | Use existing API, batch operation handles it |
| Multi-shot | Skipped entirely | Two-pass: async + sequential fallback |

## Files to Modify

### 1. ReferencePnpJobProcessor.java

**Changes:**
- Add import: `java.util.concurrent.CompletableFuture;`
- Replace `preCaptureAllImages()` method body (lines 2044-2130)

**No changes needed to ReferenceBottomVision.java** - uses existing `captureImageOnly()` method.

## Implementation Overview

### Pass 1 (Parallel) - Before Settle

```java
// Trigger async captures for single-shot parts
for (Camera captureCamera : cameras) {
    if (captureCamera.equals(settleCamera)) continue;
    
    for (PlannedPlacement placement : placementsByCamera.get(captureCamera)) {
        if (needsMultipleShots(part)) continue; // Skip for Pass 2
        
        CompletableFuture.supplyAsync(() -> 
            bottomVision.captureImageOnly(part, camera, nozzle, location, isFirstTip));
    }
}
```

### Settle Loop - Main Thread Blocks

```java
// Main thread performs settle (10-12ms)
// Async captures run in parallel on commonPool
captureImageOnlyWithSettle(...);
```

### Sync - After Settle

```java
// Wait for async captures (very fast)
CompletableFuture.allOf(futures).join();
```

### Pass 2 (Sequential) - Multi-shot Fallback

```java
// Handle multi-shot parts that need head movement
for (PlannedPlacement placement : allPlacements) {
    if (!needsMultipleShots(part)) continue;
    
    // Sequential capture with movement
    captureImageOnlyWithSettle(...);
}
```

## Thread Pool Rationale

### ForkJoinPool.commonPool() (v2)

- **Parallelism**: `Runtime.getRuntime().availableProcessors() - 1` = 7 threads on 8-core CPU
- **Best for**: I/O bound work (USB camera capture)
- **Doesn't block**: visionExecutor threads

### visionExecutor (v1 - incorrect)

- **Pool size**: `CPU cores / 2` = 4 threads on 8-core CPU
- **Best for**: CPU bound work (OpenCV image processing)
- **Would block**: If used for I/O, Starves OpenCV processing

## Expected Performance

### Single-Shot Parts (Best Case)

| Cameras | v1 | v2 | Improvement |
|---------|-----|-----|-------------|
| 2 | ~15ms | ~14ms | ~1ms |
| 4 | ~15ms | ~13ms | ~2ms |

### Mixed Single/Multi-shot

| Scenario | Time | Notes |
|----------|------|-------|
| 2 single + 1 multi | ~18ms | Parallel + sequential |
| 4 multi-shot | ~35ms | Falls back to sequential |

## Testing Checklist

- [ ] Compile successfully
- [ ] Single-shot parts: verify parallel execution in logs
- [ ] Multi-shot parts: verify sequential fallback
- [ ] Mixed workloads: verify hybrid behavior
- [ ] Monitor ForkJoinPool vs visionExecutor thread usage
- [ ] Measure alignment time improvement
- [ ] Verify no light control issues (batch operation)
- [ ] Test error handling (async capture failures)

## Risk Assessment

| Risk | Probability | Mitigation |
|------|-------------|------------|
| Compilation error | Low | Straightforward code changes |
| Runtime lockup | Very Low | commonPool isolated from main thread |
| Light control issues | Very Low | Batch operation handles natively |
| Multi-shot regression | Low | Sequential fallback preserves behavior |
| Thread starvation | None | commonPool doesn't use visionExecutor |

## Rollback Plan

1. **Quick**: Revert ReferencePnpJobProcessor.java changes
2. **Partial**: Comment out Pass 1 async code - falls back to all sequential
3. **Debug**: Add trace logging for async task lifecycle

## Documentation

- `parallel-camera-capture-implementation-v2.md` - Full code changes
- `parallel-camera-capture-timing.md` - Timing diagrams (still valid)
- `parallel-camera-capture-summary.md` - Original summary (outdated)

## Next Steps

1. Review v2 implementation in `parallel-camera-capture-implementation-v2.md`
2. Confirm corrections address all concerns
3. Ready to implement? Say "proceed" and I'll make the changes.

---

## Summary of Corrections

1. ✅ **Thread Pool**: Changed from `getVisionExecutor()` to `ForkJoinPool.commonPool()`
2. ✅ **API Purity**: Removed `skipLightControl` parameter, use existing methods
3. ✅ **Multi-shot Fallback**: Implemented two-pass system with sequential fallback

The revised plan is cleaner, more efficient, and better aligned with OpenPnP's architecture.
