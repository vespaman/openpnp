# Grabbing frames ahead of being asked for them

Status: implemented, off by default, one setting per camera. Enabled on pn52 since 2026-10-02 for the
run described in "Measuring it on the machine" below.

This is the detailed documentation of the frame ring: what problem it is a solution to, what the code
actually does, what it costs, what the tests prove and what they cannot. The measurement background -
why a frame costs what it costs on this machine - is in `CAPTURE-COST.md`; the item there is item 2 of
"What to do, ranked by saving per job".

## The problem, in one frame

`capture()` on a camera is a request and a wait. The asking and the exposing start at the same instant,
so the caller pays the exposure, the frame interval and the driver's own round-trip. On pn52 the first
frame of a settling costs 51 ms on the fiducial camera and 28 ms on a bottom camera, against a steady
state of 33 ms and 8.3 ms, because the very first ask finds no frame in flight. Across a job those cold
first frames are about 210 ms, and the second bottom camera's wait for one frame inside the first
camera's still window is worth another 38-80 ms.

Nothing about that is a Java problem. A frame that was exposed while the head was still travelling to
the camera would answer the question just as well, and would already have been paid for. The ring is the
machinery for having such frames around, and for being able to say honestly when each of them was taken.

## Switching it on

Four attributes on an `OpenPnpCaptureCamera`, all optional, all off/shy by default:

| attribute | default | what it is |
|---|---|---|
| `grab-ahead-enabled` | `false` | the switch. With it off no ring is even allocated and every question about buffered frames is answered "none" |
| `grab-ahead-depth` | `8` | how many frames the ring holds. Frames are full size, so this is the memory |
| `grab-ahead-session-ms` | `1500` | the ceiling on how long one grabbing session may run without being renewed |
| `grab-ahead-max-frame-age-ms` | `0` | how stale, in ms of *content*, a capture may be answered from when the caller states no bound of its own. `0` means two measured frame intervals, which is what a capture on demand always delivered |

On pn52, since 2026-10-02, all three cameras carry:

```xml
grab-ahead-enabled="true" grab-ahead-depth="8" grab-ahead-session-ms="1500"
```

The previous configuration is kept as `~/.openpnp2/machine.xml.before-grab-ahead`. There is no wizard
page for these yet; they are edited in `machine.xml` and appear in the camera's property sheet once the
file is reloaded. Turning the switch off returns the camera to exactly the old code path, because every
new call is behind either the switch or a "does this camera have a ring" question.

## The pieces

| piece | file | role |
|---|---|---|
| `FrameBuffer` | `machine/reference/camera/FrameBuffer.java` | the ring, the grabbing thread, the timestamps, the session, the statistics |
| `BufferedFrames` | `spi/BufferedFrames.java` | the capability a camera may answer: is it buffering, give me a frame not older than T, give me the earliest frame not older than T, begin a session, end one |
| `OpenPnpCaptureCamera` | `machine/reference/camera/OpenPnpCaptureCamera.java` | owns the ring and the stream; `internalCapture()` takes from the ring when the ring is being filled, and from the stream when it is not |
| `ReferenceCamera` | `machine/reference/camera/ReferenceCamera.java` | carries the instant of the last raw frame of this thread, `getRawFrameCapturedAtMs()`, so that a frame taken out of a ring can still tell when it was taken |
| `AbstractSettlingCamera` | `machine/reference/camera/AbstractSettlingCamera.java` | records its frame times from that stamp instead of from the return of `capture()`, and opens a session if nobody else has |
| `ReferenceBottomVision` | `machine/reference/vision/ReferenceBottomVision.java` | the hunt: `takeFrame()` asks the ring for the frame nearest the opening of the still window before it asks the camera for an exposure |
| `ReferencePnpJobProcessor` | `machine/reference/ReferencePnpJobProcessor.java` | begins the session before the head is sent to the alignment position of a parallel pre-capture batch and ends it when the batch is over |
| `ReferenceFiducialLocator` | `machine/reference/vision/ReferenceFiducialLocator.java` | same around a fiducial search, ended by the try-with-resources that closes the pipeline |

```
                            one per camera
  job processor ──begin──► FrameBuffer ──grab()──► CaptureStream ──► the device
  fiducial locator ─begin─►   ring      ▲
  settling ─────────begin─┘   │        │ hasNewFrame()/capture()
                              │        └── stamped by the grabbing thread
      settling.capture() ────► newest frame at or after "now"
      the hunt ──────────────► earliest frame at or after the window opening
```

