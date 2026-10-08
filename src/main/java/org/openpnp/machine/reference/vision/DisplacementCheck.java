package org.openpnp.machine.reference.vision;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.CameraBatchOperation;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;
import org.pmw.tinylog.Logger;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Measures the camera displacement of the up-looking cameras, i.e. how far each camera's optical
 * axis really is from the position that the machine claims for it.
 *
 * The problem this solves: a parallel alignment move is made for one nozzle/camera pair but it has
 * to be right for all of them. The claimed camera spacing and the claimed nozzle spacing are two
 * independently assembled mechanical dimensions, so they differ, and the alignment then puts every
 * non-primary nozzle slightly off its camera axis. See
 * docs/design/parallel-bottom-vision/camera-displacement-plan.md for the measurement algebra.
 *
 * The measurement needs no parts and no placing, just bare nozzle tips. One tip is moved in front
 * of one camera and the offset from that camera's axis is measured; doing this for every tip and
 * every camera gives the over-determined system
 *
 *   X(k,j) = u_k - e_j - c_k
 *
 * with the measured tip offset X, the nozzle error u (tip vs. claimed head offsets), the camera
 * error e (camera axis vs. claimed position) and the stored runout axis offset c that has been
 * subtracted from the commanded position. The system has rank N+M-1: the common offset of all
 * cameras cannot be separated from the common offset of all nozzles, so the gauge sum(e) = 0 is
 * used and the result is a redistribution of the displacement over the cameras, which is all the
 * machine can possibly know.
 *
 * Run it headless:
 *
 *   Result result = DisplacementCheck.run(new DisplacementCheck.Options());
 *   System.out.println(result.report);
 *
 * Nothing is ever written to the machine configuration by this class.
 */
public class DisplacementCheck {

    public static class Options {
        /**
         * Number of times the whole matrix is measured, to get a spread. The first pass settles the
         * cameras, so a spread over a low number mostly reports repeatability.
         */
        public int repeats = 3;

        /**
         * Measure every tip in front of every camera. Without this only the paired views are taken,
         * which cannot separate the nozzle spacing error from the camera displacement.
         */
        public boolean crossedMatrix = true;

        /**
         * Additionally measure the batch situation directly: centre nozzle k in camera k the way the
         * job processor does and then look at what all the other cameras see. This is the quantity
         * that the runtime compensation has to remove, so it is the check that counts.
         */
        public boolean batchCheck = true;

        /**
         * Subtract the stored runout axis offset from the commanded position, so that the result is
         * the residual of the current model instead of the absolute offset, and so that the rotation
         * angle of the tip does not matter. Switch off to measure the raw absolute offsets.
         */
        public boolean applyStoredAxisOffsets = true;

        /**
         * Pretend the advanced camera calibration does not exist by removing its fitted offsets from
         * the camera positions. Comparing the two runs shows how much of the displacement the
         * advanced calibration has already absorbed.
         */
        public boolean suppressAdvancedCalibration = false;

        /**
         * Extra Z offsets of the tip relative to the calibration plane. A real tilt of the camera
         * shows up as a displacement that changes with Z.
         */
        public double[] zOffsetsMm = new double[] {0.};

        /**
         * Extra rotation of the tip. With {@link #applyStoredAxisOffsets} this only checks that the
         * runout compensation works; without it the runout has to be averaged out over a full cycle.
         */
        public double[] cAngles = new double[] {0.};

        /**
         * A fit residual above this means the shots were not taken on one rigid machine, i.e. a
         * misdetection, a knock or a stale Z, and nothing else from the run means anything.
         */
        public double maxResidualMm = 0.05;

        /**
         * How far the shots of one single repeat may fail to close. The closure is the one check
         * that does not depend on the model at all: the head is rigid, so the four views of one
         * repeat have to add up. Anything above this means a tip was misdetected, the machine was
         * still settling, or something between the shots moved.
         */
        public double maxClosureMm = 0.05;

        /**
         * The run may not claim an error smaller than the spread it measures.
         */
        public double maxSpreadMm = 0.02;

        /**
         * Write the displacement this run proposes into the cameras' {@code <displacement>} and save
         * the configuration. Off by default: a run that only measures costs nothing but time, while a
         * run that writes changes where every part lands, so the caller has to say so. The save goes
         * through {@link org.openpnp.model.Configuration#save()}, which backs the previous
         * {@code machine.xml} up first.
         */
        public boolean writeDisplacement = false;

        /**
         * Write even when the run calls itself not valid. Off, and it should stay off: that verdict is
         * the check's own answer to whether what it measured is a displacement at all, and a number
         * that failed it goes on to move every part that comes after it. It is here for the case where
         * the complaint is understood and accepted, a tilt a few thousandths over the limit say, and
         * where the alternative to writing is a machine that keeps claiming the cameras are where they
         * are demonstrably not.
         */
        public boolean forceWriteDisplacement = false;

        /**
         * d(displacement)/dZ, i.e. the tilt of a camera. Only checked when more than one Z was
         * measured.
         */
        public double maxTiltMmPerMm = 0.02;

        /**
         * Detect the tip this many times per shot and average the result. The camera noise of a
         * single blurry tip silhouette is of the same order as the displacement being measured, and
         * averaging is the only way to get below it without touching the optics. The scatter of the
         * frames is reported separately, it is the vision noise floor of the machine.
         */
        public int framesPerShot = 1;

        /**
         * How long to let the light burn after switching it on before a frame is taken. The lamps on
         * this machine take a few milliseconds to strike, and a frame exposed during that time is dim
         * in a way the detector does not complain about, it just measures something else. The light is
         * additionally kept on for the whole shot (see the batch operation in measure()), so this is
         * paid once per shot and not once per frame.
         */
        public long lightWarmupMs = 50;

        /**
         * A captured frame with less contrast than this (standard deviation of its pixels, on the
         * 0..255 scale) is not a measurement at all: the light is off, the exposure is still
         * ramping up or the lens is covered. The shot is retried and, if it stays bad, discarded,
         * because a detector will happily fit a circle to noise.
         */
        public double minImageStdDev = 5.;

        /**
         * Store the pipeline images of every shot in the run directory.
         */
        public boolean saveImages = true;

        /**
         * Store every frame of a shot rather than only the one the shot was judged by, numbered
         * _f0, _f1, ... Needed to see what a flickering light or a camera delivering the same frame
         * twice actually does to the detection; costs a lot of disk, so it is off by default.
         */
        public boolean saveFrames = false;

        /**
         * How much softer than the sharpest camera a camera may be imaged before the run is refused.
         * The displacement is a difference of what two cameras see of the same tip, so both have to
         * see the same feature. If one of them is defocused then its specular ring has spread into
         * its penumbra and the two no longer measure the same point, which shows up as a closure
         * that no amount of averaging removes. 1.0 means equally sharp.
         */
        public double maxFocusRatio = 2.5;

        /**
         * Command this much X and Y in both directions at the paired centre position and store the
         * frames. The commanded move is in machine coordinates and has nothing to do with the
         * camera's own scaling, so the pictures measure the true pixel size of every camera and say
         * whether the units-per-pixel the advanced calibrations report agree with each other. Two
         * cameras whose pixel sizes disagree by a few percent put a permanent error into the closure
         * that looks exactly like a bad displacement. 0 switches the probe off.
         */
        public double pixelScaleProbeMm = 0.;

        /**
         * Look for a circle of this diameter instead of the tip diameter the nozzle tip calibration
         * is configured with. An up-looking camera sees several circles in a nozzle tip: the bright
         * specular ring, the wider penumbra and, in the middle of it, the vacuum hole, whose edge is
         * the tip wall. The detector locks onto whichever it is told to expect, so this is how a run
         * can be made to measure the hole rather than the glare. 0 leaves the tip calibration in
         * charge, which is the same feature the runout calibration measures.
         */
        public double holeDiameterMm = 0.;

        /**
         * Where to put the report, default is <configurationDirectory>/displacement/<timestamp>.
         */
        public File directory = null;

        /**
         * Set by {@link #run(Options)} when it chose {@link #directory} itself. A second run from the
         * same script - the write loop measures again to show what the write was worth - then knows to
         * choose a directory of its own rather than overwrite the first run's report and images.
         */
        public boolean directoryFromRun = false;

        @Override
        public String toString() {
            return "repeats "+repeats+", framesPerShot "+framesPerShot+", lightWarmupMs "+lightWarmupMs+", crossedMatrix "+crossedMatrix
                    +", batchCheck "+batchCheck+", applyStoredAxisOffsets "+applyStoredAxisOffsets
                    +", suppressAdvancedCalibration "+suppressAdvancedCalibration
                    +", maxFocusRatio "+maxFocusRatio+", pixelScaleProbeMm "+pixelScaleProbeMm
                    +", holeDiameterMm "+holeDiameterMm
                    +", zOffsetsMm "+zOffsetsMm.length+", cAngles "+cAngles.length;
        }
    }

    public static class Shot {
        public String moveName;
        /**
         * Set for the batch shots only: the nozzle the batch would be centred on.
         */
        public ReferenceNozzle primary;
        public ReferenceNozzle nozzle;
        public Camera camera;
        public double zOffsetMm;
        public double cAngle;
        public int repeat;
        /**
         * The measured offset of the tip from the camera axis in machine coordinates, or null if
         * nothing was detected.
         */
        public Location measured;
        /**
         * How many frames were detected and averaged into {@link #measured}.
         */
        public int frames;
        /**
         * The scatter of those frames, i.e. the vision noise of one shot, as opposed to the spread
         * of the whole move over the repeats.
         */
        public double frameSpreadMm = Double.NaN;
        /**
         * The contrast of the frame the shot was taken with, standard deviation on 0..255.
         */
        public double imageStdDev = Double.NaN;
        /**
         * How sharp the frame the shot was taken with is, i.e. the root-mean-square of its Laplacian
         * over the region the tip is seen in. A camera that is not focused on the tip does not fail to detect
         * it, it measures the spread of the penumbra instead of the tip, and every camera has its own
         * penumbra. Reported so that the two cameras of a comparison can be seen to be equally sharp.
         */
        public double imageSharpness = Double.NaN;
        /**
         * One entry per detected frame: x, y and the instant the camera says that frame was taken. The
         * last one is what tells a shot that averaged eight views of the world from a shot that
         * averaged eight copies of one view, which on a camera that grabs its frames ahead of being
         * asked is a real distinction.
         */
        public List<double[]> frameValues = new ArrayList<>();
        /**
         * Distinct frame instants among the detected frames, i.e. how many different views of the
         * machine the shot really is.
         */
        public int distinctFrames;
        public String note = "";
    }

    public static class CameraResult {
        public ReferenceCamera camera;
        /**
         * The measured camera error e in machine coordinates, gauge sum(e) = 0.
         */
        public Location error;
        public double sigmaX;
        public double sigmaY;
        public Location stored;
        /**
         * stored + error, the value to write into the camera's displacement.
         */
        public Location proposed;
    }

    public static class NozzleResult {
        public ReferenceNozzle nozzle;
        public ReferenceNozzleTip nozzleTip;
        /**
         * The measured nozzle error u (tip vs. claimed head offsets), correlated with the camera
         * errors by the gauge.
         */
        public Location error;
        public double sigmaX;
        public double sigmaY;
        /**
         * The stored runout axis offset c.
         */
        public Location storedAxisOffset;
        /**
         * The measured pair offset m minus the stored axis offset c, i.e. how wrong the runout
         * calibration currently is for this pair.
         */
        public Location pairResidual;
        public double pairResidualMm = Double.NaN;
        /**
         * The peak and rms error the stored runout model had on the day it was fitted, in
         * millimetres, NaN when the model does not know. The runout calibration is a circle fitted to
         * a handful of measured positions, so the offset it hands out was never exact; a
         * {@link #pairResidualMm} of the same size as this says the calibration is merely as bad as
         * it always was rather than stale.
         */
        public double modelPeakErrorMm = Double.NaN;
        public double modelRmsErrorMm = Double.NaN;
    }

