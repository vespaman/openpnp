package org.openpnp.model;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The verdict of a bottom vision pre-check, i.e. of the analysis of the first frame the camera
 * delivered while the mechanics were still settling.
 *
 * The object is created by the thread that captures, filled in by a thread of the vision executor
 * and read by the job processor. Nothing ever waits for it: a pre-check that is waited for is worth
 * nothing, because its whole value is being early. Everything is therefore volatile, and
 * {@link Verdict#Pending} is an answer the reader has to be able to live with.
 *
 * The three durations are deliberately three and never one sum. Capture, settle and check are
 * affected by different things and optimising a sum of them is meaningless.
 */
public class PreCheckResult {

    public enum Verdict {
        /**
         * The check has not come round yet. Not a statement about the part.
         */
        Pending,
        Ok,
        /**
         * A frame in which no part could be found at all.
         */
        PartMissing,
        /**
         * A part was found but its measured size is outside the tolerance.
         */
        SizeOut,
        /**
         * The pipeline ran but has nothing to say, e.g. it found something of the wrong type.
         */
        Inconclusive,
        /**
         * The check itself could not be done. Always fails open: a check that is broken must never
         * stop a job that would otherwise have run.
         */
        Failed;

        public boolean isVerdict() {
            return this != Pending;
        }
    }

    private volatile Verdict verdict = Verdict.Pending;
    private volatile String message = "";

    private volatile double measuredWidth = -1.0;
    private volatile double measuredHeight = -1.0;
    private volatile double nominalWidth = -1.0;
    private volatile double nominalHeight = -1.0;
    private volatile double rotation = 0.0;
    /**
     * The distance from the centre of the frame to the centre of the part, in mm. Recorded and
     * logged, deliberately never judged: the first frame is taken while the mechanics are still
     * settling, so how far the part sits from where it will end up is not yet known - measuring
     * that on a real machine is what has to happen before any tolerance on it can be invented.
     */
    private volatile double offsets = -1.0;

    private volatile double captureMs = -1.0;
    private volatile double settleMs = -1.0;
    private volatile double checkMs = -1.0;

    /**
     * Whether the one log line of this check has been written. Whoever wins this writes it, which
     * is how one line per shot is guaranteed without anybody waiting: if the check has finished by
     * the time the settling has, the capture thread writes the line with all the numbers in it, and
     * if it has not, the check writes it itself, with the settling duration it could not know yet.
     */
    private final AtomicBoolean lineWritten = new AtomicBoolean(false);

    /**
     * The time from the start of the settling to the first frame being in hand. Set by the capture
     * thread, which is the only one that can know it.
     */
    /**
     * @return true if the caller has the right to write the log line, i.e. nobody else has.
     */
    public boolean claimLogLine() {
        return lineWritten.compareAndSet(false, true);
    }

    public boolean isLogLineWritten() {
        return lineWritten.get();
    }

    public void setCaptureMs(double captureMs) {
        this.captureMs = captureMs;
    }

    /**
     * The time the settling took after that first frame, i.e. what the pre-check bought.
     */
    public void setSettleMs(double settleMs) {
        this.settleMs = settleMs;
    }

    public void setCheckMs(double checkMs) {
        this.checkMs = checkMs;
    }

    public void setMeasuredSize(double width, double height) {
        this.measuredWidth = width;
        this.measuredHeight = height;
    }

    public void setNominalSize(double width, double height) {
        this.nominalWidth = width;
        this.nominalHeight = height;
    }

    public void setRotation(double rotation) {
        this.rotation = rotation;
    }

    public void setOffsets(double offsets) {
        this.offsets = offsets;
    }

    /**
     * Set the verdict. Called once, by whoever ran the check.
     */
    public void finish(Verdict verdict, String message) {
        this.message = message == null ? "" : message;
        this.verdict = verdict;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getMessage() {
        return message;
    }

    public double getMeasuredWidth() {
        return measuredWidth;
    }

    public double getMeasuredHeight() {
        return measuredHeight;
    }

    public double getNominalWidth() {
        return nominalWidth;
    }

    public double getNominalHeight() {
        return nominalHeight;
    }

    public double getRotation() {
        return rotation;
    }

    public double getOffsets() {
        return offsets;
    }

    public double getCaptureMs() {
        return captureMs;
    }

    public double getSettleMs() {
        return settleMs;
    }

    public double getCheckMs() {
        return checkMs;
    }

    @Override
    public String toString() {
        return "PreCheckResult{" +
                "verdict=" + verdict +
                ", measured=" + measuredWidth + "x" + measuredHeight +
                ", nominal=" + nominalWidth + "x" + nominalHeight +
                ", rot=" + rotation +
                ", offset=" + offsets +
                ", capture=" + captureMs + " ms" +
                ", settle=" + settleMs + " ms" +
                ", check=" + checkMs + " ms" +
                (message.isEmpty() ? "" : ", message=" + message) +
                '}';
    }
}
