package io.github.ralfspoeth.xldr.app;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the server has done so far, counted as it happens.
 * <p>
 * Loads run concurrently on virtual threads, so every count is atomic and every
 * timestamp is written as a whole reference. Nothing here is read on the loading
 * path, only written, and a reader gets a consistent number per field rather
 * than a consistent snapshot across fields - which is what a counter is for.
 */
final class Statistics {

    /** counted for the server as a whole and, separately, for each feed */
    private final Counters total = new Counters();
    private final Map<String, Counters> byFeed = new ConcurrentHashMap<>();

    private static final class Counters {
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong records = new AtomicLong();
        private volatile Instant lastLoad;
        private volatile Instant lastFailure;
    }

    private final AtomicInteger inProgress = new AtomicInteger();

    void loadStarted() {
        inProgress.incrementAndGet();
    }

    void loadFinished() {
        inProgress.decrementAndGet();
    }

    void loaded(String feed, int records) {
        var now = Instant.now();
        for (var c : new Counters[]{total, of(feed)}) {
            c.succeeded.incrementAndGet();
            c.records.addAndGet(records);
            c.lastLoad = now;
        }
    }

    void failed(String feed) {
        var now = Instant.now();
        for (var c : new Counters[]{total, of(feed)}) {
            c.failed.incrementAndGet();
            c.lastFailure = now;
        }
    }

    private Counters of(String feed) {
        return byFeed.computeIfAbsent(feed, _ -> new Counters());
    }

    int loadsInProgress() {
        return inProgress.get();
    }

    long loadsSucceeded() {
        return total.succeeded.get();
    }

    long loadsFailed() {
        return total.failed.get();
    }

    long recordsLoaded() {
        return total.records.get();
    }

    String lastLoad() {
        return text(total.lastLoad);
    }

    String lastFailure() {
        return text(total.lastFailure);
    }

    long loadsSucceeded(String feed) {
        return of(feed).succeeded.get();
    }

    long loadsFailed(String feed) {
        return of(feed).failed.get();
    }

    long recordsLoaded(String feed) {
        return of(feed).records.get();
    }

    String lastLoad(String feed) {
        return text(of(feed).lastLoad);
    }

    String lastFailure(String feed) {
        return text(of(feed).lastFailure);
    }

    /** an absent instant is empty rather than null, which reads better over JMX */
    private static String text(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
