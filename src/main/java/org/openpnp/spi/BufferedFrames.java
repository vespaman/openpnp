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

package org.openpnp.spi;

import java.awt.image.BufferedImage;

/**
 * A camera that is already taking frames, so that the ones a measurement needs can be picked out of
 * what it has taken instead of being waited for.
 *
 * {@link Camera#capture()} asks for a frame and then waits for the exposure, the readout and the
 * driver; the caller pays all of that, every time, and a settling that takes four frames pays it four
 * times over. A camera that keeps grabbing into a buffer turns the same question into "give me the one
 * you took 8 ms ago", which is answered without waiting for the camera at all.
 *
 * This is a capability, not a promise. A camera that does not grab ahead simply does not implement it,
 * and everything falls back to capturing on demand. A camera that does implement it may still stop
 * buffering at any moment (the buffer exists to be cheap when nothing is measuring), so every method
 * here has an answer for "there is no frame": the caller then captures on demand, and is no worse off
 * than it was.
 *
 * The frames handed out are the pictures the camera would have captured: the white balance, the lens
 * correction and the flip have been applied by the camera itself, before the frame is handed over, so
 * that a caller picking one out of the past gets exactly what {@link Camera#capture()} would have given
 * it and cannot forget a step. A camera whose frames arrive untransformed is not implementing this
 * interface, it is implementing a {@code FrameBuffer}.
 */
public interface BufferedFrames {
    /**
     * One frame and the instant the grabbing thread had it.
     *
     * That instant is the best stand-in for the exposure there is: the capture APIs report no exposure
     * time of their own, so a frame is known to be no older than the moment it was read out plus the
     * time it sat here. For the still-window argument this is what matters - the frame has to be shown
     * to be later than the moment the head was declared still, and a timestamp taken too late (when the
     * consumer got around to it) would let a frame from before that moment pass for one after it.
     */
    interface Frame {
        /**
         * The picture, transformed as {@link Camera#capture()} would have transformed it, and read-only:
         * the camera may hand the same one to somebody else.
         */
        BufferedImage getImage();

        long getCapturedAtMs();
    }

    /**
     * @return true while the camera is grabbing ahead into its buffer. False is a complete answer: every
     *         method here is then expected to be cheap and to hand back nothing, so that a caller can ask
     *         before every capture without having to know anything about the camera in front of it.
     */
    boolean isFrameBuffering();

    /**
     * Wait for a frame taken at or after {@code notBeforeMs}, giving up at {@code deadlineMs}, and return
     * the oldest one that qualifies - the frame nearest the opening of the window rather than the newest.
     *
     * The nearest one is what the still-window hunt wants, for the reason that hunt already gives for
     * taking the first qualifying frame rather than the last: the window opened at the instant two of the
     * settling camera's frames were found alike, and a frame close to that instant has had less time to
     * drift than one at the end of the window.
     *
     * While the camera is buffering, this is answered out of the buffer in less than one frame interval.
     * When it is not buffering - the session expired, the camera cannot do this at all, the stream died -
     * it returns null at once rather than waiting, so that a caller that has to fall back to
     * {@link Camera#capture()} loses nothing but the question.
     *
     * @param notBeforeMs on the {@link NanosecondTime} millisecond scale, as everywhere else in the
     *            still-window code.
     * @return the frame, or null.
     */
    Frame awaitEarliestBufferedFrameAtOrAfter(long notBeforeMs, long deadlineMs)
            throws InterruptedException;

    /**
     * The camera's frame interval in milliseconds, estimated from the frames actually taken, or 0 when
     * nothing has been taken yet. A caller that wants to bound a wait by "one frame, and no guessing"
     * uses this.
     */
    long getBufferedFrameIntervalMs();

    /**
     * Ask the camera to keep frames available for a while. Call this where the work is known to be
     * coming - before the move to the alignment position, for instance, so that the frames are already
     * being taken while the head travels and are in hand the moment it stops.
     *
     * Nested calls take the later deadline. The session expires by itself, so a caller that never
     * reaches {@link #endFrameBufferSession(String)} costs the camera some grabbing for a bounded time
     * and nothing more.
     *
     * @param reason what asked, for the log.
     * @param maxMs how long the session may run unrenewed.
     */
    void beginFrameBufferSession(String reason, long maxMs);

    /**
     * Stop grabbing ahead. Frames already taken are kept and can still be handed out; they were paid for
     * and are the freshest thing there is.
     */
    void endFrameBufferSession(String reason);

    /**
     * Tell the camera the earliest instant a captured frame's <em>content</em> may come from, for as long
     * as this caller's work lasts. This is what a buffer is for: a frame that has already been taken is
     * handed over in a millisecond or two instead of being waited for, and what the consumer needs to know
     * is not when the frame arrived but when it looked at the subject.
     *
     * <p>A frame returned at T shows the subject from before T, and a camera that cannot time its own
     * exposure can only say how long before, so a capture asked for the newest frame is answered with
     * pixels that are up to that much old. Ask for a frame of the instant of the ask instead and no
     * buffered frame can ever qualify, the buffer is full and useless, and every capture pays a whole
     * frame interval for the privilege of being asked. On pn52 that turned a 9 ms capture into a 23 ms one
     * and cost a fiducial check a second and a half per job; see
     * docs/design/parallel-bottom-vision/GRAB-AHEAD.md.
     *
     * <p>So the bound belongs to whoever knows what the frame has to show. Bottom vision says "the head
     * had arrived", which a frame taken during the approach does not show; a settling comparing frames,
     * or a fiducial looking for a cross, cares about no such instant and sets nothing.
     *
     * @param notBeforeMs the earliest content instant, in
     *            {@link org.openpnp.util.NanosecondTime} milliseconds, or 0 to set no bound and let the
     *            camera answer with the newest frame it holds.
     */
    void setBufferedFrameNotBeforeMs(long notBeforeMs);
}
