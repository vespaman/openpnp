import java.io.File;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSizeCheckMethod;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreCheckAction;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.model.PreCheckResult;
import org.openpnp.model.PreCheckResult.Verdict;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;

import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;

import com.google.common.io.Files;

/**
 * The pre-check of the first settling frame, in report mode.
 *
 * What is asserted is that the check runs, that it says something, and that the three durations are
 * three separate numbers. Nothing is asserted about the machine behaving differently because of it,
 * because in report mode nothing does.
 *
 * The tests do wait for a verdict, which production never does: a test has to know what the check
 * answered, a job does not.
 */
public class PreCheckReportTest {

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

        // A settling loop that really loops: the simulated camera is perfectly still, so the loop
        // runs until its timeout. Short, but several frames, which is the point - the pre-check is
        // supposed to judge the first one of them and not the last one.
        camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.Maximum);
        camera.setSettleTimeoutMs(300);
        camera.setSettleDebounce(0);
        camera.setErrorOffsets(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));

        machine.setEnabled(true);
        machine.home();
    }

    private PreCapturedPlacement captureWithSettle(Location offset) throws Exception {
        return captureWithSettle(offset, 0.0);
    }

    /**
     * The rotation is the rotation of the nozzle, and the simulated camera draws the part at the
     * rotation it is held at, which is how a part a quarter turn off is produced without lying about
     * its size.
     */
    private PreCapturedPlacement captureWithSettle(Location offset, double rotation) throws Exception {
        final PreCapturedPlacement[] captured = new PreCapturedPlacement[1];
        machine.execute(() -> {
            nozzle.pick(part, null);
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle, 0., false);
            if (offset != null) {
                wantedLocation = wantedLocation.add(offset);
            }
            MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation.derive(null, null, null, rotation));
            captured[0] = bottomVision.captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, true);
            return true;
        });
        return captured[0];
    }

    private PreCapturedPlacement captureWithoutSettle() throws Exception {
        final PreCapturedPlacement[] captured = new PreCapturedPlacement[1];
        machine.execute(() -> {
            nozzle.pick(part, null);
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle, 0., false);
            MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation);
            captured[0] = bottomVision.captureImageOnly(part, camera, nozzle, wantedLocation, true);
            return true;
        });
        return captured[0];
    }

    private PreCheckResult awaitVerdict(PreCapturedPlacement shot) throws Exception {
        PreCheckResult result = shot.getPreCheckResult();
        long deadline = System.currentTimeMillis()+20000;
        while(result != null && result.getVerdict() == Verdict.Pending && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return result;
    }

    @Test
    public void testFirstFrameOfTheSettlingIsChecked() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(2.0);
        part.getPackage().getFootprint().setBodyHeight(1.25);
        visionSettings.setCheckSizeTolerancePercent(50);

        PreCheckResult result = awaitVerdict(captureWithSettle(null));

        assert result != null : "the pre-check was not attached to the shot at all";
        assert result.getVerdict() == Verdict.Ok : "verdict should be Ok, was "+result+" ("+result.getMessage()+")";
        assert result.getMeasuredWidth() > 0.5 && result.getMeasuredWidth() < 10.0
                : "measured width should be in millimetres of a plausible order, was "+result.getMeasuredWidth();
        assert result.getCheckMs() > 0.0 : "the check itself was not timed: "+result;
        assert result.getCaptureMs() >= 0.0 : "the capture was not timed: "+result;
        assert result.getSettleMs() >= 0.0 : "the settling was not timed: "+result;
        assert result.getFrameCount() >= 1 : "the number of settling frames was not recorded: "+result;
        // Where the check spent its time. A check that is too slow is only fixable if one knows
        // which of its four parts is, so all four have to survive to the log.
        assert result.getCloneMs() >= 0.0 : "keeping the frame was not timed: "+result;
        assert result.getQueueMs() >= 0.0 : "the wait for the vision executor was not timed: "+result;
        assert result.getPipelineMs() >= 0.0 : "cloning the pipeline was not timed: "+result;
        assert result.getPrepMs() >= 0.0 : "preparing the pipeline was not timed: "+result;
        assert result.getProcessMs() > 0.0 : "running the pipeline was not timed: "+result;
        assert result.getJudgeMs() >= 0.0 : "the check does not add up: "+result;
        System.out.println("[TEST] pre-check on a part that is there: "+result);
    }

    /**
     * The camera that does not settle has one frame and no early one, so the pre-check on it cannot
     * win a race. It is there so that every nozzle in the alignment has a verdict when the head
     * departs, and so that the log line of the second nozzle is not silently missing.
     */
    @Test
    public void testTheCameraThatDoesNotSettleIsCheckedToo() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(2.0);
        part.getPackage().getFootprint().setBodyHeight(1.25);
        visionSettings.setCheckSizeTolerancePercent(50);

        PreCapturedPlacement shot = captureWithoutSettle();

        assert shot != null && shot.getImage() != null : "nothing was captured";
        PreCheckResult result = awaitVerdict(shot);
        assert result != null : "the camera that does not settle got no pre-check at all";
        assert result.getVerdict() == Verdict.Ok
                : "verdict should be Ok, was "+result+" ("+result.getMessage()+")";
        assert result.getSettleMs() < 0.0 : "a camera that does not settle claims a settling: "+result;
        assert !result.isSettleWindowOpen() : "the settling of a camera that does not settle is still open: "+result;
        assert result.getFrameCount() == 1 : "one frame was captured, the log says "+result.getFrameCount();
        // Zero is a number this one may honestly reach: a simulated frame is rendered between two
        // readings of a clock that counts milliseconds. What must not happen is the field being left
        // at the -1 of a thing that was never timed.
        assert result.getCaptureMs() >= 0.0 : "the capture was not timed: "+result;
        assert result.getPipelineMs() >= 0.0 : "cloning the pipeline was not timed: "+result;
        System.out.println("[TEST] pre-check on the only frame of a camera that does not settle: "+result);
    }

    @Test
    public void testPartOfTheWrongSizeIsReported() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(20.0);
        part.getPackage().getFootprint().setBodyHeight(20.0);
        visionSettings.setCheckSizeTolerancePercent(20);

        PreCheckResult result = awaitVerdict(captureWithSettle(null));

        assert result != null : "the pre-check was not attached to the shot at all";
        assert result.getVerdict() == Verdict.SizeOut
                : "a part nowhere near the expected size should be SizeOut, was "+result+" ("+result.getMessage()+")";
        System.out.println("[TEST] pre-check on a part of the wrong size: "+result);
    }

    @Test
    public void testPartThatIsNotThereIsReported() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.Disabled);

        // The wanted location is where the part would have been seen, but the nozzle has been moved
        // away, so the camera looks at an empty field.
        PreCheckResult result = awaitVerdict(
                captureWithSettle(new Location(LengthUnit.Millimeters, 25, 25, 0, 0)));

        assert result != null : "the pre-check was not attached to the shot at all";
        assert result.getVerdict() != Verdict.Ok 
                : "an empty field must not be reported as Ok: "+result;
        assert result.getVerdict() != Verdict.Pending : "the check never said anything at all";
        System.out.println("[TEST] pre-check on an empty field: "+result);
    }

    @Test
    public void testDisabledPreCheckLeavesTheShotAlone() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Disabled);

        PreCapturedPlacement shot = captureWithSettle(null);

        assert shot != null : "nothing was captured";
        assert shot.getImage() != null : "the shot has no image, and the pre-check is supposed to be unrelated";
        assert shot.getPreCheckResult() == null : "a disabled pre-check still produced "+shot.getPreCheckResult();
    }

    @Test
    public void testDisabledPreCheckLeavesTheQuickCaptureAlone() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Disabled);

        PreCapturedPlacement shot = captureWithoutSettle();

        assert shot != null : "nothing was captured";
        assert shot.getImage() != null : "the shot has no image, and the pre-check is supposed to be unrelated";
        assert shot.getPreCheckResult() == null : "a disabled pre-check still produced "+shot.getPreCheckResult();
    }

    /**
     * A rectangle that OpenCV hands over as 17x37 at 0° and the same rectangle handed over as 37x17 at
     * 90° are the same rectangle, and which of the two a frame gets depends on where the contour starts.
     * The size check compares width with width, so on a part that is a quarter turn off it is a coin
     * toss: on the machine the turned 0402 measured 0.985x0.448 on the frame the alignment looked at and
     * 0.45x0.97 on the frame the pre-check looked at, 26 ms apart, and the pre-check warned.
     *
     * Asserted here without a camera, because the two readings are the whole question and a stopwatch
     * would only make them harder to read.
     */
    @Test
    public void testTheSizeCheckReadsARectangleBothWays() throws Exception {
        setUp();

        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(2.0);
        part.getPackage().getFootprint().setBodyHeight(1.25);
        // Tight enough that the short side cannot pass for the long one: at 50 % the swap is inside the
        // tolerance and the question never gets asked.
        visionSettings.setCheckSizeTolerancePercent(10);

        RotatedRect asReported = new RotatedRect(new Point(100, 100),
                new Size(px(2.0), px(1.25)), 0);
        RotatedRect theOtherWayRound = new RotatedRect(new Point(100, 100),
                new Size(px(1.25), px(2.0)), 0);
        RotatedRect anotherPart = new RotatedRect(new Point(100, 100),
                new Size(px(1.25), px(0.9)), 0);

        ReferenceBottomVision.SizeFit straight = ReferenceBottomVision.checkPartSizeEitherWay(part,
                visionSettings, asReported, camera);
        assert straight.fits() && !straight.isTurned() : "the part as it is drawn is now not the part: "+straight.getMessage();

        ReferenceBottomVision.SizeFit turned = ReferenceBottomVision.checkPartSizeEitherWay(part,
                visionSettings, theOtherWayRound, camera);
        assert turned.fits() : "a part that is the right size a quarter turn off was refused: "+turned.getMessage();
        assert turned.isTurned() : "the check does not say it had to read the rectangle the other way round";

        ReferenceBottomVision.SizeFit wrong = ReferenceBottomVision.checkPartSizeEitherWay(part,
                visionSettings, anotherPart, camera);
        assert !wrong.fits() : "a part that is neither of the two expected sides is now accepted as well: "+wrong.getMessage();

        System.out.println("[TEST] size check read both ways: "+asReported.size+" fits, "+theOtherWayRound.size
                +" fits turned, "+anotherPart.size+" refused ("+wrong.getMessage()+")");
    }

    /**
     * The same thing end to end: the simulated camera draws the part at the rotation of the nozzle, so
     * a nozzle turned through 90° holds a part whose two sides reach the check in the other order.
     */
    @Test
    public void testATurnedPartIsNotReportedAsTheWrongPart() throws Exception {
        setUp();

        visionSettings.setPreCheckAction(PreCheckAction.Report);
        visionSettings.setCheckPartSizeMethod(PartSizeCheckMethod.BodySize);
        part.getPackage().getFootprint().setBodyWidth(2.0);
        part.getPackage().getFootprint().setBodyHeight(1.25);
        visionSettings.setCheckSizeTolerancePercent(10);

        PreCheckResult result = awaitVerdict(captureWithSettle(null, 90.0));

        assert result != null : "the pre-check was not attached to the shot at all";
        assert result.getMeasuredWidth() < result.getMeasuredHeight()
                : "the part was not drawn a quarter turn off, so nothing was tested: "+result;
        assert result.getVerdict() == Verdict.Ok
                : "a part of the right size and a quarter turn off is the right part, was "+result
                        +" ("+result.getMessage()+")";
        assert result.getMessage().contains("turned") : "the check accepted it without saying why: "+result;
        System.out.println("[TEST] pre-check on a part a quarter turn off: "+result);
    }

    private double px(double millimetres) {
        return VisionUtils.toPixels(new Length(millimetres, LengthUnit.Millimeters), camera);
    }
}
