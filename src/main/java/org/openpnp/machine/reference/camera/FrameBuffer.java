/*
 * Copyright (C) 2026 OpenPnP contributors
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.camera;

import java.awt.image.BufferedImage;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import org.openpnp.util.CycleProfiler;
import org.openpnp.util.NanosecondTime;
import org.pmw.tinylog.Logger;

/**
 * A short ring of frames that were grabbed ahead of the moment they are asked for.
 *
 * A capture on demand costs the exposure interval plus whatever the driver needs to notice that a
 * frame is there: on the machines measured in docs/design/parallel-bottom-vision/CAPTURE-COST.md the
 * first frame of a settling costs about 210 ms per job, because the asking and the exposing start at
 * the same instant. This class moves the asking to a thread of its own, so that the frames are
 * already in hand, with their timestamps, when the settling or the second camera's hunt arrives.
 *
 * What it holds is raw: frames exactly as {@link Source#grab()} handed them over, before the crop, the
 * white balance, the lens correction and the flip. What it hands out depends on who is asking, and the
 * two are deliberately different methods rather than one with a flag, because getting this wrong is not
 * a crash but a part that is silently not where the pipeline expects it:
 *
 *  - the {@code ...Raw...} methods give the frame as the sensor made it. They exist for
 *    {@code ReferenceCamera.captureRaw()}, which is answered out of the ring and has the whole transform
 *    chain applied above it, exactly as an on-demand capture would be.
 *  - {@link #awaitEarliestAtOrAfter(long, long)} and {@link #earliestAtOrAfter(long)} give the frame the
 *    camera would have made of it, through the {@code handOutTransform} given at construction. Whoever
 *    picks a frame out of the past instead of waiting for one gets the same picture {@code capture()}
 *    would have handed them; asking them to remember to transform it is asking for a bug.
 *
 * Two things make the ring safe to put in front of a camera that is also being previewed:
 *
 * One, it is only fed for as long as a session lasts. A session is begun by the code that knows it is
 * about to need frames - the move to the alignment position, the settling itself - and it expires by
 * itself after the requested time, so a caller that forgets to close it cannot leave the camera being
 * drained at its full frame rate for the rest of the afternoon. The memory the frames cost is only
 * paid inside a session.
 *
 * Two, while a session is active, this is the only thing that talks to the stream. A caller that
 * wants a frame asks here and waits here; nobody reaches past it. Two consumers pulling from one
 * capture stream is how frames get lost without anyone noticing.
 *
 * The timestamps are taken by the grabbing thread at the instant {@link Source#grab()} returns, which
 * is the best there is: the native capture returns a picture and no exposure time of its own. A frame
 * taken this way is therefore no older than one frame interval, whereas the frame that capture on
 * demand returns is the exposure plus the wait, and the wait is the part nobody can predict.
 */
public class FrameBuffer {
    /**
     * Where the frames come from. In the machine this is a {@link CaptureStream}; in a test it is
     * anything that can produce a picture and say whether a new one has arrived.
     */
    public interface Source {
        boolean hasNewFrame();

        /**
         * @return the newest frame, or null if there is none. May block; the buffer does not hold its
         *         monitor while calling this.
         */
        BufferedImage grab() throws Exception;

        /**
         * The earliest instant the content of the frame that {@link #grab()} last returned can be from,
         * on the same clock the buffer stamps with, or 0 to say "I cannot tell, work it out yourself".
         *
         * Not the instant the driver gave the picture up: pixels are gathered over the exposure, and
         * between two frames of a stream the exposure sits somewhere inside the frame interval, so the
         * return time is one interval too optimistic about what the picture shows. The capture API in the
         * machine cannot tell, and then the buffer subtracts the interval it has measured. A source that
         * does know - a test that manufactures frames, or a driver that ever reports the start of its
         * exposure - should say so here, and the timestamps then mean exactly what they say.
         */
        default long getCapturedAtMs() {
            return 0;
        }
    }

