import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.logging.TraceRecorder;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeAsyncDriver;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;
import org.openpnp.machine.reference.driver.TcpCommunications;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Machine;
import org.openpnp.util.GcodeServer;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.writers.RollingFileWriter;

/**
 * A spike, not a gate: it makes one session of the real GcodeDriver against the in-process GcodeServer and records
 * it <em>twice</em> - as the debug log the corpus is made of, and as a trace written by
 * {@link TraceRecorder} - so that the two can be compared byte for byte by the C++ reader.
 *
 * <p>
 * That comparison is the claim this patch rests on. The trace format is defined and graded in the C++ repository,
 * where a trace read out of a recorded log is the only Java-written evidence there is. This makes a Java process
 * write the same file format directly, and if the two agree event for event then the definition is shared rather
 * than paraphrased. Run it with:
 *
 * <pre>
 *   mvn -o -B test -Dtest=TraceRecorderSpike \
 *       -Dvpnp.evidence=/tmp/vpnp-emitter \
 *       -Dvpnp.trace.oracle_sha=&lt;the revision under test&gt;
 * </pre>
 *
 * and it leaves {@code log_probe.txt}, {@code machine.xml} and {@code trace.jsonl} in the evidence directory.
 *
 * <p>
 * It is deliberately not named {@code *Test}: it writes files outside the build directory and turns TRACE logging on
 * process-wide, which is what a spike does and what {@code mvn test} should not do on its own. Run it by name with
 * {@code -Dtest=TraceRecorderSpike} and it will.
 *
 * <p>
 * The system property {@code vpnp.evidence} says where the evidence goes; it defaults to {@code /tmp/vpnp-emitter}.
 */
