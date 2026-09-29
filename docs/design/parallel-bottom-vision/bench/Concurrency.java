package org.openpnp.vision.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class Concurrency {
    public static void main(String[] a) throws Exception {
        Class.forName("org.openpnp.vision.pipeline.CvPipeline");
        Mat frame = Imgcodecs.imread(a[0]);
        int threads = Integer.parseInt(a.length > 1 ? a[1] : "2");
        int iterations = Integer.parseInt(a.length > 2 ? a[2] : "200");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for(int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for(int i = 0; i < iterations; i++) {
                    Mat m = frame.clone();
                    Mat hsv = new Mat();
                    Imgproc.cvtColor(m, hsv, Imgproc.COLOR_BGR2HSV);
                    Imgproc.GaussianBlur(hsv, hsv, new Size(5, 5), 0);
                    Mat resized = new Mat();
                    Imgproc.resize(hsv, resized, new Size(m.cols() / 3.0, m.rows() / 3.0));
                    m.release(); hsv.release(); resized.release();
                }
            }));
        }
        for(Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        System.out.println("ok: " + threads + " threads x " + iterations + " iterations");
    }
}
