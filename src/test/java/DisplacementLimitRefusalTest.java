import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Board;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import com.google.common.io.Files;

/**
 * The displacement limits are a decision, not a caption (D5). A parallel batch never moves a nozzle onto
 * its own shot - the head stands at one shot location and the rest of the nozzles are where the rigid
 * head leaves them - so the only honest question is whether what is left is small enough to be measured
 * out of the picture, and the only place that question can still be answered is after the head has
 * arrived and before anything has been exposed. Afterwards there is nothing to do but print the number,
 * which is what this used to do.
 *
 * <p>The Z of a shot is put at the machine's balance level for the same reason: on a head whose two Z
 * axes are one rocker turned the other way for the second nozzle, the balance point is the one height
 * where both nozzles agree, so comparing each nozzle against the balance is comparing like with like,
 * while comparing it against its own camera's focal height compares a height with its negation.</p>
 */
public class DisplacementLimitRefusalTest {

    /**
     * The JobProcessorTest machine with a second bottom camera, pre-rotate bottom vision and
     * displacement switched on - the configuration in which a parallel batch is possible and the limits
     * have something to say.
     */
    private ReferenceMachine setUpDisplacingMachine(double camera2XOffsetMm) throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");

        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/machine.xml"),
                new File(workingDirectory, "machine.xml"));
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/packages.xml"),
                new File(workingDirectory, "packages.xml"));
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/parts.xml"),
                new File(workingDirectory, "parts.xml"));

        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();

        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        SampleJobTest.makeMachineFastest();

        SimulatedUpCamera camera1 = (SimulatedUpCamera) machine.getCamera("C2");
        camera1.setName("Bottom 1");
        SimulatedUpCamera camera2 = new SimulatedUpCamera();
        camera2.setId("C3");
        camera2.setName("Bottom 2");
        camera2.setLooking(org.openpnp.spi.Camera.Looking.Up);
        // Where the second nozzle holds its part when the first is over its camera. Moved a little
        // from that in one test, which is what a camera that has been bumped, or a machine whose
        // camera spacing does not match its nozzle spacing, amounts to.
        camera2.setHeadOffsets(new Location(LengthUnit.Millimeters, 158.799 + camera2XOffsetMm, 7.117,
                0, 0));
        machine.addCamera(camera2);

        for (SimulatedUpCamera camera : new SimulatedUpCamera[] {camera1, camera2}) {
            camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
            camera.setSettleTimeMs(0);
        }
        for (Nozzle nozzle : machine.getDefaultHead().getNozzles()) {
            ((ReferenceNozzle) nozzle).setPickDwellMilliseconds(0);
            ((ReferenceNozzle) nozzle).setPlaceDwellMilliseconds(0);
        }

        ReferenceBottomVision.getDefault().setPreRotate(true);
        machine.setParallelAlignmentEnabled(true);
        machine.setDisplacementEnabled(true);
        assertTrue(machine.isDisplacementEnabled(), "without displacement the limits belong to "
                +"the sequential skip-move and not to this decision");
        return machine;
    }

    private Job createJob() {
        Job job = new Job();
        Board board = new Board();
        board.setName("displacement-limit-test");
        board.addPlacement(createPlacement("A", "R0805-1K", 10, 10, 0, 0));
        board.addPlacement(createPlacement("B", "R0603-1K", 20, 10, 0, 0));
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 0, 40, -10, 0));
        boardLocation.setGlobalSide(Side.Top);
        job.addBoardOrPanelLocation(boardLocation);
        return job;
    }

    private Placement createPlacement(String id, String partId, double x, double y, double z,
            double rotation) {
        Placement placement = new Placement(id);
        placement.setPart(Configuration.get().getPart(partId));
        placement.setLocation(new Location(LengthUnit.Millimeters, x, y, z, rotation));
        return placement;
    }

    private void runJob(ReferenceMachine machine, Job job) throws Exception {
        ReferencePnpJobProcessor jobProcessor =
                (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        machine.setEnabled(true);
        machine.home();
        machine.execute(() -> {
            jobProcessor.initialize(job);
            while (jobProcessor.next()) {
                //spin
            }
            return null;
        }, false, 60000);
    }

    private void assertNothingLeftOnTheHead(ReferenceMachine machine) throws Exception {
        for (Nozzle nozzle : machine.getDefaultHead().getNozzles()) {
            assertTrue(nozzle.getPart() == null,
                    "nozzle "+nozzle.getId()+" still holds a part, the job did not finish");
        }
    }

    /**
     * A batch that is within the limits still goes parallel: the check has to refuse the batches that
     * cannot work and not every batch, which is the difference between a limit and an off switch.
     */
    @Test
    public void testABatchWithinTheLimitsIsStillAlignedInParallel() throws Exception {
        ReferenceMachine machine = setUpDisplacingMachine(0);

        runJob(machine, createJob());

        assertTrue(ReferencePnpJobProcessor.getParallelAttempts() > 0,
                "the parallel alignment was owed a chance");
        assertEquals(0, ReferencePnpJobProcessor.getParallelFallbacks(),
                "and nothing about this machine is outside the tolerated displacement; fallbacks: "
                        +ReferencePnpJobProcessor.getParallelFallbacks());
        assertNothingLeftOnTheHead(machine);
    }

    /**
     * Move the second camera a millimetre away - less than the spacing of the nozzles, more than the
     * 0.5mm the machine will see through - and the batch has to notice before it exposes anything. The
     * parts still have to be placed, because the sequential path moves each nozzle onto its own shot and
     * can therefore do the job properly.
     */
    @Test
    public void testANozzleOverTheDistanceLimitGoesSequential() throws Exception {
        ReferenceMachine machine = setUpDisplacingMachine(1.0);
        // Leave Z out of it, so that what refuses this batch is the distance and nothing else.
        machine.setDisplacementMaxZDiff(new Length(10, LengthUnit.Millimeters));

        runJob(machine, createJob());

        assertTrue(ReferencePnpJobProcessor.getParallelAttempts() > 0,
                "the batch was attempted; it is the arrival that found the nozzle too far away");
        assertTrue(ReferencePnpJobProcessor.getParallelFallbacks() > 0,
                "a nozzle more than "+machine.getDisplacementMaxDistance()+" from its own shot has to "
                        +"send the batch sequential instead of being photographed from there; fallbacks: "
                        +ReferencePnpJobProcessor.getParallelFallbacks());
        assertNothingLeftOnTheHead(machine);
    }

    /**
     * Height is judged the same way, and this is the case it exists for: a head whose nozzles have Z
     * axes of their own leaves every nozzle but the one it moved for at whatever height it was last at,
     * so the parts under the other cameras are photographed out of focus and at the wrong magnification,
     * quietly. A head whose two Z axes are one rocker turned the other way for the second nozzle meets
     * the same limit without being asked, because both nozzles follow the one motor to the balance level;
     * this simulated head is not such a head, so it is refused, which is the slower route and the right
     * one. See the machine note about independent Z axes in
     * docs/design/parallel-bottom-vision/runtime-displacement-plan.md.
     */
    @Test
    public void testANozzleThatCannotStandAtTheHeightOfItsShotGoesSequential() throws Exception {
        ReferenceMachine machine = setUpDisplacingMachine(0);
        // A balance level well away from where the second nozzle is left, with no slack in Z.
        machine.setBalanceLevel(new Length(3.0, LengthUnit.Millimeters));
        machine.setDisplacementMaxZDiff(new Length(0.0001, LengthUnit.Millimeters));

        runJob(machine, createJob());

        assertTrue(ReferencePnpJobProcessor.getParallelAttempts() > 0,
                "the batch was attempted");
        assertTrue(ReferencePnpJobProcessor.getParallelFallbacks() > 0,
                "a nozzle that is not at the height the shots are taken at cannot be measured out of a "
                        +"picture of it; fallbacks: "+ReferencePnpJobProcessor.getParallelFallbacks());
        assertNothingLeftOnTheHead(machine);
    }

    /**
     * The shot Z is the balance level and not the camera's focal height, because on a rocker the second
     * nozzle is that far the other way. The units matter: the balance level is a {@link Length} of its
     * own and the plain double would be read in the units of the shot location.
     */
    @Test
    public void testTheShotZIsTheBalanceLevelInItsOwnUnits() throws Exception {
        ReferenceMachine machine = setUpDisplacingMachine(0);
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();

        machine.setBalanceLevel(new Length(0.42, LengthUnit.Millimeters));
        Location shot = new Location(LengthUnit.Millimeters, 156.3, 27.6, 0.987, 0.);
        Location balanced = vision.withBalanceLevelZ(shot);
        assertEquals(0.42, balanced.getLengthZ().convertToUnits(LengthUnit.Millimeters).getValue(),
                1e-9, "the shot Z is the balance level");
        assertEquals(156.3, balanced.getLengthX().convertToUnits(LengthUnit.Millimeters).getValue(),
                1e-9, "and nothing else about the shot is touched");
        assertEquals(27.6, balanced.getLengthY().convertToUnits(LengthUnit.Millimeters).getValue(),
                1e-9, "nor the Y");

        // The same balance level asked of a shot expressed in inches: a double would have been taken as
        // 0.42 inches, which is 10.7mm of error on a machine with a 0.1mm tolerance.
        Location inInches = new Location(LengthUnit.Inches, 6.15, 1.08, 0.039, 0.);
        Location balancedInches = vision.withBalanceLevelZ(inInches);
        assertEquals(0.42, balancedInches.getLengthZ().convertToUnits(LengthUnit.Millimeters).getValue(),
                1e-9, "the balance level is a length, not a bare number");
        assertEquals(6.15, balancedInches.getLengthX().convertToUnits(LengthUnit.Inches).getValue(),
                1e-9, "and the rest stays in the units it came in");

        machine.setDisplacementEnabled(false);
        Location notDisplacing = vision.withBalanceLevelZ(shot);
        assertEquals(0.987, notDisplacing.getLengthZ().convertToUnits(LengthUnit.Millimeters).getValue(),
                1e-9, "with displacement off nothing is displaced, so the camera's own height stands");
    }
}
