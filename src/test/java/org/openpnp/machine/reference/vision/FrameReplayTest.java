package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.imgcodecs.Imgcodecs;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.CapturedShot;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.FrameDump;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.openpnp.vision.pipeline.CvPipeline.PipelineShot;

/**
 * The frames the machine kept, measured again somewhere else.
 *
 * The parallel path makes a promise that cannot be checked on the machine it runs on: that a part was
 * measured on a frame which shows the head stood still, and that the measurement the machine reports is
 * the one that picture contains. FrameDump exists so a frame can leave the machine with its provenance
 * written into its name. This test exists so the second half can be checked by somebody who was not
 * there: it loads the machine's own configuration, feeds each kept frame to that part's own pipeline -
 * the same preparePipeline() call with the same crop, the same colour masks, the same parameter
 * assignments the alignment used - and asks whether the rectangle that comes out is the rectangle the
 * log says came out.
 *
 * The log does say it:
 *
 * <pre>
 * Bottom vision part C0402-4n7/50V result rect { {97.96086120605469, 97.51771545410156} 36x18 * 2.2025983333587646 }
 * </pre>
 *
 * The centre and the angle of that line are OpenCV's own numbers, unrounded, and a replayed rectangle
 * either is that rectangle - to the fifteenth decimal of a coordinate nobody could guess - or the frame
 * and the configuration do not after all contain the whole of the measurement. Two things about the line
 * keep the comparison from being literally exact, and both are noted where they are handled: the sides
 * are printed by OpenCV's toString() with their decimals dropped, and OpenCV has no opinion about which
 * side of a rectangle it calls the width, so 37x18 at 90 degrees is the same rectangle as 18x37 at 0.
 *
 * That is the load-bearing wall of everything planned on top of it. Departing the head before the
 * analysis is finished, re-judging a frame, choosing between frames by their provenance: all of it is
 * safe only if a frame is a complete account of what was seen. If a frame plus the configuration failed
 * to reproduce the machine's reading, then the analysis depends on some state the frame does not carry,
 * and no cleverness about scheduling would be worth applying on top of that.
 *
 * The pre-check numbers are read too, but only to be labelled for what they are. A pre-check measures
 * the first frame of the hunt (its log line says frame=1/4), while the frame that was kept - and
 * therefore the frame that was dumped and is replayed here - is the one the hunt ended on, f4 in that
 * same batch. The two are the same part lying nearly still, and how far apart the two readings are is
 * precisely the drift the stillness of the kept frame is supposed to have excluded, so the difference is
 * printed as a measurement of that and not as a failure of the replay.
 *
 * It reads the machine as it was configured on the day of the run, not a simulation of one. Without a
 * kept run it says nothing and skips: a green assertion about a machine nobody has is worse than no test.
 */
public class FrameReplayTest {

    /**
     * Runs kept under tests_YYYY-MM-DD together with the configuration they ran with: bv-corpus with the
     * frames, log_q.txt with what the machine said about them. A corpus too big or too private to commit
     * is replayed by naming its directory instead: -Dreplay.dir=/path/to/the/run.
     */
    private static final String[][] KEPT_RUNS = {
        { "tests_2026-10-01", "bv-corpus", "log_q.txt" },
        { "tests_2026-10-01", "bv-corpus-r", "log_r.txt" },
    };

    /** The rectangle as the alignment logged it, in pixels of the image it was found in. */
    private static final Pattern RECT = Pattern.compile(
            "Bottom vision part (\\S+) result rect \\{ \\{([-0-9.E]+), ([-0-9.E]+)\\} "
            + "([0-9.]+)x([0-9.]+) \\* ([-0-9.E]+) \\}");

    private static final Pattern PRE_CHECK = Pattern.compile(
            "\\[PRE-CHECK(?:-LATE)?\\] nozzle=(\\S+) camera=(\\S+) part=(\\S+) frame=(\\S+)"
            + ".*?size=([0-9.]+)x([0-9.]+).*?rot=(-?[0-9.]+|\\?) offset=([0-9.]+|\\?)"
            + ".*?verdict=(\\w+)");

