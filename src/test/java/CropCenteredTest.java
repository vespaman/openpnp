import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.CropCentered;

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
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
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
}
