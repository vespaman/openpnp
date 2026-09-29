package org.openpnp.vision.pipeline.stages;

import org.opencv.core.Mat;
import org.openpnp.model.Length;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.Property;
import org.openpnp.vision.pipeline.Stage;
import org.simpleframework.xml.Attribute;

@Stage(description="Crop the working image to a rectangle centered on the center of the image.")
public class CropCentered extends CvStage {
    @Attribute(required = false)
    @Property(description="Width of the crop in pixels. Use 0 to take it from the pipeline property "
            + "\"footprint.maxWidth\", which is what bottom vision computes from the part size and the "
            + "maximum mask radius. If that is not set either, the image is passed through unchanged.")
    private double width = 0;

    @Attribute(required = false)
    @Property(description="Height of the crop in pixels, 0 meaning the pipeline property "
            + "\"footprint.maxHeight\" as for the width.")
    private double height = 0;

    @Attribute(required = false)
    @Property(description="Added to the width and the height on each side. A part is allowed to sit up to "
            + "the maximum vision offset away from the center, and it can be a little bigger than the "
            + "package says.")
    private double margin = 0;

    @Attribute(required = false)
    @Property(description="Never crop to less than this many pixels on a side, however small the part is. "
            + "The pixel-wise stages stop being the cost below a few hundred pixels anyway, so the default "
            + "keeps a margin of robustness for free.")
    private double minimum = 200;

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public double getMargin() {
        return margin;
    }

    public void setMargin(double margin) {
        this.margin = margin;
    }

    public double getMinimum() {
        return minimum;
    }

    public void setMinimum(double minimum) {
        this.minimum = minimum;
    }

    /**
     * The crop is always centered, and that is not a limitation but the whole point: everything that
     * turns pixels into millimetres assumes that the center of the image is the point the camera is
     * looking at, and a centered crop keeps that true of the crop as much as of the whole frame. A crop
     * offset from the center would shift every offset the pipeline reports by half that offset,
     * silently.
     *
     * The other half of that agreement is that the pixels of the crop must be read against the center of
     * the crop, and not against the center of the frame the camera reports: a part found dead center in
     * a 200 px crop of an 805 px frame is 0 mm away, but read against the frame it is 11 mm away, which
     * is what the machine did on 2026-09-28 and why it steered away from a part it had just found. The
     * image a detection was made in therefore travels with the detection, see
     * VisionUtils#getPixelCenterOffsets(Camera, double, double, double, double) and the millimetre to
     * pixel conversion in CvStage#getPossiblePipelinePropertyOverride, which uses the size of the working
     * image for the same reason.
     *
     * The image before the crop is not lost: the stage that captured it still has its result, so an
     * ImageRecall of that stage brings the full frame back, which is how a pipeline falls back to
     * analyzing everything when it has found nothing inside the crop. Do that only at the end however,
     * after the geometry stages have run, or those will again be looking at a frame they are not the
     * center of.
     */
    @Override
    public Result process(CvPipeline pipeline) throws Exception {
        Mat mat = pipeline.getWorkingImage();
        if(mat == null || mat.empty()) {
            return null;
        }
        double width = getPossiblePipelinePropertyOverride(this.width, pipeline, "footprint.maxWidth",
                Double.class, Length.class);
        double height = getPossiblePipelinePropertyOverride(this.height, pipeline, "footprint.maxHeight",
                Double.class, Length.class);
        if(width <= 0 && height <= 0) {
            // Nothing was asked for, and nothing can be derived. Pass the frame through rather than
            // fail a job because a stage was configured badly.
            return null;
        }
        if(width <= 0) {
            width = mat.cols();
        }
        if(height <= 0) {
            height = mat.rows();
        }
        width += 2*margin;
        height += 2*margin;
        width = Math.max(width, minimum);
        height = Math.max(height, minimum);
        int cropWidth = (int)Math.min(mat.cols(), Math.round(width));
        int cropHeight = (int)Math.min(mat.rows(), Math.round(height));
        if(cropWidth <= 0 || cropHeight <= 0) {
            return null;
        }
        if(cropWidth == mat.cols() && cropHeight == mat.rows()) {
            return null;
        }
        int x = (mat.cols() - cropWidth)/2;
        int y = (mat.rows() - cropHeight)/2;
        return new Result(mat.submat(y, y + cropHeight, x, x + cropWidth).clone());
    }
}