    /** A centre coordinate is 17 significant digits in the log; anything looser is not the same point. */
    private static final double PIXEL_TOLERANCE = 1e-6;

    /** A frame as it lies on disk, and what its name says about it. */
    private static class Kept {
        File file;
        FrameDump.Name name;
    }

    /** The machine's own reading of one frame: the rectangle, and the size it was judged to be. */
    private static class Measured {
        double centerX;
        double centerY;
        double width;
        double height;
        double angle;
        boolean matched;

        boolean sameRectAs(RotatedRect rect) {
            if (Math.abs(centerX - rect.center.x) > PIXEL_TOLERANCE
                    || Math.abs(centerY - rect.center.y) > PIXEL_TOLERANCE) {
                return false;
            }
            // OpenCV has no opinion about which side of a rectangle it calls the width: the same four
            // corners come back as 37x18 at 90 degrees or as 18x37 at 0, and the pipeline of one part can
            // hand back either depending on nothing a caller can see. Both readings are the same
            // rectangle, and the centre above is what says so.
            return (sameSizeAs(rect) && Math.abs(Math.IEEEremainder(angle - rect.angle, 180.))
                    < PIXEL_TOLERANCE)
                    || (sameSidesTurnedAs(rect) && Math.abs(Math.IEEEremainder(angle - rect.angle
                    + 90., 180.)) < PIXEL_TOLERANCE);
        }

        private boolean sameSidesTurnedAs(RotatedRect rect) {
            return Math.abs(rect.size.height - width) < 1.0
                    && Math.abs(rect.size.width - height) < 1.0;
        }

        private boolean sameSizeAs(RotatedRect rect) {
            // The log writes the rectangle through OpenCV's toString(), which prints the sides with their
            // decimals dropped. The centre and the angle come through at full precision, so it is those
            // that prove the two rectangles are the one rectangle; the sides can only be required to be
            // within a pixel of what the log has room to say.
            return Math.abs(rect.size.width - width) < 1.0
                    && Math.abs(rect.size.height - height) < 1.0;
        }
    }

    /** What the pre-check said, on the frame it actually measured: the first of the hunt. */
    private static class PreChecked {
        String nozzleId;
        String partId;
        int grabCount;
        double width;
        double height;
        double rotation;
        double offset;
    }

    @Test
    public void framesReplayedAwayFromTheMachineMeasureWhatTheMachineMeasured() throws Exception {
        String measure = System.getProperty("replay.measure");
        if (measure != null && !measure.trim().isEmpty()) {
            measureEveryFrame(new File(measure.trim()));
            return;
        }
        for (String[] run : runsToReplay()) {
            File directory = new File(run[0]);
            File corpus = new File(directory, run[1]);
            File log = new File(directory, run[2]);
            Assumptions.assumeTrue(new File(directory, "machine.xml").isFile()
                    && corpus.isDirectory() && log.isFile(),
                    "the kept run "+run+" (its configuration, its corpus and its log) is not here");

            List<Kept> frames = readFrames(corpus);
            assertFalse(frames.isEmpty(), "no file in "+corpus+" is a frame dump name");
            List<Measured> measured = readMeasured(log);
            assertFalse(measured.isEmpty(), "the log records no rectangle measured by an alignment");
            List<PreChecked> preChecked = readPreChecks(log);

            Configuration.initialize(directory);
            Configuration.get().load();
            replayAll(run[0]+"/"+run[1], frames, measured, preChecked);
        }
    }

    /**
     * A run to replay is a directory holding a machine's configuration, a corpus of frames inside it and
     * the log of the session that made them. -Dreplay.dir names another such directory to replay instead
     * of the ones kept in git; -Dreplay.corpus and -Dreplay.log say what its corpus and log are called
     * when they are not the usual ones.
     */
    private String[][] runsToReplay() {
        String named = System.getProperty("replay.dir");
        if (named == null || named.trim().isEmpty()) {
            return KEPT_RUNS;
        }
        return new String[][] {
            {
                named.trim(),
                System.getProperty("replay.corpus", "bv-corpus").trim(),
                System.getProperty("replay.log", "log_q.txt").trim(),
            }
        };
    }

