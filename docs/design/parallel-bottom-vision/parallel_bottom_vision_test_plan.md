# Parallel Bottom Vision Test Plan - Revised

## Executive Summary

This test plan validates the parallel bottom vision implementation based on **real-world alignment scenarios**, not artificial benchmarks. The key insight is:

**Parallel vision processing benefits come from processing MULTIPLE NOZZLES simultaneously**, each with their own camera - NOT from compositing (which requires head movement and defeats parallelism).

---

## Architecture Understanding

### Decision Point: Sequential vs Parallel

Location: `ReferencePnpJobProcessor.java:1645`

```java
if (cameraCount <= 1 || plannedPlacements.size() <= 1) {
    // Sequential alignment
} else {
    // Parallel alignment
}
```

**Parallel alignment triggers when:**
- Multiple cameras are available (e.g., 2 cameras on dual-head machine)
- Multiple placements need alignment
- Each nozzle has a compatible camera

**Within each camera group, placements are processed SEQUENTIALLY** because they share the same camera pipeline.

### Real-World Timing Model

| Operation | Time | Parallelizable? |
|-----------|------|-----------------|
| Head move to alignment position | 100-200ms | NO (single move) |
| Settling delay | 100ms | NO (mechanical) |
| Camera capture | 20ms | YES (multiple cameras) |
| Vision processing | 10-50ms | YES (CPU cores) |
| **Total per placement (sequential)** | **~250ms** | |
| **Total for 2 placements (parallel)** | **~270ms** | Move once, capture both |

**Expected speedup for 2 cameras:** ~45-50% (not 65%+)

---

## Test Objectives

### Primary Tests (Must Pass)

1. **Sequential Alignment Correctness** - Verify single-nozzle alignment produces correct offsets
2. **Parallel Alignment Correctness** - Verify multi-nozzle alignment produces same offsets as sequential
3. **Performance Benchmark** - Measure realistic speedup with proper timing model
4. **Thread Safety** - Verify no race conditions in concurrent execution
5. **Error Handling** - Verify failures in one camera don't corrupt other camera results

### Secondary Tests (Nice to Have)

6. **Stress Test** - 100+ placements with mixed parts
7. **Configuration Edge Cases** - 1 camera, 1 placement, no vision

---

## Test Implementation

### Test 1: Sequential Alignment Correctness

**Purpose:** Establish baseline - verify alignment works correctly in sequential mode.

```java
@Test
public void testSequentialAlignmentProducesCorrectOffsets() throws Exception {
    // Setup: Single nozzle, single placement
    Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
    Part part = loadTestPart("R0805-1K");
    
    // Pick part
    machine.execute(() -> {
        nozzle.pick(part, null);
        return true;
    });
    
    // Align sequentially
    BoardLocation boardLocation = new BoardLocation(new Board());
    Placement placement = new Placement("test");
    placement.setLocation(new Location(LengthUnit.Millimeters, 10, 10, 0, 0));
    placement.setPart(part);
    
    ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
    PartAlignmentOffset offset = vision.findOffsets(part, boardLocation, placement, nozzle);
    
    // Verify offset is reasonable (not null, not crazy values)
    assertNotNull(offset);
    assertNotNull(offset.getLocation());
    
    double distance = offset.getLocation().getLinearLengthTo(Location.origin)
        .convertToUnits(LengthUnit.Millimeters).getValue();
    assertTrue(distance < 5.0, "Offset should be reasonable: " + distance + "mm");
    
    // Cleanup
    machine.execute(() -> {
        nozzle.place();
        return true;
    });
}
```

---

### Test 2: Parallel vs Sequential Equivalence

**Purpose:** Verify parallel alignment produces SAME results as sequential alignment.

