/*
 * Copyright (C) 2026 Micael <m8847@abc.se>
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.PreCapturedPlacement;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.base.AbstractHeadMountable;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;

import com.google.common.io.Files;

/**
 * §9 of docs/design/parallel-bottom-vision/runtime-displacement-plan.md: the displacement a parallel
 * batch hands the analysis of a nozzle it never moved to.
 *
 * <p>The contract (§3) is one sentence: the displacement of a shot is the difference between where
 * that shot wanted to be and where the nozzle actually was, recorded at the instant the frame was
 * taken. A parallel batch moves the head <em>once</em> - to the wanted bottom-vision location of
 * {@code allPlacements.get(0)} - so only the nozzle the head was moved for can stand on its own
 * camera axis. Every other nozzle is left standing at whatever the rigid head leaves it at, and the
 * difference between that and where its own shot wanted it is a real, computable vector that the
 * analysis has to be told about or it places the part wrong by exactly that much.
 *
 * <p>The rig below is that batch with the minimum of moving parts, and every number in it is a
 * constant of this file:
 *
 * <pre>
 *   nozzle head offsets   H1 = (0.000,  0.000)   H2 = (20.000, 0.300)   →  H2-H1 = ( 20.000, 0.300)
 *   camera head offsets   S1 = (100.000, 50.000) S2 = (119.700, 50.450) →  S2-S1 = ( 19.700, 0.450)
 *   spacing mismatch      (H2-H1)-(S2-S1)                                =  (  0.300, -0.150)
 *   runout circle centre  C1 = (0.040,  0.030)   C2 = (0.185,  0.024)
 *   runout radius         R1 = 0.120            R2 = 0.090             (phase 0, both nozzles at C=0)
 *   injected camera error e1 = (0.250, 0.750)   e2 = (-0.150, 0.350)   (SimulatedUpCamera error offsets)
 * </pre>
 *
 * The camera spacing is deliberately <em>not</em> the mirror of the nozzle spacing - 0.335mm of
 * mismatch, the same class of error pn52 measures as 0.502mm (DISPLACEMENT-VERIFICATION.md §5) - so
 * no single head position can put both tips on both camera axes. The head is moved once, for
 * N1/Upp1, exactly the way {@code ReferencePnpJobProcessor.moveNozzlesToAlignmentPosition()} does
 * it, and each nozzle's shot is then taken the way the batch takes it, with nothing said about which
 * nozzle the head was moved for.
 *
 * <p>What the run says (2026-10-07, {@code mvn -o test -Dtest=ParallelBatchResidualTest}):
 *
 * <pre>
 *   N1/Upp1 moved-for    truth wanted-actual = (0.000000, 0.000000, 0.000000, 0.000000 mm)   term handed over = (0.000000, 0.000000, 0.000000, 0.000000 mm)   shot displacement = (0.000000, 0.000000, 0.000000, 0.000000 mm)
 *   N2/Upp2 not-moved    truth wanted-actual = (-0.415000, 0.156000, 0.000000, 0.000000 mm)   term handed over = (0.275000, 0.024000, 0.000000, 0.000000 mm)   shot displacement = (-0.415000, 0.156000, 0.000000, 0.000000 mm)
 *
 *   AssertionFailedError: N2/Upp2 (not moved for): the analysis must be handed the residual of its own shot. Truth (wanted - actual) is (-0.415000, 0.156000, 0.000000, 0.000000 mm)mm but captureImageOnlyWithSettle() hands it (0.275000, 0.024000, 0.000000, 0.000000 mm)mm, off by 0.7025126333383553mm against a 0.026875mm tolerance. ==&gt; expected: &lt;-0.41499999999999204&gt; but was: &lt;0.27499999999999997&gt;
 *   Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.093 s &lt;&lt;&lt; FAILURE! - in ParallelBatchResidualTest
 * </pre>
 *
 * The second line is the bug this plan exists for and it fails: the term is the tip calibration's
 * circle centre plus the runout at the rotation the nozzle happens to stand at, it contains none of
 * the 0.300, -0.150 spacing mismatch, and it charges C2 again although wanted2 = S2 - C2 already
 * carries it (E2). Off by 0.690mm in X, 0.132mm in Y, 0.702513mm in total on a 0.026875mm tolerance.
 *
 * <p>The first line does <em>not</em> fail, and that is a finding of its own against the wording of
 * §9, which expected the primary to fail too ("it is 0 for the first when the truth is -e1"). It
 * cannot: with the head moved for it, the primary's wanted-minus-actual is 0 exactly, and the
 * injected camera error does not touch it. {@link SimulatedUpCamera#setErrorOffsets(Location)} is
 * applied in {@code fillShape()} only where {@code addError} is true, which is the part's body and
 * pads and not the tip - it simulates a part sitting off-centre on the tip, so it moves what the
 * camera <em>sees</em> and not where the machine thinks the head is. Both
 * {@code getActualAtCapture()} and {@code getShotDisplacement()} are position bookkeeping and are
 * bit-for-bit the same with e1 = (0.250, 0.750) injected as with it removed; that is asserted below
 * so it cannot quietly change. A camera that is not where the model says it is is what the vision is
 * there to measure; no shot displacement can ever compensate it, and §3's contract agrees
 * ("the nozzle the head was moved for gets 0 because it is 0"). §9's expression for the second
 * nozzle, C2-C1+(H2-H1)-(S2-S1), is also the negation of §3's wanted-minus-actual, which is the sign
 * Q3 in §7 warned was asserted rather than measured.
 *
 * <p>The prediction is gone (Stage 4e): there is one term, it is the residual, and the contract holds for
 * both nozzles - the term handed to the analysis of the nozzle that was never moved is the residual of its
 * own shot, (-0.415, 0.156), to within two pixels. What is asserted below is that quantity seen two ways:
 * as the bookkeeping of the shot, and as the difference of two positions computed out of this file's
 * constants alone. The old prediction would have said (0.275, 0.024) for the second line, 0.70mm away.
 */
