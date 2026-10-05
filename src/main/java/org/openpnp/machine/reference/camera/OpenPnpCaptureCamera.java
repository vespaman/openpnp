/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.camera;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import org.openpnp.capture.CaptureDevice;
import org.openpnp.capture.CaptureFormat;
import org.openpnp.capture.CaptureProperty;
import org.openpnp.capture.CaptureStream;
import org.openpnp.capture.OpenPnpCapture;
import org.openpnp.capture.PropertyLimits;
import org.openpnp.gui.support.Wizard;
import org.openpnp.util.NanosecondTime;
import org.openpnp.machine.reference.camera.wizards.OpenPnpCaptureCameraConfigurationWizard;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.FrameProvenance;
import org.openpnp.spi.BufferedFrames;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.core.Commit;

public class OpenPnpCaptureCamera extends ReferenceCamera implements Runnable, BufferedFrames {
    private OpenPnpCapture capture = new OpenPnpCapture();

    private CaptureDevice device;
    private CaptureFormat format;

    /**
     * The stream. Volatile because the grabbing thread of {@link #frameBuffer} reads it without holding
     * the camera monitor: it must be able to keep grabbing while a capture on demand is in progress, and
     * two consumers of one stream is exactly what the ring exists to prevent.
     */
    private volatile CaptureStream stream;

    @Attribute(required = false)
    private String uniqueId;

    @Attribute(required = false)
    private Integer formatId;

    @Element(required = false)
    private CapturePropertyHolder backLightCompensation = new CapturePropertyHolder(CaptureProperty.BackLightCompensation);

    @Element(required = false)
    private CapturePropertyHolder brightness = new CapturePropertyHolder(CaptureProperty.Brightness);

    @Element(required = false)
    private CapturePropertyHolder contrast = new CapturePropertyHolder(CaptureProperty.Contrast);

    @Element(required = false)
    private CapturePropertyHolder exposure = new CapturePropertyHolder(CaptureProperty.Exposure);

    @Element(required = false)
    private CapturePropertyHolder focus = new CapturePropertyHolder(CaptureProperty.Focus);

    @Element(required = false)
    private CapturePropertyHolder gain = new CapturePropertyHolder(CaptureProperty.Gain);

    @Element(required = false)
    private CapturePropertyHolder gamma = new CapturePropertyHolder(CaptureProperty.Gamma);

    @Element(required = false)
    private CapturePropertyHolder hue = new CapturePropertyHolder(CaptureProperty.Hue);

    @Element(required = false)
    private CapturePropertyHolder powerLineFrequency = new CapturePropertyHolder(CaptureProperty.PowerLineFrequency);

    @Element(required = false)
    private CapturePropertyHolder saturation = new CapturePropertyHolder(CaptureProperty.Saturation);

    @Element(required = false)
    private CapturePropertyHolder sharpness = new CapturePropertyHolder(CaptureProperty.Sharpness);

    @Element(required = false)
    private CapturePropertyHolder whiteBalance = new CapturePropertyHolder(CaptureProperty.WhiteBalance);

    @Element(required = false)
    private CapturePropertyHolder zoom = new CapturePropertyHolder(CaptureProperty.Zoom);

    @Attribute(required = false)
    private boolean freezeProperties = false;

    /**
     * Grab the frames ahead of being asked for them, into a ring, and hand the measurements the frames
     * that are already there. Off by default: it buys about 200 ms per job on the first frame of a
     * settling and removes the wait from the second bottom camera's hunt, at the cost of one thread per
     * camera and of the frames' memory while a session lasts. See
     * docs/design/parallel-bottom-vision/CAPTURE-COST.md.
     */
    @Attribute(required = false)
    private boolean grabAheadEnabled = false;

    /**
     * How many frames the ring holds. They are not small - a 1920x1080 frame is some 8 MB once it is a
     * BufferedImage - so this is deliberately short: enough to cover a settling of a few frames and the
     * still window around it, and no more.
     */
    @Attribute(required = false)
    private int grabAheadDepth = 8;

    /**
     * How long a grabbing session may run without being renewed, in milliseconds. Whoever starts one is
     * expected to end it; this is only the bound on what a caller that forgot can cost.
     */
    @Attribute(required = false)
    private int grabAheadSessionMs = 1500;