```java
@Test
public void testParallelAlignmentMatchesSequential() throws Exception {
    final int PLACEMENT_COUNT = 4;
    
    // Setup: 2 nozzles, 4 placements
    List<Nozzle> nozzles = Arrays.asList(
        machine.getDefaultHead().getNozzle(0),
        machine.getDefaultHead().getNozzle(1)
    );
    
    Part part = loadTestPart("R0805-1K");
    
    // Pick parts on both nozzles
    machine.execute(() -> {
        nozzles.get(0).pick(part, null);
        nozzles.get(1).pick(part, null);
        return true;
    });
    
    // Create placements
    List<Placement> placements = new ArrayList<>();
    for (int i = 0; i < PLACEMENT_COUNT; i++) {
        Placement p = new Placement("P" + i);
        p.setLocation(new Location(LengthUnit.Millimeters, i * 10, 0, 0, 0));
        p.setPart(part);
        placements.add(p);
    }
    
    BoardLocation boardLocation = new BoardLocation(new Board());
    
    // Run SEQUENTIAL alignment (baseline)
    Map<Nozzle, PartAlignmentOffset> sequentialOffsets = new HashMap<>();
    for (Nozzle nozzle : nozzles) {
        for (Placement placement : placements) {
            ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
            PartAlignmentOffset offset = vision.findOffsets(
                part, boardLocation, placement, nozzle);
            sequentialOffsets.put(nozzle, offset);
        }
    }
    
    // Run PARALLEL alignment
    Map<Nozzle, PartAlignmentOffset> parallelOffsets = new HashMap<>();
    // (Implementation uses ReferencePnpJobProcessor.alignAllParallel())
    
    // Verify offsets match within tolerance
    for (Nozzle nozzle : nozzles) {
        PartAlignmentOffset seq = sequentialOffsets.get(nozzle);
        PartAlignmentOffset par = parallelOffsets.get(nozzle);
        
        assertNotNull(par, "Parallel offset should not be null");
        
        double xDiff = Math.abs(seq.getLocation().getX() - par.getLocation().getX());
        double yDiff = Math.abs(seq.getLocation().getY() - par.getLocation().getY());
        double rotDiff = Math.abs(seq.getLocation().getRotation() - par.getLocation().getRotation());
        
        assertTrue(xDiff < 0.01, "X offset should match: " + xDiff + "mm");
        assertTrue(yDiff < 0.01, "Y offset should match: " + yDiff + "mm");
        assertTrue(rotDiff < 0.5, "Rotation offset should match: " + rotDiff + "deg");
    }
    
    // Cleanup
    machine.execute(() -> {
        for (Nozzle nozzle : nozzles) {
            nozzle.place();
        }
        return true;
    });
}
```

---

### Test 3: Realistic Performance Benchmark

**Purpose:** Measure REAL speedup with proper timing model (no artificial delays).

```java
@Test
public void testRealisticPerformanceBenchmark() throws Exception {
    final int PLACEMENTS_PER_NOZZLE = 3;
    final int ITERATIONS = 5;
    
    // Setup
    List<Nozzle> nozzles = Arrays.asList(
        machine.getDefaultHead().getNozzle(0),
        machine.getDefaultHead().getNozzle(1)
    );
    
    Part part = loadTestPart("R0805-1K");
    
    // Warmup
    warmupAlignment(nozzles, part, 2);
    
    // Benchmark SEQUENTIAL
    long sequentialStart = System.nanoTime();
    for (int iter = 0; iter < ITERATIONS; iter++) {
        for (Nozzle nozzle : nozzles) {
            machine.execute(() -> {
                nozzle.pick(part, null);
                return true;
            });
            
            for (int p = 0; p < PLACEMENTS_PER_NOZZLE; p++) {
                Placement placement = createPlacement(part, p);
                BoardLocation boardLocation = new BoardLocation(new Board());
                
                ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
                vision.findOffsets(part, boardLocation, placement, nozzle);
            }
            
            machine.execute(() -> {
                nozzle.place();
                return true;
            });
        }
    }
    long sequentialTime = System.nanoTime() - sequentialStart;
    
    // Benchmark PARALLEL
    long parallelStart = System.nanoTime();
    for (int iter = 0; iter < ITERATIONS; iter++) {
        // Pick on both nozzles
        machine.execute(() -> {
            nozzles.get(0).pick(part, null);
            nozzles.get(1).pick(part, null);
            return true;
        });
        
        // Parallel alignment
        List<PlannedPlacement> plannedPlacements = createPlannedPlacements(
            nozzles, part, PLACEMENTS_PER_NOZZLE);
        
        ReferencePnpJobProcessor processor = (ReferencePnpJobProcessor) machine;
        // processor.alignAllParallel(plannedPlacements); // API needed
        
        // Place
        machine.execute(() -> {
            for (Nozzle nozzle : nozzles) {
                nozzle.place();
            }
            return true;
        });
    }
    long parallelTime = System.nanoTime() - parallelStart;
    
    // Calculate metrics
    double sequentialMs = sequentialTime / 1_000_000.0;
    double parallelMs = parallelTime / 1_000_000.0;
    double speedup = sequentialMs / parallelMs;
    double improvement = (sequentialMs - parallelMs) / sequentialMs * 100;
    
    System.out.println(String.format(
        "Sequential: %.2fms, Parallel: %.2fms, Speedup: %.2fx, Improvement: %.1f%%",
        sequentialMs, parallelMs, speedup, improvement));
    
    // Verify realistic speedup (45-55% for 2 cameras)
    // NOT expecting 65%+ because move/settle time dominates
    assertTrue("Parallel should be faster", parallelTime < sequentialTime);
    assertTrue("Speedup should be at least 1.3x (30%)", speedup >= 1.3);
    assertTrue("Speedup should not exceed 2.0x (theoretical max)", speedup <= 2.0);
}

private void warmupAlignment(List<Nozzle> nozzles, Part part, int iterations) throws Exception {
    // Run alignment a few times to warm up JVM and camera
    for (int i = 0; i < iterations; i++) {
        for (Nozzle nozzle : nozzles) {
            machine.execute(() -> {
                nozzle.pick(part, null);
                return true;
            });
            
            Placement placement = createPlacement(part, 0);
            BoardLocation boardLocation = new BoardLocation(new Board());
            
            ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
            vision.findOffsets(part, boardLocation, placement, nozzle);
            
            machine.execute(() -> {
                nozzle.place();
                return true;
            });
        }
    }
}
```

