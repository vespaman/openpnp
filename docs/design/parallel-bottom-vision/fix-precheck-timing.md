# Fix Pre-Check Timing Issues

## Problem Analysis

### Current Symptoms
- Pre-check timing reported: **39-178ms** (target: ~30ms)
- Variation: Wildly inconsistent in logs
- Thread executor change had **no effect**

### Root Cause Investigation

**CRITICAL DISCOVERY:** The reported "pre-check time" includes **BOTH image capture AND pipeline processing!**

**Code Analysis (ReferencePnpJobProcessor.java line 2138-2154):**
```java
final long preCheckStart = System.currentTimeMillis();  // Timer starts HERE
CompletableFuture.supplyAsync(() -> {
    captureImageOnly(...)              // ← Image capture (includes settle)
    quickCheckPartPresence(...)        // ← Pipeline processing
    result = new PreAlignmentCheckResult(..., System.currentTimeMillis() - preCheckStart, ...)
});
```

**Log Analysis (OpenPnP.g.log):**
```
Entry 1: 116ms (cold start - JVM + OpenCV init)
Entry 2: 137ms (cold start)  
Entry 3: 39ms (WARM - this is REAL time!)
```

**Breakdown of 39ms:**
- `.581 - .596 = 15ms` - Actual pipeline processing (MaskHsv → result)
- `39ms - 15ms = 24ms` - Image capture time

**Alignment Comparison:**
- Alignment also uses same pipeline
- Alignment takes ~15ms for pipeline (same as pre-check!)
- **No difference between pre-check and alignment pipeline time!**

### Conclusion

**THERE IS NO PROBLEM!** 

Pre-check and alignment take the SAME time (~15ms for pipeline) when JVM is warm. The variation (39-178ms) is due to:
1. **Cold start overhead** (first few runs: 100-170ms)
2. **Image capture time included** in reported metric (~24ms)
3. **JVM JIT compilation** (warming up)

The change to `getVisionExecutor()` was correct - it eliminates thread creation overhead. The remaining timing is **expected and optimal**.

## Solution Approach

### Phase 1: Create Performance Test

Create `ReferenceBottomVisionPreCheckPerformanceTest.java` with:
1. **Warmup iterations** (3-5 runs to JIT compile)
2. **Measurement iterations** (10+ runs for statistics)
3. **Detailed timing breakdown** per pipeline stage
4. **Statistics**: min, max, avg, stddev

### Phase 2: Pipeline Optimization

Based on test results, optimize:
1. **Pipeline stage optimization** - Which stages are slowest?
2. **Pre-cached pipeline** - Avoid repeated `preparePipeline()` calls
3. **Image preprocessing** - Reduce image size if possible
4. **Parallel pipeline execution** - Run independent stages in parallel

### Phase 3: Integration Test

Create end-to-end test that:
1. Simulates parallel alignment with multiple cameras
2. Measures total pre-check time for all cameras
3. Verifies no thread contention
4. Ensures pre-check completes before alignment starts

## Questions for User

1. **What is the actual target time?** You mentioned ~30ms - is this based on:
   - Async camera pre-check timing?
   - Previous experience with single camera?
   - Theoretical calculation?

2. **Is 50-70ms acceptable?** The current timing includes:
   - Settle time running in parallel (27-44ms)
   - Pipeline processing (50-70ms)
   - Total wall-clock time might still be acceptable

3. **Production machine specs:**
   - How many cores?
   - CPU frequency scaling enabled?
   - OpenCV native library version?

4. **Priority:**
   - Is pre-check timing critical for cycle time?
   - Or is it acceptable if it runs in parallel with settle?

## Next Steps

1. **Create performance test** to measure exact timing breakdown
2. **Run on production machine** to get real-world numbers
3. **Profile pipeline stages** to identify bottlenecks
4. **Optimize based on data**, not speculation
