# A part refused as too thin because one of its terminations went dark

Written 2026-10-05, and deliberately left unfinished: the fault did not reappear while the
instrumentation was being built, so this is a note-to-self that says what the fault is, what it is
*not*, what is already measured, what the leading suspect is, and exactly which command to run when
it happens again.

Companion documents: `PRE-CHECK-ASSESSMENT.md` and `PRE-ALIGNMENT-CHECK.md` for what the pre-check
is for; `MOTION-BUDGET.md` for the cost of the fallback it forces; `CAPTURE-COST.md` for what a
frame costs and how the cameras are configured.

## The symptom in one line

Parallel alignment photographs the part, the picture is fine, then the size check refuses it as
being about half as wide as it should be, and the nozzle falls back to a sequential re-alignment
that succeeds a moment later on the same part, in the same gripper, under the same light:

```
Bottom vision part C0402-4n7/50V result rect { {113.0, 93.5} 8x19 * 0.0 }
Part C0402-4n7/50V width too small: nominal 1.000, limit 0.620, measured 0.211
... falling back to sequential alignment
```

`8 px` at the up camera's `~0.0267 mm/px` is `0.214 mm`. The `19 px` side is the correct `0.50 mm`
length. So the camera saw one end terminal of the 0402 and nothing else, and reported that as the
part. From the same failed cycle, `log_M.txt`, the sequential re-shoot a second later:

```
Upp2 result rect { {105.5, 94.5} 33x19 * 0.0 }   size ok. Width 0.869, Length 0.501
Upp1 result rect { {96.5, 100.5} 37x19 * -180.0 } size ok. Width 0.988, Length 0.508
```

Both terminals, whole part. Nothing had moved.

## There are two kinds of refusal, and only one of them is the fault

Counted over `tests_2026-10-01/log_*.txt`, refusals of the nominal `1.000 mm` width:

| Measured | Count | What it is | Verdict |
|---|---|---|---|
| 0.21 - 0.26 mm | 47 | one end terminal, the other did not survive the image processing | **the fault** |
| 0.39 - 0.60 mm | 134 | the whole part is there, but with width and length exchanged | correct refusal, see below |

The second class is not a bug and must not be "fixed". `findOffsetsPostRotate()` builds its wanted
location at rotation 0 (`ReferenceBottomVision.java:449`) and `preparePipeline()` hands that to the
pipeline as `MinAreaRect.expectedAngle` (`ReferenceBottomVision.java:757`); `MinAreaRect` then
canonicalises the rectangle with `Utils2D.rotateToExpectedAngle()` (`MinAreaRect.java:251`,
`Utils2D.java:455`), which snaps the reported angle to the expected one and *moves a 90 degree
difference into the size*. A part held across the axis the vision expects therefore appears as an
exchanged width and length at an angle of 0. The size check is the only thing that stands between
such a part and a placement turned 90 degrees. It is load-bearing as an orientation guard, and any
"either way round" tolerance added to it would place turned chips. Verified on a real frame in
`tests_2026-10-01/bv-corpus`, whose evidence line says it outright:

```
[VISION-EVIDENCE] Upp2/N2 C0402-4n7/50V refused: ... width too small: nominal 1.000, limit 0.620, measured 0.474
[VISION-EVIDENCE]   rect { {107.0, 96.5} 18x37 * 0.0 }
[VISION-EVIDENCE]   wanted 0.0°, the 18px side lies at 0.0° and the 37px side at 90.0°, MinAreaRect was told to expect 0.0° within ±45°
[VISION-EVIDENCE]   filterContours (filtered) 2 contour(s) [136px @{108,111 17x8}, 136px @{108,83 17x8}]
```

Two terminals, 28 px apart along Y, part lying across the expected axis. The refusal was right.
(Why the part is turned at vision time is a separate question that this document does not settle.)

The first class - 47 refusals, spread over `log_I` (10), `log_s` (17), `log_t` (5), `log_w` (6),
`log_y` (3), `log_K` (2), `log_M` (4) - is the one that is not supposed to happen.

## What has already been ruled out for the one-terminal class

