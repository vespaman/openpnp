import java.io.File;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * The same work as StockLoop with no OpenPnP in it at all: OpenCV called directly, in a loop, on the
 * thread that called it. If this dies too, nothing in OpenPnP is.
 */
public class RawLoop {
    public static void main(String[] argv) throws Exception {
        int reps = argv.length > 0 ? Integer.parseInt(argv[0]) : 600;
        nu.pattern.OpenCV.loadLocally();
        File frame = new File("frame.png");
        if(!frame.exists()) {
            Mat mat = new Mat(926, 1080, CvType.CV_8UC3);
            Core.randn(mat, 40, 60);
            Imgproc.rectangle(mat, new Point(400, 380), new Point(680, 546), new Scalar(255, 255, 255), -1);
            Imgcodecs.imwrite(frame.getAbsolutePath(), mat);
            mat.release();
        }
        for(int i = 0; i < reps; i++) {
            Mat image = Imgcodecs.imread(frame.getAbsolutePath());
            Mat gray = new Mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new org.opencv.core.Size(9, 9), 0);
            Imgproc.threshold(gray, gray, 128, 255, Imgproc.THRESH_BINARY);
            java.util.List<MatOfPoint> contours = new java.util.ArrayList<>();
            Imgproc.findContours(gray, contours, new Mat(), Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            Imgproc.drawContours(image, contours, -1, new Scalar(0, 255, 0), 1);
            for(MatOfPoint contour : contours) {
                contour.release();
            }
            image.release();
            gray.release();
        }
        System.out.println("OK " + reps + " raw runs, java=" + System.getProperty("java.version"));
    }
}
