package org.openpnp.logging;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.openpnp.model.Configuration;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;

import org.pmw.tinylog.Logger;

/**
 * Writes a run as a trace: one JSON object per line, the first naming the run and the rest being the
 * payloads the controller was given and the replies it sent.
 *
 * <p>
 * The format is defined by the C++ project that reads these files, and the definition is
 * {@code include/vpnp/trace.hpp} in that repository, graded there by its own tests and by gate G2g. This class is
 * the writer half of that one definition and nothing else: it adds no behaviour to the machine, and it is inert
 * unless the system property {@code vpnp.trace} names a file to write. When it is inert the two lines that call it
 * (in {@code GcodeAsyncDriver}'s writer thread and {@code GcodeDriver}'s reader thread) do one volatile read and
 * return.
 *
 * <p>
 * There are two ways to turn it on, and they differ in who says when the run began. A harness calls {@link
 * #open(File, String, String, Map)} once its configuration exists, and so chooses the subject and the stamp. Or
 * nothing calls it, and the first payload starts the file by itself from the property — which is the shape a real
 * machine session needs, because {@code openpnp.sh -Dvpnp.trace=/tmp/job.jsonl} is the only seam available when the
 * thing being instrumented is a session nobody is scripting. In that second case the header says so in its subject,
 * and a shutdown hook closes the file, so a run that dies with an exception still leaves what it got to rather than
 * a half-buffered line.
 *
 * <p>
 * What it records is what those two log lines already say: {@code [GcodeDriver:ttyUSB0] >> <payload>} is written
 * immediately after {@code comms.writeLine()}, and {@code << <reply>} immediately after the line is read and
 * trimmed. That is deliberate. The recorded debug logs are the corpus the C++ gates have been grading for a week,
 * and a trace whose payload bytes were taken from a different point in the code could not be compared with a trace
 * read back out of a log — which is the cross-check this writer exists to make possible.
 *
 * <p>
 * Replies are off by default, and that is a decision rather than an oversight. What the reading project grades a
 * reply on is its shape - one acknowledgement per line sent, and a data-bearing answer matching the read regex that
 * the configuration recorded beside it declares - never the numbers inside it, which the controller is free to
 * change from one answer to the next. A trace is a golden of the outbound wire; the inbound half is what you turn on
 * to find out why a session went wrong (a refusal, an {@code error:}, a drag pin's retry note), and
 * {@code -Dvpnp.trace.replies=true} is that switch.
 *
 * <p>
 * The header attests the {@code [environment_stamp]} fields it can reach and omits the rest: the schema has no
 * "unknown" sentinel on purpose, because two traces both stamped unknown would <em>match</em>. Four of the seven are
 * facts the running application knows — {@code config_hash} (the bytes of the machine.xml it loaded),
 * {@code effective_locale_format} (the locale numbers will be formatted with, which is <em>not</em> the one
 * {@code Locale.getDefault()} prints on every host: see ADR-0008), {@code nozzle_tips_loaded} (what the nozzles hold
 * as the trace opens), and {@code oracle_sha} when told — and three are things only the person in front of the machine
 * can say, so they arrive as properties and stay absent when nobody says them: {@code -Dvpnp.trace.v4l2_parameters},
 * {@code -Dvpnp.trace.lighting_note}, and {@code -Dvpnp.trace.job_file}, the last of which is attested by the digest of
 * the file rather than its path, so that the same job on two hosts is the same stamp.
 *
 * <p>
 * A field is written only when it was actually known. That is why an operator's property that names a job file which
 * cannot be read leaves the field out and says so in the log, rather than writing the words "unreadable file": two
 * traces both stamped "unreadable" would match, which is the failure the schema's missing sentinel exists to avoid.
 */
public final class TraceRecorder {
    /** Setting this to a file path turns recording on; unset, the recorder does nothing at all. */
    public static final String PROPERTY = "vpnp.trace";

    /** The revision being run, for the {@code oracle_sha} stamp field. Absent means the field is absent. */
    public static final String ORACLE_SHA_PROPERTY = "vpnp.trace.oracle_sha";
    /**
     * The v4l2 parameters the cameras in this run were set with, for the {@code v4l2_parameters} stamp field. The
     * application does not own these: they live in the capture device and are read from it, so the recorder writes
     * what it is told and says nothing when it is not. The value should be the device's own words, e.g. the output of
     * {@code v4l2-ctl --list-ctrls} for the device node, digested or verbatim.
     */
    public static final String V4L2_PROPERTY = "vpnp.trace.v4l2_parameters";
    /** The lighting in front of the camera, for the {@code lighting_note} stamp field. A human's sentence. */
    public static final String LIGHTING_NOTE_PROPERTY = "vpnp.trace.lighting_note";
    /**
     * The job the run is going to process, for the {@code job_file} stamp field. Named by a path, attested as the
     * SHA-256 of that file's bytes: the frozen rule is that a differing stamp makes two traces incomparable, and a
     * path would make the same job incomparable between {@code pn52} and this host, which is a difference the rule
     * does not mean to catch.
     */
    public static final String JOB_FILE_PROPERTY = "vpnp.trace.job_file";

