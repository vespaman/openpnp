# AGENTS.md

Notes for an AI assistant (or human) working in this checkout. Everything below was measured in this
repo, not copied from the wiki. Where a document and the code disagree, **the code wins** (see
`docs/design/parallel-bottom-vision/README.md`).

## What this checkout is

OpenPnP: Java/Swing control software for an open-source SMT pick-and-place machine. Maven project,
`org.openpnp:openpnp-gui`, single jar, entry point `org.openpnp.Main`.

**This fork has two hosts and they are not interchangeable:**

| Host | Has | Do here |
|---|---|---|
| `pn52` | the actual machine, cameras, nozzles, the install4j launcher | machine runs, only when a document says so |
| this LXC (Debian 13) | JDK + Maven, **no hardware, no display** | build, unit/simulation tests, analysis of captured data |

Never assume a camera, serial port or `/dev/video*` exists here. Machine runs are described in
`docs/design/parallel-bottom-vision/MACHINE-TEST.md` and are executed on `pn52`.

Remotes: `origin` = the fork, `upstream` = openpnp/openpnp with **push DISABLED on purpose**,
`agent1` = a local path remote. Do not push `upstream`, and do not push at all unless asked.

## Build and test

Verified on Debian 13 with OpenJDK 21.0.12.1 + Maven 3.9.9:

```bash
mvn -B clean package -DskipTests    # ~11 s: checkstyle, 723 main + 75 test sources, jar + target/lib
mvn -B clean test                   # ~5 min full suite: 372 tests
mvn -B test -Dtest=FrameProvenanceTest -DfailIfNoSpecifiedTests=false   # one class
mvn -B -o ...                       # offline, once ~/.m2 is warm
```

- The suite passes **headless** (`DISPLAY` unset). No X server is needed for `mvn test`.
- To actually run the GUI here: build, then `xvfb-run -a ./openpnp.sh` (or SSH X-forward). `openpnp.sh`
  already passes the required `--add-opens` and adds `--enable-native-access=ALL-UNNAMED` when the JVM
  knows that flag.
- The full suite is ~5 min, `JobProcessorTest` alone is ~62 s. While iterating, name the classes you
  touched instead of running everything.
- 47 test classes sit in the **default package** and 22 in `org.openpnp.*`; `-Dtest=SimpleName` finds
  both. There are no `@Disabled` or `@Tag` markers, so nothing is silently skipped.
- Do **not** override surefire's `-DargLine` from the command line: `pom.xml` sets the heap,
  `GCLockerRetryAllocationCount` and the three `--add-opens` that the tests need.
- `target/` is gitignored; `mvn clean` is safe.

Two build messages are noise, not breakage:

* `[ERROR] 'dependencyManagement...systemPath' for com.sun:tools:jar must specify an absolute path`
  from the `jaxb-core` / `jaxb-impl` 2.2.11 POMs — upstream, harmless, build succeeds.
* `[WARNING] Parameter 'mainClass' is unknown for plugin 'maven-compiler-plugin:3.1'` — the
  `<mainClass>org.openpnp.app.Main</mainClass>` in `pom.xml` is stale and ignored. The real main class
  is `org.openpnp.Main`.

## Java version: the one trap that matters

`pom.xml` compiles with `<source>11</source> <target>11</target>`, CI tests Temurin **11, 17, 19**, and
`pn52` runs **Temurin 25**. Building on 21 works (verified).

But `source`/`target` do **not** restrict the JDK's API surface the way `<release>` does. Code compiled
on JDK 21 that uses a 12+-only API or language feature (e.g. `String.formatted()`, text blocks, switch
expressions, records, `Stream.toList()`) compiles here and then fails on a Java 11 runtime. **Write to
the Java 11 language and API level**, because CI and old installs still run it.

## Locale: this bit us, keep it fixed

This container has `LANG=en_US.UTF-8` but `LC_CTYPE=sv_SE.UTF-8`, which makes the JVM's
`Locale.getDefault(Locale.Category.FORMAT)` `sv_SE` **while `Locale.getDefault()` still prints `en_US`**.
So `String.format("%+.1f", 1000.0)` silently returns `+1000,0`.

Anything whose output is read back — file names, saved config, log lines that get parsed, corpus
frame names — must pin the locale explicitly:

```java
String.format(Locale.US, "%.1f", v)   // right: Location.toString() and FrameDump.number() do this
Double.parseDouble(s)                 // only ever accepts a dot
```

That is why `FrameDump` writes dot decimals even on a Swedish machine: a dumped frame whose name cannot
be parsed is a picture, not evidence. If you touch formatting or parsing, run the affected tests under a
comma locale (`LC_CTYPE=sv_SE.UTF-8 mvn test -Dtest=...`) as well as the normal one.

## Style and the gates that actually fail the build

* **Indent with 4 spaces** in new code. 716 of the 723 files in `src/main/java` are space-indented,
  while 33 files contain tab-indented lines (28 mixed, 5 tabs-only — the `photon` and `neoden4` drivers,
  some feeders and GUI panels, e.g. `FeedersPanel.java`). Follow the file you are editing and never
  reindent lines you did not change. `pom.xml` itself is tab-indented.