    /**
     * Frames belong to their alignments by the instant they arrived at; the machine's readings belong to
     * them in the order they were logged, two per alignment because the machine has two nozzles and both
     * were aligned in parallel. The pairing across a batch deliberately does not care which of the two
     * readings belonged to which nozzle - each replayed rectangle must match one reading of its batch
     * exactly, and no reading may be matched twice. A rectangle with a centre quoted to seventeen digits
     * is not something a wrong pairing can hit by luck.
     */
    private void replayAll(String run, List<Kept> frames, List<Measured> measured,
            List<PreChecked> preChecked) throws Exception {
        // Only a frame the alignment ended up with can be replayed against a rectangle, because only
        // such a frame has one in the log. The ringing frame a pre-check judged, and any frame whose
        // provenance is missing altogether, are counted here and left for check_frames.py to worry
        // about; they are the "before" of a settling and no measurement was ever taken on them.
        List<Kept> measuredFrames = new ArrayList<>();
        int ringing = 0;
        int unprovenanced = 0;
        for (Kept frame : frames) {
            if (frame.name.origin == FrameProvenance.Origin.Ringing) {
                ringing++;
            }
            else if (frame.name.arrivalAtMs == FrameProvenance.UNKNOWN) {
                unprovenanced++;
            }
            else {
                measuredFrames.add(frame);
            }
        }
        Map<Long, List<Kept>> byArrival = new LinkedHashMap<>();
        for (Kept frame : measuredFrames) {
            byArrival.computeIfAbsent(frame.name.arrivalAtMs, key -> new ArrayList<>()).add(frame);
        }
        int perBatch = largestBatch(byArrival);
        assertEquals(0, measured.size() % perBatch, "the log holds "+measured.size()
                + " measurements, which is not a whole number of alignments of "+perBatch+" nozzles");
        assertEquals(measured.size() / perBatch, byArrival.size(), "the log measured "
                +measured.size()/perBatch+" alignments and the corpus holds "+byArrival.size()
                +"; they are not the same run");

        System.out.println();
        System.out.println(measuredFrames.size()+" frames to replay, "+ringing+" taken while the head was "
                +"still ringing and "+unprovenanced+" without provenance, which no alignment measured.");
        System.out.println("batch nozzle frame  the rectangle the replay found              replayed mm "
                + "   the first frame of the hunt was");

        List<String> problems = new ArrayList<>();
        int batchIndex = 0;
        for (Map.Entry<Long, List<Kept>> entry : byArrival.entrySet()) {
            List<Measured> batch = measured.subList(batchIndex * perBatch, (batchIndex + 1) * perBatch);
            batchIndex++;
            for (Kept frame : entry.getValue()) {
                assertEquals(asDumpId(frame.name.partId), frame.name.partId, frame.file.getName()
                        + ": a part id that survives the file name this way cannot be read back");
                double[] replayed = replay(frame);
                RotatedRect rect = new RotatedRect();
                rect.center = new org.opencv.core.Point(replayed[4], replayed[5]);
                rect.size = new org.opencv.core.Size(replayed[6], replayed[7]);
                rect.angle = replayed[8];

                Measured found = null;
                for (Measured candidate : batch) {
                    if (!candidate.matched && candidate.sameRectAs(rect)) {
                        found = candidate;
                    }
                }
                if (found == null) {
                    problems.add(frame.file.getName()+": replayed "+describe(rect)
                            +", which no reading of this batch is; the batch says "
                            +describe(batch));
                }
                if (found != null) {
                    found.matched = true;
                }

                System.out.printf("%5d %-6s %-5s %-42s %7.3fx%-6.3f  ",
                        batchIndex, frame.name.nozzleId, "f"+frame.name.frameIndex, describe(rect),
                        replayed[0], replayed[1]);
                printTheFirstFrame(preChecked, batchIndex, frame, replayed);
            }
        }
        for (Measured leftover : measured) {
            if (!leftover.matched) {
                problems.add("the machine measured "+describe(leftover)+" and no frame replays to it");
            }
        }
        System.out.println();
        if (!problems.isEmpty()) {
            System.out.println("where the replay and the machine part company:");
            for (String problem : problems) {
                System.out.println("  - "+problem);
            }
        }
        assertTrue(problems.isEmpty(), byArrival.size()+" alignments replayed and "+problems.size()
                + " of their frames not the rectangle the machine reported; see the table above.");
        System.out.println(byArrival.size()+" alignments of "+run+" replayed through the pipeline "
                + "of the run itself, and every frame is the picture the machine measured.");
    }

