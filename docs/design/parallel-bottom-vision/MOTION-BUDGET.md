# Where the two seconds of a placement cycle go on the machine side

Measured on pn52 on 2026-10-04, build `2.7_2026-10-03_21-16-41.aa0f65b`, log `tests_2026-10-01/log_M.txt`.
Four complete placement cycles of 1.99-2.17 s are used for the per-cycle numbers; the annotated cycle in
the middle of this document is one of them. Every figure is a difference of timestamps in the log, nothing
is estimated unless it says so.

Companion documents: `TIME-BUDGET.md` is the same question from the camera side and still holds for what it
covers; `CAPTURE-COST.md` and `GRAB-AHEAD.md` are the frame-level budget. This document is about motion,
driver and job sequencing.

## The head is busy, Java is not

The controller is a Smoothieware port, and two of its acknowledge semantics decide how this log must be
read. `M400` does not acknowledge until it has *executed* and arrived, and it holds the lines written after it
in the firmware's rx queue; an ordinary movement line is acknowledged as soon as it has been *planned* into
the motion queue, which can be hundreds of milliseconds before it runs. So `M400` acks are arrival times and
movement acks are not, and every one of the figures below was checked against that rule. Pairing by counting
is then possible - 1575 commands against 1295 acks in the annotated log - but only after the duplicated `>>`
lines are collapsed, as the method section explains.

Idle is measured the way that needs no queue model: find the silences on the wire - gaps over 20 ms between
consecutive writes - and measure from the last ack inside each one to the next write.

| measured over the whole log | |
|---|---|
| host silences over 20 ms | 127 |
| of them, work still outstanding when the silence began | 123 |
| last ack inside the silence to the next write | median **0 ms**, p90 36 ms |
| the same for the 445-480 ms feed silences | 0-3 ms |

The p90 is the vision phase, where the host really is doing something else. At the motion barriers the host
writes the next block within a few milliseconds of the controller reporting the last one finished, which is
the definition of a machine-bound cycle. Two earlier figures for idle - 19 ms and then 268 ms a cycle, 195 ms
of it after the feed - are retracted below, and the traps that produced them are worth reading.

What the pairing does establish, and what survives every correction, is that the driver and the wire are not
the bottleneck, that `M400` barrier arrivals are trustworthy, and that the host is not the limit.

**Two claims made earlier in this workstream were wrong and are retracted here.**

- "`plan interlock` is 826 ms a cycle of waiting, so the dragpin interlock is the biggest consumer." The
  826 ms is real as *thread blocking* time, but the read is issued while the machine is mid-move and its
  answer arrives when the block it was queued behind finishes. It overlaps motion almost perfectly. The
  `M119` round trip of 53 ms median is the remaining travel time, not read latency: `M119` is executed in
  queue order, so a read written behind a `G1`+`M400` is answered at the arrival.
- "~230 ms a cycle of dead stops, of which ~195 ms before the pick." That measurement anchored on an `ok`
  that was only an *accept* acknowledge and then called the following wire silence idle. Under the `M400`
  semantics above that silence is the controller executing queued blocks. There is no dead stop before the
  pick, and the head does not stand there waiting for a pin reading.
- "Idle after a verified stop is 19 ms a cycle, so the head is busy 99 % of the cycle" - and the correction
  that replaced it, "idle is 268 ms a cycle, 195 ms of it after the feed". Both wrong. The `M114` written
  after every `M400` hid the first, and counting the acks of a two-`M400` batch up to the wrong one hid the
  second. Measured from the ack that really ends the feed batch, the next motion is written **0-3 ms** later:
  the head is busy essentially the whole cycle.

## One cycle, annotated

Head released from the vision position, one placement of two parts with two nozzles, feed of both tapes,
back to the cameras. `>>>` is a verified stop. The indented `java` lines are what the job thread did.

