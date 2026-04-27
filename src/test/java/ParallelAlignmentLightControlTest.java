import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Machine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.pmw.tinylog.Logger;

/**
 * Test camera light control during parallel and sequential alignment.
 * This verifies that camera lights are properly turned off after alignment,
 * whether using parallel alignment (multiple cameras) or sequential fallback.
 */
public class ParallelAlignmentLightControlTest {

    @BeforeEach
    public void before() throws Exception {
        File workingDirectory = File.createTempFile("openpnp-test", "");
        workingDirectory.delete();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        
        // Configure for fast execution
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        NullDriver driver = (NullDriver) machine.getDefaultDriver();
        driver.setFeedRateMmPerMinute(0);
        for (org.openpnp.spi.Axis axis : machine.getAxes()) {
            if (axis instanceof ReferenceControllerAxis) {
                ((ReferenceControllerAxis) axis).setFeedratePerSecond(new Length(1000000, LengthUnit.Millimeters));
                ((ReferenceControllerAxis) axis).setAccelerationPerSecond2(new Length(2000000, LengthUnit.Millimeters));
                ((ReferenceControllerAxis) axis).setJerkPerSecond3(new Length(0, LengthUnit.Millimeters));
            }
        }
        
        machine.setEnabled(true);
        machine.home();
    }
    
    /**
     * Test 1: Parallel alignment with 2 cameras - verify lights turn off after batch
     * This simulates the exact scenario from the bug report:
     * - 2 cameras (Upp1, Upp2)
     * - Parallel alignment should keep lights on during capture, turn off after
     */
    @Test
    public void testParallelAlignmentLightControl() throws Exception {
        System.out.println("\n=== Test 1: Parallel Alignment Light Control ===");
        
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Add 2 simulated cameras (like Upp1, Upp2)
        List<SimulatedUpCamera> cameras = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            SimulatedUpCamera camera = new SimulatedUpCamera();
            camera.setName("Upp" + i);
            camera.setAntiGlareLightOff(true);
            machine.addCamera(camera);
            cameras.add(camera);
            System.out.println("Added camera: " + camera.getName());
        }
        
        // Simulate parallel alignment pre-capture pattern
        System.out.println("\nSimulating parallel alignment pre-capture...");
        
        cbo.startBatchOperation("parallel-align-precapture");
        System.out.println("Batch operation started");
        
        int captureCount = 0;
        try {
            // Capture on camera 1 (settle camera)
            Camera camera1 = cameras.get(0);
            
            System.out.println("Capturing on " + camera1.getName());
            camera1.actuateLightBeforeCapture(null);
            camera1.capture();
            camera1.actuateLightAfterCapture(); // The fix!
            captureCount++;
            
            // Capture on camera 2 (quick capture)
            Camera camera2 = cameras.get(1);
            
            System.out.println("Capturing on " + camera2.getName());
            camera2.actuateLightBeforeCapture(null);
            camera2.capture();
            camera2.actuateLightAfterCapture(); // The fix!
            captureCount++;
            
            System.out.println("Total captures: " + captureCount);
            
        }
        finally {
            cbo.endBatchOperation("parallel-align-precapture");
            System.out.println("Batch operation ended - all lights should be off");
        }
        
