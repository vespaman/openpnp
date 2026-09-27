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
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.vision.pipeline.CvPipeline;

/**
 * The displacement of a picture taken without moving the nozzle onto the shot location is kept per
 * shot on the pipeline, never on the camera: the nozzles are aligned in parallel through their own
 * cameras, and a displacement left behind on a camera by one of them would be applied to the next.
 */
public class ShotDisplacementTest {

    @Test
    public void testDisplacementIsPerShotIndex() {
        CvPipeline pipeline = new CvPipeline();
        Location displacement = new Location(LengthUnit.Millimeters, 0.2, -0.1, 0, 0);

        assertNull(pipeline.getShotDisplacement(0));

        pipeline.setShotDisplacement(1, displacement);
        assertEquals(displacement, pipeline.getShotDisplacement(1));
        assertNull(pipeline.getShotDisplacement(0));

        pipeline.setShotDisplacement(1, null);
        assertNull(pipeline.getShotDisplacement(1));
    }

    @Test
    public void testDisplacementIsClearedWithThePipeline() {
        CvPipeline pipeline = new CvPipeline();
        pipeline.setShotDisplacement(0, new Location(LengthUnit.Millimeters, 0.2, 0.1, 0, 0));
        pipeline.resetReusedPipeline();
        assertNull(pipeline.getShotDisplacement(0));
    }

    @Test
    public void testDisplacementIsNotSharedBetweenPipelines() {
        CvPipeline pipeline1 = new CvPipeline();
        CvPipeline pipeline2 = new CvPipeline();

        pipeline1.setShotDisplacement(0, new Location(LengthUnit.Millimeters, 0.2, 0.1, 0, 0));

        assertNull(pipeline2.getShotDisplacement(0));
    }
}
