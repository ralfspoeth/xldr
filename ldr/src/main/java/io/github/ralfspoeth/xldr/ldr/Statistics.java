package io.github.ralfspoeth.xldr.ldr;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What has been loaded so far, counted as it happens.
 * <p>
 * Here rather than in a front end because loading is what is being counted, and
 * loading is what this module is. The file server and the servlet both wrap
 * {@link Loader#load}, both want to know how many loads succeeded and how many
 * rows came in, and neither wants the other's answer.
 * <p>
 * What is <em>not</em> here is anything file-shaped. The file server also reports
 * how many inputs are waiting and how many are in a hospital, and those are not
 * counters at all - they are computed from the directories when asked, because
 * the directories are the truth and a count kept beside them could disagree with
 * them. That is why this divides cleanly: everything below is a number that only
 * a load can change.
 * <p>
 * Loads run concurrently, so every count is atomic and every timestamp is written
 * as a whole reference. Nothing here is read on the loading path, only written,
 * and a reader gets a consistent number per field rather than a consistent
 * snapshot across fields - which is what a counter is for.
 */
public final class Statistics {

    /**
     * Counted in total and, separately, per name.
     * <p>
     * The name is whatever the front end calls one stream of loads: the feed in
     * the file server, the spec in the servlet. This module has no opinion about
     * which, and needs none - it is a key.
     */
    private final Counters total = new Counters();
    private final Map<String, Counters> byName = new ConcurrentHashMap<>();

    private static final class Counters {
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong records = new AtomicLong();
        private volatile @Nullable Instant lastLoad;
        private volatile @Nullable Instant lastFailure;
    }

    private final AtomicInteger inProgress = new AtomicInteger();

    public void loadStarted() {
        inProgress.incrementAndGet();
    }

    public void loadFinished() {
        inProgress.decrementAndGet();
    }

    public void loaded(String name, int records) {
        var now = Instant.now();
        for (var c : new Counters[]{total, of(name)}) {
            c.succeeded.incrementAndGet();
            c.records.addAndGet(records);
            c.lastLoad = now;
        }
    }

    public void failed(String name) {
        var now = Instant.now();
        for (var c : new Counters[]{total, of(name)}) {
            c.failed.incrementAndGet();
            c.lastFailure = now;
        }
    }

    private Counters of(String name) {
        return byName.computeIfAbsent(name, _ -> new Counters());
    }

    public int loadsInProgress() {
        return inProgress.get();
    }

    public long loadsSucceeded() {
        return total.succeeded.get();
    }

    public long loadsFailed() {
        return total.failed.get();
    }

    public long recordsLoaded() {
        return total.records.get();
    }

    public String lastLoad() {
        return text(total.lastLoad);
    }

    public String lastFailure() {
        return text(total.lastFailure);
    }

    public long loadsSucceeded(String name) {
        return of(name).succeeded.get();
    }

    public long loadsFailed(String name) {
        return of(name).failed.get();
    }

    public long recordsLoaded(String name) {
        return of(name).records.get();
    }

    public String lastLoad(String name) {
        return text(of(name).lastLoad);
    }

    public String lastFailure(String name) {
        return text(of(name).lastFailure);
    }

    /** an absent instant is empty rather than null, which reads better over JMX */
    private static String text(@Nullable Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
