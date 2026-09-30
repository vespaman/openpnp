package org.openpnp.model;

/**
 * Thrown when a frame is offered to vision that cannot show that the head was still while it was
 * captured.
 *
 * <p>This is not a malfunction of the machine and not an error in the image: the frame simply is not
 * evidence of where the part is. It exists as its own type so that the caller can tell "this frame
 * was not qualified" apart from "the part could not be found", count the two separately, and choose
 * what to do about each. See {@link FrameProvenance}.</p>
 */
public class FrameNotStillException extends Exception {
    private static final long serialVersionUID = 1L;

    private final FrameProvenance provenance;

    public FrameNotStillException(String message, FrameProvenance provenance) {
        super(message);
        this.provenance = provenance;
    }

    /**
     * The provenance that failed to qualify, possibly null when the frame carried none at all - which
     * is itself a failure, as nothing was claimed.
     */
    public FrameProvenance getProvenance() {
        return provenance;
    }
}