## A session, not a stream that is always drained

The ring is filled only between `beginFrameBufferSession()` and `endFrameBufferSession()`. Three reasons,
all of them money:

- a 960x1080 `TYPE_3BYTE_BGR` frame is 3.1 MB and a 1920x1080 one is 6.2 MB. Draining the two bottom
  cameras at 120 fps continuously would move some 740 MB/s through the JVM to keep pictures that nobody
  looks at, and the garbage collector would be the place where it shows up.
- the light. A camera that is being drained is a camera whose frames are being produced; during the parts
  of the job where nothing is measured there is nothing to gain from that.
- the stream. While a session runs the ring's thread is the *only* consumer of the capture stream, and a
  caller that wants a frame asks the ring instead of reaching past it. Two consumers pulling at one
  stream is how frames get lost without anybody noticing. This is why `suspend-preview-in-tasks="true"`
  matters: with the preview alive during a job, the preview would be the second consumer. (With the
  feature off nothing changes: the preview and the capture take turns at the stream exactly as before.)

Sessions nest by deadline: a `begin` while one is running takes the later of the two end times, which is
what a settling that starts inside a camera batch should do. `endSession()` on a camera that was never
begun does nothing. A caller that forgets to end costs `grab-ahead-session-ms` of grabbing and nothing
afterwards, because the session expires on its own - which is also what happens if it throws on the way
out. `open()` and `close()` of the stream throw the ring away entirely: the frames in it are of the video
format that was set at the time, and one of them must not be handed out later as if it were of whatever
the format is now.

## The grabbing thread

One daemon thread per camera, per session (`camera-grab-<camera name>`). Its loop is what
`internalCapture()` used to do - `hasNewFrame()`, then `capture()` - with nobody waiting, so it neither
spins nor blocks a caller, and with the frames kept rather than thrown away:

- when the source has nothing new the thread parks for 0.5 ms (`POLL_PARK_NANOS`). That is far finer than
  any frame rate and far coarser than a spin.
- each frame is stamped with the instant `grab()` returned, unless the source can say better (see
  "Timestamps"), pushed into the ring, and waiters are notified.
- the ring never grows and never copies a frame: it is a fixed array of references and the oldest is
  dropped when it is full. The camera's own allocation is the only one made here.
- the last nine frame intervals are kept and their median is `getFrameIntervalMs()`, which is what bounds
  every wait anyone puts on the ring.
- five consecutive failures end the session and log a WARN. A stream that cannot produce a frame will not
  start again by being asked five thousand times a second, and the consumers fall back the moment the
  session is gone.

## Reading the ring

There are two questions, deliberately, because there are two callers and they want different things.

**"A frame of now", which is what `capture()` has always meant.** `internalCapture()` takes the newest
frame the ring holds, provided its content is not older than two frame intervals - `grab-ahead-max-frame-age-ms`
overrules that - and waits at most `min(max(20, 3 x interval), 250)` ms if even that is not there yet. A
consumer may raise the price for its own work: `BufferedFrames#setBufferedFrameNotBeforeMs(long)` says what
the frame has to show, and bottom vision says *the head had arrived*, so nothing taken during the approach
can be used as a picture of a head at rest. If the ring is not being filled the answer comes back "none"
immediately and the camera captures on demand exactly as before - so a camera with the feature off, or a
session that has expired, or a stream that has failed, behaves identically to the old code. This is why the
settling and the hundred other `capture()` callers did not have to change. This path is
`FrameBuffer.awaitNewestForCapture()`, and it hands over the frame **as the sensor made it**, because it
sits under `captureRaw()` and the camera's transform chain runs above it.

It is not asking for content from after the instant of the call, and *The buffer that answered nothing*
below is the account of why it cannot.

**"The frame nearest the opening of that still window", which is what the pre-capture hunt wants.** The
hunt asks for the *earliest* frame taken at or after the window's opening. The newest frame would also be
inside the window and would also be legal; it would also have had more time to drift. Before the window
has an opening to aim at, the hunt aims at the instant the head arrived, because a frame from before that
shows a field of view the nozzle is not in. This path is `FrameBuffer.awaitEarliestAtOrAfter()`, reached
through `BufferedFrames`, and it hands over the frame **transformed**, through the `transformImage` the
camera gave the ring when it was built: the same picture `capture()` would have produced.

