package org.openpnp.util;

import java.util.Map;
import java.util.TreeMap;

import org.pmw.tinylog.Logger;

/**
 * A wall clock account of what one placement cycle spends its time on.
 *
 * The question this exists to answer is a specific one: when something upstream gets faster, the
 * cycle time often does not move, which means the saving was absorbed by something else and nobody
 * knows what. The cycle is therefore accounted, not sampled: nested spans are recorded on the job
 * thread, a child's time is taken out of its parent so that nothing is counted twice, and the
 * totals are printed once per cycle.
 *
 * Time is not a thing that only the job thread spends, so spans may also be opened on other
 * threads (a vision thread, the driver's writer thread). Those add to the same account and can
 * therefore make the sum exceed the cycle; they are named so it is visible which is which.
 *
 * Turn it off with <code>-Dopenpnp.cycleProfiler=false</code>.
 */
public class CycleProfiler {

    public static class Handle {
        private final String phase;
        private final Handle parent;
        private final long startNanos;
        private long childrenNanos;

        private Handle(String phase, Handle parent) {
            this.phase = phase;
            this.parent = parent;
            this.startNanos = System.nanoTime();
        }
    }

    private static final boolean ENABLED = 
            !"false".equals(System.getProperty("openpnp.cycleProfiler"));

    private static final ThreadLocal<Handle> current = new ThreadLocal<>();

    /** phase name to {nanoseconds, number of spans}. */
    private static final Map<String, long[]> phases = new TreeMap<>();

    /** phase name to count, for things that are counted rather than timed. */
    private static final Map<String, Long> counters = new TreeMap<>();

    private static long cycleStartNanos;

    /**
     * Start accounting a span. Nest freely: the time of a child is subtracted from the parent, so
     * a parent ends up holding only the work that was neither delegated to a child nor to a span
     * opened later on the same thread.
     *
     * @return the handle to give to {@link #end(Handle)}, or null when profiling is off.
     */
    public static Handle begin(String phase) {
        if (!ENABLED) {
            return null;
        }
        Handle parent = current.get();
        Handle handle = new Handle(phase, parent);
        current.set(handle);
        return handle;
    }

    /**
     * Close a span opened by {@link #begin(String)}. Safe with a null handle so that callers do not
     * have to know whether profiling is on.
     *
     * @return the time of the span that is not also the time of one of its children, in ms.
     */
    public static long end(Handle handle) {
        if (handle == null) {
            return 0;
        }
        long total = System.nanoTime()-handle.startNanos;
        current.set(handle.parent);
        if (handle.parent != null) {
            handle.parent.childrenNanos += total;
        }
        long own = total-handle.childrenNanos;
        record(handle.phase, own, 1);
        return own/1000000;
    }

    /**
     * Account a duration that has no nesting to speak of, e.g. one controller round trip.
     */
    public static void add(String phase, long nanos) {
        if (ENABLED) {
            record(phase, nanos, 1);
        }
    }

    /**
     * Count something per cycle instead of timing it, e.g. the frames the grabber threw away.
     */
    public static void count(String phase, long count) {
        if (ENABLED) {
            synchronized (counters) {
                counters.merge(phase, count, Long::sum);
            }
        }
    }

    /**
     * Close the current cycle: print the account and start the next one.
     *
     * @param note what it was that came around again, so that logs of different jobs can be told
     *            apart and a cycle can be recognised by its boundary rather than by its length.
     */
    public static void cycle(String note) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        long elapsedMs = cycleStartNanos == 0 ? 0 : (now-cycleStartNanos)/1000000;
        cycleStartNanos = now;
        StringBuilder sb = new StringBuilder();
        synchronized (phases) {
            for (Map.Entry<String, long[]> entry : phases.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue()[0]/1000000).append("ms");
                if (entry.getValue()[1] > 1) {
                    sb.append(" x").append(entry.getValue()[1]);
                }
            }
            phases.clear();
        }
        synchronized (counters) {
            for (Map.Entry<String, Long> entry : counters.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            counters.clear();
        }
        Logger.debug("[CYCLE] {} ms at {}: {}", elapsedMs, note, sb);
    }

    private static void record(String phase, long nanos, int spans) {
        synchronized (phases) {
            long[] entry = phases.computeIfAbsent(phase, k -> new long[2]);
            entry[0] += nanos;
            entry[1] += spans;
        }
    }
}
