package org.openpnp.machine.reference;
import java.util.ArrayList;
import java.util.List;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Camera;
import org.pmw.tinylog.Logger;


// This class is used to keep track of ReferenceMachine using one or more cameras to
// take several images in a batch. It lets the camera defer having its light turned off
// until the end of the batch of operations.
public class ReferenceCameraBatchOperation implements CameraBatchOperation {

    // This is the list of cameras used in this operation, or
    // null if there is no batch operation in progress
    private List<Camera> cameras;
    private int nestingLevel = 0;
    private String currentBatchName = null;

    // Start a batch
    public void startBatchOperation(String name) {
        if (cameras==null) {
            cameras = new ArrayList<Camera>();
        }
        nestingLevel += 1;
        currentBatchName = name;
        Logger.trace("Batch START level {} '{}' - cameras: {}",nestingLevel, name, cameras.size());
    }

    // End the batch operation, and get any cameras used in this operation to turn off their lights.
    public synchronized void endBatchOperation(String name) throws Exception {
        Logger.trace("Batch END level {} '{}' - expected: {}",nestingLevel, name, currentBatchName);

        nestingLevel -= 1;

        if (nestingLevel==0) {
            List<Camera> camerasFormerlyInUse = cameras;
            cameras = null;
            currentBatchName = null;
            Logger.debug("Batch operation '{}' complete - turning off lights for {} cameras", 
                name, camerasFormerlyInUse.size());
            for (Camera c: camerasFormerlyInUse) {
                Logger.debug("  Camera '{}' - turning off light", c.getName());
                c.actuateLightAfterCapture();
            }
        }

        if(nestingLevel<0) {
            Logger.error("Batch operation underflow - nesting level {}", nestingLevel);
            nestingLevel = 0;
        }
    }

    public synchronized boolean registerWithBatchOperation(Camera c) {
        if (cameras==null) {
            // There is no batch in progress
            Logger.trace("Camera '{}' registration attempted but no batch in progress", c.getName());
            return false;
        }

        if (!cameras.contains(c)) {
            Logger.debug("Camera '{}' registered with batch '{}' (level {})", 
                c.getName(), currentBatchName, nestingLevel);
            cameras.add(c);
        } else {
            Logger.trace("Camera '{}' already registered with batch '{}' (level {})", 
                c.getName(), currentBatchName, nestingLevel);
        }

        return true;
    }
}
