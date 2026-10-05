package org.openpnp.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.openpnp.vision.pipeline.stages.FilterContours;
import org.openpnp.vision.pipeline.stages.FindContours;
import org.openpnp.vision.pipeline.stages.MaskHsv;
import org.openpnp.vision.pipeline.stages.MinAreaRect;
import org.pmw.tinylog.Logger;

/**
 * What the pipeline itself has to say about a part it refused.
 *
 * <p>A refusal says only how small the measured rectangle was. That is not enough to act on, because
 * the same measurement of 0.21&nbsp;mm can mean a part that has slid under the nozzle shoulder, a part
 * that is where it should be but whose second termination is too dark for the brightness stage, or a
 * contour that was thrown away for being too small. Those three need three different remedies, and
 * they cannot be told apart from the refusal message.</p>
 *
 * <p>So this reads the answer out of the stages that are already there and have already run: how much
 * of the image each brightness mask let through, and what contours were found before and after the
 * area filter. A part whose two terminations are both present shows two contours of a termination's
 * size; a part that has become one termination shows one. Nothing new is computed about the pixels
 * beyond a count, which is why this can be done at the moment of refusal instead of on every frame.</p>
 *
 * <p>The stages are found by what they are, not by what the user happened to call them, and a stage
 * that is not in the pipeline is simply not reported - a pipeline of somebody else's making produces
 * a shorter report rather than no report.</p>
 */
public class VisionEvidence {

    /**
     * How many contours to name in the report. Enough to see the two terminations and be told that
     * there is more of something; short enough to read in one line of the log.
     */
    protected static final int MAX_CONTOURS = 4;

