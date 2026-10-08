package org.openpnp.model;

import org.opencv.core.Mat;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;

/**
 * A frame captured ahead of the analysis, for the parallel alignment path.
 *
 * The parallel path captures without moving the head onto each shot, so the analysis has to be told how
 * far off the shot location the picture actually is. It is told exactly one thing, {@link
 * #getShotDisplacement()}: the difference between where the shot wanted to be and where the machine said
 * the nozzle was when the frame was exposed. There used to be a second, predicted answer, worked out of
 * the nozzle tip calibration; it was deleted along with the setting that selected it, because it could
 * not see either the rotation the head was really standing at or the mismatch between the nozzle spacing
 * and the camera spacing.
 */
public class PreCapturedPlacement extends CapturedShot {
    /**
     * The verdict of the pre-check that was run against the first frame of the settling this shot
     * came out of. Filled in asynchronously, by the vision executor, possibly long after the shot
     * itself has been handed over - and possibly never, if the check did not get round to it.
     * Whoever reads it must treat a missing verdict as "no opinion", never as a problem.
     */
    private volatile PreCheckResult preCheckResult;

    /**
     * Where the machine said this nozzle actually was when the frame was exposed, which is the whole
     * basis of the displacement this shot is analysed with.
     *
     * It is set rather than passed in because it is a reading, taken as close to the exposure as the
     * code can manage, and not something a shot is conceived with. A grab-ahead frame is the one frame
     * this cannot be honest about, since the head was still on its way past when it was taken; whether
     * a frame may be trusted at all is the provenance's to decide, not this field's.
     */
    private volatile Location actualAtCapture;
    
    /**
     * A shot taken for the parallel path has to say where it came from, so the provenance is not
     * optional: a frame that cannot show that the head was still is not a pre-captured placement, it
     * is a photograph. Callers that genuinely have nothing to say must say so with
     * {@link FrameProvenance#unknown()}, which the alignment will then refuse.
     */
    public PreCapturedPlacement(Camera camera, Nozzle nozzle, Mat image, Location shotLocation,
                                FrameProvenance provenance) {
        super(camera, nozzle, image, shotLocation, provenance);
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

    /**
     * Where the nozzle actually was when this frame was exposed, or null when nobody looked.
     */
    public Location getActualAtCapture() {
        return actualAtCapture;
    }

    public void setActualAtCapture(Location actualAtCapture) {
        this.actualAtCapture = actualAtCapture == null ? null
                : actualAtCapture.convertToUnits(LengthUnit.Millimeters);
    }

    /**
     * What the position at capture says this shot was displaced by: wanted minus actual, in the XY the
     * analysis can compensate, in millimetres. This is the same quantity the sequential path records
     * against the pipeline shot ({@code CvPipeline.setShotDisplacement}), arrived at here by measuring
     * rather than by predicting it from the calibration. Null while no position was recorded, which is
     * not the same as zero: a shot nobody looked at has no opinion.
     */
    public Location getShotDisplacement() {
        return shotDisplacement(getShotLocation(), actualAtCapture);
    }

    /**
     * The same, for a shot that is still being made: the capture code needs the answer before it has a
     * shot to put it in. Returns null when either side is unknown, because a shot whose position was
     * never read has no opinion rather than a zero one.
     */
    public static Location shotDisplacement(Location wanted, Location actual) {
        if (wanted == null || actual == null) {
            return null;
        }
        Location difference = wanted.convertToUnits(LengthUnit.Millimeters)
                .subtract(actual.convertToUnits(LengthUnit.Millimeters));
        // Z and rotation belong to the shot for other purposes and mean nothing as a displacement; the
        // sequential path likewise hands on an XY-only vector.
        return difference.deriveLengths(null, null, new Length(0.0, LengthUnit.Millimeters), 0.0);
    }
    
    @Override
    public String toString() {
        return "PreCapturedPlacement{" +
                "camera=" + getCamera().getId() +
                ", nozzle=" + getNozzle().getId() +
                ", shotLocation=" + getShotLocation() +
                ", shotDisplacement=" + getShotDisplacement() +
                ", actualAtCapture=" + actualAtCapture +
                '}';
    }
}