* **Crop and mask geometry.** `CropCentered` has `minimum=200` on a 200x200 capture centred on the
  nozzle tip; `MaskCircle` radius corresponds to about 4 mm; the part spans roughly 37x19 px around
  the centre. There is an order of magnitude of margin. The failing rect is at `{113, 93.5}`, i.e.
  nowhere near a cut edge.
* **The pre-check gating the wrong frame.** In the failing cycle the pre-check answered `Ok` on an
  early frame (`frame=1/3`, measured 0.91x0.48 or 0.95x0.50, whole part). The later frame that the
  deferred alignment used had collapsed. The image changed between the two frames; nothing about the
  pre-check mechanism did.
* **The threshold being simply wrong as a setting.** `pThreshold=164` accepts thousands of frames of
  the same part in the same runs. The same threshold accepted frame 1 and rejected frame 4. So the
  variable is the frame, not the number - which is a different claim from "the number is not involved"
  and see the exposure section below.
* **A light switching itself off mid-batch.** Both up cameras are on the *same* light actuator
  (`ACT1579799618401`) and both have `after-capture-light-off="true"`, which would be a race between
  two parallel settles - except that the pre-capture runs inside one `CameraBatchOperation`
  (`ReferencePnpJobProcessor.java:2551` to `:2857`) that defers every light-off until both cameras
  are done (`AbstractCamera.java:589`, `ReferenceCameraBatchOperation.java`). So within the
  pre-capture, the light stays on. Two smaller things were noticed on the way there and are unproven:
  `startBatchOperation()` and `registerWithBatchOperation()` are not `synchronized` while
  `endBatchOperation()` is, and the `cameras` list is a plain `ArrayList` written from both capture
  threads. Worth reading once more, but there is no evidence pointing at it.

## Leading suspect: the up cameras are exposed at the absolute minimum

Read from `pn52` with `v4l2-ctl -d /dev/video0 --list-ctrls` (Upp2 is `ELE01 usb-0000:05:00.3-2` =
`/dev/video0`, Upp1 is `ELE01 usb-0000:05:00.4-2.4` = `/dev/video4`, from `machine.xml`
`unique-id`):

```
auto_exposure            (menu) min=0 max=3 default=3 value=1 (Manual Mode)
exposure_time_absolute   (int)  min=1 max=8192 default=512  value=1
gain                     (int)  min=0 max=255 default=17   value=17
```

Both up cameras are in manual exposure with the exposure time set to **1** out of a default of 512
and a maximum of 8192. Nothing in OpenPnP writes those: both cameras are
`freeze-properties="true"`, and the only exposure the launcher touches is
`v4l2-ctl -d /dev/video2 -c exposure_time_absolute=50` five seconds after start - and `/dev/video2`
is the *down* camera (`FHD Camera usb-0000:01:00.0-1`), not an up camera.

That is a plausible mechanism for exactly this fault: at the shortest exposure the picture is a
strobe snapshot sitting on the noise floor, the ceramic body is far below the threshold and only the
bright metal terminals survive - so the measurement depends on the *margin* between the terminals
and `pThreshold=164`, not on the terminals being visible at all. A few percent less light (LED
current vs. temperature, a slightly different stand-off, one nozzle shadowing the other, a dirty
lens) and the weaker of the two terminals drops below 164. Then the pipeline reports a single
terminal, and does so with a straight face.

The failing cycle in `log_M.txt` is consistent with this and with nothing else that was found: the
`MaskHsv` stage trace reports `Fraction actually masked = 0.99695` and `0.993` for the two rejected
frames, i.e. about 0.3-0.7 % of the 200x200 field survives, which is one terminal, against the few
percent a good frame keeps.

It does not yet explain why the *same* camera keeps failing while its neighbour is fine in most of
these logs, nor why the sequential re-shoot a second later is bright. Both are things the evidence
line below is designed to answer next time.

## Instrumentation that is now in the build

All of it costs nothing when nothing is refused; the whole point was to be able to leave it on.

* `-DframeDump=onFailure[:<dir>]` (`org.openpnp.vision.FrameDump`). Frames are cloned into RAM and
  *nothing is written* unless a part is refused, at which point the retained frames of that
  alignment are written out and the reason is attached. `-DframeDumpKeep=<n>` bounds the retention,
  default 8. In the happy path the cost is one `Mat` clone per alignment, no PNG, no disk I/O.
  Without the property, or with `-DframeDump=every`, behaviour is as before.
