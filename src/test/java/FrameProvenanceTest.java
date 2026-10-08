import java.io.File;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera.SettleMethod;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera.SettleResult;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameNotStillException;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.NanosecondTime;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.FrameDump;

import com.google.common.io.Files;

/**
 * Where a frame came from, and what may therefore be done with it.
 *
 * The rule these tests hold the code to is a narrow one and it is the whole point of the parallel
 * vision design: a part may only be measured on a frame that can show the head stood still while the
 * camera integrated it. Everything else - the speed, the pre-check, the departure before the analysis
 * - is worth nothing if a part can be placed where a moving image said it was.
 *
 * So what is asserted here is not what a frame looks like but what may be claimed about it, and in
 * particular that a claim that cannot be made is refused rather than quietly accepted. Refusal is the
 * interesting case: the machine aligns the part the slow way and the job finishes either way, so
 * without these tests a hole in the rule would be invisible.
 */
public class FrameProvenanceTest {

    private Machine machine;
    private Nozzle nozzle;
    private SimulatedUpCamera camera;
    private Part part;
    private ReferenceBottomVision bottomVision;
    private BottomVisionSettings visionSettings;

    private void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();

        machine = Configuration.get().getMachine();
        nozzle = machine.getDefaultHead().getDefaultNozzle();
        camera = (SimulatedUpCamera) VisionUtils.getBottomVisionCamera(nozzle);
        part = Configuration.get().getPart("R0805-1K");
        bottomVision = ReferenceBottomVision.getDefault();
        visionSettings = bottomVision.getInheritedVisionSettings(part);

