import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.FrameBuffer;
import org.openpnp.util.NanosecondTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An offline re-enactment of what grabbing ahead cost on pn52, so that the lesson is not learned a third
 * time on the machine.
 *
 * The machine said this: a ring that had been filling for the whole time the head travelled answered
 * nothing, and every capture got slower. The reason is arithmetic rather than tuning. A frame handed to
 * the ring is stamped with the earliest instant its content can come from, which for a camera that cannot
 * time its own exposure is the instant it was returned less one frame interval. Ask the ring - as
 * {@code captureFromRing()} did - for a frame whose content lies at or after the instant of the ask, and
 * no frame already in the ring can qualify, nor can any frame that will be in it a moment later: the
 * answer is necessarily a frame the camera has not taken yet. The buffer is therefore always full and
 * always useless, and what a capture costs is a whole frame interval instead of the half interval the
 * driver used to take. Measured: 9 ms became 23 ms on the 8 ms up cameras, 15 ms became 33-99 ms on the
 * 33 ms fiducial camera, and a job's fiducial checks cost 1.2 s.
 *
 * What a buffer is worth is the opposite question: give me the newest frame you hold and tell me honestly
 * when it looked at the subject. The staleness that buys is exactly the staleness capture on demand always
 * had (a frame returned at T shows the subject from up to one interval before T); the correctness that
 * really matters - never judge a frame exposed before the head arrived, which is the bug this whole line
 * of work started with - is a bound only the consumer knows, so the consumer says it.
 *
 * Everything here is off the machine. The "driver" is a cadence and a copy under a lock; the frames are
 * small (a fifth of the machine's 3 MB, enough to keep the shape of the contention without needing
 * gigabytes of heap), so where the rig reproduces a cost, the machine's is larger.
 */
public class GrabAheadLatencyRig {

    /** Measured on pn52 for Upp1 and Upp2 while grabbing ahead. */
    private static final int UP_INTERVAL_MS = 8;

    /** Measured on pn52 for the Down Locking camera. */
    private static final int DOWN_INTERVAL_MS = 33;

    /** About a fifth of a 960x1080 frame, which is what the machine copies per frame, twice, under one lock. */
    private static final int WIDTH = 320;

    private static final int HEIGHT = 600;

    /**
     * Where the measured numbers are written down, so that a run of this rig leaves something to read
     * afterwards rather than only a pass or a fail. Surefire swallows what a test prints.
     */
    private static final java.io.File REPORT = new java.io.File("target/grab-ahead-rig-report.txt");

    /**
     * A re-enactment of {@code org.openpnp.capture.CaptureStream} as the machine sees it: frames appear on
     * a cadence, and taking one means copying a whole frame into one shared buffer and the whole of that
     * out again, both inside one monitor, which is what {@code CaptureStream.capture()} does.
     */
    private static class SimulatedStream implements FrameBuffer.Source {
        private final long intervalMs;

        private final byte[] shared = new byte[WIDTH*HEIGHT*3];

        private volatile long lastFrameAtMs;

        final AtomicInteger copies = new AtomicInteger();

        SimulatedStream(long intervalMs) {
            this.intervalMs = intervalMs;
            this.lastFrameAtMs = NanosecondTime.getRuntimeMilliseconds() - intervalMs;
        }

        @Override
        public boolean hasNewFrame() {
            return NanosecondTime.getRuntimeMilliseconds()-lastFrameAtMs >= intervalMs;
        }

        @Override
        public BufferedImage grab() {
            byte[] decoded = new byte[shared.length];
            synchronized (shared) {
                System.arraycopy(decoded, 0, shared, 0, shared.length);
                byte[] out = new byte[shared.length];
                System.arraycopy(shared, 0, out, 0, shared.length);
                copies.incrementAndGet();
                lastFrameAtMs = NanosecondTime.getRuntimeMilliseconds();
                BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
                image.getRaster().setDataElements(0, 0, WIDTH, HEIGHT, out);
                return image;
            }
        }

        @Override
        public long getCapturedAtMs() {
            // Like the driver: no idea when the pixels were gathered. The ring subtracts a frame interval,
            // which is what makes a held frame look older than it is, and hence what a bound of "now" can
            // never be answered from.
            return 0;
        }
    }

    /**
     * Start a ring and let it fill for a while, as it does while the head is on its way to the cameras.
     */
    private static FrameBuffer filling(SimulatedStream stream, int ms) throws InterruptedException {
        FrameBuffer buffer = new FrameBuffer("rig", 8, null);
        buffer.startSession(stream, "rig", 5000);
        Thread.sleep(ms);
        return buffer;
    }

    /**
     * How long a capture will wait for the ring before capturing on demand, as
     * {@code OpenPnpCaptureCamera.captureFromRing()} does.
     */
    private static long deadlineAfter(FrameBuffer buffer, long fromMs) {
        return fromMs + Math.min(Math.max(20, 3*Math.max(4, buffer.getFrameIntervalMs())), 250);
    }

