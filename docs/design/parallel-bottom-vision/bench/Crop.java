import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

public class Crop {
    public static void main(String[] argv) {
        nu.pattern.OpenCV.loadLocally();
        Mat src = Imgcodecs.imread(argv[0]);
        System.out.println(argv[0] + " " + src.cols() + "x" + src.rows() + " ch=" + src.channels());
        for(int i = 1; i < argv.length; i++) {
            int side = Integer.parseInt(argv[i]);
            int x = (src.cols() - side) / 2;
            int y = (src.rows() - side) / 2;
            Mat crop = src.submat(y, y + side, x, x + side).clone();
            String out = "in/crop" + side + ".png";
            Imgcodecs.imwrite(out, crop);
            System.out.println("  wrote " + out + " " + crop.cols() + "x" + crop.rows());
        }
    }
}
