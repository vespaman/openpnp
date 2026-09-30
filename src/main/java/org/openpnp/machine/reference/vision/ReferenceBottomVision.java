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

    private boolean partSizeCheck(Part part, BottomVisionSettings bottomVisionSettings, RotatedRect partRect, Camera camera) throws Exception {
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
            
            // Return the pre-captured shot (no hardware references)
            return new CapturedShot(camera, nozzle, preCapturedImage, shotLocation);
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
        return captureImageOnly(part, camera, nozzle, wantedLocation, false);
    }

    public PreCapturedPlacement captureImageOnly(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip) throws Exception {
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
            Logger.debug("captureImageOnly: isFirstNozzleTip={}, cameraOffset={}, runoutCompensation={}, displacementDelta={}", 
                isFirstNozzleTip, cameraOffset, runoutCompensation, displacementDelta);
            
            // Capture the image (no settling needed - head is already stationary)
            BufferedImage capturedBufferedImage = camera.capture();
            Mat capturedImage = OpenCvUtils.toMat(capturedBufferedImage);
            
            // Turn off light or register with batch operation (if active)
            try {
                camera.actuateLightAfterCapture();
            }
            catch (Exception e) {
                Logger.warn("Failed to turn off camera light after pre-capture: {}", e.getMessage());
            }
            
            // Create a PreCapturedPlacement with the captured image and displacement delta
            return new PreCapturedPlacement(
                    camera, nozzle, capturedImage, wantedLocation, displacementDelta);
        }
        finally {
            // Always close the pipeline to release resources
            pipeline.close();
        }
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
        return captureImageOnlyWithSettle(part, camera, nozzle, wantedLocation, false);
    }

    public PreCapturedPlacement captureImageOnlyWithSettle(Part part, Camera camera, 
            Nozzle nozzle, Location wantedLocation, boolean isFirstNozzleTip) throws Exception {
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
                    final PreCheckResult result = preCheckResult;
                    firstFrameConsumer = mat -> startPreCheck(mat, result, part, camera, nozzle,
                            wantedLocation, bottomVisionSettings, settlingCamera);
                }

                BufferedImage capturedBufferedImage = settlingCamera.settleAndCapture(
                    AbstractSettlingCamera.SettleOption.Settle,
                    firstFrameConsumer);
                Mat capturedImage = OpenCvUtils.toMat(capturedBufferedImage);
                if (preCheckResult != null) {
                    // These two numbers only exist now that the settling is over. The check is not
                    // waited for: if it has not reported by now then it has not reported at all, and
                    // that is a normal outcome rather than a failure. It is written here, with the
                    // complete numbers, whenever the verdict happens to be in by now; if it is not,
                    // the line goes out with the verdict still pending and the check adds its own
                    // line when it gets round to it.
                    preCheckResult.setSettleMs(settlingCamera.getRecordedSettleMilliseconds()
                            - settlingCamera.getRecordedFirstFrameMilliseconds());
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
                        camera, nozzle, capturedImage, wantedLocation, displacementDelta);
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
                        camera, nozzle, capturedImage, wantedLocation, displacementDelta);
            }
        }
        finally {
            // Always close the pipeline to release resources
            pipeline.close();
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
            BottomVisionSettings bottomVisionSettings, AbstractSettlingCamera settlingCamera) {
        // What the frame cost, while it is still known only to the camera and the settling has not
        // finished: the camera recorded it just before handing the frame over.
        preCheckResult.setCaptureMs(settlingCamera.getRecordedFirstCaptureMilliseconds());
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
            CvPipeline pipeline = getThreadLocalPipeline(bottomVisionSettings);
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
                result.setPrepMs(tPrepare - t0);
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
                    partSizeCheck(part, bottomVisionSettings, rect, camera);
                    result.finish(Verdict.Ok, (nominal == null 
                            ? "part found, no size configured to compare with" : "part found within size tolerance"));
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
            if (camera instanceof AbstractSettlingCamera) {
                // However many frames the settling has got through by now. If it is still settling
                // then this is the count so far and the capture thread replaces it with the final
                // one when it writes the line; if it has finished then they are the same number.
                result.setFrameCount(((AbstractSettlingCamera) camera).getRecordedFrameCount());
            }
            if (result.getSettleMs() >= 0.0) {
                // The settling is over, so the capture thread has written or is about to write the
                // one line: claim it if it can still be had, otherwise add the verdict as a late
                // line of its own, because the line that went out could not contain it.
                logPreCheck(result, part, camera, nozzle, bottomVisionSettings.getPreCheckAction(),
                        !result.claimLogLine());
            }
        }
    }

    /**
     * One line per pre-check, with capture, settle and check as three separate numbers and never as
     * a sum of each other.
     */
    protected void logPreCheck(PreCheckResult result, Part part, Camera camera, Nozzle nozzle,
            PreCheckAction action, boolean late) {
        String line = String.format(
                "[PRE-CHECK%s] nozzle=%s camera=%s part=%s frame=1/%s capture=%.1f settle=%.1f check=%.1f"
                        + " clone=%.1f queue=%.1f prep=%.1f pipe=%.1f judge=%.1f"
                        + " size=%.2fx%.2f expected=%.2fx%.2f rot=%.1f offset=%.2f verdict=%s action=%s",
                late ? "-LATE" : "", nozzle.getId(), camera.getName(), part.getId(),
                result.getFrameCount() < 0 ? "?" : String.valueOf(result.getFrameCount()),
                result.getCaptureMs(), result.getSettleMs(), result.getCheckMs(),
                result.getCloneMs(), result.getQueueMs(), result.getPrepMs(),
                result.getProcessMs(), result.getJudgeMs(),
                result.getMeasuredWidth(), result.getMeasuredHeight(),
                result.getNominalWidth(), result.getNominalHeight(),
                result.getRotation(), result.getOffsets(),
                result.getVerdict().name(), action.name());
        if (result.getVerdict() == Verdict.Ok) {
            Logger.debug(line);
        }
        else {
            Logger.warn(line+(result.getMessage().isEmpty() ? "" : " : "+result.getMessage()));
        }
    }

    public static ReferenceBottomVision getDefault() { 
        return (ReferenceBottomVision) Configuration.get().getMachine().getPartAlignments().get(0);
    }
}
