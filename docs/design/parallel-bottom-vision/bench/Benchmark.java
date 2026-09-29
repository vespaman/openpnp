package org.openpnp.vision.pipeline;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;

/**
 * Runs a real CvPipeline (the one the C0402-22p/50V part uses, with the capture replaced by an
 * ImageRead of a captured frame) against a captured frame, and prints the per stage processing time.
 * Nothing here talks to hardware.
 */
public class Benchmark {
    static Camera mockCamera(final double uppX, final double uppY, final double width,
            final double height) {
        return (Camera)Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[] {Camera.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        switch(method.getName()) {
                            case "getName": return "Upp1";
                            case "getWidth": return (int) width;
                            case "getHeight": return (int) height;
                            case "getUnitsPerPixel":
                            case "getUnitsPerPixelAtZ":
                            case "getUnitsPerPixelXY":
                            case "getUnitsPerPixelXYExact":
                            case "getUnitsPerPixelZ":
                                return new Location(LengthUnit.Millimeters, uppX, uppY, 0.0, 0.0);
                            case "getLocation": return new Location(LengthUnit.Millimeters);
                            case "getCameraRotation": return 0.0;
                            case "toString": return "MockCamera";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                        }
                        Class<?> r = method.getReturnType();
                        if(r == boolean.class) {
                            return false;
                        }
                        if(r == int.class) {
                            return 0;
                        }
                        if(r == long.class) {
                            return 0L;
                        }
                        if(r == double.class) {
                            return 0.0d;
                        }
                        if(r == float.class) {
                            return 0.0f;
                        }
                        return null;
                    }
                });
    }

    public static void main(String[] argv) throws Exception {
        String xmlPath = argv[0];
        int reps = argv.length > 1 ? Integer.parseInt(argv[1]) : 20;
        double uppX = argv.length > 2 ? Double.parseDouble(argv[2]) : 0.0386;
        double uppY = argv.length > 3 ? Double.parseDouble(argv[3]) : 0.0341;
        double kernelMm = argv.length > 4 ? Double.parseDouble(argv[4]) : 0.1;
        double footprintMm = argv.length > 5 ? Double.parseDouble(argv[5]) : 0.0;
        // The frame the Camera would report, i.e. the whole captured image. 0 means take it from the
        // capture stage of this very pipeline, which is what it is on the machine.
        double cameraWidth = argv.length > 6 ? Double.parseDouble(argv[6]) : 0;
        double cameraHeight = argv.length > 7 ? Double.parseDouble(argv[7]) : 0;

        String xml = new String(Files.readAllBytes(Paths.get(xmlPath)), "UTF-8");
        CvPipeline pipeline = new CvPipeline();
        pipeline.fromXmlString(xml);
        List<CvStage> stages = new ArrayList<>();
        for(CvStage stage : pipeline.getStages()) {
            if(stage.isEnabled()) {
                stages.add(stage);
            }
        }
        double[][] samples = new double[stages.size()][reps];
        long[] totals = new long[reps];

        if(cameraWidth <= 0 || cameraHeight <= 0) {
            // One pass to learn the frame the capture stage produces, which is the size the Camera
            // would report on the machine, and which the crop makes smaller than what is analysed.
            apply(pipeline, uppX, uppY, kernelMm, footprintMm, 0, 0);
            pipeline.process();
            for(CvStage stage : stages) {
                CvStage.Result result = pipeline.getResult(stage);
                if(result != null && result.image != null) {
                    cameraWidth = Math.max(cameraWidth, result.image.cols());
                    cameraHeight = Math.max(cameraHeight, result.image.rows());
                }
            }
        }

        for(int i = 0; i < reps; i++) {
            pipeline.resetReusedPipeline();
            // The reset puts the stage parameters back to their XML values, exactly as it does on the
            // machine between two shots, where preparePipeline() then applies these very properties
            // again. So they have to be applied per rep, not once.
            apply(pipeline, uppX, uppY, kernelMm, footprintMm, cameraWidth, cameraHeight);
            long t0 = System.nanoTime();
            pipeline.process();
            totals[i] = System.nanoTime() - t0;
            Exception terminal = pipeline.getTerminalException();
            if(terminal != null) {
                System.out.println("TERMINAL EXCEPTION: " + terminal);
                terminal.printStackTrace();
                return;
            }
            for(int s = 0; s < stages.size(); s++) {
                CvStage.Result result = pipeline.getResult(stages.get(s));
                samples[s][i] = result == null ? 0 : result.processingTimeNs / 1e6;
            }
        }

        System.out.println(xmlPath + "  reps=" + reps);
        List<int[]> order = new ArrayList<>();
        double[] median = new double[stages.size()];
        for(int s = 0; s < stages.size(); s++) {
            double[] copy = samples[s].clone();
            java.util.Arrays.sort(copy);
            median[s] = copy[copy.length / 2];
            order.add(new int[] {s});
        }
        Collections.sort(order, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Double.compare(median[b[0]], median[a[0]]);
            }
        });
        double sum = 0;
        for(int[] o : order) {
            int s = o[0];
            CvStage stage = stages.get(s);
            CvStage.Result result = pipeline.getResult(stage);
            sum += median[s];
            String note = result == null ? "" : (result.model instanceof Exception ? result.model.toString()
                    : (result.model != null ? abbreviate(result.model) : ""));
            System.out.printf("  %7.2f ms  %-20s %-13s %s%n", median[s], stage.getClass().getSimpleName(),
                    stage.getName(), note);
        }
        java.util.Arrays.sort(totals);
        System.out.printf("  %7.2f ms  %s%n", sum, "(sum of stages)");
        System.out.printf("  %7.2f ms  %s%n", totals[totals.length / 2] / 1e6, "(whole process())");
        // The millimetres, which is what the machine steers by and what the timings above cannot say
        // anything about. The first machine trial of the crop measured 20 ms and found the part, and
        // still steered 15 mm away from it, because the pixels of the crop were read against the center
        // of the whole frame. Compare this line between the cropped and the uncropped run: they have to
        // agree, and the second of the two lines is only interesting when they do not.
        for(CvStage stage : stages) {
            if(!"results".equals(stage.getName()) && !"result".equals(stage.getName())) {
                continue;
            }
            CvStage.Result result = pipeline.getResult(stage);
            if(result == null || !(result.model instanceof org.opencv.core.RotatedRect)) {
                continue;
            }
            org.opencv.core.RotatedRect rect = (org.opencv.core.RotatedRect) result.model;
            Camera camera = mockCamera(uppX, uppY, cameraWidth, cameraHeight);
            org.openpnp.model.Location analysed = VisionUtils.getPixelCenterOffsets(camera,
                    result.image.cols(), result.image.rows(), rect.center.x, rect.center.y);
            org.openpnp.model.Location frame = VisionUtils.getPixelCenterOffsets(camera,
                    rect.center.x, rect.center.y);
            System.out.printf("  geometry %s in %dx%d px%n", abbreviate(rect), result.image.cols(),
                    result.image.rows());
            System.out.printf("  offsets, analysed image: %+.3f, %+.3f mm%n", analysed.getX(),
                    analysed.getY());
            System.out.printf("  offsets, camera frame  : %+.3f, %+.3f mm%n", frame.getX(), frame.getY());
        }
        System.out.println("  java.version=" + System.getProperty("java.version")
                + " cores=" + Runtime.getRuntime().availableProcessors());
    }

    static void apply(CvPipeline pipeline, double uppX, double uppY, double kernelMm,
            double footprintMm, double cameraWidth, double cameraHeight) {
        pipeline.setProperty("camera", mockCamera(uppX, uppY, cameraWidth, cameraHeight));
        pipeline.setProperty("MinAreaRect.center", new Location(LengthUnit.Millimeters));
        pipeline.setProperty("MinAreaRect.expectedAngle", 0.0);
        pipeline.setProperty("MaskCircle.diameter", new Length(4.0, LengthUnit.Millimeters));
        pipeline.setProperty("BlurGaussian.kernelSize", new Length(kernelMm, LengthUnit.Millimeters));
        if(footprintMm > 0) {
            // What preparePipeline() sets from shot.getMaxMaskRadius(): sqrt(2) * radius.
            pipeline.setProperty("footprint.maxWidth", new Length(footprintMm, LengthUnit.Millimeters));
            pipeline.setProperty("footprint.maxHeight", new Length(footprintMm, LengthUnit.Millimeters));
        }
    }

    static String abbreviate(Object model) {
        String s = model.toString().replaceAll("\\s+", " ");
        if(model instanceof Exception) { java.io.StringWriter w = new java.io.StringWriter(); ((Exception)model).printStackTrace(new java.io.PrintWriter(w)); return w.toString().replace("\n","\n           "); }
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