    public static class BatchCell {
        public ReferenceNozzle nozzle;
        public ReferenceCamera camera;
        /**
         * Where the tip of this nozzle sits in its own camera when the batch is centred on the
         * primary nozzle. This is exactly what the runtime analysis compensation has to remove.
         */
        public Location predicted;
        /**
         * The same thing measured directly, one entry per repeat, null if nothing was measured.
         */
        public List<Location> measurements = new ArrayList<>();
        public Location measured;
        public double errorMm = Double.NaN;
        /**
         * True for the cell of the nozzle this batch was centred on. The runtime calls that one the
         * first nozzle tip and gives it no displacement at all.
         */
        public boolean primary;
    }

    public static class ConditionResult {
        /**
         * Every shot taken for this condition, including the misses.
         */
        public List<Shot> allShots = new ArrayList<>();
        public double zOffsetMm;
        public double cAngle;
        public List<CameraResult> cameraResults = new ArrayList<>();
        public List<NozzleResult> nozzleResults = new ArrayList<>();
        /**
         * Keyed by the primary nozzle id, i.e. the nozzle the batch would be centred on.
         */
        public Map<String, List<BatchCell>> batch = new LinkedHashMap<>();
        public double residualMm = Double.NaN;
        /**
         * The worst per repeat rigidity violation, i.e. how far the shots of one single repeat fail
         * to close, and a human readable account of it.
         */
        public double closureMm = Double.NaN;
        public String closureNote = "";
        /**
         * The worst vision noise of any single shot, from averaging its frames.
         */
        public double frameSpreadMm = Double.NaN;
        public String frameSpreadOf = "";
        /**
         * Shots whose frames were not actually new pictures, i.e. a camera handing out the same frame
         * several times, which makes an average of eight into an average of one.
         */
        public String staleFrames = "";
        /**
         * The contrast of the frames as seen per camera, worst spread reported. A light that flickers,
         * or that is not yet up when the frame is taken, changes what the detector measures without
         * changing anything the detector would complain about.
         */
        public String contrastNote = "";
        /**
         * The sharpness of the frames as seen per camera, with the softest camera named. Two cameras
         * that are not equally sharp do not measure the same feature of the same tip, whatever the
         * detector says about it.
         */
        public String focusNote = "";
        /**
         * The softest camera of the condition relative to the sharpest one, 1.0 when they are equally
         * sharp.
         */
        public double focusRatio = Double.NaN;
        /**
         * Observations per repeat on the matrix side, and unknowns of the solve. When the first is not
         * above the second the system is exactly determined: the residual is then zero by construction
         * and says nothing at all about the machine.
         */
        public int rowsPerRepeat;
        public int unknowns;
        /**
         * The worst repeatability of any single move over the repeats.
         */
        public double spreadMm = Double.NaN;
        public String spreadOf = "";
        public int shots;
        public int missed;
    }

    public static class Result {
        public File directory;
        /**
         * What the detector was told to look for, so that a run can be compared with another run
         * that measured a different circle of the same tip.
         */
        public String detection = "";
        /**
         * d(displacement)/dZ per camera, only filled in when more than one Z was measured.
         */
        public Map<String, double[]> tiltMmPerMm = new LinkedHashMap<>();
        public List<ConditionResult> conditions = new ArrayList<>();
        public List<String> problems = new ArrayList<>();
        /**
         * Things worth knowing that do not invalidate the run.
         */
        public List<String> notes = new ArrayList<>();
        /**
         * Complaints about the machine rather than about the measurement: a tilted camera, an unequal
         * focus. They say how far the number is good for, not that it is wrong, so they do not make the
         * run invalid, but they go into the dialog next to the write so the choice is made with them in
         * view. A limit that is exceeded here costs something proportional to what it is a slope of,
         * which is usually small over the window the machine actually applies the compensation in.
         */
        public List<String> caveats = new ArrayList<>();
        public String report = "";
        public boolean valid = false;

        public ConditionResult getCondition(int index) {
            return conditions.get(index);
        }
    }

    protected static class Pair {
        ReferenceNozzle nozzle;
        ReferenceNozzleTipCalibration calibration;
        ReferenceCamera camera;
    }

    /**
     * Measure and report. Writes to the configuration only when
     * {@link Options#writeDisplacement} asks it to; a plain measurement costs nothing but time and
     * can be repeated until the numbers stop moving.
     */
    public static Result run(Options options) throws Exception {
        Result result = new Result();
        try {
            List<Pair> pairs = collectPairs(result);
            result.detection = describeDetection(options, pairs);
            if (options.directoryFromRun) {
                // Chosen by a run, not by the caller: this run is the second one of a loop and belongs
                // in a directory of its own, next to the one it is there to compare against.
                options.directory = null;
            }
            File directory = prepareDirectory(options);
            result.directory = directory;
            options.directory = directory;
            options.directoryFromRun = true;
            snapshotSettings(directory);

            for (double zOffsetMm : options.zOffsetsMm) {
                for (double cAngle : options.cAngles) {
                    ConditionResult condition = measureCondition(options, pairs, zOffsetMm, cAngle);
                    result.conditions.add(condition);
                }
            }

            statistics(result, options);
            check(result, options);
            if (options.writeDisplacement) {
                for (String line : writeDisplacement(result, options)) {
                    result.notes.add("stored displacement of "+line);
                }
            }
            result.report = makeReport(result, options);
            writeReport(result, options);
        }
        catch (Exception e) {
            result.problems.add(e.getMessage() == null ? e.toString() : e.getMessage());
            result.report = makeReport(result, options);
            throw e;
        }
        return result;
    }

    /**
     * What this run proposes to store on each camera: the stored displacement plus the measured
     * error, averaged over the conditions. Only the difference between two cameras is observable, so
     * the solve fixes the gauge with sum(e) = 0 and the write keeps the mean of the stored values
     * where it was while moving the cameras apart by exactly the measured difference.
     */
    public static Map<ReferenceCamera, Location> proposal(Result result) {
        Map<ReferenceCamera, double[]> summed = new LinkedHashMap<>();
        for (ConditionResult condition : result.conditions) {
            for (CameraResult r : condition.cameraResults) {
                if (r.proposed == null) {
                    continue;
                }
                double[] v = summed.computeIfAbsent(r.camera, k -> new double[3]);
                v[0] += r.proposed.getX();
                v[1] += r.proposed.getY();
                v[2] += 1.;
            }
        }
        Map<ReferenceCamera, Location> proposal = new LinkedHashMap<>();
        for (Map.Entry<ReferenceCamera, double[]> entry : summed.entrySet()) {
            double[] v = entry.getValue();
            proposal.put(entry.getKey(), new Location(LengthUnit.Millimeters, v[0]/v[2], v[1]/v[2],
                    0, 0));
        }
        return proposal;
    }

    /**
     * Store the displacement this run proposes in the {@code <displacement>} element of each camera
     * and save the configuration, which writes the previous {@code machine.xml} into the backup
     * directory first. Returns one line per camera saying what went in, so that a caller can show it
     * or print it; nothing is written when the run proposed nothing.
     *
     * Storing it changes where the cameras claim to be, which is exactly what the runout calibration
     * of a nozzle tip absorbed into its own camera offset. The tips therefore have to be calibrated
     * again afterwards, and this is said in the note.
     */
    public static List<String> writeDisplacement(Result result, Options options)
            throws Exception {
        if (! result.valid && ! options.forceWriteDisplacement) {
            throw new Exception("The run says NOT VALID, so nothing is written: "
                    + String.join(" | ", result.problems) + " Either fix what it complains about, or "
                    + "set forceWriteDisplacement when that complaint is understood and accepted.");
        }
        Map<ReferenceCamera, Location> proposal = proposal(result);
        double spread = 0.;
        for (ConditionResult condition : result.conditions) {
            if (! Double.isNaN(condition.spreadMm)) {
                spread = Math.max(spread, condition.spreadMm);
            }
        }
        String verifiedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<String> written = new ArrayList<>();
        for (Map.Entry<ReferenceCamera, Location> entry : proposal.entrySet()) {
            ReferenceCamera camera = entry.getKey();
            Location from = camera.getDisplacement().convertToUnits(LengthUnit.Millimeters);
            Location to = entry.getValue();
            camera.setDisplacement(to);
            camera.setDisplacementVerifiedAt(verifiedAt);
            camera.setDisplacementSpreadMm(spread);
            written.add(camera.getName()+": "+f(from.getX())+", "+f(from.getY())+" -> "+f(to.getX())
                    +", "+f(to.getY()));
        }
        if (written.isEmpty()) {
            return written;
        }
        Configuration.get().save();
        result.notes.add("Displacement written on "+verifiedAt+" ("+String.join(" | ", written)
                +"), spread "+f(spread)+"mm. This moves the cameras, and the runout calibration of"
                +" every nozzle tip has absorbed where the cameras used to claim to be: calibrate the"
                +" nozzle tips again and then run this check again. Its paired centres should have"
                +" come down towards the runout of the tip itself. The wipe is not a note in a report,"
                +" it happens on the spot (ReferenceCamera.setDisplacement() -> viewHasChanged() ->"
                +" ReferenceNozzleTipCalibration.resetAllNozzleTips()), for every tip on the machine"
                +" and not only the ones measured here, so anything that stops between this write and"
                +" the calibration leaves the machine without a runout calibration, and a save then"
                +" keeps it that way.");
        regenerateReport(result, options);
        return written;
    }

