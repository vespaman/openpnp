import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.FrameBuffer;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.spi.BufferedFrames;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.NanosecondTime;
import org.openpnp.util.OpenCvUtils;

import com.google.common.io.Files;

/**
 * Frames that were taken before anybody asked for them, and the two places that then read them: the
 * settling, and the second camera's hunt for a frame inside that settling's window.
 *
 * A camera that grabs ahead changes what a frame knows about itself. Asked for a frame, an ordinary
 * camera exposes it, waits, and returns it; the best timestamp anyone has is the moment the wait ended,
 * which is later than the exposure by however long the wait happened to be. A camera with a ring of
 * frames already taken hands over one that was exposed a frame ago and says when - and that turns the
 * still-window argument from "this frame came back after the window opened" into "this frame was looking
 * at the head after the window opened", which is the claim the alignment actually needs.
 *
 * So these tests are not about speed, which a rendered frame cannot show. They are about three things:
 * the recorded times being the producing thread's instants and not the consuming thread's; the frame
 * picked for the window being the one nearest the window's opening rather than the one nearest the
 * asking; and nothing at all leaving the ring from before the head arrived, however convenient the stale
 * frame makes the wait.
 */
public class GrabAheadCaptureTest {

    /**
     * A camera that takes its frames on a thread of its own, at a fixed cadence, into a ring, and that,
     * when asked for one, hands over a frame from the ring instead of making one.
     *
     * The frames behave like a head that arrives and then stands still: the first
     * {@code loudCaptures} of them are shifted bodily, which is motion nothing may be called still by,
     * and after them they wobble by a single grey level, which is what mechanics at rest look like to a
     * camera. The same rendering serves whether the frame comes out of the ring or, if the ring has
     * nothing to give, out of a capture on demand - so that a test comparing the two ways of being served
     * is comparing the ring and not the pictures.
     */
    public static class RingCamera extends SimulatedUpCamera implements BufferedFrames {
        /**
         * A colour the renderer never produces, painted into the corner of every frame the ring hands to
         * somebody who did not wait for it. See {@link #handOut(BufferedImage)}.
         */
        static final int HAND_OUT_MARK = 0xFF00FE01;

        private final FrameBuffer ring = new FrameBuffer("ring-test", 64, this::handOut);

        private volatile boolean producing;

        private volatile long intervalMs = 8;

        private volatile int loudCaptures = 4;

        /**
         * When each frame the producing thread took was taken, in order. The tests read this to check
         * that the instants the camera quotes are these and not the instants at which somebody else
         * happened to be waiting.
         */
        private final List<Long> producedAtMs = Collections.synchronizedList(new ArrayList<Long>());

        private volatile int demandCaptures = 0;

        private volatile int ringCaptures = 0;

        /**
         * Frames taken out of the ring, whether by an ordinary capture or by the hunt asking for the
         * earliest one inside a window. The two are counted together because the point is simply that no
         * exposure was made.
         */
        private volatile int ringReads = 0;

        private volatile BufferedImage base;

        /**
         * How many frames the ring has handed out through {@link #handOut(BufferedImage)}, i.e. how many
         * times somebody picked a frame out of the past instead of waiting for one.
         */
        private volatile int handOuts = 0;

        public int getHandOuts() {
            return handOuts;
        }

        /**
         * The stand-in for the camera's transform chain, which the machine passes to its ring for exactly
         * this reason: a frame picked out of the past has to arrive as the picture {@code capture()}
         * would have made of it, because the pipeline searches where the rotation and the flip put the
         * part. On pn52 the hunt was handed Upp1's frames as the sensor made them - unrotated - and the
         * answer for a part that was plainly in the picture was "no rectangle in the pipeline result".
         *
         * Here the transform is a copy with a colour in the corner that nothing else paints, so that a
         * test can tell the two kinds of frame apart.
         */
        private BufferedImage handOut(BufferedImage image) {
            BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            copy.setRGB(0, 0, HAND_OUT_MARK);
            handOuts++;
            return copy;
        }

        public int getDemandCaptures() {
            return demandCaptures;
        }

        public int getRingCaptures() {
            return ringCaptures;
        }

        public int getRingReads() {
            return ringReads;
        }