That asymmetry is the whole of the difference between the two methods and it is why they have different
names rather than a boolean. See *The frame that was not the picture* below for what happens when the
second one forgets.

Both bounds are inclusive and neither is ever relaxed. What they are bounds *of* is the consumer's demand,
not the moment of the ask: two frame intervals of staleness is what a capture on demand always delivered,
and the instants that matter - the head having arrived, the still window having opened - are stated by the
code that needs them. Frames the consumer has refused do not leave the ring: a pre-check that judged a
frame from before the arrival would report a part that is not there, and an alignment that used one would
correct the head onto something it is not looking at. That refusal is the single most important property in
this document and it is the thing the offline tests spend most of their effort on.

 ## Timestamps, and what they license

The native capture API returns a picture and no exposure time. So the source is asked -
`Source.getCapturedAtMs()` - and `CaptureStream` answers 0, meaning "I cannot tell". A source that *can*
tell (a test that manufactures frames; a driver that ever reports the start of its exposure) answers with a
real instant and nothing else changes.

When the source cannot tell, the frame is stamped **one measured frame interval before the instant the
grabbing thread got it**, not at that instant:

> A frame is not as new as the paperwork. Its pixels were gathered during the frame interval that *ended*
> when the driver handed it over, so a frame returned at +2 ms after the head came to rest can show the
> head still on its way there and the light not yet switched on.

Every consumer asks some form of "is this frame from at or after that instant", so the stamp has to be the
earliest instant the content can be from. Getting this wrong was not theoretical - see *The run that
proved nothing* below - and it is the reason `awaitRawAtOrAfter(now)` in `captureFromRing()` waits about a
frame interval rather than returning the frame that happens to be sitting in the ring.

Two consequences worth having in mind:

- the interval used in the correction is measured between *returns*, never between stamps. The stamp is
  computed from the interval, and a number fed through itself wanders: the first attempt did exactly that
  and reported 36 ms for a camera running at 60 ms.
- the still window of a settling moves about an interval earlier for both cameras together, so the
  relationship between the settling camera's window and the other camera's frame is untouched while the
  absolute instants become honest.

What follows from the stamp, and what does not:

- a buffered frame's content is no earlier than its stamp, which is a fact about the world rather than
  about how long the caller's own path happened to be.
- a settling served from the ring records, for its still window, the instant a frame was *looking at the
  head* rather than the instant somebody's wait ended. `AbstractSettlingCamera` takes this from
  `ReferenceCamera.getRawFrameCapturedAtMs()`, which is per thread and is reset to `UNKNOWN` before every
  raw capture, and falls back to the return time when the camera has nothing to say. Durations
  (`first frame`, `capture`) stay wall-clock: they measure the cost of the call, which is a different
  quantity from when its pixels were taken.
- it is still not an exposure timestamp. It is a bound, deliberately one interval pessimistic, and the
  exposure lies somewhere inside that interval.


## Invariants

The list a reviewer should check against, and the test that covers each:

1. nothing older than the bound leaves the ring, and the bound is inclusive. `FrameBufferTest`, and the
   two hunt tests in `GrabAheadCaptureTest`. "Older" means older *content*, not older paperwork:
   `FrameBufferTest.testAFrameIsNotStampedWithTheInstantTheDriverHadIt` holds the grabber to it. The bound
   belongs to the consumer, and a capture that states none is held to two frame intervals of content
   rather than to the instant of the ask - see *The buffer that answered nothing*. A bound of the instant
   of the ask in particular is not answerable from a buffer, and both `FrameBufferTest` and
   `GrabAheadLatencyRig` assert the `null` rather than papering over it.
2. the ring hands out the *earliest* frame that satisfies the bound when asked for the earliest, and the
   newest when asked for the newest; a caller that asked for the newest is never left waiting for one it
   could have had. `FrameBufferTest`.
3. the frame a consumer picks out of the past is the transformed picture, i.e. the one `capture()` would
   have made; the frame a camera's own raw capture is answered from is the one the sensor made. Both
   directions are held: `FrameBufferTest.testTheFrameHandedToAConsumerIsTransformedAndTheCameraKeepsItsRawOne`
   and `GrabAheadCaptureTest.testFramePickedOutOfThePastIsThePictureTheCameraWouldHaveMade`, with
   `testSettlingQuotesTheInstantItsFramesWereTaken` holding the other side so the transform cannot be
   applied twice.