        SampleJobTest.makeMachineFastest();
        camera.setErrorOffsets(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));

        machine.setEnabled(true);
        machine.home();
    }

    /**
     * A settling that measures, on a camera that is perfectly still, never agrees: identical frames
     * differ by nothing at all, and "nothing changed" is not distinguishable from "the camera sent me
     * the same frame twice", so the loop runs until its timeout. That is the honest case for these
     * tests, and it is exactly the case a rule about stillness has to fail closed on.
     */
    private void settleByMeasuringUntilTimeout() {
        camera.setSettleMethod(SettleMethod.Maximum);
        camera.setSettleTimeoutMs(300);
        camera.setSettleDebounce(0);
    }

    /**
     * A settling that is a configured wait. Less is claimed - nothing was measured - but what is
     * claimed is true, and the frame is the one the settings asked for.
     */
    private void settleByWaiting() {
        camera.setSettleMethod(SettleMethod.FixedTime);
        camera.setSettleTimeMs(20);
    }

    private PreCapturedPlacement captureWithSettle(long arrivalAtMs) throws Exception {
        final PreCapturedPlacement[] captured = new PreCapturedPlacement[1];
        machine.execute(() -> {
            nozzle.pick(part, null);
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle,
                    0., false);
            MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation);
            captured[0] = bottomVision.captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation,
                    arrivalAtMs);
            return true;
        });
        return captured[0];
    }

    private PreCapturedPlacement captureWithoutSettle(long arrivalAtMs) throws Exception {
        final PreCapturedPlacement[] captured = new PreCapturedPlacement[1];
        machine.execute(() -> {
            nozzle.pick(part, null);
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle,
                    0., false);
            MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation);
            captured[0] = bottomVision.captureImageOnly(part, camera, nozzle, wantedLocation,
                    arrivalAtMs);
            return true;
        });
        return captured[0];
    }

    /**
     * Ask the alignment to measure the part on a given frame, and report what it said rather than
     * throwing, so that a test can tell a refusal apart from any of the other things that can go wrong.
     */
    private Throwable alignOn(PreCapturedPlacement shot) throws Exception {
        final Placement placement = new Placement("1");
        placement.setPart(part);
        try {
            PartAlignmentOffset offsets = bottomVision.findOffsets(part, null, placement, nozzle, true,
                    shot);
            System.out.println("[TEST] alignment accepted the frame, offsets "+offsets.getLocation());
            return null;
        }
        catch (Throwable t) {
            System.out.println("[TEST] alignment refused the frame: "+t);
            return t;
        }
    }

    @Test
    public void testAFrameCapturedWithoutSettlingIsNotStill() throws Exception {
        setUp();

        long arrivalAtMs = NanosecondTime.getRuntimeMilliseconds();
        PreCapturedPlacement shot = captureWithoutSettle(arrivalAtMs);

        assert shot != null && shot.getImage() != null : "nothing was captured";
        FrameProvenance provenance = shot.getProvenance();
        assert provenance != null : "the shot carries no provenance object at all";
        assert provenance.getOrigin() == FrameProvenance.Origin.QuickCapture
                : "a capture that settled nothing claims "+provenance.getOrigin();
        assert !provenance.isStill() : "a frame nobody measured the motion of calls itself still: "
                +provenance;
        assert !Double.isNaN(provenance.getAgeAfterArrivalMs()) : "the age of the frame after the "
                + "head arrived is not known, so the frame cannot be placed in time: "+provenance;
        assert provenance.getAgeAfterArrivalMs() >= 0.0
                : "the frame claims to have been captured before the head arrived: "+provenance;
        System.out.println("[TEST] the frame of a camera that does not settle: "+provenance);
    }

    @Test
    public void testTheAlignmentRefusesAFrameThatIsNotStill() throws Exception {
        setUp();

        PreCapturedPlacement shot = captureWithoutSettle(NanosecondTime.getRuntimeMilliseconds());
        Throwable refused = alignOn(shot);

        assert refused instanceof FrameNotStillException
                : "a frame that cannot show the head was still should not be aligned upon, but the "
                + "alignment said: "+refused;
        assert ((FrameNotStillException) refused).getProvenance() == shot.getProvenance()
                : "the refusal should name the frame it refused, it named "
                +((FrameNotStillException) refused).getProvenance();
    }

    @Test
    public void testTheAlignmentAcceptsTheFrameThatEndedTheSettling() throws Exception {
        setUp();
        settleByWaiting();

        long arrivalAtMs = NanosecondTime.getRuntimeMilliseconds();
        PreCapturedPlacement shot = captureWithSettle(arrivalAtMs);
        FrameProvenance provenance = shot.getProvenance();

        assert provenance.getOrigin() == FrameProvenance.Origin.Settled
                : "the frame that ended a settling is "+provenance.getOrigin();
        assert provenance.isStill() : "the frame a settling handed back is not still: "+provenance;
        assert provenance.getFrameIndex() >= 1
                : "the frame is not counted: "+provenance;

        Throwable refused = alignOn(shot);
        assert !(refused instanceof FrameNotStillException)
                : "a settled frame was refused: "+refused.getMessage();
    }

    /**
     * The frame a settling hands back when it gives up is the measurement answering "no, this has not
     * stopped". Waiting longer is not the same as measuring, so this frame is not still however many
     * milliseconds went by - and the alignment has to say so instead of aligning on it.
     */
    @Test
    public void testASettlingThatRanOutOfTimeHasProvenNothing() throws Exception {
        setUp();
        settleByMeasuringUntilTimeout();

        PreCapturedPlacement shot = captureWithSettle(NanosecondTime.getRuntimeMilliseconds());
        FrameProvenance provenance = shot.getProvenance();

        assert provenance.getOrigin() == FrameProvenance.Origin.SettleTimedOut
                : "the settling timed out, the frame claims "+provenance.getOrigin();
        assert !provenance.isStill() : "a settling that ran out of time is not a proof of stillness: "
                +provenance;
        assert camera.getRecordedSettleResult() == SettleResult.TimedOut
                : "the camera does not remember how it ended: "+camera.getRecordedSettleResult();
        assert provenance.getFrameIndex() > 1
                : "a settling that timed out took several frames, the frame says "
                +provenance.getFrameIndex();

        Throwable refused = alignOn(shot);
        assert refused instanceof FrameNotStillException
                : "a frame from a settling that never agreed should not be aligned upon, but the "
                + "alignment said: "+refused;
    }

    /**
     * The window is what a frame from a camera that does not drive the settling has to fall inside.
     * Before it, the head may still have been ringing; inside it, two frames of the settling have
     * already agreed that nothing moves.
     */
    @Test
    public void testAStreamedFrameIsStillOnlyInsideItsWindow() {
        FrameProvenance inside = FrameProvenance.streamed(1000, 1030, 1025, 3);
        FrameProvenance exactlyOnIt = FrameProvenance.streamed(1000, 1025, 1025, 2);
        FrameProvenance before = FrameProvenance.streamed(1000, 1020, 1025, 1);
        FrameProvenance noWindow = FrameProvenance.streamed(1000, 1030, FrameProvenance.UNKNOWN, 1);

        assert inside.isStill() : "a frame inside the still window is not still: "+inside;
        assert exactlyOnIt.isStill() : "the window begins with a frame that is itself inside it: "
                +exactlyOnIt;
        assert !before.isStill() : "a frame from before the head demonstrably stopped calls itself "
                + "still: "+before;
        assert !noWindow.isStill() : "a streamed frame with no window has nothing to point at: "
                +noWindow;

        // The other four, as one table, because the interesting property is that only two of the six
        // origins can ever be still and that a frame cannot choose which one it is.
        assert FrameProvenance.settled(1000, 1082, 1061, 4).isStill();
        assert FrameProvenance.settled(1000, 1082, FrameProvenance.UNKNOWN, 1).isStill()
                : "a settling that is a configured wait still hands back the frame it waited for";
        assert !FrameProvenance.settled(1000, 1050, 1061, 4).isStill()
                : "a frame older than its own window cannot be the frame that ended it";
        assert !FrameProvenance.quickCapture(1000, 1014).isStill();
        assert !FrameProvenance.settleTimedOut(1000, 1082, 6).isStill();
        assert !FrameProvenance.unknown().isStill();
    }

    /**
     * A frame on disk is only evidence if the file alone says everything the analysis of it needs:
     * which camera, which nozzle, which part, which rotation, which frame of the settling, and where
     * it sat relative to the still window. Nothing of that kind may live in a side file that can be
     * lost, and none of it may be guessed afterwards.
     */
    @Test
    public void testADumpedFrameCarriesItsWholeStoryInTheName() throws Exception {
        setUp();

        // A part id of the kind that really occurs, with the characters in it that make a delimiter
        // out of anything one could innocently choose.
        Part realPart = new Part("C0402-4n7/50V");
        FrameProvenance provenance = FrameProvenance.settled(1000, 1082, 1061, 4);

        String name = FrameDump.fileName(provenance, camera, nozzle, realPart, -12.5);
        System.out.println("[TEST] a dumped frame is called "+name);

        FrameDump.Name parsed = FrameDump.parse(name);
        assert parsed.origin == provenance.getOrigin() : "origin lost: "+parsed.origin;
        assert parsed.cameraId.equals(camera.getId()) : "camera lost: "+parsed.cameraId;
        assert parsed.nozzleId.equals(nozzle.getId()) : "nozzle lost: "+parsed.nozzleId;
        assert parsed.partId.equals("C0402-4n7-50V") : "part id not carried through a slash: "
                +parsed.partId;
        assert parsed.frameIndex == 4 : "frame index lost: "+parsed.frameIndex;
        assert parsed.arrivalAtMs == 1000 : "arrival lost: "+parsed.arrivalAtMs;
        assert parsed.capturedAtMs == 1082 : "capture time lost: "+parsed.capturedAtMs;
        assert parsed.stillWindowStartAtMs == 1061 : "window lost: "+parsed.stillWindowStartAtMs;
        assert Math.abs(parsed.rotation+12.5) < 0.001 : "rotation lost: "+parsed.rotation;
        assert parsed.toProvenance().isStill() : "the frame that came back is not as still as the "
                + "frame that went out";

        // The frame taken while the head is still ringing is the one a corpus is gathered for, so it
        // has to keep its name through a round trip too - and above all two of them have to keep two
        // names. A run whose ringing frames were all handed over as unknown wrote all eight of them to
        // one filename over each other, and the corpus had one frame where eight should have been.
        String ringingName = FrameDump.fileName(FrameProvenance.ringing(1000, 1016), camera, nozzle,
                realPart, -12.5);
        FrameDump.Name ringing = FrameDump.parse(ringingName);
        assert ringing.origin == FrameProvenance.Origin.Ringing : ringing.origin;
        assert ringing.arrivalAtMs == 1000 : "a ringing arrival lost: "+ringing.arrivalAtMs;
        assert ringing.capturedAtMs == 1016 : "a ringing capture lost: "+ringing.capturedAtMs;
        assert !ringing.toProvenance().isStill() : "a ringing frame claims to be still: "+ringingName;
        assert !FrameProvenance.ringing(1000, 1033).equals(FrameProvenance.ringing(1000, 1016));
        String anotherRinging = FrameDump.fileName(FrameProvenance.ringing(1000, 1033), camera, nozzle,
                realPart, -12.5);
        assert !anotherRinging.equals(ringingName) : "two ringing frames of one batch would be written "
                +"to one file over each other: "+ringingName;

        // A frame with nothing to say has to survive as a frame with nothing to say, rather than
        // coming back as something more encouraging.
        String unknownName = FrameDump.fileName(FrameProvenance.unknown(), camera, nozzle, realPart,
                Double.NaN);
        FrameDump.Name unknown = FrameDump.parse(unknownName);
        assert unknown.origin == FrameProvenance.Origin.Unknown : unknown.origin.toString();
        assert unknown.frameIndex < 0 : "an unknown frame index came back as "+unknown.frameIndex;
        assert unknown.arrivalAtMs == FrameProvenance.UNKNOWN : "an unknown arrival came back as the "
                + "epoch, which is a timestamp like any other: "+unknown.arrivalAtMs;
        assert unknown.capturedAtMs == FrameProvenance.UNKNOWN : "an unknown capture time came back "
                + "as "+unknown.capturedAtMs;
        assert unknown.stillWindowStartAtMs == FrameProvenance.UNKNOWN : "an unknown window came "
                + "back as "+unknown.stillWindowStartAtMs;
        assert Double.isNaN(unknown.rotation) : "an unknown rotation came back as "
                +unknown.rotation;
        assert !unknown.toProvenance().isStill() : "nothing became something: "+unknownName;

        // And a name that is not one of ours is not accepted as one.
        boolean rejected = false;
        try {
            FrameDump.parse("bv_source_1777745698182922876.png");
        }
        catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected : "the dumps of the pipeline's own debug stage must not parse as provenance";

        // The letters in the name are what says which number is the arrival and which the capture. A
        // name without them would have to be guessed at, and a guessed provenance that parses cleanly
        // is worse than one that is refused.
        String letterless = name.replaceAll("__(a|c|w|r)\\+", "__+");
        assert !letterless.equals(name) : "the name to strip was not built as expected: "+name;
        rejected = false;
        try {
            FrameDump.parse(letterless);
        }
        catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected : "a name whose fields do not say which they are was accepted: "+letterless;
    }

    @Test
    public void testFramesAreOnlyDumpedWhenSomeoneAsksForThem() throws Exception {
        setUp();

        File dumpDirectory = Files.createTempDir();
        visionSettings.setFrameDumpDirectory(dumpDirectory.getAbsolutePath());
        Mat frame = new Mat(new Size(64, 48), CvType.CV_8UC3, new Scalar(30, 40, 50));
        try {
            FrameDump.writeIfNeeded(visionSettings, frame,
                    FrameProvenance.settled(1000, 1082, 1061, 4), camera, nozzle, part, 0.);
            FrameDump.awaitIdle(2000);
            assert dumpDirectory.list().length == 0
                    : "frames were written that nobody asked for: "
                    +java.util.Arrays.toString(dumpDirectory.list());

            long offered = FrameDump.getFramesOffered();
            visionSettings.setFrameDumpEnabled(true);
            FrameDump.writeIfNeeded(visionSettings, frame,
                    FrameProvenance.settled(1000, 1082, 1061, 4), camera, nozzle, part, 0.);
            assert FrameDump.awaitIdle(5000) : "the writer never came up";
            String[] written = dumpDirectory.list();
            assert written.length == 1 : "one frame was asked for, "+java.util.Arrays
                    .toString(written)+" came out";
            assert new File(dumpDirectory, written[0]).length() > 0 : "the frame is empty";
            assert FrameDump.getFramesOffered()-offered == 1 && FrameDump.getFramesDropped() == 0;

            // The name of the thing on disk is the name the code wrote, and it still tells the truth
            // about the frame after the round trip through the filesystem.
            FrameDump.Name name = FrameDump.parse(written[0]);
            assert name.origin == FrameProvenance.Origin.Settled;
            assert name.frameIndex == 4;
            assert name.capturedAtMs == 1082;
        }
        finally {
            frame.release();
        }
    }

    /**
     * A corpus run has to be started by one word on the command line: there are five Bottom Vision
     * settings on this machine and a corpus taken with three of them ticked is not a corpus, it is a
     * mystery. So the property wins over the settings, in both directions.
     */
    @Test
    public void testTheCommandLineTurnsTheDumpOnForEverySettingAtOnce() throws Exception {
        setUp();

        File dumpDirectory = Files.createTempDir();
        Mat frame = new Mat(new Size(64, 48), CvType.CV_8UC3, new Scalar(30, 40, 50));
        System.setProperty(FrameDump.PROPERTY, dumpDirectory.getAbsolutePath());
        try {
            assert FrameDump.isEnabled(visionSettings) : "the command line did not turn the dump on";
            assert FrameDump.directory(visionSettings).equals(dumpDirectory)
                    : "the command line did not say where: "+FrameDump.directory(visionSettings);

            visionSettings.setFrameDumpEnabled(true);
            visionSettings.setFrameDumpDirectory(Files.createTempDir().getAbsolutePath());
            assert FrameDump.directory(visionSettings).equals(dumpDirectory)
                    : "a directory ticked once in the GUI overrode the run that was asked for";

            long offered = FrameDump.getFramesOffered();
            FrameDump.writeIfNeeded(visionSettings, frame,
                    FrameProvenance.settled(1000, 1082, 1061, 4), camera, nozzle, part, 12.5);
            assert FrameDump.awaitIdle(5000) : "the writer never came up";
            String[] written = dumpDirectory.list();
            assert written.length == 1 : "the dump asked for on the command line wrote "
                    +java.util.Arrays.toString(written);
            FrameDump.Name name = FrameDump.parse(written[0]);
            assert name.partId.equals(part.getId().replaceAll("[^A-Za-z0-9.-]", "-")) : name.partId;
            assert Math.abs(name.rotation-12.5) < 0.001 : name.rotation;

            // And when the property is gone the settings are the whole story again, including "off".
            System.clearProperty(FrameDump.PROPERTY);
            visionSettings.setFrameDumpEnabled(false);
            assert !FrameDump.isEnabled(visionSettings) : "the dump outlived the property that asked "
                    + "for it";
            FrameDump.writeIfNeeded(visionSettings, frame,
                    FrameProvenance.settled(1000, 1082, 1061, 4), camera, nozzle, part, 12.5);
            FrameDump.awaitIdle(2000);
            assert dumpDirectory.list().length == 1 : "a run nobody asked for wrote frames: "
                    +java.util.Arrays.toString(dumpDirectory.list());
            assert FrameDump.getFramesOffered() == offered+1
                    : "a refused frame still counts as one that was asked for";
        }
        finally {
            System.clearProperty(FrameDump.PROPERTY);
            System.clearProperty(FrameDump.MAX_PROPERTY);
            frame.release();
        }
    }

    /**
     * The writing of a corpus must not be allowed to decide how the run it is watching goes: a queue
     * that fills up loses frames and says so, rather than making the head wait over the camera. The
     * count of what was lost is as important as the frames themselves, because a corpus with holes in
     * it has to be read as one.
     */
    @Test
    public void testABurstOfFramesIsWrittenWithoutLosingTheOnesThatFit() throws Exception {
        setUp();

        File dumpDirectory = Files.createTempDir();
        System.setProperty(FrameDump.PROPERTY, dumpDirectory.getAbsolutePath());
        Mat frame = new Mat(new Size(64, 48), CvType.CV_8UC3, new Scalar(30, 40, 50));
        try {
            int asked = 40;
            long offered = FrameDump.getFramesOffered();
            long writtenBefore = FrameDump.getFramesWritten();
            for (int i = 0; i < asked; i++) {
                FrameDump.writeIfNeeded(visionSettings, frame,
                        FrameProvenance.settled(1000, 1082+i, 1061, i+1), camera, nozzle, part, 0.);
            }
            assert FrameDump.awaitIdle(20000) : "the queue never drained";
            assert FrameDump.getFramesOffered()-offered == asked;
            assert FrameDump.getFramesWritten()-writtenBefore == asked
                    : asked+" frames were asked for, "+(FrameDump.getFramesWritten()-writtenBefore)
                            +" arrived";
            String[] files = dumpDirectory.list();
            assert files.length == asked : java.util.Arrays.toString(files);

            // Every frame is itself: the pixels that went in are the pixels that come out, because a
            // replay that disagrees with the machine has to be able to prove it looked at the same
            // picture and not at a corrupted one.
            Mat read = Imgcodecs.imread(new File(dumpDirectory, files[0]).getAbsolutePath());
            assert !read.empty() : "the frame on disk cannot be read";
            assert read.width() == 64 && read.height() == 48 : read.size().toString();
            double[] pixel = read.get(0, 0);
            double[] sent = frame.get(0, 0);
            assert pixel[0] == sent[0] && pixel[1] == sent[1] && pixel[2] == sent[2]
                    : "the frame came back with different pixels: "+java.util.Arrays.toString(pixel)
                            +" against "+java.util.Arrays.toString(sent);
            read.release();

            // A run left running with the dump on cannot fill the disk.
            System.setProperty(FrameDump.MAX_PROPERTY, "5");
            int before = dumpDirectory.list().length;
            for (int i = 0; i < 30; i++) {
                FrameDump.writeIfNeeded(visionSettings, frame,
                        FrameProvenance.settled(1000, 2082+i, 1061, i+1), camera, nozzle, part, 0.);
            }
            FrameDump.awaitIdle(5000);
            assert dumpDirectory.list().length == before
                    : "the cap did not hold: "+(dumpDirectory.list().length-before)+" frames written "
                            +"past it";
        }
        finally {
            System.clearProperty(FrameDump.PROPERTY);
            System.clearProperty(FrameDump.MAX_PROPERTY);
            frame.release();
        }
    }

    /**
     * The camera is the only thing that knows how a settling ended, so it has to remember it for the
     * frame it handed over - and forget it, correctly, when a new settling starts. A number from the
     * previous settling would be worse than no number, because it would be believed.
     */
    @Test
    public void testTheCameraForgetsThePreviousSettling() throws Exception {
        setUp();
        settleByWaiting();

        assert camera.getRecordedSettleResult() == SettleResult.NotSettled
                : "before anything settled, the camera already claims "+camera.getRecordedSettleResult();
        assert camera.getRecordedSettledFrameAtMs() == FrameProvenance.UNKNOWN;
        assert camera.getRecordedStillWindowStartAtMs() == FrameProvenance.UNKNOWN;

        captureWithSettle(NanosecondTime.getRuntimeMilliseconds());
        assert camera.getRecordedSettleResult() == SettleResult.FixedTime
                : "after a wait, the camera claims "+camera.getRecordedSettleResult();
        assert camera.getRecordedSettledFrameAtMs() != FrameProvenance.UNKNOWN
                : "the frame is not timestamped";

        settleByMeasuringUntilTimeout();
        captureWithSettle(NanosecondTime.getRuntimeMilliseconds());
        assert camera.getRecordedSettleResult() == SettleResult.TimedOut
                : "the camera is still reporting the previous settling: "
                +camera.getRecordedSettleResult();
        assert camera.getRecordedStillWindowStartAtMs() == FrameProvenance.UNKNOWN
                : "a settling that timed out must not leave a window behind for someone else to "
                + "quote: "+camera.getRecordedStillWindowStartAtMs();
    }
}
