# Pre-check and early departure - design

Status: **design, nothing implemented**. Written 2026-09-29 on top of the tag `pbv-2026-09-29`
(`023daf9657`), after the crop had been verified on the machine (`log_c.txt`).

Relations: `PRE-ALIGNMENT-CHECK.md` is the April requirement list, of which R1 is dead and the rest
survives. `PRE-CHECK-ASSESSMENT.md` is the measurement that made the feature affordable at all, and
its options 3 and 4 are what this document turns into one design. Where anything here disagrees with
those two, this document is the current opinion.

## 1. What is asked for

Stated by the machine owner 2026-09-29:

1. While the up camera is settling, check the part: is it there, is it the right size, is it roughly
   straight.
2. If that check is happy, let the head leave the vision position while the real alignment is still
   running.
3. If the real alignment then fails, the head comes back to the bottom vision and the alignment is
   redone the sequential way.
4. If the pre-check is not happy, do not leave; go straight to the sequential alignment.
5. A part that aligns from several shots (compositing) is excluded from all of this.
6. Simulate as much of it as possible on the OpenPnP PC rather than on the machine.

Two features are in that list, and they only pay together: the pre-check is worth little if the head
still stands and waits, and leaving early without a check means a missing part is discovered after
the head is already on its way to the board.

## 2. Why this is affordable now and was not in April

The April attempt measured the pre-check at 50-70 ms against a ~30 ms settle budget
(`PRE-ALIGNMENT-CHECK.md` *Why it stopped*), and `fix-precheck-timing.md` then found that part of the
reported number was the capture and the settle themselves, measured on the full frame either way.
Nothing about the design was wrong; the frame was. With `CropCentered` in the pipeline, on the vision
PC, on the pipeline that actually runs (`PRE-CHECK-ASSESSMENT.md` §3):

| | full frame | 200 px (installed) | 105 px |
|---|--:|--:|--:|
| analysis alone | 22.9 ms | 2.6 ms | 0.7 ms |
| whole `process()` | 45 ms | 20 ms | 1.2 ms |
| machine window, one shot | 48-55 ms (`log_a.txt`) | 8.1-13.2 ms (`log_c.txt`) | - |
| the settle it must hide in | 35-60 ms | 35-60 ms | 35-60 ms |

The 2.6 ms figure is the analysis in isolation; a check that runs the pipeline also pays `preparePipeline()`,
the recall of the full frame that the diagnostics draw on, and the bookkeeping between the stages. Measured
on the OpenPnP PC, in `bench/PreCheckBench.java`, with the pipeline of `R0402` as it runs on the machine and
a frame handed to the capture stage the way the parallel alignment hands one (§7). All rows are the same
JVM, OpenJDK 25.0.3, on that PC; Temurin 21, the java the machine actually uses, measures the pre-check at
20.3 ms median instead of 14.9 ms and is discussed below:

| | median | p90 | max |
|---|--:|--:|--:|
| one pre-check, nothing else running | 14.9 ms | 20.5 ms | 21.3 ms |
| two pre-checks at once, wall clock | 16.3 ms | 22.6 ms | 25.1 ms |
| one settling iteration | 3.0 ms | 3.2 ms | 4.8 ms |
| one settling iteration, two pre-checks running | 2.8 ms | 3.2 ms | 3.9 ms |
| a pre-check running during a settle | 20.8 ms | 27.6 ms | 65.7 ms |

What the table says, and it is not what the April attempt feared:

- **The pre-check does not lengthen the settling.** The iteration that does the settle's own arithmetic
  takes 3.0 ms with nothing else running and 2.8 ms with two pipelines running on other cores; the frame
  interval stays 33.4 ms against 33.2 ms. The settling waits for frames, not for CPU, and a pre-check
  therefore cannot steal time from it. That was the failure of the April attempt, and it does not happen.
- **The pre-check costs what the alignment costs.** Two at once cost barely more than one, so there is no
  queueing problem. The absolute figure should not be over-read: it moves with the JVM (15 ms median on
  OpenJDK 25, 20 ms on Temurin 21), the footprint fed to it makes no difference at all (0.6, 1.0, 1.5 and
  2.83 mm all measure the same, so the crop is not what the money goes on), and the machine itself measures
  8-13 ms for the `process()` of the same pipeline once it is warm. Somewhere between the two figures is a
  few milliseconds the bench carries and the product does not; what matters is that the pre-check is of the
  same order as the alignment it accompanies, not ten times cheaper and not ten times dearer.