```
    0  java  [EARLY-DEPARTURE] head released with 2 alignments still being computed
   75  WIRE  G1 X139.08 Y125.02 Z-16.8 F56329      travel to place point of N1
   76  WIRE  G1 X139.14 Y125.14 F600               backlash sneak-up, 0.09 mm at 10 mm/s
   78  WIRE  M400                                  commit
   78  java  queue committed 7 ms after the release; 2 alignments handed to the vision thread
  309  >>>   stop (2 lines already queued)                                    231 ms
  312  java  N1.place()
  312  WIRE  G1 X139.07 Y125.12 A22.3 B-22.6 F2208 rotate to the place angle, 37 mm/s
  314  WIRE  G1 X139.01 Y125.00 F600               sneak-up
  316  WIRE  G1 Z-38.8 F60000                      plunge, 20 mm
  436  >>>   stop                                                               124 ms
  437  java  AVACS1.read() 308.3 -> part placed, vacuum off
  440  WIRE  G1 Z-18.5 F60000                      lift to safe Z, 20 mm
  493  >>>   stop                                                                53 ms
  495  java  N2.place()
  495  WIRE  G1 X115.42 Y125.98 Z16.7 B-29.3 F68564  travel 24 mm + 35 mm cam traverse to N2's place point
  497  WIRE  G1 X115.36 Y126.10 F600                sneak-up
  499  WIRE  G1 Z38.8 F60000                        plunge, 22 mm
  683  >>>   stop                                                               188 ms
  684  java  AVACS2.read() 241.6 -> placed, vacuum off, feeder N1 asked to advance
  688  WIRE  G1 Z18.4 F60000                        lift to safe Z, 20 mm
  742  >>>   stop                                                                54 ms
  744  WIRE  G1 X25.18 Y47.67 A67.6 B22.6 F94663    travel 119 mm to the feeder
  747  WIRE  M816                                   drag pin down
  747  WIRE  G4 P10                                 dwell 10 ms
  749  WIRE  G1 X29.27 C4 F12724                    peel/drag 4 mm
  751  WIRE  G1 X29.18 F1386
  753  WIRE  M817                                   drag pin up
 1005  >>>   stop                                                              (10 lines queued)
 1198  >>>   stop
 1200  java  N1.pick()
 1200  WIRE  G1 X26.33 Y81.29 Z-18.5 F49846         travel 34 mm + 37 mm cam traverse
 1202  WIRE  G1 Z-36.9 F60000                       plunge, 20 mm
 1426  >>>   stop                                                               226 ms
 1427  java  vacuum on, then 30 M105 reads in 20 ms until the level is established
 1448  WIRE  G1 Z-16.8 F60000                       lift to safe Z, 20 mm
 1501  >>>   stop                                                                53 ms
 1503  java  N2.pick()
 1503  WIRE  G1 X1.34 Y81.46 Z18.4 F67404           travel 25 mm + 35 mm cam traverse
 1505  WIRE  G1 Z36.9 F60000                        plunge, 20 mm
 1687  >>>   stop                                                               184 ms
 1688  java  vacuum on, level established
 1706  WIRE  G1 Z16.7 F60000                        lift to safe Z, 20 mm
 1760  >>>   stop                                                                54 ms
 1766  java  light on
 1767  WIRE  G1 X122.20 Y46.03 Z-1.4 A-22.6 F122991 travel 126 mm to the cameras, 18 mm Z for free
 1988  >>>   stop                                                               222 ms
 1989  [CYCLE] next cycle starts with the settling of the cameras
```

## What a cycle is made of

Commands written per cycle:

| command | per cycle | what it is |
|---|---|---|
| `G1` | 26.0 | motion blocks |
| `M400` | 13.0 | forced stillstands |
| `G1 … F600` | 9.0 | of the 26: backlash sneak-ups of ~0.06-0.14 mm at 10 mm/s |
| `M105` | 67.0 | vacuum reads, mostly in two bursts of ~30 |
| `M119` | 7.0 | dragpin endstop reads (the interlock) |
| `M114` | 6.0 | position reports, because `reported-location-confirmation="true"` |
| `G4 P10` | 1.0 | dwell inside the feed |

Time per segment, measured from the write of the first motion block of the segment to the `M400` arrival
that closes it. A segment is the group of blocks between two stillstands, so a segment can contain several
`G1`s:

| segment | per cycle | ms/cycle | median | worst | distance |
|---|---|---|---|---|---|
| travel with Z | 3.5 | 749 | 226 ms | 263 ms | 232 mm |
| travel with rotation | 2.5 | 580 | 226 ms | 263 ms | 270 mm |
| pure Z (plunge and lift) | 5.0 | 343 | 54 ms | 133 ms | 103 mm |
| total accounted | | **1672** | | | |

1672 ms of the ~2100 ms cycle. The rest is the camera phase that the cycle markers place at the window
edges; the idle is a few ms a cycle and does not appear in this accounting at all. Per cycle the head commands 402 mm of XY, 299 mm of Z and 142° of rotation, and
comes to a complete standstill 13 times.

## The thirteen stops, one by one

The number is real, not an average of the whole job taken apart: one `[CYCLE]` window of 1989 ms contains
exactly 13 `M400` writes, and the table is that window. Interval is write-to-write, which under saturation is
the machine's own time. "Rides it" is what the host needs to happen at that instant, in queue order.

| written at | interval | what just moved | rides it | needed |
|---|---|---|---|---|
| 64 ms | 253 | 80 mm travel to place N1, safe Z | nothing | no |
| 74 ms | 10 | nothing, same point | `M119` pin read | the read, not the stop |
| 322 | 125 | rotate + plunge `Z-38.8` | place; vacuum off read after | yes |
| 447 | 60 | retract `Z-18.5`, 20 mm lift | `M119` pin read | the read, not the stop |
| 507 | 184 | travel + rotate + plunge `Z38.8` | place | yes |
| 691 | 57 | retract `Z18.4` | `M119` pin read, `G92 C0` | the read, not the stop |
| 748 | 6 | nothing yet (feeder XY already arrived) | nothing | no |
| 754 | 450 | the drag | `M817` pin release + read | yes |
| 1204 | 243 | travel + plunge `Z-36.9` | pick; vacuum on and 30 vacuum reads | yes |
| 1447 | 59 | retract `Z-16.8` | `M119` pin read | the read, not the stop |
| 1506 | 199 | travel + plunge `Z36.9` | pick; vacuum reads | yes |
| 1705 | 54 | retract `Z16.7` | `M114` position report | no |
| 1769 | 64 | 126 mm travel to the cameras | `M810` light, then the photos | yes |

Seven of the thirteen are the instant something has to happen - the vacuum is read or switched, the pin is
released, the light comes on, the photo is taken, the drag is committed. Those are not stops chosen for
convenience; a read queued behind a `G1` executes where the head is, so it has to be told to wait. They stay.