    /**
     * How much older than the instant of a capture the <em>content</em> of the frame handed over may be,
     * in milliseconds, when the caller has not said what the frame has to show (see
     * {@link BufferedFrames#setBufferedFrameNotBeforeMs(long)}). 0 means two measured frame intervals,
     * which is how stale the newest frame in the ring can honestly be: one interval because the exposure
     * lies inside the frame period, one because the frame may have arrived an interval ago. Those are the
     * pixels a capture on demand always delivered; asking instead for content from after the instant of
     * the ask means no buffered frame ever qualifies and every capture waits for a frame that does not
     * exist yet.
     */
    @Attribute(required = false)
    private int grabAheadMaxFrameAgeMs = 0;

    /**
     * The content bound of whoever is working with this camera, see
     * {@link BufferedFrames#setBufferedFrameNotBeforeMs(long)}. Volatile because it is written by the
     * vision thread and read by any thread capturing from the ring; 0 when nobody has said anything.
     */
    private volatile long bufferedFrameNotBeforeMs = 0;

    /**
     * The content instant of the last frame the ring handed to a capture.
     *
     * <p>A capture on demand has always delivered a new exposure, and a buffer must not be permitted to
     * deliver less than that. When it does, the settling compares a frame with the very picture it
     * compared a moment ago, scores an exact 0.000, and learns nothing - and because a difference of
     * nothing is also not a bounce, the debounce does not advance and the settling keeps going until the
     * pipeline produces something new. Seen on pn52 (log_J.txt) in a settling that took 115 ms.</p>
     */
    private volatile long lastRingFrameGivenAtMs = 0;

    /**
     * The ring itself, made on first use and thrown away when the stream is reopened, because a frame of
     * the old video format must not be handed out as if it were of the new one.
     */
    private FrameBuffer frameBuffer;

    public List<CaptureDevice> getCaptureDevices() {
        return capture.getDevices();
    }