    /**
     * The report, one string with newlines in it, to be logged as a single entry so that the lines of
     * two nozzles refusing at the same moment cannot interleave.
     *
     * @param reason what the refusal said.
     * @param wantedAngle the rotation the part is to be placed at, which is also the angle the pipeline
     *            canonicalised the rectangle to. It belongs in the report because a refused size is not
     *            by itself a wrong part: {@link MinAreaRect} snaps the angle of the rectangle to the
     *            expected angle and moves an odd multiple of 90° into the size, so the size is the only
     *            trace a turned part leaves - and the only thing that keeps a turned part from being
     *            placed turned. Whether this refusal is that part or merely the same rectangle written
     *            the other way round cannot be told from the rectangle alone, which is why the angle the
     *            pipeline was run at is printed with it.
     * @return the report; never {@code null} and never throws, because evidence that is worth more than
     *             a placed part is not a reason to fail a placement twice.
     */
    public static String describe(CvPipeline pipeline, RotatedRect rect, String reason,
            String cameraName, String nozzleName, String partId, double wantedAngle) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("\n[VISION-EVIDENCE] ").append(cameraName).append('/').append(nozzleName)
                    .append(' ').append(partId).append(" refused: ").append(reason);
            sb.append("\n[VISION-EVIDENCE]   rect ").append(rect == null ? "none" : rect.toString());
            if (rect != null) {
                sb.append("\n[VISION-EVIDENCE]   ").append(orientation(pipeline, rect, wantedAngle));
            }
            if (pipeline == null) {
                sb.append("\n[VISION-EVIDENCE]   no pipeline, nothing more to say");
                return sb.toString();
            }
            int stages = 0;
            for (CvStage stage : pipeline.getStages()) {
                if (!stage.isEnabled()) {
                    continue;
                }
                Result result = pipeline.getResult(stage);
                if (result == null) {
                    continue;
                }
                if (stage instanceof MaskHsv) {
                    String kept = keptFraction(result.image);
                    if (kept != null) {
                        sb.append("\n[VISION-EVIDENCE]   ").append(stage.getName())
                                .append(" (MaskHsv) kept ").append(kept);
                        stages++;
                    }
                }
                else if (stage instanceof FindContours || stage instanceof FilterContours) {
                    String contours = describeContours(result.model);
                    if (contours != null) {
                        sb.append("\n[VISION-EVIDENCE]   ").append(stage.getName())
                                .append(stage instanceof FilterContours ? " (filtered) " : " (found) ")
                                .append(contours);
                        stages++;
                    }
                }
            }
            if (stages == 0) {
                sb.append("\n[VISION-EVIDENCE]   the pipeline kept no stage results to report");
            }
        }
        catch (Exception e) {
            sb.setLength(0);
            sb.append("\n[VISION-EVIDENCE] unavailable for ").append(partId).append(": ")
                    .append(e.toString());
        }
        return sb.toString();
    }

    /**
     * The rectangle said in the two ways that matter: which way round its sides came out, and which way
     * the pipeline was told to expect the part. A part turned 90° from what was wanted and a part measured
     * with its sides exchanged are the same rectangle in the image, so the only difference between them is
     * the angle the pipeline canonicalised to, and that is what has to be in the log for a refusal to be
     * readable afterwards.
     */
    protected static String orientation(CvPipeline pipeline, RotatedRect rect, double wantedAngle) {
        double widthSide = rect.angle;
        double longSide = rect.size.width >= rect.size.height ? rect.angle : rect.angle+90.0;
        StringBuilder sb = new StringBuilder();
        sb.append("wanted ").append(String.format("%.1f", wantedAngle)).append("°")
                .append(", the ").append((long) rect.size.width).append("px side lies at ")
                .append(String.format("%.1f", widthSide)).append("° and the ")
                .append((long) rect.size.height).append("px side at ")
                .append(String.format("%.1f", longSide)).append('°');
        try {
            Object expected = pipeline.getProperty("MinAreaRect.expectedAngle");
            sb.append(", MinAreaRect was told to expect ").append(expected == null ? "nothing"
                    : String.valueOf(expected)).append('°');
            for (CvStage stage : pipeline.getStages()) {
                if (stage instanceof MinAreaRect) {
                    sb.append(" within ±").append(String.format("%.0f",
                            ((MinAreaRect) stage).getSearchAngle())).append('°');
                    break;
                }
            }
        }
        catch (Exception e) {
            sb.append(", and the expected angle could not be read: ").append(e);
        }
        return sb.toString();
    }

    /**
     * How much of an image survived, as a percentage. The masked-out pixels are black, so a non-zero
     * pixel is a surviving one - which is exact for a binary mask and an approximation for a colour
     * mask, where a surviving pixel could still be black.
     *
     * @return the percentage, or {@code null} if there is no image to count.
     */
    protected static String keptFraction(Mat image) {
        if (image == null || image.empty()) {
            return null;
        }
        Mat gray = null;
        try {
            long kept;
            if (image.channels() == 1) {
                kept = Core.countNonZero(image);
            }
            else {
                gray = new Mat();
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
                kept = Core.countNonZero(gray);
            }
            double fraction = kept/(double) ((long) image.rows()*image.cols());
            return String.format("%s%.2f%% of %dx%d", image.channels() == 1 ? "" : "~",
                    fraction*100.0, image.cols(), image.rows());
        }
        catch (Exception e) {
            return null;
        }
        finally {
            if (gray != null) {
                gray.release();
            }
        }
    }

    /**
     * The contours of a stage model: how many there are and, for the largest few, how big they are and
     * where they sit. Two terminations and one are the two answers that matter.
     *
     * @return the description, or {@code null} if the model holds no contours.
     */
    protected static String describeContours(Object model) {
        if (!(model instanceof List)) {
            return null;
        }
        List<?> contours = (List<?>) model;
        List<double[]> shapes = new ArrayList<>();
        for (Object contour : contours) {
            if (!(contour instanceof MatOfPoint)) {
                continue;
            }
            MatOfPoint points = (MatOfPoint) contour;
            try {
                Rect box = Imgproc.boundingRect(points);
                double area = Math.max(Imgproc.contourArea(points), box.area());
                shapes.add(new double[] {area, box.x+box.width/2.0, box.y+box.height/2.0,
                        box.width, box.height});
            }
            catch (Exception e) {
                // One unreadable contour is not a reason to lose the rest of the report.
            }
            finally {
                // Not released: the contours belong to the pipeline.
            }
        }
        if (shapes.isEmpty()) {
            return contours.isEmpty() ? "no contours" : null;
        }
        Collections.sort(shapes, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(b[0], a[0]);
            }
        });
        StringBuilder sb = new StringBuilder(String.valueOf(contours.size())).append(" contour(s) [");
        for (int i = 0; i < Math.min(MAX_CONTOURS, shapes.size()); i++) {
            double[] shape = shapes.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append((long) shape[0]).append("px @{").append(String.format("%.0f,%.0f", shape[1],
                    shape[2])).append(" ").append((int) shape[3]).append('x').append((int) shape[4])
                    .append('}');
        }
        if (shapes.size() > MAX_CONTOURS) {
            sb.append(", +").append(shapes.size()-MAX_CONTOURS).append(" more");
        }
        return sb.append(']').toString();
    }

    /**
     * Log {@link #describe} at warn level, being the level at which the refusal itself was logged.
     */
    public static void report(CvPipeline pipeline, RotatedRect rect, String reason,
            String cameraName, String nozzleName, String partId, double wantedAngle) {
        Logger.warn(describe(pipeline, rect, reason, cameraName, nozzleName, partId, wantedAngle));
    }
}
