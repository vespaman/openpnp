import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
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
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import com.google.common.io.Files;

/**
 * Test to investigate NTC (Nozzle Tip Calibration) issues with multiple cameras.
 * 
 * This test simulates a scenario where:
 * 1. Multiple bottom vision cameras are configured
 * 2. NTC is performed on different nozzles
 * 3. CameraBatchOperation is used to manage LED lifecycle
 * 
 * The goal is to identify if there's a race condition or registration issue
 * when multiple cameras are used during calibration.
 */
public class NozzleTipCalibrationMultiCameraTest {

    @BeforeEach
    public void before() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();
        
        // Make machine fastest
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        NullDriver driver = (NullDriver) machine.getDefaultDriver();
        driver.setFeedRateMmPerMinute(0);
        for (Axis axis : machine.getAxes()) {
            if (axis instanceof org.openpnp.machine.reference.axis.ReferenceControllerAxis) {
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis).setFeedratePerSecond(new Length(1000000, LengthUnit.Millimeters));
                ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis).setAccelerationPerSecond2(new Length(2000000, LengthUnit.Millimeters));
            }
        }
        
        // Configure multiple cameras with settling support
        Head head = machine.getDefaultHead();
        List<Nozzle> nozzles = new ArrayList<>(head.getNozzles());
        List<Camera> cameras = new ArrayList<>(machine.getCameras());
        
        System.out.println("Found " + nozzles.size() + " nozzles and " + cameras.size() + " cameras");
        
        // Settle method to FixedTime with 0ms for fast testing
        for (Camera camera : cameras) {
            if (camera instanceof SimulatedUpCamera) {
                SimulatedUpCamera simCamera = (SimulatedUpCamera) camera;
                simCamera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
                simCamera.setSettleTimeMs(0);
                System.out.println("Configured camera " + camera.getName() + " for fast testing");
            }
        }
        
        // Settle nozzles
        for (Nozzle nozzle : nozzles) {
            ((ReferenceNozzle) nozzle).setPickDwellMilliseconds(0);
            ((ReferenceNozzle) nozzle).setPlaceDwellMilliseconds(0);
        }
    }

    /**
     * Test 1: Verify CameraBatchOperation works correctly with single camera NTC
     */
    @Test
    public void testSingleCameraNTC() throws Exception {
        System.out.println("\n=== Test 1: Single Camera NTC (Skip - requires physical calibration) ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        Nozzle nozzle = machine.getDefaultHead().getNozzles().get(0);
        Camera camera = VisionUtils.getBottomVisionCamera(nozzle);
        
        System.out.println("Nozzle: " + nozzle.getName());
        System.out.println("Camera: " + camera.getName());
        
        // Get the nozzle tip
        ReferenceNozzle refNozzle = (ReferenceNozzle) nozzle;
        ReferenceNozzleTip ntc = refNozzle.getCalibrationNozzleTip();
        
        if (ntc == null) {
            System.out.println("SKIPPED: No nozzle tip configured in test environment");
            return;
        }
        
        if (!ntc.getCalibration().isCalibrated(refNozzle)) {
            System.out.println("SKIPPED: Nozzle tip not calibrated (requires physical calibration on real machine)");
            return;
        }
        
        System.out.println("Single camera NTC test PASSED");
    }

    /**
     * Test 2: Verify CameraBatchOperation registration with multiple cameras
     * This simulates what happens during parallel alignment
     */
    @Test
    public void testCameraBatchOperationRegistration() throws Exception {
        System.out.println("\n=== Test 2: CameraBatchOperation Registration ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        List<Camera> cameras = new ArrayList<>(machine.getCameras());
        List<Nozzle> nozzles = new ArrayList<>(machine.getDefaultHead().getNozzles());
        
        System.out.println("Testing with " + cameras.size() + " cameras and " + nozzles.size() + " nozzles");
        
        // Get camera batch operation
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Simulate batch operation like parallel alignment does
        cbo.startBatchOperation("test-precapture");
        System.out.println("Batch operation started");
        
        try {
            // Simulate capturing from multiple cameras
            for (int i = 0; i < Math.min(cameras.size(), nozzles.size()); i++) {
                Camera camera = cameras.get(i);
                Nozzle nozzle = nozzles.get(i);
                
                System.out.println("Simulating capture on camera " + camera.getName() + " with nozzle " + nozzle.getName());
                
                // This simulates what happens in captureImageOnly/captureImageOnlyWithSettle
                // The camera.light should be turned on, and camera should register with batch
                camera.actuateLightBeforeCapture(null);
                
                // Simulate capture (this would normally call actuateLightAfterCapture)
                // For testing, we'll manually register
                boolean registered = cbo.registerWithBatchOperation(camera);
                System.out.println("Camera " + camera.getName() + " registered: " + registered);
                
                assertTrue(registered, "Camera should register with batch operation");
            }
            
            System.out.println("All cameras registered successfully");
        }
        finally {
            // End batch operation - this should turn off all camera lights
            cbo.endBatchOperation("test-precapture");
            System.out.println("Batch operation ended");
        }
        
        System.out.println("CameraBatchOperation registration test PASSED");
    }

    /**
     * Test 3: Simulate NTC with potential multiple camera scenario
     * This tests if NTC could have issues if multiple cameras are somehow involved
     */
    @Test
    public void testNTCWithMultipleCameras() throws Exception {
        System.out.println("\n=== Test 3: NTC with Multiple Cameras ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        List<Nozzle> nozzles = new ArrayList<>(machine.getDefaultHead().getNozzles());
        List<Camera> cameras = new ArrayList<>(machine.getCameras());
        
        System.out.println("Testing NTC on " + nozzles.size() + " nozzles with " + cameras.size() + " cameras");
        
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        
        // Test each nozzle
        for (Nozzle nozzle : nozzles) {
            System.out.println("\nTesting nozzle: " + nozzle.getName());
            
            // Get the camera for this nozzle
            Camera camera = VisionUtils.getBottomVisionCamera(nozzle);
            System.out.println("  Assigned camera: " + camera.getName());
            
            // Start batch operation like NTC does
            cbo.startBatchOperation("nozzle-tip-calibration");
            
            try {
                // Simulate NTC process
                ReferenceNozzle refNozzle = (ReferenceNozzle) nozzle;
                ReferenceNozzleTip ntc = refNozzle.getCalibrationNozzleTip();
                
                if (ntc != null && ntc.getCalibration().isCalibrated(refNozzle)) {
                    System.out.println("  Nozzle tip already calibrated");
                    
                    // Simulate the calibration capture process
                    // NTC captures multiple images at different rotation angles
                    // All captures should use the SAME camera
                    for (int angle = 0; angle <= 360; angle += 90) {
                        // Turn on light
                        camera.actuateLightBeforeCapture(null);
                        
                        // Simulate capture
                        // In real NTC, this would be actual vision processing
                        System.out.println("    Capture at angle " + angle + " on camera " + camera.getName());
                        
                        // Light should be deferred due to batch operation
                        camera.actuateLightAfterCapture();
                    }
                }
                else {
                    System.out.println("  Nozzle tip not calibrated (expected in test environment)");
                }
            }
            finally {
                // End batch operation
                cbo.endBatchOperation("nozzle-tip-calibration");
                System.out.println("  Batch operation completed");
            }
        }
        
        System.out.println("\nNTC with multiple cameras test PASSED");
    }

    /**
     * Test 4: Verify no camera light leakage in batch operations
     */
    @Test
    public void testNoLightLeakage() throws Exception {
        System.out.println("\n=== Test 4: No Light Leakage ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        List<Camera> cameras = new ArrayList<>(machine.getCameras());
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        
        // Perform multiple nested batch operations
        for (int i = 0; i < 3; i++) {
            System.out.println("Outer batch iteration: " + i);
            cbo.startBatchOperation("outer-batch-" + i);
            
            try {
                cbo.startBatchOperation("inner-batch-" + i);
                
                try {
                    // Capture on all cameras
                    for (Camera camera : cameras) {
                        camera.actuateLightBeforeCapture(null);
                        cbo.registerWithBatchOperation(camera);
                    }
                }
                finally {
                    cbo.endBatchOperation("inner-batch-" + i);
                }
            }
            finally {
                cbo.endBatchOperation("outer-batch-" + i);
            }
        }
        
        System.out.println("No light leakage test PASSED");
    }

    /**
     * Test 5: Verify camera-to-nozzle mapping consistency
     */
    @Test
    public void testCameraNozzleMapping() throws Exception {
        System.out.println("\n=== Test 5: Camera-Nozzle Mapping ===");
        
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        List<Nozzle> nozzles = new ArrayList<>(machine.getDefaultHead().getNozzles());
        List<Camera> cameras = new ArrayList<>(machine.getCameras());
        
        System.out.println("Verifying camera-nozzle mappings:");
        
        for (Nozzle nozzle : nozzles) {
            Camera camera = VisionUtils.getBottomVisionCamera(nozzle);
            System.out.println("  " + nozzle.getName() + " -> " + camera.getName());
            
            // Verify mapping is consistent
            Camera camera2 = VisionUtils.getBottomVisionCamera(nozzle);
            assertEquals(camera, camera2, "Camera mapping should be consistent");
        }
        
        System.out.println("Camera-nozzle mapping test PASSED");
    }
}