    @Override
    public synchronized BufferedImage internalCapture() {
        if (! ensureOpen()) {
            return null;
        }
        BufferedImage grabbed = captureFromRing();
        if (grabbed != null) {
            return grabbed;
        }
        try {
            /**
             * The timeout is only needed if the stream is somehow in error and not producing frames (anymore) 
             * which can happen, if you disconnect the USB port and then try to capture from a pipeline.  
             */
            long timeout = System.currentTimeMillis()+500;
            while (!stream.hasNewFrame()) {
                Thread.yield();
                if (System.currentTimeMillis() > timeout) {
                    return null;
                }
            }
            BufferedImage img = stream.capture();
            // No idea when those pixels were exposed: the frame is the age of the wait that produced it,
            // which is the thing capture on demand cannot predict. Say so, and let the caller fall back to
            // the instant the capture returned, as it always has.
            setRawFrameCapturedAtMs(FrameProvenance.UNKNOWN);
            return img;
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * A frame from the ring, if the ring is being filled and has one the caller can use.
     *
     * What the frame may be used for is decided by a bound on its <em>content</em>, not on the instant it
     * was handed over, and the bound comes from the caller: from
     * {@link BufferedFrames#setBufferedFrameNotBeforeMs(long)} if someone said what the frame has to show,
     * and otherwise from how stale a frame a capture on demand would have delivered anyway - one frame
     * interval, which is what a frame returned at T can be at the oldest.
     *
     * Asking instead for content from after the instant of the call was tried first, on the reasoning that
     * capture on demand delivers a frame of now and a frame of the head as it was on the way over looks
     * like a part that is missing. It cannot work, and the machine said so: a frame is stamped with the
     * earliest instant its content can come from, one frame interval before it is returned, so no frame
     * already in the ring can ever satisfy such a bound. A full ring answered nothing, the caller waited
     * for the next frame the camera had not taken yet, and a 9 ms capture became a 23 ms one on the up
     * cameras and cost the fiducial checks of a job 1.2 s, at 33 ms per interval. The correctness that
     * bound was bought to get - not judging a frame exposed before the head arrived - is bought far more
     * cheaply by the caller saying so, which is what bottom vision now does with the arrival instant.
     *
     * The frame is taken from the ring untransformed, which is right here and only here: this method sits
     * under {@code internalCapture()}, and {@code captureTransformed()} applies the camera's transforms
     * to whatever comes up from here, ring or no ring.
     *
     * @return the frame, or null to fall through to capturing on demand.
     */
    private BufferedImage captureFromRing() {
        FrameBuffer buffer = frameBuffer;
        if (buffer == null || !buffer.isSessionActive()) {
            return null;
        }
        try {
            long interval = Math.max(4, buffer.getFrameIntervalMs());
            // Two intervals, not one: the content of a frame is up to one interval behind the instant it
            // was returned, and the newest frame in hand may itself have arrived an interval ago. Make the
            // allowance a single interval and the newest frame held fails the bound by a hair on every
            // call, which is the trap this whole change exists to get out of.
            long maxAge = grabAheadMaxFrameAgeMs > 0 ? grabAheadMaxFrameAgeMs : 2*interval;
            // The newest frame in the ring answers immediately whenever its content is from after the
            // bound, which is the normal case, because the ring has been filling while the head travelled
            // here. Only a caller who demands more than the ring holds - a frame from after the head
            // arrived, say, taken before the head arrived - has to wait, and then for one frame rather
            // than for an exposure. Bounded by three intervals either way, so that a ring that is not
            // being fed costs a capture on demand rather than the caller's patience.
            long deadline = NanosecondTime.getRuntimeMilliseconds()
                    + Math.min(Math.max(20, 3*interval), 250);
            long askedAtMs = NanosecondTime.getRuntimeMilliseconds();
            FrameBuffer.Frame before = buffer.newest();
            // Either the caller demands content from a moment of its own choosing, or it merely demands
            // not the picture it was given a moment ago. Whichever is later is the bound.
            long notBeforeMs = Math.max(bufferedFrameNotBeforeMs, lastRingFrameGivenAtMs + 1);
            FrameBuffer.Frame frame = buffer.awaitNewestForCapture(notBeforeMs, maxAge,
                    deadline);
            if (frame == null) {
                return null;
            }
            if (NanosecondTime.getRuntimeMilliseconds() - askedAtMs > 4) {
                // A capture that had to wait is the interesting one, and the reason is one of two quite
                // different things: either the video pipeline is genuinely that far behind the world (in
                // which case nothing can be done except ask the pipeline for less latency, and the frame
                // we certify will be that old whatever we do), or the grabbing thread was starved and the
                // ring fell behind its own frame interval (in which case the frames exist and are merely
                // not in our hands yet). Say which, by comparing the deficit against the interval.
                long waitedMs = NanosecondTime.getRuntimeMilliseconds() - askedAtMs;
                String held = before == null ? "nothing was held"
                        : before.getCapturedAtMs() == FrameProvenance.UNKNOWN
                                ? "newest held of unknown age"
                                : "newest held " + (askedAtMs - before.getCapturedAtMs()) + " ms old"
                                        + (notBeforeMs == 0 ? ""
                                                : ", its content "
                                                        + (notBeforeMs
                                                                - before.getCapturedAtMs())
                                                        + " ms behind the bound"
                                                        + (lastRingFrameGivenAtMs
                                                                >= bufferedFrameNotBeforeMs
                                                                        ? " (the one just given)" : ""));
                Logger.trace(String.format("[RING] %s capture waited %d ms: %s, interval %d ms,"
                                +" answered at %s",
                        getName(), waitedMs, held, interval,
                        notBeforeMs == 0
                                ? (askedAtMs - frame.getCapturedAtMs()) + " ms of age"
                                : (notBeforeMs - frame.getCapturedAtMs())
                                        + " ms behind the bound"));
            }
            lastRingFrameGivenAtMs = frame.getCapturedAtMs();
            setRawFrameCapturedAtMs(frame.getCapturedAtMs());
            return frame.getImage();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void setBufferedFrameNotBeforeMs(long notBeforeMs) {
        this.bufferedFrameNotBeforeMs = notBeforeMs;
    }

    public int getGrabAheadMaxFrameAgeMs() {
        return grabAheadMaxFrameAgeMs;
    }

    public void setGrabAheadMaxFrameAgeMs(int grabAheadMaxFrameAgeMs) {
        this.grabAheadMaxFrameAgeMs = grabAheadMaxFrameAgeMs;
    }

    @Override
    protected synchronized boolean isOpen() {
        return super.isOpen() 
                && stream != null;
    }
    
    @Override
    public boolean isFrameBuffering() {
        FrameBuffer buffer = frameBuffer;
        return buffer != null && buffer.isSessionActive();
    }

    @Override
    public Frame awaitEarliestBufferedFrameAtOrAfter(long notBeforeMs, long deadlineMs)
            throws InterruptedException {
        FrameBuffer buffer = frameBuffer;
        return buffer == null ? null : buffer.awaitEarliestAtOrAfter(notBeforeMs, deadlineMs);
    }

    @Override
    public long getBufferedFrameIntervalMs() {
        FrameBuffer buffer = frameBuffer;
        return buffer == null ? 0 : buffer.getFrameIntervalMs();
    }

    @Override
    public void beginFrameBufferSession(String reason, long maxMs) {
        if (!grabAheadEnabled) {
            return;
        }
        // A session is a new piece of work on the head, so the frame that was last handed out belongs to
        // the work before it. See lastRingFrameGivenAtMs.
        lastRingFrameGivenAtMs = 0;
        // Deliberately not under the camera monitor: a capture on demand can hold that monitor for a
        // quarter of a second, and the point of this call is that it is made while such a thing may be
        // going on. A stream that is being reopened right now is answered for by the grabbing thread's
        // own failure handling.
        CaptureStream grabbing = stream;
        if (grabbing == null) {
            return;
        }
        FrameBuffer buffer = getFrameBuffer();
        if (buffer == null) {
            return;
        }
        buffer.startSession(new FrameBuffer.Source() {
            @Override
            public boolean hasNewFrame() {
                return grabbing.hasNewFrame();
            }

            @Override
            public BufferedImage grab() throws Exception {
                return grabbing.capture();
            }
        }, reason, Math.min(maxMs, grabAheadSessionMs));
    }

    @Override
    public void endFrameBufferSession(String reason) {
        FrameBuffer buffer = frameBuffer;
        if (buffer != null) {
            buffer.endSession(reason);
        }
    }

    /**
     * The ring, made on first use when the feature is enabled, and never made when it is not: a camera
     * that does not grab ahead must not pay for one, and everything that asks about buffered frames is
     * prepared to be told there are none.
     *
     * The ring is told how to make a frame out of what it holds, i.e. it hands out the transformed
     * picture to whoever picks one out of the past, which is the same bargain {@code capture()} makes.
     * This is not decoration: Upp1 is rotated -90 and flipped, so a frame handed out untransformed is a
     * picture in which the part sits nowhere the pipeline's search window is, and the answer comes back
     * "no rectangle in the pipeline result" for a part that is plainly in the frame.
     */
    private synchronized FrameBuffer getFrameBuffer() {
        if (!grabAheadEnabled) {
            return null;
        }
        if (frameBuffer == null) {
            frameBuffer = new FrameBuffer(getName(), Math.max(2, grabAheadDepth), this::transformImage);
        }
        return frameBuffer;
    }

    /**
     * Throw the ring away. Called whenever the stream is opened or closed: the frames in it are of the
     * format that was set then, and one of those must not be handed out later as if it were of whatever
     * the format is now.
     */
    private void discardFrameBuffer() {
        FrameBuffer buffer = frameBuffer;
        if (buffer != null) {
            buffer.reset();
        }
    }

    @Override
    public synchronized boolean hasNewFrame() {
        if (isOpen()) {
            return stream.hasNewFrame();
        }        
        return false;
    }

    @Override
    public synchronized void open() throws Exception {
        stop();
        discardFrameBuffer();

        if (stream != null) {
            try {
                stream.close();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            stream = null;
        }
        setPropertiesStream(stream);

        clearCalibrationCache();
        
        // If a device and format are not set, see if we can read them from the stored
        // properties. This will only happen during startup.
        if (device == null && format == null) {
            if (uniqueId == null) {
                return;
            }
            int n = 0;
            for (CaptureDevice device : capture.getDevices()) {
                if (device.getUniqueId().equals(uniqueId)) {
                    this.device = device;
                    if (++n > 1) {
                        Logger.warn("Multiple cameras found with ID {} for camera {}", uniqueId, getName());
                    }
                }
            }
            if (device == null) {
                Logger.warn("No camera found with ID {} for camera {}", uniqueId, getName());
                return;
            }

            if (formatId == null) {
                return;
            }
            for (CaptureFormat format : device.getFormats()) {
                if (format.getFormatId() == formatId) {
                    this.format = format;
                }
            }
            if (format == null) {
                Logger.warn("No format found with ID {} for camera {}", formatId, getName());
            }
        }


        if (device == null) {
            Logger.debug("open called with null device");
            return;
        }
        if (format == null) {
            Logger.debug("open called with null format");
            return;
        }

        try {
            width = null;
            height = null;

            stream = device.openStream(format);
            setPropertiesStream(stream);
        }
        catch (Exception e) {
            e.printStackTrace();
            return;
        }

        super.open();
    }

    public synchronized double estimateCaptureFps() throws Exception {
        ensureOpen();
        if (stream == null || format == null) {
            throw new Exception("Camera stream not properly initialized."); 
        }
        // Stop the broadcasting thread.
        stop();
        // And the grabbing ahead of the ring, which would otherwise be asking for frames at the same
        // time and be measured as if it were the caller.
        endFrameBufferSession("frame rate measurement");
        // Start warmup capture timer for 1 second.
        boolean warmup = true;
        long t0 = System.currentTimeMillis();
        long timeout = t0 + 1000;
        long t1 = 0;
        int capturedFrames = 0;
        for (int frames = 0; frames < 480; frames++) {
            stream.capture();
            while (true) {
                t1 = System.currentTimeMillis();
                if (stream.hasNewFrame()) {
                    capturedFrames++;
                    break;
                }
                if (t1 > timeout) {
                    break;
                }
            }
            if (t1 > timeout) {
                if (warmup) {
                    // Warmup complete. 
                    warmup = false;
                    // Start the real capture timer of 2 seconds.
                    t0 = t1;
                    timeout = t0 + 2000;
                    capturedFrames = 0;
                }
                else {
                    break;
                }
            }
        }
        // Start the broadcasting thread.
        start();
        // Compute the fps.
        return capturedFrames*1000./(t1-t0);
    }

    @Commit
    protected void commit() throws Exception {
        super.commit();
        backLightCompensation.setCamera(this);
        brightness.setCamera(this);
        contrast.setCamera(this);
        exposure.setCamera(this);
        focus.setCamera(this);
        gain.setCamera(this);
        gamma.setCamera(this);
        hue.setCamera(this);
        powerLineFrequency.setCamera(this);
        saturation.setCamera(this);
        sharpness.setCamera(this);
        whiteBalance.setCamera(this);
        zoom.setCamera(this);
    }

    private void setPropertiesStream(CaptureStream stream) {
        backLightCompensation.setStream(stream);
        brightness.setStream(stream);
        contrast.setStream(stream);
        exposure.setStream(stream);
        focus.setStream(stream);
        gain.setStream(stream);
        gamma.setStream(stream);
        hue.setStream(stream);
        powerLineFrequency.setStream(stream);
        saturation.setStream(stream);
        sharpness.setStream(stream);
        whiteBalance.setStream(stream);
        zoom.setStream(stream);
    }

    @Override
    public synchronized void close() throws IOException {
        super.close();
        discardFrameBuffer();

        if (stream != null) {
            try {
                stream.close();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            stream = null;
        }

        capture.close();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new OpenPnpCaptureCameraConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    public CaptureDevice getDevice() {
        return device;
    }

    public void setDevice(CaptureDevice device) {
        this.device = device;
        if (device == null) {
            this.uniqueId = null;
        }
        else {
            this.uniqueId = device.getUniqueId();
        }
        firePropertyChange("device", null, device);
    }

    public CaptureFormat getFormat() {
        return format;
    }

    public void setFormat(CaptureFormat format) {
        this.format = format;
        if (format == null) {
            this.formatId = null;
        }
        else {
            this.formatId = format.getFormatId();
        }
        firePropertyChange("format", null, format);
        firePropertyChange("formatName", null, format.toString());
    }

    // Note: due to an instability of the CaptureFormat.equals() 
    // method, we provide a format setter/getter by Name.

    public String getFormatName() {
        return format != null ? format.toString() : null;
    }

    public void setFormatName(String formatName) {
        if (formatName == null) {
            format = null;
        }
        else if (device != null) {
            for (CaptureFormat format : device.getFormats()) {
                if (format.toString().equals(formatName)) {
                    setFormat(format);
                    break;
                }
            }
        }
    }

    public CapturePropertyHolder getBackLightCompensation() {
        return backLightCompensation;
    }

    public CapturePropertyHolder getBrightness() {
        return brightness;
    }

    public CapturePropertyHolder getContrast() {
        return contrast;
    }

    public CapturePropertyHolder getExposure() {
        return exposure;
    }

    public CapturePropertyHolder getFocus() {
        return focus;
    }

    public CapturePropertyHolder getGain() {
        return gain;
    }

    public CapturePropertyHolder getGamma() {
        return gamma;
    }

    public CapturePropertyHolder getHue() {
        return hue;
    }

    public CapturePropertyHolder getPowerLineFrequency() {
        return powerLineFrequency;
    }

    public CapturePropertyHolder getSaturation() {
        return saturation;
    }

    public CapturePropertyHolder getSharpness() {
        return sharpness;
    }

    public CapturePropertyHolder getWhiteBalance() {
        return whiteBalance;
    }

    public CapturePropertyHolder getZoom() {
        return zoom;
    }

    public static class CapturePropertyHolder extends AbstractModelObject {
        @Attribute(required = false)
        private CaptureProperty property;

        @Attribute(required = false)
        private Integer value;

        @Attribute(required = false)
        private Boolean auto;

        private OpenPnpCaptureCamera camera;
        private CaptureStream stream;

        public CapturePropertyHolder(CaptureProperty property) {
            this.property = property;
        }

        public CapturePropertyHolder() {
            this(null);
        }

        public void setCamera(OpenPnpCaptureCamera camera) {
            this.camera = camera;
            camera.addPropertyChangeListener("device", e -> {
                firePropertyChange("supported", null, isSupported());
            });
            camera.addPropertyChangeListener("format", e -> {
                firePropertyChange("supported", null, isSupported());
            });
        }

        public void setStream(CaptureStream stream) {
            this.stream = stream;
            if (stream == null) {
                return;
            }
            if (auto != null) {
                setAuto(auto);
            }
            if (value != null) {
                setValue(value);
            }
            firePropertyChange("supported", null, isSupported());
            firePropertyChange("autoSupported", null, isAutoSupported());
            firePropertyChange("min", null, getMin());
            firePropertyChange("max", null, getMax());
            firePropertyChange("default", null, getDefault());
            firePropertyChange("value", null, getValue());
            firePropertyChange("auto", null, isAuto());
        }

        public int getMin() {
            try {
                PropertyLimits limits = stream.getPropertyLimits(property);
                return limits.getMin();
            }
            catch (Exception e) {
                return 0;
            }
        }

        public int getMax() {
            try {
                PropertyLimits limits = stream.getPropertyLimits(property);
                return limits.getMax();
            }
            catch (Exception e) {
                return 0;
            }
        }

        public int getDefault() {
            try {
                PropertyLimits limits = stream.getPropertyLimits(property);
                return limits.getDefault();
            }
            catch (Exception e) {
                return 0;
            }
        }

        public boolean isAuto() {
            try {
                if (this.auto == null || !this.camera.isFreezeProperties()) {
                    return this.auto = stream.getAutoProperty(property);
                }
                else {
                    return this.auto;
                }
            }
            catch (Exception e) {
                return false;
            }
        }

        public void setAuto(boolean auto) {
            try {
                stream.setAutoProperty(property, auto);
                this.auto = auto;
                firePropertyChange("auto", null, auto);
            }
            catch (Exception e) {
            }
        }

        public void setValue(int value) {
            try {
                stream.setProperty(property, value);
                this.value = value;
                firePropertyChange("value", null, value);
            }
            catch (Exception e) {
            }
        }

        public int getValue() {
            try {
                if (this.value == null || !this.camera.isFreezeProperties()) {
                    return this.value = stream.getProperty(property);
                }
                else {
                    return this.value;
                }
            }
            catch (Exception e) {
                return 0;
            }
        }

        public boolean isSupported() {
            try {
                stream.getPropertyLimits(property);
                return true;
            }
            catch (Exception e) {
                return false;
            }
        }

        public boolean isAutoSupported() {
            try {
                stream.getAutoProperty(property);
                return true;
            }
            catch (Exception e) {
                return false;
            }
        }
    }

    public boolean isFreezeProperties() {
        return freezeProperties;
    }

    public void setFreezeProperties(boolean freezeProperties) {
        Object oldValue = this.freezeProperties;
        this.freezeProperties = freezeProperties;
        if (! freezeProperties) {
            // Unfrozen properties might be read back different. 
            reapplyProperties();
        }
        firePropertyChange("freezeProperties", oldValue, freezeProperties);
    }

    public void reapplyProperties() {
        setPropertiesStream(stream);
    }

    @Override
    public void home() throws Exception {
        super.home();
        if (isFreezeProperties()) {
            reapplyProperties();
        }
    }
}
