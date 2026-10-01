package org.openpnp.vision;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.openpnp.util.OpenCvUtils;

/**
 * What a dumped frame costs the thread that has just captured it, as against the thread that writes it.
 *
 * <p>{@link FrameDump} claims that the capture thread pays only for a copy of the pixels and that the
 * PNG encoding - forty times the work - happens on a thread of its own at minimum priority. That claim
 * is why a dump can be switched on during a run whose timings matter, so it is worth measuring rather
 * than asserting. It measures, and deliberately does not assert: a millisecond on one machine is not a
 * millisecond on another, and a test that fails because a build server was busy teaches nobody
 * anything. Run it on the machine when a dump is suspected of slowing the machine down:</p>
 *
 * <pre>
 * mvn -Dtest=FrameDumpCostTest test
 * </pre>
 */
public class FrameDumpCostTest {

    @Test
    public void theCopyIsCheapAndTheEncodingIsSomebodyElses() throws Exception {
        // Touching OpenCvUtils is what loads the native library; the pipeline loads it the same way.
        Class.forName(OpenCvUtils.class.getName());

        File corpus = new File("tests_2026-10-01/bv-corpus");
        File[] pngs = corpus.listFiles((dir, name) -> name.endsWith(".png"));
        if (pngs == null || pngs.length == 0) {
            System.out.println("no corpus to measure");
            return;
        }
        Mat image = Imgcodecs.imread(pngs[0].getAbsolutePath());
        long bytes = image.total()*image.elemSize();
        System.out.println("measuring a "+image.cols()+"x"+image.rows()+" frame of "
                +bytes/1024/1024+" MB of pixels, "+pngs[0].getName());

        int copies = 200;
        long start = System.nanoTime();
        for (int i = 0; i < copies; i++) {
            OpenCvUtils.toBufferedImage(image).flush();
        }
        double copy = (System.nanoTime() - start) / 1e6 / copies;

        File scratch = File.createTempFile("frame-dump-cost", ".png");
        int writes = 20;
        start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            Imgcodecs.imwrite(scratch.getAbsolutePath(), image);
        }
        double encode = (System.nanoTime() - start) / 1e6 / writes;
        long written = scratch.length();
        scratch.delete();

        System.out.println(String.format(
                "the capture thread pays %.2f ms per frame for the copy; the writer thread pays "
                        + "%.2f ms for the PNG, %s on disk",
                copy, encode, formatBytes(written)));
    }

    private String formatBytes(long bytes) {
        return bytes < 1024 ? bytes+" B" : bytes/1024+" kB";
    }
}