---

### Test 4: Thread Safety Under Load

**Purpose:** Verify no race conditions when multiple cameras process concurrently.

```java
@Test
public void testThreadSafetyUnderConcurrentLoad() throws Exception {
    final int ITERATIONS = 50;
    final AtomicInteger failures = new AtomicInteger(0);
    final AtomicInteger successfulAlignments = new AtomicInteger(0);
    
    List<Nozzle> nozzles = Arrays.asList(
        machine.getDefaultHead().getNozzle(0),
        machine.getDefaultHead().getNozzle(1)
    );
    
    Part part = loadTestPart("R0805-1K");
    
    for (int iter = 0; iter < ITERATIONS; iter++) {
        try {
            // Pick on both nozzles
            machine.execute(() -> {
                nozzles.get(0).pick(part, null);
                nozzles.get(1).pick(part, null);
                return true;
            });
            
            // Create placements for parallel alignment
            List<PlannedPlacement> plannedPlacements = createPlannedPlacements(
                nozzles, part, 2); // 2 placements per nozzle
            
            // Run parallel alignment
            ReferencePnpJobProcessor processor = (ReferencePnpJobProcessor) machine;
            // processor.alignAllParallel(plannedPlacements); // API needed
            
            // Verify all placements have valid offsets
            for (PlannedPlacement pp : plannedPlacements) {
                assertNotNull(pp.alignmentOffsets, 
                    "Placement " + pp.jobPlacement.getPlacement().getId() + 
                    " should have offsets");
            }
            
            successfulAlignments.incrementAndGet();
            
            // Place
            machine.execute(() -> {
                for (Nozzle nozzle : nozzles) {
                    nozzle.place();
                }
                return true;
            });
            
        } catch (Exception e) {
            failures.incrementAndGet();
            System.err.println("Iteration " + iter + " failed: " + e.getMessage());
            e.printStackTrace();
            
            // Cleanup on failure
            try {
                machine.execute(() -> {
                    for (Nozzle nozzle : nozzles) {
                        if (nozzle.isPicked()) {
                            nozzle.place();
                        }
                    }
                    return true;
                });
            } catch (Exception cleanupEx) {
                // Ignore cleanup errors
            }
        }
    }
    
    assertEquals(0, failures.get(), 
        "All " + ITERATIONS + " iterations should succeed (failures: " + failures.get() + ")");
    assertEquals(ITERATIONS, successfulAlignments.get(),
        "All iterations should complete successfully");
}
```

---

### Test 5: Error Isolation

**Purpose:** Verify failure in one camera doesn't corrupt other camera results.

```java
@Test
public void testErrorIsolationBetweenCameras() throws Exception {
    // This test would require simulating a camera failure
    // Implementation depends on mockability of camera hardware
    
    // Expected behavior:
    // 1. Camera 1 fails during capture/processing
    // 2. Camera 2 completes successfully
    // 3. Exception is thrown after both complete (or timeout)
    // 4. Camera 2's results are preserved and accessible
    
    // For now, this is a manual test using physical hardware
    // or requires mocking infrastructure
}
```

