package org.openpnp.model;

import org.opencv.core.Mat;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;

/**
 * Data class holding pre-captured image data for parallel vision processing.
 * 
 * This class contains NO hardware references that could cause side-effects.
 * It's used to pass pre-captured images from main thread (capture) to background threads (processing).
 */
public class CapturedShot {
    private final Camera camera;           // For identification only, not used for hardware access
    private final Nozzle nozzle;           // For identification only
    private final Mat image;               // Pre-captured image (OpenCV native memory)
    private final Location shotLocation;   // Where image was captured
    private final long captureTimestamp;
    private volatile FrameProvenance provenance; // What may be claimed about the moment of capture
    private volatile boolean released;
    
    public CapturedShot(Camera camera, Nozzle nozzle, Mat image, Location shotLocation) {
        this(camera, nozzle, image, shotLocation, FrameProvenance.unknown());
    }

    public CapturedShot(Camera camera, Nozzle nozzle, Mat image, Location shotLocation,
            FrameProvenance provenance) {
        this.camera = camera;
        this.nozzle = nozzle;
        this.image = image;
        this.shotLocation = shotLocation;
        this.captureTimestamp = System.currentTimeMillis();
        this.provenance = provenance == null ? FrameProvenance.unknown() : provenance;
        this.released = false;
    }
    
    public Camera getCamera() {
        return camera;
    }
    
    public Nozzle getNozzle() {
        return nozzle;
    }
    
    /**
     * Get the pre-captured image. Caller should clone if modifying.
     */
    public Mat getImage() {
        return image;
    }
    
    public Location getShotLocation() {
        return shotLocation;
    }
    
    public long getCaptureTimestamp() {
        return captureTimestamp;
    }

    /**
     * What may be claimed about the moment this frame was captured. Never null; a frame that carries
     * no statement is treated as not still, see {@link FrameProvenance#isStill()}.
     */
    public FrameProvenance getProvenance() {
        return provenance;
    }

    public void setProvenance(FrameProvenance provenance) {
        this.provenance = provenance == null ? FrameProvenance.unknown() : provenance;
    }

    /**
     * Whether this frame may be aligned upon. Kept on the shot rather than asked of the camera that
     * produced it, because by the time the analysis runs the camera has moved on to the next
     * settling and no longer knows anything about this frame.
     */
    public boolean isStill() {
        return provenance.isStill();
    }
    
    /**
     * Release native OpenCV memory associated with this captured shot.
     * MUST be called when shot is no longer needed to prevent memory leaks.
     */
    public void release() {
        if (image != null && !released) {
            image.release();
            this.released = true;
        }
    }
    
    public boolean isReleased() {
        return released;
    }
}
