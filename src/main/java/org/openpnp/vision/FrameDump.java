package org.openpnp.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.OpenCvUtils;
import org.pmw.tinylog.Logger;

/**
 * Writes captured frames to disk with their provenance in the file name.
 *
 * <p>A dumped frame is only useful as evidence if the analysis of it does not have to guess anything:
 * a PNG of a bottom camera says nothing about which camera it came from, whether the head was still,
 * or how far inside the still window it was taken. So everything
 * {@link FrameProvenance} knows, plus what the replay needs to re-run the pipeline (part, wanted
 * rotation), is encoded in the file name and nothing is kept in a side file that can get lost.</p>
 *
 * <p>The name of a frame is</p>
 *
 * <pre>
 * bv__&lt;origin&gt;__&lt;cameraId&gt;__&lt;nozzleId&gt;__&lt;partId&gt;__f&lt;frameIndex&gt;__a&lt;arrivalMs&gt;__c&lt;capturedMs&gt;__w&lt;windowStartMs&gt;__r&lt;rotation&gt;.png
 * </pre>
 *
 * <p>where the times are milliseconds on the {@link org.openpnp.util.NanosecondTime} scale with the
 * sign written as {@code +} or {@code ~}, a time nobody recorded is {@code na}, a frame nobody
 * counted is {@code fna}, and every identifier has anything outside {@code A-Za-z0-9.-} replaced by
 * {@code -}, because part ids are full of slashes. {@link #parse(String)} reads a name back.</p>
 *
 * <p>Two frames of one alignment can be written, because an alignment keeps two: the frame the hunt
 * finished with, which is the one the part was measured on, and the first frame the camera gave, which
 * is the one the pre-check answered for and which nothing else preserves. The first is the ringing and
 * the second is what came after it; between them is the motion that decides whether a head may leave
 * before the second exists.</p>
 *
 * <p>Turning this on is not free, but it is cheaper than it looks, because what is expensive is done
 * elsewhere: copying the pixels into a {@code BufferedImage} is under a millisecond and has to happen on
 * the capturing thread, since the frame belongs to that thread and stops existing when it returns, while
 * the PNG encoding - forty times the work, tens of milliseconds - and the write happen on a thread of
 * their own at minimum priority. {@code FrameDumpCostTest} measures both on whatever machine runs it. The
 * dump therefore does not lengthen the settling it is watching, though it is still not something to leave
 * enabled while timings are being compared between runs: it writes megabytes per job and touches the same
 * disk the log goes to.</p>
 *
 * <p>{@code -DframeDump=<directory>} turns it on for every vision setting at once, which is what a
 * run meant to produce a corpus needs: the alternative is to open every Bottom Vision setting in the
 * GUI and tick a box, and a corpus missing three of its five parts is not a corpus.
 * {@code -DframeDumpMax=<frames>} stops it after that many frames.</p>
 *
 * <p>{@code -DframeDump=onFailure[:<directory>]} is the same evidence without the cost of a corpus:
 * nothing is written while the parts are being accepted, instead the frames of each alignment are kept
 * in memory - a clone, which is a memcpy, not a PNG - and they are written only when a part is refused.
 * A refusal already costs a sequential re-alignment, so the tens of milliseconds it now also costs to
 * encode four frames cannot be noticed, whereas a corpus written on every cycle touches the disk the
 * log goes to and lengthens the very cycle it is meant to observe. The frames kept are the two an
 * alignment holds anyway, so a refusal leaves behind the frame the pre-check answered for and the frame
 * the alignment measured, which is the before and the after of the settling.</p>
 */
public class FrameDump {

    /**
     * The system property that turns the dump on, and to where. Named like the others the launcher
     * passes ({@code configDir}, {@code backups}), because it is passed on the same command line.
     */
    public static final String PROPERTY = "frameDump";

    /**
     * How many frames one session writes at most, so that a run left running with the dump on cannot
     * fill the disk.
     */
    public static final String MAX_PROPERTY = "frameDumpMax";