- **It does not reliably finish inside the settle.** 15-20 ms median against a 35-60 ms window fits; 21-28
  ms at p90 and 66 ms at the worst fit only against the long end of that window. So the check may start the
  departure, but nothing may ever *wait* for it: if the verdict has not arrived when the settled frame is
  analysed, the alignment simply proceeds and the verdict is recorded as "late". A check that the job waits
  for would turn a 3.6 % share of the cycle into a variance of the same size, which is the opposite trade.

The relation that killed the first attempt has partly reversed: the check is cheap enough to run during the
settling without disturbing it, and not cheap enough to be certain of an answer before the settling is over.

Do not resurrect `parallel-bottom-vision-wip` (`b0fb281577`): it analyses with a *second* pipeline
cache of its own (`getOrCreatePreCheckPipeline`), runs `preparePipeline()` on the fly, and calls
`processCompositeResult()` on a shot that will be computed again two frames later. Keep three things
from it and nothing else:

- the first-frame callback on `AbstractSettlingCamera.settleAndCapture()` - the hook is right and it
  is 20 lines;
- the shape of `PreAlignmentCheckResult` (present / size / confidence / duration / nozzle);
- the discipline of writing the timing into the log from the first commit.

## 3. What the check is allowed to claim

The frame the pre-check sees is exposed before the head has come to rest. The cameras are global
shutter so the frame is sharp; what is not known is *where the tool was*, because what settles is the
play in the belts and the nozzle holder, not the stepper (`PRE-CHECK-ASSESSMENT.md` §5).

| question | answerable from the first frame |
|---|---|
| is a part held at all | yes |
| is it the size the package expects | yes - a distance inside the image, the head position cancels |
| is it turned | yes, same reason |
| how far is it off centre | **no**, wrong by however far the head had not yet stopped |

So requirement 1's "is it roughly straight" is answerable, "is it roughly in place" is answerable only
as *inside a ring wide enough to contain the settle*, and nobody has measured that ring. `M114`
(`GcodeDriver.getReportedLocation()`) does not help: it is a round trip and it reports the motors, not
the tool.

Consequence for the first version: judge presence, size and rotation, measure the offset and **report
it without judging it**, until one real run has produced the first-frame-to-settled-frame displacement
in millimetres. That measurement is §6 step 3 and it is the only part of this that cannot be
simulated.

## 4. Where it goes in the code

All of it is inside the parallel path, which already excludes multi-shot parts before it captures
anything (`needsMultipleShots()` at `ReferencePnpJobProcessor.java:2131` and `:2197`), so requirement 5
is inherited rather than implemented - with one caveat: a part with compositing *configured* but only
one shot needed takes the single-shot path, and it must not, because the pre-check would judge a frame
that the multi-shot pass then re-takes. The exclusion test therefore wants "compositing enabled", not
"needs multiple shots".

| place | change |
|---|---|
| `AbstractSettlingCamera` | overload `settleAndCapture(SettleOption, Consumer<Mat>)`; invoke it with the **first** frame only, and have it hand the frame to the vision executor and return. `Mat`, not `BufferedImage` - the April snapshot handed out a `BufferedImage` and made the consumer convert it back. |
| `ReferenceBottomVision.captureImageOnlyWithSettle()` | pass a callback that runs the part's own pipeline against the pre-captured shot on `getVisionExecutor()`, the way `processPreCapturedImage()` already does, and stores the verdict on the `PreCapturedPlacement`. No second pipeline cache: the pipeline of the alignment, with its crop, is the pre-check. |
| `PreCapturedPlacement` | carries the verdict and the three durations (§5). |
| `ReferencePnpJobProcessor.preCaptureAllImages()` | nothing new to call; it already owns the only settle in the step. |
| `ParallelAlign.alignAllParallel()` STEP 2 | the existing "any placement without a shot → sequential for all" condition gains "or any pre-check says no". Same fallback, same code path, already tested by `JobAbortTest` and `ParallelAlignmentTest`. |
| `ParallelAlign.alignAllParallel()` STEP 4 | do not `join()`; hand the futures to the placements, issue the offset-independent Z retraction, and join in `getPlacementLocation()` (`:2766`), which is the first thing that truly needs the offsets. |
| late failure | the alignment of one placement fails after the head has left: stop, move back to the vision position, align that placement sequentially, continue. This is a designed path with its own log line, not an exception that happens to find the head somewhere else. |

