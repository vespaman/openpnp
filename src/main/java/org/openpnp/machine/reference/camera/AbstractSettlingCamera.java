/*
 * Copyright (C) 2020 <mark@makr.zone>
 * inspired and based on work
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.camera;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.opencv.core.Core;
import org.opencv.core.Core.MinMaxLocResult;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameProvenance;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.base.AbstractCamera;
import org.openpnp.util.NanosecondTime;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.SimpleGraph;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.core.Commit;

public abstract class AbstractSettlingCamera extends AbstractCamera {
    public enum SettleMethod {
        FixedTime,
        Maximum,
        Mean,
        Euclidean,
        Square,
        Motion;

        protected int getNorm() {
            switch(this) {
                case Maximum:
                    return Core.NORM_INF;
                case Mean:
                    return Core.NORM_L1;
                case Euclidean:
                    return Core.NORM_L2;
                case Square:
                    return Core.NORM_L2SQR;
                default:
                    return -1;
            }
        }

        protected double getScale(Mat mat) {
            switch(this) {
                case Maximum:
                    return 255.0;
                case Mean:
                    return 255.0*mat.cols()*mat.rows()*mat.channels();
                case Euclidean:
                    return 255.0*Math.sqrt(mat.cols()*mat.rows()*mat.channels());
                case Square:
                    return 255.0*255.0*mat.cols()*mat.rows()*mat.channels(); 
                default:
                    return -1;
            }
        }
    }

    protected double computeDifference(SettleMethod method, Mat mat0, Mat mat1, double settleContrastEnhance, Mat mask) { 
        if (method == SettleMethod.Motion) {
            int dim = Math.min(mat1.cols(), mat1.rows());
            int margin = (int) Math.round(dim*maxRelativeMotion);
            int w = mat1.cols() - margin*2;
            int h = mat1.rows() - margin*2;
            Mat template = new Mat(mat1, new Rect(margin, margin, w, h));
            Mat resultMat = new Mat();
            if (mask != null) {
                Mat innerMask = new Mat();
                innerMask = createMask(template, Math.max(w, h));
                Imgproc.matchTemplate(mat0, template, resultMat,
                        Imgproc.TM_CCOEFF_NORMED, innerMask);
                innerMask.release();
            }
            else {
                Imgproc.matchTemplate(mat0, template, resultMat,
                        Imgproc.TM_CCOEFF_NORMED);
            }
            MinMaxLocResult match = Core.minMaxLoc(resultMat);
            double result;
            if (match.maxVal > minMotionTemplateMatchScore) {
                // Found the shifted image, calculate the difference as the distance in pixels.
                result = Math.hypot(match.maxLoc.x - margin, match.maxLoc.y - margin);
                // Take the match value as kind of a "sub-pixel" value.
                result += Math.pow((1 - match.maxVal)/(1 - minMotionTemplateMatchScore), 0.25); 
            }
            else {
                // Shifted image not found, max out.
                result = Math.hypot(margin, margin);
                // Take the match value as sub-integral value.
                result += (1 - match.maxVal); 
            }
            template.release();
            resultMat.release();
            return result;
        }
        else {
            // Compute the method difference norm
            double result, range = 1.0;
            if (mask != null) { 
                // masked by the circle
                result = Core.norm(mat0, mat1, method.getNorm(), mask)/method.getScale(mat1);
                if (settleContrastEnhance != 0.0) {
                    range = Core.norm(mat1, method.getNorm(), mask)/method.getScale(mat1);
                }
            }
            else {
                // the whole image
                result = Core.norm(mat0, mat1, method.getNorm())/method.getScale(mat1);
                if (settleContrastEnhance != 0.0) {
                    range = Core.norm(mat1, method.getNorm())/method.getScale(mat1);
                }
            }
            if (range != 0.0) {
                result = (settleContrastEnhance/range + (1.0 - settleContrastEnhance))*result;
            }
            // Make it percent
            return result*100.0;
        }
    }

    /**
     * Maximum assumed motion relative to the camera (or mask) dimension. 
     * Larger motion should register as no template match, and max out.
     */
    @Attribute(required = false)
    protected double maxRelativeMotion = 0.05;
    /**
     * Minimum motion template image matching score.
     */
    @Attribute(required = false)
    protected double minMotionTemplateMatchScore = 0.9;
    /**
     * Minimum image channel (brightness) range for contrast enhancement.
     */
    @Attribute(required = false)
    protected double minContrastRange = 16;


    @Attribute(required = false)
    protected SettleMethod settleMethod = null;

    @Attribute(required = false)
    protected long settleTimeMs = 250;

    @Attribute(required = false)
    protected long settleTimeoutMs = 500;

    @Attribute(required = false)
    protected double settleThreshold = 0.0;

    @Attribute(required = false)
    protected int settleDebounce = 0;

    @Attribute(required = false)
    protected boolean settleFullColor = false;

    @Attribute(required = false)
    protected int settleGaussianBlur = 0;

    @Attribute(required = false)
    protected boolean settleGradients = false;

    @Attribute(required = false)
    protected double settleMaskCircle = 0.0;

    @Attribute(required = false)
    protected double settleContrastEnhance = 0.0;

    @Attribute(required = false)
    protected boolean settleDiagnostics = false;

    @Commit
    protected void commit() throws Exception {
        if (settleMethod == null) {
            if (settleTimeMs < 0) {
                settleMethod = SettleMethod.Maximum;
                // migrate the old threshold, coded as a negative number
                settleThreshold = Math.abs(settleTimeMs)/2.55;
                settleTimeMs = 250;
            }
            else {
                settleMethod = SettleMethod.FixedTime;
            }
        }
    }

    public static final String DIFFERENCE = "D"; 
    public static final String BOOLEAN = "B"; 
    public static final String CAPTURE = "C"; 
    public static final String THRESHOLD = "TH"; 
    public static final String DATA = "D"; 

    protected TreeMap<Double, BufferedImage> recordedImages = null;
    protected TreeMap<Double, BufferedImage> heatMappedImages = null;
    protected Double recordedImagePlayed = null;
    private SimpleGraph settleGraph = null;
    private int recordedMaskDiameter;
    private double recordedComputeMilliseconds;
    // Read by the pre-check, which runs on another thread while the settling is still going on, so
    // these are volatile: they are written once per frame and read once per check, and the answer is
    // either a millisecond stale or the one the settling ended with.
    private volatile long recordedSettleMilliseconds;
    private volatile double recordedFirstCaptureMilliseconds;
    private volatile double recordedFirstFrameMilliseconds;
    private volatile int recordedFrameCount;
    /**
     * How the last settling ended, and when its frames were taken. What a settling decided is worth
     * more than the picture it ended with: a frame that came out of two frames which agreed is proof
     * the mechanics had stopped, a frame that came out of a timeout is the opposite. The pre-check and
     * the parallel alignment ask these questions of the camera while it is still settling, hence
     * volatile. See org.openpnp.model.FrameProvenance.
     */
    public enum SettleResult {
        /**
         * Nothing settled, e.g. the settling was skipped. The frame says nothing about stillness.
         */
        NotSettled,
        /**
         * The settling was a configured wait, not a measurement. The frame is as still as the
         * configured wait says it is; there is no window to place anything inside.
         */
        FixedTime,
        /**
         * Two frames in a row were alike, so the mechanics had stopped by the earlier of them.
         */
        Agreed,
        /**
         * The settling gave up. Nothing was demonstrated, least of all that the head stood still.
         */
        TimedOut
    }

    private volatile SettleResult recordedSettleResult = SettleResult.NotSettled;
    private volatile long recordedSettledFrameAtMs = FrameProvenance.UNKNOWN;
    private volatile long recordedStillWindowStartAtMs = FrameProvenance.UNKNOWN;
    private volatile long recordedPreviousFrameAtMs = FrameProvenance.UNKNOWN;
    /**
     * The same claim as {@link #recordedStillWindowStartAtMs}, but made while the settling is still
     * running: the moment from which the frames so far look alike, or {@link FrameProvenance#UNKNOWN}
     * while the last frame still said "moving".
     *
     * It is a promise and not a verdict. One more frame can break the run and reset it, in which case
     * the stillness the settling eventually certifies starts later than this said. Anyone acting on it
     * therefore has to have the verdict checked afterwards, which is what the recorded window is for.
     *
     * It exists because a frame cannot be recognised as still by the camera that is not settling. That
     * camera can only go by what the settling says and when, and every frame it takes for itself is a
     * frame the settling wanted: taking frames before there is any prospect of one of them being still
     * is pure cost for both cameras.
     */
    private volatile long candidateStillWindowStartAtMs = FrameProvenance.UNKNOWN;
    /**
     * Counts the settlings this camera has been asked to perform. Another camera waiting on this one's
     * still window cannot tell a window of this batch from a window of the batch before it by looking at
     * the window alone - both are perfectly good timestamps - so it has to watch this number move
     * before it believes any of them.
     */
    private volatile long settleEpoch = 0;

    private SimpleGraph startDiagnostics() {
        if (settleDiagnostics) {
            // Diagnostics wanted, create the simple graph.
            SimpleGraph settleGraph = new SimpleGraph();
            settleGraph.setRelativePaddingLeft(0.05);
            // init difference scale
            SimpleGraph.DataScale settleScale =  settleGraph.getScale(DIFFERENCE);
            settleScale.setRelativePaddingBottom(0.3);
            settleScale.setColor(SimpleGraph.getDefaultGridColor());
            SimpleGraph.DataScale captureScale =  settleGraph.getScale(BOOLEAN);
            captureScale.setRelativePaddingTop(0.8);
            captureScale.setRelativePaddingBottom(0.1);
            // init the difference data
            settleGraph.getRow(DIFFERENCE, DATA)
            .setColor(new Color(255, 0, 0));
            // setpoint
            settleGraph.getRow(DIFFERENCE, THRESHOLD)
            .setColor(new Color(0, 180, 0));
            // init the capture data
            settleGraph.getRow(BOOLEAN, CAPTURE)
            .setColor(new Color(00, 0x5B, 0xD9)); // the OpenPNP blue
            return settleGraph;
        }
        else {
            // No diagnostics wanted, also cleanup the previous one. 
            setSettleGraph(null);
            recordedImages = null;
            return null;
        }
    }

    private BufferedImage autoSettleAndCapture(double settleMaskCircle, Consumer<Mat> firstFrameConsumer) throws Exception {
        Mat mask = null;
        Mat maskFullsize = null;
        Mat lastSettleMat = null;

        try {
            long t0 = NanosecondTime.getRuntimeMilliseconds();
            long timeout = t0 + settleTimeoutMs;
            int debounceCount = 0;
            SimpleGraph settleGraph = startDiagnostics();
            TreeMap<Double, BufferedImage> settleImages = null;
            if (settleGraph != null) {
                settleImages = new TreeMap<>();
            }
            while(true) {
                // Capture an image. 
                if (settleGraph != null) {
                    // Record begin of capture.
                    settleGraph.getRow(BOOLEAN, CAPTURE).recordDataPoint(settleGraph.getT(), 0);
                    settleGraph.getRow(BOOLEAN, CAPTURE).recordDataPoint(settleGraph.getT(), 1);
                }

                // The actual capture.
                long tCapture0 = NanosecondTime.getRuntimeMilliseconds();
                BufferedImage image = capture();

                long t1 = NanosecondTime.getRuntimeMilliseconds();
                recordedFrameCount++;
                final boolean firstFrame = (lastSettleMat == null);
                // The frame this one will be compared against, i.e. the one taken just before it. When
                // the two turn out to agree, this is the moment from which the head can be said to have
                // been still, and it is the moment any other camera's frame has to be later than to be
                // inside the same stillness.
                final long previousFrameAtMs = recordedPreviousFrameAtMs;
                recordedPreviousFrameAtMs = t1;
                if (firstFrame) {
                    // What the pre-check is judged against, so what it costs has to be known too.
                    recordedFirstCaptureMilliseconds = t1 - tCapture0;
                    recordedFirstFrameMilliseconds = t1 - t0;
                }
                double tCapture = 0.0; 
                if (settleGraph != null) {
                    tCapture = settleGraph.getT();
                    // Record end of capture.
                    settleGraph.getRow(BOOLEAN, CAPTURE).recordDataPoint(tCapture, 1);
                    settleGraph.getRow(BOOLEAN, CAPTURE).recordDataPoint(settleGraph.getT(), 0);
                }

                // Convert to Mat and if not full color, convert to gray.
                Mat mat = OpenCvUtils.toMat(image);

                if (firstFrameConsumer != null && firstFrame) {
                    // The very first frame, as the camera delivered it. Everything below this turns
                    // it into the small gray thing the settling compares, so this is the only moment
                    // a pre-check can still judge what the camera actually saw.
                    acceptFirstFrame(firstFrameConsumer, mat);
                }
                if (!settleFullColor) {
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);
                }

                // Gaussian blur is the most expensive operation, so if it is large, we rescale the image instead.
                // This is effectively a box blur followed (later) by a Gaussian blur, i.e. still reasonable quality.
                // Rescaling will also make all subsequent steps significantly faster.
                // Do these calculations up front.
                final int resizeToMaxGaussianKernelSize = 5;
                int gaussianBlurEff = settleGaussianBlur;
                int divisor = (gaussianBlurEff > resizeToMaxGaussianKernelSize) ? 
                        (settleGaussianBlur+resizeToMaxGaussianKernelSize/2)/resizeToMaxGaussianKernelSize
                        : 1;

                int maskDiameter = 0;
                if (settleMaskCircle > 0.0) {
                    // Crop the image to the mask dimension. 
                    int imageDimension = Math.min(mat.rows(), mat.cols());
                    maskDiameter = Math.max(1, (int)(settleMaskCircle*imageDimension));
                    int maskedWidth= Math.min(mat.cols(), maskDiameter);
                    int maskedHeight= Math.min(mat.rows(), maskDiameter);
                    // Make it multiples of the rescale divisor*2.
                    maskDiameter = (int)Math.floor(maskDiameter/divisor/2)*divisor*2;
                    maskedWidth = (int)Math.floor(maskedWidth/divisor/2)*divisor*2;
                    maskedHeight = (int)Math.floor(maskedHeight/divisor/2)*divisor*2;
                    Rect rectCrop = new Rect(
                            (mat.cols() - maskedWidth)/2, (mat.rows() - maskedHeight)/2,
                            maskedWidth, maskedHeight);
                    Mat cropMat = mat.submat(rectCrop);
                    mat.release();
                    mat = cropMat;
                    if (maskFullsize == null) {
                        // This must be the first frame, also create the mask circle.
                        maskFullsize = createMask(mat, maskDiameter);
                        if (divisor == 1) {
                            // also valid as the rescaled mask
                            mask = maskFullsize;
                        }
                    }
                }

                if (settleContrastEnhance > 0.0) {
                    // Enhance the contrast. Note we need to do this before scaling the image down, so mixed
                    // colors can be created in the full dynamic range. 
                    mat = enhanceContrast(mat, maskFullsize);
                }

                if (divisor > 1) {
                    // Scale the image down, see the calculations further up.  
                    gaussianBlurEff = ((settleGaussianBlur)/divisor)|1;
                    Mat resizeMat = new Mat();
                    Imgproc.resize(mat, resizeMat, new Size(mat.cols()/divisor, mat.rows()/divisor), 1.0/divisor, 1.0/divisor);
                    mat.release();
                    mat = resizeMat;
                    maskDiameter /= divisor;
                }

                if (maskDiameter > 0 && mask == null) {
                    // This must be the first frame, also create the mask circle after rescale.
                    mask = createMask(mat, maskDiameter);
                }

                if (gaussianBlurEff > 1) {
                    // Apply the Gaussian blur, make the kernel size an odd number. 
                    Imgproc.GaussianBlur(mat, mat, new Size(gaussianBlurEff|1, gaussianBlurEff|1), 0);
                }

                if (settleGradients) {
                    // Apply Laplacian transform.
                    Mat gradientMat = new Mat();
                    Imgproc.Laplacian(mat, gradientMat, CvType.CV_16S, 3, 1, 0, Core.BORDER_REPLICATE );
                    Core.convertScaleAbs(gradientMat, gradientMat);
                    mat.release();
                    mat = gradientMat;
                }

                // Record the image with the capture time.
                if (settleGraph != null) {
                    BufferedImage img;
                    img = OpenCvUtils.toBufferedImage(mat);
                    settleImages.put(tCapture, img);

                }

                // If this is the first time through the loop then assign the new image to
                // the lastSettleMat and loop again. We need at least two images to check.
                if (lastSettleMat == null) {
                    lastSettleMat = mat;
                    continue;
                }

                // Compute the differences of the two images according to the method.
                double result = computeDifference(settleMethod, lastSettleMat, mat, settleContrastEnhance, mask);
                if (settleGraph != null) {
                    settleGraph.getRow(DIFFERENCE, DATA).recordDataPoint(settleGraph.getT(), result);
                }

                // Release the lastSettleMat and store the new image as the lastSettleMat.
                lastSettleMat.release();
                lastSettleMat = mat;

                long t = NanosecondTime.getRuntimeMilliseconds();
                recordedComputeMilliseconds = (t-t1);
                Logger.trace("autoSettleAndCapture t="+(t-t0)+" auto settle score: " + String.format("%.3f", result) +" compute time: "+(t-t1));

                // If the image changed at least a bit (due to noise) and less than our
                // threshold, we have a winner. The check for > 0 is to ensure that we're not just
                // receiving a duplicate frame from the camera. Every camera has at least a little
                // noise so we're just checking that at least one pixel changed by 1 bit.
                if (result > settleThreshold) {
                    // No good, reset the debounce count, as we crossed over the limit (again).
                    debounceCount = 0;
                    // The run of alike frames is broken, so nothing before this frame can be called
                    // still. Whoever was watching for a window has to start over as well.
                    candidateStillWindowStartAtMs = FrameProvenance.UNKNOWN;
                }
                else if (result > 0.0) {
                    // Register one "bounce" under the limit.
                    debounceCount++;
                    if (candidateStillWindowStartAtMs == FrameProvenance.UNKNOWN) {
                        // Two frames that look alike. From the earlier of them the head may
                        // provisionally be called still, which is the earliest moment another camera
                        // has any prospect of taking a still frame. The verdict itself only comes when
                        // the debounce is full; until then this is a promise that can still be broken.
                        candidateStillWindowStartAtMs = previousFrameAtMs;
                    }
                }
                if (t > timeout || debounceCount > settleDebounce) {
                    // Timeout or debounced settleThreshold reached.
                    // Say which it was, and from when the head can be called still: the frame handed
                    // back means two different things depending on how we got here, and the difference
                    // is the difference between a measurement and a shrug.
                    boolean agreed = debounceCount > settleDebounce;
                    recordedSettleResult = agreed ? SettleResult.Agreed : SettleResult.TimedOut;
                    recordedSettledFrameAtMs = t1;
                    recordedStillWindowStartAtMs = agreed ? previousFrameAtMs
                            : FrameProvenance.UNKNOWN;
                    if (!agreed) {
                        // Nothing was demonstrated, so the provisional claim that some two frames
                        // once looked alike is worth exactly as much as the settling that produced it.
                        candidateStillWindowStartAtMs = FrameProvenance.UNKNOWN;
                    }
                    // Cleanup.
                    lastSettleMat.release();
                    lastSettleMat = null;
                    if (settleGraph != null) {
                        // Record last points in the graph. 
                        double tEnd = settleGraph.getT()+1;
                        settleGraph.getRow(BOOLEAN, CAPTURE).recordDataPoint(tEnd, 0);
                        settleGraph.getRow(DIFFERENCE, THRESHOLD).recordDataPoint(0.0, settleThreshold);
                        settleGraph.getRow(DIFFERENCE, THRESHOLD).recordDataPoint(tEnd, settleThreshold);
                        settleGraph.getRow(DIFFERENCE, DATA).recordDataPoint(tEnd, result);
                        // Set the graph to the camera.
                        setSettleGraph(settleGraph);
                        // Set recorded images along with the graph.
                        setRecordedImages(settleImages);
                        recordedMaskDiameter = maskDiameter;
                    }
                    recordedSettleMilliseconds = NanosecondTime.getRuntimeMilliseconds() - t0;
                    Logger.debug("autoSettleAndCapture in {} ms", recordedSettleMilliseconds);
                    return image;
                }
            }
        }
        finally {
            // Whatever happens, always release these looping mats.
            if (maskFullsize != null) {
                maskFullsize.release();
                if (mask == maskFullsize) {
                    mask = null;
                }
            }
            if (mask != null) {
                mask.release();
            }
            if (lastSettleMat != null) {
                lastSettleMat.release();
            }
        }
    }

    /**
     * Hand the first frame of a settling to its consumer, without letting the consumer break the
     * settling.
     */
    protected void acceptFirstFrame(Consumer<Mat> firstFrameConsumer, Mat mat) {
        try {
            firstFrameConsumer.accept(mat);
        }
        catch (Exception e) {
            Logger.warn(e, "Camera {} first frame consumer failed: {}", getName(), e.getMessage());
        }
    }

    protected static Mat createMask(Mat mat, int maskDiameter) {        Mat mask;
        mask = new Mat(mat.rows(), mat.cols(), CvType.CV_8U, Scalar.all(0));
        Imgproc.circle(mask,
                new Point(mat.cols()/2, mat.rows()/2),
                maskDiameter/2,
                new Scalar(255, 255, 255), -1);
        return mask;
    }

    protected Mat enhanceContrast(Mat mat, Mat mask) {
        // It's weirdly difficult to extract the minimum level (black point) from an image.
        // Core.norm(... NORM_MINMAX) does not seem to work and minMaxLoc() takes only single channel images. 
        // So we need to work with the channels individually here. I must be missing something.
        double range = minContrastRange/255.0;
        double max = 0 + range;
        int nChannels = mat.channels();
        if (nChannels > 1) {
            Mat workingMat = new Mat();
            for (int cn=0; cn < nChannels; cn++) {
                Core.extractChannel(mat, workingMat, cn);
                MinMaxLocResult res = Core.minMaxLoc(workingMat, mask); // Note, mask can for once be null
                workingMat.release();
                max = Math.max(max, res.maxVal)/255.0;
                range = Math.max(range, res.maxVal-res.minVal)/255.0;
            }
        }
        else {
            MinMaxLocResult res = Core.minMaxLoc(mat, mask); // Note, mask can for once be null
            max = Math.max(max, res.maxVal)/255.0;
            range = Math.max(range, res.maxVal-res.minVal)/255.0;
        }
        double scale = settleContrastEnhance/range + (1.0 - settleContrastEnhance);
        double offset = -(max-range)*settleContrastEnhance/range;
        Mat tmpMat = new Mat();  
        Core.convertScaleAbs(mat, tmpMat, scale, offset*255.0);
        mat.release();
        mat = tmpMat;
        return mat;
    }

    protected BufferedImage createHeatMapDiagnosticImage(Mat mat0, Mat mat1) {
        // Record diagnostic images.
        Mat diagnosticMat = mat1;
        Mat mask = null;
        if (recordedMaskDiameter > 0) {
            // Also create the mask.
            mask = createMask(mat1, recordedMaskDiameter);
            // Simulate the mask for the diagnostic image.
            Mat tmpMat = new Mat(diagnosticMat.rows(), diagnosticMat.cols(), diagnosticMat.type(), Scalar.all(0));
            diagnosticMat.copyTo(tmpMat, mask);
            if (diagnosticMat != mat1) {
                diagnosticMat.release();
            }
            diagnosticMat = tmpMat;
        }

        // Create the difference heat-map
        if (mat0 != null) {
            // We have mat0, i.e. this is at least the second frame. 
            Mat normMat = new Mat();
            Mat diffMat = new Mat();
            Core.absdiff(mat0, mat1, diffMat);
            if (settleFullColor) {
                Imgproc.cvtColor(diffMat, diffMat, Imgproc.COLOR_BGR2GRAY);
            }
            if (mask != null) {
                // Adaptive gain.
                double maxDiff = Core.norm(mat0, mat1,  Core.NORM_INF, mask);
                int limit = Math.min(255, (int)(maxDiff*16)); 
                Core.normalize(diffMat, normMat, limit, 0, 
                        Core.NORM_INF, 
                        0, mask);
            }
            else {
                // Adaptive gain.
                double maxDiff = Core.norm(mat0, mat1,  Core.NORM_INF);
                int limit = Math.min(255, (int)(maxDiff*16)); 
                Core.normalize(diffMat, normMat, limit, 0, 
                        Core.NORM_INF, 
                        0);
            }
            diffMat.release();

            Imgproc.cvtColor(normMat, normMat, Imgproc.COLOR_GRAY2BGR);
            Mat heatmapMat = new Mat();
            Imgproc.applyColorMap(normMat, heatmapMat, Imgproc.COLORMAP_HOT);
            Mat backgroundMat = new Mat();
            if (settleFullColor) {
                diagnosticMat.copyTo(backgroundMat);
            }
            else {
                Imgproc.cvtColor(diagnosticMat, backgroundMat, Imgproc.COLOR_GRAY2BGR);
            }

            // Am I doing something wrong? This OpenCV arithmetic is awful to code. 
            Mat alphaMat = new Mat();
            // Square the normed diff, to emphasize large movements for the alpha channel.
            Core.multiply(normMat, normMat, alphaMat, 1/255.0/255.0, CvType.CV_32F);
            // Create the complement for the alpha channel.
            Mat oneMat = new Mat(alphaMat.rows(), alphaMat.cols(), alphaMat.type(), Scalar.all(1.0));
            Mat invertedAlphaMat = new Mat();
            Core.subtract(oneMat, alphaMat, invertedAlphaMat);
            oneMat.release();
            // Use the alpha channel for the heat map.
            Mat mulMat = new Mat();
            Core.multiply(heatmapMat, alphaMat, mulMat, 1.0, CvType.CV_8U);
            heatmapMat.release();
            heatmapMat = mulMat;
            // Use the inverted alpha channel for the backgound image.
            mulMat = new Mat();
            Core.multiply(backgroundMat, invertedAlphaMat, mulMat, 1.0, CvType.CV_8U);
            backgroundMat.release();
            backgroundMat = mulMat;
            // Blend the two.
            Mat blendedMat = new Mat();
            Core.add(backgroundMat, heatmapMat, blendedMat);
            backgroundMat.release();
            heatmapMat.release();
            // Cleanup
            normMat.release();
            alphaMat.release();
            invertedAlphaMat.release();
            // New result
            if (diagnosticMat != mat1) {
                diagnosticMat.release();
            }
            diagnosticMat = blendedMat;
            if (settleMethod == SettleMethod.Motion) {
                // Draw the motion detection margin.
                int margin = (int) (Math.min(diagnosticMat.cols(), diagnosticMat.rows())*maxRelativeMotion);
                if (mask != null) {
                    Imgproc.circle (
                            diagnosticMat,
                            new Point(diagnosticMat.cols()/2, diagnosticMat.rows()/2),
                            recordedMaskDiameter/2 - margin,
                            new Scalar(64, 64, 64),
                            1, Imgproc.LINE_AA);
                }
                if (recordedMaskDiameter == 0
                       || recordedMaskDiameter > mask.cols() || recordedMaskDiameter > mask.rows())  {
                    Imgproc.rectangle(
                            blendedMat, 
                            new Point(margin, margin), 
                            new Point(diagnosticMat.cols() - margin, diagnosticMat.rows() - margin),
                            new Scalar(64, 64, 64),
                            1, Imgproc.LINE_AA);
                }
            }
        }

        // Save file to disk.
        if (Logger.getLevel() == org.pmw.tinylog.Level.DEBUG || Logger.getLevel() == org.pmw.tinylog.Level.TRACE) {
            try {
                File file = Configuration.get()
                        .createResourceFile(getClass(), "settle", ".png");
                Imgcodecs.imwrite(file.getAbsolutePath(), diagnosticMat);
            }
            catch (Exception e) {
                Logger.error(e);
            }
        }

        BufferedImage img = OpenCvUtils.toBufferedImage(diagnosticMat);
        // cleanup
        if (diagnosticMat != mat1) {
            diagnosticMat.release();
        }
        if (mask != null) {
            mask.release();
        }
        return img;
    }

    @Override
    public BufferedImage lightSettleAndCapture() throws Exception {
        actuateLightBeforeCapture();
        try {
            return settleAndCapture();
        }
        finally {
            actuateLightAfterCapture();
        }
    }

    @Override
    public BufferedImage settleAndCapture(SettleOption settleOption) throws Exception {
        return settleAndCapture(settleOption, null);
    }

    /**
     * Settle and capture, handing the very first captured frame to a consumer on the way.
     *
     * The frame is given to the consumer raw, i.e. before the settling has turned it grayscale,
     * cropped, rescaled, blurred or gradient-transformed, and before it has been compared with
     * anything. It is therefore the frame a pre-check must judge: the one that was in the camera
     * while the mechanics were still settling.
     *
     * The Mat belongs to the settling loop and is released by it. A consumer that wants to keep
     * the frame must clone() it. The consumer runs on the thread that settles, at the moment the
     * frame arrives, so it must hand any work to somebody else and return: everything in the
     * settling loop is timed, and a slow consumer is a slow settle. Exceptions from it are logged
     * and otherwise ignored, because a pre-check that cannot run must not spoil the capture.
     *
     * @param settleOption
     * @param firstFrameConsumer may be null
     * @return
     * @throws Exception
     */
    public BufferedImage settleAndCapture(SettleOption settleOption, Consumer<Mat> firstFrameConsumer) throws Exception {
        // Whatever happens below, the recorded numbers are about this settling and not about the
        // previous one. The epoch moves first, so anyone watching for a window can tell that the one
        // they are looking at has just stopped being the current one.
        settleEpoch++;
        recordedFirstCaptureMilliseconds = 0.0;
        recordedFirstFrameMilliseconds = 0.0;
        recordedSettleMilliseconds = 0;
        recordedFrameCount = 0;
        recordedSettleResult = SettleResult.NotSettled;
        recordedSettledFrameAtMs = FrameProvenance.UNKNOWN;
        recordedStillWindowStartAtMs = FrameProvenance.UNKNOWN;
        recordedPreviousFrameAtMs = FrameProvenance.UNKNOWN;
        candidateStillWindowStartAtMs = FrameProvenance.UNKNOWN;
        long tStart = NanosecondTime.getRuntimeMilliseconds();
        if (settleOption == SettleOption.Skip) {
            // Skip settling.
            BufferedImage image = capture();
            recordedFirstCaptureMilliseconds = NanosecondTime.getRuntimeMilliseconds() - tStart;
            recordedFirstFrameMilliseconds = recordedFirstCaptureMilliseconds;
            recordedFrameCount = 1;
            if (firstFrameConsumer != null) {
                Mat mat = OpenCvUtils.toMat(image);
                acceptFirstFrame(firstFrameConsumer, mat);
                mat.release();
            }
            return image;
        }
        Map<String, Object> globals = new HashMap<>();
        globals.put("camera", this);
        Configuration.get().getScripting().on("Camera.BeforeSettle", globals);

        try {
            // Make sure the camera (or its subject) stands still.
            waitForCompletion(CompletionType.WaitForStillstand);

            if (settleMethod == null) {
                // Method undetermined, probably created a new camera (no @Commit handler)
                settleMethod = SettleMethod.FixedTime;
            }
            if (settleMethod == SettleMethod.FixedTime) {
                try {
                    Logger.trace(getName()+" settling fixed time "+getSettleTimeMs()+"ms");
                    Thread.sleep(getSettleTimeMs());
                }
                catch (Exception e) {

                }
                BufferedImage image = capture();
                recordedFirstCaptureMilliseconds = NanosecondTime.getRuntimeMilliseconds() - tStart;
                recordedFirstFrameMilliseconds = recordedFirstCaptureMilliseconds;
                recordedFrameCount = 1;
                recordedSettleMilliseconds = (long) recordedFirstCaptureMilliseconds;
                // A configured wait, not a measurement. Nothing was demonstrated about the mechanics,
                // so there is no still window that another camera's frame could be placed inside; the
                // frame is nevertheless the frame this machine's settings asked for.
                recordedSettleResult = SettleResult.FixedTime;
                recordedSettledFrameAtMs = NanosecondTime.getRuntimeMilliseconds();
                recordedStillWindowStartAtMs = FrameProvenance.UNKNOWN;
                if (firstFrameConsumer != null) {
                    // There is only this one frame, so it is also the first one.
                    Mat mat = OpenCvUtils.toMat(image);
                    acceptFirstFrame(firstFrameConsumer, mat);
                    mat.release();
                }
                return image;
            }
            else {
                return autoSettleAndCapture(settleOption == SettleOption.SettleFullArea ? 0 : settleMaskCircle, 
                        firstFrameConsumer);
            }
        }
        finally {

            Configuration.get().getScripting().on("Camera.AfterSettle", globals);
        }
    }

    public SettleMethod getSettleMethod() {
        return settleMethod;
    }

    public void setSettleMethod(SettleMethod settleMethod) {
        this.settleMethod = settleMethod;
    }

    public long getSettleTimeMs() {
        return settleTimeMs;
    }

    public void setSettleTimeMs(long settleTimeMs) {
        this.settleTimeMs = settleTimeMs;
    }

    public long getSettleTimeoutMs() {
        return settleTimeoutMs;
    }

    public void setSettleTimeoutMs(long settleTimeoutMs) {
        this.settleTimeoutMs = settleTimeoutMs;
    }

    public double getSettleThreshold() {
        return settleThreshold;
    }

    public void setSettleThreshold(double settleThreshold) {
        this.settleThreshold = settleThreshold;
    }

    public int getSettleDebounce() {
        return settleDebounce;
    }

    public void setSettleDebounce(int settleDebounce) {
        this.settleDebounce = settleDebounce;
    }

    public boolean isSettleGradients() {
        return settleGradients;
    }

    public void setSettleGradients(boolean settleGradients) {
        this.settleGradients = settleGradients;
    }

    public boolean isSettleFullColor() {
        return settleFullColor;
    }

    public void setSettleFullColor(boolean settleFullColor) {
        this.settleFullColor = settleFullColor;
    }

    public int getSettleGaussianBlur() {
        return settleGaussianBlur;
    }

    public void setSettleGaussianBlur(int settleGaussianBlur) {
        Object oldValue = this.settleGaussianBlur;
        this.settleGaussianBlur = settleGaussianBlur <= 1 ? 0 : settleGaussianBlur|1; // make it odd
        firePropertyChange("settleGaussianBlur", oldValue, settleGaussianBlur);
    }

    public double getSettleMaskCircle() {
        return settleMaskCircle;
    }

    public void setSettleMaskCircle(double settleMaskCircle) {
        this.settleMaskCircle = settleMaskCircle;
    }

    public double getSettleContrastEnhance() {
        return settleContrastEnhance;
    }

    public void setSettleContrastEnhance(double settleContrastEnhance) {
        this.settleContrastEnhance = settleContrastEnhance;
    }

    public boolean isSettleDiagnostics() {
        return settleDiagnostics;
    }

    public void setSettleDiagnostics(boolean settleDiagnostics) {
        this.settleDiagnostics = settleDiagnostics;
        if (!settleDiagnostics) {
            // get rid of recordings
            setSettleGraph(null);
            setRecordedImages(null);
        }
    }

    public double getRecordedComputeMilliseconds() {
        return recordedComputeMilliseconds;
    }

    /**
     * How long the capture of the very first frame of the last settling took, in ms. This is the
     * frame a pre-check judges, so this is what a pre-check's "capture" means.
     */
    public double getRecordedFirstCaptureMilliseconds() {
        return recordedFirstCaptureMilliseconds;
    }

    /**
     * The time from the start of the last settling to that first frame being in hand, in ms, i.e.
     * including the wait for stillstand.
     */
    /**
     * @return how many frames the last settling took, 0 when nothing was captured. The pre-check
     *         logs it, because a check that races the settling wants to know over how many frames
     *         the race is.
     */
    public int getRecordedFrameCount() {
        return recordedFrameCount;
    }

    public double getRecordedFirstFrameMilliseconds() {
        return recordedFirstFrameMilliseconds;
    }

    public long getRecordedSettleMilliseconds() {
        return recordedSettleMilliseconds;
    }

    /**
     * How the settling that produced the last frame ended. A frame from a settling that timed out is
     * not a settled frame, however many milliseconds it waited.
     */
    public SettleResult getRecordedSettleResult() {
        return recordedSettleResult;
    }

    /**
     * When the frame that ended the last settling was captured, on the
     * {@link org.openpnp.util.NanosecondTime} millisecond scale, or
     * {@link FrameProvenance#UNKNOWN} if nothing has settled yet.
     */
    public long getRecordedSettledFrameAtMs() {
        return recordedSettledFrameAtMs;
    }

    /**
     * From when the head may be called still, i.e. the capture time of the earlier of the two frames
     * that the settling found alike. Another camera's frame is inside the same stillness when it was
     * captured at or after this time. {@link FrameProvenance#UNKNOWN} when nothing was demonstrated,
     * which is every settling that timed out and every settling that was a configured wait.
     */
    public long getRecordedStillWindowStartAtMs() {
        return recordedStillWindowStartAtMs;
    }

    /**
     * From when the head may provisionally be called still while the settling is still going on, see
     * {@link #candidateStillWindowStartAtMs}. Another camera aims its own frame at this and has the
     * claim confirmed afterwards by {@link #getRecordedStillWindowStartAtMs()}, because a run of alike
     * frames can still be broken by the next one. {@link FrameProvenance#UNKNOWN} while the last frame
     * said "moving", and while this camera has not been asked to settle.
     */
    public long getCandidateStillWindowStartAtMs() {
        return candidateStillWindowStartAtMs;
    }

    /**
     * How many settlings this camera has been asked to perform. Another camera waiting for this one's
     * still window takes the number before it starts waiting and must see it move before the recorded
     * window means anything about its own batch: the window of the previous placement was a real
     * stillness, but it belongs to a head that has since moved away.
     */
    public long getSettleEpoch() {
        return settleEpoch;
    }

    public SimpleGraph getSettleGraph() {
        return settleGraph;
    }

    public void setSettleGraph(SimpleGraph settleGraph) {
        Object oldValue = this.settleGraph;
        this.settleGraph = settleGraph;
        firePropertyChange("settleGraph", oldValue, settleGraph);
    }

    protected TreeMap<Double, BufferedImage> getRecordedImages() {
        return recordedImages;
    }

    protected void setRecordedImages(TreeMap<Double, BufferedImage> recordedImages) {
        this.recordedImages = recordedImages;
        this.heatMappedImages = recordedImages == null ? null : new TreeMap<>();
    }

    public Double getRecordedImagePlayed() {
        return recordedImagePlayed;
    }

    public void setRecordedImagePlayed(Double recordedImagePlayed) {
        Object oldValue = this.recordedImagePlayed;
        this.recordedImagePlayed = recordedImagePlayed;
        firePropertyChange("recordedImagePlayed", oldValue, recordedImagePlayed);
        if (recordedImagePlayed != null && oldValue != recordedImagePlayed) {
            playRecordedImage(recordedImagePlayed);
        }
    }

    public BufferedImage getRecordedImage(double t) {
        if (recordedImages != null) {
            Map.Entry<Double, BufferedImage> entry = recordedImages.floorEntry(t);
            if (entry != null) {
                return entry.getValue();
            }
        }
        return null;
    }

    public void playRecordedImage(double t) {
        if (recordedImages != null) {
            BufferedImage img = null;
            Map.Entry<Double, BufferedImage> entry1 = recordedImages.floorEntry(t);
            if (entry1 != null) {
                double tFrame = entry1.getKey();
                img = heatMappedImages.get(tFrame);
                if (img == null) {
                    // first time use, create the heat mapped image
                    Map.Entry<Double, BufferedImage> entry0 = recordedImages.lowerEntry(tFrame);
                    Mat mat0 = null;
                    if (entry0 != null) {
                        mat0 = OpenCvUtils.toMat(entry0.getValue());
                    }
                    Mat mat1 = OpenCvUtils.toMat(entry1.getValue());
                    img = createHeatMapDiagnosticImage(mat0, mat1);
                    if (mat0 != null) {
                        mat0.release();
                    }
                    mat1.release();
                    heatMappedImages.put(tFrame, img);
                }
                // I'm sure there's a better way to count the preceding images :-(
                int n = 0;
                for (Double t1 : recordedImages.keySet()) {
                    if (t1 > t) {
                        break;
                    }
                    ++n;
                }
                String message = "Camera settling, frame number "+n+", t=+"+String.format(Locale.US, "%.1f", tFrame)+"ms";
                MainFrame.get().getCameraViews().getCameraView(this)
                .showFilteredImage(img, message, 1500);
                ensureCameraVisible();
            }
        }
    }
}