    public static final int DEFAULT_MAX_FRAMES = 4000;

    /**
     * The value of {@value #PROPERTY} that keeps frames in memory instead of writing them, so that only
     * a refusal produces files.
     */
    public static final String ON_FAILURE = "onFailure";

    /**
     * How many frames are remembered per {@link #ON_FAILURE} run. A frame of an up-looking camera is a
     * megabyte, so eight of them are eight megabytes, which is a fair price for never losing the
     * evidence of a failure; the oldest goes when the ninth arrives.
     */
    public static final String KEEP_PROPERTY = "frameDumpKeep";

    public static final int DEFAULT_KEEP = 8;

    /**
     * What the frame dump does with a frame.
     */
    public enum Mode {
        /**
         * Nothing at all.
         */
        Off,
        /**
         * Every frame is written as it is captured. What a corpus needs, and what a timing comparison
         * must not use.
         */
        Every,
        /**
         * Frames are cloned into memory and written only when {@link #writeRetained(String)} is called,
         * i.e. when a part is refused. The cost in the accepted case is the clone.
         */
        OnFailure
    }

    /**
     * Frames waiting to be encoded. Small on purpose: a machine that cannot write frames as fast as it
     * takes them is better off losing frames than queues.
     */
    private static final int QUEUE_DEPTH = 64;

