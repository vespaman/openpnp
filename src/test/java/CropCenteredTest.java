import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.FluentCv;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.CropCentered;
import org.openpnp.vision.pipeline.stages.MinAreaRect;

import com.google.common.io.Files;

/**
 * The crop that makes the pixel-wise stages cheap has to be centered, because everything downstream
 * reads the offset of the part off the center of the image. So the tests are about the size, and about
 * nothing moving.
 */
public class CropCenteredTest {
    static class SourceStage extends CvStage {
        private final int width;
        private final int height;

        SourceStage(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public Result process(CvPipeline pipeline) {
            Mat mat = new Mat(height, width, CvType.CV_8UC1);
            mat.setTo(new org.opencv.core.Scalar(0));
            // A 10x10 mark whose center is 40,60 in a 100x100 frame.
            for(int y = 55; y < 65; y++) {
                for(int x = 35; x < 45; x++) {
                    mat.put(y, x, 255);
                }
            }
            return new Result(mat);
        }
    }

    private static boolean configurationInitialized = false;

    private static synchronized void initializeConfiguration() throws Exception {
        // The stages convert millimetres through the machine as soon as a camera is involved. Once
        // per JVM is enough; initializing a second machine over the first one is not something the
        // rest of the test suite does either.
        if(configurationInitialized) {
            return;
        }
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        configurationInitialized = true;
    }

    private CvPipeline build(double width, double height, double margin, double minimum,
            Length footprintMaxWidth, Length footprintMaxHeight) throws Exception {
        CvPipeline pipeline = new CvPipeline();
        if(footprintMaxWidth != null) {
            pipeline.setProperty("camera", new VisionUtilsTest.TestCamera());
            pipeline.setProperty("footprint.maxWidth", footprintMaxWidth);
            pipeline.setProperty("footprint.maxHeight", footprintMaxHeight);
        }
        pipeline.add(new SourceStage(100, 100));
        CropCentered stage = new CropCentered();
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setMargin(margin);
        stage.setMinimum(minimum);
        pipeline.add(stage);
        pipeline.process();
        return pipeline;
    }

