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

### The native heap is corrupted, and only Temurin 21 notices: confirmed on the machine

`PreCheckBench` on the OpenPnP PC, `check` phase, `p_200.xml`, 200 reps, one thread at a time, run five times
on each of the three JDKs that PC has:

| the OpenPnP PC, `PreCheckBench check`, 200 reps, 5 attempts | 21.0.12.1 | 11.0.32.1 | 25.0.3 |
|---|---|---|---|
| result | **aborted, 5 of 5** | survived, 5 of 5 | survived, 5 of 5 |
| median pre-check | never printed, it dies first | 2.21 ms | 2.20 ms |

The abort is in the allocator and not in Java, and it is not the same message twice:

    free(): chunks in smallbin corrupted        corrupted double-linked list
    malloc(): invalid next size (unsorted)      corrupted size vs. prev_size while consolidating
    free(): corrupted unsorted chunks

Here on think3 the same thing happens to the product's own tests: the six vision test classes in one Surefire
fork abort 2 runs out of 2 with that first glibc line in the dumpstream, while each class on its own passes
(6 of 6) and either half of three passes (3 of 3). It is cumulative work in one process, not one test.

**It is Temurin 21; no other JVM tested aborts it reliably.** Which JVM the machine itself uses turned out to
be a different question, and the answer is not the one first written here. The OpenPnP PC is started from
`/home/micke/openpnp/OpenPnP`, an Install4j launcher, and Install4j never asks `eselect`: it walks
`common_jvm_locations` (which includes `/usr/lib/jvm/*`) and takes the first entry inside a hard-coded window -
`ver_major >= 11` and `ver_major <= 23`. Traced with `sh -x`, that resolves to `openjdk-bin-11`, and the jar is
the 2025-03-07 `/home/micke/openpnp/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`. So the machine has been running
OpenJDK 11 - which survived 5 of 5 here at 200 reps and died 1 of 6 in the 150-pair batches - not Temurin 21.
`eselect` only decides what a bare `java` gets: every bench run in this document, and the fork's `openpnp.sh`.
(Read "only Temurin 21" below as *how often*, not *which JVM* - see "It is not the JVM" further down.)

No `hs_err_pid*.log` exists in the home directory, in `/tmp` or beside the jar, so nothing says the corruption
has happened during real work on any JVM.

So: **changing the JVM is a measured mitigation, not a cure.** The obstacle to doing it is not the flags -
both the Install4j launcher and `openpnp.sh` pass the same three `--add-opens`, all of which 25 accepts - it is
the launcher's `ver_major <= 23` ceiling, which silently declines 25 and falls back to 11;
`INSTALL4J_JAVA_HOME_OVERRIDE` is ignored until the ceiling moves. `openpnp.sh` passes only
`--add-opens` flags, all of which 25 still accepts, and 25 would want `--enable-native-access=ALL-UNNAMED` for
the `System.load` of the OpenCV native (a warning today, blocked in some future release). Two things to know
before switching a machine that works: the fork's tests cannot run on 25 at all, because the Surefire `argLine`
in `pom.xml` passes `-XX:GCLockerRetryAllocationCount`, which 25 no longer recognises, so the build stays on
21 while the runtime moves; and one real job has to be run on the new JVM before it is trusted, because the
camera and vision timing there has never been measured.

Retracted, twice over, in fairness to the record:

- **`StockLoop`, "upstream dies too".** `StockLoop` was the intended minimal repro and it "died" on every JVM
  on both boxes. It was never running a pipeline: the XML was wrong (`<cv-stage>` must sit inside a `<stages>`
  element, the attributes are hyphenated, `Threshold` requires `auto`), simple-xml threw, the JVM exited 1 and
  the harness counted exit statuses, so a `ValueRequiredException` was logged as a segfault. With the XML fixed
  and the harness distinguishing an exception (exit 2, message printed) from a signal, the same loop is the
  cleanest thing here: 600 runs of one `ImageRead` stage and 300 of five stages survived 3 of 3 attempts on
  Temurin 21, on 25, on think3 and on the OpenPnP PC. Repeating a `CvPipeline` is not the problem.
- **"OpenJDK 25 crashes the tests too."** The 25 fork never started: `Unrecognized VM option
  'GCLockerRetryAllocationCount=100'`, exit 1, which was read as a crash. The first valgrind log is void for
  the same kind of reason (wrong class name, nothing ran).

