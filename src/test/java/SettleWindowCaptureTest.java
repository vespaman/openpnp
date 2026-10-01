import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.model.PreCheckResult;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.NanosecondTime;

import com.google.common.io.Files;

/**
 * The frame of the camera that does not drive the settling, taken inside the still window of the one
 * that does.
 *
 * The head is one piece of metal, so the stillness that one camera measures is the stillness the other
 * one stands in - but only if the second camera's frame was taken inside the window the first one
 * measured, and only if the window it was placed inside is the window of this batch. Those two things
 * are all this file tests, and it tests them off the machine, with frames that are made rather than
 * photographed.
 *
 * The simulated camera is a problem of its own: it renders the same pixels twice in a row to the last
 * bit, so a settling that compares two of its frames sees a difference of exactly nothing, which is
 * not "the same" but "no measurement at all" - and so it waits until its timeout. {@link JitteryCamera}
 * supplies what a real camera has and a simulated one has not, namely motion that stops: the first
 * handful of frames are shifted bodily, which is motion, and after them the frames wobble by a single
 * grey level, which is the noise of mechanics that have come to rest. The settling therefore agrees
 * for the same reason it agrees on the machine, and the window it records is the window the tests place
 * frames inside of.
 */
public class SettleWindowCaptureTest {

    /**
     * What one frame costs on the shared bus of the two cameras, in milliseconds. On the machine the
     * frame period of the settling camera grew from 12.3 ms to 16.3-16.6 ms under 4-7 concurrent grabs,
     * i.e. something like 1 ms per grab per frame; a frame's whole transfer is rounded up here so that
     * the test is not chasing a rounding error.
     */
    private static final long BUS_COST_MS = 5;

    /**
     * The bus the two cameras of the contention test share. Any object will do; a dedicated one says
     * what it is.
     */
    private static final Object BUS = new Object();

    /**
     * A camera whose image moves for the first few frames and afterwards only trembles.
     */
    public static class JitteryCamera extends SimulatedUpCamera {
        private volatile int captureCount = 0;
        private volatile int loudCaptures = 0;

        public void setLoudCaptures(int loudCaptures) {
            this.captureCount = 0;
            this.loudCaptures = loudCaptures;
        }

        public int getCaptureCount() {
            return captureCount;
        }

