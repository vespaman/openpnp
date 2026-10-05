import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.FrameBuffer;
import org.openpnp.util.NanosecondTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ring of frames that are grabbed before anybody asks for them.
 *
 * Everything here is off the machine: the "camera" is a thread that manufactures a picture every few
 * milliseconds. What is being tested is not the pixels but the promises the ring makes - that it hands
 * out frames no earlier than the bound it was given, that it hands out the newest of them to a caller
 * that wanted "a frame from now" and the oldest of them to a caller that wanted "a frame from the
 * beginning of the window", that it stops grabbing when its session expires, and that a caller never
 * waits for anything that is not being produced. Those are the promises the still-window argument in
 * ReferenceBottomVision rests on; if they are wrong here, the alignment is being handed frames of a
 * head that was moving.
 */
public class FrameBufferTest {

    /**
     * A frame that is not a real frame: 16x16 pixels, nothing in them but a grey level so that a test
     * can tell one from another.
     */
    private static BufferedImage frame(int grey) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, grey & 0xffffff);
            }
        }
        return image;
    }

    private static int greyOf(BufferedImage image) {
        return image.getRGB(3, 3) & 0xff;
    }

    /**
     * A source that produces a frame on demand, as fast as it is asked, counting the asks. Stamps are
     * given by the buffer, not by the source, which is how it is in the machine too.
     */
    private static class CountingSource implements FrameBuffer.Source {
        final AtomicLong asks = new AtomicLong();

        final AtomicInteger produced = new AtomicInteger();

        volatile boolean fail;

        @Override
        public boolean hasNewFrame() {
            return true;
        }

        @Override
        public BufferedImage grab() throws Exception {
            asks.incrementAndGet();
            if (fail) {
                throw new Exception("the stream died");
            }
            return frame(produced.incrementAndGet() & 0xff);
        }
    }

    /**
     * A source that produces a frame at a fixed cadence, so that the interval estimate and the "wait for
     * the next one" behaviour can be tested against a known rate.
     */
    private static class CadencedSource implements FrameBuffer.Source {
        private final long intervalMs;

        private final AtomicLong lastAt = new AtomicLong();

        final AtomicInteger produced = new AtomicInteger();

        CadencedSource(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        @Override
        public boolean hasNewFrame() {
            long now = NanosecondTime.getRuntimeMilliseconds();
            return now - lastAt.get() >= intervalMs;
        }

        @Override
        public BufferedImage grab() {
            lastAt.set(NanosecondTime.getRuntimeMilliseconds());
            return frame(produced.incrementAndGet() & 0xff);
        }
    }

    @Test
    public void testRingHoldsTheMostRecentFramesAndForgetstheOldest() {
        FrameBuffer buffer = new FrameBuffer("test", 4);
        for (int i = 1; i <= 6; i++) {
            buffer.push(frame(i), 1000 + i * 10);
        }
        assertEquals(4, buffer.getSize(), "the ring holds its capacity and no more");
        assertEquals(6, greyOf(buffer.newest().image), "the newest is the last one pushed");
        assertEquals(6, buffer.getGrabbedFrames(), "every pushed frame is counted");
        assertEquals(2, buffer.getDroppedFrames(), "the two oldest were dropped to make room");
        // The oldest frame still held is number 3, taken at 1030.
        assertEquals(3, greyOf(buffer.earliestAtOrAfter(0).image));
        assertEquals(6, greyOf(buffer.atOrAfter(0).image));
    }

    @Test
    public void testBoundsAreInclusiveAndNeverLoose() {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        buffer.push(frame(1), 1000);
        buffer.push(frame(2), 1010);
        buffer.push(frame(3), 1020);

        // atOrAfter() answers "the newest frame from this instant on", which is what a capture on demand
        // wants: of everything that qualifies, the freshest is the least stale answer to the question.
        assertEquals(3, greyOf(buffer.atOrAfter(1010).image));
        assertEquals(3, greyOf(buffer.atOrAfter(1011).image));
        assertNull(buffer.atOrAfter(1021), "nothing was taken after that, so nothing is handed out");
        assertEquals(3, greyOf(buffer.atOrAfter(999).image), "a bound in the past gets the newest frame");
    }

    @Test
    public void testEarliestIsNearestTheOpeningOfTheWindow() {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        buffer.push(frame(1), 1000);
        buffer.push(frame(2), 1010);
        buffer.push(frame(3), 1020);

        // A window that opened at 1005: the frame nearest its opening is 1010, and that is the frame the
        // hunt wants, because it has had the least time to drift since the head came to rest.
        assertEquals(2, greyOf(buffer.earliestAtOrAfter(1005).image));
        assertEquals(1, greyOf(buffer.earliestAtOrAfter(1000).image));
        assertNull(buffer.earliestAtOrAfter(1021));
    }

    /**
     * The ring has two ways out and they are not interchangeable, which is why they have different names.
     *
     * Whoever picks a frame out of the past - the second camera's hunt for a frame inside the settling's
     * window - must be handed the picture the camera would have made of it, because the vision pipeline
     * searches where the camera's crop, rotation and flip put the part. Whoever is answering a camera's
     * own raw capture must be handed the frame as the sensor made it, because the transform chain runs
     * above that call and would otherwise run twice.
     *
     * Getting the first one wrong is not a crash: on pn52 a hunt handed Upp1's unrotated frames answered a
     * part that was plainly in the picture with "no rectangle in the pipeline result", which reads as a
     * missing part.
     */
    @Test
    public void testTheFrameHandedToAConsumerIsTransformedAndTheCameraKeepsItsRawOne() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8, image -> frame(42));
        buffer.push(frame(1), 1000);
        buffer.push(frame(2), 1010);
        buffer.push(frame(3), 1020);

        FrameBuffer.Frame handed = buffer.earliestAtOrAfter(1005);
        assertEquals(42, greyOf(handed.image), "the transform did not run on the way out");
        assertEquals(1010, handed.capturedAtMs,
                "transforming a frame does not move the instant it was taken");

        FrameBuffer.Frame awaited = buffer.awaitEarliestAtOrAfter(1005,
                NanosecondTime.getRuntimeMilliseconds()+500);
        assertEquals(42, greyOf(awaited.image), "the transform did not run on the way out");
        assertEquals(1010, awaited.capturedAtMs);

        assertEquals(3, greyOf(buffer.awaitRawAtOrAfter(1005,
                NanosecondTime.getRuntimeMilliseconds()+500).image),
                "a camera's raw capture is answered with the raw frame; its transforms run above this");
        assertEquals(3, greyOf(buffer.atOrAfter(1005).image));
        assertEquals(3, greyOf(buffer.newest().image));
    }

    @Test
    public void testClearRefusesTheFramesOfThePreviousEpoch() {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        buffer.push(frame(1), 1000);
        long epochBefore = buffer.getEpoch();

        buffer.clear();

        assertEquals(0, buffer.getSize());
        assertNull(buffer.newest());
        assertNull(buffer.atOrAfter(0), "a frame of before the head moved is not a frame at all");
        assertEquals(epochBefore + 1, buffer.getEpoch());
    }

    @Test
    public void testAwaitDoesNotWaitWhenNothingIsBeingGrabbed() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        buffer.push(frame(7), NanosecondTime.getRuntimeMilliseconds());

        long t0 = NanosecondTime.getRuntimeMilliseconds();
        // A bound the buffered frame satisfies: it is handed out at once.
        FrameBuffer.Frame frame = buffer.awaitRawAtOrAfter(t0 - 1000, t0 + 5000);
        assertNotNull(frame);
        assertEquals(7, greyOf(frame.image));
        // A bound it does not satisfy, and no session feeding the ring: answer immediately rather than
        // burning the five seconds the caller was prepared to wait. This is what keeps the fallback to
        // capture-on-demand cheap.
        FrameBuffer.Frame none = buffer.awaitRawAtOrAfter(t0 + 1_000_000, t0 + 5000);
        assertNull(none);
        long waited = NanosecondTime.getRuntimeMilliseconds() - t0;
        assertTrue(waited < 500, "answered in " + waited + " ms, not by waiting out the deadline");
    }

    @Test
    public void testAwaitReturnsTheFrameThatArrives() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        // A frame already in the ring, from before the window opened.
        long before = NanosecondTime.getRuntimeMilliseconds();
        buffer.push(frame(1), before);

        // At a cadence, not as fast as it can go: a source that floods the ring evicts the very frames
        // that are nearest the bound, and then the earliest one left is the newest one, which is true of
        // the ring but proves nothing about the waiting.
        CadencedSource source = new CadencedSource(10);
        // The session must be running before the await looks, or the await answers immediately with
        // nothing, which is the correct behaviour for a camera that is not grabbing.
        buffer.startSession(source, "test", 5000);
        long bound = NanosecondTime.getRuntimeMilliseconds() + 20;
        FrameBuffer.Frame frame = buffer.awaitEarliestAtOrAfter(bound,
                NanosecondTime.getRuntimeMilliseconds() + 2000);

        assertNotNull(frame, "the grabbing thread produced one");
        assertTrue(frame.getCapturedAtMs() >= bound, "and not before the bound");
        assertSame(frame, buffer.earliestAtOrAfter(bound),
                "the earliest frame at or after the bound, not the newest");
        assertTrue(frame.getCapturedAtMs() - bound < 100,
                "it came as soon as the bound passed rather than " + (frame.getCapturedAtMs() - bound)
                        + " ms after");
        buffer.endSession("test");
    }

    @Test
    public void testSessionExpiresAndGrabbingStops() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CountingSource source = new CountingSource();
        buffer.startSession(source, "test", 120);
        Thread.sleep(250);
        long asksDuring = source.asks.get();
        assertTrue(asksDuring > 0, "it grabbed while the session lasted");
        buffer.startSession(source, "test", 5000);
        buffer.endSession("test");
        long asksAtEnd = source.asks.get();
        Thread.sleep(200);
        assertEquals(asksAtEnd, source.asks.get(), "and it stopped when the session ended");

        FrameBuffer buffer2 = new FrameBuffer("test2", 8);
        CountingSource source2 = new CountingSource();
        buffer2.startSession(source2, "test", 100);
        Thread.sleep(400);
        long afterExpiry = source2.asks.get();
        Thread.sleep(200);
        assertEquals(afterExpiry, source2.asks.get(), "a session nobody closed expires by itself");
    }

    @Test
    public void testNestedSessionsTakeTheLaterDeadline() throws Exception {
        // There is one session and one deadline, not a counter: a settling that asks for 100 ms inside a
        // camera batch that asked for 500 ms must not stop the grabbing after 100 ms.
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CountingSource source = new CountingSource();
        buffer.startSession(source, "batch", 500);
        long asksAtStart = source.asks.get();
        buffer.startSession(source, "settling", 100);
        Thread.sleep(300);
        assertTrue(source.asks.get() > asksAtStart,
                "the shorter inner request must not cut the longer outer one short");
        buffer.endSession("batch");
        long afterEnd = source.asks.get();
        Thread.sleep(150);
        assertEquals(afterEnd, source.asks.get(), "but ending the session does stop it");
    }

    @Test
    public void testAFailingSourceDoesNotTakeTheBufferDownWithIt() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CountingSource source = new CountingSource();
        source.fail = true;
        buffer.startSession(source, "test", 200);
        Thread.sleep(120);
        assertNotNull(buffer.getLastFailure(), "the failure is visible to the camera");

        // The consumer must not be left waiting: nothing is coming, so it is told so at once.
        long t0 = NanosecondTime.getRuntimeMilliseconds();
        assertNull(buffer.awaitRawAtOrAfter(t0, t0 + 5000));
        assertTrue(NanosecondTime.getRuntimeMilliseconds() - t0 < 500);

        // And when the source works again, so does the buffer, with the failure cleared.
        source.fail = false;
        buffer.push(frame(9), NanosecondTime.getRuntimeMilliseconds());
        assertNull(buffer.getLastFailure());
        assertEquals(9, greyOf(buffer.newest().image));
    }

    @Test
    public void testIntervalEstimateFollowsTheSource() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        assertEquals(0, buffer.getFrameIntervalMs(), "nothing taken yet, nothing to estimate");
        CadencedSource source = new CadencedSource(10);
        buffer.startSession(source, "test", 400);
        Thread.sleep(350);
        buffer.endSession("test");
        long interval = buffer.getFrameIntervalMs();
        assertTrue(interval >= 5 && interval <= 40, "estimated " + interval + " ms for a 10 ms source");
        assertTrue(buffer.getGrabbedFrames() > 10,
                "a 10 ms source over 350 ms gives more than 10 frames, got " + buffer.getGrabbedFrames());
    }

    @Test
    public void testFramesAreAlreadyThereWhenTheMeasurementArrives() throws Exception {
        // The whole point: with the ring being fed, a consumer asking for "a frame from now" is answered
        // out of the ring, and the frame it gets was taken before it asked.
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CadencedSource source = new CadencedSource(8);
        buffer.startSession(source, "test", 3000);
        Thread.sleep(120);
        int worstAge = 0;
        for (int i = 0; i < 20; i++) {
            long t0 = NanosecondTime.getRuntimeMilliseconds();
            FrameBuffer.Frame frame = buffer.awaitRawAtOrAfter(t0, t0 + 500);
            long waited = NanosecondTime.getRuntimeMilliseconds() - t0;
            assertNotNull(frame, "at 8 ms per frame, one is always there within 500 ms");
            assertTrue(frame.getCapturedAtMs() >= t0, "never a frame from before the ask");
            assertTrue(waited <= 20, "waited " + waited + " ms for a frame taken at 8 ms intervals");
            worstAge = (int) Math.max(worstAge, waited);
            Thread.sleep(3);
        }
        buffer.endSession("test");
        assertTrue(buffer.getBestFrameAgeMs() >= 0, "the ages were recorded");
        assertTrue(buffer.getWorstFrameAgeMs() >= buffer.getBestFrameAgeMs());
    }

    @Test
    public void testAFrameIsNotStampedWithTheInstantTheDriverHadIt() throws Exception {
        // The frame that came back a millisecond ago shows the scene from up to a frame interval earlier,
        // which is not the same instant and is not interchangeable with it. On the machine this cost a
        // whole job per part: the frame handed to the pre-check of the settling camera was returned just
        // after the head came to rest, exposed while the light was still being switched on, showed no
        // part at all, and the pre-check reported the part missing.
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CadencedSource source = new CadencedSource(60);
        buffer.startSession(source, "test", 5000);
        long deadline = NanosecondTime.getRuntimeMilliseconds() + 3000;
        // The estimate is a median of the gaps actually seen, so it needs a few of them: read after the
        // first two grabs and you may be reading one gap that happened to be short.
        while ((buffer.getFrameIntervalMs() <= 0 || buffer.getGrabbedFrames() < 4)
                && NanosecondTime.getRuntimeMilliseconds() < deadline) {
            Thread.sleep(5);
        }
        long interval = buffer.getFrameIntervalMs();
        assertTrue(interval >= 40, "a source at 60 ms should report an interval of about that, not "
                +interval);

        long seen = buffer.getGrabbedFrames();
        deadline = NanosecondTime.getRuntimeMilliseconds() + 3000;
        while (buffer.getGrabbedFrames() == seen && NanosecondTime.getRuntimeMilliseconds() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(buffer.getGrabbedFrames() > seen, "the source produced a frame");

        // Asked now, the frame that has just been handed over is not an answer, because what it shows is
        // from before the question, and a frame of the head still on its way is worse than no frame.
        assertNull(buffer.atOrAfter(NanosecondTime.getRuntimeMilliseconds()),
                "a frame returned a moment ago cannot vouch for the instant it arrived at");
        long age = NanosecondTime.getRuntimeMilliseconds() - buffer.newest().getCapturedAtMs();
        assertTrue(age >= interval/2, "a frame is stamped when its pixels happened, an interval before "
                + "the return, but this one is only "+age+" ms old against an interval of "+interval);
        buffer.endSession("test");
    }

    @Test
    public void testConsumersAndProducerDoNotLoseOrDuplicateFrames() throws Exception {
        // Four threads hammering the ring while it is being fed: the ring must stay consistent (no null
        // image, no size beyond capacity, no exception) and every consumer must be served.
        FrameBuffer buffer = new FrameBuffer("test", 6);
        CadencedSource source = new CadencedSource(1);
        buffer.startSession(source, "test", 1500);

        AtomicInteger served = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Thread> consumers = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        for (int c = 0; c < 4; c++) {
            Thread t = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        long t0 = NanosecondTime.getRuntimeMilliseconds();
                        FrameBuffer.Frame frame = buffer.awaitRawAtOrAfter(t0, t0 + 200);
                        if (frame != null && frame.image != null) {
                            served.incrementAndGet();
                        }
                        else {
                            failed.incrementAndGet();
                        }
                    }
                    catch (InterruptedException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            consumers.add(t);
            t.start();
        }
        Thread.sleep(600);
        stop.set(true);
        for (Thread t : consumers) {
            t.join(2000);
        }
        buffer.endSession("test");

        assertTrue(served.get() > 100, "served " + served.get() + " frames");
        assertEquals(0, failed.get(), "with a 1 ms source no consumer should have gone unserved");
        assertTrue(buffer.getSize() <= 6);
        assertTrue(buffer.getGrabbedFrames() > 100, "the producer ran the whole time");
    }

    @Test
    public void testResetStopsTheThreadAndForgetsTheFrames() throws Exception {
        FrameBuffer buffer = new FrameBuffer("test", 8);
        CountingSource source = new CountingSource();
        buffer.startSession(source, "test", 5000);
        Thread.sleep(100);
        buffer.reset();
        assertEquals(0, buffer.getSize());
        assertFalse(buffer.isSessionActive());
        long asks = source.asks.get();
        Thread.sleep(200);
        assertEquals(asks, source.asks.get(), "nothing is grabbed after a reset");
        // And the camera can be started up again afterwards, as it is when a stream is reopened.
        buffer.startSession(source, "test", 200);
        Thread.sleep(120);
        assertTrue(source.asks.get() > asks);
        buffer.reset();
    }

    /**
     * What a capture on demand is answered from, which is the promise the ring was nearly built without:
     * the newest frame held, handed over without waiting, and with whatever bound the consumer actually
     * needs still kept.
     *
     * Asking instead for content from after the instant of the ask cannot be answered from a buffer at
     * all, because a frame is stamped with the earliest instant its content can come from, one frame
     * interval behind the instant it was returned. That is not a tuning mistake to be worked around: it is
     * why a full ring on pn52 answered nothing and turned a 9 ms capture into a 23 ms one. See
     * GrabAheadLatencyRig for the measurement and docs/design/parallel-bottom-vision/GRAB-AHEAD.md for
     * the story.
     */
    @Test
    public void theNewestFrameAnswersACaptureWhileAConsumersBoundIsKept() throws Exception {
        CadencedSource source = new CadencedSource(8);
        FrameBuffer buffer = new FrameBuffer("test", 8, null);
        buffer.startSession(source, "test", 5000);
        try {
            Thread.sleep(5*8);
            assertTrue(source.produced.get() >= 4, "the ring should have been filling");

            long askedAtMs = NanosecondTime.getRuntimeMilliseconds();
            assertNull(buffer.awaitRawAtOrAfter(askedAtMs, askedAtMs+1),
                    "a frame already held should not be able to answer a bound of the instant of the ask");

            FrameBuffer.Frame newest = buffer.awaitNewestForCapture(0, 2*8,
                    NanosecondTime.getRuntimeMilliseconds()+1);
            assertNotNull(newest, "the newest frame held should answer a capture at once");
            assertTrue(askedAtMs-newest.getCapturedAtMs() <= 3*8,
                    "the frame handed over was "+(askedAtMs-newest.getCapturedAtMs())+" ms of content old");

            // A consumer that needs more than that - the head to have arrived, say - is still refused
            // everything earlier, and waits about a frame rather than an exposure for what it asked for.
            long arrivalAtMs = NanosecondTime.getRuntimeMilliseconds()+3*8;
            FrameBuffer.Frame bounded = buffer.awaitNewestForCapture(arrivalAtMs, 2*8,
                    NanosecondTime.getRuntimeMilliseconds()+20*8);
            assertNotNull(bounded, "a bound the camera can meet should still be met");
            assertTrue(bounded.getCapturedAtMs() >= arrivalAtMs,
                    "a frame from before the head arrived was handed to a caller that refused it");
        }
        finally {
            buffer.endSession("test");
        }
    }
}
