package org.openpnp.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;

/**
 * Thread-safe buffer for managing pre-captured shots during parallel vision processing.
 * 
 * Main thread writes shots, background threads read shots - uses ConcurrentHashMap for visibility.
 */
public class ImageBuffer {
    private final ConcurrentMap<Nozzle, CapturedShot> shotsByNozzle;
    
    public ImageBuffer() {
        this.shotsByNozzle = new ConcurrentHashMap<>();
    }
    
    /**
     * Store a captured shot for a specific nozzle.
     * Called by main thread during pre-capture phase.
     * Works with both CapturedShot and PreCapturedPlacement.
     */
    public void addShot(Nozzle nozzle, CapturedShot shot) {
        shotsByNozzle.put(nozzle, shot);
    }
    
    /**
     * Get the pre-captured shot for a specific nozzle.
     * Called by background threads during processing phase.
     * Returns CapturedShot which can be cast to PreCapturedPlacement if needed.
     */
    public CapturedShot getShot(Nozzle nozzle) {
        return shotsByNozzle.get(nozzle);
    }
    
    /**
     * Check if a shot exists for a specific nozzle.
     */
    public boolean hasShot(Nozzle nozzle) {
        return shotsByNozzle.containsKey(nozzle);
    }
    
    /**
     * Release all captured shots and their OpenCV native memory.
     * MUST be called when buffer is no longer needed to prevent memory leaks.
     */
    public void release() {
        for (CapturedShot shot : shotsByNozzle.values()) {
            shot.release();
        }
        shotsByNozzle.clear();
    }
    
    /**
     * Get the number of captured shots.
     */
    public int size() {
        return shotsByNozzle.size();
    }
}