    /**
     * What the part's own pipeline makes of every frame on disk, with the log left out of it. An
     * alignment that refused a part never writes the rectangle it measured into the log, so the only way
     * to read such a frame back is to measure it here: -Dreplay.measure=the corpus directory, whose
     * parent holds the configuration of the run that took it.
     */
    private void measureEveryFrame(File corpus) throws Exception {
        File directory = corpus.getParentFile();
        Assumptions.assumeTrue(directory != null && new File(directory, "machine.xml").isFile()
                && corpus.isDirectory(), "-Dreplay.measure="+corpus+" has no machine.xml beside it");
        List<Kept> frames = readFrames(corpus);
        assertFalse(frames.isEmpty(), "no frame dump in "+corpus);
        Configuration.initialize(directory);
        Configuration.get().load();
        System.out.println();
        System.out.println("the pipeline of "+directory+" over every frame of "+corpus
                +", in the order they arrived:");
        for (Kept frame : frames) {
            FrameProvenance provenance = frame.name.toProvenance();
            String when = provenance.isStill() ? "still" : "not still";
            try {
                double[] replayed = replay(frame);
                System.out.printf("%s %-8s %-3s f%-2s %-9s  %.3fx%.3f mm   {%s}%n", when,
                        frame.name.origin,
                        frame.name.nozzleId, frame.name.frameIndex,
                        "+"+(frame.name.capturedAtMs-frame.name.arrivalAtMs)+" ms",
                        replayed[0], replayed[1], replayed[6], replayed[7], replayed[8], replayed[4],
                        replayed[5]);
            }
            catch (Throwable e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                System.out.printf("%s %-8s %-3s f%-2s %-9s  found nothing: %s%n", when,
                        frame.name.origin, frame.name.nozzleId, frame.name.frameIndex,
                        "+"+(frame.name.capturedAtMs-frame.name.arrivalAtMs)+" ms",
                        why.replaceAll("\\s+", " "));
            }
        }
    }

    /**
     * How far the part had moved between the frame the pre-check measured and the frame the hunt kept.
     * Not a check of anything: it is the drift that the still window is meant to have excluded, read off
     * the only two frames of a batch whose readings were both written down.
     */
    private void printTheFirstFrame(List<PreChecked> preChecked, int batchIndex, Kept frame,
            double[] replayed) {
        PreChecked first = null;
        int seen = 0;
        for (PreChecked candidate : preChecked) {
            if (candidate.nozzleId.equals(frame.name.nozzleId)) {
                seen++;
                if (seen == batchIndex) {
                    first = candidate;
                }
            }
        }
        if (first == null) {
            System.out.println("(the log has no pre-check for this nozzle)");
            return;
        }
        double across = Math.min(Math.abs(first.width - replayed[0]), Math.abs(first.height - replayed[0]));
        double along = Math.min(Math.abs(first.height - replayed[1]), Math.abs(first.width - replayed[1]));
        System.out.printf("%s grabs: %.2fx%.2f mm at %+5.1f deg, %.2f mm off centre%n",
                first.grabCount > 0 ? String.valueOf(first.grabCount) : "?", first.width, first.height,
                first.rotation, first.offset);
        if (Math.max(across, along) > 0.05) {
            System.out.println("        ^^ the first frame and the kept one differ by "
                    +String.format("%.2f mm", Math.max(across, along))+", which is the drift the "
                    + "hunt was for");
        }
    }