* `maven-checkstyle-plugin` runs in the `validate` phase and **fails the build** on:
  `NeedBraces`, `OneStatementPerLine`, `MemberName` `^[a-z][a-zA-Z0-9_]*$`,
  `TypeName` `^[A-Z][a-zA-Z0-9_]*$`, `MethodName` `^[a-z][a-zA-Z0-9]*$`,
  `LocalVariableName` `^[a-z][a-zA-Z0-9_]*$`, and any **CR or CRLF** line ending.
  It covers test sources too. Exempted: `org/openpnp/model/eagle/xml/*` (generated),
  `org/openpnp/machine/neoden4/Neoden4CameraDriver*`.
* **LF only, everywhere** — `.gitattributes` sets `* text=auto eol=lf`.
* Long descriptive method names, comments that explain *why*, and no gratuitous reformatting of
  untouched files: a diff that rewraps other people's code is a rejected diff.
* BeanShell scripts in `scripts/` must be checked with `scripts/ScriptHarness.java`, not by eye — its
  header comment lists the interpreter's traps (no varargs resolution, arrays cannot be sized from a
  variable, assigning `null` deletes the variable, dynamic scope).

## Committing

`git-hooks/pre-commit` is installed in `.git/hooks/` and will **reject** a commit that contains
`STOPSHIP` (case-insensitive) in the staged diff, or that does not stage `CHANGES.md`.

* `CHANGES.md`: one bullet, under the current `# Version` heading's `## New Features` or
  `## Bug Fixes`, newest bullet first, written as the effect a user would notice. Add
  `[PR nnnn](https://github.com/openpnp/openpnp/pull/nnnn)` only when a PR actually exists.
  This is upstream convention; note that much of this fork's own recent work skipped it, so a
  dev-only commit may legitimately go in with `--no-verify` if the maintainer says so.
* Commit message style in this fork is a **lower-case declarative sentence about what is now true**,
  not a capitalised imperative: *"a dumped frame is named the same on every machine, so it can still be
  read back"*. No prefixes, no ticket tags.
* One commit per idea. Keep `CHANGES.md` in the same commit as the change it describes.

## Where things live

```
src/main/java/org/openpnp/
  Main.java            entry point
  model/               machine model: Configuration, Machine, Part, Location, FrameProvenance
  spi/                 the interfaces drivers implement: Camera, Nozzle, Driver, Feeder, MotionPlanner
  machine/reference/   the software driver everything simulates. Notably:
                         ReferencePnpJobProcessor.java  the job loop
                         driver/{Abstract,ReferenceAdvanced,Null}MotionPlanner.java
                         vision/ReferenceBottomVision, vision/DisplacementCheck  <- part alignment work
                         camera/ (OpenPnpCaptureCamera, GstreamerCamera), feeder/
  machine/{neoden4,photon,pandaplacer,rapidplacer}/  hardware drivers
  vision/              FrameDump, OpenCvUtils, pipeline stages (incl. stages/ImageCapture) + pipeline UI
  gui/                 Swing UI (FlatLaf); scripting/ = JSR-223 (Nashorn, Jython, BeanShell)
src/test/resources/    config/<TestName>/ machine+job fixtures; samples/ boards
samples/               example jobs and boards
tests_<YYYY-MM-DD>/    tracked run records: log_*.txt plus the machine.xml that produced them
docs/design/parallel-bottom-vision/
  README.md            index + status of every design idea (history, not specification)
  MACHINE-TEST.md      how to run something on pn52, step by step
  SALVAGE-PLAN.md      the risk register for the current implementation
  *.py, bench/         analysis helpers and standalone micro-benchmarks for captured data
```

Camera/vision work in particular: `FrameDump` (`-DframeDump=<dir>`, `-DframeDumpMax`,
`-DframeDumpKeep`) writes the bottom-vision frames that `tests_*/bv-corpus-r/` keeps and
`src/test/java/org/openpnp/machine/reference/vision/FrameReplayTest.java` replays. The file name is the
evidence: `bv__<origin>__<camera>__<nozzle>__<part>__f<n>__a+..__c+..__w+..__r+..` with `__` as
separator, `~` for a minus sign, `na` for unknown, and dot decimals.

## The C++ rewrite is a different repository

`docs/design/vpnp-rewrite.md` points at `../vpnp`, a separate project rewriting OpenPnP in C++20 with a web UI.
No part of it is worked on here. This repository is its **oracle**, which implies three things:

- tags named `oracle-<date>` mark the revisions that project may build. **Build the reference app from an
  `oracle-*` tag, never from a working tree**, and in a `git worktree` so nobody's checkout moves.
- development on `parallel-bottom-vision` stays unconstrained; the rewrite must not care how far it moves. It
  refreshes its oracle by cutting a new tag and diffing freshly recorded reference traces.
- a `pn52` session that leaves `tests_<date>/` behind is feeding that project as well as this one, so keep
  corpus + config + log together even when the run was for something else.

## Working habits on this host

* The repo root is deliberately littered with run output (`*-run.log`, `hs_err_pid*.log`); both are
  gitignored, so they stay out of `git status`. So does `framedump-A/`, for a subtler reason: it is an
  empty directory, and git does not see empty directories. Do not clean any of it up, and do not commit
  new logs.
* Put scratch work in `/tmp/opencode/`. Anything worth keeping goes into a `tests_<date>/` record with
  the `machine.xml` that produced it.
* `~/.openpnp2` is the user config dir on a machine host; it does not exist here and is not needed for
  tests, which build their `Configuration` from `src/test/resources/config/`.
* After a change: run the specific tests you affected, then the full `mvn -B clean test` before calling
  anything done. Report the actual `Tests run:` line, not a guess.
