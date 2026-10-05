package org.openpnp.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.openpnp.model.FrameProvenance;
import org.openpnp.util.OpenCvUtils;

/**
 * The {@code onFailure} frame dump: keep the frames in memory, and only write them when a part has
 * actually been refused.
 *
 * <p>What matters about this mode is not that the frames arrive - the ordinary dump already does that -
 * but that nothing is written while the parts are being accepted, because a corpus written on every
 * cycle touches the disk the log goes to and lengthens the cycle it is meant to observe. So the tests
 * are mostly about files that are <em>not</em> there.</p>
 */
public class FrameDumpFailureModeTest {

    @AfterEach
    public void clearTheCommandline() {
        System.clearProperty(FrameDump.PROPERTY);
        System.clearProperty(FrameDump.KEEP_PROPERTY);
        FrameDump.releaseRetained();
    }

    @Test
    public void onFailureSaysHowAndOptionallyWhere() throws Exception {
        System.setProperty(FrameDump.PROPERTY, "onFailure");
        assertEquals(FrameDump.Mode.OnFailure, FrameDump.mode(null));
        // Says how, not where: with nothing else to go on there is no override directory, and the
        // settings (or the configuration) are the ones that say where.
        assertEquals(null, FrameDump.overrideDirectory());

        File directory = Files.createTempDirectory("frame-dump-on-failure").toFile();
        System.setProperty(FrameDump.PROPERTY, "onFailure:"+directory.getAbsolutePath());
        assertEquals(FrameDump.Mode.OnFailure, FrameDump.mode(null));
        assertEquals(directory.getAbsolutePath(), FrameDump.overrideDirectory());

        System.setProperty(FrameDump.PROPERTY, directory.getAbsolutePath());
        assertEquals(FrameDump.Mode.Every, FrameDump.mode(null));
        assertEquals(directory.getAbsolutePath(), FrameDump.overrideDirectory());

        System.clearProperty(FrameDump.PROPERTY);
        assertEquals(FrameDump.Mode.Off, FrameDump.mode(null));
        assertFalse(FrameDump.isEnabled(null));
    }

    @Test
    public void nothingIsWrittenUntilSomethingIsRefused() throws Exception {
        Class.forName(OpenCvUtils.class.getName());
        File directory = Files.createTempDirectory("frame-dump-on-failure").toFile();
        System.setProperty(FrameDump.PROPERTY, "onFailure:"+directory.getAbsolutePath());

        long offeredBefore = FrameDump.getFramesOffered();
        Mat frame = frame(64, 48);
        try {
            FrameDump.writeIfNeeded(null, frame, FrameProvenance.ringing(1000, 1012), null, null, null,
                    12.5);
            assertEquals(1, FrameDump.getFramesRetained());
            assertEquals(offeredBefore, FrameDump.getFramesOffered(),
                    "an accepted part must not put a file on disk");
            assertEquals(0, listPngs(directory).length);

            assertEquals(1, FrameDump.writeRetained("Part X width too small"));
            assertTrue(FrameDump.awaitIdle(10000), "the remembered frame should have been written");
            assertEquals(0, FrameDump.getFramesRetained());
            File[] written = listPngs(directory);
            assertEquals(1, written.length);

            // The name has to survive being read back, or the file is a picture and not evidence.
            FrameDump.Name name = FrameDump.parse(written[0].getName());
            assertEquals(1012, name.capturedAtMs);
            assertEquals(12.5, name.rotation, 0.001);

            // And the frames are gone, not merely unwritten: a refusal must not be able to write the
            // same evidence twice.
            assertEquals(0, FrameDump.writeRetained("again"));
        }
        finally {
            frame.release();
            delete(directory);
        }
    }

    @Test
    public void onlyTheMostRecentFramesAreRemembered() throws Exception {
        Class.forName(OpenCvUtils.class.getName());
        File directory = Files.createTempDirectory("frame-dump-on-failure").toFile();
        System.setProperty(FrameDump.PROPERTY, "onFailure:"+directory.getAbsolutePath());
        System.setProperty(FrameDump.KEEP_PROPERTY, "2");

        try {
            for (int i = 1; i <= 5; i++) {
                Mat frame = frame(32, 32);
                FrameDump.writeIfNeeded(null, frame, FrameProvenance.ringing(1000, 1000+i), null, null,
                        null, 0);
                frame.release();
            }
            assertEquals(2, FrameDump.getFramesRetained());
            assertEquals(2, FrameDump.writeRetained("refused"));
            assertTrue(FrameDump.awaitIdle(10000));
            // The two that are left are the two most recent.
            File[] written = listPngs(directory);
            assertEquals(2, written.length);
            for (File file : written) {
                assertTrue(FrameDump.parse(file.getName()).capturedAtMs > 1003,
                        "the frames that were kept should be the last ones, not the first: "
                                +file.getName());
            }
        }
        finally {
            delete(directory);
        }
    }

    @Test
    public void releasedFramesAreNotWritten() throws Exception {
        Class.forName(OpenCvUtils.class.getName());
        File directory = Files.createTempDirectory("frame-dump-on-failure").toFile();
        System.setProperty(FrameDump.PROPERTY, "onFailure:"+directory.getAbsolutePath());
        Mat frame = frame(16, 16);
        try {
            FrameDump.writeIfNeeded(null, frame, FrameProvenance.ringing(1000, 1001), null, null, null,
                    0);
            FrameDump.releaseRetained();
            assertEquals(0, FrameDump.writeRetained("nothing left"));
            assertEquals(0, listPngs(directory).length);
        }
        finally {
            frame.release();
            delete(directory);
        }
    }

    private static Mat frame(int columns, int rows) {
        Mat mat = new Mat(rows, columns, org.opencv.core.CvType.CV_8UC1);
        mat.setTo(new org.opencv.core.Scalar(128));
        return mat;
    }

    private static File[] listPngs(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".png"));
        return files == null ? new File[0] : files;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
