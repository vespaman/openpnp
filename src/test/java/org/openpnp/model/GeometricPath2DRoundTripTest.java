package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An outline that the application writes has to read back as the same outline.
 *
 * {@link GeometricPath2D} stores itself as two flat lists - one segment type per segment, then that segment's
 * coordinates - and {@link GeometricPath2D#commit()} rebuilds the path from them after deserialization. While the
 * SEG_MOVETO case fell through into SEG_LINETO, every move also drew a line and consumed the next segment's two
 * coordinates, so the walk drifted past the end of the point list and reading any board file that carried a
 * &lt;profile&gt; failed with an IndexOutOfBoundsException. Drawing an outline is a GUI action, which is why this was
 * latent: a board saved without one writes no &lt;profile&gt; at all and loads happily.
 *
 * Stands in org.openpnp.model because GeometricPath2D's constructors are package-private (:50-104), so a test
 * outside it cannot build a path with curves in it. Writes and reads go through the application's own serializer,
 * the way Configuration.saveBoard (:1534) and Configuration.loadBoard (:1547) do, because it is the @Persist and
 * @Commit hooks - not a hand-written document - that this test is about.
 */
public class GeometricPath2DRoundTripTest {
    static File workingDirectory;

    @BeforeEach
    public void setup() throws Exception {
        workingDirectory = Files.createTempDirectory("openpnp-geometric-path-test").toFile();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
    }

    @Test
    public void testDerivedRectangleOutlineRoundTrips() throws Exception {
        // The outline a board gets when someone accepts the rectangle implied by its own dimensions:
        // PlacementsHolder.getProfile() (:161) synthesises it, and setProfile is what makes the board write it out.
        Board board = newBoard("derived");
        board.setDimensions(new Location(LengthUnit.Millimeters, 50.0, 25.0, 0.0, 0.0));
        board.setProfile(board.getProfile());

        Board read = writeThenRead(board);

        GeometricPath2D profile = read.getProfile();
        assertNotNull(profile, "the board read back with no outline at all");
        assertEquals(segments(board.getProfile()), segments(profile),
                "the outline read back is a different path than the one written");
        assertEquals(LengthUnit.Millimeters, profile.getUnits(), "the outline lost the units it was written in");
    }

    @Test
    public void testCurvedOutlineRoundTrips() throws Exception {
        // The segments whose own cases were always correct - SEG_QUADTO, SEG_CUBICTO - are corrupted anyway while
        // the move before them eats two more coordinates than it owns.
        Board board = newBoard("curves");
        board.setDimensions(new Location(LengthUnit.Millimeters, 50.0, 25.0, 0.0, 0.0));
        GeometricPath2D path = new GeometricPath2D();
        path.moveTo(0.0, 0.0);
        path.lineTo(50.0, 0.0);
        path.quadTo(40.0, 30.0, 30.0, 25.0);
        path.curveTo(20.0, 20.0, 10.0, 22.0, 4.0, 18.0);
        path.closePath();
        board.setProfile(path);

        Board read = writeThenRead(board);

        assertEquals(segments(path), segments(read.getProfile()));
    }

    // ------------------------------------------------------------------ helpers

    private Board newBoard(String name) throws Exception {
        File file = new File(workingDirectory, name + ".board.xml");
        Board board = new Board(file);
        board.setName(name);
        return board;
    }

    /**
     * Writes with the application's own writer and reads with its own reader, so both the @Persist that flattens
     * the path and the @Commit that rebuilds it are on the path under test.
     */
    private Board writeThenRead(Board board) throws Exception {
        Configuration.get().saveBoard(board);
        return Configuration.createSerializer().read(Board.class, board.getFile());
    }

    /**
     * The path as one line per segment, which is what a failure should print: the defect is a segment appearing
     * where none was written, or a coordinate belonging to a later segment, and both are visible here.
     *
     * Double.toString rather than a format string because it always writes a dot, whatever the machine's locale
     * says about numbers.
     */
    private static List<String> segments(Shape shape) {
        List<String> lines = new ArrayList<>();
        double[] coords = new double[6];
        PathIterator iterator = shape.getPathIterator(null);
        for (int n = 0; !iterator.isDone(); iterator.next(), n++) {
            StringBuilder line = new StringBuilder();
            line.append(n).append(' ').append(segmentName(iterator.currentSegment(coords)));
            for (int i = 0; i < 6; i++) {
                line.append(' ').append(Double.toString(coords[i]));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String segmentName(int type) {
        switch (type) {
            case PathIterator.SEG_MOVETO:
                return "moveto";
            case PathIterator.SEG_LINETO:
                return "lineto";
            case PathIterator.SEG_QUADTO:
                return "quadto";
            case PathIterator.SEG_CUBICTO:
                return "curveto";
            case PathIterator.SEG_CLOSE:
                return "close";
            default:
                return "unknown";
        }
    }
}
