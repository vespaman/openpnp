package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.apache.commons.io.IOUtils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.openpnp.ConfigurationListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration.BackgroundCalibrationMethod;
import org.openpnp.machine.reference.vision.wizards.BottomVisionSettingsConfigurationWizard;
import org.openpnp.machine.reference.vision.wizards.ReferenceBottomVisionConfigurationWizard;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.CapturedShot;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.FrameNotStillException;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.PartSettingsHolder;
import org.openpnp.model.Placement;
import org.openpnp.model.PreCheckResult;
import org.openpnp.model.PreCheckResult.Verdict;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.model.VisionCompositing;
import org.openpnp.model.VisionCompositing.Composite;
import org.openpnp.model.VisionCompositing.Shot;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.LogUtils;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.NanosecondTime;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.FrameDump;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvPipeline.PipelineShot;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementMap;
import org.simpleframework.xml.Root;

public class ReferenceBottomVision extends AbstractPartAlignment {

    /**
     * How many frames one camera will grab at most while waiting for another camera's settling to open
     * a still window. The waiting is bounded by a deadline as well, and this is the bound for the case
     * where the deadline cannot be trusted, because a capture loop that does not end is a head that
     * never leaves the cameras.
     */
    protected static final int MAX_WINDOW_GRABS = 500;

    /**
     * How patiently a camera waits for another camera's settling: it polls the settling rather than
     * counting milliseconds, and it polls by sleeping, because the waiting is not the work and a
     * spinning thread is one less core for the settling to measure on.
     */
    protected static final long SETTLE_POLL_MS = 1;

    @Deprecated
    @Element(required = false)
    protected CvPipeline pipeline;

    @Attribute(required = false)
    protected boolean enabled = false;

    @Attribute(required = false)
    protected boolean preRotate = false;

    @Attribute(required = false)
    protected int maxVisionPasses = 3;

    @Element(required = false)
    protected Length maxLinearOffset = new Length(1, LengthUnit.Millimeters);

    @Attribute(required = false)
    protected double maxAngularOffset = 10;

    @Attribute(required = false)
    protected double testAlignmentAngle = 0.0;

    @Attribute(required = false)
    @Deprecated
    private Integer edgeDetectionPixels = null;

    @Deprecated
    @ElementMap(required = false)
    protected Map<String, PartSettings> partSettingsByPartId = null;