4. an inactive session answers "nothing" at once. Nothing waits on a ring that will never be filled; this
   is what keeps `capture()` and the hunt honest when the feature is off. `FrameBufferTest`,
   `GrabAheadCaptureTest.testCameraThatDoesNotGrabAheadIsNoWorseOff`.
5. a forgotten `endSession()` costs `grab-ahead-session-ms` and stops. `FrameBufferTest`.
6. five consecutive failures stop the grabbing, warn, and let the consumers fall back. `FrameBufferTest`.
7. the ring never grows and never copies pixels. There is no allocation in the ring itself beyond the
   `Frame` wrapper; `FrameBufferTest` checks eviction and the count of dropped frames.
8. frames are shared, not cloned, and must be treated as read-only. A hand-out that has to transform
   produces a new image and a hand-out that has nothing to transform hands the ring's own `BufferedImage`
   straight out, exactly as `ReferenceCamera.capture()` does for a plain camera. Nothing in OpenPnP draws
   into a captured image today, and the vision converts to a `Mat` and back, which produces a new one.
9. a stream that is reopened discards the ring.
10. a session begun inside another session extends it rather than replacing it.

## What it costs

For pn52 with `grab-ahead-depth="8"`:

| camera | frame size | ring while a session runs | session length, roughly |
|---|---|---|---|
| `Down Locking` (1080p, 30 fps) | 6.2 MB | 50 MB | one fiducial search, ~1-2 s, two per placement batch |
| `Upp1`, `Upp2` (960x1080, 120 fps) | 3.1 MB | 25 MB each | the travel to the alignment position plus the settling, a few hundred ms per batch |

The frames themselves are bounded and reused by the ring; the churn is the frames the grabbing thread
produces between reads. On the bottom cameras at 120 fps that is ~370 MB/s while a session runs, which is
why sessions are short, and it is the thing a job has to be watched for: if the job gets slower with the
feature on, or GC activity rises, reduce `grab-ahead-depth` (4 frames is 33 ms of history at 120 fps,
which is still more than a frame interval) or turn the feature off on the two bottom cameras and keep it
where a frame interval is 33 ms and the frames are more expensive to wait for.

One thread per camera while a session runs, and the ring's bookkeeping, which is nothing.

What it does **not** buy, so that it is not counted twice:

- the transforms. `capture()` is `captureRaw()` plus white balance, the undistort remap, the rotation and
  the conversion to a `Mat`, and all of it is still done by whoever takes a frame out of the ring. The
  ring's thread grabs; it does not prepare. Settling on a downscaled raw frame is a separate item and
  unbuilt.
- the vibration. A settling still waits for the machine to stop moving. Buffered frames let it look at
  what has already happened sooner, which is one frame interval per settling and not the ringing itself.

Expected on a job, from `CAPTURE-COST.md`: the cold first frame (~210 ms) and the join (38-80 ms), so
250-290 ms of an 11 s job.

## What to look for in a log

The session summary, written at DEBUG when a session ends:

```
FrameBuffer DEBUG: Frame buffer Upp1 session parallel-align-precapture in 214 ms: grabbed 26, dropped 0, interval 8 ms, worst frame 9 ms old when taken
```

| field | what it means | what is wrong |
|---|---|---|
| `in N ms` | how long the session ran | a session much longer than the operation that began it means an `end` was forgotten; it will still stop by itself |
| `grabbed N` | frames taken during this session | `grabbed 0` means the switch is off, or the stream was not open, or the grabbing thread failed and said so at WARN |
| `dropped N` | frames thrown out of a full ring to make room | once the ring has filled, `dropped` climbing at about the same rate as `grabbed` is the normal steady state and means nothing: a ring of eight in front of a camera at 8 ms discards a frame every time it takes one. It is only a symptom when `grabbed` is high while the consumers are asking for frames and being refused |
| `interval N ms` | median of the last nine frame intervals | much more than the device's own interval means the bus or the light is contending |
| `worst frame N ms old when taken` | the oldest a handed-out frame was, measured against the instant its *content* is from, at the moment it was handed out | should sit near one interval plus however long the caller waited. Several intervals means the consumer asked for something older than the newest frame - a hunt aiming at the opening of a window that opened a while ago, which is correct - or that the grabbing thread starved, which is not |

