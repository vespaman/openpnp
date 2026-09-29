package org.openpnp.vision.pipeline;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.opencv.core.Core;
import org.opencv.core.Core.MinMaxLocResult;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.openpnp.model.CapturedShot;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.OpenCvUtils;

/**
 * Does the pre-check fit inside the settling of the camera, and does it lengthen that settling?
 *
 * Two things are measured on this box, with the fork's own classes and the bundled OpenCV, without
 * touching hardware:
 *
 *  - the cost of one pre-check, i.e. the part's own pipeline fed a frame that is already in hand,
 *    exactly the way the parallel alignment feeds it one today;
 *  - the cost of one iteration of the settling loop, and what that iteration costs when two
 *    pre-checks are running on other threads at the same time. The second number is the one the
 *    April attempt never separated from the first and therefore mis-judged.
 *
 * The settling is mimicked from AbstractSettlingCamera.autoSettleAndCapture() as configured on the
 * machine (settle-gaussian-blur="15" hence the /3 rescale, settle-mask-circle="0.0",
 * settle-gradients="false", settle-contrast-enhance="0.0", settle-full-color="false",
 * settle-method="Motion", settle-diagnostics="true" so the frame is converted back to a
 * BufferedImage on every iteration).
 *
 * Usage, in a directory holding the fork's classes and lib and the captured frame in in/:
 *
 *     javac -cp "classes:lib/*" -d out PreCheckBench.java
 *     java -cp "out:classes:lib/*" org.openpnp.vision.pipeline.PreCheckBench p_run2.xml in/frame.png 40
 */
public class PreCheckBench {
    static final int SETTLE_DIVISOR = 3;
    static final int SETTLE_BLUR = 5;
    static final double SETTLE_MAX_RELATIVE_MOTION = 0.05;

