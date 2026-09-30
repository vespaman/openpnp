import java.io.File;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PartSizeCheckMethod;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreCheckAction;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
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
        final PreCapturedPlacement[] captured = new PreCapturedPlacement[1];
        machine.execute(() -> {
            nozzle.pick(part, null);
            Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle, 0., false);
            if (offset != null) {
                MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation.add(offset));
            }
            else {
                MovableUtils.moveToLocationAtSafeZ(nozzle, wantedLocation);
            }
            captured[0] = bottomVision.captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, true);
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
        System.out.println("[TEST] pre-check on a part that is there: "+result);
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
}