    private double[] centerOfMark(CvPipeline pipeline) {
        Mat mat = pipeline.getWorkingImage();
        double sumX = 0;
        double sumY = 0;
        double count = 0;
        for(int y = 0; y < mat.rows(); y++) {
            for(int x = 0; x < mat.cols(); x++) {
                if(mat.get(y, x)[0] > 0) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        return new double[] {sumX/count, sumY/count};
    }

    @Test
    public void testCropsToGivenSize() throws Exception {
        CvPipeline pipeline = build(40, 30, 0, 0, null, null);
        assertEquals(40, pipeline.getWorkingImage().cols());
        assertEquals(30, pipeline.getWorkingImage().rows());
    }

    @Test
    public void testCropsToTheFootprintTheMachineComputes() throws Exception {
        // The test camera is 1 mm per pixel, so the footprint comes through as pixels.
        initializeConfiguration();
        CvPipeline pipeline = build(0, 0, 0, 0,
                new Length(30, LengthUnit.Millimeters), new Length(20, LengthUnit.Millimeters));
        assertEquals(30, pipeline.getWorkingImage().cols());
        assertEquals(20, pipeline.getWorkingImage().rows());
    }

    @Test
    public void testMarginAndMinimum() throws Exception {
        CvPipeline pipeline = build(40, 40, 5, 0, null, null);
        assertEquals(50, pipeline.getWorkingImage().cols());
        pipeline = build(10, 10, 0, 60, null, null);
        assertEquals(60, pipeline.getWorkingImage().cols());
    }

    @Test
    public void testNeverBiggerThanTheFrameAndNeverBroken() throws Exception {
        // Asked for more than there is: the frame passes through.
        CvPipeline pipeline = build(1000, 1000, 0, 0, null, null);
        assertEquals(100, pipeline.getWorkingImage().cols());
        // Asked for nothing at all, with nothing to derive it from: likewise, a badly configured
        // stage must not fail a job.
        pipeline = build(0, 0, 0, 0, null, null);
        assertEquals(100, pipeline.getWorkingImage().cols());
    }

    @Test
    public void testTheOffsetFromTheCenterIsPreserved() throws Exception {
        // What the pipeline reports is the mark's distance from the center of the image, so that is
        // what must survive the crop, not the pixel coordinate itself.
        CvPipeline full = build(0, 0, 0, 0, null, null);
        CvPipeline cropped = build(40, 40, 0, 0, null, null);
        double[] before = centerOfMark(full);
        double[] after = centerOfMark(cropped);
        assertEquals(before[0] - full.getWorkingImage().cols()/2.,
                after[0] - cropped.getWorkingImage().cols()/2., 0.5);
        assertEquals(before[1] - full.getWorkingImage().rows()/2.,
                after[1] - cropped.getWorkingImage().rows()/2., 0.5);
    }

    /**
     * A frame the size of the test camera's, with a mark 20 mm to the right and 10 mm below the point
     * the camera looks at, in the grayscale form the geometry stages want.
     */
    static class GeometrySourceStage extends CvStage {
        @Override
        public Result process(CvPipeline pipeline) {
            Camera camera = (Camera) pipeline.getProperty("camera");
            Mat mat = new Mat((int) camera.getHeight(), (int) camera.getWidth(), CvType.CV_8UC1);
            mat.setTo(new org.opencv.core.Scalar(0));
            double centerX = camera.getWidth()/2. + 20;
            double centerY = camera.getHeight()/2. + 10;
            for(int y = (int) centerY - 6; y < (int) centerY + 6; y++) {
                for(int x = (int) centerX - 6; x < (int) centerX + 6; x++) {
                    mat.put(y, x, 255);
                }
            }
            return new Result(mat, FluentCv.ColorSpace.Gray);
        }
    }

    /**
     * Reads back how a millimetre location, e.g. the wanted location of the part, is turned into the
     * pixels the stages work in.
     */
    static class ReadLocationStage extends CvStage {
        @Override
        public Result process(CvPipeline pipeline) throws Exception {
            return new Result(null, getPossiblePipelinePropertyOverride(new org.opencv.core.Point(0, 0),
                    pipeline, "wanted.center", org.opencv.core.Point.class, Location.class));
        }
    }

    private CvStage.Result detectMark(double cropWidth, double cropHeight) throws Exception {
        CvPipeline pipeline = new CvPipeline();
        pipeline.setProperty("camera", new VisionUtilsTest.TestCamera());
        pipeline.add(new GeometrySourceStage());
        if(cropWidth > 0) {
            CropCentered crop = new CropCentered();
            crop.setWidth(cropWidth);
            crop.setHeight(cropHeight);
            crop.setMinimum(0);
            pipeline.add(crop);
        }
        MinAreaRect minAreaRect = new MinAreaRect();
        minAreaRect.setName("results");
        minAreaRect.setThresholdMin(1);
        minAreaRect.setThresholdMax(255);
        pipeline.add(minAreaRect);
        pipeline.process();
        return pipeline.getResult("results");
    }

    @Test
    public void testTheMillimetreOffsetsSurviveTheCrop() throws Exception {
        // The machine reports where the part is relative to the center of the image the pipeline
        // looked at, so a crop may not change the answer. This is the millimetre version of the check
        // above, and it is the one that matters: the first machine trial of the crop found the part
        // dead center and then steered 15 mm away from it, because the pixels of the crop were read
        // against the center of the whole frame.
        initializeConfiguration();
        Camera camera = new VisionUtilsTest.TestCamera();
        CvStage.Result full = detectMark(0, 0);
        CvStage.Result cropped = detectMark(100, 100);
        RotatedRect fullRect = (RotatedRect) full.model;
        RotatedRect croppedRect = (RotatedRect) cropped.model;

        Location fullOffsets = VisionUtils.getPixelCenterOffsets(camera,
                full.image.cols(), full.image.rows(), fullRect.center.x, fullRect.center.y);
        Location croppedOffsets = VisionUtils.getPixelCenterOffsets(camera,
                cropped.image.cols(), cropped.image.rows(), croppedRect.center.x, croppedRect.center.y);

        // Sanity: the mark is 20 mm right and 10 mm below where the camera looks.
        assertEquals(20, Math.abs(fullOffsets.getX()), 0.5);
        assertEquals(10, Math.abs(fullOffsets.getY()), 0.5);
        assertEquals(fullOffsets.getX(), croppedOffsets.getX(), 0.01);
        assertEquals(fullOffsets.getY(), croppedOffsets.getY(), 0.01);

        // And this is what the bug was: the same pixels referred to the whole camera frame.
        Location broken = VisionUtils.getPixelCenterOffsets(camera, croppedRect.center.x,
                croppedRect.center.y);
        assertTrue(Math.abs(broken.getX()) > 100);
    }

    @Test
    public void testAWantedLocationFollowsTheCroppedImage() throws Exception {
        // The other direction: a stage that is told where in millimetres to look, e.g. the wanted
        // location of the part, has to be told in the pixels of the image the pipeline actually looks
        // at, which a CropCentered has shrunk around the same center.
        initializeConfiguration();
        CvPipeline full = new CvPipeline();
        full.setProperty("camera", new VisionUtilsTest.TestCamera());
        full.setProperty("wanted.center", new Location(LengthUnit.Millimeters, 10, 0, 0, 0));
        ReadLocationStage fullReader = new ReadLocationStage();
        fullReader.setName("read");
        full.add(fullReader);
        full.process();
        org.opencv.core.Point fullPoint = (org.opencv.core.Point) full.getResult("read").model;

        CvPipeline cropped = new CvPipeline();
        cropped.setProperty("camera", new VisionUtilsTest.TestCamera());
        cropped.setProperty("wanted.center", new Location(LengthUnit.Millimeters, 10, 0, 0, 0));
        cropped.add(new GeometrySourceStage());
        CropCentered crop = new CropCentered();
        crop.setWidth(100);
        crop.setHeight(100);
        crop.setMinimum(0);
        cropped.add(crop);
        ReadLocationStage croppedReader = new ReadLocationStage();
        croppedReader.setName("read");
        cropped.add(croppedReader);
        cropped.process();
        org.opencv.core.Point croppedPoint = (org.opencv.core.Point) cropped.getResult("read").model;

        // Both are 10 mm right of the center of the image they end up in, so the cropped one has moved
        // left by half a frame; had it stayed put, the stage would be looking at nothing.
        assertEquals(330, fullPoint.x, 0.01);
        assertEquals(60, croppedPoint.x, 0.01);
        assertEquals(240, fullPoint.y, 0.01);
        assertEquals(50, croppedPoint.y, 0.01);
    }
}