        @Override
        public BufferedImage capture() throws Exception {
            BufferedImage image = super.capture();
            int n = ++captureCount;
            if (n <= loudCaptures) {
                image = shift(image, (n%2 == 0 ? 8 : -8));
            }
            else if(n%2 == 0) {
                // One grey level, which is what a camera looks at when nothing moves: small enough to
                // be under the threshold, non-zero so that "alike" is a measurement and not an absence.
                image = new RescaleOp(1.0f, 1.0f, null).filter(image, null);
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
    }

    /**
     * A camera that counts the frames taken out of it, so that a test can say which frame of a ringing
     * head something was looked at.
     *
     * It can also say how many of those frames were taken while there was nothing to aim at, i.e. while
     * the settling it watches had not even a prospect of a still window. That is the budget the camera
     * which hunts for a window is held to: frames cost the settling, because on a machine two cameras
     * share the bus, the CPU and the light, and a frame taken while the head is visibly moving is a
     * frame that is going to be thrown away. Only the frame the pre-check judges the ringing on is
     * taken before there is anything to aim at.
     *
     * Give it a bus with {@link #useBus(Object, long)} and it behaves like a device instead of like a
     * function: one frame at a time, and each frame occupies the bus for the time a frame takes. That
     * is what makes the cost of grabbing next door visible to a test at all, see
     * {@link BusCamera}.
     */
    public static class CountingCamera extends SimulatedUpCamera {
        private volatile int captures = 0;
        private volatile int blindCaptures = 0;
        private volatile AbstractSettlingCamera watched;
        private volatile long watchedEpochBefore = 0;
        private volatile Object bus;
        private volatile long busCostMs = 0;
        private volatile long lastFrameAskedAtMs = FrameProvenance.UNKNOWN;
        private volatile long lastFrameReturnedAtMs = FrameProvenance.UNKNOWN;

        public int getCaptures() {
            return captures;
        }

        /**
         * How many frames were taken before the settling had anything a frame could be still by.
         */
        public int getBlindCaptures() {
            return blindCaptures;
        }

        /**
         * When the last frame of this camera was asked for. A frame is a picture of the interval that
         * begins there, not of the one that ends when the camera hands it over, so this - and not the
         * time it came back - is the instant that has to be inside a still window.
         */
        public long getLastFrameAskedAtMs() {
            return lastFrameAskedAtMs;
        }

        public long getLastFrameReturnedAtMs() {
            return lastFrameReturnedAtMs;
        }

        public void watchSettling(AbstractSettlingCamera settlingCamera, long epochBefore) {
            watched = settlingCamera;
            watchedEpochBefore = epochBefore;
        }

        public void useBus(Object bus, long costMs) {
            this.bus = bus;
            this.busCostMs = costMs;
        }

        /**
         * Whether the settling has said anything that a frame of this camera could be inside: a run of
         * alike frames under way, or a settling that has finished and has something to claim.
         */
        protected boolean hasProspectOfWindow() {
            AbstractSettlingCamera settlingCamera = watched;
            if (settlingCamera == null) {
                return true;
            }
            if (settlingCamera.getSettleEpoch() <= watchedEpochBefore) {
                return false;
            }
            AbstractSettlingCamera.SettleResult result = settlingCamera.getRecordedSettleResult();
            return settlingCamera.getCandidateStillWindowStartAtMs()
                        != org.openpnp.model.FrameProvenance.UNKNOWN
                    || result == AbstractSettlingCamera.SettleResult.Agreed
                    || result == AbstractSettlingCamera.SettleResult.FixedTime;
        }

        protected BufferedImage captureThroughBus() throws Exception {
            Object bus = this.bus;
            if (bus == null) {
                return super.capture();
            }
            synchronized(bus) {
                if (busCostMs > 0) {
                    Thread.sleep(busCostMs);
                }
                return super.capture();
            }
        }

        @Override
        public BufferedImage capture() throws Exception {
            boolean blind = !hasProspectOfWindow();
            long askedAtMs = NanosecondTime.getRuntimeMilliseconds();
            BufferedImage image = captureThroughBus();
            lastFrameAskedAtMs = askedAtMs;
            lastFrameReturnedAtMs = NanosecondTime.getRuntimeMilliseconds();
            captures++;
            if (blind) {
                blindCaptures++;
            }
            return image;
        }
    }

    /**
     * A settling camera on the same bus as the camera that hunts for its window: a frame of one cannot
     * be had while the other one is taking a frame.
     *
     * This is the part of the machine that a simulated camera leaves out by construction. Rendered
     * images cost nothing, so a test that only counts milliseconds measures nothing when a second
     * camera grabs away, while on the pn52 every frame taken next door cost the settling about 4 ms of
     * its own frame period (log_m without competing grabs: 12.3 ms between settling frames, log_n and
     * log_o with 4-7 of them: 16.3-16.6 ms). Modelled here as an exclusive bus with a fixed transfer
     * time, which is what a USB bus is, the test can hold the hunt to a budget instead of finding out
     * on the machine.
     */
    public static class BusCamera extends JitteryCamera {
        private volatile Object bus;
        private volatile long busCostMs = 0;

        public void useBus(Object bus, long costMs) {
            this.bus = bus;
            this.busCostMs = costMs;
        }

        @Override
        public BufferedImage capture() throws Exception {
            Object bus = this.bus;
            if (bus == null) {
                return super.capture();
            }
            synchronized(bus) {
                if (busCostMs > 0) {
                    Thread.sleep(busCostMs);
                }
                return super.capture();
            }
        }
    }

    /**
     * Bottom vision that remembers how many frames the camera had already given up when the pre-check
     * was handed its frame. This is the only honest way to test stage 1 away from the machine: the check
     * has to be asked for while the hunt is still hunting, and a wall clock can only say so by racing
     * with the very threads the test is trying to interleave.
     */
    public static class WatchingBottomVision extends ReferenceBottomVision {
        private volatile int grabsWhenCheckStarted = -1;

        public int getGrabsWhenCheckStarted() {
            return grabsWhenCheckStarted;
        }

        @Override
        protected void startPreCheck(org.opencv.core.Mat firstFrame, PreCheckResult preCheckResult,
                Part part, org.openpnp.spi.Camera camera, Nozzle nozzle, Location wantedLocation,
                BottomVisionSettings bottomVisionSettings, double captureMs) {
            if (camera instanceof CountingCamera) {
                grabsWhenCheckStarted = ((CountingCamera) camera).getCaptures();
            }
            super.startPreCheck(firstFrame, preCheckResult, part, camera, nozzle, wantedLocation,
                    bottomVisionSettings, captureMs);
        }
    }

    private Machine machine;
    private Nozzle nozzle;
    private BusCamera settlingCamera;
    private CountingCamera otherCamera;
    private Part part;
    private ReferenceBottomVision bottomVision;
    private BottomVisionSettings visionSettings;
    private Location wantedLocation;
    private long arrivalAtMs;

    private void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
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

        settlingCamera = new BusCamera();
        settlingCamera.setName("Upp1-settling");
        machine.addCamera(settlingCamera);
        otherCamera = new CountingCamera();
        otherCamera.setName("Upp2-other");
        machine.addCamera(otherCamera);

        // A settling that is a measurement and not a wait: two of its frames have to be alike before
        // anything may be called still. The threshold is of the order the field uses; the wobble in
        // JitteryCamera is what keeps it honest on a camera that cannot tremble by itself.
        settlingCamera.setSettleMethod(AbstractSettlingCamera.SettleMethod.Maximum);
        settlingCamera.setSettleThreshold(1.0);
        settlingCamera.setSettleDebounce(1);
        settlingCamera.setSettleContrastEnhance(0.0);
        settlingCamera.setSettleGaussianBlur(0);
        settlingCamera.setSettleGradients(false);
        settlingCamera.setSettleTimeoutMs(3000);

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
        arrivalAtMs = NanosecondTime.getRuntimeMilliseconds();
    }

