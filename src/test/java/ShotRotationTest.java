import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;

import com.google.common.io.Files;

/**
 * A shot has to say the angle it was taken at (E7). The analysis reads the angle the part shows in the
 * picture against the angle the nozzle was told to hold it at, so a shot recorded at 0 while the part is
 * held at -90 does not describe its own frame: the difference between the two numbers is handed to the
 * placement as if it were the part's error.
 *
 * <p>That angle is not the placement angle either - it is the placement angle as the board transforms it,
 * which is the number the nozzle pre-rotation step is given on the way to the cameras, and therefore the
 * number a batch is really standing at when it exposes.</p>
 */
public class ShotRotationTest {

    private BoardLocation boardAt(double rotationDegrees) {
        Board board = new Board();
        board.setName("rotation-test");
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 40, 50, -10, rotationDegrees));
        boardLocation.setGlobalSide(Side.Top);
        return boardLocation;
    }

    private Placement placementAt(double rotationDegrees) {
        Placement placement = new Placement("A");
        placement.setLocation(new Location(LengthUnit.Millimeters, 10, 10, 0, rotationDegrees));
        return placement;
    }

    /**
     * A board turned by 30° is a machine coordinate system of its own: a placement asking for 90° is
     * asking for the part to be held at 120° by the nozzle, and that is the angle the picture is taken
     * at. The head move of a parallel batch used to ask the placement itself instead, which on a board
     * that is not square to the machine turns every part of the batch wrong by the board's own angle.
     */
    @Test
    public void testTheBoardTransformsTheAngle() {
        BoardLocation boardLocation = boardAt(30);

        assertEquals(120., ReferenceBottomVision.wantedRotationOf(boardLocation, placementAt(90)), 1e-9,
                "the placement angle as the board turns it");
        assertEquals(30., ReferenceBottomVision.wantedRotationOf(boardLocation, placementAt(0)), 1e-9,
                "a placement square to its board is still turned by the board");
        assertEquals(0., ReferenceBottomVision.wantedRotationOf(null, placementAt(0)), 1e-9,
                "and with no board there is nothing to transform it");
    }

    /**
     * The folding matters as much as the addition: the analysis compares angles it has been told to
     * understand in -180..180, so 200° has to come out as -160° rather than as a number the comparison
     * will read as a 200° error.
     */
    @Test
    public void testTheAngleIsFoldedIntoAWholeTurn() {
        BoardLocation boardLocation = boardAt(0);

        assertEquals(-160., ReferenceBottomVision.wantedRotationOf(boardLocation, placementAt(200)), 1e-9,
                "200° is the same heading as -160°");
        assertEquals(-90., ReferenceBottomVision.wantedRotationOf(boardAt(180), placementAt(90)), 1e-9,
                "a board turned half way round, a placement at 90°, held at -90°");
    }

    private ReferenceMachine setUpMachine() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/machine.xml"),
                new File(workingDirectory, "machine.xml"));
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/packages.xml"),
                new File(workingDirectory, "packages.xml"));
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/JobProcessorTest/parts.xml"),
                new File(workingDirectory, "parts.xml"));
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        Configuration.get().save();
        return (ReferenceMachine) Configuration.get().getMachine();
    }

    /**
     * Whether the shot is taken at the placement angle at all is the part's own decision, on top of the
     * machine's: pre-rotate photographs the part already turned and post-rotate photographs it unturned
     * and rotates the nozzle afterwards, so for a post-rotate part 0 is not a lost number, it is the
     * truth. The location the alignment announces for a part carries whichever it is, because that is
     * the location the nozzle pre-rotation step drives the nozzle to.
     */
    @Test
    public void testAPostRotatePartIsPhotographedUnturned() throws Exception {
        ReferenceMachine machine = setUpMachine();
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
        vision.setPreRotate(true);

        Part preRotatePart = Configuration.get().getPart("R0805-1K");
        Nozzle nozzle = machine.getDefaultHead().getNozzles().get(0);
        BoardLocation boardLocation = boardAt(30);
        Placement placement = placementAt(90);

        assertEquals(120., vision.shotRotationOf(preRotatePart, boardLocation, placement), 1e-9,
                "pre-rotate holds the part at its placement angle");
        Camera camera = org.openpnp.util.VisionUtils.getBottomVisionCamera(nozzle);
        assertTrue(camera != null, "the nozzle has a bottom camera to be announced to");
        Location announced = vision.getLocation(preRotatePart, boardLocation, placement, nozzle);
        assertEquals(120., announced.getRotation(), 1e-9,
                "and the alignment announces that angle, which is what the pre-rotation step drives to");

        Part postRotatePart = Configuration.get().getPart("R0603-1K");
        BottomVisionSettings settings = new BottomVisionSettings();
        settings.setEnabled(true);
        settings.setPreRotateUsage(PreRotateUsage.AlwaysOff);
        BottomVisionSettings inherited = vision.getInheritedVisionSettings(postRotatePart);
        if (inherited != null) {
            settings.setPipeline(inherited.getPipeline().clone());
        }
        postRotatePart.setBottomVisionSettings(settings);

        assertEquals(0., vision.shotRotationOf(postRotatePart, boardLocation, placement), 1e-9,
                "post-rotate is the choice to photograph the part unturned");
        Location postAnnounced = vision.getLocation(postRotatePart, boardLocation, placement, nozzle);
        assertEquals(0., postAnnounced.getRotation(), 1e-9, "and it says so in the location it announces");
        assertTrue(vision.usesPreRotate(preRotatePart), "the control part is still pre-rotate");
    }
}