Two rules follow from §2 and belong to the shape of the code rather than to its configuration:

- **The callback never analyses.** It clones the `Mat` and submits the work. The settling loop's own
  iteration is 3 ms and it is waiting on frames, so a pre-check on the capture thread is the one way
  measured to be harmless that could still be made harmful.
- **Nobody waits for the verdict.** At p90 the check outlives the short end of the settle window, so the
  verdict is something the job notices if it is there, not something it blocks on. The check's whole value
  is being early, and a check that is waited for is worth nothing at all.

The STEP 4 change is the risky one and it is not a vision change. `Align` and `Place` are states of a
retrying state machine; `plannedPlacement.alignmentOffsets` is written by one and read by the other,
and a placement whose offsets have not arrived yet has to be re-read against `Job.Placement.Starting`,
the nozzle-order optimisation and the prerotate-for-place step before it is trusted. That is R6
territory, the least exercised part of this branch, so it goes in **behind a setting, default off**,
and only after the runs A/B/D and the abort/retry checks of `MACHINE-TEST.md` have given a baseline to
compare a failure against.

## 5. Outcomes, exhaustively

| pre-check | true alignment | head has left? | action |
|---|---|---|---|
| ok | ok | yes | nothing. This is the normal case. |
| ok | fails | yes | return to the vision position, sequential alignment, then the existing error/retry path if that fails too. |
| ok | fails | no | existing behaviour, unchanged. |
| says no | - | no | sequential alignment for all, from the vision position, nothing to undo. |
| says no, but only after the settled frame was analysed | - | no | the verdict is late, which is normal (§2); log it and let the alignment decide. |
| timed out | - | no | **fail open** (R6): treat as ok, log it. A check that is broken must never stop a job that would otherwise have run. |
| errored | - | no | fail open, log it. |

Fail-open and "a failed check means sequential" are not in contradiction: what fails the check is a
confident verdict (a contour found whose size is outside the tolerance, or a frame in which the part
is confidently absent), and everything inconclusive is not a verdict. A check that gates motion has to
be lazier about saying no than a check that only prints.

For the first version the action on a failed check is a setting, not a hard-wired behaviour:

    pre-check-action = report | sequential | error     (default: report)

`report` keeps R7: the code runs, the log fills, the machine behaves exactly as it does today. It is
also the only mode that can be exercised on the simulated PC without a part on the nozzle.

## 6. Logging

The rule the April attempt broke: **capture, settle and pipeline are three numbers, never one sum**
(`PRE-CHECK-ASSESSMENT.md` §4). One line per shot, debug gated like the rest of the diagnostics, next
to the existing `[ALIGN-TIMING]` line:

    [PRE-CHECK] nozzle=N1 camera=Upp1 part=C0402-22p/50V frame=1 capture=4.1 settle=38.2 check=3.0
      size=0.41x0.20 expected=0.40x0.20 rot=11.4 offset=0.32 verdict=ok action=report

`align_table.py` grows a pre-check column so that a run can be read as one line per shot as it already
is for the alignment.

## 7. Measuring on the OpenPnP PC

The PC that runs the machine (`pn52`, Ryzen 7 5800H, 16 threads, governor `performance`) is where the
cost has to be measured, because it is where the check will run, with the bundled OpenCV and the pipeline
the machine really uses. `~/opencode_tests` on it holds the fork's `classes` and `lib`, the `R0402`
pipeline pulled out of the machine's own configuration (`make_pipeline.py`) and a real bottom vision
frame (`in/frame.png`).

**Done: `bench/PreCheckBench.java`.** No hardware, repeatable in a minute, and it feeds the pipeline the
way the product does - an `ImageCapture` stage handed a `CapturedShot` - rather than reading a PNG, which
is what `Benchmark.java` does and which would have added a decode that the pre-check does not pay:

    javac -cp "classes:lib/*" -d out PreCheckBench.java Benchmark.java
    java  -cp "out:classes:lib/*" org.openpnp.vision.pipeline.PreCheckBench \
          p_run2.xml in/frame.png 60 0.0386 0.0341 2.83 30 all