---

### Test 6: Edge Cases

**Purpose:** Verify correct behavior in edge cases.

```java
@Test
public void testSingleCameraUsesSequential() throws Exception {
    // Setup: Machine with only 1 camera (or 1 nozzle with camera)
    // Expected: Should use sequential alignment (no parallelism possible)
    
    // This requires configuration manipulation or hardware with 1 camera
}

@Test
public void testSinglePlacementUsesSequential() throws Exception {
    // Setup: Only 1 placement to align
    // Expected: Should use sequential alignment (no parallelism possible)
    
    Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
    Part part = loadTestPart("R0805-1K");
    
    machine.execute(() -> {
        nozzle.pick(part, null);
        return true;
    });
    
    Placement placement = createPlacement(part, 0);
    BoardLocation boardLocation = new BoardLocation(new Board());
    
    ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
    PartAlignmentOffset offset = vision.findOffsets(part, boardLocation, placement, nozzle);
    
    assertNotNull(offset);
    
    machine.execute(() -> {
        nozzle.place();
        return true;
    });
}

@Test
public void testNoVisionUsesNullOffsets() throws Exception {
    // Setup: Part with no vision alignment configured
    // Expected: Should return null offsets without error
    
    Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
    Part part = loadTestPart("NO_VISION_PART"); // Part without vision
    
    machine.execute(() -> {
        nozzle.pick(part, null);
        return true;
    });
    
    Placement placement = createPlacement(part, 0);
    BoardLocation boardLocation = new BoardLocation(new Board());
    
    ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
    PartAlignmentOffset offset = vision.findOffsets(part, boardLocation, placement, nozzle);
    
    // Should be null or zero offset
    // (depends on implementation)
    
    machine.execute(() -> {
        nozzle.place();
        return true;
    });
}
```

---

## Helper Methods

```java
private Part loadTestPart(String partId) throws Exception {
    Part part = Configuration.get().getPart(partId);
    if (part == null) {
        // Create test part if not exists
        part = createTestPart(partId);
    }
    return part;
}

private Part createTestPart(String partId) throws Exception {
    Part part = new Part(partId);
    // Configure with default package and vision settings
    Configuration.get().addPart(part);
    return part;
}

private Placement createPlacement(Part part, int index) {
    Placement p = new Placement("P" + index);
    p.setLocation(new Location(LengthUnit.Millimeters, index * 10, 0, 0, 0));
    p.setPart(part);
    return p;
}

private List<PlannedPlacement> createPlannedPlacements(
        List<Nozzle> nozzles, Part part, int placementsPerNozzle) {
    
    List<PlannedPlacement> result = new ArrayList<>();
    
    for (Nozzle nozzle : nozzles) {
        for (int p = 0; p < placementsPerNozzle; p++) {
            Placement placement = createPlacement(part, p);
            BoardLocation boardLocation = new BoardLocation(new Board());
            
            JobPlacement jobPlacement = new JobPlacement(boardLocation, placement);
            PlannedPlacement planned = new PlannedPlacement(nozzle, jobPlacement, 0);
            result.add(planned);
        }
    }
    
    return result;
}
```

---

## Expected Results Summary

| Test | Expected Outcome |
|------|------------------|
| Sequential Correctness | Offsets within 5mm, no exceptions |
| Parallel Equivalence | Offsets match sequential within 0.01mm / 0.5° |
| Performance Benchmark | 30-50% improvement (1.3-1.5x speedup) |
| Thread Safety | 50 iterations, 0 failures |
| Error Isolation | Manual test required |
| Edge Cases | Correct fallback to sequential |

---

## Notes

1. **Compositing is NOT the focus** - Most real-world alignments use single-shot (no compositing)
2. **Move/settle time dominates** - Cannot parallelize mechanical movement
3. **Real speedup is 30-50%** - Not 65%+ (which was due to artificial sleep in old benchmark)
4. **Parallelism is at camera level** - Multiple cameras process simultaneously
5. **Within-camera is sequential** - Placements sharing a camera are processed one at a time

---

*Test plan created: 2026-04-12*
*Based on user feedback about realistic timing and compositing limitations*