Then the two things the feature exists to change, both already in the log from the earlier work:

```
AbstractSettlingCamera DEBUG: autoSettleAndCapture in 47 ms
ReferenceBottomVision DEBUG: [SETTLE-WINDOW] camera Upp2 frame 2 at +79.0 ms after the rest is inside the window of Upp1 which opened at +78.0 ms, margin 1.0 ms of 2 grabs
```

The first is a settling's whole duration; the second is the hunt, where `frame 2` is how many frames it
took and `margin` is how far the kept frame was past the opening of the window. Against `log_x.txt`,
which is the same job with the feature off, what should move is the settling duration (by roughly one
frame interval per settling) and the hunt's margin (which was 27 ms there because an exposure and a wait
had to happen between the verdict and the frame).

## Measuring it on pn52

The run started 2026-10-02: jar built at 12:38, `machine.xml` patched with the three attributes,
`./openpnp.sh` started on the machine's display at 12:55, console mirrored to
`/mnt/public/stuff/OpenPnP/openpnp/grab-ahead-run.log` and the rolling application log at
`~/.openpnp2/log/OpenPnP.log`.

Procedure, which is deliberately the same job as the baseline so the two are comparable:

1. home, then run the same job that produced `tests_2026-10-01/log_x.txt`.
2. take the log and compare: total job time, the sum of `autoSettleAndCapture in N ms`, the
   `[SETTLE-WINDOW]` margins and grab counts, and every `Frame buffer ... session ...` line.
3. check the camera is behaving: `dropped` and `worst frame` as above, no `Frame buffer ... giving up`
   warnings, and the preview still working when the machine is idle (the preview is suspended during job
   tasks, so a session and the preview should not meet; if a session is begun while the user is dragging
   the preview around, whoever gets the frame is undefined and the preview will look stuttery).

### The run that proved nothing

`tests_2026-10-01/log_z.txt`, the first real job with the feature on: **13.4 s against the 10.8 s of
`log_x.txt`**, and the head visibly returning to the cameras before every placement, which is what the
feature was supposed to remove. The `Frame buffer ... session ...` lines were all present and healthy, so
the ring was doing exactly what it was told. The damage was elsewhere and the log said it plainly, in a
line that had nothing to do with the ring:

```
ReferenceBottomVision WARNING: [PRE-CHECK] nozzle=N1 camera=Upp1 ... verdict=PartMissing action=Report : no rectangle in the pipeline result
ReferencePnpJobProcessor WARNING: Parallel alignment fell back to sequential for all nozzles (1 of 1 in this job)
...
[PARALLEL-VISION] attempts=4 fell back=4 frames refused=0 : the parallel path did not carry this job
```

Four batches, four fallbacks, and `frames refused 0`: the capture logic never refused a frame, because
every frame it handed out satisfied the rule it was given. The rule was the problem.

`Upp1` in 8 of 8, `Upp2` in 0 of 8, and nothing else failing, is the shape of the answer. `Upp1` is the
settling camera, so its pre-check is judged on the *first* frame of the settling - the earliest frame the
ring would produce from the instant the head came to rest. `Upp2` hunts inside `Upp1`'s window, 55-90 ms
later, and was never troubled. And the light: `moveNozzlesToAlignmentPosition()` actuates it just before
`waitForCompletion()`, so it is lit at about the arrival instant and not one frame interval earlier.

So the earliest frame the ring could offer was a frame *returned* just after the arrival, whose pixels had
been gathered during the interval before it - while the head was still moving and the light still dark. No
part in it, and no rectangle in the pipeline result. The pre-check said the part was missing, the parallel
path fell back to sequential, the head walked back to the cameras for every part, and the job cost 2.6 s
more than the baseline it was meant to beat.

Every offline test passed throughout, because in the tests the frames arrive already lit and the question
"is this frame from after the arrival" was answered about the paperwork rather than about the picture. The
fix is the stamp rule above; the test that now holds it is
`FrameBufferTest.testAFrameIsNotStampedWithTheInstantTheDriverHadIt`, which fails against the old stamping
in exactly the way the machine found.

What is worth keeping from the failure, beyond the fix:

- a feature that changes *when* pixels are taken has to be judged by what the vision makes of them, not by
  whether its own counters look healthy. The counters were the only thing looking at the ring.
