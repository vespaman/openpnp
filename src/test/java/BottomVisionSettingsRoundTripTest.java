import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.FileUtils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Machine;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.CropCentered;
import org.openpnp.vision.pipeline.stages.ImageCapture;

import com.google.common.io.Files;

/**
 * Settings that a person configures in a wizard and that the job reads at runtime, i.e. section 8 of
 * docs/design/parallel-bottom-vision/MACHINE-TEST.md, without the machine: the parallel alignment
 * switches and the displacement limits of the machine, and a pipeline that carries the
 * {@link CropCentered} stage.
 * 
 * The round trip is the part worth testing rather than the setters. A property that is settable but
 * never written to XML is configured at every start of the machine without anybody noticing, and a
 * stage class that the writer does not know is dropped from the pipeline on the next save, which
 * loses the pipeline silently rather than loudly.
 */
public class BottomVisionSettingsRoundTripTest {
    static File workingDirectory;

    @BeforeAll
    public static void setup() throws Exception {
        workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        // Save back the migrated defaults so that each test starts from them.
        Configuration.get().save();
    }

    @BeforeEach
    public void before() throws Exception {
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    private static double mm(Length length) {
        return length.convertToUnits(LengthUnit.Millimeters).getValue();
    }

    @Test
    public void testParallelAlignmentAndDisplacementSettingsRoundTrip() throws Exception {
        Machine machine = Configuration.get().getMachine();
        machine.setParallelAlignmentEnabled(false);
        machine.setDisplacementEnabled(true);
        machine.setBalanceLevel(new Length(1.25, LengthUnit.Millimeters));
        machine.setDisplacementMaxDistance(new Length(3.3, LengthUnit.Millimeters));
        machine.setDisplacementMaxZDiff(new Length(0.4, LengthUnit.Millimeters));
        machine.setDisplacementMaxRotation(7.5);
        Configuration.get().save();

        String xml = FileUtils.readFileToString(new File(workingDirectory, "machine.xml"),
                StandardCharsets.UTF_8);
        assertTrue(xml.contains("<parallel-alignment-enabled>false</parallel-alignment-enabled>"),
                "machine.xml has to carry the switch and not only the running machine, got: "
                        + xml.replaceAll("\\s+", " "));

        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Machine reloaded = Configuration.get().getMachine();
        assertFalse(reloaded.isParallelAlignmentEnabled(),
                "parallel alignment has to come back off, otherwise the classic path cannot be turned off");
        assertTrue(reloaded.isDisplacementEnabled(), "displacement has to come back on");
        assertEquals(1.25, mm(reloaded.getBalanceLevel()), 1e-9);
        assertEquals(3.3, mm(reloaded.getDisplacementMaxDistance()), 1e-9);
        assertEquals(0.4, mm(reloaded.getDisplacementMaxZDiff()), 1e-9);
        assertEquals(7.5, reloaded.getDisplacementMaxRotation(), 1e-9);
    }

    protected static int insertionPoint(CvPipeline pipeline) {
        for (int i = 0; i < pipeline.getStages().size(); i++) {
            if (pipeline.getStages().get(i) instanceof ImageCapture) {
                return i + 1;
            }
        }
        return 0;
    }

    @Test
    public void testCropStageRoundTripsThroughVisionSettings() throws Exception {
        BottomVisionSettings settings = ReferenceBottomVision.getDefault().getBottomVisionSettings();
        CvPipeline pipeline = settings.getPipeline();
        assertNotNull(pipeline, "the stock bottom vision has to have a pipeline to extend");
        int n = pipeline.getStages().size();

        CropCentered crop = new CropCentered();
        crop.setName("crop");
        crop.setMinimum(200);
        crop.setMargin(1);
        pipeline.insert(crop, insertionPoint(pipeline));
        settings.setPipeline(pipeline);
        Configuration.get().save();

        String xml = FileUtils.readFileToString(new File(workingDirectory, "vision-settings.xml"),
                StandardCharsets.UTF_8);
        assertTrue(xml.contains("stages.CropCentered"),
                "the stage has to be named in vision-settings.xml, or the next load loses it");

        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        CvPipeline reloadedPipeline = ReferenceBottomVision.getDefault().getBottomVisionSettings()
                .getPipeline();
        assertEquals(n + 1, reloadedPipeline.getStages().size(),
                "the pipeline has to come back with the stage and with nothing else changed");
        CvStage reloaded = null;
        for (CvStage stage : reloadedPipeline.getStages()) {
            if ("crop".equals(stage.getName())) {
                reloaded = stage;
            }
        }
        assertNotNull(reloaded, "the stage has to come back by its name");
        assertTrue(reloaded instanceof CropCentered,
                "the stage has to come back as the class it was written as, not as some other");
        assertEquals(200, ((CropCentered) reloaded).getMinimum(), 1e-9);
        assertEquals(1, ((CropCentered) reloaded).getMargin(), 1e-9);
        assertEquals(0, ((CropCentered) reloaded).getWidth(), 1e-9);
    }
}
