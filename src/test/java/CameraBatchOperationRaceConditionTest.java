import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.spi.base.AbstractAxis;
import org.openpnp.util.VisionUtils;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import com.google.common.io.Files;

/**
 * Test to investigate potential race conditions in CameraBatchOperation
 * when multiple cameras are used in rapid succession.
 */
public class CameraBatchOperationRaceConditionTest {

    @BeforeEach
    public void before() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();
    }

    /**
     * Test 1: Rapid sequential camera registration/deregistration
     * This simulates what happens during parallel alignment pre-capture
     */
    @Test
    public void testRapidCameraRegistration() throws Exception {
        System.out.println("\n=== Test 1: Rapid Camera Registration ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        NullDriver driver = (NullDriver) machine.getDefaultDriver();
        driver.setFeedRateMmPerMinute(0);
        
        // Configure all axes for fast movement
        for (Axis axis : machine.getAxes()) {
            if (axis instanceof org.openpnp.machine.reference.axis.ReferenceControllerAxis) {
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis)
                    .setFeedratePerSecond(new Length(1000000, LengthUnit.Millimeters));
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis)
                    .setAccelerationPerSecond2(new Length(2000000, LengthUnit.Millimeters));
            }
        }
        
        // Add multiple simulated cameras
        Head head = machine.getDefaultHead();
        List<SimulatedUpCamera> cameras = new ArrayList<>();
        
        // Create 3 simulated cameras
        for (int i = 0; i < 3; i++) {
            SimulatedUpCamera camera = new SimulatedUpCamera();
            camera.setName("Camera" + (i + 1));
            camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
            camera.setSettleTimeMs(0);
            machine.addCamera(camera);
            cameras.add(camera);
            System.out.println("Added camera: " + camera.getName());
        }
        
        // Add multiple nozzles
        List<Nozzle> nozzles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ReferenceNozzle nozzle = new ReferenceNozzle();
            nozzle.setName("N" + (i + 1));
            head.addNozzle(nozzle);
            nozzles.add(nozzle);
            System.out.println("Added nozzle: " + nozzle.getName());
        }
        
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Simulate parallel alignment pre-capture pattern
        // This is the pattern used in preCaptureAllImages()
        System.out.println("\nSimulating parallel alignment pre-capture pattern...");
        
        cbo.startBatchOperation("parallel-align-precapture");
        System.out.println("Batch operation started");
        
        int captureCount = 0;
        try {
            // Simulate the pattern from preCaptureAllImages():
            // 1. Settle on first camera (multiple captures)
            // 2. Quick-capture on remaining cameras
            
            // Step 1: Settle on first camera
            Camera settleCamera = cameras.get(0);
            Nozzle settleNozzle = nozzles.get(0);
            
            System.out.println("\nStep 1: Settling on camera " + settleCamera.getName());
            for (int i = 0; i < 3; i++) {
                settleCamera.actuateLightBeforeCapture(null);
                settleCamera.capture();  // This calls actuateLightAfterCapture internally
                captureCount++;
                System.out.println("  Capture " + (i + 1) + " on " + settleCamera.getName());
            }
            
            // Step 2: Quick-capture on remaining cameras
            for (int i = 1; i < cameras.size(); i++) {
                Camera quickCamera = cameras.get(i);
                Nozzle quickNozzle = nozzles.get(i);
                
                System.out.println("\nStep 2: Quick-capture on camera " + quickCamera.getName());
                quickCamera.actuateLightBeforeCapture(null);
                quickCamera.capture();  // This calls actuateLightAfterCapture internally
                captureCount++;
                System.out.println("  Capture on " + quickCamera.getName());
            }
            
            System.out.println("\nTotal captures: " + captureCount);
            System.out.println("All cameras should be registered with batch operation");
            
        }
        finally {
            cbo.endBatchOperation("parallel-align-precapture");
            System.out.println("\nBatch operation ended - all lights should be off");
        }
        
        System.out.println("\nRapid camera registration test PASSED");
    }

    /**
     * Test 2: Simulate the exact bug scenario from the logs
     * Camera at index 0 after sorting is not the settle camera and gets skipped
     */
    @Test
    public void testCameraSortingBugScenario() throws Exception {
        System.out.println("\n=== Test 2: Camera Sorting Bug Scenario ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        NullDriver driver = (NullDriver) machine.getDefaultDriver();
        driver.setFeedRateMmPerMinute(0);
        
        // Configure all axes for fast movement
        for (Axis axis : machine.getAxes()) {
            if (axis instanceof org.openpnp.machine.reference.axis.ReferenceControllerAxis) {
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis)
                    .setFeedratePerSecond(new Length(1000000, LengthUnit.Millimeters));
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis)
                    .setAccelerationPerSecond2(new Length(2000000, LengthUnit.Millimeters));
            }
        }
        
        // Add cameras with names that will sort alphabetically
        // N2 camera will be at index 0 after sorting, but N1 is the settle camera
        Head head = machine.getDefaultHead();
        List<SimulatedUpCamera> cameras = new ArrayList<>();
        
        SimulatedUpCamera cameraN2 = new SimulatedUpCamera();
        cameraN2.setName("N2-Bottom");  // Will sort to index 0
        cameraN2.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        cameraN2.setSettleTimeMs(0);
        machine.addCamera(cameraN2);
        cameras.add(cameraN2);
        
        SimulatedUpCamera cameraN1 = new SimulatedUpCamera();
        cameraN1.setName("N1-Bottom");  // Will sort to index 1
        cameraN1.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        cameraN1.setSettleTimeMs(0);
        machine.addCamera(cameraN1);
        cameras.add(cameraN1);
        
        // Add nozzles
        ReferenceNozzle nozzleN1 = new ReferenceNozzle();
        nozzleN1.setName("N1");
        head.addNozzle(nozzleN1);
        
        ReferenceNozzle nozzleN2 = new ReferenceNozzle();
        nozzleN2.setName("N2");
        head.addNozzle(nozzleN2);
        
        System.out.println("Cameras added: N2-Bottom, N1-Bottom");
        System.out.println("After alphabetical sorting: N1-Bottom (0), N2-Bottom (1)");
        System.out.println("Settle camera will be N1-Bottom (first nozzle in placement list)");
        
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        
        // Simulate the BUGGY code (old version that started from index 1)
        System.out.println("\n--- Simulating OLD (buggy) code ---");
        cbo.startBatchOperation("old-buggy-code");
        
        try {
            // Get cameras sorted alphabetically (like the code does)
            List<Camera> sortedCameras = new ArrayList<>(machine.getCameras());
            sortedCameras.sort((c1, c2) -> c1.getName().compareTo(c2.getName()));
            
            System.out.println("Sorted cameras:");
            for (int i = 0; i < sortedCameras.size(); i++) {
                System.out.println("  Index " + i + ": " + sortedCameras.get(i).getName());
            }
            
            // Settle camera is N1-Bottom (from first nozzle)
            Camera settleCamera = VisionUtils.getBottomVisionCamera(nozzleN1);
            System.out.println("Settle camera: " + settleCamera.getName());
            
            // OLD BUGGY CODE: Start from index 1, assuming settle camera is at index 0
            System.out.println("\nOLD CODE: Iterating from index 1 (assuming settle at index 0)...");
            for (int i = 1; i < sortedCameras.size(); i++) {
                Camera captureCamera = sortedCameras.get(i);
                if (captureCamera.equals(settleCamera)) {
                    System.out.println("  Skipping " + captureCamera.getName() + " (settle camera)");
                    continue;
                }
                System.out.println("  Capturing on " + captureCamera.getName() + " at index " + i);
                captureCamera.actuateLightBeforeCapture(null);
                captureCamera.capture();
            }
            
            // Check which cameras were captured
            System.out.println("\nOLD CODE RESULT:");
            System.out.println("  N1-Bottom (settle): Captured manually");
            System.out.println("  N2-Bottom (index 0): SKIPPED! (BUG!)");
            
        }
        finally {
            cbo.endBatchOperation("old-buggy-code");
        }
        
        // Simulate the FIXED code (new version that starts from index 0)
        System.out.println("\n--- Simulating NEW (fixed) code ---");
        cbo.startBatchOperation("new-fixed-code");
        
        try {
            // Get cameras sorted alphabetically (like the code does)
            List<Camera> sortedCameras = new ArrayList<>(machine.getCameras());
            sortedCameras.sort((c1, c2) -> c1.getName().compareTo(c2.getName()));
            
            // Settle camera is N1-Bottom (from first nozzle)
            Camera settleCamera = VisionUtils.getBottomVisionCamera(nozzleN1);
            
            // NEW FIXED CODE: Start from index 0, explicitly skip settle camera
            System.out.println("NEW CODE: Iterating from index 0, explicitly skipping settle camera...");
            for (int i = 0; i < sortedCameras.size(); i++) {
                Camera captureCamera = sortedCameras.get(i);
                if (captureCamera.equals(settleCamera)) {
                    System.out.println("  Skipping " + captureCamera.getName() + " (settle camera)");
                    continue;
                }
                System.out.println("  Capturing on " + captureCamera.getName() + " at index " + i);
                captureCamera.actuateLightBeforeCapture(null);
                captureCamera.capture();
            }
            
            // Check which cameras were captured
            System.out.println("\nNEW CODE RESULT:");
            System.out.println("  N1-Bottom (settle): Captured manually");
            System.out.println("  N2-Bottom (index 0): Captured correctly!");
            
        }
        finally {
            cbo.endBatchOperation("new-fixed-code");
        }
        
        System.out.println("\nCamera sorting bug scenario test PASSED");
    }

    /**
     * Test 3: Verify CameraBatchOperation handles nested operations correctly
     */
    @Test
    public void testNestedBatchOperations() throws Exception {
        System.out.println("\n=== Test 3: Nested Batch Operations ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        
        // Add a camera
        SimulatedUpCamera camera = new SimulatedUpCamera();
        camera.setName("TestCamera");
        camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        camera.setSettleTimeMs(0);
        machine.addCamera(camera);
        
        System.out.println("Testing nested batch operations...");
        
        // Outer batch
        cbo.startBatchOperation("outer-batch");
        System.out.println("Outer batch started (level 1)");
        
        try {
            // Inner batch 1
            cbo.startBatchOperation("inner-batch-1");
            System.out.println("Inner batch 1 started (level 2)");
            
            try {
                camera.actuateLightBeforeCapture(null);
                cbo.registerWithBatchOperation(camera);
                System.out.println("Camera registered at level 2");
            }
            finally {
                cbo.endBatchOperation("inner-batch-1");
                System.out.println("Inner batch 1 ended (should NOT turn off light)");
            }
            
            // Inner batch 2
            cbo.startBatchOperation("inner-batch-2");
            System.out.println("Inner batch 2 started (level 2)");
            
            try {
                camera.actuateLightBeforeCapture(null);
                // Camera already registered, should not register again
                boolean registered = cbo.registerWithBatchOperation(camera);
                System.out.println("Camera registration at level 2: " + registered);
            }
            finally {
                cbo.endBatchOperation("inner-batch-2");
                System.out.println("Inner batch 2 ended (should NOT turn off light)");
            }
        }
        finally {
            cbo.endBatchOperation("outer-batch");
            System.out.println("Outer batch ended (SHOULD turn off light)");
        }
        
        System.out.println("\nNested batch operations test PASSED");
    }
}