- `fell back=4` with `frames refused=0` is the difference between "the new path was refused work" and "the
  new path gave an answer nobody could use", and only the second line of the two says which.
- the asymmetry between the two cameras was the whole diagnosis. A log that had said only "pre-check
  failed" would have taken a week instead of an afternoon.

### The frame that was not the picture

The stamp fix above was necessary and it was not sufficient. The next jar, with the corrected content
stamp visible in the log (`worst frame` moved out by the expected 8 ms), produced `log_A.txt` and failed in
exactly the same way: `8 verdict=Ok`, `8 verdict=PartMissing`, four batches, four fallbacks, 21.2 s.

So the frames were turned into pictures and looked at. `log_B.txt` was taken with
`-DframeDump=/home/micke/framedump-A`, and the failing frame -
`bv__Ringing__CAM1830e7329b17e54f__N1__...__f1__a+333551145.0__c+333551145.0` - was not dark (mean 43.7,
p95 95, max 248) and did contain the part, a bright blob in the middle of the field. Nor was it a different
scene from the frame the same batch later aligned on successfully: mean absolute difference between them
`1.18`, the blob in the same place. A part in the picture, and no rectangle in the pipeline result.

The thing that settled it was replaying the frames on a machine that was not pn52. `FrameReplayTest`
already knows how to take a dumped frame, find the part's own pipeline in the run's own configuration, and
run it, and `-Dreplay.measure=<corpus>` does that for every frame on disk without needing a log to match:

```
not still Ringing  N1  f1  +3 ms      found nothing: ... the pipeline returned null, not a rectangle
still Streamed N1  f2  +51 ms     found nothing: ... the pipeline returned null, not a rectangle
not still Ringing  N2  f1  +22 ms     0.896x0.501 mm   {34.0}
still Settled  N2  f3  +114 ms    0.948x0.501 mm   {36.0}
```

`N1` fails on *every* frame, including the one the alignment used, while `N2` is fine on all of them. The
older corpora from before grab-ahead (`bv-corpus`, `bv-corpus-s`), replayed against the same
configuration, give `N1` rectangles on all of them - including their `Ringing` frames taken 12-22 ms after
the arrival. So it was not the pipeline, not the part, not the light and not the timing: it was that this
run's `N1` frames were not the same kind of picture.

They were not, literally: `960x1080` against the `1078x947` of every frame from before. `Upp1` is rotated
-90 and flipped in x, so `960x1080` is the sensor's own frame and `1078x947` is what the camera makes of
it. `Upp2`, which is rotated 90 and not flipped, produced `1080x926` in both runs, which is why one camera
was affected and the other was not: the hunt took its frames out of the ring for `Upp1` and out of the
settling for `Upp2`, and the ring handed out raw frames while the settling's `capture()` did not.

`ReferenceBottomVision.takeFrame()` asked the buffer for a frame and got one *as the sensor made it*,
because that is what `BufferedFrames` used to promise - "whoever wants the white balance, the lens
correction and the flip has to apply them" - and it did not apply them. The pipeline searches where the
transform puts the part; in the untransformed frame the part sits 45 px left and 60 px below where the
search window is, so the honest answer of a working pipeline was "nothing here", which the pre-check
reported as a missing part and the job processor acted on by falling back to sequential for the whole job.
The sequential re-capture went through `capture()`, was transformed, and found the part every time - which
is why the job finished, slowly, and why nothing ever looked broken.

The fix is not "remember to transform in `takeFrame()`", because the next consumer will forget for the
same reason. `FrameBuffer` is now built with the camera's transform (`OpenPnpCaptureCamera` passes
`this::transformImage`) and the methods that answer *"pick one out of what you already took"* apply it on
the way out, so a frame from the ring is the same picture `capture()` would have handed over. The methods
that answer a camera's own raw capture are renamed `...Raw...` and deliberately do not, because the
transform runs above that call and would otherwise be applied twice. `BufferedFrames` now promises the
transformed picture, and the promise it used to make was the bug.

Two tests hold it:

- `FrameBufferTest.testTheFrameHandedToAConsumerIsTransformedAndTheCameraKeepsItsRawOne` - the hand-out
  methods transform and keep the captured instant; the raw methods do not transform.
