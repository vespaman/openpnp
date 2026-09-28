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
import org.openpnp.machine.reference.ReferenceCameraBatchOperation;

/**
 * The parallel alignment pre-captures inside the camera batch of the alignment step, so a batch can
 * be open while another one is closed. The name of a batch is only there to say in the log who
 * forgot to close it, which is worth nothing if closing an inner batch renames the one around it.
 */
public class CameraBatchNestingTest {

    @Test
    public void testClosingAnInnerBatchRestoresTheOuterName() throws Exception {
        ReferenceCameraBatchOperation batch = new ReferenceCameraBatchOperation();

        batch.startBatchOperation("align step");
        batch.startBatchOperation("parallel-align-precapture");
        assertEquals("parallel-align-precapture", batch.getCurrentBatchName());

        batch.endBatchOperation("parallel-align-precapture");
        assertEquals("align step", batch.getCurrentBatchName());

        batch.endBatchOperation("align step");
        assertNull(batch.getCurrentBatchName());
    }

    @Test
    public void testBatchesAfterEachOther() throws Exception {
        ReferenceCameraBatchOperation batch = new ReferenceCameraBatchOperation();

        batch.startBatchOperation("fid");
        batch.endBatchOperation("fid");
        assertNull(batch.getCurrentBatchName());

        batch.startBatchOperation("align step");
        assertEquals("align step", batch.getCurrentBatchName());
        batch.endBatchOperation("align step");
        assertNull(batch.getCurrentBatchName());
    }
}