    /** What the recorded session is about, for a run started from the properties. */
    public static final String SUBJECT_PROPERTY = "vpnp.trace.subject";

    /** Record the inbound arrow too. Off by default; see the class comment. */
    public static final String REPLIES_PROPERTY = "vpnp.trace.replies";

    private static final String EVENT_TX = "gcode.tx";
    private static final String EVENT_RX = "gcode.rx";

    private static volatile TraceRecorder recorder;

    /** Whether {@link #PROPERTY} was set the first time an event needed a recorder. Read once, on purpose. */
    private static boolean startedFromProperty;

    private final Object lock = new Object();
    private final File file;
    private final BufferedWriter writer;
    private final boolean recordReplies;
    private long sequence = 0;
    private boolean wroteAnEvent = false;

    private TraceRecorder(File file, String headerLine, boolean recordReplies) throws IOException {
        this.file = file;
        this.recordReplies = recordReplies;
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create the directory for " + file);
        }
        this.writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        this.writer.write(headerLine);
        this.writer.write('\n');
    }

    /**
     * Starts recording to {@code file}, replacing any trace already being written. Call it once the configuration
     * has been loaded and saved, so that {@code config_hash} names the bytes the run actually ran on.
     *
     * <p>
     * The stamp a caller passes is added to the one the application can derive, and wins over it field by field: a
     * harness that has just saved a configuration knows the bytes better than a file read would, and it should not
     * have to re-derive the locale and the loaded tips to say so.
     */
    public static void open(File file, String producer, String subject, Map<String, String> suppliedStamp)
            throws IOException {
        close();
        TraceRecorder candidate = new TraceRecorder(file,
                headerLine(producer, subject, stamp(suppliedStamp)),
                Boolean.parseBoolean(System.getProperty(REPLIES_PROPERTY, "false")));
        recorder = candidate;
        addHook();
        Logger.info("Trace recording to {}", file.getAbsolutePath());
    }

    /**
     * The recorder a machine session gets when nobody scripts it: the file, the revision and the subject come from the
     * properties, and the stamp is what {@link #stamp(Map)} can ask of the running application at that moment. That
     * includes {@code config_hash}, taken from the configuration directory as the trace opens: it names the bytes the
     * run started on, which is the claim a stamp makes. A run that saves a different configuration mid-session does not
     * change the stamp, and a scripted caller that opens the trace after its save passes its own value, which wins.
     */
    private static TraceRecorder fromProperties() throws IOException {
        File file = new File(System.getProperty(PROPERTY));
        String subject = System.getProperty(SUBJECT_PROPERTY,
                "the wire of an OpenPnP session, started by the recorder from -D" + PROPERTY);
        return new TraceRecorder(file, headerLine(producer(), subject, stamp(null)),
                Boolean.parseBoolean(System.getProperty(REPLIES_PROPERTY, "false")));
    }

    /**
     * The recorder to write into: the one a harness opened, the one the properties started, or null when neither was
     * asked for. The steady state is one volatile read and one boolean.
     */
    private static TraceRecorder current() {
        TraceRecorder found = recorder;
        if (found != null || startedFromProperty) {
            return found;
        }
        synchronized (TraceRecorder.class) {
            startedFromProperty = true;
            String path = System.getProperty(PROPERTY);
            if (path != null && !path.isEmpty()) {
                try {
                    recorder = fromProperties();
                    addHook();
                    Logger.info("Trace recording to {} because -{} was set", recorder.file, PROPERTY);
                }
                catch (IOException e) {
                    Logger.error(e, "Trace {} could not be started", path);
                }
            }
            return recorder;
        }
    }

    private static void addHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(TraceRecorder::close, "vpnp-trace-close"));
    }

    /** The producer line both paths write: the application, and the revision it is running. */
    private static String producer() {
        String sha = System.getProperty(ORACLE_SHA_PROPERTY);
        return sha == null || sha.isEmpty()
                ? "openpnp"
                : "openpnp@" + sha;
    }

    /**
     * The {@code [environment_stamp]} the run can attest: what the application knows, what the operator was asked, and
     * nothing else. Nulls are omitted rather than written, because a field the two sides both spell "unknown" would
     * <em>match</em>, and a matching pair of traces is read as "same environment" by whoever compares them.
     *
     * <p>
     * A caller's own fields go in last and win, so a harness that opened the trace after saving a configuration can
     * attest the bytes it saved rather than the ones on disk when the session started.
     */
    private static Map<String, String> stamp(Map<String, String> extra) {
        Map<String, String> fields = new TreeMap<>();
        putIfPresent(fields, "oracle_sha", System.getProperty(ORACLE_SHA_PROPERTY));
        putIfPresent(fields, "effective_locale_format", effectiveLocaleFormat());
        putIfPresent(fields, "nozzle_tips_loaded", nozzleTipsLoaded());
        putIfPresent(fields, "config_hash", machineConfigHash());
        putIfPresent(fields, "v4l2_parameters", System.getProperty(V4L2_PROPERTY));
        putIfPresent(fields, "lighting_note", System.getProperty(LIGHTING_NOTE_PROPERTY));
        putIfPresent(fields, "job_file", jobFileHash());
        if (extra != null) {
            fields.putAll(extra);
        }
        return fields;
    }

    /** Writes a field only when there is something to say: an empty string would attest an environment of nothing. */
    private static void putIfPresent(Map<String, String> fields, String name, String value) {
        if (value != null && !value.isEmpty()) {
            fields.put(name, value);
        }
    }

    /**
     * The locale numbers will be formatted with, which on a host like the one this fork is developed on is not the one
     * {@code Locale.getDefault()} prints: {@code LANG=en_US.UTF-8} with {@code LC_CTYPE=sv_SE.UTF-8} gives a FORMAT
     * locale of {@code sv_SE} while the plain default says {@code en_US}, and it is the FORMAT locale that decides
     * whether a saved {@code 1000.0} comes out as {@code 1000,0}. Spelled as a language tag so two hosts that would
     * format alike stamp alike, and so the value cannot contain the space a bare display name brings.
     */
    private static String effectiveLocaleFormat() {
        return Locale.getDefault(Locale.Category.FORMAT).toLanguageTag();
    }

    /**
     * What the nozzles hold as the trace opens, sorted and spelled {@code NOZZLE1=BL898;NOZZLE2=none}: which tips are
     * loaded changes what a job sends (a tip change is a command, and a nozzle that holds nothing refuses to place),
     * so it belongs to the environment a golden is recorded against.
     *
     * <p>
     * The moment matters and is stated rather than hidden: a job that changes tips ends with different ones from the
     * stamp, and the stamp is the starting state because that is the part a comparison needs to be told about. A
     * machine with no nozzles says {@code no nozzles}: that is a fact about the run, and it is a different fact from
     * the one a run that could not ask would leave unsaid.
     */
    private static String nozzleTipsLoaded() {
        Configuration configuration = configurationOrNull();
        if (configuration == null || configuration.getMachine() == null) {
            return null;
        }
        if (configuration.getMachine().getHeads() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Head head : configuration.getMachine().getHeads()) {
            List<Nozzle> nozzles = head.getNozzles();
            if (nozzles == null) {
                continue;
            }
            for (Nozzle nozzle : nozzles) {
                NozzleTip tip = nozzle.getNozzleTip();
                parts.add(nozzle.getName() + "=" + (tip == null ? "none" : tip.getId()));
            }
        }
        Collections.sort(parts);
        return parts.isEmpty() ? "no nozzles" : String.join(";", parts);
    }

    /**
     * The bytes of the machine configuration this run loaded. The path is the one {@code Configuration} itself writes,
     * which is a coupling worth naming: if the configuration directory moves, this field goes absent rather than
     * pointing at something else, because it is derived from the same getter the loader uses.
     */
    private static String machineConfigHash() {
        Configuration configuration = configurationOrNull();
        if (configuration == null || configuration.getConfigurationDirectory() == null) {
            return null;
        }
        File machine = new File(configuration.getConfigurationDirectory(), "machine.xml");
        return machine.isFile() ? sha256(machine) : null;
    }

    /**
     * The job the operator said this run is for, digested. Unreadable names the file in the log and attest nothing:
     * "the job was unreadable" and "there was no job" are different claims, and neither is a value two traces should be
     * allowed to agree on.
     */
    private static String jobFileHash() {
        String named = System.getProperty(JOB_FILE_PROPERTY);
        if (named == null || named.isEmpty()) {
            return null;
        }
        File job = new File(named);
        if (!job.isFile()) {
            Logger.error("Trace stamp field job_file was asked for, but {} is not a readable file: the field stays "
                    + "absent rather than attesting a job nobody ran", job.getAbsolutePath());
            return null;
        }
        return sha256(job);
    }

    /**
     * The configuration, or null when there is not one yet — the state of a unit test, and of the first seconds of an
     * application before its load. {@code Configuration.get()} answers that with an {@code Error}, and a field that
     * cannot be asked is a field left out rather than one invented, so the {@code Error} is caught here and nowhere
     * else in this file.
     */
    private static Configuration configurationOrNull() {
        try {
            return Configuration.get();
        }
        catch (Error | RuntimeException e) {
            return null;
        }
    }

    /** Stops recording. A run that produced no events leaves no file, because a trace with no events is not a record. */
    public static void close() {
        TraceRecorder current = recorder;
        if (current == null) {
            return;
        }
        recorder = null;
        try {
            synchronized (current.lock) {
                current.writer.close();
            }
            if (!current.wroteAnEvent) {
                if (current.file.delete()) {
                    Logger.info("Trace {} held no events and is removed; an empty trace is not a record",
                            current.file);
                }
                return;
            }
            Logger.info("Trace written: {} ({} events)", current.file, current.sequence);
        }
        catch (IOException e) {
            Logger.error(e, "Trace {} could not be closed", current.file);
        }
    }

    /** The payload that has just been handed to the controller, after pre-processing. */
    public static void recordSend(String payload) {
        TraceRecorder current = current();
        if (current != null) {
            current.record(EVENT_TX, payload);
        }
    }

    /** A line that has just come back from the controller and been trimmed. */
    public static void recordReceive(String payload) {
        TraceRecorder current = current();
        if (current != null && current.recordReplies) {
            current.record(EVENT_RX, payload);
        }
    }

    private void record(String event, String payload) {
        try {
            StringBuilder line = new StringBuilder(64);
            long stampNanoseconds;
            long number;
            synchronized (lock) {
                // The order events are written in is the order they happened in, and the writer thread and the
                // reader thread are two threads: the lock is what makes the trace a total order rather than two
                // interleaving ones decided by the scheduler.
                number = ++sequence;
                stampNanoseconds = System.nanoTime();
                line.append("{\"ev\":\"").append(event).append("\",\"line\":\"");
                escape(line, payload);
                line.append("\",\"seq\":").append(number).append(",\"t_ns\":").append(stampNanoseconds)
                        .append('}');
                writer.write(line.toString());
                writer.write('\n');
                // Flushed as it is written. The writer thread is paced by the port anyway, and a trace you cannot
                // read while the machine runs — or that loses its last kilobyte when the machine dies — is not
                // evidence, it is a hint.
                writer.flush();
                wroteAnEvent = true;
            }
        }
        catch (IOException e) {
            // A trace that cannot be written is never a reason to disturb the machine.
            Logger.error(e, "Trace event {} could not be written", event);
        }
    }

    private static String headerLine(String producer, String subject, Map<String, String> stamp) {
        StringBuilder line = new StringBuilder(256);
        line.append("{\"clock\":\"monotonic\",\"metrics\":{},\"producer\":\"");
        escape(line, producer);
        line.append("\",\"stamp\":{");
        // Sorted, because that is what the reader writes and what its own byte-stability check compares against.
        Map<String, String> sorted = new TreeMap<>(stamp);
        boolean first = true;
        for (Map.Entry<String, String> field : sorted.entrySet()) {
            if (field.getValue() == null) {
                // Absent, not "unknown": a sentinel would match itself across two runs that know nothing.
                continue;
            }
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append('"').append(field.getKey()).append("\":\"");
            escape(line, field.getValue());
            line.append('"');
        }
        line.append("},\"subject\":\"");
        escape(line, subject);
        line.append("\",\"t_ns_resolution\":1,\"trace\":1}");
        return line.toString();
    }

    /**
     * The escaping the reader's encoder uses: quotes and backslashes, the six short control escapes, and
     * a backslash-escaped {@code u00xx} with lower-case hex digits for everything else below U+0020. A tab is a
     * real payload byte in this application - {@code M809} is logged with two of them at the end - so getting
     * this wrong is not cosmetic.
     */
    static void escape(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        // Pinned, because a dumped byte that cannot be read back is decoration: the JVM's FORMAT
                        // locale on this host is sv_SE while Locale.getDefault() still prints en_US.
                        out.append("\\").append(String.format(Locale.US, "u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
            }
        }
    }

    /** The SHA-256 of a file, lower-case hex - the same digest the reader takes of the config beside a log. */
    public static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            FileInputStream in = new FileInputStream(file);
            try {
                while (true) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    digest.update(buffer, 0, read);
                }
            }
            finally {
                in.close();
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException | IOException e) {
            Logger.error(e, "The config hash for {} could not be taken", file);
            return null;
        }
    }
}