- `GrabAheadCaptureTest.testFramePickedOutOfThePastIsThePictureTheCameraWouldHaveMade` - the hunt, driven
  end to end through `captureImageFromSettlingWindow`, is given a frame carrying the test camera's
  transform mark; and `testSettlingQuotesTheInstantItsFramesWereTaken` asserts the settling gets *no*
  hand-out transforms at all, so the rule cannot be applied twice by accident.

Worth keeping from this one too:

- An offline test suite that never looks at pixels cannot catch a defect that is only about pixels. The
  corpus, the log lines that quote OpenCV's own rectangle, and the replay that re-runs the machine's own
  pipeline on a laptop were what found it in minutes; the counters and the timings were wrong for two
  whole runs and never once said so.
- "The frame the alignment used also fails" is a much better clue than "the pre-check fails", and it came
  from measuring *all* the frames rather than the ones the log claimed to have measured.
- A camera where the transform is the identity is a camera that hides this class of bug. The two bottom
  cameras differ in rotation and flip and so differed in symptom; a machine with two unrotated cameras
  would have shipped it.

### The buffer that answered nothing

The feature was switched on, and the machine got slower. Not in one place, everywhere:

| per bottom-vision settling (Upp2, 12 samples) | grab-ahead off | grab-ahead on |
|---|---|---|
| frames the settling took | 3 | 3 |
| the first frame of it | 11.5 ms | 23.0 ms |
| the settling as a whole | 31.5 ms | 51.5 ms |
| the pre-check on that frame | 19.0 ms | 40.0 ms |
| a job's fiducial checks | 8.9 s | 11.6 s |
| a warm job | 10.8 s | 12.3 s |

The ring had been filling the whole time - 41 frames per batch on each up camera, 26 to 34 on the
fiducial camera - and it answered nothing. The reason is arithmetic, not tuning, and it was in the design
from the first line of it:

- a frame handed to the ring is stamped with the earliest instant its content can come from, which for a
  camera that cannot time its own exposure is *the instant it was returned, less one frame interval*;
- `captureFromRing()` asked for a frame whose content lay *at or after the instant of the call*.

No frame in the ring can satisfy that, and no frame that will be in it a moment later either: the answer is
necessarily a frame the camera has not taken yet. Every capture therefore waited a whole frame interval
and more - 8 ms streams paid 13 ms extra, the 33 ms fiducial camera paid 90 to 160 ms per vision pass -
while the capture on demand it replaced had been answering in half an interval by handing over the last
frame the driver held and not asking when it was taken. The freshness rule was bought to close the
"frame that was not the picture" class of bug, and it bought it by taxing every capture on every camera to
protect the one frame the pre-check judges.

What changed is that the bound moved from the camera to the consumer:

- a capture is answered with **the newest frame the ring holds**, provided its content is not older than
  two frame intervals - which is exactly how stale the pixels of a capture on demand always were, one
  interval because the exposure lies inside the frame period, one because the frame may have arrived an
  interval ago - and it is answered *without waiting*, in a millisecond or two;
- a consumer that needs more than that says so, for as long as its work lasts:
  `BufferedFrames#setBufferedFrameNotBeforeMs(long)`. Bottom vision states the instant the head was
  reported at rest, which the batch sets for its cameras and clears with them, because a frame taken
  during the approach is not a picture of a head that has arrived;
- a settling comparing two of its own frames, and a fiducial looking for a cross, say nothing and get the
  newest frame there is, honestly stamped.

`FrameBuffer.awaitNewestForCapture()` is that bargain; `awaitRawAtOrAfter()` is left in place because the
hunt path asks its own question about its own window. Two intervals rather than one is not sloppiness: an
allowance of a single interval asks of the newest frame held that it have been returned at or after this
very instant, which is the same wall, met a hair earlier.

### Without moving the machine

`~/.openpnp2/scripts/Grab_Ahead_Smoke_Test.bsh` (Scripts menu, machine enabled) does the whole thing on a
still machine: it opens a session on one camera, waits 400 ms for the ring to fill, takes five captures,
closes the session and takes one more. The five inside the session and the one outside are the same
question asked two ways, and the difference between them is the whole of the feature:

```
grab-ahead smoke: session open, the device is producing a frame every 8 ms
grab-ahead smoke: grab 0 in 9 ms, 960x1080, brightness   12.4, its content is from 8 ms before the return
...
grab-ahead smoke: session closed, the same capture took 28 ms, 960x1080, brightness  118.7, nobody knows
when it was taken
```

