import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.openpnp.logging.TraceRecorder;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.SimulationModeMachine;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.writers.RollingFileWriter;

/**
 * A spike, not a gate: it runs the reference's own whole job — the one {@code SampleJobTest} runs, against the
 * in-process {@code GcodeServer} — and records the wire as a trace, so that the question {@code M0}'s third clause
 * depends on can be asked here instead of on a machine:
 *
 * <p>
 * <em>is a reference job's outbound command stream reproducible at all?</em> Three recorded job traces are M0's
 * evidence, and every one of them so far has had to come from {@code pn52}. This produces one on this host. Run it
 * twice, into two directories, and compare the traces: if the two agree byte for byte then a job trace can be a
 * golden, and if they do not then the differences say which part of a job is not reproducible and M0's clause needs
 * rewording. Either answer is worth having before someone spends machine time recording the third trace.
 *
 * <p>
 * The job loop is {@code SampleJobTest}'s, copied rather than called, so that the recorder can be opened between the
 * configuration being loaded and the machine being enabled — that is the only place where {@code config_hash} can
 * name the bytes the run is about to run on. The machine is the imperfect one the test ships with (bad
 * non-squareness, nozzle runout, camera lag, jerk-limited planning), which is the more interesting machine to ask,
 * and it runs in quasi-real-time: about eighty seconds.
 *
 * <p>
 * It is deliberately not named {@code *Test}, for the same reason {@link TraceRecorderSpike} is not: it writes files
 * outside the build directory and turns TRACE logging on process-wide.
 *
 * <pre>
 *   mvn -o -B test -Dtest=TraceJobSpike \
 *       -Dvpnp.evidence=/tmp/vpnp-job-a \
 *       -Dvpnp.trace.oracle_sha=&lt;the revision under test&gt;
 * </pre>
 */