    /**
     * One frame, together with the earliest instant its content can be from.
     *
     * That is not the instant the grabbing thread had it in hand, which is one frame interval later and
     * would let a frame taken while the head was still moving answer a request for one taken after it
     * stopped. Consumers ask "is this from at or after such and such an instant", and the number here is
     * what makes that a question about the picture rather than about the paperwork.
     *
     * The image is shared, not copied: it is the object the ring holds, and it stays there until the ring
     * fills round to it again. Two consumers may be handed the same one, and a frame that is being looked
     * at may be handed to a third later. A consumer must therefore treat it as read-only, which the
     * transform chain does (it converts to a {@code Mat} and back and produces a new image) but which is
     * worth knowing before anything draws into a captured frame. Note that a camera with nothing to
     * transform - no rotation, crop, white balance or distortion correction - hands the very same object
     * straight back out of {@code capture()}.
     */
    public static class Frame implements org.openpnp.spi.BufferedFrames.Frame {
        public final BufferedImage image;
        public final long capturedAtMs;

        public Frame(BufferedImage image, long capturedAtMs) {
            this.image = image;
            this.capturedAtMs = capturedAtMs;
        }

        @Override
        public BufferedImage getImage() {
            return image;
        }

        @Override
        public long getCapturedAtMs() {
            return capturedAtMs;
        }

        public long ageMs(long nowMs) {
            return nowMs - capturedAtMs;
        }
    }

    /**
     * How long the grabbing thread waits between looks at the source when the source says there is
     * nothing new. Half a millisecond is far finer than any frame rate a camera runs at, and far
     * coarser than a spin, which would burn a core for nothing.
     */
    private static final long POLL_PARK_NANOS = 500_000;

    /**
     * How many frame intervals are kept to estimate one, for the bound put on a wait.
     */
    private static final int INTERVAL_HISTORY = 9;

    /**
     * How many failures in a row are put up with before the session is abandoned. A stream that cannot
     * produce a frame will not start again by being asked five thousand times a second; the consumers
     * fall back to capturing on demand as soon as they see a failure.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final Object monitor = new Object();

    /**
     * The ring. Slot i is the i-th oldest of the {@link #size} frames held, counting from
     * {@link #head}. Frames are never copied, only their references move, so handing a frame to a
     * consumer costs nothing and the camera's own allocations are the only ones made here.
     */
    private final Frame[] slots;

    private int head;

    private int size;

    /**
     * Bumped whenever the ring is emptied. A consumer that asks for a frame relative to a settling it
     * started before the ring was emptied must not be handed a frame from before it - the head has
     * moved since, and the stillness those frames showed was about a different position. See
     * {@link AbstractSettlingCamera}, which does exactly this with its own epoch.
     */
    private long epoch;

    private long grabbedFrames;

    private long droppedFrames;

    private long bytesGrabbed;

    /**
     * Age, at the instant a consumer got it, of the freshest and the stalest frame ever handed out, and
     * of the last one. Negative until the first frame is handed out. This is the number the whole design
     * is judged by: a frame that was already in the ring when it was asked for has an age of less than a
     * frame interval, which is what the grabbing thread is for.
     */
    private long bestFrameAgeMs = -1;

    private long worstFrameAgeMs = -1;

    private long lastFrameAgeMs = -1;

    private final long[] intervalSamples = new long[INTERVAL_HISTORY];

    private int intervalHead;

    private int intervalCount;

    private long lastStamp;

    private long lastIntervalMs;

    private Source source;

    private Thread thread;

    private volatile boolean sessionActive;

    private volatile long sessionUntilMs;

    private volatile String sessionReason;

    /**
     * Set when the grabbing thread fails, so that the camera can fall back to capturing on demand
     * instead of waiting for frames that will never come. Cleared by the next successful grab.
     */
    private volatile String lastFailure;