    public ReferenceBottomVision() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration configuration) throws Exception {
                migratePartSettings(configuration);
                if (bottomVisionSettings == null) {
                    // Recovery mode, take any setting.
                    for (AbstractVisionSettings settings : configuration.getVisionSettings()) {
                        if (settings instanceof BottomVisionSettings) {
                            bottomVisionSettings = (BottomVisionSettings) settings;
                            break;
                        }
                    }
                }
            }
        });
    }

    @Override
    public PartAlignmentOffset findOffsets(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle) throws Exception {
        return findOffsets(part, boardLocation, placement, nozzle, false);
    }

    public PartAlignmentOffset findOffsets(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, boolean skipMovement) throws Exception {
        return findOffsets(part, boardLocation, placement, nozzle, skipMovement, null);
    }

    public PartAlignmentOffset findOffsets(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, boolean skipMovement, 
            CapturedShot preCapturedShot) throws Exception {
        BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);

        if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
            return new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false);
        }

        if (part == null || nozzle.getPart() == null) {
            throw new Exception("No part on nozzle.");
        }
        if (part != nozzle.getPart()) {
            throw new Exception("Part mismatch with part on nozzle.");
        }

        Camera camera = VisionUtils.getBottomVisionCamera(nozzle);
        // A frame is only worth aligning upon if it can show that the head stood still while the
        // camera integrated it. Ask the frame, not whoever captured it.
        requireStillFrame(preCapturedShot, camera, part, nozzle);
        PartAlignmentOffset offsets;
        if ((bottomVisionSettings.getPreRotateUsage() == PreRotateUsage.Default && preRotate)
                || (bottomVisionSettings.getPreRotateUsage() == PreRotateUsage.AlwaysOn)) {
            offsets = findOffsetsPreRotate(part, boardLocation, placement, nozzle, camera, bottomVisionSettings, skipMovement, preCapturedShot);
        }
        else {
            offsets = findOffsetsPostRotate(part, boardLocation, placement, nozzle, camera, bottomVisionSettings, skipMovement, preCapturedShot);
        }
        if (nozzle.isAligningRotationMode()) {
            // Add the rotation offset to the rotation mode rather than adjusting for it in placement. This has the advantage of
            // showing the rotation aligned with the part rotation in the DRO, cross-hairs etc.
            double rotOff = nozzle.getRotationModeOffset() != null ? nozzle.getRotationModeOffset() : 0;
            nozzle.setRotationModeOffset(rotOff + offsets.getLocation().getRotation());
            Location newOffsets = offsets.getLocation().derive(null, null, null, 0.);
            offsets = new PartAlignmentOffset(newOffsets, offsets.getPreRotated()); 
        }
        return offsets;
    }

    /**
     * Refuse to measure a part on a frame that cannot show the head was still.
     *
     * This belongs here, at the entrance of the alignment, rather than with whoever captured the
     * frame, because the two are far apart in time and in mind: the capture is trying to be quick and
     * the measurement is trying to be true, and the one must not have to trust the other. A frame
     * arrives with a {@link FrameProvenance} and either it says the head was still or the alignment
     * does not look at it.
     *
     * No frame at all is not a failure: it simply means the alignment is the ordinary kind, which
     * settles and then captures its own.
     *
     * @throws FrameNotStillException so that the caller can tell a frame that was not qualified apart
     *             from a part that could not be found, and count them separately.
     */
    protected static void requireStillFrame(CapturedShot preCapturedShot, Camera camera, Part part,
            Nozzle nozzle) throws FrameNotStillException {
        if (preCapturedShot == null) {
            return;
        }
        FrameProvenance provenance = preCapturedShot.getProvenance();
        if (provenance.isStill()) {
            return;
        }
        throw new FrameNotStillException("refusing to align part "+part.getId()+" on nozzle "
                +nozzle.getId()+" from camera "+camera.getName()+": the frame is not a still frame ("
                +provenance+")", provenance);
    }

    @Override
    public Location getLocation(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle) throws Exception {
        BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);

        if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
            return null;
        }

        Camera camera = VisionUtils.getBottomVisionCamera(nozzle);
        double wantedAngle = 0.0;
        if ((bottomVisionSettings.getPreRotateUsage() == PreRotateUsage.Default && preRotate)
                || (bottomVisionSettings.getPreRotateUsage() == PreRotateUsage.AlwaysOn)) {

            wantedAngle = placement.getLocation().getRotation();
            if (boardLocation != null) {
                wantedAngle = Utils2D.calculateBoardPlacementLocation(boardLocation, placement.getLocation())
                        .getRotation();
            }
            wantedAngle = Utils2D.angleNorm(wantedAngle, 180.);
        }
        
        Location wantedLocation = getCameraLocationAtPartHeight(part, camera, nozzle, wantedAngle);

        return wantedLocation;
    }
    
    public Location getCameraLocationAtPartHeight(Part part, Camera camera, Nozzle nozzle, double angle) throws Exception {
        return getCameraLocationAtPartHeight(part, camera, nozzle, angle, false);
    }

    public Location getCameraLocationAtPartHeight(Part part, Camera camera, Nozzle nozzle, double angle, boolean skipMovement) throws Exception {
        if (part == null) {
            // No part height accounted for.
            return camera.getLocation(nozzle)
                    .derive(null, null, null, angle);
        }
        if (part.isPartHeightUnknown()) {
            if (skipMovement) {
                throw new Exception("Part height unknown and skipMovement is enabled. Part height must be determined before parallel vision processing.");
            }
            if (camera.getFocusProvider() != null
                    && nozzle.getNozzleTip() != null) {
                NozzleTip nt = nozzle.getNozzleTip();
                
                Location locationNominal = camera.getLocation(nozzle)
                        .derive(null, null, null, angle);
                BottomVisionSettings bottomVisionSettings = ReferenceBottomVision.getDefault()
                        .getInheritedVisionSettings(part);
                Composite composite = part.getPackage().getVisionCompositing().new Composite(part.getPackage(), bottomVisionSettings, nozzle, nt, 
                        camera, locationNominal);
                Length partHeight = new Length(0, LengthUnit.Millimeters);
                int weight = 0;
                for (Shot shot : composite.getShotsTravel()) {
                    Location location1 = composite.getShotLocation(shot);
                    Location location0 = location1.add(new Location(nt.getMaxPartHeight().getUnits(), 
                            0, 0, nt.getMaxPartHeight().getValue(), 0));
                    Location focus = camera.getFocusProvider().autoFocus(camera, nozzle, nt.getMaxPartDiameter()
                            .add(nt.getMaxPickTolerance().multiply(2.0)), location0, location1);
                    partHeight = partHeight.add(focus.getLengthZ().subtract(location1.getLengthZ()));
                    weight++;
                }
                partHeight = partHeight.divide(weight);
                if (partHeight.convertToUnits(LengthUnit.Millimeters).getValue() <= 0.001) {
                    throw new Exception("Auto focus part height determination failed. Camera seems to have focused on nozzle tip.");
                }
                Logger.info("Part "+part.getId()+" height set to "+partHeight+" by camera focus provider.");
                part.setHeight(partHeight);
            }
            if (part.isPartHeightUnknown()) {
                throw new Exception("Part height unknown and camera "+camera.getName()+" does not support part height sensing.");
            }
        }
        return camera.getLocation(nozzle)
                .add(new Location(part.getHeight()
                        .getUnits(),
                        0.0, 0.0, part.getHeight()
                        .getValue(),
                        0.0))
                .derive(null, null, null, angle);
    }

    private PartAlignmentOffset findOffsetsPreRotate(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, Camera camera, BottomVisionSettings bottomVisionSettings)
                    throws Exception {
        return findOffsetsPreRotate(part, boardLocation, placement, nozzle, camera, bottomVisionSettings, false, null);
    }

    private PartAlignmentOffset findOffsetsPreRotate(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, Camera camera, BottomVisionSettings bottomVisionSettings,
            boolean skipMovement, CapturedShot preCapturedShot) throws Exception {
        double wantedAngle = placement.getLocation().getRotation();
        if (boardLocation != null) {
            wantedAngle = Utils2D.calculateBoardPlacementLocation(boardLocation, placement.getLocation())
                    .getRotation();
        }
        wantedAngle = Utils2D.angleNorm(wantedAngle, 180.);
        // Wanted location.
        Location wantedLocation = getCameraLocationAtPartHeight(part, camera, nozzle, wantedAngle, skipMovement);

        Location nozzleLocation = wantedLocation;
        final Location center = new Location(maxLinearOffset.getUnits());

        try (CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings)) {

            // The running, iterative offset.
            Location offsets = new Location(nozzleLocation.getUnits());
            RotatedRect rect;
            // Try getting a good fix on the part in multiple passes.
            for(int pass = 0;;) {
                PipelineGeometry geometry = processPipelineAndGetResult(pipeline, camera, part, nozzle,
                        wantedLocation, nozzleLocation, bottomVisionSettings, skipMovement, preCapturedShot);
                rect = geometry.rect;

                // For parallel alignment with pre-captured shots, use displacement delta from the shot
                if (preCapturedShot instanceof PreCapturedPlacement) {
                    Location displacement = ((PreCapturedPlacement) preCapturedShot).getIntendedDisplacementDelta();
                    if (displacement != null) {
                        String cameraName = ((PreCapturedPlacement) preCapturedShot).getCamera().getName();
                        Logger.debug("{} displacement (parallel), using displacement delta {}", cameraName, displacement);
                        nozzleLocation = nozzleLocation.subtract(displacement);
                    }
                }
                else {
                    // The image may have been taken without the nozzle being exactly on the shot
                    // location, see preparePipeline(). Compensate what is left over.
                    Location displacement = pipeline.getShotDisplacement(pipeline.getCurrentShotIndex());
                    if (displacement != null) {
                        nozzleLocation = nozzleLocation.subtract(displacement);
                        Logger.debug("Bottom vision part {} displacement (sequential) {} compensated, "
                                + "nozzle location becomes {}", part.getId(), displacement, nozzleLocation);
                    }
                }
                Logger.debug("Bottom vision part {} result rect {}", part.getId(), rect);

                // Create the offsets object. This is the physical distance from
                // the center of the camera to the located part.
                offsets = geometry.getCenterOffsets(camera);

                double angleOffset = VisionUtils.getPixelAngle(camera, rect.angle) - wantedAngle;
                // Most OpenCV Pipelines can only tell us the angle of the recognized rectangle in a   
                // wrapping-around range of 0° .. 90° as it has no notion of which rectangle side 
                // is which. We can assume that the part is never picked more than +/-45º rotated.
                // So we change the range wrapping-around to -45° .. +45°. See angleNorm():
                if (bottomVisionSettings.getMaxRotation() == MaxRotation.Adjust ) {
                    angleOffset = Utils2D.angleNorm(angleOffset);
                } else {
                    // turning more than 180° in one direction makes no sense
                    angleOffset = Utils2D.angleNorm(angleOffset, 180);
                }

                // When we rotate the nozzle later to compensate for the angle offset, the X, Y offsets 
                // will change too, as the off-center part rotates around the nozzle axis.
                // So we need to compensate for that.
                offsets = offsets.rotateXy(-angleOffset)
                        .derive(null, null, null, angleOffset);
                nozzleLocation = nozzleLocation.subtractWithRotation(offsets);

                if (++pass >= maxVisionPasses) {
                    // Maximum number of passes reached. 
                    break;
                }

                if (preCapturedShot != null) {
                    // The image has already been taken, it cannot be taken again. Any further pass
                    // would analyse the very same picture and count the offsets twice, so this is
                    // the last pass. 
                    Logger.debug("Bottom vision part {} uses a pre-captured image, single pass only", 
                            part.getId());
                    break;
                }

                // We not only check the center offset but also the corner offset brought about by the angular offset
                // so a large part will react more sensitively to angular offsets.
                Point corners[] = new Point[4];
                rect.points(corners);
                Location corner = geometry
                        .getPixelCenterOffsets(camera, corners[0].x, corners[0].y)
                        .convertToUnits(maxLinearOffset.getUnits());
                Location cornerWithAngularOffset = corner.rotateXy(angleOffset);

                if (center.getLinearDistanceTo(offsets) > getMaxLinearOffset().getValue()) {
                    Logger.debug("Offsets too large {} : center offset {} > {}", 
                            offsets, center.getLinearDistanceTo(offsets), getMaxLinearOffset().getValue()); 
                } 
                else if (corner.getLinearDistanceTo(cornerWithAngularOffset) >  getMaxLinearOffset().getValue()) {
                    Logger.debug("Offsets too large {} : corner offset {} > {}", 
                            offsets, corner.getLinearDistanceTo(cornerWithAngularOffset), getMaxLinearOffset().getValue()); 
                }
                else if (Math.abs(angleOffset) > getMaxAngularOffset()) {
                    Logger.debug("Offsets too large {} : angle offset {} > {}", 
                            offsets, Math.abs(angleOffset), getMaxAngularOffset());
                }
                else {
                    // We have a good enough fix - go on with that. 
                    break;
                }

                // Not a good enough fix - try again with corrected position.
            }
            Logger.debug("Offsets accepted {}", offsets);

            // Calculate cumulative offsets over all the passes.  
            offsets = wantedLocation.subtractWithRotation(nozzleLocation);

            // subtract visionCenterOffset
            offsets = offsets.subtract(bottomVisionSettings.getVisionOffset().rotateXy(wantedAngle));

            displayResult(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), part, offsets, camera, nozzle);
            offsetsCheck(part, nozzle, offsets);

            partSizeCheck(part, bottomVisionSettings, rect, camera);

            return new PartAlignment.PartAlignmentOffset(offsets, true);
        }
    }

    private PartAlignmentOffset findOffsetsPostRotate(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, Camera camera, BottomVisionSettings bottomVisionSettings)
                    throws Exception {
        return findOffsetsPostRotate(part, boardLocation, placement, nozzle, camera, bottomVisionSettings, false, null);
    }

    private PartAlignmentOffset findOffsetsPostRotate(Part part, BoardLocation boardLocation,
            Placement placement, Nozzle nozzle, Camera camera, BottomVisionSettings bottomVisionSettings,
            boolean skipMovement, CapturedShot preCapturedShot) throws Exception {
        // Create a location that is the Camera's X, Y, it's Z + part height
        // and a rotation of 0, unless preRotate is enabled
        Location wantedLocation = getCameraLocationAtPartHeight(part, camera, nozzle, 0., skipMovement);

        try (CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings)) {
            PipelineGeometry geometry = processPipelineAndGetResult(pipeline, camera, part, nozzle, wantedLocation, wantedLocation, bottomVisionSettings, skipMovement, preCapturedShot);
            RotatedRect rect = geometry.rect;

            Logger.debug("Bottom vision part {} result rect {}", part.getId(), rect);

            // Create the offsets object. This is the physical distance from
            // the center of the camera to the located part.
            Location offsets = geometry.getCenterOffsets(camera);

            double angleOffset = VisionUtils.getPixelAngle(camera, rect.angle);
            // Most OpenCV Pipelines can only tell us the angle of the recognized rectangle in a   
            // wrapping-around range of 0° .. 90° as it has no notion of which rectangle side 
            // is which. We can assume that the part is never picked more than +/-45º rotated.
            // So we change the range wrapping-around to -45° .. +45°. See angleNorm():
            if (bottomVisionSettings.getMaxRotation() == MaxRotation.Adjust ) {
                angleOffset = Utils2D.angleNorm(angleOffset);
            } else {
                // turning more than 180° in one direction makes no sense
                angleOffset = Utils2D.angleNorm(angleOffset, 180);
            }

            partSizeCheck(part, bottomVisionSettings, rect, camera);

            // Set the angle on the offsets.
            offsets = offsets.derive(null, null, null, angleOffset);

            // subtract visionCenterOffset
            offsets = offsets.subtract(bottomVisionSettings.getVisionOffset().rotateXy(offsets.getRotation()));

            displayResult(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), part, offsets, camera, nozzle);
            offsetsCheck(part, nozzle, offsets);

            return new PartAlignmentOffset(offsets, false);
        }
    }

    protected void offsetsCheck(Part part, Nozzle nozzle, Location offsets) throws Exception {
        if (nozzle.getNozzleTip() != null) {
            NozzleTip nt = nozzle.getNozzleTip();
            Length offsetsLength = offsets.getLinearLengthTo(Location.origin);
            Length maxPickTolerance = nt.getMaxPickTolerance();
            if (offsetsLength.compareTo(maxPickTolerance) > 0) {
                LengthConverter lengthConverter = new LengthConverter(); 
                throw new Exception("Part "+part.getId()+" bottom vision offsets length "+lengthConverter.convertForward(offsetsLength)
                +" larger than the allowed Max. Pick Tolerance "+lengthConverter.convertForward(maxPickTolerance)+" set on nozzle tip "
                + nt.getName()+".");
            }
        }
    }

    protected static boolean partSizeCheck(Part part, BottomVisionSettings bottomVisionSettings, RotatedRect partRect, Camera camera) throws Exception {
        // Check if this test needs to be done
        Location partSize = bottomVisionSettings.getPartCheckSize(part, false);
        if (partSize == null) {
            return true;
        }

        double pxWidth = VisionUtils.toPixels(partSize.getLengthX(), camera);
        double pxLength = VisionUtils.toPixels(partSize.getLengthY(), camera);

        Size measuredSize = partRect.size;
        if (! (measuredSize.width>0 && measuredSize.height>0) ) {
            // This branch handles the case for zero size, negative size, and NaN size
            throw new Exception(String.format("Invalid measured size for size check.\nwidth=%s\nlength=%s",measuredSize.width,measuredSize.height));
        }

        double widthTolerance = pxWidth * 0.01 * (double) bottomVisionSettings.getCheckSizeTolerancePercent();
        double heightTolerance = pxLength * 0.01 * (double) bottomVisionSettings.getCheckSizeTolerancePercent();
        double pxMaxWidth = pxWidth + widthTolerance;
        double pxMinWidth = pxWidth - widthTolerance;
        double pxMaxLength = pxLength + heightTolerance;
        double pxMinLength = pxLength - heightTolerance;
        boolean ret;
        Location upp = camera.getUnitsPerPixelAtZ();
        LengthConverter lengthConverter = new LengthConverter();
        String measuredWidth = lengthConverter.convertForward(upp.getLengthX().multiply(measuredSize.width));
        String measuredLength = lengthConverter.convertForward(upp.getLengthY().multiply(measuredSize.height));
        String nominalWidth = lengthConverter.convertForward(partSize.getLengthX());
        String nominalLength = lengthConverter.convertForward(partSize.getLengthY());
        String msg;
        if (measuredSize.width > pxMaxWidth) {
            msg = String.format("Part %s width too large: nominal %s, limit %s, measured %s", part.getId(), 
                    nominalWidth, lengthConverter.convertForward(upp.getLengthX().multiply(pxMaxWidth)), 
                    measuredWidth);
            ret = false;
        } else if (measuredSize.width < pxMinWidth) {
            msg = String.format("Part %s width too small: nominal %s, limit %s, measured %s", part.getId(), 
                    nominalWidth, lengthConverter.convertForward(upp.getLengthX().multiply(pxMinWidth)), 
                    measuredWidth);
            ret = false;
        } else if (measuredSize.height > pxMaxLength) {
            msg = String.format("Part %s length too large: nominal %s, limit %s, measured %s", part.getId(), 
                    nominalLength, lengthConverter.convertForward(upp.getLengthY().multiply(pxMaxLength)),
                    measuredLength);
            ret = false;
        } else if (measuredSize.height < pxMinLength) {
            msg = String.format("Part %s length too small: nominal %s, limit %s, measured %s", part.getId(), 
                    nominalLength, lengthConverter.convertForward(upp.getLengthY().multiply(pxMinLength)),
                    measuredLength);
            ret = false;
        }
        else {
            msg = String.format("Part %s size ok. Width %s, Length %s", part.getId(), 
                    measuredWidth, measuredLength);
            ret = true;
        }
        Logger.debug(msg);
        if (! ret) {
            throw new Exception(msg);
        }
        return true;
    }

    /**
     * The same rectangle reported with its two sides exchanged.
     *
     * A rectangle fitted to a contour has no notion of which of its sides is the part's width:
     * 17x37 at 0° and 37x17 at 90° are one and the same rectangle, and OpenCV hands out either of
     * them, depending on where the contour it was fitted to happens to start.
     */
    public static RotatedRect withSidesExchanged(RotatedRect rect) {
        return new RotatedRect(new Point(rect.center.x, rect.center.y), 
                new Size(rect.size.height, rect.size.width), rect.angle);
    }

    /**
     * The answer of {@link #checkPartSizeEitherWay}: whether the rectangle is the part at all, and if
     * it is, whether it had to be read the other way round to say so.
     *
     * Public, and so are its methods, so that a test can look at the answer without having to go
     * through a camera and a pipeline to get one.
     */
    public static class SizeFit {
        private final boolean fits;
        private final boolean turned;
        private final String message;

        private SizeFit(boolean fits, boolean turned, String message) {
            this.fits = fits;
            this.turned = turned;
            this.message = message;
        }

        static SizeFit fitsAsReported() {
            return new SizeFit(true, false, null);
        }

        static SizeFit fitsTurned(String reasonItDidNotFitAsReported) {
            return new SizeFit(true, true, reasonItDidNotFitAsReported);
        }

        static SizeFit doesNotFit(String reason) {
            return new SizeFit(false, false, reason);
        }

        public boolean fits() {
            return fits;
        }

        public boolean isTurned() {
            return turned;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Does this rectangle show a part of the size the package describes, in either of the two ways
     * that size can be laid down on a rectangle?
     *
     * The alignment proper compares width with width and length with length, which is a coin toss on
     * a part that is a quarter turn off: on the machine the two sides of a turned 0402 came out as
     * 37x17 on the frame the alignment measured and as 17x37 on the frame the check measured, the two
     * frames 26 ms apart, and the alignment was the one that happened to land well. A check that is
     * only allowed to speak when it is sure must not inherit a coin toss; and it must not be the one
     * that cries wolf either, because a warning that a legitimately turned part provokes is a warning
     * that will be read as noise the next time it matters. So the pre-check asks both ways.
     *
     * What it found in the second way is worth keeping rather than swallowing: sides exchanged is a
     * part lying a quarter turn from the orientation its footprint was written down in, which is the
     * one thing this frame can say about the rotation of a part it is not allowed to judge.
     */
    public static SizeFit checkPartSizeEitherWay(Part part, BottomVisionSettings bottomVisionSettings,
            RotatedRect rect, Camera camera) {
        try {
            partSizeCheck(part, bottomVisionSettings, rect, camera);
            return SizeFit.fitsAsReported();
        }
        catch (Exception asReported) {
            try {
                partSizeCheck(part, bottomVisionSettings, withSidesExchanged(rect), camera);
                return SizeFit.fitsTurned(asReported.getMessage());
            }
            catch (Exception neitherWay) {
                return SizeFit.doesNotFit(asReported.getMessage());
            }
        }
    }

    @Override
    public void displayResult(BufferedImage image, Part part, Location offsets, Camera camera, Nozzle nozzle) {
        String s = part.getId();
        if (offsets != null) {
            LengthConverter lengthConverter = new LengthConverter();
            DoubleConverter doubleConverter = new DoubleConverter(Configuration.get().getLengthDisplayFormat());
            s += "  |  X:"+lengthConverter.convertForward(offsets.getLengthX())+" "
                    +"Y:"+lengthConverter.convertForward(offsets.getLengthY())+" "
                    +"C:"+doubleConverter.convertForward(offsets.getRotation())
                    +" Δ:"+lengthConverter.convertForward(offsets.getLinearLengthTo(Location.origin));
        }
        Logger.debug("Alignment result: {}", s);
        MainFrame mainFrame = MainFrame.get();
        if (mainFrame != null) {
            try {
                mainFrame
                .getCameraViews()
                .getCameraView(camera)
                .showFilteredImage(image, s,
                        2000);
                // Also make sure the right nozzle is selected for correct cross-hair rotation.
                MovableUtils.fireTargetedUserAction(nozzle);
            }
            catch (Exception e) {
                // Throw away, just means we're running outside of the UI.
            }
        }
    }

    public void preparePipeline(CvPipeline pipeline, Map<String, Object> pipelineParameterAssignments, 
            Camera camera, Package pkg, Nozzle nozzle, NozzleTip nozzleTip, Location wantedLocation, 
            Location adjustedNozzleLocation, BottomVisionSettings bottomVisionSettings) throws Exception {
        preparePipeline(pipeline, pipelineParameterAssignments, camera, pkg, nozzle, nozzleTip, 
                wantedLocation, adjustedNozzleLocation, bottomVisionSettings, false);
    }

    protected void preparePipeline(CvPipeline pipeline, Map<String, Object> pipelineParameterAssignments, 
            Camera camera, Package pkg, Nozzle nozzle, NozzleTip nozzleTip, Location wantedLocation, 
            Location adjustedNozzleLocation, BottomVisionSettings bottomVisionSettings, 
            boolean skipMovement) throws Exception {
        VisionCompositing visionCompositing = pkg.getVisionCompositing();
        VisionCompositing.Composite composite = visionCompositing.new Composite(
                pkg, bottomVisionSettings, nozzle, nozzleTip, camera, wantedLocation);
        if (visionCompositing.getCompositingMethod().isEnforced() 
                && composite.getCompositingSolution().isInvalid()) {
            throw new Exception("Vision Compositing has not found a valid solution for package "+pkg.getId()+". "
                    + "Status: "+composite.getCompositingSolution()+", "+composite.getDiagnostics()+". "
                    + "For more diagnostic information go to the Vision Compositing tab on package "+pkg.getId()+". ");
        }
        pipeline.resetReusedPipeline();
        // Taking the picture from where the nozzle already is, instead of moving it onto the shot
        // location, saves the settling of a tiny move. Only possible when displacement is enabled
        // and when the part is photographed in a single shot: a composited part is assembled from
        // its shots in machine coordinates, so shots taken from different places than the ones the
        // compositing computed do not add up. Only in pre-rotate: the post-rotate path returns the
        // offsets relative to the camera centre and never sees a nozzle location to apply the
        // difference to, see displacementCheck().
        final boolean displacementPossible = !skipMovement
                && nozzle != null
                && isPreRotate()
                && composite.getShotsTravel().size() == 1
                && Configuration.get().getMachine().isDisplacementEnabled();
        for (Shot shot : composite.getShotsTravel()) {
            Location upp = camera.getUnitsPerPixelAtZ();
            pipeline.setProperty("camera", camera);
            Length samplingSize = new Length(0.1, LengthUnit.Millimeters); // Default, if no setting on nozzle tip. 
            // Set the footprint.
            pipeline.setProperty("footprint", composite.getFootprint());
            pipeline.setProperty("footprint.rotation", wantedLocation.getRotation());
            pipeline.setProperty("footprint.xOffset", new Length(shot.getX(), composite.getUnits()));
            pipeline.setProperty("footprint.yOffset", new Length(shot.getY(), composite.getUnits()));
            // Crop to fit into the mask. 
            // TODO: make the template circular. 
            Length maxDim = new Length(Math.sqrt(2)*shot.getMaxMaskRadius(), composite.getUnits())
                    .subtract(nozzleTip.getMaxPickTolerance().multiply(1.2));
            pipeline.setProperty("footprint.maxWidth", maxDim);
            pipeline.setProperty("footprint.maxHeight", maxDim);
            // Set alignment parameters.
            pipeline.setProperty("MinAreaRect.center", wantedLocation);
            pipeline.setProperty("MinAreaRect.expectedAngle", wantedLocation.getRotation());
            pipeline.setProperty("DetectRectlinearSymmetry.center", wantedLocation);
            pipeline.setProperty("DetectRectlinearSymmetry.expectedAngle", wantedLocation.getRotation());
            // Set the background removal properties.
            pipeline.setProperty("DetectRectlinearSymmetry.searchDistance", nozzleTip.getMaxPickTolerance()
                    .multiply(1.2)); // Allow for some tolerance, we will check the result later.
            // mask the corner
            pipeline.setProperty("MaskCircle.diameter", new Length(shot.getMaxMaskRadius()*2, composite.getUnits()));
            if (nozzleTip instanceof ReferenceNozzleTip) {
                ReferenceNozzleTipCalibration calibration = ((ReferenceNozzleTip) nozzleTip).getCalibration();
                if (calibration != null 
                        && calibration.getBackgroundCalibrationMethod() != BackgroundCalibrationMethod.None) {
                    samplingSize = calibration.getMinimumDetailSize().multiply(0.5);
                    pipeline.setProperty("MaskHsv.hueMin", 
                            Math.max(0, calibration.getBackgroundMinHue() - calibration.getBackgroundTolHue()));
                    pipeline.setProperty("MaskHsv.hueMax", 
                            Math.min(255, calibration.getBackgroundMaxHue() + calibration.getBackgroundTolHue()));
                    pipeline.setProperty("MaskHsv.saturationMin", 
                            Math.max(0, calibration.getBackgroundMinSaturation() - calibration.getBackgroundTolSaturation()));
                    pipeline.setProperty("MaskHsv.saturationMax", 255);  
                    // no need to restrict to this: Math.min(255, calibration.getBackgroundMaxSaturation() + calibration.getBackgroundTolSaturation()));
                    pipeline.setProperty("MaskHsv.valueMin", 0); 
                    // no need to restrict to this: Math.max(0, calibration.getBackgroundMinValue() - calibration.getBackgroundTolValue()));
                    pipeline.setProperty("MaskHsv.valueMax", 
                            Math.min(255, calibration.getBackgroundMaxValue() +  calibration.getBackgroundTolValue()));
                }
            }
            if (samplingSize.compareTo(upp.getLengthX().multiply(2)) < 0) {
                // We want the sampling size to at least be 2 pixels, otherwise subSampling will be too costly. 
                // This means: a camera with less than 4 pixels per smallest contact size, is likely to cause problems
                // but that's to be expected anyways.
                samplingSize = upp.getLengthX().multiply(2);
            }
            pipeline.setProperty("BlurGaussian.kernelSize", samplingSize);
            pipeline.setProperty("DetectRectlinearSymmetry.subSampling", samplingSize);
            // Add a margin for edge detection.
            pipeline.setProperty("DetectRectlinearSymmetry.maxWidth",
                    new Length(shot.getWidth(), composite.getUnits())
                    .add(samplingSize.multiply(2)));
            pipeline.setProperty("DetectRectlinearSymmetry.maxHeight",
                    new Length(shot.getHeight(), composite.getUnits())
                    .add(samplingSize.multiply(2)));

            if (composite.getCompositingSolution().isAdvanced()) {
                // mask the whole part
                pipeline.setProperty("partmask.diameter", new Length(composite.getMaxPadRadius(), composite.getUnits())
                        .add(nozzleTip.getMaxPickTolerance()).multiply(2));
                pipeline.setProperty("partmask.center", camera.getLocation()
                        .subtract(new Location(composite.getUnits(), shot.getX(), shot.getY(), 0., 0.)
                                .rotateXy(wantedLocation.getRotation())));
                pipeline.setProperty("MinAreaRect.leftEdge", shot.hasLeftEdge());
                pipeline.setProperty("MinAreaRect.rightEdge", shot.hasRightEdge());
                pipeline.setProperty("MinAreaRect.topEdge", shot.hasTopEdge());
                pipeline.setProperty("MinAreaRect.bottomEdge", shot.hasBottomEdge());
                pipeline.setProperty("MinAreaRect.searchAngle", Math.toDegrees(Math.atan2(composite.getTolerance(), composite.getMaxCornerRadius())));
            }
            pipeline.addProperties(pipelineParameterAssignments);

            // Get the shot location, but adjusted by the adjustedNozzleLocation.
            Location shotLocationTemp = composite.getShotLocation(shot)
                    .addWithRotation(adjustedNozzleLocation.subtractWithRotation(wantedLocation));
            if (nozzle != null && Configuration.get().getMachine().isDisplacementEnabled()) {
                // Adjust 'shot Z' to nozzle balance level. Note the Length variant, the plain
                // double value of the balance level would be interpreted in the units of the shot
                // location.
                shotLocationTemp = shotLocationTemp.deriveLengths(null, null,
                        Configuration.get().getMachine().getBalanceLevel(), null);
            }
            final Location shotLocation = shotLocationTemp;
            final boolean skipMove = skipMovement;

            pipeline.new PipelineShot() {
                @Override
                public void apply() throws Exception {
                    if (skipMove) {
                        super.apply();
                        return;
                    }
                    Location displacement = null;
                    if (nozzle.getLocation().getLinearLengthTo(camera.getLocation(nozzle))
                            .compareTo(camera.getRoamingRadius()
                                    .add(nozzleTip.getMaxPickTolerance())) > 0) {
                        // Nozzle is not yet in camera roaming radius. Move at safe Z.
                        MovableUtils.moveToLocationAtSafeZ(nozzle, shotLocation);
                    }
                    else {
                        Machine machine = Configuration.get().getMachine();
                        Location currentLocation = nozzle.getLocation();
                        Length distance = currentLocation.getLinearLengthTo(shotLocation);
                        Length zDifference = currentLocation.getLengthZ()
                                .subtract(shotLocation.getLengthZ()).abs();
                        double rotationDifference = Math.abs(Math.IEEEremainder(
                                currentLocation.getRotation() - shotLocation.getRotation(), 360.));
                        if (displacementPossible
                                && distance.compareTo(machine.getDisplacementMaxDistance()) < 0
                                && zDifference.compareTo(machine.getDisplacementMaxZDiff()) < 0
                                && rotationDifference < machine.getDisplacementMaxRotation()) {
                            // The nozzle is so close to the shot location that the picture can be
                            // taken from where it is. Remember the difference, the image capture
                            // stage skips the settling of a move that is not made and the analysis
                            // compensates what is left.
                            displacement = shotLocation.deriveLengths(
                                    shotLocation.getLengthX().subtract(currentLocation.getLengthX()),
                                    shotLocation.getLengthY().subtract(currentLocation.getLengthY()),
                                    null, null);
                            Logger.debug("Bottom vision on nozzle {} camera {}: nozzle not moved to "
                                            + "the shot location {}, it is only {} away (Z {}, "
                                            + "rotated {} degrees), compensating {} in the analysis.",
                                    nozzle.getId(), camera.getName(), shotLocation, distance,
                                    zDifference, rotationDifference, displacement);
                        }
                        else {
                            // When skipMove=false and the difference is too big to be compensated
                            // by vision, the nozzle must be moved to the exact shot location.
                            nozzle.moveTo(shotLocation);
                        }
                    }
                    super.apply();
                    // Only now, because the shot index of this shot is not known before it has been
                    // applied.
                    pipeline.setShotDisplacement(pipeline.getCurrentShotIndex(), displacement);
                }

                @Override 
                public void processResult(Result result) {
                    composite.accumulateShotDetection(shot, (RotatedRect) result.model);
                }

                @Override 
                public Result processCompositeResult() throws Exception {
                    composite.interpret();
                    return new Result(null, composite.getDetectedRotatedRect());
                }
            };
        }
    }

    public static CvPipeline getThreadLocalPipeline(BottomVisionSettings bottomVisionSettings) throws CloneNotSupportedException {
        // Every thread gets a pipeline of its own, the one of the vision settings is shared. It is
        // cloned without resetting the parameter stages: those are applied again by preparePipeline()
        // below, and the pipeline of the vision settings has no camera of its own to reset them with.
        return bottomVisionSettings.getPipeline().cloneWithoutReset();
    }

    /**
     * The geometry a bottom vision pipeline has detected, together with the size of the image it was
     * detected in.
     *
     * The two belong together and must never be mixed up with something else: a
     * {@link org.openpnp.vision.pipeline.stages.CropCentered} stage makes the pipeline analyse a
     * centred part of the captured frame, and then the pixels of the detection are counted from the
     * corner of that crop, while the Camera still reports the size of the whole frame. Interpret one
     * against the center of the other and a part found dead center is reported half an image away -
     * which is exactly the 15 mm of nonsense this produced on the machine on 2026-09-28.
     */
    private static class PipelineGeometry {
        final RotatedRect rect;
        final double imageWidth;
        final double imageHeight;

        PipelineGeometry(RotatedRect rect, Result result) {
            this.rect = rect;
            this.imageWidth = (result == null || result.image == null ? 0 : result.image.cols());
            this.imageHeight = (result == null || result.image == null ? 0 : result.image.rows());
        }

        /**
         * The physical distance from the center of the image the part was found in to that part.
         */
        Location getCenterOffsets(Camera camera) {
            return getPixelCenterOffsets(camera, rect.center.x, rect.center.y);
        }

        Location getPixelCenterOffsets(Camera camera, double x, double y) {
            return VisionUtils.getPixelCenterOffsets(camera,
                    imageWidth > 0 ? imageWidth : camera.getWidth(),
                    imageHeight > 0 ? imageHeight : camera.getHeight(),
                    x, y);
        }
    }

    private PipelineGeometry processPipelineAndGetResult(CvPipeline pipeline, Camera camera,
            Part part, Nozzle nozzle, Location wantedLocation, Location adjustedNozzleLocation, BottomVisionSettings bottomVisionSettings) throws Exception {
        return processPipelineAndGetResult(pipeline, camera, part, nozzle, wantedLocation, adjustedNozzleLocation, bottomVisionSettings, false);
    }

    private PipelineGeometry processPipelineAndGetResult(CvPipeline pipeline, Camera camera,
            Part part, Nozzle nozzle, Location wantedLocation, Location adjustedNozzleLocation, BottomVisionSettings bottomVisionSettings,
            boolean skipMovement) throws Exception {
        return processPipelineAndGetResult(pipeline, camera, part, nozzle, wantedLocation, adjustedNozzleLocation, bottomVisionSettings, skipMovement, null);
    }

    private PipelineGeometry processPipelineAndGetResult(CvPipeline pipeline, Camera camera,
            Part part, Nozzle nozzle, Location wantedLocation, Location adjustedNozzleLocation, BottomVisionSettings bottomVisionSettings,
            boolean skipMovement, CapturedShot preCapturedShot) throws Exception {
        long prepareStartNs = System.nanoTime();
        preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(), camera, part.getPackage(), 
                nozzle, nozzle.getNozzleTip(), wantedLocation, adjustedNozzleLocation, bottomVisionSettings, skipMovement);
        long prepareNs = System.nanoTime() - prepareStartNs;
        // Set pre-captured shot AFTER preparePipeline (which calls resetReusedPipeline and clears pre-captured shots)
        if (preCapturedShot != null) {
            pipeline.setPreCapturedShot(0, preCapturedShot);
        }
        // The image the geometry was measured in, i.e. the image the offsets have to be referred to.
        Result geometryResult = null;
        for (PipelineShot pipelineShot : pipeline.getPipelineShots()) {
            pipelineShot.apply();

            long processStartNs = System.nanoTime();
            pipeline.process();
            long processNs = System.nanoTime() - processStartNs;
            logAlignTimings(pipeline, part, camera, prepareNs, processNs);
            // Only the first shot pays for the preparation of the pipeline.
            prepareNs = 0;
            Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);

            // Fall back to the old name of "result" instead of "results" for backwards
            // compatibility.
            if (result == null) {
                result = pipeline.getResult("result");
            }

            if (result == null) {
                throw new Exception(String.format(
                        "ReferenceBottomVision (%s): Pipeline error. Pipeline must contain a result named '%s'.",
                        part.getId(), VisionUtils.PIPELINE_RESULTS_NAME));
            }

            if (result.model == null) {
                throw new Exception(String.format(
                        "ReferenceBottomVision (%s): No result found.",
                        part.getId()));
            }

            if (!(result.model instanceof RotatedRect)) {
                throw new Exception(String.format(
                        "ReferenceBottomVision (%s): Incorrect pipeline result type (%s). Expected RotatedRect.",
                        part.getId(), result.model.getClass().getSimpleName()));
            }
            pipelineShot.processResult(result);
            geometryResult = result;
            // Display the shot result.   
            displayResult(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), part, null, camera, nozzle);
        }
        
        RotatedRect resultRect = (RotatedRect) pipeline.getCurrentPipelineShot().processCompositeResult().getModel();
        
        // A pre-captured image was taken without moving the nozzle onto the shot location, so the
        // remaining displacement must stay within the limits that can be compensated by vision.
        if (preCapturedShot instanceof PreCapturedPlacement) {
            displacementCheck((PreCapturedPlacement) preCapturedShot);
        }
        
        return new PipelineGeometry(resultRect, geometryResult);
    }

    /**
     * Log how long the vision analysis really took, stage by stage. Whether the analysis can be
     * overlapped with the move to placement is decided by this cost, so it has to be measured on the
     * machine instead of guessed. Gated on debug logging to keep production logs as they are.
     */
    protected void logAlignTimings(CvPipeline pipeline, Part part, Camera camera, long prepareNs,
            long processNs) {
        if(!LogUtils.isDebugEnabled()) {
            return;
        }
        StringBuilder stages = new StringBuilder();
        for(CvStage stage : pipeline.getStages()) {
            if(!stage.isEnabled()) {
                continue;
            }
            Result stageResult = pipeline.getResult(stage);
            if(stages.length() > 0) {
                stages.append(", ");
            }
            stages.append(stage.getName())
                    .append(String.format(Locale.US, "=%.1f", (stageResult == null ? 0 : stageResult.processingTimeNs)/1e6));
        }
        Logger.debug("[ALIGN-TIMING] part {} camera {} prepare {} ms process {} ms stages [{}]",
                part.getId(), camera.getName(),
                String.format(Locale.US, "%.1f", prepareNs/1e6),
                String.format(Locale.US, "%.1f", processNs/1e6),
                stages);
    }

    /**
     * Check that a pre-captured image was taken close enough to the wanted shot location that the
     * remaining displacement can still be compensated by the vision analysis instead of moving the
     * nozzle.
     *
     * @param preCapturedPlacement the captured shot including the intended displacement delta.
     */
    protected void displacementCheck(PreCapturedPlacement preCapturedPlacement) {
        Location displacement = preCapturedPlacement.getIntendedDisplacementDelta();
        if (displacement == null) {
            return;
        }
        Machine machine = Configuration.get().getMachine();
        Length maxDistance = machine.getDisplacementMaxDistance();
        Length maxZDiff = machine.getDisplacementMaxZDiff();
        double maxRotation = machine.getDisplacementMaxRotation();

        Length distance = displacement.getLinearLengthTo(Location.origin);
        Length zDiff = displacement.getLengthZ().abs();
        double rotation = Math.abs(Math.IEEEremainder(displacement.getRotation(), 360.));

        if (distance.compareTo(maxDistance) > 0
                || zDiff.compareTo(maxZDiff) > 0
                || rotation > maxRotation) {
            Logger.warn("Bottom vision displacement of {} (Z {}, rotated {} degrees) exceeds the tolerated "
                    + "{} (Z {}, max {} degrees) on nozzle {} camera {}. Check the Advanced Camera "
                    + "Calibration and the Nozzle Tip Runout Calibration.",
                    distance, zDiff, rotation, maxDistance, maxZDiff, maxRotation,
                    preCapturedPlacement.getNozzle() == null ? "?" 
                            : preCapturedPlacement.getNozzle().getId(),
                    preCapturedPlacement.getCamera() == null ? "?" 
                            : preCapturedPlacement.getCamera().getName());
        }

        if (distance.compareTo(new Length(0, distance.getUnits())) != 0 && !isPreRotate()) {
            // Only the pre-rotate path applies the displacement delta to the nozzle location, so a
            // displaced shot would be evaluated as if the nozzle had been exactly on the shot
            // location.
            Logger.warn("Bottom vision of nozzle {} uses post-rotate but the pre-captured image on "
                    + "camera {} is displaced by {}. The displacement is not compensated for, "
                    + "enable Pre-Rotate or check the camera placement.",
                    preCapturedPlacement.getNozzle() == null ? "?" 
                            : preCapturedPlacement.getNozzle().getId(),
                    preCapturedPlacement.getCamera() == null ? "?" 
                            : preCapturedPlacement.getCamera().getName(),
                    distance);
        }
    }

    @Override
    public boolean canHandle(PartSettingsHolder settingsHolder, boolean allowDisabled) {
        BottomVisionSettings visionSettings = getInheritedVisionSettings(settingsHolder);
        if (visionSettings != null) {
            boolean isEnabled = (enabled && visionSettings.isEnabled());
            if (!allowDisabled) {
                Logger.trace("{}.canHandle({}) => {}, {}", this.getClass().getSimpleName(), 
                        settingsHolder == null ? "" : settingsHolder.getId(), visionSettings, isEnabled ? "enabled" : "disabled");
            }
            return allowDisabled || isEnabled;
        }
        return false;
    }

    private BottomVisionSettings createBottomVisionSettings(String id, String name, CvPipeline pipeline) {
        BottomVisionSettings bottomVisionSettings;
        try {
            bottomVisionSettings = new BottomVisionSettings(id);
            bottomVisionSettings.setName(name);
            bottomVisionSettings.setEnabled(true);
            bottomVisionSettings.setPipeline(pipeline);
            return bottomVisionSettings;
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    @Override
    public String getId() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public void setName(String name) {
    }

    @Override
    public String getShortName() {
        return getPropertySheetHolderTitle();
    }

    @Override
    public void setBottomVisionSettings(BottomVisionSettings visionSettings) {
        if (visionSettings == null) {
            return; // do not allow null
        }
        super.setBottomVisionSettings(visionSettings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPreRotate() {
        return preRotate;
    }

    public void setPreRotate(boolean preRotate) {
        this.preRotate = preRotate;
    }

    public int getMaxVisionPasses() {
        return maxVisionPasses;
    }

    public void setMaxVisionPasses(int maxVisionPasses) {
        this.maxVisionPasses = maxVisionPasses;
    }

    public Length getMaxLinearOffset() {
        return maxLinearOffset;
    }

    public void setMaxLinearOffset(Length maxLinearOffset) {
        this.maxLinearOffset = maxLinearOffset;
    }

    public double getMaxAngularOffset() {
        return maxAngularOffset;
    }

    public void setMaxAngularOffset(double maxAngularOffset) {
        this.maxAngularOffset = maxAngularOffset;
    }

    public double getTestAlignmentAngle() {
        return testAlignmentAngle;
    }

    public void setTestAlignmentAngle(double testAlignmentAngle) {
        Object oldValue = this.testAlignmentAngle; 
        this.testAlignmentAngle = testAlignmentAngle;
        firePropertyChange("testAlignmentAngle", oldValue, testAlignmentAngle);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return "Bottom Vision";
    }

    public static CvPipeline createStockPipeline(String variant) {
        try {
            String xml = IOUtils.toString(ReferenceBottomVision.class
                    .getResource("ReferenceBottomVision-"+variant+"Pipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {
                new PropertySheetWizardAdapter(new ReferenceBottomVisionConfigurationWizard(this)),
                new PropertySheetWizardAdapter(new BottomVisionSettingsConfigurationWizard(getBottomVisionSettings(), this))};
    }

    public enum PreRotateUsage {
        Default, AlwaysOn, AlwaysOff
    }

    public enum PartSizeCheckMethod {
        Disabled, BodySize, PadExtents
    }

    public enum MaxRotation {
        Adjust, Full
    }

    /**
     * What a failed pre-check does about it. A pre-check never makes the job faster by being
     * ignored and never makes it slower by being waited for, so the only thing it may do on its own
     * is to say something.
     *
     * {@link #Report} runs the check and writes the log line, and changes nothing else - not even
     * when the verdict is no. That is the mode in which the numbers of section 7 of the design doc
     * can be collected without the machine doing anything it would not have done anyway.
     */
    public enum PreCheckAction {
        Disabled, Report, Sequential, Error
    }

    @Deprecated
    @Root
    public static class PartSettings extends AbstractModelObject {

        @Deprecated
        @Attribute
        protected boolean enabled = true;
        @Deprecated
        @Attribute(required = false)
        protected PreRotateUsage preRotateUsage = PreRotateUsage.Default;

        @Deprecated
        @Attribute(required = false)
        protected PartSizeCheckMethod checkPartSizeMethod = PartSizeCheckMethod.Disabled;

        @Deprecated
        @Attribute(required = false)
        protected int checkSizeTolerancePercent = 20;

        @Deprecated
        @Attribute(required = false)
        protected MaxRotation maxRotation = MaxRotation.Adjust;

        @Deprecated
        @Element(required = false)
        protected Location visionOffset = new Location(LengthUnit.Millimeters);

        @Deprecated
        @Element
        protected CvPipeline pipeline;

        @Deprecated
        public PartSettings() {
        }

        @Deprecated
        public boolean isEnabled() {
            return enabled;
        }

        @Deprecated
        public PreRotateUsage getPreRotateUsage() {
            return preRotateUsage;
        }

        @Deprecated
        public CvPipeline getPipeline() {
            return pipeline;
        }

        @Deprecated
        public void setPipeline(CvPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Deprecated
        public MaxRotation getMaxRotation() {
            return maxRotation;
        }

        public PartSizeCheckMethod getCheckPartSizeMethod() {
            return checkPartSizeMethod;
        }

        public int getCheckSizeTolerancePercent() {
            return checkSizeTolerancePercent;
        }

        public Location getVisionOffset() {
            return visionOffset;
        }
    }

    protected void migratePartSettings(Configuration configuration) {
        if (partSettingsByPartId == null) {
            AbstractVisionSettings stockVisionSettings = configuration.getVisionSettings(AbstractVisionSettings.STOCK_BOTTOM_ID);
            if (stockVisionSettings == null) {
                // Fresh configuration: need to migrate the stock and default settings, even if no partSettingsById are present.  
                partSettingsByPartId = new HashMap<>();
            }
            else { 
                // Reassign the stock pipeline.
                stockVisionSettings.setPipeline(createStockPipeline("Default"));
                // Create other stock pipelines.
                createStockVisionSettings(configuration, AbstractVisionSettings.STOCK_BOTTOM_RECTLINEAR_ID, "Rectlinear", "- Rectlinear Symmetry Bottom Vision Settings -");
                createStockVisionSettings(configuration, AbstractVisionSettings.STOCK_BOTTOM_BODY_ID, "Body", "- Whole Part Body Bottom Vision Settings -");
                return;
            }
        }

        HashMap<String, BottomVisionSettings> bottomVisionSettingsHashMap = new HashMap<>();
        // Create the factory stock settings.
        BottomVisionSettings stockBottomVisionSettings = createStockBottomVisionSettings();
        configuration.addVisionSettings(stockBottomVisionSettings);
        BottomVisionSettings rectlinearBottomVisionSettings = createRectlinearBottomVisionSettings();
        configuration.addVisionSettings(rectlinearBottomVisionSettings);
        PartSettings equivalentPartSettings = new PartSettings();
        equivalentPartSettings.setPipeline(stockBottomVisionSettings.getPipeline());
        bottomVisionSettingsHashMap.put(AbstractVisionSettings.createSettingsFingerprint(equivalentPartSettings), stockBottomVisionSettings);
        // Migrate the default settings.
        BottomVisionSettings defaultBottomVisionSettings = new BottomVisionSettings(AbstractVisionSettings.DEFAULT_BOTTOM_ID);
        defaultBottomVisionSettings.setName("- Default Machine Bottom Vision -");
        defaultBottomVisionSettings.setEnabled(enabled);
        configuration.addVisionSettings(defaultBottomVisionSettings);
        if(pipeline != null) {
            defaultBottomVisionSettings.setPipeline(pipeline);
            pipeline = null;
        }
        else {
            defaultBottomVisionSettings.setPipeline(stockBottomVisionSettings.getPipeline());
        }
        setBottomVisionSettings(defaultBottomVisionSettings);
        equivalentPartSettings.setPipeline(defaultBottomVisionSettings.getPipeline());
        bottomVisionSettingsHashMap.put(AbstractVisionSettings.createSettingsFingerprint(equivalentPartSettings), defaultBottomVisionSettings);
        for (Part part: configuration.getParts()) {
            part.setBottomVisionSettings(null);
        }
        for (org.openpnp.model.Package pkg : configuration.getPackages()) {
            pkg.setBottomVisionSettings(null);
        }
        partSettingsByPartId.forEach((partId, partSettings) -> {
            if (partSettings == null) {
                return;
            }

            try {
                Part part = configuration.getPart(partId);
                if (part != null) { 
                    String serializedHash = AbstractVisionSettings.createSettingsFingerprint(partSettings);
                    BottomVisionSettings bottomVisionSettings = bottomVisionSettingsHashMap.get(serializedHash);
                    if (bottomVisionSettings == null) {
                        bottomVisionSettings = new BottomVisionSettings(partSettings);
                        bottomVisionSettings.setName("");
                        bottomVisionSettingsHashMap.put(serializedHash, bottomVisionSettings);

                        configuration.addVisionSettings(bottomVisionSettings);
                    }

                    part.setBottomVisionSettings((bottomVisionSettings != defaultBottomVisionSettings) ? bottomVisionSettings : null);
                    Logger.info("Part "+partId+" BottomVisionSettings migrated.");
                } else {
                    Logger.warn("Part "+partId+" BottomVisionSettings with no part.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        partSettingsByPartId = null;

        optimizeVisionSettings(configuration);
    }

    public AbstractVisionSettings createStockVisionSettings(Configuration configuration, String id, String tag, String description) {
        // Add the vision settings if missing.
        AbstractVisionSettings visionSettings = configuration.getVisionSettings(id);
        if (visionSettings == null) {
            visionSettings = createBottomVisionSettings(id, description, createStockPipeline(tag));
            configuration.addVisionSettings(visionSettings);
        }
        else {
            // Reassign the stock pipeline.
            visionSettings.setPipeline(createStockPipeline(tag));
        }
        return visionSettings;
    }

    protected BottomVisionSettings createRectlinearBottomVisionSettings() {
        return createBottomVisionSettings(AbstractVisionSettings.STOCK_BOTTOM_RECTLINEAR_ID, 
                "- Rectlinear Symmetry Bottom Vision Settings -", createStockPipeline("Rectlinear"));
    }

    protected BottomVisionSettings createStockBottomVisionSettings() {
        return createBottomVisionSettings(AbstractVisionSettings.STOCK_BOTTOM_ID, 
                "- Stock Bottom Vision Settings -", createStockPipeline("Default"));
    }

    public void optimizeVisionSettings(Configuration configuration) {
        // Remove any duplicate settings.
        HashMap<String, AbstractVisionSettings> bottomVisionSettingsHashMap = new HashMap<>();
        BottomVisionSettings defaultVisionSettings = getBottomVisionSettings();
        // Make it dominant in case it is identical to stock.
        bottomVisionSettingsHashMap.put(AbstractVisionSettings.createSettingsFingerprint(defaultVisionSettings), defaultVisionSettings);
        for (AbstractVisionSettings visionSettings : configuration.getVisionSettings()) {
            if (visionSettings instanceof BottomVisionSettings) {
                String serializedHash = AbstractVisionSettings.createSettingsFingerprint(visionSettings);
                AbstractVisionSettings firstVisionSettings = bottomVisionSettingsHashMap.get(serializedHash);
                if (firstVisionSettings == null) {
                    bottomVisionSettingsHashMap.put(serializedHash, visionSettings);
                }
                else if (visionSettings != defaultVisionSettings
                        && !visionSettings.isStockSetting()) {
                    // Duplicate, remove any references.
                    for (PartSettingsHolder holder : visionSettings.getUsedBottomVisionIn()) {
                        holder.setBottomVisionSettings((BottomVisionSettings) firstVisionSettings);
                    }
                    if (visionSettings.getUsedBottomVisionIn().size() == 0) {
                        if (firstVisionSettings != defaultVisionSettings  
                                && !firstVisionSettings.isStockSetting()) {
                            firstVisionSettings.setName(firstVisionSettings.getName()+" + "+visionSettings.getName());
                        }
                        configuration.removeVisionSettings(visionSettings);
                    }
                }
            }
        }

        // Per package, search the most common settings on parts, and make them inherited package setting.
        for (org.openpnp.model.Package pkg : configuration.getPackages()) {
            HashMap<String, Integer> histogram = new HashMap<>();
            BottomVisionSettings mostFrequentVisionSettings = null;
            int highestFrequency = 0;
            BottomVisionSettings packageVisionSettings = AbstractPartAlignment.getInheritedVisionSettings(pkg, true);
            for (Part part: configuration.getParts()) {
                if (part.getPackage() == pkg) {
                    BottomVisionSettings visionSettings = AbstractPartAlignment.getInheritedVisionSettings(part, true);
                    String id = visionSettings != null ? visionSettings.getId() : "";
                    Integer frequency = histogram.get(id);
                    frequency = (frequency != null ? frequency + 1 : 1);
                    histogram.put(id, frequency);
                    if (highestFrequency < frequency) {
                        highestFrequency = frequency;
                        mostFrequentVisionSettings = visionSettings;
                    }
                }
            }
            if (mostFrequentVisionSettings != null) {
                if (mostFrequentVisionSettings == defaultVisionSettings) {
                    pkg.setBottomVisionSettings(null);
                }
                else {
                    pkg.setBottomVisionSettings(mostFrequentVisionSettings);
                }
                for (Part part: configuration.getParts()) {
                    if (part.getPackage() == pkg) {
                        if (part.getBottomVisionSettings() == mostFrequentVisionSettings) {
                            // Parts inherit from package now.
                            part.setBottomVisionSettings(null);
                        }
                        else if (part.getBottomVisionSettings() == null 
                                && packageVisionSettings != mostFrequentVisionSettings){
                            // Former package settings were inherited, now we must freeze them. 
                            part.setBottomVisionSettings(packageVisionSettings);
                        }
                    }
                }
                if (mostFrequentVisionSettings != defaultVisionSettings
                        && !mostFrequentVisionSettings.isStockSetting()
                        && !mostFrequentVisionSettings.getName().isEmpty() 
                        && mostFrequentVisionSettings.getUsedBottomVisionIn().size() == 1) {
                    // If these part settings are now unique to the package, name them so. 
                    mostFrequentVisionSettings.setName(pkg.getShortName());
                }
            }
        }

        // Set missing names by usage.
        AbstractVisionSettings.ListConverter listConverter = new AbstractVisionSettings.ListConverter(false);
        int various = 0;
        for (AbstractVisionSettings visionSettings : configuration.getVisionSettings()) {
            if (visionSettings instanceof BottomVisionSettings) {
                List<PartSettingsHolder> usedIn = visionSettings.getUsedBottomVisionIn();
                if (!visionSettings.isStockSetting()
                        && visionSettings != defaultVisionSettings
                        && usedIn.isEmpty()) {
                    configuration.removeVisionSettings(visionSettings);
                }
                else if (visionSettings.getName().isEmpty()) {
                    if (usedIn.size() <= 3) {
                        visionSettings.setName(listConverter.convertForward(usedIn));
                    }
                    else {
                        various++;
                        visionSettings.setName("Migrated "+various);
                    }
                }
            }
        }
    }
    
    /**
     * Capture placement image in main thread (before background processing).
     * All hardware interactions happen here - movement, settling, capture.
     * This is the "Pre-capture Pattern" - images are captured in main thread,
     * then background threads process pre-captured images only.
     * 
     * @param part The part being aligned
     * @param camera The camera to use for capture
     * @param nozzle The nozzle performing the capture
     * @param wantedLocation The location where capture should occur
     * @return CapturedShot containing the pre-captured image (no hardware references)
     * @throws Exception If capture fails
     */
    public CapturedShot capturePlacement(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation) throws Exception {
        BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);
        
        if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
            throw new Exception("Bottom vision not enabled for part "+part.getId());
        }
        
        // Clone pipeline for this capture
        CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
        try {
            // Prepare the pipeline with camera and other settings
            preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(), 
                    camera, part.getPackage(), nozzle, nozzle.getNozzleTip(), 
                    wantedLocation, wantedLocation, bottomVisionSettings, false);
            
            // Get the first pipeline shot (typically only one for simple capture)
            List<PipelineShot> shots = pipeline.getPipelineShots();
            if (shots.isEmpty()) {
                throw new Exception("No pipeline shots configured for part "+part.getId());
            }
            
            PipelineShot pipelineShot = shots.get(0);
            
            // Apply the shot - this moves nozzle to position if not already there
            pipelineShot.apply();
            
            // Process pipeline to get the result
            pipeline.process();
            
            // Get the result
            Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
            if (result == null) {
                result = pipeline.getResult("result");
            }
            
            if (result == null) {
                throw new Exception("Pipeline processing failed for part "+part.getId());
            }
            
            if (result.model == null) {
                throw new Exception("No result found in pipeline for part "+part.getId());
            }
            
            if (!(result.model instanceof RotatedRect)) {
                throw new Exception("Incorrect pipeline result type for part "+part.getId());
            }
            
            // Process the result (accumulate detection)
            pipelineShot.processResult(result);
            
            // Get the final composite result
            Result compositeResult = pipelineShot.processCompositeResult();
            
            if (compositeResult == null || compositeResult.model == null) {
                throw new Exception("Composite result processing failed for part "+part.getId());
            }
            
            RotatedRect rect = (RotatedRect) compositeResult.model;
            
            // Create a CapturedShot with the pre-captured image
            // The image is in the pipeline's working image after process()
            Mat preCapturedImage = pipeline.getWorkingImage().clone();
            
            // Use the wantedLocation parameter passed to this method
            Location shotLocation = wantedLocation;
            
            // Return the pre-captured shot (no hardware references). The capture itself happened
            // inside the pipeline, whose image stage settles the camera it captures from - so the
            // camera is the one that knows whether anything was demonstrated about the motion, and it
            // is asked rather than assumed. A camera that does not settle, or a shot that skipped the
            // settling, has nothing to say and the frame will be refused by the alignment.
            FrameProvenance provenance = camera instanceof AbstractSettlingCamera
                    ? settleProvenance((AbstractSettlingCamera) camera, FrameProvenance.UNKNOWN)
                    : FrameProvenance.unknown();
            FrameDump.writeIfNeeded(bottomVisionSettings, preCapturedImage, provenance, camera,
                    nozzle, part, wantedLocation.getRotation());
            return new CapturedShot(camera, nozzle, preCapturedImage, shotLocation, provenance);
        }
        finally {
            // Always close the pipeline to release resources
            pipeline.close();
        }
    }
    
    /**
     * Capture image ONLY (no pipeline processing) in main thread for parallel alignment.
     * This method captures the image without processing the pipeline, allowing true parallelism
     * when combined with background thread processing.
     * 
     * The displacement is calculated as the actual offset from the current nozzle position
     * to the target shot location. This includes both the camera's configured displacement
     * AND the nozzle tip runout error.
     * 
     * @param part The part being aligned
     * @param camera The camera to use for capture
     * @param nozzle The nozzle performing the capture
     * @param wantedLocation The location where capture should occur
     * @return PreCapturedPlacement containing the captured image and displacement delta
     * @throws Exception If capture fails
     */
    public PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation) throws Exception {
        return captureImageOnly(part, camera, nozzle, wantedLocation, false,
                FrameProvenance.UNKNOWN);
    }

    public PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip) throws Exception {
        return captureImageOnly(part, camera, nozzle, wantedLocation, isFirstNozzleTip,
                FrameProvenance.UNKNOWN);
    }

    /**
     * Capture one frame without settling, for a camera that is not the one driving the settling.
     *
     * The frame this produces is not a still frame and is never going to be one: nothing measured the
     * motion, the head had merely stopped being commanded to move. It is captured anyway because the
     * pre-check wants a frame, and it is stamped {@link FrameProvenance.Origin#QuickCapture} so that
     * the alignment can say so and refuse it. The frame this camera ought to hand to the alignment is
     * the one taken inside the still window of the settling camera, which is what
     * {@link #captureImageFromSettlingWindow} is for.
     *
     * @param arrivalAtMs when the head was reported at stillstand after moving to the cameras, so that
     *            the age of this frame can be stated; {@link FrameProvenance#UNKNOWN} if nobody
     *            recorded it.
     */
    public PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip, long arrivalAtMs)
            throws Exception {
        return capturePreparedFrame(part, camera, nozzle, wantedLocation, isFirstNozzleTip,
                (earliestFrameHandedTo) -> {
            long tCapture = NanosecondTime.getRuntimeMilliseconds();
            BufferedImage capturedBufferedImage = camera.capture();
            long capturedAtMs = NanosecondTime.getRuntimeMilliseconds();
            // Stamped as what it is: a frame nobody proved anything about. The alignment will refuse
            // it, which is the point - see the requirements.
            return new GrabbedFrame(capturedBufferedImage,
                    FrameProvenance.quickCapture(arrivalAtMs, capturedAtMs),
                    capturedAtMs - tCapture);
        });
    }

    /**
     * The frame of a camera that does not drive the settling, taken inside the still window of the one
     * that does.
     *
     * The head is one piece of metal, so the stillness that the settling camera demonstrates by finding
     * two of its frames alike is the same stillness this camera is standing in. What this camera has to
     * get right is being looking at the part at a moment inside that window, and the only way to know
     * that afterwards is to keep grabbing until there is a window and then keep the first frame whose
     * capture time is at or after its start. Frames grabbed earlier are dropped, and so is the whole
     * effort if the settling never agreed: with no window there is nothing to be inside, the frame is
     * handed over with no claim on it, the alignment refuses it and the batch goes sequential - which
     * costs a re-shoot and is counted.
     *
     * The camera is grabbed rather than taken from its running preview because a preview frame is a
     * preview frame: its own exposure and no light actuation of its own. At 8-17 ms a grab this is three
     * or four frames inside a settling of 25-53 ms, and those grabs are free in the only sense that
     * matters, because the main thread is waiting for that settling anyway and this runs on another
     * thread while it waits.
     *
     * @param arrivalAtMs as recorded by the motion of this batch, {@link FrameProvenance#UNKNOWN} if
     *            nobody recorded it.
     * @param settlingCamera the camera whose settling the head is waiting for.
     * @param settleEpochBefore {@link AbstractSettlingCamera#getSettleEpoch()} as it stood before this
     *            batch began. The window recorded by an older settling is a real stillness about a head
     *            that has since moved away, so none of the recorded numbers are looked at until this
     *            number has moved.
     * @param deadlineAtMs stop asking at this instant on the {@link NanosecondTime} millisecond scale;
     *            the caller takes the settling's own timeout plus room, so that a settling which never
     *            starts holds the head up for a bounded time instead of stopping the job.
     */
    public PreCapturedPlacement captureImageFromSettlingWindow(Part part, Camera camera,
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip, long arrivalAtMs,
            AbstractSettlingCamera settlingCamera, long settleEpochBefore, long deadlineAtMs)
            throws Exception {
        return capturePreparedFrame(part, camera, nozzle, wantedLocation, isFirstNozzleTip,
                (earliestFrameHandedTo) -> grabUntilTheStillWindow(camera, settlingCamera,
                        settleEpochBefore, arrivalAtMs, deadlineAtMs, earliestFrameHandedTo));
    }

    /**
     * One frame from a camera together with the claim that goes with it.
     */
    private static class GrabbedFrame {
        final BufferedImage image;
        final FrameProvenance provenance;
        final double captureMs;

        GrabbedFrame(BufferedImage image, FrameProvenance provenance, double captureMs) {
            this.image = image;
            this.provenance = provenance;
            this.captureMs = captureMs;
        }
    }

    /**
     * How a frame is obtained. Everything else about a pre-capture - the pipeline preparation, the
     * displacement, the pre-check, the light - is the same whether the frame was grabbed once or hunted
     * for inside a window, so it is written once, in {@link #capturePreparedFrame}.
     *
     * A strategy that produces more than one frame hands the earliest of them to
     * {@code earliestFrameHandedTo} as soon as it has it, without waiting for the frame it is hunting
     * for, because the pre-check has work to do while the head is still ringing. See
     * {@link #capturePreparedFrame}.
     */
    private interface FrameGrab {
        GrabbedFrame grab(Consumer<GrabbedFrame> earliestFrameHandedTo) throws Exception;
    }

    /**
     * The body the pre-captures share: prepare the pipeline so the displacement comes out the same way
     * as everywhere else, obtain a frame by the strategy given, start the pre-check on the earliest
     * frame there is, and put the light out on the way back.
     *
     * The pre-check frame and the alignment frame are deliberately not the same frame wherever they can
     * differ. Stage 1 of this design is "fill the ringing window with useful work": the frames the head
     * may not align on are exactly the frames the pre-check is for, and it has to look at them while
     * the ringing is still going on, so that the verdict is in hand when the head may leave. A check
     * that waited for the frame the alignment gets would run after the settling, take the vision
     * executor away from the alignment, and be worth nothing as a safety net because by then the head
     * has gone. What it judges is presence and size, which survive the ringing; position is not its to
     * judge, the alignment corrects that anyway.
     */
    private PreCapturedPlacement capturePreparedFrame(Part part, Camera camera, Nozzle nozzle,
            Location wantedLocation, boolean isFirstNozzleTip, FrameGrab frameGrab) throws Exception {
        BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);

        if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
            throw new Exception("Bottom vision not enabled for part "+part.getId());
        }

        // Clone pipeline for this capture
        CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
        try {
            // Prepare the pipeline with camera and other settings
            // skipMovement=true to prevent any hardware movement during parallel capture
            preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(), 
                    camera, part.getPackage(), nozzle, nozzle.getNozzleTip(), 
                    wantedLocation, wantedLocation, bottomVisionSettings, true);

            // Get the first pipeline shot (typically only one for simple capture)
            List<PipelineShot> shots = pipeline.getPipelineShots();
            if (shots.isEmpty()) {
                throw new Exception("No pipeline shots configured for part "+part.getId());
            }

            // Apply the shot - with skipMovement=true, this sets properties but doesn't move hardware
            shots.get(0).apply();

            // The light was already turned on in moveNozzlesToAlignmentPosition() before its
            // waitForCompletion(), so that the M810 was queued before the M400 and the light is on by
            // the time anything is captured here.
            Location displacementDelta = displacementDelta(camera, nozzle, wantedLocation,
                    isFirstNozzleTip);

            final PreCheckResult preCheckResult
                    = bottomVisionSettings.getPreCheckAction() != PreCheckAction.Disabled
                    && shots.size() == 1 ? new PreCheckResult() : null;
            final boolean[] checkStarted = new boolean[1];
            // Given the first frame the camera produces, from inside the hunt if there is a hunt,
            // because that is the frame that exists earliest and therefore the one the ringing window
            // can be spent on. Of this frame only the pixels and the duration are used: it carries no
            // claim, since a frame this early is the ringing itself.
            final Consumer<GrabbedFrame> earliestFrameHandedTo = (earliestFrame) -> {
                if (preCheckResult != null && !checkStarted[0]) {
                    checkStarted[0] = true;
                    Mat earliestImage = OpenCvUtils.toMat(earliestFrame.image);
                    try {
                        startPreCheck(earliestImage, preCheckResult, part, camera, nozzle,
                                wantedLocation, bottomVisionSettings, earliestFrame.captureMs);
                    }
                    finally {
                        earliestImage.release();
                    }
                }
            };

            GrabbedFrame grabbed = frameGrab.grab(earliestFrameHandedTo);
            Mat capturedImage = OpenCvUtils.toMat(grabbed.image);
            FrameProvenance provenance = grabbed.provenance;
            if (preCheckResult != null) {
                // A strategy that takes one frame hands nothing over early: there the frame is the
                // frame. How many frames the camera was asked for is only known once a hunt is over,
                // which is why that number is filled in here and starts out as "not known".
                earliestFrameHandedTo.accept(grabbed);
                preCheckResult.setFrameCount(Math.max(1, provenance.getFrameIndex()));
            }
            FrameDump.writeIfNeeded(bottomVisionSettings, capturedImage, provenance, camera, nozzle,
                    part, wantedLocation.getRotation());

            // Turn off light or register with batch operation (if active)
            try {
                camera.actuateLightAfterCapture();
            }
            catch (Exception e) {
                Logger.warn("Failed to turn off camera light after pre-capture: {}", e.getMessage());
            }

            PreCapturedPlacement capturedShot = new PreCapturedPlacement(
                    camera, nozzle, capturedImage, wantedLocation, displacementDelta, provenance);
            capturedShot.setPreCheckResult(preCheckResult);
            return capturedShot;
        }
        finally {
            // Always close the pipeline to release resources
            pipeline.close();
        }
    }

    /**
     * How far the part has moved on the camera between the place the head was taken to and the place
     * this nozzle's tip is really at: the calibrated camera offset of this nozzle tip, plus the runout
     * of that tip at the wanted rotation. The first nozzle tip is the one the head was positioned for,
     * so for it the displacement is nothing at all.
     */
    private Location displacementDelta(Camera camera, Nozzle nozzle, Location wantedLocation,
            boolean isFirstNozzleTip) {
        ReferenceNozzle refNozzle = (ReferenceNozzle) nozzle;
        ReferenceNozzleTip calibrationNozzleTip = refNozzle.getCalibrationNozzleTip();

        // The offset between the nozzle tip and the camera, from the nozzle tip runout calibration.
        Location cameraOffset = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
        if (calibrationNozzleTip != null
                && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
            cameraOffset = calibrationNozzleTip.getCalibration()
                    .getCalibratedCameraOffset(refNozzle, camera);
        }

        // The runout of the nozzle tip at the wanted rotation, from the same calibration.
        Location runoutCompensation = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
        if (calibrationNozzleTip != null
                && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
            runoutCompensation = calibrationNozzleTip.getCalibration()
                    .getCalibratedOffset(refNozzle, wantedLocation.getRotation());
        }

        Location displacementDelta = isFirstNozzleTip
                ? new Location(LengthUnit.Millimeters, 0, 0, 0, 0)
                : cameraOffset.add(runoutCompensation);
        Logger.debug("displacement: isFirstNozzleTip={}, cameraOffset={}, runoutCompensation={},"
                + " displacementDelta={}", isFirstNozzleTip, cameraOffset, runoutCompensation,
                displacementDelta);
        return displacementDelta;
    }

    /**
     * Get a frame from this camera that is demonstrably inside the still window the other camera's
     * settling is opening, and keep that one.
     *
     * The window is not something this camera can decide for itself; it is what the settling says, when
     * it says it, which is why the loop reads the settling rather than counting a predicted number of
     * milliseconds. What it does with that is aim rather than hammer: it takes one frame immediately
     * (for the pre-check, which has to see the ringing while it rings), then waits for the settling to
     * have a run of alike frames going, and only then captures again. A camera grabbing at full rate
     * while another one settles is not free - the two share the bus, the CPU and the light, and on the
     * machine the settling's own frames were measurably slower for every extra frame taken next door -
     * and a frame taken while the head is still moving is a frame that is going to be thrown away.
     *
     * Three ways out, in order of how much they mean:
     *
     *  - a frame captured at or after the start of the window: {@link FrameProvenance.Origin#Streamed}
     *    with that window in it, and the alignment accepts it;
     *  - the settling gave up: no window will open, so the frame is handed over with nothing claimed
     *    and the alignment refuses it, without waiting for a deadline that cannot help;
     *  - the deadline: same refusal, and the head leaves the cameras a bounded time after the rest.
     */
    private GrabbedFrame grabUntilTheStillWindow(Camera camera,
            AbstractSettlingCamera settlingCamera, long settleEpochBefore, long arrivalAtMs,
            long deadlineAtMs, Consumer<GrabbedFrame> earliestFrameHandedTo) throws Exception {
        long tCapture = 0;
        BufferedImage image = null;
        long capturedAtMs = 0;
        int grabs = 0;

        while (true) {
            if (grabs == 0) {
                // The first frame is taken at once, whatever the settling is doing: the head is over
                // the cameras, the light is on, and the pre-check can only judge the ringing while it
                // rings. It is also the frame the alignment gets if the settling had already made up its
                // mind before we arrived, which is why nothing is waited for here.
                tCapture = NanosecondTime.getRuntimeMilliseconds();
                image = camera.capture();
                capturedAtMs = NanosecondTime.getRuntimeMilliseconds();
                grabs = 1;
                earliestFrameHandedTo.accept(new GrabbedFrame(image, FrameProvenance.unknown(),
                        capturedAtMs-tCapture));
            }
            else {
                // Nothing the settling has recorded is about this batch until its epoch has moved, so
                // the numbers of the previous placement cannot be quoted as the stillness of this one.
                awaitSettling(settlingCamera, settleEpochBefore, deadlineAtMs);
                // Then wait until there is a prospect of a frame being still. Sleeping instead of
                // grabbing is the whole point: the two cameras share the bus, the CPU and the light, and
                // every frame taken here costs the settling a fraction of a frame of its own, so frames
                // taken while the head is still visibly moving are paid for twice and thrown away.
                // Aiming at the window is what leaves the settling alone while it measures.
                awaitProspectOfWindow(settlingCamera, settleEpochBefore, deadlineAtMs);

                tCapture = NanosecondTime.getRuntimeMilliseconds();
                image = camera.capture();
                capturedAtMs = NanosecondTime.getRuntimeMilliseconds();
                grabs++;
            }

            AbstractSettlingCamera.SettleResult result =
                    recordedResult(settlingCamera, settleEpochBefore);
            if (result == AbstractSettlingCamera.SettleResult.TimedOut) {
                Logger.debug("[SETTLE-WINDOW] camera {} drops frame {} after {} grabs: {} ran out of"
                        + " time, so there is no window this frame could be inside",
                        camera.getName(), grabs, grabs, settlingCamera.getName());
                return new GrabbedFrame(image, FrameProvenance.streamed(arrivalAtMs, capturedAtMs,
                        FrameProvenance.UNKNOWN, grabs), capturedAtMs - tCapture);
            }
            // The frame is either already inside a window that has been announced, or the settling is
            // one frame away from announcing one it belongs to. Asking the settling is cheaper than
            // taking another frame to find out, and the answer is bounded either way.
            long windowStartAtMs = awaitWindowCovering(settlingCamera, settleEpochBefore,
                    capturedAtMs, deadlineAtMs);
            if (windowStartAtMs != FrameProvenance.UNKNOWN) {
                Logger.debug("[SETTLE-WINDOW] camera {} frame {} at +{} ms after the rest is inside the"
                        + " window of {} which opened at +{} ms, margin {} ms of {} grabs",
                        camera.getName(), grabs, elapsed(arrivalAtMs, capturedAtMs),
                        settlingCamera.getName(), elapsed(arrivalAtMs, windowStartAtMs),
                        capturedAtMs-windowStartAtMs, grabs);
                return new GrabbedFrame(image, FrameProvenance.streamed(arrivalAtMs, capturedAtMs,
                        windowStartAtMs, grabs), capturedAtMs - tCapture);
            }
            if (NanosecondTime.getRuntimeMilliseconds() >= deadlineAtMs || grabs >= MAX_WINDOW_GRABS) {
                Logger.debug("[SETTLE-WINDOW] camera {} drops frame {} after {} grabs: no still window"
                        + " by +{} ms", camera.getName(), grabs, grabs,
                        elapsed(arrivalAtMs, capturedAtMs));
                return new GrabbedFrame(image, FrameProvenance.streamed(arrivalAtMs, capturedAtMs,
                        FrameProvenance.UNKNOWN, grabs), capturedAtMs - tCapture);
            }
        }
    }

    /**
     * What the settling has said about the settling this batch asked for, or nothing at all while the
     * epoch has not moved: see {@link AbstractSettlingCamera#getSettleEpoch()}.
     */
    private static AbstractSettlingCamera.SettleResult recordedResult(
            AbstractSettlingCamera settlingCamera, long settleEpochBefore) {
        return settlingCamera.getSettleEpoch() > settleEpochBefore
                ? settlingCamera.getRecordedSettleResult()
                : AbstractSettlingCamera.SettleResult.NotSettled;
    }

    /**
     * Wait until the settling being watched is the one this batch asked for, or until there is no time
     * left to wait for. Nothing is captured while waiting, because a settling that has not been asked
     * for yet has nothing to say about a frame.
     */
    private static void awaitSettling(AbstractSettlingCamera settlingCamera, long settleEpochBefore,
            long deadlineAtMs) throws Exception {
        while (settlingCamera.getSettleEpoch() <= settleEpochBefore
                && NanosecondTime.getRuntimeMilliseconds() < deadlineAtMs) {
            Thread.sleep(SETTLE_POLL_MS);
        }
    }

    /**
     * Wait until a frame of this camera has something to be still by: either the settling has a run of
     * alike frames going, which is the earliest a window can be said to open, or the settling is done
     * and has announced a window of its own - which for a configured wait is the whole of what it
     * knows, and there is no point in arriving before it. A settling that gave up ends the wait too,
     * because nothing is going to be still.
     */
    private static void awaitProspectOfWindow(AbstractSettlingCamera settlingCamera,
            long settleEpochBefore, long deadlineAtMs) throws Exception {
        while (NanosecondTime.getRuntimeMilliseconds() < deadlineAtMs) {
            AbstractSettlingCamera.SettleResult result =
                    recordedResult(settlingCamera, settleEpochBefore);
            if (stillWindowOf(result, settlingCamera) != FrameProvenance.UNKNOWN
                    || result == AbstractSettlingCamera.SettleResult.TimedOut
                    || (settlingCamera.getSettleEpoch() > settleEpochBefore
                            && settlingCamera.getCandidateStillWindowStartAtMs()
                                    != FrameProvenance.UNKNOWN)) {
                return;
            }
            Thread.sleep(SETTLE_POLL_MS);
        }
    }

    /**
     * The start of the still window that a frame captured at capturedAtMs is inside, waiting for the
     * settling to get round to saying it if it is going to. {@link FrameProvenance#UNKNOWN} means the
     * settling has finished with this frame and does not cover it: it gave up, ran out of the time this
     * head can spare, or certified a stillness that started after the frame was taken.
     */
    private static long awaitWindowCovering(AbstractSettlingCamera settlingCamera,
            long settleEpochBefore, long capturedAtMs, long deadlineAtMs) throws Exception {
        while (true) {
            AbstractSettlingCamera.SettleResult result =
                    recordedResult(settlingCamera, settleEpochBefore);
            long windowStartAtMs = stillWindowOf(result, settlingCamera);
            if (windowStartAtMs != FrameProvenance.UNKNOWN) {
                // Settled and said. It either covers this frame or it started after the frame was
                // taken; asking again will not make the frame older, so this is where the caller gets
                // to take another one.
                return capturedAtMs >= windowStartAtMs ? windowStartAtMs : FrameProvenance.UNKNOWN;
            }
            if (result == AbstractSettlingCamera.SettleResult.TimedOut
                    || NanosecondTime.getRuntimeMilliseconds() >= deadlineAtMs) {
                return FrameProvenance.UNKNOWN;
            }
            Thread.sleep(SETTLE_POLL_MS);
        }
    }

    /**
     * The instant from which the settling calls the head still, or {@link FrameProvenance#UNKNOWN} when
     * it has not said anything yet.
     *
     * A settling that measures takes the window from the earlier of the two frames it found alike. A
     * settling that is a configured wait has measured nothing and has no window, yet the frame it hands
     * over is still accepted on that claim (see {@link FrameProvenance}), and the frame of the other
     * camera at that same instant is entitled to exactly the same claim, so for it the end of the wait
     * is the window. A settling that timed out has no claim to give away.
     */
    private static long stillWindowOf(AbstractSettlingCamera.SettleResult result,
            AbstractSettlingCamera settlingCamera) {
        switch (result) {
            case Agreed:
                return settlingCamera.getRecordedStillWindowStartAtMs();
            case FixedTime:
                return settlingCamera.getRecordedSettledFrameAtMs();
            default:
                return FrameProvenance.UNKNOWN;
        }
    }

    private static double elapsed(long fromMs, long toMs) {
        if (fromMs == FrameProvenance.UNKNOWN || toMs == FrameProvenance.UNKNOWN) {
            return Double.NaN;
        }
        return toMs-fromMs;
    }

    /**
     * Capture an image with automatic settling for the first camera in parallel alignment.
     * This method performs auto-settle (capturing multiple frames until stable) before 
     * capturing the final image. This is used for the "settle camera" in parallel alignment
     * to stabilize the mechanical system before capturing both cameras.
     * 
     * The method calculates the displacement delta to account for both camera displacement
     * AND the nozzle tip runout error.
     * 
     * @param part The part being aligned
     * @param camera The settling camera to use for capture (must support settling)
     * @param nozzle The nozzle performing the capture
     * @param wantedLocation The location where capture should occur
     * @return PreCapturedPlacement containing the captured image and displacement delta
     * @throws Exception If capture fails
     */
    public PreCapturedPlacement captureImageOnlyWithSettle(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation) throws Exception {
        return captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, false,
                FrameProvenance.UNKNOWN);
    }

    public PreCapturedPlacement captureImageOnlyWithSettle(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip) throws Exception {
        return captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, isFirstNozzleTip,
                FrameProvenance.UNKNOWN);
    }

    /**
     * Settle, then take the frame that ended the settling. The frame is not captured again after the
     * settling has finished: the last frame of a settling is by definition the frame on which the
     * settling agreed, so capturing once more would only cost the time this whole design is trying to
     * save.
     *
     * @param arrivalAtMs when the head was reported at stillstand after moving to the cameras.
     */
    public PreCapturedPlacement captureImageOnlyWithSettle(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip, long arrivalAtMs)
            throws Exception {
        BottomVisionSettings bottomVisionSettings = getInheritedVisionSettings(part);
        
        if (!isEnabled() || !bottomVisionSettings.isEnabled()) {
            throw new Exception("Bottom vision not enabled for part "+part.getId());
        }
        
        // Clone pipeline for this capture
        CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
        try {
            // Prepare the pipeline with camera and other settings
            // skipMovement=true to prevent any hardware movement during parallel capture
            preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(), 
                    camera, part.getPackage(), nozzle, nozzle.getNozzleTip(), 
                    wantedLocation, wantedLocation, bottomVisionSettings, true);
            
            // Get the first pipeline shot (typically only one for simple capture)
            List<PipelineShot> shots = pipeline.getPipelineShots();
            if (shots.isEmpty()) {
                throw new Exception("No pipeline shots configured for part "+part.getId());
            }
            
            PipelineShot pipelineShot = shots.get(0);
            
            // Apply the shot - with skipMovement=true, this sets properties but doesn't move hardware
            pipelineShot.apply();
            
            // Light was already turned on in moveNozzlesToAlignmentPosition() before waitForCompletion()
            // This ensures M810 is queued before M400, so light is guaranteed on when capture happens
            
            // Calculate the displacement delta: nozzle tip camera offset + nozzle runout compensation
            // The camera offset comes from Nozzle Tip Runout Calibration (camera offset per nozzle tip)
            // The nozzle runout compensation comes from Nozzle Tip Runout Calibration at the wanted rotation
            ReferenceNozzle refNozzle = (ReferenceNozzle) nozzle;
            ReferenceNozzleTip calibrationNozzleTip = refNozzle.getCalibrationNozzleTip();
            
            // Get the camera offset for this nozzle tip (this is the offset between nozzle tip and camera)
            Location cameraOffset = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
            if (calibrationNozzleTip != null && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
                cameraOffset = calibrationNozzleTip.getCalibration().getCalibratedCameraOffset(refNozzle, camera);
            }
            
            // Get nozzle runout compensation at the wanted rotation angle
            Location runoutCompensation = new Location(cameraOffset.getUnits(), 0, 0, 0, 0);
            if (calibrationNozzleTip != null && calibrationNozzleTip.getCalibration().isCalibrated(refNozzle)) {
                runoutCompensation = calibrationNozzleTip.getCalibration().getCalibratedOffset(refNozzle, wantedLocation.getRotation());
            }
            
            // Combine: displacement delta = camera offset + runout compensation
            // This should be small (< 2mm in each direction)
            // For the first nozzle tip, displacement is zero since we're already at the correct position
            Location displacementDelta;
            if (isFirstNozzleTip) {
                displacementDelta = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
            }
            else {
                displacementDelta = cameraOffset.add(runoutCompensation);
            }
            Logger.debug("captureImageOnlyWithSettle: isFirstNozzleTip={}, cameraOffset={}, runoutCompensation={}, displacementDelta={}", 
                isFirstNozzleTip, cameraOffset, runoutCompensation, displacementDelta);
            
            // Perform auto-settle and capture - this captures multiple frames until stable
            // The camera must support settling (extend AbstractSettlingCamera)
            if (camera instanceof AbstractSettlingCamera) {
                AbstractSettlingCamera settlingCamera = 
                    (AbstractSettlingCamera) camera;
                // The pre-check, if the part wants one: the first frame of this very settling,
                // analysed while the mechanics are still settling. A part whose pipeline composites
                // several shots is left alone, because the frame the pre-check would judge is not
                // the frame the alignment uses.
                PreCheckResult preCheckResult = null;
                Consumer<Mat> firstFrameConsumer = null;
                if (bottomVisionSettings.getPreCheckAction() != PreCheckAction.Disabled
                        && shots.size() == 1) {
                    preCheckResult = new PreCheckResult();
                    // Until the settling is over, the line this check belongs to will be written by
                    // the thread that is settling, with the settling numbers in it.
                    preCheckResult.setSettleWindowOpen(true);
                    final PreCheckResult result = preCheckResult;
                    firstFrameConsumer = mat -> startPreCheck(mat, result, part, camera, nozzle,
                            wantedLocation, bottomVisionSettings,
                            settlingCamera.getRecordedFirstCaptureMilliseconds());
                }

                BufferedImage capturedBufferedImage = settlingCamera.settleAndCapture(
                    AbstractSettlingCamera.SettleOption.Settle,
                    firstFrameConsumer);
                Mat capturedImage = OpenCvUtils.toMat(capturedBufferedImage);
                // What the settling has just demonstrated about this frame. If it demonstrated nothing
                // - a settling that ran out of time - then this frame is no better than a snapshot
                // taken at full speed, and it says so.
                FrameProvenance provenance = settleProvenance(settlingCamera, arrivalAtMs);
                FrameDump.writeIfNeeded(bottomVisionSettings, capturedImage, provenance, camera,
                        nozzle, part, wantedLocation.getRotation());
                if (preCheckResult != null) {
                    // These two numbers only exist now that the settling is over. The check is not
                    // waited for: if it has not reported by now then it has not reported at all, and
                    // that is a normal outcome rather than a failure. It is written here, with the
                    // complete numbers, whenever the verdict happens to be in by now; if it is not,
                    // the line goes out with the verdict still pending and the check adds its own
                    // line when it gets round to it.
                    preCheckResult.setSettleMs(settlingCamera.getRecordedSettleMilliseconds()
                            - settlingCamera.getRecordedFirstFrameMilliseconds());
                    preCheckResult.setFrameCount(settlingCamera.getRecordedFrameCount());
                    preCheckResult.setSettleWindowOpen(false);
                    if (preCheckResult.claimLogLine()) {
                        logPreCheck(preCheckResult, part, camera, nozzle,
                                bottomVisionSettings.getPreCheckAction(), false);
                    }
                }
                
                // Turn off light or register with batch operation (if active)
                try {
                    camera.actuateLightAfterCapture();
                }
                catch (Exception e) {
                    Logger.warn("Failed to turn off camera light after pre-capture: {}", e.getMessage());
                }
                
                // Create a PreCapturedPlacement with the captured image and displacement delta
                PreCapturedPlacement capturedShot = new PreCapturedPlacement(
                        camera, nozzle, capturedImage, wantedLocation, displacementDelta, provenance);
                capturedShot.setPreCheckResult(preCheckResult);
                return capturedShot;
            }
            else {
                // Camera doesn't support settling - fall back to regular capture
                Logger.warn("Camera {} does not support settling, using regular capture", camera.getName());
                BufferedImage capturedBufferedImage = camera.capture();
                Mat capturedImage = OpenCvUtils.toMat(capturedBufferedImage);
                
                // Turn off light or register with batch operation (if active)
                try {
                    camera.actuateLightAfterCapture();
                }
                catch (Exception e) {
                    Logger.warn("Failed to turn off camera light after pre-capture: {}", e.getMessage());
                }
                
                return new PreCapturedPlacement(
                        camera, nozzle, capturedImage, wantedLocation, displacementDelta,
                        FrameProvenance.quickCapture(arrivalAtMs,
                                NanosecondTime.getRuntimeMilliseconds()));
            }
        }
        finally {
            // Always close the pipeline to release resources
            pipeline.close();
        }
    }

    /**
     * What the settling has just demonstrated about the frame it handed back.
     *
     * A settling that agreed is a measurement: two frames taken at different moments came out the
     * same, so between them the mechanics were still, and that moment is the still window every other
     * camera's frame has to fall inside. A settling that ran out of time is the same measurement
     * answering "no", which is why its frame is not still however long it waited. A settling that was
     * only ever a configured wait claims less: the frame is the one the settings asked for, but there
     * is no window to place anything in, so no other camera can borrow its stillness.
     */
    protected static FrameProvenance settleProvenance(AbstractSettlingCamera settlingCamera,
            long arrivalAtMs) {
        switch (settlingCamera.getRecordedSettleResult()) {
            case Agreed:
            case FixedTime:
                return FrameProvenance.settled(arrivalAtMs,
                        settlingCamera.getRecordedSettledFrameAtMs(),
                        settlingCamera.getRecordedStillWindowStartAtMs(),
                        settlingCamera.getRecordedFrameCount());
            case TimedOut:
                return FrameProvenance.settleTimedOut(arrivalAtMs,
                        settlingCamera.getRecordedSettledFrameAtMs(),
                        settlingCamera.getRecordedFrameCount());
            default:
                return FrameProvenance.unknown();
        }
    }

    /**
     * Hand the first frame of a settling to the vision executor to be pre-checked.
     *
     * This runs on the thread that is settling, inside the settling loop, at the moment the frame
     * arrived. Consequently nothing may be analysed here and nothing may be waited for here: the
     * loop measures itself, and every millisecond spent here is a millisecond of settling that did
     * not happen. The only things done are a clone of the frame and a submit, and the frame itself
     * stays owned by the camera, which releases it as soon as this returns.
     */
    protected void startPreCheck(Mat firstFrame, PreCheckResult preCheckResult, Part part,
            Camera camera, Nozzle nozzle, Location wantedLocation,
            BottomVisionSettings bottomVisionSettings, double captureMs) {
        preCheckResult.setCaptureMs(captureMs);
        final Mat frame;
        long tClone = NanosecondTime.getRuntimeMilliseconds();
        try {
            frame = firstFrame.clone();
        }
        catch (Exception e) {
            // Said nothing, as everywhere else during the settling: the line is written once the
            // durations are known, by whoever has them all.
            preCheckResult.finish(Verdict.Failed, "cannot keep the first frame: "+e.getMessage());
            return;
        }
        // The shot owns the clone from here on, so that the single flag on it is the only answer to
        // whether the native image has been given back.
        final CapturedShot shot = new CapturedShot(camera, nozzle, frame, wantedLocation);
        // Keeping the frame is the one thing the pre-check does on the settling thread, so it is
        // measured: everything else about the check is concurrent with the settling and shows up
        // there only as interference.
        final double cloneMs = NanosecondTime.getRuntimeMilliseconds() - tClone;
        preCheckResult.setCloneMs(cloneMs);
        final long handedOverAt = NanosecondTime.getRuntimeMilliseconds();
        // The check is timed from the frame being in hand, not from the moment a thread picked the
        // task up, because the frame arriving is what the settling is racing against.
        final long startedAt = tClone;
        try {
            ReferencePnpJobProcessor.getVisionExecutor().submit(() -> {
                try {
                    runPreCheck(shot, preCheckResult, part, camera, nozzle, wantedLocation,
                            bottomVisionSettings, startedAt, handedOverAt);
                }
                finally {
                    shot.release();
                }
            });
        }
        catch (RejectedExecutionException e) {
            shot.release();
            preCheckResult.finish(Verdict.Failed, "vision executor refused the check: "+e.getMessage());
        }
    }

    /**
     * Analyse one pre-captured first frame with the pipeline of the alignment itself, i.e. with the
     * same crop and the same parameter assignments the alignment will use, so that the two verdicts
     * mean the same thing.
     *
     * What it decides is presence and size, and nothing else: the distance of the part from the
     * centre of the frame is measured and logged but never judged, because this frame was taken
     * while the mechanics were still settling and how far the part will have drifted by the settled
     * frame is not yet known on this machine.
     *
     * Never throws and never gates anything: the verdict is a piece of information handed to the
     * job, and a check that cannot produce one is itself reported as {@link Verdict#Failed} rather
     * than allowed to disturb the job.
     */
    protected void runPreCheck(CapturedShot shot, PreCheckResult result, Part part, Camera camera,
            Nozzle nozzle, Location wantedLocation, BottomVisionSettings bottomVisionSettings,
            long startedAt, long handedOverAt) {
        long t0 = NanosecondTime.getRuntimeMilliseconds();
        result.setQueueMs(t0 - handedOverAt);
        try {
            long tPipeline = NanosecondTime.getRuntimeMilliseconds();
            CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
            result.setPipelineMs(NanosecondTime.getRuntimeMilliseconds() - tPipeline);
            try {
                preparePipeline(pipeline, bottomVisionSettings.getPipelineParameterAssignments(),
                        camera, part.getPackage(), nozzle, nozzle.getNozzleTip(),
                        wantedLocation, wantedLocation, bottomVisionSettings, true);
                pipeline.setPreCapturedShot(0, shot);
                List<PipelineShot> shots = pipeline.getPipelineShots();
                if (shots.isEmpty()) {
                    result.finish(Verdict.Inconclusive, "pipeline has no shot");
                    return;
                }
                shots.get(0).apply();
                long tPrepare = NanosecondTime.getRuntimeMilliseconds();
                result.setPrepMs(tPrepare - t0 - result.getPipelineMs());
                pipeline.process();
                result.setProcessMs(NanosecondTime.getRuntimeMilliseconds() - tPrepare);
                Result visionResult = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
                if (visionResult == null) {
                    // The older name of the results stage.
                    visionResult = pipeline.getResult("result");
                }
                if (visionResult == null) {
                    result.finish(Verdict.PartMissing, "pipeline has no result");
                    return;
                }
                if (!(visionResult.model instanceof RotatedRect)) {
                    result.finish(Verdict.PartMissing, "no rectangle in the pipeline result");
                    return;
                }
                RotatedRect rect = (RotatedRect) visionResult.model;
                Location upp = camera.getUnitsPerPixelAtZ();
                result.setMeasuredSize(
                        upp.getLengthX().multiply(rect.size.width).convertToUnits(LengthUnit.Millimeters).getValue(),
                        upp.getLengthY().multiply(rect.size.height).convertToUnits(LengthUnit.Millimeters).getValue());
                PipelineGeometry geometry = new PipelineGeometry(rect, visionResult);
                result.setOffsets(geometry.getCenterOffsets(camera).getLinearLengthTo(Location.origin)
                        .convertToUnits(LengthUnit.Millimeters).getValue());
                result.setRotation(Utils2D.angleNorm(VisionUtils.getPixelAngle(camera, rect.angle)));
                Location nominal = bottomVisionSettings.getPartCheckSize(part, false);
                if (nominal != null) {
                    result.setNominalSize(
                            nominal.getLengthX().convertToUnits(LengthUnit.Millimeters).getValue(),
                            nominal.getLengthY().convertToUnits(LengthUnit.Millimeters).getValue());
                }
                if (!(rect.size.width > 0 && rect.size.height > 0)) {
                    result.finish(Verdict.Inconclusive, "the pipeline returned a rectangle without size");
                    return;
                }
                try {
                    SizeFit fit = checkPartSizeEitherWay(part, bottomVisionSettings, rect, camera);
                    if (fit.fits() && fit.isTurned()) {
                        // The part is the part. It is lying a quarter turn from its footprint, which is
                        // not this check's business to gate on and is its business to say out loud.
                        result.finish(Verdict.Ok, "part found within size tolerance, turned: "+fit.getMessage());
                    }
                    else if (fit.fits()) {
                        result.finish(Verdict.Ok, (nominal == null 
                                ? "part found, no size configured to compare with" : "part found within size tolerance"));
                    }
                    else {
                        result.finish(Verdict.SizeOut, fit.getMessage());
                    }
                }
                catch (Exception e) {
                    result.finish(Verdict.SizeOut, e.getMessage());
                }
            }
            finally {
                pipeline.close();
            }
        }
        catch (Exception e) {
            result.finish(Verdict.Failed, e.getMessage());
        }
        finally {
            result.setCheckMs(NanosecondTime.getRuntimeMilliseconds() - startedAt);
            if (result.getFrameCount() < 0) {
                // Nobody has told this check how many frames were captured for the placement, which
                // means the capture is still going on: the check was handed the first frame of a hunt
                // and the hunt has not ended. The number is the capturer's to fill in when it does, and
                // guessing it from this camera's last settling would quote a different batch. The line
                // therefore says "?" and the hunt says "of N grabs" in its own line.
                Logger.trace("pre-check reports before the frame count is known");
            }
            if (!result.isSettleWindowOpen()) {
                // Either there was no settling at all, so nobody else will write this line, or the
                // settling is over and the capture thread has written or is about to write it: claim
                // it if it can still be had, otherwise add the verdict as a late line of its own,
                // because the line that went out could not contain it.
                logPreCheck(result, part, camera, nozzle, bottomVisionSettings.getPreCheckAction(),
                        !result.claimLogLine());
            }
        }
    }

    /**
     * One line per pre-check, with capture, settle and check as three separate numbers and never as
     * a sum of each other.
     *
     * A number the check has not produced is left out rather than written as -1.0: a line that says
     * check=-1.0 size=-1.00x-1.00 reads as a measurement of nothing in particular, and the one line
     * where that happens - the hunt ending before the check reported - is exactly the line nobody
     * should have to decode.
     */
    protected void logPreCheck(PreCheckResult result, Part part, Camera camera, Nozzle nozzle,
            PreCheckAction action, boolean late) {
        boolean pending = result.getVerdict() == Verdict.Pending;
        String line = String.format(
                "[PRE-CHECK%s] nozzle=%s camera=%s part=%s frame=1/%s capture=%s settle=%s check=%s"
                        + " clone=%s queue=%s pipeline=%s prep=%s pipe=%s judge=%s"
                        + " size=%s expected=%s rot=%s offset=%s verdict=%s action=%s",
                late ? "-LATE" : "", nozzle.getId(), camera.getName(), part.getId(),
                result.getFrameCount() < 0 ? "?" : String.valueOf(result.getFrameCount()),
                ms(result.getCaptureMs()), ms(result.getSettleMs()), ms(result.getCheckMs()),
                ms(result.getCloneMs()), ms(result.getQueueMs()), ms(result.getPipelineMs()),
                ms(result.getPrepMs()),
                ms(result.getProcessMs()), ms(result.getJudgeMs()),
                size(result.getMeasuredWidth(), result.getMeasuredHeight()),
                size(result.getNominalWidth(), result.getNominalHeight()),
                pending ? "?" : String.format("%.1f", result.getRotation()),
                pending ? "?" : String.format("%.2f", result.getOffsets()),
                result.getVerdict().name(), action.name());
        if (pending) {
            // Not a finding but the absence of one, and a normal one: the check is on the vision
            // executor and the head does not wait for it. The verdict arrives on its own line, marked
            // -LATE, when it arrives.
            Logger.debug(line+" : the check has not reported yet, it is still on the vision executor");
        }
        else if (result.getVerdict() == Verdict.Ok) {
            Logger.debug(line);
        }
        else {
            Logger.warn(line+(result.getMessage().isEmpty() ? "" : " : "+result.getMessage()));
        }
    }

    protected static String ms(double milliseconds) {
        return milliseconds < 0.0 ? "-" : String.format("%.1f", milliseconds);
    }

    protected static String size(double width, double height) {
        return (width < 0.0 && height < 0.0) ? "-" : String.format("%.2fx%.2f", width, height);
    }

    public static ReferenceBottomVision getDefault() { 
        return (ReferenceBottomVision) Configuration.get().getMachine().getPartAlignments().get(0);
    }
}