    public static void main(String[] argv) throws Exception {
        String xmlPath = argv[0];
        String framePath = argv.length > 1 ? argv[1] : "in/frame.png";
        int reps = argv.length > 2 ? Integer.parseInt(argv[2]) : 40;
        double uppX = argv.length > 3 ? Double.parseDouble(argv[3]) : 0.0386;
        double uppY = argv.length > 4 ? Double.parseDouble(argv[4]) : 0.0341;
        double footprintMm = argv.length > 5 ? Double.parseDouble(argv[5]) : 2.83;
        double captureMs = argv.length > 6 ? Double.parseDouble(argv[6]) : 30.0;
        // Which measurements to run, so that a crash in one of them can be told apart from the
        // others: check, settle, load, or all.
        String phases = argv.length > 7 ? argv[7] : "all";
        boolean doCheck = phases.contains("all") || phases.contains("check");
        boolean doSettle = phases.contains("all") || phases.contains("settle");
        boolean doLoad = phases.contains("all") || phases.contains("load");
        boolean doCheckAll = doCheck || phases.contains("read");

        // The static initialiser of CvPipeline is what loads the bundled OpenCV native, and it must
        // be the only class that does: loading that native twice loads it twice, with two heaps, and
        // then a Mat allocated in one is freed in the other. OpenCvUtils has such an initialiser too,
        // so touching it before the pipeline class would be exactly that mistake.
        Class.forName("org.openpnp.vision.pipeline.CvPipeline");
        Mat frame = Imgcodecs.imread(framePath);
        if(frame.empty()) {
            System.out.println("Cannot read " + framePath);
            return;
        }
        System.out.println(xmlPath + " frame=" + frame.cols() + "x" + frame.rows()
                + " footprint=" + footprintMm + "mm capture~" + captureMs + "ms");
        System.out.println("  java=" + System.getProperty("java.version")
                + " cores=" + Runtime.getRuntime().availableProcessors());

        // Warm up, the way the first two shots of a job warm up the JVM and OpenCV.
        for(int i = 0; i < 5; i++) {
            if(doCheck) {
                preCheck(newPipeline(xmlPath, true), frame, uppX, uppY, footprintMm);
            }
            if(phases.contains("read")) {
                preCheck(newPipeline(xmlPath, false), frame, uppX, uppY, footprintMm, false);
            }

        if(doSettle) {
                Mat[] last = new Mat[1];
                settleFrame(frame, last);
                if(last[0] != null) {
                    last[0].release();
                }
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        int frames = 5;

        if(doCheck) {
            CvPipeline one = newPipeline(xmlPath, true);
            double[] alone = new double[reps];
            String found = "";
            for(int i = 0; i < reps; i++) {
                double[] result = preCheck(one, frame, uppX, uppY, footprintMm);
                alone[i] = result[0];
                found = String.valueOf((int) result[1]) + "x" + (int) result[2];
            }
            report("pre-check, one nozzle, nothing else running", alone);
            System.out.println("    (detected " + found + ")");

            // Two nozzles, as the parallel alignment runs them: two pipelines, because a pipeline is
            // not used by two threads at once, and two of the same part is the worst case this job
            // presents.
            List<CvPipeline> pipelines = new ArrayList<>();
            pipelines.add(newPipeline(xmlPath, true));
            pipelines.add(newPipeline(xmlPath, true));
            double[] both = new double[reps];
            for(int i = 0; i < reps; i++) {
                long t0 = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();
                for(int n = 0; n < 2; n++) {
                    final CvPipeline pipeline = pipelines.get(n);
                    futures.add(pool.submit(() -> preCheck(pipeline, frame, uppX, uppY, footprintMm)));
                }
                for(Future<?> future : futures) {
                    future.get();
                }
                both[i] = (System.nanoTime() - t0) / 1e6;
            }
            report("pre-check, two nozzles at once, wall clock", both);
        }

        if(phases.contains("read")) {
            // The same two measurements against the pipeline that reads the frame from disk instead
            // of being handed it, so that the two can be told apart, and so that a crash can be
            // attributed to one of them.
            CvPipeline one = newPipeline(xmlPath, false);
            double[] readAlone = new double[reps];
            for(int i = 0; i < reps; i++) {
                readAlone[i] = preCheck(one, frame, uppX, uppY, footprintMm, false)[0];
            }
            report("capture-from-disk, one at a time", readAlone);

            List<CvPipeline> pipelines = new ArrayList<>();
            pipelines.add(newPipeline(xmlPath, false));
            pipelines.add(newPipeline(xmlPath, false));
            double[] readBoth = new double[reps];
            for(int i = 0; i < reps; i++) {
                long t0 = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();
                for(int n = 0; n < 2; n++) {
                    final CvPipeline pipeline = pipelines.get(n);
                    futures.add(pool.submit(() -> preCheck(pipeline, frame, uppX, uppY, footprintMm,
                            false)));
                }
                for(Future<?> future : futures) {
                    future.get();
                }
                readBoth[i] = (System.nanoTime() - t0) / 1e6;
            }
            report("capture-from-disk, two at once", readBoth);
        }

        if(doSettle) {
            double[] iteration = new double[reps * frames];
            double[] interval = new double[reps * frames];
            settling(frame, reps, frames, captureMs, null, new ArrayList<CvPipeline>(), uppX, uppY,
                    footprintMm, iteration, interval, new ArrayList<Double>());
            report("settling iteration, nothing else running", iteration);
            report("settling frame interval, nothing else running", interval);
        }

        if(doLoad) {
            double[] iteration = new double[reps * frames];
            double[] interval = new double[reps * frames];
            List<CvPipeline> pipelines = new ArrayList<>();
            pipelines.add(newPipeline(xmlPath, true));
            pipelines.add(newPipeline(xmlPath, true));
            List<Double> concurrent = Collections.synchronizedList(new ArrayList<Double>());
            settling(frame, reps, frames, captureMs, pool, pipelines, uppX, uppY, footprintMm,
                    iteration, interval, concurrent);
            report("settling iteration, two pre-checks running", iteration);
            report("settling frame interval, two pre-checks running", interval);
            double[] checks = new double[concurrent.size()];
            for(int i = 0; i < checks.length; i++) {
                checks[i] = concurrent.get(i);
            }
            report("the same pre-checks, under settling load", checks);
        }
        pool.shutdown();
    }

    /**
     * The settling loop of one camera, run the way autoSettleAndCapture() runs it: a frame every
     * captureMs, each frame measured, the previous frame kept. When an executor is given, two
     * pre-checks are started on the second frame, which is the moment the first frame of a real
     * settle is in hand; the durations of those pre-checks go into concurrent.
     */
    static void settling(Mat frame, int reps, int frames, double captureMs, ExecutorService pool,
            List<CvPipeline> checkPipelines, double uppX, double uppY, double footprintMm,
            double[] iteration, double[] interval, List<Double> concurrent) throws Exception {
        for(int i = 0; i < reps; i++) {
            List<Future<?>> started = new ArrayList<>();
            Mat[] last = new Mat[1];
            long previous = System.nanoTime();
            for(int f = 0; f < frames; f++) {
                sleep(captureMs);
                iteration[i * frames + f] = settleFrame(frame, last);
                long done = System.nanoTime();
                interval[i * frames + f] = (done - previous) / 1e6;
                previous = done;
                if(f == 1 && pool != null) {
                    for(int n = 0; n < 2 && n < checkPipelines.size(); n++) {
                        final CvPipeline pipeline = checkPipelines.get(n);
                        started.add(pool.submit(() -> concurrent.add(
                                preCheck(pipeline, frame, uppX, uppY, footprintMm)[0])));
                    }
                }
            }
            for(Future<?> future : started) {
                future.get();
            }
            if(last[0] != null) {
                last[0].release();
            }
        }
    }

    /**
     * One pre-check as it would run in the product: the part's own pipeline, prepared the way
     * preparePipeline() prepares it, fed the frame that is already in hand. Returns {ms, rect width,
     * rect height}.
     */
    static final boolean DEBUG = System.getProperty("bench.debug") != null;

    static void dbg(String what) {
        if(DEBUG) {
            System.err.println("  pre-check: " + what);
            System.err.flush();
        }
    }

    /**
     * A pipeline of the kind the pre-check would run, one per thread: the machine also keeps the
     * pipeline of a part and reuses it shot after shot rather than reading it from XML again, so a
     * measurement that parsed it per run would measure the parser.
     */
    static CvPipeline newPipeline(String xmlPath, boolean useShot) throws Exception {
        CvPipeline pipeline = new CvPipeline();
        pipeline.fromXmlString(useShot ? pipelineXml(xmlPath) : rawXml(xmlPath));
        return pipeline;
    }

    static double[] preCheck(CvPipeline pipeline, Mat frame, double uppX, double uppY,
            double footprintMm) {
        return preCheck(pipeline, frame, uppX, uppY, footprintMm, true);
    }

    static double[] preCheck(CvPipeline pipeline, Mat frame, double uppX, double uppY,
            double footprintMm, boolean useShot) {
        try {
            dbg("reset");
            pipeline.resetReusedPipeline();
            Benchmark.apply(pipeline, uppX, uppY, 0.1, footprintMm, frame.cols(), frame.rows());
            // The pipeline takes ownership of the shot and releases it, so the frame of the run has
            // to be cloned in, exactly as the capture stage hands its own clone to the analysis.
            pipeline.setProperty("camera", Benchmark.mockCamera(uppX, uppY, frame.cols(), frame.rows()));
            if(useShot) {
                pipeline.setPreCapturedShot(0, new CapturedShot(
                        Benchmark.mockCamera(uppX, uppY, frame.cols(), frame.rows()), null,
                        frame.clone(), new Location(LengthUnit.Millimeters)));
            }
            dbg("shot set");
            long t0 = System.nanoTime();
            pipeline.process();
            double ms = (System.nanoTime() - t0) / 1e6;
            dbg("processed " + ms);
            if(pipeline.getTerminalException() != null) {
                throw pipeline.getTerminalException();
            }
            double w = 0, h = 0;
            for(CvStage stage : pipeline.getStages()) {
                if(!"results".equals(stage.getName()) && !"result".equals(stage.getName())) {
                    continue;
                }
                CvStage.Result result = pipeline.getResult(stage);
                if(result != null && result.model instanceof org.opencv.core.RotatedRect) {
                    org.opencv.core.RotatedRect rect = (org.opencv.core.RotatedRect) result.model;
                    w = Math.min(rect.size.width, rect.size.height);
                    h = Math.max(rect.size.width, rect.size.height);
                }
            }
            dbg("results read");
            return new double[] {ms, w, h};
        }
        catch(Exception e) {
            System.out.println("  pre-check failed: " + e);
            return new double[] {Double.NaN, 0, 0};
        }
    }

    /**
     * One iteration of the settling loop. last[0] is the previous frame, as lastSettleMat.
     */
    static double settleFrame(Mat frame, Mat[] last) {
        long t0 = System.nanoTime();
        Mat mat = frame.clone();
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);
        // resize() is not an in-place operation; the product makes a new Mat and releases the old
        // one, and so must this.
        Mat resized = new Mat();
        Imgproc.resize(mat, resized, new Size(mat.cols() / SETTLE_DIVISOR, mat.rows() / SETTLE_DIVISOR),
                1.0 / SETTLE_DIVISOR, 1.0 / SETTLE_DIVISOR);
        mat.release();
        mat = resized;
        Imgproc.GaussianBlur(mat, mat, new Size(SETTLE_BLUR, SETTLE_BLUR), 0);
        BufferedImage recorded = OpenCvUtils.toBufferedImage(mat);
        recorded.hashCode();
        if(last[0] != null) {
            int dim = Math.min(last[0].cols(), last[0].rows());
            int margin = (int) Math.round(dim * SETTLE_MAX_RELATIVE_MOTION);
            Mat template = new Mat(last[0], new Rect(margin, margin, last[0].cols() - margin * 2,
                    last[0].rows() - margin * 2));
            Mat resultMat = new Mat();
            Imgproc.matchTemplate(mat, template, resultMat, Imgproc.TM_CCOEFF_NORMED);
            MinMaxLocResult match = Core.minMaxLoc(resultMat);
            template.release();
            resultMat.release();
            match.hashCode();
        }
        if(last[0] != null) {
            last[0].release();
        }
        last[0] = mat;
        return (System.nanoTime() - t0) / 1e6;
    }

    static String rawXml(String xmlPath) throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(xmlPath)),
                "UTF-8");
        return xml.replaceAll("(<cv-stage class=\"org\\.openpnp\\.vision\\.pipeline\\.stages"
                + "\\.ImageWriteDebug\"[^>]*?)enabled=\"true\"", "$1enabled=\"false\"");
    }

    static String pipelineXml(String xmlPath) throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(xmlPath)),
                "UTF-8");
        // The bench pipelines read the frame from disk; the product hands the capture stage a frame
        // that is already in memory, and that is the path being measured here.
        xml = xml.replaceAll("<cv-stage class=\"org\\.openpnp\\.vision\\.pipeline\\.stages\\.ImageRead\""
                + "[^/>]*name=\"([^\"]*)\"[^/]*/>",
                "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageCapture\" name=\"$1\""
                + " enabled=\"true\" default-light=\"true\" settle-option=\"Skip\"/>");
        // No debug PNG writes: the log level of a production run is not part of what is measured,
        // and a pre-check that encoded a PNG would be a bug, not a measurement.
        xml = xml.replaceAll("(<cv-stage class=\"org\\.openpnp\\.vision\\.pipeline\\.stages"
                + "\\.ImageWriteDebug\"[^>]*?)enabled=\"true\"", "$1enabled=\"false\"");
        return xml;
    }

    static void sleep(double ms) {
        try {
            Thread.sleep((long) ms);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void report(String what, double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for(double sample : sorted) {
            sum += sample;
        }
        System.out.printf("  %-46s median %7.2f ms  p90 %7.2f  max %7.2f  mean %7.2f%n", what,
                sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.9)],
                sorted[sorted.length - 1], sum / sorted.length);
    }
}
