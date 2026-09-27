# Diagnose Pre-Check Timing Issues - Systematic Approach

> Historical design note from the development checkout, imported 2026-09-27. It describes what was
> intended at the time, not what this branch does. Current state: `SALVAGE-PLAN.md`.

## Problem Statement

**Known Facts:**
1. Alignment takes ~35ms (fast, working correctly)
2. Pre-check takes 50-70ms (slow, problem!)
3. Both use IDENTICAL pipeline code
4. Thread executor change (cached → fixed pool) had no effect
5. Image capture should take <10ms but appears to take longer

**Hypothesis:** The delay is NOT in the pipeline itself, but in:
- Image capture timing
- Threading overhead between capture and pipeline
- Something happening between capture completion and pipeline start

## Phase 1: Add Detailed Timing Markers

### Goal
Add microsecond-precision timing markers at EVERY stage of the pre-check flow to pinpoint exactly where time is spent.

### Timing Points to Instrument

**In `ReferencePnpJobProcessor.java` - async capture path (lines 2138-2160):**
```
T0: preCheckStart = System.currentTimeMillis()  [Already exists]
T1: captureImageOnly() starts
T2: captureImageOnly() completes (image captured)
T3: quickCheckPartPresence() starts
T4: quickCheckPartPresence() completes
T5: preCheckResults.put() completes
```

**In `ReferencePnpJobProcessor.java` - settle camera path (lines 2214-2232):**
```
T0: preCheckStart = System.currentTimeMillis()  [Already exists]
T1: captureImageOnlyWithSettle() starts
T2: settleAndCapture() callback invoked (first frame ready)
T3: quickCheckPartPresence() starts
T4: quickCheckPartPresence() completes
T5: preCheckResults.put() completes
```

**In `ReferenceBottomVision.java` - quickCheckPartPresence() (lines 515-650):**
```
T0: Method entry (startTime already logged)
T1: preparePipeline() starts
T2: preparePipeline() completes
T3: pipeline.process() starts
T4: pipeline.process() completes (end of line 561)
T5: processCompositeResult() completes (line 582)
T6: Method exit (log total time - already exists)
```

**In `AbstractSettlingCamera.java` - settleAndCapture() (lines 271-450):**
```
T0: Method entry
T1: First camera.capture() starts
T2: First camera.capture() completes
T3: First frame callback invoked (line 397)
T4: Settle loop completes
T5: Method exit
```

### Logging Format

```java
Logger.info("PRE-CHECK TIMING: nozzle={} stage={} duration={}ms total={}ms",
    nozzle.getName(), "capture_start", captureStart - preCheckStart, captureStart - preCheckStart);
Logger.info("PRE-CHECK TIMING: nozzle={} stage={} duration={}ms total={}ms",
    nozzle.getName(), "capture_end", captureEnd - captureStart, captureEnd - preCheckStart);
Logger.info("PRE-CHECK TIMING: nozzle={} stage={} duration={}ms total={}ms",
    nozzle.getName(), "pipeline_start", pipelineStart - captureEnd, pipelineStart - preCheckStart);
Logger.info("PRE-CHECK TIMING: nozzle={} stage={} duration={}ms total={}ms",
    nozzle.getName(), "pipeline_end", pipelineEnd - pipelineStart, pipelineEnd - preCheckStart);
```

## Phase 2: Collect Baseline Data

### Run Test Scenario
1. Run a job with parallel alignment (2 cameras, 2 nozzles)
2. Capture logs with DEBUG level for timing markers
3. Run 10+ iterations to get warm JVM data
4. Compare cold vs warm runs

### Expected Output
```
PRE-CHECK TIMING: nozzle=N1 stage=capture_start duration=0ms total=0ms
PRE-CHECK TIMING: nozzle=N1 stage=capture_end duration=8ms total=8ms
PRE-CHECK TIMING: nozzle=N1 stage=pipeline_start duration=25ms total=33ms  ← PROBLEM HERE?
PRE-CHECK TIMING: nozzle=N1 stage=pipeline_end duration=12ms total=45ms

PRE-CHECK TIMING: nozzle=N2 stage=capture_start duration=0ms total=0ms
PRE-CHECK TIMING: nozzle=N2 stage=capture_end duration=9ms total=9ms
PRE-CHECK TIMING: nozzle=N2 stage=pipeline_start duration=23ms total=32ms  ← PROBLEM HERE?
PRE-CHECK TIMING: nozzle=N2 stage=pipeline_end duration=13ms total=45ms
```

## Phase 3: Analyze Results

### If capture is slow (>10ms)
- Check camera driver/GcodeDriver timing
- Check if camera light is being actuated (adds delay)
- Check if settle loop is running when it shouldn't

### If pipeline_start delay is large (>5ms)
- Check thread pool contention
- Check if quickCheckPartPresence() is waiting for executor
- Check if there's synchronization blocking the call

### If pipeline is slow (>20ms)
- Compare with alignment pipeline timing (should be same)
- Check if preparePipeline() is doing extra work
- Check if pipeline.process() is running extra stages

## Phase 4: Compare with Alignment

### Add Same Timing Markers to Alignment
Add identical timing markers in `processPipelineAndGetResult()` (lines 989-1052):
```
T0: Method entry
T1: preparePipeline() starts
T2: preparePipeline() completes  
T3: pipeline.process() starts
T4: pipeline.process() completes
T5: Method exit
```

### Expected Alignment Timing
```
ALIGNMENT TIMING: nozzle=N1 stage=preparePipeline duration=3ms total=3ms
ALIGNMENT TIMING: nozzle=N1 stage=pipeline_process duration=12ms total=15ms
ALIGNMENT TIMING: nozzle=N1 stage=total duration=15ms
```

### Compare Pre-Check vs Alignment
```
Pre-check preparePipeline:  X ms
Alignment preparePipeline:  Y ms  (should be same)

Pre-check pipeline.process: A ms
Alignment pipeline.process: B ms  (should be same)
```

## Phase 5: Identify Root Cause

### Possible Findings

**Scenario A: preparePipeline() is slower in pre-check**
- Cause: Different pipeline parameter assignments?
- Fix: Optimize preparePipeline() for pre-check case

**Scenario B: pipeline.process() is slower in pre-check**
- Cause: Different pipeline stages enabled?
- Fix: Ensure identical pipeline configuration

**Scenario C: Delay between capture and pipeline**
- Cause: Thread pool contention, callback overhead
- Fix: Use direct invocation instead of async, or optimize executor

**Scenario D: Image capture is slow**
- Cause: Camera driver, settle loop, light actuation
- Fix: Optimize capture path, skip settle if not needed

**Scenario E: JVM warmup is the issue**
- Cause: Cold start overhead
- Fix: Accept as normal, or add warmup phase

## Phase 6: Verify Fix

### Test Criteria
1. Pre-check pipeline time matches alignment pipeline time (±5ms)
2. Total pre-check time (capture + pipeline) < 30ms on warm JVM
3. No significant variance between runs (stddev < 5ms)
4. No thread contention in logs

## Questions Before Implementation

1. **Logging level:** Should timing markers use INFO (always visible) or DEBUG (can be enabled on demand)?

2. **Timing precision:** Use `System.currentTimeMillis()` (ms) or `System.nanoTime()` (ns, more precise but harder to read)?

3. **Test scope:** Focus on settle camera only, or both settle and async cameras?

4. **Production vs dev:** Should we test on dev machine first, or wait for production machine (8+ cores)?

5. **Alignment comparison:** Do you want me to add timing markers to alignment code as well for direct comparison?