public class TraceRecorderSpike {
    private static File evidenceDirectory() {
        String given = System.getProperty("vpnp.evidence");
        File dir = given == null ? new File("/tmp/vpnp-emitter") : new File(given);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create the evidence directory " + dir);
        }
        return dir;
    }

    @Test
    public void recordOneSessionTwice() throws Exception {
        File evidence = evidenceDirectory();
        File logFile = new File(evidence, "log_probe.txt");
        if (logFile.exists() && !logFile.delete()) {
            throw new IllegalStateException("The previous log is in the way: " + logFile);
        }

        // Main.configureLogging()'s own pattern and writer, so that these lines are the shape the corpus has and
        // the C++ reader's marker matches them. Level.TRACE is what the WriterThread and ReaderThread need.
        Configurator.currentConfig()
                .writer(new RollingFileWriter(logFile.getAbsolutePath(), 10))
                .formatPattern("{date:yyyy-MM-dd HH:mm:ss.SSS} {class_name} {level}: {message}")
                .level(Level.TRACE)
                .activate();

        GcodeServer server = new GcodeServer();
        server.addCommandResponse("G21 ; Set millimeters mode", "ok");
        server.addCommandResponse("G90 ; Set absolute positioning mode", "ok");
        server.addCommandResponse("M82 ; Set absolute mode for extruder", "ok");
        server.addCommandResponse("G28 ; Home all axes", "ok");
        server.addCommandResponse("M400 ; Wait for moves to complete before returning", "ok");
        server.addCommandResponse("READ A1", "read:a1:497\nok");
        // The in-process emulator answers M114 from its own notion of where it is, which is not this
        // driver's axes; a static report is enough for the recorder, which cares about the bytes on the
        // wire and not about where the simulated head ended up.
        server.addCommandResponse("M114 ; get position", "ok C: X:0.0000 Y:0.0000");

        File workingDirectory = java.nio.file.Files.createTempDirectory("openpnp-trace-spike").toFile();
        File configurationDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(configurationDirectory);
        Configuration.get().load();
        // Written back the way the other machine tests do it, so that the bytes hashed into the stamp are the bytes
        // this run ran on - and so that the config copied beside the log is that same file.
        Configuration.get().save();

        // The async driver, which is what pn52 runs and what the corpus logs are made of: its
        // WriterThread is the line shape the C++ reader matches, and it is where this patch's send seam is.
        // The synchronous GcodeDriver logs `>> <cmd>, <timeout>` at DEBUG instead, which is a different
        // sentence about the wire and is deliberately not read as one.
        GcodeAsyncDriver driver = new GcodeAsyncDriver();
        driver.createDefaults();
        driver.setConnectionKeepAlive(false);
        driver.setCommunicationsType(CommunicationsType.tcp);
        TcpCommunications tcp = (TcpCommunications) driver.getCommunications();
        tcp.setIpAddress("localhost");
        tcp.setPort(server.getListenerPort());
        driver.setConnectWaitTimeMilliseconds(0);
        driver.setTimeoutMilliseconds(500);

        // The command table a real config has and `createDefaults()` does not: the async driver asks the
        // controller where it is at the end of homing. Spelled the way the corpus spells it.
        driver.setCommand(null, CommandType.GET_POSITION_COMMAND, "M114 ; get position");
        driver.setCommand(null, CommandType.POSITION_REPORT_REGEX,
                "^.*X:(?<X>-?\\d+\\.\\d+) Y:(?<Y>-?\\d+\\.\\d+).*$");

        ReferenceMachine referenceMachine = (ReferenceMachine) Configuration.get().getMachine();
        driver.setName("GcodeDriver");
        while (referenceMachine.getDrivers().size() > 0) {
            referenceMachine.removeDriver(referenceMachine.getDrivers().get(0));
        }
        referenceMachine.addDriver(driver);

        Machine machine = Configuration.get().getMachine();
        Actuator actuator = new ReferenceActuator();
        actuator.setName("A1");
        machine.addActuator(actuator);
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_COMMAND, "READ A1");
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_REGEX, "read:a1:(?<Value>-?\\d+)");

        // Saved again, after the driver and its commands exist: `config_hash` is the hash of the config the run
        // ran on, so a session whose driver was invented in code has to write it back before it can be attested.
        Configuration.get().save();

        File machineXml = new File(configurationDirectory, "machine.xml");
        File besideTheLog = new File(evidence, "machine.xml");
        java.nio.file.Files.copy(machineXml.toPath(), besideTheLog.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Map<String, String> stamp = new LinkedHashMap<>();
        stamp.put("config_hash", TraceRecorder.sha256(machineXml));
        stamp.put("oracle_sha", System.getProperty(TraceRecorder.ORACLE_SHA_PROPERTY));
        TraceRecorder.open(new File(evidence, "trace.jsonl"),
                "openpnp@" + System.getProperty(TraceRecorder.ORACLE_SHA_PROPERTY, "unknown-at-commit")
                        + " (recorded in-process)",
                "the wire of one GcodeServer session, recorded by the app itself", stamp);

        // A session: connect and enable, home, read an actuator, disable. Every one of those goes out through the
        // writer thread that the recorder is hooked into.
        machine.setEnabled(true);
        machine.home();
        Nozzle n1 = machine.getDefaultHead().getNozzle("N1");
        for (double[] point : new double[][] {{10.0, 20.0}, {40.0, 25.0}, {12.5, -3.25}}) {
            Location target = new Location(LengthUnit.Millimeters, point[0], point[1], 0, 0);
            machine.execute(() -> {
                n1.moveTo(target, 10000.);
                return Boolean.TRUE;
            });
            String each = machine.execute(() -> actuator.read());
            System.out.println("SPIKE   read at " + point[0] + "," + point[1] + " = " + each);
        }
        // A payload with trailing tabs, which is what the corpus's `M809\t\t` family is: the recorder has to
        // escape a tab the same way the C++ writer does, and this is the session that proves it did.
        Actuator tabbed = new ReferenceActuator();
        tabbed.setName("A2");
        machine.addActuator(tabbed);
        driver.setCommand(tabbed, CommandType.ACTUATOR_READ_COMMAND, "M809\tREAD\tEND");
        driver.setCommand(tabbed, CommandType.ACTUATOR_READ_REGEX, "read:a2:(?<Value>-?\\d+)");
        server.addCommandResponse("M809\tREAD\tEND", "read:a2:512\nok");
        String withTabs = machine.execute(() -> tabbed.read());

        String read = machine.execute(() -> actuator.read());
        machine.setEnabled(false);
        TraceRecorder.close();

        System.out.println("SPIKE evidence in " + evidence.getAbsolutePath());
        System.out.println("SPIKE   log     " + logFile.length() + " bytes");
        System.out.println("SPIKE   trace   " + new File(evidence, "trace.jsonl").length() + " bytes");
        System.out.println("SPIKE   actuator read " + read + ", tabbed actuator read " + withTabs);
    }
}
