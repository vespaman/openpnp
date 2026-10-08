/*
 * A harness for the BeanShell scripts in this directory, run against the machine by nobody.
 *
 * OpenPnP's Scripting panel evaluates scripts through javax.script, and the interpreter it ships,
 * BeanShell 2.0b5 (pom.xml), behaves differently under that engine than it does under its own
 * bsh.Interpreter. Parsing a script with bsh.Interpreter therefore lets through several ways of dying
 * that the panel then dies of, each of which costs a run in front of the machine. This harness evals a
 * script's definitions through the same BshScriptEngine, with fake nozzles, cameras and calibrations,
 * and checks both the arithmetic of the script and the interpreter rules it has to be written around.
 *
 * The rules, all of them found by being bitten:
 *
 *   - No varargs call resolves at all, so every moveTo (Movable.java declares both its forms with
 *     trailing MotionOption...) has to be handed its array explicitly.
 *   - Inside a method an array cannot be sized from a variable: not a global, not a parameter holding
 *     one, not a local copy of one. A literal, a static field, an array element or a method call as the
 *     size are all fine.
 *   - Assigning null removes the variable, so reading it back against null works while passing it on
 *     dies with "Undefined argument" somewhere far from the reason. "There is no value" has to be a
 *     value, and here it is a NaN Location.
 *   - The interpreter has dynamic scope: a function assigning to a name that the caller already has
 *     writes into the caller's variable, and only formal parameters are local. A helper that loops over
 *     "i" silently ended the caller's loop over the nozzles after the first nozzle, and the run looked
 *     healthy while reporting half of what it was sent to find out.
 *
 * Run it with the openpnp jar and the bsh jar on the classpath, and with a scratch home directory,
 * because the script opens its run log under user.home as soon as it evaluates:
 *
 *   java -Duser.home=/tmp/bsh \
 *        -cp ~/.m2/repository/org/beanshell/bsh/2.0b5/bsh-2.0b5.jar:\
 *target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar \
 *        scripts/ScriptHarness.java scripts/BatchResidualCheck.bsh
 *
 * Exits non-zero when a check fails, so it can be run before deploying a script.
 */

import java.nio.file.*;
import javax.script.*;

public class ScriptHarness {
    static int failures = 0;

    static final String PRELUDE = "import org.openpnp.model.LengthUnit;\n"
            + "import org.openpnp.model.Location;\n"
            + "import org.openpnp.model.Motion;\n";