    /**
     * The short version for a dialog: what the cameras claim now, what this run says they should
     * claim, and how much the run is worth.
     */
    public static String describeDisplacement(Result result) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ReferenceCamera, Location> entry : proposal(result).entrySet()) {
            Location stored = entry.getKey().getDisplacement().convertToUnits(LengthUnit.Millimeters);
            sb.append(entry.getKey().getName()).append(": stored ")
                    .append(f(stored.getX())).append(", ").append(f(stored.getY()))
                    .append("  proposed ").append(f(entry.getValue().getX())).append(", ")
                    .append(f(entry.getValue().getY())).append("\n");
        }
        double spread = 0.;
        for (ConditionResult condition : result.conditions) {
            if (! Double.isNaN(condition.spreadMm)) {
                spread = Math.max(spread, condition.spreadMm);
            }
        }
        sb.append("spread ").append(f(spread)).append("mm, ").append(result.valid ? "valid"
                : "NOT VALID");
        if (! result.caveats.isEmpty()) {
            sb.append(", ").append(result.caveats.size()).append(result.caveats.size() == 1
                    ? " caveat" : " caveats");
        }
        sb.append("\n");
        // The dialog that offers the write has to carry the reasons with it. Sentencing somebody to
        // go and read a file they cannot open from a dialog is how a check ends up being ignored.
        for (String problem : result.problems) {
            sb.append("    not valid: ").append(firstSentence(problem)).append("\n");
        }
        for (String caveat : result.caveats) {
            sb.append("    caveat: ").append(firstSentence(caveat)).append("\n");
        }
        return sb.toString();
    }

    /**
     * The first sentence of a complaint, for a dialog that has to be readable without becoming a copy
     * of the report.
     */
    protected static String firstSentence(String s) {
        int end = s.indexOf(". ");
        return end < 0 ? s.trim() : s.substring(0, end + 1).trim();
    }

    /**
     * The runout side of a run in a few lines: where each tip's stored centre is, how far today's
     * measurement is from it, and how good that centre was the day it was fitted. Comparing two runs
     * of this across a camera displacement write answers whether the runout calibration was carrying
     * the camera's own mounting error: once the displacement is stored and the tips calibrated again,
     * the pair residual should be of the order of the model's own error and the centres should have
     * come down towards the runout of the tip itself. The worst condition per nozzle is shown.
     */
    public static String describeRunout(Result result) {
        Map<String, String> lines = new LinkedHashMap<>();
        Map<String, Double> worst = new LinkedHashMap<>();
        for (ConditionResult condition : result.conditions) {
            for (NozzleResult r : condition.nozzleResults) {
                String name = r.nozzle.getName();
                double residual = Double.isNaN(r.pairResidualMm) ? 0. : r.pairResidualMm;
                if (worst.containsKey(name) && residual <= worst.get(name)) {
                    continue;
                }
                worst.put(name, residual);
                lines.put(name, String.format(Locale.ENGLISH, "%-4s stored %8s, %-8s  pair res %6s"
                        +"  model pk %6s", name, f(r.storedAxisOffset.getX()),
                        f(r.storedAxisOffset.getY()), f(r.pairResidualMm), f(r.modelPeakErrorMm)));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines.values()) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * How sharp the frames were per camera against the Z they were taken at. Two cameras that are not
     * equally sharp do not measure the same feature of the same tip, and this says which kind of
     * unequal it is: a camera whose sharpest Z is not the Z the machine shoots at is focused for
     * another height, which a calibration Z offset can answer, while one whose curve is flat is as
     * sharp as its lens gets and is not worth arguing with. Needs more than one Z in the run.
     */
    protected static void appendSharpnessByZ(StringBuilder sb, Result result) {
        List<Double> zOffsets = new ArrayList<>();
        for (ConditionResult condition : result.conditions) {
            if (! zOffsets.contains(condition.zOffsetMm)) {
                zOffsets.add(condition.zOffsetMm);
            }
        }
        if (zOffsets.size() < 2) {
            return;
        }
        Collections.sort(zOffsets);
        Map<String, Map<Double, double[]>> byCamera = new LinkedHashMap<>();
        for (ConditionResult condition : result.conditions) {
            for (Shot shot : condition.allShots) {
                if (shot.camera == null || Double.isNaN(shot.imageSharpness)) {
                    continue;
                }
                double[] value = byCamera
                        .computeIfAbsent(shot.camera.getName(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(condition.zOffsetMm, k -> new double[2]);
                value[0] += shot.imageSharpness;
                value[1] += 1.;
            }
        }
        sb.append("sharpness of the frames by Z (a peak away from the Z the machine shoots at is a")
                .append(" camera focused for another height, which its calibration Z can answer; a")
                .append(" flat curve is a lens at its limit and nothing to fix):\n            ");
        for (Double z : zOffsets) {
            sb.append(String.format(Locale.ENGLISH, "%9s  ", "Z"+(z >= 0 ? "+" : "")+f(z)));
        }
        sb.append("\n");
        for (Map.Entry<String, Map<Double, double[]>> camera : byCamera.entrySet()) {
            sb.append(String.format(Locale.ENGLISH, "  %-10s", camera.getKey()));
            double best = -1.;
            double bestZ = 0.;
            for (Double z : zOffsets) {
                double[] value = camera.getValue().get(z);
                if (value == null || value[1] == 0.) {
                    sb.append(String.format(Locale.ENGLISH, "%9s  ", "-"));
                    continue;
                }
                double sharpness = value[0]/value[1];
                sb.append(String.format(Locale.ENGLISH, "%9.2f  ", sharpness));
                if (sharpness > best) {
                    best = sharpness;
                    bestZ = z;
                }
            }
            sb.append(" sharpest at Z").append(bestZ >= 0 ? "+" : "").append(f(bestZ)).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Rebuild the report and write it out again, so that a write done after the measuring (a script
     * asking first, say) still leaves the run directory telling the truth about what happened.
     */
    protected static void regenerateReport(Result result, Options options) throws IOException {
        result.report = makeReport(result, options);
        writeReport(result, options);
    }

    protected static List<Pair> collectPairs(Result result) throws Exception {
        Machine machine = Configuration.get().getMachine();
        if (! machine.isHomed()) {
            throw new Exception("Machine must be homed before a displacement check.");
        }

        List<Pair> pairs = new ArrayList<>();
        for (Head head : machine.getHeads()) {
            for (Nozzle mNozzle : head.getNozzles()) {
                if (! (mNozzle instanceof ReferenceNozzle)) {
                    continue;
                }
                ReferenceNozzle nozzle = (ReferenceNozzle) mNozzle;
                ReferenceNozzleTip nozzleTip = nozzle.getCalibrationNozzleTip();
                if (nozzleTip == null) {
                    result.problems.add("Nozzle "+nozzle.getName()+" has no nozzle tip, cannot measure.");
                    continue;
                }
                if (nozzle.getPart() != null) {
                    throw new Exception("Nozzle "+nozzle.getName()+" still has a part picked up. The "
                            + "displacement check measures bare nozzle tips, remove the part first.");
                }
                ReferenceCamera camera;
                try {
                    camera = (ReferenceCamera) VisionUtils.getBottomVisionCamera(nozzle);
                }
                catch (Exception e) {
                    result.problems.add("Nozzle "+nozzle.getName()+" has no bottom vision camera: "
                            +e.getMessage());
                    continue;
                }
                ReferenceNozzleTipCalibration calibration = nozzleTip.getCalibration();
                if (! calibration.isEnabled()) {
                    result.problems.add("Nozzle tip "+nozzleTip.getName()
                            +" calibration is disabled, its tip position is not compensated, results for "
                            +nozzle.getName()+" will contain the runout.");
                }
                else if (! calibration.isCalibrated(nozzle)) {
                    result.problems.add("Nozzle tip "+nozzleTip.getName()+" is not calibrated on "
                            +nozzle.getName()+", its runout is not compensated.");
                }
                Pair pair = new Pair();
                pair.nozzle = nozzle;
                pair.calibration = calibration;
                pair.camera = camera;
                pairs.add(pair);
            }
        }
        if (pairs.size() < 2) {
            throw new Exception("Need at least two calibrated nozzle/camera pairs to separate the "
                    + "nozzle spacing error from the camera displacement, found "+pairs.size()+".");
        }
        if (pairs.size() < 3) {
            result.notes.add("Two pairs is the minimum that can be solved: "+(2*(long)pairs.size()-1)
                    +" unknowns from "+(pairs.size()*(long)pairs.size())+" observations per repeat. The "
                    +"camera displacement is a difference and is therefore still well determined, but "
                    +"the individual nozzle and camera errors cannot be separated any further, and a "
                    +"single bad shot has no redundancy to absorb it.");
        }
        return pairs;
    }

    /**
     * Say which circle of the tip was measured, in millimetres and in the pixels of the camera that
     * was paired to that nozzle. The pixel figure is the one the detector works in: a circle told to
     * the detector as 0.22mm is a search window of a dozen pixels on one camera and of thirty on
     * another, and whether the feature is there at all depends on that, not on the millimetres.
     */
    protected static String describeDetection(Options options, List<Pair> pairs) {
        StringBuilder sb = new StringBuilder();
        sb.append(options.holeDiameterMm > 0. ? "vacuum hole, diameter set by this run"
                : "tip as configured in the nozzle tip calibration");
        for (Pair pair : pairs) {
            Length configured = options.holeDiameterMm > 0. ?
                    new Length(options.holeDiameterMm, LengthUnit.Millimeters)
                    : pair.calibration.getCalibrationTipDiameter();
            double diameterMm = configured == null ? 0.
                    : configured.convertToUnits(LengthUnit.Millimeters).getValue();
            double unitsPerPixel = pair.camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters)
                    .getX();
            sb.append("; ").append(pair.nozzle.getName()).append(" in ").append(pair.camera.getName())
                    .append(" ").append(f(diameterMm)).append("mm = ")
                    .append(f(diameterMm/Math.max(unitsPerPixel, 1e-9))).append("px");
        }
        return sb.toString();
    }

    protected static File prepareDirectory(Options options) throws IOException {
        File directory = options.directory;
        if (directory == null) {
            String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
                    .format(LocalDateTime.now());
            directory = new File(Configuration.get().getConfigurationDirectory(), "displacement/"+stamp);
        }
        if (! directory.isDirectory() && ! directory.mkdirs()) {
            throw new IOException("Cannot create "+directory);
        }
        return directory.getCanonicalFile();
    }

    protected static void snapshotSettings(File directory) {
        File machineXml = new File(Configuration.get().getConfigurationDirectory(), "machine.xml");
        if (machineXml.isFile()) {
            try {
                Files.copy(machineXml.toPath(), new File(directory, "settings-snapshot.xml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e) {
                Logger.warn("Could not snapshot machine.xml: {}", e.getMessage());
            }
        }
    }

    protected static ConditionResult measureCondition(Options options, List<Pair> pairs,
            double zOffsetMm, double cAngle) throws Exception {
        ConditionResult condition = new ConditionResult();
        condition.zOffsetMm = zOffsetMm;
        condition.cAngle = cAngle;

        // All nozzles share the R axis only for the moved one, each has its own. Rotate every one
        // of them in place to the measurement angle, so that the runout compensation applied by the
        // last move of a nozzle is valid for the angle it is then seen at. Without this a nozzle
        // that is not moved contributes an uncompensated runout phase to the measurement.
        for (Pair pair : pairs) {
            Location location = pair.nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            MovableUtils.moveToLocationAtSafeZ(pair.nozzle,
                    location.derive(null, null, null, cAngle));
        }

        List<Shot> shots = new ArrayList<>();
        for (int repeat = 0; repeat < options.repeats; repeat++) {
            for (Pair moved : pairs) {
                for (Pair target : pairs) {
                    if (! options.crossedMatrix && target != moved) {
                        continue;
                    }
                    Location measureLocation = getMeasureLocation(options, moved, target.camera,
                            zOffsetMm, cAngle);
                    MovableUtils.moveToLocationAtSafeZ(moved.nozzle, measureLocation);
                    shots.add(measure(options, moved, target.camera, measureLocation,
                            moved.nozzle.getName()+"→"+target.camera.getName(), zOffsetMm, cAngle,
                            repeat));
                }
            }
        }

        if (options.pixelScaleProbeMm > 0.) {
            for (Pair pair : pairs) {
                Location measureLocation = getMeasureLocation(options, pair, pair.camera, zOffsetMm,
                        cAngle);
                MovableUtils.moveToLocationAtSafeZ(pair.nozzle, measureLocation);
                pixelScaleProbe(options, pair, measureLocation, zOffsetMm);
            }
        }

        if (options.batchCheck) {
            for (int repeat = 0; repeat < options.repeats; repeat++) {
                for (Pair primary : pairs) {
                    Location measureLocation = getMeasureLocation(options, primary, primary.camera,
                            zOffsetMm, cAngle);
                    MovableUtils.moveToLocationAtSafeZ(primary.nozzle, measureLocation);
                    for (Pair pair : pairs) {
                        // What each camera sees when the batch is centred on the primary nozzle.
                        // Only the X/Y is shared by the batch, every nozzle has its own Z, so bring
                        // every tip to its own calibration plane first, exactly as the job processor
                        // does. Without this the non-primary tips are seen out of focus.
                        Location at = pair.nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
                        double z = getCalibrationZ(pair, zOffsetMm);
                        if (Math.abs(at.getZ()-z) > 0.01) {
                            pair.nozzle.moveTo(at.derive(null, null, z, null));
                        }
                        Shot shot = measure(options, pair, pair.camera,
                                getClaimedTipLocation(measureLocation, primary, pair, zOffsetMm),
                                "batch@"+primary.nozzle.getName(), zOffsetMm, cAngle, repeat);
                        shot.primary = primary.nozzle;
                        shots.add(shot);
                    }
                }
            }
        }

        // The batch shots of the primary pair are the same measurement as the paired matrix shots,
        // keep them out of the solve and use them for the batch table instead.
        List<Shot> solveShots = new ArrayList<>();
        for (Shot shot : shots) {
            if (shot.primary == null) {
                solveShots.add(shot);
            }
        }
        for (Pair pair : pairs) {
            pair.nozzle.moveToSafeZ();
        }

        condition.allShots.addAll(shots);
        solve(condition, pairs, solveShots, options);
        batch(condition, pairs, shots);
        return condition;
    }

    /**
     * Take pictures of the tip in its own camera at known machine offsets. The commanded move is in
     * machine coordinates and owes nothing to a camera's own scaling, so the shift of the tip between
     * the pictures, measured afterwards, is that camera's true pixel size. The displacement is the
     * difference of two cameras' readings in their own millimetres, so two advanced calibrations
     * whose pixel sizes disagree by a few percent put a fixed error of that same few percent of the
     * off-centre distance into every crossed shot, which is of the same order as the displacement
     * being measured. Saved for offline measurement, the commanded offsets are in the file names.
     */
    protected static void pixelScaleProbe(Options options, Pair pair, Location centre,
            double zOffsetMm) {
        double probe = Math.abs(options.pixelScaleProbeMm);
        if (probe <= 0. || ! options.saveImages) {
            return;
        }
        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation batch = machine == null ? null : machine.getCameraBatchOperation();
        try {
            if (batch != null) {
                batch.startBatchOperation("displacement");
            }
            pair.camera.actuateLightBeforeCapture();
            sleep(options.lightWarmupMs);
            saveProbeImage(options, pair, "x0", centre, zOffsetMm, capture(pair.camera));
            String[] tags = {"xp", "xm", "yp", "ym"};
            for (String tag : tags) {
                double dx = tag.equals("xp") ? probe : (tag.equals("xm") ? -probe : 0.);
                double dy = tag.equals("yp") ? probe : (tag.equals("ym") ? -probe : 0.);
                Location at = centre.derive(centre.getX()+dx, centre.getY()+dy, null, null);
                MovableUtils.moveToLocationAtSafeZ(pair.nozzle, at);
                sleep(options.lightWarmupMs/2);
                saveProbeImage(options, pair, tag, at, zOffsetMm, capture(pair.camera));
            }
            MovableUtils.moveToLocationAtSafeZ(pair.nozzle, centre);
        }
        catch (Exception e) {
            Logger.info("Displacement pixel scale probe of tip {} in camera {} failed: {}",
                    pair.nozzle.getName(), pair.camera.getName(), e.getMessage());
        }
        finally {
            if (batch != null) {
                try {
                    batch.endBatchOperation("displacement");
                }
                catch (Exception e) {
                    Logger.info("Could not end the displacement light batch: {}", e.getMessage());
                }
            }
        }
    }

    protected static void saveProbeImage(Options options, Pair pair, String tag, Location at,
            double zOffsetMm, BufferedImage frame) {
        if (frame == null || options.directory == null) {
            return;
        }
        try {
            File images = new File(options.directory, "images");
            images.mkdirs();
            String name = String.format(Locale.ENGLISH, "probe_%s-%s_z%+.2f_%s_x%+.4f_y%+.4f",
                    pair.nozzle.getName(), pair.camera.getName(), zOffsetMm, tag, at.getX(),
                    at.getY());
            name = name.replaceAll("[^A-Za-z0-9.+-]", "-")+".png";
            ImageIO.write(frame, "png", new File(images, name));
        }
        catch (Exception e) {
            Logger.debug("Could not save displacement probe image: {}", e.getMessage());
        }
    }

    protected static double getCalibrationZ(Pair pair, double zOffsetMm) {
        return pair.calibration.getCalibrationZOffset().convertToUnits(LengthUnit.Millimeters)
                .getValue()+zOffsetMm;
    }

    /**
     * The machine Z at which the tip of this pair is in focus. Note that this is an absolute Z and
     * that the Z of the camera location is not it and must not be used: the Z of a bottom camera is
     * the gantry Z plus a few hundredths of head offset, i.e. wherever the head happens to be, while
     * the focal plane of the camera is a property of the machine. The calibration Z offset of the tip
     * is what already encodes it, exactly as in the nozzle tip calibration itself. Where the two
     * cameras have different focal planes is a real effect, and it is the Z sweep that shows it up as
     * an apparent tilt, not something a fixed Z can express.
     */

    /**
     * Where the model claims the tip of the pair is when the primary nozzle has been commanded to
     * primaryMeasureLocation. Used to place the detection mask and its threshold; the reported shot
     * value is the offset from the camera axis and does not depend on it.
     */
    protected static Location getClaimedTipLocation(Location primaryMeasureLocation, Pair primary,
            Pair pair, double zOffsetMm) {
        return primaryMeasureLocation
                .subtract(primary.nozzle.getHeadOffsets().convertToUnits(LengthUnit.Millimeters))
                .add(pair.nozzle.getHeadOffsets().convertToUnits(LengthUnit.Millimeters))
                .derive(null, null, getCalibrationZ(pair, zOffsetMm), null);
    }

    /**
     * The commanded position of the moved nozzle such that its tip sits on the axis of the target
     * camera, as far as the current model knows. The camera position is the plain one, so it
     * contains the head offsets and the advanced calibration fit but not the runout axis offset of
     * any tip; that one is subtracted explicitly so that the treatment is identical for the paired
     * and for the crossed view.
     */
    protected static Location getMeasureLocation(Options options, Pair moved, Camera targetCamera,
            double zOffsetMm, double cAngle) {
        Location cameraLocation = targetCamera.getLocation().convertToUnits(LengthUnit.Millimeters);
        if (options.suppressAdvancedCalibration
                && targetCamera instanceof ReferenceCamera) {
            Location fitted = ((ReferenceCamera) targetCamera).getAdvancedCalibration()
                    .getCalibratedOffsets().convertToUnits(LengthUnit.Millimeters);
            cameraLocation = cameraLocation.subtract(fitted);
        }
        if (options.applyStoredAxisOffsets) {
            cameraLocation = cameraLocation.subtract(moved.calibration
                    .getCalibratedAxisOffset(moved.nozzle).convertToUnits(LengthUnit.Millimeters));
        }
        return cameraLocation.derive(null, null, getCalibrationZ(moved, zOffsetMm), cAngle);
    }

    /**
     * One shot: the tip of the pair as seen in the given camera, averaged over
     * {@link Options#framesPerShot} frames.
     *
     * The light is switched on once for the whole shot and kept there, because the lamps take a few
     * milliseconds to strike and the vision pipeline by itself switches them on and off around every
     * single capture: eight frames would then be eight strikes, and every one of them exposes while
     * the lamp is still coming up. Keeping the light on is what the machine's own batch operation is
     * for, and once the light is on, the pipeline's own actuation is a no-op because the actuator is
     * already at that value.
     */
    protected static Shot measure(Options options, Pair pair, Camera camera,
            Location measureLocation, String moveName, double zOffsetMm, double cAngle, int repeat) {
        Shot shot = new Shot();
        shot.moveName = moveName;
        shot.nozzle = pair.nozzle;
        shot.camera = camera;
        shot.zOffsetMm = zOffsetMm;
        shot.cAngle = cAngle;
        shot.repeat = repeat;

        Machine machine = Configuration.get().getMachine();
        CameraBatchOperation batch = machine == null ? null : machine.getCameraBatchOperation();
        try {
            if (batch != null) {
                batch.startBatchOperation("displacement");
            }
            camera.actuateLightBeforeCapture();
            sleep(options.lightWarmupMs);

            // A frame without contrast is not a measurement: given noise, a circle detector will find
            // a circle anyway, and a shot like that poisons the whole run. Judge the frame now that
            // the light is actually up.
            BufferedImage frame = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                frame = capture(camera);
                shot.imageStdDev = contrast(frame);
                shot.imageSharpness = sharpness(frame);
                if (shot.imageStdDev >= options.minImageStdDev) {
                    break;
                }
                Logger.info("Displacement shot {} in camera {}: frame contrast {} below {}, attempt {}",
                        moveName, camera.getName(), f(shot.imageStdDev), f(options.minImageStdDev));
                sleep(400);
            }
            if (shot.imageStdDev < options.minImageStdDev) {
                shot.note = "frame without contrast "+f(shot.imageStdDev)+", not measured";
                if (options.saveImages) {
                    saveImage(options, shot, frame, -1);
                }
                return shot;
            }
            if (options.saveImages && options.saveFrames) {
                saveImage(options, shot, frame, -1);
            }

            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            Length wantedDiameter = options.holeDiameterMm > 0. ?
                    new Length(options.holeDiameterMm, LengthUnit.Millimeters) : null;
            int wanted = Math.max(1, options.framesPerShot);
            for (int frameIndex = 0; frameIndex < wanted; frameIndex++) {
                try {
                    Location measured = pair.calibration.measureTip(pair.nozzle, camera, measureLocation,
                            wantedDiameter);
                    if (measured == null) {
                        shot.note = "nothing detected";
                        continue;
                    }
                    measured = measured.convertToUnits(LengthUnit.Millimeters);
                    xs.add(measured.getX());
                    ys.add(measured.getY());
                    shot.frameValues.add(new double[] {measured.getX(), measured.getY(),
                            frameInstantMs(camera)});
                    if (options.saveImages && options.saveFrames) {
                        saveImage(options, shot, buffered(camera), frameIndex);
                    }
                }
                catch (Exception e) {
                    shot.note = e.getMessage() == null ? e.toString() : e.getMessage();
                    Logger.info("Displacement shot {} of tip {} in camera {} failed: {}", moveName,
                            pair.nozzle.getName(), camera.getName(), shot.note);
                }
            }
            if (! xs.isEmpty()) {
                shot.measured = new Location(LengthUnit.Millimeters, mean(xs), mean(ys), 0, 0);
                shot.frames = xs.size();
                if (xs.size() > 1) {
                    shot.frameSpreadMm = Math.sqrt(variance(xs)+variance(ys));
                }
                Set<Long> instants = new LinkedHashSet<>();
                for (double[] value : shot.frameValues) {
                    if (value[2] != FrameProvenance.UNKNOWN) {
                        instants.add((long)value[2]);
                    }
                }
                shot.distinctFrames = instants.size();
                if (shot.frames > 1 && shot.distinctFrames == 1) {
                    shot.note = (shot.note.isEmpty() ? "" : shot.note+", ")
                            +"camera delivered the same frame "+shot.frames+" times";
                }
                if (shot.frames < wanted && ! shot.note.contains("same frame")) {
                    shot.note = (shot.note.isEmpty() ? "" : shot.note+", ")+shot.frames+" of "+wanted
                            +" frames";
                }
            }
            if (options.saveImages && ! options.saveFrames) {
                saveImage(options, shot, frame, -1);
            }
            return shot;
        }
        catch (Exception e) {
            shot.note = e.getMessage() == null ? e.toString() : e.getMessage();
            Logger.info("Displacement shot {} in camera {}: {}", moveName, camera.getName(), shot.note);
            return shot;
        }
        finally {
            if (batch != null) {
                try {
                    batch.endBatchOperation("displacement");
                }
                catch (Exception e) {
                    Logger.info("Could not end the displacement light batch: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * The instant the camera says the frame it last handed out was taken, or
     * {@link FrameProvenance#UNKNOWN} if it cannot say. A camera that grabs ahead knows, and that is
     * precisely the camera that can hand out the same frame twice.
     */
    protected static double frameInstantMs(Camera camera) {
        if (camera instanceof ReferenceCamera) {
            long capturedAtMs = ((ReferenceCamera) camera).getRawFrameCapturedAtMs();
            return capturedAtMs == FrameProvenance.UNKNOWN ? FrameProvenance.UNKNOWN : capturedAtMs;
        }
        return FrameProvenance.UNKNOWN;
    }

    protected static void sleep(long milliseconds) {
        if (milliseconds <= 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A frame of the camera, settled and copied.
     *
     * Both matter. The copy, because a camera that grabs its frames ahead of being asked hands out a
     * view of a buffer it is still filling, and a frame that must survive until the shot is written
     * to disk has to be taken hold of at once. Settling, because the buffer is also whatever the
     * camera last looked at: after a crossed shot the head has moved on for the other camera's sake,
     * and the frame still lying in this camera's buffer is the empty field of the previous shot, i.e.
     * the lamp and no tip at all. Judging that frame, or saving it as if it were the shot, tells
     * nothing about the tip, which is how the contrast and focus notes came out inverted while the
     * detections themselves were fine.
     */
    protected static BufferedImage capture(Camera camera) {
        return copied(camera, true);
    }

    /**
     * The frame the camera is holding, without waiting for a new one. Right after a pipeline run the
     * buffer is the frame that pipeline just settled for, so this is the cheap way to picture every
     * frame of a shot; judging or probing with it would be wrong, see capture().
     */
    protected static BufferedImage buffered(Camera camera) {
        return copied(camera, false);
    }

    private static BufferedImage copied(Camera camera, boolean settle) {
        try {
            BufferedImage image = settle ? camera.settleAndCapture() : camera.captureTransformed();
            if (image == null) {
                return null;
            }
            BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = copy.createGraphics();
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            return copy;
        }
        catch (Exception e) {
            Logger.debug("Could not capture from {}: {}", camera.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * The luma of a window centred on the optical centre, i.e. on the point the tip is measured
     * against. Judging a corner of the frame instead would report whatever the cameras sit over
     * rather than the tip.
     */
    protected static double[][] lumaWindow(BufferedImage frame, int wanted) {
        if (frame == null) {
            return null;
        }
        try {
            int width = Math.min(frame.getWidth(), wanted);
            int height = Math.min(frame.getHeight(), wanted);
            int x = Math.max(0, (frame.getWidth()-width)/2);
            int y = Math.max(0, (frame.getHeight()-height)/2);
            width = Math.min(width, frame.getWidth()-x);
            height = Math.min(height, frame.getHeight()-y);
            if (width < 3 || height < 3) {
                return null;
            }
            int[] pixels = frame.getRGB(x, y, width, height, null, 0, width);
            double[][] luma = new double[height][width];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int rgb = pixels[row*width+column];
                    luma[row][column] = 0.299*((rgb >> 16) & 0xff)+0.587*((rgb >> 8) & 0xff)
                            +0.114*(rgb & 0xff);
                }
            }
            return luma;
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * The standard deviation of the pixels of a frame, on the 0..255 scale. A frame in which the
     * light is off or the exposure has not ramped up yet has almost none.
     */
    protected static double contrast(BufferedImage frame) {
        double[][] luma = lumaWindow(frame, 256);
        if (luma == null) {
            return 0.;
        }
        double sum = 0.;
        double square = 0.;
        int count = 0;
        for (double[] row : luma) {
            for (double value : row) {
                sum += value;
                square += value*value;
                count++;
            }
        }
        double mean = sum/count;
        return Math.sqrt(Math.max(0., square/count-mean*mean));
    }

    /**
     * The root-mean-square of the Laplacian of a frame, i.e. how well defined its edges are. Defocus is what
     * takes it down, and the tip of one camera being softer than the tip of the other means the two
     * are no longer looking at the same edge.
     */
    protected static double sharpness(BufferedImage frame) {
        double[][] luma = lumaWindow(frame, 256);
        if (luma == null) {
            return 0.;
        }
        double sum = 0.;
        double square = 0.;
        int count = 0;
        for (int row = 1; row+1 < luma.length; row++) {
            for (int column = 1; column+1 < luma[row].length; column++) {
                double laplacian = 4.*luma[row][column]-luma[row-1][column]-luma[row+1][column]
                        -luma[row][column-1]-luma[row][column+1];
                sum += laplacian;
                square += laplacian*laplacian;
                count++;
            }
        }
        double mean = sum/count;
        return Math.sqrt(Math.max(0., square/count-mean*mean));
    }

    protected static void saveImage(Options options, Shot shot, BufferedImage frame, int frameIndex) {
        if (frame == null || options.directory == null) {
            return;
        }
        try {
            File images = new File(options.directory, "images");
            images.mkdirs();
            // The move belongs in the name: a matrix shot and a batch shot of the same tip in the
            // same camera are two different pictures of the same object and must not overwrite.
            String name = String.format(Locale.ENGLISH, "%s_%s-%s_z%+.2f_c%+.0f_r%d%s",
                    shot.moveName, shot.nozzle.getName(), shot.camera.getName(), shot.zOffsetMm,
                    shot.cAngle, shot.repeat, frameIndex < 0 ? "" : "_f"+frameIndex);
            name = name.replaceAll("[^A-Za-z0-9.+-]", "-");
            ImageIO.write(frame, "png", new File(images, name+".png"));
        }
        catch (Exception e) {
            Logger.debug("Could not save displacement image: {}", e.getMessage());
        }
    }

    protected static double mean(List<Double> values) {
        double sum = 0.;
        for (double value : values) {
            sum += value;
        }
        return sum/values.size();
    }

    protected static double variance(List<Double> values) {
        if (values.size() < 2) {
            return 0.;
        }
        double mean = mean(values);
        double square = 0.;
        for (double value : values) {
            square += (value-mean)*(value-mean);
        }
        return square/(values.size()-1);
    }

    /**
     * Least squares solve of X + c = u - e under the gauge sum(e) = 0, done per axis.
     */
    protected static void solve(ConditionResult condition, List<Pair> pairs, List<Shot> shots,
            Options options) {
        int n = pairs.size();
        int p = 2*n - 1; // u_0..u_{n-1}, e_0..e_{n-2}; the last camera's e is -sum of the others

        for (Pair pair : pairs) {
            CameraResult cameraResult = new CameraResult();
            cameraResult.camera = pair.camera;
            cameraResult.stored = pair.camera.getDisplacement()
                    .convertToUnits(LengthUnit.Millimeters);
            condition.cameraResults.add(cameraResult);
            NozzleResult nozzleResult = new NozzleResult();
            nozzleResult.nozzle = pair.nozzle;
            nozzleResult.nozzleTip = pair.nozzle.getCalibrationNozzleTip();
            nozzleResult.storedAxisOffset = pair.calibration
                    .getCalibratedAxisOffset(pair.nozzle).convertToUnits(LengthUnit.Millimeters);
            double[] fit = pair.calibration.getCalibratedRunoutFitError(pair.nozzle);
            if (fit != null) {
                nozzleResult.modelPeakErrorMm = fit[0];
                nozzleResult.modelRmsErrorMm = fit[1];
            }
            nozzleResult.error = new Location(LengthUnit.Millimeters);
            condition.nozzleResults.add(nozzleResult);
        }

        double totalSquare = 0.;
        int rows = 0;
        for (int axis = 0; axis < 2; axis++) {
            List<Integer> usedNozzles = new ArrayList<>();
            List<Integer> usedCameras = new ArrayList<>();
            List<Double> usedValues = new ArrayList<>();
            for (Shot shot : shots) {
                if (shot.measured == null) {
                    continue;
                }
                int k = indexOf(pairs, shot.nozzle);
                int j = indexOfCamera(pairs, shot.camera);
                if (k < 0 || j < 0) {
                    continue;
                }
                double v = (axis == 0 ? shot.measured.getX() : shot.measured.getY());
                if (options.applyStoredAxisOffsets) {
                    Location c = pairs.get(k).calibration
                            .getCalibratedAxisOffset(pairs.get(k).nozzle)
                            .convertToUnits(LengthUnit.Millimeters);
                    v += (axis == 0 ? c.getX() : c.getY());
                }
                usedNozzles.add(k);
                usedCameras.add(j);
                usedValues.add(v);
            }
            int[] nozzleIndex = new int[usedNozzles.size()];
            int[] cameraIndex = new int[usedCameras.size()];
            double[] y = new double[usedValues.size()];
            for (int r = 0; r < y.length; r++) {
                nozzleIndex[r] = usedNozzles.get(r);
                cameraIndex[r] = usedCameras.get(r);
                y[r] = usedValues.get(r);
            }
            double[] x = leastSquares(n, nozzleIndex, cameraIndex, y);
            if (x == null) {
                continue;
            }
            double[][] inverse = normalInverse(n, nozzleIndex, cameraIndex);
            double square = 0.;
            for (int r = 0; r < y.length; r++) {
                double predicted = predict(n, x, nozzleIndex[r], cameraIndex[r]);
                square += (predicted - y[r])*(predicted - y[r]);
            }
            double sigma = Math.sqrt(square/Math.max(1, y.length - p));
            totalSquare += square;
            rows += y.length;
            for (int i = 0; i < n; i++) {
                double ei = predictE(n, x, i);
                NozzleResult nozzleResult = condition.nozzleResults.get(i);
                CameraResult cameraResult = condition.cameraResults.get(i);
                double eVariance = inverse == null ? 0.
                        : (i < n-1 ? inverse[n+i][n+i] : gaugeVariance(inverse, n));
                double uVariance = inverse == null ? 0. : inverse[i][i];
                if (axis == 0) {
                    nozzleResult.error = nozzleResult.error.derive(x[i], null, null, null);
                    nozzleResult.sigmaX = sigma*sqrt(uVariance);
                    cameraResult.error = new Location(LengthUnit.Millimeters, ei, 0, 0, 0);
                    cameraResult.sigmaX = sigma*sqrt(eVariance);
                }
                else {
                    nozzleResult.error = nozzleResult.error.derive(null, x[i], null, null);
                    nozzleResult.sigmaY = sigma*sqrt(uVariance);
                    cameraResult.error = cameraResult.error.derive(null, ei, null, null);
                    cameraResult.sigmaY = sigma*sqrt(eVariance);
                    cameraResult.proposed = cameraResult.stored.add(cameraResult.error);
                }
            }
        }
        condition.shots = rows/2;
        condition.residualMm = rows > 0
                ? Math.sqrt(totalSquare/Math.max(1, rows - 2*p))
                : Double.NaN;
        for (int i = 0; i < n; i++) {
            NozzleResult nozzleResult = condition.nozzleResults.get(i);
            CameraResult cameraResult = condition.cameraResults.get(i);
            // the pair offset as measured, minus what the runout calibration has stored for it
            nozzleResult.pairResidual = nozzleResult.error.subtract(cameraResult.error)
                    .subtract(nozzleResult.storedAxisOffset);
            nozzleResult.pairResidualMm = nozzleResult.pairResidual.getLinearDistanceTo(0, 0);
        }
    }



    /**
     * The design row of one observation: model value u_k - e_j, with the last camera's e eliminated
     * through the gauge sum(e) = 0.
     */
    public static void designRow(int n, int k, int j, double[] row) {
        int p = 2*n - 1;
        java.util.Arrays.fill(row, 0.);
        row[k] += 1.;
        if (j < n-1) {
            row[n+j] -= 1.;
        }
        else {
            for (int q = 0; q < n-1; q++) {
                row[n+q] += 1.;
            }
        }
    }

    public static double predict(int n, double[] x, int k, int j) {
        return x[k] - predictE(n, x, j);
    }

    public static double predictE(int n, double[] x, int j) {
        if (j < n-1) {
            return x[n+j];
        }
        return -sum(x, n, 2*n - 2);
    }

    /**
     * Least squares solution of u_k - e_j = y[r] for every r, gauge sum(e) = 0. Returns the unknowns
     * u_0..u_{n-1}, e_0..e_{n-2} or null when the observations do not constrain them.
     */
    public static double[] leastSquares(int n, int[] nozzleIndex, int[] cameraIndex, double[] y) {
        double[][] ata = normalMatrix(n, nozzleIndex, cameraIndex);
        if (ata == null) {
            return null;
        }
        int p = 2*n - 1;
        double[] aty = new double[p];
        double[] row = new double[p];
        for (int r = 0; r < y.length; r++) {
            designRow(n, nozzleIndex[r], cameraIndex[r], row);
            for (int a = 0; a < p; a++) {
                aty[a] += row[a]*y[r];
            }
        }
        double[][] inverse = invert(ata);
        if (inverse == null) {
            return null;
        }
        double[] x = new double[p];
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < p; b++) {
                x[a] += inverse[a][b]*aty[b];
            }
        }
        return x;
    }

    protected static double[][] normalMatrix(int n, int[] nozzleIndex, int[] cameraIndex) {
        int p = 2*n - 1;
        if (nozzleIndex.length < p) {
            return null;
        }
        double[][] ata = new double[p][p];
        double[] row = new double[p];
        for (int r = 0; r < nozzleIndex.length; r++) {
            designRow(n, nozzleIndex[r], cameraIndex[r], row);
            for (int a = 0; a < p; a++) {
                if (row[a] == 0.) {
                    continue;
                }
                for (int b = 0; b < p; b++) {
                    ata[a][b] += row[a]*row[b];
                }
            }
        }
        return ata;
    }

    protected static double[][] normalInverse(int n, int[] nozzleIndex, int[] cameraIndex) {
        double[][] ata = normalMatrix(n, nozzleIndex, cameraIndex);
        return ata == null ? null : invert(ata);
    }

    protected static double gaugeVariance(double[][] inverse, int n) {
        double v = 0.;
        for (int a = n; a < 2*n - 1; a++) {
            for (int b = n; b < 2*n - 1; b++) {
                v += inverse[a][b];
            }
        }
        return v;
    }

    /**
     * The batch table: where every tip sits in its own camera when the batch is centred on one of
     * the nozzles, predicted from the solved errors and compared with a direct measurement. The
     * predicted value is what the runtime analysis compensation has to remove for that nozzle, so the
     * difference between the two is the end to end check of the whole model.
     */
    protected static void batch(ConditionResult condition, List<Pair> pairs, List<Shot> shots) {
        for (Pair primary : pairs) {
            NozzleResult primaryStage = findNozzle(condition, primary.nozzle);
            List<BatchCell> cells = new ArrayList<>();
            if (primaryStage != null) {
                Location primaryCamera = primary.camera.getLocation()
                        .convertToUnits(LengthUnit.Millimeters);
                // The commanded position of the primary nozzle when the batch is centred on it.
                Location head = primaryCamera.subtract(primaryStage.storedAxisOffset);
                Location primaryHeadOffset = primary.nozzle.getHeadOffsets()
                        .convertToUnits(LengthUnit.Millimeters);
                for (Pair pair : pairs) {
                    NozzleResult nozzleResult = findNozzle(condition, pair.nozzle);
                    CameraResult cameraResult = findCamera(condition, pair.camera);
                    if (nozzleResult == null || cameraResult == null) {
                        continue;
                    }
                    BatchCell cell = new BatchCell();
                    cell.nozzle = pair.nozzle;
                    cell.camera = pair.camera;
                    cell.primary = pair.nozzle == primary.nozzle;
                    Location cameraAxis = pair.camera.getLocation()
                            .convertToUnits(LengthUnit.Millimeters).add(cameraResult.error);
                    Location headOffset = pair.nozzle.getHeadOffsets()
                            .convertToUnits(LengthUnit.Millimeters);
                    // head - H_primary + t_i - (C_i + e_i), with t_i = u_i + H_i
                    cell.predicted = head.subtract(primaryHeadOffset).add(nozzleResult.error)
                            .add(headOffset).subtract(cameraAxis);
                    cells.add(cell);
                }
            }
            condition.batch.put(primary.nozzle.getName(), cells);
        }
        for (Shot shot : shots) {
            if (shot.primary == null) {
                continue;
            }
            List<BatchCell> cells = condition.batch.get(shot.primary.getName());
            if (cells == null) {
                continue;
            }
            for (BatchCell cell : cells) {
                if (cell.nozzle == shot.nozzle && shot.measured != null) {
                    cell.measurements.add(shot.measured);
                }
            }
        }
        for (List<BatchCell> cells : condition.batch.values()) {
            for (BatchCell cell : cells) {
                if (cell.measurements.isEmpty()) {
                    continue;
                }
                double x = 0.;
                double y = 0.;
                for (Location l : cell.measurements) {
                    x += l.getX();
                    y += l.getY();
                }
                cell.measured = new Location(LengthUnit.Millimeters,
                        x/cell.measurements.size(), y/cell.measurements.size(), 0, 0);
                cell.errorMm = cell.measured.getLinearDistanceTo(cell.predicted);
            }
        }
    }

    /**
     * The repeatability and the tilt. The spread is the sample standard deviation of a single
     * tip/camera combination over the repeats, worst combination reported, and it is the floor under
     * every other number of the run. The tilt is d(offset)/dZ per camera and needs more than one Z.
     */
    protected static void statistics(Result result, Options options) {
        for (ConditionResult condition : result.conditions) {
            // Repeatability is per move: a matrix shot and a batch shot of the same tip in the same
            // camera are taken from two different head positions and are not repeats of each other.
            Map<String, List<Location>> byMove = new LinkedHashMap<>();
            for (Shot shot : condition.allShots) {
                if (shot.measured == null) {
                    continue;
                }
                String key = shot.moveName+"/"+shot.nozzle.getName()+"→"+shot.camera.getName();
                List<Location> list = byMove.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    byMove.put(key, list);
                }
                list.add(shot.measured);
                if (shot.frameSpreadMm == shot.frameSpreadMm
                        && (condition.frameSpreadMm != condition.frameSpreadMm
                                || shot.frameSpreadMm > condition.frameSpreadMm)) {
                    condition.frameSpreadMm = shot.frameSpreadMm;
                    condition.frameSpreadOf = key;
                }
            }
            double worst = 0.;
            String worstKey = "";
            for (Map.Entry<String, List<Location>> entry : byMove.entrySet()) {
                List<Location> list = entry.getValue();
                if (list.size() < 2) {
                    continue;
                }
                double x = 0.;
                double y = 0.;
                for (Location l : list) {
                    x += l.getX();
                    y += l.getY();
                }
                x /= list.size();
                y /= list.size();
                double square = 0.;
                for (Location l : list) {
                    square += (l.getX()-x)*(l.getX()-x)+(l.getY()-y)*(l.getY()-y);
                }
                double deviation = Math.sqrt(square/(list.size()-1));
                if (deviation > worst) {
                    worst = deviation;
                    worstKey = entry.getKey();
                }
            }
            condition.spreadMm = worst;
            condition.spreadOf = worstKey;

            Map<Integer, Integer> rows = new LinkedHashMap<>();
            Set<String> matrixNozzles = new LinkedHashSet<>();
            Set<String> matrixCameras = new LinkedHashSet<>();
            List<String> stale = new ArrayList<>();
            for (Shot shot : condition.allShots) {
                if (shot.primary != null && shot.measured != null) {
                    rows.put(shot.repeat, Integer.valueOf((rows.get(shot.repeat) == null ? 0
                            : rows.get(shot.repeat).intValue())+1));
                    matrixNozzles.add(shot.nozzle.getName());
                    matrixCameras.add(shot.camera.getName());
                }
                if (shot.frames > 1 && shot.distinctFrames == 1) {
                    stale.add(shot.moveName+"/"+shot.nozzle.getName()+"→"+shot.camera.getName());
                }
            }
            for (Integer count : rows.values()) {
                condition.rowsPerRepeat = Math.max(condition.rowsPerRepeat, count.intValue());
            }
            condition.unknowns = 2*Math.min(matrixNozzles.size(), matrixCameras.size())-1;
            for (int i = 0; i < stale.size(); i++) {
                if (i > 2) {
                    stale.set(3, "...");
                    break;
                }
            }
            condition.staleFrames = join(stale, ", ");

            Map<String, double[]> contrast = new LinkedHashMap<>();
            Map<String, double[]> focus = new LinkedHashMap<>();
            for (Shot shot : condition.allShots) {
                if (shot.imageStdDev == shot.imageStdDev && shot.imageStdDev > 0.) {
                    double[] minMax = contrast.get(shot.camera.getName());
                    if (minMax == null) {
                        minMax = new double[] {shot.imageStdDev, shot.imageStdDev};
                        contrast.put(shot.camera.getName(), minMax);
                    }
                    minMax[0] = Math.min(minMax[0], shot.imageStdDev);
                    minMax[1] = Math.max(minMax[1], shot.imageStdDev);
                }
                if (shot.imageSharpness == shot.imageSharpness && shot.imageSharpness > 0.) {
                    double[] minMax = focus.get(shot.camera.getName());
                    if (minMax == null) {
                        minMax = new double[] {shot.imageSharpness, shot.imageSharpness};
                        focus.put(shot.camera.getName(), minMax);
                    }
                    minMax[0] = Math.min(minMax[0], shot.imageSharpness);
                    minMax[1] = Math.max(minMax[1], shot.imageSharpness);
                }
            }
            List<String> unstable = new ArrayList<>();
            for (Map.Entry<String, double[]> entry : contrast.entrySet()) {
                if (entry.getValue()[0]*2. < entry.getValue()[1]) {
                    unstable.add(entry.getKey()+" contrast "+f(entry.getValue()[0])+".."+f(entry.getValue()[1]));
                }
            }
            condition.contrastNote = join(unstable, ", ");

            // Sharpness per camera relative to the sharpest camera of the condition. The displacement
            // is the difference of what two cameras see of one tip, so a camera whose tip is soft is
            // not a camera that measures the tip badly, it is a camera that measures something else.
            double sharpest = 0.;
            for (double[] minMax : focus.values()) {
                sharpest = Math.max(sharpest, minMax[1]);
            }
            List<String> soft = new ArrayList<>();
            for (Map.Entry<String, double[]> entry : focus.entrySet()) {
                if (sharpest <= 0.) {
                    break;
                }
                double ratio = entry.getValue()[1]/sharpest;
                soft.add(entry.getKey()+" "+f(ratio));
                if (condition.focusRatio != condition.focusRatio) {
                    condition.focusRatio = ratio;
                }
                condition.focusRatio = Math.min(condition.focusRatio, ratio);
            }
            condition.focusNote = join(soft, ", ");

            closure(condition, options);
        }

        // d(offset)/dZ per camera, from the mean of the shots taken in front of that camera at each
        // of the measured Z levels.
        List<Double> levels = new ArrayList<>();
        for (ConditionResult condition : result.conditions) {
            if (! levels.contains(condition.zOffsetMm)) {
                levels.add(condition.zOffsetMm);
            }
        }
        if (levels.size() < 2) {
            return;
        }
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        for (ConditionResult condition : result.conditions) {
            Map<String, double[]> sums = new LinkedHashMap<>();
            for (Shot shot : condition.allShots) {
                if (shot.measured == null || shot.primary != null) {
                    continue;
                }
                String key = shot.camera.getName();
                double[] sum = sums.get(key);
                if (sum == null) {
                    sum = new double[3];
                    sums.put(key, sum);
                }
                sum[0] += shot.measured.getX();
                sum[1] += shot.measured.getY();
                sum[2] += 1.;
            }
            for (Map.Entry<String, double[]> entry : sums.entrySet()) {
                double[] sum = entry.getValue();
                if (sum[2] < 1.) {
                    continue;
                }
                List<double[]> list = points.get(entry.getKey());
                if (list == null) {
                    list = new ArrayList<>();
                    points.put(entry.getKey(), list);
                }
                list.add(new double[] {condition.zOffsetMm, sum[0]/sum[2], sum[1]/sum[2]});
            }
        }
        for (Map.Entry<String, List<double[]>> entry : points.entrySet()) {
            List<double[]> list = entry.getValue();
            if (list.size() < 2) {
                continue;
            }
            double meanZ = 0.;
            for (double[] p : list) {
                meanZ += p[0];
            }
            meanZ /= list.size();
            double zz = 0.;
            double zx = 0.;
            double zy = 0.;
            for (double[] p : list) {
                zz += (p[0]-meanZ)*(p[0]-meanZ);
                zx += (p[0]-meanZ)*p[1];
                zy += (p[0]-meanZ)*p[2];
            }
            if (zz == 0.) {
                continue;
            }
            result.tiltMmPerMm.put(entry.getKey(), new double[] {zx/zz, zy/zz});
        }
    }

    /**
     * The rigidity check, per repeat. Within one repeat the head is one rigid body, so its shots
     * have to be consistent with u_k - e_j, and the residual of that fit is a violation of geometry
     * rather than a measurement uncertainty: a tip misdetected, a move not settled, or something
     * that moved between the shots. It is evaluated on every repeat separately because the overall
     * fit averages the repeats and so hides exactly what is being looked for. For two pairs this is
     * half of the loop closure X(1&rarr;1)+X(2&rarr;2)-X(1&rarr;2)-X(2&rarr;1).
     */
    protected static void closure(ConditionResult condition, Options options) {
        List<String> nozzles = new ArrayList<>();
        List<String> cameras = new ArrayList<>();
        for (Shot shot : condition.allShots) {
            if (shot.primary != null || shot.measured == null) {
                continue;
            }
            if (! nozzles.contains(shot.nozzle.getName())) {
                nozzles.add(shot.nozzle.getName());
            }
            if (! cameras.contains(shot.camera.getName())) {
                cameras.add(shot.camera.getName());
            }
        }
        int n = Math.min(nozzles.size(), cameras.size());
        if (n < 2) {
            return;
        }
        Map<Integer, List<Shot>> byRepeat = new LinkedHashMap<>();
        for (Shot shot : condition.allShots) {
            if (shot.primary != null || shot.measured == null) {
                continue;
            }
            if (nozzles.indexOf(shot.nozzle.getName()) >= n
                    || cameras.indexOf(shot.camera.getName()) >= n) {
                continue;
            }
            List<Shot> list = byRepeat.get(shot.repeat);
            if (list == null) {
                list = new ArrayList<>();
                byRepeat.put(shot.repeat, list);
            }
            list.add(shot);
        }
        double worst = 0.;
        StringBuilder note = new StringBuilder();
        for (Map.Entry<Integer, List<Shot>> entry : byRepeat.entrySet()) {
            double worstAxis = 0.;
            boolean fitted = false;
            for (int axis = 0; axis < 2; axis++) {
                List<Shot> shots = entry.getValue();
                if (shots.size() < 2*(n-1)+1) {
                    continue;
                }
                int[] nozzleIndex = new int[shots.size()];
                int[] cameraIndex = new int[shots.size()];
                double[] y = new double[shots.size()];
                for (int r = 0; r < shots.size(); r++) {
                    Shot shot = shots.get(r);
                    nozzleIndex[r] = nozzles.indexOf(shot.nozzle.getName());
                    cameraIndex[r] = cameras.indexOf(shot.camera.getName());
                    y[r] = axis == 0 ? shot.measured.getX() : shot.measured.getY();
                }
                double[] x = leastSquares(n, nozzleIndex, cameraIndex, y);
                if (x == null) {
                    continue;
                }
                double square = 0.;
                for (int r = 0; r < y.length; r++) {
                    double predicted = predict(n, x, nozzleIndex[r], cameraIndex[r]);
                    square += (predicted-y[r])*(predicted-y[r]);
                }
                int dof = y.length-(2*n-1);
                if (dof <= 0) {
                    // Nothing to check: the shots fit the unknowns exactly, whatever they are. Say so
                    // rather than reporting a beautiful zero.
                    condition.closureNote = "no redundancy, "+y.length+" shots for "+(2*n-1)
                            +" unknowns";
                    continue;
                }
                worstAxis = Math.max(worstAxis, Math.sqrt(square/dof));
                fitted = true;
            }
            if (! fitted) {
                continue;
            }
            if (note.length() > 0) {
                note.append(", ");
            }
            note.append("repeat ").append(entry.getKey()).append(" ").append(f(worstAxis))
                    .append("mm");
            worst = Math.max(worst, worstAxis);
        }
        condition.closureMm = worst;
        condition.closureNote = note.toString();
    }

    protected static void check(Result result, Options options) {
        if (result.conditions.isEmpty()) {
            result.problems.add("Nothing measured.");
            return;
        }
        result.valid = true;
        for (ConditionResult condition : result.conditions) {
            if (condition.shots == 0) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": nothing detected at all. Check the calibration pipelines and lighting.");
                result.valid = false;
                continue;
            }
            if (condition.residualMm != condition.residualMm) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": the system could not be solved, the shots do not constrain the unknowns."
                        +" crossedMatrix must be on to separate the nozzle side from the camera side.");
                result.valid = false;
            }
            else if (condition.residualMm > options.maxResidualMm) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": fit residual "+f(condition.residualMm)+"mm over the "+f(options.maxResidualMm)
                        +"mm limit, so the shots were not taken on one rigid machine. Nothing else from"
                        +" this condition can be trusted: look for a misdetection, a knock, or a stale Z.");
                result.valid = false;
            }
            if (condition.spreadMm == condition.spreadMm
                    && condition.spreadMm > options.maxSpreadMm) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": spread "+f(condition.spreadMm)+"mm on "+condition.spreadOf
                        +" over the "+f(options.maxSpreadMm)+"mm limit. The run may not claim an error"
                        +" smaller than this; settle and lighting are the usual causes.");
                result.valid = false;
            }
            if (condition.rowsPerRepeat > 0 && condition.rowsPerRepeat <= condition.unknowns) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": only "+condition.rowsPerRepeat+" matrix shots per repeat for "
                        +condition.unknowns+" unknowns, so the system is exactly determined and the "
                        +"fit residual of "+f(condition.residualMm)+"mm is arithmetic, not evidence. "
                        +"Usually the crossed shots are missing: they are the ones that separate the "
                        +"nozzle spacing error from the camera displacement, and without them the run "
                        +"measures nothing. Check why those shots failed, the lighting is where to "
                        +"look first.");
                result.valid = false;
            }
            if (! condition.staleFrames.isEmpty()) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": the camera delivered the same frame several times in "+condition.staleFrames
                        +", so what looks like an average of several views is one view counted twice. "
                        +"The scatter of such a shot is meaningless and its mean is not the average of"
                        +" anything; a camera that grabs ahead of being asked needs its frames to be "
                        +"new, not merely present.");
                result.valid = false;
            }
            if (condition.closureMm != condition.closureMm && condition.rowsPerRepeat > 0
                    && ! condition.closureNote.isEmpty()) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": "+condition.closureNote+", so nothing about this run can be checked "
                        +"against the rigidity of the head.");
                result.valid = false;
            }
            if (condition.closureMm == condition.closureMm
                    && condition.closureMm > options.maxClosureMm) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": the shots of a single repeat do not close, worst "
                        +f(condition.closureMm)+"mm over the "+f(options.maxClosureMm)
                        +"mm limit ("+condition.closureNote+"). The head is rigid, so this is not"
                        +" measurement scatter but a shot that is simply wrong: look for a tip picked"
                        +" up out of focus, a move still settling, or a frame taken before the light"
                        +" was up. The camera displacement itself is the difference of these shots and"
                        +" is less affected than the individual errors, but nothing here is a number"
                        +" to write back.");
                result.valid = false;
            }
            if (! condition.contrastNote.isEmpty()) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": the frame contrast of a camera varied by more than a factor two within the "
                        +"run ("+condition.contrastNote+"), so the lighting is not the same from shot to "
                        +"shot. Thresholds and circle fitting are not brightness invariant; keep the "
                        +"light on over a shot and let it burn before the first frame.");
                result.valid = false;
            }
            if (condition.focusRatio == condition.focusRatio
                    && condition.focusRatio < 1./options.maxFocusRatio) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": the cameras are not equally well focused on the tip (sharpness relative to "
                        +"the sharpest camera: "+condition.focusNote+"), over the 1/"
                        +f(options.maxFocusRatio)+" limit. The displacement is the difference of what "
                        +"two cameras see of one tip, so if one of them sees a soft tip it measures the "
                        +"middle of that tip's penumbra rather than the tip. No amount of averaging or "
                        +"closure checking fixes this: make the tip plane of both cameras the same, or "
                        +"measure the cameras one at a time at their own focus.");
                result.valid = false;
            }
            if (condition.frameSpreadMm == condition.frameSpreadMm
                    && condition.frameSpreadMm > options.maxSpreadMm) {
                result.problems.add("Condition Z+"+f(condition.zOffsetMm)+" C"+f(condition.cAngle)
                        +": a single shot varied "+f(condition.frameSpreadMm)+"mm between its own "
                        +"frames on "+condition.frameSpreadOf+", over the "+f(options.maxSpreadMm)
                        +"mm limit. The tip is not being detected reliably at all, usually because it"
                        +" is blurred or the contrast is too low for the pipeline.");
                result.valid = false;
            }
        }
        for (Map.Entry<String, double[]> entry : result.tiltMmPerMm.entrySet()) {
            double tilt = Math.hypot(entry.getValue()[0], entry.getValue()[1]);
            if (tilt > options.maxTiltMmPerMm) {
                // A tilt says the camera is mounted at an angle, which storing one lateral number does
                // not fix, but it is not a reason to throw the measurement away either: the number is
                // only ever applied within the Z window the machine allows the compensation, so what
                // the slope costs is the slope times that window. Say the size of it and let the
                // operator decide, rather than refusing a displacement that is otherwise good.
                double window = 0.1;
                try {
                    window = Configuration.get().getMachine().getDisplacementMaxZDiff()
                            .convertToUnits(LengthUnit.Millimeters).getValue();
                }
                catch (Exception e) {
                    // The width of the window makes the caveat concrete rather than theoretical, but
                    // it is not worth failing a run over, and a run has to be able to speak outside a
                    // machine.
                }
                result.caveats.add("Camera "+entry.getKey()+": the displacement moves by "+f(tilt)
                        +"mm per mm of Z, over the "+f(options.maxTiltMmPerMm)+"mm/mm limit, so this"
                        +" camera is tilted, not merely displaced. The cost is bounded: the"
                        +" compensation is only ever applied within "+f(window)+"mm of Z, so the tilt"
                        +" can be worth about "+f(tilt*window)+"mm there. Storing the displacement is"
                        +" still worth its while; shimming the camera is what takes the tilt out.");
            }
        }
    }

    protected static NozzleResult findNozzle(ConditionResult condition, ReferenceNozzle nozzle) {
        for (NozzleResult r : condition.nozzleResults) {
            if (r.nozzle == nozzle) {
                return r;
            }
        }
        return null;
    }

    protected static CameraResult findCamera(ConditionResult condition, Camera camera) {
        for (CameraResult r : condition.cameraResults) {
            if (r.camera == camera) {
                return r;
            }
        }
        return null;
    }

    protected static int indexOf(List<Pair> pairs, ReferenceNozzle nozzle) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).nozzle == nozzle) {
                return i;
            }
        }
        return -1;
    }

    protected static int indexOfCamera(List<Pair> pairs, Camera camera) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).camera == camera) {
                return i;
            }
        }
        return -1;
    }

    protected static String makeReport(Result result, Options options) {
        StringBuilder sb = new StringBuilder();
        sb.append("Camera displacement check\n");
        sb.append("=========================\n");
        sb.append("Options: ").append(options).append("\n");
        if (! result.detection.isEmpty()) {
            sb.append("Detection: ").append(result.detection).append("\n");
        }
        if (result.directory != null) {
            sb.append("Directory: ").append(result.directory).append("\n");
        }
        sb.append("\n");
        for (ConditionResult condition : result.conditions) {
            sb.append("Condition Z+").append(f(condition.zOffsetMm)).append("mm C")
                    .append(f(condition.cAngle)).append("deg, ")
                    .append(condition.shots).append(" shots used, fit residual ")
                    .append(f(condition.residualMm)).append("mm, spread ")
                    .append(f(condition.spreadMm)).append("mm");
            if (! condition.spreadOf.isEmpty()) {
                sb.append(" on ").append(condition.spreadOf);
            }
            sb.append(", ").append(condition.rowsPerRepeat).append(" shots/repeat for ")
                    .append(condition.unknowns).append(" unknowns, closure ")
                    .append(f(condition.closureMm)).append("mm");
            if (! condition.closureNote.isEmpty()) {
                sb.append(" (").append(condition.closureNote).append(")");
            }
            if (condition.frameSpreadMm == condition.frameSpreadMm) {
                sb.append(", frame noise ").append(f(condition.frameSpreadMm)).append("mm on ")
                        .append(condition.frameSpreadOf);
            }
            if (! condition.staleFrames.isEmpty()) {
                sb.append(", stale frames on ").append(condition.staleFrames);
            }
            if (! condition.contrastNote.isEmpty()) {
                sb.append(", contrast ").append(condition.contrastNote);
            }
            if (! condition.focusNote.isEmpty()) {
                sb.append(", focus ").append(condition.focusNote);
            }
            sb.append("\n");
            sb.append(String.format(Locale.ENGLISH, "%-10s %10s %10s %10s %10s %10s %8s%n",
                    "camera", "stored x", "err x", "err y", "prop x", "prop y", "sigma"));
            for (CameraResult r : condition.cameraResults) {
                if (r.error == null) {
                    sb.append(r.camera.getName()).append(": not solved\n");
                    continue;
                }
                sb.append(String.format(Locale.ENGLISH, "%-10s %10.4f %10.4f %10.4f %10.4f %10.4f %8.4f%n",
                        r.camera.getName(), r.stored.getX(), r.error.getX(), r.error.getY(),
                        r.proposed.getX(), r.proposed.getY(),
                        Math.hypot(r.sigmaX, r.sigmaY)));
            }
            sb.append("\n");
            sb.append(String.format(Locale.ENGLISH, "%-10s %10s %10s %10s %10s %10s %9s %9s%n",
                    "nozzle", "u x", "u y", "stored c x", "stored c y", "pair res", "model pk",
                    "model rms"));
            for (NozzleResult r : condition.nozzleResults) {
                if (r.error == null) {
                    continue;
                }
                sb.append(String.format(Locale.ENGLISH,
                        "%-10s %10.4f %10.4f %10.4f %10.4f %10.4f %9.4f %9.4f%n",
                        r.nozzle.getName(), r.error.getX(), r.error.getY(),
                        r.storedAxisOffset.getX(), r.storedAxisOffset.getY(),
                        r.pairResidualMm, r.modelPeakErrorMm, r.modelRmsErrorMm));
            }
            sb.append("  model pk and model rms are the error the stored runout model already had when")
                    .append(" it was fitted; pair res is how far it is off today. A pair res of the same")
                    .append(" size as model pk is a calibration that is only as good as it ever was, a")
                    .append(" pair res well above it is one that has gone stale since.\n");
            sb.append("\n");
            for (Map.Entry<String, List<BatchCell>> entry : condition.batch.entrySet()) {
                sb.append("Batch centred on nozzle ").append(entry.getKey()).append(":\n");
                for (BatchCell cell : entry.getValue()) {
                    sb.append(String.format(Locale.ENGLISH, "  %-8s %-8s predicted %8.4f %8.4f",
                            cell.nozzle.getName(), cell.camera.getName(),
                            cell.predicted.getX(), cell.predicted.getY()));
                    if (cell.measured != null) {
                        double offAxisMm = Math.hypot(cell.measured.getX(), cell.measured.getY());
                        double unitsPerPixel = cell.camera.getUnitsPerPixelAtZ()
                                .convertToUnits(LengthUnit.Millimeters).getX();
                        sb.append(String.format(Locale.ENGLISH,
                                "  measured %8.4f %8.4f  err %6.4f%n",
                                cell.measured.getX(), cell.measured.getY(), cell.errorMm));
                        sb.append(String.format(Locale.ENGLISH,
                                "           %8s %8s  off-axis %6.4fmm (%4.1fpx)%n",
                                "", "", offAxisMm, offAxisMm/Math.max(unitsPerPixel, 1e-9)));
                    }
                    else {
                        sb.append("  measured   n/a\n");
                    }
                }
            }
            sb.append("  predicted is this check's model of the batch, measured is where the tip really")
                    .append(" was, and err is the end to end answer for that nozzle. It is not a camera")
                    .append(" displacement: a displacement is seen by the crossed matrix, this is not,")
                    .append(" because the matrix never looks at a tip it has not moved onto the axis.")
                    .append(" The off-axis distance says whether the tip was detected near the middle of")
                    .append(" the image, where a detector is at its best, or out towards the edge.\n");
            sb.append("Limits: residual ").append(f(options.maxResidualMm))
                    .append("mm, closure ").append(f(options.maxClosureMm))
                    .append("mm, spread ").append(f(options.maxSpreadMm)).append("mm, tilt ")
                    .append(f(options.maxTiltMmPerMm)).append("mm per mm of Z\n");
            if (! result.tiltMmPerMm.isEmpty()) {
                for (Map.Entry<String, double[]> entry : result.tiltMmPerMm.entrySet()) {
                    sb.append("tilt of ").append(entry.getKey()).append(": ")
                            .append(f(Math.hypot(entry.getValue()[0], entry.getValue()[1])))
                            .append("mm per mm of Z (")
                            .append(f(entry.getValue()[0])).append(", ")
                            .append(f(entry.getValue()[1])).append(")\n");
                }
            }
            sb.append("\n");
        }
        appendSharpnessByZ(sb, result);
        for (String note : result.notes) {
            sb.append("NOTE: ").append(note).append("\n");
        }
        for (String problem : result.problems) {
            sb.append("PROBLEM: ").append(problem).append("\n");
        }
        for (String caveat : result.caveats) {
            sb.append("CAVEAT: ").append(caveat).append("\n");
        }
        sb.append(result.valid ? "\nVALID" : "\nNOT VALID");
        if (! result.caveats.isEmpty()) {
            sb.append(" - ").append(result.caveats.size()).append(result.caveats.size() == 1
                    ? " caveat" : " caveats").append(" above: they say what the number is worth, not"
                    + " that it is wrong");
        }
        sb.append("\n");
        return sb.toString();
    }

    protected static void writeReport(Result result, Options options) throws IOException {
        if (result.directory == null) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(new File(result.directory, "report.txt"),
                StandardCharsets.UTF_8.name())) {
            writer.print(result.report);
        }
        try (PrintWriter writer = new PrintWriter(new File(result.directory, "results.csv"),
                StandardCharsets.UTF_8.name())) {
            writer.println("zOffsetMm,cAngle,repeat,move,primary,nozzle,camera,xMm,yMm,"
                    +"frames,distinctFrames,frameSpreadMm,imageStdDev,imageSharpness,note");
            for (ConditionResult condition : result.conditions) {
                for (Shot shot : condition.allShots) {
                    writer.printf(Locale.ENGLISH, "%s,%s,%d,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,\"%s\"%n",
                            f(shot.zOffsetMm), f(shot.cAngle), shot.repeat, shot.moveName,
                            shot.primary == null ? "" : shot.primary.getName(),
                            shot.nozzle.getName(), shot.camera.getName(),
                            shot.measured == null ? "" : f(shot.measured.getX()),
                            shot.measured == null ? "" : f(shot.measured.getY()),
                            shot.frames, shot.distinctFrames, num(shot.frameSpreadMm),
                            num(shot.imageStdDev), num(shot.imageSharpness), shot.note);
                }
            }
        }
        try (PrintWriter writer = new PrintWriter(new File(result.directory, "frames.csv"),
                StandardCharsets.UTF_8.name())) {
            writer.println("zOffsetMm,cAngle,repeat,move,primary,nozzle,camera,frame,xMm,yMm,"
                    +"frameAtMs");
            for (ConditionResult condition : result.conditions) {
                for (Shot shot : condition.allShots) {
                    for (int i = 0; i < shot.frameValues.size(); i++) {
                        double[] value = shot.frameValues.get(i);
                        writer.printf(Locale.ENGLISH, "%s,%s,%d,%s,%s,%s,%s,%d,%s,%s,%s%n",
                                f(shot.zOffsetMm), f(shot.cAngle), shot.repeat, shot.moveName,
                                shot.primary == null ? "" : shot.primary.getName(),
                                shot.nozzle.getName(), shot.camera.getName(), i, f(value[0]),
                                f(value[1]), value[2] == FrameProvenance.UNKNOWN ? "" : f(value[2]));
                    }
                }
            }
        }
        try (PrintWriter writer = new PrintWriter(new File(result.directory, "summary.csv"),
                StandardCharsets.UTF_8.name())) {
            writer.printf(Locale.ENGLISH, 
                    "zOffsetMm,cAngle,residualMm,kind,name,storedX,storedY,errorX,errorY,proposedX,proposedY%n");
            for (ConditionResult condition : result.conditions) {
                for (CameraResult r : condition.cameraResults) {
                    if (r.error == null) {
                        continue;
                    }
                    writer.printf(Locale.ENGLISH, "%s,%s,%s,camera,%s,%s,%s,%s,%s,%s,%s%n",
                            f(condition.zOffsetMm), f(condition.cAngle), f(condition.residualMm),
                            r.camera.getName(), f(r.stored.getX()), f(r.stored.getY()),
                            f(r.error.getX()), f(r.error.getY()),
                            f(r.proposed.getX()), f(r.proposed.getY()));
                }
                for (NozzleResult r : condition.nozzleResults) {
                    if (r.error == null) {
                        continue;
                    }
                    writer.printf(Locale.ENGLISH, "%s,%s,%s,nozzle,%s,%s,%s,%s,%s,,%n",
                            f(condition.zOffsetMm), f(condition.cAngle), f(condition.residualMm),
                            r.nozzle.getName(), f(r.storedAxisOffset.getX()),
                            f(r.storedAxisOffset.getY()), f(r.error.getX()), f(r.error.getY()));
                }
                for (Map.Entry<String, List<BatchCell>> entry : condition.batch.entrySet()) {
                    for (BatchCell cell : entry.getValue()) {
                        writer.printf(Locale.ENGLISH, 
                                "%s,%s,batch,nozzle,%s@%s,,,,"+f(cell.predicted.getX())+","+f(cell.predicted.getY())+"%n",
                                f(condition.zOffsetMm), f(condition.cAngle), cell.nozzle.getName(),
                                entry.getKey());
                    }
                }
            }
        }
    }

    protected static String f(double d) {
        return String.format(Locale.ENGLISH, "%.4f", d);
    }

    protected static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(value);
        }
        return sb.toString();
    }

    /**
     * A number for the CSV that is simply empty where there is none, so that a spreadsheet does not
     * turn a missing value into a word.
     */
    protected static String num(double value) {
        return Double.isNaN(value) ? "" : f(value);
    }

    public static double sum(double[] x, int from, int to) {
        double s = 0.;
        for (int i = from; i <= to; i++) {
            s += x[i];
        }
        return s;
    }

    protected static double sqrt(double d) {
        return d > 0. ? Math.sqrt(d) : 0.;
    }

    /**
     * Gauss-Jordan inversion with partial pivoting. Returns null when the matrix is singular, which
     * here means the shots do not constrain all the unknowns.
     */
    protected static double[][] invert(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][2*n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = aIn[i][j];
            }
            a[i][n+i] = 1.;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col+1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double d = a[col][col];
            for (int j = 0; j < 2*n; j++) {
                a[col][j] /= d;
            }
            for (int row = 0; row < n; row++) {
                if (row == col) {
                    continue;
                }
                double f = a[row][col];
                if (f == 0.) {
                    continue;
                }
                for (int j = 0; j < 2*n; j++) {
                    a[row][j] -= f*a[col][j];
                }
            }
        }
        double[][] inverse = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], n, inverse[i], 0, n);
        }
        return inverse;
    }
}