public class TraceJobSpike {
    private static File evidenceDirectory() {
        String given = System.getProperty("vpnp.evidence");
        File dir = given == null ? new File("/tmp/vpnp-job") : new File(given);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create the evidence directory " + dir);
        }
        return dir;
    }

    @Test
    public void recordAWholeJobAsATrace() throws Exception {
        File evidence = evidenceDirectory();
        File logFile = new File(evidence, "log_job.txt");
        if (logFile.exists() && !logFile.delete()) {
            throw new IllegalStateException("The previous log is in the way: " + logFile);
        }
        File traceFile = new File(evidence, "trace.jsonl");

        // The application's own writer and pattern, so that this log has the shape the corpus has: the C++ reader
        // reads it, and the trace written beside it can be compared with what that reader derives.
        Configurator.currentConfig()
                .writer(new RollingFileWriter(logFile.getAbsolutePath(), 10))
                .formatPattern("{date:yyyy-MM-dd HH:mm:ss.SSS} {class_name} {level}: {message}")
                .level(Level.TRACE)
                .activate();

        File workingDirectory = java.nio.file.Files.createTempDirectory("openpnp-trace-job").toFile();
        File configurationDirectory = new File(workingDirectory, ".openpnp");
        File machineXml = new File(configurationDirectory, "machine.xml");
        // Which machine to run: a config directory under src/test/resources/config, and the job beside it or under
        // samples/. SampleJobTest's is the default because it is the one that ships with a gcode driver.
        String configName = System.getProperty("vpnp.job.config", "SampleJobTest");
        String jobPath = System.getProperty("vpnp.job.job", "samples/pnp-test/pnp-test.job.xml");
        FileUtils.copyURLToFile(ClassLoader.getSystemResource("config/" + configName + "/machine.xml"), machineXml);
        for (String sibling : new String[] {"parts.xml", "packages.xml"}) {
            java.net.URL resource = ClassLoader.getSystemResource("config/" + configName + "/" + sibling);
            if (resource != null) {
                FileUtils.copyURLToFile(resource, new File(configurationDirectory, sibling));
            }
        }

        // -Dvpnp.job.static=true asks the same job of the same config with the *dynamic* simulation off: still the
        // imperfect geometry (non-squareness, runout), no camera lag, no camera noise, no vibration. It is edited
        // into the FILE rather than into the loaded machine, and that is not tidiness. A harness that changes the
        // machine in Java cannot say so in the stamp - `config_hash` is the hash of the bytes that were loaded, so
        // an in-memory edit leaves two runs of two different machines carrying one stamp. And saving is not a fix:
        // measured here, `Configuration.save()` of the same loaded config is not reproducible across runs either -
        // two saves of this file differed in exactly one attribute, `fiducial-vision-id`, which the fiducial locator
        // resolves from an unordered collection (FVS_Default in one JVM, FVS_Stock in the next).
        boolean quiet = Boolean.parseBoolean(System.getProperty("vpnp.job.static", "false"));
        if (quiet) {
            String xml = FileUtils.readFileToString(machineXml, "UTF-8");
            String spelled = xml.replace("simulation-mode=\"DynamicImperfectionsMachine\"",
                    "simulation-mode=\"StaticImperfectionsMachine\"");
            if (spelled.equals(xml)) {
                throw new IllegalStateException("This config does not spell simulation-mode the way the spike expects;"
                        + " editing nothing and claiming a different machine is exactly what this is here to prevent");
            }
            FileUtils.writeStringToFile(machineXml, spelled, "UTF-8");
        }

        // -Dvpnp.job.two-cameras=true gives the second nozzle a bottom camera of its own, which is the only way to
        // reach the pre-captured alignment route: VisionUtils.getBottomVisionCamera() pairs a nozzle with the
        // up-looking camera whose name carries the same number, and performParallelAlignment() needs two of them.
        // Edited in the FILE before it is loaded, for the reason spelled out on the simulation-mode edit above.
        if (Boolean.parseBoolean(System.getProperty("vpnp.job.two-cameras", "false"))) {
            String xml = FileUtils.readFileToString(machineXml, "UTF-8");
            String quote = String.valueOf('\"');
            String up = "looking=" + quote + "Up" + quote;
            // The camera to copy is the up-looking one, which is not the first <camera> in the file: the table camera
            // comes first and is looking down.
            int first = -1;
            for (int at = xml.indexOf("<camera "); at >= 0; at = xml.indexOf("<camera ", at + 1)) {
                int openTagEnd = xml.indexOf('>', at);
                if (openTagEnd > 0 && xml.substring(at, openTagEnd).contains(up)) {
                    first = at;
                    break;
                }
            }
            int end = first < 0 ? -1 : xml.indexOf("</camera>", first);
            if (first < 0 || end < 0) {
                throw new IllegalStateException(configName + " has no up-looking camera element to copy");
            }
            String one = xml.substring(first, end + 9);
            String second = one.replace("name=" + quote + "Bottom" + quote, "name=" + quote + "Bottom2" + quote)
                    .replace("id=" + quote + "C2" + quote, "id=" + quote + "C3" + quote);
            if (second.equals(one)) {
                throw new IllegalStateException("the up camera in " + configName
                        + " is not named Bottom with id C2, so a second one was not written");
            }
            // Splice first, rename afterwards: the rename changes the string's length, and an index taken before it
            // would point at something else after.
            xml = xml.substring(0, end + 9) + second + xml.substring(end + 9);
            xml = xml.replace("name=" + quote + "Bottom" + quote, "name=" + quote + "Bottom1" + quote);
            FileUtils.writeStringToFile(machineXml, xml, "UTF-8");
        }

        Configuration.initialize(configurationDirectory);
        Configuration.get().load();

        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();

        if (quiet) {
            // Read back, because the file being edited is only the claim; the machine running it is the fact.
            System.out.println("SPIKE   simulation-mode "
                    + ((SimulationModeMachine) machine).getSimulationMode());
        }

        Object defaultCamera = machine.getDefaultHead().getDefaultCamera();
        // SampleJobTest's own warm-up: the imperfect simulation races the camera's first frame. A camera that does not
        // settle - a config whose default camera is the table image - has no first frame to race.
        if (defaultCamera instanceof AbstractSettlingCamera) {
            AbstractSettlingCamera settling = (AbstractSettlingCamera) defaultCamera;
            try {
                settling.capture();
            }
            catch (Exception e) {
            }
            try {
                settling.capture();
            }
            catch (Exception e) {
            }
        }

        // The hash is taken here, after every edit this harness makes and before the first payload: `config_hash`
        // names the bytes the run ran on, which is the file that will be copied beside the trace. Checked again at
        // the end rather than assumed, because a configuration that changed during the run makes the hash a claim
        // about nothing.
        String configHash = TraceRecorder.sha256(machineXml);

        File jobFile = new File(jobPath);
        Job job = Configuration.get().loadJob(jobFile);

        Map<String, String> stamp = new LinkedHashMap<>();
        stamp.put("config_hash", configHash);
        stamp.put("oracle_sha", System.getProperty(TraceRecorder.ORACLE_SHA_PROPERTY));
        stamp.put("effective_locale_format", Locale.getDefault(Locale.Category.FORMAT).toString());
        stamp.put("job_file", jobPath);
        stamp.put("nozzle_tips_loaded", tipsLoaded(machine));
        // `v4l2_parameters` and `lighting_note` are omitted, not written as "none": this run has no such hardware,
        // but the reference's own spelling of that on a machine is not ours to invent, and the comparator already
        // refuses a field attested on one side only. Two runs of this fixture therefore compare; a machine trace
        // and a simulated one do not, which is the correct verdict.
        TraceRecorder.open(traceFile,
                "openpnp@" + System.getProperty(TraceRecorder.ORACLE_SHA_PROPERTY, "unknown-at-commit")
                        + " (recorded in-process)",
                "the wire of one " + configName + " job, recorded by the app itself"
                        + (quiet ? " (simulation-mode StaticImperfectionsMachine)" : ""),
                stamp);

        System.out.println("SPIKE   frameDump property=" + System.getProperty("frameDump")
                + " enabled=" + org.openpnp.vision.FrameDump.isEnabled(null)
                + " directory=" + org.openpnp.vision.FrameDump.overrideDirectory()
                + " max=" + org.openpnp.vision.FrameDump.maxFrames());

        ReferencePnpJobProcessor jobProcessor = (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        jobProcessor.addTextStatusListener((text) -> System.out.println("SPIKE " + text));

        Machine runner = Configuration.get().getMachine();
        runner.setEnabled(true);
        runner.home();
        runner.execute(() -> {
            runner.home();
            jobProcessor.initialize(job);
            while (jobProcessor.next()) {
                //spin
            }
            return null;
        }, false, 10000);
        runner.setEnabled(false);
        TraceRecorder.close();

        if (!configHash.equals(TraceRecorder.sha256(machineXml))) {
            System.out.println("SPIKE   WARNING the configuration changed during the run; config_hash names the "
                    + "bytes that were loaded, not the ones left behind");
        }
        java.nio.file.Files.copy(machineXml.toPath(), new File(evidence, "machine.xml").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.copy(jobFile.toPath(), new File(evidence, "pnp-test.job.xml").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        System.out.println("SPIKE evidence in " + evidence.getAbsolutePath());
        System.out.println("SPIKE   log     " + logFile.length() + " bytes");
        System.out.println("SPIKE   trace   " + traceFile.length() + " bytes");
    }

    /**
     * What each nozzle carries when the session starts, spelled {@code N1:tip}. The job changes tips during the run
     * and so does the real machine; the stamp field is about the state the session began in, which is the only state
     * two runs can be expected to share.
     */
    private static String tipsLoaded(ReferenceMachine machine) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Nozzle nozzle : machine.getDefaultHead().getNozzles()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(nozzle.getName()).append(':');
            out.append(nozzle.getNozzleTip() == null ? "none" : nozzle.getNozzleTip().getName());
        }
        return out.toString();
    }
}