    /*
     * Nozzles, cameras and calibrations that answer just enough to be measured by: one that reads a tip
     * moving a hundredth further out each frame, one that always refuses, and one that counts how often
     * it was asked, which is how the loop over several stations can be checked.
     */
    static final String FAKES = PRELUDE
            + "class GoodCal {\n"
            + "    int n;\n"
            + "    Location measureTip(Object nozzle, Object camera, Location expected) {\n"
            + "        n = n + 1;\n"
            + "        return new Location(LengthUnit.Millimeters, 0.1 + 0.01*n, -0.1 - 0.01*n, 0., 0.);\n"
            + "    }\n"
            + "}\n"
            + "class BadCal {\n"
            + "    Location measureTip(Object nozzle, Object camera, Location expected) throws Exception {\n"
            + "        throw new Exception(\"simulated: tip not found\");\n"
            + "    }\n"
            + "}\n"
            + "st = new Station();\n"
            + "st.nozzle = \"N1\"; st.camera = \"Upp1\"; st.calibration = new GoodCal();\n"
            + "bad = new Station();\n"
            + "bad.nozzle = \"N2\"; bad.camera = \"Upp2\"; bad.calibration = new BadCal();\n"
            + "class CountingCal {\n"
            + "    int calls;\n"
            + "    Location measureTip(Object nozzle, Object camera, Location expected) {\n"
            + "        calls = calls + 1;\n"
            + "        return new Location(LengthUnit.Millimeters, 0.2, 0.1, 0., 0.);\n"
            + "    }\n"
            + "}\n"
            + "sharedCal = new CountingCal();\n"
            + "many = new java.util.ArrayList();\n"
            + "for (mk = 0; mk < 3; mk = mk + 1) {\n"
            + "    one = new Station();\n"
            + "    one.nozzle = \"N\" + (mk + 1); one.camera = \"Upp\" + (mk + 1);\n"
            + "    one.calibration = sharedCal;\n"
            + "    many.add(one);\n"
            + "}\n"
            + "here = new Location(LengthUnit.Millimeters, 100., 200., 0., 0.);\n";

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + " -> " + got
                + (ok ? "" : " (wanted " + want + ")"));
    }

    static void expect(String what, String script, Object want) {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("beanshell");
            check(what, engine.eval(script), want);
        }
        catch (Exception ex) {
            failures++;
            String message = String.valueOf(ex.getMessage()).replace('\n', ' ');
            System.out.println("  FAIL " + what + " threw "
                    + message.substring(0, Math.min(160, message.length())));
        }
    }

    static void note(String what, String script) {
        try {
            System.out.println("  note " + what + ": "
                    + new ScriptEngineManager().getEngineByName("beanshell").eval(script));
        }
        catch (Exception ex) {
            String message = String.valueOf(ex.getMessage()).replace('\n', ' ');
            System.out.println("  note " + what + ": threw "
                    + message.substring(0, Math.min(70, message.length())));
        }
    }

    static void expectThrow(String what, String script) {
        try {
            new ScriptEngineManager().getEngineByName("beanshell").eval(script);
            failures++;
            System.out.println("  FAIL " + what + " did not throw, so the hazard is elsewhere");
        }
        catch (Exception ex) {
            String message = String.valueOf(ex.getMessage()).replace('\n', ' ');
            System.out.println("  ok   " + what + " threw: "
                    + message.substring(0, Math.min(70, message.length())));
        }
    }

    /*
     * The definitions and the fakes have to be evaluated together: separate evals would not leave the
     * script's own functions in the same namespace as the stations that use them.
     */
    static void combined(String what, String defs, String body, Object want) {
        expect(what, defs + FAKES + body, want);
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "scripts/BatchResidualCheck.bsh";
        String text = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
        // Everything up to the first use of the machine is definitions and settings, and is exactly the
        // part that can be exercised without a machine.
        int cut = text.indexOf("if (!machine.isEnabled())");
        if (cut < 0) {
            System.out.println(path + ": no definitions section found, the script was restructured");
            System.exit(2);
        }
        String defs = text.substring(0, cut);

        System.out.println(path + ", definitions only, under the engine the Scripting panel uses:");
        expect("definitions evaluate", defs, null);

        System.out.println("the script's own arithmetic:");
        combined("measureTipOffsets averages frames", defs,
                "avg = measureTipOffsets(st, here, \"harness\", framesPerMeasure);\nxy(avg);",
                "0.1200 -0.1200");
        combined("nothing measured is a value that says it is not one", defs,
                "m = measureTipOffsets(bad, here, \"harness\", framesPerMeasure);\n"
                        + "hasValue(m) + \" \" + Double.isNaN(mag(m)) + \" \" + xy(m);",
                "false true       -        -");
        combined("a frame that fails does not lose the frames that did not", defs,
                "class HalfCal {\n"
                        + "    int n;\n"
                        + "    Location measureTip(Object nozzle, Object camera, Location expected) throws Exception {\n"
                        + "        n = n + 1;\n"
                        + "        if (n > 1) { throw new Exception(\"simulated second frame\"); }\n"
                        + "        return new Location(LengthUnit.Millimeters, 0.5, 0.25, 0., 0.);\n"
                        + "    }\n"
                        + "}\n"
                        + "half = new Station(); half.nozzle = \"N1\"; half.camera = \"Upp1\"; half.calibration = new HalfCal();\n"
                        + "m = measureTipOffsets(half, here, \"harness\", framesPerMeasure);\n"
                        + "hasValue(m) + \" \" + xy(m);",
                "true 0.5000 0.2500");
        combined("a measured station does not end the caller's station loop", defs,
                "visited = \"\";\n"
                        + "for (i = 0; i < many.size(); i = i + 1) {\n"
                        + "    st3 = many.get(i);\n"
                        + "    measureTipOffsets(st3, here, \"station \" + i, 2);\n"
                        + "    visited = visited + i + \",\";\n"
                        + "}\n"
                        + "visited + \" calls \" + sharedCal.calls;",
                "0,1,2, calls 6");
        combined("goTo hands the move an explicit option array", defs,
                "class FakeNozzle {\n"
                        + "    String moveTo(Object at, Object options) {\n"
                        + "        return \"moved to \" + xy(at) + \" with \"\n"
                        + "                + java.lang.reflect.Array.getLength(options) + \" options\";\n"
                        + "    }\n"
                        + "}\n"
                        + "goTo(new FakeNozzle(), new Location(LengthUnit.Millimeters, 12., 34., 0., 0.));",
                "moved to 12.0000 34.0000 with 0 options");

        System.out.println("the interpreter rules the script is written around:");
        expectThrow("a variable assigned null cannot be passed on", defs + "c = null;\nxy(c);");
        expectThrow("array sized from a variable inside a method",
                "f(k) { xs = new double[k]; return \"\" + xs.length; } n = 3; f(n);");
        expectThrow("array sized from a local copy of a variable inside a method",
                "f(k) { j = k; xs = new double[j]; return \"\" + xs.length; } n = 3; f(n);");
        expect("array sized from a literal inside a method",
                "f(k) { xs = new double[3]; return \"\" + xs.length; } n = 3; f(n);", "3");
        expect("array sized from an array element inside a method",
                "arr = new double[4]; arr[0] = 3; f(k) { xs = new double[arr[0]];"
                        + " return \"\" + xs.length; } f(1);",
                "3");
        expect("array sized from a method call inside a method",
                "k3() { return 3; } f(k) { xs = new double[k3()]; return \"\" + xs.length; } n = 3; f(n);",
                "3");
        // A note rather than a check: what this says is how the interpreter behaves today, which the
        // script works around rather than relies on, and which would be worth knowing the day it changes.
        note("a function assigning the caller's name writes the caller's variable",
                "f(k) { for (i = 0; i < 3; i = i + 1) { } return 0; } out = \"\";"
                        + " for (i = 0; i < 4; i = i + 1) { f(1); out = out + i + \",\"; }"
                        + " out.equals(\"0,1,2,3,\") ? \"scoped after all\" : \"clobbered, ran to \" + out;");
        combined("the settings the script reads are there to be read", defs,
                "angles.length + \" \" + framesPerMeasure + \" \" + q2Steps + \" \" + recheck;",
                "4 3 2 true");

        System.out.println(failures == 0 ? "all good" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