    private static final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);

    /**
     * Frames handed to the dump, whether or not they were written.
     */
    private static final AtomicLong offered = new AtomicLong();

    private static final AtomicLong written = new AtomicLong();

    private static final AtomicLong dropped = new AtomicLong();

    private static final AtomicBoolean capSaid = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The frames that have been handed over and have not been written yet, counted from the moment a
     * frame is queued rather than from the moment a writer picks it up, so that waiting for zero really
     * means the corpus is complete and not merely that no writer is holding a frame this instant.
     */
    private static final AtomicInteger pending = new AtomicInteger();

    private static final Object idleMonitor = new Object();

    private static Thread writerThread;

    private static class Job {
        final File file;
        final BufferedImage image;
        final boolean still;

        Job(File file, BufferedImage image, boolean still) {
            this.file = file;
            this.image = image;
            this.still = still;
        }
    }

    public static final String PREFIX = "bv";
    /**
     * Two underscores, not one: part ids on a real job are things like C0402-4n7/50V, so a single
     * dash cannot be both a separator and a character of an identifier.
     */
    public static final String SEPARATOR = "__";
    public static final String SUFFIX = ".png";
    public static final String UNKNOWN = "na";

    /**
     * The file name for a frame, as described in the class comment.
     */
    public static String fileName(FrameProvenance provenance, Camera camera, Nozzle nozzle,
            Part part, double rotation) {
        FrameProvenance p = provenance == null ? FrameProvenance.unknown() : provenance;
        StringBuilder sb = new StringBuilder(PREFIX);
        sb.append(SEPARATOR).append(p.getOrigin().name());
        sb.append(SEPARATOR).append(id(camera == null ? null : camera.getId()));
        sb.append(SEPARATOR).append(id(nozzle == null ? null : nozzle.getId()));
        sb.append(SEPARATOR).append(id(part == null ? null : part.getId()));
        sb.append(SEPARATOR).append("f").append(p.getFrameIndex() > 0
                ? String.valueOf(p.getFrameIndex()) : UNKNOWN);
        sb.append(SEPARATOR).append("a").append(time(p.getArrivalAtMs()));
        sb.append(SEPARATOR).append("c").append(time(p.getCapturedAtMs()));
        sb.append(SEPARATOR).append("w").append(time(p.getStillWindowStartAtMs()));
        sb.append(SEPARATOR).append("r").append(number(rotation));
        return sb.append(SUFFIX).toString();
    }

    /**
     * Read back a file name produced by {@link #fileName}.
     *
     * @throws IllegalArgumentException if the name was not produced by {@link #fileName}, i.e. if it
     *             cannot be trusted as provenance.
     */
    public static Name parse(String fileName) {
        String name = fileName;
        if (name.startsWith("/")) {
            name = new File(name).getName();
        }
        if (!name.startsWith(PREFIX+SEPARATOR) || !name.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("not a frame dump name: "+fileName);
        }
        String[] parts = name.substring(0, name.length()-SUFFIX.length())
                .split(java.util.regex.Pattern.quote(SEPARATOR));
        if (parts.length != 10) {
            throw new IllegalArgumentException("not a frame dump name: "+fileName);
        }
        // Every field carries the letter of the thing it holds, and the letter is insisted upon: a
        // name whose fields have been shuffled would otherwise be read as a frame that is perfectly
        // well described and completely wrong, which is worse than a name that does not read at all.
        return new Name(
                FrameProvenance.Origin.valueOf(require(fileName, parts[1], '\0')),
                parts[2],
                parts[3],
                parts[4],
                index(require(fileName, parts[5], 'f')),
                timeValue(require(fileName, parts[6], 'a')),
                timeValue(require(fileName, parts[7], 'c')),
                timeValue(require(fileName, parts[8], 'w')),
                numberValue(require(fileName, parts[9], 'r')));
    }

    private static String require(String fileName, String field, char letter) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("not a frame dump name: "+fileName);
        }
        if (letter == 0) {
            return field;
        }
        if (field.charAt(0) == letter) {
            return field.substring(1);
        }
        throw new IllegalArgumentException("field "+letter+" missing from frame dump name: "
                +fileName);
    }

    /**
     * A parsed file name. Equal to another one when it describes the same frame.
     */
    public static class Name {
        public final FrameProvenance.Origin origin;
        public final String cameraId;
        public final String nozzleId;
        public final String partId;
        public final int frameIndex;
        public final long arrivalAtMs;
        public final long capturedAtMs;
        public final long stillWindowStartAtMs;
        public final double rotation;

        Name(FrameProvenance.Origin origin, String cameraId, String nozzleId, String partId,
                int frameIndex, long arrivalAtMs, long capturedAtMs, long stillWindowStartAtMs,
                double rotation) {
            this.origin = origin;
            this.cameraId = cameraId;
            this.nozzleId = nozzleId;
            this.partId = partId;
            this.frameIndex = frameIndex;
            this.arrivalAtMs = arrivalAtMs;
            this.capturedAtMs = capturedAtMs;
            this.stillWindowStartAtMs = stillWindowStartAtMs;
            this.rotation = rotation;
        }

        /**
         * The provenance the name carries, as a provenance object.
         */
        public FrameProvenance toProvenance() {
            switch (origin) {
                case Settled:
                    return FrameProvenance.settled(arrivalAtMs, capturedAtMs, stillWindowStartAtMs,
                            frameIndex);
                case Streamed:
                    return FrameProvenance.streamed(arrivalAtMs, capturedAtMs, stillWindowStartAtMs,
                            frameIndex);
                case QuickCapture:
                    return FrameProvenance.quickCapture(arrivalAtMs, capturedAtMs);
                case SettleTimedOut:
                    return FrameProvenance.settleTimedOut(arrivalAtMs, capturedAtMs, frameIndex);
                default:
                    return FrameProvenance.unknown();
            }
        }
    }

    /**
     * The directory frames go to: what {@value #PROPERTY} says, because a command line beats a tick
     * box that has to be found five times, then what the settings say, then a directory of the
     * configuration.
     */
    public static File directory(BottomVisionSettings settings) {
        String override = overrideDirectory();
        if (override != null) {
            return new File(override);
        }
        String configured = settings == null ? null : settings.getFrameDumpDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        return new File(Configuration.get().getConfigurationDirectory(), "vision-frame-dump");
    }

    /**
     * The directory named on the command line, or {@code null} if the command line says nothing - which
     * includes saying only {@code onFailure}, because that says how and not where.
     */
    public static String overrideDirectory() {
        String value = overrideValue();
        if (value == null) {
            return null;
        }
        if (value.equalsIgnoreCase(ON_FAILURE)) {
            return null;
        }
        if (isOnFailure(value)) {
            value = value.substring(ON_FAILURE.length()+1).trim();
            return value.isEmpty() ? null : value;
        }
        return value;
    }

    /**
     * What the frame dump does with the frames it is offered. The command line wins over the settings,
     * and a command line that says {@code onFailure} wins even where the settings say nothing, because
     * the person who wants the evidence of one refusal is exactly the person who does not want to
     * configure five vision settings.
     */
    public static Mode mode(BottomVisionSettings settings) {
        String value = overrideValue();
        if (value != null) {
            return isOnFailure(value) || value.equalsIgnoreCase(ON_FAILURE) ? Mode.OnFailure
                    : Mode.Every;
        }
        return settings != null && settings.isFrameDumpEnabled() ? Mode.Every : Mode.Off;
    }

    /**
     * Whether frames are being dumped at all: either the command line asked for it, or the vision
     * settings did. Also true for {@link Mode#OnFailure}, which does not write until it must.
     */
    public static boolean isEnabled(BottomVisionSettings settings) {
        return mode(settings) != Mode.Off;
    }

    private static String overrideValue() {
        String property = System.getProperty(PROPERTY);
        if (property == null || property.trim().isEmpty()) {
            return null;
        }
        return property.trim();
    }

    private static boolean isOnFailure(String value) {
        return value.regionMatches(true, 0, ON_FAILURE+":", 0, ON_FAILURE.length()+1);
    }

    public static int maxFrames() {
        String property = System.getProperty(MAX_PROPERTY);
        if (property == null || property.trim().isEmpty()) {
            return DEFAULT_MAX_FRAMES;
        }
        try {
            return Integer.parseInt(property.trim());
        }
        catch (NumberFormatException e) {
            Logger.warn("[FRAME-DUMP] -D{} is \"{}\", which is not a number; using {}", MAX_PROPERTY,
                    property, DEFAULT_MAX_FRAMES);
            return DEFAULT_MAX_FRAMES;
        }
    }

    /**
     * How many frames were asked for, how many were written and how many were lost, for whoever wants
     * to know whether the corpus they are about to analyse is the corpus that was taken. Only complete
     * after {@link #awaitIdle(long)}.
     */
    public static long getFramesOffered() {
        return offered.get();
    }

    public static long getFramesWritten() {
        return written.get();
    }

    public static long getFramesDropped() {
        return dropped.get();
    }

    /**
     * Write one frame if the settings ask for it. Never throws and never fails a capture: a dump that
     * cannot be written is a lost piece of evidence, not a reason to stop placing parts. The reason is
     * said once, because a machine that cannot write a dump will not fail on one frame alone.
     *
     * <p>The pixels are copied here rather than on the writing thread, because the {@code Mat} is the
     * capture's and is released when this returns; the expensive part, which is the PNG encoding, goes
     * to the writing thread.</p>
     */
    public static void writeIfNeeded(BottomVisionSettings settings, Mat frame,
            FrameProvenance provenance, Camera camera, Nozzle nozzle, Part part, double rotation) {
        Mode mode = isEnabled(settings) ? mode(settings) : Mode.Off;
        if (mode == Mode.Off || frame == null || frame.empty()) {
            return;
        }
        if (mode == Mode.OnFailure) {
            retain(settings, frame, provenance, camera, nozzle, part, rotation);
            return;
        }
        if (offered.incrementAndGet() > maxFrames()) {
            if (capSaid.compareAndSet(false, true)) {
                Logger.warn("[FRAME-DUMP] stopped after {} frames; -D{}={} if that was not enough",
                        maxFrames(), MAX_PROPERTY, maxFrames());
            }
            return;
        }
        File directory = directory(settings);
        File file = new File(directory, fileName(provenance, camera, nozzle, part, rotation));
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("cannot create "+directory);
            }
            BufferedImage image = OpenCvUtils.toBufferedImage(frame);
            if (!queue.offer(new Job(file, image, provenance != null && provenance.isStill()))) {
                long lost = dropped.incrementAndGet();
                if (lost == 1 || lost % 100 == 0) {
                    Logger.warn("[FRAME-DUMP] the writer is {} frames behind, {} frames have been "
                            + "dropped", QUEUE_DEPTH, lost);
                }
                return;
            }
            if (offered.get() == 1) {
                Logger.info("[FRAME-DUMP] frames go to {}{}", directory.getAbsolutePath(),
                        overrideDirectory() == null ? "" : " because -D"+PROPERTY+" is set");
            }
            pending.incrementAndGet();
            ensureWriter();
        }
        catch (Exception e) {
            Logger.warn("[FRAME-DUMP] cannot write {}: {}", file.getName(), e.getMessage());
        }
    }

    /**
     * A frame that has been remembered rather than written.
     */
    private static class Retained {
        final Mat image;
        final File file;

        Retained(Mat image, File file) {
            this.image = image;
            this.file = file;
        }
    }

    private static final java.util.Deque<Retained> retained = new java.util.ArrayDeque<>();

    private static boolean keepSaid;

    /**
     * Remember one frame in memory, in case it turns out to have been the last picture of a part that
     * was later refused. Only the clone is paid for here - a megabyte copied, not a PNG encoded - which
     * is what makes it affordable to do on every alignment instead of only on the ones that fail.
     *
     * <p>Never throws: a dump that cannot be remembered is a lost piece of evidence, not a reason to
     * stop placing parts.</p>
     */
    public static void retain(BottomVisionSettings settings, Mat frame, FrameProvenance provenance,
            Camera camera, Nozzle nozzle, Part part, double rotation) {
        if (frame == null || frame.empty()) {
            return;
        }
        try {
            File directory = directory(settings);
            File file = new File(directory,
                    fileName(provenance, camera, nozzle, part, rotation));
            int max = keepMax();
            synchronized (retained) {
                while (retained.size() >= max) {
                    Retained oldest = retained.removeFirst();
                    oldest.image.release();
                    if (!keepSaid) {
                        keepSaid = true;
                        Logger.info("[FRAME-DUMP] remembering only the last {} frames; -D{}=<n> for more",
                                max, KEEP_PROPERTY);
                    }
                }
                retained.addLast(new Retained(frame.clone(), file));
            }
            if (!keepSaid) {
                Logger.info("[FRAME-DUMP] keeping frames in memory, nothing written unless a part is "
                        + "refused; would go to {}", directory.getAbsolutePath());
            }
        }
        catch (Exception e) {
            Logger.warn("[FRAME-DUMP] cannot keep a frame: {}", e.getMessage());
        }
    }

    /**
     * Write every remembered frame and forget it. Called when a part has been refused, because that is
     * the moment the frames become evidence - and the moment when the cycle has already been lost to
     * the re-alignment, so paying for the encoding costs nothing anybody can notice.
     *
     * @param reason what the refusal said, so that the log line that wrote the frames can be found again
     *            from the files and the other way round.
     * @return how many frames were written.
     */
    public static int writeRetained(String reason) {
        java.util.List<Retained> frames = new java.util.ArrayList<>();
        synchronized (retained) {
            Retained frame;
            while ((frame = retained.pollFirst()) != null) {
                frames.add(frame);
            }
        }
        if (frames.isEmpty()) {
            return 0;
        }
        int queued = 0;
        for (Retained frame : frames) {
            try {
                if (offered.incrementAndGet() > maxFrames()) {
                    break;
                }
                File directory = frame.file.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("cannot create "+directory);
                }
                BufferedImage image = OpenCvUtils.toBufferedImage(frame.image);
                if (!queue.offer(new Job(frame.file, image, false))) {
                    dropped.incrementAndGet();
                    continue;
                }
                pending.incrementAndGet();
                queued++;
                ensureWriter();
            }
            catch (Exception e) {
                Logger.warn("[FRAME-DUMP] cannot write {}: {}", frame.file.getName(), e.getMessage());
            }
            finally {
                frame.image.release();
            }
        }
        Logger.info("[FRAME-DUMP] wrote {} remembered frame(s) to {} because: {}", queued,
                frames.get(0).file.getParentFile().getAbsolutePath(), reason);
        return queued;
    }

    /**
     * Forget the remembered frames without writing them, e.g. when a run is over.
     */
    public static void releaseRetained() {
        synchronized (retained) {
            Retained frame;
            while ((frame = retained.pollFirst()) != null) {
                frame.image.release();
            }
        }
    }

    public static int getFramesRetained() {
        synchronized (retained) {
            return retained.size();
        }
    }

    public static int keepMax() {
        String property = System.getProperty(KEEP_PROPERTY);
        if (property == null || property.trim().isEmpty()) {
            return DEFAULT_KEEP;
        }
        try {
            return Math.max(1, Integer.parseInt(property.trim()));
        }
        catch (NumberFormatException e) {
            Logger.warn("[FRAME-DUMP] -D{} is \"{}\", which is not a number; using {}", KEEP_PROPERTY,
                    property, DEFAULT_KEEP);
            return DEFAULT_KEEP;
        }
    }

    private static void ensureWriter() {
        synchronized (FrameDump.class) {
            if (writerThread == null) {
                writerThread = new Thread(FrameDump::drain, "frame-dump");
                writerThread.setDaemon(true);
                writerThread.setPriority(Thread.MIN_PRIORITY);
                writerThread.start();
            }
        }
    }

    private static void drain() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            }
            catch (InterruptedException e) {
                return;
            }
            try {
                if (!ImageIO.write(job.image, "png", job.file)) {
                    throw new IOException("no png writer available");
                }
                written.incrementAndGet();
                Logger.info("[FRAME-DUMP] {} still={}", job.file.getName(), job.still);
            }
            catch (Exception e) {
                Logger.warn("[FRAME-DUMP] cannot write {}: {}", job.file.getName(), e.getMessage());
            }
            finally {
                if (pending.decrementAndGet() <= 0) {
                    synchronized (idleMonitor) {
                        idleMonitor.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * Wait until every frame handed over has been written. Only a corpus that is known to be complete
     * can be copied away, and a directory being written to says nothing about whether it is finished.
     *
     * @return {@code false} if the timeout passed with frames still to write.
     */
    public static boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis()+timeoutMs;
        synchronized (idleMonitor) {
            while (pending.get() > 0) {
                long left = deadline-System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    idleMonitor.wait(left);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private static String id(String id) {
        if (id == null || id.isEmpty()) {
            return UNKNOWN;
        }
        return id.replaceAll("[^A-Za-z0-9.-]", "-");
    }

    private static String time(long value) {
        if (value == FrameProvenance.UNKNOWN) {
            return UNKNOWN;
        }
        return number(value);
    }

    /**
     * A number with its sign written as a character, so that a negative number cannot be mistaken for
     * the separator between two fields.
     */
    private static String number(double value) {
        if (Double.isNaN(value)) {
            return UNKNOWN;
        }
        // The name of a dumped frame is evidence, so it has to read the same on every machine: the
        // decimal separator is pinned to a dot, never the one the JVM's format locale happens to use.
        return (value < 0 ? "~" : "+")+String.format(Locale.US, "%.1f", Math.abs(value));
    }

    private static int index(String field) {
        if (UNKNOWN.equals(field)) {
            return -1;
        }
        return Integer.parseInt(field);
    }

    private static long timeValue(String field) {
        double value = numberValue(field);
        // A time that was never known has to come back as never known. Cast to a long, a NaN becomes
        // zero, and zero is a timestamp like any other: the frame would suddenly look like it was
        // captured at the epoch, which is a thing someone could act on.
        return Double.isNaN(value) ? FrameProvenance.UNKNOWN : (long) value;
    }

    private static double numberValue(String field) {
        if (UNKNOWN.equals(field)) {
            return Double.NaN;
        }
        return Double.parseDouble(field.replace('~', '-'));
    }
}