* `[VISION-EVIDENCE]` lines (`org.openpnp.vision.VisionEvidence`), logged once per refusal: the
  refused rect, the angle the pipeline was told to expect and which way round the rect's sides came
  out, the fraction of the image each stage kept, and the contours `FindContours`/`FilterContours`
  saw with their areas and bounding boxes. This is the line that separates the two classes above
  without any offline work.
* `FrameReplayTest` re-measures a corpus offline through the real pipeline and the real
  `partSizeCheck()`, and can sweep thresholds:

  ```
  JAVA_HOME=/opt/android-studio/jbr ~/tools/apache-maven-3.9.6/bin/mvn -B -o \
    -Djvm=/usr/lib/jvm/openjdk-bin-21/bin/java -Dtest='FrameReplayTest' \
    -Dreplay.measure=tests_2026-10-01/bv-corpus \
    -Dreplay.threshold=100,120,140,164 -Dreplay.evidence=1 test
  ```

  What it said about the corpus on 2026-10-05: thresholds 120, 140 and 164 keep the settled frames
  at about 1.0x0.5; threshold 100 turns them into `3.14x3.14` and refuses them, because the
  background joins in. So the threshold has a floor well above 100 and lowering it is not a free
  fix. The corpus does not contain the fault: it has no frame that collapses to one terminal, which
  is why the machine has to catch one.

## When it happens again

Run with the dump enabled. `openpnp.sh` passes `$1` as a single string of VM options, so several
options go in one quoted argument, and the directory must not contain spaces:

```
./openpnp.sh "-DframeDump=onFailure:/home/micke/vision-evidence"
```

A refusal then leaves behind the frames of that alignment plus, in the log, everything needed to
classify it. Do the classification before doing anything else:

| The evidence line says | It is | Next step |
|---|---|---|
| `kept ~0.3-0.7 %`, one contour in `filterContours` | the fault | compare the two dumped frames (the pre-check frame and the kept frame): pixel maximum and mean, terminal by terminal; and read `v4l2-ctl -d /dev/video0 --get-ctrls` for the camera that failed, on the spot, before anything resets it |
| `kept` a few %, two contours, `the ...px side lies at 90.0°` | a turned part | correct refusal; do not touch the size check; ask instead why the part lay across the axis at vision time |
| `No result found in pipeline` | nothing survived at all | look at the dumped frame with the naked eye first; light, shutter, or a part that is really not on the nozzle |

Then, and only then, the experiment the exposure reading suggests, on one camera, one job at a time,
keeping the frames:

```
v4l2-ctl -d /dev/video0 --get-ctrls auto_exposure,exposure_time_absolute,gain
v4l2-ctl -d /dev/video0 -c exposure_time_absolute=4      # and re-tune pThreshold afterwards
```

The thing being tested is whether a frame that is not on the noise floor keeps both terminals with
margin, which would take the fault out of the "may or may not clear 164" category. Note that a
longer exposure also smears more of the background into the picture, which is exactly what the
threshold-100 result above is warning about, so exposure and threshold have to be moved together and
the corpus re-run, not just one frame admired.

## Things that must not be done to this fault

* Do not make `partSizeCheck()` accept an exchanged width and length, and do not widen its tolerance.
  It is the orientation guard, for the reason given above.
* Do not lower `pThreshold` on its own. Below about 120 the background joins the part and the
  measured size becomes the crop, not the part.
* Do not conclude anything from one good run. The fault is intermittent by nature; the value of the
  instrumentation is that it can stay on permanently and cost nothing while nothing fails.

## Where the data is

* `tests_2026-10-01/log_M.txt` - the failing cycle quoted above.
* `tests_2026-10-01/log_*.txt` - the counts in the table above.
* `tests_2026-10-01/bv-corpus` - frames for `FrameReplayTest`, from `log_q`; useful for threshold
  work, does not contain the fault.
* `tests_2026-10-01/vision-settings.xml` - the `R0402` pipeline: `PadExtents` size check,
  `check-size-tolerance-percent=38`, `pre-check-action=Report`, `pThreshold=164`.
* `/home/micke/vision-evidence` on `pn52` - where a future failure will land, once the machine is
  started with `-DframeDump=onFailure`.
