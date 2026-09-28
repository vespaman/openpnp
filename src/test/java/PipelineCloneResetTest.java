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

import org.junit.jupiter.api.Test;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.stages.Threshold;

/**
 * Bottom vision gives every worker thread a clone of the pipeline of the vision settings, and the
 * pipeline of the settings is shared with the GUI and with the other threads. Cloning for a worker
 * thread must therefore leave the pipeline it clones alone, unlike cloning a pipeline to save or to
 * hand it to an editor.
 */
public class PipelineCloneResetTest {

    private static final String PIPELINE = ""
            + "<cv-pipeline>"
            + "   <stages>"
            + "      <cv-stage class=\"org.openpnp.vision.pipeline.stages.Threshold\" name=\"threshold\" threshold=\"100\" auto=\"false\" invert=\"false\"/>"
            + "      <cv-stage class=\"org.openpnp.vision.pipeline.stages.ParameterNumeric\" name=\"pDetail\""
            + "             stage-name=\"threshold\" property-name=\"threshold\" default-value=\"20\" numeric-type=\"Integer\"/>"
            + "   </stages>"
            + "</cv-pipeline>";

    private static int thresholdOf(CvPipeline pipeline) {
        return ((Threshold) pipeline.getStage("threshold")).getThreshold();
    }

    @Test
    public void testCloneWithoutResetLeavesTheSourceAlone() throws Exception {
        CvPipeline pipeline = new CvPipeline(PIPELINE);
        // What a parameter stage leaves behind once the pipeline has been processed.
        ((Threshold) pipeline.getStage("threshold")).setThreshold(150);

        CvPipeline clone = pipeline.cloneWithoutReset();

        assertEquals(150, thresholdOf(pipeline), "the cloned pipeline must not be reset");
        assertEquals(150, thresholdOf(clone), "the clone is the pipeline as it was");
    }

    @Test
    public void testCloneStillResetsTheSource() throws Exception {
        CvPipeline pipeline = new CvPipeline(PIPELINE);
        ((Threshold) pipeline.getStage("threshold")).setThreshold(150);

        CvPipeline clone = pipeline.clone();

        assertEquals(20, thresholdOf(pipeline), "a clone for saving must not keep the runtime value");
        assertEquals(20, thresholdOf(clone));
    }

    @Test
    public void testClonesAreIndependent() throws Exception {
        CvPipeline pipeline = new CvPipeline(PIPELINE);
        CvPipeline first = pipeline.cloneWithoutReset();
        CvPipeline second = pipeline.cloneWithoutReset();

        ((Threshold) first.getStage("threshold")).setThreshold(40);

        assertEquals(100, thresholdOf(pipeline));
        assertEquals(100, thresholdOf(second));
    }
}