The other six are a different animal, and they cluster: **the top of every one of the four lifts** (447, 691,
1447, 1705, ~55-60 ms each) and **two arrivals at an XY position whose only passenger is a position report or
a pin read that does not care where in the pause it is taken** (64, 748, which are 10 ms apart at the same
point). The prize in those is not the barrier on the wire - the host resumes 0-3 ms later - it is the physical
dead stop it forces: the carriage decelerates to zero at the top of a lift, and the following 184-253 ms of
travel starts again from zero.

Removing a barrier is not the same as fusing the moves, and fusing them would be wrong: one 3D block of
"Z up plus XY" moves diagonally, and the nozzle travels sideways while still below clearance. What the six
candidates need is for the *second* block to be written while the first is still in the planner and for
`M400` to be dropped at the junction, so the controller blends the corner at its junction-deviation limit -
a bounded path deviation of hundredths of a millimetre, not a diagonal shortcut. That is only possible if the
host already knows the next target, which for a planned cycle it does; the `waitForCompletion()` between the
lift and the travel is the thing that throws the knowledge away.

Blending through a junction that carries a cam stroke in the spring direction is a separate question and is
excluded from the six until the moderation is settled.

## The plan is close to the machine's envelope, and structurally the worst case for blending

Two checks say the *geometry* of the plan is not the problem:

- The 126 mm leg to the cameras runs in 222 ms. With Y acceleration at the configured 12500 mm/s², the
  ideal accelerate-half/decelerate-half profile over 131 mm (the move also carries 38 mm of Z) is 205 ms.
  The head is at 93 % of what the configured acceleration allows.
- The 20 mm Z moves run in 54 ms, which is 27700 mm/s² of effective acceleration in commanded coordinates
  against the 100000 mm/s² configured on the axis. The axis settings are correct; the difference is the cam
  that couples the nozzles to the carriage, and it is dealt with in its own section below. The point here is
  only that the *commanded* feed rate is not what limits these moves.

What is wrong is the scheduling. Smoothie blends a junction only if the next block is already in its
planner buffer when it plans the current one. At the 52 stillstands of the typical cycles:

| motion blocks queued behind the block that just finished | stillstands |
|---|---|
| none | 48 |
| four (the feed burst) | 4 |

So the machine plans every block alone, decelerates to a dead stop, and re-accelerates - 13 times a cycle,
because `waitForCompletion()` writes `M400` (`GcodeDriver.java:496`, `MOVE_TO_COMPLETE_COMMAND`) and the
job processor asks for a stillstand between steps. The plan is optimal in shape and worst-case in cadence.

This is also the answer to "could the work done during the stillstand have been done during the travel".
There is no CPU work to move: the silences end a median 0 ms after the controller's last ack, and the
planner's own spans (`plan send` 7 ms, `plan diagnostics` 43 ms, `plan backlash`
8 ms a cycle) all overlap motion already. What is done during the stillstand is not computation, it is a
*decision*: the next target is not known until the current step has reported - the vacuum level that says
the part is on, the deferred alignment that says where it is. OpenPnP already breaks that dependency in one
place, the vision step, by committing the queue before the alignment results exist (`[EARLY-DEPARTURE]`,
and the `M810` light command deliberately written before the `M400` in `ReferencePnpJobProcessor.java:2420-2431`).
Doing it for the rest of the cycle means deciding the next target early and writing it early, i.e. keeping
three or four motion blocks in the controller instead of one. That is a sequencing change, not a faster
planner, and it is the precondition for any blending: with a queue of one there is nothing to blend with.

### The route is not the problem either

The five stations of this job and the legs between them, from the coordinates of the annotated cycle:

| from | to | mm |
|---|---|---|
| cameras (122.2, 46.0) | place of N1 (139.1, 125.0) | 80.8 |
| place of N1 | place of N2 (115.4, 126.0) | 23.8 |
| place of N2 | feeder (25.2, 47.7) | 119.5 |
| feeder | pick of N1 (26.3, 81.3) | 33.6 |
| pick of N1 | pick of N2 (1.3, 81.5) | 25.0 |
| pick of N2 | cameras | 126.0 |
| | **total** | **408** |

The layout is not available as a lever and an earlier version of this document implied that it was. The
cameras are where the board is, in the same corner; what makes two legs long is the width of the machine in
X between that corner (X 105-122) and the feeder that happens to be next in the queue (X 1.3 and 25.2 in this
cycle). Nothing to win there, and the 408 mm is what the route is.

which is the 402 mm of XY measured over the cycles. The order is forced by the process - both places must
happen before the head is empty, the tape must advance before a pick, and both picks must be photographed
before they can be placed - so reordering has nothing to give back. The two long legs are the geometry of
the machine: the cameras are in the corner diagonally opposite the feeder. XY distance is therefore fixed,
and what is left on the motion side is acceleration, and the 13 stops.

## The CAM-coupled Z is where the motion time goes

Both nozzles ride one physical Z through a cam: `ZN` is the controller axis, `Z1` is a
`ReferenceCamCounterClockwiseAxis` driven by `ZN`, `Z2` is a `ReferenceCamClockwiseAxis` driven by `Z1`, and
N1 uses `Z1` while N2 uses `Z2`. The consequence of that geometry, as the machine builder describes it: the
cam can only *push a nozzle down*, and the nozzle returns up on a spring. So the down stroke is closed loop
and the up stroke is not - the controller knows where the carriage is, never where the nozzle is - and the
carriage must not retreat faster than the spring can follow, because otherwise the nozzle meets its upper
stop hard enough to shake or drop whatever is on the tip.

