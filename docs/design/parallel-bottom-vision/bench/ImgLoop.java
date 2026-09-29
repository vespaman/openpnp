import java.io.File;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * CvPipeline dies on a loop of one ImageRead stage, RawLoop does the same work by hand and does not. The
 * difference between the two is one clone per run and who releases it, so this is the same decode with the
 * clone handled four different ways.
 *
 * argv: reps mode
 *   0 decode only, release
 *   1 decode, clone, release clone then decode
 *   2 decode, clone, release both
 *   3 decode, clone, release the decode, leave the clone to the finalizer
 *   4 decode, clone, release the clone, leave the decode to the finalizer
 *   5 decode, clone, release neither
 */
public class ImgLoop {
    public static void main(String[] argv) throws Exception {
        int reps = argv.length > 0 ? Integer.parseInt(argv[0]) : 600;
        int mode = argv.length > 1 ? Integer.parseInt(argv[1]) : 0;
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
            if(mode == 0) {
                image.release();
                continue;
            }
            Mat clone = image.clone();
            switch(mode) {
                case 1:
                    clone.release();
                    image.release();
                    break;
                case 2:
                    image.release();
                    clone.release();
                    break;
                case 3:
                    image.release();
                    break;
                case 4:
                    clone.release();
                    break;
                default:
                    break;
            }
        }
        System.out.println("OK " + reps + " runs, mode " + mode + ", java="
                + System.getProperty("java.version"));
    }
}