What the tools say now. Valgrind 3.27.1, twice on the machine against the `PreCheckBench` repro, once with the
JIT and once with `-Xint`: 1801-2319 errors, every one of them the JVM's own startup writes below its stack
pointer inside `libjvm`, none in `libopencv_java455.so`, and **the run under valgrind does not abort**. So
memcheck, which replaces the allocator, cannot see it, while the glibc allocator aborts on the same work. That
points away from a wild write in OpenCV and towards something in the 21 runtime's use of the allocator, and it
is why ASAN - which would need OpenCV rebuilt from source, since the jar ships a 2022 prebuilt native - is not
yet worth the day it costs.

Context that may or may not matter: both boxes moved to glibc 2.43 recently (think3 2.43-r2, the OpenPnP PC
2.43-r4, whose system update was shortly before this was first seen), while `libopencv_java455.so` in the
`org.openpnp:opencv:4.5.5-1` jar was built against glibc 2.27 and bundles its own libjpeg and libpng. The JVM
correlation is much stronger than this one, since 11 and 25 are clean on the same glibc, but an older-glibc run
is still worth doing now that a real repro exists (docker on think3, `eclipse-temurin:11-jdk-jammy`, glibc
2.35, against `PreCheckBench`).

The controls that were clean before and are clean now, so none of them is the mechanism: `ImgLoop`, `imread`
plus `clone` with the release done five different ways, 600 runs on 21 and 25 on both boxes; `RawLoop`, the
whole contour chain called directly without `CvPipeline`; `DoubleRelease`, releasing the same `Mat` wrapper
twice with up to 512 other `Mat`s churned in between; and `-Xint`, `-XX:-CriticalJNINatives`,
`-Xmx10g -XX:+DisableExplicitGC` and `MALLOC_CHECK_=0`, none of which stopped the abort on 21.

### It is not the JVM: a pipeline built per run corrupts the heap on 21 *and* on 25

Six `hs_err_pid*.log` files sat in `~/opencode_tests` the whole time, all Temurin 21.0.12.1, all SIGSEGV - the
artefact §7 said was missing. Only one is complete (`hs_err_pid53506.log`); it names the faulting frame:

```
C  [libopencv_java455.so+0x406c20]  cv::Mat::release()+0x50
j  org.opencv.core.Mat.release()V+4
j  org.openpnp.vision.pipeline.CvPipeline.release()V+64
j  org.openpnp.vision.pipeline.CvPipeline.finalize()V+1
j  java.lang.ref.Finalizer$FinalizerThread.run()V+29
Current thread: JavaThread "Finalizer" daemon
```

