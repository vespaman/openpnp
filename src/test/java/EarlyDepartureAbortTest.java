import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import org.openpnp.model.Board;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.CapturedShot;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Machine;
import org.openpnp.spi.base.AbstractMachine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;

import com.google.common.io.Files;

/**
 * R9 of the parallel bottom vision: the head is allowed to leave the cameras while the alignment is
 * still being computed, but when that computation fails the machine must notice, go back and do it
 * the ordinary way - and the board must still get its parts.
 *
 * <p>The failure is injected in a subclass of {@link ReferenceBottomVision} that refuses the
 * pre-captured picture once. That is exactly the seam the deferred pipelines use
 * ({@link org.openpnp.util.VisionUtils#findPartAlignmentOffsetsWithCapturedShot}), and it is the
 * only seam, because that dispatch tests for ReferenceBottomVision itself rather than for an
 * interface method - a wrapper would quietly have been asked to align the old way and the test
 * would have proved nothing.</p>
 */
public class EarlyDepartureAbortTest {

    /**
     * Bottom vision that throws on one pre-captured picture, so that a deferred alignment is seen
     * to fail while the head is on its way to the board.
     */
    public static class OnceRefusingBottomVision extends ReferenceBottomVision {
        private static final long serialVersionUID = 1L;

        /**
         * Placement ids whose pre-captured picture was refused, in the order it happened.
         */
        final List<String> refused = Collections.synchronizedList(new ArrayList<>());
        /**
         * Placement ids aligned the ordinary way, over the camera. If the abort works, the refused
         * placement turns up here as well.
         */
        final List<String> overCamera = Collections.synchronizedList(new ArrayList<>());
        /**
         * Placement ids whose alignment was handed a picture taken beforehand.
         */
        final List<String> withShot = Collections.synchronizedList(new ArrayList<>());
        /**
         * Placement ids that were aligned, however the picture was obtained.
         */
        final List<String> aligned = Collections.synchronizedList(new ArrayList<>());
        /**
         * Set once, by the machine itself, to make the next pre-captured picture fail.
         */
        volatile boolean refuseNext = false;

        @Override
        public PartAlignmentOffset findOffsets(Part part, BoardLocation boardLocation,
                Placement placement, Nozzle nozzle, boolean skipMovement,
                CapturedShot preCapturedShot) throws Exception {
            // Every way into bottom vision comes through here, the picture in hand or not.
            if (preCapturedShot == null) {
                overCamera.add(placement.getId());
            }
            else {
                withShot.add(placement.getId());
                if (refuseNext) {
                    refuseNext = false;
                    refused.add(placement.getId());
                    throw new Exception("simulated failure of a deferred alignment");
                }
            }
            PartAlignmentOffset offsets = super.findOffsets(part, boardLocation, placement,
                    nozzle, skipMovement, preCapturedShot);
            aligned.add(placement.getId());
            return offsets;
        }
    }

    @Test
    public void testADeferredAlignmentThatFailsIsMadeGoodOverTheCamera() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");

        // The machine with the feeders and the bottom camera that the job tests use.
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

        // Two nozzles cannot share one camera, and the parallel alignment says so and works its way
        // through them one at a time. Give the second nozzle a camera of its own, standing where
        // the first one stands but a nozzle-spacing over, which is where N2 will be holding its
        // part. The camera is found by the number in its name.
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

        // The vision that will refuse one picture. There is no way to put a part alignment into a
        // machine - the GUI edits the XML and there is none in these test machines, which is created
        // for them when they load - so the one that was created is stood aside for ours. Its name is
        // kept, so that anything that knows it by name still knows it.
        OnceRefusingBottomVision vision = new OnceRefusingBottomVision();
        vision.setEnabled(true); // a bare ReferenceBottomVision is not enabled until a machine says so
        Field partAlignments = AbstractMachine.class.getDeclaredField("partAlignments");
        partAlignments.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PartAlignment> alignments = (List<PartAlignment>) partAlignments.get(machine);
        vision.setName(alignments.get(0).getName());
        alignments.set(0, vision);

        machine.setParallelAlignmentEnabled(true);
        machine.setEarlyAlignmentDeparture(true);
        assertTrue(machine.isEarlyAlignmentDepartureEnabled(),
                "the machine must actually be armed for the head to leave");

        // Two parts, two nozzles, one board.
        Job job = new Job();
        Board board = new Board();
        board.setName("abort-test");
        board.addPlacement(createPlacement("A", "R0805-1K", 10, 10, 0, 0));
        board.addPlacement(createPlacement("B", "R0603-1K", 20, 10, 0, 0));
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 0, 40, -10, 0));
        boardLocation.setGlobalSide(Side.Top);
        job.addBoardOrPanelLocation(boardLocation);

        ReferencePnpJobProcessor jobProcessor =
                (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        jobProcessor.addTextStatusListener(text -> System.out.println(text));

        machine.setEnabled(true);
        machine.home();

        // Refuse the first pre-captured picture the deferred pipelines ask for. The head is armed
        // to be on its way to the board by then, but nothing about the failure can be predicted,
        // so it is the machine that decides when it has happened: the vision refuses, the abort
        // path runs, and the rest of the job is history.
        machine.execute(() -> {
            jobProcessor.initialize(job);
            vision.refuseNext = true;
            while (jobProcessor.next()) {
                //spin
            }
            return null;
        }, false, 60000);

        assertFalse(vision.refused.isEmpty(),
                "the deferred alignment never ran over a pre-captured picture, so the head never "
                        +"had anything to abort");
        assertEquals(1, vision.refused.size(), "exactly one picture was to be refused");
        String abandoned = vision.refused.get(0);
        assertTrue(vision.overCamera.contains(abandoned),
                "placement "+abandoned+" was refused over the cameras and never went back to be "
                        +"aligned the ordinary way; the abort path did not run.  "
                        +"over the camera: "+vision.overCamera+", with a picture: "
                        +vision.withShot);

        // The proof that the board was not given up on: both parts were aligned and put down, and
        // the head came to rest with nothing on it.
        assertTrue(vision.aligned.containsAll(Arrays.asList("A", "B")),
                "both placements had to be aligned after the abort, only these were: "
                        +vision.aligned);
        for (Nozzle nozzle : machine.getDefaultHead().getNozzles()) {
            assertTrue(nozzle.getPart() == null,
                    "nozzle "+nozzle.getId()+" still holds a part, the job did not finish");
        }
    }

    private Placement createPlacement(String id, String partId, double x, double y, double z,
            double rotation) {
        Placement placement = new Placement(id);
        placement.setPart(Configuration.get().getPart(partId));
        placement.setLocation(new Location(LengthUnit.Millimeters, x, y, z, rotation));
        return placement;
    }
}
