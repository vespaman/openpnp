package org.openpnp.model;

/**
 * What may be claimed about the moment a frame was captured.
 *
 * <p>Bottom vision measures a part, so a frame is only worth as much as the claim that the head was
 * not moving while the camera integrated it. "The head had already stopped" is not such a claim:
 * the head stops commanding motion long before it stops moving, and on this machine the ringing
 * lasts tens of milliseconds after the last move has completed. Everything that is decided about a
 * frame - whether it may be aligned upon, which settling it belongs to, whether another camera's
 * frame fell inside the same still window - is decided from this object rather than from the
 * goodwill of whoever captured the frame.</p>
 *
 * <p>The rule is deliberately fail-closed: a frame that cannot say where it came from is not still.
 * It is better for a job to align a part the slow way than to place a part where a blurred image
 * said it was.</p>
 *
 * <p>Times are milliseconds on the {@link org.openpnp.util.NanosecondTime} scale, i.e. relative to
 * the start of the JVM. They are only ever compared with each other.</p>
 */
public class FrameProvenance {

    /**
     * How the frame came to be. The origin decides on its own whether the frame can ever be still,
     * so that a frame captured without settling cannot talk its way in by carrying a window.
     */
    public enum Origin {
        /**
         * The frame that ended a settling, i.e. one of the frames the settling agreed were the same.
         * Still by definition, because the settling is exactly the measurement of "no longer moving".
         */
        Settled,
        /**
         * A frame captured immediately after the head stopped, with no settling at all. This is what
         * the second camera used to produce, and it is the frame the requirements forbid.
         */
        QuickCapture,
        /**
         * A frame from a camera that free-runs while another camera settles. It is still if - and
         * only if - it was captured inside the settling camera's still window.
         */
        Streamed,
        /**
         * A frame a settling handed back because it gave up waiting. The waiting is not the
         * measurement; the measurement said the mechanics had not stopped.
         */
        SettleTimedOut,
        /**
         * A frame handed to the pre-check while the mechanics were still ringing: the first frame of a
         * settling, or the first grab of a hunt. Never still - that is what it is for - and never used
         * to align on, but worth keeping, because the difference between this frame and the one the
         * alignment ended up with is the motion itself.
         */
        Ringing,
        /**
         * Nothing is known. Not still.
         */
        Unknown
    }

    public static final long UNKNOWN = Long.MIN_VALUE;

    private final Origin origin;
    private final long arrivalAtMs;
    private final long capturedAtMs;
    private final long stillWindowStartAtMs;
    private final int frameIndex;

    private FrameProvenance(Origin origin, long arrivalAtMs, long capturedAtMs,
            long stillWindowStartAtMs, int frameIndex) {
        this.origin = origin;
        this.arrivalAtMs = arrivalAtMs;
        this.capturedAtMs = capturedAtMs;
        this.stillWindowStartAtMs = stillWindowStartAtMs;
        this.frameIndex = frameIndex;
    }

    /**
     * The frame that ended a settling.
     *
     * @param arrivalAtMs when the head was reported at stillstand after moving to the cameras,
     *            {@link #UNKNOWN} if nobody recorded it
     * @param capturedAtMs when the camera delivered this frame
     * @param stillWindowStartAtMs the start of the still window, i.e. the capture time of the frame
     *            that this one was compared with to decide that the mechanics had settled; a frame
     *            from another camera is inside the window when it was captured at or after this
     *            time. {@link #UNKNOWN} if the camera cannot say.
     * @param frameIndex how many frames this camera had captured since the arrival, this one included
     */
    public static FrameProvenance settled(long arrivalAtMs, long capturedAtMs,
            long stillWindowStartAtMs, int frameIndex) {
        return new FrameProvenance(Origin.Settled, arrivalAtMs, capturedAtMs, stillWindowStartAtMs,
                frameIndex);
    }

    /**
     * A frame captured without settling. Never still, whatever the timings say: the whole point is
     * that nobody measured the motion.
     */
    public static FrameProvenance quickCapture(long arrivalAtMs, long capturedAtMs) {
        return new FrameProvenance(Origin.QuickCapture, arrivalAtMs, capturedAtMs, UNKNOWN, 1);
    }

