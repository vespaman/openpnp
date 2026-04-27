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
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.VisionUtils;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;

/**
 * Test CameraBatchOperation behavior with multiple cameras and sequential operations.
 * This helps diagnose issues where lights might not turn on/off correctly during
 * nozzle tip calibration or parallel alignment.
 */
public class CameraBatchOperationTest {

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
        for (Axis axis : machine.getAxes()) {
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
     * Test 1: Verify CameraBatchOperation registers cameras correctly
     */
    @Test
    public void testBatchOperationRegistersCameras() throws Exception {
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        assertNotNull(cbo, "CameraBatchOperation should be available");
        
        // Get all cameras
        List<Camera> cameras = new ArrayList<>(machine.getAllCameras());
        assertTrue(cameras.size() > 0, "Should have at least one camera");
        
        // Start batch
        cbo.startBatchOperation("test-batch");
        
        // Register each camera
        for (Camera camera : cameras) {
            boolean registered = cbo.registerWithBatchOperation(camera);
            assertTrue(registered, "Camera " + camera.getName() + " should register");
        }
        
        // End batch - should turn off all lights
        cbo.endBatchOperation("test-batch");
        
        System.out.println("Test 1 PASSED: Batch operation registered " + cameras.size() + " cameras");
    }
    
    /**
     * Test 2: Simulate NTC-like sequential captures with batch operation
     * This simulates what happens during nozzle tip calibration where multiple
     * captures are made in sequence.
     */
    @Test
    public void testSequentialCapturesWithBatch() throws Exception {
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
        
        // Load test nozzle tip and part
        NozzleTip nozzleTip = loadTestNozzleTip(Configuration.get(), (ReferenceNozzle) nozzle);
        Part part = loadTestPart(Configuration.get(), "R0805-1K");
        
        assertNotNull(nozzleTip, "Nozzle tip should be loaded");
        assertNotNull(part, "Part should be loaded");
        
        // Set up nozzle
        setNozzleTipSafely((ReferenceNozzle) nozzle, nozzleTip);
        
        SimulatedUpCamera camera = (SimulatedUpCamera) VisionUtils.getBottomVisionCamera(nozzle);
        camera.setErrorOffsets(new Location(LengthUnit.Millimeters, 0.25, 0.75, 0, 13));
        
        // Pick part
        machine.execute(() -> {
            nozzle.pick(part, null);
            return true;
        });
        
        // Simulate NTC-like sequential captures with batch operation
        BoardLocation boardLocation = new BoardLocation(new Board());
        int captureCount = 10;
        
        for (int i = 0; i < captureCount; i++) {
            // Create placement at different angle
            Placement placement = new Placement("P" + i);
            double angle = i * 36.0; // 10 positions around circle
            placement.setLocation(new Location(LengthUnit.Millimeters, 10, 10, 0, angle));
            placement.setPart(part);
            
            // Start batch for this capture
            cbo.startBatchOperation("ntc-capture-" + i);
            
            try {
                ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
                PartAlignmentOffset offset = vision.findOffsets(
                    part, boardLocation, placement, nozzle);
                
                assertNotNull(offset, "Offset should not be null for capture " + i);
                System.out.println("Capture " + i + " at angle " + angle + ": offset=" + 
                    offset.getLocation().getLinearLengthTo(Location.origin).getValue() + "mm");
            }
            finally {
                // End batch - should turn off light
                cbo.endBatchOperation("ntc-capture-" + i);
            }
        }
        
        System.out.println("Test 2 PASSED: Completed " + captureCount + " sequential captures with batch operation");
    }
    
    /**
     * Test 3: Test nested batch operations (simulating complex scenarios)
     */
    @Test
    public void testNestedBatchOperations() throws Exception {
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        List<Camera> cameras = new ArrayList<>(machine.getAllCameras());
        
        // Outer batch
        cbo.startBatchOperation("outer-batch");
        
        // Register cameras with outer batch
        for (Camera camera : cameras) {
            cbo.registerWithBatchOperation(camera);
        }
        
        // Inner batch (nested)
        cbo.startBatchOperation("inner-batch");
        
        // Register same cameras with inner batch
        for (Camera camera : cameras) {
            boolean registered = cbo.registerWithBatchOperation(camera);
            assertTrue(registered, "Camera should register with inner batch");
        }
        
        // End inner batch - should NOT turn off lights (still in outer batch)
        cbo.endBatchOperation("inner-batch");
        
        // End outer batch - should turn off lights now
        cbo.endBatchOperation("outer-batch");
        
        System.out.println("Test 3 PASSED: Nested batch operations handled correctly");
    }
    
    /**
     * Test 4: Rapid sequential batch operations (stress test)
     * This attempts to reproduce the NTC issue where lights might not turn on/off correctly
     */
    @Test
    public void testRapidSequentialBatches() throws Exception {
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation cbo = machine.getCameraBatchOperation();
        Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
        
        // Load test setup
        NozzleTip nozzleTip = loadTestNozzleTip(Configuration.get(), (ReferenceNozzle) nozzle);
        Part part = loadTestPart(Configuration.get(), "R0805-1K");
        
        assertNotNull(nozzleTip, "Nozzle tip should be loaded");
        assertNotNull(part, "Part should be loaded");
        
        setNozzleTipSafely((ReferenceNozzle) nozzle, nozzleTip);
        
        SimulatedUpCamera camera = (SimulatedUpCamera) VisionUtils.getBottomVisionCamera(nozzle);
        camera.setErrorOffsets(new Location(LengthUnit.Millimeters, 0.25, 0.75, 0, 13));
        
        machine.execute(() -> {
            nozzle.pick(part, null);
            return true;
        });
        
        // Rapid sequential batches - try to trigger race conditions
        int iterations = 20;
        for (int i = 0; i < iterations; i++) {
            Placement placement = new Placement("P" + i);
            placement.setLocation(new Location(LengthUnit.Millimeters, 10, 10, 0, i * 18.0));
            placement.setPart(part);
            
            BoardLocation boardLocation = new BoardLocation(new Board());
            
            cbo.startBatchOperation("rapid-" + i);
            try {
                ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
                PartAlignmentOffset offset = vision.findOffsets(
                    part, boardLocation, placement, nozzle);
                assertNotNull(offset);
            }
            finally {
                cbo.endBatchOperation("rapid-" + i);
            }
            
            if ((i + 1) % 5 == 0) {
                System.out.println("Completed " + (i + 1) + " rapid batches");
            }
        }
        
        System.out.println("Test 4 PASSED: Completed " + iterations + " rapid sequential batches");
    }
    
    // Helper methods
    private NozzleTip loadTestNozzleTip(Configuration config, ReferenceNozzle nozzle) throws Exception {
        // Use first available nozzle tip
        for (NozzleTip nt : config.getMachine().getNozzleTips()) {
            if (nt instanceof ReferenceNozzleTip) {
                // Enable calibration
                ((ReferenceNozzleTip) nt).getCalibration().setEnabled(true);
                return nt;
            }
        }
        throw new Exception("No nozzle tips found");
    }
    
    private Part loadTestPart(Configuration config, String partId) throws Exception {
        Part part = config.getPart(partId);
        if (part == null) {
            // Create a test part
            part = new Part(partId);
            part.setPackage(config.getPackage("Test-Package-0.1mm"));
            if (part.getPackage() == null) {
                throw new Exception("Test part " + partId + " not found and no default package");
            }
            part.setHeight(new Length(1.0, LengthUnit.Millimeters));
        }
        return part;
    }
    
    private void setNozzleTipSafely(ReferenceNozzle nozzle, NozzleTip nozzleTip) throws Exception {
        if (nozzle.getNozzleTip() != nozzleTip) {
            if (nozzle.getNozzleTip() != null) {
                nozzle.unloadNozzleTip();
            }
            if (nozzleTip != null) {
                nozzle.loadNozzleTip(nozzleTip, false);
            }
        }
    }
}