The phases are `check` (the pre-check, alone and two at once), `settle` (the settling loop's own
arithmetic and the frame interval), `load` (the same, with two pre-checks running on other threads) and
`read` (the capture-from-disk path, to attribute a cost or a crash to one of them). The numbers it
produced are in §2, and one of its own measurements is worth repeating here: a pipeline that is parsed
from XML per call instead of reused costs about the same again, which is how the April attempt could
report a pre-check of 50-70 ms for a check that costs 20.

**Not done: the simulated machine, real cameras.** `Main.java:176` honours `-DconfigDir=`, so a second
configuration directory can be run beside the real one:

    cp -a ~/.openpnp2 ~/.openpnp2-precheck
    # edit machine.xml: driver communications -> SimulatedCommunications

    java -DconfigDir=/home/micke/.openpnp2-precheck -jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar

Motion is simulated (`SimulatedCommunications` answers the gcode without moving anything), the up
cameras, their lighting, the settle loop, the pipelines, the job processor and the fallback logic are
all the real thing. That proves the things §2 could not: that the hook fires on a real frame, that the
`sequential` action really falls back, that the multi-shot exclusion really excludes, and that the
early-departure plumbing survives offsets arriving late. It needs only the production GUI stopped, since
the v4l2 devices are exclusive.

**What cannot be simulated there.** Nothing moves, so there is no belt play and no motion-induced settle:
the window (35-60 ms) and the first-frame displacement (§3) are machine-only numbers, and the part has to
be presented by hand for the camera to see anything. A run there showing a comfortable pre-check is
therefore *not* a claim that it fits the window; that claim needs one real run, and it is why §3 refuses
to judge the offset until then.

### Repeating the pipeline corrupts the native heap: upstream code, any JVM, and the machine runs it

`PreCheckBench` found something that was not what it was looking for and that matters more than the
pre-check: running the same pipeline many times corrupts the native heap, and the crash lands in the
allocator rather than anywhere the Java code can be blamed for it.

    free(): chunks in smallbin corrupted
    malloc(): unaligned tcache chunk detected
    # A fatal error ... Problematic frame: # V [libjvm.so+0x7a453c]

The base rate on this PC. A batch is *n* runs of the pipeline of `R0402`; "concurrent" is two pipelines on
two threads, which is what the parallel alignment does today and what a pre-check would do twice as often:

| path | Temurin 21.0.12.1 | OpenJDK 11.0.32.1 | OpenJDK 25.0.3 |
|---|---|---|---|
| handed a pre-captured shot, one at a time, 600 runs | **died, 4 of 4** | not run | survived, 4 of 4 |
| the same, then 600 pairs two at a time | died, 4 of 4 | not run | survived, 4 of 4 |
| capture from disk (no `CapturedShot` at all), 600 runs | **died, 4 of 4** | survived, 4 of 4 | not run |
| the same, then 600 pairs two at a time | died, 4 of 4 | survived, 4 of 4 | not run |
| batches of 40 pairs | died, 10 of 10 | survived, 10 of 10 | survived, 10 of 10 |
| batches of 150 pairs | - | **died, 1 of 6** | survived, 6 of 6 |

Five things follow from that table, and the first guess it kills is the comfortable one: it is not the
fork's doing.

- **Concurrency is not required for it.** Temurin 21 dies in the loop that runs one pipeline at a time, on
  the same thread, with nothing else in the process.
- **The fork's pre-captured shot is not required either.** The disk path, which never touches
  `CapturedShot`, dies the same way on the same JVM. Neither is it the crop: with `CropCentered` disabled it
  still dies.
- **Upstream OpenPnP dies on it too.** `bench/StockLoop.java` is one file with no fork code in it: it builds
  a pipeline of camera-free stages, calls `resetReusedPipeline()` and `process()` in a loop, and says whether
  it got to the end. Compiled once against `upstream/main` (`5bd404cfc7`) and once against this branch, with
  the same source, the same frame and the same JVM, both die. With **one stage**, a plain `ImageRead`, 300
  runs kill 3 of 3 attempts on Temurin 21.
