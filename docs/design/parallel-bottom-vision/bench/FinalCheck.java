import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicInteger;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * OpenCV's own Java wrapper deletes the native Mat from finalize():
 *
 *   protected void finalize() throws Throwable { n_delete(nativeObj); ... }
 *   public void release()                     { n_release(nativeObj); }   // does not clear nativeObj
 *
 * so everything depends on whether a JVM still runs finalizers, and how fast. Two questions, answered here
 * on whatever JVM runs it:
 *
 *   1. does finalize() get called at all, and how many of a known number of objects?
 *   2. if Mats are dropped without release(), does their native memory come back - i.e. is the finalizer
 *      doing the work? Measured as VmRSS after a full GC, so a JVM that stopped finalizing shows up as a
 *      leak of exactly the frames left behind.
 *
 * Run:  java -cp out11:lib/* FinalCheck in/frame.png 2000
 */
public class FinalCheck {
    static final AtomicInteger FINALISED = new AtomicInteger();

    static class Hook {
        byte[] keep = new byte[64];
        @Override protected void finalize() throws Throwable {
            FINALISED.incrementAndGet();
            super.finalize();
        }
    }

    public static void main(String[] argv) throws Exception {
        String framePath = argv.length > 0 ? argv[0] : "in/frame.png";
        int reps = argv.length > 1 ? Integer.parseInt(argv[1]) : 2000;
        Class.forName("org.openpnp.vision.pipeline.CvPipeline");
        System.out.println("java=" + System.getProperty("java.version") + " "
                + System.getProperty("java.vm.name"));

        int hooks = 20000;
        for(int i = 0; i < hooks; i++) {
            new Hook();
        }
        System.gc();
        Thread.sleep(1500);
        System.gc();
        Thread.sleep(1500);
        System.out.println("finalizers run for " + FINALISED.get() + " of " + hooks + " unreachable objects");

        Mat probe = Imgcodecs.imread(framePath);
        long bytes = (long) probe.total() * probe.elemSize();
        System.out.println("one frame = " + probe.cols() + "x" + probe.rows() + " = " + bytes / 1024 + " KiB");
        probe.release();

        System.out.println("  reps |  VmRSS MiB | finalisers");
        for(int i = 1; i <= reps; i++) {
            Mat m = Imgcodecs.imread(framePath); // never released on purpose
            if(m.empty()) {
                System.out.println("cannot read " + framePath);
                return;
            }
            if(i % 100 == 0) {
                System.gc();
                Thread.sleep(400);
                System.out.printf("  %4d | %10.1f | %d%n", i, rssMiB(), FINALISED.get());
            }
        }
    }

    static double rssMiB() throws Exception {
        try(BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while((line = r.readLine()) != null) {
                if(line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("\\D+", "")) / 1024.0;
                }
            }
        }
        return -1;
    }
}