That reframes the Z numbers. The axis settings can be trusted, so the effective 27700 mm/s² measured on the
20 mm strokes is the cam ratio at work, not a mis-set controller: in nozzle coordinates the achievable
acceleration is the carriage acceleration divided by the local slope of the cam, and it changes along the
stroke. Raising limits is therefore not the lever, and the moderation of the spring direction is a
requirement, not an oversight.

What the cam does cost is visible when the Z moves are split by size, because the two nozzles sit at
*opposite ends* of the carriage stroke: N1's coordinates are negative, N2's positive, and a pick of N1
commands Z -36.9 while a pick of N2 commands Z +38.8. Every change of the working nozzle therefore drags the
carriage across the whole cam:

| | per cycle |
|---|---|
| cam traverses (a Z step of ≥ 25 mm) | 3.0 |
| distance of one traverse | 35.2-36.9 mm |
| Z travel in traverses | 166 mm of the 299 mm total |
| time of the segments that carry them | 599 ms of the 1672 ms accounted |

and the traverses are the slowest motion on the machine, per millimetre:

| | distance | time | average |
|---|---|---|---|
| hop between the two place points, 24 mm XY + 35 mm cam traverse | 42 mm | 188 ms | 226 mm/s |
| the leg to the cameras, 126 mm XY + 18 mm Z | 127 mm | 222 ms | 573 mm/s |