Every readable command line in those logs is the `read` phase, which never hands a pipeline a pre-captured
shot. So the pre-check is not the cause, and to find out what is, the bench now varies how pipelines are made
and run (`p_run2.xml`, the machine's own `R0402` pipeline, `in/frame.png` 1080x926, 3 attempts of each):

| what the process does | 60 reps on Temurin 21 | 60 reps on Temurin 25 |
|---|---|---|
| one pipeline, reset + reconfigure + `process()`, 200 reps (`reuse`) | survived 3 of 3 | - |
| one pipeline, no reset (`noreset`) / configured once (`nocfg`) | survived 3 of 3 | - |
| build 60 pipelines, process only the warm-ups (`freshbuild`) | survived 3 of 3 | survived 3 of 3 |
| build a pipeline and process it, over and over (`fresh`) | **aborted 6 of 6** | **aborted 2 of 3**, at about rep 50 |
| the same, stages configured once (`freshnocfg`) | **aborted 3 of 3** | **aborted 3 of 3** |
| `fresh`, each pipeline `release()`d explicitly (`freshrel`) | **aborted 3 of 3** | - |
| `fresh`, every pipeline kept alive so nothing is finalised (`freshkeep`) | **aborted 3 of 3** | - |

Building a pipeline is innocent; running one that was just built is not, and keeping them alive, releasing them
by hand, or configuring them once does not help. 25 does the same thing about twice as slowly - which is the
answer to the question this document has been circling: **the JVM switch changes how long it takes glibc to
notice, not whether the heap is being corrupted.** What has kept the machine alive is that the production path
reuses one pipeline per part instead of building one per operation, and the first row above is that path: 200
reps, clean.

The mechanism is visible in the wrapper the fork ships (`javap` on `org.openpnp:opencv:4.5.5-1`):

```
protected void finalize() { n_delete(nativeObj); }     // org.opencv.core.Mat
public      void release() { n_release(nativeObj); }   // and nativeObj is never cleared
```

and in ours, `CvPipeline.release()` (CvPipeline.java:367) releasing `workingImage` plus every `result.image`
**without nulling `result.image`**, called again from `CvPipeline.finalize()` (CvPipeline.java:389). A `Mat`
that is released, deleted, and then reached a second time produces exactly the observed frame and exactly these
glibc messages. The newest logs, from the `fresh` runs, are 0 to 20 lines long: the crash handler itself dies,
and the recorded `pc` is nonsense (`0x0000001500000011`), so the heap is long wrecked by the time anything
notices.

Things ruled out along the way, so nobody repeats them: `-Xcheck:jni` reports nothing (only the native-access
warning); `MALLOC_CHECK_=3` changes nothing; and `-XX:-RegisterFinalizersAtInit` does **not** disable
finalisation - `bench/FinalCheck.java` still sees 20000 of 20000 finalisers run - which also shows that 21 and
25 finalise equally and reclaim the native frames equally (RSS flat over 1000 undropped 2929 KiB frames on
both), so 25 is not leaking the Mats the way it would if finalisation had gone away. JDK 11 cannot run these
variants at all (the bench is now compiled as class file v65); its earlier numbers belong to the reuse-based
phases only.

Consequences, in order of importance:

1. The pre-check must reuse a pipeline per part, as the alignment path does. Building one per shot would put
   the machine on the row that aborts on both JVMs.
2. `CvPipeline.release()` should null `result.image` (and `workingImage` is already nulled) and `CvPipeline`
   should stop relying on `finalize()`; the smallest experiment worth running after that is `fresh` ten times
   on Temurin 21, which is currently 6 of 6.
3. "Temurin 21 is the problem" is now a statement about frequency, not about which JVM. §7 above should be read
   that way.

### The machine is on Temurin 25 now, and what that costs

Switched 2026-09-29, in the only place that reaches the machine: the Install4j launcher now sets
`INSTALL4J_JAVA_HOME_OVERRIDE=/opt/openjdk-bin-25` and its ceiling reads 26, so `sh -x` ends in
`exec /opt/openjdk-bin-25/bin/java ... install4j.org.openpnp.Main` (before the ceiling moved it still exec'd
`openjdk-bin-11`), and `OpenPnP.vmoptions` gained `--enable-native-access=ALL-UNNAMED`. Both files have
`.bak-2026-09-29` copies beside them and `SMOKE-JVM25.md` says what to watch when it is next started.
`eselect` is set to 25 as well, which matters for benches and `openpnp.sh` but not for the launcher. Confirmed
on the machine, the workload that killed Temurin 21 five times out of five inside 200 reps:

| Temurin 25.0.3 on the OpenPnP PC, `PreCheckBench check` | result |
|---|---|
| `p_200.xml`, 600 reps | survived, 2 of 2, median 2.18 ms |
| `p_200.xml`, 2000 reps | survived, median 2.07 ms |
| `p_run2.xml` (the real `R0402` pipeline), 300 reps | survived, 2 of 2, median 16.25 ms |

`bench/ConfigSmoke.java` reads the machine's own `vision-settings.xml` with the XStream setup OpenPnP uses and
writes it back: 40 settings, 188080 characters, byte for byte the same on 21 and on 25, which is the reflection
layer that a new JVM breaks first. `machine.xml`, `packages.xml` and `parts.xml` cannot be read headless at all
(`java.lang.Error: Configuration instance not yet initialized`), so the remaining proof is the plain one: start
OpenPnP on 25, load the configuration, run one job.

What switching to 25 costs:

1. The tests cannot run on it. `pom.xml` hands Surefire `-XX:GCLockerRetryAllocationCount`, which 25 no longer
   recognises, so the fork dies on the command line before any test runs. The build and the test suite stay on
   21 (or 11) until that flag is made conditional - a small change, and one that would also stop the suite
   aborting here, since 21 is the JVM that aborts.
2. `openpnp.sh` should gain `--enable-native-access=ALL-UNNAMED`: `nu.pattern.OpenCV$LocalLoader` calls
   `System.load` from an unnamed module, which warns on 25 and will be blocked in a future release. Its three
   `--add-opens` are all still valid.
3. The rest of the stack is not validated on 25 by anyone: `jSerialComm` 2.10.2, `webcam-capture` 0.3.10 with
   `bridj`, `jython` 2.7.1, `nashorn-core` 15.4, `batik` 1.7. The vision settings round trip says the XStream
   layer is fine; the serial port, the scripting console and the GUI are only proven by running it.
4. It treats the symptom. The corruption is still there - 11 and 25 just do not hand glibc something it
   notices, and valgrind cannot see it either - so a future 25.0.x could start aborting, and the report to
   Temurin and upstream is still owed.
5. The timing on the machine has never been measured on 25. The pre-check is slightly *faster* there (16.25 ms
   against 20.3 ms on 21, §2), but the camera path, the settle loop and the GUI run in this same JVM.

think3 keeps 21: everything that runs here is a build or a test, and 21 is what the pom's flags still start.

### The fix: CvPipeline must not release Mats from a finalizer

`finalize()` is gone from `CvPipeline` (CvPipeline.java:383). `release()` and `close()` are unchanged, and every
vision operation already closes its pipeline - commit 16ab399fcc, which added the finalizer, describes it as "a
final bid to release Mats", i.e. a belt added on top of try-with-resources. The belt is what kills us: Mats own
themselves (`Mat.finalize()` deletes the native object) and `Mat.release()` never clears the pointer it was
handed, so whichever of the two finalizers loses the race calls into memory that the other one has already
given back to the allocator.

Measured, always on the real `p_run2.xml` pipeline:

| workload | with the finalizer | without it |
|---|---|---|
| `fresh`, pipeline built and run, 60 reps, Temurin 21 | aborted 6 of 6 | clean 10 of 10 |
| `fresh`, same, Temurin 25 | aborted 2 of 3 | clean 3 of 3 |
| `check` phase, 200 reps, Temurin 21 (the shape that started this) | aborted 5 of 5 | clean 5 of 5 |
| `reuse`, one pipeline, 200 reps | clean | clean |
| `CvPipeline` finaliser runs at all | yes, 20000 of 20000 | no, and Mats still do: RSS flat |

And the repository's own tests, forked JVM `openjdk-bin-21`, same command before and after:

- before: the eight vision classes start, six finish, then `The forked VM terminated without properly saying
  goodbye. VM crash or System.exit called?`
- after: 40 of 40 pass, and the **whole suite is green - 298 tests, 0 failures, `BUILD SUCCESS`**, which it has
  not been once during this investigation.

One caveat worth stating: a pipeline that is neither closed nor finalized now holds its Mats until GC collects
the pipeline and the Mats with it. `FinalCheck` shows the Mats do get reclaimed by their own finalizers (RSS
flat over 1000 frames released by nothing), but the reclaim lags: abandoning a pipeline per shot instead of
reusing one cost 6.5 GiB over 400 reps. Production reuses pipelines per part and closes them, and so must the
pre-check - which was already a requirement for a different reason (see above).

### log_f: three jobs on the fixed build, and a warm job is a different animal

`tests_2026-09-28/log_f.txt`, build `2.7_2026-09-30_05-47-35.1b02100`, three back-to-back jobs on the machine:

| run | job time | CPH | alignments | vision sum | median per alignment | p90 | max |
|---|---|---|---|---|---|---|---|
| 1 (cold) | 11.6 s | 2476.6 | 8 | 97.4 ms | 12.40 ms | 15.80 | 17.10 |
| 2 | 10.6 s | 2707.5 | 8 | 29.9 ms | 3.60 ms | 4.10 | 5.10 |
| 3 | 10.6 s | 2704.5 | 8 | 37.7 ms | 3.50 ms | 8.60 | 9.30 |

No exception, no warning, no `hs_err_pid*.log` anywhere on the machine after these runs (newest is still 22:24
the previous evening). Run 1 is the fair comparison with the two earlier single-run logs: 11.6 s against 11.8 s
on the same JVM before the fix, so the fix costs nothing.

The number that matters for this design is the median: **3.5 ms per alignment once the JVM and the allocator are
warm, against 12.4 ms cold.** Everything before this document was written measured only cold, single jobs, so
the pre-check budget (PRE-CHECK section 2) has been sized against a cost three to four times what a running
machine actually pays. Two things follow. One, the pre-check has more headroom than we thought, which strengthens
the case for doing it during the settle rather than after it. Two, and less comfortably: we do not know yet
whether warm vision has *always* been this fast or whether the finalizer removal contributed, because no log
before this one contains a second job. A pre-fix build run twice on the machine would answer it; it is not
worth reverting the fix to find out, but it should be kept in mind the next time a cold-start number is quoted
as a steady-state one.

## 8. Order of work

1. ~~Bench the pre-check's cost~~ done, §7. The verdict is not the one the April attempt expected and not
   the one §2 hoped for either: cheap enough not to disturb the settling, too slow to be counted on inside
   it. The design consequence is in §4: never block on the check.
2. The heap corruption of §7, because the pre-check doubles the vision work it is made of. It is real and it
   is Temurin 21: `PreCheckBench` on the OpenPnP PC aborts 5 of 5 there and 0 of 5 on 11 and on 25. Decision
   to take and to validate with one real job: move the machine's runtime JVM off 21. The hunt for the cause
   continues beside that - older glibc, a Temurin 21.0.12 report, ASAN-built OpenCV if both fail.
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