    /**
     * The policy pn52 ran with: a frame whose content is from at or after the instant of this call.
     */
    private static long captureAskingForNow(FrameBuffer buffer, long consumerNotBeforeMs)
            throws InterruptedException {
        long askedAtMs = NanosecondTime.getRuntimeMilliseconds();
        FrameBuffer.Frame frame = buffer.awaitRawAtOrAfter(askedAtMs, deadlineAfter(buffer, askedAtMs));
        assertNotNull(frame, "the ring stopped answering altogether");
        return NanosecondTime.getRuntimeMilliseconds()-askedAtMs;
    }

    /**
     * The policy that replaced it: the newest frame held, provided its content is neither older than what
     * the consumer demands nor older than the pixels a capture on demand would have delivered anyway.
     */
    private static long captureAskingForNewest(FrameBuffer buffer, long consumerNotBeforeMs)
            throws InterruptedException {
        long askedAtMs = NanosecondTime.getRuntimeMilliseconds();
        FrameBuffer.Frame frame = buffer.awaitNewestForCapture(consumerNotBeforeMs,
                2*Math.max(4, buffer.getFrameIntervalMs()), deadlineAfter(buffer, askedAtMs));
        assertNotNull(frame, "the ring stopped answering altogether");
        return NanosecondTime.getRuntimeMilliseconds()-askedAtMs;
    }

