import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.model.Board;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Nozzle;

import com.google.common.io.Files;

/**
 * A parallel batch and a post-rotate alignment cannot both be had (D10 of the runtime displacement
 * plan). The head stands at one shot location, so every nozzle but the one it moved for is left
 * displaced by whatever the rigid head leaves it at, and post-rotate has no nozzle location of its
 * own to apply that displacement to: it measures the part against the camera and turns the nozzle
 * afterwards. Such a part must be aligned the ordinary way, and it must be said before the head
 * moves rather than in the analysis, where the only thing left to do is watch the part go down
 * uncompensated.
 */
public class PostRotateParallelRefusalTest {

    /**
     * The JobProcessorTest machine, which has two nozzles and one bottom camera, given a second
     * bottom camera so that a parallel batch is possible at all. The camera is found by the number
     * in its name.
     */
    private ReferenceMachine setUpTwoCameraMachine() throws Exception {
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
        camera2.setHeadOffsets(new Location(LengthUnit.Millimeters, 158.799, 7.117, 0, 0));
        machine.addCamera(camera2);

        for (SimulatedUpCamera camera : new SimulatedUpCamera[] {camera1, camera2}) {
            camera.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
            camera.setSettleTimeMs(0);
        }
        for (Nozzle nozzle : machine.getDefaultHead().getNozzles()) {
            ((ReferenceNozzle) nozzle).setPickDwellMilliseconds(0);
            ((ReferenceNozzle) nozzle).setPlaceDwellMilliseconds(0);
        }
        return machine;
    }

    /**
     * One board, two parts, two nozzles: the shape of job that the parallel alignment exists for.
     */
    private Job createJob() {
        Job job = new Job();
        Board board = new Board();
        board.setName("post-rotate-test");
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
     * The control: with the pre-rotate bottom vision that the parallel alignment was built for, this
     * job really does go parallel. Without this the test below could pass simply because nothing was
     * ever able to go parallel.
     */
    @Test
    public void testAPreRotateJobIsAlignedInParallel() throws Exception {
        ReferenceMachine machine = setUpTwoCameraMachine();
        ReferenceBottomVision.getDefault().setPreRotate(true);
        assertTrue(ReferenceBottomVision.getDefault().usesPreRotate(
                Configuration.get().getPart("R0805-1K")),
                "the machine default is what makes these parts pre-rotate");

        runJob(machine, createJob());

        assertTrue(ReferencePnpJobProcessor.getParallelAttempts() > 0,
                "a pre-rotate job spanning two bottom cameras has to attempt the parallel alignment");
        assertEquals(0, ReferencePnpJobProcessor.getParallelFallbacks(),
                "and it has to manage it; fallbacks: "+ReferencePnpJobProcessor.getParallelFallbacks());
        assertNothingLeftOnTheHead(machine);
    }

    /**
     * The same job with the machine default turned off, which is how OpenPnP ships: every part is
     * then aligned post-rotate, so the batch must not be attempted at all. The parts still have to
     * be placed, because going sequential is a change of route and not a failure.
     */
    @Test
    public void testAPostRotateJobIsNotGivenAParallelBatch() throws Exception {
        ReferenceMachine machine = setUpTwoCameraMachine();
        ReferenceBottomVision.getDefault().setPreRotate(false);
        assertFalse(ReferenceBottomVision.getDefault().usesPreRotate(
                Configuration.get().getPart("R0805-1K")),
                "post-rotate is what the machine default says here");

        runJob(machine, createJob());

        assertEquals(0, ReferencePnpJobProcessor.getParallelAttempts(),
                "no part of this job can compensate a displaced shot, so no parallel batch was owed");
        assertEquals(0, ReferencePnpJobProcessor.getParallelFallbacks(),
                "a route that was never attempted is not a fallback either");
        assertNothingLeftOnTheHead(machine);
    }

    /**
     * A part can also opt out on its own, and one post-rotate part is enough to refuse the batch: it
     * is one part placed uncompensated, however well the rest of the batch was aligned.
     */
    @Test
    public void testOnePostRotatePartRefusesTheWholeBatch() throws Exception {
        ReferenceMachine machine = setUpTwoCameraMachine();
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
        vision.setPreRotate(true);

        Part otherPart = Configuration.get().getPart("R0805-1K");
        BottomVisionSettings inherited = vision.getInheritedVisionSettings(otherPart);
        assertTrue(vision.usesPreRotate(otherPart), "the machine default makes this one pre-rotate");

        Part postRotatePart = Configuration.get().getPart("R0603-1K");
        BottomVisionSettings settings = new BottomVisionSettings();
        settings.setEnabled(true);
        settings.setPreRotateUsage(PreRotateUsage.AlwaysOff);
        if (inherited != null) {
            settings.setPipeline(inherited.getPipeline().clone());
        }
        postRotatePart.setBottomVisionSettings(settings);
        assertFalse(vision.usesPreRotate(postRotatePart),
                "the part said AlwaysOff, so the machine default cannot overrule it");

        runJob(machine, createJob());

        assertEquals(0, ReferencePnpJobProcessor.getParallelAttempts(),
                "one post-rotate part is enough that no parallel batch was owed here");
        assertNothingLeftOnTheHead(machine);
    }
}