The brightness column is there because of the section above: the ring grabs without actuating the light, so
with the camera looking at an empty stage the ring's frames are the dark ones, and a capture on demand -
which does actuate the light - is the bright one. A run in which the two are equally bright is a camera
that is already lit, and a run in which the ring's frames are dark while something is asking it what it
sees is the bug, on a still machine, in one line.

Note that the script tests for the capability by interface *name* rather than with `instanceof`: the first
version wrote `camera instanceof BufferedFrames`, BeanShell answered false about a camera that was at that
moment filling its ring and logging sessions, and the script reported "cannot buffer frames at all".

The `was N ms old when it came back` figure is new with this feature and it is the one worth watching: it
is the age of the pixels at the moment the caller got them, which for a capture on demand is the exposure
and the wait and is unknowable. If the script says the camera could not say when the frame was taken, the
capture did not come from the ring, which means the session was not running or the switch is off.

The offline tests cannot answer either of the two questions this run is about: how cold the first frame
actually is on this hardware, and what the frame churn does to the garbage collector on a machine that is
also moving. Both are only answerable here.

## Tests

`FrameBufferTest` (16 tests, offline, no machine): bounds are inclusive and never loose; nothing before
the bound is ever returned; the newest frame answering a capture at once while a consumer's bound of "the
head had arrived" is still refused anything earlier; eviction and the counts; the ring's epoch increments when it is emptied;
session expiry and nesting; the giving-up policy; the interval estimate; waiting for a frame that arrives
after the ask returns the earliest one that satisfies the bound rather than the newest; the two ways out of
the ring giving the two different kinds of picture they are supposed to.

`GrabAheadCaptureTest` (5 tests, offline, with a camera that manufactures frames on its own thread, and
frames arranged so that the head arrives, rings for four frames and then stands still):

- the settling quotes the producing thread's instants - the recorded window opening is an instant at which
  a frame was actually taken, and every settle frame came out of the ring with no exposure made, none of
  them transformed on the way out;
- the hunt keeps the buffered frame nearest the window opening, and no frame taken between the opening and
  the kept one exists, because such a frame would have been nearer;
- the frame the hunt is given has been through the camera's transform, which is the one property whose
  absence made pn52 fall back to sequential for every part of every job;
- frames taken before the head arrived stay in the ring even though the ring is full of them;
- a camera with no ring loses nothing: the asks come back empty at once, the frames are exposed as they
  always were, and nothing waits for a ring that is never going to be filled.

`GrabAheadLatencyRig` (4 tests, offline, no machine) is the measurement above rather than a proof about
correctness. It re-enacts a driver that produces a frame on a cadence and copies it under one lock, fills a
ring, and asks it the two questions: a full ring and a bound of the instant of the ask answers *nothing*
(`assertNull`, on purpose); a settling of three frames costs 8 ms per frame asked that way and 0 ms asked
the other way; and four batches of settling plus the eight fiducial captures of a fiducial camera cost
1.7 s against 59 ms. The numbers of a run are written to `target/grab-ahead-rig-report.txt`. It is the
test that should have existed before the first machine run, and its absence is why two machine runs were
spent learning what a laptop could have said in three seconds.

## Not done

- no wizard page; the three settings are edited in XML.
- no downscaled or grey ring, so the ring holds the same frames the camera produces. That is the obvious
  next saving if the memory or the GC complains: the settling needs a small grey image, and only the one
  frame that is kept needs to be pulled at full size.
- no exposure timestamps. `Source.getCapturedAtMs()` is the seam; `openpnp-capture` would have to report
  them.
- no compute overlap, which is the difference between this and what `CAPTURE-COST.md` originally sketched:
  the settling still scores frames on the thread that asked for them.
- no per-camera tuning of the depth beyond the attribute, and no per-camera tuning of the staleness a
  capture tolerates beyond `grab-ahead-max-frame-age-ms`.
- no exposure timestamps. The staleness a capture may be answered from would be a frame interval less if
  `openpnp-capture` said when the pixels were gathered rather than the ring having to assume the worst;
  the seam is `Source.getCapturedAtMs()`.
- the frame churn itself is unmeasured: 41 frames grabbed per batch to use two of them is a good deal of
  copying under one driver lock, which is the most likely reason the pre-check's own pipeline took twice
  as long while rings were filling.
