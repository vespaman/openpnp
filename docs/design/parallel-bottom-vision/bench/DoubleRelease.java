import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * CvPipeline.release() releases the Mats of the results it drops; the OpenCV finalizer releases them
 * again whenever the wrapper is collected. Releasing twice in a row with nothing in between is harmless,
 * so the question is what happens when the heap is busy in between, which is what a pipeline does.
 *
 * argv: reps [gap]
 */
public class DoubleRelease {
    public static void main(String[] a) throws Exception {
        int reps = a.length > 0 ? Integer.parseInt(a[0]) : 200;
        int gap = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        nu.pattern.OpenCV.loadLocally();
        for(int i = 0; i < reps; i++) {
            Mat m = new Mat(926, 1080, CvType.CV_8UC3);
            m.release();
            Mat[] busy = new Mat[gap];
            for(int j = 0; j < gap; j++) {
                busy[j] = new Mat(64, 64, CvType.CV_8UC3);
            }
            for(int j = 0; j < gap; j++) {
                busy[j].release();
            }
            m.release();
        }
        System.out.println("OK " + reps + " double releases with gap " + gap + ", java="
                + System.getProperty("java.version"));
    }
}