    private int consecutiveFailures;

    /**
     * What the session in hand has cost, so that the line written when it ends describes the session and
     * not the camera's whole life. Only meaningful while a session runs, or immediately after one.
     */
    private long sessionStartedAtMs;

    private long sessionGrabbedFrames;

    private long sessionDroppedFrames;

    private final String name;

    /**
     * The camera's transform chain, applied to every frame handed to somebody who did not wait for it.
     * Null for a buffer whose consumer wants the frames as the sensor made them, which is a camera's own
     * {@code captureRaw()} and everything a test wants to look at.
     */
    private final Function<BufferedImage, BufferedImage> handOutTransform;

    public FrameBuffer(String name) {
        this(name, 8);
    }

    public FrameBuffer(String name, int capacity) {
        this(name, capacity, null);
    }

    /**
     * @param handOutTransform applied to the pixels of every frame handed out by
     *            {@link #awaitEarliestAtOrAfter(long, long)} and {@link #earliestAtOrAfter(long)}; the
     *            machine passes the camera's {@code transformImage}. May be null.
     */
    public FrameBuffer(String name, int capacity, Function<BufferedImage, BufferedImage> handOutTransform) {
        this.name = name;
        if (capacity < 2) {
            throw new IllegalArgumentException("A frame buffer of less than two frames can hold no window.");
        }
        this.slots = new Frame[capacity];
        this.handOutTransform = handOutTransform;
    }

    /**
     * The frame as its recipient should see it: the pixels the camera would have made of what the ring
     * holds, with the instant the ring took them kept as it was, because the transform does not move the
     * moment the head was being looked at.
     */
    private Frame handedOut(Frame frame) {
        if (frame == null || handOutTransform == null) {
            return frame;
        }
        return new Frame(handOutTransform.apply(frame.image), frame.capturedAtMs);
    }

    public int getCapacity() {
        return slots.length;
    }

    /**
     * Begin (or extend) a session: from now until {@code maxMs} later, the grabbing thread keeps the
     * ring full. Calls nest by taking the later of the two deadlines, which is what a settling that
     * starts inside a camera batch should do.
     *
     * @param reason what asked for the session; only ever seen in the log.
     * @param maxMs how long the session may run without being renewed. A forgotten
     *               {@link #endSession(String)} must not cost more than this.
     */
    public void startSession(Source source, String reason, long maxMs) {
        synchronized (monitor) {
            long until = NanosecondTime.getRuntimeMilliseconds() + maxMs;
            this.source = source;
            sessionReason = reason;
            if (sessionActive && until <= sessionUntilMs) {
                // Already running, and for at least as long as was now asked for.
                return;
            }
            if (!sessionActive) {
                sessionStartedAtMs = NanosecondTime.getRuntimeMilliseconds();
                sessionGrabbedFrames = grabbedFrames;
                sessionDroppedFrames = droppedFrames;
                // The staleness of the frames handed out is a property of the session being measured, so
                // the measuring starts here.
                worstFrameAgeMs = -1;
            }
            sessionUntilMs = until;
            sessionActive = true;
            if (thread == null || !thread.isAlive()) {
                thread = new Thread(this::grabLoop, "camera-grab-" + name);
                thread.setDaemon(true);
                thread.start();
            }
            monitor.notifyAll();
        }
    }

    /**
     * End the session early. The ring keeps what it has - frames already paid for are still the freshest
     * thing there is - but nothing new is grabbed.
     */
    public void endSession(String reason) {
        synchronized (monitor) {
            if (!sessionActive) {
                return;
            }
            sessionActive = false;
            long now = NanosecondTime.getRuntimeMilliseconds();
            // Debug, not trace: this is the line a machine run is read for. It says whether the ring was
            // filled at all, how stale the frames handed out were, and whether the stream was fighting
            // back - which is everything the offline tests cannot tell.
            Logger.debug("Frame buffer {} session {} in {} ms: grabbed {}, dropped {}, interval {} ms, "
                            + "worst frame {} ms old when taken{}", 
                    this.name, reason, now-sessionStartedAtMs, 
                    grabbedFrames-sessionGrabbedFrames, droppedFrames-sessionDroppedFrames,
                    getFrameIntervalMs(), worstFrameAgeMs, 
                    lastFailure == null ? "" : ", last failure: "+lastFailure);
            sessionUntilMs = now;
            monitor.notifyAll();
        }
    }

