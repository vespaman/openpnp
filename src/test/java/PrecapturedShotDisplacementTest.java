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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openpnp.model.FrameProvenance;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.PreCapturedPlacement;

/**
 * What a pre-captured shot says about where the nozzle actually was, which is the only thing it can say:
 * the analysis is handed the measured residual, nothing predicted. So the sign is the whole test:
 * subtracting what the shot reports from where the shot wanted to be has to land on where the nozzle was.
 */
public class PrecapturedShotDisplacementTest {

    private static PreCapturedPlacement shotAt(Location wanted) {
        return new PreCapturedPlacement(null, null, null, wanted, FrameProvenance.unknown());
    }

    @Test
    public void testSubtractingItFromTheWantedLocationGivesTheActualOne() {
        Location wanted = new Location(LengthUnit.Millimeters, 10.0, 20.0, 1.5, 0.0);
        Location actual = new Location(LengthUnit.Millimeters, 9.8, 20.1, 1.5, 0.0);
        PreCapturedPlacement shot = shotAt(wanted);
        shot.setActualAtCapture(actual);

        Location displacement = shot.getShotDisplacement();
        assertEquals(0.2, displacement.getX(), 1e-9);
        assertEquals(-0.1, displacement.getY(), 1e-9);
        assertEquals(actual.getX(), wanted.subtract(displacement).getX(), 1e-9);
        assertEquals(actual.getY(), wanted.subtract(displacement).getY(), 1e-9);
    }

    @Test
    public void testUnitsDoNotChangeTheAnswer() {
        Location wanted = new Location(LengthUnit.Millimeters, 25.4, 0.0, 0.0, 0.0);
        Location actual = new Location(LengthUnit.Inches, 0.99, 0.0, 0.0, 0.0);
        PreCapturedPlacement shot = shotAt(wanted);
        shot.setActualAtCapture(actual);

        Location displacement = shot.getShotDisplacement();
        assertEquals(LengthUnit.Millimeters, displacement.getUnits());
        assertEquals(25.4 - 0.99 * 25.4, displacement.getX(), 1e-9);
    }

    /**
     * A batch moves the head at one Z and one rotation per nozzle, so height and angle differ between
     * the shot and the nozzle without being things the vision can compensate. They belong to the shot
     * and to the limits, not to the displacement.
     */
    @Test
    public void testHeightAndAngleAreNotDisplacements() {
        Location wanted = new Location(LengthUnit.Millimeters, 10.0, 20.0, 2.0, 0.0);
        Location actual = new Location(LengthUnit.Millimeters, 10.0, 20.0, 1.0, 90.0);
        PreCapturedPlacement shot = shotAt(wanted);
        shot.setActualAtCapture(actual);

        Location displacement = shot.getShotDisplacement();
        assertEquals(0.0, displacement.getZ(), 1e-9);
        assertEquals(0.0, displacement.getRotation(), 1e-9);
    }

    @Test
    public void testNoPositionRecordedMeansNoOpinion() {
        PreCapturedPlacement shot = shotAt(new Location(LengthUnit.Millimeters, 1.0, 1.0, 0.0, 0.0));
        assertNull(shot.getShotDisplacement());
        shot.setActualAtCapture(new Location(LengthUnit.Millimeters, 1.0, 1.0, 0.0, 0.0));
        assertEquals(0.0, shot.getShotDisplacement().getX(), 1e-9);
        shot.setActualAtCapture(null);
        assertNull(shot.getShotDisplacement());
    }
}