- **OpenCV by itself does not die.** `bench/RawLoop.java` performs the same work - decode, colour convert,
  blur, threshold, find contours, draw contours, release - called directly, no `CvPipeline`, no XStream, no
  stages: clean 4 of 4 on Temurin 21 and 4 of 4 on OpenJDK 25 here. On OpenJDK 17 it died 4 of 4, so the
  JNI boundary is somewhere in the story, but the pipeline machinery decides whether you meet it.
- **The product's own tests reproduce it, and no single test does.** Six vision test classes in one Surefire
  fork abort 4 of 4 runs on Temurin 21 *and* 4 of 4 on OpenJDK 25, always with the same glibc line in the
  dumpstream; run one class at a time, all twelve combinations pass. It is cumulative, which is why a machine
  that runs eight pipelines per job can live with it and a test suite that runs them by the hundred cannot.

| here (workstation) | Temurin 21.0.12.1 | OpenJDK 25.0.3 | OpenJDK 17 |
|---|---|---|---|
| `StockLoop`, upstream classes, 6 stages, 600 runs | died, 4 of 4 | died, 4 of 4 | died, 4 of 4 |
| `StockLoop`, upstream classes, 1 stage (`ImageRead`), 300 runs | died, 3 of 3 | - | - |
| `StockLoop`, fork classes, 6 stages, 600 runs | died, 4 of 4 | - | - |
| `RawLoop`, no OpenPnP at all, 600 runs | survived, 4 of 4 | survived, 4 of 4 | **died, 4 of 4** |
| six vision test classes, one Surefire fork | died, 4 of 4 | died, 4 of 4 | - |
| any one of those classes alone | survived, 2 of 2 | - | - |

Two more mechanisms went the way of the first guesses. `bench/ImgLoop.java` is the decode plus the clone
that `process()` makes, with the release done five different ways - the decode alone, both, either one left
to the finalizer, neither - and all five modes survive 600 runs on 21 and on 25, on both boxes; `imread`,
`clone` and `release` are clean in every combination until `CvPipeline` puts them inside a stage. And
`StockLoop 300 1 fresh`, which builds a pipeline per run so that `process()` has nothing of a previous run
left to release, dies as often as the reused one, which clears the bookkeeping across runs and leaves the
stage machinery - `processPrepare()`, `process()`, the `Result` plumbing - as the only thing that is in the
dying program and not in the surviving one.

What has been excluded as the mechanism, each by an experiment that failed to stop the death: the JIT
(`-Xint`); JEP 374 critical arrays (`-XX:-CriticalJNINatives`); memory pressure and `Mat` finalizer timing
(`-Xmx10g -XX:+DisableExplicitGC`); and a double `release()` of the same `Mat` wrapper, which is the first
thing an inspection of `CvPipeline` suggests - `process()` calls `release()` at its top
(`CvPipeline.java:262` upstream) and thereby releases result `Mat`s whose wrappers the finalizer will
release again - but `bench/DoubleRelease.java` does exactly that, 300 times, churning up to 512 other `Mat`s
between the two releases, on two JVMs, and nothing happens.

The JVM and the machine change the odds and not the verdict, and on the machine's own box the odds are no
better than here. The two loops were run there as well, on each of the three JDKs it has installed, three
attempts each:

| the OpenPnP PC, which boots Temurin 21 | Temurin 21.0.12.1 | OpenJDK 11.0.32.1 | OpenJDK 25.0.3 |
|---|---|---|---|
| `StockLoop`, one `ImageRead` stage, 300 runs | died, 3 of 3 | died, 3 of 3 | died, 3 of 3 |
| `StockLoop`, six stages, 300 runs | died, 3 of 3 | died, 3 of 3 | died, 3 of 3 |
| `ImgLoop`, the same work by hand, 600 runs | survived, 3 of 3 | survived, 3 of 3 | survived, 3 of 3 |

OpenPnP compiles to Java 11 bytecode, so 25 does run it and the pipeline path has been exercised on 25
here, but it is not the JVM the machine is validated on, the dependency set is 2011-2020 vintage (batik 1.7,
simple-xml 2.9, bridj, jython) and 25 additionally warns about the `System.load` of the OpenCV native, which
wants `--enable-native-access=ALL-UNNAMED` in `openpnp.sh`. None of that is worth trading while the crash
follows every JVM.

