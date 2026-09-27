# Parallel Camera Capture - Timing Diagram

## Before (Sequential Capture)

```
Timeline (ms):  0         5         10        15        20        25        30
                |         |         |         |         |         |         |
Main Thread:    ├─Move───┤├─Settle(CAM1)──────┤├─Capture(CAM2)─┤├─Process...
                |         |    10-12ms         |    3-5ms        |
                |         |                     |                 |
Background:     |         |                     |                 └─Vision processing
                |         |                     |
CAM1 (Settle):  |         ├─[capture×N]─────────┤  (auto-settle loop)
                |         |   until stable      |
CAM2 (Secondary)|         |                     ├─capture()
                |         |                     |
Latency:        └───────────────────────────────────────┘ = ~25-30ms total
                        ^ CAM2 waits for CAM1 settle to finish
```

## After (Parallel Capture)

```
Timeline (ms):  0         5         10        15        20        25        30
                |         |         |         |         |         |         |
Main Thread:    ├─Move───┤├─Settle(CAM1)──────┤├─Sync──┤├─Process...
                |         |    10-12ms         |  <1ms   |
                |         |                     |         |
Background T1:  |         |                     |         └─Vision processing
                |         |                     |
Background T2:  |         |                     ├─[Capture CAM2]  (runs parallel!)
                |         |   └─async capture started here        |
CAM1 (Settle):  |         ├─[capture×N]─────────┤  (auto-settle loop)
                |         |   until stable      |
CAM2 (Secondary)|         |   └─async capture runs here           |
                |         |                     |
Latency:        └───────────────────┘ = ~15ms total
                        ^ CAM2 captures DURING CAM1 settle
Gain:                   └──────────────────────────── = ~10-12ms saved (40-50%)
```

## Key Observations

1. **Async Trigger Point**: Right after `waitForCompletion(Stillstand)`, before settle loop
   - Machine is guaranteed idle
   - No resource contention with motion planner

2. **Parallel Execution**: 
   - Main thread: Settles CAM1 (10-12ms)
   - Background thread: Captures CAM2 (3-5ms)
   - Both complete in ~12ms (max of the two)

3. **Sync Point**: After settle completes
   - `CompletableFuture.join()` - returns immediately if capture done
   - If capture not done (slow camera), waits only remaining time

4. **Light Control**: 
   - All lights on before captures
   - `CameraBatchOperation` turns off ALL lights after ALL captures complete
   - Async captures use `skipLightControl=true`

## Thread Pool Utilization

```
visionExecutor (pool size = CPU cores / 2)

Before:
  Thread 1: [Process CAM1] [Process CAM2]  (sequential processing)
  Thread 2: [Process CAM3] [Process CAM4]  (if 4 cameras)

After:
  Thread 1: [Capture CAM2] [Process CAM1]  (parallel capture + processing)
  Thread 2: [Capture CAM4] [Process CAM3]  (better utilization)
```

## Edge Cases

### Case 1: Secondary camera slower than settle
```
Settle:     ├───────┤ (10ms)
CAM2:       ├──────────────┤ (15ms)
Sync waits:          └─────┤ (5ms wait)
Total:      └──────────────┤ (15ms, not 25ms)
```
Still better than sequential (25ms)

### Case 2: Very fast settle (<5ms)
```
Settle:     ├───┤ (5ms)
CAM2:       ├───────┤ (10ms)
Sync waits:      └───┤ (5ms wait)
Total:      └───────┤ (10ms, not 15ms)
```
Still ~33% improvement

### Case 3: 4 cameras
```
Sequential: Settle(10) + Capture(3) + Capture(3) + Capture(3) = 19ms
Parallel:   Settle(10) + max(Capture×3) = 10-13ms
Improvement: ~40-50%
```

## Why No Lockup?

Previous lockup (~1 second) was likely caused by:

1. **Motion Planner Contention**: Triggering captures WHILE `waitForCompletion()` was running
   - Solution: Trigger AFTER `waitForCompletion(Stillstand)` returns

2. **Shared Machine Resources**: G-code command queue contention
   - Solution: Captures don't use motion planner, only camera hardware

3. **AWT/UI Thread Blocking**: Image processing on UI thread
   - Solution: All capture+processing on visionExecutor background threads

4. **OpenCV Native Thread Starvation**: Too many concurrent OpenCV operations
   - Solution: visionExecutor pool limits concurrency (CPU cores / 2)

## Verification Steps

1. **Check async trigger timing**:
   ```
   Logger.trace("Async capture started for camera {}", cam.getName());
   ```
   Should appear BEFORE settle loop completes

2. **Check parallel execution**:
   ```
   Logger.trace("Auto settle...")      (main thread)
   Logger.trace("Async capture...")    (background thread)
   ```
   These should interleave in logs

3. **Check sync time**:
   ```
   Logger.trace("Waiting for X async captures...")
   Logger.trace("All async captures complete")
   ```
   Time between should be <1ms if parallelism worked

4. **Measure total time**:
   ```
   Logger.debug("Parallel alignment completed in X ms")
   ```
   Should see ~10-12ms improvement over baseline
