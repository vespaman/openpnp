package org.openpnp.model;

import org.opencv.core.Mat;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;

/**
 * Extended CapturedShot that includes the intended displacement delta for parallel alignment.
 * 
 * This is needed because when we capture images in parallel without moving the head,
 * we still need to pass the displacement delta (including nozzle tip runout) to the
 * vision processing code, just like in sequential alignment.
 * 
 * The displacement delta represents what we WOULD have moved if doing sequential alignment,
 * even if no physical move occurred during parallel capture.
 */
public class PreCapturedPlacement extends CapturedShot {
    /**
     * The intended displacement delta that would have been applied during sequential capture.
     * This includes both the camera's configured displacement AND the nozzle tip runout error.
     */
    private final Location intendedDisplacementDelta;
    
    public PreCapturedPlacement(Camera camera, Nozzle nozzle, Mat image, Location shotLocation,
                                Location intendedDisplacementDelta) {
        super(camera, nozzle, image, shotLocation);
        this.intendedDisplacementDelta = intendedDisplacementDelta;
    }
    
    /**
     * Get the intended displacement delta for this placement.
     * This is the displacement that was SUPPOSED to be applied (including runout),
     * even if no physical move occurred during parallel capture.
     */
    public Location getIntendedDisplacementDelta() {
        return intendedDisplacementDelta;
    }
    
    @Override
    public String toString() {
        return "PreCapturedPlacement{" +
                "camera=" + getCamera().getId() +
                ", nozzle=" + getNozzle().getId() +
                ", shotLocation=" + getShotLocation() +
                ", intendedDisplacementDelta=" + intendedDisplacementDelta +
                '}';
    }
}
