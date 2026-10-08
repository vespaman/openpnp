package org.openpnp.model;

import org.openpnp.spi.Nozzle;

/**
 * Thrown when the head is standing where a parallel batch wanted to take its pictures but one of its
 * nozzles is further from where its own shot would be than the machine is willing to see through.
 *
 * <p>This is not a malfunction either. The displacement that a bottom vision analysis can be asked to
 * compensate is bounded by the camera's field of view and by the calibration that says where the
 * camera is; beyond that the picture is not a slightly-off version of the part, it is a picture of
 * something else. The type exists so that the batch can tell "this one cannot be done in parallel"
 * apart from "the part could not be found" and "the frame was not still", and go and do it the
 * ordinary way instead - which it can still do here, because the exception is thrown before anything
 * has been exposed. See the limits on {@link org.openpnp.spi.Machine}
 * ({@code displacement-max-distance}, {@code displacement-max-Z-diff}) and
 * docs/design/parallel-bottom-vision/runtime-displacement-plan.md (D5).</p>
 */
public class DisplacementExceededException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Nozzle nozzle;
    private final Location shotLocation;
    private final Location actualLocation;

    public DisplacementExceededException(String message, Nozzle nozzle, Location shotLocation,
            Location actualLocation) {
        super(message);
        this.nozzle = nozzle;
        this.shotLocation = shotLocation;
        this.actualLocation = actualLocation;
    }

    /**
     * The nozzle that could not be reached, possibly null when the check had no nozzle to blame.
     */
    public Nozzle getNozzle() {
        return nozzle;
    }

    /**
     * Where this nozzle's own shot would have been taken from.
     */
    public Location getShotLocation() {
        return shotLocation;
    }

    /**
     * Where the nozzle actually was when the batch was asked to expose.
     */
    public Location getActualLocation() {
        return actualLocation;
    }
}