    /**
     * A frame from a camera that free-runs during somebody else's settling. Becomes usable when the
     * window is known and the frame falls inside it.
     */
    public static FrameProvenance streamed(long arrivalAtMs, long capturedAtMs,
            long stillWindowStartAtMs, int frameIndex) {
        return new FrameProvenance(Origin.Streamed, arrivalAtMs, capturedAtMs, stillWindowStartAtMs,
                frameIndex);
    }

    /**
     * A frame handed back by a settling that ran out of time. Never still: the settling is the
     * measurement, and this is the measurement saying it never got an answer.
     */
    public static FrameProvenance settleTimedOut(long arrivalAtMs, long capturedAtMs,
            int frameIndex) {
        return new FrameProvenance(Origin.SettleTimedOut, arrivalAtMs, capturedAtMs, UNKNOWN,
                frameIndex);
    }

    /**
     * The first frame of a settling or of a hunt, handed to the pre-check while the mechanics were still
     * ringing. It carries the instant it was taken and the batch it belongs to, and no claim about
     * stillness: it is the "before" of a settling, and only worth having next to the "after".
     *
     * @param arrivalAtMs when the head was reported at stillstand after moving to the cameras.
     * @param capturedAtMs when this frame was captured, on the same millisecond scale.
     */
    public static FrameProvenance ringing(long arrivalAtMs, long capturedAtMs) {
        return new FrameProvenance(Origin.Ringing, arrivalAtMs, capturedAtMs, UNKNOWN, 1);
    }

    /**
     * A frame that carries no statement at all.
     */
    public static FrameProvenance unknown() {
        return new FrameProvenance(Origin.Unknown, UNKNOWN, UNKNOWN, UNKNOWN, -1);
    }

    /**
     * Whether this frame may be used to align a part on. See {@link Origin}.
     */
    public boolean isStill() {
        switch (origin) {
            case Settled:
                // The settling is the proof of stillness. The window, when it is known, must not
                // contradict it: a settled frame is by construction the newest of the agreeing ones.
                return stillWindowStartAtMs == UNKNOWN || capturedAtMs >= stillWindowStartAtMs;
            case Streamed:
                // Nothing settles on this camera, so only the window can say anything.
                return stillWindowStartAtMs != UNKNOWN && capturedAtMs >= stillWindowStartAtMs;
            default:
                return false;
        }
    }

    public Origin getOrigin() {
        return origin;
    }

    public long getArrivalAtMs() {
        return arrivalAtMs;
    }

    public long getCapturedAtMs() {
        return capturedAtMs;
    }

    public long getStillWindowStartAtMs() {
        return stillWindowStartAtMs;
    }

    /**
     * 1-based count of the frames this camera captured since the arrival, this frame included, or -1
     * when nobody counted.
     */
    public int getFrameIndex() {
        return frameIndex;
    }

    /**
     * How long after the head was reported at stillstand this frame was captured, or NaN.
     */
    public double getAgeAfterArrivalMs() {
        if (arrivalAtMs == UNKNOWN || capturedAtMs == UNKNOWN) {
            return Double.NaN;
        }
        return capturedAtMs - arrivalAtMs;
    }

    /**
     * How far inside (positive) or outside (negative) the still window this frame is, or NaN when
     * either is unknown.
     */
    public double getWindowMarginMs() {
        if (stillWindowStartAtMs == UNKNOWN || capturedAtMs == UNKNOWN) {
            return Double.NaN;
        }
        return capturedAtMs - stillWindowStartAtMs;
    }

    /**
     * Short form for log lines: what it is, how old it is, and where it sits relative to the window.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(origin.name().toLowerCase());
        if (frameIndex > 0) {
            sb.append(" f").append(frameIndex);
        }
        double age = getAgeAfterArrivalMs();
        if (!Double.isNaN(age)) {
            sb.append(String.format(" +%.1fms", age));
        }
        double margin = getWindowMarginMs();
        if (!Double.isNaN(margin)) {
            sb.append(String.format(" window%+.1fms", margin));
        }
        return sb.toString();
    }
}
