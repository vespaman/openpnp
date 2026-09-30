package org.openpnp.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.OpenCvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Turning this on is not free: encoding and writing a frame takes tens of milliseconds, which is
 * of the same order as the settling it is meant to observe. It exists to produce evidence for
 * offline analysis, not to be left enabled while timings are being measured.</p>
 */
public class FrameDump {

    private static final Logger logger = LoggerFactory.getLogger(FrameDump.class);

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
     * The directory frames go to: what the settings say, or a directory of the configuration if the
     * settings give nothing.
     */
    public static File directory(BottomVisionSettings settings) {
        String configured = settings == null ? null : settings.getFrameDumpDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        return new File(Configuration.get().getConfigurationDirectory(), "vision-frame-dump");
    }

    /**
     * Write one frame if the settings ask for it. Never throws and never fails a capture: a dump that
     * cannot be written is a lost piece of evidence, not a reason to stop placing parts. The reason is
     * said once, because a machine that cannot write a dump will not fail on one frame alone.
     */
    public static void writeIfNeeded(BottomVisionSettings settings, Mat frame,
            FrameProvenance provenance, Camera camera, Nozzle nozzle, Part part, double rotation) {
        if (settings == null || !settings.isFrameDumpEnabled() || frame == null || frame.empty()) {
            return;
        }
        File file = new File(directory(settings), fileName(provenance, camera, nozzle, part,
                rotation));
        try {
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
                throw new IOException("cannot create "+file.getParentFile());
            }
            BufferedImage image = OpenCvUtils.toBufferedImage(frame);
            if (!ImageIO.write(image, "png", file)) {
                throw new IOException("no png writer available");
            }
            logger.info("[FRAME-DUMP] {} still={}", file.getName(), provenance != null
                    && provenance.isStill());
        }
        catch (Exception e) {
            logger.warn("[FRAME-DUMP] cannot write {}: {}", file.getName(), e.getMessage());
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
        return (value < 0 ? "~" : "+")+String.format("%.1f", Math.abs(value));
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