public class ParallelBatchResidualTest {

    /**
     * The up-looking cameras of pn52 are 0.0134mm per pixel; the simulated ones are given the same
     * resolution so that a tolerance stated in pixels means the same thing here as on the machine.
     * Nothing in the comparison below is measured out of an image - both sides are bookkeeping, so
     * they ought to agree exactly - but a tolerance of two pixels is what the machine could ever
     * have been held to, and it is wide enough that a correct implementation cannot fail on
     * floating point or unit round-trips.
     */
    private static final double PIXEL_MM = 0.0134375;
    private static final double TOLERANCE_MM = 2.0 * PIXEL_MM;

    // The head: two nozzles, 20mm apart in X and 0.3mm apart in Y.
    private static final Location NOZZLE_HEAD_OFFSET_1 = new Location(LengthUnit.Millimeters, 0.000, 0.000, 0, 0);
    private static final Location NOZZLE_HEAD_OFFSET_2 = new Location(LengthUnit.Millimeters, 20.000, 0.300, 0, 0);

    // The cameras: deliberately 0.300, -0.150 off being the mirror image of the nozzles.
    private static final Location CAMERA_HEAD_OFFSET_1 = new Location(LengthUnit.Millimeters, 100.000, 50.000, 0, 0);
    private static final Location CAMERA_HEAD_OFFSET_2 = new Location(LengthUnit.Millimeters, 119.700, 50.450, 0, 0);

    // The circle centres a runout calibration would have fitted, per nozzle.
    private static final double CENTRE_1_X = 0.040;
    private static final double CENTRE_1_Y = 0.030;
    private static final double CENTRE_2_X = 0.185;
    private static final double CENTRE_2_Y = 0.024;

    // The runout of the two tips. The fitted model has no phase shift, so at C = 0 it points along +X.
    private static final double RUNOUT_1_AT_ZERO_X = 0.120;
    private static final double RUNOUT_2_AT_ZERO_X = 0.090;

    // The injected camera errors. Visible in the picture, invisible to the head position - see javadoc.
    private static final Location CAMERA_ERROR_1 = new Location(LengthUnit.Millimeters, 0.250, 0.750, 0, 0);
    private static final Location CAMERA_ERROR_2 = new Location(LengthUnit.Millimeters, -0.150, 0.350, 0, 0);

    private static final Length PART_HEIGHT = new Length(1.0, LengthUnit.Millimeters);

    private Machine machine;
    private ReferenceNozzle nozzle1;
    private ReferenceNozzle nozzle2;
    private SimulatedUpCamera camera1;
    private SimulatedUpCamera camera2;
    private Part part;

    @BeforeEach
    public void before() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        // Save migrated settings
        Configuration.get().save();

