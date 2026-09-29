import java.io.File;
import java.util.Arrays;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.openpnp.vision.pipeline.CvPipeline;

/**
 * Run a camera-free pipeline of N stages, N given on the command line, and say whether the process
 * survives it. Compiled against the fork and against upstream unchanged, so the only difference is the
 * library on the classpath.
 */
public class StockLoop {
    static final String[] STAGES = {
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ImageRead\" name=\"0\""
                + " enabled=\"true\" file=\"FRAME\" grayscale=\"false\"/>",
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.ConvertColor\" name=\"1\""
                + " enabled=\"true\" conversion=\"Bgr2Gray\"/>",
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.BlurGaussian\" name=\"2\""
                + " enabled=\"true\" kernelSize=\"9\"/>",
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.Threshold\" name=\"3\""
                + " enabled=\"true\" threshold=\"128\" invert=\"false\"/>",
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.FindContours\" name=\"4\""
                + " enabled=\"true\" retrieval=\"External\" approximation=\"Simple\"/>",
        "<cv-stage class=\"org.openpnp.vision.pipeline.stages.DrawContours\" name=\"5\""
                + " enabled=\"true\" contours=\"4\" image=\"0\" mode=\"External\""
                + " thickness=\"1\" lineType=\"8\" shift=\"0\" color=\"0,255,0,0\"/>",
    };

    public static void main(String[] argv) throws Exception {
        int reps = argv.length > 0 ? Integer.parseInt(argv[0]) : 600;
        int count = argv.length > 1 ? Integer.parseInt(argv[1]) : STAGES.length;
        boolean fresh = argv.length > 2 && "fresh".equals(argv[2]);
        nu.pattern.OpenCV.loadLocally();
        File frame = new File("frame.png");
        if(!frame.exists()) {
            Mat mat = new Mat(926, 1080, CvType.CV_8UC3);
            Core.randn(mat, 40, 60);
            Imgproc.rectangle(mat, new Point(400, 380), new Point(680, 546), new Scalar(255, 255, 255), -1);
            Imgcodecs.imwrite(frame.getAbsolutePath(), mat);
            mat.release();
        }
        StringBuilder xml = new StringBuilder("<cv-pipeline>");
        for(int i = 0; i < count; i++) {
            xml.append(STAGES[i].replace("FRAME", frame.getAbsolutePath()));
        }
        xml.append("</cv-pipeline>");
        CvPipeline reused = new CvPipeline();
        reused.fromXmlString(xml.toString());
        double[] samples = new double[reps];
        for(int i = 0; i < reps; i++) {
            // argv[3] = "fresh" builds a pipeline per run, so that process() has nothing of a previous
            // run left in it to release - the one thing this harness and ImgLoop still differ in.
            CvPipeline pipeline;
            if(fresh) {
                pipeline = new CvPipeline();
                pipeline.fromXmlString(xml.toString());
            }
            else {
                pipeline = reused;
            }
            long t0 = System.nanoTime();
            pipeline.resetReusedPipeline();
            pipeline.process();
            samples[i] = (System.nanoTime() - t0) / 1e6;
            if(fresh) {
                pipeline.close();
            }
        }
        Arrays.sort(samples);
        System.out.println("OK " + reps + " runs of " + count + " stages, java="
                + System.getProperty("java.version") + ", median "
                + String.format("%.2f ms", samples[reps / 2]));
    }
}
