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

    /**
     * The verdict of the pre-check that was run against the first frame of the settling this shot
     * came out of. Filled in asynchronously, by the vision executor, possibly long after the shot
     * itself has been handed over - and possibly never, if the check did not get round to it.
     * Whoever reads it must treat a missing verdict as "no opinion", never as a problem.
     */
    private volatile PreCheckResult preCheckResult;
    
    /**
     * A shot taken for the parallel path has to say where it came from, so the provenance is not
     * optional: a frame that cannot show that the head was still is not a pre-captured placement, it
     * is a photograph. Callers that genuinely have nothing to say must say so with
     * {@link FrameProvenance#unknown()}, which the alignment will then refuse.
     */
    public PreCapturedPlacement(Camera camera, Nozzle nozzle, Mat image, Location shotLocation,
                                Location intendedDisplacementDelta, FrameProvenance provenance) {
        super(camera, nozzle, image, shotLocation, provenance);
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

    /**
     * Get the pre-check verdict, or null if there is none (yet).
     */
    public PreCheckResult getPreCheckResult() {
        return preCheckResult;
    }

    public void setPreCheckResult(PreCheckResult preCheckResult) {
        this.preCheckResult = preCheckResult;
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