    /**
     * Run the part's own pipeline over the frame that was kept of it, exactly as the background analysis
     * of the alignment did: the pipeline cloned from the part's vision settings, prepared with the same
     * crop and the same parameter assignments, and handed the frame instead of capturing a new one.
     *
     * @return the size in millimetres, then the rectangle in pixels, exactly as the pipeline returned it.
     */
    private double[] replay(Kept frame) throws Exception {
        Machine machine = Configuration.get().getMachine();
        Camera camera = null;
        for (Camera candidate : machine.getCameras()) {
            if (candidate.getId().equals(frame.name.cameraId)) {
                camera = candidate;
            }
        }
        assertNotNull(camera, "the configuration of this run has no camera "+frame.name.cameraId);
        Nozzle nozzle = null;
        for (Nozzle candidate : machine.getDefaultHead().getNozzles()) {
            if (candidate.getId().equals(frame.name.nozzleId)) {
                nozzle = candidate;
            }
        }
        assertNotNull(nozzle, "the configuration of this run has no nozzle "+frame.name.nozzleId);
        assertNotNull(nozzle.getNozzleTip(), "nozzle "+frame.name.nozzleId+" has no nozzle tip, and the "
                + "crop, the mask and the sampling size of the pipeline are all derived from it");

        Part part = Configuration.get().getPart(readBack(frame.name.partId));
        assertNotNull(part, "the configuration of this run has no part "+frame.name.partId);
        ReferenceBottomVision bottomVision = (ReferenceBottomVision) AbstractPartAlignment
                .getPartAlignment(part);
        assertNotNull(bottomVision, "part "+part.getId()+" is not aligned by bottom vision");
        BottomVisionSettings settings = ReferenceBottomVision.getDefault()
                .getInheritedVisionSettings(part);

        // The shot location the job captured at: the camera looking at the nozzle, at part height, and
        // unrotated, because these parts were aligned post-rotate. Nothing moves here - skipMovement is
        // what the background analysis of the alignment itself passes.
        Location wantedLocation = bottomVision.getCameraLocationAtPartHeight(part, camera, nozzle, 0.,
                true);
        try (CvPipeline pipeline = ReferenceBottomVision.getThreadLocalPipeline(settings)) {
            bottomVision.preparePipeline(pipeline, settings.getPipelineParameterAssignments(), camera,
                    part.getPackage(), nozzle, nozzle.getNozzleTip(), wantedLocation, wantedLocation,
                    settings, true);
            String name = frame.file.getName();
            Mat image = Imgcodecs.imread(frame.file.getAbsolutePath());
            assertFalse(image.empty(), name+" could not be read");
            pipeline.setPreCapturedShot(0, new CapturedShot(camera, nozzle, image, wantedLocation,
                    frame.name.toProvenance()));
            List<PipelineShot> shots = pipeline.getPipelineShots();
            assertFalse(shots.isEmpty(), "the pipeline of "+part.getId()+" has no shot");
            shots.get(0).apply();
            pipeline.process();

            Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
            if (result == null) {
                result = pipeline.getResult("result");
            }
            assertNotNull(result, "the pipeline of "+part.getId()+" found nothing in "+name);
            assertTrue(result.model instanceof RotatedRect,
                    name+": the pipeline returned "+result.model+", not a rectangle");
            RotatedRect rect = (RotatedRect) result.model;

            Location upp = camera.getUnitsPerPixelAtZ();
            return new double[] {
                    upp.getLengthX().multiply(rect.size.width)
                            .convertToUnits(LengthUnit.Millimeters).getValue(),
                    upp.getLengthY().multiply(rect.size.height)
                            .convertToUnits(LengthUnit.Millimeters).getValue(),
                    0, 0, rect.center.x, rect.center.y, rect.size.width, rect.size.height, rect.angle,
            };
        }
    }