    public boolean isSessionActive() {
        return sessionActive && NanosecondTime.getRuntimeMilliseconds() < sessionUntilMs;
    }

    /**
     * Stop everything and forget the frames. Used when the camera is closed or reconfigured: a frame of
     * the old format must not be handed out as if it were of the new one.
     */
    public void reset() {
        synchronized (monitor) {
            sessionActive = false;
            sessionUntilMs = 0;
            source = null;
            clear();
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    /**
     * Put a frame in the ring, stamped with the earliest instant its content can be from. The oldest
     * frame is discarded when the ring is full, which is the whole point of a ring in front of a live
     * camera: the frames that matter are always the recent ones.
     */
    public void push(BufferedImage image, long capturedAtMs) {
        push(image, capturedAtMs, capturedAtMs);
    }

    /**
     * As above, for a grabber that knows the two instants apart: {@code returnedAtMs} is when the frame
     * was in hand and is what the rate of the stream is measured from, while {@code capturedAtMs} is what
     * the frame claims its content is from. They must not be conflated in the measurement, because the
     * claimed instant is itself computed from the measured rate for a source that cannot time its own
     * exposures, and a number fed through itself wanders wherever it likes.
     */
     public void push(BufferedImage image, long capturedAtMs, long returnedAtMs) {
         if (image == null) {
             return;
         }
         // Counted outside the monitor on purpose: the cycle account keeps its own lock and this is
         // called while ours is held. Nothing here ever takes the buffer's monitor from the other
         // side, so the two can never meet in the middle.
         CycleProfiler.count("frames grabbed", 1);
         synchronized (monitor) {
             if (size == slots.length) {
                 slots[head] = null;
                 head = (head + 1) % slots.length;
                 size--;
                 droppedFrames++;
                 CycleProfiler.count("frames dropped", 1);
             }
            slots[(head + size) % slots.length] = new Frame(image, capturedAtMs);
            size++;
            recordInterval(returnedAtMs);
            grabbedFrames++;
            bytesGrabbed += estimateBytes(image);
            if (lastFailure != null) {
                lastFailure = null;
                consecutiveFailures = 0;
            }
            monitor.notifyAll();
        }
    }

    /**
     * Forget the frames but keep the statistics.
     */
    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < slots.length; i++) {
                slots[i] = null;
            }
            head = 0;
            size = 0;
            // The next frame is not a continuation of these, however true the interval would be: the
            // head has moved, and a gap measured across it says nothing about the frame rate.
            lastStamp = 0;
            epoch++;
        }
    }

    public long getEpoch() {
        synchronized (monitor) {
            return epoch;
        }
    }

    public int getSize() {
        synchronized (monitor) {
            return size;
        }
    }

    /**
     * The newest frame in the ring, regardless of when it was taken.
     */
    public Frame newest() {
        synchronized (monitor) {
            return size == 0 ? null : slots[(head + size - 1) % slots.length];
        }
    }

    /**
     * The newest frame taken at or after {@code notBeforeMs}, or null if the ring holds none. This is
     * what a capture on demand should be answered with: the caller wanted a frame taken after it asked,
     * and the newest one is the least stale answer to that question.
     */
    public Frame atOrAfter(long notBeforeMs) {
        return select(notBeforeMs, false);
    }

    /**
     * The oldest frame taken at or after {@code notBeforeMs}, i.e. the frame nearest the opening of the
     * window, or null if the ring holds none.
     *
     * This is the one the still-window hunt wants, for the reason the hunt already gives for taking the
     * first qualifying frame rather than the last: the window opened at the instant two of the settling
     * camera's frames were found alike, and the frame nearest that instant has had the least time to
     * drift since. Later frames are inside the window as well, but further along it.
     *
     * Handed out transformed, as everything a consumer gets without having waited for the exposure is.
     */
    public Frame earliestAtOrAfter(long notBeforeMs) {
        return handedOut(select(notBeforeMs, true));
    }

    private Frame select(long notBeforeMs, boolean oldest) {
        synchronized (monitor) {
            return selectNoLock(notBeforeMs, oldest);
        }
    }

    private Frame selectNoLock(long notBeforeMs, boolean oldest) {
        Frame newest = null;
        for (int i = 0; i < size; i++) {
            Frame frame = slots[(head + i) % slots.length];
            if (frame != null && frame.capturedAtMs >= notBeforeMs) {
                if (oldest) {
                    // Scanning from the oldest held frame, so this is the nearest to the bound.
                    return frame;
                }
                newest = frame;
            }
        }
        return newest;
    }

    /**
     * Wait for a frame taken at or after {@code notBeforeMs}, giving up at {@code deadlineMs}. Returns
     * the newest qualifying frame, as the sensor made it: this is the one a camera's raw capture is
     * answered from, and the transform chain runs above that, not here.
     */
    public Frame awaitRawAtOrAfter(long notBeforeMs, long deadlineMs) throws InterruptedException {
        return await(notBeforeMs, deadlineMs, false);
    }

    /**
     * The frame with which a capture on demand is answered: the newest one held whose content is neither
     * older than what the caller demands of it nor older than the pixels a capture on demand would have
     * handed over anyway.
     *
     * The second limit is what a buffer is permitted to be worth. A frame returned at T shows the subject
     * from as much as one frame interval before T, and capture on demand has always delivered exactly
     * that, unknowingly. Demand instead that the content lie after the instant of the ask and no frame in
     * the ring can ever qualify, because a held frame is stamped with the earliest instant its content can
     * come from, which is behind the ask by at least that much: the buffer is full, answers nothing, and
     * every capture waits for a frame the camera has not taken yet. That is the difference between a 9 ms
     * capture and a 23 ms one, measured on pn52, and 1.2 s a job at a fiducial check.
     *
     * What the frame has to show is not the camera's business but the consumer's, so it is passed in:
     * bottom vision says "from the instant the head arrived", which a frame taken during the approach does
     * not show, and a settling comparing two of its own frames says nothing and is answered at once.
     *
     * @param consumerNotBeforeMs the earliest content instant the caller will accept, or anything that is
     *            not a real instant (0, {@code Long.MIN_VALUE}) for no demand of the kind.
     * @param maxAgeMs how much older than this call the content may be. Two frame intervals is the honest
     *            answer for a camera that cannot time its own exposure: one interval because the exposure
     *            lies somewhere inside the frame period that ended when the frame was returned, and one
     *            because the newest frame in hand may itself have arrived a whole interval ago. One
     *            interval is not enough - it asks of the newest frame held that it have been returned at
     *            or after this very instant, which is the same wall the bound this replaced ran into.
     * @param deadlineMs give up at this instant and let the caller capture on demand.
     * @return the frame, or null when none turned up in time.
     */
    public Frame awaitNewestForCapture(long consumerNotBeforeMs, long maxAgeMs, long deadlineMs)
            throws InterruptedException {
        long notBefore = Math.max(Math.max(consumerNotBeforeMs, 0),
                NanosecondTime.getRuntimeMilliseconds() - Math.max(1, maxAgeMs));
        return await(notBefore, deadlineMs, false);
    }

    /**
     * Wait for a frame taken at or after {@code notBeforeMs}, giving up at {@code deadlineMs}. Returns
     * the oldest qualifying frame, i.e. the one nearest the opening of the window.
     *
     * The caller waits no longer than one frame interval in the normal case, because the grabbing thread
     * has been taking frames all along; a wait that runs to the deadline means the ring was not being
     * fed, and the caller is expected to fall back to capturing on demand in that case.
     *
     * The frame is handed over transformed, i.e. as {@code capture()} would have made it. A caller who
     * takes a frame out of the past rather than waiting for one must not have to remember that the crop,
     * the white balance, the lens correction and the flip still belong to it: on pn52 a hunt that forgot
     * was handed Upp1's frames unrotated, and answered a part that was plainly in the picture with
     * "no rectangle in the pipeline result".
     *
     * @return the frame, or null if none turned up in time.
     */
    public Frame awaitEarliestAtOrAfter(long notBeforeMs, long deadlineMs) throws InterruptedException {
        return handedOut(await(notBeforeMs, deadlineMs, true));
    }

    private Frame await(long notBeforeMs, long deadlineMs, boolean oldest) throws InterruptedException {
        long now = NanosecondTime.getRuntimeMilliseconds();
        if (now >= deadlineMs) {
            return select(notBeforeMs, oldest);
        }
        if (!isSessionActive()) {
            // Nothing is being grabbed, so nothing new will arrive no matter how long we look. Answer
            // now, out of what is here, and let the caller capture on demand instead of losing the
            // whole deadline. This is what makes the fallback cheap for a camera that is not buffering.
            return select(notBeforeMs, oldest);
        }
        synchronized (monitor) {
            while (true) {
                Frame frame = selectNoLock(notBeforeMs, oldest);
                if (frame != null) {
                    noteAge(frame, NanosecondTime.getRuntimeMilliseconds());
                    return frame;
                }
                if (lastFailure != null) {
                    // The grabbing thread cannot produce anything until the stream works again, and it
                    // says so. Waiting would only cost the caller its whole deadline for a frame that is
                    // not coming, so it is told now and captures on demand instead.
                    return null;
                }
                long remaining = deadlineMs - NanosecondTime.getRuntimeMilliseconds();
                if (remaining <= 0) {
                    return null;
                }
                monitor.wait(remaining);
            }
        }
    }

    private void noteAge(Frame frame, long nowMs) {
        long age = nowMs - frame.capturedAtMs;
        lastFrameAgeMs = age;
        if (bestFrameAgeMs < 0 || age < bestFrameAgeMs) {
            bestFrameAgeMs = age;
        }
        if (age > worstFrameAgeMs) {
            worstFrameAgeMs = age;
        }
    }

    /**
     * The median of the last few intervals between frames, in milliseconds, or 0 if nothing has been
     * grabbed yet. Used to bound a wait at one frame interval instead of a guessed constant.
     */
    public long getFrameIntervalMs() {
        synchronized (monitor) {
            if (intervalCount == 0) {
                return 0;
            }
            long[] sorted = new long[intervalCount];
            for (int i = 0; i < intervalCount; i++) {
                sorted[i] = intervalSamples[i];
            }
            java.util.Arrays.sort(sorted);
            return sorted[intervalCount / 2];
        }
    }

    /**
     * The interval between the two most recent frames, in milliseconds, or 0 if there is only one.
     */
    public long getLastFrameIntervalMs() {
        synchronized (monitor) {
            return lastIntervalMs;
        }
    }

    private void recordInterval(long capturedAtMs) {
        if (lastStamp != 0) {
            long delta = capturedAtMs - lastStamp;
            if (delta > 0) {
                intervalSamples[intervalHead] = delta;
                intervalHead = (intervalHead + 1) % INTERVAL_HISTORY;
                if (intervalCount < INTERVAL_HISTORY) {
                    intervalCount++;
                }
                lastIntervalMs = delta;
            }
        }
        lastStamp = capturedAtMs;
    }

    private void grabLoop() {
        Logger.trace("Frame buffer {} grabbing, session {}", name, sessionReason);
        long grabbedHere = 0;
        consecutiveFailures = 0;
        try {
            while (true) {
                long now = NanosecondTime.getRuntimeMilliseconds();
                if (now >= sessionUntilMs) {
                    break;
                }
                Source source = this.source;
                if (source == null) {
                    break;
                }
                boolean grabbed = false;
                try {
                    if (source.hasNewFrame()) {
                        BufferedImage image = source.grab();
                        if (image != null) {
                            long grabbedAtMs = NanosecondTime.getRuntimeMilliseconds();
                            long sourceStamp = source.getCapturedAtMs();
                            // The instant the driver handed the frame over is not the instant the pixels
                            // happen: they were gathered during the frame interval that ended there, so a
                            // frame returned at +2 ms after the head came to rest can show the head still
                            // on its way and the light not yet lit. A source that knows when it exposed is
                            // believed; otherwise the frame is stamped with the earliest instant its content
                            // can be from, which is one frame interval before the return. Consumers ask
                            // "is this frame from after that instant", and this is what makes the answer
                            // mean the pixels and not the paperwork.
                            long contentMs = sourceStamp != 0 ? sourceStamp
                                    : grabbedAtMs - getFrameIntervalMs();
                            push(image, Math.min(contentMs, grabbedAtMs), grabbedAtMs);
                            grabbedHere++;
                            grabbed = true;
                        }
                    }
                }
                catch (InterruptedException e) {
                    throw e;
                }
                catch (Exception e) {
                    lastFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    consecutiveFailures++;
                    Logger.trace("Frame buffer {} grab failed ({}): {}", 
                            name, consecutiveFailures, lastFailure);
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        // The stream is not producing and is not going to. Give up on the session rather
                        // than asking a dead stream five thousand times a second: everything consuming
                        // this buffer already falls back to capturing on demand as soon as it sees a
                        // failure, and the next camera open() starts the grabbing afresh.
                        Logger.warn("Frame buffer {} giving up on session {} after {} failures: {}", 
                                name, sessionReason, consecutiveFailures, lastFailure);
                        break;
                    }
                }
                if (!grabbed) {
                    LockSupport.parkNanos(POLL_PARK_NANOS);
                }
            }
        }
        catch (InterruptedException e) {
            // Stopped by a reset; nothing to say.
        }
        finally {
            Logger.trace("Frame buffer {} grabbed {} frames in session {} ({})", 
                    name, grabbedHere, sessionReason, getFrameIntervalMs() + " ms/frame");
            synchronized (monitor) {
                sessionActive = false;
            }
        }
    }

    /**
     * The instant the grabbing thread last failed, or null when it is healthy. A camera that sees this
     * set should stop waiting for buffered frames and capture on demand.
     */
    public String getLastFailure() {
        return lastFailure;
    }

    public long getGrabbedFrames() {
        synchronized (monitor) {
            return grabbedFrames;
        }
    }

    public long getDroppedFrames() {
        synchronized (monitor) {
            return droppedFrames;
        }
    }

    public long getBytesGrabbed() {
        synchronized (monitor) {
            return bytesGrabbed;
        }
    }

    /**
     * The freshest age ever observed between a frame being taken and a consumer having it, in
     * milliseconds. Negative until the first frame is handed out.
     */
    public long getBestFrameAgeMs() {
        synchronized (monitor) {
            return bestFrameAgeMs;
        }
    }

    public long getWorstFrameAgeMs() {
        synchronized (monitor) {
            return worstFrameAgeMs;
        }
    }

    public long getLastFrameAgeMs() {
        synchronized (monitor) {
            return lastFrameAgeMs;
        }
    }

    private static long estimateBytes(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * 4;
    }
}