    private void settle() throws Exception {
        machine.execute(() -> {
            settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
            return true;
        });
    }

    /**
     * What the job processor does on its background thread while the main thread waits for the
     * settling: take the first frame of the other camera that is demonstrably inside that settling's
     * window.
     */
    private PreCapturedPlacement captureFromWindow(long settleEpochBefore, long deadlineAtMs)
            throws Exception {
        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            captured.set(bottomVision.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                    wantedLocation, false, arrivalAtMs, settlingCamera, settleEpochBefore,
                    deadlineAtMs));
            return true;
        });
        return captured.get();
    }

    /**
     * The settling has ended and found the head still. A frame of the other camera now is inside that
     * stillness, and has to say so: same window, capture time after its start, and the first grab kept
     * because there was nothing to wait for.
     */
    @Test
    public void testFrameTakenInsideTheWindowOfTheOtherCamera() throws Exception {
        setUp();

        settlingCamera.setLoudCaptures(4);
        settle();
        assert settlingCamera.getRecordedSettleResult() == AbstractSettlingCamera.SettleResult.Agreed
                : "the generated frames did not produce a settling that agreed: "
                        + settlingCamera.getRecordedSettleResult();
        long windowStartAtMs = settlingCamera.getRecordedStillWindowStartAtMs();
        assert windowStartAtMs != FrameProvenance.UNKNOWN : "an agreed settling recorded no window";

        PreCapturedPlacement shot = captureFromWindow(settlingCamera.getSettleEpoch()-1,
                NanosecondTime.getRuntimeMilliseconds()+1000);
        FrameProvenance provenance = shot.getProvenance();

        assert shot.getImage() != null : "no frame was handed over at all";
        assert provenance.getOrigin() == FrameProvenance.Origin.Streamed : provenance;
        assert provenance.isStill() : "a frame taken after the window opened is not called still: "
                + provenance;
        assert provenance.getStillWindowStartAtMs() == windowStartAtMs
                : "the frame quotes a window that is not the settling's: " + provenance;
        assert provenance.getCapturedAtMs() >= windowStartAtMs
                : "the frame claims a moment before the head was still: " + provenance;
        assert provenance.getFrameIndex() == 1 : "the window had already opened, so the first frame of "
                + "the other camera should have been kept, not grab number "
                + provenance.getFrameIndex();
        System.out.println("[TEST] frame inside the window of the other camera: "+provenance);
    }

    /**
     * A window recorded by an earlier settling was a real stillness about a head that has since moved
     * away. The batch's own settling has not begun, which is what an epoch that has not moved means,
     * and then there is nothing to be inside of: the frame goes over without a claim and the alignment
     * will refuse it.
     */
    @Test
    public void testWindowOfAnEarlierSettlingIsNotBelieved() throws Exception {
        setUp();

        settlingCamera.setLoudCaptures(4);
        settle();
        assert settlingCamera.getRecordedStillWindowStartAtMs() != FrameProvenance.UNKNOWN
                : "expected a window on record, to be tempted by";

        long t0 = NanosecondTime.getRuntimeMilliseconds();
        PreCapturedPlacement shot = captureFromWindow(settlingCamera.getSettleEpoch(), t0+150);
        long waitedMs = NanosecondTime.getRuntimeMilliseconds()-t0;
        FrameProvenance provenance = shot.getProvenance();

        assert provenance.getStillWindowStartAtMs() == FrameProvenance.UNKNOWN
                : "the stillness of a previous placement was quoted as this one's: " + provenance;
        assert !provenance.isStill() : "a frame with no window of this batch is called still: "
                + provenance;
        assert waitedMs >= 120 : "it did not wait for a settling of its own at all, but handed over a "
                + "frame after "+waitedMs+" ms";
        assert waitedMs < 1500 : "it did not stop waiting: "+waitedMs+" ms";
        System.out.println("[TEST] stale window refused after "+waitedMs+" ms: "+provenance);
    }

    /**
     * A settling that ran out of time measured motion, and motion is the answer: no window will open,
     * so there is no sense in going on grabbing until a deadline. The frame is refused at once, and the
     * head leaves the cameras.
     */
    @Test
    public void testSettlingThatRanOutOfTimeOpensNoWindow() throws Exception {
        setUp();

        settlingCamera.setLoudCaptures(100000);
        settlingCamera.setSettleTimeoutMs(200);
        settle();
        assert settlingCamera.getRecordedSettleResult()
                == AbstractSettlingCamera.SettleResult.TimedOut
                : "the generated frames were supposed to keep moving: "
                        + settlingCamera.getRecordedSettleResult();

        long t0 = NanosecondTime.getRuntimeMilliseconds();
        PreCapturedPlacement shot = captureFromWindow(settlingCamera.getSettleEpoch()-1, t0+3000);
        long waitedMs = NanosecondTime.getRuntimeMilliseconds()-t0;
        FrameProvenance provenance = shot.getProvenance();

        assert !provenance.isStill() : "a settling that gave up cannot make anyone's frame still: "
                + provenance;
        assert waitedMs < 800 : "it went on grabbing for a window that could not open, "+waitedMs+" ms";
        System.out.println("[TEST] refused after a timed out settling in "+waitedMs+" ms: "+provenance);
    }

    /**
     * A settling that is a configured wait has measured nothing, and yet the frame it hands over is
     * accepted on that claim. The frame of the other camera at the same instant is entitled to exactly
     * the same claim, so the end of the wait is its window too - and if the wait is not trusted, neither
     * is this.
     */
    @Test
    public void testConfiguredWaitClaimsTheSameStillnessForBoth() throws Exception {
        setUp();

        settlingCamera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        settlingCamera.setSettleTimeMs(40);
        settle();
        assert settlingCamera.getRecordedSettleResult()
                == AbstractSettlingCamera.SettleResult.FixedTime : "the settling was not a wait: "
                        + settlingCamera.getRecordedSettleResult();
        assert settlingCamera.getRecordedStillWindowStartAtMs() == FrameProvenance.UNKNOWN
                : "a wait measured nothing, but a window was recorded anyway";

        PreCapturedPlacement shot = captureFromWindow(settlingCamera.getSettleEpoch()-1,
                NanosecondTime.getRuntimeMilliseconds()+1000);
        FrameProvenance provenance = shot.getProvenance();

        assert provenance.isStill() : "the camera that waited is believed and the camera beside it is "
                + "not: " + provenance;
        assert provenance.getStillWindowStartAtMs() == settlingCamera.getRecordedSettledFrameAtMs()
                : "the window of the other camera is not the end of the wait: " + provenance;
        System.out.println("[TEST] configured wait shared with the other camera: "+provenance);
    }

    /**
     * The pre-check is the work of the ringing window, not of the stillness: stage 1 is "while the
     * settle camera settles, the pre-check inspects the first frames". So the check of this camera has
     * to be started on its first grab and be through with it before the hunt for the window is, and
     * not sit behind the alignment waiting for a frame it could have measured fifty milliseconds
     * earlier.
     *
     * Which frame the check was asked for is what is asserted here, not when it finished: the number of
     * grabs the camera had served at that moment is a fact the code leaves behind, whereas the finishing
     * time of a check on three contending threads is only ever a race.
     *
     * The frame the check looked at and the frame the alignment gets are two different frames, and that
     * is the point: presence and size survive the ringing, position does not, and position is not the
     * check's to judge.
     */
    @Test
    public void testThePreCheckWorksOnTheFirstFrameWhileTheHuntGoesOn() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(ReferenceBottomVision.PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(ReferenceBottomVision.PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(2.0);
        part.getPackage().getFootprint().setBodyHeight(1.25);
        visionSettings.setCheckSizeTolerancePercent(50);

        WatchingBottomVision watching = new WatchingBottomVision();
        watching.setEnabled(true);
        // An instance of its own resolves its settings of its own, and would bring a stock set of them
        // to the party; the test has configured the machine's, so hand those over.
        watching.setBottomVisionSettings(visionSettings);


        settlingCamera.setLoudCaptures(12);
        final long settleEpochBefore = settlingCamera.getSettleEpoch();
        final AtomicReference<Exception> settlingFailure = new AtomicReference<>();
        Thread settlingThread = new Thread(() -> {
            try {
                settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
            }
            catch(Exception e) {
                settlingFailure.set(e);
            }
        }, "test-settling");

        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            settlingThread.start();
            try {
                captured.set(watching.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                        wantedLocation, false, arrivalAtMs, settlingCamera, settleEpochBefore,
                        NanosecondTime.getRuntimeMilliseconds()+5000));
            }
            finally {
                try {
                    settlingThread.join(10000);
                }
                catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        });

        assert settlingFailure.get() == null : "the settling thread failed: "
                + settlingFailure.get();
        PreCapturedPlacement shot = captured.get();
        assert shot.getProvenance().isStill() : "the alignment frame is not the one from the window: "
                + shot.getProvenance();

        assert shot.getProvenance().getFrameIndex() >= 2
                : "the hunt kept the frame it grabbed first, so nothing here tells a check run during "
                        +"the ringing from one run after it: "+shot.getProvenance();
        assert watching.getGrabsWhenCheckStarted() == 1
                : "the pre-check was handed its frame after grab "
                        +watching.getGrabsWhenCheckStarted()+" of "+shot.getProvenance().getFrameIndex()
                        +", and the ringing was over by then";

        PreCheckResult result = shot.getPreCheckResult();
        assert result != null : "the camera that hunts for a window got no pre-check at all";
        long until = NanosecondTime.getRuntimeMilliseconds()+5000;
        while (result.getVerdict() == PreCheckResult.Verdict.Pending
                && NanosecondTime.getRuntimeMilliseconds() < until) {
            Thread.sleep(5);
        }
        assert result.getVerdict() == PreCheckResult.Verdict.Ok : "verdict should be Ok, was "+result;
        assert result.getSettleMs() < 0.0 : "a camera that does not settle claims a settling: "+result;
        assert result.getFrameCount() == shot.getProvenance().getFrameIndex()
                : "the check says it ran on a shot of "+result.getFrameCount()+" frames, the hunt took "
                        +shot.getProvenance().getFrameIndex();
        System.out.println("[TEST] pre-check started on grab 1 of a hunt of "
                +shot.getProvenance().getFrameIndex()+": "+result);
    }

    /**
     * The shape of it in the machine: one thread settling, another thread grabbing, neither knowing
     * what the other has done. The grabber has to keep throwing frames away until the window exists,
     * keep the first one that is inside it, and neither return early nor hang.
     */
    @Test
    public void testTheGrabbingStopsWhenTheWindowOpens() throws Exception {
        setUp();

        // Frames that move for a while: the window cannot open before the settling has seen all of
        // them, so the grabber has to have thrown frames away before it was allowed to keep one.
        settlingCamera.setLoudCaptures(12);
        final long settleEpochBefore = settlingCamera.getSettleEpoch();

        final AtomicReference<Exception> settlingFailure = new AtomicReference<>();
        Thread settlingThread = new Thread(() -> {
            try {
                settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
            }
            catch(Exception e) {
                settlingFailure.set(e);
            }
        }, "test-settling");

        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            settlingThread.start();
            try {
                captured.set(bottomVision.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                        wantedLocation, false, arrivalAtMs, settlingCamera, settleEpochBefore,
                        NanosecondTime.getRuntimeMilliseconds()+5000));
            }
            finally {
                try {
                    settlingThread.join(10000);
                }
                catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        });

        assert settlingFailure.get() == null : "the settling thread failed: "
                + settlingFailure.get();
        assert settlingCamera.getSettleEpoch() > settleEpochBefore
                : "the settling of this batch never began, so nothing here means anything";

        FrameProvenance provenance = captured.get().getProvenance();
        assert provenance.isStill() : "the head stood still and the frame says otherwise: "
                + provenance;
        assert provenance.getStillWindowStartAtMs()
                == settlingCamera.getRecordedStillWindowStartAtMs() : provenance;
        assert provenance.getCapturedAtMs() >= provenance.getStillWindowStartAtMs()
                : "the frame is outside the window it claims: " + provenance;
        assert provenance.getFrameIndex() >= 2 : "twelve frames of motion had to be watched first, yet "
                + "the kept frame is grab number "+provenance.getFrameIndex();
        System.out.println("[TEST] grabbed into the window on another thread: "+provenance
                +" after "+settlingCamera.getCaptureCount()+" frames of settling");
    }

    /**
     * Whether there was a frame in flight when the settling said the window was open.
     *
     * The settling claims the opening of a window and announces it in very nearly the same breath: the
     * candidate is the first frame under the threshold and the agreement is that frame plus the debounce,
     * which on pn52 is one frame of about 12 ms apart (log_x). So a frame that is exposed only after the
     * verdict is a whole exposure younger than the window it was wanted in, and the head stands over the
     * cameras for that exposure. The hunt instead keeps exposing while it has nothing to go on and picks
     * the frame out afterwards, which buys the frame's length back - at the price of frames thrown away,
     * which is what the bus test below is for.
     *
     * The bus is on here because without one a capture costs nothing, and a frame started after the
     * verdict is then indistinguishable from one that was already on its way when the verdict came.
     */
    @Test
    public void testTheKeptFrameWasAlreadyInFlightWhenTheVerdictLanded() throws Exception {
        setUp();

        settlingCamera.useBus(BUS, BUS_COST_MS);
        otherCamera.useBus(BUS, BUS_COST_MS);

        // Frames that move for a while, so the window cannot open before the settling has seen them.
        settlingCamera.setLoudCaptures(12);
        final long settleEpochBefore = settlingCamera.getSettleEpoch();
        otherCamera.watchSettling(settlingCamera, settleEpochBefore);

        final AtomicReference<Exception> settlingFailure = new AtomicReference<>();
        final AtomicLong verdictAtMs = new AtomicLong(FrameProvenance.UNKNOWN);
        Thread settlingThread = new Thread(() -> {
            try {
                settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
                verdictAtMs.set(NanosecondTime.getRuntimeMilliseconds());
            }
            catch(Exception e) {
                settlingFailure.set(e);
            }
        }, "test-settling");

        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            settlingThread.start();
            try {
                captured.set(bottomVision.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                        wantedLocation, false, arrivalAtMs, settlingCamera, settleEpochBefore,
                        NanosecondTime.getRuntimeMilliseconds()+5000));
            }
            finally {
                try {
                    settlingThread.join(10000);
                }
                catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        });

        assert settlingFailure.get() == null : "the settling thread failed: "
                + settlingFailure.get();
        FrameProvenance provenance = captured.get().getProvenance();
        assert provenance.isStill() : "the frame says it is not the one from the window: "
                + provenance;
        long windowStartAtMs = provenance.getStillWindowStartAtMs();
        long askedAtMs = otherCamera.getLastFrameAskedAtMs();
        assert askedAtMs >= windowStartAtMs : "the frame kept was asked for "+(windowStartAtMs-askedAtMs)
                +" ms before the window opened, so it pictures the head still moving: "+provenance;
        // Either the frame was on its way before it was asked for - which is the saving - or the verdict
        // was already there when it was asked for, and then it had better come straight back.
        assert askedAtMs <= verdictAtMs.get()
                || provenance.getCapturedAtMs()-verdictAtMs.get() < BUS_COST_MS
                : "nothing was in flight when the settling announced the window: it was told at +"
                        +verdictAtMs.get()+" ms and the frame it accepted was not asked for until +"+askedAtMs
                        +" ms and came back at +"+provenance.getCapturedAtMs()+" ms, on a bus of "
                        +BUS_COST_MS+" ms a frame";
        // What the frames cost is the bus test's business; this one only stops a hunt that never runs
        // out of ideas, which would grab until the deadline with both cameras drowning.
        int grabs = otherCamera.getCaptures();
        assert grabs <= 4*settlingCamera.getCaptureCount()+8 : "took "+grabs+" frames while the settling "
                +("took "+settlingCamera.getCaptureCount()+", so the hunt is not being stopped by anything");
        System.out.println("[TEST] settling announced the window at +"+verdictAtMs.get()+" ms; the kept frame "
                +"was asked for at +"+askedAtMs+" ms and handed over at +"
                +provenance.getCapturedAtMs()+" ms, grab "+provenance.getFrameIndex()+" of "+grabs
                +" against "+settlingCamera.getCaptureCount()+" frames of settling");
    }

    /**
     * The hunt must not be what makes the settling slow.
     *
     * Measured on the machine: the settling's frames came every 12.3 ms while the other camera took one
     * quick frame (log_m) and every 16.3-16.6 ms while it took 4-7 of them (log_n, log_o), which turned
     * a settling of two to four frames from 40 ms into 66 ms. That is a bus, a CPU and a light shared
     * between two cameras, and a simulated camera that renders a frame out of thin air cannot show it.
     * So the cameras here are given a bus: one frame at a time, each frame occupying it for
     * {@link #BUS_COST_MS}, and the settling is timed with and without the hunt running against it.
     *
     * What the assertion allows is the hunt's own frames on the bus, and not much else: a hunt that
     * grabs at full rate would hold the bus for a dozen frames and blow this up by several times.
     */
    @Test
    public void testTheHuntDoesNotCostTheSettlingItsFrames() throws Exception {
        setUp();

        settlingCamera.useBus(BUS, BUS_COST_MS);
        otherCamera.useBus(BUS, BUS_COST_MS);

        // The same settling with nobody else on the bus but itself.
        settlingCamera.setLoudCaptures(12);
        settle();
        long aloneMs = settlingCamera.getRecordedSettleMilliseconds();
        assert aloneMs > 0 : "the settling was not timed";

        // And again with the other camera hunting for a frame inside it.
        settlingCamera.setLoudCaptures(12);
        otherCamera.watchSettling(settlingCamera, settlingCamera.getSettleEpoch());
        final long settleEpochBefore = settlingCamera.getSettleEpoch();
        final AtomicReference<Exception> settlingFailure = new AtomicReference<>();
        Thread settlingThread = new Thread(() -> {
            try {
                settlingCamera.settleAndCapture(AbstractSettlingCamera.SettleOption.Settle, null);
            }
            catch(Exception e) {
                settlingFailure.set(e);
            }
        }, "test-settling");
        final AtomicReference<PreCapturedPlacement> captured = new AtomicReference<>();
        machine.execute(() -> {
            settlingThread.start();
            try {
                captured.set(bottomVision.captureImageFromSettlingWindow(part, otherCamera, nozzle,
                        wantedLocation, false, arrivalAtMs, settlingCamera, settleEpochBefore,
                        NanosecondTime.getRuntimeMilliseconds()+5000));
            }
            finally {
                try {
                    settlingThread.join(10000);
                }
                catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        });

        assert settlingFailure.get() == null : "the settling thread failed: "
                + settlingFailure.get();
        assert captured.get().getProvenance().isStill() : "the frame is not the one from the window: "
                + captured.get().getProvenance();
        long withHuntMs = settlingCamera.getRecordedSettleMilliseconds();
        int grabs = otherCamera.getCaptures();
        // The frames the hunt had to take, plus one for the awkwardness of two threads meeting a lock.
        long allowed = (grabs+1)*BUS_COST_MS;
        assert withHuntMs-aloneMs <= allowed
                : "the settling took "+withHuntMs+" ms with the hunt and "+aloneMs+" ms without it, so "
                        +grabs+" grabs cost it "+(withHuntMs-aloneMs)+" ms on a bus of "
                        +BUS_COST_MS+" ms per frame";
        System.out.println("[TEST] settling "+aloneMs+" ms alone, "+withHuntMs+" ms against "
                +grabs+" grabs of another camera ("+BUS_COST_MS+" ms per frame on the bus)");
    }
}