The difference is not the distance, it is what the block is made of: OpenPnP's
`ModeratedConstantAcceleration` computes one feed rate for the whole 3D block from the tightest constrained
axis, so a block dominated by a cam stroke inherits the cam's slowness, and a block dominated by XY does
not. A shallow Z under a long XY does hide - the 126 mm leg to the cameras carries 18 mm of Z and still runs
at pure-XY speed - but a full sweep never does. Measured over the whole log fleet (4634 blocks timed from the
first write to the arrival `ok`, so the host's thinking time is excluded), median block time:

| XY in the block | with a cam sweep | without a sweep |
|---|---|---|
| 0-12 mm | 180 ms | - |
| 12-30 mm | 185 ms | 133 ms |
| 30-50 mm | 225 ms | - |
| 50-90 mm | 283 ms | 173 ms |
| 90-150 mm | 267 ms | - |

The sweep is absorbed *partly* (180 ms alone plus 173 ms for the XY of the 50-90 mm bin would be 353 ms in
series, and the block takes 283 ms) but it never disappears: on the longest legs in the cycle it still costs
70-110 ms. That is the honest statement of what one feed rate per block means - the two axes share the block
time, they do not run free of each other.

That kills the seductive version of this analysis - reorder the nozzles so the sweeps ride the long legs -
which is tested and rejected in the next section but one. What survives is the geometry itself: three sweeps
of 35 mm a cycle, 166 mm of the Z travel, all of it necessary, none of it reducible by planning. What can be
changed is only *when* it happens - and there is nowhere idle to move it to, because the machine never stops.
What is left is either mechanical (a shorter sweep, a stiffer spring, a cam arms angle that does not require
one) or a block that carries less XY while it sweeps.

Two things follow from the same geometry:

- The lift to safe Z is unconditional in `MovableUtils.moveToLocationAtSafeZ()`
  (`MovableUtils.java:32-34`: any change in X, Y or C lifts to safe Z first), with no distance threshold,
  which is why the 24 mm hop between the place points buys a full 20 mm lift on top of its traverse. The
  `unsafe-Z-roaming-distance` that looks like the natural knob is only consulted by the manual jog panel
  (`MachineControlsPanel.java:551`), never by the job path. Shortening a lift is a time question and a
  clearance question; whether it is also a vibration question depends on where along the stroke the nozzle
  leaves the cam, which the log cannot see and nobody has measured.
- Nothing in the current configuration distinguishes the two directions: the lifts and the plunges are all
  commanded `F60000` with the same `M204 S90909`, and `reported-location-confirmation="true"` confirms the
  carriage position, which on the up stroke is not the nozzle position. If the spring direction has to be
  moderated, the moderation has to become a property of the move direction - something OpenPnP's axis model
  does not have today, since a `ReferenceControllerAxis` carries one acceleration and the planner computes
  one feed rate per block.

## There is no dead spot: the machine is saturated

The previous version of this section claimed 268 ms provably idle per cycle, 195-197 ms of it in one gap
after the feed. **That was wrong**, and the reason is worth writing down, because the same trap is waiting
for anyone who reads timing out of this log.

Smoothie acks a movement line when the line has been *planned*, not when it has been *executed*. Only `M400`
acks on completion. So the stream of `ok` after a batch of writes drains in two very different ways: the
moves are acked as soon as there is room in the planner buffer, and the `M400` that follows them is acked
when they have actually finished. The feed batch contains **two** `M400`s - one before the pin goes down and
one after the drag - and the 197 ms gap was measured from the ack of the first one, which is not where the
machine stopped.

Followed through properly, with a real FIFO over the whole log, the same batch looks like this:

```
09.951  write  G1 X25.12Y47.55 F600        sneak-up into the drag position
09.954  write  M400  ·  M816  ·  G4 P10    (acked 10.269 / 10.270 / 10.279)
09.964  write  G1 X29.27 C4 F12724         drag 4.15 mm + peel index 4      ┐ executed
09.966  write  G1 X29.34 F600              retract                          │ while the
09.967  write  G1 X29.18 F1386                                             │ host is
09.969  write  G1 X29.12 F600              (these four acked 10.280)        ┘ "idle"
09.977  write  M400                 →  acked 10.462   the drag really finished
09.978  write  M817 · G92C0 · M119         (acked 10.462 / 10.463 / 10.467)
10.466  write  G1 X3.29Y81.33Z18.4         travel to the pick
```

The `M817` line is acked 444 ms after it is written - the controller is still working through the drag - and
the next motion follows the last ack of the batch by **3 ms**. The head was never standing still; it was the
host waiting for the machine, exactly as it should be. Note what the last ack of the batch is: the `M119` pin
report, executed after the `M400` because the interlock read is queued behind the barrier. The interlock is
therefore on the critical path at the feed and costs about 3 ms there - see its own section. The same 3 ms is
what the queue-model-free measurement finds at every long silence in the log (method section).

Corrected numbers:

| per cycle | |
|---|---|
| cycle | 2036 ms |
| machine executing | ~2030 ms |
| provably idle | **a few ms** |
| feed batch machine time (pin down → drag → pin up, acked) | ~193 ms, of which the drag + peel index block is ~156 ms for 4.15 mm of X and 4 of `LPeel` |

Consequences, all of them removing ideas rather than adding them:

- there is no window to hide a cam sweep in. The sweep stays where it is, inside a travel block.
- the cycle *is* machine motion time. What Java owes is not wire latency but the hand-over at the stops, and
  that is a different measurement - see "Where the machine waits for Java: 55 ms a cycle". The empty-queue
  test above cannot see it: a block parked in the controller's rx buffer does not make the head move past an
  `M400`, and a stop can be host-caused with a queue that never looks empty.
- the only remaining levers are ones that make the machine's own moves shorter or faster: the ~156 ms drag
  block (is that the peel force limit, or a conservative feed rate? the axis is configured 2100 mm/s,
  10000 mm/s² and the block ran at 212 mm/s), the three cam sweeps, the 10 mm/s sneak-ups (nine a cycle),
  and the 13 verified stops that forbid any blending between blocks.
- with X at 25000 mm/s² and Y at 12500 mm/s² configured, a 60 mm move between stops should take ~140 ms and
  does ~173 ms, and a cycle full of stops is what makes the effective acceleration feel far below the
  configured numbers. Dead-stop blending is now the largest remaining item rather than a speculative one.

## Where the machine waits for Java: 55 ms a cycle

Queue depth on the wire is the wrong instrument for this question and it is worth saying why. The driver
writes into the controller's rx buffer, and the controller stops at an `M400` whether or not another block is
waiting behind it; a block sitting in the rx buffer does not make the head move. So "the queue is never empty
at a stop" is compatible with the head standing still for 90 ms. What matters is when the next move was
*handed to the driver*, which the log records separately as `commandQueue offer`, and that is measurable:

- for each verified stop: the instant its `M400` is acked, and, if something has to be read there, the
  completion of that last read (a vacuum `M105` ack, a vision verdict line);
- the offer of the next motion block;
- the difference is the time the head stood still with nothing left to wait for except Java.

Over the six steady cycles of `log_M.txt`, 74 such stop-to-next-move transitions:

| | transitions | pause, median | recoverable, per cycle |
|---|---|---|---|
| nothing pending, pure hand-over | 36 | 3 ms | **39 ms** |
| after a vacuum read | 24 | 8 ms | 10 ms |
| after the last vision frame | 14 | 53 ms | 6 ms |
| all | 74 | | **55 ms** |

The driver itself contributes nothing: from the offer to the byte on the wire the median is 0 ms and the total
over all 1228 commands of the log is 11 ms. Everything in the table above is Java.

Two observations give the shape of the fix:

- **Only 6 of the 74 transitions were handed over before the head stopped.** The early-departure path covers
  the vision step and nothing else, so in 68 cases the machine stops first and Java starts arranging the next
  move afterwards.
- The single largest item is one **~29 ms stall a cycle** between the drag completing and the travel to the
  first pick being offered, with nothing in the log there but `ReferenceNozzle`/`AbstractHeadMountable` and the
  driver. The next ones are the departures from the cameras, where the head is released at
  `[EARLY-DEPARTURE] ... 2 alignments not yet computed` and then Java runs the deferred alignment, the nozzle
  tip optimization and repeated `TravellingSalesman`/`TravelCost` cost estimation - three log triplets of it
  in 30 ms - before the move is offered. That part is already mostly hidden behind the camera dwell (53 ms
  median pause, only 6 ms of it left over), so the prize there is small; the prize is in the plain hand-overs.

55 ms of a 2000 ms cycle is 2.7 %, and it is the honest size of the Java-side prize: real, concentrated in
one place rather than spread thin, and about a tenth of what the motion side holds.

## The order of the picks and the feed: measured, no difference

The question is whether `place, place, feed, pick, pick` is slower than `place, pick, feed, pick`, and
whether the cam rise from the first pick can be hidden in the feed. The history answers it, because both
shapes really occur: the feeders hold a queue of parts, so on cycles where the second tape is already
advanced the feed lands between the picks.

Every cycle in every file in `~/.openpnp2/log` (101 files, 420 clean cycles of 1.1-3.2 s with exactly one
feed), where `a`/`b` is a place with N1/N2 and `A`/`B` a pick:

| shape | n | median cycle | median XY | cam sweeps | sweeps on hops < 60 mm |
|---|---|---|---|---|---|
| `abFAB` | 125 | 1951 ms | 381 mm | 3 | 3 |
| `baFBA` | 63 | 1989 ms | 400 mm | 3 | 3 |
| `abAFB` | 52 | 1820 ms | 308 mm | 3 | 2 |
| `baBFA` | 33 | 1833 ms | 320 mm | 3 | 2 |

The `AFB` shapes look 130 ms faster, but they also carry 70 mm less XY and come from different boards and
sessions. Restricting to cycles with the same path length (280-360 mm) removes the difference:

| shape | n | median cycle | sweeps on short hops |
|---|---|---|---|
| `abFAB` | 54 | 1839 ms | 3 |
| `baFBA` | 31 | 1830 ms | 3 |
| `abAFB` | 63 | 1821 ms | 2 |
| `baBFA` | 39 | 1833 ms | 2 |

A least-squares fit over all 420 cycles gives `cycle ≈ 996 ms + 3.0 ms per mm of XY` and no detectable
coefficient for the short-hop sweeps at all. **Neither ordering is faster.**

The reason is not that a sweep is free when it rides a leg - the block table above shows it costs 70-110 ms
wherever it goes. It is that *every* ordering performs the same three sweeps: the two nozzles are on opposite
cam sides for both the places and the picks, so one sweep is spent between the places, one to reach the first
pick and one between the picks, whichever order is chosen. Reordering moves the cost around, it does not
remove it, and moving it onto a longer leg recovers only the part the block absorbs. `AFB`'s 70 mm of saved
XY and its one sweep moved onto a long leg cancel to within the 20 ms the shapes differ by.

What is the same in both shapes, in the end, is that neither has anywhere to put a sweep that the other does
not have: the machine is busy end to end (see "There is no dead spot").

## What a sweep costs, and the value of splitting it at Z = 0

A cam sweep never appears alone in any log; it is always bundled with travel and usually with a plunge too,
so it cannot be timed directly. It can be priced from the strokes that *are* measurable: a pure Z stroke of
20 mm takes 53 ms in `log_M.txt` and 58-78 ms across the fleet of older logs, which puts the effective
acceleration in commanded coordinates at 13000-28000 mm/s² (the axis is configured with 100000; the cam
ratio and the moderation of the spring direction are the reason, as above). Acceleration-limited strokes go
as the square root of distance, so with 60 ms for 20 mm:

| stroke | time | carriage speed at mid-stroke |
|---|---|---|
| 20 mm lift or plunge | 60 ms | 670 mm/s |
| 37 mm sweep in one move | 82 ms | 900 mm/s |
| 37 mm sweep split into 2 x 18.5 mm via Z = 0 | 114 ms | 650 mm/s, and **zero at Z = 0** |

That last row is the answer to the safety question the machine builder raised: the nozzle is released from
the cam somewhere near the middle of the stroke, and in one continuous sweep the carriage is at its
*peak* speed of ~900 mm/s exactly there. Split at Z = 0, the carriage is standing still at the crossing and
the withdrawal through the release point is slower, so the spring returns the nozzle more gently - and the
nozzle has the whole first half of the move to reach its upper stop before the other one starts descending.
The split costs 32 ms of machine time, which is nothing if it is spent inside a window that is already dead.

Where the sweep actually hurts, and where it is free

The block table above has a shape that is worth reading carefully, because it is the opposite of intuition:
a sweep costs **+52 ms** added to a 12-30 mm block but **+110 ms** added to a 50-90 mm block. A sweep is
therefore *more* expensive on a long leg, not less. This is what one feed rate per block means in practice:
the block runs at a rate every axis can follow, so throttling for the cam throttles the whole path length,
and the penalty grows with the XY that has to be driven at the throttled rate.

The corollary was supposed to be a free win: a sweep is cheapest where there is least XY to throttle, so put
it where the head stands still. **There is no such place** - see "There is no dead spot" - so the sweep stays
inside a travel block and keeps paying 52-110 ms there.

What the split at Z = 0 is still worth is the mechanism, not the time. In a continuous sweep the carriage
passes the level point - where the released nozzle's spring stop is - at its peak speed of ~900 mm/s, which
drives the nozzle up at 0.306 x that, ~280 mm/s, into its stop. Split at Z = 0 the carriage is commanded to a
stop at exactly that point and the nozzle lifts the last 5.5 mm from rest. That costs 43 ms of machine time
per sweep (2 x 58 ms plus the stop, against 82 ms continuous), which is a real cost now that nowhere is idle,
so it should be an option, tried on the machine, and decided by what it can be heard doing.

The geometry is reassuring on the safety side too. In the nozzle frame Z = 0 is the spring stop (both nozzles
up, cam level), and each nozzle's configured safe Z is 17.5.sin(18.5deg) = **5.5 mm below that stop**. So in a
sweep the descending nozzle travels only those 5.5 mm, and only in the second half; the follower on the other
side has long since left its arm behind. This is a fraction of the descent the same nozzle performs at the
pick or the place, and the place-to-place sweep (which does cross the board at full speed) is left exactly as
it is today.

What it gives, and it is not an ordering win

Each cycle performs three sweeps - one between the two place points, one to reach the first pick, one between
the picks - because the nozzles are on opposite cam sides at both the places and the picks. Whichever order is
chosen, exactly one of them is adjacent to the feed:

So the ordering buys nothing, either before or after any change to how the sweep is issued: both shapes
perform the same three sweeps, the machine is busy throughout so there is no window either shape can claim,
and the residual path difference between them is a few mm. What was wrong in the earlier version of this
section was the assumption that in `AFB` a sweep "rides the long leg for free"; the block table above says a
long leg is the *worst* place for one.

The dependency the machine builder named is satisfied either way: the vacuum is established before the nozzle
is raised at all, and the hidden sweep runs after the first pick's lift, so it never races the vacuum check.

Machine-side estimates throughout: the sweep times are scaled from the measured 20 mm strokes (sqrt law),
and where the follower leaves the arm is a property of the cam curve, not of the log.

## The small consumers, ranked

There is no forgotten giant in this part of the code; there are many small ones. In order of the prize,
with the knob and the measurement that would confirm each one.

1. **Thirteen dead stops a cycle, and the acceleration they throw away.** Now the largest item, because with
   the host writing the next move a few ms after the controller reports the last one finished, the whole cycle
   is machine motion.
   X is configured 2500 mm/s at 25000 mm/s² and Y 1600 mm/s at 12500 mm/s²; a 60 mm move that starts and ends
   at a standstill then costs the measured ~173 ms where ~140 ms is the ideal, and every block pays that from
   zero. If half the barriers could become blended junctions at 15-25 ms of deceleration and re-acceleration
   each, that is **100-150 ms a cycle**, and the ideal-travel comparison suggests the ceiling is higher.
   Stillstand has to stay where a sensor is read, which is seven of the thirteen; the six candidates are
   listed in "The thirteen stops, one by one".
   Blending a junction that carries a cam traverse in the spring direction is a separate question and should
   be excluded until the moderation is settled.
2. **The unconditional lift to safe Z: four pure Z moves of 20.2 mm, 53-54 ms each.** `MovableUtils` lifts
   whenever X, Y or C change, with no distance threshold, so the 24 mm hop between the place points pays a
   full 20 mm lift on top of its traverse. A 5 mm lift would take 27 ms instead of 54 ms, so about **110 ms
   a cycle**. Confirm by logging what safe Z the planner computes for each lift and what it is clearing; the
   safe Z zone already has a "dive down as soon as possible" optimisation (`MovableUtils.java:40-47`), so
   the policy is there and the question is its height.
3. **The drag block: ~156 ms of machine time for 4.15 mm of X and 4 of `LPeel`.** The feed batch is 193 ms
   end to end and this one block is most of it; the block ran at 212 mm/s although `LPeel` is configured
   2100 mm/s at 10000 mm/s². If that feed rate is a conservative choice rather than a tape/peel force limit,
   this is **~100 ms a cycle**. This is a question for the machine, not for the log.
4. **Backlash sneak-ups, nine blocks a cycle at F600.** X and Y are `DirectionalSneakUp` with
   `backlash-speed-factor="0.25"`, and the log shows one after nearly every positioning move: 0.06-0.14 mm at
   10 mm/s, each its own planner block with its own `M204 S119` jerk change. The blocks are 6-15 ms each, so
   **60-130 ms a cycle** - but this one is contested: they are only correct when the approach direction
   actually reverses, and it is not yet established that they are not all needed. Confirm by instrumenting
   how many of the nine follow a real reversal before spending anything on it.
5. **Serial chatter, 80 round trips a cycle** (67 `M105` + 7 `M119` + 6 `M114`) at ~1 ms each on the wire.
   Today it hides behind the queue, so it costs nothing; it becomes expensive the moment the queue is thin,
   which is exactly what item 1 requires. They also misled this analysis twice: the `M114` written after every
   `M400` made the queue look busy, and the planning-time acks of the moves before `M400` made the machine
   look idle. Two of them still have easy knobs: `reported-location-confirmation` drives the six `M114`s -
   and on a CAM Z that confirmation reports the carriage, not the nozzle - and
   `establishPickVacuumLevel()`'s burst of thirty reads in 20 ms is a poll-until-stable loop that could
   sample on a timer or read a controller-side threshold instead.
6. **`G4 P10` inside the feed**: 10 ms a cycle, and it is a dwell between the pin going down and the drag
   starting. The pin is measured to move in under 20 ms, so a smaller dwell is probably safe.

## The dragpin interlock, and what the firmware fix is actually worth

The interlock is `ActuatorInterlockMonitor` on the DRAGPIN actuator with
`interlock-type="ConfirmMatchBeforeAxesMove"` and `conditional-actuator-state="SwitchedOff"`, which means
the pin state is confirmed before essentially every XY move rather than once when the pin is released. Each
confirmation is an `M119`, seven of them a cycle, and each one blocks the job thread until the controller
answers - which under `M400` semantics means until the machine reaches the point where the read is executed.
That is the 826 ms a cycle of `plan interlock`, and it is why the phase looked like the biggest consumer in
the machine.

What removing it is worth in time is bounded by the idle measurement: a few ms a cycle, since only a read
issued onto an empty queue is additive. The annotated feed batch shows exactly how it behaves: the `M119` is
written with the rest of the batch, is executed last (its report is the final ack, at 477 ms of a 480 ms
silence), and the next motion follows it 3 ms later. It is on the critical path and almost free there. It is still worth doing, for
reasons that are not milliseconds:

- seven samples a cycle becomes continuous supervision, and a pin that is down where it should be up
  becomes a fault at the drag spot instead of a crash discovered mid-flight;
- it removes seven blocking round trips and their wire traffic from a cycle that is about to be asked to
  run with a thin queue (item 1), where such blocking reads are precisely what would starve the controller;
- the failsafe release on host loss is something the firmware has to do anyway before any queue-ahead
  sequencing is safe, because queue-ahead means the host is no longer looking at the machine when the
  next block starts.

The shape agreed for it: sequence-based, no regions and no position tolerances. `M816` authorises the
pin-down from that point in the queue order; motion blocks are executed only while the pin input is up, or
when the block belongs to an authorised drag sequence; the pin is supervised continuously, not at
command-execution points; the pin state rides in every `ok` and is also pushed unsolicited on change, with
a capability handshake so the host knows whether the firmware is watching; and a reset or host timeout
releases the pin. The host must treat a missing or stale telemetry field as unknown, and unknown must never
count as cleared.

## Experiments, in the order that would tell us the most

1. **Ask why the drag block takes 156 ms** for 4.15 mm of X and 4 of `LPeel` at 212 mm/s, when `LPeel` is
   configured 2100 mm/s. If the feed rate is conservatism rather than peel force, this is ~100 ms a cycle and
   it is the cheapest thing on the list to change.
2. **Log the safe Z the planner chooses** for each of the four lifts and ask what it is clearing. This prices
   item 2.
3. **Instrument the sneak-ups**: how many of the nine follow a real direction reversal. One number, and it
   prices item 4.
4. **Prototype queue-ahead on one boundary** - the lift and travel after a place, where the next target is
   known and no sensor is needed - and measure whether the junction blends. That decides whether item 3, and
   with it any blending at all, is real. Keep the cam's spring direction out of the blended set until the
   moderation question is settled.
5. Read back the controller's own limits (`M201?`, `M204?`) once - not because the axis settings are doubted,
   but because a queue-ahead scheme needs to know how deep the controller's planner buffer really is.

## How these numbers were produced

The three scripts used are throwaway analysis of the log, kept here as a description rather than as code in
the tree:

- **FIFO pairing, and a trap in the log format that is probably the origin of the miscounting retracted
  above.** Each command appears as *two* `>>` lines: the raw one with its spacing and comments, and a
  sanitized one (`>> M400 ; Wait for moves to complete before returning`, then `>> M400`). 881 of the 3086
  `>>` lines of `log_M.txt` are the second kind, so a naive FIFO over `>>` against `<<` sees 2.4 writes per
  ack and drifts. Dedupe a spaced line against the unspaced line that follows it within 2 ms; do not dedupe
  "adjacent identical", which wrongly merges the consecutive `M105` polls. With that done the queue does stay
  in step, and `M400` pops give verified stops.
- **Segments.** Between two consecutive verified stops lie the commands written in between; the first motion
  block of that group is where the segment starts, so the segment time is the machine's time for that group.
  Segments are classified by which axes move inside them.
- **Ordering shapes.** A contact is a `G1` that commands `|Z| >= 30`; it is a place or a pick by its Y
  (board or feeder lane) and N1 or N2 by its sign. A feed is the `M816` write. A cycle is two places and two
  picks with exactly one feed between the first place of one cycle and the first of the next, kept only if it
  lasts 1.1-3.2 s; that gave 420 cycles over the 101 files of `~/.openpnp2/log`. The comparison of shapes is
  then restricted to a common XY band, because the two shapes do not occur on the same boards.
- **Idle, measured without any queue model.** Find the host silences - gaps of > 20 ms between consecutive
  writes - and measure from the **last ack inside the silence** to the next write. 127 silences in the
  annotated log, 123 of them with work outstanding when they began, ending a median **0 ms** and p90 36 ms
  after an ack; the 445-480 ms feed silences end in 0-3 ms. The p90 is the vision phase, not a stillstand.
  This needs no pairing and no ack semantics beyond "an ack means that line is done being dealt with", which
  is why the saturation conclusion rests on it rather than on the segment arithmetic. It also shows the
  *content* of a silence: in the feed batch the acks trickle at 283-294 ms (the drag planned and run) and
  again at 450-477 ms (`M400`, then `M817`, `G92`, and the `M119` pin report), and the next motion is written
  3 ms after that report.
- **Coordinates.** Distances are differences of the coordinates in the written `G1`s. These are the same
  coordinates the controller reports back in `M114` (checked: reported and commanded agree to the last
  digit, including Z at ±38.8), so the machine really is commanded through 402 mm of XY and 299 mm of Z a
  cycle. Note `log_M.txt` contains more than one job and one of them works at X ≈ 414 mm; the per-cycle
  figures above use only the four windows of 1.99-2.17 s, and the whole-log figures (the FIFO idle, the
  135 `M400` acks) use all of it.

One more, about the Z figures: the nozzle axes `Z1` and `Z2` are CAM-derived, so every `Z` word in the log is a carriage coordinate, i.e. the axis the controller actually moves. That is the right quantity for timing, but it is not the nozzle: on the spring direction the nozzle leaves the cam, and neither the `M114` report nor `reported-location-confirmation` can see where it has got to.

Caveats worth repeating: `CycleProfiler`'s `machine wait` (983 ms a cycle) is only the time inside
`GcodeAsyncDriver.waitForCompletion()` and is *not* the machine's busy time; `plan interlock` (826 ms) is
thread blocking time and is not on the critical path; and a wire silence is only idle when the controller
has been shown to have no block to execute, which is what the pairing, and not the silence, establishes.
