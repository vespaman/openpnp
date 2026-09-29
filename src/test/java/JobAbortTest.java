import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

/**
 * Section 5 of docs/design/parallel-bottom-vision/MACHINE-TEST.md: abort a job, then run it again.
 *
 * The parallel aligner holds a camera batch open across a shot, and the batch is what holds the anti glare
 * light on, so {@code abort()} ends the batch it owns before it cleans up - see the {@code cameraBatchOperationStarted}
 * branch in {@code ReferencePnpJobProcessor.abort()}. This checks the bookkeeping survives an abort and that
 * the job can simply be started again.
 *
 * Two things are deliberately not claimed here, because a simulated machine cannot see them. The abort in the
 * middle of a shot, with the lamp on and the shutter mid-capture, is the machine test: a batch is only open
 * inside one step of the job processor, so a test in this thread cannot be inside it. And "the part is still
 * on the nozzle" cannot be asserted with {@code isPartOn()} at all, because {@code TestActuator} answers the
 * vacuum sense with a constant, so it reports part on whenever the valve is open; {@code getPart()} is the
 * bookkeeping that does mean something here.
 */
public class JobAbortTest {

    private static final int PLACEMENTS = 4;

    private Job job;

    private ReferenceMachine setUp() throws Exception {
        Configuration.initialize();
        ReferenceMachine machine = (ReferenceMachine) new ReferenceJobProcessorRetryTests.MachineBuilder()
                .head("H1")
                .nozzleTip("NT1")
                .nozzle("N1", "NT1")
                .topCamera("TOP")
                .bottomCamera("BOTTOM")
                .build();
        ((NullDriver) machine.getDefaultDriver()).setFeedRateMmPerMinute(0);
        job = buildJob();
        ((ReferenceJobProcessorRetryTests.TestFeeder) machine.getFeederByName("F1")).setPartCount(20);
        machine.setEnabled(true);
        machine.home();
        return machine;
    }

    private Job buildJob() throws Exception {
        return new ReferenceJobProcessorRetryTests.JobBuilder()
                .board("B1", 10, 10, 10, -10)
                .packag("R0402", "NT1")
                .part("R0402-1k", "R0402")
                .feeder("F1", "R0402-1k", 100, 20, -5, 0)
                .placement("R1", "R0402-1k", 10, 10, 0)
                .placement("R2", "R0402-1k", 20, 20, 0)
                .placement("R3", "R0402-1k", 30, 30, 0)
                .placement("R4", "R0402-1k", 40, 40, 0)
                .build();
    }

    @Test
    public void abortEndsTheBatchAndGivesThePickedPartBack() throws Exception {
        ReferenceMachine machine = setUp();
        ReferencePnpJobProcessor jobProcessor = (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        CameraBatchOperation batch = machine.getCameraBatchOperation();
        Camera bottomCamera = camera(machine, "BOTTOM");
        Nozzle nozzle = machine.getHeadByName("H1").getNozzleByName("N1");

        jobProcessor.initialize(job);
        boolean aborted = false;
        while (jobProcessor.next()) {
            if (countUnplaced(job) > PLACEMENTS - 2) {
                continue;
            }
            jobProcessor.abort();
            aborted = true;
            break;
        }

        assertTrue(aborted, "the job should not have reached its end before the abort");
        assertFalse(batch.registerWithBatchOperation(bottomCamera),
                "abort() must not leave the camera batch, and with it the light, open");
        assertNull(nozzle.getPart(), "abort() must discard the part that was picked");
        assertEquals(2, countUnplaced(job), "the two later placements must still be to do");

        // And the bookkeeping must be usable again, not stuck at some nesting depth the abort skipped.
        batch.startBatchOperation("after-abort");
        assertTrue(batch.registerWithBatchOperation(bottomCamera),
                "a batch opened after an aborted job must still be a batch");
        batch.endBatchOperation("after-abort");
        assertFalse(batch.registerWithBatchOperation(bottomCamera));
    }

    @Test
    public void anAbortedJobCanBeRunAgainToPlaceTheRest() throws Exception {
        ReferenceMachine machine = setUp();
        ReferencePnpJobProcessor jobProcessor = (ReferencePnpJobProcessor) machine.getPnpJobProcessor();

        jobProcessor.initialize(job);
        while (jobProcessor.next()) {
            if (countUnplaced(job) <= PLACEMENTS - 2) {
                jobProcessor.abort();
                break;
            }
        }
        int remaining = countUnplaced(job);
        assertTrue(remaining > 0 && remaining < PLACEMENTS, "the abort must leave some placements to do");

        // The abort parks the head, which is what the GUI does too before it lets the job start again.
        machine.home();
        jobProcessor.initialize(job);
        while (jobProcessor.next()) {
            //spin
        }

        assertEquals(0, countUnplaced(job), "the resumed job must place what the aborted one left");
        for (Nozzle nozzle : machine.getHeadByName("H1").getNozzles()) {
            assertNull(nozzle.getPart(), "no nozzle may still hold a part at the end of a job");
        }
    }

    private Camera camera(Machine machine, String name) {
        for (Camera camera : machine.getCameras()) {
            if (name.equals(camera.getName())) {
                return camera;
            }
        }
        throw new IllegalArgumentException("no camera " + name);
    }

    private int countUnplaced(Job job) {
        return job.getActivePlacements(job.getRootPanelLocation());
    }
}