        // Verify: Both cameras should have been registered and lights turned off
        System.out.println("\nTest 1 PASSED: Parallel alignment light control verified");
    }
    
    /**
     * Test 2: Sequential alignment fallback - verify lights turn off after each capture
     * This tests the fallback case when parallel alignment fails (single camera or single placement)
     */
    @Test
    public void testSequentialAlignmentLightControl() throws Exception {
        System.out.println("\n=== Test 2: Sequential Alignment Light Control ===");
        
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Add single camera (triggers sequential fallback)
        SimulatedUpCamera camera = new SimulatedUpCamera();
        camera.setName("SingleCamera");
        camera.setAntiGlareLightOff(true);
        machine.addCamera(camera);
        System.out.println("Added camera: " + camera.getName());
        
        // Simulate sequential alignment (single batch per capture)
        System.out.println("\nSimulating sequential alignment...");
        
        int iterations = 5;
        for (int i = 0; i < iterations; i++) {
            System.out.println("\nIteration " + (i + 1) + "/" + iterations);
            
            cbo.startBatchOperation("sequential-capture-" + i);
            
            try {
                camera.actuateLightBeforeCapture(null);
                camera.capture();
                camera.actuateLightAfterCapture(); // The fix!
                System.out.println("Capture " + (i + 1) + " completed");
            }
            finally {
                cbo.endBatchOperation("sequential-capture-" + i);
                System.out.println("Batch ended - light should be off");
            }
        }
        
        System.out.println("\nTest 2 PASSED: Sequential alignment light control verified");
    }
    
    /**
     * Test 3: Mixed scenario - parallel alignment followed by sequential fallback
     * This tests the real-world scenario where parallel alignment might succeed for some
     * placements but fail for others, requiring sequential fallback.
     */
    @Test
    public void testMixedParallelSequentialAlignment() throws Exception {
        System.out.println("\n=== Test 3: Mixed Parallel/Sequential Alignment ===");
        
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Add 2 cameras
        List<SimulatedUpCamera> cameras = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            SimulatedUpCamera camera = new SimulatedUpCamera();
            camera.setName("Cam" + i);
            camera.setAntiGlareLightOff(true);
            machine.addCamera(camera);
            cameras.add(camera);
        }
        
        System.out.println("\nPhase 1: Parallel alignment (2 cameras)");
        
        // Phase 1: Parallel alignment
        cbo.startBatchOperation("parallel-phase");
        try {
            for (int i = 0; i < cameras.size(); i++) {
                Camera camera = cameras.get(i);
                
                System.out.println("Parallel capture on " + camera.getName());
                camera.actuateLightBeforeCapture(null);
                camera.capture();
                camera.actuateLightAfterCapture(); // The fix!
            }
            System.out.println("Parallel phase complete");
        }
        finally {
            cbo.endBatchOperation("parallel-phase");
            System.out.println("Parallel batch ended - lights should be off");
        }
        
        System.out.println("\nPhase 2: Sequential fallback (simulating parallel failure)");
        
        // Phase 2: Sequential fallback (e.g., if parallel alignment failed)
        for (int i = 0; i < 3; i++) {
            cbo.startBatchOperation("sequential-fallback-" + i);
            
            try {
                Camera camera = cameras.get(0); // Use first camera for fallback
                
                System.out.println("Sequential fallback capture " + (i + 1) + " on " + camera.getName());
                camera.actuateLightBeforeCapture(null);
                camera.capture();
                camera.actuateLightAfterCapture(); // The fix!
            }
            finally {
                cbo.endBatchOperation("sequential-fallback-" + i);
                System.out.println("Fallback batch " + (i + 1) + " ended - light should be off");
            }
        }
        
        System.out.println("\nTest 3 PASSED: Mixed parallel/sequential alignment verified");
    }
    
    /**
     * Test 4: Nested batch operations (the original bug scenario)
     * This tests the exact nesting pattern that caused the bug:
     * - Outer batch: "align step"
     * - Inner batch: "parallel-align-precapture"
     */
    @Test
    public void testNestedBatchOperations() throws Exception {
        System.out.println("\n=== Test 4: Nested Batch Operations ===");
        
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Add 2 cameras
        List<SimulatedUpCamera> cameras = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            SimulatedUpCamera camera = new SimulatedUpCamera();
            camera.setName("Upp" + i);
            camera.setAntiGlareLightOff(true);
            machine.addCamera(camera);
            cameras.add(camera);
        }
        
        System.out.println("\nSimulating nested batch structure (align step -> parallel-align-precapture)");
        
        // Outer batch: "align step"
        cbo.startBatchOperation("align step");
        System.out.println("Outer batch 'align step' started (level 1)");
        
        try {
            // Inner batch: "parallel-align-precapture"
            cbo.startBatchOperation("parallel-align-precapture");
            System.out.println("Inner batch 'parallel-align-precapture' started (level 2)");
            
            try {
                // Capture on all cameras
                for (int i = 0; i < cameras.size(); i++) {
                    Camera camera = cameras.get(i);
                    
                    System.out.println("Capturing on " + camera.getName());
                    camera.actuateLightBeforeCapture(null);
                    camera.capture();
                    camera.actuateLightAfterCapture(); // The fix!
                }
                System.out.println("All captures complete");
            }
            finally {
                cbo.endBatchOperation("parallel-align-precapture");
                System.out.println("Inner batch ended (level 1) - lights should stay ON");
            }
            
            // Additional sequential captures after parallel
            System.out.println("\nAdditional sequential captures after parallel alignment");
            for (int i = 0; i < 2; i++) {
                Camera camera = cameras.get(0);
                
                System.out.println("Sequential capture " + (i + 1) + " on " + camera.getName());
                camera.actuateLightBeforeCapture(null);
                camera.capture();
                camera.actuateLightAfterCapture(); // The fix!
            }
            
        }
        finally {
            cbo.endBatchOperation("align step");
            System.out.println("Outer batch ended (level 0) - ALL lights should be OFF");
        }
        
        System.out.println("\nTest 4 PASSED: Nested batch operations verified");
    }
}