        public List<Long> getProducedAtMs() {
            synchronized (producedAtMs) {
                return new ArrayList<>(producedAtMs);
            }
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        /**
         * How the frames are arranged: motion for the first so many of them, tremble afterwards. Set it
         * before the frames start being taken, because the producing thread numbers its own.
         */
        public void setLoudCaptures(int loudCaptures) {
            this.loudCaptures = loudCaptures;
        }

        public void startProducing() {
            renderBase();
            beginFrameBufferSession("test", 60000);
        }

        public void stopProducing() {
            endFrameBufferSession("test");
        }

        private synchronized BufferedImage renderBase() {
            if (base == null) {
                try {
                    base = super.capture();
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return base;
        }

        /**
         * The frame the device would have delivered at this point in the ringing.
         */
        private BufferedImage renderDeviceFrame(int n) {
            BufferedImage image = renderBase();
            if (n <= loudCaptures) {
                return shift(image, (n%2 == 0 ? 8 : -8));
            }
            if (n%2 == 0) {
                return new RescaleOp(1.0f, 1.0f, null).filter(image, null);
            }
            return image;
        }

        private static BufferedImage shift(BufferedImage image, int pixels) {
            BufferedImage shifted = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = shifted.createGraphics();
            g.drawImage(image, pixels, pixels/2, null);
            g.dispose();
            return shifted;
        }

        @Override
        public BufferedImage capture() throws Exception {
            long now = NanosecondTime.getRuntimeMilliseconds();
            // The same bargain the machine makes: the newest frame in the ring if it is one this caller can
            // use, and only if not, the cost of making one. What the caller can use is its own bound - the
            // instant the head arrived, say - plus the staleness a capture on demand always had, which is
            // one frame interval. Asking instead for content from after the instant of the call leaves the
            // ring unable to answer at all, since a held frame is stamped behind its own return by that
            // much; see GrabAheadLatencyRig.
            FrameBuffer.Frame frame = ring.awaitNewestForCapture(bufferedFrameNotBeforeMs, intervalMs,
                    now + 3*intervalMs + 25);
            if (frame != null) {
                setRawFrameCapturedAtMs(frame.getCapturedAtMs());
                ringCaptures++;
                ringReads++;
                return frame.image;
            }
            BufferedImage image = renderDeviceFrame(frameNumber++);
            setRawFrameCapturedAtMs(FrameProvenance.UNKNOWN);
            demandCaptures++;
            return image;
        }

        @Override
        public boolean isFrameBuffering() {
            return ring.isSessionActive();
        }

        @Override
        public Frame awaitEarliestBufferedFrameAtOrAfter(long notBeforeMs, long deadlineMs)
                throws InterruptedException {
            FrameBuffer.Frame frame = ring.awaitEarliestAtOrAfter(notBeforeMs, deadlineMs);
            if (frame != null) {
                ringReads++;
            }
            return frame;
        }

        @Override
        public long getBufferedFrameIntervalMs() {
            return ring.getFrameIntervalMs();
        }

        @Override
        public void beginFrameBufferSession(String reason, long maxMs) {
            // Exactly the arrangement the machine uses: the buffer keeps its own thread and asks this
            // source for a frame whenever the device has one, which here is once an interval.
            ring.startSession(new FrameBuffer.Source() {
                @Override
                public boolean hasNewFrame() {
                    return NanosecondTime.getRuntimeMilliseconds()-lastProducedAtMs >= intervalMs;
                }

                @Override
                public BufferedImage grab() {
                    lastFrameAtMs = NanosecondTime.getRuntimeMilliseconds();
                    lastProducedAtMs = lastFrameAtMs;
                    int n = frameNumber++;
                    producedAtMs.add(lastFrameAtMs);
                    return renderDeviceFrame(n);
                }

                @Override
                public long getCapturedAtMs() {
                    return lastFrameAtMs;
                }
            }, reason, maxMs);
        }

        @Override
        public void endFrameBufferSession(String reason) {
            ring.endSession(reason);
        }

        /**
         * What the current caller demands of a frame's content, see
         * {@link BufferedFrames#setBufferedFrameNotBeforeMs(long)}.
         */
        private volatile long bufferedFrameNotBeforeMs = 0;

        @Override
        public void setBufferedFrameNotBeforeMs(long notBeforeMs) {
            bufferedFrameNotBeforeMs = notBeforeMs;
        }

        private volatile long lastProducedAtMs = 0;

        private volatile long lastFrameAtMs = 0;

        private volatile int frameNumber = 0;
    }

    private Machine machine;
    private Nozzle nozzle;
    private SettleWindowCaptureTest.JitteryCamera settlingCamera;
    private RingCamera otherCamera;
    private Part part;
    private ReferenceBottomVision bottomVision;
    private BottomVisionSettings visionSettings;
    private Location wantedLocation;
    private long arrivalAtMs;

    private void setUp(boolean otherCameraGrabbingAhead) throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();

        machine = Configuration.get().getMachine();
        nozzle = machine.getDefaultHead().getDefaultNozzle();
        part = Configuration.get().getPart("R0805-1K");
        bottomVision = ReferenceBottomVision.getDefault();
        visionSettings = bottomVision.getInheritedVisionSettings(part);

        SampleJobTest.makeMachineFastest();

        machine.setEnabled(true);
        machine.home();

        settlingCamera = new SettleWindowCaptureTest.JitteryCamera();
        settlingCamera.setName("Upp1-settling");
        machine.addCamera(settlingCamera);
        otherCamera = new RingCamera();
        otherCamera.setName("Upp2-ring");
        machine.addCamera(otherCamera);

        for (AbstractSettlingCamera camera : new AbstractSettlingCamera[] {settlingCamera, otherCamera}) {
            // A settling that is a measurement and not a wait: two of its frames have to be alike before
            // anything may be called still.
            camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.Maximum);
            camera.setSettleThreshold(1.0);
            camera.setSettleDebounce(1);
            camera.setSettleContrastEnhance(0.0);
            camera.setSettleGaussianBlur(0);
            camera.setSettleGradients(false);
            camera.setSettleTimeoutMs(3000);
        }

        visionSettings.setPreCheckAction(ReferenceBottomVision.PreCheckAction.Disabled);

        machine.execute(() -> {
            nozzle.pick(part, null);
            return true;
        });
        wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, settlingCamera, nozzle, 0.,
                false);
        machine.execute(() -> {
            MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation);
            return true;
        });
        if (otherCameraGrabbingAhead) {
            otherCamera.startProducing();
            // Frames of where the head was on its way, taken before it arrived: these exist and the hunt
            // must still refuse them.
            Thread.sleep(4*otherCamera.getBufferedFrameIntervalMs()+50);
        }
        arrivalAtMs = NanosecondTime.getRuntimeMilliseconds();
    }

    /**
     * The head comes to rest in front of the settling camera: four frames of motion and then tremble,
     * which is what the settling has to sit through before it may call anything still.
     */
    private void settle() throws Exception {
        settlingCamera.setLoudCaptures(4);
        machine.execute(() -> {
            settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
            return true;
        });
        assert settlingCamera.getRecordedSettleResult() == AbstractSettlingCamera.SettleResult.Agreed
                : "the generated frames did not produce a settling that agreed: "
                        + settlingCamera.getRecordedSettleResult();
    }

    /**
     * What the job processor does on its background thread while the main thread waits for the settling:
     * take the first frame of the other camera that is demonstrably inside that settling's window.
     */
    private PreCapturedPlacement captureFromWindow(long settleEpochBefore, long deadlineAtMs)
            throws Exception {
        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            captured.set(bottomVision.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                    wantedLocation, arrivalAtMs, settlingCamera, settleEpochBefore,
                    deadlineAtMs));
            return true;
        });
        return captured.get();
    }

    /**
     * A settling served from a ring says when its frames were looked at, and says the producing thread's
     * instant rather than the instant its own waiting happened to end. That is the whole reason a ring is
     * worth anything to provenance: a window whose opening is a looking time and not a getting-round-to-it
     * time is a window another camera can be placed inside honestly.
     */
    @Test
    public void testSettlingQuotesTheInstantItsFramesWereTaken() throws Exception {
        setUp(false);

        RingCamera ringing = new RingCamera();
        ringing.setName("Upp3-ring-settling");
        machine.addCamera(ringing);
        ringing.setSettleMethod(AbstractSettlingCamera.SettleMethod.Maximum);
        ringing.setSettleThreshold(1.0);
        ringing.setSettleDebounce(1);
        ringing.setSettleContrastEnhance(0.0);
        ringing.setSettleGaussianBlur(0);
        ringing.setSettleGradients(false);
        ringing.setSettleTimeoutMs(3000);
        ringing.setLoudCaptures(4);
        ringing.startProducing();
        try {
            machine.execute(() -> {
                ringing.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
                return true;
            });

            assert ringing.getRecordedSettleResult() == AbstractSettlingCamera.SettleResult.Agreed
                    : "the ringing frames did not come to rest: "
                            + ringing.getRecordedSettleResult();
            assert ringing.getRingCaptures() > 1 : "the settling was not served from the ring at all";
            assert ringing.getDemandCaptures() == 0 : "it made " + ringing.getDemandCaptures()
                    + " frames of its own as well, so it was not served by the ring";
            assert ringing.getHandOuts() == 0 : "the settling was handed " + ringing.getHandOuts()
                    + " transformed frames. Its capture is a raw one and the camera's transform chain "
                            + "runs above it; transforming inside the ring would apply them twice";

            List<Long> produced = ringing.getProducedAtMs();
            long windowStartAtMs = ringing.getRecordedStillWindowStartAtMs();
            assert windowStartAtMs != FrameProvenance.UNKNOWN : "an agreed settling recorded no window";
            assert produced.contains(windowStartAtMs)
                    : "the window is said to open at " + windowStartAtMs + ", which is not an instant at "
                            + "which the producing thread took a frame: " + produced;
            assert ringing.getRecordedFirstCaptureAtMs() == produced.get(0)
                    || produced.contains(ringing.getRecordedFirstCaptureAtMs())
                    : "the first frame is quoted as " + ringing.getRecordedFirstCaptureAtMs()
                            + ", which the producing thread never took";
            System.out.println("[TEST] settling timed by the producing thread: window at "
                    + windowStartAtMs + " of " + produced.size() + " frames, "
                    + ringing.getRecordedFrameCount() + " frames compared, "
                    + ringing.getRingCaptures() + " served from the ring");
        }
        finally {
            ringing.stopProducing();
        }
    }

    /**
     * The frame the second camera keeps is the one nearest the opening of the still window, not the one
     * nearest the moment it felt like asking, and it costs no exposure. On pn52 the frame that was wanted
     * landed 27 ms after the window opened, because a capture had to be started and then stood by while
     * the exposure and the verdict ran; out of a ring there is nothing to stand by for.
     */
    @Test
    public void testHuntPicksTheFrameNearestTheWindowOpening() throws Exception {
        setUp(true);

        long settleEpochBefore = settlingCamera.getSettleEpoch();
        settle();
        long windowStartAtMs = settlingCamera.getRecordedStillWindowStartAtMs();
        assert windowStartAtMs != FrameProvenance.UNKNOWN : "an agreed settling recorded no window";

        PreCapturedPlacement shot = captureFromWindow(settleEpochBefore,
                NanosecondTime.getRuntimeMilliseconds()+1000);
        FrameProvenance provenance = shot.getProvenance();
        otherCamera.stopProducing();

        assert shot.getImage() != null : "no frame was handed over at all";
        assert provenance.getOrigin() == FrameProvenance.Origin.Streamed : provenance;
        assert otherCamera.getDemandCaptures() == 0
                : "the ring was there and " + otherCamera.getDemandCaptures()
                        + " frames were exposed anyway";
        assert otherCamera.getRingReads() >= 1 : "not one frame came out of the ring";
        assert provenance.getCapturedAtMs() >= arrivalAtMs
                : "a frame from before the head arrived was handed over: " + provenance;
        assert provenance.isStill() : "a frame inside the window is not called still: " + provenance;
        assert provenance.getStillWindowStartAtMs() == windowStartAtMs
                : "the frame quotes a window that is not the settling's: " + provenance;

        // Nearest the opening: of all the frames the producing thread took, none may lie between the
        // opening of the window and the frame that was kept, because such a frame would have been nearer.
        for (Long atMs : otherCamera.getProducedAtMs()) {
            assert !(atMs >= windowStartAtMs && atMs < provenance.getCapturedAtMs())
                    : "a frame taken at " + atMs + " was inside the window and nearer its opening than "
                            + "the frame kept at " + provenance.getCapturedAtMs();
        }
        System.out.println("[TEST] hunt took a buffered frame " + provenance + " after "
                + otherCamera.getRingReads() + " ring reads and " + otherCamera.getDemandCaptures()
                + " exposures");
    }

    /**
     * A frame picked out of the past arrives as the picture the camera would have made of it, not as the
     * sensor made it.
     *
     * This is the one thing about a ring that cannot be got wrong quietly. A camera that rotates and
     * flips - Upp1 on pn52 is rotated -90 and flipped in x - puts the part somewhere else in the frame
     * depending on whether that has been done, and the pipeline's search window is where the transformed
     * picture says it should be. Handed the untransformed frame, the pipeline finds nothing and says so,
     * and what it says is not "the frame was raw" but "no rectangle in the pipeline result", which reads
     * as a missing part and makes the parallel path fall back to sequential for the whole job. Measured
     * that way on pn52: every Upp1 pre-check of every batch failed on a frame in which the part is
     * plainly visible.
     */
    @Test
    public void testFramePickedOutOfThePastIsThePictureTheCameraWouldHaveMade() throws Exception {
        setUp(true);

        long settleEpochBefore = settlingCamera.getSettleEpoch();
        settle();

        PreCapturedPlacement shot = captureFromWindow(settleEpochBefore,
                NanosecondTime.getRuntimeMilliseconds()+1000);
        otherCamera.stopProducing();

        assert otherCamera.getHandOuts() >= 1 : "the hunt was served from the ring without the ring ever "
                + "transforming a frame for it; it was served " + otherCamera.getDemandCaptures()
                + " exposed frames instead, or the transform is not wired in at all";
        BufferedImage handed = OpenCvUtils.toBufferedImage(shot.getImage());
        assert handed.getRGB(0, 0) == RingCamera.HAND_OUT_MARK : "the frame the hunt was given is not one the camera "
                + "transformed on the way out: corner is "
                + Integer.toHexString(handed.getRGB(0, 0)) + ", expected "
                + Integer.toHexString(RingCamera.HAND_OUT_MARK);
        System.out.println("[TEST] hunt was handed a transformed frame after "
                + otherCamera.getHandOuts() + " hand-outs");
    }

    /**
     * The ring holds frames of the head while it was still on its way, and those frames are worthless
     * however handy they are: a pre-check that judged one would report a part that is not there, and an
     * alignment that used one would correct the head onto something it is not looking at. Bound the ask by
     * the arrival and they cannot leave the ring.
     */
    @Test
    public void testFramesTakenBeforeTheHeadArrivedStayInTheRing() throws Exception {
        setUp(true);
        // Make sure of it: frames taken before the arrival have to be in there for this to refuse
        // anything at all.
        Thread.sleep(60);
        boolean anyBeforeArrival = false;
        for (Long atMs : otherCamera.getProducedAtMs()) {
            if (atMs < arrivalAtMs) {
                anyBeforeArrival = true;
                break;
            }
        }
        assert anyBeforeArrival : "the ring holds no stale frame, so this would prove nothing";

        long settleEpochBefore = settlingCamera.getSettleEpoch();
        settle();
        PreCapturedPlacement shot = captureFromWindow(settleEpochBefore,
                NanosecondTime.getRuntimeMilliseconds()+1000);
        otherCamera.stopProducing();

        assert shot.getProvenance().getCapturedAtMs() >= arrivalAtMs
                : "a frame taken while the head was still travelling went to the alignment: "
                        + shot.getProvenance();
        System.out.println("[TEST] stale frames stayed behind, kept " + shot.getProvenance());
    }

    /**
     * A camera that does not grab ahead must lose nothing by not doing it. With no session there is
     * nothing in the ring, the asks come back empty at once instead of after a deadline, and the frames
     * are exposed as they always were.
     */
    @Test
    public void testCameraThatDoesNotGrabAheadIsNoWorseOff() throws Exception {
        setUp(false);
        assert !otherCamera.isFrameBuffering();

        long t0 = NanosecondTime.getRuntimeMilliseconds();
        long settleEpochBefore = settlingCamera.getSettleEpoch();
        settle();
        PreCapturedPlacement shot = captureFromWindow(settleEpochBefore, t0+3000);
        FrameProvenance provenance = shot.getProvenance();

        assert shot.getImage() != null : "no frame at all";
        assert otherCamera.getDemandCaptures() >= 1 : "not one frame was exposed, yet one was wanted";
        assert otherCamera.getRingReads() == 0;
        assert provenance.getOrigin() == FrameProvenance.Origin.Streamed : provenance;
        assert NanosecondTime.getRuntimeMilliseconds() - t0 < 2900 : "it waited for a ring that was "
                + "never going to be filled";
        System.out.println("[TEST] no ring, captured on demand as before: " + provenance);
    }
}