**Changing the JVM on the machine buys nothing.** The earlier survivals of 11 and 25 there were the `R0402`
pipeline, which is a different workload and a different number of runs; that is luck, not immunity, and this
table is the one to plan on. Everything else in this section reads the same for all three.

**The machine is exposed to it.** `eselect java-vm list` on the OpenPnP PC selects `openjdk-bin-21` as both
system and user vm, `openpnp.sh` starts a bare `java`, and the jar it starts is
`/home/micke/openpnp/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`. At the bench's rate an 8 part job is about one
draw per 75 jobs' worth of exposure, which is rare enough to have been put down to something else and not
rare enough to ignore; no `hs_err_pid*.log` exists in the home directory, in `/tmp` or beside the jar, so
there is no evidence either way yet.

To reproduce it in a minute, which is enough to file it upstream:

    git worktree add /tmp/upstream upstream/main
    cd /tmp/upstream && mvn -o -q -DskipTests package
    mvn -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
    javac -cp "target/classes:$(cat /tmp/cp.txt)" -d out /path/to/bench/StockLoop.java
    java  -cp "out:target/classes:$(cat /tmp/cp.txt)" StockLoop 300 1     # one ImageRead stage, dies
    javac -cp "$(cat /tmp/cp.txt)" -d out /path/to/bench/ImgLoop.java     # no OpenPnP at all
    java  -cp "out:$(cat /tmp/cp.txt)" ImgLoop 600 0                      # survives, same box, same java

What would settle it properly is a sanitizer - `valgrind`, or an ASAN build of the OpenCV natives, running
that loop - which is its own piece of work, and beyond anything the pre-check needs.

Until it is settled the design consequence is narrow but real: a pre-check doubles the number of pipeline
runs per cycle, so whatever this is, the check makes it twice as likely. That is why §8 puts it before the
check.

## 8. Order of work

1. ~~Bench the pre-check's cost~~ done, §7. The verdict is not the one the April attempt expected and not
   the one §2 hoped for either: cheap enough not to disturb the settling, too slow to be counted on inside
   it. The design consequence is in §4: never block on the check.
2. The repeated-pipeline crash of §7, because the pre-check doubles the runs it is made of. Done as far as
   the questions "is it ours?" and "can we dodge it?" go - it is not ours, and no JVM dodges it, so the
   machine's JVM stays where it is. `bench/StockLoop.java` reproduces it against `upstream/main` with a
   pipeline of one stage and `bench/ImgLoop.java` survives the same work without OpenPnP; both belong in the
   upstream report. What is left is the part that needs a sanitizer.
3. The callback and the check, `report` mode only, the three numbers in the log. Verified on the simulated
   machine with the real cameras.
4. One real run of the 8 part job reading the first-frame displacement, which is the number §3 needs
   before the offset can be judged and before any tolerance on it is invented.
5. Runs A/B/D and the abort/retry checks of `MACHINE-TEST.md` - the baseline for the state machine.
6. Early departure behind its setting, default off, with the late-failure return path written first and
   tested on the simulated machine before it is ever armed.
7. `pre-check-action = sequential`, then `error`, each only after runs at the level before.

## 9. Open questions

- The tolerance on size: the part's package has `width`/`height`, and `ReferenceBottomVision` already
  compares the detected rect against them for the *alignment*. Should the pre-check use the same
  `check-size` tolerance and setting, or its own, looser one? The same number is one less thing to
  maintain; a looser one is safer against an unsettled frame.
- Does the head need to have left at all for the pre-check to be worth anything, or is the re-pick
  saving (1.4 s) enough on its own? If the latter, steps 3 and 4 are the feature and 6 is a separate
  project.
- Rotation: the machine deliberately corrects a turned part and has no rotation tolerance (decided
  2026-09-29, `LATER-TESTS.md`). A pre-check that reports rotation must not be read as a rejection
  rule; confirm that "turned but right size" is a pass for the pre-check.
- If the verdict is late more often than not - which §2 says is likely - is a check that only sometimes
  knows early worth a new stage in the settling loop at all, or should the same verdict simply come from
  the alignment of the settled frame, which knows everything except the time it takes?