    /**
     * What a settling costs: three frames taken one after another, which is what
     * {@code AbstractSettlingCamera} asked for on pn52 ({@code frame=1/3} in every pre-check of every run).
     */
    private static List<Long> settling(FrameBuffer buffer, boolean askForNow, long consumerNotBeforeMs)
            throws InterruptedException {
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waits.add(askForNow ? captureAskingForNow(buffer, consumerNotBeforeMs)
                    : captureAskingForNewest(buffer, consumerNotBeforeMs));
        }
        return waits;
    }

    private static double median(List<Long> waits) {
        List<Long> sorted = new ArrayList<>(waits);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size()/2);
    }

    @Test
    public void aFrameOfTheInstantOfTheAskIsNotInABufferAtAll() throws Exception {
        SimulatedStream stream = new SimulatedStream(UP_INTERVAL_MS);
        FrameBuffer buffer = filling(stream, 5*UP_INTERVAL_MS);
        try {
            // The ring is full of frames and has measured its own rate.
            assertTrue(buffer.isSessionActive());
            assertTrue(stream.copies.get() >= 4, "the ring should have been filling, took "+stream.copies.get());
            assertTrue(buffer.getFrameIntervalMs() >= 4);

            // None of it can answer a capture that asks for a frame of now, and no wait of one interval
            // will produce one either: content stamped behind the return time cannot be brought forward.
            long now = NanosecondTime.getRuntimeMilliseconds();
            assertNull(buffer.awaitRawAtOrAfter(now, now+1),
                    "a held frame satisfied a bound of the instant of the ask, which the machine says cannot happen");

            // It answers a capture that asks for the newest frame it has, which is what a capture on
            // demand used to hand over anyway - and this time with an honest stamp on it.
            FrameBuffer.Frame newest = buffer.awaitNewestForCapture(0, 2*Math.max(4,
                    buffer.getFrameIntervalMs()), NanosecondTime.getRuntimeMilliseconds()+1);
            assertNotNull(newest, "the newest frame held should answer at once");
            assertTrue(now-newest.getCapturedAtMs() <= 3L*Math.max(4, buffer.getFrameIntervalMs()),
                    "the frame handed over was "+(now-newest.getCapturedAtMs())+" ms of content old");
        }
        finally {
            buffer.endSession("rig done");
        }
    }

    @Test
    public void aBoundOfTheAskMakesEveryFrameOfASettlingCostOneInterval() throws Exception {
        SimulatedStream stream = new SimulatedStream(UP_INTERVAL_MS);
        FrameBuffer buffer = filling(stream, 5*UP_INTERVAL_MS);
        try {
            List<Long> askedForNow = settling(buffer, true, 0);
            List<Long> askedForNewest = settling(buffer, false, 0);

            report("settling of 3 frames on an "+UP_INTERVAL_MS+" ms stream", askedForNow, askedForNewest);

            assertTrue(median(askedForNow) >= UP_INTERVAL_MS/2,
                    "asking for a frame of now should have cost about a frame interval, cost "+median(askedForNow)
                            +" ms (median of "+askedForNow+"); if the rig has become too fast to show the"
                            + " effect, raise the interval rather than believe the problem has gone");
            assertTrue(median(askedForNewest) < median(askedForNow),
                    "asking for the newest frame should be cheaper than asking for a frame of now: "
                            +askedForNewest+" against "+askedForNow);
            assertTrue(median(askedForNewest) <= UP_INTERVAL_MS,
                    "the first frame of a settling should come out of the ring inside a frame interval,"
                            + " it took "+median(askedForNewest)+" ms (of "+askedForNewest+")");
        }
        finally {
            buffer.endSession("rig done");
        }
    }

    @Test
    public void aBoundFromTheConsumerStillRefusesTheFrameBeforeTheArrival() throws Exception {
        SimulatedStream stream = new SimulatedStream(UP_INTERVAL_MS);
        FrameBuffer buffer = filling(stream, 5*UP_INTERVAL_MS);
        try {
            // The head has not arrived yet and the bound says frames must show it after the arrival, which
            // is the promise the pre-check needs: a frame of the head on its way, or of the field before
            // the light came on, is answered "part missing" and is worse than no frame at all.
            long arrivalAtMs = NanosecondTime.getRuntimeMilliseconds()+UP_INTERVAL_MS;
            long waited = captureAskingForNewest(buffer, arrivalAtMs);
            long now = NanosecondTime.getRuntimeMilliseconds();
            assertTrue(now >= arrivalAtMs-UP_INTERVAL_MS,
                    "the capture was answered before the head could have arrived");
            assertTrue(waited >= UP_INTERVAL_MS/2,
                    "a bound the ring cannot already satisfy should cost about a frame interval, not "+waited);

            // Once the head has been there for a few intervals, the same bound answers at once: it is the
            // instant of the ask that made the buffer useless, not the having of a bound.
            Thread.sleep(3*UP_INTERVAL_MS);
            long answeredImmediately = captureAskingForNewest(buffer, arrivalAtMs);
            assertTrue(answeredImmediately <= UP_INTERVAL_MS,
                    "a bound the ring has been satisfying for two intervals should answer at once, took "
                            +answeredImmediately+" ms");
        }
        finally {
            buffer.endSession("rig done");
        }
    }

    @Test
    public void aBatchOfThemIsWhatTheJobPaysFor() throws Exception {
        // Three cameras, as on the machine: two up cameras at ~8 ms and a fiducial camera at ~33 ms, all
        // filling while the head travels, all asked from afterwards.
        SimulatedStream up1 = new SimulatedStream(UP_INTERVAL_MS);
        SimulatedStream up2 = new SimulatedStream(UP_INTERVAL_MS);
        SimulatedStream down = new SimulatedStream(DOWN_INTERVAL_MS);

        for (boolean askForNow : new boolean[] {true, false}) {
            FrameBuffer ringUp1 = filling(up1, 4*UP_INTERVAL_MS);
            FrameBuffer ringUp2 = filling(up2, 4*UP_INTERVAL_MS);
            FrameBuffer ringDown = filling(down, 2*DOWN_INTERVAL_MS);
            long total = 0;
            try {
                for (int batch = 0; batch < 4; batch++) {
                    Thread.sleep(UP_INTERVAL_MS);
                    long arrivalAtMs = NanosecondTime.getRuntimeMilliseconds();
                    // The settling camera and the pre-check on it, then the fiducial-like checks of the
                    // same phase, which ask eight times on a camera three times slower.
                    total += settling(ringUp2, askForNow, askForNow ? 0 : arrivalAtMs).stream()
                            .mapToLong(Long::longValue).sum();
                    for (int i = 0; i < 8; i++) {
                        total += askForNow ? captureAskingForNow(ringDown, 0)
                                : captureAskingForNewest(ringDown, 0);
                        Thread.sleep(DOWN_INTERVAL_MS/2);
                    }
                    // The other camera's hunt for a frame inside a window is the one thing the ring was
                    // always good at: it takes a frame out of the past and pays nothing for it.
                    long windowOpenedAtMs = arrivalAtMs;
                    FrameBuffer.Frame hunted = ringUp1.awaitEarliestAtOrAfter(windowOpenedAtMs,
                            NanosecondTime.getRuntimeMilliseconds()+3*UP_INTERVAL_MS);
                    assertNotNull(hunted, "the hunt found no frame inside the still window");
                }
            }
            finally {
                ringUp1.endSession("rig done");
                ringUp2.endSession("rig done");
                ringDown.endSession("rig done");
            }
            report(askForNow ? "4 batches, asking for a frame of now"
                    : "4 batches, asking for the newest frame", List.of(total), List.of(total));
            if (askForNow) {
                strictTotalMs = total;
            }
            else {
                assertTrue(total < strictTotalMs,
                        "asking for the newest frame should be cheaper over a batch than asking for a frame"
                                + " of now: "+total+" ms against "+strictTotalMs+" ms");
            }
        }
    }

    private long strictTotalMs;

    private static void report(String what, List<Long> strict, List<Long> relaxed) {
        String line = String.format("%-46s strict now: %6.1f ms median, newest: %6.1f ms median%n",
                what, median(strict), median(relaxed));
        System.out.print(line);
        try {
            REPORT.getParentFile().mkdirs();
            try (java.io.FileWriter out = new java.io.FileWriter(REPORT, true)) {
                out.write(line);
            }
        }
        catch (java.io.IOException e) {
            // A report that cannot be written is not a reason to fail a measurement.
        }
    }
}