    private int largestBatch(Map<Long, List<Kept>> byArrival) {
        int largest = 0;
        for (List<Kept> batch : byArrival.values()) {
            largest = Math.max(largest, batch.size());
        }
        return largest;
    }

    private String describe(Measured measured) {
        return String.format("{{%.17g, %.17g} %.0fx%.0f * %.17g}", measured.centerX, measured.centerY,
                measured.width, measured.height, measured.angle);
    }

    private String describe(RotatedRect rect) {
        return String.format("{{%.17g, %.17g} %.2fx%.2f * %.17g}", rect.center.x, rect.center.y,
                rect.size.width, rect.size.height, rect.angle);
    }

    private String describe(List<Measured> batch) {
        StringBuilder sb = new StringBuilder();
        for (Measured measured : batch) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format("{{%.17g, %.17g} %.0fx%.0f * %.17g}", measured.centerX,
                    measured.centerY, measured.width, measured.height, measured.angle));
        }
        return sb.toString();
    }

    private List<Kept> readFrames(File corpus) {
        List<Kept> frames = new ArrayList<>();
        File[] files = corpus.listFiles();
        assertNotNull(files, corpus+" is not a directory");
        Arrays.sort(files);
        for (File file : files) {
            if (file.getName().endsWith(".png")) {
                Kept frame = new Kept();
                frame.file = file;
                frame.name = FrameDump.parse(file.getName());
                frames.add(frame);
            }
        }
        frames.sort((a, b) -> a.name.arrivalAtMs == b.name.arrivalAtMs
                ? a.name.nozzleId.compareTo(b.name.nozzleId)
                : Long.compare(a.name.arrivalAtMs, b.name.arrivalAtMs));
        return frames;
    }

    private List<Measured> readMeasured(File log) throws Exception {
        List<Measured> measured = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = RECT.matcher(line);
                if (matcher.find()) {
                    Measured reading = new Measured();
                    reading.centerX = Double.parseDouble(matcher.group(2));
                    reading.centerY = Double.parseDouble(matcher.group(3));
                    reading.width = Double.parseDouble(matcher.group(4));
                    reading.height = Double.parseDouble(matcher.group(5));
                    reading.angle = Double.parseDouble(matcher.group(6));
                    measured.add(reading);
                    continue;
                }
            }
        }
        return measured;
    }

    private List<PreChecked> readPreChecks(File log) throws Exception {
        List<PreChecked> preChecks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = PRE_CHECK.matcher(line);
                if (!matcher.find() || "Pending".equals(matcher.group(9))) {
                    continue;
                }
                PreChecked preCheck = new PreChecked();
                preCheck.nozzleId = matcher.group(1);
                preCheck.partId = matcher.group(3);
                String[] counted = matcher.group(4).split("/");
                preCheck.grabCount = (int) numberOrNaN(counted.length > 1 ? counted[1] : "0");
                preCheck.width = Double.parseDouble(matcher.group(5));
                preCheck.height = Double.parseDouble(matcher.group(6));
                preCheck.rotation = numberOrNaN(matcher.group(7));
                preCheck.offset = numberOrNaN(matcher.group(8));
                preChecks.add(preCheck);
            }
        }
        return preChecks;
    }

    /**
     * The file name sanitizes a part id and the log does not, so the id is read back by undoing exactly
     * what {@link FrameDump} did to it. The corpus parts lose only a slash, so that is all that is
     * undone; anything more complicated is refused rather than guessed at.
     */
    private String readBack(String partId) {
        return partId.replaceFirst("^(C[0-9]{3,4})-(.*)-([0-9]+V)$", "$1-$2/$3");
    }

    private String asDumpId(String partId) {
        return partId.replaceAll("[^A-Za-z0-9.-]", "-");
    }

    private double numberOrNaN(String text) {
        try {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