        machine = Configuration.get().getMachine();
        ReferenceMachine referenceMachine = (ReferenceMachine) machine;
        NullDriver driver = (NullDriver) referenceMachine.getDefaultDriver();
        driver.setFeedRateMmPerMinute(0);
        for (Axis axis : referenceMachine.getAxes()) {
            if (axis instanceof ReferenceControllerAxis) {
                ((ReferenceControllerAxis) axis).setFeedratePerSecond(new Length(1000000, LengthUnit.Millimeters));
                ((ReferenceControllerAxis) axis).setAccelerationPerSecond2(new Length(2000000, LengthUnit.Millimeters));
                ((ReferenceControllerAxis) axis).setJerkPerSecond3(new Length(0, LengthUnit.Millimeters));
            }
        }

        buildHead(referenceMachine);
        buildCameras(referenceMachine);
        buildPart();

        // The tip calibration last: touching an up-looking camera's offsets wipes every nozzle tip
        // calibration on the machine (ReferenceCamera.viewHasChanged()), which is E4 of the plan.
        calibrateTips((ReferenceNozzleTip) referenceMachine.getNozzleTips().get(0));

        referenceMachine.setEnabled(true);
        referenceMachine.home();
    }

    /**
     * The contract of the parallel path: the displacement handed to the analysis of a nozzle that was
     * never moved is the residual of that nozzle's own shot. Nothing predicted, and nothing privileged as
     * the nozzle the head was moved for.
     */
    @Test
    public void testTheTermOfAParallelBatchIsTheResidualOfItsShot() throws Exception {
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();

        assertSame(camera1, VisionUtils.getBottomVisionCamera(nozzle1),
                "N1 must be paired with the camera whose name carries a 1");
        assertSame(camera2, VisionUtils.getBottomVisionCamera(nozzle2),
                "N2 must be paired with the camera whose name carries a 2");
        assertRig();

        // The batch moves the head once, for the first placement's nozzle (E8), to that nozzle's own
        // wanted location at part height - exactly what moveNozzlesToAlignmentPosition() computes.
        Location wanted1 = vision.getCameraLocationAtPartHeight(part, camera1, nozzle1, 0., false);
        Location wanted2 = vision.getCameraLocationAtPartHeight(part, camera2, nozzle2, 0., false);
        nozzle1.moveToSafeZ();
        MovableUtils.moveToLocationAtSafeZ(nozzle1, wanted1);

        // The truth, from this file's constants and the kinematics alone: the move for N1 commands
        // the head to wanted1 minus N1's offsets minus the runout the tip model claims at the rotation
        // being commanded; the report then adds both back for the nozzle it describes, and adds only
        // its own for the sibling the head dragged along.
        Location wanted1FromConstants = CAMERA_HEAD_OFFSET_1.subtract(centre1()).add(height());
        Location wanted2FromConstants = CAMERA_HEAD_OFFSET_2.subtract(centre2()).add(height());
        Location headAtAlignment = wanted1FromConstants.subtract(NOZZLE_HEAD_OFFSET_1).subtract(runout1AtZero());
        Location actual1FromConstants = headAtAlignment.add(NOZZLE_HEAD_OFFSET_1).add(runout1AtZero());
        Location actual2FromConstants = headAtAlignment.add(NOZZLE_HEAD_OFFSET_2).add(runout2AtZero());
        Location truth1 = toXy(wanted1FromConstants.subtract(actual1FromConstants));
        Location truth2 = toXy(wanted2FromConstants.subtract(actual2FromConstants));

        // No head position can serve both nozzles: the mismatch is far bigger than the tolerance this
        // test is written to.
        assertTrue(length2D(truth2) > 10 * TOLERANCE_MM,
                "the rig must not be able to satisfy both nozzles at once, but truth2 is " + truth2);

        // The same truth as the machine sees it: where the shot wanted to be minus where its nozzle
        // actually was, XY, millimetres.
        Location truth1Measured = toXy(wanted1.subtract(nozzle1.getLocation()));
        Location truth2Measured = toXy(wanted2.subtract(nozzle2.getLocation()));
        assertEquals(truth1.getX(), truth1Measured.getX(), 1e-9, "truth1 X from constants vs from the machine");
        assertEquals(truth1.getY(), truth1Measured.getY(), 1e-9, "truth1 Y from constants vs from the machine");
        assertEquals(truth2.getX(), truth2Measured.getX(), 1e-9, "truth2 X from constants vs from the machine");
        assertEquals(truth2.getY(), truth2Measured.getY(), 1e-9, "truth2 Y from constants vs from the machine");

        // The two shots of the batch, taken the way the batch takes them: one head move, two nozzles,
        // and each shot measuring for itself where its own nozzle turned out to be.
        PreCapturedPlacement shot1 = vision.captureImageOnlyWithSettle(part, camera1, nozzle1, wanted1);
        PreCapturedPlacement shot2 = vision.captureImageOnlyWithSettle(part, camera2, nozzle2, wanted2);

        System.out.println("N1/Upp1 moved-for    truth wanted-actual = " + truth1
                + "   term handed over = " + toXy(shot1.getShotDisplacement()));
        System.out.println("N2/Upp2 not-moved    truth wanted-actual = " + truth2
                + "   term handed over = " + toXy(shot2.getShotDisplacement()));

        // The residual is the truth, not a term, and it is blind to the injected camera error:
        // e1 = (0.250, 0.750) is on Upp1 and nowhere in this vector.
        assertEquals(truth1.getX(), shot1.getShotDisplacement().getX(), 1e-9);
        assertEquals(truth1.getY(), shot1.getShotDisplacement().getY(), 1e-9);
        assertEquals(truth2.getX(), shot2.getShotDisplacement().getX(), TOLERANCE_MM,
                "the residual recorded at capture must be wanted-actual");
        assertEquals(truth2.getY(), shot2.getShotDisplacement().getY(), TOLERANCE_MM);
        assertEquals(0.0, shot1.getShotDisplacement().getX(), 1e-9);
        assertEquals(0.0, shot1.getShotDisplacement().getY(), 1e-9);

        // The contract: what the analysis is handed is the residual of the shot, for both nozzles.
        assertEquals(truth1.getX(), toXy(shot1.getShotDisplacement()).getX(), TOLERANCE_MM,
                termMessage("N1/Upp1 (moved for)", truth1, shot1.getShotDisplacement()));
        assertEquals(truth1.getY(), toXy(shot1.getShotDisplacement()).getY(), TOLERANCE_MM,
                termMessage("N1/Upp1 (moved for)", truth1, shot1.getShotDisplacement()));
        assertEquals(truth2.getX(), toXy(shot2.getShotDisplacement()).getX(), TOLERANCE_MM,
                termMessage("N2/Upp2 (not moved for)", truth2, shot2.getShotDisplacement()));
        assertEquals(truth2.getY(), toXy(shot2.getShotDisplacement()).getY(), TOLERANCE_MM,
                termMessage("N2/Upp2 (not moved for)", truth2, shot2.getShotDisplacement()));
    }

    private String termMessage(String which, Location truth, Location term) {
        return which + ": the analysis must be handed the residual of its own shot. Truth (wanted - actual) is "
                + toXy(truth) + "mm but captureImageOnlyWithSettle() hands it " + toXy(term)
                + "mm, off by " + length2D(toXy(truth).subtract(toXy(term)))
                + "mm against a " + TOLERANCE_MM + "mm tolerance.";
    }

    /**
     * The rig, checked against its own constants: the centres and the runout really are the ones the
     * test computes with, and the two camera spacings really do disagree with the two nozzle offsets.
     */
    private void assertRig() throws Exception {
        ReferenceNozzleTipCalibration calibration
                = ((ReferenceNozzleTip) machine.getNozzleTips().get(0)).getCalibration();
        assertTrue(calibration.isCalibrated(nozzle1), "the batch needs calibrated tips");
        assertTrue(calibration.isCalibrated(nozzle2), "the batch needs calibrated tips");
        assertEquals(CENTRE_1_X, calibration.getCalibratedCameraOffset(nozzle1, camera1).getX(), 1e-9);
        assertEquals(CENTRE_1_Y, calibration.getCalibratedCameraOffset(nozzle1, camera1).getY(), 1e-9);
        assertEquals(CENTRE_2_X, calibration.getCalibratedCameraOffset(nozzle2, camera2).getX(), 1e-9);
        assertEquals(CENTRE_2_Y, calibration.getCalibratedCameraOffset(nozzle2, camera2).getY(), 1e-9);
        assertEquals(RUNOUT_1_AT_ZERO_X, calibration.getCalibratedOffset(nozzle1, 0.0).getX(), 1e-9);
        assertEquals(0.0, calibration.getCalibratedOffset(nozzle1, 0.0).getY(), 1e-9);
        assertEquals(RUNOUT_2_AT_ZERO_X, calibration.getCalibratedOffset(nozzle2, 0.0).getX(), 1e-9);
        assertEquals(0.0, calibration.getCalibratedOffset(nozzle2, 0.0).getY(), 1e-9);

        Location nozzleSpacing = NOZZLE_HEAD_OFFSET_2.subtract(NOZZLE_HEAD_OFFSET_1);
        Location cameraSpacing = CAMERA_HEAD_OFFSET_2.subtract(CAMERA_HEAD_OFFSET_1);
        assertEquals(0.300, nozzleSpacing.subtract(cameraSpacing).getX(), 1e-9);
        assertEquals(-0.150, nozzleSpacing.subtract(cameraSpacing).getY(), 1e-9);

        assertEquals(LengthUnit.Millimeters, PART_HEIGHT.getUnits());
        assertEquals(1.0, part.getHeight().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
    }

    private void buildHead(ReferenceMachine referenceMachine) throws Exception {
        nozzle1 = (ReferenceNozzle) referenceMachine.getDefaultHead().getNozzles().get(0);
        nozzle1.setName("N1");
        nozzle1.setHeadOffsets(NOZZLE_HEAD_OFFSET_1);

        nozzle2 = new ReferenceNozzle();
        nozzle2.setName("N2");
        nozzle2.setHeadOffsets(NOZZLE_HEAD_OFFSET_2);
        // The driver's axis defaults are only laid out for the machine as loaded, so the second
        // nozzle gets what createAxisMappingDefaults() would have given it: the shared X and Y plus a
        // Z and a C of its own.
        ReferenceControllerAxis sharedX = null;
        ReferenceControllerAxis sharedY = null;
        for (Axis axis : referenceMachine.getAxes()) {
            if (axis instanceof ReferenceControllerAxis && axis.getType() == Axis.Type.X) {
                sharedX = (ReferenceControllerAxis) axis;
            }
            if (axis instanceof ReferenceControllerAxis && axis.getType() == Axis.Type.Y) {
                sharedY = (ReferenceControllerAxis) axis;
            }
        }
        ReferenceControllerAxis axisZ = newAxis(referenceMachine, Axis.Type.Z, "zN2", referenceMachine);
        ReferenceControllerAxis axisC = newAxis(referenceMachine, Axis.Type.Rotation, "rotationN2", referenceMachine);
        ((AbstractHeadMountable) nozzle2).setAxisX(sharedX);
        ((AbstractHeadMountable) nozzle2).setAxisY(sharedY);
        ((AbstractHeadMountable) nozzle2).setAxisZ(axisZ);
        ((AbstractHeadMountable) nozzle2).setAxisRotation(axisC);
        axisZ.setSafeZoneLow(new Length(0, LengthUnit.Millimeters));
        axisZ.setSafeZoneLowEnabled(true);
        axisZ.setSafeZoneHigh(new Length(0, LengthUnit.Millimeters));
        axisZ.setSafeZoneHighEnabled(true);
        referenceMachine.getDefaultHead().addNozzle(nozzle2);

        ReferenceNozzleTip nozzleTip = (ReferenceNozzleTip) referenceMachine.getNozzleTips().get(0);
        nozzle1.addCompatibleNozzleTip(nozzleTip);
        nozzle2.addCompatibleNozzleTip(nozzleTip);
        nozzle1.setNozzleTip(nozzleTip);
        nozzle2.setNozzleTip(nozzleTip);
    }

    private ReferenceControllerAxis newAxis(ReferenceMachine referenceMachine, Axis.Type type, String name,
            ReferenceMachine driverOwner) throws Exception {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setName(name);
        axis.setDriver((NullDriver) driverOwner.getDefaultDriver());
        referenceMachine.addAxis(axis);
        return axis;
    }

    private void buildCameras(ReferenceMachine referenceMachine) throws Exception {
        // The one up-looking camera the default machine ships with becomes Upp1, and Upp2 joins it.
        // The names matter: VisionUtils.getBottomVisionCamera() pairs a nozzle with the up camera
        // carrying the same first number.
        camera1 = (SimulatedUpCamera) referenceMachine.getCameras().get(0);
        camera1.setName("Upp1");
        camera1.setHeadOffsets(CAMERA_HEAD_OFFSET_1);
        camera1.setUnitsPerPixel(new Location(LengthUnit.Millimeters, PIXEL_MM, PIXEL_MM, 0, 0));
        camera1.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        camera1.setSettleTimeMs(0);
        camera1.setErrorOffsets(CAMERA_ERROR_1);

        camera2 = new SimulatedUpCamera();
        camera2.setName("Upp2");
        camera2.setHeadOffsets(CAMERA_HEAD_OFFSET_2);
        camera2.setUnitsPerPixel(new Location(LengthUnit.Millimeters, PIXEL_MM, PIXEL_MM, 0, 0));
        camera2.setSettleMethod(AbstractSettlingCamera.SettleMethod.FixedTime);
        camera2.setSettleTimeMs(0);
        camera2.setErrorOffsets(CAMERA_ERROR_2);
        referenceMachine.addCamera(camera2);
    }

    private void buildPart() throws Exception {
        part = Configuration.get().getPart("R0805-1K");
        org.openpnp.model.Package pkg = part.getPackage();
        pkg.setBottomVisionSettings(ReferenceBottomVision.getDefault().getBottomVisionSettings());
        part.setHeight(PART_HEIGHT);
    }

    /**
     * Both tips calibrated, each against its own camera, with the circle this file names.
     *
     * The calibration cannot be <em>measured</em> here: a simulated machine has a tip that runs true,
     * and the offset it would report - the distance between the tip and a camera 100mm away - is far
     * outside the 0.5mm offset threshold a real calibration refuses above. So the very object that
     * {@code calibrate()} fits and stores in the tip's per-nozzle lookup is fitted here to twelve
     * points of a circle the test defines, through the same
     * {@code ModelBasedRunoutCameraOffsetCompensation} the default
     * ({@code ModelCameraOffsetAffine}) algorithm leaves behind. Nothing else about the calibration
     * is pretended: {@code isCalibrated()} answers true, {@code getCalibratedCameraOffset()} answers
     * the centre and {@code getCalibratedOffset(angle)} the runout, which is all the batch reads.
     */
    private void calibrateTips(ReferenceNozzleTip nozzleTip) throws Exception {
        storeRunoutModel(nozzleTip.getCalibration(), nozzle1, CENTRE_1_X, CENTRE_1_Y, RUNOUT_1_AT_ZERO_X);
        storeRunoutModel(nozzleTip.getCalibration(), nozzle2, CENTRE_2_X, CENTRE_2_Y, RUNOUT_2_AT_ZERO_X);
        nozzleTip.getCalibration().setEnabled(true);
    }

    private void storeRunoutModel(ReferenceNozzleTipCalibration calibration, ReferenceNozzle nozzle,
            double centreX, double centreY, double radiusX) throws Exception {
        List<Location> measured = new ArrayList<>();
        List<Location> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angleDegrees = i * 30.0;
            double angleRadians = Math.toRadians(angleDegrees);
            expected.add(new Location(LengthUnit.Millimeters,
                    Math.cos(angleRadians), Math.sin(angleRadians), 0, angleDegrees));
            measured.add(new Location(LengthUnit.Millimeters,
                    centreX + radiusX * Math.cos(angleRadians),
                    centreY + radiusX * Math.sin(angleRadians), 0, angleDegrees));
        }
        ReferenceNozzleTipCalibration.RunoutCompensation compensation
                = new ReferenceNozzleTipCalibration.ModelBasedRunoutCameraOffsetCompensation(measured, expected);
        Field lookupField = ReferenceNozzleTipCalibration.class.getDeclaredField("runoutCompensationLookup");
        lookupField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ReferenceNozzleTipCalibration.RunoutCompensation> lookup
                = (Map<String, ReferenceNozzleTipCalibration.RunoutCompensation>) lookupField.get(calibration);
        lookup.put(nozzle.getId(), compensation);
    }

    private Location centre1() {
        return new Location(LengthUnit.Millimeters, CENTRE_1_X, CENTRE_1_Y, 0, 0);
    }

    private Location centre2() {
        return new Location(LengthUnit.Millimeters, CENTRE_2_X, CENTRE_2_Y, 0, 0);
    }

    private Location runout1AtZero() {
        return new Location(LengthUnit.Millimeters, RUNOUT_1_AT_ZERO_X, 0, 0, 0);
    }

    private Location runout2AtZero() {
        return new Location(LengthUnit.Millimeters, RUNOUT_2_AT_ZERO_X, 0, 0, 0);
    }

    private Location height() {
        return new Location(LengthUnit.Millimeters, 0, 0, PART_HEIGHT.getValue(), 0);
    }

    private Location toXy(Location location) {
        Location millimetres = location.convertToUnits(LengthUnit.Millimeters);
        return millimetres.derive(millimetres.getX(), millimetres.getY(), 0.0, 0.0);
    }

    private double length2D(Location location) {
        Location millimetres = location.convertToUnits(LengthUnit.Millimeters);
        return millimetres.getLinearDistanceTo(0.0, 0.0);
    }
}
